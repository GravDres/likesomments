import ru.hod.chess.core.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Scripted evaluations verify selection and protocol, not neural accuracy or Elo. */
public final class TacticalMaiaTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static Analysis score(Move move,int cp,Integer mate){return new Analysis("fixture",12,100,cp,mate,List.of(new Analysis.Line(1,cp,mate,List.of(move))));}
    private static final class Fixture implements RootMoveEngine {
        Analysis regular,restricted;Position expected;List<Move> expectedHistory;
        int calls,restrictedCalls,closes,resets,totalMillis;
        boolean fail;Runnable before=()->{};
        CountDownLatch entered,release;
        Fixture(Position p,List<Move> history,Analysis regular,Analysis restricted){expected=p;expectedHistory=history;this.regular=regular;this.restricted=restricted;}
        public String name(){return "fixture";}
        public void analyze(Position p,int millis,Consumer<Analysis> output)throws Exception{analyzeGame(p,p,List.of(),millis,output);}
        public void analyzeGame(Position initial,Position p,List<Move> history,int millis,Consumer<Analysis> output)throws Exception{calls++;run(initial,p,history,millis,regular,output);}
        public void analyzeMoveGame(Position initial,Position p,List<Move> history,Move move,int millis,Consumer<Analysis> output)throws Exception{restrictedCalls++;check(p.legalMoves().contains(move),"constrained search receives a legal root move");run(initial,p,history,millis,restricted,output);}
        private void run(Position initial,Position p,List<Move> history,int millis,Analysis answer,Consumer<Analysis> output)throws Exception{
            check(p.fen().equals(expected.fen())&&history.equals(expectedHistory),"both engines retain the exact position and history");
            check(GameTracker.replay(initial,history).position().fen().equals(p.fen()),"non-start FEN and all metadata reach both searches");
            totalMillis+=millis;before.run();if(entered!=null){entered.countDown();release.await();}
            if(answer!=null)output.accept(answer);if(fail)throw new IOException("scripted failure after partial result");
        }
        public void newGame(){resets++;}
        public void close(){closes++;if(release!=null)release.countDown();}
    }
    public static void main(String[] args)throws Exception{
        check(EngineMode.fromPreference("maia-tactical")==EngineMode.MAIA_TACTICAL,"hybrid selection persists independently of pure Maia");
        EngineSettings timed=new EngineSettings(EngineMode.MAIA_TACTICAL,MaiaModel.M2200,MaiaNetwork.PRECISE,TimeControlProfile.CUSTOM,EngineStrength.MEDIUM,8000,false);
        check(timed.responseTimeoutMillis()>=25000,"health timeout covers model inference and both verifier searches");
        Position white=Position.start(),black=white.play(Move.parse("e2e4"));
        for(boolean isWhite:new boolean[]{true,false}){
            Position p=isWhite?white:black;List<Move> history=isWhite?List.of():List.of(Move.parse("e2e4"));
            Move proposal=Move.parse(isWhite?"d2d4":"c7c5"),reference=Move.parse(isWhite?"e2e4":"e7e5");int sign=isWhite?1:-1;
            scenario(white,p,history,proposal,reference,50*sign,null,-400*sign,null,true);
            scenario(white,p,history,proposal,reference,50*sign,null,-49*sign,null,false);
            scenario(white,p,history,proposal,reference,50*sign,null,-50*sign,null,true);
            scenario(white,p,history,proposal,reference,0,4*sign,500*sign,null,true);
            scenario(white,p,history,proposal,reference,0,3*sign,0,8*sign,false);
            scenario(white,p,history,proposal,reference,0,null,0,-3*sign,true);
            scenario(white,p,history,proposal,reference,0,-6*sign,0,null,false);
            scenario(white,p,history,proposal,reference,0,null,0,2*sign,false);
        }
        // Real special-move legality is exercised before any candidate is accepted.
        for(String[] special:new String[][]{
            {"k3r3/8/8/8/8/8/8/4K2R w K - 0 1","e1d1"},
            {"r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 17","e1g1"},
            {"7k/8/8/3pP3/8/8/8/7K w - d6 0 18","e5d6"},
            {"7k/P7/8/8/8/8/8/7K w - - 0 1","a7a8n"}}){
            Position p=Position.fromFen(special[0]);Move candidate=Move.parse(special[1]);Move reference=p.legalMoves().stream().filter(m->!m.equals(candidate)).findFirst().orElseThrow();
            scenario(p,p,List.of(),candidate,reference,100,null,80,null,false);
        }
        lifecycle(white);nativeProtocol();
        System.out.println("PASS: "+checks+" tactical Maia assertions (score perspective, mate, history, special moves, cancellation and UCI searchmoves; not measured playing strength)");
    }
    private static void scenario(Position initial,Position p,List<Move> history,Move chosen,Move best,int bestCp,Integer bestMate,int chosenCp,Integer chosenMate,boolean replace)throws Exception{
        Fixture policy=new Fixture(p,history,score(chosen,0,null),null),verifier=new Fixture(p,history,score(best,bestCp,bestMate),score(chosen,chosenCp,chosenMate));
        try(TacticalMaiaEngine engine=new TacticalMaiaEngine(policy,verifier)){
            var reports=new ArrayList<Analysis>();engine.analyzeGame(initial,p,history,600,reports::add);
            check(reports.size()==1,"unchecked policy and intermediate searches never reach the overlay");Analysis result=reports.get(0);
            check(result.bestMove().equals(replace?best:chosen),"only a significant tactical loss replaces the proposed move");
            check(result.centipawns()==(replace?bestCp:chosenCp)&&Objects.equals(result.mate(),replace?bestMate:chosenMate),"selected move retains its own white-perspective score");
            check(result.policyProbability()==null,"hybrid score is a search evaluation, not human move probability");
            check(result.engine().contains(replace?"заменён":"подтверждён"),"actual replacement is visible to the user");
            check(verifier.totalMillis==600&&verifier.calls==1&&verifier.restrictedCalls==1,"two searches share one profile budget");
        }
        check(policy.closes==1&&verifier.closes==1,"both engines are released");
    }
    private static void lifecycle(Position p)throws Exception{
        Move chosen=Move.parse("e2e4"),other=Move.parse("d2d4");
        Fixture policy=new Fixture(p,List.of(),score(chosen,0,null),null),verifier=new Fixture(p,List.of(),score(chosen,35,null),null);
        TacticalMaiaEngine engine=new TacticalMaiaEngine(policy,verifier);var reports=new ArrayList<Analysis>();engine.analyze(p,600,reports::add);
        check(verifier.restrictedCalls==0&&reports.size()==1&&reports.get(0).centipawns()==35,"matching moves avoid a redundant constrained search");
        engine.newGame();check(policy.resets==1&&verifier.resets==1,"new game clears both engine histories");engine.close();engine.close();check(policy.closes==1&&verifier.closes==1,"hybrid close is idempotent");
        reports.clear();boolean rejected=false;try{engine.analyze(p,600,reports::add);}catch(IOException expected){rejected=true;}check(rejected&&reports.isEmpty(),"closed engine cannot publish a stale move");
        for(String kind:new String[]{"illegal","mismatched-history","missing","failure","wrong-root","interrupted"}){
            Fixture a=new Fixture(p,List.of(),kind.equals("illegal")?score(Move.parse("e2e5"),0,null):score(chosen,0,null),null);
            Fixture b=new Fixture(p,List.of(),score(other,40,null),kind.equals("missing")?null:score(kind.equals("wrong-root")?other:chosen,0,null));
            if(kind.equals("failure"))b.fail=true;if(kind.equals("interrupted"))b.before=()->Thread.currentThread().interrupt();
            try(TacticalMaiaEngine e=new TacticalMaiaEngine(a,b)){
                var output=new ArrayList<Analysis>();boolean failed=false;
                try{e.analyzeGame(p,p,kind.equals("mismatched-history")?List.of(chosen):List.of(),600,output::add);}catch(Exception expected){failed=true;}finally{Thread.interrupted();}
                check(failed&&output.isEmpty(),"unverified answer cannot escape after "+kind);
                if(kind.equals("mismatched-history"))check(a.calls==0&&b.calls==0,"bad history is rejected before inference");
            }
        }
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Fixture a=new Fixture(p,List.of(),score(chosen,0,null),null),b=new Fixture(p,List.of(),score(other,0,null),score(chosen,0,null));b.entered=new CountDownLatch(1);b.release=new CountDownLatch(1);
            TacticalMaiaEngine e=new TacticalMaiaEngine(a,b);var output=new CopyOnWriteArrayList<Analysis>();
            Future<?> task=worker.submit(()->{try{e.analyze(p,600,output::add);}catch(Exception expected){}});
            check(b.entered.await(3,TimeUnit.SECONDS),"verifier entered an active search");e.close();task.get(3,TimeUnit.SECONDS);
            check(output.isEmpty()&&a.closes==1&&b.closes==1,"closing during verification cancels without exposing an unchecked move");
        }finally{worker.shutdownNow();}
    }
    private static void nativeProtocol()throws Exception{
        File fixture=new File("tests/fake-uci.sh");fixture.setExecutable(true);
        try(UciEngine engine=new UciEngine(fixture,2,1,20)){
            Position initial=Position.start();List<Move> history=List.of(Move.parse("e2e4"));Position black=initial.play(history.get(0));
            for(boolean white:new boolean[]{true,false}){
                Position p=white?initial:black;List<Move> moves=white?List.of():history;Move candidate=Move.parse(white?"d2d4":"c7c5");var result=new ArrayList<Analysis>();
                engine.analyzeMoveGame(initial,p,moves,candidate,40,result::add);Analysis answer=result.get(result.size()-1);
                check(answer.bestMove().equals(candidate)&&answer.centipawns()==(white?-250:250),"real UCI client sends searchmoves and preserves candidate score for either side");
            }
            boolean bad=false;try{engine.analyzeMoveGame(initial,initial,List.of(),Move.parse("e2e5"),40,a->{});}catch(IllegalArgumentException expected){bad=true;}check(bad,"invalid root restriction is rejected before writing to UCI");
            var result=new ArrayList<Analysis>();engine.analyze(initial,40,result::add);check(result.get(result.size()-1).bestMove().uci().equals("e2e4"),"unrestricted analysis works after constrained searches");
        }
    }
}
