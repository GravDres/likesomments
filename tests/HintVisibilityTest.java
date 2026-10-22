import java.util.*;
import ru.hod.chess.core.*;

/** State transitions only; actual Android outside-touch delivery requires a phone. */
public final class HintVisibilityTest {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    public static void main(String[] args){
        HintVisibility gate=new HintVisibility();Position initial=Position.start();
        gate.confirmed(initial);check(!gate.hidden(initial),"new position visible");
        check(gate.touch(initial)&&gate.hidden(initial),"first touch hides default enabled mode");
        check(!gate.touch(initial),"repeated touch is idempotent");
        for(int frame=0;frame<25;frame++){
            gate.confirmed(initial);gate.markers(initial,frame%2==0?7:0);
            check(gate.hidden(initial),"same position cannot be revealed by another frame, deselection or a late result");
        }
        Position whiteMoved=initial.play(Move.parse("e2e4"));
        gate.confirmed(whiteMoved);check(!gate.hidden(whiteMoved)&&!gate.hidden(initial),"confirmed legal move releases old advice latch");
        Position nextTurn=whiteMoved.play(Move.parse("e7e5"));gate.confirmed(nextTurn);
        check(!gate.hidden(nextTurn),"next own turn is ready for fresh advice");
        check(gate.markers(nextTurn,4)&&gate.hidden(nextTurn),"visual selection hides without outside-touch delivery");
        gate.reveal();check(!gate.hidden(nextTurn),"explicit reveal restores current advice");
        for(int frame=0;frame<10;frame++){gate.markers(nextTurn,4);check(!gate.hidden(nextTurn),"same visible dots cannot defeat explicit reveal");}
        gate.markers(nextTurn,0);check(gate.markers(nextTurn,2)&&gate.hidden(nextTurn),"new selection hides again after deselection");
        gate.reveal();check(gate.touch(nextTurn)&&gate.hidden(nextTurn),"fresh physical touch overrides reveal");
        gate.enabled(false);check(!gate.hidden(nextTurn),"disabling releases current latch");
        check(!gate.touch(nextTurn)&&!gate.markers(nextTurn,7)&&!gate.hidden(nextTurn),"disabled mode ignores both detectors");
        gate.enabled(true);check(!gate.hidden(nextTurn),"reenabling does not fabricate a touch");
        gate.markers(nextTurn,3);check(gate.hidden(nextTurn),"reenabled markers work");
        gate.reset();gate.confirmed(nextTurn);check(!gate.hidden(nextTurn),"new game or explicit context reset restores same FEN");
        check(!gate.touch(null),"touch before confirmed board does nothing");
        GameTracker game=GameTracker.replay(List.of(Move.parse("e2e4"),Move.parse("e7e5")));
        String fen=game.position().fen();List<Move> history=game.history();gate.confirmed(game.position());gate.touch(game.position());
        for(int frame=0;frame<8;frame++){
            check(game.accept(game.position().pieces()).status()==GameTracker.Status.SAME,"selection changes no chess move");
            gate.confirmed(game.position());check(gate.hidden(game.position()),"stable tracking does not redisplay advice");
        }
        check(game.position().fen().equals(fen)&&game.history().equals(history),"hiding never changes FEN or history");
        Position after=game.position().play(Move.parse("g1f3"));
        check(game.accept(after.pieces()).status()==GameTracker.Status.MOVED,"real own move still accepted while hidden");
        gate.confirmed(game.position());check(!gate.hidden(game.position()),"actual move releases latch");
        after=game.position().play(Move.parse("b8c6"));game.accept(after.pieces());gate.confirmed(game.position());
        check(!gate.hidden(game.position())&&game.history().size()==4,"opponent reply enables next turn without losing history");
        for(boolean white:new boolean[]{true,false}){
            Position p=Position.fromFen(white?Position.START:"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1");
            gate.reset();gate.touch(p);gate.confirmed(p);check(gate.hidden(p),"touch before clean frame remains hidden for either side");
            gate.reveal();gate.markers(p,3);check(!gate.hidden(p),"reveal while selected works for either side");
            Position moved=p.play(p.legalMoves().get(0));gate.confirmed(moved);check(!gate.hidden(moved),"first confirmed change restores either side");
        }
        System.out.println("PASS: "+checks+" hint-visibility assertions (state, late callbacks, markers and legal history; not Android touch delivery)");
    }
}
