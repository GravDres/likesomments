package ru.hod.chess;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.*;
import android.widget.Toast;
import java.util.*;
import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import ru.hod.chess.core.*;

public final class CaptureService extends Service {
    public static volatile boolean running,capturing,rootMode,rootStarting;
    public static final String START_ROOT="ru.hod.chess.START_ROOT";
    public static final String RESUME_CAPTURE="ru.hod.chess.RESUME_CAPTURE";
    private static final String STOP="ru.hod.chess.STOP";
    public static final String SIDE_CHANGED="ru.hod.chess.SIDE_CHANGED";
    public static final String PLATFORM_CHANGED="ru.hod.chess.PLATFORM_CHANGED";
    public static final String STRENGTH_CHANGED="ru.hod.chess.STRENGTH_CHANGED";
    public static final String ENGINE_CHANGED="ru.hod.chess.ENGINE_CHANGED";
    private final Handler main=new Handler(Looper.getMainLooper());
    private HandlerThread captureThread;private Handler capture;
    private volatile MediaProjection projection;private volatile VirtualDisplay display;private volatile ImageReader reader;
    private volatile RootCaptureSession rootCapture;private volatile boolean rootSecureSeen;
    private final ExecutorService visionQueue=Executors.newSingleThreadExecutor(),engineQueue=Executors.newSingleThreadExecutor();
    private final AtomicBoolean processing=new AtomicBoolean();private final AtomicLong epoch=new AtomicLong(),searchEpoch=new AtomicLong();
    private final AtomicBoolean arrowActive=new AtomicBoolean();
    private volatile boolean destroyed,paused,selecting,preparingSelection;private volatile long selectAfter;private volatile Frame latest;private volatile BoardVision.Region region;
    private volatile ChessEngine engine;private Future<?> search;private FloatingPanel panel;private BoardSelector selector;private PositionReview review;
    private volatile EngineSettings activeEngineSettings;private volatile boolean engineLoading;
    private volatile boolean reviewRequested;private volatile BoardVision.Region rememberedRegion,selectionRegion;
    private ArrowOverlay arrows;
    private volatile Guide guide;
    private final CleanFrameGate cleanFrames=new CleanFrameGate();
    private volatile long lastImageAt,lastGoodAt,engineStartedAt,lastReaderRepair,lastRecoveryAt,automaticRetryAt;
    private volatile int captureGeneration;private volatile String captureProblem="Нужно разрешить захват · нажми ↻";
    private int readerRepairs,frameErrors;private boolean initialized;
    private volatile Confirmed confirmed;
    private volatile boolean analysisComplete;
    private volatile boolean automaticSearch,automaticRefresh;
    private volatile long automaticAfter;
    private volatile AutoBoardSearch automaticFinder;
    private volatile AutoAttempt automaticAttempt;private int automaticMisses;
    private volatile BoardVision vision;private volatile GameTracker tracker;private final StableBoard stability=new StableBoard();private int badFrames;private boolean whiteBottom;
    private final BoardRecoveryGate boardRecovery=new BoardRecoveryGate();
    private long lastPanelMoveAt;
    private volatile PlayerSide playerSide;private volatile boolean boardVerified;private SessionStore sessionStore;private volatile ChessPlatform platform;
    private long lastUiReport;
    private long lastFrame;private volatile int width,height;private volatile Analysis lastAnalysis;private volatile Position analyzedPosition;private volatile float currentConfidence;
    private record Frame(int[] pixels,int width,int height,int left,int top) {
        BoardVision.Region local(BoardVision.Region screen){return new BoardVision.Region(screen.left()-left,screen.top()-top,screen.size());}
        boolean sameArea(Frame other){return other!=null&&width==other.width&&height==other.height&&left==other.left&&top==other.top;}
    }
    private record Guide(ArrowGeometry shape, Frame clean) {}
    private record Confirmed(Position initial,Position position,List<Move> moves,Frame frame) {}
    private record AutoAttempt(AutoBoardSearch.Result result,Frame frame) {}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        try{
            if(!initialized){initialize();if(intent==null)captureProblem="Окно восстановлено после остановки · нажми ↻ для захвата";}
            if(intent!=null&&SIDE_CHANGED.equals(intent.getAction()))changeSide(prefs().getBoolean("playerWhite",true)?PlayerSide.WHITE:PlayerSide.BLACK);
            if(intent!=null&&PLATFORM_CHANGED.equals(intent.getAction()))changePlatform();
            if(intent!=null&&STRENGTH_CHANGED.equals(intent.getAction()))changeStrength(engineStrength());
            if(intent!=null&&ENGINE_CHANGED.equals(intent.getAction()))changeEngineSettings();
            if(intent!=null&&START_ROOT.equals(intent.getAction()))startRootCapture(intent.getBooleanExtra("refresh",false));
            else if(intent==null&&prefs().getBoolean("rootCapture",false))startRootCapture(false);
            else if(intent!=null&&intent.hasExtra("permission"))startCapture(intent);
            else if(!capturing){panel.message(captureProblem);panel.health("Захват остановлен · ↻ продолжить");}
        }catch(Exception e){
            if(panel!=null)captureLost("Не удалось подключить экран: "+e.getMessage());
            else{Toast.makeText(this,"Не удалось открыть окно: "+e.getMessage(),Toast.LENGTH_LONG).show();stopSelf();return START_NOT_STICKY;}
        }
        return START_STICKY;
    }
    private void foreground(boolean screen){
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("capture","Плавающий помощник",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).setAction(RESUME_CAPTURE),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CaptureService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification=new Notification.Builder(this,"capture").setSmallIcon(ru.hod.chess.R.drawable.ic_hod)
            .setContentTitle("ХОД · окно активно").setContentText(rootMode?(rootStarting?"Ожидаю разрешение root":"Root-захват · анализ на устройстве"):screen?"Экран подключён · анализ на устройстве":"Захват остановлен · нажми, чтобы продолжить")
            .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Остановить",stop).build()).build();
        int type=screen?ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION:0;
        if(Build.VERSION.SDK_INT>=34)type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        startForeground(1,notification,type);
    }
    private void initialize(){
        foreground(false);captureThread=new HandlerThread("screen-frames");captureThread.start();capture=new Handler(captureThread.getLooper());
        arrows=new ArrowOverlay(this);whiteBottom=prefs().getBoolean("whiteBottom",true);playerSide=prefs().getBoolean("playerWhite",true)?PlayerSide.WHITE:PlayerSide.BLACK;platform=ChessPlatform.fromPreference(prefs().getString("chessPlatform",null));sessionStore=new SessionStore(this,platform);
        try{GameMemory.Restored saved=sessionStore.load();if(saved!=null){vision=saved.vision();tracker=saved.game();whiteBottom=vision.whiteBottom();}}catch(java.io.IOException e){captureProblem="Сохранённая партия не читается · начни новую";}
        panel=new FloatingPanel(this,new FloatingPanel.Actions(){
            public void calibrate(){if(capturing)startAutomaticSearch();else requestCapture();}
            public void manual(){if(capturing)selectBoard();else requestCapture();}
            public void recover(){recoverAssistant();}
            public void verify(){recoverAssistant();}
            public void review(){if(capturing)selectPosition();else requestCapture();}
            public void side(PlayerSide side){changeSide(side);}public void newGame(){startNewGame();}
            public void strength(EngineStrength value){changeStrength(value);}
            public void engine(EngineMode value){prefs().edit().putString(EngineMode.PREFERENCE,value.key()).apply();changeEngineSettings();}
            public void model(MaiaModel value){prefs().edit().putInt(MaiaModel.PREFERENCE,value.rating()).apply();changeEngineSettings();}
            public void network(MaiaNetwork value){prefs().edit().putString(MaiaNetwork.PREFERENCE,value.key()).apply();changeEngineSettings();}
            public void profile(TimeControlProfile value){prefs().edit().putString(TimeControlProfile.PREFERENCE,value.key()).apply();changeEngineSettings();}
            public void pause(boolean value){setPaused(value);}
            public void arrows(boolean value){prefs().edit().putBoolean("arrows",value).apply();if(!value)hideArrow();}
            public void deeper(){Position p=analyzedPosition;if(!capturing||paused||p==null||!playerSide.ownsTurn(p)){Toast.makeText(CaptureService.this,"Дождись своего хода и подтверждённой позиции",Toast.LENGTH_SHORT).show();return;}beginAnalysis(p,currentConfidence,epoch.get(),8000);}
            public void stop(){stopSelf();}
        });
        initialized=true;running=true;main.postDelayed(healthCheck,1000);main.post(framePulse);
    }
    private void startCapture(Intent intent){
        releaseCapture();foreground(true);
        Intent permission=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra("permission",Intent.class):intent.getParcelableExtra("permission");
        MediaProjection next=getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("code",Activity.RESULT_CANCELED),permission);
        if(next==null)throw new IllegalStateException("Захват не разрешён");
        projection=next;int generation=++captureGeneration;
        next.registerCallback(new MediaProjection.Callback(){
            @Override public void onStop(){main.post(()->{if(!destroyed&&generation==captureGeneration)captureLost("Android остановил захват · нажми ↻, чтобы продолжить");});}
            @Override public void onCapturedContentResize(int w,int h){if(generation==captureGeneration&&display!=null&&(w!=width||h!=height)&&w>0&&h>0)try{resize(w,h);}catch(RuntimeException e){main.post(()->{if(!destroyed&&generation==captureGeneration)captureLost("Не удалось изменить размер захвата · нажми ↻");});}}
        },capture);
        DisplayMetrics metrics=new DisplayMetrics();getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);width=metrics.widthPixels;height=metrics.heightPixels;
        reader=createReader(width,height);display=next.createVirtualDisplay("ХОД — локальный анализ",width,height,getResources().getConfiguration().densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,capture);
        captureConnected(generation,intent.getBooleanExtra("refresh",false));
    }
    private void captureConnected(int generation,boolean refresh){
        capturing=true;rootStarting=false;paused=false;panel.setPaused(false);region=null;confirmed=null;boardVerified=false;lastImageAt=SystemClock.elapsedRealtime();lastGoodAt=lastImageAt;lastFrame=0;readerRepairs=frameErrors=0;cleanFrames.reset();
        prefs().edit().remove("lastCaptureProblem").apply();
        panel.message(rootMode?"Root подключён · открой "+platform.label():"Экран подключён · ищу доску "+platform.label());warmEngine();
        main.postDelayed(()->{if(!destroyed&&capturing&&generation==captureGeneration)startAutomaticSearch(refresh);},350);
    }
    private void startRootCapture(boolean refresh){
        if(Build.VERSION.SDK_INT<34){captureLost("Root-захват требует Android 14 или новее");return;}
        releaseCapture();rootMode=true;rootStarting=true;rootSecureSeen=false;foreground(false);
        panel.message("Разреши Root для ХОД в менеджере root");panel.health("Root · ожидаю разрешение");
        DisplayMetrics metrics=new DisplayMetrics();getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);width=metrics.widthPixels;height=metrics.heightPixels;
        int generation=++captureGeneration;
        RootCaptureSession session=new RootCaptureSession(getApplicationInfo().sourceDir,new RootCaptureSession.Listener(){
            public void ready(int sdk){main.post(()->{if(!destroyed&&generation==captureGeneration){captureConnected(generation,refresh);foreground(false);}});}
            public RootCaptureSession.Request request(){
                long now=SystemClock.elapsedRealtime(),version=cleanFrames.version();
                if(destroyed||generation!=captureGeneration||!capturing||!wantFrame(now,true))return new RootCaptureSession.Request(RootCaptureWire.Request.idleRequest(),epoch.get(),version);
                BoardVision.Region crop=region;boolean cropped=!automaticSearch&&!preparingSelection&&crop!=null&&crop.inside(width,height);
                return new RootCaptureSession.Request(new RootCaptureWire.Request(width,height,cropped?crop.left():0,cropped?crop.top():0,cropped?crop.size():0,false),epoch.get(),version);
            }
            public void heartbeat(){if(generation==captureGeneration)lastImageAt=SystemClock.elapsedRealtime();}
            public void frame(RootCaptureWire.Frame f,RootCaptureSession.Request request){
                if(destroyed||generation!=captureGeneration||!capturing)return;
                long now=SystemClock.elapsedRealtime();lastImageAt=now;
                if(f.screenWidth()!=width||f.screenHeight()!=height){main.post(()->{if(!destroyed&&generation==captureGeneration)rootResized(f.screenWidth(),f.screenHeight());});return;}
                rootSecureSeen=(f.flags()&RootCaptureWire.SECURE_LAYERS)!=0;
                if(request.epoch()!=epoch.get()||request.cleanVersion()!=cleanFrames.version()||paused||selecting||MainActivity.isVisible())return;
                acceptFrame(new Frame(f.pixels(),f.width(),f.height(),f.left(),f.top()),f.screenWidth(),f.screenHeight(),request.cleanVersion(),now);
            }
            public void failed(String reason){main.post(()->{if(!destroyed&&generation==captureGeneration)captureLost(reason);});}
        });
        rootCapture=session;session.start();
    }
    private void rootResized(int w,int h){
        if(!rootMode||!capturing||w<1||h<1||(w==width&&h==height))return;
        boolean refresh=automaticSearch&&automaticRefresh;epoch.incrementAndGet();invalidateSearch();width=w;height=h;region=null;rememberedRegion=null;latest=null;boardVerified=false;stability.clear();cleanFrames.reset();automaticSearch=false;preparingSelection=false;selecting=false;
        if(selector!=null){selector.close();selector=null;}if(review!=null){review.close();review=null;}
        panel.message("Размер Root-кадра изменился · ищу доску");if(!paused)startAutomaticSearch(refresh);
    }
    private ChessEngine ensureEngine(int threads,EngineSettings wanted)throws Exception{
        if(engine==null||!wanted.equals(activeEngineSettings)){
            ChessEngine old=engine;engine=null;activeEngineSettings=null;if(old!=null)old.close();engineLoading=true;
            try{ChessEngine ready=EngineFactory.create(this,threads,wanted);if(destroyed){ready.close();throw new java.io.IOException("Помощник остановлен");}engine=ready;activeEngineSettings=wanted;}finally{engineLoading=false;}
        }
        return engine;
    }
    private void warmEngine(){int generation=captureGeneration;engineQueue.submit(()->{try{if(destroyed||!capturing||generation!=captureGeneration)return;ensureEngine(engineThreads(),EngineFactory.settings(this));}catch(Exception e){main.post(()->{if(!destroyed&&generation==captureGeneration)panel.message("Движок не запустился: "+e.getMessage());});}});}
    private synchronized void releaseCapture(){
        capturing=false;rootStarting=false;rootMode=false;RootCaptureSession oldRoot=rootCapture;rootCapture=null;if(oldRoot!=null)oldRoot.close();automaticRetryAt=0;++captureGeneration;epoch.incrementAndGet();automaticSearch=false;selecting=false;preparingSelection=false;boardVerified=false;region=null;confirmed=null;stability.clear();invalidateSearch();
        if(selector!=null){selector.close();selector=null;}if(review!=null){review.close();review=null;}
        VirtualDisplay oldDisplay=display;ImageReader oldReader=reader;MediaProjection oldProjection=projection;display=null;reader=null;projection=null;latest=null;cleanFrames.reset();
        if(capture!=null)capture.post(()->{try{if(oldDisplay!=null)oldDisplay.release();}catch(RuntimeException ignored){}try{if(oldReader!=null)oldReader.close();}catch(RuntimeException ignored){}try{if(oldProjection!=null)oldProjection.stop();}catch(RuntimeException ignored){}});
        ChessEngine old=engine;engine=null;if(old!=null)old.close();
    }
    private synchronized void captureLost(String reason){
        if(destroyed)return;releaseCapture();captureProblem=reason;prefs().edit().putString("lastCaptureProblem",reason).apply();foreground(false);panel.ensureAttached();panel.message(reason);panel.health("Захват остановлен · ↻ продолжить");
    }
    private void requestCapture(){if(rootStarting)return;if(prefs().getBoolean("rootCapture",false)){startRootCapture(true);return;}startActivity(new Intent(this,MainActivity.class).setAction(RESUME_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
    private synchronized void recoverAssistant(){
        if(destroyed)return;if(!capturing){requestCapture();return;}
        if(paused){paused=false;panel.setPaused(false);}automaticSearch=false;selecting=false;preparingSelection=false;if(selector!=null){selector.close();selector=null;}if(review!=null){review.close();review=null;}
        startAutomaticSearch(true);
    }
    // Some virtual displays deliver an image only when the composition changes.
    // A small indicator outside the board keeps independent clean frames flowing.
    private final Runnable framePulse=new Runnable(){public void run(){
        if(destroyed)return;
        if(capturing&&!paused&&panel!=null)panel.capturePulse();
        main.postDelayed(this,300);
    }};
    private final Runnable healthCheck=new Runnable(){public void run(){
        if(destroyed)return;
        try{
            panel.ensureAttached();long now=SystemClock.elapsedRealtime();
            if(capturing){
                long age=now-lastImageAt;panel.health(paused?"Пауза · окно активно":age>4000?"Жду кадр экрана · ↻ повторить":rootMode?(rootSecureSeen?"Root · защищённый кадр получен":"Root · кадры поступают · ↻"):"Экран подключён · ↻ восстановить");
                if(!paused&&!selecting&&!preparingSelection&&age>8000&&now-lastReaderRepair>12000){
                    if(rootMode)captureLost("Root перестал передавать кадры · нажми ↻");
                    else if(readerRepairs++<2){lastReaderRepair=now;repairReader();}
                    else captureLost("Кадры экрана не поступают · нажми ↻ для нового захвата");
                }
                if(!paused&&region==null&&!automaticSearch&&!selecting&&!preparingSelection&&automaticRetryAt>0&&now>=automaticRetryAt)startAutomaticSearch(automaticRefresh);
                if(!paused&&engineStartedAt>0&&!analysisComplete&&now-engineStartedAt>(engineLoading?60000:Math.max(15000,EngineFactory.settings(CaptureService.this).profile().inferenceTimeoutMillis()+5000))){
                    engineStartedAt=0;ChessEngine stalled=engine;engine=null;invalidateSearch();if(stalled!=null)stalled.close();panel.message("Перезапускаю расчёт · история сохранена");
                }
            }else panel.health(rootStarting?"Root · ожидаю разрешение":"Захват остановлен · ↻ продолжить");
        }catch(RuntimeException e){if(!destroyed){captureProblem="Проверь разрешение плавающего окна";}}
        if(!destroyed)main.postDelayed(this,1000);
    }};
    private synchronized void repairReader(){
        if(!capturing||display==null)return;
        boolean refresh=automaticSearch&&automaticRefresh;
        try{ImageReader old=reader;ImageReader next=createReader(width,height);display.setSurface(null);display.setSurface(next.getSurface());reader=next;if(old!=null)old.close();epoch.incrementAndGet();automaticSearch=false;automaticRetryAt=0;frameErrors=0;lastImageAt=SystemClock.elapsedRealtime();cleanFrames.reset();stability.clear();boardVerified=false;invalidateSearch();panel.message("Восстанавливаю кадры · партия сохранена");if(region==null&&!paused)startAutomaticSearch(refresh);}
        catch(RuntimeException e){captureLost("Не удалось восстановить кадры · нажми ↻ для продолжения");}
    }
    private ImageReader createReader(int w,int h){ImageReader r=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2);r.setOnImageAvailableListener(this::onImage,capture);return r;}
    private synchronized void resize(int w,int h){
        if(destroyed)return;boolean refresh=automaticSearch&&automaticRefresh;boardVerified=false;stability.clear();ImageReader old=reader;ImageReader next=createReader(w,h);display.setSurface(null);display.resize(w,h,getResources().getConfiguration().densityDpi);display.setSurface(next.getSurface());reader=next;width=w;height=h;if(old!=null)old.close();latest=null;region=null;rememberedRegion=null;preparingSelection=false;automaticSearch=false;long gen=epoch.incrementAndGet();invalidateSearch();main.post(()->{if(destroyed||gen!=epoch.get()||!capturing)return;if(selector!=null){selector.close();selector=null;}if(review!=null){review.close();review=null;}selecting=false;if(panel!=null){panel.message("Размер изменился · ищу доску заново");if(!paused)startAutomaticSearch(refresh);}});
    }
    private void onImage(ImageReader source){
        if(destroyed)return;
        try(Image image=source.acquireLatestImage()){
            if(image==null||source!=reader)return;lastImageAt=SystemClock.elapsedRealtime();readerRepairs=0;
            long now=SystemClock.elapsedRealtime(),cleanVersion=cleanFrames.version();if(!wantFrame(now,false))return;
            Image.Plane plane=image.getPlanes()[0];int stride=plane.getRowStride()/plane.getPixelStride();if(plane.getPixelStride()!=4)return;
            int imageWidth=image.getWidth(),imageHeight=image.getHeight();BoardVision.Region crop=region;
            boolean cropped=!automaticSearch&&!preparingSelection&&crop!=null&&crop.inside(imageWidth,imageHeight);
            int left=cropped?crop.left():0,top=cropped?crop.top():0,w=cropped?crop.size():imageWidth,h=cropped?crop.size():imageHeight;
            int[] pixels=new int[w*h];ByteBuffer bytes=plane.getBuffer();bytes.rewind();IntBuffer rgba=bytes.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            // Outside calibration, copy and convert the board only, not the whole display.
            for(int row=0;row<h;row++){rgba.position((top+row)*stride+left);rgba.get(pixels,row*w,w);}
            for(int i=0;i<pixels.length;i++){int px=pixels[i];pixels[i]=(px&0xff00ff00)|((px>>>16)&255)|((px&255)<<16);}
            acceptFrame(new Frame(pixels,w,h,left,top),imageWidth,imageHeight,cleanVersion,now);
        }catch(IllegalStateException e){if(source==reader&&capturing&&++frameErrors>=3)main.post(()->{if(!destroyed&&capturing&&source==reader)repairReader();});}
        catch(RuntimeException e){if(!destroyed&&source==reader)main.post(()->{if(destroyed||!capturing||source!=reader)return;if(++frameErrors>=3)captureLost("Ошибка чтения экрана · нажми ↻ для продолжения");else repairReader();});}
    }
    private boolean wantFrame(long now,boolean fresh){
        if(destroyed||selecting||paused||!capturing)return false;
        if(MainActivity.isVisible()){boardVerified=false;stability.clear();hideArrow();return false;}
        int interval=automaticSearch?250:160;if(now-lastFrame<interval)return false;lastFrame=now;
        return guide!=null||(fresh?cleanFrames.acceptFreshCapture(System.nanoTime()):cleanFrame());
    }
    private void acceptFrame(Frame f,int imageWidth,int imageHeight,long cleanVersion,long now){
        if(destroyed||paused||selecting||!capturing||MainActivity.isVisible())return;
        latest=f;
        // Visibility may change during the pixel copy; recheck the clean-frame
        // boundary before calibration or recognition can consume this image.
        if(guide==null&&(arrowActive.get()||cleanVersion!=cleanFrames.version()))return;
        if(automaticSearch){if(now>=automaticAfter&&processing.compareAndSet(false,true)){long gen=epoch.get();AutoBoardSearch finder=automaticFinder;visionQueue.execute(()->{try{probeAutomatic(f,finder,gen);}catch(Exception e){Log.e("HodCapture","Board search failed",e);main.post(()->{if(gen==epoch.get()&&!destroyed){automaticSearch=false;automaticRetryAt=SystemClock.elapsedRealtime()+5000;panel.setCompact(true);panel.message("Сбой распознавания: "+errorText(e)+" · повторю поиск");}});}finally{processing.set(false);}});}return;}
        if(preparingSelection){if(now>=selectAfter){preparingSelection=false;selecting=true;long gen=epoch.get();boolean position=reviewRequested;main.post(()->{if(position)openReview(f,gen);else openSelector(f,gen);});}return;}
        if(paused||region==null)return;
        BoardVision.Region selected=region;long gen=epoch.get();
        if(panel!=null&&panel.overlaps(selected)){boardVerified=false;stability.clear();hideArrow();if(now-lastPanelMoveAt>800){lastPanelMoveAt=now;main.post(()->{if(panel!=null&&gen==epoch.get()&&!destroyed){panel.placeOutside(selected,imageWidth,imageHeight);panel.message("Перемещаю окно за пределы доски");}});}return;}
        Guide shown=guide;
        if(shown!=null){
            // A screenshot containing our arrow is used ONLY for motion screening.
            // Recognition always waits for an image captured after the arrow was hidden.
            if(!prefs().getBoolean("arrows",true)){hideArrow();return;}
            ArrowGeometry localShape=new ArrowGeometry(f.local(shown.shape().region),shown.shape().move,whiteBottom);
            if(!f.sameArea(shown.clean())||BoardChangeDetector.changed(shown.clean().pixels(),f.pixels(),f.width(),f.height(),localShape)){
                boardVerified=false;stability.clear();hideArrow();main.post(()->{if(panel!=null)panel.message("Позиция меняется · проверяю ход…");});
            }
            return;
        }
        if(!processing.compareAndSet(false,true))return;
        visionQueue.execute(()->{try{if(gen==epoch.get()&&!destroyed&&!paused)process(f,selected,gen);}catch(Exception e){Log.e("HodCapture","Board frame failed",e);recordBoardFailure(gen);main.post(()->{if(panel!=null&&gen==epoch.get()&&!destroyed)panel.message("Ошибка чтения доски: "+errorText(e)+" · повторю поиск");});}finally{processing.set(false);}});
    }
    private void process(Frame f,BoardVision.Region selected,long gen){
        BoardVision model=vision;GameTracker game=tracker;if(model==null||game==null)return;
        BoardVision.Observation o=model.recognize(f.pixels(),f.width(),f.height(),f.local(selected));
        synchronized(this){
            if(gen!=epoch.get()||destroyed||paused||game!=tracker)return;
            if(!stability.accept(o)){
                boardVerified=false;hideArrow();
                recordBoardFailure(gen);
                if(o.uncertain()>0&&++badFrames==3)main.post(()->{if(panel!=null&&gen==epoch.get())panel.message("Проверяю фигуры · окно остаётся активно");});
                return;
            }
            GameTracker.Update update=game.accept(o.board());
            long now=SystemClock.elapsedRealtime();
            if(update.status()==GameTracker.Status.UNRECOGNIZED&&badFrames>=2&&now-lastRecoveryAt>3000){lastRecoveryAt=now;update=game.recover(o.board());}
            if(update.status()==GameTracker.Status.UNRECOGNIZED||update.status()==GameTracker.Status.AMBIGUOUS||update.status()==GameTracker.Status.RECOVERY_LIMIT){
                recordBoardFailure(gen);if(++badFrames==3)main.post(()->{if(panel!=null&&gen==epoch.get())panel.message("Позиция не совпала · перепроверяю расположение доски");});return;
            }
            boardRecovery.reset();badFrames=0;frameErrors=0;lastGoodAt=SystemClock.elapsedRealtime();boardVerified=true;currentConfidence=o.confidence();Position p=game.position();confirmed=new Confirmed(game.initial(),p,game.history(),f);
            if(update.status()==GameTracker.Status.MOVED)saveSession(game,model);
            if(p.legalMoves().isEmpty()){invalidateSearch();main.post(()->{if(panel!=null&&gen==epoch.get())panel.terminal(p);});return;}
            String draw=game.automaticDraw();if(draw!=null){invalidateSearch();main.post(()->{if(panel!=null&&gen==epoch.get())panel.draw(draw);});return;}
            if(!playerSide.ownsTurn(p)){
                if(analyzedPosition!=null)invalidateSearch();
                main.post(()->{if(panel!=null&&gen==epoch.get()&&boardVerified&&!paused&&!playerSide.ownsTurn(p)&&confirmed!=null&&confirmed.position().fen().equals(p.fen()))panel.waiting(playerSide,game.history().size());});return;
            }
            if(analyzedPosition!=null&&analyzedPosition.fen().equals(p.fen())){
                Analysis a=lastAnalysis;long token=searchEpoch.get();if(a!=null)main.post(()->{if(panel!=null&&gen==epoch.get()&&token==searchEpoch.get()&&!paused&&boardVerified&&playerSide.ownsTurn(p)){panel.show(p,a,o.confidence(),analysisComplete,game.history().size());if(analysisComplete)showArrow(p,a,gen,token);}});return;
            }
            beginAnalysis(p,o.confidence(),gen);
        }
    }
    private synchronized void recordBoardFailure(long gen){
        if(gen!=epoch.get()||destroyed||paused)return;
        boardVerified=false;hideArrow();
        if(boardRecovery.failed(SystemClock.elapsedRealtime()))main.post(()->{
            if(gen==epoch.get()&&!destroyed&&capturing&&!paused&&!automaticSearch&&!selecting&&!preparingSelection&&boardRecovery.requested())startAutomaticSearch();
        });
    }
    private static String errorText(Exception e){String text=e.getMessage();return text==null||text.trim().isEmpty()?e.getClass().getSimpleName():text;}
    private void saveSession(GameTracker game,BoardVision model){
        try{sessionStore.save(game,model);}catch(java.io.IOException e){main.post(()->{if(!destroyed)Toast.makeText(this,"Не удалось сохранить партию",Toast.LENGTH_SHORT).show();});}
    }
    private void archiveSession(){
        try{sessionStore.archive(tracker,vision);}catch(java.io.IOException e){Log.e("HodCapture","Previous game backup failed",e);main.post(()->{if(!destroyed)Toast.makeText(this,"Не удалось сохранить копию прошлой партии",Toast.LENGTH_SHORT).show();});}
    }
    private int engineThreads(){return Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-1));}
    private EngineStrength engineStrength(){return EngineStrength.fromPreference(prefs().getInt(EngineStrength.PREFERENCE,EngineStrength.DEFAULT_SKILL));}
    private synchronized void changeStrength(EngineStrength value){
        if(destroyed)return;
        prefs().edit().putInt(EngineStrength.PREFERENCE,value.skill()).apply();changeEngineSettings();
    }
    private synchronized void changeEngineSettings(){
        if(destroyed)return;panel.refreshEngineSettings();invalidateSearch();
        if(confirmed!=null&&boardVerified&&!paused){Position p=confirmed.position();if(playerSide.ownsTurn(p))beginAnalysis(p,currentConfidence,epoch.get());else panel.waiting(playerSide,confirmed.moves().size());}
        else panel.message(EngineFactory.settings(this).label()+" · проверяю доску");
    }
    private synchronized void changePlatform(){
        ChessPlatform selected=ChessPlatform.fromPreference(prefs().getString("chessPlatform",null));if(selected==platform)return;
        // Every recognition/save operation holds this monitor before committing. Invalidate
        // old work before changing stores, so another platform cannot receive its history.
        epoch.incrementAndGet();invalidateSearch();automaticSearch=false;automaticRetryAt=0;selecting=false;preparingSelection=false;
        if(selector!=null){selector.close();selector=null;}if(review!=null){review.close();review=null;}
        if(tracker!=null&&vision!=null)saveSession(tracker,vision);
        platform=selected;sessionStore=new SessionStore(this,selected);tracker=null;vision=null;region=null;rememberedRegion=null;confirmed=null;latest=null;boardVerified=false;stability.clear();boardRecovery.reset();cleanFrames.reset();badFrames=0;
        try{GameMemory.Restored saved=sessionStore.load();if(saved!=null){tracker=saved.game();vision=saved.vision();whiteBottom=vision.whiteBottom();}}catch(java.io.IOException e){panel.message("Сохранение "+selected.label()+" не читается · нужна начальная доска");}
        engineQueue.submit(()->{if(destroyed||platform!=selected)return;try{if(engine instanceof HistoryEngine nativeEngine)nativeEngine.newGame();}catch(Exception e){ChessEngine old=engine;engine=null;if(old!=null)old.close();}});
        panel.message(selected.label()+" выбран · открой его доску");if(capturing&&!paused)startAutomaticSearch();
    }
    private synchronized void changeSide(PlayerSide side){
        if(destroyed)return;playerSide=side;panel.setSide(side);prefs().edit().putBoolean("playerWhite",side==PlayerSide.WHITE).apply();
        invalidateSearch();if(confirmed!=null&&boardVerified&&!paused){Position p=confirmed.position();if(side.ownsTurn(p))beginAnalysis(p,currentConfidence,epoch.get());else panel.waiting(side,confirmed.moves().size());}
        else panel.message("Ты играешь "+side.label()+" · проверяю доску");
    }
    private synchronized void startNewGame(){
        if(destroyed)return;invalidateSearch();long gen=epoch.incrementAndGet();automaticSearch=false;preparingSelection=false;selecting=false;
        if(selector!=null){selector.close();selector=null;}if(review!=null){review.close();review=null;}boardVerified=false;confirmed=null;region=null;stability.clear();
        // Queue the reset behind any in-flight recognition and persistence operation.
        engineQueue.submit(()->{try{if(engine instanceof HistoryEngine nativeEngine)nativeEngine.newGame();}catch(Exception e){ChessEngine old=engine;engine=null;if(old!=null)old.close();}});
        visionQueue.execute(()->{synchronized(CaptureService.this){if(destroyed||gen!=epoch.get())return;archiveSession();tracker=new GameTracker(Position.start());if(vision!=null)saveSession(tracker,vision);else sessionStore.clear();}main.post(()->{if(gen==epoch.get()&&!destroyed&&!paused&&capturing)startAutomaticSearch(true);});});
        panel.message("Новая партия · открой начальную расстановку");
    }
    private synchronized void invalidateSearch(){engineStartedAt=0;searchEpoch.incrementAndGet();if(search!=null)search.cancel(true);search=null;analyzedPosition=null;lastAnalysis=null;analysisComplete=false;hideArrow();}
    private synchronized void beginAnalysis(Position p,float confidence,long gen){
        beginAnalysis(p,confidence,gen,EngineFactory.settings(this).analysisMillis());
    }
    private synchronized void beginAnalysis(Position p,float confidence,long gen,int millis){
        if(destroyed||paused||!boardVerified||!playerSide.ownsTurn(p)||gen!=epoch.get())return;
        Confirmed snapshot=confirmed;if(snapshot==null||!snapshot.position().fen().equals(p.fen()))return;
        invalidateSearch();long token=searchEpoch.get();analyzedPosition=p;lastUiReport=0;engineStartedAt=SystemClock.elapsedRealtime();main.post(()->{if(panel!=null&&token==searchEpoch.get()&&gen==epoch.get()&&!paused&&boardVerified&&playerSide.ownsTurn(p))panel.thinking(p);});
        search=engineQueue.submit(()->{
            try{
                if(token!=searchEpoch.get()||destroyed)return;
                int threads=engineThreads();
                ensureEngine(threads,EngineFactory.settings(this));
                if(engine instanceof UciEngine nativeEngine)nativeEngine.configure(threads,1,engineStrength().skill());
                if(token!=searchEpoch.get())return;
                java.util.function.Consumer<Analysis> report=a->{synchronized(CaptureService.this){if(token!=searchEpoch.get()||gen!=epoch.get()||destroyed||paused||!playerSide.ownsTurn(p))return;lastAnalysis=a;long now=SystemClock.elapsedRealtime();if(now-lastUiReport<180)return;lastUiReport=now;}main.post(()->{if(panel!=null&&token==searchEpoch.get()&&gen==epoch.get()&&!paused&&boardVerified&&playerSide.ownsTurn(p))panel.show(p,a,confidence,false,snapshot.moves().size());});};
                if(engine instanceof HistoryEngine nativeEngine)nativeEngine.analyzeGame(snapshot.initial(),p,snapshot.moves(),millis,report);else engine.analyze(p,millis,report);
                synchronized(CaptureService.this){if(token==searchEpoch.get()&&gen==epoch.get()&&!destroyed&&!paused){analysisComplete=true;engineStartedAt=0;Analysis finalResult=lastAnalysis;if(finalResult!=null)main.post(()->{if(token==searchEpoch.get()&&gen==epoch.get()&&boardVerified&&playerSide.ownsTurn(p)&&!paused){panel.show(p,finalResult,confidence,true,snapshot.moves().size());showArrow(p,finalResult,gen,token);}});}}
            }catch(Exception e){ChessEngine old=engine;engine=null;if(old!=null)old.close();synchronized(this){if(token==searchEpoch.get())analyzedPosition=null;}main.post(()->{if(panel!=null&&token==searchEpoch.get())panel.message("Движок недоступен · "+e.getMessage());});}
        });
    }
    private boolean cleanFrame(){return cleanFrames.accept(System.nanoTime());}
    private synchronized void showArrow(Position p,Analysis a,long gen,long token){
        if(destroyed||paused||!boardVerified||!playerSide.ownsTurn(p)||selecting||preparingSelection||gen!=epoch.get()||token!=searchEpoch.get()||!analysisComplete||guide!=null||!prefs().getBoolean("arrows",true))return;
        Confirmed snapshot=confirmed;if(snapshot==null||!snapshot.position().fen().equals(p.fen()))return;Frame clean=snapshot.frame();BoardVision.Region selected=region;Move move=a.bestMove();
        if(clean==null||selected==null||move==null||!p.legalMoves().contains(move)||panel.overlaps(selected))return;
        try{ArrowGeometry shape=new ArrowGeometry(selected,move,whiteBottom);arrowActive.set(true);arrows.show(shape);guide=new Guide(shape,clean);}
        catch(RuntimeException e){hideArrow();prefs().edit().putBoolean("arrows",false).apply();Toast.makeText(this,"Стрелка недоступна: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private synchronized void hideArrow(){
        if(!arrowActive.getAndSet(false)){guide=null;return;}
        // First block recognition, then clear the guide. Only the main thread can
        // hide a window; allow the compositor time to publish an unpainted frame.
        cleanFrames.block();guide=null;
        main.post(()->{if(arrows!=null&&!arrowActive.get()){arrows.hide();cleanFrames.hidden(System.nanoTime());}});
    }
    private void startAutomaticSearch(){startAutomaticSearch(false);}
    private synchronized void startAutomaticSearch(boolean refresh){
        if(destroyed||!capturing||automaticSearch||selecting||preparingSelection)return;
        if(paused){Toast.makeText(this,"Сначала продолжи работу помощника",Toast.LENGTH_SHORT).show();return;}
        invalidateSearch();long gen=epoch.incrementAndGet();if(region!=null)rememberedRegion=region;region=null;confirmed=null;boardVerified=false;stability.clear();boardRecovery.reset();badFrames=0;
        automaticRefresh=refresh;automaticFinder=new AutoBoardSearch(refresh,platform.label());automaticAttempt=null;automaticMisses=0;automaticAfter=SystemClock.elapsedRealtime()+100;
        panel.prepareSearch();automaticRetryAt=0;automaticSearch=true;
        cleanFrames.block();main.post(()->{if(gen==epoch.get()&&!destroyed){arrows.hide();cleanFrames.hidden(System.nanoTime());panel.capturePulse();}});
        Toast.makeText(this,refresh?"Обновляю доску · проверяю текущую или новую партию":tracker!=null&&!tracker.history().isEmpty()?"Ищу доску · продолжаю сохранённую партию":"Ищу доску. Открой начальную расстановку "+platform.label(),Toast.LENGTH_LONG).show();
        main.postDelayed(()->automaticTimeout(gen),18000);
    }
    private void probeAutomatic(Frame f,AutoBoardSearch finder,long gen){
        if(destroyed||!automaticSearch||gen!=epoch.get()||finder==null)return;
        AutoBoardSearch.Result result=finder.inspect(f.pixels(),f.width(),f.height(),vision,tracker,rememberedRegion);
        synchronized(this){
            if(destroyed||!automaticSearch||gen!=epoch.get())return;
            automaticAttempt=new AutoAttempt(result,f);
            if(result.detection()==null){
                int misses=++automaticMisses;
                if(misses%4==0&&misses<=16){
                    automaticAfter=SystemClock.elapsedRealtime()+350;
                    main.post(()->{if(gen==epoch.get()&&!destroyed)panel.searchCorner(misses/4-1);});
                }
            }else automaticMisses=0;
            if(result.detection()!=null)rememberedRegion=result.detection().region();
            main.post(()->{if(gen==epoch.get()&&!destroyed)panel.message(result.message());});
            if(result.detection()!=null&&panel.overlaps(result.detection().region())){automaticAfter=SystemClock.elapsedRealtime()+300;main.post(()->{if(gen==epoch.get()&&!destroyed)panel.placeOutside(result.detection().region(),f.width(),f.height());});return;}
            if(result.state()!=AutoBoardSearch.State.READY)return;
            AutoBoardSearch.Calibration calibration=result.calibration();
            GameTracker checked=calibration.game().restore();
            if(!calibration.reuseGame()){
                archiveSession();
                engineQueue.submit(()->{try{if(engine instanceof HistoryEngine nativeEngine)nativeEngine.newGame();}catch(Exception e){ChessEngine old=engine;engine=null;if(old!=null)old.close();}});
            }
            tracker=checked;vision=calibration.vision();saveSession(tracker,vision);
            whiteBottom=calibration.whiteBottom();prefs().edit().putBoolean("whiteBottom",whiteBottom).apply();
            stability.clear();boardVerified=false;badFrames=0;region=calibration.region();rememberedRegion=region;selecting=false;automaticSearch=false;
            main.post(()->{if(gen==epoch.get()&&!destroyed){panel.placeOutside(calibration.region(),f.width(),f.height());panel.message("Доска найдена · "+(whiteBottom?"белые снизу":"чёрные снизу"));}});
        }
    }
    private synchronized void automaticTimeout(long gen){
        if(destroyed||gen!=epoch.get()||!automaticSearch)return;
        automaticSearch=false;automaticRetryAt=SystemClock.elapsedRealtime()+5000;epoch.incrementAndGet();AutoAttempt attempt=automaticAttempt;
        panel.setCompact(true);
        if(attempt==null)panel.message("Нет кадра экрана · нажми ↻");
        else{
            AutoBoardSearch.State state=attempt.result().state();
            panel.message(switch(state){
                case NEED_HISTORY -> "История не совпала\nНажми «Позиция», чтобы продолжить";
                case UNCERTAIN_GRID,NEED_ORIENTATION -> "Сетка неоднозначна\nУдержи «Автопоиск»";
                case NEED_INITIAL -> "Нужна начальная доска\nили подтверждение через «Позиция»";
                default -> attempt.result().message();
            });
        }
        if(attempt!=null&&attempt.result().detection()!=null&&attempt.result().detection().automatic()){
            panel.placeOutside(attempt.result().detection().region(),attempt.frame().width(),attempt.frame().height());
        }
    }
    private synchronized void markBoard(BoardVision.Region found,long gen){
        if(destroyed||gen!=epoch.get())return;
        selecting=true;cleanFrames.block();arrowActive.set(true);
        try{arrows.showOutline(found);}catch(RuntimeException e){hideArrow();selecting=false;return;}
        main.postDelayed(()->{if(!destroyed&&gen==epoch.get()&&guide==null){hideArrow();selecting=false;}},800);
    }
    private void selectBoard(){prepareManual(false);}
    private void selectPosition(){prepareManual(true);}
    private synchronized void prepareManual(boolean position){
        if(!capturing||destroyed)return;if(selecting||preparingSelection){Toast.makeText(this,"Сначала заверши открытую проверку",Toast.LENGTH_SHORT).show();return;}
        if(latest==null){panel.message("Жду кадр экрана · попробуй снова через секунду");return;}
        automaticSearch=false;automaticRetryAt=0;invalidateSearch();long gen=epoch.incrementAndGet();boardVerified=false;
        reviewRequested=position;selectionRegion=region!=null?region:rememberedRegion;whiteBottom=vision!=null?vision.whiteBottom():prefs().getBoolean("whiteBottom",true);
        if(selectionRegion!=null&&selectionRegion.inside(width,height))panel.placeOutside(selectionRegion,width,height);else panel.prepareSearch();
        panel.message(position?"Готовлю проверку текущей позиции…":"Проверь найденную сетку и нажми «Готово»");selectAfter=SystemClock.elapsedRealtime()+200;preparingSelection=true;
        main.postDelayed(()->{if(preparingSelection&&gen==epoch.get()){preparingSelection=false;panel.message("Нет свежего кадра · ↻ подключить экран снова");}},5000);
    }
    private void openReview(Frame snapshot,long gen){
        if(destroyed||gen!=epoch.get()){selecting=false;return;}
        BoardVision.Region selected=freshRegion(snapshot,selectionRegion);
        if(selected==null){selecting=false;panel.message("Не вижу границы доски · сначала нажми «Автопоиск»");return;}
        BoardVision.Region chosen=selected;Position reference=tracker==null?Position.start():tracker.position();
        char[] pieces=reference.pieces();
        if(vision!=null)try{pieces=vision.withOrientation(whiteBottom).recognize(snapshot.pixels(),snapshot.width(),snapshot.height(),chosen).board();}catch(RuntimeException ignored){}
        try{review=new PositionReview(this,pieces,reference,whiteBottom,choice->{
            review=null;panel.message("Проверяю подтверждённую позицию…");
            visionQueue.execute(()->{try{
                BoardVision previous=vision==null?new BoardVision(choice.whiteBottom()):vision.withOrientation(choice.whiteBottom());
                BoardVision model=previous.learnConfirmed(snapshot.pixels(),snapshot.width(),snapshot.height(),chosen,choice.position());
                BoardVision.Observation observed=model.recognize(snapshot.pixels(),snapshot.width(),snapshot.height(),chosen);
                if(observed.uncertain()!=0||!choice.position().matches(observed.board()))throw new IllegalArgumentException("Фигуры на кадре не совпали с подтверждёнными. Проверь клетки ещё раз");
                synchronized(CaptureService.this){if(destroyed||gen!=epoch.get())return;
                    GameTracker keep=tracker;boolean same=keep!=null&&keep.position().fen().equals(choice.position().fen());
                    if(!same)archiveSession();
                    tracker=same?keep:new GameTracker(choice.position());vision=model;whiteBottom=choice.whiteBottom();prefs().edit().putBoolean("whiteBottom",whiteBottom).apply();
                    region=chosen;rememberedRegion=chosen;confirmed=null;boardVerified=false;badFrames=0;boardRecovery.reset();stability.clear();saveSession(tracker,vision);
                }
                engineQueue.submit(()->{try{if(engine instanceof HistoryEngine nativeEngine)nativeEngine.newGame();}catch(Exception e){ChessEngine old=engine;engine=null;if(old!=null)old.close();}});
                main.post(()->{if(!destroyed&&gen==epoch.get()){selecting=false;panel.placeOutside(chosen,snapshot.width(),snapshot.height());panel.message("Позиция подтверждена · продолжаю");}});
            }catch(Exception e){main.post(()->{if(!destroyed&&gen==epoch.get()){selecting=false;panel.message("Не удалось восстановить: "+e.getMessage());}});}});
        },()->{review=null;selecting=false;panel.message("Проверка отменена · история сохранена");});}
        catch(RuntimeException e){selecting=false;panel.message("Не удалось открыть проверку: "+e.getMessage());}
    }
    private void openSelector(Frame snapshot,long gen){
        openSelector(snapshot,gen,freshRegion(snapshot,selectionRegion));
    }
    private BoardVision.Region freshRegion(Frame snapshot,BoardVision.Region hint){
        BoardVision.Region valid=hint!=null&&hint.inside(snapshot.width(),snapshot.height())?hint:null;
        BoardLocator.Detection found=new BoardLocator().find(snapshot.pixels(),snapshot.width(),snapshot.height(),valid);
        return found!=null&&!found.ambiguous()?found.region():null;
    }
    private void openSelector(Frame snapshot,long gen,BoardVision.Region initial){
        if(destroyed||gen!=epoch.get()){selecting=false;return;}
        try{selector=new BoardSelector(this,snapshot.pixels(),snapshot.width(),snapshot.height(),initial,whiteBottom,choice->{
            selector=null;region=null;panel.setCompact(true);panel.message("Проверяю начальную позицию…");
            visionQueue.execute(()->{try{
                BoardVision model=vision==null?null:vision.withOrientation(choice.whiteBottom());GameTracker previous=tracker;
                GameTracker.Snapshot checkedGame=null;boolean reuse=false;
                if(model!=null&&previous!=null){BoardVision.Observation seen=model.recognize(snapshot.pixels(),snapshot.width(),snapshot.height(),choice.region());
                    if(seen.uncertain()==0){GameTracker.Snapshot source=previous.snapshot();GameTracker trial=new GameTracker(source.position());GameTracker.Status status=trial.recover(seen.board()).status();
                        reuse=status==GameTracker.Status.SAME||status==GameTracker.Status.MOVED;if(reuse)checkedGame=source.append(trial.history());}}
                if(!reuse){model=new BoardVision(choice.whiteBottom());model.calibrate(snapshot.pixels(),snapshot.width(),snapshot.height(),choice.region());checkedGame=new GameTracker(Position.start()).snapshot();}
                synchronized(CaptureService.this){if(gen!=epoch.get()||destroyed)return;
                if(!reuse)archiveSession();tracker=checkedGame.restore();vision=model;saveSession(tracker,vision);whiteBottom=choice.whiteBottom();prefs().edit().putBoolean("whiteBottom",whiteBottom).apply();
                stability.clear();boardRecovery.reset();boardVerified=false;badFrames=0;region=choice.region();rememberedRegion=region;}
                main.post(()->{if(gen==epoch.get()&&!destroyed){panel.placeOutside(choice.region(),snapshot.width(),snapshot.height());panel.message("Доска готова · жду стабильный кадр");markBoard(choice.region(),gen);}});
            }catch(Exception e){main.post(()->{if(gen==epoch.get()&&!destroyed){selecting=false;panel.message("Калибровка не удалась: "+e.getMessage());}});}});
        },()->{selector=null;selecting=false;panel.message("Проверка отменена · можно повторить автопоиск");});}
        catch(Exception e){selecting=false;panel.message("Не удалось проверить доску: "+e.getMessage());}
    }
    private synchronized void setPaused(boolean value){paused=value;boardVerified=false;stability.clear();invalidateSearch();if(value){automaticSearch=false;preparingSelection=false;selecting=false;if(selector!=null){selector.close();selector=null;}if(review!=null){review.close();review=null;}epoch.incrementAndGet();panel.message("На паузе · захват не обрабатывается");}else{epoch.incrementAndGet();panel.message("Продолжаю · жду стабильную позицию");if(region==null)startAutomaticSearch();}}
    private android.content.SharedPreferences prefs(){return getSharedPreferences("hod",MODE_PRIVATE);}
    @Override public synchronized void onDestroy(){
        destroyed=true;running=false;capturing=false;rootMode=false;rootStarting=false;RootCaptureSession oldRoot=rootCapture;rootCapture=null;if(oldRoot!=null)oldRoot.close();automaticSearch=false;main.removeCallbacks(healthCheck);main.removeCallbacks(framePulse);epoch.incrementAndGet();invalidateSearch();if(selector!=null)selector.close();if(review!=null)review.close();if(panel!=null)panel.close();if(arrows!=null)arrows.close();confirmed=null;
        visionQueue.shutdownNow();engineQueue.shutdownNow();ChessEngine e=engine;if(e!=null)e.close();
        // Release capture resources on their owning thread, after any in-flight image callback.
        if(capture!=null)capture.post(()->{if(display!=null)display.release();if(reader!=null)reader.close();latest=null;if(projection!=null)projection.stop();captureThread.quitSafely();});
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
