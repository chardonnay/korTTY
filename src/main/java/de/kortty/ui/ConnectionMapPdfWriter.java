package de.kortty.ui;

import de.kortty.core.ConnectionColorSupport;
import de.kortty.core.ExportBranding;
import de.kortty.core.PdfReportKit;
import de.kortty.core.PdfReportKit.Fonts;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The Connection Manager's map as a vector PDF, in print colours. It shows what {@link ConnectionMapView}
 * shows: a zone per folder with a light tint, a node per connection (a dashed border for teamwork
 * connections, the OS badge at the left), jump routes as orange dashed curves, tunnels as teal dotted
 * curves and the tunnels listed under this computer. The arrangement is {@link ConnectionMapLayout}'s; the
 * page scales it to the largest size that fits the map area. A map that would need less than
 * {@code MIN_SCALE} of its size is continued on further pages, cut between zone shelves so that no zone is
 * split. Every page repeats the header, and a long tunnel list flows onto the top of the following pages.
 * Nothing here touches JavaFX.
 */
final class ConnectionMapPdfWriter {

    /**
     * What a node shows: the name, the address, whether it is a teamwork connection (dashed border) and an
     * optional OS badge image.
     */
    record MapNode(String name, String address, boolean teamwork, BufferedImage badge) { }

    /** The texts the caller translates. */
    record Labels(String thisComputer, String jumpRoute, String tunnel, String teamwork, String externalZone,
                  String noFolder) { }

    /**
     * One map to write.
     *
     * @param entries   the connections, as {@link ConnectionMapLayout} takes them
     * @param nodes     the node of each entry, keyed by {@link ConnectionMapLayout.Entry#id()}
     * @param zoneTitle a zone key's title, or null to use the key itself
     * @param zoneColor a zone key's colour as {@code #RRGGBB}, or null for grey
     */
    record Request(String title, String subtitle, boolean landscape, List<ConnectionMapLayout.Entry> entries,
                   Map<String, MapNode> nodes, Function<String, String> zoneTitle,
                   Function<String, String> zoneColor, Labels labels) { }

    /** A horizontal slice of the layout, in layout units, that one page shows, counted from the top. */
    record Band(double top, double bottom) { }

    /**
     * How the map sits on its pages: the layout, the scale from layout units to points, and the bands of the
     * layout that the pages show, from the top of the map area down.
     */
    record Plan(ConnectionMapLayout.Result layout, float scale, List<Band> bands) { }

    private static final PDRectangle PORTRAIT = PDRectangle.A4;
    private static final PDRectangle LANDSCAPE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private static final float MARGIN = PdfReportKit.DEFAULT_MARGIN;
    private static final float MAP_BOTTOM = PdfReportKit.DEFAULT_BOTTOM_INSET;
    /** From the page's top edge down to the map area: logo, title, subtitle, rule and legend. */
    private static final float HEADER_HEIGHT = 98f;
    private static final float LOGO_TOP = 28f;
    private static final float LOGO_HEIGHT = 28f;
    /** The logo is embedded at this pixel height, which stays sharp at {@link #LOGO_HEIGHT} on paper. */
    private static final int LOGO_PIXEL_HEIGHT = 240;
    private static final String LOGO_RESOURCE = "/icon/kortty_logo.png";
    private static final float TITLE_SIZE = 17f;
    private static final float SUBTITLE_SIZE = 9f;
    private static final float LEGEND_SIZE = 8.5f;
    private static final float LEGEND_SAMPLE = 22f;
    private static final float LEGEND_GAP = 24f;
    private static final String PAGE_LABEL_KEY = "ai.result.export.pdf.pageNumber";

    /**
     * The smallest scale at which the node text stays readable. A map that would need less to fit one page
     * is kept at this scale and continued on further pages.
     */
    static final float MIN_SCALE = 0.45f;
    private static final float MAX_SCALE = 1f;
    /** The widths tried for the arrangement: the narrower, the taller the map. */
    private static final int MIN_CANDIDATE_WIDTH = 600;
    private static final int MAX_CANDIDATE_WIDTH = 2600;
    private static final int CANDIDATE_STEP = 100;

