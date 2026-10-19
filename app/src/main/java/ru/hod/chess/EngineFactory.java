package ru.hod.chess;

import android.content.Context;
import java.io.File;
import java.io.InputStream;
import java.util.Properties;
import ru.hod.chess.core.*;

final class EngineFactory {
    static EngineSettings settings(Context c){
        android.content.SharedPreferences p=c.getSharedPreferences("hod",Context.MODE_PRIVATE);
        return new EngineSettings(EngineMode.fromPreference(p.getString(EngineMode.PREFERENCE,null)),MaiaModel.fromPreference(p.getInt(MaiaModel.PREFERENCE,2200)),MaiaNetwork.fromPreference(p.getString(MaiaNetwork.PREFERENCE,null)),TimeControlProfile.fromPreference(p.getString(TimeControlProfile.PREFERENCE,null)),EngineStrength.fromPreference(p.getInt(EngineStrength.PREFERENCE,EngineStrength.DEFAULT_SKILL)),p.getInt("millis",1500),p.getBoolean("maiaSampling",true));
    }
    static ChessEngine create(Context c,int threads)throws Exception{
        return create(c,threads,settings(c));
    }
    static ChessEngine create(Context c,int threads,EngineSettings settings)throws Exception{
        if(settings.mode().usesMaia()){
            Properties manifest=new Properties();try(InputStream in=c.getAssets().open("maia/models.properties")){manifest.load(in);}catch(java.io.IOException e){throw new java.io.IOException("Maia-3 отсутствует в APK. Нужна полная сборка из Actions",e);}
            String name=settings.network().fileName();File model=ModelStore.ensure(new File(new File(c.getFilesDir(),"maia"),name),manifest.getProperty(name+".sha256"),()->c.getAssets().open("maia/"+name));
            MaiaEngine maia=new MaiaEngine(model,settings,threads);
            if(settings.mode()==EngineMode.MAIA)return maia;
            try{
                File verifier=new File(c.getApplicationInfo().nativeLibraryDir,"libstockfish.so");
                if(!verifier.isFile())throw new java.io.IOException("Для проверки Maia нужен Stockfish в полной сборке Actions");
                return new TacticalMaiaEngine(maia,new UciEngine(verifier,threads,1,20));
            }catch(Exception e){maia.close();throw e;}
        }
        File stockfish=new File(c.getApplicationInfo().nativeLibraryDir,"libstockfish.so");
        // A packaged executable is launched from the OS-managed native directory,
        // not a writable app-data directory (Android's W^X requirement).
        if(stockfish.isFile()){
            android.content.SharedPreferences prefs=c.getSharedPreferences("hod",Context.MODE_PRIVATE);
            return new UciEngine(stockfish,threads,prefs.getInt("variations",1),settings.strength().skill());
        }
        throw new java.io.IOException("Stockfish 19 отсутствует в APK. Установи полную сборку из GitHub Actions");
    }
}
