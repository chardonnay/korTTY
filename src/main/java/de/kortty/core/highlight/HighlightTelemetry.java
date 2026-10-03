package de.kortty.core.highlight;

import java.util.Map;

/**
 * The props of the anonymous {@code terminal_highlight_applied} event, sent once when a pane's shown
 * rule set changes — never per pass or per match. A set is reported by its class only: a built-in by
 * its id, every user set as {@value #SET_CUSTOM}, and no set as {@value #SET_NONE}. Patterns, set
 * names and terminal text are never sent.
 */
public final class HighlightTelemetry {

    /** Prop key of the set's class. */
    public static final String PROP_SET = "set";

    /** Prop key of where the choice came from. */
    public static final String PROP_SOURCE = "source";

    /** A user-defined set. */
    public static final String SET_CUSTOM = "custom";

    /** No set: highlighting off for the pane. */
    public static final String SET_NONE = TerminalHighlightService.NONE_ID;

    /** Chosen in View → Highlighting or the pane's context menu. */
    public static final String SOURCE_MENU = "menu";

    /** Toggled with Cmd/Ctrl+Shift+H. */
    public static final String SOURCE_SHORTCUT = "shortcut";

    /** The connection's own set. */
    public static final String SOURCE_CONNECTION = "connection";

    /** The global default set. */
    public static final String SOURCE_DEFAULT = "default";

    private HighlightTelemetry() {
    }

    /** The class of a set id: the built-in's id, {@value #SET_CUSTOM}, or {@value #SET_NONE} for none. */
    public static String setClass(String setId) {
        if (setId == null || setId.isBlank() || SET_NONE.equals(setId.trim())) {
            return SET_NONE;
        }
        String id = setId.trim();
        return HighlightBuiltinSets.isBuiltin(id) ? id : SET_CUSTOM;
    }

    /**
     * The source of a set a pane shows without a choice of its own: {@value #SOURCE_CONNECTION} when the
     * connection's rule set decided, otherwise {@value #SOURCE_DEFAULT}.
     */
    public static String inheritedSource(TerminalHighlightService.Level level) {
        return level == TerminalHighlightService.Level.CONNECTION ? SOURCE_CONNECTION : SOURCE_DEFAULT;
    }

    /** The props for a pane that now shows {@code shownSetId} ({@code null} = none) because of {@code source}. */
    public static Map<String, Object> props(String shownSetId, String source) {
        return Map.of(PROP_SET, setClass(shownSetId), PROP_SOURCE, source);
    }
}
