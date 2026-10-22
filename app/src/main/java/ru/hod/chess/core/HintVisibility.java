package ru.hod.chess.core;

/** Hides advice for one confirmed position; recognition, history and search can continue. */
public final class HintVisibility {
    public static final String PREFERENCE="hideHintOnTouch";
    private boolean enabled=true,ignoreMarkers;
    private String hiddenFen,currentFen;
    public synchronized void enabled(boolean value){enabled=value;if(!value){hiddenFen=null;ignoreMarkers=false;}}
    public synchronized void confirmed(Position p){
        String fen=p.fen();if(!fen.equals(currentFen)){currentFen=fen;hiddenFen=null;ignoreMarkers=false;}
    }
    public synchronized boolean touch(Position p){
        if(!enabled||p==null)return false;
        // A touch can arrive before the next clean screenshot; use the last confirmed position.
        currentFen=p.fen();ignoreMarkers=false;boolean changed=!currentFen.equals(hiddenFen);hiddenFen=currentFen;return changed;
    }
    public synchronized boolean markers(Position p,int count){
        confirmed(p);if(count==0)ignoreMarkers=false;
        if(!enabled||ignoreMarkers||count<1)return false;
        boolean changed=!p.fen().equals(hiddenFen);hiddenFen=p.fen();return changed;
    }
    public synchronized boolean hidden(Position p){return enabled&&p!=null&&p.fen().equals(hiddenFen);}
    /** Explicit reveal overrides already visible dots until deselection or a fresh touch. */
    public synchronized void reveal(){hiddenFen=null;ignoreMarkers=true;}
    public synchronized void reset(){hiddenFen=null;currentFen=null;ignoreMarkers=false;}
}
