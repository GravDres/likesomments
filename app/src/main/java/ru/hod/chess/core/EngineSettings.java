package ru.hod.chess.core;

public record EngineSettings(EngineMode mode,MaiaModel model,MaiaNetwork network,TimeControlProfile profile,EngineStrength strength,int customMillis,boolean sampling) {
    public EngineSettings {java.util.Objects.requireNonNull(mode);java.util.Objects.requireNonNull(model);java.util.Objects.requireNonNull(network);java.util.Objects.requireNonNull(profile);java.util.Objects.requireNonNull(strength);}
    public int analysisMillis(){return profile.stockfishMillis(customMillis);}
    public int responseTimeoutMillis(){return Math.max(15000,profile.inferenceTimeoutMillis()+5000+(mode==EngineMode.MAIA_TACTICAL?analysisMillis():0));}
    public String label(){return mode.usesMaia()?model.label()+" · "+network.key()+(mode==EngineMode.MAIA_TACTICAL?" + проверка":""):"Stockfish · "+strength.skill()+"/20";}
}