    private static final float NODE_W = (float) ConnectionMapLayout.NODE_WIDTH;
    private static final float NODE_H = (float) ConnectionMapLayout.NODE_HEIGHT;
    private static final float ZONE_PAD = (float) ConnectionMapLayout.ZONE_PADDING;
    private static final float ZONE_TITLE_H = (float) ConnectionMapLayout.ZONE_TITLE;
    /** The gap between this computer's node and its first tunnel (layout units). */
    private static final double TUNNEL_GAP = 12;
    private static final float ZONE_RADIUS = 14f;
    private static final float NODE_RADIUS = 9f;

    private static final Color TEXT = new Color(0x1F, 0x29, 0x37);
    private static final Color BODY = new Color(0x37, 0x41, 0x51);
    private static final Color MUTED = PdfReportKit.MUTED_TEXT;
    private static final Color RULE = PdfReportKit.RULE;
    private static final Color JUMP = new Color(0xDC, 0x98, 0x25);
    private static final Color TUNNEL = new Color(0x13, 0xA8, 0xAD);
    private static final Color TUNNEL_TEXT = new Color(0x0E, 0x76, 0x7A);
    private static final Color NODE_FILL = Color.WHITE;
    private static final Color NODE_BORDER = new Color(0x8A, 0x94, 0xA3);
    /** The colour of a folder without one, as the screen draws it in grey. */
    private static final Color NEUTRAL = new Color(0x80, 0x80, 0x80);
    private static final Color NEUTRAL_STROKE = new Color(0x9A, 0xA0, 0xA6);
    /** A folder's tint is its colour at this share over white, the screen's 8 % alpha. */
    private static final float TINT = 0.08f;

    private ConnectionMapPdfWriter() {
    }

