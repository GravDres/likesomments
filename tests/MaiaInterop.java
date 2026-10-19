import ru.hod.chess.core.*;
import java.util.*;

/** CI compares this adapter to the actual upstream Maia-3 Python encoding. */
public final class MaiaInterop {
    public static void main(String[] args){
        Position initial=Position.fromFen(args[0]),p=initial;var moves=new ArrayList<Move>();if(args.length>1&&!args[1].isBlank())for(String uci:args[1].split(",")){Move move=Move.parse(uci);moves.add(move);p=p.play(move);}
        float[] tokens=MaiaPolicy.encode(initial,p,moves);StringBuilder s=new StringBuilder("{\"tokens\":[");for(int i=0;i<tokens.length;i++){if(i>0)s.append(',');s.append((int)tokens[i]);}s.append("],\"indices\":{");boolean first=true;for(Move move:p.legalMoves()){if(!first)s.append(',');first=false;s.append('"').append(move.uci()).append("\":").append(MaiaPolicy.moveIndex(move,p.white));}s.append("}}");System.out.println(s);
    }
}
