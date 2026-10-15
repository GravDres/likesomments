package ru.hod.chess.core;

import java.util.*;

/** Bounded reconstruction of missed moves. Ambiguity never invents game history. */
final class LegalRecovery {
    record Result(List<Move> moves, boolean ambiguous, boolean incomplete) {}
    private final char[] target;
    private final long deadline;
    private int visited;
    private boolean incomplete,ambiguous;
    private List<Move> found;
    private LegalRecovery(char[] target,int millis){this.target=target.clone();deadline=System.nanoTime()+millis*1_000_000L;}
    static Result find(Position initial,char[] target,int maxPlies,int millis){
        LegalRecovery search=new LegalRecovery(target,millis);
        for(int depth=3;depth<=maxPlies;depth++){
            search.walk(initial,depth,new ArrayList<>());
            if(search.found!=null||search.ambiguous||search.incomplete)break;
        }
        return new Result(search.found,search.ambiguous,search.incomplete);
    }
    private void walk(Position position,int remaining,List<Move> path){
        if(incomplete||ambiguous)return;
        if(++visited>100_000||Thread.currentThread().isInterrupted()||System.nanoTime()>deadline){incomplete=true;return;}
        if(remaining==0){
            if(position.matches(target)){if(found!=null)ambiguous=true;else found=List.copyOf(path);}return;
        }
        int differences=0,whiteGoals=0,blackGoals=0;
        for(int i=0;i<64;i++)if(position.at(i)!=target[i]){differences++;if(target[i]!='.'){if(Position.isWhite(target[i]))whiteGoals++;else blackGoals++;}}
        // One ply can alter at most four squares (castling), and place at most
        // two friendly pieces. Captures cannot create the opponent's pieces.
        int whiteTurns=(remaining+(position.white?1:0))/2,blackTurns=remaining-whiteTurns;
        if(differences>remaining*4||whiteGoals>whiteTurns*2||blackGoals>blackTurns*2)return;
        List<Move> legal=position.legalMoves();
        if(remaining==1){
            for(Move move:legal){Position next=position.applyUnchecked(move);if(next.matches(target)){path.add(move);walk(next,0,path);path.remove(path.size()-1);if(ambiguous||incomplete)return;}}return;
        }
        for(Move move:legal){path.add(move);walk(position.applyUnchecked(move),remaining-1,path);path.remove(path.size()-1);if(ambiguous||incomplete)return;}
    }
}
