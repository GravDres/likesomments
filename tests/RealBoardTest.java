import ru.hod.chess.core.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Real board pixels from the reported failure, with phone UI deliberately excluded. */
public final class RealBoardTest {
    private static int checks;
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static final Position PHOTO=Position.fromFen("2r1kb1r/1p1q1ppp/p1n5/1N1pP3/Q7/3RB3/PP3PPP/1K5R w k - 0 17");
    public static void main(String[] args)throws Exception{
        BufferedImage[] images={ImageIO.read(new File("tests/fixtures/chesscom-midgame-1.png")),ImageIO.read(new File("tests/fixtures/chesscom-midgame-2.png"))};
        BoardVision model=null;BoardVision.Region trained=null;
        for(int index=0;index<images.length;index++){
            BufferedImage image=images[index];int w=image.getWidth(),h=image.getHeight();int[] pixels=image.getRGB(0,0,w,h,null,0,w);
            BoardLocator.Detection found=new BoardLocator().find(pixels,w,h);
            check(found!=null&&found.automatic(),"real highlighted JPEG-derived board is found automatically: "+index);
            check(found.region().left()<=2&&found.region().top()<=2&&Math.abs(found.region().size()-582)<=2,"fractional cell borders and coordinate labels retain exact bounds");
            if(model==null){model=new BoardVision(true).learnConfirmed(pixels,w,h,found.region(),PHOTO);trained=found.region();}
            BoardVision.Observation observed=model.recognize(pixels,w,h,found.region());
            if(observed.uncertain()!=0||!PHOTO.matches(observed.board())){System.out.println("uncertain="+observed.uncertain()+" confidence="+observed.confidence());for(int sq=0;sq<64;sq++)if(PHOTO.at(sq)!=observed.board()[sq])System.out.println(Position.square(sq)+": expected "+PHOTO.at(sq)+" observed "+observed.board()[sq]);}
            check(observed.uncertain()==0&&PHOTO.matches(observed.board()),"all 64 real squares match the manually checked diagram across highlight change");
            AutoBoardSearch search=new AutoBoardSearch();GameTracker game=new GameTracker(PHOTO);
            check(search.inspect(pixels,w,h,model,game).state()==AutoBoardSearch.State.VERIFYING,"real midgame reaches legal verification");
            check(search.inspect(pixels,w,h,model,game).state()==AutoBoardSearch.State.READY,"real midgame resumes on the second clean frame");
            check(game.position().fen().equals(PHOTO.fen()),"probing real screenshot preserves confirmed metadata");
        }
        int[] original=images[1].getRGB(0,0,582,582,null,0,582);
        int[] kingMoved=movePixels(original,582,trained,Position.index("b1"),Position.index("c1"));Position next=PHOTO.play(Move.parse("b1c1"));
        BoardVision.Observation king=model.recognize(kingMoved,582,582,trained);
        if(king.uncertain()!=0)System.out.println("cross-color king uncertain="+king.uncertain());
        check(king.uncertain()==0&&next.matches(king.board()),"white king remains recognized after moving from light to dark background");
        int[] queenMoved=movePixels(original,582,trained,Position.index("a4"),Position.index("b4"));Position q=PHOTO.play(Move.parse("a4b4"));
        BoardVision.Observation queen=model.recognize(queenMoved,582,582,trained);
        if(queen.uncertain()!=0||!q.matches(queen.board())){System.out.println("queen region="+trained+" uncertain="+queen.uncertain());for(int sq=0;sq<64;sq++)if(q.at(sq)!=queen.board()[sq])System.out.println(Position.square(sq)+": expected "+q.at(sq)+" observed "+queen.board()[sq]);}
        check(queen.uncertain()==0&&q.matches(queen.board()),"white queen remains recognized after moving to the opposite square color");
        for(int size:new int[]{511,800,1264}){
            int width=size+80,height=size+240,left=40,top=120;int[] screen=new int[width*height];Arrays.fill(screen,0xff212321);
            for(int y=0;y<size;y++)for(int x=0;x<size;x++)screen[(top+y)*width+left+x]=original[(y*582/size)*582+x*582/size];
            BoardLocator.Detection located=new BoardLocator().find(screen,width,height);
            check(located!=null&&located.automatic()&&BoardLocator.near(located.region(),new BoardVision.Region(left,top,size)),"real board survives resizing and a nonzero screen offset: "+size);
        }
        byte[] bytes=GameMemory.encode(new GameTracker(PHOTO),model);GameMemory.Restored restored=GameMemory.decode(bytes);
        check(restored.game().initial().fen().equals(PHOTO.fen())&&restored.game().position().fen().equals(PHOTO.fen()),"user-confirmed midgame and its own starting FEN survive restart");
        // Derived fixture: arrange the photograph's sprites at the start, then
        // read the untouched photograph. This tests generalization after start
        // calibration, rather than learning the exact same midgame frame.
        for(boolean whiteBottom:new boolean[]{true,false}){
            int[] start=arrangeStartingSprites(original,trained,whiteBottom);
            BoardVision fromStart=new BoardVision(whiteBottom);fromStart.calibrate(start,640,640,new BoardVision.Region(0,0,640));
            BoardVision.Observation read=fromStart.withOrientation(true).recognize(original,582,582,trained);
            if(read.uncertain()!=0||!PHOTO.matches(read.board())){System.out.println("from-start uncertain="+read.uncertain()+" orientation="+whiteBottom);for(int sq=0;sq<64;sq++)if(PHOTO.at(sq)!=read.board()[sq])System.out.println(Position.square(sq)+": expected "+PHOTO.at(sq)+" observed "+read.board()[sq]);}
            check(read.uncertain()==0&&PHOTO.matches(read.board()),"real sprites learned on their starting square colors recognize the original midgame, orientation="+whiteBottom);
        }
        System.out.println("PASS: "+checks+" real-board regression assertions (both reported frames; personal UI excluded)");
    }
    private static int[] arrangeStartingSprites(int[] photo,BoardVision.Region r,boolean whiteBottom){
        int[] board=new int[640*640];float cell=r.size()/8f;
        for(int display=0;display<64;display++){
            int col=display%8,row=display/8,bg=(row+col)%2==0?0xffedeed0:0xff769656;
            for(int y=0;y<80;y++)Arrays.fill(board,(row*80+y)*640+col*80,(row*80+y)*640+(col+1)*80,bg);
            char piece=Position.start().at(whiteBottom?display:63-display);if(piece=='.')continue;
            int source=0;while(PHOTO.at(source)!=piece)source++;
            int sx=r.left()+Math.round(source%8*cell),sy=r.top()+Math.round(source/8*cell);
            BoardVision.Feature feature=BoardVision.extract(photo,582,r,source);
            for(int y=7;y<73;y++)for(int x=10;x<70;x++){
                int fx=Math.min(23,Math.max(0,(int)((x/80f-.12f)/.76f*24))),fy=Math.min(23,Math.max(0,(int)((y/80f-.08f)/.84f*24)));
                if(feature.mask()[fy*24+fx]>.12f||feature.silhouette()[fy*24+fx]>.5f)board[(row*80+y)*640+col*80+x]=photo[(sy+Math.round(y*cell/80f))*582+sx+Math.round(x*cell/80f)];
            }
        }
        return board;
    }
    private static int[] movePixels(int[] pixels,int width,BoardVision.Region r,int from,int to){
        int[] copy=pixels.clone();float cell=r.size()/8f;
        int fx=r.left()+Math.round(from%8*cell),fy=r.top()+Math.round(from/8*cell),tx=r.left()+Math.round(to%8*cell),ty=r.top()+Math.round(to/8*cell);
        int fw=Math.round((from%8+1)*cell)-Math.round(from%8*cell),fh=Math.round((from/8+1)*cell)-Math.round(from/8*cell);
        int tw=Math.round((to%8+1)*cell)-Math.round(to%8*cell),th=Math.round((to/8+1)*cell)-Math.round(to/8*cell);
        int background=pixels[(fy+fh/8)*width+fx+fw/8],destination=pixels[(ty+th/8)*width+tx+tw/8];
        for(int y=0;y<fh;y++)Arrays.fill(copy,(fy+y)*width+fx,(fy+y)*width+fx+fw,background);
        for(int y=0;y<th;y++)for(int x=0;x<tw;x++){int p=pixels[(fy+y*fh/th)*width+fx+x*fw/tw];int dr=(p>>16&255)-(background>>16&255),dg=(p>>8&255)-(background>>8&255),db=(p&255)-(background&255);copy[(ty+y)*width+tx+x]=dr*dr+dg*dg+db*db>150?p:destination;}
        return copy;
    }
}
