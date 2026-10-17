package ru.hod.chess.core;

import java.io.*;

/** Bounded, versioned pipe protocol. Pixels never go through a shell or a file. */
public final class RootCaptureWire {
    private RootCaptureWire() {}
    private static final int MAGIC=0x484f4431,VERSION=1,HELLO=1,FRAME=2,IDLE=3,ERROR=-1;
    private static final int MAX_EDGE=8192,MAX_PIXELS=12_000_000;
    public static final int SECURE_LAYERS=1;
    public record Hello(int uid,int sdk) {}
    public record Request(int screenWidth,int screenHeight,int left,int top,int size,boolean idle) {
        public static Request idleRequest(){return new Request(0,0,0,0,0,true);}
        public boolean cropFits(int w,int h){return !idle&&screenWidth==w&&screenHeight==h&&size>0&&left>=0&&top>=0&&(long)left+size<=w&&(long)top+size<=h;}
    }
    public record Frame(int screenWidth,int screenHeight,int left,int top,int width,int height,int flags,int[] pixels) {}
    private static void header(DataOutputStream out,int type)throws IOException{out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(type);}
    private static int type(DataInputStream in)throws IOException{
        if(in.readInt()!=MAGIC)throw new IOException("Неизвестный ответ su / root-процесса");
        if(in.readInt()!=VERSION)throw new IOException("Несовместимая версия root-протокола");
        int type=in.readInt();if(type==ERROR)throw new IOException(in.readUTF());return type;
    }
    public static void hello(DataOutputStream out,int uid,int sdk)throws IOException{header(out,HELLO);out.writeInt(uid);out.writeInt(sdk);out.flush();}
    public static Hello readHello(DataInputStream in)throws IOException{if(type(in)!=HELLO)throw new IOException("Нет приветствия root-процесса");return new Hello(in.readInt(),in.readInt());}
    public static void error(DataOutputStream out,String message)throws IOException{header(out,ERROR);out.writeUTF(message.substring(0,Math.min(512,message.length())));out.flush();}
    public static void request(DataOutputStream out,Request r)throws IOException{
        header(out,r.idle?IDLE:FRAME);
        if(!r.idle){out.writeInt(r.screenWidth);out.writeInt(r.screenHeight);out.writeInt(r.left);out.writeInt(r.top);out.writeInt(r.size);}out.flush();
    }
    public static Request readRequest(DataInputStream in)throws IOException{
        int t=type(in);if(t==IDLE)return Request.idleRequest();if(t!=FRAME)throw new IOException("Неверная команда захвата");
        Request r=new Request(in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),false);
        dimensions(r.screenWidth,r.screenHeight);if(r.size<0||r.size>MAX_EDGE||r.left<0||r.top<0)throw new IOException("Неверная область захвата");return r;
    }
    public static void idle(DataOutputStream out)throws IOException{header(out,IDLE);out.flush();}
    private static void dimensions(int w,int h)throws IOException{if(w<1||h<1||w>MAX_EDGE||h>MAX_EDGE||(long)w*h>MAX_PIXELS)throw new IOException("Недопустимый размер root-кадра");}
    private static void bounds(int sw,int sh,int left,int top,int w,int h)throws IOException{
        dimensions(sw,sh);dimensions(w,h);
        if(left<0||top<0||(long)left+w>sw||(long)top+h>sh)throw new IOException("Root-кадр за пределами экрана");
    }
    public static void frame(DataOutputStream out,Frame f)throws IOException{
        bounds(f.screenWidth,f.screenHeight,f.left,f.top,f.width,f.height);
        if(f.pixels==null||f.pixels.length!=f.width*f.height)throw new IOException("Неполный root-кадр");
        header(out,FRAME);out.writeInt(f.screenWidth);out.writeInt(f.screenHeight);out.writeInt(f.left);out.writeInt(f.top);out.writeInt(f.width);out.writeInt(f.height);out.writeInt(f.flags);out.writeInt(f.pixels.length);
        byte[] chunk=new byte[Math.min(4096,f.pixels.length)*4];
        for(int at=0;at<f.pixels.length;){int n=Math.min(chunk.length/4,f.pixels.length-at);for(int i=0;i<n;i++){int p=f.pixels[at+i],j=i*4;chunk[j]=(byte)(p>>>24);chunk[j+1]=(byte)(p>>>16);chunk[j+2]=(byte)(p>>>8);chunk[j+3]=(byte)p;}out.write(chunk,0,n*4);at+=n;}out.flush();
    }
    /** null is a heartbeat, never a substitute for a screenshot. */
    public static Frame readFrame(DataInputStream in)throws IOException{
        int t=type(in);if(t==IDLE)return null;if(t!=FRAME)throw new IOException("Неверный ответ захвата");
        int sw=in.readInt(),sh=in.readInt(),left=in.readInt(),top=in.readInt(),w=in.readInt(),h=in.readInt(),flags=in.readInt(),n=in.readInt();
        bounds(sw,sh,left,top,w,h);if(n!=w*h)throw new IOException("Неверная длина root-кадра");
        int[] pixels=new int[n];byte[] chunk=new byte[Math.min(4096,n)*4];
        for(int at=0;at<n;){int count=Math.min(chunk.length/4,n-at);in.readFully(chunk,0,count*4);for(int i=0;i<count;i++){int j=i*4;pixels[at+i]=((chunk[j]&255)<<24)|((chunk[j+1]&255)<<16)|((chunk[j+2]&255)<<8)|(chunk[j+3]&255);}at+=count;}
        return new Frame(sw,sh,left,top,w,h,flags,pixels);
    }
}
