package de.kortty.ui;

import de.kortty.core.TerminalPaletteSupport;
import de.kortty.model.Theme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;

/**
 * The sample session the Themes tab renders in the selected theme: which text goes on which line
 * and in which colour. Pure data, so it can be tested without the JavaFX toolkit; the painting is
 * {@link TerminalThemePreview}'s job.
 */
final class TerminalPreviewModel {

    /** A run of text in one colour; {@code hex} is a {@code #rrggbb} string, null for the theme foreground. */
    record Run(String text, String hex, boolean bold) {
        static Run plain(String text) {
            return new Run(text, null, false);
        }
    }

    /** One terminal line; {@code cursorAfter} puts the cursor right after the last run. */
    record Line(List<Run> runs, boolean cursorAfter) {
        static Line of(Run... runs) {
            return new Line(List.of(runs), false);
        }
    }

    /** How a theme's cursor style string is drawn. */
    enum CursorShape { BLOCK, UNDERLINE, BAR }

    /** Palette lookup: ANSI index 0–7 and whether the bright variant is wanted, giving {@code #rrggbb}. */
    interface Palette extends BiFunction<Integer, Boolean, String> { }

    static final int BLACK = 0, RED = 1, GREEN = 2, YELLOW = 3, BLUE = 4, MAGENTA = 5, CYAN = 6, WHITE = 7;

    private TerminalPreviewModel() { }

    /** The theme's cursor style ({@code BLINK_BLOCK}, {@code STEADY_UNDERLINE}, {@code BLINK_BAR}, …) as a shape. */
    static CursorShape cursorShape(String cursorStyle) {
        String style = cursorStyle == null ? "" : cursorStyle.toUpperCase(Locale.ROOT);
        if (style.contains("UNDERLINE") || style.contains("UNDER")) {
            return CursorShape.UNDERLINE;
        }
        if (style.contains("BAR") || style.contains("BEAM") || style.contains("VERTICAL")) {
            return CursorShape.BAR;
        }
        return CursorShape.BLOCK;
    }

    /** A palette backed by the global default terminal settings; falls back to the built-in colour for bad values. */
    static Palette palette(de.kortty.model.ConnectionSettings settings) {
        return (index, bright) -> safeHex(TerminalPaletteSupport.effectiveHex(settings, index, bright), index, bright);
    }

    /** {@code hex} when it parses as a colour, else the built-in colour for that index. */
    static String safeHex(String hex, int index, boolean bright) {
        return isColor(hex) ? hex : TerminalPaletteSupport.builtInHex(index, bright);
    }

    /** Whether {@code value} is a {@code #rgb}, {@code #rrggbb} or {@code #rrggbbaa} string. */
    static boolean isColor(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        if (!v.startsWith("#") || (v.length() != 4 && v.length() != 7 && v.length() != 9)) {
            return false;
        }
        for (int i = 1; i < v.length(); i++) {
            if (Character.digit(v.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The sample: a prompt and command, an {@code ls --color} listing, a git status, an error and a
     * warning line, a second prompt with the cursor, and a row of all sixteen palette colours.
     * The text is shell output and stays English on purpose.
     */
    static List<Line> sampleSession(Theme theme, Palette palette) {
        String host = theme != null && theme.getName() != null && !theme.getName().isBlank()
            ? theme.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "")
            : "theme";
        if (host.isEmpty()) {
            host = "theme";
        }
        Run promptUser = new Run("user@" + host, palette.apply(GREEN, true), true);
        Run promptPath = new Run("~/projects", palette.apply(BLUE, true), true);
        List<Line> lines = new ArrayList<>();
        lines.add(Line.of(promptUser, Run.plain(":"), promptPath, Run.plain("$ ls --color -l")));
        lines.add(Line.of(Run.plain("drwxr-xr-x  5 user staff   160 Oct 17 09:12 "), new Run("docs/", palette.apply(BLUE, true), true)));
        lines.add(Line.of(Run.plain("drwxr-xr-x 12 user staff   384 Oct 17 09:12 "), new Run("src/", palette.apply(BLUE, true), true)));
        lines.add(Line.of(Run.plain("-rwxr-xr-x  1 user staff  2048 Oct 16 18:40 "), new Run("build.sh", palette.apply(GREEN, true), true)));
        lines.add(Line.of(Run.plain("lrwxr-xr-x  1 user staff    11 Oct 15 11:03 "), new Run("current", palette.apply(CYAN, true), true),
            Run.plain(" -> "), new Run("release-2/", palette.apply(BLUE, true), true)));
        lines.add(Line.of(Run.plain("-rw-r--r--  1 user staff 51200 Oct 14 07:55 "), new Run("backup.tar.gz", palette.apply(RED, false), true)));
        lines.add(Line.of(Run.plain("-rw-r--r--  1 user staff  1337 Oct 13 22:10 README.md")));
        lines.add(Line.of(promptUser, Run.plain(":"), promptPath, Run.plain("$ git status --short")));
        lines.add(Line.of(new Run(" M ", palette.apply(RED, false), false), Run.plain("src/main.c")));
        lines.add(Line.of(new Run("A  ", palette.apply(GREEN, false), false), Run.plain("src/net/tunnel.c")));
        lines.add(Line.of(new Run("?? ", palette.apply(RED, false), false), Run.plain("notes.txt")));
        lines.add(Line.of(promptUser, Run.plain(":"), promptPath, Run.plain("$ make test")));
        lines.add(Line.of(new Run("warning:", palette.apply(YELLOW, false), true), Run.plain(" tunnel.c:42: unused variable 'retries'")));
        lines.add(Line.of(new Run("error:", palette.apply(RED, true), true), Run.plain(" main.c:108: expected ';' before 'return'")));
        lines.add(Line.of(new Run("PASS", palette.apply(GREEN, false), true), Run.plain(" 24  "),
            new Run("FAIL", palette.apply(RED, false), true), Run.plain(" 1  "),
            new Run("SKIP", palette.apply(MAGENTA, false), true), Run.plain(" 3")));
        List<Run> swatches = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            swatches.add(new Run("██", palette.apply(i, false), false));
        }
        swatches.add(Run.plain(" "));
        for (int i = 0; i < 8; i++) {
            swatches.add(new Run("██", palette.apply(i, true), false));
        }
        lines.add(new Line(List.copyOf(swatches), false));
        lines.add(new Line(List.of(promptUser, Run.plain(":"), promptPath, Run.plain("$ ")), true));
        return List.copyOf(lines);
    }
}
