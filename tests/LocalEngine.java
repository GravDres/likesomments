package ru.hod.chess.core;

import java.util.*;
import java.util.function.Consumer;

/** Small test-only search for rule regressions. Excluded from the Android source set and APK. */
public final class LocalEngine implements ChessEngine {
    private long deadline,nodes;
    private boolean timeout;
    public String name(){return "ХОД · компактный";}
    public void close(){}
    public void analyze(Position p,int millis,Consumer<Analysis> progress){
        deadline=System.nanoTime()+millis*1_000_000L;nodes=0;timeout=false;
        List<Move> root=p.legalMoves(); if(root.isEmpty()){progress.accept(new Analysis(name(),0,0,0,p.inCheck(p.white)?(p.white?-1:1):null,List.of()));return;}
        List<Analysis.Line> last=new ArrayList<>();
        for(int depth=1;depth<=12;depth++){
            List<Scored> scored=new ArrayList<>();
            for(Move move:root){int value=-search(p.applyUnchecked(move),depth-1,-32000,32000,1);if(timeout)break;scored.add(new Scored(move,value));}
            if(timeout)break;
            scored.sort(Comparator.comparingInt(Scored::score).reversed());root=new ArrayList<>();for(Scored s:scored)root.add(s.move());
            last=new ArrayList<>();for(int i=0;i<Math.min(3,scored.size());i++){int cp=scored.get(i).score()*(p.white?1:-1);Integer mate=Math.abs(cp)>29000?(cp>0?1:-1)*Math.max(1,(30000-Math.abs(cp)+1)/2):null;last.add(new Analysis.Line(i+1,cp,mate,List.of(scored.get(i).move())));}
            Analysis.Line best=last.get(0);progress.accept(new Analysis(name(),depth,nodes,best.centipawns(),best.mate(),List.copyOf(last)));
            if(Math.abs(scored.get(0).score())>29000)break;
        }
        if(last.isEmpty()&&!Thread.currentThread().isInterrupted())progress.accept(new Analysis(name(),0,nodes,evaluate(p)*(p.white?1:-1),null,List.of(new Analysis.Line(1,0,null,List.of(root.get(0))))));
    }
    private record Scored(Move move,int score){}
    private int search(Position p,int depth,int alpha,int beta,int ply){
        nodes++;if((nodes&127)==0&&(System.nanoTime()>deadline||Thread.currentThread().isInterrupted())){timeout=true;return 0;}
        List<Move> moves=p.legalMoves();if(moves.isEmpty())return p.inCheck(p.white)?-30000+ply:0;
        if(p.halfmove>=100)return 0;
        if(depth<=0)return quiesce(p,moves,alpha,beta,ply,0);
        moves.sort(Comparator.comparingInt((Move m)->order(p,m)).reversed());
        for(Move m:moves){int value=-search(p.applyUnchecked(m),depth-1,-beta,-alpha,ply+1);if(timeout)return 0;if(value>=beta)return beta;if(value>alpha)alpha=value;}return alpha;
    }
    private int quiesce(Position p,List<Move> moves,int alpha,int beta,int ply,int q){
        nodes++;if((nodes&127)==0&&(System.nanoTime()>deadline||Thread.currentThread().isInterrupted())){timeout=true;return 0;}
        boolean check=p.inCheck(p.white);int eval=evaluate(p);
        if(q>=6)return eval;
        if(!check){if(eval>=beta)return beta;alpha=Math.max(alpha,eval);}
        moves.sort(Comparator.comparingInt((Move m)->order(p,m)).reversed());
        for(Move m:moves){if(!check&&p.at(m.to())=='.'&&m.to()!=p.ep&&m.promotion()=='\0')continue;
            Position next=p.applyUnchecked(m);List<Move> replies=next.legalMoves();int value=replies.isEmpty()?(next.inCheck(next.white)?30000-ply:0):-quiesce(next,replies,-beta,-alpha,ply+1,q+1);
            if(timeout)return 0;if(value>=beta)return beta;alpha=Math.max(alpha,value);
        }return alpha;
    }
    private int order(Position p,Move m){return value(p.at(m.to()))*10-value(p.at(m.from()))+(m.promotion()!='\0'?value(m.promotion())*10:0);}
    private static int value(char p){return switch(Character.toLowerCase(p)){case 'p'->100;case 'n'->320;case 'b'->335;case 'r'->500;case 'q'->900;case 'k'->20000;default->0;};}
    private static int evaluate(Position p){int score=0;for(int i=0;i<64;i++){char c=p.at(i);if(c=='.')continue;boolean w=Position.isWhite(c);int row=w?7-i/8:i/8,col=i%8;int center=7-(Math.abs(2*col-7)+Math.abs(2*row-7))/2;
        int positional=switch(Character.toLowerCase(c)){case 'p'->row*8+center*2;case 'n'->center*12;case 'b'->center*6;case 'r'->row*2;case 'q'->center*2;default->-center*3;};score+=(w?1:-1)*(value(c)+positional);}return p.white?score:-score;}
}
