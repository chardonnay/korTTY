package de.kortty.ui;

import de.kortty.core.swarm.SwarmModels;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Pure, FX-free logic behind {@link SwarmStatusStrip}: the adaptive slow-agent rule, orb layout
 * math, state-to-color mapping, legend summary and the deterministic animation curves. Everything
 * takes time as a parameter so it is unit-testable without a JavaFX toolkit.
 */
final class SwarmStatusStripSupport {

    private SwarmStatusStripSupport() {
    }

    // ---- Per-agent view model (mutated only on the FX thread) ----------------

    /** Mutable render model of one orb. Keyed by agentId in live mode, by list index in static mode. */
    static final class AgentViz {
        final String agentId;
        final String displayName;
        SwarmModels.SwarmAgentState state = SwarmModels.SwarmAgentState.QUEUED;
        String lastActivity = "";
        long elapsedSeconds;
        long totalTokens;
        long startedAtMillis;
        double lastStateChangeT;
        boolean slow;

        AgentViz(String agentId, String displayName) {
            this.agentId = agentId != null ? agentId : "";
            this.displayName = displayName != null ? displayName : "";
        }
    }

    // ---- Adaptive slow rule ---------------------------------------------------

    static final long FALLBACK_SLOW_THRESHOLD_SECONDS = 180L;
    static final long MIN_ADAPTIVE_SLOW_SECONDS = 60L;

