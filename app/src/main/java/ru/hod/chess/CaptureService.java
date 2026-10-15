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
import android.view.*;
import android.widget.Toast;
import java.util.*;
import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import ru.hod.chess.core.*;

public final class CaptureService extends Service {
    public static volatile boolean running;
    private static final String STOP="ru.hod.chess.STOP";
    private final Handler main=new Handler(Looper.getMainLooper());
    private HandlerThread captureThread;private Handler capture;
    private MediaProjection projection;private VirtualDisplay display;private ImageReader reader;
    private final ExecutorService visionQueue=Executors.newSingleThreadExecutor(),engineQueue=Executors.newSingleThreadExecutor();
    private final AtomicBoolean processing=new AtomicBoolean();private final AtomicLong epoch=new AtomicLong(),searchEpoch=new AtomicLong();
    private final AtomicBoolean arrowActive=new AtomicBoolean();
    private volatile boolean destroyed,paused,selecting,preparingSelection;private volatile long selectAfter;private volatile Frame latest;private volatile BoardVision.Region region;
    private volatile ChessEngine engine;private Future<?> search;private FloatingPanel panel;private BoardSelector selector;
    private ArrowOverlay arrows;
    private volatile Guide guide;
    private volatile long cleanAfterNanos;
    private volatile Confirmed confirmed;
    private volatile boolean analysisComplete;
    private volatile boolean automaticSearch;
    private volatile long automaticAfter;
    private volatile AutoBoardSearch automaticFinder;
    private volatile AutoAttempt automaticAttempt;
    private BoardVision vision;private GameTracker tracker;private char[] candidate;private int stable,badFrames;private boolean whiteBottom;
    private long lastFrame;private int width,height;private volatile Analysis lastAnalysis;private volatile Position analyzedPosition;private volatile float currentConfidence;
    private record Frame(int[] pixels,int width,int height) {}
    private record Guide(ArrowGeometry shape, Frame clean) {}
    private record Confirmed(Position position,List<Move> moves,Frame frame) {}
    private record AutoAttempt(AutoBoardSearch.Result result,Frame frame) {}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(running)return START_NOT_STICKY;
        if(intent==null||!intent.hasExtra("permission")){stopSelf();return START_NOT_STICKY;}
        try{
            NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("capture","Плавающий помощник",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CaptureService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n=new Notification.Builder(this,"capture").setSmallIcon(ru.hod.chess.R.drawable.ic_hod).setContentTitle("ХОД · помощник активен").setContentText("Обработка экрана на устройстве").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Остановить",stop).build()).build();
            if(Build.VERSION.SDK_INT>=29)startForeground(1,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);else startForeground(1,n);
            captureThread=new HandlerThread("screen-frames");captureThread.start();capture=new Handler(captureThread.getLooper());
            Intent permission=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra("permission",Intent.class):intent.getParcelableExtra("permission");
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("code",Activity.RESULT_CANCELED),permission);
            projection.registerCallback(new MediaProjection.Callback(){
                @Override public void onStop(){main.post(()->{if(!destroyed){Toast.makeText(CaptureService.this,"Захват экрана завершён",Toast.LENGTH_SHORT).show();stopSelf();}});}
                @Override public void onCapturedContentResize(int w,int h){if(display!=null&&(w!=width||h!=height)&&w>0&&h>0)resize(w,h);}
            },capture);
            arrows=new ArrowOverlay(this);
            panel=new FloatingPanel(this,new FloatingPanel.Actions(){public void calibrate(){startAutomaticSearch();}public void manual(){selectBoard();}public void pause(boolean value){setPaused(value);}public void arrows(boolean value){prefs().edit().putBoolean("arrows",value).apply();if(!value)hideArrow();}public void deeper(){Position p=analyzedPosition;if(paused||p==null){Toast.makeText(CaptureService.this,"Сначала дождись подтверждённой позиции",Toast.LENGTH_SHORT).show();return;}beginAnalysis(p,currentConfidence,epoch.get(),8000);}public void stop(){stopSelf();}});
            WindowManager wm=getSystemService(WindowManager.class);DisplayMetrics metrics=new DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(metrics);width=metrics.widthPixels;height=metrics.heightPixels;reader=createReader(width,height);
            display=projection.createVirtualDisplay("ХОД — локальный анализ",width,height,getResources().getConfiguration().densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,capture);running=true;main.postDelayed(()->{if(!destroyed&&!paused&&!selecting&&!preparingSelection)startAutomaticSearch();},700);
        }catch(Exception e){Toast.makeText(this,"Не удалось начать захват: "+e.getMessage(),Toast.LENGTH_LONG).show();stopSelf();}
        return START_NOT_STICKY;
    }
    private ImageReader createReader(int w,int h){ImageReader r=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2);r.setOnImageAvailableListener(this::onImage,capture);return r;}
    private synchronized void resize(int w,int h){
        if(destroyed)return;ImageReader old=reader;ImageReader next=createReader(w,h);display.setSurface(null);display.resize(w,h,getResources().getConfiguration().densityDpi);display.setSurface(next.getSurface());reader=next;width=w;height=h;if(old!=null)old.close();latest=null;region=null;preparingSelection=false;automaticSearch=false;epoch.incrementAndGet();invalidateSearch();main.post(()->{if(selector!=null){selector.close();selector=null;}selecting=false;if(panel!=null){panel.hide(false);panel.message("Размер изменился · ищу доску заново");if(!paused)startAutomaticSearch();}});
    }
    private void onImage(ImageReader source){
        if(destroyed)return;
        try(Image image=source.acquireLatestImage()){
            if(image==null||selecting)return;long now=SystemClock.elapsedRealtime();int interval=automaticSearch?250:prefs().getInt("millis",1500)==600?1000:450;if(now-lastFrame<interval)return;lastFrame=now;
            if(guide==null&&!cleanFrame(image.getTimestamp()))return;
            Image.Plane plane=image.getPlanes()[0];int stride=plane.getRowStride()/plane.getPixelStride();if(plane.getPixelStride()!=4)return;
            int w=image.getWidth(),h=image.getHeight();int[] pixels=new int[w*h];ByteBuffer bytes=plane.getBuffer();bytes.rewind();IntBuffer rgba=bytes.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            // The last scanline may omit row padding. Copy only actual pixels per row.
            for(int row=0;row<h;row++){rgba.position(row*stride);rgba.get(pixels,row*w,w);}
            for(int i=0;i<pixels.length;i++){int px=pixels[i];pixels[i]=(px&0xff00ff00)|((px>>>16)&255)|((px&255)<<16);}
            Frame f=new Frame(pixels,w,h);latest=f;
            // Visibility may change during the pixel copy; recheck the clean-frame
            // boundary before calibration or recognition can consume this image.
            if(guide==null&&!cleanFrame(image.getTimestamp()))return;
            if(automaticSearch){if(now>=automaticAfter&&processing.compareAndSet(false,true)){long gen=epoch.get();AutoBoardSearch finder=automaticFinder;visionQueue.execute(()->{try{probeAutomatic(f,finder,gen);}catch(Exception e){main.post(()->{if(gen==epoch.get()&&!destroyed){automaticSearch=false;panel.hide(false);panel.setCompact(true);panel.message("Не удалось найти доску · нажми «Найти доску»");}});}finally{processing.set(false);}});}return;}
            if(preparingSelection){if(now>=selectAfter){preparingSelection=false;selecting=true;long gen=epoch.get();main.post(()->openSelector(f,gen));}return;}
            if(paused||region==null)return;
            BoardVision.Region selected=region;long gen=epoch.get();
            if(panel!=null&&panel.overlaps(selected)){invalidateSearch();main.post(()->{if(panel!=null)panel.message("Окно перекрывает доску");});return;}
            Guide shown=guide;
            if(shown!=null){
                // A screenshot containing our arrow is used ONLY for motion screening.
                // Recognition always waits for an image captured after the arrow was hidden.
                if(!prefs().getBoolean("arrows",true)){hideArrow();return;}
                if(shown.clean().width()!=w||shown.clean().height()!=h||BoardChangeDetector.changed(shown.clean().pixels(),pixels,w,h,shown.shape())){
                    invalidateSearch();main.post(()->{if(panel!=null)panel.message("Позиция меняется · обновляю стрелку…");});
                }
                return;
            }
            if(!processing.compareAndSet(false,true))return;
            visionQueue.execute(()->{try{if(gen==epoch.get()&&!destroyed&&!paused)process(f,selected,gen);}catch(Exception e){invalidateSearch();main.post(()->{if(panel!=null)panel.message("Не удалось прочитать кадр · проверь доску");});}finally{processing.set(false);}});
        }catch(IllegalStateException ignored){}
        catch(RuntimeException e){processing.set(false);if(!destroyed)main.post(()->{Toast.makeText(this,"Ошибка захвата: "+e.getMessage(),Toast.LENGTH_LONG).show();stopSelf();});}
    }
    private void process(Frame f,BoardVision.Region selected,long gen){
        if(vision==null||tracker==null)return;BoardVision.Observation o=vision.recognize(f.pixels(),f.width(),f.height(),selected);if(gen!=epoch.get()||destroyed||paused)return;
        if(o.uncertain()>0){candidate=null;stable=0;badFrames++;invalidateSearch();main.post(()->{if(panel!=null)panel.message("Фигуры не распознаны · жду стабильный кадр");});return;}
        if(Arrays.equals(candidate,o.board()))stable++;else{candidate=o.board();stable=1;invalidateSearch();main.post(()->{if(panel!=null)panel.message("Позиция меняется…");});}
        if(stable<2)return;
        GameTracker.Update update=tracker.accept(o.board());
        // A legal return to the initial arrangement still belongs to the same game
        // (and must retain repetition counts). Only reset after legal matching fails.
        if(update.status()==GameTracker.Status.UNRECOGNIZED&&Position.start().matches(o.board())){tracker=new GameTracker(Position.start());update=tracker.accept(o.board());}
        if(update.status()==GameTracker.Status.UNRECOGNIZED||update.status()==GameTracker.Status.AMBIGUOUS){badFrames++;invalidateSearch();main.post(()->{if(panel!=null)panel.message("Позиция не сходится · проверь начальную расстановку");});return;}
        badFrames=0;currentConfidence=o.confidence();Position p=tracker.position();confirmed=new Confirmed(p,tracker.history(),f);
        if(p.legalMoves().isEmpty()){invalidateSearch();main.post(()->{if(panel!=null&&gen==epoch.get())panel.terminal(p);});return;}
        String draw=tracker.automaticDraw();if(draw!=null){invalidateSearch();main.post(()->{if(panel!=null&&gen==epoch.get())panel.draw(draw);});return;}
        if(analyzedPosition!=null&&analyzedPosition.fen().equals(p.fen())){
            Analysis a=lastAnalysis;long token=searchEpoch.get();if(a!=null)main.post(()->{if(panel!=null&&gen==epoch.get()&&token==searchEpoch.get()&&!paused){panel.show(p,a,o.confidence());if(analysisComplete)showArrow(p,a,gen,token);}});return;
        }
        beginAnalysis(p,o.confidence(),gen);
    }
    private synchronized void invalidateSearch(){searchEpoch.incrementAndGet();if(search!=null)search.cancel(true);search=null;analyzedPosition=null;lastAnalysis=null;analysisComplete=false;hideArrow();}
    private synchronized void beginAnalysis(Position p,float confidence,long gen){
        beginAnalysis(p,confidence,gen,prefs().getInt("millis",1500));
    }
    private synchronized void beginAnalysis(Position p,float confidence,long gen,int millis){
        if(destroyed||paused||gen!=epoch.get())return;
        Confirmed snapshot=confirmed;if(snapshot==null||!snapshot.position().fen().equals(p.fen()))return;
        invalidateSearch();long token=searchEpoch.get();analyzedPosition=p;main.post(()->{if(panel!=null&&gen==epoch.get()&&!paused)panel.thinking(p);});
        search=engineQueue.submit(()->{
            try{
                if(token!=searchEpoch.get()||destroyed)return;
                int threads=millis<=600?1:millis>=4000?4:2;
                if(engine==null)engine=EngineFactory.create(this,threads);
                if(engine instanceof UciEngine nativeEngine)nativeEngine.configure(threads,prefs().getInt("variations",1));
                if(token!=searchEpoch.get())return;
                java.util.function.Consumer<Analysis> report=a->{synchronized(CaptureService.this){if(token!=searchEpoch.get()||gen!=epoch.get()||destroyed||paused)return;lastAnalysis=a;}main.post(()->{if(panel!=null&&token==searchEpoch.get()&&gen==epoch.get()&&!paused)panel.show(p,a,confidence);});};
                if(engine instanceof UciEngine nativeEngine)nativeEngine.analyzeGame(p,snapshot.moves(),millis,report);else engine.analyze(p,millis,report);
                synchronized(CaptureService.this){if(token==searchEpoch.get()&&gen==epoch.get()&&!destroyed&&!paused){analysisComplete=true;Analysis finalResult=lastAnalysis;if(finalResult!=null)main.post(()->showArrow(p,finalResult,gen,token));}}
            }catch(Exception e){ChessEngine old=engine;engine=null;if(old!=null)old.close();synchronized(this){if(token==searchEpoch.get())analyzedPosition=null;}main.post(()->{if(panel!=null&&token==searchEpoch.get())panel.message("Движок недоступен · "+e.getMessage());});}
        });
    }
    private boolean cleanFrame(long timestamp){long after=cleanAfterNanos;return after!=Long.MAX_VALUE&&System.nanoTime()>=after&&(timestamp<=0||timestamp>=after);}
    private synchronized void showArrow(Position p,Analysis a,long gen,long token){
        if(destroyed||paused||selecting||preparingSelection||gen!=epoch.get()||token!=searchEpoch.get()||!analysisComplete||guide!=null||!prefs().getBoolean("arrows",true))return;
        Confirmed snapshot=confirmed;if(snapshot==null||!snapshot.position().fen().equals(p.fen()))return;Frame clean=snapshot.frame();BoardVision.Region selected=region;Move move=a.bestMove();
        if(clean==null||selected==null||move==null||!p.legalMoves().contains(move)||panel.overlaps(selected))return;
        try{ArrowGeometry shape=new ArrowGeometry(selected,move,whiteBottom);arrowActive.set(true);arrows.show(shape);guide=new Guide(shape,clean);}
        catch(RuntimeException e){hideArrow();prefs().edit().putBoolean("arrows",false).apply();Toast.makeText(this,"Стрелка недоступна: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private synchronized void hideArrow(){
        if(!arrowActive.getAndSet(false)){guide=null;return;}
        // First block recognition, then clear the guide. Only the main thread can
        // hide a window; allow the compositor time to publish an unpainted frame.
        cleanAfterNanos=Long.MAX_VALUE;guide=null;
        main.post(()->{if(arrows!=null&&!arrowActive.get()){arrows.hide();cleanAfterNanos=System.nanoTime()+70_000_000L;}});
    }
    private synchronized void startAutomaticSearch(){
        if(destroyed||automaticSearch||selecting||preparingSelection)return;
        if(paused){Toast.makeText(this,"Сначала продолжи работу помощника",Toast.LENGTH_SHORT).show();return;}
        invalidateSearch();long gen=epoch.incrementAndGet();region=null;confirmed=null;
        automaticFinder=new AutoBoardSearch();automaticAttempt=null;automaticAfter=SystemClock.elapsedRealtime()+250;
        panel.hide(true);automaticSearch=true;
        Toast.makeText(this,"Ищу доску. Открой начальную расстановку Chess.com",Toast.LENGTH_LONG).show();
        main.postDelayed(()->automaticTimeout(gen),18000);
    }
    private void probeAutomatic(Frame f,AutoBoardSearch finder,long gen){
        if(destroyed||!automaticSearch||gen!=epoch.get()||finder==null)return;
        AutoBoardSearch.Result result=finder.inspect(f.pixels(),f.width(),f.height(),vision,tracker);
        synchronized(this){
            if(destroyed||!automaticSearch||gen!=epoch.get())return;
            automaticAttempt=new AutoAttempt(result,f);
            if(result.state()!=AutoBoardSearch.State.READY)return;
            AutoBoardSearch.Calibration calibration=result.calibration();
            vision=calibration.vision();if(!calibration.reuseGame())tracker=new GameTracker(Position.start());
            whiteBottom=calibration.whiteBottom();prefs().edit().putBoolean("whiteBottom",whiteBottom).apply();
            candidate=null;stable=0;badFrames=0;region=calibration.region();selecting=true;automaticSearch=false;
            main.post(()->{if(gen==epoch.get()&&!destroyed){panel.hide(false);panel.placeOutside(calibration.region(),f.width(),f.height());panel.message("Доска найдена · "+(whiteBottom?"белые снизу":"чёрные снизу"));markBoard(calibration.region(),gen);}});
        }
    }
    private synchronized void automaticTimeout(long gen){
        if(destroyed||gen!=epoch.get()||!automaticSearch)return;
        automaticSearch=false;long next=epoch.incrementAndGet();AutoAttempt attempt=automaticAttempt;
        if(attempt!=null&&(attempt.result().state()==AutoBoardSearch.State.UNCERTAIN_GRID||attempt.result().state()==AutoBoardSearch.State.NEED_ORIENTATION)){
            BoardLocator.Detection detected=attempt.result().detection();whiteBottom=detected.orientationKnown()?detected.whiteBottom():prefs().getBoolean("whiteBottom",true);
            selecting=true;openSelector(attempt.frame(),next,detected.region());return;
        }
        panel.hide(false);panel.setCompact(true);
        panel.message(attempt==null?"Нет кадра экрана · проверь разрешение захвата":attempt.result().message()+" · нажми «Найти доску»");
        if(attempt!=null&&attempt.result().detection()!=null){BoardVision.Region found=attempt.result().detection().region();panel.placeOutside(found,attempt.frame().width(),attempt.frame().height());markBoard(found,next);}
    }
    private synchronized void markBoard(BoardVision.Region found,long gen){
        if(destroyed||gen!=epoch.get())return;
        selecting=true;cleanAfterNanos=Long.MAX_VALUE;arrowActive.set(true);
        try{arrows.showOutline(found);}catch(RuntimeException e){hideArrow();selecting=false;return;}
        main.postDelayed(()->{if(!destroyed&&gen==epoch.get()&&guide==null){hideArrow();selecting=false;}},800);
    }
    private void selectBoard(){if(latest==null){Toast.makeText(this,"Жду первый кадр экрана",Toast.LENGTH_SHORT).show();return;}if(selecting||preparingSelection||automaticSearch)return;invalidateSearch();long gen=epoch.incrementAndGet();whiteBottom=prefs().getBoolean("whiteBottom",true);panel.hide(true);selectAfter=SystemClock.elapsedRealtime()+250;preparingSelection=true;
        main.postDelayed(()->{if(preparingSelection&&gen==epoch.get()){preparingSelection=false;panel.hide(false);panel.message("Нет свежего кадра · открой Chess.com и попробуй снова");}},4000);
    }
    private void openSelector(Frame snapshot,long gen){
        openSelector(snapshot,gen,null);
    }
    private void openSelector(Frame snapshot,long gen,BoardVision.Region initial){
        if(destroyed||gen!=epoch.get()){selecting=false;return;}
        try{selector=new BoardSelector(this,snapshot.pixels(),snapshot.width(),snapshot.height(),initial,whiteBottom,choice->{
            selector=null;region=null;panel.hide(false);panel.setCompact(true);panel.message("Проверяю начальную позицию…");
            visionQueue.execute(()->{try{
                BoardVision model=new BoardVision(choice.whiteBottom());model.calibrate(snapshot.pixels(),snapshot.width(),snapshot.height(),choice.region());
                if(gen!=epoch.get()||destroyed)return;
                vision=model;tracker=new GameTracker(Position.start());whiteBottom=choice.whiteBottom();prefs().edit().putBoolean("whiteBottom",whiteBottom).apply();
                candidate=null;stable=0;badFrames=0;region=choice.region();
                main.post(()->{if(gen==epoch.get()&&!destroyed){panel.placeOutside(choice.region(),snapshot.width(),snapshot.height());panel.message("Доска готова · жду стабильный кадр");markBoard(choice.region(),gen);}});
            }catch(Exception e){main.post(()->{if(gen==epoch.get()&&!destroyed){selecting=false;panel.message("Калибровка не удалась: "+e.getMessage());}});}});
        },()->{selector=null;selecting=false;panel.hide(false);panel.message("Проверка отменена · можно повторить автопоиск");});}
        catch(Exception e){selecting=false;panel.hide(false);panel.message("Не удалось проверить доску: "+e.getMessage());}
    }
    private synchronized void setPaused(boolean value){paused=value;invalidateSearch();if(value){automaticSearch=false;preparingSelection=false;selecting=false;if(selector!=null){selector.close();selector=null;}epoch.incrementAndGet();panel.hide(false);panel.message("На паузе · захват не обрабатывается");}else{epoch.incrementAndGet();panel.message("Продолжаю · жду стабильную позицию");if(region==null)startAutomaticSearch();}}
    private android.content.SharedPreferences prefs(){return getSharedPreferences("hod",MODE_PRIVATE);}
    @Override public synchronized void onDestroy(){
        destroyed=true;running=false;automaticSearch=false;epoch.incrementAndGet();invalidateSearch();if(selector!=null)selector.close();if(panel!=null)panel.close();if(arrows!=null)arrows.close();confirmed=null;
        visionQueue.shutdownNow();engineQueue.shutdownNow();ChessEngine e=engine;if(e!=null)e.close();
        // Release capture resources on their owning thread, after any in-flight image callback.
        if(capture!=null)capture.post(()->{if(display!=null)display.release();if(reader!=null)reader.close();latest=null;if(projection!=null)projection.stop();captureThread.quitSafely();});
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
