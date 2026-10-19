package ru.hod.chess;

import ai.onnxruntime.*;
import ru.hod.chess.core.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Local Maia-3 ONNX policy; no Stockfish reranking or fallback. */
final class MaiaEngine implements HistoryEngine {
    private final OrtEnvironment environment=OrtEnvironment.getEnvironment();
    private final OrtSession session;private final EngineSettings settings;
    private final ReentrantLock execution=new ReentrantLock();private volatile ActiveRun active;
    private final AtomicBoolean closed=new AtomicBoolean(),released=new AtomicBoolean();
    private final Random random=new Random();
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"maia-timeout");t.setDaemon(true);return t;});
    private static final class ActiveRun implements AutoCloseable {
        final OrtSession.RunOptions options=new OrtSession.RunOptions();private boolean released;
        ActiveRun()throws OrtException{}
        synchronized void terminate(){if(!released)try{options.setTerminate(true);}catch(OrtException|IllegalStateException ignored){}}
        public synchronized void close(){if(!released){released=true;options.close();}}
    }
    MaiaEngine(File model,EngineSettings settings,int threads)throws Exception{
        this.settings=settings;
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
            options.setIntraOpNumThreads(Math.max(1,Math.min(4,threads)));options.setInterOpNumThreads(1);options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session=environment.createSession(model.getAbsolutePath(),options);
        }
        if(!session.getInputNames().containsAll(Set.of("tokens","self_elo","opponent_elo"))||!session.getOutputNames().contains("move_logits")){session.close();watchdog.shutdownNow();throw new IOException("Формат модели Maia-3 не совпал");}
    }
    public String name(){return settings.label();}
    public void analyze(Position p,int millis,Consumer<Analysis> progress)throws Exception{analyzeGame(p,p,List.of(),millis,progress);}
    public void analyzeGame(Position initial,Position expected,List<Move> moves,int millis,Consumer<Analysis> progress)throws Exception{
        float[] input=MaiaPolicy.encode(initial,expected,moves);execution.lockInterruptibly();
        try{
            if(closed.get())throw new IOException("Maia остановлена");
            Thread owner=Thread.currentThread();AtomicBoolean expired=new AtomicBoolean();long deadline=System.nanoTime()+settings.profile().inferenceTimeoutMillis()*1_000_000L;
            try(ActiveRun run=new ActiveRun();
                OnnxTensor tokens=OnnxTensor.createTensor(environment,FloatBuffer.wrap(input),new long[]{1,64,97});
                OnnxTensor self=OnnxTensor.createTensor(environment,LongBuffer.wrap(new long[]{settings.model().rating()}),new long[]{1});
                OnnxTensor opponent=OnnxTensor.createTensor(environment,LongBuffer.wrap(new long[]{settings.model().rating()}),new long[]{1})){
                active=run;ScheduledFuture<?> guard=watchdog.scheduleAtFixedRate(()->{if(closed.get()||owner.isInterrupted()||System.nanoTime()>deadline){expired.set(System.nanoTime()>deadline);run.terminate();}},0,25,TimeUnit.MILLISECONDS);
                try(OrtSession.Result result=session.run(Map.of("tokens",tokens,"self_elo",self,"opponent_elo",opponent),run.options)){
                    if(owner.isInterrupted())throw new InterruptedException();if(closed.get())throw new IOException("Maia остановлена");if(expired.get())throw new IOException("Maia не успела ответить · выбери быструю сеть 5M");
                    float[][] logits=(float[][])result.get("move_logits").orElseThrow(()->new IOException("Нет вероятностей Maia")).getValue();
                    var choice=MaiaPolicy.choose(expected,logits[0],settings.sampling(),random.nextDouble());
                    List<Analysis.Line> lines=choice.move()==null?List.of():List.of(new Analysis.Line(1,0,null,List.of(choice.move())));
                    progress.accept(new Analysis(name(),0,1,0,null,lines,choice.probability()));
                }catch(OrtException e){if(owner.isInterrupted())throw new InterruptedException();if(expired.get())throw new IOException("Maia не успела ответить · выбери быструю сеть 5M",e);throw e;}
                finally{guard.cancel(false);active=null;}
            }
        }finally{active=null;if(closed.get())release();execution.unlock();}
    }
    public void close(){closed.set(true);ActiveRun running=active;if(running!=null)running.terminate();if(execution.tryLock()){try{release();}finally{execution.unlock();}}}
    private void release(){if(released.compareAndSet(false,true)){watchdog.shutdownNow();try{session.close();}catch(OrtException|IllegalStateException ignored){}}}
}
