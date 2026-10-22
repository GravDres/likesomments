package ru.hod.chess.core;

/** Explicit local engine choice; never substitute another engine after a failure. */
public enum EngineKind {
    TEXEL("texel","Texel 1.12","libtexel.so"),
    CAISSA("caissa","Caissa 2.0","libcaissa.so");
    public static final String PREFERENCE="localEngineKind";
    private final String key,label,binary;
    EngineKind(String key,String label,String binary){this.key=key;this.label=label;this.binary=binary;}
    public String key(){return key;}
    public String label(){return label;}
    public String binary(){return binary;}
    public boolean matchesId(String name){return name!=null&&(name.equals(label)||name.startsWith(label+" "));}
    public static EngineKind fromPreference(String value){for(EngineKind k:values())if(k.key.equals(value))return k;return TEXEL;}
}
