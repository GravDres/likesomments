package ru.hod.chess.core;
import java.util.List;
import java.util.function.Consumer;
public interface HistoryEngine extends ChessEngine {
    void analyzeGame(Position initial,Position expected,List<Move> history,int millis,Consumer<Analysis> progress)throws Exception;
    default void newGame()throws Exception{}
}
