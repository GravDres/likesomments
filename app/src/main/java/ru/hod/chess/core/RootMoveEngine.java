package ru.hod.chess.core;

import java.util.List;
import java.util.function.Consumer;

/** Searches one legal root move while retaining the complete game's history. */
public interface RootMoveEngine extends HistoryEngine {
    void analyzeMoveGame(Position initial,Position expected,List<Move> history,Move move,int millis,Consumer<Analysis> progress)throws Exception;
}