    /** Median with even-size ties resolved as the rounded-up mean of the two middle values. */
    static long medianOf(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid) + 1L) / 2L;
    }

    /**
     * Threshold above which a working agent counts as "unusually long": twice the median of the
     * already-finished agents (at least {@link #MIN_ADAPTIVE_SLOW_SECONDS}); while fewer than two
     * agents finished, a fixed {@link #FALLBACK_SLOW_THRESHOLD_SECONDS}.
     */
    static long slowThresholdSeconds(List<Long> doneElapsedSeconds) {
        if (doneElapsedSeconds == null || doneElapsedSeconds.size() < 2) {
            return FALLBACK_SLOW_THRESHOLD_SECONDS;
        }
        return Math.max(MIN_ADAPTIVE_SLOW_SECONDS, 2L * medianOf(doneElapsedSeconds));
    }

    /**
     * Only agents actively working can be slow; QUEUED (not started), AWAITING_APPROVAL (elapsed
     * accrues while a human decides) and terminal states never are.
     */
    static boolean isSlow(SwarmModels.SwarmAgentState state, long elapsedSeconds, List<Long> doneElapsedSeconds) {
        if (state != SwarmModels.SwarmAgentState.CONNECTING
            && state != SwarmModels.SwarmAgentState.PROBING
            && state != SwarmModels.SwarmAgentState.RUNNING) {
            return false;
        }
        return elapsedSeconds >= slowThresholdSeconds(doneElapsedSeconds);
    }

    /** Elapsed seconds of DONE agents only — failures and cancellations don't inform the median. */
    static List<Long> doneElapsedSeconds(Collection<AgentViz> agents) {
        List<Long> done = new ArrayList<>();
        for (AgentViz agent : agents) {
            if (agent.state == SwarmModels.SwarmAgentState.DONE) {
                done.add(agent.elapsedSeconds);
            }
        }
        return done;
    }

    static void refreshSlowFlags(Collection<AgentViz> agents) {
        List<Long> done = doneElapsedSeconds(agents);
        for (AgentViz agent : agents) {
            agent.slow = isSlow(agent.state, agent.elapsedSeconds, done);
        }
    }

    // ---- State classification & colors ---------------------------------------

    static boolean isTerminal(SwarmModels.SwarmAgentState state) {
        return state == SwarmModels.SwarmAgentState.DONE
            || state == SwarmModels.SwarmAgentState.FAILED
            || state == SwarmModels.SwarmAgentState.CANCELLED
            || state == SwarmModels.SwarmAgentState.SKIPPED;
    }

    /** States whose orb carries a continuous animation (breathe/blink/spinner). */
    static boolean isAnimated(SwarmModels.SwarmAgentState state) {
        return !isTerminal(state)
            && state != SwarmModels.SwarmAgentState.QUEUED
            && state != SwarmModels.SwarmAgentState.PAUSED;
    }

    static final String SLOW_RING_HEX = "#ffb300";

    /** Bright orb core color; {@code accentHexOrNull} substitutes the RUNNING color for app designs. */
    static String coreColorHex(SwarmModels.SwarmAgentState state, String accentHexOrNull) {
        return switch (state) {
            case QUEUED -> "#78909c";
            case CONNECTING, PROBING -> "#4f9cf0";
            case RUNNING -> accentHexOrNull != null ? accentHexOrNull : "#4f9cf0";
            case AWAITING_APPROVAL -> "#ff9800";
            case PAUSED -> "#b39ddb";
            case DONE -> "#4caf50";
            case FAILED -> "#ef5350";
            case CANCELLED, SKIPPED -> "#757575";
        };
    }

    /** Glow disc color; deliberately matches the dashboard badge hexes of SwarmAgentRow. */
    static String glowColorHex(SwarmModels.SwarmAgentState state) {
        return switch (state) {
            case QUEUED -> "#455a64";
            case CONNECTING, PROBING, RUNNING -> "#1565c0";
            case AWAITING_APPROVAL -> "#e65100";
            case PAUSED -> "#512da8";
            case DONE -> "#2e7d32";
            case FAILED -> "#c62828";
            case CANCELLED, SKIPPED -> "#616161";
        };
    }

    // ---- Layout ---------------------------------------------------------------

    record OrbGeometry(double cx, double cy, double radius) {
    }

    enum LabelMode { FULL, ABBREVIATED, HIDDEN }

    record StripLayout(List<OrbGeometry> orbs, LabelMode labelMode, double slotWidth,
                       int rows, int overflowCount) {

        static StripLayout empty() {
            return new StripLayout(List.of(), LabelMode.HIDDEN, 0, 1, 0);
        }
    }

    private static final double MAX_SLOT = 64;
    private static final double LABEL_SPACE = 16;
    private static final double MIN_RADIUS_TWO_ROWS = 4;

    /**
     * Positions {@code count} orbs inside a {@code fieldWidth} x {@code fieldHeight} area. Total
     * function: degenerate inputs yield an empty layout, never NaN. Single row with a gentle sine
     * lane; wraps to a two-row honeycomb when orbs would overlap; beyond ~2 rows of minimum-size
     * orbs the tail is folded into an overflow count.
     */
    static StripLayout layout(int count, double fieldWidth, double fieldHeight) {
        if (count <= 0 || fieldWidth <= 0 || fieldHeight <= 0
            || Double.isNaN(fieldWidth) || Double.isNaN(fieldHeight)) {
            return StripLayout.empty();
        }
        double labelSpace = LABEL_SPACE;
        double rawSlot = fieldWidth / count;
        double slot = Math.min(rawSlot, MAX_SLOT);
        double radius = clamp(slot * 0.32, 6, 16);
        radius = Math.min(radius, Math.max(2, (fieldHeight - labelSpace) * 0.28));

        int rows = 1;
        int cols = count;
        int renderable = count;
        int overflow = 0;
        if (slot < 2 * radius + 4) {
            rows = 2;
            cols = (count + 1) / 2;
            slot = fieldWidth / (cols + 0.5);
            radius = clamp(slot * 0.36, MIN_RADIUS_TWO_ROWS, 12);
            radius = Math.min(radius, Math.max(2, fieldHeight * 0.28));
            if (slot < 2 * MIN_RADIUS_TWO_ROWS) {
                cols = Math.max(1, (int) Math.floor(fieldWidth / (2 * MIN_RADIUS_TWO_ROWS)) - 1);
                slot = fieldWidth / (cols + 0.5);
                radius = clamp(slot * 0.36, MIN_RADIUS_TWO_ROWS, 12);
                radius = Math.min(radius, Math.max(2, fieldHeight * 0.28));
                renderable = Math.min(count, cols * 2);
                overflow = count - renderable;
            }
        }

        // Label room is judged by the uncapped per-orb share: the slot itself is capped at
        // MAX_SLOT, but the leftover space around centered orbs still fits full labels.
        LabelMode labelMode;
        if (rows == 2) {
            labelMode = LabelMode.HIDDEN;
        } else if (rawSlot >= 76 && count <= 12) {
            labelMode = LabelMode.FULL;
        } else if (rawSlot >= 44) {
            labelMode = LabelMode.ABBREVIATED;
        } else {
            labelMode = LabelMode.HIDDEN;
        }

        double startX = rows == 1
            ? (fieldWidth - slot * count) / 2 + slot / 2
            : slot / 2;
        double waveAmplitude = Math.min(8, fieldHeight * 0.08);
        List<OrbGeometry> orbs = new ArrayList<>(renderable);
        for (int i = 0; i < renderable; i++) {
            double cx;
            double cy;
            if (rows == 1) {
                cx = startX + i * slot;
                cy = fieldHeight * 0.46 + waveAmplitude * Math.sin(2 * Math.PI * i / 6.0);
            } else {
                int row = i % 2;
                int col = i / 2;
                cx = startX + col * slot + (row == 1 ? slot * 0.5 : 0);
                cy = fieldHeight * (row == 0 ? 0.32 : 0.68);
            }
            orbs.add(new OrbGeometry(cx, cy, radius));
        }
        return new StripLayout(List.copyOf(orbs), labelMode, slot, rows, overflow);
    }

    /** Index of the orb whose disc (radius + 6px halo) contains the point, or -1. Nearest wins. */
    static int orbIndexAt(StripLayout layout, double x, double y) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        List<OrbGeometry> orbs = layout.orbs();
        for (int i = 0; i < orbs.size(); i++) {
            OrbGeometry orb = orbs.get(i);
            double dx = x - orb.cx();
            double dy = y - orb.cy();
            double distance = Math.sqrt(dx * dx + dy * dy);
            if (distance <= orb.radius() + 6 && distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /** Shortens a server name for the orb label: drops the domain suffix, then ellipsizes. */
    static String abbreviate(String name, int maxChars) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String shortName = name;
        int dot = shortName.indexOf('.');
        if (dot > 0) {
            shortName = shortName.substring(0, dot);
        }
        if (maxChars < 2) {
            maxChars = 2;
        }
        if (shortName.length() > maxChars) {
            shortName = shortName.substring(0, maxChars - 1) + "…";
        }
        return shortName;
    }

    // ---- Legend ----------------------------------------------------------------

    record VizSummary(int total, int active, int waiting, int paused, int done, int failed, int inactive, int slow) {
    }

    record LegendChip(String colorHex, int count, boolean blinking) {
    }

    static VizSummary summarize(Collection<AgentViz> agents) {
        int active = 0;
        int waiting = 0;
        int paused = 0;
        int done = 0;
        int failed = 0;
        int inactive = 0;
        int slow = 0;
        for (AgentViz agent : agents) {
            switch (agent.state) {
                case CONNECTING, PROBING, RUNNING -> active++;
                case AWAITING_APPROVAL -> waiting++;
                case PAUSED -> paused++;
                case DONE -> done++;
                case FAILED -> failed++;
                case QUEUED, CANCELLED, SKIPPED -> inactive++;
            }
            if (agent.slow) {
                slow++;
            }
        }
        return new VizSummary(agents.size(), active, waiting, paused, done, failed, inactive, slow);
    }

    /** Chips left-to-right: active, waiting, paused, slow, done, failed — zero-count chips are dropped. */
    static List<LegendChip> legendChips(VizSummary summary, String accentHexOrNull) {
        List<LegendChip> chips = new ArrayList<>(6);
        if (summary.active() > 0) {
            chips.add(new LegendChip(accentHexOrNull != null ? accentHexOrNull : "#4f9cf0", summary.active(), false));
        }
        if (summary.waiting() > 0) {
            chips.add(new LegendChip("#ff9800", summary.waiting(), true));
        }
        if (summary.paused() > 0) {
            chips.add(new LegendChip("#b39ddb", summary.paused(), false));
        }
        if (summary.slow() > 0) {
            chips.add(new LegendChip(SLOW_RING_HEX, summary.slow(), false));
        }
        if (summary.done() > 0) {
            chips.add(new LegendChip("#4caf50", summary.done(), false));
        }
        if (summary.failed() > 0) {
            chips.add(new LegendChip("#ef5350", summary.failed(), false));
        }
        return chips;
    }

    static double legendWidth(List<LegendChip> chips) {
        double width = 0;
        for (LegendChip chip : chips) {
            width += 18 + String.valueOf(chip.count()).length() * 7;
        }
        return width;
    }

    // ---- Deterministic animation curves ----------------------------------------

    /** Stable per-agent phase in [0, 2*PI) derived from the agent id (CRC32, not hashCode). */
    static double phaseOffset(String agentId) {
        if (agentId == null || agentId.isEmpty()) {
            return 0;
        }
        CRC32 crc = new CRC32();
        crc.update(agentId.getBytes(StandardCharsets.UTF_8));
        return crc.getValue() / (double) 0xFFFFFFFFL * 2 * Math.PI;
    }

    /** Breathe scale for RUNNING orbs: 1 +/- 8%, period 2.4s. */
    static double pulseScale(double t, double phase) {
        return 1 + 0.08 * Math.sin(2 * Math.PI * t / 2.4 + phase);
    }

    /** Glow alpha for RUNNING orbs: 0.25..0.85, same period/phase as the scale. */
    static double pulseGlowAlpha(double t, double phase) {
        return 0.55 + 0.30 * Math.sin(2 * Math.PI * t / 2.4 + phase);
    }

    /** Blink alpha for AWAITING_APPROVAL: deliberately synchronized (no per-agent phase). */
    static double blinkAlpha(double t) {
        double s = Math.max(0, Math.sin(2 * Math.PI * t / 1.6));
        return 0.35 + 0.65 * s * s * s;
    }

    /** Progress of the repeating sonar ping (0..1 every 1.6s). */
    static double pingProgress(double t) {
        double m = t % 1.6;
        if (m < 0) {
            m += 1.6;
        }
        return m / 1.6;
    }

    /** Start angle of the rotating 300-degree slow-warning arc (0.25 rev/s). */
    static double slowRingStartAngle(double t) {
        return -((t * 90) % 360);
    }

    /** Short scale pop right after an agent settles into a terminal state (450ms). */
    static double settlePopScale(double secondsSinceChange) {
        if (secondsSinceChange < 0 || secondsSinceChange >= 0.45) {
            return 1;
        }
        double p = secondsSinceChange / 0.45;
        return 1 + 0.3 * Math.sin(p * Math.PI) * (1 - p);
    }

    /** Whether the terminal-transition effects (pop/ring/flash) are still playing. */
    static boolean isSettling(double secondsSinceChange) {
        return secondsSinceChange >= 0 && secondsSinceChange < 1.5;
    }

    /** Gentle horizontal idle drift so the swarm feels alive. */
    static double driftX(double t, double phase) {
        return 1.5 * Math.sin(2 * Math.PI * t / 7.3 + phase);
    }

    /** Gentle vertical idle drift, incommensurable with the horizontal period. */
    static double driftY(double t, double phase) {
        return 1.2 * Math.cos(2 * Math.PI * t / 9.1 + 1.7 * phase);
    }

    /** Defensive enum parse for persisted final states; anything unknown renders dim gray. */
    static SwarmModels.SwarmAgentState parseFinalStateOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SwarmModels.SwarmAgentState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
