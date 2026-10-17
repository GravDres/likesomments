import java.io.*;
import ru.hod.chess.core.RootCaptureWire;

/** Separate JVM process tests pipe framing, EOF, authorization failure and stalls. No su is used. */
public class FakeRootCapture {
    public static void main(String[] args)throws Exception{
        var in=new DataInputStream(new BufferedInputStream(System.in));var out=new DataOutputStream(new BufferedOutputStream(System.out));
        String mode=args[0];
        if(mode.equals("startup-hang")){Thread.sleep(10_000);return;}
        if(mode.equals("denied")){RootCaptureWire.error(out,"SU_DENIED: доступ не предоставлен");return;}
        RootCaptureWire.hello(out,mode.equals("not-root")?2000:0,36);
        if(mode.equals("eof"))return;
        if(mode.equals("frame-hang")){Thread.sleep(10_000);return;}
        while(true){
            RootCaptureWire.Request r;try{r=RootCaptureWire.readRequest(in);}catch(EOFException e){return;}
            if(r.idle()){RootCaptureWire.idle(out);continue;}
            if(mode.equals("broken")){out.writeInt(123);out.flush();return;}
            int sw=80,sh=120;boolean crop=r.cropFits(sw,sh);int left=crop?r.left():0,top=crop?r.top():0,w=crop?r.size():sw,h=crop?r.size():sh;
            int[] pixels=new int[w*h];for(int y=0;y<h;y++)for(int x=0;x<w;x++)pixels[y*w+x]=0xff000000|((y+top)<<8)|(x+left);
            RootCaptureWire.frame(out,new RootCaptureWire.Frame(sw,sh,left,top,w,h,crop?RootCaptureWire.SECURE_LAYERS:0,pixels));
        }
    }
}
