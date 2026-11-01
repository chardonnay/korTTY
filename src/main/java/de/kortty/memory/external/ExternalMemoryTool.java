package de.kortty.memory.external;

import java.util.Locale;

/**
 * The tool a reference memory file belongs to. A file written for several tools (a project's {@code AGENTS.md}) is
 * {@link #AGENTS_MD}; a file the user chose without a known location is {@link #OTHER}.
 */
public enum ExternalMemoryTool {
    CLAUDE_CODE("Claude Code"),
    CODEX("Codex"),
    QWEN("Qwen Code"),
    OPENCODE("OpenCode"),
    KILO("Kilo"),
    KIRO("Kiro"),
    GEMINI("Gemini CLI (legacy)"),
    CLINE("Cline"),
    CURSOR("Cursor"),
    COPILOT("GitHub Copilot"),
    ROO("Roo Code"),
    AGENTS_MD("AGENTS.md"),
    OTHER("Other");

    private final String displayName;

    ExternalMemoryTool(String displayName) {
        this.displayName = displayName;
    }

    /** The product name, or the file name for the two shared kinds. */
    public String displayName() {
        return displayName;
    }

    /** The kind with the given name, or {@link #OTHER} for an unknown or blank name. */
    public static ExternalMemoryTool fromName(String name) {
        if (name == null || name.isBlank()) {
            return OTHER;
        }
        try {
            return valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return OTHER;
        }
    }
}
