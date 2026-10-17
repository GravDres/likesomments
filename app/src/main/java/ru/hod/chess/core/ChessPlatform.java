package ru.hod.chess.core;

/** Selected capture source; it never changes standard chess rules or the user's side. */
public enum ChessPlatform {
    CHESS_COM("chesscom","Chess.com",new String[]{"com.chess"},"last-game.hod","previous-game.hod"),
    LICHESS("lichess","Lichess",new String[]{"org.lichess.mobileV2","org.lichess.mobileapp"},"lichess-last-game.hod","lichess-previous-game.hod");
    private final String key,label,sessionFile,archiveFile;private final String[] packages;
    ChessPlatform(String key,String label,String[] packages,String sessionFile,String archiveFile){this.key=key;this.label=label;this.packages=packages;this.sessionFile=sessionFile;this.archiveFile=archiveFile;}
    public String key(){return key;}public String label(){return label;}public String[] packages(){return packages.clone();}
    public String sessionFile(){return sessionFile;}public String archiveFile(){return archiveFile;}
    /** Existing installations keep their original Chess.com storage unless explicitly switched. */
    public static ChessPlatform fromPreference(String value){return LICHESS.key.equals(value)?LICHESS:CHESS_COM;}
}
