package ru.hod.chess;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.util.function.Consumer;
import ru.hod.chess.core.BoardVision;

/** A frozen screenshot prevents animations moving underneath the selection grid. */
final class BoardSelector {
    private final WindowManager windows;private final FrameLayout root;private final Selection view;private final Bitmap bitmap;private boolean closed;
    BoardSelector(Context context,int[] pixels,int width,int height,boolean whiteBottom,Consumer<BoardVision.Region> done,Runnable cancelled){
        windows=context.getSystemService(WindowManager.class);bitmap=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);root=new FrameLayout(context);view=new Selection(context,bitmap);root.addView(view,new FrameLayout.LayoutParams(-1,-1));
        TextView label=Ui.bold(context,"Обведи доску от угла до угла\n"+(whiteBottom?"Белые снизу":"Чёрные снизу")+" · только 64 клетки",17,Ui.INK);Ui.pad(label,16);label.setBackground(Ui.shape(0xe6101512,Ui.dp(context,16)));FrameLayout.LayoutParams title=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);title.setMargins(Ui.dp(context,16),Ui.dp(context,48),Ui.dp(context,16),0);root.addView(label,title);
        LinearLayout controls=Ui.row(context);Ui.pad(controls,12);controls.setBackground(Ui.shape(Ui.BG,Ui.dp(context,20)));Button cancel=Ui.button(context,"Отмена",false,()->{close();cancelled.run();});Button confirm=Ui.button(context,"Готово",true,()->{BoardVision.Region region=view.region();if(region==null){Toast.makeText(context,"Сначала обведи доску размером не меньше 160 px",Toast.LENGTH_SHORT).show();return;}close();done.accept(region);});controls.addView(cancel,new LinearLayout.LayoutParams(0,Ui.dp(context,52),1));View space=new View(context);controls.addView(space,new LinearLayout.LayoutParams(Ui.dp(context,10),1));controls.addView(confirm,new LinearLayout.LayoutParams(0,Ui.dp(context,52),1));FrameLayout.LayoutParams bottom=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);bottom.setMargins(Ui.dp(context,16),0,Ui.dp(context,16),Ui.dp(context,40));root.addView(controls,bottom);
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);lp.gravity=Gravity.TOP|Gravity.LEFT;if(android.os.Build.VERSION.SDK_INT>=28)lp.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;windows.addView(root,lp);
    }
    void close(){if(closed)return;closed=true;try{windows.removeView(root);}catch(IllegalArgumentException ignored){}bitmap.recycle();}
    private static final class Selection extends View {
        final Bitmap image;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);float ax,ay,bx,by;boolean selected;
        Selection(Context c,Bitmap image){super(c);this.image=image;setContentDescription("Выделение шахматной доски");}
        RectF selection(){float size=Math.min(Math.abs(bx-ax),Math.abs(by-ay));float left=bx>=ax?ax:ax-size,top=by>=ay?ay:ay-size;return new RectF(left,top,left+size,top+size);}
        BoardVision.Region region(){if(!selected)return null;RectF r=selection();float sx=(float)image.getWidth()/getWidth(),sy=(float)image.getHeight()/getHeight();if(Math.abs(sx/sy-1)>.03)return null;BoardVision.Region region=new BoardVision.Region(Math.round(r.left*sx),Math.round(r.top*sy),Math.round(r.width()*sx));return region.inside(image.getWidth(),image.getHeight())?region:null;}
        @Override public boolean onTouchEvent(android.view.MotionEvent e){float x=Math.max(0,Math.min(getWidth()-1,e.getX())),y=Math.max(0,Math.min(getHeight()-1,e.getY()));switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:ax=bx=x;ay=by=y;selected=true;break;case MotionEvent.ACTION_MOVE:case MotionEvent.ACTION_UP:bx=x;by=y;break;default:return true;}invalidate();if(e.getActionMasked()==MotionEvent.ACTION_UP)performClick();return true;}
        @Override public boolean performClick(){super.performClick();return true;}
        @Override protected void onDraw(Canvas c){c.drawBitmap(image,null,new RectF(0,0,getWidth(),getHeight()),p);p.setColor(0x88000000);c.drawRect(0,0,getWidth(),getHeight(),p);if(!selected)return;RectF r=selection();c.save();c.clipRect(r);c.drawBitmap(image,null,new RectF(0,0,getWidth(),getHeight()),p);c.restore();p.setColor(Ui.ACCENT);p.setStrokeWidth(Ui.dp(getContext(),2));p.setStyle(Paint.Style.STROKE);c.drawRect(r,p);p.setStrokeWidth(Ui.dp(getContext(),1));p.setColor(0x99c2ef97);for(int i=1;i<8;i++){float k=i/8f;c.drawLine(r.left+r.width()*k,r.top,r.left+r.width()*k,r.bottom,p);c.drawLine(r.left,r.top+r.height()*k,r.right,r.top+r.height()*k,p);}p.setStyle(Paint.Style.FILL);for(float x:new float[]{r.left,r.right})for(float y:new float[]{r.top,r.bottom})c.drawCircle(x,y,Ui.dp(getContext(),5),p);}
    }
}
