package de.kortty.core;

import org.testng.annotations.Test;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AsciiArtSvgScannerTest {

    private static AsciiArtSvgScanner.Scene scan(String body) {
        return AsciiArtSvgScanner.scan("<svg viewBox=\"0 0 100 100\">" + body + "</svg>");
    }

    private static AsciiArtSvgScanner.Shape only(AsciiArtSvgScanner.Scene scene) {
        assertThat(scene.shapes()).hasSize(1);
        return scene.shapes().get(0);
    }

    // ---- Basic shapes and defaults ----

    @Test
    void everyDrawableElementIsReadWithSvgDefaults() {
        AsciiArtSvgScanner.Scene scene = scan(
            "<rect x=\"10\" y=\"10\" width=\"20\" height=\"30\"/>"
                + "<circle cx=\"50\" cy=\"50\" r=\"10\"/>"
                + "<ellipse cx=\"50\" cy=\"50\" rx=\"10\" ry=\"5\"/>"
                + "<line x1=\"0\" y1=\"0\" x2=\"10\" y2=\"10\"/>"
                + "<polyline points=\"0,0 10,10 20,0\"/>"
                + "<polygon points=\"0,0 10,10 20,0\"/>"
                + "<path d=\"M10 10 L20 20\"/>");

        assertThat(scene.shapes()).hasSize(7);
        assertThat(scene.viewBox()).isEqualTo(new Rectangle2D.Double(0, 0, 100, 100));
        assertThat(scene.droppedElements()).isEqualTo(0);
        assertThat(scene.truncated()).isFalse();
        assertThat(scene.capsTripped()).isFalse();
        AsciiArtSvgScanner.Shape rect = scene.shapes().get(0);
        assertThat(rect.element()).isEqualTo("rect");
        // SVG defaults: black fill, no stroke, stroke-width 1.
        assertThat(rect.fillInk()).isEqualTo(1.0);
        assertThat(rect.fillAlpha()).isEqualTo(1.0);
        assertThat(rect.hasStroke()).isFalse();
        assertThat(rect.strokeWidth()).isEqualTo(AsciiArtSvgScanner.DEFAULT_STROKE_WIDTH);
        assertThat(rect.worldBounds()).isEqualTo(new Rectangle2D.Double(10, 10, 20, 30));
    }

    @Test
    void styleDeclarationsOverridePresentationAttributes() {
        AsciiArtSvgScanner.Shape shape = only(scan(
            "<rect x=\"1\" y=\"1\" width=\"5\" height=\"5\" fill=\"black\" stroke-width=\"9\""
                + " style=\"fill:#fff; stroke: black; stroke-width: 3\"/>"));

        assertThat(shape.fillInk()).isEqualTo(0.0);
        assertThat(shape.strokeInk()).isEqualTo(1.0);
        assertThat(shape.strokeWidth()).isEqualTo(3.0);
        assertThat(shape.hasStroke()).isTrue();
    }

    @Test
    void groupsPassFillStrokeAndOpacityDownToTheirChildren() {
        AsciiArtSvgScanner.Shape shape = only(scan(
            "<g fill=\"#555\" stroke=\"black\" stroke-width=\"2\" opacity=\"0.5\">"
                + "<rect x=\"1\" y=\"1\" width=\"5\" height=\"5\" fill-opacity=\"0.5\"/></g>"));

        assertThat(shape.fillInk()).isWithin(0.01).of(1.0 - 0x55 / 255.0);
        assertThat(shape.fillAlpha()).isWithin(1e-9).of(0.25);
        assertThat(shape.strokeInk()).isEqualTo(1.0);
        assertThat(shape.strokeAlpha()).isWithin(1e-9).of(0.5);
        assertThat(shape.strokeWidth()).isEqualTo(2.0);
    }

    @Test
    void roundedRectsPercentLengthsAndUnitsAreAccepted() {
        AsciiArtSvgScanner.Scene scene = AsciiArtSvgScanner.scan(
            "<svg viewBox=\"0 0 200 100\"><rect width=\"50%\" height=\"50%\" rx=\"4\"/>"
                + "<circle cx=\"10px\" cy=\"10px\" r=\"5px\"/></svg>");

        assertThat(scene.shapes()).hasSize(2);
        assertThat(scene.shapes().get(0).worldBounds()).isEqualTo(new Rectangle2D.Double(0, 0, 100, 50));
        assertThat(scene.shapes().get(0).geometry()).isInstanceOf(java.awt.geom.RoundRectangle2D.class);
        assertThat(scene.shapes().get(1).worldBounds()).isEqualTo(new Rectangle2D.Double(5, 5, 10, 10));
    }

    @Test
    void widthAndHeightStandInForAMissingViewBox() {
        AsciiArtSvgScanner.Scene scene = AsciiArtSvgScanner.scan(
            "<svg width=\"300\" height=\"150px\"><rect width=\"10\" height=\"10\"/></svg>");
        assertThat(scene.viewBox()).isEqualTo(new Rectangle2D.Double(0, 0, 300, 150));

        AsciiArtSvgScanner.Scene none = AsciiArtSvgScanner.scan("<svg><rect width=\"10\" height=\"10\"/></svg>");
        assertThat(none.viewBox()).isNull();
        assertThat(none.hasViewBox()).isFalse();
    }

    // ---- Colours ----

    @Test
    void coloursMapToInkAndAlpha() {
        assertThat(fillOf("#fff").fillInk()).isEqualTo(0.0);
        assertThat(fillOf("#000").fillInk()).isEqualTo(1.0);
        assertThat(fillOf("rgb(0%, 0%, 0%)").fillInk()).isEqualTo(1.0);
        assertThat(fillOf("rgb(255,255,255)").fillInk()).isEqualTo(0.0);
        assertThat(fillOf("#00000080").fillAlpha()).isWithin(0.01).of(0.5);
        assertThat(fillOf("#00000080").fillInk()).isEqualTo(1.0);
        assertThat(fillOf("rgba(0,0,0,0.25)").fillAlpha()).isWithin(1e-9).of(0.25);
        assertThat(fillOf("hsl(0, 0%, 100%)").fillInk()).isEqualTo(0.0);
        assertThat(fillOf("none").fillInk()).isNaN();
        assertThat(fillOf("none").hasFill()).isFalse();
        assertThat(fillOf("white").fillInk()).isEqualTo(0.0);
        assertThat(fillOf("currentColor").fillInk()).isEqualTo(1.0);
        // #aaa lands between #555 and white, which is what the four-tone contract relies on.
        assertThat(fillOf("#aaa").fillInk()).isLessThan(fillOf("#555").fillInk());
        assertThat(fillOf("#aaa").fillInk()).isGreaterThan(0.2);
    }

    @Test
    void unknownColourNamesBecomeMidGrayWithAWarningButGradientsDoNot() {
        AsciiArtSvgScanner.Scene named = scan("<rect width=\"5\" height=\"5\" fill=\"papayawhip\"/>");
        assertThat(only(named).fillInk()).isEqualTo(AsciiArtSvgScanner.UNKNOWN_PAINT_INK);
        assertThat(named.warnings()).hasSize(1);
        assertThat(named.warnings().get(0)).contains("papayawhip");

        AsciiArtSvgScanner.Scene gradient = scan("<rect width=\"5\" height=\"5\" fill=\"url(#g)\"/>");
        assertThat(only(gradient).fillInk()).isEqualTo(AsciiArtSvgScanner.UNKNOWN_PAINT_INK);
        assertThat(gradient.warnings()).isEmpty();
    }

    private static AsciiArtSvgScanner.Shape fillOf(String paint) {
        return only(scan("<rect width=\"5\" height=\"5\" fill=\"" + paint + "\"/>"));
    }

    // ---- Transforms ----

    @Test
    void nestedTransformsComposeOuterToInner() {
        AsciiArtSvgScanner.Shape shape = only(scan(
            "<g transform=\"translate(10,20)\"><g transform=\"scale(2)\">"
                + "<rect x=\"5\" y=\"5\" width=\"10\" height=\"10\"/></g></g>"));

        assertThat(shape.worldBounds()).isEqualTo(new Rectangle2D.Double(20, 30, 20, 20));
        assertThat(shape.scale()).isWithin(1e-9).of(2.0);
    }

    @Test
    void rotationAboutACentreAndMatrixAreHonoured() {
        AsciiArtSvgScanner.Shape rotated = only(scan(
            "<rect x=\"40\" y=\"10\" width=\"20\" height=\"40\" transform=\"rotate(90 50 50)\"/>"));
        Rectangle2D b = rotated.worldBounds();
        assertThat(b.getX()).isWithin(1e-9).of(50);
        assertThat(b.getY()).isWithin(1e-9).of(40);
        assertThat(b.getWidth()).isWithin(1e-9).of(40);
        assertThat(b.getHeight()).isWithin(1e-9).of(20);

        AsciiArtSvgScanner.Shape matrix = only(scan(
            "<rect width=\"10\" height=\"10\" transform=\"matrix(1 0 0 1 30 40)\"/>"));
        assertThat(matrix.worldBounds()).isEqualTo(new Rectangle2D.Double(30, 40, 10, 10));
    }

    @Test
    void strokeWidthFollowsTheTransformScaleInWorldBounds() {
        AsciiArtSvgScanner.Shape shape = only(scan(
            "<g transform=\"scale(4)\"><line x1=\"0\" y1=\"5\" x2=\"10\" y2=\"5\" stroke=\"black\" stroke-width=\"1\"/></g>"));
        // 4× scale: the 1-unit stroke is 4 world units wide, so the bounds grow by 2 on each side.
        assertThat(shape.worldBounds()).isEqualTo(new Rectangle2D.Double(-2, 18, 44, 4));
    }

    @Test
    void unreadableTransformsAreIgnoredWithAWarningInsteadOfLosingTheShape() {
        List<String> warnings = new ArrayList<>();
        assertThat(AsciiArtSvgScanner.parseTransform("frobnicate(1) translate(5 5)", warnings).getTranslateX())
            .isWithin(1e-9).of(5.0);
        assertThat(warnings).isNotEmpty();
    }

    // ---- Path data ----

    @Test
    void relativeAndImplicitPathCommandsAreFollowed() {
        AsciiArtSvgScanner.Shape square = only(scan("<path d=\"M10 10 l10 0 0 10 -10 0 z\"/>"));
        assertThat(square.worldBounds()).isEqualTo(new Rectangle2D.Double(10, 10, 10, 10));

        AsciiArtSvgScanner.Shape packed = only(scan("<path d=\"M10-5L.5.5\"/>"));
        Rectangle2D b = packed.worldBounds();
        assertThat(b.getMinX()).isWithin(1e-9).of(0.5);
        assertThat(b.getMaxX()).isWithin(1e-9).of(10);
        assertThat(b.getMinY()).isWithin(1e-9).of(-5);
        assertThat(b.getMaxY()).isWithin(1e-9).of(0.5);
    }

    @Test
    void curvesAndSmoothContinuationsProduceGeometry() {
        AsciiArtSvgScanner.Shape shape = only(scan(
            "<path d=\"M0 50 C 20 0, 40 0, 50 50 S 80 100, 100 50 Q 110 20 120 50 T 140 50 H 150 V 60\"/>"));
        Rectangle2D b = shape.worldBounds();
        assertThat(b.getMinX()).isWithin(1e-9).of(0);
        assertThat(b.getMaxX()).isWithin(1e-9).of(150);
        // The first cubic dips towards its control points at y=0, the smooth continuation mirrors
        // that control below the curve: tight bounds prove the control points were used.
        assertThat(b.getMinY()).isLessThan(30.0);
        assertThat(b.getMaxY()).isGreaterThan(80.0);
        assertThat(b.getMaxY()).isLessThan(100.0);
    }

    @Test
    void arcFlagsMayBeGluedTogetherAndArcsBulgeToTheRequestedSide() {
        // "0110 10" is large-arc 0, sweep 1, then x=10 y=10; read as one number the path would break.
        AsciiArtSvgScanner.Shape glued = only(scan("<path d=\"M10 10 a5 5 0 0110 10\"/>"));
        Rectangle2D g = glued.worldBounds();
        assertThat(g.getMinX()).isAtMost(10.0);
        assertThat(g.getMaxX()).isAtLeast(20.0);
        assertThat(g.getWidth()).isLessThan(12.5);

        // A half circle over the top: sweep=1 is clockwise on screen (y grows downwards).
        Rectangle2D over = only(scan("<path d=\"M0 50 A50 50 0 0 1 100 50\"/>")).worldBounds();
        assertThat(over.getMinY()).isWithin(0.01).of(0);
        assertThat(over.getMaxY()).isWithin(0.01).of(50);
        assertThat(over.getMinX()).isWithin(0.01).of(0);
        assertThat(over.getMaxX()).isWithin(0.01).of(100);

        Rectangle2D under = only(scan("<path d=\"M0 50 A50 50 0 0 0 100 50\"/>")).worldBounds();
        assertThat(under.getMinY()).isWithin(0.01).of(50);
        assertThat(under.getMaxY()).isWithin(0.01).of(100);

        // Radii too small for the chord are scaled up (F.6.6) instead of the arc collapsing.
        Rectangle2D scaled = only(scan("<path d=\"M0 50 A10 10 0 0 1 100 50\"/>")).worldBounds();
        assertThat(scaled.getMinY()).isWithin(0.01).of(0);
    }

    @Test
    void brokenPathDataKeepsWhatWasReadableAndQuotesTheRest() {
        AsciiArtSvgScanner.Scene scene = scan("<path d=\"M10 10 L 50 50 L 90 x 90\"/>");

        assertThat(scene.shapes()).hasSize(1);
        assertThat(scene.shapes().get(0).worldBounds()).isEqualTo(new Rectangle2D.Double(10, 10, 40, 40));
        assertThat(scene.pathErrors()).hasSize(1);
        assertThat(scene.pathErrors().get(0)).contains("d=\"M10 10 L 50 50 L 90 x 90\"");
    }

    @Test
    void aPathWithoutAnyDrawableSegmentIsDroppedAndCounted() {
        AsciiArtSvgScanner.Scene garbage = scan("<path d=\"hello\"/>");
        assertThat(garbage.shapes()).isEmpty();
        assertThat(garbage.pathErrors()).hasSize(1);
        assertThat(garbage.droppedElements()).isEqualTo(1);

        AsciiArtSvgScanner.Scene lonelyMove = scan("<path d=\"M10 10\"/>");
        assertThat(lonelyMove.shapes()).isEmpty();
        assertThat(lonelyMove.pathErrors()).isEmpty();
        assertThat(lonelyMove.droppedElements()).isEqualTo(1);

        String longData = "M10 10 L20 20 " + "L30 30 ".repeat(20) + "Z bogus";
        AsciiArtSvgScanner.Scene quoted = scan("<path d=\"" + longData + "\"/>");
        assertThat(quoted.pathErrors()).hasSize(1);
        assertThat(quoted.pathErrors().get(0)).contains(longData.substring(0, AsciiArtSvgScanner.WARNING_QUOTE_LENGTH) + "…");
    }

    // ---- Skipped subtrees and caps ----

    @Test
    void nonDrawableSubtreesAreSkippedAndCountedOnce() {
        AsciiArtSvgScanner.Scene scene = scan(
            "<defs><rect width=\"50\" height=\"50\"/><g><circle r=\"9\"/></g></defs>"
                + "<text x=\"1\" y=\"1\">Hi <tspan>there</tspan></text>"
                + "<rect x=\"10\" y=\"10\" width=\"20\" height=\"20\"/>"
                + "<image href=\"a.png\" width=\"10\" height=\"10\"/>"
                + "<use href=\"#a\"/>");

        assertThat(scene.shapes()).hasSize(1);
        assertThat(scene.droppedElements()).isEqualTo(4);
        assertThat(scene.ignoredElements()).containsExactly("defs", "text", "image", "use").inOrder();
    }

    @Test
    void commentsProcessingInstructionsAndDoctypesAreSkipped() {
        AsciiArtSvgScanner.Scene scene = AsciiArtSvgScanner.scan(
            "<?xml version=\"1.0\"?><!DOCTYPE svg><!-- <rect width=\"9\" height=\"9\"/> -->"
                + "<svg:svg xmlns:svg=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">"
                + "<svg:rect width=\"5\" height=\"5\"/><![CDATA[<circle r=\"1\"/>]]></svg:svg>");

        assertThat(scene.shapes()).hasSize(1);
        assertThat(scene.shapes().get(0).element()).isEqualTo("rect");
        assertThat(scene.viewBox()).isEqualTo(new Rectangle2D.Double(0, 0, 10, 10));
    }

    @Test
    void capsTruncateTheSceneWithoutRejectingIt() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < AsciiArtSvgScanner.MAX_SHAPES + 50; i++) {
            many.append("<rect x=\"1\" y=\"1\" width=\"2\" height=\"2\"/>");
        }
        AsciiArtSvgScanner.Scene shapes = scan(many.toString());
        assertThat(shapes.shapes()).hasSize(AsciiArtSvgScanner.MAX_SHAPES);
        assertThat(shapes.capsTripped()).isTrue();

        StringBuilder path = new StringBuilder("M0 0");
        for (int i = 0; i < AsciiArtSvgScanner.MAX_PATH_COMMANDS_PER_PATH + 10; i++) {
            path.append(" L").append(i % 50).append(' ').append(i % 30);
        }
        AsciiArtSvgScanner.Scene commands = scan("<path d=\"" + path + "\"/>");
        assertThat(commands.shapes()).hasSize(1);
        assertThat(commands.pathErrors()).isEmpty();
        assertThat(commands.capsTripped()).isTrue();

        String huge = "<svg viewBox=\"0 0 100 100\"><rect width=\"5\" height=\"5\"/>"
            + " ".repeat(AsciiArtSvgScanner.MAX_INPUT_CHARS) + "<rect width=\"5\" height=\"5\"/></svg>";
        AsciiArtSvgScanner.Scene cut = AsciiArtSvgScanner.scan(huge);
        assertThat(cut.shapes()).hasSize(1);
        assertThat(cut.capsTripped()).isTrue();
    }

    @Test
    void outOfRangeCoordinatesDropTheElementWithAWarning() {
        AsciiArtSvgScanner.Scene scene = scan("<rect x=\"1e9\" y=\"0\" width=\"5\" height=\"5\"/>"
            + "<circle cx=\"5\" cy=\"5\" r=\"2\"/>");

        assertThat(scene.shapes()).hasSize(1);
        assertThat(scene.droppedElements()).isEqualTo(1);
        assertThat(scene.warnings()).hasSize(1);
    }

    // ---- Truncated and sloppy markup ----

    @Test
    void anUnterminatedTagAtTheEndIsSalvagedAsSelfClosing() {
        AsciiArtSvgScanner.Scene scene = AsciiArtSvgScanner.scan(
            "<svg viewBox=\"0 0 100 100\"><rect x=\"10\" y=\"10\" width=\"20\" height=\"20\"/>"
                + "<circle cx=\"50\" cy=\"50\" r=\"2");

        assertThat(scene.shapes()).hasSize(2);
        assertThat(scene.shapes().get(1).worldBounds()).isEqualTo(new Rectangle2D.Double(48, 48, 4, 4));
        assertThat(scene.truncated()).isTrue();
    }

    @Test
    void aMissingClosingSvgTagCountsAsTruncatedButACompleteDocumentDoesNot() {
        assertThat(AsciiArtSvgScanner.scan("<svg viewBox=\"0 0 9 9\"><rect width=\"5\" height=\"5\"/>").truncated())
            .isTrue();
        assertThat(scan("<rect width=\"5\" height=\"5\"/>").truncated()).isFalse();
    }

    @Test
    void aMissingClosingAngleIsSalvagedWithoutFlaggingTruncation() {
        AsciiArtSvgScanner.Scene scene = AsciiArtSvgScanner.scan(
            "<svg viewBox=\"0 0 100 100\"><rect x=5 y=5 width=50 height=50 "
                + "<circle cx=\"50\" cy=\"50\" r=\"10\"/></svg>");

        assertThat(scene.shapes()).hasSize(2);
        assertThat(scene.shapes().get(0).worldBounds()).isEqualTo(new Rectangle2D.Double(5, 5, 50, 50));
        // Sloppy markup is not a cut-off answer: the orchestrator must still be allowed to repair it.
        assertThat(scene.truncated()).isFalse();
    }

    @Test
    void theFirstUsableViewBoxWinsOverABareSvgTagInProse() {
        AsciiArtSvgScanner.Scene scene = AsciiArtSvgScanner.scan(
            "Wrap the shapes in an <svg> element:\n<svg viewBox=\"0 0 100 100\">"
                + "<rect x=\"10\" y=\"10\" width=\"20\" height=\"20\"/></svg>");

        assertThat(scene.hasViewBox()).isTrue();
        assertThat(scene.viewBox()).isEqualTo(new Rectangle2D.Double(0, 0, 100, 100));
        assertThat(scene.shapes()).hasSize(1);
    }

    @Test
    void warningsQuoteAtMostASnippetOfAnOversizedAttribute() {
        String huge = "x".repeat(50_000);
        AsciiArtSvgScanner.Scene scene = scan(
            "<rect x=\"1\" y=\"1\" width=\"5\" height=\"5\" fill=\"" + huge + "\" transform=\"" + huge + "\"/>");

        assertThat(scene.shapes()).hasSize(1);
        assertThat(scene.warnings()).hasSize(2);
        for (String warning : scene.warnings()) {
            assertThat(warning.length()).isLessThan(2 * AsciiArtSvgScanner.WARNING_QUOTE_LENGTH + 80);
            assertThat(warning).contains("…");
        }
        assertThat(AsciiArtSvgScanner.quote("short")).isEqualTo("short");
    }

    @Test
    void unquotedAttributesExplicitCloseTagsAndStrayAnglesAreTolerated() {
        AsciiArtSvgScanner.Scene scene = AsciiArtSvgScanner.scan(
            "a < b <svg viewBox=\"0 0 100 100\"><rect x=10 y=10 width=20 height=20></rect>"
                + "<circle cx='50' cy='50' r='5'/></svg>");

        assertThat(scene.shapes()).hasSize(2);
        assertThat(scene.shapes().get(0).worldBounds()).isEqualTo(new Rectangle2D.Double(10, 10, 20, 20));
    }

    @Test
    void scanNeverThrows() {
        assertThat(AsciiArtSvgScanner.scan(null).shapes()).isEmpty();
        assertThat(AsciiArtSvgScanner.scan("").shapes()).isEmpty();
        assertThat(AsciiArtSvgScanner.scan("<<<>>> </g></svg> <rect width=\"x\"/> <path/>").shapes()).isEmpty();
    }

    // ---- locateSvg ----

    @Test
    void locateSvgFindsTheDocumentWhateverTheFenceSays() {
        String svg = "<svg viewBox=\"0 0 100 100\"><rect width=\"9\" height=\"9\"/></svg>";
        assertThat(AsciiArtSvgScanner.locateSvg("Here you go:\n```svg\n" + svg + "\n```\nEnjoy!")).isEqualTo(svg);
        assertThat(AsciiArtSvgScanner.locateSvg("```xml\n" + svg + "\n```")).isEqualTo(svg);
        assertThat(AsciiArtSvgScanner.locateSvg("Sure. " + svg + " Done.")).isEqualTo(svg);
        assertThat(AsciiArtSvgScanner.locateSvg("<SVG viewBox=\"0 0 1 1\"></SVG>")).isEqualTo("<SVG viewBox=\"0 0 1 1\"></SVG>");
    }

    @Test
    void locateSvgKeepsATruncatedDocumentToTheEndAndSkipsReasoning() {
        String cut = "<svg viewBox=\"0 0 100 100\"><rect width=\"9\" hei";
        assertThat(AsciiArtSvgScanner.locateSvg("```svg\n" + cut)).isEqualTo(cut);

        String reply = "<think>I could start with <svg viewBox=\"0 0 1 1\"/></think>\n<svg viewBox=\"0 0 2 2\"></svg>";
        assertThat(AsciiArtSvgScanner.locateSvg(reply)).isEqualTo("<svg viewBox=\"0 0 2 2\"></svg>");
        assertThat(AsciiArtSvgScanner.locateSvg("<think>still thinking about <svg>")).isNull();
    }

    @Test
    void locateSvgSurvivesCharactersWhoseLowerCaseFormIsLonger() {
        // U+0130 lower-cases to two chars; indices taken from a lower-cased copy would be off by one per İ.
        String svg = "<svg viewBox=\"0 0 100 100\"><rect width=\"50\" height=\"50\"/></svg>";
        assertThat(AsciiArtSvgScanner.locateSvg("İİİ " + svg)).isEqualTo(svg);
        assertThat(AsciiArtSvgScanner.locateSvg("<think>İİ</think>" + svg)).isEqualTo(svg);
        String upper = "<SVG viewBox=\"0 0 1 1\"><rect width=\"1\" height=\"1\"/></SVG>";
        assertThat(AsciiArtSvgScanner.locateSvg("İstanbul: " + upper)).isEqualTo(upper);
        assertThat(AsciiArtSvgScanner.locateSvg("<THINK>İ<svg/></THINK> İ <svg viewBox=\"0 0 2 2\"></svg>"))
            .isEqualTo("<svg viewBox=\"0 0 2 2\"></svg>");
        assertThat(AsciiArtSvgScanner.indexOfIgnoreCase("aİ<SVG", "<svg", 0)).isEqualTo(2);
        assertThat(AsciiArtSvgScanner.lastIndexOfIgnoreCase("<svg>İ</SVG>x</svg>", "</svg>")).isEqualTo(13);
        assertThat(AsciiArtSvgScanner.indexOfIgnoreCase("ab", "abc", 0)).isEqualTo(-1);
    }

    @Test
    void locateSvgReturnsNullWithoutADocument() {
        assertThat(AsciiArtSvgScanner.locateSvg(null)).isNull();
        assertThat(AsciiArtSvgScanner.locateSvg("")).isNull();
        assertThat(AsciiArtSvgScanner.locateSvg("I cannot draw that, sorry.")).isNull();
        assertThat(AsciiArtSvgScanner.locateSvg("<svgfoo>")).isNull();
    }
}
