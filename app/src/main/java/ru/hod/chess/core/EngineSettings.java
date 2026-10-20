package ru.hod.chess.core;

public record EngineSettings(EngineMode mode,MaiaModel model,MaiaNetwork network,TimeControlProfile profile,EngineStrength strength,int customMillis,boolean sampling,ApiModel apiModel) {
    public EngineSettings(EngineMode mode,MaiaModel model,MaiaNetwork network,TimeControlProfile profile,EngineStrength strength,int customMillis,boolean sampling){this(mode,model,network,profile,strength,customMillis,sampling,ApiModel.SONNET);}
    public EngineSettings {java.util.Objects.requireNonNull(mode);java.util.Objects.requireNonNull(model);java.util.Objects.requireNonNull(network);java.util.Objects.requireNonNull(profile);java.util.Objects.requireNonNull(strength);java.util.Objects.requireNonNull(apiModel);}
    public int analysisMillis(){return profile.stockfishMillis(customMillis);}
    public int responseTimeoutMillis(){return mode==EngineMode.API?ApiEngine.TIMEOUT_MILLIS+5000:Math.max(15000,profile.inferenceTimeoutMillis()+5000+(mode==EngineMode.MAIA_TACTICAL?2*analysisMillis():0));}
    public String label(){return mode==EngineMode.API?"API · "+apiModel.label():mode.usesMaia()?model.label()+" · "+network.key()+(mode==EngineMode.MAIA_TACTICAL?" + проверка":""):"Stockfish · "+strength.skill()+"/20";}
}
