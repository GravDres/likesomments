package ru.hod.chess.core;

/** Local search budgets; these are not Elo ratings or human-style settings. */
public record EngineSettings(TimeControlProfile profile,int customMillis,int threads,int hashMiB,SearchLevel level,EngineKind kind,AdviceStyle advice) {
    public EngineSettings(TimeControlProfile profile,int customMillis,int threads,int hashMiB,SearchLevel level){this(profile,customMillis,threads,hashMiB,level,EngineKind.CAISSA,AdviceStyle.EXACT);}
    public EngineSettings(TimeControlProfile profile,int customMillis,int threads,int hashMiB){this(profile,customMillis,threads,hashMiB,SearchLevel.MODERATE);}
    public EngineSettings {
        java.util.Objects.requireNonNull(profile);
        java.util.Objects.requireNonNull(level);
        java.util.Objects.requireNonNull(kind);
        java.util.Objects.requireNonNull(advice);
        customMillis=Math.max(300,Math.min(8000,customMillis));
        threads=Math.max(1,Math.min(4,threads));
        hashMiB=hashMiB<=64?64:hashMiB<=128?128:256;
    }
    public int analysisMillis(){return profile.analysisMillis(customMillis);}
    public int responseTimeoutMillis(){return Math.max(15000,analysisMillis()+7000);}
    public int effectiveThreads(){return kind==EngineKind.TEXEL&&level!=SearchLevel.FULL?1:threads;}
    public int variations(int requested){return kind==EngineKind.CAISSA&&advice==AdviceStyle.VARIED||requested==3?3:1;}
    public String strengthLabel(){return level.label()+(kind==EngineKind.TEXEL&&level!=SearchLevel.FULL?" · UCI "+level.texelElo():"");}
    public String label(){return kind.label()+" · "+strengthLabel()+" · "+effectiveThreads()+" пот. · "+hashMiB+" МБ";}
}
