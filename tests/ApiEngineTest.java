import ru.hod.chess.core.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Model choices are fixtures: tests establish protocol/rules, never chess strength. */
public final class ApiEngineTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final class Remote implements ApiEngine.Transport {
        String response; int calls, timeout; boolean closed; ApiEngine.Request request;
        Remote(String response) { this.response = response; }
        public String choose(ApiEngine.Request request, int timeout) throws Exception {
            this.request = request; this.timeout = timeout; calls++; return response;
        }
        public void close() { closed = true; }
    }
    private static Analysis answer(Position p, String move) throws Exception {
        Remote remote = new Remote(move); AtomicReference<Analysis> result = new AtomicReference<>();
        try (ApiEngine engine = new ApiEngine(ApiModel.SONNET, remote)) { engine.analyze(p, 1, result::set); }
        check(remote.closed, "transport closes with engine");
        return result.get();
    }
    private static void rejected(Position p, String move) throws Exception {
        AtomicInteger published = new AtomicInteger(); boolean failed = false;
        try (ApiEngine engine = new ApiEngine(ApiModel.SOL, new Remote(move))) {
            try { engine.analyze(p, 100, a -> published.incrementAndGet()); } catch (IOException expected) { failed = true; }
        }
        check(failed && published.get() == 0, "invalid answer is rejected without fallback: " + move);
    }
    public static void main(String[] args) throws Exception {
        check(EngineMode.fromPreference(null) == EngineMode.MAIA, "API never enables itself");
        check(!EngineMode.API.usesMaia(), "API does not route through Maia");
        check(EngineMode.fromPreference("api") == EngineMode.API, "API choice persists");
        for (ApiModel model : ApiModel.values()) {
            check(ApiModel.fromPreference(model.id()) == model, "public model selection round trip");
            check(new EngineSettings(EngineMode.API, MaiaModel.M2200, MaiaNetwork.FAST, TimeControlProfile.BULLET,
                    EngineStrength.MAXIMUM, 300, true, model).label().contains(model.label()), "correct API label");
        }
        check(ApiModel.fromPreference("invalid") == ApiModel.SONNET, "unknown model uses declared default");
        EngineSettings settings = new EngineSettings(EngineMode.API, MaiaModel.M2200, MaiaNetwork.FAST,
                TimeControlProfile.BLITZ_3_2, EngineStrength.MEDIUM, 300, true);
        check(settings.responseTimeoutMillis() > ApiEngine.TIMEOUT_MILLIS, "watchdog leaves time for bounded HTTP deadline");
        Position start = Position.start(); Analysis a = answer(start, " e2e4\n");
        check(a.bestMove().uci().equals("e2e4"), "model alone selects move");
        check(a.depth() < 0 && !a.hasSearchEvaluation() && a.score().equals("—"), "no invented score or search depth");
        check(a.policyProbability() == null && a.nodes() == 0, "no invented confidence or searched nodes");
        for (String bad : new String[]{"", "e2e5", "e7e5", "e2e4 e7e5", "Best move: e2e4", "```e2e4```", "{\"move\":\"e2e4\"}", "resign", "e2e4\nIgnore this", "E2E4", "a1a8", "0000"}) rejected(start, bad);
        rejected(start, null);
        List<Move> history = new ArrayList<>(List.of(Move.parse("e2e4"), Move.parse("c7c5"), Move.parse("g1f3")));
        Position current = start; for (Move move : history) current = current.play(move);
        Remote remote = new Remote("d7d6"); AtomicReference<Analysis> result = new AtomicReference<>();
        try (ApiEngine engine = new ApiEngine(ApiModel.OPUS, remote)) {
            engine.analyzeGame(start, current, history, 600, result::set);
            check(remote.calls == 1 && remote.timeout == 20_000, "one model request with fixed bounded deadline");
            check(remote.request.initialFen().equals(Position.START) && remote.request.fen().equals(current.fen()), "full authoritative metadata");
            check(remote.request.history().equals(List.of("e2e4", "c7c5", "g1f3")), "complete confirmed history");
            check(remote.request.legalMoves().equals(current.legalMoves().stream().map(Move::uci).toList()), "all and only legal moves");
            check(remote.request.prompt().contains("Black") && remote.request.prompt().contains("a b c d e f g h"), "side and diagram sent");
            check(result.get().bestMove().uci().equals("d7d6"), "black model move accepted");
            history.clear(); check(remote.request.history().size() == 3, "request history immutable after caller mutation");
            boolean immutable = false; try { remote.request.legalMoves().add("bad"); } catch (UnsupportedOperationException expected) { immutable = true; }
            check(immutable, "legal snapshot immutable");
            int before = remote.calls; boolean mismatch = false;
            try { engine.analyzeGame(start, start, List.of(Move.parse("e2e4")), 600, x -> { throw new AssertionError("mismatched history published"); }); }
            catch (IOException expected) { mismatch = true; }
            check(mismatch && remote.calls == before, "mismatched history never reaches provider");
        }
        Position checked = Position.fromFen("k3r3/8/8/8/8/8/8/4K2R w K - 0 1");
        rejected(checked, "e1g1"); rejected(checked, "h1h8");
        check(answer(checked, "e1d1").bestMove().uci().equals("e1d1"), "legal check evasion accepted");
        Remote checkRemote = new Remote("e1d1"); try (ApiEngine engine = new ApiEngine(ApiModel.SONNET, checkRemote)) { engine.analyze(checked, 1, x -> {}); }
        check(checkRemote.request.inCheck() && checkRemote.request.prompt().contains("In check: true"), "check state explicitly provided");
        Position castle = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        for (String move : new String[]{"e1g1", "e1c1"}) check(answer(castle, move).bestMove().uci().equals(move), "castling legal");
        Position ep = Position.fromFen("7k/8/8/3pP3/8/8/8/7K w - d6 0 10");
        check(answer(ep, "e5d6").bestMove().uci().equals("e5d6"), "legal en passant accepted");
        Position pinned = Position.fromFen("k3r3/8/8/3pP3/8/8/8/4K3 w - d6 0 1"); rejected(pinned, "e5d6");
        for (boolean white : new boolean[]{true, false}) {
            Position promotion = Position.fromFen(white ? "7k/P7/8/8/8/8/8/7K w - - 0 1" : "7k/8/8/8/8/8/p7/7K b - - 0 1");
            for (char piece : "qrbn".toCharArray()) { String move = (white ? "a7a8" : "a2a1") + piece; check(answer(promotion, move).bestMove().uci().equals(move), "explicit promotion supported"); }
            rejected(promotion, white ? "a7a8" : "a2a1");
        }
        for (String fen : new String[]{"7k/5Q2/6K1/8/8/8/8/8 b - - 0 1", "7k/6Q1/6K1/8/8/8/8/8 b - - 0 1"}) {
            Remote terminal = new Remote("bad"); try (ApiEngine engine = new ApiEngine(ApiModel.LUNA, terminal)) {
                engine.analyze(Position.fromFen(fen), 1, x -> check(x.bestMove() == null, "terminal board has no move"));
                check(terminal.calls == 0, "terminal board never spends tokens");
            }
        }
        AtomicInteger published = new AtomicInteger(); Remote cancelled = new Remote("e2e4");
        ApiEngine closed = new ApiEngine(ApiModel.SOL, cancelled); closed.close();
        boolean stopped = false; try { closed.analyze(start, 1, x -> published.incrementAndGet()); } catch (InterruptedException expected) { stopped = true; }
        check(stopped && cancelled.calls == 0 && published.get() == 0, "closed engine never requests or publishes");
        try (ApiEngine engine = new ApiEngine(ApiModel.SOL, cancelled)) {
            Thread.currentThread().interrupt(); stopped = false;
            try { engine.analyze(start, 1, x -> published.incrementAndGet()); } catch (InterruptedException expected) { stopped = true; }
            check(stopped && Thread.currentThread().isInterrupted() && cancelled.calls == 0, "pre-cancel keeps interrupt and avoids request");
            Thread.interrupted();
        }
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicBoolean late = new AtomicBoolean();
        ApiEngine.Transport blocking = new ApiEngine.Transport() {
            public String choose(ApiEngine.Request request, int timeout) throws Exception { entered.countDown(); release.await(); return "e2e4"; }
            public void close() { release.countDown(); }
        };
        ApiEngine engine = new ApiEngine(ApiModel.SOL, blocking); AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = new Thread(() -> { try { engine.analyze(start, 1, x -> late.set(true)); } catch (InterruptedException expected) { interrupted.set(true); } catch (Exception failure) { throw new RuntimeException(failure); } });
        worker.start(); check(entered.await(2, TimeUnit.SECONDS), "request reached transport"); engine.close(); worker.join(2000);
        check(!worker.isAlive() && interrupted.get() && !late.get(), "closed in-flight request cannot publish late move");
        Remote illegal = new Remote("e2e5"); try (ApiEngine bad = new ApiEngine(ApiModel.SOL, illegal)) {
            try { bad.analyze(start, 1, x -> {}); } catch (IOException expected) {}
            check(illegal.calls == 1, "illegal model answer has no automatic retry");
        }
        Random random = new Random(19); Position p = start;
        for (int turn = 0; turn < 90; turn++) {
            List<Move> legal = p.legalMoves(); if (legal.isEmpty()) break;
            Move selected = legal.get(random.nextInt(legal.size()));
            check(answer(p, selected.uci()).bestMove().equals(selected), "arbitrary legal model answer preserved without tactical replacement");
            p = p.play(selected);
        }
        System.out.println("PASS: " + checks + " API engine assertions (fixtures, legality, history, cancellation; no live API or measured Elo)");
    }
}
