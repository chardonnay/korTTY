package de.kortty.memory.external;

import de.kortty.codingagent.CodingAgentKind;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;

import java.util.Map;
import java.util.Set;

/**
 * Which reference files a local coding-agent CLI reads by itself. A request through such a CLI already gets those files,
 * so korTTY does not inject them a second time; korTTY's own memory is still sent once.
 *
 * <p>The table lists only what the vendor documents. A kind that is not listed (GitHub Copilot CLI, whose files are
 * not verified) excludes nothing, so its files are sent as before.</p>
 */
public final class LocalCliMemoryReads {

    private static final Map<CodingAgentKind, Set<ExternalMemoryTool>> READ_BY_KIND = Map.of(
        // ~/.claude/CLAUDE.md, ~/.claude/rules/*.md, the project CLAUDE.md and its auto memory. AGENTS.md is read only
        // when a Claude project has no CLAUDE.md, which korTTY cannot tell, so it is still sent.
        CodingAgentKind.CLAUDE_CODE, Set.of(ExternalMemoryTool.CLAUDE_CODE),
        // ~/.codex/AGENTS.md and the project AGENTS.md.
        CodingAgentKind.CODEX, Set.of(ExternalMemoryTool.CODEX, ExternalMemoryTool.AGENTS_MD),
        // ~/.gemini/GEMINI.md (legacy file name).
        CodingAgentKind.GEMINI_CLI, Set.of(ExternalMemoryTool.GEMINI),
        // ~/.config/opencode/AGENTS.md and the project AGENTS.md.
        CodingAgentKind.OPENCODE, Set.of(ExternalMemoryTool.OPENCODE, ExternalMemoryTool.AGENTS_MD),
        // ~/.config/kilo/AGENTS.md and the project AGENTS.md.
        CodingAgentKind.KILO, Set.of(ExternalMemoryTool.KILO, ExternalMemoryTool.AGENTS_MD),
        // ~/.qwen/QWEN.md, its auto memory, and AGENTS.md when present.
        CodingAgentKind.QWEN_CODE, Set.of(ExternalMemoryTool.QWEN, ExternalMemoryTool.AGENTS_MD),
        // ~/.kiro/steering/*.md and the root AGENTS.md, which is always included.
        CodingAgentKind.KIRO, Set.of(ExternalMemoryTool.KIRO, ExternalMemoryTool.AGENTS_MD),
        // .cursor/rules/*.mdc and AGENTS.md.
        CodingAgentKind.CURSOR_AGENT, Set.of(ExternalMemoryTool.CURSOR, ExternalMemoryTool.AGENTS_MD),
        // ~/Documents/Cline/Rules and the workspace AGENTS.md.
        CodingAgentKind.CLINE, Set.of(ExternalMemoryTool.CLINE, ExternalMemoryTool.AGENTS_MD));

    /** The AI profile's CLI provider ids, as {@code AiCliProviderRegistry} names them, for the kinds of the table. */
    private static final Map<String, CodingAgentKind> KIND_BY_PROVIDER = Map.of(
        "claude-code", CodingAgentKind.CLAUDE_CODE,
        "codex-cli", CodingAgentKind.CODEX,
        "gemini-cli", CodingAgentKind.GEMINI_CLI,
        "opencode", CodingAgentKind.OPENCODE,
        "kilo", CodingAgentKind.KILO,
        "qwen-code", CodingAgentKind.QWEN_CODE,
        "kiro-cli", CodingAgentKind.KIRO,
        "cursor-agent", CodingAgentKind.CURSOR_AGENT);

    private LocalCliMemoryReads() {
    }

    /** The reference tools the CLI of {@code kind} reads by itself; empty for a kind that is not in the table. */
    public static Set<ExternalMemoryTool> readBy(CodingAgentKind kind) {
        return READ_BY_KIND.getOrDefault(kind, Set.of());
    }

    /** The tools read by the CLI of an AI provider id, or empty for an id that is not a known CLI. */
    public static Set<ExternalMemoryTool> readByProvider(String providerId) {
        CodingAgentKind kind = providerId == null ? null : KIND_BY_PROVIDER.get(providerId.strip());
        return kind == null ? Set.of() : readBy(kind);
    }

    /**
     * The reference tools whose files a request through {@code profile} must not inject: those its CLI reads itself.
     * Empty for every profile that is not a local CLI.
     */
    public static Set<ExternalMemoryTool> excludedFor(AiProfile profile) {
        if (profile == null || profile.getConnectionMode() != AiConnectionMode.LOCAL_CLI) {
            return Set.of();
        }
        return readByProvider(profile.getCliProviderId());
    }
}
