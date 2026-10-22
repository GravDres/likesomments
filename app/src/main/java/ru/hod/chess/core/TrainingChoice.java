package ru.hod.chess.core;

import java.util.*;

/** Conservative variety among scored root alternatives. Not an anti-detection mechanism. */
public final class TrainingChoice {
    private TrainingChoice(){}
    public static Analysis select(Position p,Analysis best,EngineKind kind,AdviceStyle style){
        // Texel already chooses moves through its native strength limiter; never weaken it twice.
        if(kind!=EngineKind.CAISSA||style!=AdviceStyle.VARIED||!best.hasSearchEvaluation()||best.depth()<6||best.mate()!=null||best.bestMove()==null||p.inCheck(p.white))return best;
        Move root=best.bestMove();
        if(tactical(p,root)||danger(p))return best;
        int sign=p.white?1:-1,reference=sign*best.centipawns();
        if(Math.abs(reference)>200)return best;
        List<Analysis.Line> choices=new ArrayList<>();Set<Move> unique=new HashSet<>();
        // The first line is the authoritative move. Only alternatives from its current iteration qualify.
        choices.add(best.lines().get(0));unique.add(root);
        for(Analysis.Line line:best.lines()){
            if(line.moves().isEmpty()||line.mate()!=null)continue;Move move=line.moves().get(0);
            if(unique.contains(move)||!p.legalMoves().contains(move))continue;
            int score=sign*line.centipawns(),loss=reference-score;
            if(loss<0||loss>60||reference>=0&&score< -20||tactical(p,move))continue;
            Position after=p.applyUnchecked(move);
            if(danger(after,p.white)||immediateMate(after)||!ExchangeSafety.allowsVariation(after))continue;
            unique.add(move);choices.add(line);if(choices.size()==3)break;
        }
        if(choices.size()==1)return best;
        // Stable on refresh and across move counters: an unchanged board must not produce a new recommendation.
        int pick=Math.floorMod(p.repetitionKey().hashCode(),100);
        int index=pick<50?0:pick<85?1:choices.size()-1;
        if(index==0)return best;
        Analysis.Line chosen=choices.get(index);
        return new Analysis(best.engine(),best.depth(),best.nodes(),chosen.centipawns(),chosen.mate(),List.of(new Analysis.Line(1,chosen.centipawns(),chosen.mate(),chosen.moves())));
    }
    private static boolean tactical(Position p,Move m){
        if(m.promotion()!='\0'||p.at(m.to())!='.')return true;
        if(Character.toLowerCase(p.at(m.from()))=='p'&&m.from()%8!=m.to()%8)return true; // en passant
        Position after=p.applyUnchecked(m);return after.inCheck(after.white);
    }
    private static boolean danger(Position p){return danger(p,p.white);}
    private static boolean danger(Position p,boolean white){
        for(int i=0;i<64;i++){
            char piece=p.at(i);if(piece=='.'||Position.isWhite(piece)!=white)continue;
            char type=Character.toLowerCase(piece);
            if("qrbn".indexOf(type)>=0&&p.attacked(i,!white)&&!p.attacked(i,white))return true;
        }
        return false;
    }
    private static boolean immediateMate(Position p){
        for(Move reply:p.legalMoves()){Position after=p.applyUnchecked(reply);if(after.inCheck(after.white)&&after.legalMoves().isEmpty())return true;}
        return false;
    }
}
