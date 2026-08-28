package de.kortty.core;

import javafx.scene.shape.SVGPath;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The category glyphs are rendered by four different surfaces — the analysis window, the change
 * preview, the AI-processing rows and the HTML/PDF export. Two of them used to carry their own copy
 * of the path table, so redrawing an icon fixed three surfaces and quietly left the fourth showing
 * the old one. These tests keep the table single and keep the paths drawable.
 */
class AnalysisCategoryVisualsTest {

    private static final List<String> CATEGORIES =
        List.of("security", "optimization", "design", "dependencies");

    private static final Path JAVA_ROOT = Path.of("src/main/java");

    /** The inline-SVG wrapper the section glyphs are emitted in. */
    private static final Pattern ICON_SVG_LITERAL = Pattern.compile("class=\\\\\"sec-ic\\\\\"");

    /**
     * The glyphs these four categories were drawn with before. A copy of one of these reappearing
     * in the tree means someone reverted a surface instead of the shared table — which is exactly
     * how the export kept showing a water drop for "design" after the icons were redrawn.
     */
    private static final List<String> RETIRED_GLYPHS = List.of(
        "M8 1.3 13.5 3.3V7.6",
        "M9 1 4 8.5H7L6.5 15",
        "M8 1.4 13.6 4.2V9.8",
        "M8 1.5C11 5 13 7.5 13 10");

    @Test
    void everyCategoryHasAPathAndTwoColours() {
        for (String category : CATEGORIES) {
            assertWithMessage(category).that(AnalysisCategoryVisuals.iconPath(category)).isNotEmpty();
            assertWithMessage(category).that(AnalysisCategoryVisuals.colorHex(category)).matches("#[0-9a-f]{6}");
            assertWithMessage(category).that(AnalysisCategoryVisuals.printColorHex(category)).matches("#[0-9a-f]{6}");
        }
    }

    @Test
    void anUnknownCategoryFallsBackToDesignRatherThanBlank() {
        for (String unknown : new String[]{null, "", "nonsense"}) {
            assertThat(AnalysisCategoryVisuals.iconPath(unknown))
                .isEqualTo(AnalysisCategoryVisuals.iconPath("design"));
            assertThat(AnalysisCategoryVisuals.colorHex(unknown))
                .isEqualTo(AnalysisCategoryVisuals.colorHex("design"));
        }
    }

    @Test
    void everyCategoryHasItsOwnGlyph() {
        List<String> paths = CATEGORIES.stream().map(AnalysisCategoryVisuals::iconPath).toList();
        assertThat(paths).containsNoDuplicates();
    }

    /**
     * The glyphs are also fed to a JavaFX {@code SVGPath} in the progress rows, where a malformed
     * arc flag does not throw — it renders nothing. Parsing each path here turns "invisible icon"
     * into a failing build.
     */
    @Test
    void everyGlyphParsesAsAJavaFxPath() {
        for (String category : CATEGORIES) {
            SVGPath path = new SVGPath();
            path.setContent(AnalysisCategoryVisuals.iconPath(category));
            assertWithMessage("%s renders as an empty shape", category)
                .that(path.prefWidth(-1)).isGreaterThan(0.0);
            assertWithMessage("%s renders as an empty shape", category)
                .that(path.prefHeight(-1)).isGreaterThan(0.0);
        }
    }

    /** Every glyph is drawn inside a 16x16 box; one that overflows would be clipped in the reports. */
    @Test
    void everyGlyphStaysInsideTheSixteenPixelBox() {
        for (String category : CATEGORIES) {
            SVGPath path = new SVGPath();
            path.setContent(AnalysisCategoryVisuals.iconPath(category));
            assertWithMessage(category).that(path.getBoundsInLocal().getMinX()).isAtLeast(0.0);
            assertWithMessage(category).that(path.getBoundsInLocal().getMinY()).isAtLeast(0.0);
            assertWithMessage(category).that(path.getBoundsInLocal().getMaxX()).isAtMost(16.0);
            assertWithMessage(category).that(path.getBoundsInLocal().getMaxY()).isAtMost(16.0);
        }
    }

    /** The subpaths carry cut-outs, so every consumer must fill them with the even-odd rule. */
    @Test
    void theInlineSvgAsksForTheEvenOddFillRule() {
        for (String category : CATEGORIES) {
            assertWithMessage(category).that(AnalysisCategoryVisuals.iconSvg(category))
                .contains("fill-rule=\"evenodd\"");
        }
    }

    @Test
    void noOtherClassKeepsItsOwnCopyOfTheGlyphs() throws IOException {
        List<String> signatures = new ArrayList<>(RETIRED_GLYPHS);
        for (String category : CATEGORIES) {
            // The leading run of a path is distinctive enough to spot a pasted-back copy without
            // tripping over the unrelated SVG icons the rest of the UI is full of.
            signatures.add(AnalysisCategoryVisuals.iconPath(category).substring(0, 18));
        }

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(JAVA_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("AnalysisCategoryVisuals.java")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                boolean copies = ICON_SVG_LITERAL.matcher(source).find()
                    || signatures.stream().anyMatch(source::contains);
                if (copies) {
                    offenders.add(JAVA_ROOT.relativize(file).toString());
                }
            }
        }
        assertWithMessage(
            "These files carry their own copy of a category glyph. Read it from "
                + "AnalysisCategoryVisuals instead, or the surfaces drift apart again")
            .that(offenders).isEmpty();
    }
}
