package de.kortty.core;

import de.kortty.ui.I18n;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.PageMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reusable PDFBox primitives for korTTY's report-style PDF exports: the bundled Noto fonts with
 * their symbol/emoji fallbacks, glyph-aware measuring and wrapping, drawing helpers, a page flow
 * with keep-together blocks, and the shared page chrome (continuation header, branded footer,
 * watermark), outline and metadata.
 *
 * <p>The helpers started life as the private methods of {@link AiChatExportService} and were
 * generalised here so the code-analysis reports do not become the fifth divergent copy. The chat,
 * session-journal and agent-activity exporters still carry their own copies for now; migrating
 * them onto this kit is a separate task.</p>
 *
 * <p>All coordinates are PDF user-space points with the origin at the bottom-left of the page;
 * "top" arguments name the upper edge of the thing being drawn.</p>
 */
public final class PdfReportKit {

    public static final float DEFAULT_MARGIN = 48f;
    /** Distance from the page's top edge to the first baseline area. */
    public static final float DEFAULT_TOP_INSET = 72f;
    /** Distance from the page's bottom edge below which nothing but the footer is drawn. */
    public static final float DEFAULT_BOTTOM_INSET = 62f;
    public static final Color MUTED_TEXT = new Color(0x5E, 0x6E, 0x82);
    public static final Color RULE = new Color(0xD4, 0xDE, 0xEA);

    private static final String SANS_FONT_RESOURCE = "/fonts/noto/NotoSans-Regular.ttf";
    private static final String SANS_BOLD_FONT_RESOURCE = "/fonts/noto/NotoSans-Bold.ttf";
    private static final String MONO_FONT_RESOURCE = "/fonts/noto/NotoSansMono-Regular.ttf";
    private static final String SYMBOLS_FONT_RESOURCE = "/fonts/noto/NotoSansSymbols-Variable.ttf";
    private static final String SYMBOLS2_FONT_RESOURCE = "/fonts/noto/NotoSansSymbols2-Regular.ttf";
    private static final String EMOJI_FONT_RESOURCE = "/fonts/noto/NotoEmoji-Variable.ttf";
    private static final String ELLIPSIS = "…";
    private static final float CHROME_FONT_SIZE = 8.6f;
    private static final float HEADER_FONT_SIZE = 8.8f;
    /** Control-point distance for a quarter circle drawn as one cubic Bezier. */
    private static final float BEZIER_QUARTER = 0.5523f;
    private static final float SVG_ICON_UNITS = 16f;
    private static final Pattern SVG_PATH_TOKEN = Pattern.compile(
        "([A-Za-z])|([-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)");
    private static final String SVG_PATH_COMMANDS = "MmLlHhVvAaZz";
    /**
     * ASCII stand-ins for the mathematical operators AI text likes to use and none of the six
     * bundled Noto faces covers (the Mathematical Operators block is not in Noto Sans, Symbols or
     * Symbols2). They read correctly in a report where a {@code ?} would not.
     */
    private static final Map<Integer, String> ASCII_FALLBACKS = Map.ofEntries(
        Map.entry(0x2265, ">="),   // ≥
        Map.entry(0x2264, "<="),   // ≤
        Map.entry(0x2260, "!="),   // ≠
        Map.entry(0x2248, "~"),    // ≈
        Map.entry(0x2261, "=="),   // ≡
        Map.entry(0x2217, "*"),    // ∗
        Map.entry(0x21D2, "=>"),   // ⇒
        Map.entry(0x21D0, "<="),   // ⇐
        Map.entry(0x21D4, "<=>"),  // ⇔
        Map.entry(0x27E8, "<"),    // ⟨
        Map.entry(0x27E9, ">"));   // ⟩

    private PdfReportKit() {
    }

    // ---- fonts ---------------------------------------------------------------------------------

    /** A primary font plus the fallbacks consulted, in order, for glyphs it does not have. */
    public record FontFamily(PDFont primary, PDFont symbols, PDFont symbols2, PDFont emoji) {
    }

    public record Fonts(FontFamily sans, FontFamily bold, FontFamily mono) {
    }

    /** A maximal run of consecutive glyphs drawn with one font. */
    public record TextRun(PDFont font, String text) {
    }

    /** Loads the six bundled Noto faces into {@code document}; the fallbacks are shared by all three families. */
    public static Fonts loadFonts(PDDocument document) throws IOException {
        PDFont symbols = loadFont(document, SYMBOLS_FONT_RESOURCE);
        PDFont symbols2 = loadFont(document, SYMBOLS2_FONT_RESOURCE);
        PDFont emoji = loadFont(document, EMOJI_FONT_RESOURCE);
        return new Fonts(
            new FontFamily(loadFont(document, SANS_FONT_RESOURCE), symbols, symbols2, emoji),
            new FontFamily(loadFont(document, SANS_BOLD_FONT_RESOURCE), symbols, symbols2, emoji),
            new FontFamily(loadFont(document, MONO_FONT_RESOURCE), symbols, symbols2, emoji));
    }

