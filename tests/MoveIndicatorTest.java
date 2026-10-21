import ru.hod.chess.core.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/** Selected-square move dots are UI, not pieces. No phone UI is retained in the fixture. */
public final class MoveIndicatorTest {
    private static int checks;
    private static final String[] OPENING={"e2e4","c7c5","g1f3","d7d6","d2d4","c5d4","f3d4","g8f6","b1c3","g7g6"};
    private static final Position PHOTO=CoreTest.play(Position.start(),OPENING);
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void assertBoard(Position p,BoardVision.Observation seen,String label){
        StringBuilder mismatch=new StringBuilder();for(int i=0;i<64;i++)if(p.at(i)!=seen.board()[i])mismatch.append(Position.square(i)).append(':').append(p.at(i)).append('/').append(seen.board()[i]).append(' ');
        check(seen.uncertain()==0&&p.matches(seen.board()),label+" uncertain="+seen.uncertain()+" mismatches="+mismatch);
    }
    public static void main(String[] args)throws Exception{
        LichessTest.setupFixture();reportedFrame();renderedDots();negativeFrames();
        System.out.println("PASS: "+checks+" move-indicator assertions (reported board-only crop, rendered dots and negative pieces)");
    }
    private static void reportedFrame()throws Exception{
        BufferedImage image=ImageIO.read(new File("tests/fixtures/chesscom-selected-knight.png"));int n=image.getWidth();int[] pixels=image.getRGB(0,0,n,n,null,0,n);var region=new BoardVision.Region(0,0,n);
        // Independent earlier photograph of the same theme: never train on the seven dots.
        BufferedImage earlier=ImageIO.read(new File("tests/fixtures/chesscom-midgame-2.png"));int[] training=earlier.getRGB(0,0,n,n,null,0,n);
        Position old=Position.fromFen("2r1kb1r/1p1q1ppp/p1n5/1N1pP3/Q7/3RB3/PP3PPP/1K5R w k - 0 17");
        BoardVision vision=new BoardVision(true).learnConfirmed(training,n,n,region,old);
        var seen=vision.recognize(pixels,n,n,region);assertBoard(PHOTO,seen,"reported selected knight retains all 64 cells");
        for(String square:new String[]{"c6","e6","b5","f5","b3","f3","e2"})check(seen.board()[Position.index(square)]=='.',"reported marker is empty: "+square);
        check(seen.board()[Position.index("d4")]=='N',"selected knight remains a knight");
        var found=new BoardLocator().find(pixels,n,n);check(found!=null&&found.automatic(),"board still located with legal-move dots");assertBoard(PHOTO,vision.recognize(pixels,n,n,found.region()),"automatic region retains correct pieces");
        GameTracker game=GameTracker.replay(Arrays.stream(OPENING).map(Move::parse).toList());String fen=game.position().fen();
        check(game.accept(seen.board()).status()==GameTracker.Status.SAME&&game.position().fen().equals(fen),"selecting a piece adds no fictitious move");
        GameTracker before=GameTracker.replay(Arrays.stream(OPENING).limit(9).map(Move::parse).toList());
        check(before.accept(seen.board()).status()==GameTracker.Status.MOVED&&before.position().fen().equals(PHOTO.fen()),"opponent move accepted while own piece selected");
        StableBoard stability=new StableBoard();check(!stability.accept(seen)&&stability.accept(vision.recognize(pixels,n,n,region)),"selection can pass two-frame verification");
        AutoBoardSearch search=new AutoBoardSearch();check(search.inspect(pixels,n,n,vision,game).state()==AutoBoardSearch.State.VERIFYING,"selected board starts reacquisition");check(search.inspect(pixels,n,n,vision,game).state()==AutoBoardSearch.State.READY,"selected board reacquired on next frame");
        var restored=GameMemory.decode(GameMemory.encode(game,vision));assertBoard(PHOTO,restored.vision().recognize(pixels,n,n,region),"saved templates accept dots after restart");
        for(int size:new int[]{511,800,1264}){
            int w=size+64,h=size+160,left=32,top=80;int[] screen=new int[w*h];Arrays.fill(screen,0xff212321);
            for(int y=0;y<size;y++)for(int x=0;x<size;x++)screen[(top+y)*w+left+x]=pixels[(y*n/size)*n+x*n/size];
            var located=new BoardLocator().find(screen,w,h);check(located!=null&&located.automatic()&&BoardLocator.near(located.region(),new BoardVision.Region(left,top,size)),"selected photograph found after scaling: "+size);
            assertBoard(PHOTO,vision.recognize(screen,w,h,located.region()),"resized selected photograph: "+size);
        }
    }
    private static void renderedDots(){
        for(int theme:new int[]{0,1,4,7,12,18,24})for(boolean flipped:new boolean[]{false,true}){
            int n=theme%2==0?576:512;var region=new BoardVision.Region(0,0,n);BoardVision vision=new BoardVision(!flipped);vision.calibrate(LichessTest.render(Position.start(),n,flipped,theme,false,true,null),n,n,region);
            int[] selectedStart=LichessTest.render(Position.start(),n,flipped,theme,false,true,null);dot(selectedStart,n,Position.index("e3"),flipped,.18f,.22f,false);dot(selectedStart,n,Position.index("e4"),flipped,.18f,.22f,false);
            BoardVision initial=new BoardVision(!flipped);initial.calibrate(selectedStart,n,n,region);assertBoard(Position.start(),initial.recognize(selectedStart,n,n,region),"initial calibration with selected pawn");
            int[] clean=LichessTest.render(PHOTO,n,flipped,theme,false,true,null);
            for(boolean light:new boolean[]{false,true})for(float radius:new float[]{.10f,.18f,.21f})for(float alpha:new float[]{.10f,.22f,.35f}){
                int[] pixels=clean.clone();for(String square:new String[]{"c6","e6","b5","f5","b3","f3","e2"})dot(pixels,n,Position.index(square),flipped,radius,alpha,light);
                assertBoard(PHOTO,vision.recognize(pixels,n,n,region),"dots theme="+theme+" flipped="+flipped+" radius="+radius+" alpha="+alpha+" light="+light);
            }
            assertBoard(PHOTO,vision.recognize(clean,n,n,region),"deselection restores same position without recalibration");
        }
    }
    private static void negativeFrames(){
        for(boolean flipped:new boolean[]{false,true}){
            int n=576;var region=new BoardVision.Region(0,0,n);BoardVision vision=new BoardVision(!flipped);vision.calibrate(LichessTest.render(Position.start(),n,flipped,0,false,true,null),n,n,region);
            assertBoard(PHOTO,vision.recognize(LichessTest.render(PHOTO,n,flipped,0,false,true,null),n,n,region),"no-marker pieces unchanged");
            for(char piece:new char[]{'P','p','N','n','B','b','R','r','Q','q'}){
                char[] board=PHOTO.pieces();board[Position.index("e6")]=piece;Position added=Position.fromDiagram(board,true,0,-1,0,6);
                int[] pixels=LichessTest.render(added,n,flipped,0,false,true,null);var seen=vision.recognize(pixels,n,n,region);assertBoard(added,seen,"real piece must not be erased as dot: "+piece);
                GameTracker game=GameTracker.replay(Arrays.stream(OPENING).map(Move::parse).toList());check(game.accept(seen.board()).status()==GameTracker.Status.UNRECOGNIZED&&game.position().fen().equals(PHOTO.fen()),"a new piece cannot be hidden to invent unchanged board");
            }
            Position moved=PHOTO.play(Move.parse("d4b5"));var seen=vision.recognize(LichessTest.render(moved,n,flipped,0,false,true,null),n,n,region);assertBoard(moved,seen,"actual knight move retained");
            GameTracker game=GameTracker.replay(Arrays.stream(OPENING).map(Move::parse).toList());check(game.accept(seen.board()).status()==GameTracker.Status.MOVED&&game.history().size()==11,"actual move advances history");
        }
    }
    private static void dot(int[] pixels,int n,int logical,boolean flipped,float radius,float alpha,boolean light){
        int display=flipped?63-logical:logical;float cell=n/8f,cx=(display%8+.5f)*cell,cy=(display/8+.5f)*cell;
        for(int y=(int)(cy-cell*.3f);y<cy+cell*.3f;y++)for(int x=(int)(cx-cell*.3f);x<cx+cell*.3f;x++){
            float distance=(float)Math.hypot(x+.5f-cx,y+.5f-cy);float opacity=alpha*Math.max(0,Math.min(1,radius*cell+.5f-distance));if(opacity<=0)continue;
            int p=pixels[y*n+x],v=light?255:0,r=Math.round((p>>16&255)*(1-opacity)+v*opacity),g=Math.round((p>>8&255)*(1-opacity)+v*opacity),b=Math.round((p&255)*(1-opacity)+v*opacity);pixels[y*n+x]=0xff000000|r<<16|g<<8|b;
        }
    }
}
