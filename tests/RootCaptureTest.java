import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import ru.hod.chess.core.*;

public class RootCaptureTest {
    private static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    interface IO {void run()throws Exception;}
    static void rejected(IO run,String message)throws Exception{try{run.run();throw new AssertionError(message);}catch(IOException expected){checks++;}}
    static DataInputStream input(byte[] bytes){return new DataInputStream(new ByteArrayInputStream(bytes));}
    static byte[] packet(int...values)throws IOException{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);for(int n:values)out.writeInt(n);return bytes.toByteArray();}
    static Process fake(String mode)throws IOException{return new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("java.class.path"),"FakeRootCapture",mode).start();}
    public static void main(String[] args)throws Exception{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        int w=91,h=72;int[] colors=new int[w*h];
        Random random=new Random(54321);for(int i=0;i<colors.length;i++)colors[i]=random.nextInt();
        RootCaptureWire.frame(out,new RootCaptureWire.Frame(144,300,17,80,w,h,1,colors));
        RootCaptureWire.Frame f=RootCaptureWire.readFrame(input(bytes.toByteArray()));
        check(Arrays.equals(colors,f.pixels()),"ARGB survives chunk boundaries without swapping red and blue");
        check(f.left()==17&&f.top()==80&&f.screenWidth()==144&&f.screenHeight()==300&&f.flags()==1,"crop retains screen coordinates and secure metadata");
        byte[] truncated=Arrays.copyOf(bytes.toByteArray(),bytes.size()-1);rejected(()->RootCaptureWire.readFrame(input(truncated)),"truncated pixels must not become a valid board");
        rejected(()->RootCaptureWire.readFrame(input(packet(0x484f4431,1,2,8192,8192,0,0,8192,8192,0,1))),"oversized screen rejected before allocation");
        rejected(()->RootCaptureWire.readFrame(input(packet(0x484f4431,1,2,80,120,Integer.MAX_VALUE,0,2,2,0,4))),"overflowed crop rejected before allocation");
        rejected(()->RootCaptureWire.readFrame(input(packet(0x484f4431,1,2,80,120,0,0,2,2,0,3))),"wrong pixel count rejected");
        rejected(()->RootCaptureWire.readFrame(input(packet(0x484f4431,99,3))),"version mismatch rejected");
        rejected(()->RootCaptureWire.readFrame(input(packet(123,1,3))),"su stdout noise cannot become pixels");
        bytes.reset();RootCaptureWire.idle(out);check(RootCaptureWire.readFrame(input(bytes.toByteArray()))==null,"heartbeat never supplies a synthetic frame");
        bytes.reset();RootCaptureWire.error(out,"Permission denied");rejected(()->RootCaptureWire.readHello(input(bytes.toByteArray())),"root denial reported before hello");
        var crop=new RootCaptureWire.Request(80,120,3,6,32,false);check(crop.cropFits(80,120)&&!crop.cropFits(120,80),"rotation requests a fresh full display");
        check(!new RootCaptureWire.Request(80,120,70,110,32,false).cropFits(80,120),"outdated crop falls back to full capture");
        bytes.reset();RootCaptureWire.request(out,crop);check(RootCaptureWire.readRequest(input(bytes.toByteArray())).equals(crop),"request round trip");
        String literal="/data/app/a ' \" `printf bad` $(printf worse); space/base.apk";
        Process quoted=new ProcessBuilder("/bin/sh","-c","printf '%s' "+RootCaptureSession.quote(literal)).start();
        check(new String(quoted.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).equals(literal)&&quoted.waitFor()==0,"APK path cannot interpolate shell commands");
        try{RootCaptureSession.quote("bad\npath");throw new AssertionError("newline accepted");}catch(IllegalArgumentException expected){checks++;}
        CleanFrameGate gate=new CleanFrameGate();gate.block();check(!gate.acceptFreshCapture(Long.MAX_VALUE),"blocked overlay capture stays blocked");gate.hidden(1000);
        check(!gate.acceptFreshCapture(70_000_999)&&gate.acceptFreshCapture(70_001_000),"fresh root request waits for compositor settle without queued-buffer delay");
        check(!gate.accept(70_001_000)&&!gate.accept(70_001_000)&&gate.accept(70_001_000),"ordinary MediaProjection still discards its queued images");
        sessionRoundTrip();failure("denied","SU_DENIED");failure("not-root","UID 0");failure("startup-hang","Root не ответил");failure("frame-hang","перестал отвечать");failure("broken","Неизвестный ответ");failure("eof","su завершился");
        AtomicInteger opened=new AtomicInteger();Probe probe=new Probe();RootCaptureSession closed=new RootCaptureSession(()->{opened.incrementAndGet();return fake("ok");},probe,1000,1000);closed.close();closed.start();check(opened.get()==0,"closed session never requests root");
        System.out.println("PASS: "+checks+" RootCaptureTest checks (host subprocesses; Android root capture is not executed)");
    }
    static class Probe implements RootCaptureSession.Listener {
        final CountDownLatch finished=new CountDownLatch(1);final AtomicInteger ready=new AtomicInteger(),frames=new AtomicInteger(),failures=new AtomicInteger();volatile String error;
        public void ready(int sdk){ready.incrementAndGet();}
        public RootCaptureSession.Request request(){return new RootCaptureSession.Request(new RootCaptureWire.Request(80,120,0,0,0,false),7,11);}
        public void frame(RootCaptureWire.Frame f,RootCaptureSession.Request r){frames.incrementAndGet();}
        public void heartbeat(){}
        public void failed(String reason){error=reason;failures.incrementAndGet();finished.countDown();}
    }
    static void failure(String mode,String fragment)throws Exception{
        Probe probe=new Probe();AtomicReference<Process> process=new AtomicReference<>();
        RootCaptureSession s=new RootCaptureSession(()->{Process p=fake(mode);process.set(p);return p;},probe,1500,350);
        try{s.start();check(probe.finished.await(5,TimeUnit.SECONDS),mode+" must finish");check(probe.error.contains(fragment),mode+": "+probe.error);check(probe.frames.get()==0&&probe.failures.get()==1,mode+" must not produce a board");}
        finally{s.close();Process p=process.get();if(p!=null)check(p.waitFor(3,TimeUnit.SECONDS),mode+" process is released");}
    }
    static void sessionRoundTrip()throws Exception{
        class RoundTrip extends Probe {
            final AtomicInteger requests=new AtomicInteger(),heartbeats=new AtomicInteger();final List<RootCaptureWire.Frame> received=new CopyOnWriteArrayList<>();final List<RootCaptureSession.Request> contexts=new CopyOnWriteArrayList<>();
            public RootCaptureSession.Request request(){int n=requests.getAndIncrement();return new RootCaptureSession.Request(n==0?RootCaptureWire.Request.idleRequest():new RootCaptureWire.Request(80,120,3,6,n==1?0:32,false),70+n,110+n);}
            public void frame(RootCaptureWire.Frame f,RootCaptureSession.Request r){received.add(f);contexts.add(r);frames.incrementAndGet();if(frames.get()==2)finished.countDown();}
            public void heartbeat(){heartbeats.incrementAndGet();}
        }
        RoundTrip probe=new RoundTrip();AtomicReference<Process> process=new AtomicReference<>();RootCaptureSession s=new RootCaptureSession(()->{Process p=fake("ok");process.set(p);return p;},probe,2000,2000);
        try{s.start();check(probe.finished.await(5,TimeUnit.SECONDS),"root pipe delivers two independent frames");check(probe.ready.get()==1&&probe.failures.get()==0&&probe.heartbeats.get()==1,"single authorization and idle response");
            var full=probe.received.get(0);var crop=probe.received.get(1);
            check(full.width()==80&&full.height()==120&&crop.width()==32&&crop.left()==3&&crop.top()==6,"switch from full search to board crop");
            check(crop.pixels()[0]==full.pixels()[6*80+3]&&crop.pixels()[31*32+31]==full.pixels()[37*80+34],"crop pixels match exact screen squares");
            check(crop.flags()==RootCaptureWire.SECURE_LAYERS&&probe.contexts.get(1).epoch()==72&&probe.contexts.get(1).cleanVersion()==112,"security flag and request identity retained");}
        finally{s.close();check(process.get().waitFor(3,TimeUnit.SECONDS),"closing session releases child process");}
    }
}
