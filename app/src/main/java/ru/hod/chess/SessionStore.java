package ru.hod.chess;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import ru.hod.chess.core.*;

/** Atomic private storage: never replaces a good game with a partial write. */
final class SessionStore {
    private final AtomicFile file;
    SessionStore(Context context){file=new AtomicFile(new File(context.getFilesDir(),"last-game.hod"));}
    synchronized GameMemory.Restored load()throws IOException{
        if(!file.getBaseFile().exists())return null;
        return GameMemory.decode(file.readFully());
    }
    synchronized void save(GameTracker game,BoardVision vision)throws IOException{
        byte[] bytes=GameMemory.encode(game,vision);FileOutputStream stream=null;
        try{stream=file.startWrite();stream.write(bytes);file.finishWrite(stream);}
        catch(IOException e){if(stream!=null)file.failWrite(stream);throw e;}
    }
    synchronized void clear(){file.delete();}
}
