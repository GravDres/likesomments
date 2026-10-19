package ru.hod.chess;
import ai.onnxruntime.OrtSession;
import ru.hod.chess.core.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Runs the real MaiaEngine adapter against an ORT contract fixture, not model weights. */
public final class MaiaLifecycleTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static MaiaEngine engine()throws Exception{return new MaiaEngine(new File("not-a-model"),new EngineSettings(EngineMode.MAIA,MaiaModel.M2200,MaiaNetwork.FAST,TimeControlProfile.BULLET,EngineStrength.MEDIUM,600,true),2);}
    public static void main(String[] args)throws Exception{
        OrtSession.reset();MaiaEngine first=engine();var results=new ArrayList<Analysis>();Position p=Position.start().play(Move.parse("e2e4"));first.analyzeGame(Position.start(),p,List.of(Move.parse("e2e4")),600,results::add);
        check(results.size()==1&&p.legalMoves().contains(results.get(0).bestMove()),"one legal final result from the real adapter");check(results.get(0).policyProbability()!=null,"probability is labelled separately from engine evaluation");first.close();first.close();check(OrtSession.closes.get()==1,"closing a session is idempotent");
        OrtSession.reset();OrtSession.delayMillis=10000;MaiaEngine interrupted=engine();ExecutorService queue=Executors.newSingleThreadExecutor();AtomicInteger calls=new AtomicInteger();CountDownLatch finished=new CountDownLatch(1);
        try{
            Future<?> search=queue.submit(()->{try{interrupted.analyze(Position.start(),600,a->calls.incrementAndGet());}catch(InterruptedException expected){}catch(Exception e){throw new RuntimeException(e);}finally{finished.countDown();}});
            check(OrtSession.started.await(2,TimeUnit.SECONDS),"native-contract inference entered");search.cancel(true);check(finished.await(2,TimeUnit.SECONDS),"interruption terminates the in-flight run");check(calls.get()==0&&OrtSession.terminations.get()>0,"cancelled run publishes no suggestion");
            OrtSession.delayMillis=0;queue.submit(()->{try{interrupted.analyze(Position.start(),600,a->calls.incrementAndGet());}catch(Exception e){throw new RuntimeException(e);}}).get(2,TimeUnit.SECONDS);check(calls.get()==1,"another inference works after interruption");
        }finally{interrupted.close();queue.shutdownNow();}
        OrtSession.reset();OrtSession.delayMillis=10000;MaiaEngine closing=engine();ExecutorService executor=Executors.newSingleThreadExecutor();AtomicInteger callbacks=new AtomicInteger();
        try{
            Future<?> f=executor.submit(()->{try{closing.analyze(Position.start(),600,a->callbacks.incrementAndGet());}catch(Exception expected){}});check(OrtSession.started.await(2,TimeUnit.SECONDS),"run started before close");closing.close();f.get(2,TimeUnit.SECONDS);check(callbacks.get()==0&&OrtSession.unsafeCloses.get()==0&&OrtSession.closes.get()==1,"close terminates inference before freeing the session");
        }finally{closing.close();executor.shutdownNow();}
        OrtSession.reset();OrtSession.delayMillis=10000;MaiaEngine timeout=engine();boolean expired=false;try{timeout.analyze(Position.start(),600,a->{});}catch(IOException e){expired=e.getMessage().contains("не успела");}finally{timeout.close();}check(expired&&OrtSession.terminations.get()>0,"profile deadline terminates computation rather than falling back to Stockfish");
        OrtSession.reset();OrtSession.nan=true;MaiaEngine invalid=engine();boolean rejected=false;try{invalid.analyze(Position.start(),600,a->{throw new AssertionError("invalid output published");});}catch(IllegalArgumentException expected){rejected=true;}finally{invalid.close();}check(rejected,"non-finite native output cannot produce a suggestion");
        System.out.println("PASS: "+checks+" Maia lifecycle assertions (ORT contract fixture; no real native inference or Elo measurement)");
    }
}
