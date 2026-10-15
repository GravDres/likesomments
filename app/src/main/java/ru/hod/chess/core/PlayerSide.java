package ru.hod.chess.core;

/** The user's side is independent of the board's screen orientation. */
public enum PlayerSide {
    WHITE, BLACK;
    public boolean ownsTurn(Position position) { return position.white == (this == WHITE); }
    public String label() { return this == WHITE ? "белыми" : "чёрными"; }
    public PlayerSide opposite() { return this == WHITE ? BLACK : WHITE; }
}
