package de.kortty.ui;

import de.kortty.codingagent.AgentSummary;
import de.kortty.codingagent.CodingAgentGlyphs;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.DurationText;

import java.util.Locale;
import java.util.function.BiFunction;

/**
 * JavaFX-free helpers shared by {@link CodingAgentStatusStrip} and {@link CodingAgentPanel}: the
 * state colours for light and dark chrome, the cached count strings the 1-second tick compares by
 * identity, the strip summary text and the pulse gate. Everything here is a pure function so it is
 * unit-tested without a toolkit; the FX classes stay thin.
 */
final class CodingAgentStripSupport {

    /** Dark chrome (the terminal.css defaults): amber / blue / green / grey. */
    static final String BLOCKED_DARK = "#f59e0b";
    static final String WORKING_DARK = "#60a5fa";
    static final String DONE_DARK = "#34d399";
    static final String IDLE_DARK = "#8a8a8a";

    /** Light chrome: the same hues pulled darker so they read on a bright background. */
    static final String BLOCKED_LIGHT = "#b45309";
    static final String WORKING_LIGHT = "#1d4ed8";
    static final String DONE_LIGHT = "#15803d";
    static final String IDLE_LIGHT = "#6b7280";

    /** Text on a filled state chip: near-black on the bright dark-chrome chips, white on the light-chrome ones. */
    static final String CHIP_TEXT_ON_DARK_CHROME = "#0b1220";
    static final String CHIP_TEXT_ON_LIGHT_CHROME = "#ffffff";

    private static final int COUNT_CACHE_SIZE = 100;
    private static final String[] COUNT_CACHE = new String[COUNT_CACHE_SIZE];

    private static final String STATE_KEY_PREFIX = "codingAgent.state.";

    private CodingAgentStripSupport() {
    }

    /**
     * The decimal text of a count. Values 0..99 return the same String instance on every call so
     * a periodic refresh can skip a Label whose text identity did not change; negative values
     * render as "0".
     */
    static String countText(int count) {
        int value = Math.max(0, count);
        if (value < COUNT_CACHE_SIZE) {
            String cached = COUNT_CACHE[value];
            if (cached == null) {
                cached = Integer.toString(value);
                COUNT_CACHE[value] = cached;
            }
            return cached;
        }
        return Integer.toString(value);
    }

    /** "✋ 1 · ⚡ 2 · ✓ 1" in BLOCKED, WORKING, DONE order with zero counts omitted; "" when empty or null. */
    static String stripText(AgentSummary summary) {
        return CodingAgentGlyphs.summaryText(summary);
    }

    /**
     * The colour of a state dot or chip. IDLE, UNKNOWN and null share the grey; the light variants
     * are darker so they keep contrast on a bright background.
     */
    static String colorHex(CodingAgentState state, boolean lightBackground) {
        if (state == null) {
            return lightBackground ? IDLE_LIGHT : IDLE_DARK;
        }
        return switch (state) {
            case BLOCKED -> lightBackground ? BLOCKED_LIGHT : BLOCKED_DARK;
            case WORKING -> lightBackground ? WORKING_LIGHT : WORKING_DARK;
            case DONE -> lightBackground ? DONE_LIGHT : DONE_DARK;
            default -> lightBackground ? IDLE_LIGHT : IDLE_DARK;
        };
    }

    /** The text colour on a chip filled with {@link #colorHex}: dark on the bright chips of dark chrome, white otherwise. */
    static String chipTextHex(boolean lightBackground) {
        return lightBackground ? CHIP_TEXT_ON_LIGHT_CHROME : CHIP_TEXT_ON_DARK_CHROME;
    }

    /**
     * True when a CSS hex colour ({@code #rgb}, {@code #rrggbb} or {@code #rrggbbaa}) is bright,
     * using the same max-component brightness as {@code Color.getBrightness() > 0.5}. Anything
     * unparseable (null, blank, named colours, rgb()) counts as dark so the default dark palette
     * is used.
     */
    static boolean isLight(String bgHex) {
        if (bgHex == null) {
            return false;
        }
        String hex = bgHex.strip();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        } else {
            return false;
        }
        int r;
        int g;
        int b;
        try {
            if (hex.length() == 3 || hex.length() == 4) {
                r = Integer.parseInt(hex.substring(0, 1), 16) * 17;
                g = Integer.parseInt(hex.substring(1, 2), 16) * 17;
                b = Integer.parseInt(hex.substring(2, 3), 16) * 17;
            } else if (hex.length() == 6 || hex.length() == 8) {
                r = Integer.parseInt(hex.substring(0, 2), 16);
                g = Integer.parseInt(hex.substring(2, 4), 16);
                b = Integer.parseInt(hex.substring(4, 6), 16);
            } else {
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        if (r < 0 || g < 0 || b < 0) {
            return false;
        }
        int max = Math.max(r, Math.max(g, b));
        return max / 255.0 > 0.5;
    }

    /**
     * The pulse gate shared by the strip and the panel: something is BLOCKED, the window is the
     * active one, the user has not switched animations off and the node hangs in a showing window
     * (a never-shown smoke Stage keeps the timers off; frames then come from renderFrameForTest).
     */
    static boolean shouldPulse(AgentSummary summary, boolean windowActive, boolean animationsEnabled,
                               boolean attachedToShowingWindow) {
        return summary != null
            && summary.blocked() > 0
            && windowActive
            && animationsEnabled
            && attachedToShowingWindow;
    }

    /**
     * "✋ Waiting for you · 2:14": the state glyph, the localised state label
     * ({@code codingAgent.state.<state>}) and the time in state. Backs
     * {@link CodingAgentPanel#rowStateText}.
     *
     * @param messages (key, args) -&gt; text, typically {@code I18n::get}
     */
    static String rowStateText(CodingAgentState state, long seconds, BiFunction<String, Object[], String> messages) {
        CodingAgentState effective = state == null ? CodingAgentState.UNKNOWN : state;
        String label = messages.apply(STATE_KEY_PREFIX + effective.name().toLowerCase(Locale.ROOT),
            new Object[0]);
        return CodingAgentGlyphs.glyph(effective) + " " + label + CodingAgentGlyphs.SEPARATOR
            + DurationText.mmss(seconds);
    }
}
