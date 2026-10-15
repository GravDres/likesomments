package ru.hod.chess.core;

import java.util.function.Consumer;

public interface ChessEngine extends AutoCloseable {
    void analyze(Position p, int millis, Consumer<Analysis> progress) throws Exception;
    String name();
    void close();
}
