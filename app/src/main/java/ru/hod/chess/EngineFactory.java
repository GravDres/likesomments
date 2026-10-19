package ru.hod.chess;

import android.content.Context;
import java.io.File;
import ru.hod.chess.core.*;

final class EngineFactory {
    static ChessEngine create(Context c,int threads)throws Exception{
        File stockfish=new File(c.getApplicationInfo().nativeLibraryDir,"libstockfish.so");
        // A packaged executable is launched from the OS-managed native directory,
        // not a writable app-data directory (Android's W^X requirement).
        if(stockfish.isFile()){
            android.content.SharedPreferences prefs=c.getSharedPreferences("hod",Context.MODE_PRIVATE);
            EngineStrength strength=EngineStrength.fromPreference(prefs.getInt(EngineStrength.PREFERENCE,EngineStrength.DEFAULT_SKILL));
            return new UciEngine(stockfish,threads,prefs.getInt("variations",1),strength.skill());
        }
        throw new java.io.IOException("Stockfish 19 отсутствует в APK. Установи полную сборку из GitHub Actions");
    }
}
