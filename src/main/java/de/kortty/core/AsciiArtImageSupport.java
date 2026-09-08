package de.kortty.core;

import de.kortty.model.AsciiArtPictureSize;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Turns an existing raster image (a photo, a logo, an icon) into ASCII art through the same cell
 * matcher the AI drawings use.
 *
 * <p>A photo differs from a model's drawing in two ways the drawing pipeline never meets: it has
 * no white paper — its background is whatever the camera saw — and its tones cluster in the middle
 * of the range, so a plain luminance mapping turns everything into one grey block. The conversion
 * therefore stretches the picture's own tonal range to full contrast first (a percentile stretch,
 * so a few burnt-out or black pixels do not dictate the range) and only then applies the user's
 * brightness, contrast and inversion. Inversion exists because ASCII ink is dark on light: a light
 * subject on a dark background reads correctly only when the tones are flipped.
 *
 * <p>Deliberately free of JavaFX: decoding uses {@link ImageIO}, the conversion works on a plain
 * luminance array, so every rule here is unit-testable with synthetic images.
 */
public final class AsciiArtImageSupport {

    /**
     * Longest edge the decoded image is reduced to before it is kept in memory. The largest grid
     * rasterises at 800 px, so more source detail cannot reach the picture, and a 24-megapixel photo
     * would otherwise be resampled on every slider tick.
     */
    static final int MAX_WORKING_EDGE = 1600;

    /** Percentiles of the luminance histogram that are stretched to pure white and pure black. */
    static final double STRETCH_LOW_PERCENTILE = 0.01;
    static final double STRETCH_HIGH_PERCENTILE = 0.99;
    /** Below this tonal range the stretch would only amplify noise; the image is left as it is. */
    static final double MIN_STRETCH_RANGE = 0.02;

    /** Slider range of the adjustments as the dialog exposes them. */
    public static final double MIN_ADJUSTMENT = -1.0;
    public static final double MAX_ADJUSTMENT = 1.0;

    private AsciiArtImageSupport() {
    }

    /**
     * The user's tonal corrections. {@code brightness} and {@code contrast} run from -1 to 1 where 0
     * changes nothing; {@code invert} flips light and dark for a light subject on a dark background.
     */
    public record Adjustments(double brightness, double contrast, boolean invert) {

        public static final Adjustments NONE = new Adjustments(0.0, 0.0, false);

        public Adjustments {
            brightness = clamp(brightness);
            contrast = clamp(contrast);
        }

        private static double clamp(double value) {
            if (Double.isNaN(value)) {
                return 0.0;
            }
            return Math.max(MIN_ADJUSTMENT, Math.min(MAX_ADJUSTMENT, value));
        }
    }

    /** A decoded image reduced to luminance (0 = black, 1 = white), row-major. */
    public static final class GrayImage {
        private final int width;
        private final int height;
        private final float[] luma;

        GrayImage(int width, int height, float[] luma) {
            this.width = width;
            this.height = height;
            this.luma = luma;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        float lumaAt(int x, int y) {
            int cx = x < 0 ? 0 : (x >= width ? width - 1 : x);
            int cy = y < 0 ? 0 : (y >= height ? height - 1 : y);
            return luma[cy * width + cx];
        }
    }

    // ---- Loading ----

    /**
     * Decodes {@code file} into a working-size luminance image.
     *
     * @throws IOException when the file cannot be read, is not an image format the JDK decodes
     *     (PNG, JPEG, GIF, BMP, WBMP), or is implausibly large
     */
    public static GrayImage load(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (!AiRasterImageSupport.hasSaneDimensions(bytes)) {
            throw new IOException("not a supported image or too large to decode");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("not a supported image format");
        }
        return toGray(image);
    }

    /** Reduces {@code image} to luminance at working size (see {@link #MAX_WORKING_EDGE}). */
    public static GrayImage toGray(BufferedImage image) {
        int sourceWidth = image.getWidth();
        int sourceHeight = image.getHeight();
        double scale = Math.min(1.0, (double) MAX_WORKING_EDGE / Math.max(sourceWidth, sourceHeight));
        int width = Math.max(1, (int) Math.round(sourceWidth * scale));
        int height = Math.max(1, (int) Math.round(sourceHeight * scale));
        float[] source = new float[sourceWidth * sourceHeight];
        int[] row = new int[sourceWidth];
        for (int y = 0; y < sourceHeight; y++) {
            image.getRGB(0, y, sourceWidth, 1, row, 0, sourceWidth);
            for (int x = 0; x < sourceWidth; x++) {
                int argb = row[x];
                int alpha = (argb >>> 24) & 0xFF;
                double r = ((argb >> 16) & 0xFF) / 255.0;
                double g = ((argb >> 8) & 0xFF) / 255.0;
                double b = (argb & 0xFF) / 255.0;
                double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                // Transparent pixels show the paper behind them, which for ASCII art is white.
                double coverage = alpha / 255.0;
                source[y * sourceWidth + x] = (float) (luminance * coverage + (1.0 - coverage));
            }
        }
        GrayImage full = new GrayImage(sourceWidth, sourceHeight, source);
        return width == sourceWidth && height == sourceHeight ? full : resample(full, width, height);
    }

    // ---- Conversion ----

