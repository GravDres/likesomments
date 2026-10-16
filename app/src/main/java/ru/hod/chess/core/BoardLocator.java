package ru.hod.chess.core;

import java.util.*;

/** Finds an unobscured, axis-aligned 8x8 board without a fixed theme or screen size. */
public final class BoardLocator {
    private static final float[] EDGE_INSETS={.12f,.88f};
    public record Detection(BoardVision.Region region, float gridScore, boolean whiteBottom,
                            float orientationScore, boolean initialLayout, boolean ambiguous) {
        public boolean automatic() { return gridScore >= .95f && !ambiguous; }
        public boolean orientationKnown() { return initialLayout && orientationScore >= .6f; }
    }
    private record Run(int start, int end, int color) { int length() { return end - start; } }
    private static final class Span {
        final int start, size;
        int votes = 1;
        Span(int start, int size) { this.start = start; this.size = size; }
    }
    private record Scored(BoardVision.Region region, float score) {}

    public Detection find(int[] pixels, int width, int height) {
        return find(pixels,width,height,null);
    }

    /** A remembered rectangle is a candidate only; it must still pass the current grid. */
    public Detection find(int[] pixels, int width, int height, BoardVision.Region hint) {
        if (width < 160 || height < 160 || pixels.length != width * height) return null;
        int scale = Math.max(1, Math.min(width, height) / 600);
        int w = width / scale, h = height / scale;
        int[] small = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            small[y * w + x] = pixels[(y * scale + scale / 2) * width + x * scale + scale / 2];
        }
        List<Span> horizontal = new ArrayList<>();
        int minimumCell = Math.max(4, 20 / scale);
        for (int y = 0; y < h; y += Math.max(3, h / 110)) {
            collect(runs(small, w, h, y, false), minimumCell, -1,
                    Math.min(w, h), w, horizontal);
        }
        horizontal.sort(Comparator.<Span>comparingInt(s -> s.votes).reversed()
                .thenComparing(Comparator.comparingInt((Span s) -> s.size).reversed()));
        List<Scored> candidates = new ArrayList<>();
        if(hint!=null&&hint.inside(width,height)){
            // A previous detector can be off by one whole rank/file. Recheck
            // neighboring interpretations even when stripes are interrupted.
            for(int dy:new int[]{0,-1,1})for(int dx:new int[]{0,-1,1}){
                BoardVision.Region nearby=new BoardVision.Region(hint.left()+Math.round(dx*hint.size()/8f),hint.top()+Math.round(dy*hint.size()/8f),hint.size());
                if(!nearby.inside(width,height))continue;
                float score=gridScore(pixels,width,nearby);
                if(score>=.90f)candidates.add(new Scored(nearby,score));
            }
        }
        for (Span xs : horizontal.subList(0, Math.min(48, horizontal.size()))) {
            List<Span> vertical = new ArrayList<>();
            for (int column : new int[]{1, 2, 4, 6}) for (float inset : new float[]{.08f, .92f}) {
                int x = Math.round(xs.start + (column + inset) * xs.size / 8f);
                if (x >= w) continue;
                collect(runs(small, w, h, x, true), minimumCell,
                        xs.size / 8f, Math.min(w, h), h, vertical);
            }
            for (Span ys : vertical) {
                if (Math.abs(xs.size - ys.size) > Math.max(4, xs.size * .035f)) continue;
                BoardVision.Region region = new BoardVision.Region(xs.start * scale,
                        ys.start * scale, xs.size * scale);
                if (!region.inside(width, height)) continue;
                float score = gridScore(pixels, width, region);
                if (score < .90f) continue;
                boolean duplicate = false;
                for (int i = 0; i < candidates.size(); i++) {
                    Scored old = candidates.get(i);
                    if (near(old.region(), region)) {
                        if (score > old.score()) candidates.set(i, new Scored(region, score));
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) candidates.add(new Scored(region, score));
            }
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.<Scored>comparingDouble(Scored::score).reversed()
                .thenComparing(Comparator.comparingInt((Scored s) -> s.region().size()).reversed()));
        Scored best = candidates.get(0);
        boolean ambiguous = candidates.stream().skip(1).anyMatch(s ->
                s.score() >= best.score() - .025f && !sameBoard(best.region(), s.region()));
        BoardVision.Region r = refine(pixels,width,height,best.region());
        boolean initial = true;
        float topInk = 0, bottomInk = 0;
        for (int square = 0; square < 64; square++) {
            BoardVision.Feature feature = BoardVision.extract(pixels, width, r, square);
            int row = square / 8;
            if (row < 2 || row > 5) {
                if (feature.occupancy() < .065f || feature.occupancy() > .85f) initial = false;
            } else if (feature.occupancy() > .06f) initial = false;
            if (row == 1) topInk += feature.ink();
            if (row == 6) bottomInk += feature.ink();
        }
        float difference = (bottomInk - topInk) / 8f;
        return new Detection(r, gridScore(pixels,width,r), difference >= 0,
                initial ? Math.min(1, Math.abs(difference) / .28f) : 0, initial, ambiguous);
    }

