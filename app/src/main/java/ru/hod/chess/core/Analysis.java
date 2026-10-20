package ru.hod.chess.core;

import java.util.List;

/** Scores are from White's perspective. Negative depth means no search evaluation (API). */
public record Analysis(String engine, int depth, long nodes, int centipawns, Integer mate, List<Line> lines,Double policyProbability) {
    public Analysis(String engine,int depth,long nodes,int centipawns,Integer mate,List<Line> lines){this(engine,depth,nodes,centipawns,mate,lines,null);}
    public record Line(int rank, int centipawns, Integer mate, List<Move> moves) {}
    public Move bestMove(){return lines.isEmpty()||lines.get(0).moves().isEmpty()?null:lines.get(0).moves().get(0);}
    public boolean hasSearchEvaluation(){return depth>=0&&policyProbability==null;}
    public String score(){return depth<0?"—":policyProbability!=null?"P "+Math.round(policyProbability*100)+"%":mate!=null?"M"+mate:String.format(java.util.Locale.US,"%+.2f",centipawns/100.0);}
}
