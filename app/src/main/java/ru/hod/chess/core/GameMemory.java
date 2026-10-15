package ru.hod.chess.core;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** One atomic snapshot of legal history plus the learned piece theme. No screenshots. */
public final class GameMemory {
    public record Restored(GameTracker game, BoardVision vision) {}
    public static byte[] encode(GameTracker game, BoardVision vision) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(0x484f4404);
            List<Move> moves = game.history();
            if (moves.size() > 4096) throw new IOException("Слишком длинная партия");
            out.writeInt(moves.size());
            for (Move move : moves) out.writeUTF(move.uci());
            vision.writeTo(out);
        }
        return bytes.toByteArray();
    }
    public static Restored decode(byte[] bytes) throws IOException {
        if (bytes.length > 160_000) throw new IOException("Слишком большой файл партии");
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
            if (in.readInt() != 0x484f4404) throw new IOException("Неизвестная версия партии");
            int count = in.readInt();
            if (count < 0 || count > 4096) throw new IOException("Некорректная история");
            List<Move> moves = new ArrayList<>();
            for (int i = 0; i < count; i++) moves.add(Move.parse(in.readUTF()));
            GameTracker game = GameTracker.replay(moves);
            BoardVision model = BoardVision.readFrom(in);
            if (in.read() != -1) throw new IOException("Лишние данные партии");
            return new Restored(game, model);
        } catch (IllegalArgumentException e) { throw new IOException("Повреждена история партии", e); }
    }
}
