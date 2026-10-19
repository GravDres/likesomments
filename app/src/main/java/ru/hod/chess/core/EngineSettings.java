package ru.hod.chess.core;

public record EngineSettings(EngineMode mode,MaiaModel model,MaiaNetwork network,TimeControlProfile profile,EngineStrength strength,int customMillis,boolean sampling) {
    public EngineSettings {java.util.Objects.requireNonNull(mode);java.util.Objects.requireNonNull(model);java.util.Objects.requireNonNull(network);java.util.Objects.requireNonNull(profile);java.util.Objects.requireNonNull(strength);}
    public int analysisMillis(){return profile.stockfishMillis(customMillis);}
    public String label(){return mode==EngineMode.MAIA?model.label()+" · "+network.key():"Stockfish · "+strength.skill()+"/20";}
}
