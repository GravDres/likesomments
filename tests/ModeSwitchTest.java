import ru.hod.chess.core.*;
import java.util.*;

/** Layout changes are independent of Chess.com's game mode or opponent. */
public final class ModeSwitchTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        BoardLocator locator=new BoardLocator();
        int w=720,h=1440,left=40,top=300,size=640;
        int[] highlighted=CoreTest.screen(w,h,left,top,size,false);
        // No six uninterrupted vertical stripes remain in any of the old probes.
        for(int[] tile:new int[][]{{2,1},{4,2},{3,4},{5,6}}){
            for(int y=0;y<80;y++)Arrays.fill(highlighted,(top+tile[0]*80+y)*w+left+tile[1]*80,(top+tile[0]*80+y)*w+left+(tile[1]+1)*80,0xffc2cf58);
        }
        BoardLocator.Detection found=locator.find(highlighted,w,h);
        check(found!=null&&found.automatic()&&BoardLocator.near(found.region(),new BoardVision.Region(left,top,size)),"highlighted cells must not break board discovery");
        int[] initial=CoreTest.screen(w,h,left,top,size,false);
        AutoBoardSearch bootstrap=new AutoBoardSearch();bootstrap.inspect(initial,w,h,null,null);
        BoardVision model=bootstrap.inspect(initial,w,h,null,null).calibration().vision();
        Position played=CoreTest.play(Position.start(),"e2e4","e7e5");
        GameTracker game=GameTracker.replay(List.of(Move.parse("e2e4"),Move.parse("e7e5")));
        String fen=game.position().fen();
        // Same display dimensions, different board placement/scale/orientation.
        for(int[] layout:new int[][]{{0,140,720},{104,540,512},{28,700,664}})for(boolean rotated:new boolean[]{false,true}){
            int[] pixels=CoreTest.screenWith(played,w,h,layout[0],layout[1],layout[2],rotated);
            AutoBoardSearch search=new AutoBoardSearch();
            BoardVision.Region stale=new BoardVision.Region(left,top,size);
            AutoBoardSearch.Result one=search.inspect(pixels,w,h,model,game,stale);
            check(one.state()==AutoBoardSearch.State.VERIFYING,"layout switch is found with a stale rectangle: "+Arrays.toString(layout));
            AutoBoardSearch.Result two=search.inspect(pixels,w,h,model,game,stale);
            check(two.state()==AutoBoardSearch.State.READY&&two.calibration().reuseGame()&&two.calibration().whiteBottom()!=rotated,"layout switch keeps the legal game and detects orientation");
            check(game.position().fen().equals(fen)&&game.history().size()==2,"layout probing cannot rewrite history");
        }
        int[] differentTheme=tint(initial);
        AutoBoardSearch newGame=new AutoBoardSearch();
        AutoBoardSearch.Result newFirst=newGame.inspect(differentTheme,w,h,model,game);
        check(newFirst.state()==AutoBoardSearch.State.VERIFYING,"another game's history does not block calibration of a new theme: "+newFirst);
        AutoBoardSearch.Result next=newGame.inspect(differentTheme,w,h,model,game);
        check(next.state()==AutoBoardSearch.State.READY&&!next.calibration().reuseGame(),"a new game needs two matching frames");
        check(game.position().fen().equals(fen)&&game.history().size()==2,"old game remains available to archive before installation of a new game");
        BoardVision nextModel=next.calibration().vision();
        check(nextModel.recognize(differentTheme,w,h,next.calibration().region()).uncertain()==0,"new theme is actually calibrated");
        Position knight=CoreTest.play(Position.start(),"d2d4","d7d5","b1c3");
        BoardVision.Observation knightSeen=nextModel.recognize(tint(CoreTest.screenWith(knight,w,h,left,top,size,false)),w,h,next.calibration().region());
        check(knightSeen.uncertain()==0&&knight.matches(knightSeen.board()),"a low-contrast starting sprite is recognized after moving onto a different background");
        int[] bordered=CoreTest.screenWith(played,w,h,left,top,size,false);
        for(int edge=1;edge<8;edge++)for(int i=0;i<size;i++)for(int thickness=0;thickness<3;thickness++){
            bordered[(top+i)*w+left+edge*80+thickness]=0xff303633;
            bordered[(top+edge*80+thickness)*w+left+i]=0xff303633;
        }
        BoardVision.Region hint=new BoardVision.Region(left,top,size);
        check(locator.find(bordered,w,h)==null,"bordered layout genuinely requires the remembered-region fallback");
        AutoBoardSearch hinted=new AutoBoardSearch();
        check(hinted.inspect(bordered,w,h,model,game,hint).state()==AutoBoardSearch.State.VERIFYING,"a remembered region is rechecked even when stripe discovery fails");
        check(hinted.inspect(bordered,w,h,model,game,hint).state()==AutoBoardSearch.State.READY,"current grid, all squares and legal history validate the fallback on two frames");
        int[] two=CoreTest.screen(1000,1600,10,100,400,false),second=CoreTest.screen(1000,1600,560,1000,400,true);
        for(int y=1000;y<1400;y++)System.arraycopy(second,y*1000+560,two,y*1000+560,400);
        BoardLocator.Detection both=locator.find(two,1000,1600,new BoardVision.Region(10,100,400));
        check(both!=null&&both.ambiguous()&&!both.automatic(),"a hint cannot silently choose between two real boards");
        int[] blank=new int[w*h];Arrays.fill(blank,0xff202523);
        check(locator.find(blank,w,h,new BoardVision.Region(left,top,size))==null,"remembered rectangle cannot accept a menu or blank screen");
        AutoBoardSearch interrupted=new AutoBoardSearch();interrupted.inspect(differentTheme,w,h,model,game);interrupted.inspect(blank,w,h,model,game);
        check(interrupted.inspect(differentTheme,w,h,model,game).state()==AutoBoardSearch.State.VERIFYING,"a menu interrupts the new-game verification");
        BoardRecoveryGate gate=new BoardRecoveryGate();
        check(!gate.failed(0)&&!gate.failed(160)&&!gate.failed(320),"a normal animation does not request a new search");
        gate.reset();
        check(!gate.failed(5000)&&!gate.failed(6000)&&!gate.failed(7000)&&gate.failed(8000),"persistent impossible or uncertain frames request a full search");
        check(!gate.failed(20000),"only one recovery request is queued at a time");
        gate.reset();
        check(!gate.failed(21000)&&!gate.failed(22000),"a successful frame clears the failure interval");
        gate.reset();
        check(!gate.failed(23000)&&!gate.failed(24000)&&!gate.failed(25000)&&gate.failed(26000),"later layout changes can recover again");
        System.out.println("PASS: "+checks+" mode/layout regression assertions");
    }
    private static int[] tint(int[] image){
        int[] copy=image.clone();
        for(int i=0;i<copy.length;i++){
            if(copy[i]==0xffe6e7cb)copy[i]=0xffdee6f0;
            else if(copy[i]==0xff789154)copy[i]=0xff547999;
            else if(copy[i]==0xff242424)copy[i]=0xff1b3045;
        }
        return copy;
    }
}
