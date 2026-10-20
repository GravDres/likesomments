package ru.hod.chess.core;

import java.util.List;

/** Scores are from White's perspective; each best move keeps its own verified PV score. */
public record Analysis(String engine,int depth,long nodes,int centipawns,Integer mate,List<Line> lines) {
    public record Line(int rank,int centipawns,Integer mate,List<Move> moves) {}
    public Move bestMove(){return lines.isEmpty()||lines.get(0).moves().isEmpty()?null:lines.get(0).moves().get(0);}
    public boolean hasSearchEvaluation(){return depth>=0;}
    public String score(){return depth<0?"—":mate!=null?"M"+mate:String.format(java.util.Locale.US,"%+.2f",centipawns/100.0);}
}
