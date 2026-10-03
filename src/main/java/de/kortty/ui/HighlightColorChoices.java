package de.kortty.ui;

import de.kortty.core.TerminalPaletteSupport;
import de.kortty.core.highlight.CompiledHighlightSet;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.HighlightRule;
import javafx.scene.paint.Color;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The colors a highlight rule can use, as the rule editor offers them: <b>Keep</b> (the program's own
 * color), the 16 theme colors ({@code ansi:0} to {@code ansi:15}, which follow the terminal palette and
 * every connection's colors), and <b>Custom</b>, a fixed {@code #RRGGBB} from a color picker.
 *
 * <p>Toolkit-free ({@link Color} is a plain value class), so the mapping between stored text and the
 * editor's choices is unit-tested.
 */
final class HighlightColorChoices {

    static final String KEEP_KEY = "highlight.color.keep";
    static final String CUSTOM_KEY = "highlight.color.custom";
    /** A bright theme color; {0} is the normal color's name. */
    static final String BRIGHT_KEY = "highlight.color.bright";

    /** Names of ANSI colors 0 to 7, in palette order. */
    static final List<String> NAME_KEYS = List.of(
        "highlight.color.black", "highlight.color.red", "highlight.color.green", "highlight.color.yellow",
        "highlight.color.blue", "highlight.color.magenta", "highlight.color.cyan", "highlight.color.white");

    /** Every key of the color choices, for the i18n coverage test. */
    static final List<String> KEYS;

    static {
        List<String> keys = new ArrayList<>(List.of(KEEP_KEY, CUSTOM_KEY, BRIGHT_KEY));
        keys.addAll(NAME_KEYS);
        KEYS = List.copyOf(keys);
    }

    /** The number of theme colors: 8 normal and 8 bright. */
    static final int THEME_COLORS = 2 * ConnectionSettings.ANSI_COLOR_COUNT;

    private HighlightColorChoices() {
    }

    enum Kind {
        /** No color: the program's color stays. */
        KEEP,
        /** A theme color, {@code ansi:N}. */
        THEME,
        /** A fixed color chosen with the picker. */
        CUSTOM
    }

    /**
     * One entry of a color dropdown.
     *
     * @param spec the stored text for {@link Kind#THEME} ({@code ansi:N}); {@code null} for
     *             {@link Kind#KEEP} and {@link Kind#CUSTOM}, whose value comes from the picker
     */
    record Choice(@NotNull Kind kind, @Nullable String spec, @NotNull String label) {

        Choice {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(label, "label");
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Keep, the 16 theme colors in palette order, then Custom. */
    static List<Choice> choices() {
        List<Choice> choices = new ArrayList<>(THEME_COLORS + 2);
        choices.add(new Choice(Kind.KEEP, null, I18n.get(KEEP_KEY)));
        for (int index = 0; index < THEME_COLORS; index++) {
            choices.add(new Choice(Kind.THEME, themeSpec(index), themeLabel(index)));
        }
        choices.add(new Choice(Kind.CUSTOM, null, I18n.get(CUSTOM_KEY)));
        return choices;
    }

    /** {@code ansi:N} for theme color {@code index}. */
    static String themeSpec(int index) {
        checkThemeIndex(index);
        return "ansi:" + index;
    }

    /** The name of theme color {@code index}: "Red", or "Red (bright)" for 9. */
    static String themeLabel(int index) {
        checkThemeIndex(index);
        String name = I18n.get(NAME_KEYS.get(index % ConnectionSettings.ANSI_COLOR_COUNT));
        return index < ConnectionSettings.ANSI_COLOR_COUNT ? name : I18n.get(BRIGHT_KEY, name);
    }

    /** What a stored color is: keep for blank, theme for {@code ansi:N}, custom for anything else. */
    static Kind kindOf(@Nullable String spec) {
        if (spec == null || spec.isBlank()) {
            return Kind.KEEP;
        }
        return themeIndex(spec) >= 0 ? Kind.THEME : Kind.CUSTOM;
    }

    /** The entry of {@code choices} that shows {@code spec}. */
    static Choice choiceFor(@Nullable String spec, @NotNull List<Choice> choices) {
        Kind kind = kindOf(spec);
        String themeSpec = kind == Kind.THEME ? themeSpec(themeIndex(spec)) : null;
        for (Choice choice : choices) {
            if (choice.kind() == kind && (kind != Kind.THEME || themeSpec.equals(choice.spec()))) {
                return choice;
            }
        }
        return choices.getFirst();
    }

    /** The theme color index of an {@code ansi:N} spec, or -1. */
    static int themeIndex(@Nullable String spec) {
        if (spec == null) {
            return -1;
        }
        String value = spec.trim().toLowerCase(Locale.ROOT);
        if (!value.startsWith("ansi:")) {
            return -1;
        }
        String number = value.substring("ansi:".length());
        if (number.isEmpty() || number.length() > 2 || !number.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        int index = Integer.parseInt(number);
        return index < THEME_COLORS ? index : -1;
    }

    /** A picked color as stored: {@code #RRGGBB}, upper case; {@code null} for no color. */
    static @Nullable String customSpec(@Nullable Color color) {
        if (color == null) {
            return null;
        }
        return String.format(Locale.ROOT, "#%02X%02X%02X",
            Math.round(color.getRed() * 255), Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
    }

    /**
     * The color a stored spec stands for, as {@code #RRGGBB}: a theme color through the terminal colors
     * of {@code terminal} (the Colors tab), a custom color as is. {@code null} for keep and for anything
     * {@link CompiledHighlightSet#parseColor} rejects.
     */
    static @Nullable String hexFor(@Nullable String spec, @Nullable ConnectionSettings terminal) {
        if (spec == null || spec.isBlank() || !CompiledHighlightSet.isValidColor(spec)) {
            return null;
        }
        int index = themeIndex(spec);
        if (index >= 0) {
            return TerminalPaletteSupport.effectiveHex(terminal, index % ConnectionSettings.ANSI_COLOR_COUNT,
                index >= ConnectionSettings.ANSI_COLOR_COUNT);
        }
        return spec.trim().toUpperCase(Locale.ROOT);
    }

    /** The terminal's text color (the Colors tab), as {@code #RRGGBB}; white when it is not a color. */
    static String terminalForeground(@Nullable ConnectionSettings terminal) {
        return hexOr(terminal != null ? terminal.getForegroundColor() : null, "#FFFFFF");
    }

    /** The terminal's background (the Colors tab), as {@code #RRGGBB}; dark grey when it is not a color. */
    static String terminalBackground(@Nullable ConnectionSettings terminal) {
        return hexOr(terminal != null ? terminal.getBackgroundColor() : null, "#1E1E1E");
    }

    /**
     * CSS for a label that shows text the way {@code rule} highlights it on the terminal's colors: its
     * text and background color (or the terminal's where it keeps them) and bold, italic and underline.
     * A {@code null} rule gives plain terminal text. Used by the editor's preview and its rules table.
     */
    static String lookStyle(@Nullable HighlightRule rule, @Nullable ConnectionSettings terminal) {
        String foreground = rule != null ? hexFor(rule.getForeground(), terminal) : null;
        String background = rule != null ? hexFor(rule.getBackground(), terminal) : null;
        StringBuilder style = new StringBuilder()
            .append("-fx-text-fill: ").append(foreground != null ? foreground : terminalForeground(terminal)).append(';')
            .append(" -fx-background-color: ").append(background != null ? background : terminalBackground(terminal))
            .append(';');
        if (rule != null && rule.isBold()) {
            style.append(" -fx-font-weight: bold;");
        }
        if (rule != null && rule.isItalic()) {
            style.append(" -fx-font-style: italic;");
        }
        if (rule != null && rule.isUnderline()) {
            style.append(" -fx-underline: true;");
        }
        return style.toString();
    }

    private static String hexOr(@Nullable String color, String fallback) {
        if (color != null) {
            String value = color.trim();
            if (value.length() == 7 && value.charAt(0) == '#'
                    && value.substring(1).chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                return value.toUpperCase(Locale.ROOT);
            }
        }
        return fallback;
    }

    private static void checkThemeIndex(int index) {
        if (index < 0 || index >= THEME_COLORS) {
            throw new IllegalArgumentException("Theme color index out of range [0,15]: " + index);
        }
    }
}
