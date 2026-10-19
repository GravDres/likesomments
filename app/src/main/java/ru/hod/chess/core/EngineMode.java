package ru.hod.chess.core;

public enum EngineMode {
    MAIA("maia","Maia-3 · человеческие партии"), STOCKFISH("stockfish","Stockfish · анализ");
    public static final String PREFERENCE="engineMode";
    private final String key,label;
    EngineMode(String key,String label){this.key=key;this.label=label;}
    public String key(){return key;}public String label(){return label;}
    public static EngineMode fromPreference(String key){return "stockfish".equals(key)?STOCKFISH:MAIA;}
}
