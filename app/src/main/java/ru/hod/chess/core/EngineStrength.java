package ru.hod.chess.core;

/** Stockfish's own skill setting. Levels are not Elo ratings or human models. */
public enum EngineStrength {
    BEGINNER(0,"Начальный · 0/20"),
    CASUAL(5,"Спокойный · 5/20"),
    MEDIUM(10,"Средний · 10/20"),
    STRONG(15,"Сильный · 15/20"),
    MAXIMUM(20,"Максимум · 20/20");
    public static final String PREFERENCE="engineSkill";
    public static final int DEFAULT_SKILL=10;
    private final int skill;private final String label;
    EngineStrength(int skill,String label){this.skill=skill;this.label=label;}
    public int skill(){return skill;}
    public String label(){return label;}
    public static EngineStrength fromPreference(int skill){
        for(EngineStrength value:values())if(value.skill==skill)return value;
        return MEDIUM;
    }
}
