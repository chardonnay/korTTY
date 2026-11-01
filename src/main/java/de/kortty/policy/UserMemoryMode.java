package de.kortty.policy;

import java.util.Locale;

/**
 * How the user's own memory files ({@code [rule.ai-memory] user-memory}) may be used. Ordered so that a
 * higher ordinal is more restrictive: same-tier rule conflicts resolve to the most restrictive value.
 *
 * <ul>
 *   <li>{@link #EDITABLE} — the memory is read into prompts and the user may change it.</li>
 *   <li>{@link #READ_ONLY} — the memory is read into prompts, but nothing may be written to it.</li>
 *   <li>{@link #DISABLED} — the memory is neither read nor written. Organisation memory is still sent.</li>
 * </ul>
 */
public enum UserMemoryMode {
    EDITABLE,
    READ_ONLY,
    DISABLED;

    /** Parses the TOML value ("editable" | "read-only" | "disabled"); null for unknown input. */
    public static UserMemoryMode fromToml(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "editable" -> EDITABLE;
            case "read-only" -> READ_ONLY;
            case "disabled" -> DISABLED;
            default -> null;
        };
    }

    public static UserMemoryMode mostRestrictive(UserMemoryMode a, UserMemoryMode b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
