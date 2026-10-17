package ru.hod.chess.core;

/** Source timestamps may use another clock. Gate only on local arrival time and buffer boundaries. */
public final class CleanFrameGate {
    private boolean blocked;private long version;
    private long localReadyAt;
    private int discard;
    public synchronized void block(){blocked=true;version++;}
    public synchronized void hidden(long localNow){blocked=false;version++;localReadyAt=localNow+70_000_000L;discard=2;}
    public synchronized void reset(){blocked=false;version++;localReadyAt=0;discard=0;}
    public synchronized long version(){return version;}
    /** A synchronous root request starts after this boundary and has no queued display buffers. */
    public synchronized boolean acceptFreshCapture(long localNow){return !blocked&&localNow>=localReadyAt;}
    public synchronized boolean accept(long localNow){
        if(blocked||localNow<localReadyAt)return false;
        if(discard>0){discard--;return false;}
        return true;
    }
}
