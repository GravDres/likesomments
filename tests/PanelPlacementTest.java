import ru.hod.chess.core.PanelPlacement;
import ru.hod.chess.core.PanelPlacement.Point;
import java.util.Random;

/** Position regressions run against the same state model used by FloatingPanel. */
public final class PanelPlacementTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        Point panel=new Point(32,72),bubble=new Point(650,90);
        for(boolean pinned:new boolean[]{false,true}){
            PanelPlacement p=new PanelPlacement(panel,bubble,pinned,false);
            for(int i=0;i<100;i++){
                check(!p.moveAutomatically(i*17,i*13)&&p.shown().equals(panel),"search retries and corners cannot move the panel by default");
                p.restorePreferred();check(p.preferred().equals(panel),"recovery does not overwrite the chosen panel position");
            }
            p.minimized(true);
            for(int i=0;i<30;i++)check(!p.moveAutomatically(i*11,i*7)&&p.shown().equals(bubble),"search does not move a minimized bubble by default");
            p.beginDrag();p.dragTo(550,140);p.finishDrag(true);Point chosenBubble=p.shown();
            p.minimized(false);check(p.shown().equals(panel),"opening restores the panel's own anchor");
            p.minimized(true);check(p.shown().equals(chosenBubble),"minimizing restores the independent bubble anchor");
        }
        PanelPlacement p=new PanelPlacement(panel,bubble,false,true);
        check(p.moveAutomatically(440,800)&&p.preferred().equals(panel),"explicit auto placement is temporary and does not replace the anchor");
        p.restorePreferred();check(p.shown().equals(panel),"temporary search position cannot become the next launch position");
        p.beginDrag();check(!p.moveAutomatically(10,900),"a held touch blocks automatic movement before crossing touch slop");
        p.dragTo(120,180);p.finishDrag(true);Point moved=p.shown();check(moved.equals(p.preferred()),"completed user drag commits the preferred position");
        p.beginDrag();p.dragTo(600,1000);p.finishDrag(false);check(p.shown().equals(moved)&&p.preferred().equals(moved),"cancelled drag restores its origin and cannot save an accidental position");
        p.moveAutomatically(430,910);p.locked(true);check(p.preferred().equals(p.shown()),"pinning explicitly saves the currently visible location");
        Point fixed=p.shown();check(!p.moveAutomatically(0,40)&&p.shown().equals(fixed),"pinning overrides enabled auto placement");
        p.restorePreferred();check(p.shown().equals(fixed),"recovery cannot move a newly pinned temporary position");
        p.locked(false);p.moveAutomatically(90,130);p.automatic(false);p.restorePreferred();check(p.shown().equals(new Point(90,130)),"disabling auto placement keeps the window where it was stopped");
        p.beginDrag();p.dragTo(600,1100);p.finishDrag(true);Point tall=p.preferred();
        p.fit(258,520,720,1280,30,20);check(p.shown().equals(new Point(462,740)),"expanded window fits the viewport");
        check(p.preferred().equals(tall),"temporary size clamp does not destroy the user's anchor");
        p.restorePreferred();p.fit(230,160,720,1280,30,20);check(p.shown().equals(new Point(490,1100)),"collapsing restores the chosen location as far as the screen permits");
        p.restorePreferred();p.fit(230,160,1440,2560,30,20);check(p.shown().equals(tall),"a larger viewport restores the unmodified anchor");
        Random random=new Random(19);
        for(int i=0;i<500;i++){
            int width=120+random.nextInt(1500),height=80+random.nextInt(2400),ww=1+random.nextInt(width),wh=1+random.nextInt(height);
            PanelPlacement q=new PanelPlacement(new Point(random.nextInt(),random.nextInt()),bubble,true,false);Point anchor=q.preferred();q.fit(ww,wh,width,height,30,20);
            check(q.shown().x()>=0&&q.shown().x()+ww<=width&&q.shown().y()>=0&&q.shown().y()+wh<=height,"clamping never leaves the panel outside a valid viewport");
            check(q.preferred().equals(anchor),"clamping cannot silently save another preferred position");
        }
        System.out.println("PASS: "+checks+" panel placement assertions (search retries, pinning, independent anchors, cancelled gestures and viewport changes; no Android device)");
    }
}
