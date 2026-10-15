package ru.hod.chess.core;

import java.util.*;

/** A two-frame calibration protocol. It never mutates the live game while probing. */
public final class AutoBoardSearch {
    public enum State { SEARCHING, UNCERTAIN_GRID, NEED_INITIAL, NEED_NEW_GAME, NEED_ORIENTATION, VERIFYING, READY }
    public record Calibration(BoardVision.Region region, BoardVision vision,
                              boolean whiteBottom, boolean reuseGame) {}
    public record Result(State state, BoardLocator.Detection detection,
                         Calibration calibration, String message) {}
    private record Pending(Calibration calibration, char[] board) {}
    private final BoardLocator locator = new BoardLocator();
    private Pending pending;

    public Result inspect(int[] pixels, int width, int height,
                          BoardVision previous, GameTracker game) {
        BoardLocator.Detection d = locator.find(pixels, width, height);
        if (d == null) {
            pending = null;
            return result(State.SEARCHING, null, null, "Ищу доску · открой Chess.com");
        }
        if (!d.automatic()) {
            pending = null;
            return result(State.UNCERTAIN_GRID, d, null, "Найдена возможная доска · нужна проверка сетки");
        }
        if (pending != null && BoardLocator.near(pending.calibration().region(), d.region())) {
            Calibration c = pending.calibration();
            BoardVision.Observation seen = c.vision().recognize(pixels, width, height, c.region());
            if (seen.uncertain() == 0 && Arrays.equals(seen.board(), pending.board())) {
                return result(State.READY, d, c, "Доска найдена и проверена");
            }
        }
        pending = null;
        // A running game can survive relocation/rotation without losing castling,
        // repetition history or en passant, as long as its piece templates still fit.
        if (previous != null && game != null) {
            List<Pending> matches = new ArrayList<>();
            for (boolean whiteBottom : new boolean[]{true, false}) {
                BoardVision model = previous.withOrientation(whiteBottom);
                BoardVision.Observation seen = model.recognize(pixels, width, height, d.region());
                if (seen.uncertain() != 0) continue;
                GameTracker trial = new GameTracker(game.position());
                GameTracker.Status status = trial.accept(seen.board()).status();
                if (status == GameTracker.Status.SAME || status == GameTracker.Status.MOVED) {
                    matches.add(new Pending(new Calibration(d.region(), model, whiteBottom, true), seen.board()));
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
        if (!d.initialLayout()) {
            return result(State.NEED_INITIAL, d, null, "Доска найдена · для первого обучения нужна начальная расстановка");
        }
        if(game!=null&&!game.history().isEmpty()){
            return result(State.NEED_NEW_GAME,d,null,"Начальная расстановка другой партии · нажми «Новая партия»");
        }
        if (!d.orientationKnown()) {
            return result(State.NEED_ORIENTATION, d, null, "Доска найдена · цвета фигур неоднозначны, нужна проверка стороны");
        }
        try {
            BoardVision model = new BoardVision(d.whiteBottom());
            model.calibrate(pixels, width, height, d.region());
            BoardVision.Observation seen = model.recognize(pixels, width, height, d.region());
            if (seen.uncertain() != 0 || !Position.start().matches(seen.board())) {
                return result(State.NEED_INITIAL, d, null, "Не удалось подтвердить все фигуры начальной позиции");
            }
            pending = new Pending(new Calibration(d.region(), model, d.whiteBottom(), false), seen.board());
            return result(State.VERIFYING, d, null, "Доска найдена · проверяю стабильный кадр…");
        } catch (IllegalArgumentException e) {
            return result(State.NEED_INITIAL, d, null, "Доска найдена · " + e.getMessage());
        }
    }

    private static Result result(State state, BoardLocator.Detection d, Calibration c, String message) {
        return new Result(state, d, c, message);
    }
}
