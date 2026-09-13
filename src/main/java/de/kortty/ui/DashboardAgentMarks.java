package de.kortty.ui;

import de.kortty.codingagent.CodingAgentEntry;
import de.kortty.codingagent.CodingAgentGlyphs;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.core.AgentDashboardStatus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.BiFunction;

/**
 * Pure, FX-free helpers behind the coding-agent marks of {@link DashboardView}: chip text and style
 * class, the accent-bar class of a row, the tooltip line and the inline agent colour tokens. Kept
 * separate so the mapping is unit-testable without a JavaFX toolkit.
 */
final class DashboardAgentMarks {

    /** Style class prefix of the chip variants ({@code -blocked}, {@code -working}, {@code -done}, {@code -idle}). */
    static final String CHIP_CLASS_PREFIX = "dashboard-agent-chip-";
    /** Style class of the glyph-only badge of rows that only host korTTY's own AI agent. */
    static final String LEGACY_CHIP_CLASS = CHIP_CLASS_PREFIX + "legacy";
    /** Style class prefix of the TreeCell accent bar ({@code -blocked}, {@code -working}, {@code -done}). */
    static final String ACCENT_CLASS_PREFIX = "dashboard-row-agent-";

    private static final String TOOLTIP_KEY = "dashboard.agent.tooltip";
    private static final String STATE_KEY_PREFIX = "codingAgent.state.";
    private static final DateTimeFormatter SINCE_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private static final String DARK_TOKENS = "-kortty-dash-agent-blocked: #f59e0b;"
        + "-kortty-dash-agent-working: #60a5fa;"
        + "-kortty-dash-agent-done: #34d399;"
        + "-kortty-dash-agent-blocked-tint: rgba(245,158,11,0.14);"
        + "-kortty-dash-agent-chip-fg: #0b1220;";
    private static final String LIGHT_TOKENS = "-kortty-dash-agent-blocked: #b45309;"
        + "-kortty-dash-agent-working: #1d4ed8;"
        + "-kortty-dash-agent-done: #15803d;"
        + "-kortty-dash-agent-blocked-tint: rgba(180,83,9,0.12);"
        + "-kortty-dash-agent-chip-fg: #ffffff;";

    private DashboardAgentMarks() {
    }

    /**
     * What a row's chip shows.
     *
     * @param label the chip text ("✋ claude"), or the bare legacy glyph
     * @param styleClass the variant style class ({@code dashboard-agent-chip-blocked} ...)
     * @param pulse true only while the row's agent is BLOCKED (the status dot breathes)
     */
    record ChipText(String label, String styleClass, boolean pulse) {
    }

    /** The chip of a coding-agent entry; IDLE/UNKNOWN agents get the dim idle chip; null for null. */
    static ChipText chipFor(CodingAgentEntry entry) {
        if (entry == null) {
            return null;
        }
        CodingAgentState state = entry.state();
        return new ChipText(CodingAgentGlyphs.chipText(entry), CHIP_CLASS_PREFIX + chipVariantOf(state),
            state == CodingAgentState.BLOCKED);
    }

    /** The chip variant of a state: blocked / working / done, and "idle" for IDLE, UNKNOWN and null. */
    private static String chipVariantOf(CodingAgentState state) {
        if (state == null) {
            return "idle";
        }
        return switch (state) {
            case BLOCKED -> "blocked";
            case WORKING -> "working";
            case DONE -> "done";
            default -> "idle";
        };
    }

