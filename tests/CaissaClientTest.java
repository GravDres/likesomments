import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import ru.hod.chess.core.*;

/** Protocol and lifecycle tests. The fixture does not measure real engine strength. */
public final class CaissaClientTest {
    private static int count;
    private static final Path work;
    static {try{work=Files.createTempDirectory("hod-caissa-client-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    private static void check(boolean yes,String message){count++;if(!yes)throw new AssertionError(message);}
    private static String quoted(String value){return "'"+value.replace("'","'\"'\"'")+"'";}
    private static File fixture(String mode,Path log)throws Exception{
        Path script=Files.createTempFile(work,"run-", ".sh");
        String cp=Path.of("artifacts/core-tests").toAbsolutePath().toString();
        Files.writeString(script,"#!/usr/bin/env bash\nexec "+quoted(System.getProperty("java.home")+"/bin/java")+" -cp "+quoted(cp)+" FakeUci "+quoted(mode)+" "+quoted(log.toString())+"\n");
        check(script.toFile().setExecutable(true),"fixture executable");return script.toFile();
    }
    private static File network()throws Exception{Path file=work.resolve("verified network.pnn");Files.writeString(file,"fixture");return file.toFile();}
    private static UciEngine engine(String mode,Path log)throws Exception{return new UciEngine(fixture(mode,log),network(),2,1,128);}
    private static Analysis analyze(UciEngine engine,Position p)throws Exception{AtomicReference<Analysis> result=new AtomicReference<>();engine.analyze(p,60,result::set);check(result.get()!=null,"result available");return result.get();}
    private static Position play(Position p,String...moves){for(String move:moves)p=p.play(Move.parse(move));return p;}
    public static void main(String[] args)throws Exception{
        try{
            settings();protocol();moveOnlyResults();specialMoves();lifecycle();failureRecovery();modelStore();
            System.out.println("PASS: "+count+" Caissa client assertions (simulated protocol, not a strength benchmark)");
        }finally{try(var paths=Files.walk(work)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void settings(){
        check(TimeControlProfile.fromPreference(null)==TimeControlProfile.BLITZ_3_2,"default blitz 3+2");
        check(TimeControlProfile.fromPreference("retired-mode")==TimeControlProfile.BLITZ_3_2,"unknown profile migrates safely");
        int[] expected={300,600,900,1500,2500,8000};int i=0;
        for(TimeControlProfile profile:TimeControlProfile.values()){
            check(profile.analysisMillis(99999)==expected[i++],"profile budget "+profile.key());
            check(TimeControlProfile.fromPreference(profile.key())==profile,"profile preference roundtrip");
            EngineSettings s=new EngineSettings(profile,99999,88,9999);
            check(s.threads()==4&&s.hashMiB()==256&&s.customMillis()==8000,"resource bounds");
            check(s.responseTimeoutMillis()>=s.analysisMillis()+5000,"watchdog leaves protocol deadline time");
        }
        EngineSettings low=new EngineSettings(TimeControlProfile.CUSTOM,-5,-8,-20);
        check(low.analysisMillis()==300&&low.threads()==1&&low.hashMiB()==64,"lower resource bounds");
        check(new EngineSettings(TimeControlProfile.BLITZ_3_2,1500,2,128).label().contains("Caissa 2.0"),"honest engine label");
    }
    private static void protocol()throws Exception{
        Path log=work.resolve("protocol.log");
        try(UciEngine engine=engine("normal",log)){
            Analysis white=analyze(engine,Position.start());check(white.bestMove().uci().equals("e2e4")&&white.depth()==8&&white.centipawns()==35,"valid PV wins over malformed and bound output");
            Position black=play(Position.start(),"e2e4");Analysis answer=analyze(engine,black);
            check(answer.bestMove().uci().equals("e7e5")&&answer.centipawns()==-35,"black score normalized once");
            engine.configure(4,3,256);Analysis multi=analyze(engine,Position.start());
            check(multi.bestMove().uci().equals("d2d4")&&multi.centipawns()==10,"bestmove keeps its own PV score");
            engine.configure(2,1,128);
            Position initial=Position.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 17");
            List<Move> history=List.of(Move.parse("e2e4"),Move.parse("e7e5"),Move.parse("g1f3"));
            Position target=initial;for(Move m:history)target=target.play(m);
            AtomicReference<Analysis> result=new AtomicReference<>();engine.analyzeGame(initial,target,history,60,result::set);
            check(target.legalMoves().contains(result.get().bestMove()),"baseline plus complete history");
            boolean mismatch=false;try{engine.analyzeGame(Position.start(),target,history,60,result::set);}catch(IllegalArgumentException expected){mismatch=true;}
            check(mismatch,"wrong full FEN baseline rejected before sending");
            engine.newGame();check(analyze(engine,Position.start()).bestMove()!=null,"new game resumes");
            String transcript=Files.readString(log);
            check(transcript.contains("setoption name EvalRandomization value 0"),"random weakening off");
            check(transcript.contains("setoption name Hash value 128")&&transcript.contains("setoption name Hash value 256"),"hash configured");
            check(transcript.contains("setoption name Threads value 4"),"threads configured");
            check(transcript.contains("position fen "+initial.fen()+" moves e2e4 e7e5 g1f3"),"full baseline and history actually sent");
            check(!transcript.contains("Skill Level")&&!transcript.contains("UCI_LimitStrength"),"no retired engine options");
        }
        for(String mode:new String[]{"wrong-engine","no-network","missing-option"}){
            boolean failed=false;try(UciEngine ignored=engine(mode,work.resolve(mode+".log"))){}catch(IOException expected){failed=true;}
            check(failed,"fail closed for "+mode);
        }
        for(String mode:new String[]{"illegal","nullmove","none","crash"}){
            try(UciEngine engine=engine(mode,work.resolve(mode+".log"))){boolean failed=false;try{analyze(engine,Position.start());}catch(IOException expected){failed=true;}check(failed,"reject "+mode);}
        }
    }
    private static void noEvaluation(Analysis answer,String message){
        check(answer.bestMove()!=null,message+": root move retained");
        check(!answer.hasSearchEvaluation()&&answer.depth()==-1,message+": missing evaluation explicitly marked");
        check(answer.score().equals("—")&&answer.mate()==null,message+": no invented score or mate");
        check(answer.nodes()==0,message+": no unrelated node count");
        check(answer.lines().size()==1&&answer.lines().get(0).moves().size()==1,message+": no unrelated variation retained");
    }
    private static void moveOnlyResults()throws Exception{
        Position start=Position.start(),black=play(start,"e2e4");
        for(String mode:new String[]{"bestmove-only","bound-only","malformed-only","unreported"}){
            try(UciEngine engine=engine(mode,work.resolve(mode+".log"))){
                Analysis white=analyze(engine,start);noEvaluation(white,mode);
                check(start.legalMoves().contains(white.bestMove()),"legal final move without scored PV accepted");
                if(mode.equals("unreported")){
                    check(white.bestMove().uci().equals("g1h3"),"authoritative move replaces earlier scored e4");
                    check(white.centipawns()!=35,"old e4 score does not leak into g1h3");
                }else{
                    Analysis next=analyze(engine,black);noEvaluation(next,mode+" Black");
                    check(next.bestMove().uci().equals("e7e5"),"same process continues for Black after move-only result");
                }
                check(engine.isRunning(),"missing info does not terminate Caissa");
                engine.newGame();noEvaluation(analyze(engine,start),mode+" new game");
            }
        }
        String[][] positions={
            {"6k1/5ppp/8/8/8/8/5PPP/3Q2K1 w - - 0 1","d1d8"},
            {"7k/8/8/8/8/8/4q3/4K3 w - - 0 1","e1e2"},
            {"4k3/4Q3/8/8/8/8/8/7K b - - 0 1","e8e7"},
            {"r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 10","e1g1"},
            {"7k/8/8/3pP3/8/8/8/7K w - d6 0 10","e5d6"},
            {"7k/P7/8/8/8/8/8/7K w - - 0 1","a7a8q"}};
        int i=0;for(String[] test:positions){
            Position p=Position.fromFen(test[0]);
            try(UciEngine engine=engine("bestmove-only:"+test[1],work.resolve("move-only-"+i+++".log"))){
                Analysis answer=analyze(engine,p);noEvaluation(answer,"special move-only");
                check(answer.bestMove().uci().equals(test[1])&&p.legalMoves().contains(answer.bestMove()),"exact special bestmove retained");
                check(!p.play(answer.bestMove()).inCheck(p.white),"move-only answer obeys king safety");
            }
        }
    }
    private static void specialMoves()throws Exception{
        String[][] cases={
            {"r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 10","e1g1"},
            {"r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 10","e8c8"},
            {"7k/8/8/3pP3/8/8/8/7K w - d6 0 10","e5d6"},
            {"7k/P7/8/8/8/8/8/7K w - - 0 1","a7a8q"},
            {"7k/P7/8/8/8/8/8/7K w - - 0 1","a7a8n"},
            {"7k/8/8/8/8/8/4q3/4K3 w - - 0 1","e1e2"},
            {"4k3/4Q3/8/8/8/8/8/7K b - - 0 1","e8e7"}};
        int i=0;for(String[] test:cases){
            Position position=Position.fromFen(test[0]);
            check(position.legalMoves().contains(Move.parse(test[1])),"special test position legal");
            try(UciEngine engine=engine("move:"+test[1],work.resolve("special-"+i+++".log"))){
                Analysis a=analyze(engine,position);check(a.bestMove().uci().equals(test[1]),"special move survives protocol validation");
                check(!position.play(a.bestMove()).inCheck(position.white),"king safe after selected move");
            }
        }
        try(UciEngine engine=engine("normal",work.resolve("terminal.log"))){
            for(String fen:new String[]{"7k/6Q1/6K1/8/8/8/8/8 b - - 0 10","7k/5Q2/6K1/8/8/8/8/8 b - - 0 10"}){
                Analysis terminal=analyze(engine,Position.fromFen(fen));check(terminal.bestMove()==null,"terminal position has no suggested move");
            }
            check(!Files.readString(work.resolve("terminal.log")).contains("go "),"no search for mate or stalemate");
        }
    }
    private static void lifecycle()throws Exception{
        Path log=work.resolve("cancellation.log");
        try(UciEngine engine=engine("slow-stop",log)){
            ExecutorService executor=Executors.newSingleThreadExecutor();try{
                for(int i=0;i<3;i++){
                    CountDownLatch started=new CountDownLatch(1);
                    Future<?> search=executor.submit(()->{try{engine.analyze(Position.start(),2000,a->started.countDown());}catch(InterruptedException expected){Thread.currentThread().interrupt();}catch(Exception e){throw new RuntimeException(e);}});
                    check(started.await(3,TimeUnit.SECONDS),"search really started");search.cancel(true);
                    Future<Analysis> next=executor.submit(()->analyze(engine,play(Position.start(),"e2e4")));
                    check(next.get(5,TimeUnit.SECONDS).bestMove().uci().equals("e7e5"),"late old bestmove drained before new position");
                }
            }finally{executor.shutdownNow();}
            check(Files.readString(log).contains("stop"),"cancel sends stop");
        }
        try(UciEngine engine=engine("hang",work.resolve("close.log"))){
            ExecutorService executor=Executors.newSingleThreadExecutor();CountDownLatch started=new CountDownLatch(1);
            try{Future<?> search=executor.submit(()->{try{engine.analyze(Position.start(),8000,a->started.countDown());}catch(Exception expected){}});check(started.await(3,TimeUnit.SECONDS),"hung search started");long begin=System.nanoTime();engine.close();check(System.nanoTime()-begin<TimeUnit.MILLISECONDS.toNanos(500),"close does not wait for native quit");search.get(2,TimeUnit.SECONDS);}finally{executor.shutdownNow();}
        }
    }
    private static void failureRecovery()throws Exception{
        try(UciEngine engine=engine("hang",work.resolve("timeout.log"))){
            long start=System.nanoTime();boolean failed=false;
            try{engine.analyze(Position.start(),1,a->{});}catch(IOException expected){failed=true;}
            check(failed&&!engine.isRunning(),"timeout closes hung process instead of reusing its pending search");
            check(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(9),"timeout bounded");
        }
        try(UciEngine engine=engine("ignore-stop",work.resolve("ignore-stop.log"))){
            ExecutorService queue=Executors.newSingleThreadExecutor();CountDownLatch started=new CountDownLatch(1);
            try{
                Future<?> old=queue.submit(()->{try{engine.analyze(Position.start(),8000,a->started.countDown());}catch(InterruptedException expected){Thread.currentThread().interrupt();}catch(Exception failure){throw new RuntimeException(failure);}});
                check(started.await(3,TimeUnit.SECONDS),"unresponsive stop fixture started");old.cancel(true);
                queue.submit(()->{}).get(4,TimeUnit.SECONDS);
                check(!engine.isRunning(),"unresponsive cancellation closes process");
            }finally{queue.shutdownNow();}
        }
        try(UciEngine next=engine("normal",work.resolve("replacement.log"))){
            check(analyze(next,play(Position.start(),"e2e4")).bestMove().uci().equals("e7e5"),"replacement process resumes for correct side");
        }
    }
    private static void modelStore()throws Exception{
        byte[] data="verified-network-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));File target=work.resolve("model-store/network.pnn").toFile();
        AtomicInteger opens=new AtomicInteger();ModelStore.Source source=()->{opens.incrementAndGet();return new ByteArrayInputStream(data);};
        ModelStore.ensure(target,hash,source);check(ModelStore.hash(target).equals(hash),"verified atomic extraction");
        ModelStore.ensure(target,hash,source);check(opens.get()==1,"same verified model not recopied");
        Files.writeString(target.toPath(),"corrupted");ModelStore.ensure(target,hash,source);check(ModelStore.hash(target).equals(hash)&&opens.get()==2,"corruption repaired");
        boolean rejected=false;try{ModelStore.ensure(target,"0".repeat(64),source);}catch(IOException expected){rejected=true;}
        check(rejected&&ModelStore.hash(target).equals(hash),"wrong model hash never overwrites good data");
    }
}
