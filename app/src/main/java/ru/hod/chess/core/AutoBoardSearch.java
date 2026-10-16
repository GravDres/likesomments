package ru.hod.chess.core;

import java.util.*;

/** A two-frame calibration protocol. It never mutates the live game while probing. */
public final class AutoBoardSearch {
    public enum State { SEARCHING, UNCERTAIN_GRID, NEED_INITIAL, NEED_HISTORY, NEED_ORIENTATION, VERIFYING, READY }
    public record Calibration(BoardVision.Region region, BoardVision vision,
                              boolean whiteBottom, boolean reuseGame, GameTracker.Snapshot game) {}
    public record Result(State state, BoardLocator.Detection detection,
                         Calibration calibration, String message) {}
    private record Pending(Calibration calibration, char[] board, GameTracker.Snapshot source) {}
    private record Candidate(BoardVision model,BoardVision.Observation observation) {}
    private final BoardLocator locator = new BoardLocator();
    private final boolean refresh;
    private Pending pending;
    public AutoBoardSearch(){this(false);}
    /** Explicit refresh treats a visible starting board as a new game. */
    public AutoBoardSearch(boolean refresh){this.refresh=refresh;}

    public Result inspect(int[] pixels, int width, int height,
                          BoardVision previous, GameTracker game) {
        return inspect(pixels,width,height,previous,game,null);
    }

    public Result inspect(int[] pixels, int width, int height,
                          BoardVision previous, GameTracker game, BoardVision.Region hint) {
        GameTracker.Snapshot source=game==null?null:game.snapshot();
        BoardLocator.Detection d = locator.find(pixels, width, height,hint);
        if (d == null) {
            pending = null;
            return result(State.SEARCHING, null, null, "Ищу доску · открой Chess.com");
        }
        if (d.ambiguous()) {
            pending = null;
            return result(State.UNCERTAIN_GRID, d, null, "Сетка неоднозначна · удержи «Автопоиск» для проверки");
        }
        if (pending != null && Objects.equals(pending.source(),source) && BoardLocator.near(pending.calibration().region(), d.region())) {
            Calibration c = pending.calibration();
            BoardVision.Observation seen = c.vision().recognize(pixels, width, height, d.region());
            if (seen.uncertain() == 0 && Arrays.equals(seen.board(), pending.board())) {
                return result(State.READY, d, new Calibration(d.region(),c.vision(),c.whiteBottom(),c.reuseGame(),c.game()), "Доска найдена и проверена");
            }
        }
        pending = null;
        if(refresh&&d.automatic()&&d.initialLayout()&&d.orientationKnown())return trainInitial(pixels,width,height,d,source);
        // A running game can survive relocation/rotation without losing castling,
        // repetition history or en passant, as long as its piece templates still fit.
        if (previous != null) {
            List<Candidate> candidates=new ArrayList<>();
            for (boolean whiteBottom : new boolean[]{previous.whiteBottom(), !previous.whiteBottom()}) {
                BoardVision model = previous.withOrientation(whiteBottom);
                BoardVision.Observation seen = model.recognize(pixels, width, height, d.region());
                if(seen.uncertain()==0)candidates.add(new Candidate(model,seen));
            }
            // Explicit refresh prioritizes a short, uniquely recognized opening;
            // there is no reason to search the old game's tree first.
            if(refresh&&d.automatic()){
                List<Pending> openingMatches=new ArrayList<>();
                for(Candidate candidate:candidates){
                    BoardVision model=candidate.model();BoardVision.Observation seen=candidate.observation();
                    GameTracker opening=new GameTracker(Position.start());
                    GameTracker.Status status=opening.accept(seen.board()).status();
                    if(status==GameTracker.Status.SAME||status==GameTracker.Status.MOVED){
                        openingMatches.add(new Pending(new Calibration(d.region(),model,model.whiteBottom(),false,opening.snapshot()),seen.board(),source));
                    }
                }
                if(openingMatches.size()==1){
                    pending=openingMatches.get(0);
                    return result(State.VERIFYING,d,null,"Новая партия · проверяю первые ходы…");
                }
                if(openingMatches.size()>1)return result(State.NEED_ORIENTATION,d,null,"Ориентация новой партии неоднозначна · удержи «Автопоиск»");
            }
            List<Pending> matches = new ArrayList<>();
            if(source!=null)for(Candidate candidate:candidates){
                BoardVision model=candidate.model();BoardVision.Observation seen=candidate.observation();
                GameTracker trial=new GameTracker(source.position());
                GameTracker.Status status=trial.recover(seen.board()).status();
                if(status==GameTracker.Status.SAME||status==GameTracker.Status.MOVED){
                    matches.add(new Pending(new Calibration(d.region(),model,model.whiteBottom(),true,source.append(trial.history())),seen.board(),source));
                }
            }
            if (matches.size() == 1) {
                pending = matches.get(0);
                return result(State.VERIFYING, d, null, "Проверяю доску на следующем кадре…");
            }
            if (matches.size() > 1) {
                return result(State.NEED_ORIENTATION, d, null, "Ориентация неоднозначна · проверь сторону доски");
            }
        }
        if(!d.automatic()){
            return result(State.UNCERTAIN_GRID,d,null,"Нужна проверка сетки · удержи «Автопоиск»");
        }
        if (!d.initialLayout()) {
            if(game!=null&&(!game.history().isEmpty()||!game.initial().fen().equals(Position.START)))return result(State.NEED_HISTORY,d,null,"История не совпала · нажми «Позиция»");
            return result(State.NEED_INITIAL, d, null, "Доска найдена · для первого обучения нужна начальная расстановка");
        }
        // Reuse was tried first, so a legal return to the starting arrangement
        // keeps its repetition history. A different verified starting board is a
        // new game and may also need freshly trained templates for its theme.
        if (!d.orientationKnown()) {
            return result(State.NEED_ORIENTATION, d, null, "Доска найдена · цвета фигур неоднозначны, нужна проверка стороны");
        }
        return trainInitial(pixels,width,height,d,source);
    }

    private Result trainInitial(int[] pixels,int width,int height,BoardLocator.Detection d,GameTracker.Snapshot source){
        try {
            BoardVision model = new BoardVision(d.whiteBottom());
            model.calibrate(pixels, width, height, d.region());
            BoardVision.Observation seen = model.recognize(pixels, width, height, d.region());
            if (seen.uncertain() != 0 || !Position.start().matches(seen.board())) {
                return result(State.NEED_INITIAL, d, null, "Не удалось подтвердить все фигуры начальной позиции");
            }
            pending = new Pending(new Calibration(d.region(), model, d.whiteBottom(), false,new GameTracker(Position.start()).snapshot()), seen.board(),source);
            return result(State.VERIFYING, d, null, "Доска найдена · проверяю стабильный кадр…");
        } catch (IllegalArgumentException e) {
            return result(State.NEED_INITIAL, d, null, "Доска найдена · " + e.getMessage());
        }
    }

    private static Result result(State state, BoardLocator.Detection d, Calibration c, String message) {
        return new Result(state, d, c, message);
    }
}
