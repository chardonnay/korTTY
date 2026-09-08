package de.kortty.core;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Draws a scanned SVG scene onto a white Java2D canvas whose pixel grid matches the character
 * grid: every character cell is {@value #CELL_WIDTH_PX}×{@value #CELL_HEIGHT_PX} pixels.
 *
 * <p>The cell aspect is fixed rather than measured from a font: the preview and the paste target
 * use fonts korTTY does not know, common monospace faces are close to 1:2 anyway, and a fixed
 * ratio keeps the output identical on every machine. No fonts are loaded at all, which is also
 * what lets the glyph templates in {@link AsciiArtGlyphs} come out of this very code path.</p>
 *
 * <p>The canvas is {@code TYPE_INT_RGB}, not {@code TYPE_BYTE_GRAY}: Java2D composites the gray
 * image type in a linear colour space, which darkens every mid-tone and would collapse the
 * four-tone contract (black, #555, #aaa, white) into two.</p>
 */
final class AsciiArtRasterizer {

    static final int CELL_WIDTH_PX = 8;
    static final int CELL_HEIGHT_PX = 16;
    /** Thinner than 2 px breaks into single-pixel dust that no glyph resembles. */
    static final double MIN_STROKE_PX = 2.0;
    /** Thicker strokes are what a filled shape is for; the clamp keeps a "stroke-width 50" readable. */
    static final double MAX_STROKE_PX = 10.0;
    /** Lifts mid-tones so #aaa and #555 land on distinct rungs of the tone ramp. */
    static final double INK_GAMMA = 0.75;
    /** Margin around the shape bounds when the fit is derived from them instead of a viewBox. */
    static final double NO_VIEWBOX_MARGIN = 0.05;
    /** A background slab must contain the canvas inset by this fraction on every side. */
    static final double BACKGROUND_INSET = 0.02;
    /** Only the first drawables can be a background: anything later is part of the picture. */
    static final int BACKGROUND_CANDIDATES = 3;
    /** Shapes drawn between two interruption checks; keeps cancellation responsive at the caps. */
    static final int INTERRUPT_CHECK_INTERVAL = 100;

    private AsciiArtRasterizer() {
    }

    /** Per-pixel ink (0 = white, 1 = black) of a rendered canvas; reads outside are clamped. */
    static final class InkMap {
        final int width;
        final int height;
        private final float[] ink;

        private InkMap(int width, int height, float[] ink) {
            this.width = width;
            this.height = height;
            this.ink = ink;
        }

        /** Ink of pixel ({@code x}, {@code y}); coordinates outside the canvas read the nearest edge. */
        double inkAt(int x, int y) {
            int cx = x < 0 ? 0 : (x >= width ? width - 1 : x);
            int cy = y < 0 ? 0 : (y >= height ? height - 1 : y);
            return ink[cy * width + cx];
        }

        /** Reads the green channel back: paint is neutral gray, so one channel carries the tone. */
        static InkMap of(BufferedImage image) {
            int width = image.getWidth();
            int height = image.getHeight();
            float[] ink = new float[width * height];
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                image.getRGB(0, y, width, 1, row, 0, width);
                for (int x = 0; x < width; x++) {
                    int green = (row[x] >> 8) & 0xFF;
                    ink[y * width + x] = (float) Math.pow(1.0 - green / 255.0, INK_GAMMA);
                }
            }
            return new InkMap(width, height, ink);
        }
    }

    /**
     * What a rasterisation produced: the ink, the world rectangle that was fitted, whether a
     * background slab was suppressed, and how many shapes were actually drawn.
     */
    record Result(InkMap ink, Rectangle2D fitted, boolean backgroundDropped, int drawnShapes) {
    }

    /**
     * Draws {@code scene} into a {@code columns}×{@code rows} character grid, fitting
     * {@code fitRect} (world coordinates) uniformly and centred. With {@code suppressBackground} a
     * filled shape among the first {@value #BACKGROUND_CANDIDATES} drawables that covers the whole
     * canvas is skipped: models like to start with a sky rectangle, and on a white page that slab
     * would turn the entire picture into one dark block. Tests use {@code false} to see the raw
     * drawing.
     *
     * @throws InterruptedException when the calling thread is interrupted (checked every
     *         {@value #INTERRUPT_CHECK_INTERVAL} shapes)
     */
    static Result rasterize(AsciiArtSvgScanner.Scene scene, int columns, int rows, Rectangle2D fitRect,
            boolean suppressBackground) throws InterruptedException {
        int width = columns * CELL_WIDTH_PX;
        int height = rows * CELL_HEIGHT_PX;
        BufferedImage image = newCanvas(width, height);
        AffineTransform base = fitTransform(fitRect, width, height);
        Point2D[] backgroundProbes = suppressBackground ? probes(fitRect, base) : null;
        boolean backgroundDropped = false;
        int drawn = 0;
        int index = 0;
        Graphics2D g = graphics(image);
        try {
            for (AsciiArtSvgScanner.Shape shape : scene.shapes()) {
                index++;
                if (index % INTERRUPT_CHECK_INTERVAL == 0 && Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("ASCII art rasterisation was cancelled.");
                }
                AffineTransform device = new AffineTransform(base);
                device.concatenate(shape.transform());
                java.awt.Shape outline = device.createTransformedShape(shape.geometry());
                if (backgroundProbes != null && !backgroundDropped && index <= BACKGROUND_CANDIDATES
                    && shape.hasFill() && containsAll(outline, backgroundProbes)) {
                    backgroundDropped = true;
                    continue;
                }
                if (shape.hasFill()) {
                    g.setColor(paint(shape.fillInk(), shape.fillAlpha()));
                    g.fill(outline);
                }
                if (shape.hasStroke()) {
                    double scale = Math.sqrt(Math.abs(device.getDeterminant()));
                    double px = shape.strokeWidth() * scale;
                    float clamped = (float) Math.max(MIN_STROKE_PX, Math.min(MAX_STROKE_PX, px));
                    g.setStroke(new BasicStroke(clamped, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.setColor(paint(shape.strokeInk(), shape.strokeAlpha()));
                    g.draw(outline);
                }
                drawn++;
            }
        } finally {
            g.dispose();
        }
        return new Result(InkMap.of(image), fitRect, backgroundDropped, drawn);
    }

    /** A white canvas of the given pixel size; the glyph templates are drawn on one of these too. */
    static BufferedImage newCanvas(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * Graphics with the hints every drawing here uses: antialiasing and pure stroke control so a
     * 2 px line lands as a soft 2 px line and not snapped to pixel columns, which is what the cell
     * descriptors are calibrated on. The caller disposes.
     */
    static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    /** Neutral gray for {@code ink} at {@code alpha}; composited over white by Java2D. */
    static Color paint(double ink, double alpha) {
        int level = (int) Math.round(255 * (1.0 - Math.max(0.0, Math.min(1.0, ink))));
        int a = (int) Math.round(255 * Math.max(0.0, Math.min(1.0, alpha)));
        return new Color(level, level, level, a);
    }

    /** The world rectangle to fit: the viewBox when usable, otherwise the shape bounds plus margin. */
    static Rectangle2D fitRect(AsciiArtSvgScanner.Scene scene) {
        if (scene.hasViewBox()) {
            return scene.viewBox();
        }
        return expand(shapeBounds(scene.shapes()));
    }

    /** {@code bounds} grown by {@link #NO_VIEWBOX_MARGIN} on every side; {@code null} stays {@code null}. */
    static Rectangle2D expand(Rectangle2D bounds) {
        if (bounds == null) {
            return null;
        }
        double mx = Math.max(bounds.getWidth(), bounds.getHeight()) * NO_VIEWBOX_MARGIN;
        return new Rectangle2D.Double(
            bounds.getX() - mx, bounds.getY() - mx, bounds.getWidth() + 2 * mx, bounds.getHeight() + 2 * mx);
    }

    /** Union of the shapes' world bounds (stroke included); {@code null} when there is none. */
    static Rectangle2D shapeBounds(List<AsciiArtSvgScanner.Shape> shapes) {
        Rectangle2D union = null;
        for (AsciiArtSvgScanner.Shape shape : shapes) {
            Rectangle2D b = shape.worldBounds();
            union = union == null ? b : union.createUnion(b);
        }
        return union;
    }

    /** Uniform scale, centred, mapping {@code world} into a {@code width}×{@code height} canvas. */
    static AffineTransform fitTransform(Rectangle2D world, int width, int height) {
        double ww = world.getWidth();
        double wh = world.getHeight();
        // A degenerate extent still gets a finite scale so a lone line remains visible.
        double scale = Math.min(
            ww > 0 ? width / ww : Double.MAX_VALUE,
            wh > 0 ? height / wh : Double.MAX_VALUE);
        if (!(scale < Double.MAX_VALUE)) {
            scale = 1.0;
        }
        double offsetX = (width - ww * scale) / 2.0 - world.getX() * scale;
        double offsetY = (height - wh * scale) / 2.0 - world.getY() * scale;
        AffineTransform t = new AffineTransform();
        t.translate(offsetX, offsetY);
        t.scale(scale, scale);
        return t;
    }

    /** The four corners of {@code fitRect} inset by {@link #BACKGROUND_INSET}, in device pixels. */
    private static Point2D[] probes(Rectangle2D fitRect, AffineTransform base) {
        double dx = fitRect.getWidth() * BACKGROUND_INSET;
        double dy = fitRect.getHeight() * BACKGROUND_INSET;
        double x0 = fitRect.getMinX() + dx;
        double x1 = fitRect.getMaxX() - dx;
        double y0 = fitRect.getMinY() + dy;
        double y1 = fitRect.getMaxY() - dy;
        Point2D[] corners = {
            new Point2D.Double(x0, y0), new Point2D.Double(x1, y0),
            new Point2D.Double(x0, y1), new Point2D.Double(x1, y1)};
        for (int i = 0; i < corners.length; i++) {
            corners[i] = base.transform(corners[i], null);
        }
        return corners;
    }

    private static boolean containsAll(java.awt.Shape outline, Point2D[] points) {
        for (Point2D p : points) {
            if (!outline.contains(p)) {
                return false;
            }
        }
        return true;
    }
}
