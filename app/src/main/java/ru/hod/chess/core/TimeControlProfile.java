package ru.hod.chess.core;

/** Explicit user presets. Does not read clocks or change Maia's one-node policy. */
public enum TimeControlProfile {
    BULLET("1+0","Пуля · 1+0",300,4000),
    BLITZ_3_2("3+2","Блиц · 3+2",600,6000),
    BLITZ_5_0("5+0","Блиц · 5+0",900,8000),
    RAPID_10_0("10+0","Рапид · 10 минут",1500,12000),
    RAPID_15_10("15+10","Рапид · 15+10",2500,15000),
    CUSTOM("custom","Время расчёта вручную",0,12000);
    public static final String PREFERENCE="timeProfile";
    private final String key,label;private final int budget,timeout;
    TimeControlProfile(String key,String label,int budget,int timeout){this.key=key;this.label=label;this.budget=budget;this.timeout=timeout;}
    public String key(){return key;}public String label(){return label;}
    public int stockfishMillis(int custom){return this==CUSTOM?Math.max(300,Math.min(8000,custom)):budget;}
    public int inferenceTimeoutMillis(){return timeout;}
    public static TimeControlProfile fromPreference(String key){for(var p:values())if(p.key.equals(key))return p;return BLITZ_3_2;}
}
