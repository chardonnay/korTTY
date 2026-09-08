package de.kortty.core;

import org.testng.annotations.Test;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;

/**
 * Every rendering test prints its picture so a human can judge it from the test output; the
 * assertions themselves are geometric properties, never golden strings, because the glyph
 * matcher is tuned empirically and a golden string would freeze one tuning.
 */
class AsciiArtPictureRendererTest {

    private static final int COLUMNS = 60;
    private static final int ROWS = 30;
    private static final String OPEN = "<svg viewBox=\"0 0 100 100\">";

    /** Outlined roof and walls, black door: the classic line-drawn house. */
    private static final String HOUSE = OPEN
        + "<polygon points=\"15,50 50,10 85,50\" fill=\"none\" stroke=\"black\" stroke-width=\"1\"/>"
        + "<rect x=\"20\" y=\"50\" width=\"60\" height=\"45\" fill=\"none\" stroke=\"black\" stroke-width=\"1\"/>"
        + "<rect x=\"42\" y=\"65\" width=\"16\" height=\"30\" fill=\"black\"/>"
        + "</svg>";

    /** F12: three black discs, a #aaa face, black eyes and nose, a smile as a stroked Q path. */
    private static final String MICKEY = OPEN
        + "<circle cx=\"25\" cy=\"25\" r=\"17\" fill=\"black\"/>"
        + "<circle cx=\"75\" cy=\"25\" r=\"17\" fill=\"black\"/>"
        + "<circle cx=\"50\" cy=\"58\" r=\"34\" fill=\"black\"/>"
        + "<ellipse cx=\"50\" cy=\"64\" rx=\"24\" ry=\"22\" fill=\"#aaa\"/>"
        + "<ellipse cx=\"41\" cy=\"52\" rx=\"5\" ry=\"8\" fill=\"black\"/>"
        + "<ellipse cx=\"59\" cy=\"52\" rx=\"5\" ry=\"8\" fill=\"black\"/>"
        + "<ellipse cx=\"50\" cy=\"68\" rx=\"7\" ry=\"5\" fill=\"black\"/>"
        + "<path d=\"M34 74 Q50 88 66 74\" fill=\"none\" stroke=\"black\" stroke-width=\"3\"/>"
        + "</svg>";

    /** F13: #aaa sun and ground, #555 trees with black trunks, #555 wall, black roof, chimney, door, white window. */
    private static final String FOREST_HOUSE = OPEN
        + "<circle cx=\"84\" cy=\"16\" r=\"9\" fill=\"#aaa\"/>"
        + "<rect x=\"0\" y=\"82\" width=\"100\" height=\"18\" fill=\"#aaa\"/>"
        + "<polygon points=\"12,82 4,58 20,58\" fill=\"#555\"/>"
        + "<polygon points=\"12,66 2,42 22,42\" fill=\"#555\"/>"
        + "<rect x=\"10\" y=\"78\" width=\"4\" height=\"8\" fill=\"black\"/>"
        + "<polygon points=\"88,82 80,58 96,58\" fill=\"#555\"/>"
        + "<polygon points=\"88,66 78,42 98,42\" fill=\"#555\"/>"
        + "<rect x=\"86\" y=\"78\" width=\"4\" height=\"8\" fill=\"black\"/>"
        + "<rect x=\"32\" y=\"56\" width=\"36\" height=\"28\" fill=\"#555\"/>"
        + "<polygon points=\"26,58 50,24 74,58\" fill=\"black\"/>"
        + "<rect x=\"60\" y=\"32\" width=\"6\" height=\"14\" fill=\"black\"/>"
        + "<rect x=\"44\" y=\"68\" width=\"10\" height=\"16\" fill=\"black\"/>"
        + "<rect x=\"36\" y=\"62\" width=\"8\" height=\"8\" fill=\"white\"/>"
        + "</svg>";

    /** A complete but tiny house: 8 of the 100 units, the answer of a model that ignores the size rule. */
    private static final String TINY_HOUSE = OPEN
        + "<polygon points=\"46,50 50,46 54,50\" fill=\"black\"/>"
        + "<rect x=\"47\" y=\"50\" width=\"6\" height=\"5\" fill=\"#555\"/>"
        + "<rect x=\"49\" y=\"52\" width=\"2\" height=\"3\" fill=\"black\"/>"
        + "</svg>";

    // ---- Helpers ----

    /**
     * The lighthouse example shipped in the bundled ASCII-art skill, read from the resource: the
     * prompt shows it to the model, so the converter must accept it and the copy detector must
     * recognise it, and an edit to the resource must not silently break either.
     */
    private static String bundledExample() {
        return AiActionSkillPromptSupport.asciiArtExampleSvg();
    }

    private static AsciiArtPictureRenderer.RenderResult render(String name, String svg) throws InterruptedException {
        return render(name, svg, COLUMNS, ROWS);
    }

