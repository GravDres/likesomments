package ru.hod.chess.core;

public enum EngineMode {
    MAIA("maia","Maia-3 · человеческие партии"), STOCKFISH("stockfish","Stockfish · анализ"),
    MAIA_TACTICAL("maia-tactical","Maia-3 + тактическая проверка"),
    API("api","API · Claude / GPT · эксперимент");
    public static final String PREFERENCE="engineMode";
    private final String key,label;
    EngineMode(String key,String label){this.key=key;this.label=label;}
    public String key(){return key;}public String label(){return label;}
    public boolean usesMaia(){return this==MAIA||this==MAIA_TACTICAL;}
    public static EngineMode fromPreference(String key){for(var mode:values())if(mode.key.equals(key))return mode;return MAIA;}
}