    private static BoardVision.Region refine(int[] pixels,int width,int height,BoardVision.Region seed){
        float minimum=gridScore(pixels,width,seed)-.016f;
        BoardVision.Region coarse=refinePass(pixels,width,height,seed,Math.max(6,Math.min(12,seed.size()/64)),2,minimum);
        return refinePass(pixels,width,height,coarse,2,1,minimum);
    }
    private static BoardVision.Region refinePass(int[] pixels,int width,int height,BoardVision.Region seed,int radius,int step,float minimum){
        BoardVision.Region best=seed;float score=edgeStrength(pixels,width,seed);
        for(int size=seed.size()-radius;size<=seed.size()+radius;size+=step)
            for(int left=seed.left()-radius;left<=seed.left()+radius;left+=step)
                for(int top=seed.top()-radius;top<=seed.top()+radius;top+=step){
                    BoardVision.Region r=new BoardVision.Region(left,top,size);if(!r.inside(width,height))continue;
                    float value=edgeStrength(pixels,width,r);
                    if(value>score+.001f&&gridScore(pixels,width,r)>=minimum){score=value;best=r;}
                }
        return best;
    }
    private static float edgeStrength(int[] pixels,int width,BoardVision.Region r){
        float sum=0,cell=r.size()/8f;
        for(int edge=1;edge<8;edge++)for(int along=0;along<8;along++)for(float inset:EDGE_INSETS){
            int x=r.left()+Math.round(edge*cell),y=r.top()+Math.round((along+inset)*cell);
            sum+=Math.min(30_000,distance(pixels[y*width+x-1],pixels[y*width+x+1]));
            x=r.left()+Math.round((along+inset)*cell);y=r.top()+Math.round(edge*cell);
            sum+=Math.min(30_000,distance(pixels[(y-1)*width+x],pixels[(y+1)*width+x]));
        }
        return sum/(224f*30_000f);
    }

    public static boolean near(BoardVision.Region a, BoardVision.Region b) {
        int tolerance = Math.max(4, a.size() / 100);
        return Math.abs(a.left() - b.left()) <= tolerance
                && Math.abs(a.top() - b.top()) <= tolerance
                && Math.abs(a.size() - b.size()) <= tolerance;
    }

    private static boolean sameBoard(BoardVision.Region a, BoardVision.Region b) {
        return Math.abs(a.left() - b.left()) < a.size() / 4
                && Math.abs(a.top() - b.top()) < a.size() / 4
                && Math.abs(a.size() - b.size()) < a.size() / 4;
    }

    private static List<Run> runs(int[] image, int w, int h, int line, boolean vertical) {
        int length = vertical ? h : w;
        List<Run> result = new ArrayList<>();
        int start = 0, reference = image[vertical ? line : line * w];
        for (int i = 1; i < length; i++) {
            int color = image[vertical ? i * w + line : line * w + i];
            if (distance(color, reference) > 35 * 35) {
                // A one-pixel antialiased/JPEG border is not another square.
                // Commit a new run only when its color survives three samples.
                boolean stable = i + 2 < length;
                for (int j = 1; stable && j <= 2; j++) {
                    int next = image[vertical ? (i + j) * w + line : line * w + i + j];
                    if (distance(next, color) > 35 * 35) stable = false;
                }
                if (!stable) continue;
                result.add(new Run(start, i, reference));
                start = i;
                reference = color;
            }
        }
        result.add(new Run(start, length, reference));
        return result;
    }

