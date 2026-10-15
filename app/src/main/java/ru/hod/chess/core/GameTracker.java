package ru.hod.chess.core;

import java.util.*;

/** Keeps castling and en-passant rights by accepting only legal transitions. */
public final class GameTracker {
    public enum Status { SAME, MOVED, UNRECOGNIZED, AMBIGUOUS, RECOVERY_LIMIT }
    public record Update(Status status, Position position, List<Move> moves) {}
    private Position current;
    private final List<Move> history=new ArrayList<>();
    private final Map<String,Integer> repetitions=new HashMap<>();
    public GameTracker(Position initial){current=initial;repetitions.put(initial.repetitionKey(),1);}
    public static GameTracker replay(List<Move> moves){
        GameTracker game=new GameTracker(Position.start());
        for(Move move:moves){game.current=game.current.play(move);game.history.add(move);game.repetitions.merge(game.current.repetitionKey(),1,Integer::sum);}
        return game;
    }
    public synchronized Position position(){return current;}
    public synchronized List<Move> history(){return List.copyOf(history);}
    public synchronized Update accept(char[] board) {
        if(current.matches(board))return new Update(Status.SAME,current,List.of());
        List<Move> next=current.legalMoves(); List<Move> found=null;Position result=null;
        for(Move a:next){Position p=current.applyUnchecked(a);if(p.matches(board)){if(found!=null)return new Update(Status.AMBIGUOUS,current,List.of());found=List.of(a);result=p;}}
        if(found==null)for(Move a:next){Position p=current.applyUnchecked(a);for(Move b:p.legalMoves()){Position q=p.applyUnchecked(b);if(q.matches(board)){if(found!=null)return new Update(Status.AMBIGUOUS,current,List.of());found=List.of(a,b);result=q;}}}
        if(found==null)return new Update(Status.UNRECOGNIZED,current,List.of());
        return commit(found);
    }
    /** Only called after stable frames fail normal matching; never resets metadata. */
    public synchronized Update recover(char[] board){
        Update quick=accept(board);if(quick.status()!=Status.UNRECOGNIZED)return quick;
        LegalRecovery.Result result=LegalRecovery.find(current,board,4,350);
        if(result.incomplete())return new Update(Status.RECOVERY_LIMIT,current,List.of());
        if(result.ambiguous())return new Update(Status.AMBIGUOUS,current,List.of());
        return result.moves()==null?new Update(Status.UNRECOGNIZED,current,List.of()):commit(result.moves());
    }
    private Update commit(List<Move> moves){
        for(Move move:moves){current=current.applyUnchecked(move);repetitions.merge(current.repetitionKey(),1,Integer::sum);}history.addAll(moves);return new Update(Status.MOVED,current,moves);
    }
    public synchronized int repetitionCount(){return repetitions.getOrDefault(current.repetitionKey(),0);}
    public synchronized String automaticDraw(){
        if(current.insufficientMaterial())return "Недостаточно материала для мата";
        if(current.halfmove>=150)return "Правило 75 ходов";
        if(repetitionCount()>=5)return "Пятикратное повторение позиции";
        return null;
    }
    public synchronized String claimableDraw(){if(current.halfmove>=100)return "Можно заявить ничью по правилу 50 ходов";if(repetitionCount()>=3)return "Можно заявить ничью по повторению позиции";return null;}
}
