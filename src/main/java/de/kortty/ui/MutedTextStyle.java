package de.kortty.ui;

/**
 * Inline styles for secondary text — the small hint and status lines under a field.
 *
 * <p>These used to name Modena's {@code -fx-text-inner-color}. Every AtlantaFX app design replaces
 * the user-agent stylesheet, and that lookup does not exist there: JavaFX drops the whole
 * declaration and the label falls back to the {@code Labeled} default, i.e. pure black on dark
 * chrome. Dimming with opacity instead keeps whatever text colour the active theme provides, so the
 * hint stays readable on every design.</p>
 */
final class MutedTextStyle {

    /** Reads as secondary text without dimming below the readable range. */
    static final String MUTED = "-fx-opacity: 0.75;";

    /** The standard small hint/status line placed next to or under a field. */
    static final String HINT = "-fx-font-size: 0.8462em; " + MUTED;

    private MutedTextStyle() {
    }
}
