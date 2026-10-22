package ru.hod.chess.core;

/** Training search budgets. Not calibrated human Elo or a claim about playing style. */
public enum SearchLevel {
    LIGHT("light","Лёгкий",2000),
    MODERATE("moderate","Умеренный",12000),
    FULL("full","Максимальный",0);
    public static final String PREFERENCE="caissaSearchLevel";
    private final String key,label;
    private final long nodes;
    SearchLevel(String key,String label,long nodes){this.key=key;this.label=label;this.nodes=nodes;}
    public String key(){return key;}
    public String label(){return label;}
    /** Zero leaves only the existing time budget. Native counters can slightly overshoot. */
    public long nodeLimit(){return nodes;}
    /** Texel's own UCI scale. Not a measured Lichess account rating. */
    public int texelElo(){return this==LIGHT?1600:this==MODERATE?1900:2900;}
    public static SearchLevel fromPreference(String key){
        for(SearchLevel level:values())if(level.key.equals(key))return level;
        return MODERATE;
    }
}
