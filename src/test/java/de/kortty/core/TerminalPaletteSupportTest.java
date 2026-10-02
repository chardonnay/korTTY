package de.kortty.core;

import com.sithtermfx.core.Color;
import com.sithtermfx.core.TerminalColor;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.emulator.ColorPalette;
import com.sithtermfx.core.emulator.ColorPaletteImpl;
import com.sithtermfx.ui.settings.DefaultSettingsProvider;
import de.kortty.model.ConnectionSettings;
import org.testng.annotations.Test;

import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * Pins how the Colors tab's ANSI palette and selection colour reach the terminal: untouched
 * settings keep the SithTermFX built-in palette (no visible change for anyone who never
 * customised), customised ones map 1:1 onto the 16 indexed colours.
 */
class TerminalPaletteSupportTest {

    private static String hex(Color color) {
        return String.format(Locale.ROOT, "#%06X", color.getRGB() & 0xFFFFFF);
    }

    /** Settings holding exactly the built-in palette, the way the Colors tab saves untouched pickers. */
    private static ConnectionSettings builtInSettings() {
        ConnectionSettings settings = new ConnectionSettings();
        for (int i = 0; i < ConnectionSettings.ANSI_COLOR_COUNT; i++) {
            settings.setAnsiColor(i, false, TerminalPaletteSupport.builtInHex(i, false));
            settings.setAnsiColor(i, true, TerminalPaletteSupport.builtInHex(i, true));
        }
        return settings;
    }

    @Test
    void builtInHexIsTheVendorPaletteOfThisPlatform() {
        ColorPalette vendor = new DefaultSettingsProvider().getTerminalColorPalette();
        assertThat(TerminalPaletteSupport.builtInPalette()).isSameInstanceAs(vendor);
        for (int i = 0; i < ConnectionSettings.ANSI_COLOR_COUNT; i++) {
            assertThat(TerminalPaletteSupport.builtInHex(i, false))
                    .isEqualTo(hex(vendor.getForeground(TerminalColor.index(i))));
            assertThat(TerminalPaletteSupport.builtInHex(i, true))
                    .isEqualTo(hex(vendor.getForeground(TerminalColor.index(i + 8))));
        }
        String expectedBlue = com.sithtermfx.core.util.Platform.isWindows() ? "#000080" : "#1E90FF";
        assertThat(TerminalPaletteSupport.builtInHex(4, false)).isEqualTo(expectedBlue);
    }

    @Test
    void untouchedSettingsKeepTheBuiltInPalette() {
        ConnectionSettings settings = new ConnectionSettings(); // legacy model defaults, e.g. ansiBlue #0000EE
        assertThat(settings.isAnsiPaletteCustomized()).isFalse();

        assertThat(TerminalPaletteSupport.toColorPalette(settings)).isNull();
        assertThat(TerminalPaletteSupport.toColorPalette(null)).isNull();
        for (int i = 0; i < ConnectionSettings.ANSI_COLOR_COUNT; i++) {
            assertThat(TerminalPaletteSupport.effectiveHex(settings, i, false))
                    .isEqualTo(TerminalPaletteSupport.builtInHex(i, false));
            assertThat(TerminalPaletteSupport.effectiveHex(settings, i, true))
                    .isEqualTo(TerminalPaletteSupport.builtInHex(i, true));
        }
    }

    @Test
    void customizedPaletteMapsAllSixteenIndicesToTheConfiguredColours() {
        ConnectionSettings settings = new ConnectionSettings();
        for (int i = 0; i < ConnectionSettings.ANSI_COLOR_COUNT; i++) {
            settings.setAnsiColor(i, false, String.format(Locale.ROOT, "#10%02X20", i));
            settings.setAnsiColor(i, true, String.format(Locale.ROOT, "#30%02X40", i));
        }
        settings.setAnsiPaletteCustomized(true);

        ColorPalette palette = TerminalPaletteSupport.toColorPalette(settings);

        assertThat(palette).isNotNull();
        for (int i = 0; i < ConnectionSettings.ANSI_COLOR_COUNT; i++) {
            String normal = String.format(Locale.ROOT, "#10%02X20", i);
            String bright = String.format(Locale.ROOT, "#30%02X40", i);
            assertThat(hex(palette.getForeground(TerminalColor.index(i)))).isEqualTo(normal);
            assertThat(hex(palette.getBackground(TerminalColor.index(i)))).isEqualTo(normal);
            assertThat(hex(palette.getForeground(TerminalColor.index(i + 8)))).isEqualTo(bright);
            assertThat(hex(palette.getBackground(TerminalColor.index(i + 8)))).isEqualTo(bright);
            assertThat(TerminalPaletteSupport.effectiveHex(settings, i, false)).isEqualTo(normal);
        }
        // Truecolour cells are not touched by the palette.
        assertThat(hex(palette.getForeground(TerminalColor.rgb(1, 2, 3)))).isEqualTo("#010203");
    }

