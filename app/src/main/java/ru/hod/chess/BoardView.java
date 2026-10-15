package ru.hod.chess;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import ru.hod.chess.core.*;

final class BoardView extends View {
    private char[] board=Position.start().pieces();private java.util.function.IntConsumer onSquare;private Move move;private boolean whiteBottom=true;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    BoardView(Context c){super(c);setContentDescription("Шахматная доска");setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
    void show(Position position,Move move,boolean whiteBottom){this.board=position.pieces();this.move=move;this.whiteBottom=whiteBottom;invalidate();}
    void showPieces(char[] pieces,boolean whiteBottom,java.util.function.IntConsumer listener){this.board=pieces.clone();this.move=null;this.whiteBottom=whiteBottom;this.onSquare=listener;invalidate();}
    @Override public boolean onTouchEvent(android.view.MotionEvent event){if(onSquare==null)return super.onTouchEvent(event);if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP){int col=Math.min(7,Math.max(0,(int)(event.getX()*8/getWidth()))),row=Math.min(7,Math.max(0,(int)(event.getY()*8/getWidth())));onSquare.accept(whiteBottom?row*8+col:63-row*8-col);performClick();}return true;}
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w);setMeasuredDimension(width,width);}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float cell=getWidth()/8f;String pieces="KQRBNPkqrbnp",glyphs="♚♛♜♝♞♟♚♛♜♝♞♟";
        c.save();Path clip=new Path();clip.addRoundRect(new RectF(0,0,getWidth(),getWidth()),Ui.dp(getContext(),12),Ui.dp(getContext(),12),Path.Direction.CW);c.clipPath(clip);
        for(int d=0;d<64;d++){int logical=whiteBottom?d:63-d;int row=d/8,col=d%8;p.setColor((row+col)%2==0?0xffe3e8d5:0xff76945d);c.drawRect(col*cell,row*cell,(col+1)*cell,(row+1)*cell,p);
            if(move!=null&&(logical==move.from()||logical==move.to())){p.setColor(0x66d5ed65);c.drawRect(col*cell,row*cell,(col+1)*cell,(row+1)*cell,p);}
            char piece=board[logical];int index=pieces.indexOf(piece);if(index>=0){p.setTextSize(cell*.79f);p.setTypeface(Typeface.create("serif",Typeface.NORMAL));p.setTextAlign(Paint.Align.CENTER);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(cell*.035f);p.setColor(Position.isWhite(piece)?0xff283629:0xffd8e2cc);float y=row*cell+cell*.80f;c.drawText(glyphs.substring(index,index+1),col*cell+cell/2,y,p);p.setStyle(Paint.Style.FILL);p.setColor(Position.isWhite(piece)?0xfffaf9ed:0xff25352a);c.drawText(glyphs.substring(index,index+1),col*cell+cell/2,y,p);}
        }
        if(move!=null){int from=whiteBottom?move.from():63-move.from(),to=whiteBottom?move.to():63-move.to();float x1=(from%8+.5f)*cell,y1=(from/8+.5f)*cell,x2=(to%8+.5f)*cell,y2=(to/8+.5f)*cell;double angle=Math.atan2(y2-y1,x2-x1);float endX=x2-(float)Math.cos(angle)*cell*.13f,endY=y2-(float)Math.sin(angle)*cell*.13f;p.setColor(0xeef0b958);p.setStrokeWidth(cell*.11f);p.setStrokeCap(Paint.Cap.ROUND);c.drawLine(x1,y1,endX,endY,p);Path head=new Path();head.moveTo(x2,y2);head.lineTo(x2-(float)Math.cos(angle-.5)*cell*.43f,y2-(float)Math.sin(angle-.5)*cell*.43f);head.lineTo(x2-(float)Math.cos(angle+.5)*cell*.43f,y2-(float)Math.sin(angle+.5)*cell*.43f);head.close();c.drawPath(head,p);}
        c.restore();
    }
}
