package ru.hod.chess.core;

import java.util.*;

/** One-time defaults for the requested Lichess training update. Only a preference delta. */
public final class TrainingUpgrade {
    public static final String FLAG="training27Migrated";
    private TrainingUpgrade(){}
    public static Map<String,Object> changes(Map<String,?> previous){
        if(Boolean.TRUE.equals(previous.get(FLAG)))return Map.of();
        Map<String,Object> result=new LinkedHashMap<>();
        if(!previous.containsKey(EngineKind.PREFERENCE)){
            result.put(EngineKind.PREFERENCE,EngineKind.TEXEL.key());
            result.put(SearchLevel.PREFERENCE,SearchLevel.MODERATE.key());
        }
        if(!previous.containsKey(AdviceStyle.PREFERENCE))result.put(AdviceStyle.PREFERENCE,AdviceStyle.VARIED.key());
        result.put("chessPlatform",ChessPlatform.LICHESS.key());result.put(FLAG,true);
        return Map.copyOf(result);
    }
}