    private static AsciiArtPictureRenderer.RenderResult render(String name, String svg, int columns, int rows)
            throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult result = AsciiArtPictureRenderer.render(svg, columns, rows, null);
        System.out.println("---- " + name + " (" + columns + "x" + rows + "): "
            + AsciiArtPictureRenderer.describe(result) + " " + result.warnings());
        if (result.text() != null) {
            for (String line : result.text().split("\n")) {
                System.out.println("|" + line);
            }
        }
        return result;
    }

    private static String[] lines(AsciiArtPictureRenderer.RenderResult result) {
        return result.text().split("\n", -1);
    }

    private static int count(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static int nonBlank(String text) {
        return text.replace("\n", "").replace(" ", "").length();
    }

    private static String region(String[] lines, int top, int bottom, int left, int right) {
        StringBuilder sb = new StringBuilder();
        for (int r = top; r < bottom; r++) {
            String line = lines[r];
            for (int c = left; c < right; c++) {
                sb.append(c < line.length() ? line.charAt(c) : ' ');
            }
        }
        return sb.toString();
    }

    private static int width(String[] lines) {
        int w = 0;
        for (String line : lines) {
            w = Math.max(w, line.length());
        }
        return w;
    }

    private static int toneRank(char c) {
        return AsciiArtGlyphs.TONAL_RAMP.indexOf(c);
    }

    // ---- Fixtures F1-F13 ----

    @Test
    void f1aRectangleCoveringTheCanvasIsABackgroundAndLeavesNoInk() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = render("F1 full-canvas rect",
            OPEN + "<rect x=\"0\" y=\"0\" width=\"100\" height=\"100\"/></svg>");

        assertThat(r.rejection()).isEqualTo(AsciiArtPictureRenderer.RejectReason.NO_INK);
        assertThat(r.isSoftRejection()).isFalse();
        assertThat(r.isUsable()).isFalse();
        assertThat(r.text()).isNull();
        assertThat(r.stats().backgroundDropped()).isTrue();
    }

    @Test
    void f2aDiagonalLineReadsAsSlashes() throws InterruptedException {
        // One cell right per cell up: the slope a terminal's '/' has.
        AsciiArtPictureRenderer.RenderResult r = render("F2 diagonal", OPEN
            + "<line x1=\"27.5\" y1=\"91.667\" x2=\"69.167\" y2=\"8.333\" stroke=\"black\" stroke-width=\"1\"/></svg>");

        assertThat(r.isUsable()).isTrue();
        String[] lines = lines(r);
        int rowsWithSlash = 0;
        for (String line : lines) {
            if (line.indexOf('/') >= 0) {
                rowsWithSlash++;
            }
        }
        assertThat(rowsWithSlash).isAtLeast((int) Math.ceil(0.8 * lines.length));
        assertThat(count(r.text(), '\\')).isEqualTo(0);
        assertThat(count(r.text(), '/')).isAtLeast(20);
        // Each row's slash sits left of the one above: the line rises to the right.
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].indexOf('/') >= 0 && lines[i - 1].indexOf('/') >= 0) {
                assertThat(lines[i].indexOf('/')).isLessThan(lines[i - 1].indexOf('/'));
            }
        }
    }

    @Test
    void f3aRingHasAnEmptyMiddleAndInkInEveryQuadrant() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = render("F3 ring",
            OPEN + "<circle cx=\"50\" cy=\"50\" r=\"40\" fill=\"none\" stroke=\"black\" stroke-width=\"4\"/></svg>");

        assertThat(r.isUsable()).isTrue();
        String[] lines = lines(r);
        int h = lines.length;
        int w = width(lines);
        assertThat(region(lines, h * 2 / 5, h * 3 / 5, w * 2 / 5, w * 3 / 5).trim()).isEmpty();
        assertThat(nonBlank(region(lines, 0, h / 2, 0, w / 2))).isGreaterThan(0);
        assertThat(nonBlank(region(lines, 0, h / 2, w / 2, w))).isGreaterThan(0);
        assertThat(nonBlank(region(lines, h / 2, h, 0, w / 2))).isGreaterThan(0);
        assertThat(nonBlank(region(lines, h / 2, h, w / 2, w))).isGreaterThan(0);
    }

    @Test
    void f4aDiscIsDenseInTheMiddleAndLighterAtTheRim() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = render("F4 disc", OPEN + "<circle cx=\"50\" cy=\"50\" r=\"40\"/></svg>");

        assertThat(r.isUsable()).isTrue();
        String[] lines = lines(r);
        String middle = lines[lines.length / 2];
        assertThat(middle.charAt(middle.length() / 2)).isEqualTo('@');
        assertThat(count(r.text(), '@')).isGreaterThan(nonBlank(r.text()) / 2);
        // The rim is drawn with lighter or line glyphs, never the solid rung.
        assertThat(lines[0].trim().chars().filter(c -> c != '@').count()).isGreaterThan(0);
        assertThat(lines[lines.length - 1].trim().chars().filter(c -> c != '@').count()).isGreaterThan(0);
    }

    @Test
    void f5aRectangleOutlineLeavesItsInsideEmpty() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = render("F5 rect outline",
            OPEN + "<rect x=\"10\" y=\"10\" width=\"80\" height=\"80\" fill=\"none\" stroke=\"black\" stroke-width=\"3\"/></svg>");

        assertThat(r.isUsable()).isTrue();
        String[] lines = lines(r);
        int h = lines.length;
        int w = width(lines);
        assertThat(region(lines, 3, h - 3, 3, w - 3).trim()).isEmpty();
        for (int row = 0; row < h; row++) {
            assertThat(lines[row].trim()).isNotEmpty();
        }
        assertThat(region(lines, 0, h, 0, 1).trim()).isNotEmpty();
        assertThat(region(lines, 0, h, w - 1, w).trim()).isNotEmpty();
    }

    @Test
    void f6aHouseHasASlopedRoofStraightWallsAndADarkDoor() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = render("F6 house", HOUSE);

        assertThat(r.isAccepted()).isTrue();
        String[] lines = lines(r);
        int h = lines.length;
        int w = width(lines);
        String upperLeft = region(lines, 0, h / 2, 0, w / 2);
        String upperRight = region(lines, 0, h / 2, w / 2, w);
        assertThat(count(upperLeft, '/')).isAtLeast(5);
        assertThat(count(upperLeft, '\\')).isAtMost(1);
        assertThat(count(upperRight, '\\')).isAtLeast(5);
        assertThat(count(upperRight, '/')).isAtMost(1);
        assertThat(count(region(lines, h / 2, h, 0, w / 4), '|')).isAtLeast(5);
        assertThat(count(region(lines, h / 2, h, w * 3 / 4, w), '|')).isAtLeast(5);
        String door = region(lines, h * 3 / 4, h - 1, w * 2 / 5, w * 3 / 5);
        assertThat(door.chars().filter(c -> c == '#' || c == '%' || c == '@').count())
            .isGreaterThan(door.length() / 2);
    }

    @Test
    void f7aTransformedArcIsCentredAndBulgesTheRightWay() throws InterruptedException {
        String svg = OPEN + "<g transform=\"translate(50 50) rotate(90)\">"
            + "<path d=\"M-30 0 A30 30 0 0 1 30 0\" fill=\"none\" stroke=\"black\" stroke-width=\"4\"/></g></svg>";
        AsciiArtPictureRenderer.RenderResult r = render("F7 transformed arc", svg);

        assertThat(r.isUsable()).isTrue();
        AsciiArtSvgScanner.Scene scene = AsciiArtSvgScanner.scan(svg);
        AsciiArtRasterizer.Result raster = AsciiArtRasterizer.rasterize(
            scene, COLUMNS, ROWS, AsciiArtRasterizer.fitRect(scene), false);
        double sum = 0;
        double sx = 0;
        double sy = 0;
        for (int y = 0; y < raster.ink().height; y++) {
            for (int x = 0; x < raster.ink().width; x++) {
                double ink = raster.ink().inkAt(x, y);
                sum += ink;
                sx += ink * x;
                sy += ink * y;
            }
        }
        // The arc's centroid lies right of its chord (it bulges to the right), on the canvas' middle row.
        assertThat(sy / sum).isWithin(raster.ink().height * 0.05).of(raster.ink().height / 2.0);
        assertThat(sx / sum).isGreaterThan(raster.ink().width / 2.0);
        assertThat(sx / sum).isLessThan(raster.ink().width * 0.7);
        String[] lines = lines(r);
        assertThat(lines.length).isAtLeast(15);
        assertThat(lines[lines.length / 2].trim().length()).isLessThan(4);
        assertThat(lines[lines.length / 2].indexOf(lines[lines.length / 2].trim()))
            .isGreaterThan(width(lines) / 2);
    }

    @Test
    void f8aTruncatedDocumentRendersWhatItHas() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = render("F8 truncated", OPEN
            + "<circle cx=\"30\" cy=\"50\" r=\"20\"/><rect x=\"55\" y=\"30\" width=\"30\" height=\"40\"/>"
            + "<polygon points=\"10,90 50,");

        assertThat(r.isUsable()).isTrue();
        assertThat(r.stats().inputTruncated()).isTrue();
        assertThat(r.stats().shapes()).isEqualTo(2);
        assertThat(r.rejection()).isEqualTo(AsciiArtPictureRenderer.RejectReason.TOO_FEW_SHAPES);
        assertThat(r.isSoftRejection()).isTrue();
    }

    @Test
    void f9LightColoursGiveLightGlyphsWhiteGivesNothingAndHalfOpacityAUniformMidTone() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult light = render("F9a light disc",
            OPEN + "<circle cx=\"50\" cy=\"50\" r=\"40\" fill=\"#ddd\"/></svg>");
        assertThat(light.isUsable()).isTrue();
        for (char dark : "%#@".toCharArray()) {
            assertThat(count(light.text(), dark)).isEqualTo(0);
        }

        AsciiArtPictureRenderer.RenderResult white = render("F9b white disc",
            OPEN + "<circle cx=\"50\" cy=\"50\" r=\"40\" fill=\"white\"/></svg>");
        assertThat(white.rejection()).isEqualTo(AsciiArtPictureRenderer.RejectReason.NO_INK);
        assertThat(white.stats().backgroundDropped()).isFalse();

        AsciiArtPictureRenderer.RenderResult half = render("F9c half opacity",
            OPEN + "<rect x=\"20\" y=\"20\" width=\"60\" height=\"60\" opacity=\"0.5\"/></svg>");
        assertThat(half.isUsable()).isTrue();
        String[] lines = lines(half);
        String inside = region(lines, 2, lines.length - 2, 2, width(lines) - 2);
        char tone = inside.charAt(0);
        assertThat(tone).isNotEqualTo('@');
        assertThat(tone).isNotEqualTo(' ');
        assertThat(toneRank(tone)).isAtLeast(3);
        assertThat(count(inside, tone)).isEqualTo(inside.length());
    }

    @Test
    void f10aSkyRectangleIsDroppedAndTheHouseBehindItSurvives() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = render("F10 sky + house", OPEN
            + "<rect width=\"100\" height=\"100\" fill=\"#aaa\"/>"
            + "<polygon points=\"10,50 50,10 90,50\"/>"
            + "<rect x=\"15\" y=\"50\" width=\"70\" height=\"45\" fill=\"#555\"/>"
            + "<rect x=\"42\" y=\"65\" width=\"16\" height=\"30\" fill=\"black\"/>"
            + "<rect x=\"22\" y=\"58\" width=\"14\" height=\"12\" fill=\"white\"/>"
            + "</svg>");

        assertThat(r.isAccepted()).isTrue();
        assertThat(r.stats().backgroundDropped()).isTrue();
        assertThat(r.stats().shapes()).isEqualTo(4);
        assertThat(count(r.text(), '@')).isGreaterThan(50);
        assertThat(count(r.text(), '%')).isGreaterThan(50);
        assertThat(r.stats().inkCoverage()).isLessThan(0.7);
    }

    @Test
    void f11aBackgroundLaterInTheDocumentIsNotSuppressed() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = render("F11 late slab", OPEN
            + "<circle cx=\"20\" cy=\"20\" r=\"5\"/><circle cx=\"80\" cy=\"20\" r=\"5\"/><circle cx=\"50\" cy=\"50\" r=\"5\"/>"
            + "<rect width=\"100\" height=\"100\"/></svg>");

        assertThat(r.rejection()).isEqualTo(AsciiArtPictureRenderer.RejectReason.ALL_DARK);
        assertThat(r.stats().backgroundDropped()).isFalse();
    }

    @Test
    void f12MickeyMouseHasTwoEarsALighterFaceAndDarkerEyes() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = render("F12 Mickey Mouse", MICKEY);

        assertThat(r.isAccepted()).isTrue();
        String[] lines = lines(r);
        // Ears: the top rows hold exactly two separate runs of ink.
        for (int row = 0; row < 3; row++) {
            assertThat(lines[row].trim().split("\\s{2,}")).hasLength(2);
        }
        // Face: the row through the eyes has head ink, then face tone, then an eye, then face tone again.
        Pattern faceWithEye = Pattern.compile("[@%#].*\\+.*[@%#].*\\+.*[@%#]");
        boolean seen = false;
        int faceCells = 0;
        for (String line : lines) {
            seen |= faceWithEye.matcher(line).find();
            faceCells += count(line, '+');
        }
        assertThat(seen).isTrue();
        assertThat(faceCells).isGreaterThan(80);
        assertThat(count(r.text(), '@')).isGreaterThan(faceCells);
    }

    @Test
    void f13aSmallHouseInTheForestHasRoofWindowTreesAndGround() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = render("F13 house in the forest", FOREST_HOUSE);

        assertThat(r.isAccepted()).isTrue();
        String[] lines = lines(r);
        int h = lines.length;
        int w = width(lines);
        // Roof: a row that runs '/' … black … '\' in the middle third.
        Pattern roof = Pattern.compile("/@+\\\\");
        boolean roofSeen = false;
        for (int row = 0; row < h / 2; row++) {
            roofSeen |= roof.matcher(lines[row]).find();
        }
        assertThat(roofSeen).isTrue();
        // Window: an empty pocket inside the dense wall.
        Pattern window = Pattern.compile("%\\|? {2,}\\|?[%@]");
        boolean windowSeen = false;
        for (String line : lines) {
            windowSeen |= window.matcher(line).find();
        }
        assertThat(windowSeen).isTrue();
        // Trees left and right of the house, drawn in the #555 tone.
        assertThat(count(region(lines, h / 3, h * 3 / 4, 0, w / 4), '%')).isAtLeast(10);
        assertThat(count(region(lines, h / 3, h * 3 / 4, w * 3 / 4, w), '%')).isAtLeast(10);
        // Ground: the bottom rows are one lighter tone across the whole width.
        String ground = lines[h - 1];
        char groundTone = ground.charAt(ground.length() / 2);
        assertThat(toneRank(groundTone)).isGreaterThan(0);
        assertThat(toneRank(groundTone)).isLessThan(toneRank('%'));
        assertThat(count(ground, groundTone)).isGreaterThan(w * 9 / 10);
        // Sun: ink in the top-right corner region, none in the top-left.
        assertThat(nonBlank(region(lines, 0, 5, w * 2 / 3, w))).isGreaterThan(10);
        assertThat(nonBlank(region(lines, 0, 5, 0, w / 3))).isEqualTo(0);
    }

    // ---- Rejections ----

    @Test
    void aHairlineIsTooSmallToBeAPicture() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = render("thin line", OPEN
            + "<line x1=\"10\" y1=\"51.5\" x2=\"90\" y2=\"51.5\" stroke=\"black\" stroke-width=\"0.5\"/></svg>");

        assertThat(r.rejection()).isEqualTo(AsciiArtPictureRenderer.RejectReason.TOO_SMALL);
        assertThat(r.isSoftRejection()).isFalse();
        assertThat(r.text()).isNull();
    }

    @Test
    void aCompleteButTinyDrawingIsTooSmallNotInkless() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = render("tiny house", TINY_HOUSE);

        // Under the 1 % ink floor, but the defect is the size: only the TOO_SMALL hint asks the
        // model to enlarge the subject; the NO_INK hint would have it redraw shapes it already drew.
        assertThat(r.stats().inkCoverage()).isLessThan(AsciiArtPictureRenderer.NO_INK_MAX_COVERAGE);
        assertThat(r.rejection()).isEqualTo(AsciiArtPictureRenderer.RejectReason.TOO_SMALL);
        assertThat(r.isSoftRejection()).isTrue();
        assertThat(r.text()).isNotNull();
        assertThat(r.stats().trimmedWidth()).isAtLeast(AsciiArtPictureRenderer.MIN_TRIMMED_WIDTH);
        assertThat(r.stats().trimmedHeight()).isAtLeast(AsciiArtPictureRenderer.MIN_TRIMMED_HEIGHT);
    }

    @Test
    void aSpeckIsStillInkless() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = render("speck", OPEN + "<circle cx=\"50\" cy=\"50\" r=\"1.5\"/></svg>");

        assertThat(r.rejection()).isEqualTo(AsciiArtPictureRenderer.RejectReason.NO_INK);
        assertThat(r.text()).isNull();
    }

    @Test
    void aPictureUsingLittleOfTheCanvasIsSoftlyTooSmallButStillReturned() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = render("small house", HOUSE.replace(OPEN, "<svg viewBox=\"0 0 300 300\">"));

        assertThat(r.rejection()).isEqualTo(AsciiArtPictureRenderer.RejectReason.TOO_SMALL);
        assertThat(r.isSoftRejection()).isTrue();
        assertThat(r.isUsable()).isTrue();
        assertThat(r.stats().boundsCoverage()).isLessThan(AsciiArtPictureRenderer.MIN_BOUNDS_COVERAGE);
        assertThat(r.text()).contains("/");
    }

    @Test
    void aSlabOverMostOfTheCanvasIsAllDark() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = render("97% slab",
            OPEN + "<rect x=\"3\" y=\"0\" width=\"97\" height=\"100\"/></svg>");
        assertThat(r.rejection()).isEqualTo(AsciiArtPictureRenderer.RejectReason.ALL_DARK);
        assertThat(r.text()).isNull();

        AsciiArtPictureRenderer.RenderResult tone = render("85% light slab",
            OPEN + "<rect x=\"15\" y=\"0\" width=\"85\" height=\"100\" fill=\"#aaa\"/>"
                + "<circle cx=\"7\" cy=\"50\" r=\"5\"/></svg>");
        assertThat(tone.rejection()).isEqualTo(AsciiArtPictureRenderer.RejectReason.ALL_DARK);
    }

    @Test
    void textOnlyDocumentsHaveNoShapesAndProseHasNoSvg() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult text = AsciiArtPictureRenderer.render(
            OPEN + "<text x=\"10\" y=\"50\">A house</text></svg>", COLUMNS, ROWS, null);
        assertThat(text.rejection()).isEqualTo(AsciiArtPictureRenderer.RejectReason.NO_SHAPES);
        assertThat(text.warnings().toString()).contains("text");

        AsciiArtPictureRenderer.RenderResult prose = AsciiArtPictureRenderer.renderReply(
            "I'm sorry, I cannot draw pictures.", COLUMNS, ROWS, null);
        assertThat(prose.rejection()).isEqualTo(AsciiArtPictureRenderer.RejectReason.NO_SVG);
        assertThat(prose.text()).isNull();
        assertThat(AsciiArtPictureRenderer.renderReply(null, COLUMNS, ROWS, null).rejection())
            .isEqualTo(AsciiArtPictureRenderer.RejectReason.NO_SVG);
    }

    @Test
    void brokenPathsRejectOnlyWhenTooLittleRemains() throws InterruptedException {
        String broken = "<path d=\"M10 10 L 50 50 L 90 x\"/>";
        AsciiArtPictureRenderer.RenderResult few = AsciiArtPictureRenderer.render(
            OPEN + broken + "<circle cx=\"50\" cy=\"50\" r=\"30\"/></svg>", COLUMNS, ROWS, null);
        assertThat(few.rejection()).isEqualTo(AsciiArtPictureRenderer.RejectReason.BAD_PATH);
        assertThat(few.warnings().toString()).contains("d=\"M10 10 L 50 50 L 90 x\"");

        AsciiArtPictureRenderer.RenderResult enough = render("broken path among enough shapes",
            OPEN + broken + "<circle cx=\"30\" cy=\"50\" r=\"25\"/><circle cx=\"70\" cy=\"50\" r=\"25\"/>"
                + "<rect x=\"40\" y=\"80\" width=\"20\" height=\"15\"/></svg>");
        assertThat(enough.isAccepted()).isTrue();
        assertThat(enough.warnings().toString()).contains("Unreadable path data");
    }

    @Test
    void shapesWithoutExtentAreOutsideTheCanvas() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = AsciiArtPictureRenderer.render(
            OPEN + "<polygon points=\"50,50 50,50 50,50\"/><polygon points=\"20,20 20,20 20,20\"/></svg>",
            COLUMNS, ROWS, null);
        assertThat(r.rejection()).isEqualTo(AsciiArtPictureRenderer.RejectReason.OUTSIDE_CANVAS);
    }

    @Test
    void aDrawingOffTheCanvasIsRefittedInsteadOfRejected() throws InterruptedException {
        String big = HOUSE.replace(OPEN, OPEN + "<g transform=\"scale(8)\">").replace("</svg>", "</g></svg>");
        AsciiArtPictureRenderer.RenderResult r = render("0..800 house refitted", big);

        assertThat(r.isAccepted()).isTrue();
        assertThat(r.stats().refitted()).isTrue();
        assertThat(r.stats().boundsCoverage()).isGreaterThan(0.8);
        assertThat(r.text()).contains("/");
    }

    @Test
    void aStrayShapeFarAwayDoesNotShrinkThePicture() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = render("house with a stray dot",
            HOUSE.replace("</svg>", "<circle cx=\"5000\" cy=\"5000\" r=\"2\"/></svg>"));

        assertThat(r.isAccepted()).isTrue();
        assertThat(r.stats().refitted()).isFalse();
        assertThat(r.stats().boundsCoverage()).isGreaterThan(0.8);
    }

    @Test
    void theBundledLighthouseExamplePassesTheValidator() throws InterruptedException {
        String example = bundledExample();
        assertThat(example).startsWith("<svg viewBox=\"0 0 100 100\">");
        AsciiArtPictureRenderer.RenderResult r = render("bundled lighthouse example", example);

        assertThat(r.isAccepted()).isTrue();
        assertThat(r.warnings()).isEmpty();
        assertThat(r.stats().boundsCoverage()).isAtLeast(0.6);
        assertThat(r.text().chars().filter(c -> c != ' ' && c != '\n').distinct().count()).isAtLeast(3);
        // The white lantern window stays a hole inside the black lantern room: on at least one
        // row, dense ink on both sides of a short run of blanks.
        assertThat(Pattern.compile("[@%]+[^@ \\n]* {1,6}[^@ \\n]*[@%]+").matcher(r.text()).find()).isTrue();
    }

    // ---- Copied example ----

    @Test
    void theBundledExampleIsRecognisedThroughWhitespaceAndQuoteChanges() throws InterruptedException {
        String example = bundledExample();
        String reformatted = example.replace("\n", "").replace("  ", "").replace("><", ">\n\t<")
            .replace('"', '\'').replace("' ", "'  ");
        assertThat(reformatted).isNotEqualTo(example);
        AsciiArtPictureRenderer.RenderResult r = AsciiArtPictureRenderer.renderReply(
            "Here is the drawing:\n```svg\n" + reformatted + "\n```", COLUMNS, ROWS, example);
        assertThat(r.rejection()).isEqualTo(AsciiArtPictureRenderer.RejectReason.COPIED_EXAMPLE);
        assertThat(r.text()).isNull();

        String extended = example.replace("</svg>", "<circle cx=\"80\" cy=\"30\" r=\"6\"/></svg>");
        assertThat(AsciiArtPictureRenderer.render(extended, COLUMNS, ROWS, example).rejection())
            .isEqualTo(AsciiArtPictureRenderer.RejectReason.COPIED_EXAMPLE);
        // The detector also accepts the whole skill text as the reference, not only the bare document.
        String skillText = "Example:\n```svg\n" + example + "\n```\n";
        assertThat(AsciiArtPictureRenderer.isCopiedExample(example, skillText)).isTrue();
    }

    @Test
    void anOwnDrawingIsNotACopyEvenWhenItSharesAShapeOrTwo() throws InterruptedException {
        String example = bundledExample();
        String own = HOUSE.replace("</svg>",
            "<rect x=\"0\" y=\"78\" width=\"100\" height=\"22\" fill=\"#aaa\"/></svg>");
        assertThat(AsciiArtPictureRenderer.render(own, COLUMNS, ROWS, example).isAccepted()).isTrue();
        assertThat(AsciiArtPictureRenderer.render(FOREST_HOUSE, COLUMNS, ROWS, example).isAccepted()).isTrue();
        assertThat(AsciiArtPictureRenderer.render(HOUSE, COLUMNS, ROWS, null).isAccepted()).isTrue();
        assertThat(AsciiArtPictureRenderer.isCopiedExample(HOUSE, "no svg here")).isFalse();
    }

    // ---- Result shape and behaviour ----

    @Test
    void trimmingKeepsTheRelativePlacementOfSeparatedShapes() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = render("two blocks", OPEN
            + "<rect x=\"5\" y=\"5\" width=\"20\" height=\"20\"/>"
            + "<rect x=\"60\" y=\"60\" width=\"35\" height=\"35\"/>"
            + "<rect x=\"30\" y=\"30\" width=\"10\" height=\"10\"/></svg>");

        assertThat(r.isUsable()).isTrue();
        String[] lines = lines(r);
        assertThat(lines[0].charAt(0)).isNotEqualTo(' ');
        String last = lines[lines.length - 1];
        int indent = last.indexOf(last.trim());
        assertThat(indent).isAtLeast(COLUMNS / 2);
        assertThat(r.stats().trimmedWidth()).isEqualTo(width(lines));
        assertThat(r.stats().trimmedHeight()).isEqualTo(lines.length);
    }

    @Test
    void gridSizesAreClampedIntoTheSupportedRange() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult tiny = AsciiArtPictureRenderer.render(HOUSE, 1, 1, null);
        assertThat(width(lines(tiny))).isAtMost(AsciiArtPictureRenderer.MIN_COLUMNS);
        assertThat(lines(tiny).length).isAtMost(AsciiArtPictureRenderer.MIN_ROWS);

        AsciiArtPictureRenderer.RenderResult huge = AsciiArtPictureRenderer.render(HOUSE, 5000, 5000, null);
        assertThat(width(lines(huge))).isAtMost(AsciiArtPictureRenderer.MAX_COLUMNS);
        assertThat(lines(huge).length).isAtMost(AsciiArtPictureRenderer.MAX_ROWS);
        assertThat(lines(huge).length).isGreaterThan(ROWS);
    }

    @Test
    void renderReplyToleratesFencesProseAndReasoning() throws InterruptedException {
        String reply = "<think>Let me draw a house.</think>\nHere is the picture:\n```xml\n" + HOUSE + "\n```\nHope you like it!";
        AsciiArtPictureRenderer.RenderResult r = AsciiArtPictureRenderer.renderReply(reply, COLUMNS, ROWS, null);
        assertThat(r.isAccepted()).isTrue();
        assertThat(AsciiArtPictureRenderer.locateSvg(reply)).isEqualTo(HOUSE);
        assertThat(AsciiArtPictureRenderer.locateSvg("nothing")).isNull();
    }

    @Test
    void renderingIsDeterministicAndFastAtTheLargestGrid() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult warmUp = AsciiArtPictureRenderer.render(FOREST_HOUSE, 100, 50, null);
        long start = System.nanoTime();
        AsciiArtPictureRenderer.RenderResult first = AsciiArtPictureRenderer.render(FOREST_HOUSE, 100, 50, null);
        long millis = (System.nanoTime() - start) / 1_000_000;
        AsciiArtPictureRenderer.RenderResult second = AsciiArtPictureRenderer.render(FOREST_HOUSE, 100, 50, null);
        System.out.println("---- XL forest house rendered in " + millis + " ms");

        assertThat(first.text()).isEqualTo(second.text());
        assertThat(first.text()).isEqualTo(warmUp.text());
        assertThat(first.isAccepted()).isTrue();
        assertThat(millis).isLessThan(500);
    }

    @Test
    void anInterruptedThreadStopsTheRasteriser() {
        StringBuilder many = new StringBuilder(OPEN);
        for (int i = 0; i < 250; i++) {
            many.append("<circle cx=\"").append(10 + i % 80).append("\" cy=\"").append(10 + (i * 7) % 80).append("\" r=\"6\"/>");
        }
        many.append("</svg>");
        Thread.currentThread().interrupt();
        try {
            AsciiArtPictureRenderer.render(many.toString(), COLUMNS, ROWS, null);
            throw new AssertionError("expected an InterruptedException");
        } catch (InterruptedException expected) {
            // The rasteriser checks the flag every 100 shapes and gives up.
        } finally {
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    @Test
    void rejectReasonsCarryHintsKeysAndHardness() {
        for (AsciiArtPictureRenderer.RejectReason reason : AsciiArtPictureRenderer.RejectReason.values()) {
            assertThat(reason.i18nKey()).matches("[a-z][A-Za-z]*");
            boolean retryable = reason != AsciiArtPictureRenderer.RejectReason.RENDERER_UNAVAILABLE
                && reason != AsciiArtPictureRenderer.RejectReason.TRUNCATED_EMPTY;
            assertThat(reason.repairHint() != null).isEqualTo(retryable);
            if (retryable) {
                assertThat(reason.repairHint()).endsWith(".");
            }
        }
        assertThat(AsciiArtPictureRenderer.RejectReason.NO_SVG.i18nKey()).isEqualTo("noSvg");
        assertThat(AsciiArtPictureRenderer.RejectReason.TOO_FEW_SHAPES.i18nKey()).isEqualTo("tooFewShapes");
        assertThat(AsciiArtPictureRenderer.RejectReason.TRUNCATED_EMPTY.i18nKey()).isEqualTo("truncatedEmpty");
        assertThat(AsciiArtPictureRenderer.RejectReason.TOO_FEW_SHAPES.isHard()).isFalse();
        assertThat(AsciiArtPictureRenderer.RejectReason.TOO_SMALL.isHard()).isTrue();
        assertThat(AsciiArtPictureRenderer.RejectReason.TRUNCATED_EMPTY.isHard()).isTrue();
        assertThat(AsciiArtPictureRenderer.RejectReason.NO_SVG.repairHint()).contains("viewBox=\"0 0 100 100\"");
    }

    @Test
    void repairFeedbackNamesTheIgnoredElementsAndTheBrokenPath() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult noShapes = AsciiArtPictureRenderer.render(
            OPEN + "<use href=\"#house\"/><text x=\"10\" y=\"50\">A house</text></svg>", COLUMNS, ROWS, null);
        assertThat(noShapes.rejection()).isEqualTo(AsciiArtPictureRenderer.RejectReason.NO_SHAPES);
        assertThat(noShapes.repairFeedback()).startsWith(AsciiArtPictureRenderer.RejectReason.NO_SHAPES.repairHint());
        assertThat(noShapes.repairFeedback()).contains("ignored: use, text.");

        AsciiArtPictureRenderer.RenderResult badPath = AsciiArtPictureRenderer.render(
            OPEN + "<path d=\"M10 10 L90 90 Lx\"/></svg>", COLUMNS, ROWS, null);
        assertThat(badPath.rejection()).isEqualTo(AsciiArtPictureRenderer.RejectReason.BAD_PATH);
        assertThat(badPath.repairFeedback()).startsWith(AsciiArtPictureRenderer.RejectReason.BAD_PATH.repairHint());
        assertThat(badPath.repairFeedback()).contains("d=\"M10 10 L90 90 Lx\"");

        AsciiArtPictureRenderer.RenderResult prose = AsciiArtPictureRenderer.renderReply("no", COLUMNS, ROWS, null);
        assertThat(prose.repairFeedback()).isEqualTo(AsciiArtPictureRenderer.RejectReason.NO_SVG.repairHint());
        assertThat(AsciiArtPictureRenderer.render(HOUSE, COLUMNS, ROWS, null).repairFeedback()).isNull();
        AsciiArtPictureRenderer.RenderResult unavailable = new AsciiArtPictureRenderer.RenderResult(
            null, AsciiArtPictureRenderer.RejectReason.RENDERER_UNAVAILABLE, false, null, null);
        assertThat(unavailable.repairFeedback()).isNull();
    }

    @Test
    void resultsReportUsabilityConsistently() {
        AsciiArtPictureRenderer.Stats stats = AsciiArtPictureRenderer.Stats.NONE;
        AsciiArtPictureRenderer.RenderResult accepted = new AsciiArtPictureRenderer.RenderResult("/\\", null, false, stats, null);
        AsciiArtPictureRenderer.RenderResult soft = new AsciiArtPictureRenderer.RenderResult(
            "/\\", AsciiArtPictureRenderer.RejectReason.TOO_FEW_SHAPES, true, stats, List.of());
        AsciiArtPictureRenderer.RenderResult hard = new AsciiArtPictureRenderer.RenderResult(
            null, AsciiArtPictureRenderer.RejectReason.NO_INK, false, stats, List.of());
        AsciiArtPictureRenderer.RenderResult blankSoft = new AsciiArtPictureRenderer.RenderResult(
            "  ", AsciiArtPictureRenderer.RejectReason.TOO_SMALL, true, stats, List.of());

        assertThat(accepted.isAccepted()).isTrue();
        assertThat(accepted.isUsable()).isTrue();
        assertThat(accepted.warnings()).isEmpty();
        assertThat(soft.isAccepted()).isFalse();
        assertThat(soft.isSoftRejection()).isTrue();
        assertThat(soft.isUsable()).isTrue();
        assertThat(hard.isSoftRejection()).isFalse();
        assertThat(hard.isUsable()).isFalse();
        assertThat(blankSoft.isUsable()).isFalse();
    }

    @Test
    void theInsideShareWeighsShapesByTheirExtent() {
        AsciiArtSvgScanner.Scene mostlyInside = AsciiArtSvgScanner.scan(
            HOUSE.replace("</svg>", "<circle cx=\"5000\" cy=\"5000\" r=\"2\"/></svg>"));
        assertThat(AsciiArtPictureRenderer.insideShare(mostlyInside, new Rectangle2D.Double(0, 0, 100, 100)))
            .isGreaterThan(0.9);
        AsciiArtSvgScanner.Scene mostlyOutside = AsciiArtSvgScanner.scan(
            OPEN + "<rect x=\"0\" y=\"0\" width=\"800\" height=\"800\"/></svg>");
        assertThat(AsciiArtPictureRenderer.insideShare(mostlyOutside, new Rectangle2D.Double(0, 0, 100, 100)))
            .isLessThan(0.05);
    }

    @Test
    void warningsListIgnoredElementsAndUnknownColours() throws InterruptedException {
        AsciiArtPictureRenderer.RenderResult r = AsciiArtPictureRenderer.render(
            HOUSE.replace("</svg>", "<text x=\"1\" y=\"1\">hi</text><circle cx=\"50\" cy=\"30\" r=\"4\" fill=\"papayawhip\"/></svg>"),
            COLUMNS, ROWS, null);

        assertThat(r.isAccepted()).isTrue();
        List<String> joined = new ArrayList<>(r.warnings());
        assertThat(joined.toString()).contains("text");
        assertThat(joined.toString()).contains("papayawhip");
        assertThat(r.stats().droppedElements()).isEqualTo(1);
    }
}
