package de.kortty.core;

/**
 * The single source for how a Full-code-analysis category looks: its glyph and its accent colour.
 *
 * <p>Four surfaces render these — the analysis window's section titles, the change preview's
 * explanation cards, the AI-processing window's checklist rows, and the HTML/PDF export. They used
 * to carry two verbatim copies of the same path table, so redrawing an icon silently fixed three of
 * them and left the export showing the old one. Everything reads from here instead.</p>
 *
 * <h2>Drawing rules for the glyphs</h2>
 *
 * <p>Each glyph is one path string on a 16x16 viewBox, rendered filled — as SVG in the HTML
 * surfaces and as a JavaFX {@code SVGPath} in the progress rows. Both are filled with the
 * <b>even-odd</b> rule so a nested subpath punches a hole (the padlock's keyhole, the module
 * hexagon's centre).</p>
 *
 * <p>The consequence is a hard constraint on any future edit: <b>subpaths may touch or nest, never
 * overlap</b>. An overlap is not a drawing artefact under even-odd — it becomes a hole. Two of
 * these glyphs were redrawn for exactly that reason (the gauge needle reached into the dial's
 * band; the padlock's keyhole arc was near-degenerate).</p>
 */
public final class AnalysisCategoryVisuals {

    /** Padlock with a cut-out keyhole: shackle, body, keyhole (nested, so it renders as a hole). */
    private static final String SECURITY_PATH =
        "M8 1.2 A3.35 3.35 0 0 0 4.65 4.55 V7.7 H6.35 V4.55 A1.65 1.65 0 0 1 9.65 4.55 V7.7 "
            + "H11.35 V4.55 A3.35 3.35 0 0 0 8 1.2 Z "
            + "M3.4 7.7 H12.6 V14.8 H3.4 Z "
            + "M8.72 11.68 A1.3 1.3 0 1 0 7.28 11.68 L6.95 13.5 H9.05 Z";

    /** Gauge: a dial band plus a needle. The needle stops at radius 3.68, inside the band's 4.2. */
    private static final String OPTIMIZATION_PATH =
        "M1.8 9.2 A6.2 6.2 0 0 1 14.2 9.2 H12.2 A4.2 4.2 0 0 0 3.8 9.2 Z "
            + "M10.6 6.6 7.19 8.4 A1.15 1.15 0 1 0 8.81 10 Z";

    /** Three stacked layers — structure and architecture, the findings this category collects. */
    private static final String DESIGN_PATH =
        "M8 1.3 14.6 4.6 8 7.9 1.4 4.6 Z "
            + "M8 9.35 2.85 6.78 1.4 7.5 8 10.8 14.6 7.5 13.15 6.78 Z "
            + "M8 12.25 2.85 9.68 1.4 10.4 8 13.7 14.6 10.4 13.15 9.68 Z";

    /** Module hexagon: an outer hexagon with a nested one cut out of it. */
    private static final String DEPENDENCIES_PATH =
        "M8 1.2 14 4.6 V11.4 L8 14.8 2 11.4 V4.6 Z "
            + "M8 4.6 4.6 6.55 V10.45 L8 12.4 11.4 10.45 V6.55 Z";

    private AnalysisCategoryVisuals() {
    }

    /**
     * The 16x16 glyph path for a category. Unknown categories fall back to design's, matching how
     * the callers treat an unrecognised category elsewhere.
     */
    public static String iconPath(String category) {
        return switch (category != null ? category : "") {
            case "security" -> SECURITY_PATH;
            case "optimization" -> OPTIMIZATION_PATH;
            case "dependencies" -> DEPENDENCIES_PATH;
            default -> DESIGN_PATH; // design / catch-all
        };
    }

    /**
     * The glyph as inline SVG, coloured through CSS {@code currentColor} — style the surrounding
     * element. Inline SVG rather than an emoji because the WebView's default fonts cannot render
     * supplementary-plane emoji; they come out as replacement boxes.
     */
    public static String iconSvg(String category) {
        return "<svg class=\"sec-ic\" viewBox=\"0 0 16 16\" xmlns=\"http://www.w3.org/2000/svg\">"
            + "<path fill-rule=\"evenodd\" d=\"" + iconPath(category) + "\"/></svg>";
    }

    /** Accent colour for a category on korTTY's dark UI surfaces. */
    public static String colorHex(String category) {
        return switch (category != null ? category : "") {
            case "security" -> "#e5484d";
            case "optimization" -> "#f59e0b";
            case "dependencies" -> "#14b8a6";
            default -> "#8b5cf6"; // design / catch-all
        };
    }

    /**
     * Accent colour for a category on a white page (the HTML and PDF exports). Amber and teal are
     * deliberately darker than {@link #colorHex}: the UI values are tuned for a dark background and
     * wash out on paper.
     */
    public static String printColorHex(String category) {
        return switch (category != null ? category : "") {
            case "security" -> "#e5484d";
            case "optimization" -> "#d97706";
            case "dependencies" -> "#0d9488";
            default -> "#8b5cf6"; // design / catch-all
        };
    }
}
