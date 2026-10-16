package ru.hod.chess.core;

/** Debounces recognition failures, including confident but impossible positions. */
public final class BoardRecoveryGate {
    private long since=-1;
    private int failures;
    private boolean requested;
    public synchronized boolean failed(long elapsedMillis){
        if(since<0)since=elapsedMillis;
        failures++;
        if(!requested&&failures>=4&&elapsedMillis-since>=3000){requested=true;return true;}
        return false;
    }
    public synchronized void reset(){since=-1;failures=0;requested=false;}
    public synchronized boolean requested(){return requested;}
}
