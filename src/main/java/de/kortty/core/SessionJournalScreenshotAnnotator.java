package de.kortty.core;

import de.kortty.model.SessionJournalAnnotation;
import de.kortty.model.SessionJournalEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Draws a screenshot's annotations into the PNG the journal actually references.
 *
 * <p>The untouched capture is kept beside it as {@code shot-000004.orig.png} and the annotated
 * result is written back to {@code shot-000004.png}. That keeps the marks re-editable while every
 * consumer — the HTML page, the PDF and Markdown exports, the bundle — keeps using the same file
 * name it always did and needs no change at all.</p>
 *
 * <p>The {@code .orig.png} exists only so korTTY can undo the marks locally, and must never leave
 * the machine. Being referenced by nothing is not enough to guarantee that: the unfiltered bundle
 * copies the whole {@code screenshots/} directory rather than the referenced files, and shipped the
 * originals along with it until {@link #isOriginalBackup(String)} was there to exclude them. Every
 * path that copies screenshots by directory listing has to ask.</p>
 *
 * <p>Marking something out therefore survives an export — but it is still <em>drawing on top</em>,
 * not redaction: the original stays on this machine inside the journal folder.</p>
 */
public final class SessionJournalScreenshotAnnotator {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalScreenshotAnnotator.class);

    /** Suffix of the untouched capture kept for re-editing. */
    public static final String ORIGINAL_SUFFIX = ".orig.png";

    private SessionJournalScreenshotAnnotator() {
    }

    /**
     * Whether this file is an untouched-capture backup, and so must stay inside the journal folder.
     * Takes a bare file name or a journal-relative path.
     */
    public static boolean isOriginalBackup(String fileName) {
        return fileName != null && fileName.endsWith(ORIGINAL_SUFFIX);
    }

    /** The path holding the untouched capture for {@code screenshots/shot-000004.png}. */
    public static String originalPathOf(String screenshotFile) {
        if (screenshotFile == null || screenshotFile.isBlank()) {
            return null;
        }
        int dot = screenshotFile.lastIndexOf('.');
        String base = dot > 0 ? screenshotFile.substring(0, dot) : screenshotFile;
        return base + ORIGINAL_SUFFIX;
    }

    /**
     * The image the editor should start from: the untouched capture when one exists, otherwise the
     * referenced file (which is then still untouched itself).
     */
    public static Path sourceImage(Path journalDir, SessionJournalEntry entry) {
        String referenced = entry.getScreenshotFile();
        if (referenced == null || referenced.isBlank()) {
            return null;
        }
        Path original = resolveInside(journalDir, originalPathOf(referenced));
        if (original != null && Files.isRegularFile(original)) {
            return original;
        }
        return resolveInside(journalDir, referenced);
    }

    /**
     * Writes the entry's annotations into its screenshot. Creates the {@code .orig.png} backup on
     * the first call and re-renders from it afterwards, so repeated edits never stack up.
     *
     * @return true when the file on disk changed.
     */
    public static boolean apply(Path journalDir, SessionJournalEntry entry) throws IOException {
        String referenced = entry.getScreenshotFile();
        Path target = resolveInside(journalDir, referenced);
        if (target == null || !Files.isRegularFile(target)) {
            return false;
        }
        Path original = resolveInside(journalDir, originalPathOf(referenced));
        if (original == null) {
            return false;
        }

        if (!entry.hasAnnotations()) {
            // Every mark removed: restore the untouched capture and drop the backup.
            if (Files.isRegularFile(original)) {
                Files.move(original, target, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
            return false;
        }

        if (!Files.isRegularFile(original)) {
            Files.copy(target, original, StandardCopyOption.REPLACE_EXISTING);
        }
        BufferedImage source = ImageIO.read(original.toFile());
        if (source == null) {
            logger.warn("Could not read the journal screenshot {}", original.getFileName());
            return false;
        }
        BufferedImage annotated = render(source, entry.getAnnotations());
        ImageIO.write(annotated, "png", target.toFile());
        return true;
    }

    /** Draws the marks onto a copy of {@code source}; pure, so it is testable without a journal. */
    public static BufferedImage render(BufferedImage source, List<SessionJournalAnnotation> annotations) {
        BufferedImage canvas = new BufferedImage(
            source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
            // Coarsen first, then draw the marks: a pen stroke or a label placed over a region
            // must stay sharp instead of being blurred away with it.
            for (SessionJournalAnnotation annotation : annotations) {
                if (annotation != null && annotation.isDrawable()
                    && annotation.getKind() == SessionJournalAnnotation.Kind.PIXELATE) {
                    pixelate(canvas, annotation);
                }
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (SessionJournalAnnotation annotation : annotations) {
                if (annotation != null && annotation.isDrawable()) {
                    draw(g, annotation);
                }
            }
        } finally {
            g.dispose();
        }
        return canvas;
    }

    private static void draw(Graphics2D g, SessionJournalAnnotation annotation) {
        g.setColor(color(annotation.getColor()));
        List<Double> p = annotation.getPoints();
        switch (annotation.getKind()) {
            case PIXELATE -> { /* handled before the marks are drawn; see render(). */ }
            case PEN -> {
                g.setStroke(new BasicStroke((float) annotation.getStrokeWidth(),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D.Double path = new Path2D.Double();
                path.moveTo(p.get(0), p.get(1));
                for (int i = 2; i + 1 < p.size(); i += 2) {
                    path.lineTo(p.get(i), p.get(i + 1));
                }
                g.draw(path);
            }
            case BOX -> {
                g.setStroke(new BasicStroke((float) annotation.getStrokeWidth(),
                    BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                // Stored as x, y, width, height with width/height possibly negative when the user
                // dragged up or left; normalize so the rectangle is always drawable.
                double x = Math.min(p.get(0), p.get(0) + p.get(2));
                double y = Math.min(p.get(1), p.get(1) + p.get(3));
                double w = Math.abs(p.get(2));
                double h = Math.abs(p.get(3));
                g.draw(new java.awt.geom.Rectangle2D.Double(x, y, w, h));
            }
            case TEXT -> {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) Math.round(annotation.getFontSize())));
                // A thin dark halo keeps the label readable on a light terminal background too.
                g.setColor(new Color(0, 0, 0, 140));
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx != 0 || dy != 0) {
                            g.drawString(annotation.getText(),
                                (float) (p.get(0) + dx), (float) (p.get(1) + dy));
                        }
                    }
                }
                g.setColor(color(annotation.getColor()));
                g.drawString(annotation.getText(), p.get(0).floatValue(), p.get(1).floatValue());
            }
        }
    }

    /**
     * Replaces a rectangle's contents with its own average colours, one block at a time, so the
     * area reads as texture instead of text while the surrounding context stays intact.
     */
    private static void pixelate(BufferedImage canvas, SessionJournalAnnotation annotation) {
        List<Double> p = annotation.getPoints();
        int x = (int) Math.round(Math.min(p.get(0), p.get(0) + p.get(2)));
        int y = (int) Math.round(Math.min(p.get(1), p.get(1) + p.get(3)));
        int width = (int) Math.round(Math.abs(p.get(2)));
        int height = (int) Math.round(Math.abs(p.get(3)));
        // Clamp to the image; a box dragged past the edge must not throw.
        x = Math.max(0, Math.min(x, canvas.getWidth() - 1));
        y = Math.max(0, Math.min(y, canvas.getHeight() - 1));
        width = Math.min(width, canvas.getWidth() - x);
        height = Math.min(height, canvas.getHeight() - y);
        if (width <= 0 || height <= 0) {
            return;
        }
        int block = annotation.blockSize();
        for (int by = y; by < y + height; by += block) {
            for (int bx = x; bx < x + width; bx += block) {
                int bw = Math.min(block, x + width - bx);
                int bh = Math.min(block, y + height - by);
                canvas.setRGB(bx, by, bw, bh, averageBlock(canvas, bx, by, bw, bh), 0, 0);
            }
        }
    }

    /** One block's average colour, expanded back over the block. */
    private static int[] averageBlock(BufferedImage image, int x, int y, int width, int height) {
        long red = 0;
        long green = 0;
        long blue = 0;
        for (int py = y; py < y + height; py++) {
            for (int px = x; px < x + width; px++) {
                int rgb = image.getRGB(px, py);
                red += (rgb >> 16) & 0xff;
                green += (rgb >> 8) & 0xff;
                blue += rgb & 0xff;
            }
        }
        int count = width * height;
        int average = 0xff000000
            | ((int) (red / count) << 16)
            | ((int) (green / count) << 8)
            | (int) (blue / count);
        int[] block = new int[count];
        java.util.Arrays.fill(block, average);
        return block;
    }

    /** {@code #rrggbb} or {@code #rgb}; anything else falls back to the default marker colour. */
    static Color color(String hex) {
        String value = hex != null ? hex.trim() : "";
        if (!value.startsWith("#")) {
            value = "#" + value;
        }
        if (value.length() == 4) {
            value = "#" + value.charAt(1) + value.charAt(1)
                + value.charAt(2) + value.charAt(2)
                + value.charAt(3) + value.charAt(3);
        }
        try {
            return Color.decode(value);
        } catch (NumberFormatException e) {
            return Color.decode(SessionJournalAnnotation.DEFAULT_COLOR);
        }
    }

    /** Resolves a journal-relative path and refuses anything escaping the journal directory. */
    static Path resolveInside(Path journalDir, String relative) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        Path base = journalDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(relative).normalize();
        return resolved.startsWith(base) ? resolved : null;
    }
}
