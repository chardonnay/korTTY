package de.kortty.ui;

import com.sithtermfx.core.TerminalColor;
import com.sithtermfx.core.emulator.ColorPalette;
import com.sithtermfx.ui.FxTransformers;
import de.kortty.core.TerminalPaletteSupport;
import de.kortty.model.ConnectionSettings;
import javafx.scene.paint.Color;

/**
 * Turns highlight-rule colours into JavaFX colours for the log viewer.
 *
 * <p>A rule colour is either an RGB value or an ANSI index ({@code ansi:N}), which only means
 * something against a palette; the viewer has no terminal pane of its own, so it uses the
 * connection-independent palette the user configured for new terminals, falling back to the
 * built-in one.</p>
 */
final class LogfileColors {

    private LogfileColors() {
    }

    /** The palette ANSI-indexed rule colours resolve against. */
    static ColorPalette palette(ConnectionSettings terminalDefaults) {
        ColorPalette configured = TerminalPaletteSupport.toColorPalette(terminalDefaults);
        return configured != null ? configured : TerminalPaletteSupport.builtInPalette();
    }

    /** The JavaFX colour for a rule colour, or {@code null} when the rule keeps the default. */
    static Color toFx(TerminalColor color, ColorPalette palette) {
        if (color == null) {
            return null;
        }
        try {
            com.sithtermfx.core.Color resolved = color.isIndexed()
                ? (palette != null ? palette : TerminalPaletteSupport.builtInPalette()).getForeground(color)
                : color.toColor();
            return FxTransformers.toFxColor(resolved);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
