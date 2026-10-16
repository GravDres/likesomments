import ru.hod.chess.core.*;
import java.util.*;
import java.io.*;
import javax.imageio.ImageIO;

/** A speech bubble next to the board must not replace a complete rank/file. */
public final class BoardBoundsTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        int w=900,h=1500,left=130,top=400,size=640,cell=80;
        for(int axis=0;axis<4;axis++)for(int color:new int[]{0xffe6e7cb,0xff789154}){
            int[] screen=CoreTest.screen(w,h,left,top,size,false);
            int extraLeft=axis==2?left-cell:axis==3?left+size:left;
            int extraTop=axis==0?top-cell:axis==1?top+size:top;
            int extraWidth=axis<2?size:cell,extraHeight=axis<2?cell:size;
            for(int y=extraTop;y<extraTop+extraHeight;y++)Arrays.fill(screen,y*w+extraLeft,y*w+extraLeft+extraWidth,color);
            BoardVision.Region wrong=new BoardVision.Region(left+(axis==2?-cell:axis==3?cell:0),top+(axis==0?-cell:axis==1?cell:0),size);
            BoardLocator.Detection found=new BoardLocator().find(screen,w,h,wrong);
            check(found!=null&&found.automatic()&&BoardLocator.near(found.region(),new BoardVision.Region(left,top,size)),"uniform UI cannot replace an outer rank/file: "+axis+" "+found);
            check(found.initialLayout()&&found.orientationKnown()&&found.whiteBottom(),"all eight actual ranks determine the starting orientation");
            AutoBoardSearch search=new AutoBoardSearch(true);
            check(search.inspect(screen,w,h,null,null,wrong).state()==AutoBoardSearch.State.VERIFYING,"bad remembered frame is corrected without opening a manual selector");
            AutoBoardSearch.Result ready=search.inspect(screen,w,h,null,null,wrong);
            check(ready.state()==AutoBoardSearch.State.READY&&ready.calibration().game().position().fen().equals(Position.START),"all starting figures are confirmed automatically");
        }
        int[] bordered=CoreTest.screen(w,h,left,top,size,false);
        for(int y=top-cell;y<top;y++)Arrays.fill(bordered,y*w+left,y*w+left+size,0xffe6e7cb);
        for(int edge=1;edge<8;edge++)for(int i=0;i<size;i++)for(int thickness=0;thickness<3;thickness++){
            bordered[(top+i)*w+left+edge*cell+thickness]=0xff303633;
            bordered[(top+edge*cell+thickness)*w+left+i]=0xff303633;
        }
        BoardLocator.Detection corrected=new BoardLocator().find(bordered,w,h,new BoardVision.Region(left,top-cell,size));
        check(corrected!=null&&corrected.automatic()&&BoardLocator.near(corrected.region(),new BoardVision.Region(left,top,size)),"one-cell hint correction also works when thin cell borders interrupt every stripe");
        int[] incomplete=CoreTest.screen(w,h,left,top,size,false);
        for(int y=top+size-cell;y<top+size;y++)Arrays.fill(incomplete,y*w+left,y*w+left+size,0xffe6e7cb);
        BoardLocator.Detection missing=new BoardLocator().find(incomplete,w,h);
        check(missing==null||!missing.automatic(),"seven ranks plus a flat strip cannot become a complete board");
        BoardVision.Region source=new BoardVision.Region(0,414,582);
        for(int[] dims:new int[][]{{582,1280,582,1254},{582,1280,582,1216},{1264,2780,1264,2692},{1280,720,1180,720},{582,1280,582,1280}}){
            ImageViewport viewport=new ImageViewport(dims[0],dims[1],dims[2],dims[3]);
            int boardSize=Math.min(source.size(),dims[1]-source.top());
            ImageViewport.Rect rect=viewport.toView(source.left(),source.top(),boardSize,boardSize);
            check(Math.abs(rect.width()-rect.height())<.001f,"overlay with navigation insets keeps cells square: "+Arrays.toString(dims));
            ImageViewport.Rect image=viewport.image();
            check(Math.abs((rect.left()-image.left())/image.width()-source.left()/(float)dims[0])<.00001f
                &&Math.abs((rect.top()-image.top())/image.height()-source.top()/(float)dims[1])<.00001f
                &&Math.abs(rect.width()/image.width()-boardSize/(float)dims[0])<.00001f
                &&Math.abs(rect.height()/image.height()-boardSize/(float)dims[1])<.00001f,"grid edges align with the exact corresponding pixels in the drawn bitmap");
            ImageViewport.Point a=viewport.toSource(rect.left(),rect.top()),b=viewport.toSource(rect.right(),rect.bottom());
            check(Math.round(a.x())==source.left()&&Math.round(a.y())==source.top()&&Math.round(b.x()-a.x())==boardSize&&Math.round(b.y()-a.y())==boardSize,"image and grid use the same inverse transform");
            ImageViewport.Point corner=viewport.toSource(-100,-100);
            check(corner.x()==0&&corner.y()==0,"dragging outside the image stays within source bounds");
        }
        ImageViewport cropped=new ImageViewport(582,1280,582,1254);
        ImageViewport.Rect reported=cropped.toView(0,414,582,582);
        check(Math.abs(reported.left()-5.91094f)<.001f&&Math.abs(reported.top()-405.59063f)<.001f&&Math.abs(reported.width()-570.17813f)<.001f,"reported navigation inset produces one consistent board rectangle, without a separate stretched image");
        check(!cropped.contains(0,500)&&cropped.contains(291,500),"letterbox margins cannot start a selection");
        for(int index=1;index<=2;index++){
            var photo=ImageIO.read(new File("tests/fixtures/chesscom-selector-"+index+".png"));
            int pw=photo.getWidth(),ph=photo.getHeight();int[] pixels=photo.getRGB(0,0,pw,ph,null,0,pw);
            BoardLocator.Detection detected=new BoardLocator().find(pixels,pw,ph);
            check(detected==null||!detected.automatic(),"already stretched/overlaid selector image is not accepted as a clean captured board: "+index);
        }
        System.out.println("PASS: "+checks+" board-boundary regression assertions");
    }
}
