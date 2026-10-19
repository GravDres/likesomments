import ru.hod.chess.core.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** The reported Lichess check frame plus independent renders of check backgrounds. */
public final class CheckHighlightTest {
    private static int checks;
    // Rights and move counters are explicit test metadata, not inferred from an image.
    private static final Position BEFORE=Position.fromFen("r1bqk2r/pp3ppp/2n5/2bp4/5B2/2NP1N2/PPP3PP/R2QK2R w KQkq - 0 10");
    private static final Position CHECK=BEFORE.play(Move.parse("d1e2"));
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void assertBoard(Position p,BoardVision.Observation o,String label){
        StringBuilder different=new StringBuilder();for(int i=0;i<64;i++)if(p.at(i)!=o.board()[i])different.append(Position.square(i)).append(':').append(p.at(i)).append('/').append(o.board()[i]).append(' ');
        check(o.uncertain()==0&&p.matches(o.board()),label+" uncertain="+o.uncertain()+" mismatches="+different);
    }
    private static BoardVision train(int size,boolean flipped,int theme){
        BoardVision vision=new BoardVision(!flipped);vision.calibrate(LichessTest.render(Position.start(),size,flipped,theme,false,true,null),size,size,new BoardVision.Region(0,0,size));return vision;
    }
    public static void main(String[] args)throws Exception{
        LichessTest.setupFixture();reportedFrame();renderedFrames();negativeFrames();colouredPieces();
        System.out.println("PASS: "+checks+" check-highlight assertions (reported board crop, synthetic glows, legal continuation and negative frames)");
    }
    private static void reportedFrame()throws Exception{
        BufferedImage photo=ImageIO.read(Path.of("tests/fixtures/lichess/reported-check.png").toFile());int n=photo.getWidth();int[] pixels=photo.getRGB(0,0,n,n,null,0,n);
        BoardVision model=train(n,true,0);var region=new BoardVision.Region(0,0,n);var seen=model.recognize(pixels,n,n,region);
        check(CHECK.inCheck(false),"fixture really is a check, not checkmate");
        assertBoard(CHECK,seen,"all 64 photographed squares including black king e8");
        BoardLocator.Detection found=new BoardLocator().find(pixels,n,n);check(found!=null&&found.automatic(),"reported check board is found automatically");assertBoard(CHECK,model.recognize(pixels,n,n,found.region()),"automatically located region");
        StableBoard stable=new StableBoard();check(!stable.accept(seen)&&stable.accept(model.recognize(pixels,n,n,region)),"two independent clean check frames become stable");
        GameTracker game=new GameTracker(BEFORE);check(game.accept(seen.board()).status()==GameTracker.Status.MOVED,"checking move is accepted without re-entering position");check(game.position().fen().equals(CHECK.fen())&&game.history().equals(java.util.List.of(Move.parse("d1e2"))),"checking move keeps history, turn and castling rights");
        check(game.position().legalMoves().stream().noneMatch(m->m.uci().equals("e8g8")||m.uci().equals("e8c8")),"castling out of check remains illegal");
        Position reply=CHECK.play(Move.parse("c8e6"));var replied=model.recognize(LichessTest.render(reply,n,true,0,false,true,null),n,n,region);assertBoard(reply,replied,"red glow disappears after blocking the check");check(!stable.accept(replied)&&stable.accept(replied),"changed reply needs two observations");check(game.accept(replied.board()).status()==GameTracker.Status.MOVED,"play continues after answering check");
        var restored=GameMemory.decode(GameMemory.encode(game,model));check(restored.game().position().fen().equals(reply.fen())&&restored.game().history().size()==2,"restart retains both moves and metadata");assertBoard(CHECK,restored.vision().recognize(pixels,n,n,region),"saved theme handles another check");
        for(int size:new int[]{512,800}){
            BufferedImage resized=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);Graphics2D g=resized.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(photo,0,0,size,size,null);g.dispose();int w=size+60,h=size+180;int[] screen=new int[w*h];Arrays.fill(screen,0xff161512);int[] board=resized.getRGB(0,0,size,size,null,0,size);for(int y=0;y<size;y++)System.arraycopy(board,y*size,screen,(y+90)*w+30,size);
            var located=new BoardLocator().find(screen,w,h);check(located!=null&&located.automatic()&&BoardLocator.near(located.region(),new BoardVision.Region(30,90,size)),"resized check crop found at nonzero offset: "+size);assertBoard(CHECK,model.recognize(screen,w,h,located.region()),"resized photographed check: "+size);
        }
    }
    private static void renderedFrames()throws Exception{
        Position[] positions={CHECK,Position.fromFen("k3r3/8/8/8/8/8/8/4K3 w - - 0 1"),Position.fromFen("4k3/8/8/8/8/8/8/K3R3 b - - 0 1"),Position.fromFen("k7/8/8/8/8/8/8/3Kr3 w - - 0 1")};
        for(int theme=0;theme<25;theme++)for(boolean flipped:new boolean[]{false,true}){
            int size=theme%2==0?576:512;var region=new BoardVision.Region(0,0,size);var model=train(size,flipped,theme);
            for(Position p:positions){int king=0;while(p.at(king)!=(p.white?'K':'k'))king++;check(p.inCheck(p.white),"synthetic position is a real check");
                for(float strength:new float[]{.65f,1})for(float blur:new float[]{.08f,.18f}){int[] pixels=LichessTest.render(p,size,flipped,theme,true,true,Move.parse("d1e2"),king,strength,blur);assertBoard(p,model.recognize(pixels,size,size,region),"rendered check theme="+theme+" flipped="+flipped+" king="+Position.square(king)+" strength="+strength+" blur="+blur);}
            }
        }
    }
    private static void negativeFrames(){
        int size=576,square=Position.index("e8");var region=new BoardVision.Region(0,0,size);BoardVision vision=train(size,true,0);
        for(char piece:new char[]{'.','q','b','r','n'}){
            char[] board=CHECK.pieces();board[square]=piece;board[Position.index("h8")]='k';Position changed=Position.fromDiagram(board,false,0,-1,0,1);
            int[] pixels=LichessTest.render(changed,size,true,0,false,true,null,square,1,.18f);var seen=vision.recognize(pixels,size,size,region);
            check(seen.board()[square]!='k',"red background does not invent a king for "+piece);GameTracker game=new GameTracker(BEFORE);String fen=game.position().fen();check(game.accept(seen.board()).status()==GameTracker.Status.UNRECOGNIZED&&game.position().fen().equals(fen)&&game.history().isEmpty(),"invalid checked board cannot overwrite history: "+piece);
        }
    }
    private static void colouredPieces(){
        int n=576;var region=new BoardVision.Region(0,0,n);BoardVision model=new BoardVision(false);model.calibrate(tintPieces(LichessTest.render(Position.start(),n,true,0,false,true,null)),n,n,region);
        assertBoard(CHECK,model.recognize(tintPieces(LichessTest.render(CHECK,n,true,0,false,true,null)),n,n,region),"red-coloured sprites without a glow keep their roles");
    }
    private static int[] tintPieces(int[] pixels){
        for(int i=0;i<pixels.length;i++){int p=pixels[i],r=p>>16&255,g=p>>8&255,b=p&255;if(Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<4&&r<230){float t=r/255f;pixels[i]=0xff000000|(Math.round(190+65*t)<<16)|(Math.round(30+225*t)<<8)|Math.round(30+225*t);}}return pixels;
    }
}
