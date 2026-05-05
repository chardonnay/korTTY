package de.kortty.plugin.terminaleffects;

/**
 * Shared animation speed bounds for terminal effect plugins.
 */
public final class TerminalEffectAnimationSpeed {

    public static final double MINIMUM = 1.0;
    public static final double DEFAULT = 1.0;
    public static final double SLIDER_MAXIMUM = 10.0;
    public static final double MAXIMUM = 99.0;

    private TerminalEffectAnimationSpeed() {
    }

    public static double normalize(double speed) {
        if (!Double.isFinite(speed) || speed <= 0.0) {
            return DEFAULT;
        }
        return Math.max(MINIMUM, Math.min(MAXIMUM, speed));
    }
}
