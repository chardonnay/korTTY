package de.kortty.policy;

/**
 * Admin-imposed mode for the terminal "load into snippet editor" feature. Ordered so that a higher
 * ordinal is more restrictive — same-tier rule conflicts resolve to the most restrictive value.
 *
 * <ul>
 *   <li>{@link #ALLOW} — load and write-back both available.</li>
 *   <li>{@link #READ_ONLY} — files may be loaded, but never written back to the target system.</li>
 *   <li>{@link #DENY} — the feature is unavailable.</li>
 * </ul>
 */
public enum LoadIntoEditorMode {
    ALLOW,
    READ_ONLY,
    DENY;

    /** Parses the TOML value ("allow" | "read-only" | "deny"); null for unknown input. */
    public static LoadIntoEditorMode fromToml(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "allow" -> ALLOW;
            case "read-only" -> READ_ONLY;
            case "deny" -> DENY;
            default -> null;
        };
    }

    public static LoadIntoEditorMode mostRestrictive(LoadIntoEditorMode a, LoadIntoEditorMode b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
