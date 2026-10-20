package ru.hod.chess.core;

import java.util.*;
import java.util.function.LongSupplier;

/** Exact one-ply rule facts. No evaluation, move ordering, pruning or chosen move. */
public final class ApiChessFacts {
    public static final long BUDGET_NANOS = 80_000_000L;
    public static final int MAX_TEXT = 8000;
    public record Fact(String move, String capture, boolean check, boolean mate, List<String> opponentCaptures) {
        public Fact { opponentCaptures = List.copyOf(opponentCaptures); }
        public String text() {
            return move + ": take=" + capture + (mate ? "; CHECKMATE" : check ? "; check" : "")
                    + "; opponent legal captures=" + (opponentCaptures.isEmpty() ? "none" : String.join(",", opponentCaptures));
        }
    }
    public record Snapshot(String pieces, List<Fact> facts, int legalCount, boolean complete) {
        public Snapshot { facts = List.copyOf(facts); }
        public String text() {
            StringBuilder out = new StringBuilder("Pieces: ").append(pieces)
                    .append("\nRule annotations (not evaluations; a possible capture is not proof a move is bad):\n");
            for (Fact fact : facts) out.append(fact.text()).append('\n');
            return out.append("Coverage ").append(facts.size()).append('/').append(legalCount)
                    .append(complete ? ". All legal moves annotated." : ". INCOMPLETE: unannotated moves are unknown, not safe or inferior.")
                    .append(" No deeper tactics were searched.\n").toString();
        }
    }
    private ApiChessFacts() {}
    public static Snapshot inspect(Position p) throws InterruptedException { return inspect(p, BUDGET_NANOS, System::nanoTime); }
    public static Snapshot inspect(Position p, long budgetNanos, LongSupplier clock) throws InterruptedException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("Подготовка позиции отменена");
        long start = clock.getAsLong();
        List<Move> legal = p.legalMoves();
        // Coordinate order only; no candidate receives a numerical score or preference.
        legal.sort(Comparator.comparing(Move::uci));
        List<Fact> facts = new ArrayList<>(); int size = 0;
        for (Move move : legal) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Подготовка позиции отменена");
            if (clock.getAsLong() - start >= budgetNanos) break;
            Position after = p.applyUnchecked(move);
            List<Move> replies = after.legalMoves();
            List<String> captures = new ArrayList<>();
            for (Move reply : replies) {
                char captured = victim(after, reply);
                if (captured != '.') captures.add(reply.uci() + "(" + Character.toUpperCase(captured) + ")");
            }
            Collections.sort(captures);
            char captured = victim(p, move);
            boolean check = after.inCheck(after.white);
            Fact fact = new Fact(move.uci(), captured == '.' ? "-" : String.valueOf(Character.toUpperCase(captured)),
                    check, check && replies.isEmpty(), captures);
            int length = fact.text().length() + 1;
            if (size + length > MAX_TEXT - 1000) break;
            facts.add(fact); size += length;
        }
        StringBuilder pieces = new StringBuilder();
        for (boolean white : new boolean[]{true, false}) {
            pieces.append(white ? "White " : "; Black ");
            for (int i = 0; i < 64; i++) {
                char piece = p.at(i);
                if (piece != '.' && Position.isWhite(piece) == white)
                    pieces.append(Character.toUpperCase(piece)).append(Position.square(i)).append(' ');
            }
        }
        return new Snapshot(pieces.toString().trim(), facts, legal.size(), facts.size() == legal.size());
    }
    private static char victim(Position p, Move move) {
        char piece = p.at(move.to());
        if (piece == '.' && Character.toLowerCase(p.at(move.from())) == 'p' && move.to() == p.ep && move.from() % 8 != move.to() % 8)
            return p.white ? 'p' : 'P';
        return piece;
    }
}
