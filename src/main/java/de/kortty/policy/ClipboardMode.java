package de.kortty.policy;

/**
 * Admin-imposed clipboard mode. Ordered so that a higher ordinal is more restrictive — same-tier
 * rule conflicts resolve to the most restrictive value.
 *
 * <ul>
 *   <li>{@link #SYSTEM} — the normal OS clipboard.</li>
 *   <li>{@link #INTERNAL} — korTTY uses only its own in-memory clipboard: text copied in other
 *       applications cannot be pasted into korTTY, and text copied in korTTY never reaches the OS
 *       clipboard. Copy/paste <i>within</i> korTTY keeps working.</li>
 * </ul>
 */
public enum ClipboardMode {
    SYSTEM,
    INTERNAL;

    /** Parses the TOML value ("system" | "internal"); null for unknown input. */
    public static ClipboardMode fromToml(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "system" -> SYSTEM;
            case "internal" -> INTERNAL;
            default -> null;
        };
    }

    public static ClipboardMode mostRestrictive(ClipboardMode a, ClipboardMode b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
