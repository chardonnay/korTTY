package de.kortty.core;

import de.kortty.core.AsciiArtImageSupport.Adjustments;
import de.kortty.core.AsciiArtImageSupport.GrayImage;
import de.kortty.model.AsciiArtPictureSize;
import org.testng.annotations.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AsciiArtImageSupportTest {

    private static final String DENSE = "#%@";

    // ---- Conversion ----

    @Test
    void aBlackSquareOnWhiteBecomesADenseBlockWithBlankSurroundings() {
        String picture = AsciiArtImageSupport.convert(
            AsciiArtImageSupport.toGray(squareOn(Color.WHITE, Color.BLACK, 200, 50, 100)),
            AsciiArtPictureSize.MEDIUM, Adjustments.NONE);
        print("black square", picture);

        assertThat(picture).isNotNull();
        List<String> lines = picture.lines().toList();
        // The square covers half of the canvas in each direction: about 30 x 15 cells after trimming.
        assertThat(lines.size()).isAtLeast(12);
        assertThat(lines.size()).isAtMost(17);
        String middle = lines.get(lines.size() / 2);
        assertThat(DENSE.indexOf(middle.charAt(middle.length() / 2))).isAtLeast(0);
        assertThat(middle.strip().length()).isAtLeast(26);
        assertThat(middle.strip().length()).isAtMost(34);
    }

    @Test
    void aWideImageUsesFewerRowsAndKeepsItsAspect() {
        BufferedImage wide = new BufferedImage(400, 100, BufferedImage.TYPE_INT_RGB);
        fill(wide, Color.BLACK);

        String picture = AsciiArtImageSupport.convert(AsciiArtImageSupport.toGray(wide),
            AsciiArtPictureSize.MEDIUM, Adjustments.NONE);

        assertThat(picture).isNotNull();
        List<String> lines = picture.lines().toList();
        // 400:100 on a square canvas is 60 columns by about 7.5 rows.
        assertThat(lines.size()).isAtLeast(6);
        assertThat(lines.size()).isAtMost(8);
        assertThat(lines.get(lines.size() / 2).strip()).hasLength(60);
    }

    @Test
    void aLowContrastImageIsStretchedSoItsShapeShows() {
        // Background 45 % grey, square 55 % grey: without the stretch both land in the same tone.
        Color background = new Color(115, 115, 115);
        Color square = new Color(140, 140, 140);
        String picture = AsciiArtImageSupport.convert(
            AsciiArtImageSupport.toGray(squareOn(background, square, 200, 50, 100)),
            AsciiArtPictureSize.MEDIUM, Adjustments.NONE);
        print("low contrast", picture);

        assertThat(picture).isNotNull();
        assertThat(distinctInk(picture)).isAtLeast(2);
        // The lighter square is now white paper inside dark surroundings.
        List<String> lines = picture.lines().toList();
        String middle = lines.get(lines.size() / 2);
        assertThat(middle.charAt(middle.length() / 2)).isEqualTo(' ');
    }

    @Test
    void invertingALightSubjectOnADarkBackgroundGivesTheDarkOnLightResult() {
        String dark = AsciiArtImageSupport.convert(
            AsciiArtImageSupport.toGray(squareOn(Color.WHITE, Color.BLACK, 200, 50, 100)),
            AsciiArtPictureSize.MEDIUM, Adjustments.NONE);
        String inverted = AsciiArtImageSupport.convert(
            AsciiArtImageSupport.toGray(squareOn(Color.BLACK, Color.WHITE, 200, 50, 100)),
            AsciiArtPictureSize.MEDIUM, new Adjustments(0, 0, true));

        assertThat(inverted).isEqualTo(dark);
    }

    @Test
    void brightnessLightensAndContrastFlattens() {
        GrayImage image = AsciiArtImageSupport.toGray(gradient(300, 100));
        String plain = AsciiArtImageSupport.convert(image, AsciiArtPictureSize.MEDIUM, Adjustments.NONE);
        String bright = AsciiArtImageSupport.convert(image, AsciiArtPictureSize.MEDIUM, new Adjustments(1.0, 0, false));
        String flat = AsciiArtImageSupport.convert(image, AsciiArtPictureSize.MEDIUM, new Adjustments(0, -1.0, false));
        print("gradient", plain);

        assertThat(plain).isNotNull();
        assertThat(distinctInk(plain)).isAtLeast(4);
        assertThat(inkCells(bright)).isLessThan(inkCells(plain));
        // Contrast -1 collapses every tone onto mid grey: one character everywhere.
        assertThat(distinctInk(flat)).isEqualTo(1);
    }

    @Test
    void transparentPixelsCountAsPaper() {
        BufferedImage transparent = new BufferedImage(120, 120, BufferedImage.TYPE_INT_ARGB);

        assertThat(AsciiArtImageSupport.convert(AsciiArtImageSupport.toGray(transparent),
            AsciiArtPictureSize.SMALL, Adjustments.NONE)).isNull();
    }

    @Test
    void aTinyImageIsEnlargedInsteadOfVanishing() {
        BufferedImage tiny = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        fill(tiny, Color.BLACK);

        String picture = AsciiArtImageSupport.convert(AsciiArtImageSupport.toGray(tiny),
            AsciiArtPictureSize.SMALL, Adjustments.NONE);

        assertThat(picture).isNotNull();
        assertThat(inkCells(picture)).isAtLeast(40 * 20 / 2);
    }

    @Test
    void nullAndEmptyInputsYieldNoPicture() {
        assertThat(AsciiArtImageSupport.convert(null, AsciiArtPictureSize.MEDIUM, Adjustments.NONE)).isNull();
        BufferedImage white = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        fill(white, Color.WHITE);
        assertThat(AsciiArtImageSupport.convert(AsciiArtImageSupport.toGray(white),
            AsciiArtPictureSize.MEDIUM, null)).isNull();
    }

    // ---- Helpers under test ----

    @Test
    void adjustmentsAreClampedToTheSliderRange() {
        Adjustments wild = new Adjustments(5.0, -7.0, true);

        assertThat(wild.brightness()).isEqualTo(1.0);
        assertThat(wild.contrast()).isEqualTo(-1.0);
        assertThat(new Adjustments(Double.NaN, Double.NaN, false).brightness()).isEqualTo(0.0);
    }

    @Test
    void shrinkingAveragesAndEnlargingInterpolates() {
        float[] checker = new float[16];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                checker[y * 4 + x] = (x + y) % 2 == 0 ? 1f : 0f;
            }
        }
        GrayImage small = AsciiArtImageSupport.resample(new GrayImage(4, 4, checker), 2, 2);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                assertThat((double) small.lumaAt(x, y)).isWithin(1e-6).of(0.5);
            }
        }

        GrayImage ramp = new GrayImage(2, 1, new float[] {0f, 1f});
        GrayImage large = AsciiArtImageSupport.resample(ramp, 4, 1);
        assertThat((double) large.lumaAt(0, 0)).isLessThan((double) large.lumaAt(3, 0));
        assertThat((double) large.lumaAt(1, 0)).isLessThan((double) large.lumaAt(2, 0));
    }

    @Test
    void theWorkingImageIsCappedAtTheLongestEdge() {
        BufferedImage huge = new BufferedImage(3200, 100, BufferedImage.TYPE_INT_RGB);

        GrayImage gray = AsciiArtImageSupport.toGray(huge);

        assertThat(gray.width()).isEqualTo(AsciiArtImageSupport.MAX_WORKING_EDGE);
        assertThat(gray.height()).isEqualTo(50);
    }

    @Test
    void stretchLeavesAFlatImageAlone() {
        float[] flat = {0.5f, 0.5f, 0.505f, 0.5f};

        assertThat(AsciiArtImageSupport.stretch(flat)).isEqualTo(flat);
    }

    // ---- Loading ----

    @Test
    void aPngFileLoadsAndAnUnsupportedFileIsRefused() throws Exception {
        Path dir = Files.createTempDirectory("ascii-art-image");
        try {
            Path png = dir.resolve("square.png");
            ImageIO.write(squareOn(Color.WHITE, Color.BLACK, 80, 20, 40), "png", png.toFile());
            GrayImage loaded = AsciiArtImageSupport.load(png);
            assertThat(loaded.width()).isEqualTo(80);
            assertThat(loaded.height()).isEqualTo(80);
            assertThat((double) loaded.lumaAt(40, 40)).isWithin(1e-3).of(0.0);
            assertThat((double) loaded.lumaAt(2, 2)).isWithin(1e-3).of(1.0);

            Path text = dir.resolve("notes.txt");
            Files.writeString(text, "not an image");
            try {
                AsciiArtImageSupport.load(text);
                throw new AssertionError("expected an IOException");
            } catch (IOException expected) {
                assertThat(expected.getMessage()).contains("not a supported image");
            }
        } finally {
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(dir);
        }
    }

    // ---- Fixtures ----

    /** A {@code size} x {@code size} image of {@code background} with a centred square of {@code foreground}. */
    private static BufferedImage squareOn(Color background, Color foreground, int size, int inset, int side) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(background);
            g.fillRect(0, 0, size, size);
            g.setColor(foreground);
            g.fillRect(inset, inset, side, side);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage gradient(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            int level = (int) Math.round(255.0 * x / (width - 1));
            int rgb = (level << 16) | (level << 8) | level;
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    private static void fill(BufferedImage image, Color color) {
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            g.dispose();
        }
    }

    private static int inkCells(String picture) {
        if (picture == null) {
            return 0;
        }
        int count = 0;
        for (char c : picture.toCharArray()) {
            if (c != ' ' && c != '\n') {
                count++;
            }
        }
        return count;
    }

    private static int distinctInk(String picture) {
        return (int) picture.chars().filter(c -> c != ' ' && c != '\n').distinct().count();
    }

    private static void print(String title, String picture) {
        System.out.println("---- " + title);
        if (picture != null) {
            picture.lines().forEach(line -> System.out.println("|" + line));
        }
    }
}
