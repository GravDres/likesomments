import ru.hod.chess.core.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Exact rules/context and scripted model selection. Does not measure live models or Elo. */
public final class ApiUpgradeTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static ApiChessFacts.Snapshot facts(Position p)throws Exception{return ApiChessFacts.inspect(p,Long.MAX_VALUE,()->0L);}
    private static ApiChessFacts.Fact fact(ApiChessFacts.Snapshot data,String uci){return data.facts().stream().filter(f->f.move().equals(uci)).findFirst().orElseThrow();}
    public static void main(String[]args)throws Exception{ruleFacts();cache();benchmark();System.out.println("PASS: "+checks+" API upgrade assertions (exact rule context, recovery cache, explicit retry, benchmark filtering/cancellation; no measured Elo)");}
    private static void ruleFacts()throws Exception{
        Position p=Position.fromFen("6k1/8/2b5/8/8/8/8/3Q2K1 w - - 0 1");
        var data=facts(p);check(data.complete(),"all candidate facts enumerated when budget available");
        check(fact(data,"d1a4").opponentCaptures().contains("c6a4(Q)"),"API sees exact legal queen capture after its candidate");
        check(data.pieces().contains("Qa4")==false&&data.pieces().contains("Qd1")&&data.pieces().contains("Bc6"),"piece list remains current position, not an imagined future board");
        Position pinned=Position.fromFen("4k3/4r3/7Q/8/8/8/8/4R1K1 w - - 0 1");
        check(!fact(facts(pinned),"h6h7").opponentCaptures().contains("e7h7(Q)"),"pinned rook is not advertised as a legal queen capturer");
        Position ep=Position.fromFen("7k/8/8/3pP3/8/8/8/7K w - d6 0 10");
        check(fact(facts(ep),"e5d6").capture().equals("P"),"en passant describes captured pawn on empty target square");
        Position epReply=Position.fromFen("7k/8/8/8/3p4/8/4P3/7K w - - 0 1");
        check(fact(facts(epReply),"e2e4").opponentCaptures().contains("d4e3(P)"),"opponent en passant capture is not missed");
        Position promotion=Position.fromFen("7k/P7/8/8/8/8/8/7K w - - 0 1");
        for(char role:"qrbn".toCharArray())check(fact(facts(promotion),"a7a8"+role)!=null,"every promotion receives independent facts");
        Position checked=Position.fromFen("k3r3/8/8/8/8/8/8/4K2R w K - 0 1");
        check(facts(checked).facts().stream().noneMatch(f->f.move().equals("e1g1")||f.move().equals("h1h8")),"check annotations never reintroduce illegal candidates");
        var empty=ApiChessFacts.inspect(Position.start(),0,()->0L);
        check(empty.facts().isEmpty()&&!empty.complete()&&empty.legalCount()==20,"zero budget is explicitly incomplete");
        check(empty.text().contains("unknown, not safe or inferior"),"missing coverage is not advertised as safety");
        boolean cancelled=false;Thread.currentThread().interrupt();try{facts(Position.start());}catch(InterruptedException expected){cancelled=true;}
        check(cancelled&&Thread.currentThread().isInterrupted(),"fact collection keeps interrupt flag");Thread.interrupted();
        Random random=new Random(226);Position current=Position.start();
        for(int turn=0;turn<35;turn++){
            List<Move> legal=current.legalMoves();if(legal.isEmpty())break;
            var snapshot=facts(current);check(snapshot.text().length()<=ApiChessFacts.MAX_TEXT,"context text remains bounded");
            Set<String> advertised=new HashSet<>();
            for(var f:snapshot.facts()){
                Move move=Move.parse(f.move());check(legal.contains(move),"annotated candidate is legal");advertised.add(f.move());
                Position child=current.play(move);List<Move> replies=child.legalMoves();
                check(f.check()==child.inCheck(child.white),"check fact matches child position");
                check(f.mate()==(child.inCheck(child.white)&&replies.isEmpty()),"mate fact matches terminal state");
                for(String capture:f.opponentCaptures()){
                    String uci=capture.substring(0,capture.indexOf('('));check(replies.contains(Move.parse(uci)),"every capture advertised is a legal reply");
                }
            }
            if(snapshot.complete())check(advertised.equals(new HashSet<>(legal.stream().map(Move::uci).toList())),"complete coverage includes all moves without selection");
            current=current.play(legal.get(random.nextInt(legal.size())));
        }
    }
    private static void cache()throws Exception{
        AtomicInteger calls=new AtomicInteger();AtomicReference<ApiEngine.Request> request=new AtomicReference<>();
        ApiEngine.Transport transport=new ApiEngine.Transport(){public String choose(ApiEngine.Request r,int timeout){calls.incrementAndGet();request.set(r);return "e2e4";}public void close(){}};
        try(ApiEngine engine=new ApiEngine(ApiModel.SONNET,transport)){
            AtomicReference<Analysis> result=new AtomicReference<>();engine.analyze(Position.start(),1,result::set);engine.analyze(Position.start(),1,result::set);
            check(calls.get()==1&&result.get().engine().contains("сохранённый"),"same board after automatic recovery does not spend a second request");
            check(request.get().facts().contains("Rule annotations")&&request.get().prompt().contains("Pieces:"),"actual API request includes local facts");
            engine.clearCache();engine.analyze(Position.start(),1,result::set);check(calls.get()==2,"explicit repeat invalidates cached decision");
            engine.newGame();engine.analyze(Position.start(),1,result::set);check(calls.get()==3,"new game permits independent decision");
            List<Move> cycle=List.of(Move.parse("g1f3"),Move.parse("g8f6"),Move.parse("f3g1"),Move.parse("f6g8"));Position repeated=Position.start();for(Move move:cycle)repeated=repeated.play(move);
            engine.analyzeGame(Position.start(),repeated,cycle,1,result::set);int before=calls.get();engine.analyzeGame(repeated,repeated,List.of(),1,result::set);
            check(calls.get()==before+1,"same current FEN with different known history never shares cache");
        }
        AtomicInteger requests=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Throwable> problem=new AtomicReference<>();
        try(ApiEngine engine=new ApiEngine(ApiModel.SOL,new ApiEngine.Transport(){
            public String choose(ApiEngine.Request r,int timeout)throws Exception{if(requests.incrementAndGet()==1){entered.countDown();release.await();}return "e2e4";}public void close(){release.countDown();}
        })){
            Thread worker=new Thread(()->{try{engine.analyze(Position.start(),1,a->{});}catch(Throwable fail){problem.set(fail);}});
            worker.start();check(entered.await(2,TimeUnit.SECONDS),"old request reached remote fixture");engine.clearCache();release.countDown();worker.join(2000);
            check(!worker.isAlive()&&problem.get()==null,"old request completed after cache invalidation");engine.analyze(Position.start(),1,a->{});
            check(requests.get()==2,"old in-flight response cannot refill a cache cleared for explicit repeat");
        }
    }
    private static void benchmark()throws Exception{
        Map<String,String> solutions=new HashMap<>();
        for(var puzzle:ApiModelBenchmark.PUZZLES){
            Position p=puzzle.position();Move solution=Move.parse(puzzle.expected());check(p.legalMoves().contains(solution),"benchmark solution is legal");solutions.put(p.fen(),solution.uci());
            if(puzzle.label().startsWith("Мат")){Position after=p.play(solution);check(after.inCheck(after.white)&&after.legalMoves().isEmpty(),"benchmark mate is an actual checkmate");}
            if(puzzle.label().startsWith("Вилка")){
                Position after=p.play(solution);check(after.inCheck(after.white),"benchmark fork gives check");
                for(Move reply:after.legalMoves()){Position next=after.play(reply);check(next.at(Position.index("h7"))=='q'&&next.legalMoves().contains(Move.parse("f6h7")),"every legal check evasion leaves the queen capturable by the knight");}
            }
        }
        check(ApiModelBenchmark.MAX_REQUESTS==16,"bounded paid-request count is explicit");
        AtomicLong clock=new AtomicLong();AtomicInteger calls=new AtomicInteger();List<ApiModelBenchmark.Progress> progress=new ArrayList<>();
        ApiModelBenchmark.Factory factory=model->new ApiEngine(model,new ApiEngine.Transport(){
            public String choose(ApiEngine.Request request,int timeout){calls.incrementAndGet();long latency=switch(model){case LUNA->1;case SONNET->200;case SOL->300;case OPUS->800;};clock.addAndGet(latency*1_000_000);
                if(model==ApiModel.LUNA)return request.legalMoves().stream().filter(m->!m.equals(solutions.get(request.fen()))).findFirst().orElseThrow();return solutions.get(request.fen());}
            public void close(){}
        });
        try(ApiModelBenchmark benchmark=new ApiModelBenchmark(factory,clock::get)){
            var report=benchmark.run(progress::add);check(report.winner()==ApiModel.SONNET,"fastest model among perfect results chosen; faster failing model excluded");
            check(calls.get()==13&&progress.size()==13,"failed model stops early instead of spending all four requests");
            var fastFailure=report.results().stream().filter(r->r.model()==ApiModel.LUNA).findFirst().orElseThrow();
            check(!fastFailure.qualified()&&fastFailure.attempts()==1,"legal but wrong move fails quality gate");
            check(report.results().stream().filter(ApiModelBenchmark.Result::qualified).count()==3,"three scripted models solve all puzzles");
            check(report.summary().contains("не измерение 2000 Elo"),"report never converts puzzle score to Elo");
        }
        var median=new ApiModelBenchmark.Result(ApiModel.SOL,4,4,List.of(100L,300L,20L,40L),null);check(median.medianMillis()==70,"median includes central pair for even count");
        check(!new ApiModelBenchmark.Result(ApiModel.SOL,4,4,List.of(4000L,4000L,4000L,4000L),null).qualified(),"correct but slow median is not qualified for fast preset");
        check(!new ApiModelBenchmark.Result(ApiModel.SOL,4,4,List.of(100L,100L,100L,6001L),null).qualified(),"fast median with a very slow outlier is not qualified");
        AtomicLong slowClock=new AtomicLong();AtomicInteger slowCalls=new AtomicInteger();
        try(ApiModelBenchmark slow=new ApiModelBenchmark(model->new ApiEngine(model,new ApiEngine.Transport(){
            public String choose(ApiEngine.Request request,int timeout){slowCalls.incrementAndGet();slowClock.addAndGet(9_000_000_000L);return solutions.get(request.fen());}public void close(){}
        }),slowClock::get)){
            var report=slow.run(x->{});check(report.winner()==null&&slowCalls.get()==4,"all slow models yield no winner and stop before spending more requests");
        }
        AtomicInteger factories=new AtomicInteger();try(ApiModelBenchmark failed=new ApiModelBenchmark(model->{factories.incrementAndGet();throw new IOException("fixture-sensitive-provider-message");})){
            var report=failed.run(x->{});check(report.winner()==null&&factories.get()==4,"all unavailable models yield no winner, no retries");
            check(!report.summary().contains("fixture-sensitive"),"raw provider exception is not included in report");
        }
        ApiModelBenchmark closed=new ApiModelBenchmark(factory);closed.close();boolean stopped=false;try{closed.run(x->{});}catch(InterruptedException expected){stopped=true;}check(stopped,"closed benchmark cannot start requests");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger opened=new AtomicInteger();AtomicBoolean cancelled=new AtomicBoolean(),published=new AtomicBoolean();
        ApiModelBenchmark blocked=new ApiModelBenchmark(model->{opened.incrementAndGet();return new ApiEngine(model,new ApiEngine.Transport(){
            public String choose(ApiEngine.Request r,int timeout)throws Exception{entered.countDown();release.await();return solutions.get(r.fen());}public void close(){release.countDown();}
        });});
        Thread worker=new Thread(()->{try{blocked.run(x->{});published.set(true);}catch(InterruptedException expected){cancelled.set(true);}});
        worker.start();check(entered.await(2,TimeUnit.SECONDS),"benchmark reached first request");blocked.close();worker.join(2000);
        check(!worker.isAlive()&&cancelled.get()&&!published.get()&&opened.get()==1,"cancelled benchmark never opens later paid requests or publishes winner");
    }
}
