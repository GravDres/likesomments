package ru.hod.chess.core;

/** Bounded, legal same-square exchanges. A guard for optional alternatives, not a full search. */
public final class ExchangeSafety {
    private ExchangeSafety(){}
    private static final class Budget {int left=128;boolean exhausted;}
    /** After our candidate: can the opponent win more than a pawn by an immediate exchange? */
    public static boolean allowsVariation(Position after){
        Budget budget=new Budget();
        for(Move reply:after.legalMoves()){
            int gain=gain(after,reply);if(gain<=100)continue;
            int returned=bestCapture(after.applyUnchecked(reply),reply.to(),budget);
            if(budget.exhausted||gain-returned>100)return false;
        }
        return true;
    }
    private static int bestCapture(Position p,int target,Budget budget){
        if(--budget.left<0){budget.exhausted=true;return 0;}
        int best=0;
        for(Move move:p.legalMoves()){
            if(move.to()!=target)continue;int gain=gain(p,move);if(gain==0)continue;
            int score=gain-bestCapture(p.applyUnchecked(move),target,budget);
            if(budget.exhausted)return 0;best=Math.max(best,score);
        }
        return best;
    }
    private static int gain(Position p,Move move){
        int gain=value(p.at(move.to()));
        if(gain==0&&Character.toLowerCase(p.at(move.from()))=='p'&&move.from()%8!=move.to()%8)gain=100;
        if(move.promotion()!='\0')gain+=value(move.promotion())-100;
        return gain;
    }
    private static int value(char piece){return switch(Character.toLowerCase(piece)){case 'p'->100;case 'n'->320;case 'b'->330;case 'r'->500;case 'q'->900;default->0;};}
}
