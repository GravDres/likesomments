package ru.hod.chess.core;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Explicit hybrid: Maia proposes a move, full-strength Stockfish checks it.
 * This is a bounded tactical search, not an Elo or error-free-play guarantee. */
public final class TacticalMaiaEngine implements HistoryEngine {
    public static final int MAX_LOSS_CP=100;
    private final HistoryEngine policy;
    private final RootMoveEngine verifier;
    private final AtomicBoolean closed=new AtomicBoolean();

    public TacticalMaiaEngine(HistoryEngine policy,RootMoveEngine verifier){
        this.policy=java.util.Objects.requireNonNull(policy);this.verifier=java.util.Objects.requireNonNull(verifier);
    }
    public String name(){return "Maia-3 + Stockfish · проверка";}
    public void analyze(Position p,int millis,Consumer<Analysis> progress)throws Exception{analyzeGame(p,p,List.of(),millis,progress);}
    public synchronized void analyzeGame(Position initial,Position expected,List<Move> history,int millis,Consumer<Analysis> progress)throws Exception{
        List<Move> moves=List.copyOf(history);
        GameTracker.Snapshot snapshot=new GameTracker.Snapshot(initial,expected,moves);snapshot.restore();checkpoint();
        if(expected.legalMoves().isEmpty()){
            progress.accept(new Analysis(name(),0,0,0,expected.inCheck(expected.white)?(expected.white?-1:1):null,List.of()));return;
        }
        Analysis proposal=collect(expected,output->policy.analyzeGame(initial,expected,moves,millis,output));checkpoint();
        int budget=Math.max(300,Math.min(8000,millis)),firstBudget=budget/2;
        Analysis reference=collect(expected,output->verifier.analyzeGame(initial,expected,moves,firstBudget,output));checkpoint();
        Analysis selected;boolean replaced=false;
        if(proposal.bestMove().equals(reference.bestMove()))selected=reference;
        else{
            Analysis candidate=collect(expected,output->verifier.analyzeMoveGame(initial,expected,moves,proposal.bestMove(),budget-firstBudget,output));checkpoint();
            if(!proposal.bestMove().equals(candidate.bestMove()))throw new IOException("Проверка вернула другой ход");
            replaced=isBlunder(expected.white,reference,candidate);selected=replaced?reference:candidate;
        }
        checkpoint();Analysis.Line line=selected.lines().get(0);
        progress.accept(new Analysis(name()+(replaced?" · ход заменён":" · ход Maia подтверждён"),selected.depth(),selected.nodes(),selected.centipawns(),selected.mate(),List.of(new Analysis.Line(1,line.centipawns(),line.mate(),line.moves()))));
    }
    @FunctionalInterface private interface Search{void run(Consumer<Analysis> output)throws Exception;}
    private Analysis collect(Position p,Search search)throws Exception{
        final Analysis[] last={null};search.run(a->last[0]=a);checkpoint();Analysis a=last[0];
        if(a==null||a.bestMove()==null)throw new IOException("Нет подтверждённого ответа для проверки");
        Position cursor=p;for(Move move:a.lines().get(0).moves()){if(!cursor.legalMoves().contains(move))throw new IOException("Недопустимый вариант при проверке");cursor=cursor.play(move);}
        return a;
    }
    private static boolean isBlunder(boolean white,Analysis reference,Analysis candidate){
        int direction=white?1:-1;
        int bestMate=reference.mate()==null?0:Integer.signum(reference.mate())*direction;
        int candidateMate=candidate.mate()==null?0:Integer.signum(candidate.mate())*direction;
        // Keep sound mating sacrifices. Reject a new forced loss or lost forced win.
        if(bestMate!=candidateMate)return bestMate>candidateMate;
        if(bestMate!=0)return false;
        return ((long)reference.centipawns()-candidate.centipawns())*direction>=MAX_LOSS_CP;
    }
    private void checkpoint()throws Exception{if(Thread.currentThread().isInterrupted())throw new InterruptedException();if(closed.get())throw new IOException("Тактическая проверка остановлена");}
    public synchronized void newGame()throws Exception{checkpoint();policy.newGame();checkpoint();verifier.newGame();}
    public void close(){if(closed.compareAndSet(false,true)){try{policy.close();}finally{verifier.close();}}}
}
