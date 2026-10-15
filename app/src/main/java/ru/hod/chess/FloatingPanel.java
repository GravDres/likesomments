package ru.hod.chess;

import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import ru.hod.chess.core.*;

final class FloatingPanel {
    interface Actions {void calibrate();void manual();void pause(boolean value);void arrows(boolean value);void deeper();void stop();}
    private final Context c;private final WindowManager wm;private final WindowManager.LayoutParams lp;private final LinearLayout root,body;
    private final TextView status,score,move,details,variants,compactSummary;private final Button collapse,pause,arrowToggle;
    private boolean arrowsEnabled;
    private boolean compact,paused,closed;private float startX,startY;private int startLeft,startTop;private volatile Rect bounds=new Rect();
    FloatingPanel(Context c,Actions actions){this.c=c;wm=c.getSystemService(WindowManager.class);root=Ui.col(c);Ui.pad(root,12);root.setBackground(Ui.shape(Ui.CARD,Ui.dp(c,22)));root.setElevation(Ui.dp(c,16));
        LinearLayout title=Ui.row(c);TextView logo=Ui.bold(c,"♞  ХОД",18,Ui.ACCENT);title.addView(logo,new LinearLayout.LayoutParams(0,-2,1));collapse=small("−",()->setCompact(!compact));title.addView(collapse);Button close=small("×",actions::stop);title.addView(close);root.addView(title);
        compactSummary=Ui.bold(c,"Выдели начальную доску",14,Ui.INK);compactSummary.setVisibility(View.GONE);root.addView(compactSummary);
        body=Ui.col(c);Ui.gap(body,8);status=Ui.text(c,"Выдели начальную доску",12,Ui.MUTED);body.addView(status);Ui.gap(body,8);score=Ui.bold(c,"—",32,Ui.INK);body.addView(score);move=Ui.bold(c,"Готов к калибровке",16,Ui.INK);body.addView(move);Ui.gap(body,6);details=Ui.text(c,"Открой Chess.com и нажми «Доска»",11,Ui.MUTED);body.addView(details);Ui.gap(body,8);variants=Ui.text(c,"",12,Ui.MUTED);body.addView(variants);Ui.gap(body,10);
        LinearLayout tools=Ui.row(c);Button calibrate=small("Найти доску",actions::calibrate);calibrate.setOnLongClickListener(v->{actions.manual();return true;});tools.addView(calibrate,new LinearLayout.LayoutParams(0,Ui.dp(c,42),1));pause=small("Пауза",()->{paused=!paused;pauseText();actions.pause(paused);});tools.addView(pause,new LinearLayout.LayoutParams(0,Ui.dp(c,42),1));body.addView(tools);Ui.gap(body,6);
        arrowsEnabled=c.getSharedPreferences("hod",Context.MODE_PRIVATE).getBoolean("arrows",true);arrowToggle=small(arrowsEnabled?"Стрелка: вкл":"Стрелка: выкл",()->{arrowsEnabled=!arrowsEnabled;arrowToggleText();actions.arrows(arrowsEnabled);});body.addView(arrowToggle,new LinearLayout.LayoutParams(-1,Ui.dp(c,38)));Ui.gap(body,6);body.addView(small("Глубже · 8 секунд",actions::deeper),new LinearLayout.LayoutParams(-1,Ui.dp(c,38)));root.addView(body);
        lp=new WindowManager.LayoutParams(Ui.dp(c,258),-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);lp.gravity=Gravity.TOP|Gravity.LEFT;lp.x=Ui.dp(c,12);lp.y=Ui.dp(c,56);wm.addView(root,lp);root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateBounds());title.setOnTouchListener(this::drag);logo.setOnTouchListener(this::drag);
    }
    private Button small(String label,Runnable action){Button b=Ui.button(c,label,false,action);b.setTextSize(12);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(Ui.dp(c,8),0,Ui.dp(c,8),0);b.setLayoutParams(new LinearLayout.LayoutParams(-2,Ui.dp(c,38)));return b;}
    private void pauseText(){pause.setText(paused?"Продолжить":"Пауза");}
    private void arrowToggleText(){arrowToggle.setText(arrowsEnabled?"Стрелка: вкл":"Стрелка: выкл");}
    private boolean drag(View v,MotionEvent e){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:startX=e.getRawX();startY=e.getRawY();startLeft=lp.x;startTop=lp.y;return true;case MotionEvent.ACTION_MOVE:lp.x=startLeft+Math.round(e.getRawX()-startX);lp.y=startTop+Math.round(e.getRawY()-startY);clamp();wm.updateViewLayout(root,lp);updateBounds();return true;case MotionEvent.ACTION_UP:if(Math.abs(e.getRawX()-startX)+Math.abs(e.getRawY()-startY)<Ui.dp(c,6)&&compact)setCompact(false);v.performClick();return true;default:return false;}}
    private void clamp(){android.util.DisplayMetrics m=new android.util.DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(m);lp.x=Math.max(0,Math.min(m.widthPixels-root.getWidth(),lp.x));lp.y=Math.max(Ui.dp(c,30),Math.min(m.heightPixels-root.getHeight()-Ui.dp(c,20),lp.y));}
    private void updateBounds(){int[]xy=new int[2];root.getLocationOnScreen(xy);bounds=new Rect(xy[0],xy[1],xy[0]+root.getWidth(),xy[1]+root.getHeight());}
    boolean overlaps(BoardVision.Region r){Rect b=bounds;return b.left<r.left()+r.size()&&b.right>r.left()&&b.top<r.top()+r.size()&&b.bottom>r.top();}
    void setCompact(boolean value){compact=value;body.setVisibility(value?View.GONE:View.VISIBLE);compactSummary.setVisibility(value?View.VISIBLE:View.GONE);collapse.setText(value?"+":"−");lp.width=Ui.dp(c,value?230:258);wm.updateViewLayout(root,lp);}
    void placeOutside(BoardVision.Region r,int screenWidth,int screenHeight){
        setCompact(true);
        root.postOnAnimation(()->root.post(()->{if(closed)return;int margin=Ui.dp(c,12),pw=root.getWidth(),ph=root.getHeight();
            int bottom=screenHeight-Ui.dp(c,36),top=Ui.dp(c,32);
            if(bottom-r.top()-r.size()>=ph+margin){lp.x=Math.max(0,Math.min(screenWidth-pw,r.left()));lp.y=r.top()+r.size()+margin;}
            else if(r.top()-top>=ph+margin){lp.x=Math.max(0,Math.min(screenWidth-pw,r.left()));lp.y=r.top()-ph-margin;}
            else if(screenWidth-r.left()-r.size()>=pw+margin){lp.x=r.left()+r.size()+margin;lp.y=Math.max(top,r.top());}
            else if(r.left()>=pw+margin){lp.x=r.left()-pw-margin;lp.y=Math.max(top,r.top());}
            clamp();wm.updateViewLayout(root,lp);root.post(this::updateBounds);
        }));
    }
    void message(String message){if(closed)return;compactSummary.setText(message);status.setText(message);status.setTextColor(Ui.MUTED);score.setText("—");move.setText("Жду точную позицию");details.setText("Перетащи окно за пределы доски");variants.setText("");}
    void thinking(Position p){compactSummary.setText((p.white?"Ход белых":"Ход чёрных")+" · считаю…");status.setText("●  Позиция подтверждена");status.setTextColor(Ui.ACCENT);score.setText("…");move.setText(p.white?"Ход белых":"Ход чёрных");details.setText("Расчёт вариантов на устройстве");variants.setText("");}
    void show(Position p,Analysis a,float confidence){if(closed)return;status.setText("●  "+(p.white?"Ход белых":"Ход чёрных")+" · совпадение "+Math.round(confidence*100)+"%");status.setTextColor(Ui.ACCENT);score.setText(a.score());Move best=a.bestMove();compactSummary.setText(best==null?"Партия завершена":Position.square(best.from())+" → "+Position.square(best.to())+(best.promotion()!='\0'?"="+best.promotion():"")+"   ·   "+a.score()+"\n"+(p.white?"Белые":"Чёрные")+" · глубина "+a.depth());move.setText(best==null?"Нет легальных ходов":p.describe(best));details.setText(a.engine()+" · глубина "+a.depth()+"\nОценка всегда за белых");StringBuilder s=new StringBuilder();for(Analysis.Line line:a.lines()){s.append(line.rank()).append(". ");int n=0;for(Move m:line.moves()){if(n++>=4)break;s.append(m.uci()).append(' ');}s.append('\n');}variants.setText(s.toString().trim());}
    void terminal(Position p){compactSummary.setText(p.inCheck(p.white)?"Мат · партия завершена":"Пат · ничья");status.setText("Партия завершена");score.setText(p.inCheck(p.white)?"МАТ":"½–½");move.setText(p.inCheck(p.white)?(p.white?"Чёрные выиграли":"Белые выиграли"):"Пат");details.setText("Для новой партии нажми «Доска»");variants.setText("");}
    void draw(String reason){compactSummary.setText("Ничья · "+reason);status.setText("Партия завершена");score.setText("½–½");move.setText("Ничья");details.setText(reason);variants.setText("");}
    void hide(boolean hidden){root.setVisibility(hidden?View.INVISIBLE:View.VISIBLE);}
    void close(){if(closed)return;closed=true;try{wm.removeView(root);}catch(IllegalArgumentException ignored){}}
}