    /**
     * Writes the map to {@code file}: one page per band of the map, each with the header, and the shared
     * export footer on every page. An empty map is one page with the header and this computer.
     */
    static void write(Path file, Request request) throws IOException {
        Objects.requireNonNull(request.labels(), "labels");
        PDRectangle size = request.landscape() ? LANDSCAPE : PORTRAIT;
        List<ConnectionMapLayout.Entry> entries = request.entries() != null ? request.entries() : List.of();
        float areaWidth = size.getWidth() - 2f * MARGIN;
        float areaHeight = size.getHeight() - HEADER_HEIGHT - MAP_BOTTOM;
        Plan plan = plan(entries, areaWidth, areaHeight);
        try (PDDocument document = new PDDocument()) {
            Fonts fonts = PdfReportKit.loadFonts(document);
            Painter painter = new Painter(document, fonts, request, entries, plan, size.getWidth(), size.getHeight());
            for (int index = 0; index < painter.pageCount(); index++) {
                PDPage page = new PDPage(size);
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    painter.drawPage(stream, index);
                }
            }
            PdfReportKit.applyPageChrome(document, fonts, branding(), null, false, PAGE_LABEL_KEY, MARGIN);
            PdfReportKit.applyMetadata(document, new PdfReportKit.Metadata(
                request.title() != null ? request.title() : "",
                request.subtitle(),
                "korTTY, connection map",
                "korTTY " + appVersion(),
                "korTTY",
                Instant.now(),
                null));
            try (OutputStream out = Files.newOutputStream(file)) {
                document.save(out);
            }
        }
    }

    /**
     * Picks the arrangement and the scale for a map area of the given size. The arrangement is the width
     * whose result scales largest into the area (capped at {@link #MAX_SCALE}). When even that is below
     * {@link #MIN_SCALE}, the scale is {@link #MIN_SCALE} and the arrangement is the one needing the least
     * height among those whose width still fits at that scale.
     */
    static Plan plan(List<ConnectionMapLayout.Entry> entries, float areaWidth, float areaHeight) {
        List<ConnectionMapLayout.Result> candidates = new ArrayList<>();
        ConnectionMapLayout.Result best = null;
        double bestScale = -1;
        for (int width = MIN_CANDIDATE_WIDTH; width <= MAX_CANDIDATE_WIDTH; width += CANDIDATE_STEP) {
            ConnectionMapLayout.Result candidate = ConnectionMapLayout.layout(entries, width);
            candidates.add(candidate);
            double fit = Math.min(MAX_SCALE, Math.min(areaWidth / candidate.width(), areaHeight / candidate.height()));
            if (fit > bestScale) {
                bestScale = fit;
                best = candidate;
            }
        }
        if (bestScale >= MIN_SCALE) {
            return new Plan(best, (float) bestScale, List.of(new Band(0, best.height())));
        }
        ConnectionMapLayout.Result shelved = null;
        for (ConnectionMapLayout.Result candidate : candidates) {
            if (candidate.width() * MIN_SCALE <= areaWidth && (shelved == null || candidate.height() < shelved.height())) {
                shelved = candidate;
            }
        }
        if (shelved == null) {
            // Not even the narrowest arrangement fits at MIN_SCALE: shrink it to the width as well.
            shelved = candidates.get(0);
        }
        float scale = (float) Math.min(MIN_SCALE, areaWidth / shelved.width());
        return new Plan(shelved, scale, bands(shelved, areaHeight / scale));
    }

    /**
     * Cuts the layout into pages of {@code pageHeight} layout units. A cut falls in the gap between two
     * shelves, so no zone is split; a shelf is never split from the zones beside it.
     */
    static List<Band> bands(ConnectionMapLayout.Result layout, double pageHeight) {
        // The zones of one shelf share their top edge; the shelf reaches down to its tallest zone.
        TreeMap<Double, Double> shelves = new TreeMap<>();
        for (ConnectionMapLayout.Zone zone : layout.zones()) {
            shelves.merge(zone.y(), zone.y() + zone.height(), (a, b) -> Math.max(a, b));
        }
        List<Double> tops = new ArrayList<>(shelves.keySet());
        List<Double> bottoms = new ArrayList<>(shelves.values());
        List<Band> bands = new ArrayList<>();
        double bandTop = 0;
        int first = 0;
        while (first < tops.size()) {
            int last = first;
            while (last + 1 < tops.size() && bottoms.get(last + 1) - bandTop <= pageHeight) {
                last++;
            }
            double bandBottom = last + 1 < tops.size()
                ? (bottoms.get(last) + tops.get(last + 1)) / 2
                : layout.height();
            bands.add(new Band(bandTop, bandBottom));
            bandTop = bandBottom;
            first = last + 1;
        }
        if (bands.isEmpty()) {
            bands.add(new Band(0, layout.height()));
        }
        return bands;
    }

    /** The korTTY logo, downscaled for embedding; null when the resource is missing. */
    private static PDImageXObject loadLogo(PDDocument document) throws IOException {
        try (InputStream in = ConnectionMapPdfWriter.class.getResourceAsStream(LOGO_RESOURCE)) {
            if (in == null) {
                return null;
            }
            BufferedImage source = ImageIO.read(in);
            if (source == null) {
                return null;
            }
            int width = Math.max(1, Math.round(LOGO_PIXEL_HEIGHT * source.getWidth() / (float) source.getHeight()));
            BufferedImage scaled = new BufferedImage(width, LOGO_PIXEL_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(source, 0, 0, width, LOGO_PIXEL_HEIGHT, null);
            } finally {
                graphics.dispose();
            }
            return LosslessFactory.createFromImage(document, scaled);
        }
    }

    /** The branding the other korTTY exports use: the user's settings when the application runs, else the defaults. */
    private static ExportBranding branding() {
        try {
            de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
            return ExportBranding.fromSettings(app != null && app.getGlobalSettingsManager() != null
                ? app.getGlobalSettingsManager().getSettings() : null);
        } catch (RuntimeException | LinkageError e) {
            return ExportBranding.defaults();
        }
    }

    private static String appVersion() {
        try {
            return de.kortty.KorTTYApplication.getAppVersion();
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    /** 0 at {@link #MIN_SCALE}, 1 at full size: how far the text may grow from its smallest size. */
    private static float growth(float scale) {
        return Math.max(0f, Math.min(1f, (scale - MIN_SCALE) / (MAX_SCALE - MIN_SCALE)));
    }

    /** {@code share} of {@code color} over white. */
    private static Color tint(Color color, float share) {
        return new Color(
            Math.round(255 - (255 - color.getRed()) * share),
            Math.round(255 - (255 - color.getGreen()) * share),
            Math.round(255 - (255 - color.getBlue()) * share));
    }

    /** A zone colour as a colour, or null for grey (missing or not a hex colour). */
    private static Color parseColor(String hex) {
        String normalized = ConnectionColorSupport.normalizeHex(hex);
        if (normalized == null) {
            return null;
        }
        try {
            return Color.decode(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * The jump route's curve between two nodes, as {@link ConnectionMapView#edgeView} draws it:
     * {@code {startX, startY, control1, control2, endX, endY}} in layout units, the controls sharing the
     * height of their end.
     */
    private static double[] curve(ConnectionMapLayout.Node from, ConnectionMapLayout.Node to) {
        double width = ConnectionMapLayout.NODE_WIDTH;
        double dx = to.centerX() - from.centerX();
        double startY = from.centerY();
        double endY = to.centerY();
        double startX;
        double endX;
        double control1;
        double control2;
        if (Math.abs(dx) < width) {
            startX = from.x() + width;
            endX = to.x() + width;
            double bulge = 36 + Math.abs(endY - startY) / 6;
            control1 = startX + bulge;
            control2 = endX + bulge;
        } else if (dx > 0) {
            startX = from.x() + width;
            endX = to.x();
            double bend = Math.max(40, (endX - startX) / 2.2);
            control1 = startX + bend;
            control2 = endX - bend;
        } else {
            startX = from.x();
            endX = to.x() + width;
            double bend = Math.max(40, (startX - endX) / 2.2);
            control1 = startX - bend;
            control2 = endX + bend;
        }
        return new double[] {startX, startY, control1, control2, endX, endY};
    }

    /** Maps layout units onto a page: {@code x = offsetX + layoutX * scale}, {@code y = offsetY - layoutY * scale}. */
    private record Frame(float scale, double offsetX, double offsetY, Band band) {

        float x(double layoutX) {
            return (float) (offsetX + layoutX * scale);
        }

        float y(double layoutY) {
            return (float) (offsetY - layoutY * scale);
        }

        /** True when the layout range from {@code top} to {@code bottom} reaches into this page's band. */
        boolean visible(double top, double bottom) {
            return bottom >= band.top() && top <= band.bottom();
        }
    }

    /** A tunnel line placed on a page, with its baseline in page coordinates. */
    private record TunnelRow(String text, float baseline) { }

    /** Draws the pages of one map; holds what the pages share. */
    private static final class Painter {
        private final PDDocument document;
        private final Fonts fonts;
        private final Request request;
        private final Labels labels;
        private final Plan plan;
        private final float pageWidth;
        private final float pageHeight;
        private final float scale;
        private final float growth;
        private final Map<String, MapNode> nodes;
        private final Map<String, ConnectionMapLayout.Entry> entries = new HashMap<>();
        private final Map<String, ConnectionMapLayout.Node> layoutNodes = new HashMap<>();
        private final Map<BufferedImage, PDImageXObject> badges = new IdentityHashMap<>();
        private final PDImageXObject logo;
        private final float tunnelSize;
        private final float tunnelX;
        private final List<List<TunnelRow>> tunnelPages;
        private final int pageCount;

        Painter(PDDocument document, Fonts fonts, Request request, List<ConnectionMapLayout.Entry> entries,
                Plan plan, float pageWidth, float pageHeight) throws IOException {
            this.document = document;
            this.fonts = fonts;
            this.request = request;
            this.labels = request.labels();
            this.plan = plan;
            this.pageWidth = pageWidth;
            this.pageHeight = pageHeight;
            this.scale = plan.scale();
            this.growth = growth(plan.scale());
            this.nodes = request.nodes() != null ? request.nodes() : Map.of();
            for (ConnectionMapLayout.Entry entry : entries) {
                this.entries.put(entry.id(), entry);
            }
            for (ConnectionMapLayout.Node node : plan.layout().nodes()) {
                this.layoutNodes.put(node.id(), node);
            }
            this.logo = loadLogo(document);
            this.tunnelSize = 6.8f + 2.2f * growth;
            this.tunnelX = originX() + (float) (ConnectionMapLayout.ME_X * scale);
            this.tunnelPages = tunnelPages();
            this.pageCount = Math.max(plan.bands().size(), tunnelPages.size());
        }

        /** The number of pages: the map's bands, or more when the tunnel list is longer than them. */
        int pageCount() {
            return pageCount;
        }

        /** One page: the header, the part of the map in its band clipped to the map area, and its tunnels. */
        void drawPage(PDPageContentStream stream, int index) throws IOException {
            drawHeader(stream);
            float mapTop = pageHeight - HEADER_HEIGHT;
            float areaWidth = pageWidth - 2f * MARGIN;
            float areaHeight = mapTop - MAP_BOTTOM;
            Band band = index < plan.bands().size()
                ? plan.bands().get(index)
                : new Band(plan.layout().height(), plan.layout().height());
            Frame frame = new Frame(scale, originX(), mapTop + band.top() * scale, band);

            stream.saveGraphicsState();
            stream.addRect(MARGIN, MAP_BOTTOM, areaWidth, areaHeight);
            stream.clip();
            // PDFBox's clip() writes only the W operator; "n" ends the path without painting it.
            stream.appendRawCommands("n\n");
            for (ConnectionMapLayout.Zone zone : plan.layout().zones()) {
                if (frame.visible(zone.y(), zone.y() + zone.height())) {
                    drawZone(stream, frame, zone);
                }
            }
            drawEdges(stream, frame);
            for (ConnectionMapLayout.Node node : plan.layout().nodes()) {
                if (frame.visible(node.y(), node.y() + ConnectionMapLayout.NODE_HEIGHT)) {
                    drawNode(stream, frame, node, display(node));
                }
            }
            stream.restoreGraphicsState();

            if (index < tunnelPages.size()) {
                for (TunnelRow row : tunnelPages.get(index)) {
                    PdfReportKit.drawText(stream, fonts.sans(), tunnelSize, TUNNEL_TEXT, tunnelX, row.baseline(),
                        row.text());
                }
            }
        }

        /** The x of the layout's origin: the map is centred horizontally in the map area. */
        private float originX() {
            float areaWidth = pageWidth - 2f * MARGIN;
            return MARGIN + (areaWidth - (float) plan.layout().width() * scale) / 2f;
        }

        /**
         * The tunnel list under this computer, in pages. The first page starts under the node. One tunnel's
         * lines stay together; a list too long for the page continues at the top of the next one.
         */
        private List<List<TunnelRow>> tunnelPages() throws IOException {
            List<List<TunnelRow>> pages = new ArrayList<>();
            ConnectionMapLayout.Node me = layoutNodes.get(ConnectionMapLayout.ME);
            if (me == null) {
                return pages;
            }
            List<List<String>> blocks = new ArrayList<>();
            for (ConnectionMapLayout.Edge edge : plan.layout().edges()) {
                if (edge.kind() != ConnectionMapLayout.EdgeKind.TUNNEL || edge.label() == null) {
                    continue;
                }
                blocks.add(PdfReportKit.wrapParagraph(displayName(edge.to()) + ": " + edge.label(),
                    fonts.sans(), tunnelSize, NODE_W * scale));
            }
            float mapTop = pageHeight - HEADER_HEIGHT;
            float top = mapTop - (float) ((me.y() + ConnectionMapLayout.NODE_HEIGHT + TUNNEL_GAP) * scale);
            List<TunnelRow> page = new ArrayList<>();
            for (List<String> block : blocks) {
                float lastBaseline = top - tunnelSize * 0.85f - (block.size() - 1) * tunnelSize * 1.3f;
                if (lastBaseline < MAP_BOTTOM && !page.isEmpty()) {
                    pages.add(page);
                    page = new ArrayList<>();
                    top = mapTop;
                }
                for (String line : block) {
                    page.add(new TunnelRow(line, top - tunnelSize * 0.85f));
                    top -= tunnelSize * 1.3f;
                }
                top -= tunnelSize * 0.6f;
            }
            if (!page.isEmpty()) {
                pages.add(page);
            }
            return pages;
        }

        private void drawHeader(PDPageContentStream stream) throws IOException {
            float top = pageHeight;
            float textX = MARGIN;
            if (logo != null) {
                float logoWidth = LOGO_HEIGHT * logo.getWidth() / logo.getHeight();
                stream.drawImage(logo, MARGIN, top - LOGO_TOP - LOGO_HEIGHT, logoWidth, LOGO_HEIGHT);
                textX += logoWidth + 12f;
            }
            float textWidth = pageWidth - MARGIN - textX;
            PdfReportKit.drawText(stream, fonts.bold(), TITLE_SIZE, TEXT, textX, top - 40f,
                PdfReportKit.fit(orEmpty(request.title()), fonts.bold(), TITLE_SIZE, textWidth));
            PdfReportKit.drawText(stream, fonts.sans(), SUBTITLE_SIZE, MUTED, textX, top - 55f,
                PdfReportKit.fit(orEmpty(request.subtitle()), fonts.sans(), SUBTITLE_SIZE, textWidth));
            PdfReportKit.line(stream, MARGIN, top - 66f, pageWidth - MARGIN, top - 66f, RULE, 0.7f);
            drawLegend(stream, top - 82f);
        }

        /** The three samples the map uses, each with its label: jump route, tunnel and teamwork connection. */
        private void drawLegend(PDPageContentStream stream, float baseline) throws IOException {
            float x = MARGIN;
            stream.saveGraphicsState();
            stream.setLineDashPattern(new float[] {7f, 5f}, 0f);
            PdfReportKit.line(stream, x, baseline + 3f, x + LEGEND_SAMPLE, baseline + 3f, JUMP, 1.8f);
            stream.restoreGraphicsState();
            x = legendLabel(stream, x + LEGEND_SAMPLE + 6f, baseline, labels.jumpRoute()) + LEGEND_GAP;

            stream.saveGraphicsState();
            stream.setLineCapStyle(1);
            stream.setLineDashPattern(new float[] {0.5f, 5f}, 0f);
            PdfReportKit.line(stream, x, baseline + 3f, x + LEGEND_SAMPLE, baseline + 3f, TUNNEL, 1.8f);
            stream.restoreGraphicsState();
            x = legendLabel(stream, x + LEGEND_SAMPLE + 6f, baseline, labels.tunnel()) + LEGEND_GAP;

            stream.saveGraphicsState();
            stream.setLineDashPattern(new float[] {5f, 4f}, 0f);
            PdfReportKit.roundRect(stream, x, baseline - 2f, 16f, 10f, 3f, NODE_FILL, NODE_BORDER, 0.8f);
            stream.restoreGraphicsState();
            legendLabel(stream, x + 16f + 6f, baseline, labels.teamwork());
        }

        /** Draws a legend label and returns the x after it. */
        private float legendLabel(PDPageContentStream stream, float x, float baseline, String text) throws IOException {
            PdfReportKit.drawText(stream, fonts.sans(), LEGEND_SIZE, BODY, x, baseline, text);
            return x + PdfReportKit.textWidth(fonts.sans(), LEGEND_SIZE, text);
        }

        private void drawZone(PDPageContentStream stream, Frame frame, ConnectionMapLayout.Zone zone)
            throws IOException {

            boolean external = ConnectionMapLayout.EXTERNAL_ZONE.equals(zone.key());
            Color color = external ? null : zoneColor(zone.key());
            Color stroke = color != null ? color : NEUTRAL_STROKE;
            float x = frame.x(zone.x());
            float top = frame.y(zone.y());
            float bottom = frame.y(zone.y() + zone.height());
            float width = (float) (zone.width() * scale);
            float pad = Math.max(4f, ZONE_PAD * scale);
            stream.saveGraphicsState();
            stream.setLineDashPattern(new float[] {Math.max(3f, 7f * scale), Math.max(2.5f, 5f * scale)}, 0f);
            PdfReportKit.roundRect(stream, x, bottom, width, top - bottom, Math.max(3f, ZONE_RADIUS * scale),
                tint(color != null ? color : NEUTRAL, TINT), stroke, 0.9f + 0.4f * growth);
            stream.restoreGraphicsState();

            float titleSize = 7.5f + 2.5f * growth;
            float titleCentre = top - ZONE_TITLE_H * scale / 2;
            float square = titleSize * 0.8f;
            PdfReportKit.fillRect(stream, x + pad, titleCentre - square / 2, square, square, stroke);
            float textX = x + pad + square + 5f;
            float textWidth = x + width - pad - textX;
            PdfReportKit.drawText(stream, fonts.bold(), titleSize, TEXT, textX, titleCentre - titleSize * 0.35f,
                PdfReportKit.fit(zoneTitle(zone.key()), fonts.bold(), titleSize, textWidth));
        }

        /** A folder's title; the external zone and the folder-less zone carry the caller's labels. */
        private String zoneTitle(String key) {
            if (ConnectionMapLayout.EXTERNAL_ZONE.equals(key)) {
                return labels.externalZone();
            }
            if (key.isEmpty()) {
                return labels.noFolder();
            }
            String title = request.zoneTitle() != null ? request.zoneTitle().apply(key) : null;
            return blank(title) ? key : title;
        }

        private Color zoneColor(String key) {
            return request.zoneColor() != null ? parseColor(request.zoneColor().apply(key)) : null;
        }

        private void drawEdges(PDPageContentStream stream, Frame frame) throws IOException {
            for (ConnectionMapLayout.Edge edge : plan.layout().edges()) {
                ConnectionMapLayout.Node from = layoutNodes.get(edge.from());
                ConnectionMapLayout.Node to = layoutNodes.get(edge.to());
                if (from == null || to == null) {
                    continue;
                }
                double[] curve = curve(from, to);
                if (frame.visible(Math.min(curve[1], curve[5]), Math.max(curve[1], curve[5]))) {
                    drawEdge(stream, frame, edge.kind() == ConnectionMapLayout.EdgeKind.JUMP, curve);
                }
            }
        }

        private void drawEdge(PDPageContentStream stream, Frame frame, boolean jump, double[] curve)
            throws IOException {

            stream.saveGraphicsState();
            stream.setStrokingColor(jump ? JUMP : TUNNEL);
            stream.setLineWidth(1f + 0.8f * growth);
            if (jump) {
                stream.setLineDashPattern(new float[] {Math.max(3f, 7f * scale), Math.max(2.5f, 5f * scale)}, 0f);
            } else {
                stream.setLineCapStyle(1);
                stream.setLineDashPattern(new float[] {Math.max(0.5f, 2f * scale), Math.max(2.5f, 5f * scale)}, 0f);
            }
            stream.moveTo(frame.x(curve[0]), frame.y(curve[1]));
            stream.curveTo(frame.x(curve[2]), frame.y(curve[1]), frame.x(curve[3]), frame.y(curve[5]),
                frame.x(curve[4]), frame.y(curve[5]));
            stream.stroke();
            stream.restoreGraphicsState();
        }

        private void drawNode(PDPageContentStream stream, Frame frame, ConnectionMapLayout.Node node, MapNode shown)
            throws IOException {

            float x = frame.x(node.x());
            float bottom = frame.y(node.y() + ConnectionMapLayout.NODE_HEIGHT);
            float width = NODE_W * scale;
            float height = NODE_H * scale;
            stream.saveGraphicsState();
            if (shown.teamwork()) {
                stream.setLineDashPattern(new float[] {Math.max(2.4f, 5f * scale), Math.max(2f, 4f * scale)}, 0f);
            }
            PdfReportKit.roundRect(stream, x, bottom, width, height, Math.max(2.5f, NODE_RADIUS * scale),
                NODE_FILL, NODE_BORDER, 0.6f + 0.3f * growth);
            stream.restoreGraphicsState();

            float pad = Math.max(3f, 7f * scale);
            float textX = x + pad;
            if (shown.badge() != null) {
                float box = height * 0.5f;
                float aspect = shown.badge().getWidth() / (float) shown.badge().getHeight();
                float badgeWidth = aspect >= 1f ? box : box * aspect;
                float badgeHeight = aspect >= 1f ? box / aspect : box;
                stream.drawImage(badgeImage(shown.badge()), textX, bottom + (height - badgeHeight) / 2,
                    badgeWidth, badgeHeight);
                textX += box + Math.max(3f, 5f * scale);
            }
            float textWidth = x + width - pad - textX;
            float nameSize = 7f + 3f * growth;
            if (blank(shown.address())) {
                PdfReportKit.drawText(stream, fonts.bold(), nameSize, TEXT, textX,
                    bottom + height / 2 - nameSize * 0.36f,
                    PdfReportKit.fit(shown.name(), fonts.bold(), nameSize, textWidth));
                return;
            }
            float addressSize = 6f + 2f * growth;
            float gap = nameSize * 0.3f + addressSize * 0.9f + 1.5f;
            float nameBaseline = bottom + height / 2 + (gap + 0.22f * addressSize - 0.72f * nameSize) / 2;
            PdfReportKit.drawText(stream, fonts.bold(), nameSize, TEXT, textX, nameBaseline,
                PdfReportKit.fit(shown.name(), fonts.bold(), nameSize, textWidth));
            PdfReportKit.drawText(stream, fonts.sans(), addressSize, MUTED, textX, nameBaseline - gap,
                PdfReportKit.fit(shown.address(), fonts.sans(), addressSize, textWidth));
        }

        /** What a node shows: this computer, an external jump server's label, or the connection's own text. */
        private MapNode display(ConnectionMapLayout.Node node) {
            if (ConnectionMapLayout.ME.equals(node.id())) {
                return new MapNode(labels.thisComputer(), null, false, null);
            }
            if (node.external()) {
                return new MapNode(node.label(), null, false, null);
            }
            MapNode known = nodes.get(node.id());
            if (known != null) {
                return known;
            }
            ConnectionMapLayout.Entry entry = entries.get(node.id());
            return new MapNode(entry != null && entry.host() != null ? entry.host() : node.id(), null, false, null);
        }

        private String displayName(String id) {
            ConnectionMapLayout.Node node = layoutNodes.get(id);
            return node != null ? display(node).name() : id;
        }

        private PDImageXObject badgeImage(BufferedImage badge) throws IOException {
            PDImageXObject image = badges.get(badge);
            if (image == null) {
                image = LosslessFactory.createFromImage(document, badge);
                badges.put(badge, image);
            }
            return image;
        }

        private static String orEmpty(String text) {
            return text != null ? text : "";
        }
    }
}
