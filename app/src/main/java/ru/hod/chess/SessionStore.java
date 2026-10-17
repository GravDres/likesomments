package ru.hod.chess;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import ru.hod.chess.core.*;

/** Atomic private storage: never replaces a good game with a partial write. */
final class SessionStore {
    private final AtomicFile file;
    private final String archiveFile;
    SessionStore(Context context,ChessPlatform platform){file=new AtomicFile(new File(context.getFilesDir(),platform.sessionFile()));archiveFile=platform.archiveFile();}
    synchronized GameMemory.Restored load()throws IOException{
        // Let AtomicFile restore its backup before deciding that no game exists.
        try{return GameMemory.decode(file.readFully());}catch(FileNotFoundException missing){return null;}
    }
    synchronized void save(GameTracker game,BoardVision vision)throws IOException{
        byte[] bytes=GameMemory.encode(game,vision);FileOutputStream stream=null;
        try{stream=file.startWrite();stream.write(bytes);file.finishWrite(stream);}
        catch(IOException e){if(stream!=null)file.failWrite(stream);throw e;}
    }
    synchronized void clear(){file.delete();}
    /** Keep the last game intact before automatic detection starts a different one. */
    synchronized void archive(GameTracker game,BoardVision vision)throws IOException{
        if(game==null||vision==null||game.history().isEmpty()&&game.initial().fen().equals(Position.START))return;
        AtomicFile previous=new AtomicFile(new File(file.getBaseFile().getParentFile(),archiveFile));
        byte[] bytes=GameMemory.encode(game,vision);FileOutputStream stream=null;
        try{stream=previous.startWrite();stream.write(bytes);previous.finishWrite(stream);}
        catch(IOException e){if(stream!=null)previous.failWrite(stream);throw e;}
    }
}
