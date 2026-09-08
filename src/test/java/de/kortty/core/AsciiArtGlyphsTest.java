package de.kortty.core;

import org.testng.annotations.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

class AsciiArtGlyphsTest {

    private static AsciiArtGlyphs.Glyph glyph(char c) {
        for (AsciiArtGlyphs.Glyph g : AsciiArtGlyphs.glyphs()) {
            if (g.character() == c) {
                return g;
            }
        }
        throw new AssertionError("No glyph '" + c + "'");
    }

    private static double degrees(char c) {
        return Math.toDegrees(glyph(c).descriptor().orientation());
    }

    /** A cell whose left {@code darkColumns} pixel columns are black, the rest white. */
    private static AsciiArtRasterizer.InkMap halfDarkCell(int darkColumns) {
        BufferedImage cell = AsciiArtRasterizer.newCanvas(
            AsciiArtRasterizer.CELL_WIDTH_PX, AsciiArtRasterizer.CELL_HEIGHT_PX);
        Graphics2D g = AsciiArtRasterizer.graphics(cell);
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, darkColumns, AsciiArtRasterizer.CELL_HEIGHT_PX);
        } finally {
            g.dispose();
        }
        return AsciiArtRasterizer.InkMap.of(cell);
    }

    // ---- Glyph set ----

    @Test
    void theSetHoldsThirtyFiveDistinctPrintableCharacters() {
        List<AsciiArtGlyphs.Glyph> glyphs = AsciiArtGlyphs.glyphs();
        assertThat(glyphs).hasSize(35);
        assertThat(AsciiArtGlyphs.CHARACTERS).hasLength(35);
        Set<Character> seen = new HashSet<>();
        for (AsciiArtGlyphs.Glyph g : glyphs) {
            assertThat(g.character()).isAtLeast(' ');
            assertThat(g.character()).isAtMost('~');
            assertThat(seen.add(g.character())).isTrue();
        }
        assertThat(glyphs.get(0).character()).isEqualTo(' ');
        assertThat(glyphs.get(0).descriptor().mean()).isEqualTo(0.0);
    }

    @Test
    void everyPairOfGlyphsIsTellableApart() {
        List<AsciiArtGlyphs.Glyph> glyphs = AsciiArtGlyphs.glyphs();
        for (int i = 0; i < glyphs.size(); i++) {
            for (int j = i + 1; j < glyphs.size(); j++) {
                double ssd = AsciiArtGlyphs.meanSquaredDifference(
                    glyphs.get(i).descriptor().blurred(), glyphs.get(j).descriptor().blurred());
                assertThat(ssd).isGreaterThan(0.002);
            }
        }
    }

    @Test
    void theTemplatesAreBuiltOnceAndAreDeterministic() {
        assertThat(AsciiArtGlyphs.glyphs()).isSameInstanceAs(AsciiArtGlyphs.glyphs());
        AsciiArtGlyphs.Descriptor first = AsciiArtGlyphs.describe(AsciiArtGlyphs.renderTemplate('@'), 0, 0);
        AsciiArtGlyphs.Descriptor second = AsciiArtGlyphs.describe(AsciiArtGlyphs.renderTemplate('@'), 0, 0);
        assertThat(first.blurred()).isEqualTo(second.blurred());
        assertThat(first.mean()).isEqualTo(second.mean());
    }

    // ---- Descriptors ----

    @Test
    void lineGlyphsReportTheirOrientation() {
        assertThat(degrees('/')).isAtLeast(55.0);
        assertThat(degrees('/')).isAtMost(75.0);
        assertThat(degrees('\\')).isAtLeast(105.0);
        assertThat(degrees('\\')).isAtMost(125.0);
        assertThat(degrees('|')).isAtLeast(80.0);
        assertThat(degrees('|')).isAtMost(100.0);
        assertThat(Math.min(degrees('-'), 180 - degrees('-'))).isLessThan(10.0);
        assertThat(Math.min(degrees('_'), 180 - degrees('_'))).isLessThan(10.0);
        // A single straight stroke is coherent; a blob is not.
        assertThat(glyph('|').descriptor().coherence()).isGreaterThan(0.8);
        assertThat(glyph('o').descriptor().coherence()).isLessThan(0.2);
    }

    @Test
    void atIsTheDarkestGlyphAndTheRampOrdersItsRungsByInk() {
        double darkest = glyph('@').descriptor().mean();
        for (AsciiArtGlyphs.Glyph g : AsciiArtGlyphs.glyphs()) {
            if (g.character() != '@') {
                assertThat(g.descriptor().mean()).isLessThan(darkest);
            }
        }
        double previous = -1;
        for (int i = 0; i < AsciiArtGlyphs.TONAL_RAMP.length(); i++) {
            double mean = glyph(AsciiArtGlyphs.TONAL_RAMP.charAt(i)).descriptor().mean();
            assertThat(mean).isGreaterThan(previous);
            previous = mean;
        }
    }

    @Test
    void aStepEdgeIsToldFromALine() {
        AsciiArtGlyphs.Descriptor edge = AsciiArtGlyphs.describe(halfDarkCell(4), 0, 0);
        assertThat(edge.step()).isGreaterThan(AsciiArtGlyphs.EDGE_MIN_STEP);
        assertThat(edge.coherence()).isGreaterThan(AsciiArtGlyphs.EDGE_MIN_COHERENCE);
        assertThat(Math.toDegrees(edge.orientation())).isWithin(5.0).of(90.0);
        assertThat(AsciiArtGlyphs.edgeGlyph(edge)).isEqualTo('|');

        AsciiArtGlyphs.Descriptor line = glyph('|').descriptor();
        assertThat(line.step()).isLessThan(AsciiArtGlyphs.EDGE_MIN_STEP);
        assertThat(AsciiArtGlyphs.edgeGlyph(line)).isEqualTo((char) 0);
    }

    // ---- Matching ----

    @Test
    void everyTemplateMatchesItself() {
        for (AsciiArtGlyphs.Glyph g : AsciiArtGlyphs.glyphs()) {
            // Blank and flat cells are decided by the mean alone; every other template is its own best match.
            AsciiArtGlyphs.Descriptor d = g.descriptor();
            if (d.mean() >= AsciiArtGlyphs.BLANK_MEAN && d.flatness() >= AsciiArtGlyphs.FLAT_STDDEV
                && AsciiArtGlyphs.edgeGlyph(d) == 0) {
                assertThat(AsciiArtGlyphs.match(d)).isEqualTo(g.character());
            }
        }
    }

    @Test
    void theToneRampFollowsTheThresholds() {
        assertThat(AsciiArtGlyphs.toneFor(0.0)).isEqualTo(' ');
        assertThat(AsciiArtGlyphs.toneFor(0.05)).isEqualTo('.');
        assertThat(AsciiArtGlyphs.toneFor(0.5)).isEqualTo('*');
        assertThat(AsciiArtGlyphs.toneFor(0.86)).isEqualTo('@');
        assertThat(AsciiArtGlyphs.toneFor(1.0)).isEqualTo('@');
    }

    @Test
    void blankAndSolidCellsBecomeSpaceAndAt() {
        AsciiArtRasterizer.InkMap white = halfDarkCell(0);
        AsciiArtRasterizer.InkMap black = halfDarkCell(AsciiArtRasterizer.CELL_WIDTH_PX);
        assertThat(AsciiArtGlyphs.match(AsciiArtGlyphs.describe(white, 0, 0))).isEqualTo(' ');
        assertThat(AsciiArtGlyphs.match(AsciiArtGlyphs.describe(black, 0, 0))).isEqualTo('@');
        assertThat(AsciiArtGlyphs.match(AsciiArtGlyphs.describe(halfDarkCell(4), 0, 0))).isEqualTo('|');
    }

    // ---- Whole pictures ----

    @Test
    void convertYieldsExactlyRowsByColumns() {
        String[] lines = AsciiArtGlyphs.convert(halfDarkCell(8), 7, 3);
        assertThat(lines).hasLength(3);
        for (String line : lines) {
            assertThat(line).hasLength(7);
        }
        // Reads outside the tiny canvas clamp to its edge, so every cell is solid.
        assertThat(lines[1]).isEqualTo("@@@@@@@");
    }

    @Test
    void trimmingKeepsRelativeAlignment() {
        String trimmed = AsciiArtGlyphs.trimPicture(new String[] {
            "          ",
            "    /\\    ",
            "   /  \\   ",
            "          ",
            "      ##  ",
            "          "});
        assertThat(trimmed).isEqualTo(" /\\\n/  \\\n\n   ##");
        assertThat(AsciiArtGlyphs.trimPicture(new String[] {"   ", "   "})).isEmpty();
    }
}
