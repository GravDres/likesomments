package ru.hod.chess.core;

import java.util.Arrays;

/** Require two clean observations; animations never change the recorded game. */
public final class StableBoard {
    private char[] candidate;
    private int count;
    public synchronized boolean accept(BoardVision.Observation observation) {
        if (observation.uncertain() != 0) { clear(); return false; }
        if (Arrays.equals(candidate, observation.board())) count++;
        else { candidate = observation.board().clone(); count = 1; }
        return count >= 2;
    }
    public synchronized void clear() { candidate = null; count = 0; }
}
