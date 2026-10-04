package de.kortty.telemetry;

import de.kortty.model.SessionRestoreMode;
import de.kortty.ui.MultiExecMembership;
import de.kortty.ui.actions.PaletteEntry;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The props of the anonymous usage events of the terminal UX: {@value TelemetryEvents#COMMAND_PALETTE_USED},
 * {@value TelemetryEvents#MULTI_EXEC_CHANGED} and {@value TelemetryEvents#SESSION_RESTORED}
 * ({@code terminal_highlight_applied} has its own builder, {@code HighlightTelemetry}).
 *
 * <p>Anti-PII contract: every value is a boolean, a coarse number ({@link #countBucket}) or one of a
 * fixed set of ids. Never a query, a host, a user name, a tab or snippet title, a command or an
 * action id. FX-free, so the prop types are unit-tested without the toolkit.
 */
public final class TerminalUxTelemetry {

    /** The lower bounds of the buckets a count is reported in; anything from the last one up is reported as it. */
    static final int[] COUNT_BUCKETS = {0, 1, 2, 3, 5, 10, 20};

    /** How a restore of the previous session was started. */
    public enum RestoreTrigger {
        /** File › Restore Previous Session, also through the command palette or its shortcut. */
        MENU,
        /** The Restore button of the startup offer bar ({@code ask}). */
        OFFER,
        /** By itself at startup ({@code auto}). */
        AUTO;

        /** The id sent: the lowercase name. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private TerminalUxTelemetry() {
    }

    /**
     * {@code command_palette_used}: a row was run. {@code kind} is the row's kind (action, tab,
     * connection, snippet), {@code scoped} whether the query started with a scope prefix. Neither the
     * query nor the row is sent.
     */
    public static Map<String, Object> commandPaletteUsed(PaletteEntry.Kind kind, boolean scoped) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("kind", kind != null ? kind.name().toLowerCase(Locale.ROOT) : "unknown");
        props.put("scoped", scoped);
        return props;
    }

    /**
     * {@code multi_exec_changed}: the user changed which panes take part. {@code enabled} is whether
     * any pane takes part afterwards; the panes, tabs and windows they are in come as
     * {@link #countBucket buckets}.
     */
    public static Map<String, Object> multiExecChanged(boolean enabled, MultiExecMembership.Counts counts) {
        MultiExecMembership.Counts reach = counts != null ? counts : MultiExecMembership.Counts.NONE;
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("enabled", enabled);
        props.put("panes", countBucket(reach.panes()));
        props.put("tabs", countBucket(reach.tabs()));
        props.put("windows", countBucket(reach.windows()));
        return props;
    }

    /**
     * {@code session_restored}: the previous session was opened. {@code mode} is the Session Restore
     * setting ({@code ask}, {@code auto}, {@code off}), {@code trigger} what started it, and the
     * windows and tabs it opened come as {@link #countBucket buckets}.
     */
    public static Map<String, Object> sessionRestored(SessionRestoreMode mode, RestoreTrigger trigger,
                                                      int windows, int tabs) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("mode", (mode != null ? mode : SessionRestoreMode.DEFAULT).id());
        props.put("trigger", (trigger != null ? trigger : RestoreTrigger.MENU).id());
        props.put("windows", countBucket(windows));
        props.put("tabs", countBucket(tabs));
        return props;
    }

    /**
     * A count rounded down to the lower bound of its bucket (0, 1, 2, 3, 5, 10, 20): enough to tell
     * one from a few from many, too coarse to tell one installation from another.
     */
    public static int countBucket(int count) {
        int bucket = 0;
        for (int bound : COUNT_BUCKETS) {
            if (count >= bound) {
                bucket = bound;
            }
        }
        return bucket;
    }
}
