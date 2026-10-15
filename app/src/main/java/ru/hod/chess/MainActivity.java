package ru.hod.chess;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import ru.hod.chess.core.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int CAPTURE=42,OVERLAY=43,NOTIFICATIONS=44;
    private TextView permissions;
    private final ExecutorService demo=Executors.newSingleThreadExecutor();
    private volatile ChessEngine demoEngine;
    private AlertDialog analysisDialog;
    private android.content.SharedPreferences prefs;
    private boolean requesting;
    @Override public void onCreate(Bundle state){super.onCreate(state);prefs=getSharedPreferences("hod",MODE_PRIVATE);build();}
    private void build(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Ui.BG);
        LinearLayout page=Ui.col(this);Ui.pad(page,24);scroll.addView(page);setContentView(scroll);
        page.setOnApplyWindowInsetsListener((v,insets)->{int top,bottom;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());top=bars.top;bottom=bars.bottom;}else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}v.setPadding(Ui.dp(this,24),top+Ui.dp(this,20),Ui.dp(this,24),bottom+Ui.dp(this,20));return insets;});
        LinearLayout header=Ui.row(this);TextView logo=Ui.bold(this,"♞  ХОД",25,Ui.INK);logo.setLetterSpacing(.12f);header.addView(logo,new LinearLayout.LayoutParams(0,-2,1));TextView tag=Ui.bold(this,"ANDROID",10,Ui.ACCENT);tag.setLetterSpacing(.14f);header.addView(tag);page.addView(header);Ui.gap(page,32);
        page.addView(Ui.bold(this,"Твоя позиция.\nТвой следующий ход.",34,Ui.INK));Ui.gap(page,12);
        TextView intro=Ui.text(this,"Подсказка прямо на доске.\nСтрелка · плавающее окно · анализ офлайн",15,Ui.MUTED);intro.setLineSpacing(Ui.dp(this,4),1);page.addView(intro);Ui.gap(page,24);
        LinearLayout boardCard=Ui.card(this);LinearLayout status=Ui.row(this);status.addView(Ui.bold(this,"●  ГОТОВ К АНАЛИЗУ",11,Ui.ACCENT),new LinearLayout.LayoutParams(0,-2,1));status.addView(Ui.text(this,"01 / ПОЗИЦИЯ",10,Ui.MUTED));boardCard.addView(status);Ui.gap(boardCard,16);
        BoardView board=new BoardView(this);Position preview=Position.start().play(Move.parse("e2e4")).play(Move.parse("e7e5"));board.show(preview,Move.parse("g1f3"),true);boardCard.addView(board,new LinearLayout.LayoutParams(-1,-2));Ui.gap(boardCard,14);boardCard.addView(Ui.text(this,"Пример подсказки: конь g1 → f3",13,Ui.MUTED));page.addView(boardCard);Ui.gap(page,20);
        page.addView(Ui.bold(this,"Ритм анализа",18,Ui.INK));Ui.gap(page,10);
        RadioGroup modes=new RadioGroup(this);modes.setOrientation(LinearLayout.VERTICAL);String[] labels={"Экономный   ·   0,6 с / 1 поток","Баланс   ·   1,5 с / 2 потока","Глубокий   ·   4 с / 4 потока"};int[] times={600,1500,4000};for(int i=0;i<labels.length;i++){RadioButton b=new RadioButton(this);b.setText(labels[i]);b.setTextColor(Ui.INK);b.setTextSize(14);b.setId(100+i);b.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));modes.addView(b);if(prefs.getInt("millis",1500)==times[i])b.setChecked(true);}modes.setOnCheckedChangeListener((g,id)->prefs.edit().putInt("millis",times[id-100]).apply());page.addView(modes);Ui.gap(page,10);
        Switch side=new Switch(this);side.setText("Белые снизу · для ручной проверки");side.setTextColor(Ui.INK);side.setTextSize(13);side.setChecked(prefs.getBoolean("whiteBottom",true));side.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("whiteBottom",v).apply());page.addView(side);Ui.gap(page,10);
        Switch arrowSwitch=new Switch(this);arrowSwitch.setText("Стрелка поверх доски");arrowSwitch.setTextColor(Ui.INK);arrowSwitch.setTextSize(15);arrowSwitch.setChecked(prefs.getBoolean("arrows",true));arrowSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("arrows",v).apply());page.addView(arrowSwitch);Ui.gap(page,10);
        Switch alternatives=new Switch(this);alternatives.setText("Три варианта для разбора");alternatives.setTextColor(Ui.INK);alternatives.setTextSize(15);alternatives.setChecked(prefs.getInt("variations",1)==3);alternatives.setOnCheckedChangeListener((b,v)->prefs.edit().putInt("variations",v?3:1).apply());page.addView(alternatives);page.addView(Ui.text(this,"Для самого глубокого расчёта одного хода оставь выключенным.",12,Ui.MUTED));Ui.gap(page,24);
        page.addView(Ui.button(this,"Запустить плавающее окно  ↗",true,this::startAssistant),new LinearLayout.LayoutParams(-1,Ui.dp(this,56)));Ui.gap(page,10);
        permissions=Ui.text(this,"",12,Ui.MUTED);page.addView(permissions);Ui.gap(page,14);
        page.addView(Ui.button(this,"Разобрать позицию по FEN",false,this::fenDialog),new LinearLayout.LayoutParams(-1,Ui.dp(this,52)));Ui.gap(page,10);
        page.addView(Ui.button(this,"Остановить помощника",false,()->{stopService(new Intent(this,CaptureService.class));Toast.makeText(this,"Помощник остановлен",Toast.LENGTH_SHORT).show();}),new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));Ui.gap(page,24);
        LinearLayout guide=Ui.card(this);guide.addView(Ui.bold(this,"Первый запуск · без ручной рамки",18,Ui.INK));Ui.gap(guide,12);guide.addView(Ui.text(this,"1. Разреши окно поверх приложений и захват всего экрана.\n\n2. Открой начальную расстановку в Chess.com. Помощник сам ищет доску и проверяет два кадра.\n\n3. Найденная сетка кратко подсветится, затем появится стрелка хода. При сомнении проверь предложенную рамку и сторону.",14,Ui.MUTED));Ui.gap(guide,14);guide.addView(Ui.text(this,"Повторить поиск: «+» → «Найти доску». Долгое нажатие этой кнопки открывает ручной запасной режим. При первом запуске с середины партии нужна начальная расстановка.",12,Ui.MUTED));page.addView(guide);Ui.gap(page,14);
        page.addView(Ui.button(this,"Фоновая работа на realme",false,()->new AlertDialog.Builder(this).setTitle("Если realme останавливает ХОД").setMessage("В настройках батареи приложения разреши фоновую работу и при необходимости закрепи ХОД в списке недавних. Названия пунктов зависят от версии realme UI. После блокировки экрана захват нужно разрешить заново.").setPositiveButton("Настройки приложения",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).setNegativeButton("Закрыть",null).show()));Ui.gap(page,16);
        page.addView(Ui.text(this,"Локальная обработка. Кадры не сохраняются и не отправляются. Root не запрашивается.\n\nВерсия 0.3 · экспериментальная",12,Ui.MUTED));updatePermissions();
    }
    private void updatePermissions(){if(permissions!=null)permissions.setText(Settings.canDrawOverlays(this)?"✓ Окно разрешено · захват подтверждается при запуске":"Нужно разрешение «Поверх других приложений»");}
    @Override public void onResume(){super.onResume();updatePermissions();}
    private void startAssistant(){
        if(requesting)return;
        if(CaptureService.running){Toast.makeText(this,"Окно уже запущено. Открой Chess.com.",Toast.LENGTH_LONG).show();return;}
        if(!Settings.canDrawOverlays(this)){startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())),OVERLAY);return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS);return;}
        requestCapture();
    }
    private void requestCapture(){
        requesting=true;MediaProjectionManager mgr=getSystemService(MediaProjectionManager.class);
        Intent i=Build.VERSION.SDK_INT>=34?mgr.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()):mgr.createScreenCaptureIntent();
        try{startActivityForResult(i,CAPTURE);}catch(Exception e){requesting=false;error("Не удалось открыть системный захват экрана",e);}
    }
    @Override public void onRequestPermissionsResult(int request,String[] p,int[] result){super.onRequestPermissionsResult(request,p,result);if(request==NOTIFICATIONS)requestCapture();}
    @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==OVERLAY){updatePermissions();if(Settings.canDrawOverlays(this))startAssistant();return;}if(request!=CAPTURE)return;requesting=false;
        if(result!=RESULT_OK||data==null){Toast.makeText(this,"Захват не разрешён",Toast.LENGTH_SHORT).show();return;}
        Intent service=new Intent(this,CaptureService.class).putExtra("code",result).putExtra("permission",data);startForegroundService(service);
        getWindow().getDecorView().postDelayed(()->{Intent chess=getPackageManager().getLaunchIntentForPackage("com.chess");if(chess!=null)startActivity(chess);else Toast.makeText(this,"Открой Chess.com и начальную позицию",Toast.LENGTH_LONG).show();},500);
    }
    private void fenDialog(){EditText input=new EditText(this);input.setText(Position.START);input.setTextColor(Ui.INK);input.setTextSize(13);input.setMinLines(3);Ui.pad(input,16);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Позиция в FEN").setView(input).setPositiveButton("Анализ",null).setNegativeButton("Отмена",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{Position p=Position.fromFen(input.getText().toString());dialog.dismiss();analyzeDemo(p);}catch(Exception e){input.setError(e.getMessage());}}));dialog.show();}
    private void analyzeDemo(Position p){LinearLayout content=Ui.col(this);Ui.pad(content,20);BoardView board=new BoardView(this);board.show(p,null,true);content.addView(board);TextView result=Ui.text(this,"Анализирую…",15,Ui.INK);content.addView(result);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Разбор позиции").setView(content).setPositiveButton("Готово",null).create();analysisDialog=d;d.show();java.util.concurrent.atomic.AtomicBoolean visible=new java.util.concurrent.atomic.AtomicBoolean(true);
        if(p.legalMoves().isEmpty()){result.setText(p.inCheck(p.white)?"Мат. "+(p.white?"Чёрные выиграли":"Белые выиграли"):"Пат. Ничья.");return;}
        Future<?> task=demo.submit(()->{ChessEngine engine=null;try{engine=EngineFactory.create(this,2);demoEngine=engine;engine.analyze(p,prefs.getInt("millis",1500),a->runOnUiThread(()->{if(visible.get()){board.show(p,a.bestMove(),true);result.setText(a.engine()+" · глубина "+a.depth()+"\nОценка за белых: "+a.score()+"\n"+(a.bestMove()==null?"Партия завершена":p.describe(a.bestMove())));}}));}catch(Exception e){runOnUiThread(()->{if(visible.get())result.setText("Анализ недоступен: "+e.getMessage());});}finally{if(engine!=null)engine.close();demoEngine=null;}});
        d.setOnDismissListener(x->{visible.set(false);task.cancel(true);});}
    private void error(String s,Exception e){new AlertDialog.Builder(this).setTitle(s).setMessage(e.getMessage()).setPositiveButton("Закрыть",null).show();}
    @Override public void onDestroy(){if(analysisDialog!=null)analysisDialog.dismiss();demo.shutdownNow();ChessEngine e=demoEngine;if(e!=null)e.close();super.onDestroy();}
}
