package ru.hod.chess.core;

public enum MaiaNetwork {
    FAST("5m","Быстрая · 5M"),PRECISE("23m","Точная · 23M");
    public static final String PREFERENCE="maiaNetwork";
    private final String key,label;MaiaNetwork(String key,String label){this.key=key;this.label=label;}
    public String key(){return key;}public String label(){return label;}
    public String fileName(){return "maia3-"+key+".onnx";}
    public static MaiaNetwork fromPreference(String key){return "23m".equals(key)?PRECISE:FAST;}
}
