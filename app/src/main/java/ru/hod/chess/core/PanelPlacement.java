package ru.hod.chess.core;

/** Separates user-selected positions from temporary automatic placement. */
public final class PanelPlacement {
    public record Point(int x,int y){}
    private Point panel,bubble,shown,dragOrigin;
    private boolean minimized;
    private volatile boolean locked,automatic,interacting;

    public PanelPlacement(Point panel,Point bubble,boolean locked,boolean automatic){
        this.panel=java.util.Objects.requireNonNull(panel);this.bubble=java.util.Objects.requireNonNull(bubble);this.shown=panel;this.locked=locked;this.automatic=automatic;
    }
    public Point shown(){return shown;}
    public Point preferred(){return minimized?bubble:panel;}
    public boolean minimized(){return minimized;}
    public boolean locked(){return locked;}
    public boolean automatic(){return automatic;}
    public boolean interacting(){return interacting;}
    public void locked(boolean value){if(value&&!locked){finishDrag(true);commitPosition();}locked=value;}
    public void automatic(boolean value){if(!value&&automatic){finishDrag(true);commitPosition();}automatic=value;}
    public boolean automaticMovementAllowed(){return automatic&&!locked&&!interacting;}
    public boolean moveAutomatically(int x,int y){
        if(!automaticMovementAllowed())return false;Point next=new Point(x,y);boolean changed=!next.equals(shown);shown=next;return changed;
    }
    public void restorePreferred(){if(!interacting)shown=preferred();}
    public void minimized(boolean value){interacting=false;dragOrigin=null;minimized=value;shown=preferred();}
    public void beginDrag(){dragOrigin=shown;interacting=true;}
    public void dragTo(int x,int y){if(interacting)shown=new Point(x,y);}
    public void finishDrag(boolean commit){
        if(!interacting)return;
        if(commit)commitPosition();else shown=dragOrigin;
        interacting=false;dragOrigin=null;
    }
    private void commitPosition(){if(minimized)bubble=shown;else panel=shown;}
    public void fit(int windowWidth,int windowHeight,int screenWidth,int screenHeight,int topMargin,int bottomMargin){
        int maxX=Math.max(0,screenWidth-windowWidth);
        int top=Math.min(Math.max(0,topMargin),Math.max(0,screenHeight-windowHeight));
        int maxY=Math.max(top,screenHeight-windowHeight-Math.max(0,bottomMargin));
        shown=new Point(Math.max(0,Math.min(maxX,shown.x())),Math.max(top,Math.min(maxY,shown.y())));
    }
}
