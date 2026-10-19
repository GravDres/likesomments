package ai.onnxruntime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/** Lifecycle contract fixture, never a neural model and never packaged in Android. */
public final class OrtSession implements AutoCloseable {
    public static volatile long delayMillis;public static volatile boolean nan;
    public static volatile CountDownLatch started=new CountDownLatch(1);
    public static final AtomicInteger running=new AtomicInteger(),closes=new AtomicInteger(),unsafeCloses=new AtomicInteger(),terminations=new AtomicInteger();
    public static void reset(){delayMillis=0;nan=false;started=new CountDownLatch(1);running.set(0);closes.set(0);unsafeCloses.set(0);terminations.set(0);}
    public Set<String> getInputNames(){return Set.of("tokens","self_elo","opponent_elo");}
    public Set<String> getOutputNames(){return Set.of("move_logits");}
    public Result run(Map<String,OnnxTensor> inputs,RunOptions options)throws OrtException{
        running.incrementAndGet();started.countDown();long end=System.nanoTime()+delayMillis*1_000_000L;
        try{while(System.nanoTime()<end){if(options.terminated){terminations.incrementAndGet();throw new OrtException("terminated");}LockSupport.parkNanos(1_000_000);}
            float[][] logits=new float[1][4352];if(nan)logits[0][0]=Float.NaN;return new Result(OnnxTensor.output(logits));
        }finally{running.decrementAndGet();}
    }
    public void close()throws OrtException{if(running.get()>0)unsafeCloses.incrementAndGet();closes.incrementAndGet();}
    public static final class SessionOptions implements AutoCloseable {
        public enum OptLevel{ALL_OPT}
        public void setIntraOpNumThreads(int n)throws OrtException{}public void setInterOpNumThreads(int n)throws OrtException{}public void setOptimizationLevel(OptLevel n)throws OrtException{}public void close(){}
    }
    public static final class RunOptions implements AutoCloseable {
        volatile boolean terminated,closed;
        public RunOptions()throws OrtException{}
        public void setTerminate(boolean value)throws OrtException{if(closed)throw new IllegalStateException("closed");terminated=value;}
        public void close(){closed=true;}
    }
    public static final class Result implements AutoCloseable {
        private final OnnxTensor output;Result(OnnxTensor output){this.output=output;}
        public Optional<OnnxTensor> get(String name){return Optional.of(output);}public void close(){}
    }
}
