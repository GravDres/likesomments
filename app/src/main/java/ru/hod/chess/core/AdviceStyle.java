package ru.hod.chess.core;

/** Presentation/training choice, unrelated to any site's anti-cheat system. */
public enum AdviceStyle {
    EXACT("exact","Точный ход"),
    VARIED("varied","Близкие варианты"),
    IDEAS("ideas","Намёк без хода");
    public static final String PREFERENCE="adviceStyle";
    private final String key,label;
    AdviceStyle(String key,String label){this.key=key;this.label=label;}
    public String key(){return key;}
    public String label(){return label;}
    public static AdviceStyle fromPreference(String value){for(AdviceStyle s:values())if(s.key.equals(value))return s;return VARIED;}
}
