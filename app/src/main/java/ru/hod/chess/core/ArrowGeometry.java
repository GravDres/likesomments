package ru.hod.chess.core;

/** Screen coordinates shared by the renderer and the self-overlay exclusion mask. */
public final class ArrowGeometry {
    public record Point(float x, float y) {}

    public final BoardVision.Region region;
    public final Move move;
    public final Point start, end, neck, headLeft, headRight;
    public final float cell, stroke, sourceRadius;

    public ArrowGeometry(BoardVision.Region region, Move move, boolean whiteBottom) {
        if (region.size() <= 0 || move.from() < 0 || move.from() > 63
                || move.to() < 0 || move.to() > 63 || move.from() == move.to()) {
            throw new IllegalArgumentException("Некорректная стрелка");
        }
        this.region = region;
        this.move = move;
        cell = region.size() / 8f;
        stroke = cell * .105f;
        sourceRadius = cell * .22f;
        start = center(region, move.from(), whiteBottom);
        end = center(region, move.to(), whiteBottom);
        float dx = end.x() - start.x(), dy = end.y() - start.y();
        float length = (float) Math.hypot(dx, dy), ux = dx / length, uy = dy / length;
        neck = new Point(end.x() - ux * cell * .29f, end.y() - uy * cell * .29f);
        headLeft = new Point(neck.x() - uy * cell * .20f, neck.y() + ux * cell * .20f);
        headRight = new Point(neck.x() + uy * cell * .20f, neck.y() - ux * cell * .20f);
    }

    public static Point center(BoardVision.Region r, int square, boolean whiteBottom) {
        if (square < 0 || square >= 64) throw new IllegalArgumentException("Некорректная клетка");
        int display = whiteBottom ? square : 63 - square;
        return new Point(r.left() + (display % 8 + .5f) * r.size() / 8f,
                r.top() + (display / 8 + .5f) * r.size() / 8f);
    }

    /** Includes antialiasing and the optional promotion badge. */
    public boolean covers(float x, float y, float margin) {
        return segmentDistance(x, y, start, neck) <= stroke / 2 + margin
                || Math.hypot(x - start.x(), y - start.y()) <= sourceRadius + stroke / 2 + margin
                || inTriangle(x, y, end, headLeft, headRight)
                || segmentDistance(x, y, end, headLeft) <= margin
                || segmentDistance(x, y, end, headRight) <= margin
                || segmentDistance(x, y, headLeft, headRight) <= margin
                || move.promotion() != '\0'
                    && Math.hypot(x - end.x(), y - end.y()) <= cell * .23f + margin;
    }

    private static float segmentDistance(float x, float y, Point a, Point b) {
        float dx = b.x() - a.x(), dy = b.y() - a.y();
        float t = Math.max(0, Math.min(1,
                ((x - a.x()) * dx + (y - a.y()) * dy) / (dx * dx + dy * dy)));
        return (float) Math.hypot(x - a.x() - t * dx, y - a.y() - t * dy);
    }

    private static boolean inTriangle(float x, float y, Point a, Point b, Point c) {
        float p = cross(x, y, a, b), q = cross(x, y, b, c), r = cross(x, y, c, a);
        return !(p < 0 || q < 0 || r < 0) || !(p > 0 || q > 0 || r > 0);
    }

    private static float cross(float x, float y, Point a, Point b) {
        return (x - b.x()) * (a.y() - b.y()) - (a.x() - b.x()) * (y - b.y());
    }
}
