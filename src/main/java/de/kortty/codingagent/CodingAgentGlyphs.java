package de.kortty.codingagent;

import de.kortty.core.AgentDashboardStatus;

/**
 * Glyphs and short texts shared by the dashboard, tab titles, the panel and the status strip, plus
 * the merge of korTTY's own AI-agent status with a pane's coding-agent state.
 */
public final class CodingAgentGlyphs {

    /**
     * Source-neutral mark, declared in merge order: a later constant outranks an earlier one
     * (DONE-until-seen outranks WORKING so title, chip and rollup agree).
     */
    public enum Mark {
        NONE(""),
        WORKING(CodingAgentGlyphs.WORKING_GLYPH),
        PAUSED(CodingAgentGlyphs.PAUSED_GLYPH),
        DONE(CodingAgentGlyphs.DONE_GLYPH),
        BLOCKED(CodingAgentGlyphs.BLOCKED_GLYPH);

        private final String glyph;

        Mark(String glyph) {
            this.glyph = glyph;
        }

        /** The tab-title glyph; "" for NONE. */
        public String glyph() {
            return glyph;
        }
    }

    public static final String BLOCKED_GLYPH = "✋";
    public static final String WORKING_GLYPH = "⚡";
    public static final String PAUSED_GLYPH = "⏸";
    public static final String DONE_GLYPH = "✓";
    public static final String IDLE_GLYPH = "·";
    public static final String SEPARATOR = " · ";

    private static final String ELLIPSIS = "…";

    private CodingAgentGlyphs() {
    }

    /** BLOCKED, DONE, WORKING map to their marks; IDLE, UNKNOWN and null to NONE. */
    public static Mark fromCodingState(CodingAgentState state) {
        if (state == null) {
            return Mark.NONE;
        }
        return switch (state) {
            case BLOCKED -> Mark.BLOCKED;
            case DONE -> Mark.DONE;
            case WORKING -> Mark.WORKING;
            default -> Mark.NONE;
        };
    }

    /** AWAITING maps to BLOCKED, the other legacy states to their namesakes. */
    public static Mark fromLegacyState(AgentDashboardStatus.State legacy) {
        if (legacy == null) {
            return Mark.NONE;
        }
        return switch (legacy) {
            case AWAITING -> Mark.BLOCKED;
            case WORKING -> Mark.WORKING;
            case PAUSED -> Mark.PAUSED;
            case DONE -> Mark.DONE;
            default -> Mark.NONE;
        };
    }

    /** The more urgent of two marks (NONE &lt; WORKING &lt; PAUSED &lt; DONE &lt; BLOCKED). */
    public static Mark merge(Mark a, Mark b) {
        if (a == null) {
            return b == null ? Mark.NONE : b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** The chip glyph of an effective state; IDLE/UNKNOWN render as {@link #IDLE_GLYPH}. */
    public static String glyph(CodingAgentState state) {
        if (state == null) {
            return IDLE_GLYPH;
        }
        return switch (state) {
            case BLOCKED -> BLOCKED_GLYPH;
            case DONE -> DONE_GLYPH;
            case WORKING -> WORKING_GLYPH;
            default -> IDLE_GLYPH;
        };
    }

    /** The tab-title badge merged from both sources; "" when neither has anything to show. */
    public static String tabBadge(AgentDashboardStatus.State legacy, CodingAgentState codingState) {
        return merge(fromLegacyState(legacy), fromCodingState(codingState)).glyph();
    }

    /** "✋ 1 · ⚡ 2 · ✓ 1" with zero counts omitted; "" when all are zero. */
    public static String rollupText(int blocked, int working, int done) {
        StringBuilder sb = new StringBuilder();
        append(sb, BLOCKED_GLYPH, blocked);
        append(sb, WORKING_GLYPH, working);
        append(sb, DONE_GLYPH, done);
        return sb.toString();
    }

    /** {@link #rollupText} of a summary; "" for an empty or null summary. */
    public static String summaryText(AgentSummary summary) {
        if (summary == null || summary.isEmpty()) {
            return "";
        }
        return rollupText(summary.blocked(), summary.working(), summary.done());
    }

    /** "✋ claude" (glyph + alias or short name); "" for null. */
    public static String chipText(CodingAgentEntry entry) {
        if (entry == null) {
            return "";
        }
        return glyph(entry.state()) + " " + entry.shortName();
    }

    /** claude / codex / gemini / agent. */
    public static String shortName(CodingAgentKind kind) {
        if (kind == null) {
            return "agent";
        }
        return switch (kind) {
            case CLAUDE_CODE -> "claude";
            case CODEX -> "codex";
            case GEMINI_CLI -> "gemini";
            default -> "agent";
        };
    }

    /**
     * The first non-blank line of the evidence, stripped and truncated to {@code maxChars}
     * (ending in "…" when cut); "" when there is no evidence.
     */
    public static String evidenceLine(DetectionResult detection, int maxChars) {
        if (detection == null || detection.evidence() == null) {
            return "";
        }
        String line = null;
        for (String candidate : detection.evidence().split("\\R")) {
            if (!candidate.isBlank()) {
                line = candidate.strip();
                break;
            }
        }
        if (line == null) {
            return "";
        }
        if (maxChars <= 0 || line.length() <= maxChars) {
            return line;
        }
        if (maxChars == 1) {
            return ELLIPSIS;
        }
        return line.substring(0, maxChars - 1).stripTrailing() + ELLIPSIS;
    }

    private static void append(StringBuilder sb, String glyph, int count) {
        if (count <= 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(SEPARATOR);
        }
        sb.append(glyph).append(' ').append(count);
    }
}
