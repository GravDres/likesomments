package ru.hod.chess.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One persistent su/app_process session; no root command is run per frame. */
public final class RootCaptureSession implements AutoCloseable {
    public record Request(RootCaptureWire.Request capture,long epoch,long cleanVersion) {}
    public interface Listener {
        void ready(int sdk);
        Request request();
        void frame(RootCaptureWire.Frame frame,Request request);
        void heartbeat();
        void failed(String reason);
    }
    @FunctionalInterface public interface ProcessFactory {Process start()throws IOException;}
    private final ProcessFactory factory;private final Listener listener;
    private final long startupMillis,frameMillis;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicBoolean started=new AtomicBoolean();
    private volatile Process process;private volatile Thread worker;
    private volatile boolean ready;private volatile long progress=System.nanoTime();
    private volatile String stderr="";
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"root-watchdog"));
    public RootCaptureSession(String apk,Listener listener){this(()->new ProcessBuilder("su","-c",command(apk)).start(),listener,60_000,8_000);}
    public RootCaptureSession(ProcessFactory factory,Listener listener,long startupMillis,long frameMillis){this.factory=factory;this.listener=listener;this.startupMillis=startupMillis;this.frameMillis=frameMillis;}
    public static String quote(String value){if(value.indexOf('\0')>=0||value.indexOf('\n')>=0||value.indexOf('\r')>=0)throw new IllegalArgumentException("Недопустимый путь APK");return "'"+value.replace("'","'\\''")+"'";}
    static String command(String apk){return "CLASSPATH="+quote(apk)+" exec /system/bin/app_process /system/bin ru.hod.chess.RootCaptureMain";}
    private static Thread daemon(Runnable run,String name){Thread t=new Thread(run,name);t.setDaemon(true);return t;}
    public void start(){
        if(closed.get())return;if(!started.compareAndSet(false,true))throw new IllegalStateException("Root-сеанс уже запущен");
        worker=daemon(this::run,"root-screen-frames");
        watchdog.scheduleAtFixedRate(()->{long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-progress);if(!closed.get()&&elapsed>(ready?frameMillis:startupMillis))fail(ready?"Root-захват перестал отвечать · нажми ↻":"Root не ответил. Проверь разрешение ХОД в менеджере root");},100,100,TimeUnit.MILLISECONDS);
        worker.start();
    }
    private void run(){
        try{
            Process p=factory.start();process=p;if(closed.get()){dispose(p);return;}
            daemon(()->readErrors(p.getErrorStream()),"root-screen-errors").start();
            DataInputStream in=new DataInputStream(new BufferedInputStream(p.getInputStream(),64*1024));
            DataOutputStream out=new DataOutputStream(new BufferedOutputStream(p.getOutputStream(),4096));
            RootCaptureWire.Hello hello=RootCaptureWire.readHello(in);
            if(hello.uid()!=0)throw new IOException("su не выдал UID 0. Разреши root для ХОД");
            if(hello.sdk()<34)throw new IOException("Этот root-захват требует Android 14 или новее");
            ready=true;progress=System.nanoTime();if(closed.get())return;listener.ready(hello.sdk());
            while(!closed.get()){
                long started=System.nanoTime();Request r=listener.request();if(closed.get())break;
                RootCaptureWire.request(out,r.capture());RootCaptureWire.Frame f=RootCaptureWire.readFrame(in);progress=System.nanoTime();
                if(closed.get())break;
                if(r.capture().idle()){if(f!=null)throw new IOException("Ожидался ответ паузы");listener.heartbeat();}
                else{if(f==null)throw new IOException("Root не вернул изображение");listener.frame(f,r);}
                long delay=250-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                if(delay>0)Thread.sleep(delay);
            }
        }catch(Exception e){if(!closed.get()){String detail=e.getMessage();if(e instanceof EOFException)detail="Процесс su завершился. Проверь разрешение root для ХОД"+(stderr.isBlank()?"":" · "+stderr);if(detail==null||detail.isBlank())detail=stderr.isBlank()?e.getClass().getSimpleName():stderr;fail("Root: "+detail);}}
        finally{close();}
    }
    private void readErrors(InputStream stream){
        try(Reader reader=new InputStreamReader(stream,StandardCharsets.UTF_8)){char[] chars=new char[512];StringBuilder tail=new StringBuilder();for(int n;(n=reader.read(chars))>=0;){tail.append(chars,0,n);if(tail.length()>1024)tail.delete(0,tail.length()-1024);stderr=tail.toString().trim();}}catch(IOException ignored){}
    }
    private void fail(String reason){if(closed.compareAndSet(false,true)){cleanup();listener.failed(reason);}}
    @Override public void close(){if(closed.compareAndSet(false,true))cleanup();}
    private void cleanup(){
        watchdog.shutdownNow();Thread t=worker;if(t!=null&&t!=Thread.currentThread())t.interrupt();Process p=process;
        // Process pipe close may wait for a concurrent read. Never close it on Android's UI thread.
        if(p!=null)daemon(()->dispose(p),"root-screen-close").start();
    }
    private static void dispose(Process p){
        try{p.getOutputStream().close();}catch(IOException ignored){}p.destroy();
        try{p.getInputStream().close();}catch(IOException ignored){}try{p.getErrorStream().close();}catch(IOException ignored){}
    }
}
