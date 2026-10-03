package de.kortty.core;

import com.sithtermfx.core.Color;
import com.sithtermfx.core.TerminalColor;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.emulator.ColorPalette;
import com.sithtermfx.core.emulator.ColorPaletteImpl;
import com.sithtermfx.core.util.Platform;
import de.kortty.model.ConnectionSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The terminal's 16 ANSI colours and its selection colour, as configured on the Colors tab.
 *
 * <p>Until the user changes one of them ({@link ConnectionSettings#isAnsiPaletteCustomized()}),
 * the terminal keeps the look it always had: the SithTermFX built-in palette (the Windows console
 * colours on Windows, xterm's elsewhere, chosen exactly like {@code DefaultSettingsProvider} does)
 * and inverse-video selection. Toolkit-free, so it is usable from tests and the recording path.
 */
public final class TerminalPaletteSupport {

    private static final int PALETTE_SIZE = 2 * ConnectionSettings.ANSI_COLOR_COUNT;

    private TerminalPaletteSupport() {
    }

    /** The palette SithTermFX draws when korTTY does not provide one. */
    public static @NotNull ColorPalette builtInPalette() {
        return Platform.isWindows() ? ColorPaletteImpl.WINDOWS_PALETTE : ColorPaletteImpl.XTERM_PALETTE;
    }

    /** Built-in ANSI colour {@code index} (0–7) of the normal or bright variant as {@code #RRGGBB}. */
    public static @NotNull String builtInHex(int index, boolean bright) {
        return toHex(builtInRgb(index, bright));
    }

    /**
     * The colour the terminal shows for ANSI colour {@code index}: the stored one once the palette
     * is customised (falling back per colour when a stored value is not a valid hex colour),
     * otherwise the built-in one. Always a valid {@code #RRGGBB}.
     */
    public static @NotNull String effectiveHex(@Nullable ConnectionSettings settings, int index, boolean bright) {
        return toHex(effectiveRgb(settings, index, bright));
    }

    /**
     * Whether the colours stored in {@code settings} differ from the built-in look, i.e. whether
     * the Colors tab has to mark them as {@linkplain ConnectionSettings#setAnsiPaletteCustomized
     * customised}: one of the 16 ANSI colours differs from {@link #builtInHex}, or the selection
     * colour differs from {@link ConnectionSettings#DEFAULT_SELECTION_COLOR}. A stored value that is
     * not a valid colour does not count, because the terminal would draw the built-in one anyway.
     */
    public static boolean differsFromBuiltIn(@Nullable ConnectionSettings settings) {
        if (settings == null) {
            return false;
        }
        for (int i = 0; i < ConnectionSettings.ANSI_COLOR_COUNT; i++) {
            for (boolean bright : new boolean[] {false, true}) {
                int rgb = parseRgb(settings.getAnsiColor(i, bright));
                if (rgb >= 0 && rgb != builtInRgb(i, bright)) {
                    return true;
                }
            }
        }
        int selection = parseRgb(settings.getSelectionColor());
        return selection >= 0 && selection != parseRgb(ConnectionSettings.DEFAULT_SELECTION_COLOR);
    }

    /**
     * Stores what the Colors tab shows — the 8 normal and 8 bright ANSI colours (index 0–7: black,
     * red, green, yellow, blue, magenta, cyan, white) and the selection colour — and derives
     * {@link ConnectionSettings#setAnsiPaletteCustomized} from {@link #differsFromBuiltIn}. Colours
     * left at the built-in values keep the terminal's built-in look (palette and inverse-video
     * selection); changing any of them hands all of them to the terminal.
     *
     * @throws IllegalArgumentException when {@code normal} or {@code bright} does not hold 8 colours
     */
    public static void storeColorsTab(@NotNull ConnectionSettings settings, @NotNull String[] normal,
                                      @NotNull String[] bright, @Nullable String selection) {
        if (normal.length != ConnectionSettings.ANSI_COLOR_COUNT || bright.length != ConnectionSettings.ANSI_COLOR_COUNT) {
            throw new IllegalArgumentException("Expected " + ConnectionSettings.ANSI_COLOR_COUNT
                    + " normal and bright colours, got " + normal.length + " and " + bright.length);
        }
        for (int i = 0; i < ConnectionSettings.ANSI_COLOR_COUNT; i++) {
            settings.setAnsiColor(i, false, normal[i]);
            settings.setAnsiColor(i, true, bright[i]);
        }
        settings.setSelectionColor(selection);
        settings.setAnsiPaletteCustomized(differsFromBuiltIn(settings));
    }

    /**
     * The palette the terminal should draw, or {@code null} while the colours are not customised
     * so the caller keeps the built-in palette.
     */
    public static @Nullable ColorPalette toColorPalette(@Nullable ConnectionSettings settings) {
        if (settings == null || !settings.isAnsiPaletteCustomized()) {
            return null;
        }
        Color[] colors = new Color[PALETTE_SIZE];
        for (int i = 0; i < ConnectionSettings.ANSI_COLOR_COUNT; i++) {
            colors[paletteIndex(i, false)] = new Color(effectiveRgb(settings, i, false));
            colors[paletteIndex(i, true)] = new Color(effectiveRgb(settings, i, true));
        }
        return new KorttyColorPalette(colors);
    }

    /**
     * Selection drawn as a solid {@code hex} background with black or white text, whichever reads
     * better. Both are RGB colours, so a custom palette cannot change them. {@code null} when
     * {@code hex} is not a valid colour, leaving the caller to fall back to the terminal default.
     */
    public static @Nullable TextStyle selectionStyle(@Nullable String hex) {
        int rgb = parseRgb(hex);
        if (rgb < 0) {
            return null;
        }
        String normalized = toHex(rgb);
        TerminalColor text = "#000".equals(SessionJournalHtmlRenderer.contrastFor(normalized))
                ? TerminalColor.rgb(0, 0, 0)
                : TerminalColor.rgb(255, 255, 255);
        return new TextStyle(text, TerminalColor.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF));
    }

    /** {@code #RRGGBB} or {@code #RGB} (the {@code #} is optional) as {@code 0xRRGGBB}; -1 when invalid. */
    static int parseRgb(@Nullable String hex) {
        if (hex == null) {
            return -1;
        }
        String digits = hex.trim();
        if (digits.startsWith("#")) {
            digits = digits.substring(1);
        }
        if (digits.length() == 3) {
            digits = new StringBuilder(6)
                    .append(digits.charAt(0)).append(digits.charAt(0))
                    .append(digits.charAt(1)).append(digits.charAt(1))
                    .append(digits.charAt(2)).append(digits.charAt(2))
                    .toString();
        }
        if (digits.length() != 6) {
            return -1;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (Character.digit(digits.charAt(i), 16) < 0) {
                return -1;
            }
        }
        return Integer.parseInt(digits, 16);
    }

    private static int builtInRgb(int index, boolean bright) {
        return builtInPalette().getForeground(TerminalColor.index(paletteIndex(index, bright))).getRGB() & 0xFFFFFF;
    }

    private static int effectiveRgb(@Nullable ConnectionSettings settings, int index, boolean bright) {
        paletteIndex(index, bright); // rejects an index outside 0-7 before getAnsiColor maps it to white
        if (settings != null && settings.isAnsiPaletteCustomized()) {
            int rgb = parseRgb(settings.getAnsiColor(index, bright));
            if (rgb >= 0) {
                return rgb;
            }
        }
        return builtInRgb(index, bright);
    }

    private static int paletteIndex(int index, boolean bright) {
        if (index < 0 || index >= ConnectionSettings.ANSI_COLOR_COUNT) {
            throw new IllegalArgumentException("ANSI color index out of range [0,7]: " + index);
        }
        return bright ? index + ConnectionSettings.ANSI_COLOR_COUNT : index;
    }

    private static String toHex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /** The 16 configured colours; indexed colours 16–255 stay the fixed xterm cube and grey ramp. */
    private static final class KorttyColorPalette extends ColorPalette {

        private final Color[] colors;

        private KorttyColorPalette(Color[] colors) {
            this.colors = colors;
        }

        @Override
        protected @NotNull Color getForegroundByColorIndex(int colorIndex) {
            return colors[colorIndex];
        }

        @Override
        protected @NotNull Color getBackgroundByColorIndex(int colorIndex) {
            return colors[colorIndex];
        }
    }
}
