package ru.hod.chess;

import android.content.*;
import android.graphics.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import ru.hod.chess.core.*;

final class FloatingPanel {
    interface Actions {void calibrate();void manual();void recover();void verify();void review();void side(PlayerSide side);void strength(EngineStrength value);void engine(EngineMode value);void model(MaiaModel value);void network(MaiaNetwork value);void profile(TimeControlProfile value);void newGame();void pause(boolean value);void arrows(boolean value);void deeper();void stop();}
    private final Context c;private final WindowManager wm;private final WindowManager.LayoutParams lp;
    private final FrameLayout window;private final TextView bubble;
    private final LinearLayout root;private final ScrollView bodyScroll;
    private final TextView status,score,move,details,variants,compactSummary,health,captureDot;
    private boolean pulse;
    private final Button collapse,pause,arrowToggle,sideButton,quickSide,lockButton,strengthButton;
    private final Button engineButton,modelButton,networkButton,profileButton,deeperButton;
    private final SharedPreferences prefs;
    private boolean arrowsEnabled,locked,compact=true,minimized,paused,closed,dragging;
    private PlayerSide side;private float startX,startY;private int startLeft,startTop;
    private android.app.AlertDialog strengthDialog;
    private volatile Rect bounds=new Rect();private BoardVision.Region board;private String lastMessage;
    FloatingPanel(Context c,Actions actions){
        this.c=c;prefs=c.getSharedPreferences("hod",Context.MODE_PRIVATE);wm=c.getSystemService(WindowManager.class);
        side=prefs.getBoolean("playerWhite",true)?PlayerSide.WHITE:PlayerSide.BLACK;locked=prefs.getBoolean("panelLocked",true);
        compact=prefs.getBoolean("panelRestoreCompact",true);
        root=Ui.col(c);Ui.pad(root,10);root.setBackground(Ui.shape(Ui.CARD,Ui.dp(c,20)));root.setElevation(Ui.dp(c,12));
        window=new FrameLayout(c);window.addView(root,new FrameLayout.LayoutParams(-1,-1));
        bubble=Ui.bold(c,"♞",27,Ui.ACCENT);bubble.setGravity(Gravity.CENTER);bubble.setBackground(Ui.shape(Ui.CARD,Ui.dp(c,24)));bubble.setElevation(Ui.dp(c,8));bubble.setContentDescription("ХОД: нажми, чтобы открыть окно. Перетащи, чтобы переместить кнопку.");bubble.setTooltipText("ХОД · открыть окно");bubble.setOnClickListener(v->setMinimized(false));bubble.setOnTouchListener(this::dragBubble);bubble.setVisibility(View.GONE);window.addView(bubble,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout title=Ui.row(c);captureDot=Ui.text(c,"●",10,Ui.ACCENT);captureDot.setGravity(Gravity.CENTER);title.addView(captureDot,new LinearLayout.LayoutParams(Ui.dp(c,14),Ui.dp(c,38)));TextView logo=Ui.bold(c,"♞",19,Ui.ACCENT);logo.setGravity(Gravity.CENTER);logo.setContentDescription("ХОД. Заголовок для переноса окна");title.addView(logo,new LinearLayout.LayoutParams(Ui.dp(c,26),Ui.dp(c,38)));
        quickSide=small(compactSideText(),()->actions.side(side.opposite()));quickSide.setSingleLine(true);quickSide.setEllipsize(TextUtils.TruncateAt.END);quickSide.setTextSize(12);quickSide.setTextColor(Ui.ACCENT);quickSide.setPadding(Ui.dp(c,4),0,Ui.dp(c,4),0);title.addView(quickSide,new LinearLayout.LayoutParams(0,Ui.dp(c,38),1));
        collapse=small("+",()->setCompact(!compact));title.addView(collapse);Button refresh=small("↻",actions::recover);refresh.setContentDescription("Обновить доску и проверить текущую или новую партию");title.addView(refresh);Button minimize=small("↘",()->setMinimized(true));minimize.setContentDescription("Свернуть окно в маленькую кнопку");minimize.setTooltipText("Свернуть в кнопку");title.addView(minimize);root.addView(title);
        compactSummary=Ui.bold(c,"Ищу начальную доску…",13,Ui.INK);compactSummary.setLines(2);compactSummary.setEllipsize(TextUtils.TruncateAt.END);compactSummary.setGravity(Gravity.CENTER_VERTICAL);root.addView(compactSummary,new LinearLayout.LayoutParams(-1,Ui.dp(c,48)));
        LinearLayout quick=Ui.row(c);Button auto=small("Автопоиск",actions::verify);auto.setOnLongClickListener(v->{actions.manual();return true;});auto.setContentDescription("Найти доску автоматически. Удерживать для ручной рамки");quick.addView(auto,new LinearLayout.LayoutParams(0,Ui.dp(c,34),1));quick.addView(small("Позиция",actions::review),new LinearLayout.LayoutParams(0,Ui.dp(c,34),1));root.addView(quick);
        compactSummary.setOnClickListener(v->{if(lastMessage!=null){android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(c).setTitle("Состояние помощника").setMessage(lastMessage).setPositiveButton("Понятно",null).create();dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);dialog.show();}});
        bodyScroll=new ScrollView(c);bodyScroll.setFillViewport(false);bodyScroll.setVisibility(View.GONE);root.addView(bodyScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=Ui.col(c);bodyScroll.addView(body);
        sideButton=small(sideText(),()->actions.side(side.opposite()));sideButtonText();body.addView(sideButton,new LinearLayout.LayoutParams(-1,Ui.dp(c,40)));Ui.gap(body,6);
        engineButton=small("Движок",()->pick("Движок",java.util.Arrays.stream(EngineMode.values()).map(EngineMode::label).toArray(String[]::new),EngineFactory.settings(c).mode().ordinal(),i->actions.engine(EngineMode.values()[i])));body.addView(engineButton,new LinearLayout.LayoutParams(-1,Ui.dp(c,38)));Ui.gap(body,6);
        modelButton=small("Уровень Maia",()->pick("Уровень модели, не измеренный Elo",java.util.Arrays.stream(MaiaModel.values()).map(MaiaModel::label).toArray(String[]::new),EngineFactory.settings(c).model().ordinal(),i->actions.model(MaiaModel.values()[i])));body.addView(modelButton,new LinearLayout.LayoutParams(-1,Ui.dp(c,38)));Ui.gap(body,6);
        networkButton=small("Сеть Maia",()->pick("Сеть Maia-3",java.util.Arrays.stream(MaiaNetwork.values()).map(MaiaNetwork::label).toArray(String[]::new),EngineFactory.settings(c).network().ordinal(),i->actions.network(MaiaNetwork.values()[i])));body.addView(networkButton,new LinearLayout.LayoutParams(-1,Ui.dp(c,38)));Ui.gap(body,6);
        profileButton=small("Контроль времени",()->pick("Контроль времени",java.util.Arrays.stream(TimeControlProfile.values()).map(TimeControlProfile::label).toArray(String[]::new),EngineFactory.settings(c).profile().ordinal(),i->actions.profile(TimeControlProfile.values()[i])));body.addView(profileButton,new LinearLayout.LayoutParams(-1,Ui.dp(c,38)));Ui.gap(body,6);
        strengthButton=small(strengthText(),()->{
            if(closed||strengthDialog!=null)return;
            EngineStrength[] levels=EngineStrength.values();String[] labels=new String[levels.length];for(int i=0;i<levels.length;i++)labels[i]=levels[i].label();
            android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(c).setTitle("Сила подсказок Stockfish").setSingleChoiceItems(labels,engineStrength().ordinal(),(d,index)->{actions.strength(levels[index]);d.dismiss();}).setNegativeButton("Закрыть",null).create();strengthDialog=dialog;dialog.setOnDismissListener(d->{if(strengthDialog==dialog)strengthDialog=null;});dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);dialog.show();
        });body.addView(strengthButton,new LinearLayout.LayoutParams(-1,Ui.dp(c,38)));Ui.gap(body,6);
        status=Ui.text(c,"Ищу начальную доску…",12,Ui.MUTED);status.setMaxLines(8);body.addView(status);Ui.gap(body,6);
        score=Ui.bold(c,"—",28,Ui.INK);body.addView(score,new LinearLayout.LayoutParams(-1,Ui.dp(c,38)));
        move=Ui.bold(c,"Жду точную позицию",15,Ui.INK);move.setMaxLines(2);body.addView(move,new LinearLayout.LayoutParams(-1,Ui.dp(c,44)));
        details=Ui.text(c,"",11,Ui.MUTED);details.setMaxLines(3);body.addView(details,new LinearLayout.LayoutParams(-1,Ui.dp(c,48)));
        variants=Ui.text(c,"",11,Ui.MUTED);variants.setMaxLines(3);body.addView(variants);Ui.gap(body,8);
        LinearLayout tools=Ui.row(c);Button calibrate=small("Найти доску",actions::calibrate);calibrate.setOnLongClickListener(v->{actions.manual();return true;});tools.addView(calibrate,new LinearLayout.LayoutParams(0,Ui.dp(c,40),1));
        pause=small("Пауза",()->{paused=!paused;pauseText();actions.pause(paused);});tools.addView(pause,new LinearLayout.LayoutParams(0,Ui.dp(c,40),1));body.addView(tools);Ui.gap(body,6);
        arrowsEnabled=prefs.getBoolean("arrows",true);arrowToggle=small(arrowText(),()->{arrowsEnabled=!arrowsEnabled;arrowToggleText();actions.arrows(arrowsEnabled);});body.addView(arrowToggle,new LinearLayout.LayoutParams(-1,Ui.dp(c,38)));Ui.gap(body,6);
        lockButton=small(lockText(),()->{locked=!locked;prefs.edit().putBoolean("panelLocked",locked).apply();lockButtonText();});body.addView(lockButton,new LinearLayout.LayoutParams(-1,Ui.dp(c,38)));Ui.gap(body,6);
        deeperButton=small("Глубже · 8 секунд",actions::deeper);body.addView(deeperButton,new LinearLayout.LayoutParams(-1,Ui.dp(c,38)));Ui.gap(body,6);
        body.addView(small("Новая партия",actions::newGame),new LinearLayout.LayoutParams(-1,Ui.dp(c,38)));Ui.gap(body,6);
        body.addView(small("Остановить помощника",actions::stop),new LinearLayout.LayoutParams(-1,Ui.dp(c,38)));
        health=Ui.text(c,"Захват: подключаю…",10,Ui.MUTED);health.setSingleLine(true);health.setEllipsize(TextUtils.TruncateAt.END);root.addView(health,new LinearLayout.LayoutParams(-1,Ui.dp(c,20)));
        lp=new WindowManager.LayoutParams(Ui.dp(c,230),Ui.dp(c,160),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.LEFT;lp.x=prefs.getInt("panelX",Ui.dp(c,12));lp.y=prefs.getInt("panelY",Ui.dp(c,56));clamp();wm.addView(window,lp);
        window.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateBounds());title.setOnTouchListener(this::drag);logo.setOnTouchListener(this::drag);
        refreshEngineSettings();
        setCompact(compact);if(prefs.getBoolean("panelMinimized",false))setMinimized(true);
    }
    private Button small(String label,Runnable action){Button b=Ui.button(c,label,false,action);b.setTextSize(12);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(Ui.dp(c,8),0,Ui.dp(c,8),0);b.setLayoutParams(new LinearLayout.LayoutParams(-2,Ui.dp(c,38)));return b;}
    private void pick(String title,String[] labels,int selected,java.util.function.IntConsumer picked){
        if(closed||strengthDialog!=null)return;android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(c).setTitle(title).setSingleChoiceItems(labels,selected,(d,index)->{picked.accept(index);d.dismiss();}).setNegativeButton("Закрыть",null).create();strengthDialog=dialog;dialog.setOnDismissListener(d->{if(strengthDialog==dialog)strengthDialog=null;});dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);dialog.show();
    }
    void refreshEngineSettings(){EngineSettings s=EngineFactory.settings(c);engineButton.setText(s.mode()==EngineMode.MAIA_TACTICAL?"Maia-3 + проверка":s.mode().usesMaia()?"Движок: Maia-3":"Движок: Stockfish");modelButton.setText("Уровень Maia: "+s.model().rating());networkButton.setText(s.network().label());profileButton.setText(s.profile().label());setStrength(s.strength());modelButton.setVisibility(s.mode().usesMaia()?View.VISIBLE:View.GONE);networkButton.setVisibility(s.mode().usesMaia()?View.VISIBLE:View.GONE);strengthButton.setVisibility(s.mode()==EngineMode.STOCKFISH?View.VISIBLE:View.GONE);deeperButton.setText(s.mode().usesMaia()?"Повторить подсказку":"Глубже · 8 секунд");}
    private void pauseText(){pause.setText(paused?"Продолжить":"Пауза");}
    private void arrowToggleText(){arrowToggle.setText(arrowText());}
    private void lockButtonText(){lockButton.setText(lockText());}
    void setSide(PlayerSide value){side=value;sideButtonText();}
    private EngineStrength engineStrength(){return EngineStrength.fromPreference(prefs.getInt(EngineStrength.PREFERENCE,EngineStrength.DEFAULT_SKILL));}
    private String strengthText(){return "Сила: "+engineStrength().skill()+"/20 · изменить";}
    void setStrength(EngineStrength value){strengthButton.setText("Сила: "+value.skill()+"/20 · изменить");}
    private String sideText(){return "Я играю "+side.label()+" · сменить";}
    private String compactSideText(){return side==PlayerSide.WHITE?"♙ Белые ⇄":"♟ Чёрные ⇄";}
    private void sideButtonText(){sideButton.setText(sideText());quickSide.setText(compactSideText());quickSide.setContentDescription("Ты играешь "+side.label()+". Нажми, чтобы играть "+side.opposite().label());}
    private String arrowText(){return arrowsEnabled?"Стрелка: вкл":"Стрелка: выкл";}
    private String lockText(){return locked?"Окно закреплено · разрешить перенос":"Окно можно двигать · закрепить";}
    private void setMinimized(boolean value){
        if(closed||minimized==value)return;
        if(strengthDialog!=null){strengthDialog.dismiss();strengthDialog=null;}
        rememberPosition();minimized=value;
        prefs.edit().putBoolean("panelMinimized",value).putBoolean("panelRestoreCompact",compact).apply();
        root.setVisibility(value?View.GONE:View.VISIBLE);bubble.setVisibility(value?View.VISIBLE:View.GONE);
        if(value){
            lp.width=lp.height=Ui.dp(c,48);lp.x=prefs.getInt("bubbleX",lp.x);lp.y=prefs.getInt("bubbleY",lp.y);clamp();
            if(board!=null&&rectOverlaps(board)){android.util.DisplayMetrics m=metrics();positionOutside(board,m.widthPixels,m.heightPixels);}
            wm.updateViewLayout(window,lp);window.post(this::updateBounds);
        }else{
            lp.x=prefs.getInt("panelX",Ui.dp(c,12));lp.y=prefs.getInt("panelY",Ui.dp(c,56));setCompact(compact);
        }
        rememberPosition();
    }
    private boolean dragBubble(View v,MotionEvent e){
        if(!minimized||closed)return false;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:startX=e.getRawX();startY=e.getRawY();startLeft=lp.x;startTop=lp.y;dragging=false;return true;
            case MotionEvent.ACTION_MOVE:
                if(!dragging&&Math.hypot(e.getRawX()-startX,e.getRawY()-startY)<ViewConfiguration.get(c).getScaledTouchSlop())return true;
                dragging=true;lp.x=startLeft+Math.round(e.getRawX()-startX);lp.y=startTop+Math.round(e.getRawY()-startY);clamp();wm.updateViewLayout(window,lp);updateBounds();return true;
            case MotionEvent.ACTION_UP:
                boolean moved=dragging;dragging=false;if(moved)rememberPosition();else v.performClick();return true;
            case MotionEvent.ACTION_CANCEL:if(dragging)rememberPosition();dragging=false;return true;
            default:return true;
        }
    }
    private boolean drag(View v,MotionEvent e){
        if(locked)return true;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:startX=e.getRawX();startY=e.getRawY();startLeft=lp.x;startTop=lp.y;dragging=false;return true;
            case MotionEvent.ACTION_MOVE:
                if(!dragging&&Math.hypot(e.getRawX()-startX,e.getRawY()-startY)<ViewConfiguration.get(c).getScaledTouchSlop())return true;
                dragging=true;lp.x=startLeft+Math.round(e.getRawX()-startX);lp.y=startTop+Math.round(e.getRawY()-startY);clamp();wm.updateViewLayout(window,lp);updateBounds();return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:if(dragging)rememberPosition();dragging=false;v.performClick();return true;
            default:return false;
        }
    }
    private android.util.DisplayMetrics metrics(){android.util.DisplayMetrics m=new android.util.DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(m);return m;}
    private void clamp(){android.util.DisplayMetrics m=metrics();lp.x=Math.max(0,Math.min(m.widthPixels-lp.width,lp.x));lp.y=Math.max(Ui.dp(c,30),Math.min(m.heightPixels-lp.height-Ui.dp(c,20),lp.y));}
    private void rememberPosition(){prefs.edit().putInt(minimized?"bubbleX":"panelX",lp.x).putInt(minimized?"bubbleY":"panelY",lp.y).apply();}
    private void updateBounds(){if(closed)return;int[]xy=new int[2];window.getLocationOnScreen(xy);bounds=new Rect(xy[0],xy[1],xy[0]+window.getWidth(),xy[1]+window.getHeight());}
    boolean overlaps(BoardVision.Region r){Rect b=bounds;return b.left<r.left()+r.size()&&b.right>r.left()&&b.top<r.top()+r.size()&&b.bottom>r.top();}
    void setCompact(boolean value){
        // Automatic search and capture recovery may request compact mode. They
        // must never expand a bubble the user has deliberately minimized.
        if(closed||minimized)return;compact=value;bodyScroll.setVisibility(value?View.GONE:View.VISIBLE);compactSummary.setVisibility(value?View.VISIBLE:View.GONE);collapse.setText(value?"+":"−");
        android.util.DisplayMetrics m=metrics();lp.width=Ui.dp(c,value?230:258);lp.height=Ui.dp(c,160);
        if(!value){int space=m.heightPixels-Ui.dp(c,80);if(board!=null)space=Math.max(board.top()-Ui.dp(c,44),m.heightPixels-board.top()-board.size()-Ui.dp(c,44));lp.height=Math.min(Ui.dp(c,520),Math.max(Ui.dp(c,230),space));}
        clamp();if(board!=null&&rectOverlaps(board))positionOutside(board,m.widthPixels,m.heightPixels);wm.updateViewLayout(window,lp);window.post(this::updateBounds);
    }
    private boolean rectOverlaps(BoardVision.Region r){return lp.x<r.left()+r.size()&&lp.x+lp.width>r.left()&&lp.y<r.top()+r.size()&&lp.y+lp.height>r.top();}
    private void positionOutside(BoardVision.Region r,int width,int height){
        int margin=Ui.dp(c,10),top=Ui.dp(c,32),bottom=height-Ui.dp(c,30);
        if(bottom-r.top()-r.size()>=lp.height+margin){lp.x=Math.max(0,Math.min(width-lp.width,r.left()));lp.y=r.top()+r.size()+margin;}
        else if(r.top()-top>=lp.height+margin){lp.x=Math.max(0,Math.min(width-lp.width,r.left()));lp.y=r.top()-lp.height-margin;}
        else if(width-r.left()-r.size()>=lp.width+margin){lp.x=r.left()+r.size()+margin;lp.y=Math.max(top,r.top());}
        else if(r.left()>=lp.width+margin){lp.x=r.left()-lp.width-margin;lp.y=Math.max(top,r.top());}
        clamp();
    }
    void placeOutside(BoardVision.Region r,int width,int height){
        if(closed)return;board=r;setCompact(true);if(rectOverlaps(r)){positionOutside(r,width,height);wm.updateViewLayout(window,lp);}rememberPosition();window.post(this::updateBounds);
    }
    void message(String text){if(closed||text.equals(lastMessage))return;lastMessage=text;compactSummary.setText(text.length()>92?text.substring(0,64)+"…\nНажми текст: подробности":text);status.setText(text);status.setTextColor(Ui.MUTED);score.setText("—");move.setText("Жду точную позицию");details.setText("Ты играешь "+side.label()+" · история сохраняется");variants.setText("");}
    void waiting(PlayerSide player,int plies){if(closed)return;lastMessage=null;compactSummary.setText("Ты играешь "+player.label()+"\nХод соперника · жду");status.setText("●  Ход соперника");status.setTextColor(Ui.MUTED);score.setText("—");move.setText("Жду ход соперника");details.setText("История: "+plies+" полуходов · подсказки только тебе");variants.setText("");}
    void thinking(Position p){if(closed)return;lastMessage=null;compactSummary.setText("Твой ход · считаю…\nТы играешь "+side.label());status.setText("●  Позиция подтверждена");status.setTextColor(Ui.ACCENT);score.setText("…");move.setText(EngineFactory.settings(c).label()+" · считаю…");details.setText(EngineFactory.settings(c).profile().label()+" · локальный расчёт");variants.setText("");}
    void show(Position p,Analysis a,float confidence,boolean complete,int plies){
        if(closed||!side.ownsTurn(p))return;lastMessage=null;
        status.setText("●  "+(complete?"Готово":"Считаю")+" · совпадение "+Math.round(confidence*100)+"%");status.setTextColor(Ui.ACCENT);score.setText(a.score());Move best=a.bestMove();
        compactSummary.setText(best==null?"Партия завершена":Position.square(best.from())+" → "+Position.square(best.to())+(best.promotion()!='\0'?"="+best.promotion():"")+"   ·   "+a.score()+"\n"+(complete?"Готово":"Считаю…")+(EngineFactory.settings(c).mode()==EngineMode.MAIA_TACTICAL?(a.engine().contains("ход заменён")?" · ход заменён":" · проверено"):(a.policyProbability()==null?" · глубина "+a.depth():" · Maia "+EngineFactory.settings(c).model().rating())));
        move.setText(best==null?"Нет легальных ходов":p.describe(best));details.setText(a.policyProbability()!=null?a.engine()+"\nВероятность выбора хода, не оценка позиции":a.engine()+" · глубина "+a.depth()+"\nИстория: "+plies+" полуходов · оценка за белых");
        StringBuilder lines=new StringBuilder();for(Analysis.Line line:a.lines()){lines.append(line.rank()).append(". ");int n=0;for(Move m:line.moves()){if(n++>=4)break;lines.append(m.uci()).append(' ');}lines.append('\n');}variants.setText(lines.toString().trim());
    }
    void terminal(Position p){if(closed)return;compactSummary.setText(p.inCheck(p.white)?"Мат · партия завершена":"Пат · ничья");status.setText("Партия завершена");score.setText(p.inCheck(p.white)?"МАТ":"½–½");move.setText(p.inCheck(p.white)?(p.white?"Чёрные выиграли":"Белые выиграли"):"Пат");details.setText("Для следующей игры нажми «Новая партия»");variants.setText("");}
    void draw(String reason){if(closed)return;compactSummary.setText("Ничья · "+reason);status.setText("Партия завершена");score.setText("½–½");move.setText("Ничья");details.setText(reason);variants.setText("");}
    void health(String text){if(!closed&&!text.contentEquals(health.getText()))health.setText(text);}
    void capturePulse(){if(!closed){pulse=!pulse;captureDot.setTextColor(pulse?Ui.ACCENT:0xff91b755);bubble.setTextColor(pulse?Ui.ACCENT:0xff91b755);}}
    void ensureAttached(){if(closed)return;if(!window.isAttachedToWindow()){clamp();try{wm.addView(window,lp);}catch(IllegalStateException|IllegalArgumentException e){try{wm.removeViewImmediate(window);}catch(IllegalArgumentException ignored){}wm.addView(window,lp);}window.post(this::updateBounds);}window.setVisibility(View.VISIBLE);}
    void prepareSearch(){setCompact(true);if(board==null){lp.x=Ui.dp(c,8);lp.y=Ui.dp(c,32);clamp();wm.updateViewLayout(window,lp);window.post(this::updateBounds);}message("Ищу доску · окно остаётся на экране");}
    void searchCorner(int corner){
        if(closed)return;setCompact(true);android.util.DisplayMetrics m=metrics();
        lp.x=(corner&1)==0?Ui.dp(c,8):m.widthPixels-lp.width-Ui.dp(c,8);
        lp.y=corner<2?Ui.dp(c,32):m.heightPixels-lp.height-Ui.dp(c,32);
        clamp();wm.updateViewLayout(window,lp);window.post(this::updateBounds);
    }
    void setPaused(boolean value){paused=value;pauseText();}
    void close(){if(closed)return;closed=true;if(strengthDialog!=null){strengthDialog.dismiss();strengthDialog=null;}try{wm.removeView(window);}catch(IllegalArgumentException ignored){}}
}
