package ru.hod.chess.core;

/** Explicit local computation budgets. Does not read the opponent's clock. */
public enum TimeControlProfile {
    BULLET("1+0","Пуля · 1+0 · 0,3 с",300),
    BLITZ_3_2("3+2","Блиц · 3+2 · 0,6 с",600),
    BLITZ_5_0("5+0","Блиц · 5+0 · 0,9 с",900),
    RAPID_10_0("10+0","Рапид · 10 минут · 1,5 с",1500),
    RAPID_15_10("15+10","Рапид · 15+10 · 2,5 с",2500),
    CUSTOM("custom","Время расчёта вручную",0);
    public static final String PREFERENCE="timeProfile";
    private final String key,label;private final int budget;
    TimeControlProfile(String key,String label,int budget){this.key=key;this.label=label;this.budget=budget;}
    public String key(){return key;}public String label(){return label;}
    public int analysisMillis(int custom){return this==CUSTOM?Math.max(300,Math.min(8000,custom)):budget;}
    
    public static TimeControlProfile fromPreference(String key){for(var p:values())if(p.key.equals(key))return p;return BLITZ_3_2;}
}
