package de.kortty.codingagent;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A third-party coding agent CLI that may run inside a terminal pane.
 * Not korTTY's own AI Agent (core/TerminalAgentService).
 */
public enum CodingAgentKind {
    CLAUDE_CODE("Claude Code", "claude-code", Set.of("claude", "claude-code")),
    CODEX("Codex", "codex", Set.of("codex")),
    GEMINI_CLI("Gemini CLI", "gemini-cli", Set.of("gemini")),
    UNKNOWN("Unknown", null, Set.of());

    private final String displayName;
    private final String id;
    private final Set<String> executableNames;

    CodingAgentKind(String displayName, String id, Set<String> executableNames) {
        this.displayName = displayName;
        this.id = id;
        this.executableNames = executableNames;
    }

    /** Human-readable name for UI and logs. */
    public String displayName() {
        return displayName;
    }

    /** Stable kebab-case id used as rule-file base name ({@code <id>.json}); null for UNKNOWN. */
    public String id() {
        return id;
    }

    /** Lower-case executable base names (no directory, no .exe/.cmd) that identify this agent. */
    public Set<String> executableNames() {
        return executableNames;
    }

    /** @param executableBasename base name without directory and without .exe/.cmd; case-insensitive. */
    public static Optional<CodingAgentKind> forExecutable(String executableBasename) {
        if (executableBasename == null || executableBasename.isBlank()) {
            return Optional.empty();
        }
        String name = executableBasename.toLowerCase(Locale.ROOT);
        for (CodingAgentKind kind : values()) {
            if (kind.executableNames.contains(name)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    public static Optional<CodingAgentKind> forId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (CodingAgentKind kind : values()) {
            if (kind.id != null && kind.id.equals(id)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
