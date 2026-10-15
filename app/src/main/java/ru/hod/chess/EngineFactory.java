package ru.hod.chess;

import android.content.Context;
import java.io.File;
import ru.hod.chess.core.*;

final class EngineFactory {
    static ChessEngine create(Context c,int threads)throws Exception{
        File stockfish=new File(c.getApplicationInfo().nativeLibraryDir,"libstockfish.so");
        // A packaged executable is launched from the OS-managed native directory,
        // not a writable app-data directory (Android's W^X requirement).
        if(stockfish.isFile())return new UciEngine(stockfish,threads,c.getSharedPreferences("hod",Context.MODE_PRIVATE).getInt("variations",1));
        return new LocalEngine();
    }
}
