import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import ru.hod.chess.core.*;

/** CI ONLY: runs the official Linux build through the production Java client, not an Elo test. */
public final class CaissaNativeTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static Analysis analyze(UciEngine engine,Position p,int millis)throws Exception{
        AtomicReference<Analysis> result=new AtomicReference<>();engine.analyze(p,millis,result::set);
        check(result.get()!=null,"actual engine returned analysis");
        check(p.legalMoves().contains(result.get().bestMove()),"actual bestmove is legal");return result.get();
    }
    public static void main(String[] args)throws Exception{
        try(UciEngine engine=new UciEngine(new File(args[0]),new File(args[1]),2,1,128)){
            check(engine.name().startsWith("Caissa 2.0 "),"real version handshake");
            analyze(engine,Position.start(),300);
            Position start=Position.start(),target=start;List<Move> history=new ArrayList<>();
            for(int ply=0;ply<12;ply++){
                Analysis analysis=analyze(engine,target,100);
                history.add(analysis.bestMove());target=target.play(analysis.bestMove());
                AtomicReference<Analysis> next=new AtomicReference<>();engine.analyzeGame(start,target,history,100,next::set);
                check(next.get()!=null&&target.legalMoves().contains(next.get().bestMove()),"real engine continues history at ply "+ply);
            }
            Position mate=Position.fromFen("6k1/5ppp/8/8/8/8/5PPP/3Q2K1 w - - 0 1");
            Move move=analyze(engine,mate,1500).bestMove();Position after=mate.play(move);
            check(after.inCheck(after.white)&&after.legalMoves().isEmpty(),"real Caissa finds mate in one");
            Position check=Position.fromFen("7k/8/8/8/8/8/4q3/4K3 w - - 0 1");
            check(analyze(engine,check,300).bestMove().uci().equals("e1e2"),"real Caissa captures checking queen");
            for(String fen:new String[]{"r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 10","7k/8/8/3pP3/8/8/8/7K w - d6 0 10","7k/P7/8/8/8/8/8/7K w - - 0 1"})analyze(engine,Position.fromFen(fen),300);
            ExecutorService queue=Executors.newSingleThreadExecutor();CountDownLatch started=new CountDownLatch(1);
            try{
                Future<?> old=queue.submit(()->{try{engine.analyze(start,8000,a->started.countDown());}catch(InterruptedException expected){Thread.currentThread().interrupt();}catch(Exception failure){throw new RuntimeException(failure);}});
                check(started.await(5,TimeUnit.SECONDS),"real asynchronous search started");old.cancel(true);
                Analysis resumed=queue.submit(()->analyze(engine,start.play(Move.parse("e2e4")),300)).get(8,TimeUnit.SECONDS);
                check(start.play(Move.parse("e2e4")).legalMoves().contains(resumed.bestMove()),"real cancellation resumes for Black");
            }finally{queue.shutdownNow();}
            engine.newGame();engine.configure(1,3,64);analyze(engine,start,300);
        }
        System.out.println("PASS: "+checks+" real Caissa 2.0 host assertions; Android device speed/strength not measured");
    }
}
