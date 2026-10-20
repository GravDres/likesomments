package ru.hod.chess.core;

/** Public Tooken Club model IDs, not claims about chess strength or response speed. */
public enum ApiModel {
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5"),
    SOL("gpt-6.1-sol", "GPT-6.1 Sol"),
    LUNA("gpt-6-luna", "GPT-6 Luna"),
    OPUS("claude-opus-5-5", "Claude Opus 5.5");

    public static final String PREFERENCE = "apiModel";
    private final String id, label;
    ApiModel(String id, String label) { this.id = id; this.label = label; }
    public String id() { return id; }
    public String label() { return label; }
    public boolean isGpt() { return id.startsWith("gpt-"); }
    public static ApiModel fromPreference(String id) {
        for (ApiModel value : values()) if (value.id.equals(id)) return value;
        return SONNET;
    }
}
