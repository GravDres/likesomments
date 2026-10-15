package ru.hod.chess.core;

public record Move(int from, int to, char promotion) {
    public Move(int from, int to) { this(from, to, '\0'); }
    public String uci() { return Position.square(from) + Position.square(to) + (promotion == '\0' ? "" : promotion); }
    public static Move parse(String uci) {
        if (uci == null || !uci.matches("[a-h][1-8][a-h][1-8][qrbn]?")) throw new IllegalArgumentException("Некорректный ход");
        return new Move(Position.index(uci.substring(0,2)), Position.index(uci.substring(2,4)), uci.length() == 5 ? uci.charAt(4) : '\0');
    }
}
