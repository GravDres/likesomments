package ru.hod.chess.core;

import java.util.List;
import java.util.function.Consumer;

/** Searches one legal root move while retaining the complete game's history. */
public interface RootMoveEngine extends HistoryEngine {
    void analyzeMoveGame(Position initial,Position expected,List<Move> history,Move move,int millis,Consumer<Analysis> progress)throws Exception;
    /** Compare exactly two roots in one MultiPV search at a shared completed depth. */
    void compareMovesGame(Position initial,Position expected,List<Move> history,List<Move> roots,int millis,Consumer<Analysis> progress)throws Exception;
    final class IncompleteComparisonException extends java.io.IOException {
        public IncompleteComparisonException(){super("Нет полной итерации сравнения двух ходов");}
    }
}
