package de.kortty.model;

/**
 * Display/input unit for AI token limits.
 */
public enum AiTokenLimitUnit {
    THOUSANDS(1_000L),
    MILLIONS(1_000_000L);

    private final long multiplier;

    AiTokenLimitUnit(long multiplier) {
        this.multiplier = multiplier;
    }

    public long getMultiplier() {
        return multiplier;
    }
}
