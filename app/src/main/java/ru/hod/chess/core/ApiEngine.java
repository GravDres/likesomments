package ru.hod.chess.core;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

/** The remote model alone chooses a move. Local code verifies rules, not move quality. */
public final class ApiEngine implements HistoryEngine {
    public static final int TIMEOUT_MILLIS = 20_000;
    public static final String SYSTEM_PROMPT = "You are playing standard chess. Choose the strongest move "
            + "for the side to move from the supplied legal UCI moves. Use the current FEN as authoritative; "
            + "the history is included for context and repetitions. Consider check evasions, immediate mates, "
            + "opponent threats, hanging pieces and king safety. Exact local rule annotations may list legal "
            + "opponent captures after candidate moves: examine those threats, but a sacrifice can be sound. "
            + "Missing annotations mean unknown, never safe. Prefer a coherent human-understandable plan: "
            + "development, active pieces and king safety when tactically justified. Maintain the plan from "
            + "the confirmed history; do not reverse moves without a concrete reason. Return exactly one lowercase UCI move, "
            + "for example e2e4 or a7a8q. No explanation, markdown, JSON, tools or extra text.";

    public record Request(String initialFen, String fen, List<String> history, List<String> legalMoves,
                          String board, boolean inCheck, String facts) {
        public Request(String initialFen,String fen,List<String> history,List<String> legalMoves,String board,boolean inCheck){this(initialFen,fen,history,legalMoves,board,inCheck,"");}
        public Request { history = List.copyOf(history); legalMoves = List.copyOf(legalMoves); }
        public String prompt() {
            return "Current FEN: " + fen + "\nSide to move: " + (fen.split(" ")[1].equals("w") ? "White" : "Black")
                    + "\nIn check: " + inCheck + "\nBoard (uppercase White, lowercase Black, dot empty):\n" + board
                    + "\nInitial FEN: " + initialFen + "\nConfirmed UCI history from initial FEN: " + String.join(" ", history)
                    + "\n" + facts + "\nLegal UCI moves: " + String.join(" ", legalMoves) + "\nReturn one legal UCI move only.";
        }
    }

    public interface Transport extends AutoCloseable {
        String choose(Request request, int timeoutMillis) throws Exception;
        void close();
    }

    private final ApiModel model;
    private final Transport transport;
    private volatile boolean closed;
    private record Key(String initial, String current, List<Move> history) {}
    private final LinkedHashMap<Key,Analysis> cache = new LinkedHashMap<>(16,.75f,true);
    private long cacheVersion;

    public ApiEngine(ApiModel model, Transport transport) {
        this.model = Objects.requireNonNull(model); this.transport = Objects.requireNonNull(transport);
    }
    public String name() { return "API · " + model.label(); }
    public void analyze(Position p, int millis, Consumer<Analysis> progress) throws Exception {
        analyzeGame(p, p, List.of(), millis, progress);
    }
    public void analyzeGame(Position initial, Position expected, List<Move> history, int millis,
                            Consumer<Analysis> progress) throws Exception {
        requireActive();
        List<Move> snapshot = List.copyOf(history);
        if (snapshot.size() > 2048) throw new IOException("Слишком длинная история для API-запроса");
        Position replay = initial;
        for (Move move : snapshot) { requireActive(); replay = replay.play(move); }
        if (!replay.fen().equals(expected.fen())) throw new IOException("История API не совпала с текущей позицией");
        List<Move> legal = expected.legalMoves();
        if (legal.isEmpty()) { // A terminal board never spends API tokens.
            progress.accept(new Analysis(name(), -1, 0, 0, null, List.of())); return;
        }
        Key key = new Key(initial.fen(), expected.fen(), snapshot); long version;
        Analysis cached;
        synchronized(cache){version=cacheVersion;cached=cache.get(key);}
        if(cached!=null){requireActive();progress.accept(new Analysis(name()+" · сохранённый ход",-1,0,0,null,cached.lines()));return;}
        StringBuilder board = new StringBuilder();
        for (int row = 0; row < 8; row++) {
            board.append(8 - row).append(' ');
            for (int col = 0; col < 8; col++) board.append(expected.at(row * 8 + col)).append(' ');
            board.append('\n');
        }
        board.append("  a b c d e f g h");
        Request request = new Request(initial.fen(), expected.fen(), snapshot.stream().map(Move::uci).toList(),
                legal.stream().map(Move::uci).toList(), board.toString(), expected.inCheck(expected.white),ApiChessFacts.inspect(expected).text());
        requireActive();
        String content = transport.choose(request, TIMEOUT_MILLIS);
        requireActive();
        Move chosen;
        try { chosen = Move.parse(content == null ? null : content.trim()); }
        catch (IllegalArgumentException invalid) { throw new IOException("API вернул неверный формат хода · нажми «Повторить API»"); }
        if (!legal.contains(chosen)) throw new IOException("API предложил запрещённый ход · нажми «Повторить API»");
        requireActive();
        // No fabricated centipawn evaluation, search depth, confidence or win probability.
        Analysis answer = new Analysis(name(), -1, 0, 0, null,List.of(new Analysis.Line(1, 0, null, List.of(chosen))));
        synchronized(cache){if(version==cacheVersion&&!closed){cache.put(key,answer);while(cache.size()>16)cache.remove(cache.keySet().iterator().next());}}
        requireActive();progress.accept(answer);
    }
    private void requireActive() throws InterruptedException {
        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException("API-запрос отменён");
    }
    public void clearCache(){synchronized(cache){cacheVersion++;cache.clear();}}
    public void newGame(){clearCache();}
    public void close() { closed = true; clearCache(); transport.close(); }
}