    /**
     * Converts {@code image} into a picture of at most {@code size} cells, keeping its aspect ratio
     * (a landscape photo uses fewer rows, a portrait one fewer columns). Returns {@code null} when
     * nothing but blank cells remain.
     */
    public static String convert(GrayImage image, AsciiArtPictureSize size, Adjustments adjustments) {
        if (image == null || image.width() <= 0 || image.height() <= 0) {
            return null;
        }
        AsciiArtPictureSize grid = size != null ? size : AsciiArtPictureSize.DEFAULT;
        Adjustments adjust = adjustments != null ? adjustments : Adjustments.NONE;
        int canvasWidth = grid.columns() * AsciiArtRasterizer.CELL_WIDTH_PX;
        int canvasHeight = grid.rows() * AsciiArtRasterizer.CELL_HEIGHT_PX;
        double scale = Math.min((double) canvasWidth / image.width(), (double) canvasHeight / image.height());
        int targetWidth = Math.max(1, (int) Math.round(image.width() * scale));
        int targetHeight = Math.max(1, (int) Math.round(image.height() * scale));
        GrayImage fitted = resample(image, targetWidth, targetHeight);
        float[] tones = stretch(fitted.luma);
        applyAdjustments(tones, adjust);

        BufferedImage canvas = AsciiArtRasterizer.newCanvas(canvasWidth, canvasHeight);
        int offsetX = (canvasWidth - targetWidth) / 2;
        int offsetY = (canvasHeight - targetHeight) / 2;
        int[] row = new int[targetWidth];
        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                int level = (int) Math.round(tones[y * targetWidth + x] * 255.0);
                row[x] = 0xFF000000 | (level << 16) | (level << 8) | level;
            }
            canvas.setRGB(offsetX, offsetY + y, targetWidth, 1, row, 0, targetWidth);
        }
        String[] lines = AsciiArtGlyphs.convert(AsciiArtRasterizer.InkMap.of(canvas), grid.columns(), grid.rows());
        String picture = AsciiArtGlyphs.trimPicture(lines);
        return picture == null || picture.isBlank() ? null : picture;
    }

    /**
     * Stretches the tonal range so the darkest percentile becomes black and the lightest white.
     * Returns a new array; the input is left untouched.
     */
    static float[] stretch(float[] luma) {
        float[] out = luma.clone();
        int[] histogram = new int[256];
        for (float value : luma) {
            histogram[(int) Math.round(Math.max(0f, Math.min(1f, value)) * 255f)]++;
        }
        double low = percentile(histogram, luma.length, STRETCH_LOW_PERCENTILE) / 255.0;
        double high = percentile(histogram, luma.length, STRETCH_HIGH_PERCENTILE) / 255.0;
        if (high - low < MIN_STRETCH_RANGE) {
            return out;
        }
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) Math.max(0.0, Math.min(1.0, (out[i] - low) / (high - low)));
        }
        return out;
    }

    /** The histogram bin below which {@code fraction} of the samples lie. */
    private static int percentile(int[] histogram, int total, double fraction) {
        // At least one sample, so an empty leading bin never poses as the darkest tone.
        long target = Math.max(1, Math.round(total * fraction));
        long seen = 0;
        for (int bin = 0; bin < histogram.length; bin++) {
            seen += histogram[bin];
            if (seen >= target) {
                return bin;
            }
        }
        return histogram.length - 1;
    }

    /**
     * Applies brightness (a shift of up to half the range), contrast (a factor from 0 to 4 around
     * mid grey) and inversion in place.
     */
    static void applyAdjustments(float[] tones, Adjustments adjust) {
        double factor = adjust.contrast() >= 0 ? 1.0 + 3.0 * adjust.contrast() : 1.0 + adjust.contrast();
        double shift = adjust.brightness() * 0.5;
        for (int i = 0; i < tones.length; i++) {
            double value = (tones[i] - 0.5) * factor + 0.5 + shift;
            value = Math.max(0.0, Math.min(1.0, value));
            tones[i] = (float) (adjust.invert() ? 1.0 - value : value);
        }
    }

    /**
     * Resamples {@code image} to {@code width} x {@code height}: box averaging when shrinking (so
     * fine texture becomes an even tone instead of aliasing) and bilinear interpolation when
     * enlarging. Deterministic and free of any AWT scaling pipeline.
     */
    static GrayImage resample(GrayImage image, int width, int height) {
        float[] out = new float[width * height];
        double scaleX = (double) image.width() / width;
        double scaleY = (double) image.height() / height;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double x0 = x * scaleX;
                double y0 = y * scaleY;
                double x1 = (x + 1) * scaleX;
                double y1 = (y + 1) * scaleY;
                int startX = (int) Math.floor(x0);
                int startY = (int) Math.floor(y0);
                int endX = Math.max(startX + 1, (int) Math.ceil(x1));
                int endY = Math.max(startY + 1, (int) Math.ceil(y1));
                if (endX - startX <= 1 && endY - startY <= 1) {
                    out[y * width + x] = bilinear(image, (x + 0.5) * scaleX - 0.5, (y + 0.5) * scaleY - 0.5);
                    continue;
                }
                double sum = 0.0;
                int count = 0;
                for (int sy = startY; sy < endY; sy++) {
                    for (int sx = startX; sx < endX; sx++) {
                        sum += image.lumaAt(sx, sy);
                        count++;
                    }
                }
                out[y * width + x] = (float) (sum / count);
            }
        }
        return new GrayImage(width, height, out);
    }

    private static float bilinear(GrayImage image, double x, double y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double fx = x - x0;
        double fy = y - y0;
        double top = image.lumaAt(x0, y0) * (1 - fx) + image.lumaAt(x0 + 1, y0) * fx;
        double bottom = image.lumaAt(x0, y0 + 1) * (1 - fx) + image.lumaAt(x0 + 1, y0 + 1) * fx;
        return (float) (top * (1 - fy) + bottom * fy);
    }
}
