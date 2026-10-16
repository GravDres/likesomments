package ru.hod.chess.core;

/** Uniform source-to-view mapping, including inset or letterboxed overlay windows. */
public final class ImageViewport {
    public record Point(float x,float y) {}
    public record Rect(float left,float top,float right,float bottom) {
        public float width(){return right-left;}
        public float height(){return bottom-top;}
    }
    private final int sourceWidth,sourceHeight;
    private final float scale,left,top;
    public ImageViewport(int sourceWidth,int sourceHeight,int viewWidth,int viewHeight){
        if(sourceWidth<=0||sourceHeight<=0||viewWidth<=0||viewHeight<=0)throw new IllegalArgumentException("Некорректный размер изображения");
        this.sourceWidth=sourceWidth;this.sourceHeight=sourceHeight;
        scale=Math.min(viewWidth/(float)sourceWidth,viewHeight/(float)sourceHeight);
        left=(viewWidth-sourceWidth*scale)/2;top=(viewHeight-sourceHeight*scale)/2;
    }
    public Rect image(){return new Rect(left,top,left+sourceWidth*scale,top+sourceHeight*scale);}
    public Point toView(float x,float y){return new Point(left+x*scale,top+y*scale);}
    public Rect toView(float x,float y,float width,float height){Point a=toView(x,y),b=toView(x+width,y+height);return new Rect(a.x(),a.y(),b.x(),b.y());}
    public Point toSource(float x,float y){return new Point(Math.max(0,Math.min(sourceWidth,(x-left)/scale)),Math.max(0,Math.min(sourceHeight,(y-top)/scale)));}
    public boolean contains(float x,float y){Rect r=image();return x>=r.left()&&x<=r.right()&&y>=r.top()&&y<=r.bottom();}
}
