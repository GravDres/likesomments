package ru.hod.chess.core;

/** Motion screening only: overlaid screenshots are never sent to the recognizer. */
public final class BoardChangeDetector {
    private BoardChangeDetector() {}

    public static boolean changed(int[] clean, int[] visible, int width, int height,
                                  ArrowGeometry arrow) {
        if (clean.length != width * height || visible.length != clean.length
                || !arrow.region.inside(width, height)) return true;
        BoardVision.Region r = arrow.region;
        int step = Math.max(2, r.size() / 256), changes = 0;
        for (int y = r.top(); y < r.top() + r.size(); y += step) {
            for (int x = r.left(); x < r.left() + r.size(); x += step) {
                if (arrow.covers(x, y, 4f)) continue;
                int a = clean[y * width + x], b = visible[y * width + x];
                int delta = Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
                        + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                        + Math.abs((a & 255) - (b & 255));
                if (delta > 45 && ++changes >= 12) return true;
            }
        }
        return false;
    }
}