    /**
     * The chip of a row that hosts a coding agent AND korTTY's own AI agent: the glyph is the merged
     * tab badge ({@link CodingAgentGlyphs#tabBadge}), the name comes from the coding agent and the
     * colour follows the merged mark so title, chip and rollup agree. Without a legacy state this is
     * {@link #chipFor(CodingAgentEntry)}.
     */
    static ChipText chipFor(CodingAgentEntry entry, AgentDashboardStatus.State legacy) {
        if (entry == null) {
            return null;
        }
        CodingAgentGlyphs.Mark legacyMark = CodingAgentGlyphs.fromLegacyState(legacy);
        if (legacyMark == CodingAgentGlyphs.Mark.NONE) {
            return chipFor(entry);
        }
        CodingAgentGlyphs.Mark mark = CodingAgentGlyphs.merge(legacyMark, CodingAgentGlyphs.fromCodingState(entry.state()));
        String glyph = mark.glyph().isEmpty() ? CodingAgentGlyphs.IDLE_GLYPH : mark.glyph();
        String variant = switch (mark) {
            case BLOCKED -> "blocked";
            case DONE -> "done";
            case WORKING, PAUSED -> "working";
            default -> "idle";
        };
        return new ChipText(glyph + " " + entry.shortName(), CHIP_CLASS_PREFIX + variant,
            mark == CodingAgentGlyphs.Mark.BLOCKED);
    }

    /** The glyph-only badge of a legacy AI-agent row; null when the glyph is empty. */
    static ChipText legacyChip(String legacyGlyph) {
        if (legacyGlyph == null || legacyGlyph.isEmpty()) {
            return null;
        }
        return new ChipText(legacyGlyph, LEGACY_CHIP_CLASS, false);
    }

    /** The TreeCell accent class of a state; null for IDLE, UNKNOWN and null (no bar). */
    static String accentClassFor(CodingAgentState state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case BLOCKED -> ACCENT_CLASS_PREFIX + "blocked";
            case WORKING -> ACCENT_CLASS_PREFIX + "working";
            case DONE -> ACCENT_CLASS_PREFIX + "done";
            default -> null;
        };
    }

    /** True for container rows (bold header typography); CONNECTION and PANE rows are leaves. */
    static boolean isHeader(DashboardView.NodeType type) {
        return type != null && type != DashboardView.NodeType.CONNECTION && type != DashboardView.NodeType.PANE;
    }

    /**
     * The tooltip line "{displayName} · {state label} since {HH:mm}" ({@code dashboard.agent.tooltip})
     * in the system time zone; {@code messages} resolves i18n keys with arguments.
     */
    static String tooltipLine(CodingAgentEntry entry, long nowMillis, BiFunction<String, Object[], String> messages) {
        return tooltipLine(entry, nowMillis, messages, ZoneId.systemDefault());
    }

    /** {@link #tooltipLine(CodingAgentEntry, long, BiFunction)} with an explicit zone (tests). */
    static String tooltipLine(CodingAgentEntry entry, long nowMillis, BiFunction<String, Object[], String> messages,
                              ZoneId zone) {
        if (entry == null || messages == null) {
            return "";
        }
        long since = entry.stateSinceMillis() > 0 ? entry.stateSinceMillis() : nowMillis;
        String time = SINCE_FORMAT.format(Instant.ofEpochMilli(since).atZone(zone != null ? zone : ZoneId.systemDefault()));
        String stateLabel = messages.apply(STATE_KEY_PREFIX + variantOf(entry.state()), new Object[0]);
        return messages.apply(TOOLTIP_KEY, new Object[] {entry.displayName(), stateLabel, time});
    }

    /**
     * The inline {@code -kortty-dash-agent-*} token overrides {@link DashboardView#applyTheme} appends
     * for non-custom designs: readable-on-light colours for a light panel background, the
     * terminal.css dark defaults otherwise.
     */
    static String agentTokensFor(boolean lightBackground) {
        return lightBackground ? LIGHT_TOKENS : DARK_TOKENS;
    }

    /** The lower-case state name used as style-class and i18n suffix; IDLE/UNKNOWN share "idle"/"unknown". */
    private static String variantOf(CodingAgentState state) {
        if (state == null) {
            return "unknown";
        }
        return switch (state) {
            case BLOCKED -> "blocked";
            case WORKING -> "working";
            case DONE -> "done";
            case IDLE -> "idle";
            case UNKNOWN -> "unknown";
        };
    }
}
