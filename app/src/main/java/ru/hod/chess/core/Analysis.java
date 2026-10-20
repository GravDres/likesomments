package ru.hod.chess.core;

import java.util.List;

/** Scores are from White's perspective. Negative depth explicitly means no reported evaluation. */
public record Analysis(String engine,int depth,long nodes,int centipawns,Integer mate,List<Line> lines) {
    public record Line(int rank,int centipawns,Integer mate,List<Move> moves) {}
    /** A legal authoritative bestmove is useful even when the UCI engine emits no scored PV. */
    public static Analysis moveOnly(String engine,Move best){
        java.util.Objects.requireNonNull(best);
        return new Analysis(engine,-1,0,0,null,List.of(new Line(1,0,null,List.of(best))));
    }
    public Move bestMove(){return lines.isEmpty()||lines.get(0).moves().isEmpty()?null:lines.get(0).moves().get(0);}
    public boolean hasSearchEvaluation(){return depth>=0;}
    public String score(){return depth<0?"—":mate!=null?"M"+mate:String.format(java.util.Locale.US,"%+.2f",centipawns/100.0);}
}