    @Test
    void anInvalidStoredColourFallsBackToTheBuiltInOneForThatIndexOnly() {
        ConnectionSettings settings = builtInSettings();
        settings.setAnsiColor(1, false, "not-a-colour");
        settings.setAnsiColor(2, false, "#abcdef");
        settings.setAnsiPaletteCustomized(true);

        ColorPalette palette = TerminalPaletteSupport.toColorPalette(settings);

        assertThat(hex(palette.getForeground(TerminalColor.index(1))))
                .isEqualTo(TerminalPaletteSupport.builtInHex(1, false));
        assertThat(hex(palette.getForeground(TerminalColor.index(2)))).isEqualTo("#ABCDEF");
        assertThat(TerminalPaletteSupport.effectiveHex(settings, 2, false)).isEqualTo("#ABCDEF");
    }

    @Test
    void differsFromBuiltInDecidesWhetherTheColoursTabCustomizes() {
        ConnectionSettings untouched = builtInSettings();
        assertThat(TerminalPaletteSupport.differsFromBuiltIn(untouched)).isFalse();
        assertThat(TerminalPaletteSupport.differsFromBuiltIn(null)).isFalse();

        ConnectionSettings lowerCase = builtInSettings();
        lowerCase.setAnsiColor(4, false, TerminalPaletteSupport.builtInHex(4, false).toLowerCase(Locale.ROOT));
        assertThat(TerminalPaletteSupport.differsFromBuiltIn(lowerCase)).isFalse();

        ConnectionSettings red = builtInSettings();
        red.setAnsiColor(1, false, "#FF8800");
        assertThat(TerminalPaletteSupport.differsFromBuiltIn(red)).isTrue();

        ConnectionSettings brightWhite = builtInSettings();
        brightWhite.setAnsiColor(7, true, "#EEEEEE");
        assertThat(TerminalPaletteSupport.differsFromBuiltIn(brightWhite)).isTrue();

        ConnectionSettings selection = builtInSettings();
        selection.setSelectionColor("#FFFF00");
        assertThat(TerminalPaletteSupport.differsFromBuiltIn(selection)).isTrue();

        ConnectionSettings invalid = builtInSettings();
        invalid.setAnsiColor(3, false, "garbage");
        assertThat(TerminalPaletteSupport.differsFromBuiltIn(invalid)).isFalse();
    }

    @Test
    void selectionStyleUsesTheColourAsBackgroundWithContrastingText() {
        TextStyle yellow = TerminalPaletteSupport.selectionStyle("#FFFF00");
        assertThat(yellow.getBackground()).isEqualTo(TerminalColor.rgb(255, 255, 0));
        assertThat(yellow.getForeground()).isEqualTo(TerminalColor.rgb(0, 0, 0));

        TextStyle navy = TerminalPaletteSupport.selectionStyle("#000080");
        assertThat(navy.getBackground()).isEqualTo(TerminalColor.rgb(0, 0, 128));
        assertThat(navy.getForeground()).isEqualTo(TerminalColor.rgb(255, 255, 255));

        // RGB rather than indexed colours, so a custom palette cannot recolour the selection.
        assertThat(yellow.getForeground().isIndexed()).isFalse();
        assertThat(navy.getForeground().isIndexed()).isFalse();
    }

    @Test
    void selectionStyleIsNullForAnInvalidColourSoTheCallerFallsBack() {
        assertThat(TerminalPaletteSupport.selectionStyle(null)).isNull();
        assertThat(TerminalPaletteSupport.selectionStyle("")).isNull();
        assertThat(TerminalPaletteSupport.selectionStyle("#12345")).isNull();
        assertThat(TerminalPaletteSupport.selectionStyle("#GGGGGG")).isNull();
    }

    @Test
    void parseRgbAcceptsLongAndShortHexWithOrWithoutHash() {
        assertThat(TerminalPaletteSupport.parseRgb("#1E90FF")).isEqualTo(0x1E90FF);
        assertThat(TerminalPaletteSupport.parseRgb(" 1e90ff ")).isEqualTo(0x1E90FF);
        assertThat(TerminalPaletteSupport.parseRgb("#fa0")).isEqualTo(0xFFAA00);
        assertThat(TerminalPaletteSupport.parseRgb("#1E90FF80")).isEqualTo(-1);
        assertThat(TerminalPaletteSupport.parseRgb("blue")).isEqualTo(-1);
    }

    @Test
    void indexOutsideTheEightAnsiColoursIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TerminalPaletteSupport.builtInHex(8, false));
        assertThrows(IllegalArgumentException.class, () -> TerminalPaletteSupport.effectiveHex(new ConnectionSettings(), -1, true));
    }

    @Test
    void builtInPaletteIsTheSameObjectTheVendorUses() {
        ColorPalette expected = com.sithtermfx.core.util.Platform.isWindows()
                ? ColorPaletteImpl.WINDOWS_PALETTE
                : ColorPaletteImpl.XTERM_PALETTE;
        assertThat(TerminalPaletteSupport.builtInPalette()).isSameInstanceAs(expected);
    }
}
