package de.kortty.core;

import de.kortty.model.SessionJournalAnnotation;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalScreenshotAnnotatorTest {

    private Path journalDir;

    @BeforeMethod
    void setUp() throws IOException {
        journalDir = Files.createTempDirectory("kortty-annotator-test");
        Files.createDirectories(journalDir.resolve("screenshots"));
        BufferedImage white = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = white.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 100);
        g.dispose();
        ImageIO.write(white, "png", journalDir.resolve("screenshots/shot-000004.png").toFile());
    }

    @AfterMethod
    void tearDown() throws IOException {
        if (journalDir == null || !Files.exists(journalDir)) {
            return;
        }
        try (var paths = Files.walk(journalDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static SessionJournalEntry screenshotEntry() {
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setKind(SessionJournalEntryKind.SCREENSHOT);
        entry.setScreenshotFile("screenshots/shot-000004.png");
        return entry;
    }

    private static SessionJournalAnnotation box(double x, double y, double w, double h, String colour) {
        SessionJournalAnnotation annotation = new SessionJournalAnnotation(
            SessionJournalAnnotation.Kind.BOX, colour, 6);
        annotation.setPoints(List.of(x, y, w, h));
        return annotation;
    }

    private Color pixel(String relative, int x, int y) throws IOException {
        return new Color(ImageIO.read(journalDir.resolve(relative).toFile()).getRGB(x, y), true);
    }

    /** A source image with sharp per-pixel detail, so pixelation is measurable. */
    private void writeNoisyScreenshot() throws IOException {
        BufferedImage noisy = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 200; x++) {
                noisy.setRGB(x, y, ((x + y) % 2 == 0) ? 0xffffff : 0x000000);
            }
        }
        ImageIO.write(noisy, "png", journalDir.resolve("screenshots/shot-000004.png").toFile());
    }

    private static SessionJournalAnnotation pixelateBox(double x, double y, double w, double h,
                                                        double width) {
        SessionJournalAnnotation annotation = new SessionJournalAnnotation(
            SessionJournalAnnotation.Kind.PIXELATE, "#e11d48", width);
        annotation.setPoints(List.of(x, y, w, h));
        return annotation;
    }

    @Test
    void aPixelateBoxFlattensItsBlocksAndLeavesTheRestSharp() throws IOException {
        writeNoisyScreenshot();
        SessionJournalEntry entry = screenshotEntry();
        entry.getAnnotations().add(pixelateBox(0, 0, 64, 64, 8));

        SessionJournalScreenshotAnnotator.apply(journalDir, entry);

        // Inside the box every pixel of one block is the same colour...
        Color first = pixel("screenshots/shot-000004.png", 2, 2);
        assertThat(pixel("screenshots/shot-000004.png", 3, 3)).isEqualTo(first);
        // ...and the checkerboard is gone: it averaged to mid grey.
        assertThat(first.getRed()).isGreaterThan(100);
        assertThat(first.getRed()).isLessThan(155);
        // Outside the box the original detail is untouched.
        assertThat(pixel("screenshots/shot-000004.png", 150, 80))
            .isNotEqualTo(pixel("screenshots/shot-000004.png", 151, 80));
    }

    @Test
    void aWiderStrokeMakesTheBlocksCoarser() {
        assertThat(pixelateBox(0, 0, 10, 10, 2).blockSize()).isEqualTo(8);
        assertThat(pixelateBox(0, 0, 10, 10, 12).blockSize()).isEqualTo(24);
    }

    @Test
    void marksStayCrispOnTopOfAPixelatedRegion() throws IOException {
        writeNoisyScreenshot();
        SessionJournalEntry entry = screenshotEntry();
        entry.getAnnotations().add(pixelateBox(0, 0, 100, 100, 8));
        entry.getAnnotations().add(box(10, 10, 40, 40, "#e11d48"));

        SessionJournalScreenshotAnnotator.apply(journalDir, entry);

        // The red outline survives: coarsening happens first, drawing second.
        Color outline = pixel("screenshots/shot-000004.png", 10, 10);
        assertThat(outline.getRed()).isGreaterThan(150);
        assertThat(outline.getGreen()).isLessThan(100);
    }

    @Test
    void aPixelateBoxDraggedPastTheEdgeIsClampedInsteadOfThrowing() throws IOException {
        writeNoisyScreenshot();
        SessionJournalEntry entry = screenshotEntry();
        entry.getAnnotations().add(pixelateBox(150, 60, 400, 400, 8));

        assertThat(SessionJournalScreenshotAnnotator.apply(journalDir, entry)).isTrue();
    }

    @Test
    void theVersionTokenChangesWithTheMarksSoTheCachedImageIsNotReused() {
        SessionJournalAnnotation first = box(10, 10, 60, 40, "#e11d48");
        SessionJournalAnnotation moved = box(11, 10, 60, 40, "#e11d48");
        SessionJournalAnnotation recoloured = box(10, 10, 60, 40, "#0e7490");

        String base = SessionJournalAnnotation.versionToken(List.of(first));
        assertThat(base).isNotEmpty();
        assertThat(SessionJournalAnnotation.versionToken(List.of(first))).isEqualTo(base);
        assertThat(SessionJournalAnnotation.versionToken(List.of(moved))).isNotEqualTo(base);
        assertThat(SessionJournalAnnotation.versionToken(List.of(recoloured))).isNotEqualTo(base);
        assertThat(SessionJournalAnnotation.versionToken(List.of(first, moved))).isNotEqualTo(base);
        // No marks, no token — an untouched screenshot keeps its plain URL.
        assertThat(SessionJournalAnnotation.versionToken(List.of())).isNull();
        assertThat(SessionJournalAnnotation.versionToken(null)).isNull();
    }

    @Test
    void namesTheBackupNextToTheOriginalFile() {
        assertThat(SessionJournalScreenshotAnnotator.originalPathOf("screenshots/shot-000004.png"))
            .isEqualTo("screenshots/shot-000004.orig.png");
        assertThat(SessionJournalScreenshotAnnotator.originalPathOf(null)).isNull();
    }

    @Test
    void burnsTheMarksIntoTheReferencedFileAndKeepsTheCaptureAside() throws IOException {
        SessionJournalEntry entry = screenshotEntry();
        entry.getAnnotations().add(box(10, 10, 60, 40, "#e11d48"));

        assertThat(SessionJournalScreenshotAnnotator.apply(journalDir, entry)).isTrue();

        // The referenced path still exists and now carries the mark...
        assertThat(Files.isRegularFile(journalDir.resolve("screenshots/shot-000004.png"))).isTrue();
        assertThat(pixel("screenshots/shot-000004.png", 10, 10)).isNotEqualTo(Color.WHITE);
        // ...while the untouched capture sits beside it.
        assertThat(Files.isRegularFile(journalDir.resolve("screenshots/shot-000004.orig.png"))).isTrue();
        assertThat(pixel("screenshots/shot-000004.orig.png", 10, 10).getRGB())
            .isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void reEditingReplacesTheMarksInsteadOfStackingThem() throws IOException {
        SessionJournalEntry entry = screenshotEntry();
        entry.getAnnotations().add(box(10, 10, 60, 40, "#e11d48"));
        SessionJournalScreenshotAnnotator.apply(journalDir, entry);

        // Second pass with a mark somewhere else: the first one must be gone, not painted over.
        entry.setAnnotations(List.of(box(120, 60, 40, 20, "#e11d48")));
        SessionJournalScreenshotAnnotator.apply(journalDir, entry);

        assertThat(pixel("screenshots/shot-000004.png", 10, 10).getRGB())
            .isEqualTo(Color.WHITE.getRGB());
        assertThat(pixel("screenshots/shot-000004.png", 120, 60)).isNotEqualTo(Color.WHITE);
    }

    @Test
    void removingEveryMarkRestoresTheUntouchedCapture() throws IOException {
        SessionJournalEntry entry = screenshotEntry();
        entry.getAnnotations().add(box(10, 10, 60, 40, "#e11d48"));
        SessionJournalScreenshotAnnotator.apply(journalDir, entry);

        entry.setAnnotations(List.of());
        assertThat(SessionJournalScreenshotAnnotator.apply(journalDir, entry)).isTrue();

        assertThat(pixel("screenshots/shot-000004.png", 10, 10).getRGB())
            .isEqualTo(Color.WHITE.getRGB());
        // The backup is consumed, so the folder is back to how it started.
        assertThat(Files.exists(journalDir.resolve("screenshots/shot-000004.orig.png"))).isFalse();
    }

    @Test
    void theEditorStartsFromTheUntouchedCapture() throws IOException {
        SessionJournalEntry entry = screenshotEntry();
        assertThat(SessionJournalScreenshotAnnotator.sourceImage(journalDir, entry).getFileName().toString())
            .isEqualTo("shot-000004.png");

        entry.getAnnotations().add(box(10, 10, 60, 40, "#e11d48"));
        SessionJournalScreenshotAnnotator.apply(journalDir, entry);

        assertThat(SessionJournalScreenshotAnnotator.sourceImage(journalDir, entry).getFileName().toString())
            .isEqualTo("shot-000004.orig.png");
    }

    @Test
    void keepsTheImageDimensionsSoCoordinatesStayValid() throws IOException {
        SessionJournalEntry entry = screenshotEntry();
        entry.getAnnotations().add(box(10, 10, 60, 40, "#e11d48"));

        SessionJournalScreenshotAnnotator.apply(journalDir, entry);

        BufferedImage result = ImageIO.read(journalDir.resolve("screenshots/shot-000004.png").toFile());
        assertThat(result.getWidth()).isEqualTo(200);
        assertThat(result.getHeight()).isEqualTo(100);
    }

    @Test
    void refusesAPathEscapingTheJournalDirectory() throws IOException {
        SessionJournalEntry entry = screenshotEntry();
        entry.setScreenshotFile("../../etc/passwd.png");
        entry.getAnnotations().add(box(0, 0, 10, 10, "#e11d48"));

        assertThat(SessionJournalScreenshotAnnotator.apply(journalDir, entry)).isFalse();
        assertThat(SessionJournalScreenshotAnnotator.sourceImage(journalDir, entry)).isNull();
    }

    @Test
    void skipsMarksThatCarryNoUsableGeometry() {
        BufferedImage source = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        SessionJournalAnnotation emptyPen = new SessionJournalAnnotation(
            SessionJournalAnnotation.Kind.PEN, "#e11d48", 6);
        emptyPen.addPoint(5, 5);
        SessionJournalAnnotation textWithoutLabel = new SessionJournalAnnotation(
            SessionJournalAnnotation.Kind.TEXT, "#e11d48", 6);
        textWithoutLabel.setPoints(List.of(5.0, 5.0));

        assertThat(emptyPen.isDrawable()).isFalse();
        assertThat(textWithoutLabel.isDrawable()).isFalse();
        // Rendering them must not throw.
        assertThat(SessionJournalScreenshotAnnotator.render(source, List.of(emptyPen, textWithoutLabel)))
            .isNotNull();
    }

    @Test
    void fallsBackToTheDefaultColourForAnUnusableValue() {
        assertThat(SessionJournalScreenshotAnnotator.color("#7c3aed"))
            .isEqualTo(new Color(0x7c, 0x3a, 0xed));
        assertThat(SessionJournalScreenshotAnnotator.color("#f00"))
            .isEqualTo(new Color(0xff, 0x00, 0x00));
        assertThat(SessionJournalScreenshotAnnotator.color("rebeccapurple"))
            .isEqualTo(Color.decode(SessionJournalAnnotation.DEFAULT_COLOR));
    }

    @Test
    void doesNothingWhenThereIsNoScreenshotAtAll() throws IOException {
        SessionJournalEntry entry = new SessionJournalEntry();
        assertThat(SessionJournalScreenshotAnnotator.apply(journalDir, entry)).isFalse();
    }
}
