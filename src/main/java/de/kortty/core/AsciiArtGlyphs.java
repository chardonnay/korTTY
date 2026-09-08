package de.kortty.core;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Picks the printable character that best resembles one {@value AsciiArtRasterizer#CELL_WIDTH_PX}×{@value
 * AsciiArtRasterizer#CELL_HEIGHT_PX} pixel cell of ink.
 *
 * <p>The 35 glyph templates are small vector drawings (lines, dots, circles) rendered through the
 * same {@link AsciiArtRasterizer} code path as the picture itself, so no font is involved: the
 * output is identical on every platform, and a template's ink is measured exactly like the cell it
 * will be compared to. Each cell is reduced to a descriptor — mean ink, a 4×8 blurred
 * sub-sampling, the dominant orientation with its coherence, a flatness measure and a step
 * measure — and matched in four steps: near-empty cells are blank, flat cells take a rung of the
 * tone ramp by mean ink, the edge of a filled area takes the line glyph of its direction, and every
 * other textured cell takes the template with the smallest blurred difference, nudged by
 * orientation agreement.</p>
 */
final class AsciiArtGlyphs {

    /** The glyph set, in match order: ties go to the lower index, so simpler glyphs come first. */
    static final String CHARACTERS = " .`'\",:;-_~=+|/\\<>^vVxX*oO()LTMW%#@";
    /** Ten rungs from blank to solid for cells without structure. */
    static final String TONAL_RAMP = " .:-=+*#%@";
    /** Mean ink at which the next rung of {@link #TONAL_RAMP} starts. */
    static final double[] TONAL_THRESHOLDS = {0.03, 0.10, 0.18, 0.27, 0.37, 0.48, 0.60, 0.72, 0.86};
    /** Cells with less mean ink than this are blank: antialiasing dust must not become dots. */
    static final double BLANK_MEAN = 0.03;
    /** Below this standard deviation of the blurred samples a cell is a tone, not a shape. */
    static final double FLAT_STDDEV = 0.08;
    /** Weight of the orientation disagreement term against the blurred difference. */
    static final double ORIENTATION_WEIGHT = 0.05;
    /** Small cost for any non-blank glyph, so faint texture falls back to a space. */
    static final double NON_BLANK_BIAS = 0.004;
    /**
     * A cell is the edge of a filled area when one straight direction dominates this strongly, the
     * ink changes this monotonically across it, and the cell is neither nearly white nor nearly
     * solid. Such cells get the line glyph of their direction: no thin template resembles a
     * half-dark cell, so the blurred difference alone would pick a wedge like {@code L} or
     * {@code ,} and a black roof would lose its {@code /} and {@code \}.
     */
    static final double EDGE_MIN_COHERENCE = 0.7;
    static final double EDGE_MIN_STEP = 0.6;
    static final double EDGE_MIN_MEAN = 0.25;
    static final double EDGE_MAX_MEAN = 0.75;
    /** Orientation bands (degrees, y up) that read as {@code /}, {@code |} and {@code \}. */
    private static final double SLASH_MIN_DEGREES = 45;
    private static final double BAR_MIN_DEGREES = 80;
    private static final double BAR_MAX_DEGREES = 100;
    private static final double BACKSLASH_MAX_DEGREES = 135;
    static final int SUB_COLUMNS = 4;
    static final int SUB_ROWS = 8;
    static final int SUB_SAMPLES = SUB_COLUMNS * SUB_ROWS;
    /** Template stroke width in px; 2 px is what a thin picture line is clamped to as well. */
    static final float GLYPH_STROKE_PX = 2.0f;
    static final double DOT_RADIUS_PX = 1.2;

    private static final int W = AsciiArtRasterizer.CELL_WIDTH_PX;
    private static final int H = AsciiArtRasterizer.CELL_HEIGHT_PX;

    private static volatile List<Glyph> glyphs;

    private AsciiArtGlyphs() {
    }

    // ---- Descriptors ----

    /**
     * The measurements one cell is matched on. {@code orientation} is the dominant line direction
     * in radians, measured counter-clockwise from the x axis with y pointing up (so {@code /} is
     * near 70°), valid in [0, π); {@code coherence} in [0, 1] says how strongly that direction
     * dominates; {@code flatness} is the standard deviation of the blurred samples; {@code step}
     * in [0, 1] is how monotonically the ink rises across the orientation — near 1 for the edge
     * of a filled area, near 0 for a line with white on both sides.
     */
    record Descriptor(double mean, float[] blurred, double orientation, double coherence, double flatness,
            double step) {
    }

    record Glyph(char character, Descriptor descriptor) {
    }

    /** Describes the cell whose top-left pixel is ({@code x0}, {@code y0}). */
    static Descriptor describe(AsciiArtRasterizer.InkMap ink, int x0, int y0) {
        double sum = 0;
        float[] blocks = new float[SUB_SAMPLES];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double v = ink.inkAt(x0 + x, y0 + y);
                sum += v;
                blocks[(y / 2) * SUB_COLUMNS + x / 2] += (float) (v / 4.0);
            }
        }
        double mean = sum / (W * H);
        float[] blurred = blur(blocks);
        double flatness = standardDeviation(blurred);

        // Structure tensor from Sobel gradients; y is flipped so orientation reads counter-clockwise.
        double jxx = 0;
        double jyy = 0;
        double jxy = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int px = x0 + x;
                int py = y0 + y;
                double gx = (ink.inkAt(px + 1, py - 1) + 2 * ink.inkAt(px + 1, py) + ink.inkAt(px + 1, py + 1))
                    - (ink.inkAt(px - 1, py - 1) + 2 * ink.inkAt(px - 1, py) + ink.inkAt(px - 1, py + 1));
                double gy = (ink.inkAt(px - 1, py - 1) + 2 * ink.inkAt(px, py - 1) + ink.inkAt(px + 1, py - 1))
                    - (ink.inkAt(px - 1, py + 1) + 2 * ink.inkAt(px, py + 1) + ink.inkAt(px + 1, py + 1));
                jxx += gx * gx;
                jyy += gy * gy;
                jxy += gx * gy;
            }
        }
        double trace = jxx + jyy;
        double coherence = 0;
        double orientation = 0;
        if (trace > 1e-9) {
            coherence = Math.sqrt((jxx - jyy) * (jxx - jyy) + 4 * jxy * jxy) / trace;
            // The gradient's dominant direction is perpendicular to the line it bounds.
            double gradientAngle = 0.5 * Math.atan2(2 * jxy, jxx - jyy);
            orientation = gradientAngle + Math.PI / 2;
            orientation = ((orientation % Math.PI) + Math.PI) % Math.PI;
        }
        return new Descriptor(mean, blurred, orientation, coherence, flatness, step(blurred, orientation));
    }

    /**
     * Absolute correlation between each blurred sample's position across {@code orientation} and
     * its ink: a step edge correlates strongly, a symmetric line hardly at all.
     */
    private static double step(float[] blurred, double orientation) {
        double nx = -Math.sin(orientation);
        double ny = Math.cos(orientation);
        double[] position = new double[SUB_SAMPLES];
        double meanPosition = 0;
        double meanInk = 0;
        for (int i = 0; i < SUB_SAMPLES; i++) {
            int c = i % SUB_COLUMNS;
            int r = i / SUB_COLUMNS;
            position[i] = (2 * c + 1) * nx - (2 * r + 1) * ny;
            meanPosition += position[i];
            meanInk += blurred[i];
        }
        meanPosition /= SUB_SAMPLES;
        meanInk /= SUB_SAMPLES;
        double covariance = 0;
        double positionVariance = 0;
        double inkVariance = 0;
        for (int i = 0; i < SUB_SAMPLES; i++) {
            double dp = position[i] - meanPosition;
            double di = blurred[i] - meanInk;
            covariance += dp * di;
            positionVariance += dp * dp;
            inkVariance += di * di;
        }
        double denominator = Math.sqrt(positionVariance * inkVariance);
        return denominator < 1e-9 ? 0 : Math.abs(covariance / denominator);
    }

    /** Separable [1,2,1]/4 blur over the 4×8 block means with clamped edges. */
    private static float[] blur(float[] blocks) {
        float[] horizontal = new float[SUB_SAMPLES];
        for (int r = 0; r < SUB_ROWS; r++) {
            for (int c = 0; c < SUB_COLUMNS; c++) {
                float left = blocks[r * SUB_COLUMNS + Math.max(0, c - 1)];
                float mid = blocks[r * SUB_COLUMNS + c];
                float right = blocks[r * SUB_COLUMNS + Math.min(SUB_COLUMNS - 1, c + 1)];
                horizontal[r * SUB_COLUMNS + c] = (left + 2 * mid + right) / 4f;
            }
        }
        float[] result = new float[SUB_SAMPLES];
        for (int r = 0; r < SUB_ROWS; r++) {
            for (int c = 0; c < SUB_COLUMNS; c++) {
                float up = horizontal[Math.max(0, r - 1) * SUB_COLUMNS + c];
                float mid = horizontal[r * SUB_COLUMNS + c];
                float down = horizontal[Math.min(SUB_ROWS - 1, r + 1) * SUB_COLUMNS + c];
                result[r * SUB_COLUMNS + c] = (up + 2 * mid + down) / 4f;
            }
        }
        return result;
    }

    private static double standardDeviation(float[] values) {
        double mean = 0;
        for (float v : values) {
            mean += v;
        }
        mean /= values.length;
        double variance = 0;
        for (float v : values) {
            variance += (v - mean) * (v - mean);
        }
        return Math.sqrt(variance / values.length);
    }

    // ---- Glyph templates ----

    /** The 35 templates, built once through the rasteriser and cached. */
    static List<Glyph> glyphs() {
        List<Glyph> cached = glyphs;
        if (cached == null) {
            List<Glyph> built = new ArrayList<>(CHARACTERS.length());
            for (int i = 0; i < CHARACTERS.length(); i++) {
                char c = CHARACTERS.charAt(i);
                built.add(new Glyph(c, describe(renderTemplate(c), 0, 0)));
            }
            cached = Collections.unmodifiableList(built);
            glyphs = cached;
        }
        return cached;
    }

    /** Renders one glyph's geometry on a white cell exactly the way a picture is rasterised. */
    static AsciiArtRasterizer.InkMap renderTemplate(char c) {
        BufferedImage cell = AsciiArtRasterizer.newCanvas(W, H);
        Graphics2D g = AsciiArtRasterizer.graphics(cell);
        try {
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(GLYPH_STROKE_PX, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawGlyph(g, c);
        } finally {
            g.dispose();
        }
        return AsciiArtRasterizer.InkMap.of(cell);
    }

    /**
     * The geometry of each glyph on an 8×16 cell (x 0..8, y 0..16, y down): cap height ≈ 2.5,
     * x-height ≈ 6.5, baseline ≈ 12.5, like a typical terminal face at this size.
     */
    private static void drawGlyph(Graphics2D g, char c) {
        switch (c) {
            case ' ' -> { }
            case '.' -> dot(g, 4, 12.5);
            case '`' -> line(g, 2.5, 2, 4.5, 4.5);
            case '\'' -> line(g, 4.5, 2, 4.5, 4.5);
            case '"' -> {
                line(g, 2.5, 2, 2.5, 4.5);
                line(g, 5.5, 2, 5.5, 4.5);
            }
            case ',' -> {
                dot(g, 4.5, 12.5);
                line(g, 4.5, 12.5, 3, 15);
            }
            case ':' -> {
                dot(g, 4, 6.5);
                dot(g, 4, 12.5);
            }
            case ';' -> {
                dot(g, 4.5, 6.5);
                dot(g, 4.5, 12.5);
                line(g, 4.5, 12.5, 3, 15);
            }
            case '-' -> line(g, 1.5, 8.5, 6.5, 8.5);
            case '_' -> line(g, 0.5, 13.5, 7.5, 13.5);
            case '~' -> {
                Path2D.Double p = new Path2D.Double();
                p.moveTo(1, 9.5);
                p.quadTo(2.5, 6, 4, 8.5);
                p.quadTo(5.5, 11, 7, 7.5);
                g.draw(p);
            }
            case '=' -> {
                line(g, 1.5, 7, 6.5, 7);
                line(g, 1.5, 10.5, 6.5, 10.5);
            }
            case '+' -> {
                line(g, 1, 8.5, 7, 8.5);
                line(g, 4, 5, 4, 12);
            }
            case '|' -> line(g, 4, 1.5, 4, 14.5);
            case '/' -> line(g, 6.5, 1.5, 1.5, 14.5);
            case '\\' -> line(g, 1.5, 1.5, 6.5, 14.5);
            case '<' -> polyline(g, 6, 4.5, 2, 8.5, 6, 12.5);
            case '>' -> polyline(g, 2, 4.5, 6, 8.5, 2, 12.5);
            case '^' -> polyline(g, 1.5, 6.5, 4, 2.5, 6.5, 6.5);
            case 'v' -> polyline(g, 1.5, 6.5, 4, 12.5, 6.5, 6.5);
            case 'V' -> polyline(g, 1, 2.5, 4, 12.5, 7, 2.5);
            case 'x' -> {
                line(g, 1.5, 6.5, 6.5, 12.5);
                line(g, 6.5, 6.5, 1.5, 12.5);
            }
            case 'X' -> {
                line(g, 1, 2.5, 7, 12.5);
                line(g, 7, 2.5, 1, 12.5);
            }
            case '*' -> {
                line(g, 4, 4, 4, 10);
                line(g, 1.5, 5.5, 6.5, 8.5);
                line(g, 6.5, 5.5, 1.5, 8.5);
            }
            case 'o' -> g.draw(new Ellipse2D.Double(1.5, 6.5, 5, 6));
            case 'O' -> g.draw(new Ellipse2D.Double(1, 2.5, 6, 10));
            case '(' -> {
                Path2D.Double p = new Path2D.Double();
                p.moveTo(5.5, 1.5);
                p.quadTo(0.5, 8, 5.5, 14.5);
                g.draw(p);
            }
            case ')' -> {
                Path2D.Double p = new Path2D.Double();
                p.moveTo(2.5, 1.5);
                p.quadTo(7.5, 8, 2.5, 14.5);
                g.draw(p);
            }
            case 'L' -> polyline(g, 2, 2.5, 2, 12.5, 6.5, 12.5);
            case 'T' -> {
                line(g, 1, 2.5, 7, 2.5);
                line(g, 4, 2.5, 4, 12.5);
            }
            case 'M' -> polyline(g, 1, 12.5, 1, 2.5, 4, 8, 7, 2.5, 7, 12.5);
            case 'W' -> polyline(g, 1, 2.5, 2, 12.5, 4, 6, 6, 12.5, 7, 2.5);
            case '%' -> {
                line(g, 7, 2, 1, 13);
                g.draw(new Ellipse2D.Double(0.8, 2.8, 3.4, 3.4));
                g.draw(new Ellipse2D.Double(3.8, 8.8, 3.4, 3.4));
            }
            case '#' -> {
                line(g, 3, 3.5, 2.5, 12);
                line(g, 5.5, 3.5, 5, 12);
                line(g, 1.5, 6, 6.5, 6);
                line(g, 1.5, 9.5, 6.5, 9.5);
            }
            case '@' -> {
                g.draw(new Ellipse2D.Double(0.8, 2.5, 6.4, 11));
                g.draw(new Ellipse2D.Double(2.5, 5.5, 3.5, 5));
                line(g, 6, 5.5, 6, 11);
                line(g, 3, 9.5, 6.5, 12.5);
            }
            default -> throw new IllegalArgumentException("No glyph geometry for '" + c + "'");
        }
    }

    private static void line(Graphics2D g, double x1, double y1, double x2, double y2) {
        g.draw(new Line2D.Double(x1, y1, x2, y2));
    }

    private static void polyline(Graphics2D g, double... xy) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i + 1 < xy.length; i += 2) {
            p.lineTo(xy[i], xy[i + 1]);
        }
        g.draw(p);
    }

    private static void dot(Graphics2D g, double cx, double cy) {
        g.fill(new Ellipse2D.Double(cx - DOT_RADIUS_PX, cy - DOT_RADIUS_PX, 2 * DOT_RADIUS_PX, 2 * DOT_RADIUS_PX));
    }

    // ---- Matching ----

    /** The character for one described cell. */
    static char match(Descriptor cell) {
        if (cell.mean() < BLANK_MEAN) {
            return ' ';
        }
        if (cell.flatness() < FLAT_STDDEV) {
            return toneFor(cell.mean());
        }
        char edge = edgeGlyph(cell);
        if (edge != 0) {
            return edge;
        }
        char best = ' ';
        double bestCost = Double.MAX_VALUE;
        for (Glyph glyph : glyphs()) {
            double cost = cost(cell, glyph.descriptor());
            if (glyph.character() != ' ') {
                cost += NON_BLANK_BIAS;
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = glyph.character();
            }
        }
        return best;
    }

    /** The line glyph for a filled area's edge running through the cell, or 0 when it is not one. */
    static char edgeGlyph(Descriptor cell) {
        if (cell.coherence() < EDGE_MIN_COHERENCE || cell.step() < EDGE_MIN_STEP
            || cell.mean() < EDGE_MIN_MEAN || cell.mean() > EDGE_MAX_MEAN) {
            return 0;
        }
        double degrees = Math.toDegrees(cell.orientation());
        if (degrees >= SLASH_MIN_DEGREES && degrees < BAR_MIN_DEGREES) {
            return '/';
        }
        if (degrees >= BAR_MIN_DEGREES && degrees <= BAR_MAX_DEGREES) {
            return '|';
        }
        if (degrees > BAR_MAX_DEGREES && degrees <= BACKSLASH_MAX_DEGREES) {
            return '\\';
        }
        return 0;
    }

    /** The tone-ramp rung for {@code mean} ink. */
    static char toneFor(double mean) {
        int rung = 0;
        while (rung < TONAL_THRESHOLDS.length && mean >= TONAL_THRESHOLDS[rung]) {
            rung++;
        }
        return TONAL_RAMP.charAt(rung);
    }

    /**
     * Mean squared difference of the blurred samples plus the orientation disagreement: a
     * coherent cell charges every glyph that is less directional than itself in the cell's
     * direction. Charging only glyphs with a direction of their own would let blobs like
     * {@code o} and {@code T} win over {@code /} on a thin diagonal line, because they carry no
     * orientation to disagree with; charging a glyph for its own softness would make a gentle
     * {@code (} lose to {@code |} even on its own template.
     */
    static double cost(Descriptor cell, Descriptor glyph) {
        double ssd = meanSquaredDifference(cell.blurred(), glyph.blurred());
        double aligned = Math.cos(cell.orientation() - glyph.orientation());
        double agreement = glyph.coherence() * aligned * aligned;
        double shortfall = Math.max(0.0, cell.coherence() - agreement);
        return ssd + ORIENTATION_WEIGHT * cell.coherence() * shortfall;
    }

    static double meanSquaredDifference(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return sum / a.length;
    }

    // ---- Whole pictures ----

    /** Converts a canvas into exactly {@code rows} lines of {@code columns} characters. */
    static String[] convert(AsciiArtRasterizer.InkMap ink, int columns, int rows) {
        String[] lines = new String[rows];
        StringBuilder sb = new StringBuilder(columns);
        for (int r = 0; r < rows; r++) {
            sb.setLength(0);
            for (int c = 0; c < columns; c++) {
                sb.append(match(describe(ink, c * W, r * H)));
            }
            lines[r] = sb.toString();
        }
        return lines;
    }

    /**
     * Drops blank edge rows and the indentation every non-blank line shares, keeping the relative
     * alignment inside the picture. Trailing spaces are removed per line. Returns an empty string
     * for an all-blank grid.
     */
    static String trimPicture(String[] lines) {
        List<String> kept = new ArrayList<>(lines.length);
        for (String line : lines) {
            kept.add(line.stripTrailing());
        }
        while (!kept.isEmpty() && kept.get(0).isEmpty()) {
            kept.remove(0);
        }
        while (!kept.isEmpty() && kept.get(kept.size() - 1).isEmpty()) {
            kept.remove(kept.size() - 1);
        }
        int indent = Integer.MAX_VALUE;
        for (String line : kept) {
            if (!line.isEmpty()) {
                int leading = 0;
                while (leading < line.length() && line.charAt(leading) == ' ') {
                    leading++;
                }
                indent = Math.min(indent, leading);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kept.size(); i++) {
            String line = kept.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(line.isEmpty() ? "" : line.substring(indent));
        }
        return sb.toString();
    }
}
