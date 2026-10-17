package ru.hod.chess;

import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.IBinder;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicLong;
import ru.hod.chess.core.RootCaptureWire;

/** Standalone entry point, started only by the user's su manager, never as an Android component. */
public final class RootCaptureMain {
    private RootCaptureMain() {}
    public static void main(String[] args){
        DataOutputStream out=new DataOutputStream(new BufferedOutputStream(System.out,64*1024));
        AtomicLong activity=new AtomicLong(System.nanoTime());
        Thread exit=new Thread(()->{for(;;){try{Thread.sleep(1000);}catch(InterruptedException e){return;}if(System.nanoTime()-activity.get()>20_000_000_000L)System.exit(0);}},"root-parent-watchdog");exit.setDaemon(true);exit.start();
        int code=0;
        try{
            if(android.os.Process.myUid()!=0)throw new SecurityException("Менеджер root не предоставил UID 0");
            if(Build.VERSION.SDK_INT<34)throw new UnsupportedOperationException("Root-захват поддерживает Android 14+");
            // This process uses system APIs. It has no Activity, network, input injection or file output.
            Access access=new Access();
            RootCaptureWire.hello(out,android.os.Process.myUid(),Build.VERSION.SDK_INT);
            DataInputStream in=new DataInputStream(new BufferedInputStream(System.in,4096));
            for(;;){
                RootCaptureWire.Request r;
                try{r=RootCaptureWire.readRequest(in);}catch(EOFException e){break;}
                activity.set(System.nanoTime());
                if(r.idle())RootCaptureWire.idle(out);else RootCaptureWire.frame(out,access.capture(r));
            }
        }catch(Throwable e){code=1;while(e instanceof InvocationTargetException&&e.getCause()!=null)e=e.getCause();String detail=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());try{RootCaptureWire.error(out,detail);}catch(IOException ignored){}System.err.println("HOD root capture: "+detail);}
        finally{System.exit(code);}
    }
    private static final class Access {
        final Object displayToken;final Class<?> builderClass,argsClass;final Method capture,asBitmap,hardware,secure;
        Access()throws Exception{
            Class<?> factory=Class.forName("com.android.internal.os.ClassLoaderFactory");
            String paths=System.getenv("SYSTEMSERVERCLASSPATH");if(paths==null||paths.isEmpty())paths="/system/framework/services.jar";
            ClassLoader loader=(ClassLoader)factory.getDeclaredMethod("createClassLoader",String.class,String.class,String.class,ClassLoader.class,int.class,boolean.class,String.class)
                .invoke(null,paths,null,null,ClassLoader.getSystemClassLoader(),0,true,null);
            Class<?> control=loader.loadClass("com.android.server.display.DisplayControl");
            Method library=Runtime.class.getDeclaredMethod("loadLibrary0",Class.class,String.class);library.setAccessible(true);library.invoke(Runtime.getRuntime(),control,"android_servers");
            long[] ids=(long[])control.getMethod("getPhysicalDisplayIds").invoke(null);
            if(ids==null||ids.length==0)throw new IllegalStateException("Физический дисплей не найден");
            displayToken=control.getMethod("getPhysicalDisplayToken",long.class).invoke(null,ids[0]);
            if(displayToken==null)throw new IllegalStateException("Нет токена основного дисплея");
            Class<?> screen=Class.forName("android.window.ScreenCapture");
            argsClass=Class.forName("android.window.ScreenCapture$DisplayCaptureArgs");builderClass=Class.forName("android.window.ScreenCapture$DisplayCaptureArgs$Builder");
            capture=screen.getMethod("captureDisplay",argsClass);
            Class<?> buffer=Class.forName("android.window.ScreenCapture$ScreenshotHardwareBuffer");
            asBitmap=buffer.getMethod("asBitmap");hardware=buffer.getMethod("getHardwareBuffer");secure=buffer.getMethod("containsSecureLayers");
        }
        RootCaptureWire.Frame capture(RootCaptureWire.Request r)throws Exception{
            Object builder=builderClass.getConstructor(IBinder.class).newInstance(displayToken);
            builderClass.getMethod("setCaptureSecureLayers",boolean.class).invoke(builder,true);
            // DRM-protected GPU buffers cannot be read by a software bitmap. They are not requested.
            Object result=capture.invoke(null,builderClass.getMethod("build").invoke(builder));
            if(result==null)throw new IllegalStateException("SurfaceFlinger не выдал кадр. Возможен отказ прошивки/SELinux");
            HardwareBuffer buffer=null;Bitmap gpu=null,cpu=null;
            try{
                buffer=(HardwareBuffer)hardware.invoke(result);gpu=(Bitmap)asBitmap.invoke(result);
                if(gpu==null)throw new IllegalStateException("Пустой графический буфер");
                cpu=gpu.copy(Bitmap.Config.ARGB_8888,false);if(cpu==null)throw new IllegalStateException("Кадр нельзя прочитать");
                int sw=cpu.getWidth(),sh=cpu.getHeight();boolean crop=r.cropFits(sw,sh);
                int left=crop?r.left():0,top=crop?r.top():0,w=crop?r.size():sw,h=crop?r.size():sh;
                if(w<1||h<1||(long)w*h>12_000_000L)throw new IllegalStateException("Слишком большой дисплей");
                int[] pixels=new int[w*h];cpu.getPixels(pixels,0,w,left,top,w,h);
                int flags=Boolean.TRUE.equals(secure.invoke(result))?RootCaptureWire.SECURE_LAYERS:0;
                return new RootCaptureWire.Frame(sw,sh,left,top,w,h,flags,pixels);
            }finally{if(cpu!=null)cpu.recycle();if(gpu!=null&&gpu!=cpu)gpu.recycle();if(buffer!=null)buffer.close();}
        }
    }
}
