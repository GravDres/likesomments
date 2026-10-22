import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import ru.hod.chess.core.*;

/** CI: actual pinned Texel, not a simulation or an Elo benchmark. */
public final class TexelNativeTest {
    private static int count;
    private static void check(boolean ok,String message){count++;if(!ok)throw new AssertionError(message);}
    private static Analysis analyze(UciEngine engine,Position p,int millis)throws Exception{
        AtomicReference<Analysis> last=new AtomicReference<>();engine.analyze(p,millis,last::set);
        check(last.get()!=null&&p.legalMoves().contains(last.get().bestMove()),"native Texel returned legal final move: "+p.fen());return last.get();
    }
    public static void main(String[] args)throws Exception{
        try(UciEngine engine=new UciEngine(new File(args[0]),EngineKind.TEXEL,null,1,1,128)){
            check(engine.kind()==EngineKind.TEXEL&&engine.name().startsWith("Texel 1.12"),"official Texel handshake");
            for(SearchLevel level:SearchLevel.values()){
                engine.setSearchLevel(level);analyze(engine,Position.start(),300);
                analyze(engine,Position.start().play(Move.parse("e2e4")),300);
                engine.newGame();analyze(engine,Position.start(),200);
            }
            engine.setSearchLevel(SearchLevel.FULL);
            Position initial=Position.start(),target=initial;List<Move> moves=new ArrayList<>();
            for(int ply=0;ply<12;ply++){
                Move best=analyze(engine,target,100).bestMove();moves.add(best);target=target.play(best);
                AtomicReference<Analysis> next=new AtomicReference<>();engine.analyzeGame(initial,target,moves,100,next::set);
                check(next.get()!=null&&target.legalMoves().contains(next.get().bestMove()),"native Texel history at ply "+ply);
            }
            for(String fen:new String[]{"7k/8/8/8/8/8/4q3/4K3 w - - 0 1","4k3/4Q3/8/8/8/8/8/7K b - - 0 1","r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 10","7k/8/8/3pP3/8/8/8/7K w - d6 0 10","7k/P7/8/8/8/8/8/7K w - - 0 1"})analyze(engine,Position.fromFen(fen),300);
            Position mate=Position.fromFen("6k1/5ppp/8/8/8/8/5PPP/3Q2K1 w - - 0 1");Position after=mate.play(analyze(engine,mate,1500).bestMove());
            check(after.inCheck(after.white)&&after.legalMoves().isEmpty(),"native full-strength Texel finds mate in one");
            ExecutorService queue=Executors.newSingleThreadExecutor();CountDownLatch started=new CountDownLatch(1);
            try{
                Future<?> old=queue.submit(()->{try{engine.analyze(Position.start(),8000,a->started.countDown());}catch(InterruptedException expected){Thread.currentThread().interrupt();}catch(Exception e){throw new RuntimeException(e);}});
                check(started.await(5,TimeUnit.SECONDS),"native Texel search starts before cancellation");old.cancel(true);
                queue.submit(()->{try{engine.setSearchLevel(SearchLevel.MODERATE);return analyze(engine,Position.start().play(Move.parse("e2e4")),300);}catch(Exception e){throw new RuntimeException(e);}}).get(10,TimeUnit.SECONDS);
            }finally{queue.shutdownNow();}
        }
        System.out.println("PASS: "+count+" native Texel host assertions; device speed and Lichess Elo not measured");
    }
}
