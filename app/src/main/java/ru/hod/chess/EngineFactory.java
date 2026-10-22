package ru.hod.chess;

import android.content.Context;
import java.io.File;
import java.io.InputStream;
import java.util.Properties;
import ru.hod.chess.core.*;

final class EngineFactory {
    static EngineSettings settings(Context c){
        android.content.SharedPreferences p=c.getSharedPreferences("hod",Context.MODE_PRIVATE);
        int cores=Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-1));
        return new EngineSettings(TimeControlProfile.fromPreference(p.getString(TimeControlProfile.PREFERENCE,null)),
                p.getInt("millis",1500),Math.min(cores,p.getInt("caissaThreads",2)),p.getInt("caissaHash",128),
                SearchLevel.fromPreference(p.getString(SearchLevel.PREFERENCE,null)),
                EngineKind.fromPreference(p.getString(EngineKind.PREFERENCE,null)),AdviceStyle.fromPreference(p.getString(AdviceStyle.PREFERENCE,null)));
    }
    static ChessEngine create(Context c,int threads)throws Exception{return create(c,threads,settings(c));}
    static ChessEngine create(Context c,int threads,EngineSettings settings)throws Exception{
        File executable=new File(c.getApplicationInfo().nativeLibraryDir,settings.kind().binary());
        if(!executable.isFile())throw new java.io.IOException(settings.kind().label()+" отсутствует в APK. Установи полную сборку из Actions");
        if(settings.kind()==EngineKind.TEXEL){
            Properties texel=new Properties();try(InputStream in=c.getAssets().open("texel/engine.properties")){texel.load(in);}
            if(!"1.12".equals(texel.getProperty("version"))||!"bd5698c67f3c1841db623296cfd49d4a37abd5a3".equals(texel.getProperty("commit")))throw new java.io.IOException("Несовместимая комплектация Texel");
            return configured(new UciEngine(executable,EngineKind.TEXEL,null,settings.effectiveThreads(),settings.variations(c.getSharedPreferences("hod",Context.MODE_PRIVATE).getInt("variations",1)),settings.hashMiB()),settings);
        }
        Properties manifest=new Properties();
        try(InputStream in=c.getAssets().open("caissa/engine.properties")){manifest.load(in);}
        String name=manifest.getProperty("network");
        if(!"2.0".equals(manifest.getProperty("version"))||!"eval-ml-8-152B-spsa.pnn".equals(name))
            throw new java.io.IOException("Несовместимая комплектация Caissa");
        File model=ModelStore.ensure(new File(new File(c.getFilesDir(),"caissa"),name),
                manifest.getProperty("network.sha256"),()->c.getAssets().open("caissa/"+name));
        UciEngine engine=new UciEngine(executable,model,Math.min(threads,settings.threads()),
                settings.variations(c.getSharedPreferences("hod",Context.MODE_PRIVATE).getInt("variations",1)),settings.hashMiB());
        return configured(engine,settings);
    }
    private static UciEngine configured(UciEngine engine,EngineSettings settings)throws Exception{
        try{engine.setSearchLevel(settings.level());engine.setAdviceStyle(settings.advice());return engine;}
        catch(Exception failure){engine.close();throw failure;}
    }
}
