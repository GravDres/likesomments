package ru.hod.chess.core;

import java.util.*;

/** Maia-3 input and legal policy adapter. Matches CSSLab/maia3 dataset.py;
 * derived interface/encoding, AGPL-3.0, attribution in MAIA3-NOTICE.md. */
public final class MaiaPolicy {
    public static final int HISTORY=8,FEATURES=97,VOCAB=4352;
    public record Choice(Move move,double probability){}
    private MaiaPolicy(){}
    public static float[] encode(Position initial,Position expected,List<Move> moves){
        ArrayDeque<Position> history=new ArrayDeque<>();history.add(initial);Position cursor=initial;
        for(Move move:moves){cursor=cursor.play(move);history.add(cursor);if(history.size()>HISTORY)history.removeFirst();}
        if(!cursor.fen().equals(expected.fen()))throw new IllegalArgumentException("История не соответствует позиции Maia");
        ArrayList<Position> frames=new ArrayList<>();while(frames.size()+history.size()<HISTORY)frames.add(history.getFirst());frames.addAll(history);
        float[] tokens=new float[64*FEATURES];
        for(int frame=0;frame<HISTORY;frame++){
            Position p=frames.get(frame);for(int square=0;square<64;square++){
                char piece=p.at(square);if(piece=='.')continue;int role="pnbrqk".indexOf(Character.toLowerCase(piece));int color=Position.isWhite(piece)==p.white?0:6;
                int token=p.white?square^56:square;tokens[token*FEATURES+frame*12+color+role]=1;
            }
        }
        return tokens;
    }
    public static int moveIndex(Move move,boolean white){
        int from=white?move.from()^56:move.from(),to=white?move.to()^56:move.to();
        if(move.promotion()=='\0')return from*64+to;
        int role="qrbn".indexOf(move.promotion());if(role<0||from/8!=6||to/8!=7)throw new IllegalArgumentException("Некорректное превращение Maia");
        return 4096+(from%8*8+to%8)*4+role;
    }
    public static Choice choose(Position p,float[] logits,boolean sampling,double unit){
        if(logits.length!=VOCAB||!Double.isFinite(unit)||unit<0||unit>=1)throw new IllegalArgumentException("Некорректный выход Maia");
        for(float value:logits)if(!Float.isFinite(value))throw new IllegalArgumentException("Сеть Maia вернула нечисловой результат");
        List<Move> legal=p.legalMoves();if(legal.isEmpty())return new Choice(null,0);
        double max=-Double.MAX_VALUE;int best=0;for(int i=0;i<legal.size();i++){double value=logits[moveIndex(legal.get(i),p.white)];if(value>max){max=value;best=i;}}
        double[] mass=new double[legal.size()];double sum=0;for(int i=0;i<mass.length;i++){mass[i]=Math.exp(logits[moveIndex(legal.get(i),p.white)]-max);sum+=mass[i];}
        int chosen=best;if(sampling){double target=unit*sum,cumulative=0;chosen=mass.length-1;for(int i=0;i<mass.length;i++){cumulative+=mass[i];if(target<cumulative){chosen=i;break;}}}
        return new Choice(legal.get(chosen),mass[chosen]/sum);
    }
}
