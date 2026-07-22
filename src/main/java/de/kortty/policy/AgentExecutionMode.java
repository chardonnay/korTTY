package de.kortty.policy;

/**
 * Admin-imposed execution mode for the terminal AI agent. Ordered so that a higher ordinal is more
 * restrictive — same-tier rule conflicts resolve to the most restrictive value.
 *
 * <ul>
 *   <li>{@link #ALLOW} — the user's own settings govern execution and confirmation.</li>
 *   <li>{@link #CONFIRM} — command execution stays available but every mutating command set
 *       requires interactive approval; the auto-approve bypass is disabled.</li>
 *   <li>{@link #READ_ONLY} — the agent may plan and chat but never executes commands.</li>
 * </ul>
 */
public enum AgentExecutionMode {
    ALLOW,
    CONFIRM,
    READ_ONLY;

    /** Parses the TOML value ("allow" | "confirm" | "read-only"); null for unknown input. */
    public static AgentExecutionMode fromToml(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "allow" -> ALLOW;
            case "confirm" -> CONFIRM;
            case "read-only" -> READ_ONLY;
            default -> null;
        };
    }

    public static AgentExecutionMode mostRestrictive(AgentExecutionMode a, AgentExecutionMode b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
