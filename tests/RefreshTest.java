import ru.hod.chess.core.*;
import java.util.*;

public final class RefreshTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        int w=720,h=1440;BoardVision.Region region=new BoardVision.Region(40,300,640);
        int[] initial=CoreTest.screen(w,h,40,300,640,false);
        AutoBoardSearch first=new AutoBoardSearch();first.inspect(initial,w,h,null,null);
        BoardVision model=first.inspect(initial,w,h,null,null).calibration().vision();
        GameTracker previous=GameTracker.replay(List.of(Move.parse("e2e4"),Move.parse("e7e5"),Move.parse("g1f3"),Move.parse("b8c6"),Move.parse("f1b5")));
        Position opening=CoreTest.play(Position.start(),"d2d4");
        int[] pixels=CoreTest.screenWith(opening,w,h,40,300,640,false);
        AutoBoardSearch refresh=new AutoBoardSearch(true);
        check(refresh.inspect(pixels,w,h,model,previous,region).state()==AutoBoardSearch.State.VERIFYING,"refresh can identify a new game after its first move");
        AutoBoardSearch.Result ready=refresh.inspect(pixels,w,h,model,previous,region);
        check(ready.state()==AutoBoardSearch.State.READY&&!ready.calibration().reuseGame(),"first move of another game is confirmed on two frames");
        check(ready.calibration().game().restore().position().fen().equals(opening.fen()),"first move installs its actual turn, en passant and counters immediately");
        check(previous.history().size()==5,"probing another game never mutates the old game");
        for(boolean rotated:new boolean[]{false,true})for(String[] moves:new String[][]{{},{"d2d4"},{"d2d4","d7d5"}}){
            Position target=CoreTest.play(Position.start(),moves);
            int[] screen=CoreTest.screenWith(target,w,h,140,520,480,rotated);
            AutoBoardSearch search=new AutoBoardSearch(true);
            AutoBoardSearch.Result before=search.inspect(screen,w,h,model,previous,region);
            check(before.state()==AutoBoardSearch.State.VERIFYING,"refresh finds relocated new opening "+Arrays.toString(moves)+" rotated="+rotated);
            AutoBoardSearch.Result after=search.inspect(screen,w,h,model,previous,region);
            check(after.state()==AutoBoardSearch.State.READY&&!after.calibration().reuseGame()&&after.calibration().whiteBottom()!=rotated,"new opening is installed with its own orientation");
            GameTracker installed=after.calibration().game().restore();
            check(installed.position().fen().equals(target.fen())&&installed.history().size()==moves.length,"new opening keeps exactly its confirmed moves and metadata");
            check(PlayerSide.WHITE.ownsTurn(installed.position())==(moves.length%2==0),"own-side hints follow the actual new turn");
            GameMemory.Restored saved=GameMemory.decode(GameMemory.encode(installed,after.calibration().vision()));
            check(saved.game().position().fen().equals(target.fen()),"new opening survives restart without the prior game's history");
        }
        List<Move> cycle=new ArrayList<>();for(int i=0;i<2;i++)for(String uci:new String[]{"g1f3","g8f6","f3g1","f6g8"})cycle.add(Move.parse(uci));
        GameTracker repeats=GameTracker.replay(cycle);
        AutoBoardSearch automatic=new AutoBoardSearch();automatic.inspect(initial,w,h,model,repeats);
        GameTracker keep=automatic.inspect(initial,w,h,model,repeats).calibration().game().restore();
        check(keep.history().size()==8&&keep.repetitionCount()==3,"background recovery preserves a legal return to start and repetition history");
        AutoBoardSearch explicit=new AutoBoardSearch(true);explicit.inspect(initial,w,h,model,repeats);
        GameTracker reset=explicit.inspect(initial,w,h,model,repeats).calibration().game().restore();
        check(reset.history().isEmpty()&&reset.repetitionCount()==1&&reset.position().fen().equals(Position.START),"explicit refresh on starting pieces starts a fresh game");
        cycle.add(Move.parse("d2d4"));GameTracker repeatedOpening=GameTracker.replay(cycle);
        AutoBoardSearch explicitOpening=new AutoBoardSearch(true);explicitOpening.inspect(pixels,w,h,model,repeatedOpening);
        GameTracker newOpening=explicitOpening.inspect(pixels,w,h,model,repeatedOpening).calibration().game().restore();
        check(newOpening.history().size()==1&&newOpening.position().fen().equals(opening.fen()),"explicit refresh cannot inherit old knight repetitions when the new first move looks identical");
        GameTracker castling=GameTracker.replay(List.of(Move.parse("e2e4"),Move.parse("e7e5"),Move.parse("g1f3"),Move.parse("b8c6"),Move.parse("f1b5"),Move.parse("a7a6"),Move.parse("b5a4")));
        Position castled=CoreTest.play(castling.position(),"g8f6","e1g1");
        GameTracker caughtUp=resume(model,castling,castled,w,h);
        check(caughtUp.history().size()==9&&caughtUp.position().fen().equals(castled.fen())&&(caughtUp.position().rights&3)==0,"refresh installs missed castling moves and their rook/castling metadata");
        GameTracker ep=GameTracker.replay(List.of(Move.parse("e2e4"),Move.parse("a7a6"),Move.parse("e4e5")));
        Position d5=ep.position().play(Move.parse("d7d5"));
        GameTracker epResume=resume(model,ep,d5,w,h);
        check(epResume.position().fen().equals(d5.fen())&&epResume.position().play(Move.parse("e5d6")).at(Position.index("d5"))=='.',"missed pawn double step keeps en passant available after refresh");
        Position promotionBase=Position.fromFen("7k/P7/8/8/8/8/8/7K w - - 0 1");
        Position promoted=promotionBase.play(Move.parse("a7a8q"));
        GameTracker promotion=resume(model,new GameTracker(promotionBase),promoted,w,h);
        check(promotion.position().fen().equals(promoted.fen())&&promotion.initial().fen().equals(promotionBase.fen()),"promotion recovery keeps a manually confirmed baseline and correct promoted piece");
        AutoBoardSearch race=new AutoBoardSearch(true);int[] epPixels=CoreTest.screenWith(d5,w,h,40,300,640,false);
        check(race.inspect(epPixels,w,h,model,ep).state()==AutoBoardSearch.State.VERIFYING,"existing game begins verification");
        ep.accept(d5.pieces());
        check(race.inspect(epPixels,w,h,model,ep).state()==AutoBoardSearch.State.VERIFYING,"a changed source game invalidates the previous pending result");
        GameTracker raced=race.inspect(epPixels,w,h,model,ep).calibration().game().restore();
        check(raced.history().size()==4&&raced.position().fen().equals(d5.fen()),"renewed verification never appends a move twice");
        GameTracker.Snapshot snapshot=raced.snapshot();boolean immutable=false;
        try{snapshot.moves().clear();}catch(UnsupportedOperationException expected){immutable=true;}
        check(immutable&&snapshot.restore().position().fen().equals(d5.fen()),"confirmed snapshots own an immutable history");
        boolean invalid=false;try{new GameTracker.Snapshot(snapshot.initial(),Position.start(),snapshot.moves()).restore();}catch(IllegalArgumentException expected){invalid=true;}
        check(invalid,"snapshot with mismatched turn/position cannot be installed");
        int[] blank=new int[w*h];Arrays.fill(blank,0xff202523);AutoBoardSearch interrupted=new AutoBoardSearch(true);
        interrupted.inspect(pixels,w,h,model,previous);interrupted.inspect(blank,w,h,model,previous);
        check(interrupted.inspect(pixels,w,h,model,previous).state()==AutoBoardSearch.State.VERIFYING,"menu between frames cancels a pending new game");
        check(new AutoBoardSearch(true).inspect(pixels,w,h,null,null).state()==AutoBoardSearch.State.NEED_INITIAL,"an unknown theme is never invented from a partial opening");
        System.out.println("PASS: "+checks+" refresh regression assertions");
    }
    private static GameTracker resume(BoardVision model,GameTracker original,Position target,int w,int h){
        AutoBoardSearch search=new AutoBoardSearch(true);int[] pixels=CoreTest.screenWith(target,w,h,40,300,640,false);
        String before=original.position().fen();int moves=original.history().size();
        check(search.inspect(pixels,w,h,model,original).state()==AutoBoardSearch.State.VERIFYING,"legal continuation begins two-frame verification");
        AutoBoardSearch.Result ready=search.inspect(pixels,w,h,model,original);
        check(ready.state()==AutoBoardSearch.State.READY&&ready.calibration().reuseGame(),"middle-game refresh retains its original baseline");
        check(original.position().fen().equals(before)&&original.history().size()==moves,"catch-up probing leaves live history untouched");
        return ready.calibration().game().restore();
    }
}