    private static PDType0Font loadFont(PDDocument document, String resourcePath) throws IOException {
        try (InputStream inputStream = PdfReportKit.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Missing PDF font resource " + resourcePath);
            }
            return PDType0Font.load(document, inputStream, true);
        }
    }

    // ---- glyph fallback ------------------------------------------------------------------------

    /**
     * Drops control characters and emoji formatting code points, keeps every glyph one of the
     * family's fonts can draw, spells the few common math operators no bundled font has in ASCII
     * ({@code ≥} becomes {@code >=}), and replaces anything else with {@code ?}.
     */
    public static String prepareText(FontFamily family, String text) throws IOException {
        StringBuilder builder = new StringBuilder();
        String safeText = normalizeText(text);
        for (int index = 0; index < safeText.length(); ) {
            int codePoint = safeText.codePointAt(index);
            String glyph = new String(Character.toChars(codePoint));
            if (isEmojiFormattingCodePoint(codePoint)) {
                // PDFBox does not perform the OpenType shaping required for emoji variation
                // selectors and ZWJ sequences. The visible emoji code points are still retained.
            } else if (Character.isWhitespace(codePoint)) {
                builder.append(glyph);
            } else if (fontForGlyph(family, glyph) != null) {
                builder.append(glyph);
            } else {
                builder.append(ASCII_FALLBACKS.getOrDefault(codePoint, "?"));
            }
            index += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    private static boolean isEmojiFormattingCodePoint(int codePoint) {
        return codePoint == 0x200D || codePoint == 0xFE0E || codePoint == 0xFE0F;
    }

    /** The first font of the family that can encode {@code glyph}, or null when none can. */
    public static PDFont fontForGlyph(FontFamily family, String glyph) throws IOException {
        if (fontWillRender(family.primary(), glyph)) {
            return family.primary();
        }
        if (fontWillRender(family.symbols(), glyph)) {
            return family.symbols();
        }
        if (fontWillRender(family.symbols2(), glyph)) {
            return family.symbols2();
        }
        if (fontWillRender(family.emoji(), glyph)) {
            return family.emoji();
        }
        return null;
    }

    private static boolean fontWillRender(PDFont font, String glyph) throws IOException {
        if (font == null) {
            return false;
        }
        try {
            font.encode(glyph);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** Splits prepared text into runs so each run can be shown with the font that has its glyphs. */
    public static List<TextRun> splitFontRuns(FontFamily family, String text) throws IOException {
        List<TextRun> runs = new ArrayList<>();
        StringBuilder currentText = new StringBuilder();
        PDFont currentFont = null;
        String safeText = text != null ? text : "";
        for (int index = 0; index < safeText.length(); ) {
            int codePoint = safeText.codePointAt(index);
            String glyph = new String(Character.toChars(codePoint));
            PDFont glyphFont = fontForGlyph(family, glyph);
            if (glyphFont == null) {
                glyph = "?";
                glyphFont = family.primary();
            }
            if (currentFont != glyphFont && !currentText.isEmpty()) {
                runs.add(new TextRun(currentFont, currentText.toString()));
                currentText.setLength(0);
            }
            currentFont = glyphFont;
            currentText.append(glyph);
            index += Character.charCount(codePoint);
        }
        if (!currentText.isEmpty()) {
            runs.add(new TextRun(currentFont, currentText.toString()));
        }
        return runs;
    }

    public static float textWidth(FontFamily family, float fontSize, String text) throws IOException {
        String safeText = prepareText(family, text);
        float width = 0f;
        for (TextRun run : splitFontRuns(family, safeText)) {
            width += run.font().getStringWidth(run.text()) / 1000f * fontSize;
        }
        return width;
    }

    /** The text as is when it fits, otherwise the longest prefix that fits with a trailing ellipsis. */
    public static String fit(String text, FontFamily family, float fontSize, float maxWidth) throws IOException {
        String safeText = prepareText(family, text);
        if (textWidth(family, fontSize, safeText) <= maxWidth) {
            return safeText;
        }
        String ellipsis = prepareText(family, ELLIPSIS);
        int codePoints = safeText.codePointCount(0, safeText.length());
        int low = 0;
        int high = codePoints - 1;
        String best = ellipsis;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            String candidate = safeText.substring(0, safeText.offsetByCodePoints(0, mid)).stripTrailing() + ellipsis;
            if (textWidth(family, fontSize, candidate) <= maxWidth) {
                best = candidate;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }

    // ---- wrapping ------------------------------------------------------------------------------

    /**
     * Wraps prose to {@code maxWidth}. Blank lines survive as empty entries, and a line's leading
     * indentation (spaces, or tabs as four spaces) is repeated on every wrapped continuation of
     * that line, so indented bullets and numbered steps keep their shape.
     */
    public static List<String> wrapParagraph(String text, FontFamily family, float fontSize, float maxWidth)
        throws IOException {

        List<String> wrappedLines = new ArrayList<>();
        String normalized = normalizeText(text).replace("\t", "    ");
        for (String rawLine : normalized.split("\n", -1)) {
            int indentEnd = 0;
            while (indentEnd < rawLine.length() && rawLine.charAt(indentEnd) == ' ') {
                indentEnd++;
            }
            String content = rawLine.substring(indentEnd).trim();
            if (content.isEmpty()) {
                wrappedLines.add("");
                continue;
            }
            String indent = rawLine.substring(0, indentEnd);
            float indentWidth = textWidth(family, fontSize, indent);
            if (indentWidth > maxWidth / 2f) {
                // Indentation deeper than half the column would wrap word by word; drop it instead.
                indent = "";
                indentWidth = 0f;
            }
            List<String> lines = new ArrayList<>();
            wrapWordsIntoLines(lines, content, family, fontSize, maxWidth - indentWidth);
            for (String line : lines) {
                wrappedLines.add(indent + line);
            }
        }
        if (wrappedLines.isEmpty()) {
            wrappedLines.add("");
        }
        return wrappedLines;
    }

    private static void wrapWordsIntoLines(
        List<String> wrappedLines,
        String line,
        FontFamily family,
        float fontSize,
        float maxWidth) throws IOException {

        String[] words = line.split("\\s+");
        String currentLine = "";
        for (String rawWord : words) {
            String word = prepareText(family, rawWord);
            String candidate = currentLine.isEmpty() ? word : currentLine + " " + word;
            if (textWidth(family, fontSize, candidate) <= maxWidth) {
                currentLine = candidate;
                continue;
            }

            if (!currentLine.isEmpty()) {
                wrappedLines.add(currentLine);
            }
            // A token wider than the column is hard-broken even when it opens the line.
            if (textWidth(family, fontSize, word) <= maxWidth) {
                currentLine = word;
                continue;
            }

            List<String> hardWrappedWord = breakLongToken(word, family, fontSize, maxWidth);
            wrappedLines.addAll(hardWrappedWord.subList(0, hardWrappedWord.size() - 1));
            currentLine = hardWrappedWord.getLast();
        }
        if (!currentLine.isEmpty()) {
            wrappedLines.add(currentLine);
        }
    }

    /** Wraps code: whitespace is kept verbatim, tabs become four spaces, over-long lines break hard. */
    public static List<String> wrapCode(String text, FontFamily family, float fontSize, float maxWidth)
        throws IOException {

        List<String> wrappedLines = new ArrayList<>();
        String normalized = normalizeText(text).replace("\t", "    ");
        for (String rawLine : normalized.split("\n", -1)) {
            String line = prepareText(family, rawLine);
            if (line.isEmpty()) {
                wrappedLines.add("");
                continue;
            }
            if (textWidth(family, fontSize, line) <= maxWidth) {
                wrappedLines.add(line);
                continue;
            }
            wrappedLines.addAll(breakLongToken(line, family, fontSize, maxWidth));
        }
        if (wrappedLines.isEmpty()) {
            wrappedLines.add("");
        }
        return wrappedLines;
    }

    /** Splits a token that is wider than {@code maxWidth} into the longest fitting pieces, never inside a surrogate pair. */
    public static List<String> breakLongToken(String token, FontFamily family, float fontSize, float maxWidth)
        throws IOException {

        List<String> parts = new ArrayList<>();
        String remaining = token != null ? token : "";
        while (!remaining.isEmpty()) {
            int fittingLength = findLongestFittingLength(remaining, family, fontSize, maxWidth);
            parts.add(remaining.substring(0, fittingLength));
            remaining = remaining.substring(fittingLength);
        }
        if (parts.isEmpty()) {
            parts.add("");
        }
        return parts;
    }

    private static int findLongestFittingLength(String text, FontFamily family, float fontSize, float maxWidth)
        throws IOException {

        int codePointCount = text.codePointCount(0, text.length());
        int low = 1;
        int high = codePointCount;
        int best = text.offsetByCodePoints(0, 1);
        while (low <= high) {
            int mid = (low + high) / 2;
            int endIndex = text.offsetByCodePoints(0, mid);
            String candidate = text.substring(0, endIndex);
            if (textWidth(family, fontSize, candidate) <= maxWidth) {
                best = endIndex;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }

    /** Removes {@code \r} and every control character except newline and tab. */
    static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\r') {
                continue;
            }
            if (character == '\n' || character == '\t' || character >= 32) {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    // ---- drawing primitives --------------------------------------------------------------------

    /** Draws {@code text} with its baseline at {@code y}, switching fonts per glyph run as needed. */
    public static void drawText(
        PDPageContentStream stream,
        FontFamily family,
        float fontSize,
        Color color,
        float x,
        float y,
        String text) throws IOException {

        String safeText = prepareText(family, text);
        stream.beginText();
        stream.setNonStrokingColor(color);
        stream.newLineAtOffset(x, y);
        for (TextRun run : splitFontRuns(family, safeText)) {
            stream.setFont(run.font(), fontSize);
            stream.showText(run.text());
        }
        stream.endText();
    }

    public static void fillRect(PDPageContentStream stream, float x, float y, float width, float height, Color fill)
        throws IOException {

        stream.setNonStrokingColor(fill);
        stream.addRect(x, y, width, height);
        stream.fill();
    }

    public static void strokeRect(
        PDPageContentStream stream,
        float x,
        float y,
        float width,
        float height,
        Color stroke,
        float lineWidth) throws IOException {

        stream.setStrokingColor(stroke);
        stream.setLineWidth(lineWidth);
        stream.addRect(x, y, width, height);
        stream.stroke();
    }

    /**
     * A rectangle with rounded corners (quarter circles as cubic Beziers). Either colour may be
     * null to skip the fill or the stroke.
     */
    public static void roundRect(
        PDPageContentStream stream,
        float x,
        float y,
        float width,
        float height,
        float radius,
        Color fill,
        Color stroke,
        float strokeWidth) throws IOException {

        if (fill == null && stroke == null) {
            return;
        }
        // Colour operators belong outside the path object, so set them before the first move-to.
        if (fill != null) {
            stream.setNonStrokingColor(fill);
        }
        if (stroke != null) {
            stream.setStrokingColor(stroke);
            stream.setLineWidth(strokeWidth);
        }
        float r = Math.max(0f, Math.min(radius, Math.min(width, height) / 2f));
        float k = r * BEZIER_QUARTER;
        float right = x + width;
        float top = y + height;
        stream.moveTo(x + r, y);
        stream.lineTo(right - r, y);
        stream.curveTo(right - r + k, y, right, y + r - k, right, y + r);
        stream.lineTo(right, top - r);
        stream.curveTo(right, top - r + k, right - r + k, top, right - r, top);
        stream.lineTo(x + r, top);
        stream.curveTo(x + r - k, top, x, top - r + k, x, top - r);
        stream.lineTo(x, y + r);
        stream.curveTo(x, y + r - k, x + r - k, y, x + r, y);
        stream.closePath();
        if (fill != null && stroke != null) {
            stream.fillAndStroke();
        } else if (fill != null) {
            stream.fill();
        } else {
            stream.stroke();
        }
    }

    public static void line(
        PDPageContentStream stream,
        float x1,
        float y1,
        float x2,
        float y2,
        Color stroke,
        float lineWidth) throws IOException {

        stream.setStrokingColor(stroke);
        stream.setLineWidth(lineWidth);
        stream.moveTo(x1, y1);
        stream.lineTo(x2, y2);
        stream.stroke();
    }

    /**
     * A filled, fully rounded label ("pill") with its text baseline at {@code baseline}. Returns
     * the pill's width so the caller can place what follows it.
     */
    public static float pill(
        PDPageContentStream stream,
        FontFamily family,
        float fontSize,
        String label,
        Color fill,
        Color textColor,
        float x,
        float baseline) throws IOException {

        String safeLabel = prepareText(family, label);
        float paddingX = Math.max(4f, fontSize * 0.65f);
        float width = textWidth(family, fontSize, safeLabel) + 2f * paddingX;
        float height = fontSize * 1.45f;
        float descent = fontSize * 0.3f;
        roundRect(stream, x, baseline - descent, width, height, height / 2f, fill, null, 0f);
        drawText(stream, family, fontSize, textColor, x + paddingX, baseline, safeLabel);
        return width;
    }

    /**
     * Fills a 16x16-unit SVG path — the {@link AnalysisCategoryVisuals#iconPath} glyphs — scaled
     * into a {@code size} x {@code size} box whose bottom-left corner is {@code (x, y)}. Supports
     * M/L/H/V/A/Z (absolute and relative) including implicit line-to after a move-to; arcs are
     * converted to cubic Beziers. Fills with the even-odd rule, so nested subpaths cut holes exactly
     * as the SVG and JavaFX surfaces render them.
     */
    public static void fillSvgPath(
        PDPageContentStream stream,
        String path,
        float x,
        float y,
        float size,
        Color color) throws IOException {

        float scale = size / SVG_ICON_UNITS;
        stream.setNonStrokingColor(color);
        SvgPathEmitter emitter = new SvgPathEmitter(stream, x, y + size, scale);
        emitter.emit(path);
        if (emitter.hasStarted()) {
            // Also closes a degenerate path (a lone move-to) without painting anything.
            stream.fillEvenOdd();
        }
    }

    /** Parses a subset of SVG path data and writes it into a content stream with a y-flip. */
    private static final class SvgPathEmitter {
        private final PDPageContentStream stream;
        private final float originX;
        private final float originY;
        private final float scale;
        private double currentX;
        private double currentY;
        private double startX;
        private double startY;
        private boolean started;

        private SvgPathEmitter(PDPageContentStream stream, float originX, float originY, float scale) {
            this.stream = stream;
            this.originX = originX;
            this.originY = originY;
            this.scale = scale;
        }

        boolean hasStarted() {
            return started;
        }

        void emit(String path) throws IOException {
            List<String> tokens = new ArrayList<>();
            Matcher matcher = SVG_PATH_TOKEN.matcher(path != null ? path : "");
            while (matcher.find()) {
                tokens.add(matcher.group());
            }
            char command = 0;
            int index = 0;
            while (index < tokens.size()) {
                String token = tokens.get(index);
                if (Character.isLetter(token.charAt(0))) {
                    command = token.charAt(0);
                    if (SVG_PATH_COMMANDS.indexOf(command) < 0) {
                        throw new IllegalArgumentException("Unsupported SVG path command '" + command + "' in: " + path);
                    }
                    index++;
                    if (command == 'Z' || command == 'z') {
                        stream.closePath();
                        currentX = startX;
                        currentY = startY;
                        command = 0;
                    }
                    continue;
                }
                switch (command) {
                    case 'M', 'm' -> {
                        boolean relative = command == 'm';
                        double px = number(tokens, index) + (relative ? currentX : 0);
                        double py = number(tokens, index + 1) + (relative ? currentY : 0);
                        index += 2;
                        moveTo(px, py);
                        // Further coordinate pairs after a move-to are implicit line-to commands.
                        command = relative ? 'l' : 'L';
                    }
                    case 'L', 'l' -> {
                        boolean relative = command == 'l';
                        double px = number(tokens, index) + (relative ? currentX : 0);
                        double py = number(tokens, index + 1) + (relative ? currentY : 0);
                        index += 2;
                        lineTo(px, py);
                    }
                    case 'H', 'h' -> {
                        double px = number(tokens, index) + (command == 'h' ? currentX : 0);
                        index++;
                        lineTo(px, currentY);
                    }
                    case 'V', 'v' -> {
                        double py = number(tokens, index) + (command == 'v' ? currentY : 0);
                        index++;
                        lineTo(currentX, py);
                    }
                    case 'A', 'a' -> {
                        boolean relative = command == 'a';
                        double rx = number(tokens, index);
                        double ry = number(tokens, index + 1);
                        double rotation = number(tokens, index + 2);
                        boolean largeArc = number(tokens, index + 3) != 0;
                        boolean sweep = number(tokens, index + 4) != 0;
                        double px = number(tokens, index + 5) + (relative ? currentX : 0);
                        double py = number(tokens, index + 6) + (relative ? currentY : 0);
                        index += 7;
                        arcTo(rx, ry, rotation, largeArc, sweep, px, py);
                    }
                    default -> throw new IllegalArgumentException(
                        "SVG path has coordinates without a supported command near token " + index + ": " + path);
                }
            }
        }

        private static double number(List<String> tokens, int index) {
            if (index >= tokens.size() || Character.isLetter(tokens.get(index).charAt(0))) {
                throw new IllegalArgumentException("SVG path command is missing a coordinate at token " + index);
            }
            return Double.parseDouble(tokens.get(index));
        }

        private void moveTo(double px, double py) throws IOException {
            stream.moveTo(pdfX(px), pdfY(py));
            currentX = px;
            currentY = py;
            startX = px;
            startY = py;
            started = true;
        }

        private void lineTo(double px, double py) throws IOException {
            stream.lineTo(pdfX(px), pdfY(py));
            currentX = px;
            currentY = py;
        }

        /** SVG arc (endpoint parameterisation, spec appendix F.6.5) as one cubic per quarter turn. */
        private void arcTo(double rx, double ry, double rotationDegrees, boolean largeArc, boolean sweep,
                           double endX, double endY) throws IOException {
            double x1 = currentX;
            double y1 = currentY;
            if (x1 == endX && y1 == endY) {
                return;
            }
            if (rx == 0 || ry == 0) {
                lineTo(endX, endY);
                return;
            }
            rx = Math.abs(rx);
            ry = Math.abs(ry);
            double phi = Math.toRadians(rotationDegrees);
            double cosPhi = Math.cos(phi);
            double sinPhi = Math.sin(phi);
            double dx2 = (x1 - endX) / 2d;
            double dy2 = (y1 - endY) / 2d;
            double x1p = cosPhi * dx2 + sinPhi * dy2;
            double y1p = -sinPhi * dx2 + cosPhi * dy2;
            double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
            if (lambda > 1d) {
                double root = Math.sqrt(lambda);
                rx *= root;
                ry *= root;
            }
            double rx2 = rx * rx;
            double ry2 = ry * ry;
            double numerator = rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p;
            double denominator = rx2 * y1p * y1p + ry2 * x1p * x1p;
            double coefficient = (largeArc != sweep ? 1d : -1d) * Math.sqrt(Math.max(0d, numerator / denominator));
            double cxp = coefficient * (rx * y1p / ry);
            double cyp = coefficient * -(ry * x1p / rx);
            double cx = cosPhi * cxp - sinPhi * cyp + (x1 + endX) / 2d;
            double cy = sinPhi * cxp + cosPhi * cyp + (y1 + endY) / 2d;
            double ux = (x1p - cxp) / rx;
            double uy = (y1p - cyp) / ry;
            double vx = (-x1p - cxp) / rx;
            double vy = (-y1p - cyp) / ry;
            double theta1 = Math.atan2(uy, ux);
            double delta = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
            if (!sweep && delta > 0) {
                delta -= 2d * Math.PI;
            } else if (sweep && delta < 0) {
                delta += 2d * Math.PI;
            }

            int segmentCount = Math.max(1, (int) Math.ceil(Math.abs(delta) / (Math.PI / 2d) - 1e-9));
            double step = delta / segmentCount;
            double alpha = 4d / 3d * Math.tan(step / 4d);
            double theta = theta1;
            for (int segment = 0; segment < segmentCount; segment++) {
                double next = theta + step;
                double[] p1 = arcPoint(cx, cy, rx, ry, cosPhi, sinPhi, theta);
                double[] d1 = arcDerivative(rx, ry, cosPhi, sinPhi, theta);
                double[] p2 = arcPoint(cx, cy, rx, ry, cosPhi, sinPhi, next);
                double[] d2 = arcDerivative(rx, ry, cosPhi, sinPhi, next);
                stream.curveTo(
                    pdfX(p1[0] + alpha * d1[0]), pdfY(p1[1] + alpha * d1[1]),
                    pdfX(p2[0] - alpha * d2[0]), pdfY(p2[1] - alpha * d2[1]),
                    pdfX(p2[0]), pdfY(p2[1]));
                theta = next;
            }
            currentX = endX;
            currentY = endY;
        }

        private static double[] arcPoint(double cx, double cy, double rx, double ry, double cosPhi, double sinPhi,
                                         double theta) {
            double cosT = Math.cos(theta);
            double sinT = Math.sin(theta);
            return new double[] {
                cx + rx * cosT * cosPhi - ry * sinT * sinPhi,
                cy + rx * cosT * sinPhi + ry * sinT * cosPhi};
        }

        private static double[] arcDerivative(double rx, double ry, double cosPhi, double sinPhi, double theta) {
            double cosT = Math.cos(theta);
            double sinT = Math.sin(theta);
            return new double[] {
                -rx * sinT * cosPhi - ry * cosT * sinPhi,
                -rx * sinT * sinPhi + ry * cosT * cosPhi};
        }

        private float pdfX(double svgX) {
            return (float) (originX + svgX * scale);
        }

        private float pdfY(double svgY) {
            return (float) (originY - svgY * scale);
        }
    }

    // ---- images --------------------------------------------------------------------------------

    /** Embeds {@code image} losslessly (line art such as diagrams) and draws it into the given box. */
    public static PDImageXObject drawImage(
        PDDocument document,
        PDPageContentStream stream,
        BufferedImage image,
        float x,
        float y,
        float width,
        float height) throws IOException {

        PDImageXObject xObject = LosslessFactory.createFromImage(document, image);
        stream.drawImage(xObject, x, y, width, height);
        return xObject;
    }

    /** Cuts an image into horizontal strips of at most {@code stripHeightPx} rows, top to bottom. */
    public static List<BufferedImage> sliceVertically(BufferedImage image, int stripHeightPx) {
        Objects.requireNonNull(image, "image");
        if (stripHeightPx <= 0 || stripHeightPx >= image.getHeight()) {
            return List.of(image);
        }
        List<BufferedImage> strips = new ArrayList<>();
        for (int top = 0; top < image.getHeight(); top += stripHeightPx) {
            int height = Math.min(stripHeightPx, image.getHeight() - top);
            strips.add(image.getSubimage(0, top, image.getWidth(), height));
        }
        return strips;
    }

    // ---- page flow -----------------------------------------------------------------------------

    /** One measured row of a keep-together block; the painter draws it with its top edge at {@code topY}. */
    public record Row(float height, RowPainter painter) {

        public Row {
            Objects.requireNonNull(painter, "painter");
            height = Math.max(0f, height);
        }
    }

    @FunctionalInterface
    public interface RowPainter {
        void paint(PageFlow flow, float topY) throws IOException;
    }

    /**
     * The frame around a keep-together block (card border, accent bar, head line). It is painted
     * once per segment, before the segment's rows; {@code continued} is true for every segment
     * after the first when a block had to be split across pages.
     */
    public interface SegmentChrome {
        float headerHeight(boolean continued);

        float footerHeight();

        void paint(PageFlow flow, float topY, float height, boolean continued) throws IOException;
    }

    private static final SegmentChrome NO_CHROME = new SegmentChrome() {
        @Override
        public float headerHeight(boolean continued) {
            return 0f;
        }

        @Override
        public float footerHeight() {
            return 0f;
        }

        @Override
        public void paint(PageFlow flow, float topY, float height, boolean continued) {
        }
    };

    /**
     * A top-down cursor over the pages of a document. It owns the current page's content stream,
     * opens new pages on demand and lays out keep-together blocks. Insets are distances from the
     * page edges, so a landscape page opened with {@link #newPage} gets the same margins.
     */
    public static final class PageFlow implements Closeable {
        private final PDDocument document;
        private final PDRectangle defaultSize;
        private final float topInset;
        private final float bottomInset;
        private final float margin;
        private PDPage page;
        private PDPageContentStream stream;
        private float cursor;

        public PageFlow(PDDocument document, PDRectangle defaultSize, float topInset, float bottomInset, float margin)
            throws IOException {

            this.document = Objects.requireNonNull(document, "document");
            this.defaultSize = defaultSize != null ? defaultSize : PDRectangle.A4;
            this.topInset = topInset;
            this.bottomInset = bottomInset;
            this.margin = margin;
            newPage(this.defaultSize);
        }

        public PDDocument document() {
            return document;
        }

        public PDPage page() {
            return page;
        }

        public PDPageContentStream stream() {
            return stream;
        }

        /** The y coordinate of the next free line; content is drawn downwards from here. */
        public float cursor() {
            return cursor;
        }

        /** Moves the cursor down by {@code height} after the caller drew something. */
        public void advance(float height) {
            cursor -= height;
        }

        public void setCursor(float y) {
            cursor = y;
        }

        public float margin() {
            return margin;
        }

        public float bottom() {
            return bottomInset;
        }

        /** Space left on the current page above the bottom inset. */
        public float available() {
            return cursor - bottomInset;
        }

        /** The height of an empty page's content area. */
        public float pageContentHeight() {
            return page.getMediaBox().getHeight() - topInset - bottomInset;
        }

        public float contentWidth() {
            return page.getMediaBox().getWidth() - 2f * margin;
        }

        /** True while nothing has been placed on the current page. */
        public boolean atTop() {
            return cursor >= page.getMediaBox().getHeight() - topInset;
        }

        /** Starts a new default-size page when less than {@code height} is left; returns true when it did. */
        public boolean ensureSpace(float height) throws IOException {
            if (available() >= height) {
                return false;
            }
            newPage(defaultSize);
            return true;
        }

        /** Closes the current page's stream and continues on a fresh page of {@code size} (null: the default size). */
        public void newPage(PDRectangle size) throws IOException {
            closeStream();
            PDRectangle pageSize = size != null ? size : defaultSize;
            page = new PDPage(pageSize);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            cursor = pageSize.getHeight() - topInset;
        }

        /**
         * Draws a block as one piece when it fits the remaining space, on a fresh page when it
         * fits an empty page, and otherwise row by row across pages with the chrome repainted on
         * every segment. A single row taller than a page is drawn anyway rather than lost.
         */
        public void drawKeepTogether(List<Row> rows, SegmentChrome chrome) throws IOException {
            SegmentChrome frame = chrome != null ? chrome : NO_CHROME;
            List<Row> safeRows = rows != null ? rows : List.of();
            float total = frame.headerHeight(false) + frame.footerHeight();
            for (Row row : safeRows) {
                total += row.height();
            }
            if (total <= available()) {
                drawSegment(safeRows, 0, safeRows.size(), frame, false);
                return;
            }
            if (total <= pageContentHeight()) {
                newPage(defaultSize);
                drawSegment(safeRows, 0, safeRows.size(), frame, false);
                return;
            }

            int index = 0;
            boolean continued = false;
            while (index < safeRows.size()) {
                float height = frame.headerHeight(continued) + frame.footerHeight();
                int end = index;
                while (end < safeRows.size() && height + safeRows.get(end).height() <= available()) {
                    height += safeRows.get(end).height();
                    end++;
                }
                if (end == index) {
                    if (!atTop()) {
                        newPage(defaultSize);
                        continue;
                    }
                    end = index + 1;
                }
                drawSegment(safeRows, index, end, frame, continued);
                index = end;
                continued = true;
                if (index < safeRows.size()) {
                    newPage(defaultSize);
                }
            }
        }

        private void drawSegment(List<Row> rows, int from, int to, SegmentChrome frame, boolean continued)
            throws IOException {

            float header = frame.headerHeight(continued);
            float height = header + frame.footerHeight();
            for (int index = from; index < to; index++) {
                height += rows.get(index).height();
            }
            float top = cursor;
            frame.paint(this, top, height, continued);
            float rowTop = top - header;
            for (int index = from; index < to; index++) {
                Row row = rows.get(index);
                row.painter().paint(this, rowTop);
                rowTop -= row.height();
            }
            cursor = top - height;
        }

        private void closeStream() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        @Override
        public void close() throws IOException {
            closeStream();
        }
    }

    // ---- page chrome and navigation ------------------------------------------------------------

    /** The continuation header's texts; either may be null to leave that side empty. */
    public record ChromeText(String headerLeft, String headerRight) {
    }

    /**
     * Appends the shared chrome to every page once the content is complete: the continuation
     * header (skipped on the cover when {@code skipFirstHeader}), the footer rule with the
     * branding line and "Page n of m" from {@code pageLabelKey} (a message key with two
     * placeholders; null for no page numbers), the diagonal watermark and the clickable
     * repository link — the last two exactly as {@link ExportBranding} prescribes.
     */
    public static void applyPageChrome(
        PDDocument document,
        Fonts fonts,
        ExportBranding branding,
        ChromeText text,
        boolean skipFirstHeader,
        String pageLabelKey,
        float margin) throws IOException {

        ExportBranding safeBranding = branding != null ? branding : ExportBranding.defaults();
        int totalPages = document.getNumberOfPages();
        for (int pageIndex = 0; pageIndex < totalPages; pageIndex++) {
            PDPage page = document.getPage(pageIndex);
            try (PDPageContentStream stream = new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
                if (text != null && (pageIndex > 0 || !skipFirstHeader)) {
                    drawContinuationHeader(stream, fonts, text, page, margin);
                }
                drawFooter(stream, fonts, safeBranding, page, pageIndex + 1, totalPages, pageLabelKey, margin);
            }
            if (safeBranding.watermarkEnabled()) {
                PdfWatermarkSupport.draw(document, page, fonts.bold().primary(), fonts.sans().primary(), safeBranding);
            }
            if (safeBranding.footerEnabled() && safeBranding.footerUsesDefaultText()) {
                PdfWatermarkSupport.addFooterRepositoryLink(page, fonts.sans().primary(), CHROME_FONT_SIZE,
                    margin, safeBranding.footerText() + "  ·  ");
            }
        }
    }

    private static void drawContinuationHeader(
        PDPageContentStream stream,
        Fonts fonts,
        ChromeText text,
        PDPage page,
        float margin) throws IOException {

        float pageHeight = page.getMediaBox().getHeight();
        float pageWidth = page.getMediaBox().getWidth();
        float rightWidth = 0f;
        if (text.headerRight() != null && !text.headerRight().isBlank()) {
            rightWidth = textWidth(fonts.sans(), HEADER_FONT_SIZE, text.headerRight());
            drawText(stream, fonts.sans(), HEADER_FONT_SIZE, MUTED_TEXT,
                pageWidth - margin - rightWidth, pageHeight - 28f, text.headerRight());
        }
        if (text.headerLeft() != null && !text.headerLeft().isBlank()) {
            float leftWidth = pageWidth - 2f * margin - (rightWidth > 0f ? rightWidth + 16f : 0f);
            drawText(stream, fonts.sans(), HEADER_FONT_SIZE, MUTED_TEXT, margin, pageHeight - 28f,
                fit(text.headerLeft(), fonts.sans(), HEADER_FONT_SIZE, leftWidth));
        }
        line(stream, margin, pageHeight - 34f, pageWidth - margin, pageHeight - 34f, RULE, 0.7f);
    }

    private static void drawFooter(
        PDPageContentStream stream,
        Fonts fonts,
        ExportBranding branding,
        PDPage page,
        int pageNumber,
        int totalPages,
        String pageLabelKey,
        float margin) throws IOException {

        float pageWidth = page.getMediaBox().getWidth();
        line(stream, margin, 42f, pageWidth - margin, 42f, RULE, 0.7f);
        float pageLabelWidth = 0f;
        if (pageLabelKey != null && !pageLabelKey.isBlank()) {
            String pageLabel = I18n.get(pageLabelKey, pageNumber, totalPages);
            pageLabelWidth = textWidth(fonts.sans(), CHROME_FONT_SIZE, pageLabel);
            drawText(stream, fonts.sans(), CHROME_FONT_SIZE, MUTED_TEXT,
                pageWidth - margin - pageLabelWidth, 28f, pageLabel);
        }
        if (branding.footerEnabled()) {
            // The repository link annotation is positioned from the unfitted text, so only a
            // custom footer is fitted; the default text always fits an A4 page.
            String footer = branding.footerUsesDefaultText()
                ? branding.footerLine()
                : fit(branding.footerLine(), fonts.sans(), CHROME_FONT_SIZE,
                    pageWidth - 2f * margin - (pageLabelWidth > 0f ? pageLabelWidth + 16f : 0f));
            drawText(stream, fonts.sans(), CHROME_FONT_SIZE, MUTED_TEXT, margin, 28f, footer);
        }
    }

    /** One bookmark; {@code page} may be null for a heading without its own destination. */
    public record OutlineNode(String title, PDPage page, float topY, List<OutlineNode> children) {

        public OutlineNode {
            title = title != null ? title : "";
            children = children != null ? List.copyOf(children) : List.of();
        }

        public OutlineNode(String title, PDPage page, float topY) {
            this(title, page, topY, List.of());
        }
    }

    /**
     * Replaces the document outline with a root entry for the first page and the given tree
     * beneath it, and asks viewers to open the bookmarks pane. No-op for an empty document.
     */
    public static void applyOutline(PDDocument document, String rootTitle, List<OutlineNode> nodes) {
        if (document.getNumberOfPages() == 0) {
            return;
        }
        PDDocumentOutline outline = new PDDocumentOutline();
        document.getDocumentCatalog().setDocumentOutline(outline);
        PDOutlineItem root = new PDOutlineItem();
        root.setTitle(rootTitle != null ? rootTitle : "");
        PDPage first = document.getPage(0);
        root.setDestination(destination(first, first.getMediaBox().getHeight()));
        outline.addLast(root);
        addOutlineChildren(root, nodes != null ? nodes : List.of());
        root.openNode();
        outline.openNode();
        document.getDocumentCatalog().setPageMode(PageMode.USE_OUTLINES);
    }

    private static void addOutlineChildren(PDOutlineItem parent, List<OutlineNode> nodes) {
        for (OutlineNode node : nodes) {
            PDOutlineItem item = new PDOutlineItem();
            item.setTitle(node.title());
            if (node.page() != null) {
                item.setDestination(destination(node.page(), node.topY()));
            }
            parent.addLast(item);
            addOutlineChildren(item, node.children());
        }
    }

    private static PDPageXYZDestination destination(PDPage page, float topY) {
        PDPageXYZDestination destination = new PDPageXYZDestination();
        destination.setPage(page);
        destination.setTop(Math.round(topY));
        return destination;
    }

    /** Document information plus the catalog language; null fields are left untouched. */
    public record Metadata(
        String title,
        String subject,
        String keywords,
        String creator,
        String producer,
        Instant createdAt,
        Locale language) {
    }

    /**
     * Writes the info dictionary, stamps creation and modification date with {@code createdAt},
     * sets the catalog language and asks viewers to show the document title instead of the file
     * name.
     */
    public static void applyMetadata(PDDocument document, Metadata metadata) {
        if (metadata == null) {
            return;
        }
        PDDocumentInformation information = document.getDocumentInformation();
        if (metadata.title() != null) {
            information.setTitle(metadata.title());
        }
        if (metadata.subject() != null) {
            information.setSubject(metadata.subject());
        }
        if (metadata.keywords() != null) {
            information.setKeywords(metadata.keywords());
        }
        if (metadata.creator() != null) {
            information.setCreator(metadata.creator());
        }
        if (metadata.producer() != null) {
            information.setProducer(metadata.producer());
        }
        if (metadata.createdAt() != null) {
            Calendar calendar = GregorianCalendar.from(metadata.createdAt().atZone(ZoneId.systemDefault()));
            information.setCreationDate(calendar);
            information.setModificationDate(calendar);
        }
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        if (metadata.language() != null) {
            catalog.setLanguage(metadata.language().toLanguageTag());
        }
        PDViewerPreferences preferences = catalog.getViewerPreferences();
        if (preferences == null) {
            preferences = new PDViewerPreferences(new COSDictionary());
            catalog.setViewerPreferences(preferences);
        }
        preferences.setDisplayDocTitle(true);
    }

    /** A borderless link over {@code rect} that jumps to {@code topY} on {@code targetPage} (table of contents rows). */
    public static void addGoToLink(PDPage page, PDRectangle rect, PDPage targetPage, float topY) throws IOException {
        PDActionGoTo action = new PDActionGoTo();
        action.setDestination(destination(targetPage, topY));
        PDAnnotationLink link = new PDAnnotationLink();
        link.setAction(action);
        PDBorderStyleDictionary border = new PDBorderStyleDictionary();
        border.setWidth(0);
        link.setBorderStyle(border);
        link.setRectangle(rect);
        page.getAnnotations().add(link);
    }
}
