package de.kortty.control;

import java.util.Map;
import java.util.Objects;

/**
 * A parsed pane selector; exactly one shape is populated.
 *
 * <p>Accepted texts: {@code p1a2b3c4d} (a bare pane id), {@code w1:t9f3a…:p1a2b} (qualified),
 * {@code t9f3a…} (a tab, meaning that tab's focused pane) and {@code @focused}. A bare window id
 * where a pane is expected is {@link ControlErrorCode#AMBIGUOUS_PANE} rather than a guess, because a
 * window commonly holds several panes and picking one silently is how a script types into the wrong
 * terminal.
 *
 * <p>Pure, any thread.
 *
 * @param kind which shape was given
 * @param windowId the window part, or null
 * @param tabId the tab part, or null
 * @param paneId the pane part, or null
 */
public record PaneAddress(Kind kind, String windowId, String tabId, String paneId) {

    /** The alias for the pane the user is looking at. */
    private static final String FOCUSED_ALIAS = "@focused";

    /** The shapes a selector can take. */
    public enum Kind {
        /** A bare pane id, which must be unique among live widgets. */
        PANE,
        /** A bare tab id, meaning that tab's focused pane. */
        TAB,
        /** A full {@code window:tab:pane} address. */
        QUALIFIED,
        /** {@code @focused}. */
        FOCUSED
    }

    public PaneAddress {
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * Parses a selector.
     *
     * @throws ControlApiException {@link ControlErrorCode#AMBIGUOUS_PANE} for a bare window id,
     *     {@link ControlErrorCode#INVALID_PARAMS} for anything else that is not a selector
     */
    public static PaneAddress parse(String text) throws ControlApiException {
        if (text == null || text.isBlank()) {
            throw invalid(text);
        }
        String value = text.strip();
        if (FOCUSED_ALIAS.equalsIgnoreCase(value)) {
            return focused();
        }
        if (value.indexOf(':') >= 0) {
            String[] parts = value.split(":", -1);
            if (parts.length != 3
                    || !hasPrefix(parts[0], ControlIds.WINDOW_PREFIX)
                    || !hasPrefix(parts[1], ControlIds.TAB_PREFIX)
                    || !hasPrefix(parts[2], ControlIds.PANE_PREFIX)) {
                throw invalid(text);
            }
            return new PaneAddress(Kind.QUALIFIED, parts[0], parts[1], parts[2]);
        }
        if (hasPrefix(value, ControlIds.PANE_PREFIX)) {
            return new PaneAddress(Kind.PANE, null, null, value);
        }
        if (hasPrefix(value, ControlIds.TAB_PREFIX)) {
            return new PaneAddress(Kind.TAB, null, value, null);
        }
        if (hasPrefix(value, ControlIds.WINDOW_PREFIX)) {
            throw new ControlApiException(ControlErrorCode.AMBIGUOUS_PANE,
                "A window id does not identify a pane: " + value,
                Map.of("selector", value, "hint", "use a pane id, a tab id or @focused"));
        }
        throw invalid(text);
    }

    /** A selector for one known pane id. */
    public static PaneAddress ofPaneId(String paneId) {
        if (paneId == null || paneId.isBlank()) {
            throw new IllegalArgumentException("paneId must not be blank");
        }
        return new PaneAddress(Kind.PANE, null, null, paneId.strip());
    }

    /** The selector for the pane the user is looking at. */
    public static PaneAddress focused() {
        return new PaneAddress(Kind.FOCUSED, null, null, null);
    }

    /** The canonical text of this selector; {@link #parse(String)} round-trips it. */
    public String wire() {
        return switch (kind) {
            case PANE -> paneId;
            case TAB -> tabId;
            case QUALIFIED -> windowId + ":" + tabId + ":" + paneId;
            case FOCUSED -> FOCUSED_ALIAS;
        };
    }

    private static boolean hasPrefix(String value, String prefix) {
        return value != null && value.length() > prefix.length() && value.startsWith(prefix);
    }

    private static ControlApiException invalid(String text) {
        return new ControlApiException(ControlErrorCode.INVALID_PARAMS,
            "Not a pane selector: " + text,
            Map.of("param", "pane", "expected", "p<id> | w<id>:t<id>:p<id> | t<id> | @focused"));
    }
}
