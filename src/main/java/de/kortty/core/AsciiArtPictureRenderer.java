package de.kortty.core;

import java.awt.AWTError;
import java.awt.HeadlessException;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The AI Picture pipeline behind one call: locate the SVG in a model answer, scan it, rasterise
 * it, convert the cells to characters, and judge whether the result is a picture worth showing.
 *
 * <p>Every rejection carries a {@link RejectReason} whose {@link RejectReason#repairHint()} names
 * the defect and the fix in the words the model is then asked to act on — a second request that
 * just says "try again" reproduces the same answer. Hard rejections return no text; soft ones
 * (a picture that is merely small or built from too few shapes) still return it, so the caller
 * can keep it as the best result so far while it asks for a better one.</p>
 */
public final class AsciiArtPictureRenderer {

    static final int MIN_COLUMNS = 10;
    static final int MAX_COLUMNS = 200;
    static final int MIN_ROWS = 5;
    static final int MAX_ROWS = 100;
    /** Fewer non-blank cells than this share of the grid is a blank page, not a faint drawing. */
    static final double NO_INK_MAX_COVERAGE = 0.01;
    /** More than this share of non-blank cells means the page is painted over, not drawn on. */
    static final double ALL_DARK_MIN_COVERAGE = 0.92;
    /** One character filling more than this share of the grid is a slab, whatever its tone. */
    static final double ALL_DARK_SINGLE_TONE_COVERAGE = 0.80;
    /** Below 4×2 cells no subject is recognisable; this is a hard rejection. */
    static final int MIN_TRIMMED_WIDTH = 4;
    static final int MIN_TRIMMED_HEIGHT = 2;
    /** A picture spanning less of the grid than this is asked to be redrawn larger (soft). */
    static final double MIN_BOUNDS_COVERAGE = 0.45;
    /** Fewer drawable shapes than this is a sketch at best (soft), or too little to judge a path error by. */
    static final int MIN_SHAPES = 3;
    /** When less of the drawing lies inside the viewBox, the viewBox is wrong, not the drawing. */
    static final double MIN_INSIDE_VIEWBOX = 0.5;
    /** Share of the example's drawable tags that, found verbatim, makes the answer a copy. */
    static final double COPIED_EXAMPLE_TAG_SHARE = 0.8;
    /**
     * Extent (as a share of the box) a shape without area — a lone line — counts with in the
     * refit decision, so that a drawing made of lines is not ignored as having no extent.
     */
    static final double DEGENERATE_EXTENT_SHARE = 0.01;
    /** The warning that lists the element kinds the scanner skipped; also feeds the repair request. */
    static final String IGNORED_ELEMENTS_PREFIX = "Ignored elements: ";

    private AsciiArtPictureRenderer() {
    }

    // ---- Result types ----

    /** Why an answer was not accepted; the hint is what the repair request tells the model. */
    public enum RejectReason {
        NO_SVG(true, "noSvg",
            "It contained no <svg> element. Reply with exactly one fenced code block tagged svg that "
                + "starts with <svg viewBox=\"0 0 100 100\"> and ends with </svg>."),
        NO_SHAPES(true, "noShapes",
            "Its <svg> contained no drawable shape. Draw the subject with rect, circle, ellipse, line, "
                + "polyline, polygon and path elements only; text, defs, use, image and style are ignored."),
        BAD_PATH(true, "badPath",
            "A <path> had unreadable d data, so too little of the picture could be drawn. Use only the "
                + "commands M L H V C S Q T A Z with plain numbers, or replace the path with rect, circle, "
                + "ellipse, polygon and line elements."),
        COPIED_EXAMPLE(true, "copiedExample",
            "It repeated the example picture from the instructions instead of drawing the requested "
                + "subject. Draw the requested subject with your own shapes and do not reuse the "
                + "example's elements."),
        OUTSIDE_CANVAS(true, "outsideCanvas",
            "Its shapes had no visible extent on the canvas. Keep every coordinate between 0 and 100 "
                + "inside viewBox=\"0 0 100 100\" and give every shape a width and height of at least "
                + "6 units."),
        NO_INK(true, "noInk",
            "It produced no visible marks: every shape was white, invisible, or a single background "
                + "rectangle covering the whole canvas. Draw the subject as dark shapes (fill black, #555 "
                + "or #aaa) on the white canvas, without a background rectangle."),
        ALL_DARK(true, "allDark",
            "It covered almost the whole canvas with one dark tone, so no subject was recognisable. "
                + "Leave the background white, fill only the subject's parts, and use black, #555 and "
                + "#aaa for different parts so they stay distinguishable."),
        TOO_SMALL(true, "tooSmall",
            "The drawing was too small: it used only a small part of the canvas. Make the subject span "
                + "at least 60 of the 100 units in width or height and keep every detail at least 6 "
                + "units in size."),
        TOO_FEW_SHAPES(false, "tooFewShapes",
            "It contained fewer than 3 shapes. Build the subject from 4 to 40 shapes: the silhouette "
                + "parts first, then a few details."),
        RENDERER_UNAVAILABLE(true, "rendererUnavailable", null),
        /** Set by the caller when the answer was cut off before any drawable shape; never produced here. */
        TRUNCATED_EMPTY(true, "truncatedEmpty", null);

        private final boolean hard;
        private final String i18nKey;
        private final String repairHint;

        RejectReason(boolean hard, String i18nKey, String repairHint) {
            this.hard = hard;
            this.i18nKey = i18nKey;
            this.repairHint = repairHint;
        }

        /**
         * Whether the reason discards the picture by default. {@link #TOO_SMALL} is hard here but
         * is issued softly when the picture is merely under-sized rather than unrecognisable; the
         * {@link RenderResult#soft()} flag of the result is authoritative.
         */
        public boolean isHard() {
            return hard;
        }

        /** The sentence for the repair request, or {@code null} when a retry cannot help. */
        public String repairHint() {
            return repairHint;
        }

        /** The camelCase suffix of the {@code asciiArt.ai.reject.<key>} message key. */
        public String i18nKey() {
            return i18nKey;
        }
    }

    /**
     * Measurements of one conversion. {@code shapes} counts the drawable shapes that were drawn
     * (a suppressed background slab is not one of them; before rasterisation it is the scanned
     * count); {@code boundsCoverage} is the larger of the ink bounding box's width share of the
     * columns and height share of the rows, before trimming; {@code trimmedWidth}/
     * {@code trimmedHeight} are that bounding box in cells.
     */
    public record Stats(
            int shapes,
            int droppedElements,
            int nonBlankCells,
            double inkCoverage,
            double boundsCoverage,
            int trimmedWidth,
            int trimmedHeight,
            boolean inputTruncated,
            boolean capsTripped,
            boolean backgroundDropped,
            boolean refitted) {

        static final Stats NONE = new Stats(0, 0, 0, 0, 0, 0, 0, false, false, false, false);
    }

    /**
     * The outcome. {@code text} is the trimmed picture — present when accepted or softly rejected,
     * {@code null} on a hard rejection.
     */
    public record RenderResult(String text, RejectReason rejection, boolean soft, Stats stats, List<String> warnings) {

        public RenderResult {
            warnings = warnings != null ? List.copyOf(warnings) : List.of();
            stats = stats != null ? stats : Stats.NONE;
        }

        public boolean isAccepted() {
            return rejection == null;
        }

        public boolean isSoftRejection() {
            return rejection != null && soft;
        }

        /** Whether there is a picture to show: accepted or soft, and not blank. */
        public boolean isUsable() {
            return text != null && !text.isBlank() && (isAccepted() || soft);
        }

        /**
         * The text for the repair request: the reason's {@link RejectReason#repairHint()} plus
         * what this particular answer got wrong where the hint alone would stay generic — the
         * element kinds that were ignored ({@link RejectReason#NO_SHAPES}) and the offending
         * path data ({@link RejectReason#BAD_PATH}). A model that drew with {@code <use>} or
         * {@code <text>} is otherwise never told which of its elements were dropped. {@code null}
         * when the answer was accepted or when a retry cannot help.
         */
        public String repairFeedback() {
            if (rejection == null || rejection.repairHint() == null) {
                return null;
            }
            StringBuilder feedback = new StringBuilder(rejection.repairHint());
            if (rejection == RejectReason.NO_SHAPES) {
                warnings.stream().filter(w -> w.startsWith(IGNORED_ELEMENTS_PREFIX)).findFirst()
                    .ifPresent(w -> feedback.append(" The elements it used were ignored: ")
                        .append(w, IGNORED_ELEMENTS_PREFIX.length(), w.length()));
            } else if (rejection == RejectReason.BAD_PATH) {
                warnings.stream().filter(w -> w.startsWith(AsciiArtSvgScanner.PATH_ERROR_PREFIX)).findFirst()
                    .ifPresent(w -> feedback.append(' ').append(w).append('.'));
            }
            return feedback.toString();
        }
    }

    // ---- Entry points ----

    /** Everything from the first {@code <svg} to the last {@code </svg>}, or {@code null}. */
    public static String locateSvg(String rawReply) {
        return AsciiArtSvgScanner.locateSvg(rawReply);
    }

    /**
     * Renders the SVG inside a whole model answer (fenced or not, reasoning blocks tolerated) into
     * a {@code columns}×{@code rows} picture. {@code exampleSvg} is the example shown to the model;
     * an answer that merely repeats it is rejected as {@link RejectReason#COPIED_EXAMPLE} — pass
     * {@code null} to skip that check.
     *
     * @throws InterruptedException when the calling thread is interrupted during rasterisation
     */
    public static RenderResult renderReply(String rawReply, int columns, int rows, String exampleSvg)
            throws InterruptedException {
        String svg = locateSvg(rawReply);
        if (svg == null) {
            return hard(RejectReason.NO_SVG, Stats.NONE, List.of());
        }
        return render(svg, columns, rows, exampleSvg);
    }

    /** Like {@link #renderReply} for text that is already (or contains) the SVG document. */
    public static RenderResult render(String svgText, int columns, int rows, String exampleSvg)
            throws InterruptedException {
        int cols = clamp(columns, MIN_COLUMNS, MAX_COLUMNS);
        int rws = clamp(rows, MIN_ROWS, MAX_ROWS);
        String svg = locateSvg(svgText);
        if (svg == null) {
            return hard(RejectReason.NO_SVG, Stats.NONE, List.of());
        }
        AsciiArtSvgScanner.Scene scene = AsciiArtSvgScanner.scan(svg);
        List<String> warnings = new ArrayList<>(scene.warnings());
        if (!scene.ignoredElements().isEmpty()) {
            warnings.add(IGNORED_ELEMENTS_PREFIX + String.join(", ", scene.ignoredElements()) + ".");
        }
        warnings.addAll(scene.pathErrors());
        Stats stats = new Stats(scene.shapes().size(), scene.droppedElements(), 0, 0, 0, 0, 0,
            scene.truncated(), scene.capsTripped(), false, false);

        if (scene.shapes().isEmpty()) {
            return hard(RejectReason.NO_SHAPES, stats, warnings);
        }
        if (!scene.pathErrors().isEmpty() && scene.shapes().size() < MIN_SHAPES) {
            return hard(RejectReason.BAD_PATH, stats, warnings);
        }
        if (exampleSvg != null && isCopiedExample(svg, exampleSvg)) {
            return hard(RejectReason.COPIED_EXAMPLE, stats, warnings);
        }
        Rectangle2D shapeBounds = AsciiArtRasterizer.shapeBounds(scene.shapes());
        if (shapeBounds == null || !isDrawable(shapeBounds) || !anyShapeHasExtent(scene)) {
            return hard(RejectReason.OUTSIDE_CANVAS, stats, warnings);
        }
        Rectangle2D fit = AsciiArtRasterizer.fitRect(scene);
        boolean refitted = false;
        if (scene.hasViewBox() && insideShare(scene, scene.viewBox()) < MIN_INSIDE_VIEWBOX) {
            fit = AsciiArtRasterizer.expand(shapeBounds);
            refitted = true;
            warnings.add("Most of the drawing lay outside the viewBox; the canvas was refitted to the shapes.");
        }
        if (fit == null || !isDrawable(fit)) {
            return hard(RejectReason.OUTSIDE_CANVAS, stats, warnings);
        }

        AsciiArtRasterizer.Result raster;
        try {
            raster = AsciiArtRasterizer.rasterize(scene, cols, rws, fit, true);
        } catch (AWTError | HeadlessException | UnsatisfiedLinkError | NoClassDefFoundError e) {
            warnings.add("The Java2D renderer is not available: " + e);
            return hard(RejectReason.RENDERER_UNAVAILABLE, stats, warnings);
        }
        String[] grid = AsciiArtGlyphs.convert(raster.ink(), cols, rws);

        int totalCells = cols * rws;
        int nonBlank = 0;
        Map<Character, Integer> tones = new HashMap<>();
        for (String line : grid) {
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c != ' ') {
                    nonBlank++;
                    tones.merge(c, 1, Integer::sum);
                }
            }
        }
        int dominant = tones.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        String text = AsciiArtGlyphs.trimPicture(grid);
        int trimmedHeight = text.isEmpty() ? 0 : text.split("\n", -1).length;
        int trimmedWidth = 0;
        for (String line : text.split("\n", -1)) {
            trimmedWidth = Math.max(trimmedWidth, line.length());
        }
        double boundsCoverage = Math.max((double) trimmedWidth / cols, (double) trimmedHeight / rws);
        stats = new Stats(raster.drawnShapes(), scene.droppedElements(), nonBlank,
            (double) nonBlank / totalCells, boundsCoverage, trimmedWidth, trimmedHeight,
            scene.truncated(), scene.capsTripped(), raster.backgroundDropped(), refitted);

        // NO_INK is for an answer with nothing to see (white shapes, a suppressed background) or
        // a mere speck. A complete but tiny drawing also falls under the 1 % floor at 60x30 (18
        // cells), yet its defect is the size, and only the TOO_SMALL hint tells the model to
        // enlarge the subject rather than to draw dark shapes it already drew.
        boolean speck = trimmedWidth < MIN_TRIMMED_WIDTH || trimmedHeight < MIN_TRIMMED_HEIGHT;
        if ((double) nonBlank / totalCells < NO_INK_MAX_COVERAGE && (nonBlank == 0 || speck)) {
            return hard(RejectReason.NO_INK, stats, warnings);
        }
        if ((double) nonBlank / totalCells > ALL_DARK_MIN_COVERAGE
            || (double) dominant / totalCells > ALL_DARK_SINGLE_TONE_COVERAGE) {
            return hard(RejectReason.ALL_DARK, stats, warnings);
        }
        if (speck) {
            return hard(RejectReason.TOO_SMALL, stats, warnings);
        }
        if (boundsCoverage < MIN_BOUNDS_COVERAGE) {
            return new RenderResult(text, RejectReason.TOO_SMALL, true, stats, warnings);
        }
        if (raster.drawnShapes() < MIN_SHAPES) {
            return new RenderResult(text, RejectReason.TOO_FEW_SHAPES, true, stats, warnings);
        }
        return new RenderResult(text, null, false, stats, warnings);
    }

    // ---- Checks ----

    /**
     * Whether {@code svg} is the example handed to the model: the whole document matches after
     * whitespace and quote normalisation, or at least {@link #COPIED_EXAMPLE_TAG_SHARE} of the
     * example's drawable element tags appear verbatim. The tag test catches a model that keeps the
     * example's shapes and only edits the wrapper.
     */
    static boolean isCopiedExample(String svg, String exampleSvg) {
        String example = AsciiArtSvgScanner.locateSvg(exampleSvg);
        if (example == null) {
            return false;
        }
        String answer = normalise(svg);
        String reference = normalise(example);
        if (answer.equals(reference)) {
            return true;
        }
        List<String> tags = drawableTags(reference);
        if (tags.isEmpty()) {
            return false;
        }
        int found = 0;
        for (String tag : tags) {
            if (answer.contains(tag)) {
                found++;
            }
        }
        return found >= Math.ceil(COPIED_EXAMPLE_TAG_SHARE * tags.size());
    }

    /** Collapses whitespace, unifies quotes and case, and tightens the tag punctuation. */
    static String normalise(String svg) {
        StringBuilder sb = new StringBuilder(svg.length());
        boolean pendingSpace = false;
        for (int i = 0; i < svg.length(); i++) {
            char c = svg.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = true;
                continue;
            }
            if (c == '\'') {
                c = '"';
            }
            if (pendingSpace) {
                char last = sb.length() > 0 ? sb.charAt(sb.length() - 1) : ' ';
                if (last != '<' && last != '=' && last != '"' && c != '>' && c != '=' && c != '/') {
                    sb.append(' ');
                }
                pendingSpace = false;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** The normalised {@code <rect …>}, {@code <path …>} … tags of a normalised document. */
    private static List<String> drawableTags(String normalised) {
        List<String> tags = new ArrayList<>();
        int pos = 0;
        while (true) {
            int open = normalised.indexOf('<', pos);
            if (open < 0) {
                break;
            }
            int close = normalised.indexOf('>', open);
            if (close < 0) {
                break;
            }
            String tag = normalised.substring(open, close + 1);
            int nameEnd = 1;
            while (nameEnd < tag.length() && Character.isLetter(tag.charAt(nameEnd))) {
                nameEnd++;
            }
            String name = tag.substring(1, nameEnd);
            if (List.of("rect", "circle", "ellipse", "line", "polyline", "polygon", "path").contains(name)) {
                tags.add(tag);
            }
            pos = close + 1;
        }
        return tags;
    }

    /**
     * The share of the drawing's extent inside {@code box}: each shape's bounds weighted by its
     * area, so one stray shape far away neither triggers a refit nor loses the picture. Bounds
     * without area (a lone line) count with a minimal extent so they are not ignored.
     */
    static double insideShare(AsciiArtSvgScanner.Scene scene, Rectangle2D box) {
        double inside = 0;
        double total = 0;
        double minimal = Math.max(box.getWidth(), box.getHeight()) * DEGENERATE_EXTENT_SHARE;
        for (AsciiArtSvgScanner.Shape shape : scene.shapes()) {
            Rectangle2D b = shape.worldBounds();
            Rectangle2D padded = new Rectangle2D.Double(
                b.getX(), b.getY(), Math.max(b.getWidth(), minimal), Math.max(b.getHeight(), minimal));
            double area = padded.getWidth() * padded.getHeight();
            Rectangle2D overlap = padded.createIntersection(box);
            double overlapArea = overlap.isEmpty() ? 0 : overlap.getWidth() * overlap.getHeight();
            total += area;
            inside += overlapArea;
        }
        return total > 0 ? inside / total : 0;
    }

    /** Two collapsed shapes far apart span a box together; each on its own still draws nothing. */
    private static boolean anyShapeHasExtent(AsciiArtSvgScanner.Scene scene) {
        for (AsciiArtSvgScanner.Shape shape : scene.shapes()) {
            if (isDrawable(shape.worldBounds())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDrawable(Rectangle2D r) {
        return Double.isFinite(r.getX()) && Double.isFinite(r.getY())
            && Double.isFinite(r.getWidth()) && Double.isFinite(r.getHeight())
            && (r.getWidth() > 0 || r.getHeight() > 0);
    }

    private static RenderResult hard(RejectReason reason, Stats stats, List<String> warnings) {
        return new RenderResult(null, reason, false, stats, warnings);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** A short summary for log lines; the stats record itself is verbose. */
    static String describe(RenderResult result) {
        Stats s = result.stats();
        return String.format(Locale.ROOT,
            "%s shapes=%d dropped=%d ink=%.3f bounds=%.2f trimmed=%dx%d%s%s%s%s",
            result.isAccepted() ? "accepted" : (result.soft() ? "soft:" : "rejected:") + result.rejection(),
            s.shapes(), s.droppedElements(), s.inkCoverage(), s.boundsCoverage(), s.trimmedWidth(), s.trimmedHeight(),
            s.inputTruncated() ? " truncated" : "", s.capsTripped() ? " capped" : "",
            s.backgroundDropped() ? " backgroundDropped" : "", s.refitted() ? " refitted" : "");
    }
}
