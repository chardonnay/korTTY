package de.kortty.ui;

import com.sithtermfx.ui.settings.SearchBarText;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import org.jetbrains.annotations.NotNull;

/**
 * The texts of the terminal find bar ({@code Cmd/Ctrl+F}) in the UI language. SithTermFX reads them each
 * time the bar opens, so a language change shows the next time it is opened. The {@code ▼}/{@code ▲}
 * buttons keep their arrow symbols; only their tooltips are translated.
 */
final class TerminalFindBarText implements SearchBarText {

    static final String PLACEHOLDER_KEY = "terminal.find.placeholder";
    static final String IGNORE_CASE_KEY = "terminal.find.ignoreCase";
    static final String IGNORE_CASE_TOOLTIP_KEY = "terminal.find.ignoreCase.tooltip";
    /** {0} = the one-based number of the selected match, {1} = the number of matches. */
    static final String COUNTER_KEY = "terminal.find.counter";
    static final String NO_MATCHES_KEY = "terminal.find.noMatches";
    static final String NEXT_TOOLTIP_KEY = "terminal.find.next.tooltip";
    static final String PREVIOUS_TOOLTIP_KEY = "terminal.find.previous.tooltip";

    /** Every key this class reads. */
    static final List<String> KEYS = List.of(PLACEHOLDER_KEY, IGNORE_CASE_KEY, IGNORE_CASE_TOOLTIP_KEY,
        COUNTER_KEY, NO_MATCHES_KEY, NEXT_TOOLTIP_KEY, PREVIOUS_TOOLTIP_KEY);

    /** The texts from the app's current language. */
    static final TerminalFindBarText INSTANCE = new TerminalFindBarText(I18n::get);

    private final BiFunction<String, Object[], String> lookup;

    /**
     * @param lookup resolves a key and fills its {0}, {1}… with the arguments, like
     *               {@link I18n#get(String, Object...)}
     */
    TerminalFindBarText(BiFunction<String, Object[], String> lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    private @NotNull String text(String key, Object... args) {
        String value = lookup.apply(key, args);
        // A missing bundle entry comes back as the key itself; never show a raw key in the bar.
        return value == null || value.equals(key) ? fallback(key, args) : value;
    }

    private static String fallback(String key, Object[] args) {
        return switch (key) {
            case PLACEHOLDER_KEY -> ENGLISH.placeholder();
            case IGNORE_CASE_KEY -> ENGLISH.ignoreCase();
            case IGNORE_CASE_TOOLTIP_KEY -> ENGLISH.ignoreCaseTooltip();
            case COUNTER_KEY -> ENGLISH.matchCounter((Integer) args[0], (Integer) args[1]);
            case NO_MATCHES_KEY -> ENGLISH.noMatches();
            case NEXT_TOOLTIP_KEY -> ENGLISH.nextMatchTooltip();
            case PREVIOUS_TOOLTIP_KEY -> ENGLISH.previousMatchTooltip();
            default -> key;
        };
    }

    @Override
    public @NotNull String placeholder() {
        return text(PLACEHOLDER_KEY);
    }

    @Override
    public @NotNull String ignoreCase() {
        return text(IGNORE_CASE_KEY);
    }

    @Override
    public @NotNull String ignoreCaseTooltip() {
        return text(IGNORE_CASE_TOOLTIP_KEY);
    }

    @Override
    public @NotNull String matchCounter(int current, int total) {
        return text(COUNTER_KEY, current, total);
    }

    @Override
    public @NotNull String noMatches() {
        return text(NO_MATCHES_KEY);
    }

    @Override
    public @NotNull String nextMatchTooltip() {
        return text(NEXT_TOOLTIP_KEY);
    }

    @Override
    public @NotNull String previousMatchTooltip() {
        return text(PREVIOUS_TOOLTIP_KEY);
    }
}
