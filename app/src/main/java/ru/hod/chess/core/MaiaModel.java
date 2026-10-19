package ru.hod.chess.core;

/** Maia-3 Elo conditioning, not a measured rating of this application. */
public enum MaiaModel {
    M1600(1600),M2000(2000),M2200(2200),M2400(2400),M2600(2600);
    public static final String PREFERENCE="maiaModel";
    private final int rating;MaiaModel(int rating){this.rating=rating;}
    public int rating(){return rating;}public String label(){return "Maia-3 · уровень "+rating;}
    public static MaiaModel fromPreference(int rating){for(var m:values())if(m.rating==rating)return m;return M2200;}
}