    private static void collect(List<Run> runs, int minimumCell, float expectedCell,
                                int maxSize, int axisLength, List<Span> output) {
        for (int count : new int[]{8, 6, 4}) {
            for (int i = 0; i + count <= runs.size(); i++) {
                float cell = (runs.get(i + count - 1).end() - runs.get(i).start()) / (float) count;
                if (cell < minimumCell || cell * 8 > maxSize + 2
                        || expectedCell > 0 && Math.abs(cell - expectedCell) > Math.max(2, expectedCell * .06f)) continue;
                Run a = runs.get(i), b = runs.get(i + 1);
                if (distance(a.color(), b.color()) < 40 * 40) continue;
                boolean regular = true;
                for (int j = 0; j < count; j++) {
                    Run r = runs.get(i + j);
                    if (Math.abs(r.length() - cell) > Math.max(2, cell * .10f)
                            || distance(r.color(), (j & 1) == 0 ? a.color() : b.color()) > 35 * 35) {
                        regular = false;
                        break;
                    }
                }
                if (!regular) continue;
                int size = Math.round(cell * 8);
                // Highlights, coordinate labels and pieces can interrupt a line.
                // Try every possible offset of the remaining stripes; the full
                // 64-cell grid, not this short segment, validates the rectangle.
                for(int offset=0;offset<=8-count;offset++){
                    int start = Math.round(a.start() - offset*cell);
                    if (start < 0 || start + size > axisLength) continue;
                    Span match = null;
                    for (Span old : output) if (Math.abs(old.start - start) <= 2
                            && Math.abs(old.size - size) <= 4) { match = old; break; }
                    if (match != null) match.votes+=count-2;
                    else if (output.size() < 600){Span span=new Span(start,size);span.votes=count-2;output.add(span);}
                }
            }
        }
    }

    private static float gridScore(int[] pixels, int width, BoardVision.Region r) {
        int[][] samples = new int[2][128];
        int[] n = new int[2];
        float cell = r.size() / 8f;
        for (int row = 0; row < 8; row++) for (int col = 0; col < 8; col++) {
            int parity = (row + col) & 1;
            for (float dy : new float[]{.09f, .91f}) for (float dx : new float[]{.09f, .91f}) {
                int x = r.left() + Math.round((col + dx) * cell);
                int y = r.top() + Math.round((row + dy) * cell);
                samples[parity][n[parity]++] = pixels[y * width + x];
            }
        }
        int[] colors = {medianColor(samples[0]), medianColor(samples[1])};
        if (distance(colors[0], colors[1]) < 35 * 35) return 0;
        int normal = 0, highlighted = 0;
        int[] ranks=new int[8],files=new int[8];
        for (int parity = 0; parity < 2; parity++) for (int i = 0; i < 128; i += 4) {
            int matches = 0;
            int[] corners = Arrays.copyOfRange(samples[parity], i, i + 4);
            for (int color : corners) if (distance(color, colors[parity]) <= 42 * 42) matches++;
            // Coordinates/shadows may cover one corner. A highlighted last-move
            // square is uniformly recolored; it does not invalidate the 8x8 grid.
            if (matches >= 3){normal++;int tile=i/4,row=tile/4,col=(tile%4)*2+((row+parity)&1);ranks[row]++;files[col]++;}
            else {
                int median = medianColor(corners), uniform = 0;
                for (int color : corners) if (distance(color, median) <= 42 * 42) uniform++;
                if (uniform >= 3) highlighted++;
            }
        }
        // A uniform chat bubble can match half a rank and look like four
        // highlighted squares in the other half. Require checkerboard evidence
        // in every rank/file, rather than allowing one complete edge to vanish.
        for(int i=0;i<8;i++)if(ranks[i]<5||files[i]<5)return 0;
        return (normal + (highlighted <= 4 ? highlighted : 0)) / 64f;
    }

    private static int medianColor(int[] colors) {
        int[] channel = new int[colors.length];
        int result = 0xff000000;
        for (int shift : new int[]{16, 8, 0}) {
            for (int i = 0; i < colors.length; i++) channel[i] = colors[i] >> shift & 255;
            Arrays.sort(channel);
            result |= channel[channel.length / 2] << shift;
        }
        return result;
    }

    private static int distance(int a, int b) {
        int r = (a >> 16 & 255) - (b >> 16 & 255);
        int g = (a >> 8 & 255) - (b >> 8 & 255);
        int blue = (a & 255) - (b & 255);
        return r * r + g * g + blue * blue;
    }
}
