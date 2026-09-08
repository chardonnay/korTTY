package de.kortty.core;

import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the SVG subset the AI Picture prompt asks for into plain Java2D geometry.
 *
 * <p>This is a hand-written cursor scanner, not an XML parser, on purpose: a model reply is often
 * not well-formed (a cut-off document, a stray {@code <} in prose, an unquoted attribute, an
 * unknown namespace prefix), and a DOM parser would reject the whole picture for one such slip.
 * The scanner instead keeps every shape it can read, salvages an unterminated tag at the end of
 * the text as self-closing so a truncated answer still draws what it has, and reports what it
 * dropped so the validator can name the defect in its repair request. {@link #scan(String)} never
 * throws.</p>
 *
 * <p>Colours are reduced to an ink value (0 = white, 1 = black) with an alpha, because the picture
 * ends up as monochrome characters anyway; anything not resolvable (gradients, unknown names) is
 * mid-gray so a shape is never lost over its paint. The caps below keep a pathological answer from
 * consuming the worker: they truncate the scene but never reject it.</p>
 */
public final class AsciiArtSvgScanner {

    /** Longer answers are cut; 200 000 characters is far beyond any drawable 40-shape picture. */
    static final int MAX_INPUT_CHARS = 200_000;
    /** More shapes than any grid can show; protects against a model looping on one element. */
    static final int MAX_SHAPES = 4_000;
    static final int MAX_PATH_COMMANDS_PER_PATH = 10_000;
    static final int MAX_PATH_COMMANDS_TOTAL = 100_000;
    /** Coordinates beyond this are numeric noise, not a drawing; Java2D would also lose precision. */
    static final double MAX_COORDINATE = 1e6;
    /** Ink for paints the converter cannot resolve (gradients, unknown names): visible but not dominant. */
    static final double UNKNOWN_PAINT_INK = 0.5;
    static final double DEFAULT_STROKE_WIDTH = 1.0;
    /**
     * Longest quote of an attribute value (a broken {@code d}, an unknown paint, an unreadable
     * transform) in a warning: enough to recognise the culprit, while a 50 000-character attribute
     * cannot flood the log or the repair request.
     */
    static final int WARNING_QUOTE_LENGTH = 60;
    /** Every path error starts with this, so the facade can pick the offending path out of the warnings. */
    static final String PATH_ERROR_PREFIX = "Unreadable path data";
    /** An arc is split into cubic pieces of at most a quarter turn; larger pieces visibly deviate. */
    private static final double ARC_MAX_SWEEP = Math.PI / 2;
    /** Nesting deeper than this is not a drawing; the stack stops growing to bound memory. */
    private static final int MAX_NESTING = 512;

    /** Containers whose content is drawn transparently: a plain group in this converter's model. */
    private static final Set<String> GROUP_ELEMENTS = Set.of("svg", "g", "a", "switch");
    /** Elements whose subtree is not drawable here; the subtree is skipped and counted once. */
    private static final Set<String> SKIPPED_ELEMENTS = Set.of(
        "defs", "symbol", "clippath", "mask", "marker", "lineargradient", "radialgradient", "pattern",
        "filter", "text", "tspan", "textpath", "title", "desc", "metadata", "style", "script",
        "foreignobject", "image", "use");
    private static final Set<String> DRAWABLE_ELEMENTS = Set.of(
        "rect", "circle", "ellipse", "line", "polyline", "polygon", "path");

    /** Rec. 709 luma weights; the raster reads the green channel back, so paint is neutral gray. */
    private static final double LUMA_RED = 0.2126;
    private static final double LUMA_GREEN = 0.7152;
    private static final double LUMA_BLUE = 0.0722;

    /** Ink values are rounded to this many steps; the luma weights do not sum to exactly 1 in binary. */
    private static final double INK_PRECISION = 1_000_000.0;

    private static final Map<String, Integer> NAMED_COLORS = namedColors();

    private AsciiArtSvgScanner() {
    }

    // ---- Result types ----

    /**
     * A fill and/or stroke paint reduced to ink and alpha. {@code ink} is NaN for {@code none}.
     */
    record Paint(double ink, double alpha) {
        static final Paint NONE = new Paint(Double.NaN, 0.0);
        static final Paint BLACK = new Paint(1.0, 1.0);
    }

    /**
     * One drawable element. {@code geometry} is in the element's own user space, {@code transform}
     * maps it into the root canvas; {@code strokeWidth} is in user units. Ink values are 0 (white)
     * to 1 (black), NaN when the paint is {@code none}.
     */
    public record Shape(
            String element,
            java.awt.Shape geometry,
            AffineTransform transform,
            double fillInk,
            double fillAlpha,
            double strokeInk,
            double strokeAlpha,
            double strokeWidth) {

        public boolean hasFill() {
            return !Double.isNaN(fillInk) && fillAlpha > 0;
        }

        public boolean hasStroke() {
            return !Double.isNaN(strokeInk) && strokeAlpha > 0 && strokeWidth > 0;
        }

        /** The uniform scale the transform applies, so a stroke width can follow it. */
        public double scale() {
            return Math.sqrt(Math.abs(transform.getDeterminant()));
        }

        /** Bounds on the root canvas, including half the stroke width. */
        public Rectangle2D worldBounds() {
            Rectangle2D bounds = transform.createTransformedShape(geometry).getBounds2D();
            if (!hasStroke()) {
                return bounds;
            }
            double half = strokeWidth * scale() / 2.0;
            return new Rectangle2D.Double(
                bounds.getX() - half, bounds.getY() - half,
                bounds.getWidth() + 2 * half, bounds.getHeight() + 2 * half);
        }
    }

    /**
     * Everything the scanner could read. {@code viewBox} is {@code null} when the document has no
     * usable one; {@code ignoredElements} names the element kinds that were skipped, so a repair
     * request can say "text is ignored"; {@code pathErrors} quote the paths whose data stopped
     * parsing; {@code truncated} means the text ended inside a tag or before {@code </svg>}.
     */
    public record Scene(
            Rectangle2D viewBox,
            List<Shape> shapes,
            int droppedElements,
            List<String> ignoredElements,
            List<String> pathErrors,
            List<String> warnings,
            boolean truncated,
            boolean capsTripped) {

        public boolean hasViewBox() {
            return viewBox != null && viewBox.getWidth() > 0 && viewBox.getHeight() > 0;
        }
    }

    // ---- Locating the document ----

    /**
     * Cuts the SVG document out of a whole model answer: everything from the first {@code <svg}
     * to the last {@code </svg>} after it, or to the end of the text when the closing tag is
     * missing (a cut-off answer). Reasoning blocks are removed first because a model that thinks
     * aloud may quote an {@code <svg>} in its deliberation. Fences are irrelevant: whatever the
     * model tagged the block with, the document itself is what matters. Returns {@code null} when
     * there is no {@code <svg} at all.
     */
    public static String locateSvg(String rawReply) {
        if (rawReply == null || rawReply.isBlank()) {
            return null;
        }
        String text = stripThinkBlocks(rawReply);
        int start = -1;
        int from = 0;
        while (true) {
            int candidate = indexOfIgnoreCase(text, "<svg", from);
            if (candidate < 0) {
                break;
            }
            int after = candidate + "<svg".length();
            if (after >= text.length() || !isNameChar(text.charAt(after)) || text.charAt(after) == ':') {
                start = candidate;
                break;
            }
            from = after;
        }
        if (start < 0) {
            return null;
        }
        int close = lastIndexOfIgnoreCase(text, "</svg>");
        int end = close > start ? close + "</svg>".length() : text.length();
        return text.substring(start, end);
    }

    /** Removes {@code <think>…</think>} blocks and a dangling {@code <think>} that never closed. */
    private static String stripThinkBlocks(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        int pos = 0;
        while (pos < text.length()) {
            int open = indexOfIgnoreCase(text, "<think>", pos);
            if (open < 0) {
                sb.append(text, pos, text.length());
                break;
            }
            sb.append(text, pos, open);
            int close = indexOfIgnoreCase(text, "</think>", open + "<think>".length());
            if (close < 0) {
                break;
            }
            pos = close + "</think>".length();
        }
        return sb.toString();
    }

    /**
     * Case-insensitive {@code indexOf} on the text itself. The obvious shortcut — search a
     * lower-cased copy and use its indices on the original — is wrong because lower-casing is not
     * length-preserving (U+0130 {@code İ} becomes two chars), so one Turkish word in the model's
     * prose would shift every index after it and cut the document at the wrong place or throw.
     */
    static int indexOfIgnoreCase(String text, String needle, int from) {
        int last = text.length() - needle.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    /** Case-insensitive {@code lastIndexOf}; see {@link #indexOfIgnoreCase} for why not a lower-cased copy. */
    static int lastIndexOfIgnoreCase(String text, String needle) {
        for (int i = text.length() - needle.length(); i >= 0; i--) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    // ---- Scanning ----

    /** Reads every drawable element of {@code svg}; tolerant of anything, never throws. */
    public static Scene scan(String svg) {
        Scanner scanner = new Scanner(svg != null ? svg : "");
        scanner.run();
        return scanner.toScene();
    }

    /** Inherited presentation state of one open element. */
    private static final class Style {
        Paint fill = Paint.BLACK;
        Paint stroke = Paint.NONE;
        double fillOpacity = 1.0;
        double strokeOpacity = 1.0;
        /** Product of every {@code opacity} on the ancestor chain, applied to fill and stroke alike. */
        double opacity = 1.0;
        double strokeWidth = DEFAULT_STROKE_WIDTH;

        Style copy() {
            Style s = new Style();
            s.fill = fill;
            s.stroke = stroke;
            s.fillOpacity = fillOpacity;
            s.strokeOpacity = strokeOpacity;
            s.opacity = opacity;
            s.strokeWidth = strokeWidth;
            return s;
        }
    }

    private static final class Frame {
        final String name;
        final AffineTransform ctm;
        final Style style;

        Frame(String name, AffineTransform ctm, Style style) {
            this.name = name;
            this.ctm = ctm;
            this.style = style;
        }
    }

    private static final class Scanner {
        private final String text;
        private final boolean inputCut;
        private int pos;
        private final List<Frame> frames = new ArrayList<>();
        private final List<Shape> shapes = new ArrayList<>();
        private final Set<String> ignoredElements = new LinkedHashSet<>();
        private final List<String> pathErrors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final Set<String> unknownPaints = new LinkedHashSet<>();
        private Rectangle2D viewBox;
        private int droppedElements;
        private int totalPathCommands;
        private boolean truncated;
        private boolean capsTripped;
        private boolean svgOpen;
        /** Name of the skipped container being passed over and how deeply it is nested in itself. */
        private String skipName;
        private int skipDepth;

        Scanner(String text) {
            this.inputCut = text.length() > MAX_INPUT_CHARS;
            this.text = inputCut ? text.substring(0, MAX_INPUT_CHARS) : text;
            this.capsTripped = inputCut;
            frames.add(new Frame("#root", new AffineTransform(), new Style()));
        }

        Scene toScene() {
            for (String paint : unknownPaints) {
                warnings.add("Unknown colour \"" + quote(paint) + "\" was drawn as mid-gray.");
            }
            if (inputCut) {
                warnings.add("The SVG text was longer than " + MAX_INPUT_CHARS + " characters and was cut.");
            }
            return new Scene(
                viewBox,
                Collections.unmodifiableList(shapes),
                droppedElements,
                List.copyOf(ignoredElements),
                List.copyOf(pathErrors),
                List.copyOf(warnings),
                truncated || svgOpen,
                capsTripped);
        }

        void run() {
            int n = text.length();
            while (pos < n) {
                int lt = text.indexOf('<', pos);
                if (lt < 0) {
                    break;
                }
                pos = lt + 1;
                if (text.startsWith("!--", pos)) {
                    int end = text.indexOf("-->", pos + 3);
                    pos = end < 0 ? n : end + 3;
                    continue;
                }
                if (pos < n && text.charAt(pos) == '?') {
                    int end = text.indexOf("?>", pos);
                    pos = end < 0 ? n : end + 2;
                    continue;
                }
                if (pos < n && text.charAt(pos) == '!') {
                    boolean cdata = text.startsWith("![CDATA[", pos);
                    int end = cdata ? text.indexOf("]]>", pos) : text.indexOf('>', pos);
                    pos = end < 0 ? n : end + (cdata ? 3 : 1);
                    continue;
                }
                boolean closing = pos < n && text.charAt(pos) == '/';
                if (closing) {
                    pos++;
                }
                int nameStart = pos;
                while (pos < n && isNameChar(text.charAt(pos))) {
                    pos++;
                }
                if (pos == nameStart) {
                    continue; // a stray '<' in prose, not a tag
                }
                String name = localName(text.substring(nameStart, pos));
                Map<String, String> attrs = new LinkedHashMap<>();
                boolean selfClosing = readAttributes(attrs);
                if (closing) {
                    handleClose(name);
                } else {
                    handleOpen(name, attrs, selfClosing);
                }
            }
        }

        /**
         * Reads attributes up to the tag end; returns whether the tag was self-closing. A tag that
         * ends with the text (or runs into the next {@code <}) is treated as self-closing so the
         * element is still drawn — that is what a cut-off answer looks like.
         */
        private boolean readAttributes(Map<String, String> attrs) {
            int n = text.length();
            while (pos < n) {
                char c = text.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                    continue;
                }
                if (c == '>') {
                    pos++;
                    return false;
                }
                if (c == '/') {
                    if (pos + 1 < n && text.charAt(pos + 1) == '>') {
                        pos += 2;
                        return true;
                    }
                    pos++;
                    continue;
                }
                if (c == '<') {
                    // A missing '>' is sloppy markup, not a cut-off answer: salvage the tag as
                    // self-closing but leave the truncated flag alone, or the orchestrator would
                    // report a partial picture and skip the repair round for a complete answer.
                    return true;
                }
                int nameStart = pos;
                while (pos < n && isNameChar(text.charAt(pos))) {
                    pos++;
                }
                if (pos == nameStart) {
                    pos++; // junk character inside the tag
                    continue;
                }
                String attrName = localName(text.substring(nameStart, pos));
                skipWhitespace();
                String value = "";
                if (pos < n && text.charAt(pos) == '=') {
                    pos++;
                    skipWhitespace();
                    value = readAttributeValue();
                }
                attrs.putIfAbsent(attrName, value);
            }
            truncated = true;
            return true;
        }

        private String readAttributeValue() {
            int n = text.length();
            if (pos >= n) {
                return "";
            }
            char quote = text.charAt(pos);
            if (quote == '"' || quote == '\'') {
                int end = text.indexOf(quote, pos + 1);
                if (end < 0) {
                    String rest = text.substring(pos + 1);
                    pos = n;
                    truncated = true;
                    return rest;
                }
                String value = text.substring(pos + 1, end);
                pos = end + 1;
                return value;
            }
            int start = pos;
            while (pos < n) {
                char c = text.charAt(pos);
                if (Character.isWhitespace(c) || c == '>' || c == '<'
                    || (c == '/' && pos + 1 < n && text.charAt(pos + 1) == '>')) {
                    break;
                }
                pos++;
            }
            return text.substring(start, pos);
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private void handleClose(String name) {
            if (skipName != null) {
                if (name.equals(skipName) && --skipDepth == 0) {
                    skipName = null;
                }
                return;
            }
            for (int i = frames.size() - 1; i > 0; i--) {
                if (frames.get(i).name.equals(name)) {
                    while (frames.size() > i) {
                        frames.remove(frames.size() - 1);
                    }
                    if ("svg".equals(name) && frames.size() == 1) {
                        svgOpen = false;
                    }
                    return;
                }
            }
            // A close tag without its open tag: ignore it rather than unwind the wrong element.
        }

        private void handleOpen(String name, Map<String, String> attrs, boolean selfClosing) {
            if (skipName != null) {
                if (name.equals(skipName) && !selfClosing) {
                    skipDepth++;
                }
                return;
            }
            if (SKIPPED_ELEMENTS.contains(name)) {
                droppedElements++;
                ignoredElements.add(name);
                if (!selfClosing) {
                    skipName = name;
                    skipDepth = 1;
                }
                return;
            }
            Frame parent = frames.get(frames.size() - 1);
            if (DRAWABLE_ELEMENTS.contains(name)) {
                addShape(name, attrs, parent);
                return;
            }
            if (!GROUP_ELEMENTS.contains(name)) {
                ignoredElements.add(name);
            }
            if ("svg".equals(name)) {
                // The first usable viewBox wins, not the first <svg> tag: prose such as "wrap the
                // shapes in an <svg> element" precedes many answers, and that bare tag would
                // otherwise claim the slot and leave the real canvas unread.
                if (viewBox == null) {
                    viewBox = parseViewBox(attrs);
                }
                if (frames.size() == 1) {
                    svgOpen = !selfClosing;
                }
            }
            if (selfClosing) {
                return;
            }
            if (frames.size() >= MAX_NESTING) {
                capsTripped = true;
                return;
            }
            AffineTransform ctm = new AffineTransform(parent.ctm);
            ctm.concatenate(parseTransform(attrs.get("transform"), warnings));
            frames.add(new Frame(name, ctm, resolveStyle(parent.style, attrs)));
        }

        private Rectangle2D parseViewBox(Map<String, String> attrs) {
            double[] box = numbers(attrs.get("viewbox"));
            if (box.length >= 4 && isFinite(box) && box[2] > 0 && box[3] > 0) {
                return new Rectangle2D.Double(box[0], box[1], box[2], box[3]);
            }
            double width = length(attrs.get("width"), Double.NaN);
            double height = length(attrs.get("height"), Double.NaN);
            if (width > 0 && height > 0 && Double.isFinite(width) && Double.isFinite(height)) {
                return new Rectangle2D.Double(0, 0, width, height);
            }
            return null;
        }

        private Style resolveStyle(Style parent, Map<String, String> attrs) {
            Style style = parent.copy();
            Map<String, String> props = new LinkedHashMap<>();
            for (String key : List.of("fill", "stroke", "stroke-width", "opacity", "fill-opacity", "stroke-opacity")) {
                if (attrs.containsKey(key)) {
                    props.put(key, attrs.get(key));
                }
            }
            // style="…" declarations win over presentation attributes, as in the SVG cascade.
            String inline = attrs.get("style");
            if (inline != null) {
                for (String declaration : inline.split(";")) {
                    int colon = declaration.indexOf(':');
                    if (colon > 0) {
                        props.put(declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            declaration.substring(colon + 1).trim());
                    }
                }
            }
            if (props.containsKey("fill")) {
                style.fill = parsePaint(props.get("fill"), style.fill);
            }
            if (props.containsKey("stroke")) {
                style.stroke = parsePaint(props.get("stroke"), style.stroke);
            }
            if (props.containsKey("stroke-width")) {
                double width = length(props.get("stroke-width"), style.strokeWidth);
                if (Double.isFinite(width) && width >= 0) {
                    style.strokeWidth = width;
                }
            }
            if (props.containsKey("opacity")) {
                style.opacity *= opacity(props.get("opacity"));
            }
            if (props.containsKey("fill-opacity")) {
                style.fillOpacity = opacity(props.get("fill-opacity"));
            }
            if (props.containsKey("stroke-opacity")) {
                style.strokeOpacity = opacity(props.get("stroke-opacity"));
            }
            return style;
        }

        private Paint parsePaint(String value, Paint inherited) {
            String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (v.isEmpty() || v.equals("inherit")) {
                return inherited;
            }
            if (v.equals("none") || v.equals("transparent")) {
                return Paint.NONE;
            }
            if (v.equals("currentcolor")) {
                return Paint.BLACK;
            }
            if (v.startsWith("#")) {
                Paint hex = parseHexColor(v.substring(1));
                if (hex != null) {
                    return hex;
                }
            } else if (v.startsWith("rgb")) {
                Paint rgb = parseRgbFunction(v);
                if (rgb != null) {
                    return rgb;
                }
            } else if (v.startsWith("hsl")) {
                Paint hsl = parseHslFunction(v);
                if (hsl != null) {
                    return hsl;
                }
            } else if (v.startsWith("url(")) {
                return new Paint(UNKNOWN_PAINT_INK, 1.0);
            } else {
                Integer named = NAMED_COLORS.get(v.replace(" ", ""));
                if (named != null) {
                    return new Paint(inkOf(named >> 16 & 0xFF, named >> 8 & 0xFF, named & 0xFF), 1.0);
                }
            }
            unknownPaints.add(value.trim());
            return new Paint(UNKNOWN_PAINT_INK, 1.0);
        }

        private void addShape(String name, Map<String, String> attrs, Frame parent) {
            if (shapes.size() >= MAX_SHAPES) {
                capsTripped = true;
                return;
            }
            Style style = resolveStyle(parent.style, attrs);
            AffineTransform ctm = new AffineTransform(parent.ctm);
            ctm.concatenate(parseTransform(attrs.get("transform"), warnings));
            double refWidth = viewBox != null ? viewBox.getWidth() : 100.0;
            double refHeight = viewBox != null ? viewBox.getHeight() : 100.0;
            java.awt.Shape geometry = switch (name) {
                case "rect" -> rect(attrs, refWidth, refHeight);
                case "circle" -> {
                    double cx = length(attrs.get("cx"), refWidth);
                    double cy = length(attrs.get("cy"), refHeight);
                    double r = length(attrs.get("r"), refWidth);
                    yield r > 0 ? new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r) : null;
                }
                case "ellipse" -> {
                    double cx = length(attrs.get("cx"), refWidth);
                    double cy = length(attrs.get("cy"), refHeight);
                    double rx = length(attrs.get("rx"), refWidth);
                    double ry = length(attrs.get("ry"), refHeight);
                    if (Double.isNaN(rx)) {
                        rx = ry;
                    }
                    if (Double.isNaN(ry)) {
                        ry = rx;
                    }
                    yield rx > 0 && ry > 0 ? new Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry) : null;
                }
                case "line" -> new Line2D.Double(
                    length(attrs.get("x1"), refWidth), length(attrs.get("y1"), refHeight),
                    length(attrs.get("x2"), refWidth), length(attrs.get("y2"), refHeight));
                case "polyline" -> polyline(attrs.get("points"), false);
                case "polygon" -> polyline(attrs.get("points"), true);
                case "path" -> path(attrs.get("d"));
                default -> null;
            };
            if (geometry == null) {
                droppedElements++;
                return;
            }
            Rectangle2D bounds = geometry.getBounds2D();
            if (!isFinite(bounds) || exceedsRange(bounds)) {
                droppedElements++;
                warnings.add("A <" + name + "> with unreadable or out-of-range coordinates was dropped.");
                return;
            }
            shapes.add(new Shape(
                name, geometry, ctm,
                style.fill.ink(), style.fill.alpha() * style.fillOpacity * style.opacity,
                style.stroke.ink(), style.stroke.alpha() * style.strokeOpacity * style.opacity,
                style.strokeWidth));
        }

        private java.awt.Shape rect(Map<String, String> attrs, double refWidth, double refHeight) {
            double x = length(attrs.get("x"), refWidth);
            double y = length(attrs.get("y"), refHeight);
            double w = length(attrs.get("width"), refWidth);
            double h = length(attrs.get("height"), refHeight);
            if (Double.isNaN(x)) {
                x = 0;
            }
            if (Double.isNaN(y)) {
                y = 0;
            }
            if (!(w > 0) || !(h > 0)) {
                return null;
            }
            double rx = length(attrs.get("rx"), refWidth);
            double ry = length(attrs.get("ry"), refHeight);
            if (Double.isNaN(rx)) {
                rx = ry;
            }
            if (Double.isNaN(ry)) {
                ry = rx;
            }
            if (rx > 0 && ry > 0) {
                rx = Math.min(rx, w / 2);
                ry = Math.min(ry, h / 2);
                return new RoundRectangle2D.Double(x, y, w, h, 2 * rx, 2 * ry);
            }
            return new Rectangle2D.Double(x, y, w, h);
        }

        private java.awt.Shape polyline(String points, boolean close) {
            double[] values = numbers(points);
            int pairs = values.length / 2;
            if (pairs < 2) {
                return null;
            }
            Path2D.Double path = new Path2D.Double();
            path.moveTo(values[0], values[1]);
            for (int i = 1; i < pairs; i++) {
                path.lineTo(values[2 * i], values[2 * i + 1]);
            }
            if (close) {
                path.closePath();
            }
            return path;
        }

        private java.awt.Shape path(String d) {
            if (d == null || d.isBlank()) {
                return null;
            }
            PathParser parser = new PathParser(d, MAX_PATH_COMMANDS_TOTAL - totalPathCommands);
            Path2D.Double path = parser.parse();
            totalPathCommands += parser.commands;
            if (parser.capTripped) {
                capsTripped = true;
            }
            if (parser.error != null) {
                pathErrors.add(parser.error);
            }
            if (parser.segments == 0) {
                if (parser.error == null) {
                    warnings.add("A <path> without a drawable segment was dropped.");
                }
                return null;
            }
            return path;
        }
    }

    // ---- Path data ----

    /**
     * Reads SVG path data with the tolerance the spec asks of renderers: everything up to the first
     * unreadable token is kept, the rest is dropped and quoted in {@link #error}.
     */
    private static final class PathParser {
        private final String d;
        private final int remainingBudget;
        private int pos;
        private final Path2D.Double path = new Path2D.Double();
        private double cx;
        private double cy;
        private double startX;
        private double startY;
        private double controlX;
        private double controlY;
        private char lastCommand;
        private boolean open;
        int commands;
        int segments;
        boolean capTripped;
        String error;

        PathParser(String d, int remainingBudget) {
            this.d = d;
            this.remainingBudget = remainingBudget;
        }

        Path2D.Double parse() {
            char command = 0;
            while (true) {
                skipSeparators();
                if (pos >= d.length()) {
                    break;
                }
                char c = d.charAt(pos);
                if (Character.isLetter(c)) {
                    if ("MLHVCSQTAZmlhvcsqtaz".indexOf(c) < 0) {
                        fail("unknown command '" + c + "'");
                        break;
                    }
                    command = c;
                    pos++;
                } else if (command == 0) {
                    fail("path data must start with a command");
                    break;
                } else if (command == 'Z' || command == 'z') {
                    fail("coordinates after Z without a command");
                    break;
                } else if (command == 'M') {
                    command = 'L';
                } else if (command == 'm') {
                    command = 'l';
                }
                if (commands >= MAX_PATH_COMMANDS_PER_PATH || commands >= remainingBudget) {
                    capTripped = true;
                    break;
                }
                commands++;
                if (!apply(command)) {
                    break;
                }
            }
            return path;
        }

        private boolean apply(char command) {
            boolean relative = Character.isLowerCase(command);
            char upper = Character.toUpperCase(command);
            if (!open && upper != 'M') {
                return fail("path data must start with M");
            }
            switch (upper) {
                case 'M' -> {
                    double x = number();
                    double y = number();
                    if (unreadable(x, y)) {
                        return fail("expected two coordinates after M");
                    }
                    if (relative) {
                        x += cx;
                        y += cy;
                    }
                    path.moveTo(x, y);
                    cx = startX = x;
                    cy = startY = y;
                    open = true;
                }
                case 'L' -> {
                    double x = number();
                    double y = number();
                    if (unreadable(x, y)) {
                        return fail("expected two coordinates after L");
                    }
                    lineTo(relative ? cx + x : x, relative ? cy + y : y);
                }
                case 'H' -> {
                    double x = number();
                    if (Double.isNaN(x)) {
                        return fail("expected a coordinate after H");
                    }
                    lineTo(relative ? cx + x : x, cy);
                }
                case 'V' -> {
                    double y = number();
                    if (Double.isNaN(y)) {
                        return fail("expected a coordinate after V");
                    }
                    lineTo(cx, relative ? cy + y : y);
                }
                case 'C' -> {
                    double[] p = numbers(6);
                    if (p == null) {
                        return fail("expected six coordinates after C");
                    }
                    if (relative) {
                        offset(p);
                    }
                    curveTo(p[0], p[1], p[2], p[3], p[4], p[5]);
                }
                case 'S' -> {
                    double[] p = numbers(4);
                    if (p == null) {
                        return fail("expected four coordinates after S");
                    }
                    if (relative) {
                        offset(p);
                    }
                    boolean smooth = lastCommand == 'C' || lastCommand == 'S';
                    double c1x = smooth ? 2 * cx - controlX : cx;
                    double c1y = smooth ? 2 * cy - controlY : cy;
                    curveTo(c1x, c1y, p[0], p[1], p[2], p[3]);
                }
                case 'Q' -> {
                    double[] p = numbers(4);
                    if (p == null) {
                        return fail("expected four coordinates after Q");
                    }
                    if (relative) {
                        offset(p);
                    }
                    quadTo(p[0], p[1], p[2], p[3]);
                }
                case 'T' -> {
                    double[] p = numbers(2);
                    if (p == null) {
                        return fail("expected two coordinates after T");
                    }
                    if (relative) {
                        offset(p);
                    }
                    boolean smooth = lastCommand == 'Q' || lastCommand == 'T';
                    double c1x = smooth ? 2 * cx - controlX : cx;
                    double c1y = smooth ? 2 * cy - controlY : cy;
                    quadTo(c1x, c1y, p[0], p[1]);
                }
                case 'A' -> {
                    double rx = number();
                    double ry = number();
                    double rotation = number();
                    int largeArc = flag();
                    int sweep = flag();
                    double x = number();
                    double y = number();
                    if (Double.isNaN(rx) || Double.isNaN(ry) || Double.isNaN(rotation)
                        || largeArc < 0 || sweep < 0 || unreadable(x, y)) {
                        return fail("expected rx ry rotation large-arc-flag sweep-flag x y after A");
                    }
                    if (relative) {
                        x += cx;
                        y += cy;
                    }
                    arcTo(rx, ry, rotation, largeArc == 1, sweep == 1, x, y);
                }
                case 'Z' -> {
                    path.closePath();
                    cx = startX;
                    cy = startY;
                    segments++;
                }
                default -> {
                    return fail("unknown command '" + command + "'");
                }
            }
            lastCommand = upper;
            return true;
        }

        private void lineTo(double x, double y) {
            path.lineTo(x, y);
            cx = x;
            cy = y;
            segments++;
        }

        private void curveTo(double c1x, double c1y, double c2x, double c2y, double x, double y) {
            path.curveTo(c1x, c1y, c2x, c2y, x, y);
            controlX = c2x;
            controlY = c2y;
            cx = x;
            cy = y;
            segments++;
        }

        private void quadTo(double c1x, double c1y, double x, double y) {
            path.quadTo(c1x, c1y, x, y);
            controlX = c1x;
            controlY = c1y;
            cx = x;
            cy = y;
            segments++;
        }

        /**
         * Converts an SVG elliptical arc to cubic Béziers following the endpoint-to-centre
         * conversion of the SVG implementation notes (F.6.5) including the radii correction
         * (F.6.6). {@code Arc2D} is avoided on purpose: it cannot express the rotation or the
         * large-arc/sweep flag pair without the same computation, and cubic pieces of at most a
         * quarter turn keep the outline within a pixel at every raster size used here.
         */
        private void arcTo(double rx, double ry, double rotationDeg, boolean largeArc, boolean sweep,
                double x2, double y2) {
            double x1 = cx;
            double y1 = cy;
            if (x1 == x2 && y1 == y2) {
                return;
            }
            rx = Math.abs(rx);
            ry = Math.abs(ry);
            if (rx == 0 || ry == 0) {
                lineTo(x2, y2);
                return;
            }
            double phi = Math.toRadians(rotationDeg);
            double cosPhi = Math.cos(phi);
            double sinPhi = Math.sin(phi);
            double dx2 = (x1 - x2) / 2.0;
            double dy2 = (y1 - y2) / 2.0;
            double x1p = cosPhi * dx2 + sinPhi * dy2;
            double y1p = -sinPhi * dx2 + cosPhi * dy2;
            double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
            if (lambda > 1) {
                double s = Math.sqrt(lambda);
                rx *= s;
                ry *= s;
            }
            double rx2 = rx * rx;
            double ry2 = ry * ry;
            double numerator = rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p;
            double denominator = rx2 * y1p * y1p + ry2 * x1p * x1p;
            double coefficient = denominator == 0 ? 0 : Math.sqrt(Math.max(0, numerator / denominator));
            if (largeArc == sweep) {
                coefficient = -coefficient;
            }
            double cxp = coefficient * (rx * y1p / ry);
            double cyp = coefficient * (-ry * x1p / rx);
            double centerX = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2.0;
            double centerY = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2.0;
            double ux = (x1p - cxp) / rx;
            double uy = (y1p - cyp) / ry;
            double vx = (-x1p - cxp) / rx;
            double vy = (-y1p - cyp) / ry;
            double theta1 = angleBetween(1, 0, ux, uy);
            double delta = angleBetween(ux, uy, vx, vy);
            if (!sweep && delta > 0) {
                delta -= 2 * Math.PI;
            } else if (sweep && delta < 0) {
                delta += 2 * Math.PI;
            }
            int pieces = Math.max(1, (int) Math.ceil(Math.abs(delta) / ARC_MAX_SWEEP - 1e-9));
            double step = delta / pieces;
            double t = 4.0 / 3.0 * Math.tan(step / 4.0);
            double a1 = theta1;
            for (int i = 0; i < pieces; i++) {
                double a2 = a1 + step;
                double cos1 = Math.cos(a1);
                double sin1 = Math.sin(a1);
                double cos2 = Math.cos(a2);
                double sin2 = Math.sin(a2);
                double p1x = centerX + rx * cos1 * cosPhi - ry * sin1 * sinPhi;
                double p1y = centerY + rx * cos1 * sinPhi + ry * sin1 * cosPhi;
                double p2x = centerX + rx * cos2 * cosPhi - ry * sin2 * sinPhi;
                double p2y = centerY + rx * cos2 * sinPhi + ry * sin2 * cosPhi;
                double d1x = -rx * sin1 * cosPhi - ry * cos1 * sinPhi;
                double d1y = -rx * sin1 * sinPhi + ry * cos1 * cosPhi;
                double d2x = -rx * sin2 * cosPhi - ry * cos2 * sinPhi;
                double d2y = -rx * sin2 * sinPhi + ry * cos2 * cosPhi;
                double endX = i == pieces - 1 ? x2 : p2x;
                double endY = i == pieces - 1 ? y2 : p2y;
                path.curveTo(p1x + t * d1x, p1y + t * d1y, endX - t * d2x, endY - t * d2y, endX, endY);
                segments++;
                a1 = a2;
            }
            controlX = x2;
            controlY = y2;
            cx = x2;
            cy = y2;
        }

        private static double angleBetween(double ux, double uy, double vx, double vy) {
            double dot = ux * vx + uy * vy;
            double len = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy));
            double cos = len == 0 ? 1 : Math.max(-1, Math.min(1, dot / len));
            double angle = Math.acos(cos);
            return ux * vy - uy * vx < 0 ? -angle : angle;
        }

        private void offset(double[] p) {
            for (int i = 0; i + 1 < p.length; i += 2) {
                p[i] += cx;
                p[i + 1] += cy;
            }
        }

        private double[] numbers(int count) {
            double[] values = new double[count];
            for (int i = 0; i < count; i++) {
                values[i] = number();
                if (Double.isNaN(values[i])) {
                    return null;
                }
            }
            return values;
        }

        private static boolean unreadable(double x, double y) {
            return Double.isNaN(x) || Double.isNaN(y);
        }

        private boolean fail(String reason) {
            if (error == null) {
                error = PATH_ERROR_PREFIX + " (" + reason + " at character " + pos + "): d=\"" + quote(d) + "\"";
            }
            return false;
        }

        private void skipSeparators() {
            while (pos < d.length()) {
                char c = d.charAt(pos);
                if (Character.isWhitespace(c) || c == ',') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        /** Reads one number; returns NaN (without consuming anything) when none starts here. */
        private double number() {
            skipSeparators();
            int start = pos;
            int n = d.length();
            if (pos < n && (d.charAt(pos) == '+' || d.charAt(pos) == '-')) {
                pos++;
            }
            int digits = 0;
            while (pos < n && Character.isDigit(d.charAt(pos))) {
                pos++;
                digits++;
            }
            if (pos < n && d.charAt(pos) == '.') {
                pos++;
                while (pos < n && Character.isDigit(d.charAt(pos))) {
                    pos++;
                    digits++;
                }
            }
            if (digits == 0) {
                pos = start;
                return Double.NaN;
            }
            if (pos < n && (d.charAt(pos) == 'e' || d.charAt(pos) == 'E')) {
                int save = pos;
                pos++;
                if (pos < n && (d.charAt(pos) == '+' || d.charAt(pos) == '-')) {
                    pos++;
                }
                int exponentStart = pos;
                while (pos < n && Character.isDigit(d.charAt(pos))) {
                    pos++;
                }
                if (pos == exponentStart) {
                    pos = save;
                }
            }
            try {
                return Double.parseDouble(d.substring(start, pos));
            } catch (NumberFormatException e) {
                pos = start;
                return Double.NaN;
            }
        }

        /** An arc flag is a single '0' or '1' that may be glued to the next number ("0110 10"). */
        private int flag() {
            skipSeparators();
            if (pos < d.length() && (d.charAt(pos) == '0' || d.charAt(pos) == '1')) {
                return d.charAt(pos++) - '0';
            }
            return -1;
        }
    }

    // ---- Attribute helpers ----

    /** Parses a transform list left to right, so later functions apply first, as in SVG. */
    static AffineTransform parseTransform(String value, List<String> warnings) {
        AffineTransform result = new AffineTransform();
        if (value == null || value.isBlank()) {
            return result;
        }
        int pos = 0;
        int n = value.length();
        while (pos < n) {
            while (pos < n && (Character.isWhitespace(value.charAt(pos)) || value.charAt(pos) == ',')) {
                pos++;
            }
            int nameStart = pos;
            while (pos < n && Character.isLetter(value.charAt(pos))) {
                pos++;
            }
            if (pos == nameStart) {
                pos++;
                continue;
            }
            String name = value.substring(nameStart, pos).toLowerCase(Locale.ROOT);
            while (pos < n && Character.isWhitespace(value.charAt(pos))) {
                pos++;
            }
            if (pos >= n || value.charAt(pos) != '(') {
                warnings.add("Unreadable transform \"" + quote(value.trim()) + "\" was ignored from \""
                    + quote(name) + "\" on.");
                break;
            }
            int close = value.indexOf(')', pos);
            double[] a = numbers(close < 0 ? value.substring(pos + 1) : value.substring(pos + 1, close));
            pos = close < 0 ? n : close + 1;
            if (!isFinite(a)) {
                warnings.add("Transform \"" + quote(name) + "\" with unreadable numbers was ignored.");
                continue;
            }
            switch (name) {
                case "translate" -> {
                    if (a.length >= 1) {
                        result.translate(a[0], a.length > 1 ? a[1] : 0);
                    }
                }
                case "scale" -> {
                    if (a.length >= 1) {
                        result.scale(a[0], a.length > 1 ? a[1] : a[0]);
                    }
                }
                case "rotate" -> {
                    if (a.length >= 3) {
                        result.rotate(Math.toRadians(a[0]), a[1], a[2]);
                    } else if (a.length >= 1) {
                        result.rotate(Math.toRadians(a[0]));
                    }
                }
                case "skewx" -> {
                    if (a.length >= 1) {
                        result.shear(Math.tan(Math.toRadians(a[0])), 0);
                    }
                }
                case "skewy" -> {
                    if (a.length >= 1) {
                        result.shear(0, Math.tan(Math.toRadians(a[0])));
                    }
                }
                case "matrix" -> {
                    if (a.length >= 6) {
                        result.concatenate(new AffineTransform(a[0], a[1], a[2], a[3], a[4], a[5]));
                    }
                }
                default -> warnings.add("Unknown transform \"" + name + "\" was ignored.");
            }
        }
        return result;
    }

    /**
     * A length attribute as a number: units are dropped, a percentage is taken of {@code reference}.
     * NaN when absent or unreadable.
     */
    static double length(String value, double reference) {
        if (value == null) {
            return Double.NaN;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return Double.NaN;
        }
        int end = 0;
        while (end < v.length() && "+-.0123456789eE".indexOf(v.charAt(end)) >= 0) {
            end++;
        }
        // An exponent letter directly before a unit ("1em") is the unit, not an exponent.
        while (end > 0 && (v.charAt(end - 1) == 'e' || v.charAt(end - 1) == 'E')) {
            end--;
        }
        if (end == 0) {
            return Double.NaN;
        }
        double number;
        try {
            number = Double.parseDouble(v.substring(0, end));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        if (v.endsWith("%")) {
            return Double.isNaN(reference) ? number : number * reference / 100.0;
        }
        return number;
    }

    private static double opacity(String value) {
        double v = length(value, 1.0);
        if (Double.isNaN(v)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** All numbers in a whitespace/comma separated list; "10-5" and ".5.5" split like path data. */
    static double[] numbers(String list) {
        if (list == null || list.isBlank()) {
            return new double[0];
        }
        PathParser reader = new PathParser(list, Integer.MAX_VALUE);
        List<Double> values = new ArrayList<>();
        while (true) {
            double v = reader.number();
            if (Double.isNaN(v)) {
                reader.skipSeparators();
                if (reader.pos >= list.length()) {
                    break;
                }
                reader.pos++; // skip a character that is not part of a number
                continue;
            }
            values.add(v);
        }
        double[] result = new double[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    // ---- Colours ----

    /** Ink (0 white … 1 black) of an sRGB triple by its luma, rounded so white is exactly 0. */
    static double inkOf(int r, int g, int b) {
        double luma = (LUMA_RED * r + LUMA_GREEN * g + LUMA_BLUE * b) / 255.0;
        double ink = Math.round((1.0 - luma) * INK_PRECISION) / INK_PRECISION;
        return Math.max(0.0, Math.min(1.0, ink));
    }

    private static Paint parseHexColor(String hex) {
        int len = hex.length();
        if (len != 3 && len != 4 && len != 6 && len != 8) {
            return null;
        }
        for (int i = 0; i < len; i++) {
            if (Character.digit(hex.charAt(i), 16) < 0) {
                return null;
            }
        }
        int r;
        int g;
        int b;
        int a = 255;
        if (len <= 4) {
            r = Character.digit(hex.charAt(0), 16) * 17;
            g = Character.digit(hex.charAt(1), 16) * 17;
            b = Character.digit(hex.charAt(2), 16) * 17;
            if (len == 4) {
                a = Character.digit(hex.charAt(3), 16) * 17;
            }
        } else {
            r = Integer.parseInt(hex.substring(0, 2), 16);
            g = Integer.parseInt(hex.substring(2, 4), 16);
            b = Integer.parseInt(hex.substring(4, 6), 16);
            if (len == 8) {
                a = Integer.parseInt(hex.substring(6, 8), 16);
            }
        }
        return new Paint(inkOf(r, g, b), a / 255.0);
    }

    private static Paint parseRgbFunction(String v) {
        String[] parts = functionArguments(v);
        if (parts == null || parts.length < 3) {
            return null;
        }
        int[] rgb = new int[3];
        for (int i = 0; i < 3; i++) {
            double c = length(parts[i], 255.0);
            if (Double.isNaN(c)) {
                return null;
            }
            rgb[i] = (int) Math.round(Math.max(0, Math.min(255, c)));
        }
        double alpha = parts.length > 3 ? alphaArgument(parts[3]) : 1.0;
        return new Paint(inkOf(rgb[0], rgb[1], rgb[2]), alpha);
    }

    private static Paint parseHslFunction(String v) {
        String[] parts = functionArguments(v);
        if (parts == null || parts.length < 3) {
            return null;
        }
        double h = length(parts[0], 360.0);
        double s = length(parts[1], 1.0);
        double l = length(parts[2], 1.0);
        if (Double.isNaN(h) || Double.isNaN(s) || Double.isNaN(l)) {
            return null;
        }
        s = Math.max(0, Math.min(1, s));
        l = Math.max(0, Math.min(1, l));
        double c = (1 - Math.abs(2 * l - 1)) * s;
        double hp = ((h % 360) + 360) % 360 / 60.0;
        double x = c * (1 - Math.abs(hp % 2 - 1));
        double r1;
        double g1;
        double b1;
        if (hp < 1) {
            r1 = c; g1 = x; b1 = 0;
        } else if (hp < 2) {
            r1 = x; g1 = c; b1 = 0;
        } else if (hp < 3) {
            r1 = 0; g1 = c; b1 = x;
        } else if (hp < 4) {
            r1 = 0; g1 = x; b1 = c;
        } else if (hp < 5) {
            r1 = x; g1 = 0; b1 = c;
        } else {
            r1 = c; g1 = 0; b1 = x;
        }
        double m = l - c / 2;
        double alpha = parts.length > 3 ? alphaArgument(parts[3]) : 1.0;
        return new Paint(inkOf(
            (int) Math.round((r1 + m) * 255), (int) Math.round((g1 + m) * 255), (int) Math.round((b1 + m) * 255)),
            alpha);
    }

    private static String[] functionArguments(String v) {
        int open = v.indexOf('(');
        int close = v.lastIndexOf(')');
        if (open < 0) {
            return null;
        }
        String inner = close > open ? v.substring(open + 1, close) : v.substring(open + 1);
        String[] parts = inner.trim().split("[\\s,/]+");
        return parts.length == 1 && parts[0].isEmpty() ? null : parts;
    }

    private static double alphaArgument(String part) {
        double a = length(part, 1.0);
        return Double.isNaN(a) ? 1.0 : Math.max(0, Math.min(1, a));
    }

    private static Map<String, Integer> namedColors() {
        Map<String, Integer> m = new HashMap<>();
        m.put("black", 0x000000);
        m.put("white", 0xFFFFFF);
        m.put("gray", 0x808080);
        m.put("grey", 0x808080);
        m.put("silver", 0xC0C0C0);
        m.put("darkgray", 0xA9A9A9);
        m.put("darkgrey", 0xA9A9A9);
        m.put("dimgray", 0x696969);
        m.put("dimgrey", 0x696969);
        m.put("lightgray", 0xD3D3D3);
        m.put("lightgrey", 0xD3D3D3);
        m.put("gainsboro", 0xDCDCDC);
        m.put("whitesmoke", 0xF5F5F5);
        m.put("slategray", 0x708090);
        m.put("slategrey", 0x708090);
        m.put("darkslategray", 0x2F4F4F);
        m.put("darkslategrey", 0x2F4F4F);
        m.put("lightslategray", 0x778899);
        m.put("lightslategrey", 0x778899);
        m.put("red", 0xFF0000);
        m.put("darkred", 0x8B0000);
        m.put("crimson", 0xDC143C);
        m.put("firebrick", 0xB22222);
        m.put("tomato", 0xFF6347);
        m.put("coral", 0xFF7F50);
        m.put("salmon", 0xFA8072);
        m.put("orange", 0xFFA500);
        m.put("darkorange", 0xFF8C00);
        m.put("gold", 0xFFD700);
        m.put("yellow", 0xFFFF00);
        m.put("khaki", 0xF0E68C);
        m.put("green", 0x008000);
        m.put("darkgreen", 0x006400);
        m.put("forestgreen", 0x228B22);
        m.put("seagreen", 0x2E8B57);
        m.put("lime", 0x00FF00);
        m.put("limegreen", 0x32CD32);
        m.put("lightgreen", 0x90EE90);
        m.put("olive", 0x808000);
        m.put("teal", 0x008080);
        m.put("cyan", 0x00FFFF);
        m.put("aqua", 0x00FFFF);
        m.put("turquoise", 0x40E0D0);
        m.put("blue", 0x0000FF);
        m.put("navy", 0x000080);
        m.put("darkblue", 0x00008B);
        m.put("midnightblue", 0x191970);
        m.put("royalblue", 0x4169E1);
        m.put("steelblue", 0x4682B4);
        m.put("dodgerblue", 0x1E90FF);
        m.put("skyblue", 0x87CEEB);
        m.put("lightblue", 0xADD8E6);
        m.put("purple", 0x800080);
        m.put("indigo", 0x4B0082);
        m.put("violet", 0xEE82EE);
        m.put("magenta", 0xFF00FF);
        m.put("fuchsia", 0xFF00FF);
        m.put("pink", 0xFFC0CB);
        m.put("plum", 0xDDA0DD);
        m.put("orchid", 0xDA70D6);
        m.put("lavender", 0xE6E6FA);
        m.put("brown", 0xA52A2A);
        m.put("maroon", 0x800000);
        m.put("chocolate", 0xD2691E);
        m.put("saddlebrown", 0x8B4513);
        m.put("sienna", 0xA0522D);
        m.put("peru", 0xCD853F);
        m.put("sandybrown", 0xF4A460);
        m.put("tan", 0xD2B48C);
        m.put("wheat", 0xF5DEB3);
        m.put("beige", 0xF5F5DC);
        m.put("ivory", 0xFFFFF0);
        m.put("snow", 0xFFFAFA);
        m.put("linen", 0xFAF0E6);
        m.put("goldenrod", 0xDAA520);
        m.put("darkgoldenrod", 0xB8860B);
        return Collections.unmodifiableMap(m);
    }

    // ---- Small helpers ----

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ':' || c == '.';
    }

    /** An attribute value for a warning, cut to {@link #WARNING_QUOTE_LENGTH} with an ellipsis. */
    static String quote(String value) {
        return value.length() > WARNING_QUOTE_LENGTH ? value.substring(0, WARNING_QUOTE_LENGTH) + "…" : value;
    }

    /** {@code svg:rect} → {@code rect}; element and attribute names are matched case-insensitively. */
    private static String localName(String qualified) {
        int colon = qualified.lastIndexOf(':');
        String local = colon >= 0 ? qualified.substring(colon + 1) : qualified;
        return local.toLowerCase(Locale.ROOT);
    }

    private static boolean isFinite(double[] values) {
        for (double v : values) {
            if (!Double.isFinite(v)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFinite(Rectangle2D r) {
        return Double.isFinite(r.getX()) && Double.isFinite(r.getY())
            && Double.isFinite(r.getWidth()) && Double.isFinite(r.getHeight());
    }

    private static boolean exceedsRange(Rectangle2D r) {
        return Math.abs(r.getMinX()) > MAX_COORDINATE || Math.abs(r.getMaxX()) > MAX_COORDINATE
            || Math.abs(r.getMinY()) > MAX_COORDINATE || Math.abs(r.getMaxY()) > MAX_COORDINATE;
    }
}
