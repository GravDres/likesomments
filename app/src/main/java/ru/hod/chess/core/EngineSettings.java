package ru.hod.chess.core;

/** Local search budgets; these are not Elo ratings or human-style settings. */
public record EngineSettings(TimeControlProfile profile,int customMillis,int threads,int hashMiB) {
    public EngineSettings {
        java.util.Objects.requireNonNull(profile);
        customMillis=Math.max(300,Math.min(8000,customMillis));
        threads=Math.max(1,Math.min(4,threads));
        hashMiB=hashMiB<=64?64:hashMiB<=128?128:256;
    }
    public int analysisMillis(){return profile.analysisMillis(customMillis);}
    public int responseTimeoutMillis(){return Math.max(15000,analysisMillis()+7000);}
    public String label(){return "Caissa 2.0 · "+threads+" пот. · "+hashMiB+" МБ";}
}
