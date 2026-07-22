package de.kortty.policy;

/**
 * Binary allow/deny decision for a policy-controlled feature. Ordered so that a higher ordinal is
 * more restrictive — same-tier rule conflicts resolve to the most restrictive value.
 */
public enum PolicyDecision {
    ALLOW,
    DENY;

    /** Parses the TOML value ("allow" | "deny"), case-insensitive; null for unknown input. */
    public static PolicyDecision fromToml(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "allow" -> ALLOW;
            case "deny" -> DENY;
            default -> null;
        };
    }

    public static PolicyDecision mostRestrictive(PolicyDecision a, PolicyDecision b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
