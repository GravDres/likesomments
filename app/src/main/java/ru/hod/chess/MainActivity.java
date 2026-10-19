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
    private RadioGroup playerColors;
    private RadioGroup platforms,strengths;private Button openChess;
    private RadioGroup engineModes,maiaRatings,maiaNetworks,timeProfiles;private Switch sampling;
    private final SharedPreferences.OnSharedPreferenceChangeListener colorChanges=(preferences,key)->{if("playerWhite".equals(key))syncPlayerSide();if("chessPlatform".equals(key))syncPlatform();if(EngineStrength.PREFERENCE.equals(key))syncStrength();if(EngineMode.PREFERENCE.equals(key)||MaiaModel.PREFERENCE.equals(key)||MaiaNetwork.PREFERENCE.equals(key)||TimeControlProfile.PREFERENCE.equals(key)||"maiaSampling".equals(key))syncEngineOptions();};
    private final ExecutorService demo=Executors.newSingleThreadExecutor();
    private volatile ChessEngine demoEngine;
    private AlertDialog analysisDialog;
    private android.content.SharedPreferences prefs;
    private boolean requesting;
    private static final java.util.concurrent.atomic.AtomicInteger visibleActivities=new java.util.concurrent.atomic.AtomicInteger();
    private boolean countedVisible;
    static boolean isVisible(){return visibleActivities.get()>0;}
    @Override public void onCreate(Bundle state){super.onCreate(state);prefs=getSharedPreferences("hod",MODE_PRIVATE);if(!prefs.getBoolean("maia15Configured",false)){prefs.edit().putString("chessPlatform",ChessPlatform.CHESS_COM.key()).putBoolean("maia15Configured",true).apply();if(CaptureService.running)startService(new Intent(this,CaptureService.class).setAction(CaptureService.PLATFORM_CHANGED));}build();syncEngineOptions();if(CaptureService.RESUME_CAPTURE.equals(getIntent().getAction()))getWindow().getDecorView().post(this::startAssistant);}
    private void build(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Ui.BG);
        LinearLayout page=Ui.col(this);Ui.pad(page,24);scroll.addView(page);setContentView(scroll);
        page.setOnApplyWindowInsetsListener((v,insets)->{int top,bottom;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());top=bars.top;bottom=bars.bottom;}else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}v.setPadding(Ui.dp(this,24),top+Ui.dp(this,20),Ui.dp(this,24),bottom+Ui.dp(this,20));return insets;});
        LinearLayout header=Ui.row(this);TextView logo=Ui.bold(this,"♞  ХОД",25,Ui.INK);logo.setLetterSpacing(.12f);header.addView(logo,new LinearLayout.LayoutParams(0,-2,1));TextView tag=Ui.bold(this,"ANDROID",10,Ui.ACCENT);tag.setLetterSpacing(.14f);header.addView(tag);page.addView(header);Ui.gap(page,32);
        page.addView(Ui.bold(this,"Твоя позиция.\nТвой следующий ход.",34,Ui.INK));Ui.gap(page,12);
        TextView intro=Ui.text(this,"Подсказка прямо на доске.\nСтрелка · плавающее окно · анализ офлайн",15,Ui.MUTED);intro.setLineSpacing(Ui.dp(this,4),1);page.addView(intro);Ui.gap(page,24);
        page.addView(Ui.text(this,"Подсказки движка в партиях с людьми запрещены Lichess и Chess.com и могут привести к блокировке аккаунта. Ограничение силы не делает такую помощь разрешённой или незаметной.",13,Ui.MUTED));Ui.gap(page,18);
        page.addView(Ui.bold(this,"Где играешь?",18,Ui.INK));Ui.gap(page,8);platforms=new RadioGroup(this);platforms.setOrientation(LinearLayout.HORIZONTAL);
        for(ChessPlatform source:ChessPlatform.values()){RadioButton button=new RadioButton(this);button.setId(source==ChessPlatform.LICHESS?301:300);button.setText(source.label());button.setTextColor(Ui.INK);button.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));platforms.addView(button);if(source==platform())button.setChecked(true);}
        platforms.setOnCheckedChangeListener((group,id)->{if(id!=300&&id!=301)return;ChessPlatform source=id==301?ChessPlatform.LICHESS:ChessPlatform.CHESS_COM;if(source==platform())return;prefs.edit().putString("chessPlatform",source.key()).apply();if(CaptureService.running)startService(new Intent(this,CaptureService.class).setAction(CaptureService.PLATFORM_CHANGED));});page.addView(platforms);
        page.addView(Ui.text(this,"Обычные шахматы: люди и боты, любой контроль времени. История и тема фигур сохраняются отдельно для каждого приложения.",12,Ui.MUTED));Ui.gap(page,10);
        openChess=Ui.button(this,"Открыть "+platform().label(),false,this::openSelectedApp);page.addView(openChess,new LinearLayout.LayoutParams(-1,Ui.dp(this,46)));Ui.gap(page,20);
        LinearLayout boardCard=Ui.card(this);LinearLayout status=Ui.row(this);status.addView(Ui.bold(this,"●  ГОТОВ К АНАЛИЗУ",11,Ui.ACCENT),new LinearLayout.LayoutParams(0,-2,1));status.addView(Ui.text(this,"01 / ПОЗИЦИЯ",10,Ui.MUTED));boardCard.addView(status);Ui.gap(boardCard,16);
        BoardView board=new BoardView(this);Position preview=Position.start().play(Move.parse("e2e4")).play(Move.parse("e7e5"));board.show(preview,Move.parse("g1f3"),true);boardCard.addView(board,new LinearLayout.LayoutParams(-1,-2));Ui.gap(boardCard,14);boardCard.addView(Ui.text(this,"Пример подсказки: конь g1 → f3",13,Ui.MUTED));page.addView(boardCard);Ui.gap(page,20);
        page.addView(Ui.bold(this,"Каким цветом ты играешь?",18,Ui.INK));Ui.gap(page,8);
        RadioGroup player=playerColors=new RadioGroup(this);player.setOrientation(LinearLayout.HORIZONTAL);
        for(int i=0;i<2;i++){RadioButton button=new RadioButton(this);button.setId(200+i);button.setText(i==0?"Белыми":"Чёрными");button.setTextColor(Ui.INK);button.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));player.addView(button);if(prefs.getBoolean("playerWhite",true)==(i==0))button.setChecked(true);}
        player.setOnCheckedChangeListener((group,id)->{if(id!=200&&id!=201)return;boolean white=id==200;if(prefs.getBoolean("playerWhite",true)==white)return;prefs.edit().putBoolean("playerWhite",white).apply();if(CaptureService.running)startService(new Intent(this,CaptureService.class).setAction(CaptureService.SIDE_CHANGED));});page.addView(player);
        page.addView(Ui.text(this,"Подсказки только за твой цвет. Во время партии нажми «Белые ⇄» / «Чёрные ⇄» в заголовке мини-окна.",12,Ui.MUTED));Ui.gap(page,18);
        EngineSettings selected=EngineFactory.settings(this);
        engineModes=choices(page,"Движок",java.util.Arrays.stream(EngineMode.values()).map(EngineMode::label).toArray(String[]::new),selected.mode().ordinal(),i->setEnginePreference(EngineMode.PREFERENCE,EngineMode.values()[i].key()));
        maiaRatings=choices(page,"Уровень человеческих ходов",java.util.Arrays.stream(MaiaModel.values()).map(MaiaModel::label).toArray(String[]::new),selected.model().ordinal(),i->setEnginePreference(MaiaModel.PREFERENCE,MaiaModel.values()[i].rating()));
        page.addView(Ui.text(this,"2000/2200/2400 — условия модели Maia-3, а не измеренный рейтинг приложения. Рейтинг соперника для модели принят равным выбранному уровню.",12,Ui.MUTED));Ui.gap(page,8);
        maiaNetworks=choices(page,"Сеть Maia-3",java.util.Arrays.stream(MaiaNetwork.values()).map(MaiaNetwork::label).toArray(String[]::new),selected.network().ordinal(),i->setEnginePreference(MaiaNetwork.PREFERENCE,MaiaNetwork.values()[i].key()));
        sampling=new Switch(this);sampling.setText("Выбор по вероятностям Maia");sampling.setTextColor(Ui.INK);sampling.setChecked(selected.sampling());sampling.setOnCheckedChangeListener((b,v)->{if(prefs.getBoolean("maiaSampling",true)==v)return;prefs.edit().putBoolean("maiaSampling",v).apply();engineOptionsChanged();});page.addView(sampling);
        page.addView(Ui.text(this,"Включено: выбор из распределения человеческих ходов, Temperature=1. Выключено: самый вероятный ход. В обоих случаях доступны только легальные ходы.",12,Ui.MUTED));Ui.gap(page,12);
        timeProfiles=choices(page,"Контроль времени",java.util.Arrays.stream(TimeControlProfile.values()).map(TimeControlProfile::label).toArray(String[]::new),selected.profile().ordinal(),i->setEnginePreference(TimeControlProfile.PREFERENCE,TimeControlProfile.values()[i].key()));
        page.addView(Ui.text(this,"Профиль выбирается вручную. Maia отвечает одним проходом без искусственной задержки; профиль задаёт предел ожидания ответа. Часы соперника не считываются, отдельного обучения на 3+2 или 10 минутах нет.",12,Ui.MUTED));Ui.gap(page,12);
        page.addView(Ui.bold(this,"Сила Stockfish",18,Ui.INK));Ui.gap(page,8);strengths=new RadioGroup(this);strengths.setOrientation(LinearLayout.VERTICAL);
        EngineStrength[] levels=EngineStrength.values();for(int i=0;i<levels.length;i++){RadioButton b=new RadioButton(this);b.setId(400+i);b.setText(levels[i].label());b.setTextColor(Ui.INK);b.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));strengths.addView(b);if(levels[i]==engineStrength())b.setChecked(true);}
        strengths.setOnCheckedChangeListener((g,id)->{int index=id-400;if(index<0||index>=levels.length||levels[index]==engineStrength())return;prefs.edit().putInt(EngineStrength.PREFERENCE,levels[index].skill()).apply();if(CaptureService.running)startService(new Intent(this,CaptureService.class).setAction(CaptureService.STRENGTH_CHANGED));});page.addView(strengths);
        page.addView(Ui.text(this,"Штатные уровни Stockfish, не рейтинг Elo и не имитация человека. Ниже 20 движок выбирает и менее сильные варианты. По умолчанию — 10/20. В окне: «+» → «Сила».",12,Ui.MUTED));Ui.gap(page,18);
        page.addView(Ui.bold(this,"Ритм анализа",18,Ui.INK));Ui.gap(page,10);
        RadioGroup modes=new RadioGroup(this);modes.setOrientation(LinearLayout.VERTICAL);String[] labels={"Пуля   ·   0,3 с","Быстро   ·   0,6 с","Сильно   ·   1,5 с","Глубоко   ·   4 с"};int[] times={300,600,1500,4000};for(int i=0;i<labels.length;i++){RadioButton b=new RadioButton(this);b.setText(labels[i]);b.setTextColor(Ui.INK);b.setTextSize(14);b.setId(100+i);b.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));modes.addView(b);if(prefs.getInt("millis",1500)==times[i])b.setChecked(true);}modes.setOnCheckedChangeListener((g,id)->{if(id<100||id>103)return;prefs.edit().putInt("millis",times[id-100]).putString(TimeControlProfile.PREFERENCE,"custom").apply();engineOptionsChanged();});page.addView(modes);page.addView(Ui.text(this,"Это время расчёта движка. Распознавание кадра занимает дополнительное время. Для Stockfish. Выбор включает профиль ручного времени; на Maia не влияет.",12,Ui.MUTED));Ui.gap(page,10);
        Switch side=new Switch(this);side.setText("Ориентация: белые снизу · ручная проверка");side.setTextColor(Ui.INK);side.setTextSize(13);side.setChecked(prefs.getBoolean("whiteBottom",true));side.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("whiteBottom",v).apply());page.addView(side);Ui.gap(page,10);
        Switch arrowSwitch=new Switch(this);arrowSwitch.setText("Стрелка поверх доски");arrowSwitch.setTextColor(Ui.INK);arrowSwitch.setTextSize(15);arrowSwitch.setChecked(prefs.getBoolean("arrows",true));arrowSwitch.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("arrows",v).apply());page.addView(arrowSwitch);Ui.gap(page,10);
        Switch alternatives=new Switch(this);alternatives.setText("Три варианта для FEN-разбора");alternatives.setTextColor(Ui.INK);alternatives.setTextSize(15);alternatives.setChecked(prefs.getInt("variations",1)==3);alternatives.setOnCheckedChangeListener((b,v)->prefs.edit().putInt("variations",v?3:1).apply());page.addView(alternatives);page.addView(Ui.text(this,"При силе ниже 20 показывается итоговый выбранный вариант. На максимуме для FEN можно включить три.",12,Ui.MUTED));Ui.gap(page,24);
        Switch root=new Switch(this);root.setText("Захват через Root · Android 14+");root.setTextColor(Ui.INK);root.setTextSize(15);root.setEnabled(Build.VERSION.SDK_INT>=34);root.setChecked(prefs.getBoolean("rootCapture",false));root.setOnCheckedChangeListener((b,v)->{prefs.edit().putBoolean("rootCapture",v).apply();updatePermissions();});page.addView(root);
        page.addView(Ui.text(this,"Для защищённого экрана. Включи, нажми запуск и разреши ХОД доступ в своём менеджере root. Затем открой выбранное шахматное приложение.",12,Ui.MUTED));Ui.gap(page,18);
        page.addView(Ui.button(this,"Запустить плавающее окно  ↗",true,this::startAssistant),new LinearLayout.LayoutParams(-1,Ui.dp(this,56)));Ui.gap(page,10);
        permissions=Ui.text(this,"",12,Ui.MUTED);page.addView(permissions);Ui.gap(page,14);
        page.addView(Ui.button(this,"Разобрать позицию по FEN",false,this::fenDialog),new LinearLayout.LayoutParams(-1,Ui.dp(this,52)));Ui.gap(page,10);
        page.addView(Ui.button(this,"Остановить помощника",false,()->{stopService(new Intent(this,CaptureService.class));Toast.makeText(this,"Помощник остановлен",Toast.LENGTH_SHORT).show();}),new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));Ui.gap(page,24);
        LinearLayout guide=Ui.card(this);guide.addView(Ui.bold(this,"Первый запуск · без ручной рамки",18,Ui.INK));Ui.gap(guide,12);guide.addView(Ui.text(this,"1. Выбери свой цвет. Разреши окно поверх приложений. Для обычного захвата разреши весь экран; для Root-захвата подтверди доступ в менеджере root.\n\n2. Выбери Lichess или Chess.com и открой в нём начальную расстановку. Помощник сам ищет доску и проверяет два кадра.\n\n3. После проверки доски на твоём ходу появится стрелка; на ходе соперника помощник ждёт. Нажми ↻ или «Автопоиск» для повторного автоматического поиска.",14,Ui.MUTED));Ui.gap(guide,14);guide.addView(Ui.text(this,"Если захват прервался: «↻» → разрешить захват. Остановить помощника: «+» → «Остановить помощника». Автоматический поиск: «Автопоиск». Ручной запасной режим: удерживать «Автопоиск». Продолжение из середины при потерянной истории: «Позиция». Новая игра: нажми ↻ на начальной доске. Первые один–два полухода распознаются при сохранённой теме. В середине ↻ продолжает текущую игру. Повторный поиск без сброса стартовой расстановки: «+» → «Найти доску». Долгое нажатие этой кнопки открывает ручной запасной режим. При первом запуске с середины партии нужна начальная расстановка.",12,Ui.MUTED));page.addView(guide);Ui.gap(page,14);
        page.addView(Ui.button(this,"Фоновая работа на realme",false,()->new AlertDialog.Builder(this).setTitle("Если realme останавливает ХОД").setMessage("В настройках батареи приложения разреши фоновую работу и при необходимости закрепи ХОД в списке недавних. Названия пунктов зависят от версии realme UI. После остановки захвата окно остаётся: нажми ↻ и снова разреши захват. История партии и тема фигур сохраняются. Android и realme могут завершить процесс; принудительную остановку приложение обойти не может.").setPositiveButton("Настройки приложения",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).setNegativeButton("Закрыть",null).show()));Ui.gap(page,16);
        page.addView(Ui.text(this,"Локальная обработка. Кадры не сохраняются и не отправляются. История ходов и тема фигур сохраняются на телефоне. Root запрашивается только при включённом Root-захвате.\n\nВерсия 0.15 · экспериментальная",12,Ui.MUTED));updatePermissions();
    }
    private void updatePermissions(){if(permissions!=null){boolean root=prefs.getBoolean("rootCapture",false);String text=Settings.canDrawOverlays(this)?(root?"✓ Окно разрешено · при запуске будет запрос root":"✓ Окно разрешено · захват подтверждается при запуске"):"Нужно разрешение «Поверх других приложений»";String problem=prefs.getString("lastCaptureProblem","");permissions.setText(text+(problem.isEmpty()?"":"\nПоследняя остановка: "+problem));}}
    private ChessPlatform platform(){return ChessPlatform.fromPreference(prefs.getString("chessPlatform",null));}
    private RadioGroup choices(LinearLayout parent,String title,String[] labels,int selected,java.util.function.IntConsumer picked){
        parent.addView(Ui.bold(this,title,18,Ui.INK));RadioGroup group=new RadioGroup(this);for(int i=0;i<labels.length;i++){RadioButton b=new RadioButton(this);b.setId(View.generateViewId());b.setText(labels[i]);b.setTextColor(Ui.INK);b.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));group.addView(b);if(i==selected)b.setChecked(true);}group.setOnCheckedChangeListener((g,id)->{int index=g.indexOfChild(g.findViewById(id));if(index>=0)picked.accept(index);});parent.addView(group);Ui.gap(parent,12);return group;
    }
    private void setEnginePreference(String key,String value){if(value.equals(prefs.getString(key,null)))return;prefs.edit().putString(key,value).apply();engineOptionsChanged();}
    private void setEnginePreference(String key,int value){if(prefs.getInt(key,-1)==value)return;prefs.edit().putInt(key,value).apply();engineOptionsChanged();}
    private void engineOptionsChanged(){if(CaptureService.running)startService(new Intent(this,CaptureService.class).setAction(CaptureService.ENGINE_CHANGED));syncEngineOptions();}
    private void syncChoice(RadioGroup group,int index){if(group!=null){int id=group.getChildAt(index).getId();if(group.getCheckedRadioButtonId()!=id)group.check(id);}}
    private void syncEngineOptions(){EngineSettings s=EngineFactory.settings(this);syncChoice(engineModes,s.mode().ordinal());syncChoice(maiaRatings,s.model().ordinal());syncChoice(maiaNetworks,s.network().ordinal());syncChoice(timeProfiles,s.profile().ordinal());if(sampling!=null)sampling.setChecked(s.sampling());for(RadioGroup group:new RadioGroup[]{maiaRatings,maiaNetworks,strengths})if(group!=null)for(int i=0;i<group.getChildCount();i++)group.getChildAt(i).setEnabled(group==strengths?s.mode()==EngineMode.STOCKFISH:s.mode()==EngineMode.MAIA);if(sampling!=null)sampling.setEnabled(s.mode()==EngineMode.MAIA);}
    private EngineStrength engineStrength(){return EngineStrength.fromPreference(prefs.getInt(EngineStrength.PREFERENCE,EngineStrength.DEFAULT_SKILL));}
    private void syncStrength(){if(strengths!=null){int id=400+engineStrength().ordinal();if(strengths.getCheckedRadioButtonId()!=id)strengths.check(id);}}
    private void syncPlatform(){if(platforms!=null){int id=platform()==ChessPlatform.LICHESS?301:300;if(platforms.getCheckedRadioButtonId()!=id)platforms.check(id);}if(openChess!=null)openChess.setText("Открыть "+platform().label());}
    private void openSelectedApp(){
        ChessPlatform source=platform();for(String name:source.packages()){Intent app=getPackageManager().getLaunchIntentForPackage(name);if(app!=null)try{startActivity(app);return;}catch(ActivityNotFoundException ignored){}}
        Toast.makeText(this,"Приложение "+source.label()+" не найдено. Открой его вручную или установи официальную версию.",Toast.LENGTH_LONG).show();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(CaptureService.RESUME_CAPTURE.equals(intent.getAction()))startAssistant();}
    private void syncPlayerSide(){if(playerColors!=null){int id=prefs.getBoolean("playerWhite",true)?200:201;if(playerColors.getCheckedRadioButtonId()!=id)playerColors.check(id);}}
    @Override public void onResume(){super.onResume();if(!countedVisible){countedVisible=true;visibleActivities.incrementAndGet();}prefs.registerOnSharedPreferenceChangeListener(colorChanges);syncPlayerSide();syncPlatform();syncStrength();syncEngineOptions();updatePermissions();}
    @Override public void onPause(){prefs.unregisterOnSharedPreferenceChangeListener(colorChanges);if(countedVisible){countedVisible=false;visibleActivities.decrementAndGet();}super.onPause();}
    private void startAssistant(){
        if(requesting)return;
        boolean root=prefs.getBoolean("rootCapture",false);
        if(CaptureService.running&&(CaptureService.capturing||CaptureService.rootStarting)&&CaptureService.rootMode==root){if(CaptureService.rootStarting)Toast.makeText(this,"Подтверди запрос в менеджере root",Toast.LENGTH_LONG).show();else openSelectedApp();return;}
        if(!Settings.canDrawOverlays(this)){startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())),OVERLAY);return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS);return;}
        requestCapture();
    }
    private void requestCapture(){
        if(prefs.getBoolean("rootCapture",false)){
            startForegroundService(new Intent(this,CaptureService.class).setAction(CaptureService.START_ROOT).putExtra("refresh",CaptureService.RESUME_CAPTURE.equals(getIntent().getAction())));
            Toast.makeText(this,"Разреши Root для ХОД, затем нажми «Открыть "+platform().label()+"»",Toast.LENGTH_LONG).show();return;
        }
        requesting=true;MediaProjectionManager mgr=getSystemService(MediaProjectionManager.class);
        Intent i=Build.VERSION.SDK_INT>=34?mgr.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()):mgr.createScreenCaptureIntent();
        try{startActivityForResult(i,CAPTURE);}catch(Exception e){requesting=false;error("Не удалось открыть системный захват экрана",e);}
    }
    @Override public void onRequestPermissionsResult(int request,String[] p,int[] result){super.onRequestPermissionsResult(request,p,result);if(request==NOTIFICATIONS)requestCapture();}
    @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==OVERLAY){updatePermissions();if(Settings.canDrawOverlays(this))startAssistant();return;}if(request!=CAPTURE)return;requesting=false;
        if(result!=RESULT_OK||data==null){Toast.makeText(this,"Захват не разрешён",Toast.LENGTH_SHORT).show();return;}
        Intent service=new Intent(this,CaptureService.class).putExtra("code",result).putExtra("permission",data).putExtra("refresh",CaptureService.RESUME_CAPTURE.equals(getIntent().getAction()));startForegroundService(service);
        getWindow().getDecorView().postDelayed(this::openSelectedApp,500);
    }
    private void fenDialog(){EditText input=new EditText(this);input.setText(Position.START);input.setTextColor(Ui.INK);input.setTextSize(13);input.setMinLines(3);Ui.pad(input,16);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Позиция в FEN").setView(input).setPositiveButton("Анализ",null).setNegativeButton("Отмена",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{Position p=Position.fromFen(input.getText().toString());dialog.dismiss();analyzeDemo(p);}catch(Exception e){input.setError(e.getMessage());}}));dialog.show();}
    private void analyzeDemo(Position p){LinearLayout content=Ui.col(this);Ui.pad(content,20);BoardView board=new BoardView(this);board.show(p,null,true);content.addView(board);TextView result=Ui.text(this,"Анализирую…",15,Ui.INK);content.addView(result);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Разбор позиции").setView(content).setPositiveButton("Готово",null).create();analysisDialog=d;d.show();java.util.concurrent.atomic.AtomicBoolean visible=new java.util.concurrent.atomic.AtomicBoolean(true);
        if(p.legalMoves().isEmpty()){result.setText(p.inCheck(p.white)?"Мат. "+(p.white?"Чёрные выиграли":"Белые выиграли"):"Пат. Ничья.");return;}
        Future<?> task=demo.submit(()->{ChessEngine engine=null;try{engine=EngineFactory.create(this,2);demoEngine=engine;engine.analyze(p,EngineFactory.settings(this).analysisMillis(),a->runOnUiThread(()->{if(visible.get()){board.show(p,a.bestMove(),true);result.setText(a.engine()+(a.policyProbability()==null?" · глубина "+a.depth():"")+(a.policyProbability()==null?"\nОценка за белых: ":"\nВероятность хода по Maia: ")+a.score()+"\n"+(a.bestMove()==null?"Партия завершена":p.describe(a.bestMove())));}}));}catch(Exception e){runOnUiThread(()->{if(visible.get())result.setText("Анализ недоступен: "+e.getMessage());});}finally{if(engine!=null)engine.close();demoEngine=null;}});
        d.setOnDismissListener(x->{visible.set(false);task.cancel(true);});}
    private void error(String s,Exception e){new AlertDialog.Builder(this).setTitle(s).setMessage(e.getMessage()).setPositiveButton("Закрыть",null).show();}
    @Override public void onDestroy(){if(analysisDialog!=null)analysisDialog.dismiss();demo.shutdownNow();ChessEngine e=demoEngine;if(e!=null)e.close();super.onDestroy();}
}
