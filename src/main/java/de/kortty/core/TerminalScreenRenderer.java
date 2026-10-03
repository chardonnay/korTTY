package de.kortty.core;

import com.sithtermfx.core.HyperlinkStyle;
import com.sithtermfx.core.TerminalColor;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.model.TerminalTextBuffer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the visible screen of a terminal text buffer — text plus colour runs — and draws it into
 * an image without any window. Shared by the terminal recording (live widgets) and the
 * JobScheduler's invisible virtual terminal, so a screenshot of a job looks like a recording frame.
 */
public final class TerminalScreenRenderer {

    /** How ANSI colours 0–15 are resolved; the 256-colour cube and grey ramp are fixed. */
    public record Palette(AnsiColors ansiColors, boolean boldAsBright) {
        /** xterm's default ANSI colours. */
        public static final Palette DEFAULT = new Palette(TerminalScreenRenderer::defaultAnsiColor, false);

        public Palette {
            ansiColors = ansiColors != null ? ansiColors : TerminalScreenRenderer::defaultAnsiColor;
        }
    }

    /** ANSI colour {@code index} (0–7) as {@code #RRGGBB}, in its normal or bright variant. */
    @FunctionalInterface
    public interface AnsiColors {
        String hex(int index, boolean bright);
    }

    private static final int MAX_LINK_STYLE_DEPTH = 8;

    private TerminalScreenRenderer() {
    }

    /**
     * The screen as a snapshot. Call with the buffer's lock held when other threads write to it,
     * or let this method take it ({@code lock = true}).
     */
    public static TerminalRecordingScreenSnapshot snapshot(TerminalTextBuffer buffer, Palette palette, boolean colors) {
        buffer.lock();
        try {
            return new TerminalRecordingScreenSnapshot(
                buffer.getScreenLines(),
                buffer.getWidth(),
                buffer.getHeight(),
                0,
                0,
                colors ? styleRuns(buffer, palette) : List.of());
        } finally {
            buffer.unlock();
        }
    }

    /** One colour run per styled text chunk of every visible row. */
    public static List<TerminalRecordingStyleRun> styleRuns(TerminalTextBuffer textBuffer, Palette palette) {
        Palette effective = palette != null ? palette : Palette.DEFAULT;
        List<TerminalRecordingStyleRun> runs = new ArrayList<>();
        for (int row = 0; row < textBuffer.getHeight(); row++) {
            var line = textBuffer.getLine(row);
            if (line == null || line.isNulOrEmpty()) {
                continue;
            }
            int column = 0;
            for (var entry : line.getEntries()) {
                String text = entry.getText() != null ? entry.getText().toString() : "";
                if (!text.isEmpty()) {
                    TextStyle style = drawnStyle(entry.getStyle());
                    runs.add(new TerminalRecordingStyleRun(
                        row,
                        column,
                        text,
                        colorToHex(style != null ? style.getForeground() : null, style, effective, true),
                        colorToHex(style != null ? style.getBackground() : null, style, effective, false),
                        styleOptions(style)));
                }
                column += Math.max(0, entry.getLength());
            }
        }
        return runs;
    }

    /**
     * The style a cell's text is drawn with. A link cell holds a {@link HyperlinkStyle}, which has no
     * colours or attributes of its own: an OSC 8 link keeps the text's style as its previous style, a
     * link drawn over existing text as its original style, and its custom style carries the link
     * colours. Unwrapping in that order keeps the colours, bold and inverse of linked text in
     * recordings, which otherwise showed it in the default colour.
     */
    static TextStyle drawnStyle(TextStyle style) {
        TextStyle current = style;
        // A link opened inside another link wraps it; the depth bound only guards against a cycle.
        for (int depth = 0; depth < MAX_LINK_STYLE_DEPTH && current instanceof HyperlinkStyle link; depth++) {
            if (link.getPrevTextStyle() != null) {
                current = link.getPrevTextStyle();
            } else if (link.getOriginalStyle() != null) {
                current = link.getOriginalStyle();
            } else {
                return link.getCustomStyle();
            }
        }
        return current instanceof HyperlinkStyle link ? link.getCustomStyle() : current;
    }

    /** The snapshot drawn like a recording export frame (monospaced, black background). */
    public static BufferedImage render(TerminalRecordingScreenSnapshot snapshot, boolean includeColor) {
        return TerminalRecordingService.renderScreen(snapshot, includeColor);
    }

    /** {@link #render} encoded as PNG. */
    public static byte[] renderPng(TerminalRecordingScreenSnapshot snapshot, boolean includeColor) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(render(snapshot, includeColor), "png", out);
        return out.toByteArray();
    }

    private static List<String> styleOptions(TextStyle style) {
        if (style == null) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        for (TextStyle.Option option : TextStyle.Option.values()) {
            if (style.hasOption(option)) {
                options.add(option.name());
            }
        }
        return options;
    }

    private static String colorToHex(TerminalColor color, TextStyle style, Palette palette, boolean foreground) {
        if (color == null) {
            return null;
        }
        if (color.isIndexed()) {
            return indexedColorToHex(color.getColorIndex(), style, palette, foreground);
        }
        com.sithtermfx.core.Color resolved;
        try {
            resolved = color.toColor();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (resolved == null) {
            return null;
        }
        return String.format(Locale.ROOT, "#%02X%02X%02X", resolved.getRed(), resolved.getGreen(), resolved.getBlue());
    }

    private static String indexedColorToHex(int colorIndex, TextStyle style, Palette palette, boolean foreground) {
        if (colorIndex < 0) {
            return null;
        }
        if (colorIndex < 16) {
            int ansiIndex = colorIndex % 8;
            boolean bright = colorIndex >= 8
                || (foreground && palette.boldAsBright() && style != null && style.hasOption(TextStyle.Option.BOLD));
            return palette.ansiColors().hex(ansiIndex, bright);
        }
        if (colorIndex < 232) {
            int value = colorIndex - 16;
            return rgbToHex(cubeValue((value / 36) % 6), cubeValue((value / 6) % 6), cubeValue(value % 6));
        }
        if (colorIndex < 256) {
            int level = 8 + ((colorIndex - 232) * 10);
            return rgbToHex(level, level, level);
        }
        return null;
    }

    private static int cubeValue(int component) {
        return component == 0 ? 0 : 55 + (component * 40);
    }

    static String defaultAnsiColor(int index, boolean bright) {
        return switch (index) {
            case 0 -> bright ? "#7F7F7F" : "#000000";
            case 1 -> bright ? "#FF0000" : "#CD0000";
            case 2 -> bright ? "#00FF00" : "#00CD00";
            case 3 -> bright ? "#FFFF00" : "#CDCD00";
            case 4 -> bright ? "#5C5CFF" : "#0000EE";
            case 5 -> bright ? "#FF00FF" : "#CD00CD";
            case 6 -> bright ? "#00FFFF" : "#00CDCD";
            default -> bright ? "#FFFFFF" : "#E5E5E5";
        };
    }

    private static String rgbToHex(int red, int green, int blue) {
        return String.format(Locale.ROOT, "#%02X%02X%02X",
            Math.max(0, Math.min(255, red)), Math.max(0, Math.min(255, green)), Math.max(0, Math.min(255, blue)));
    }
}
