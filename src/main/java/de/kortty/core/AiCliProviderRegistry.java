package de.kortty.core;

import de.kortty.model.AiReasoningEffort;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Registry of AI CLI providers shown in the profile editor.
 */
public final class AiCliProviderRegistry {

    private static final List<AiReasoningEffort> CUSTOM_MODEL_EFFORTS = List.of(
        AiReasoningEffort.DISABLED,
        AiReasoningEffort.NONE,
        AiReasoningEffort.MINIMAL,
        AiReasoningEffort.LOW,
        AiReasoningEffort.MEDIUM,
        AiReasoningEffort.HIGH,
        AiReasoningEffort.XHIGH);

    private static final List<AiCliProviderDescriptor> PROVIDERS = List.of(
        provider("claude-code", "Claude Code", List.of("claude")),
        provider("codex-cli", "Codex CLI", List.of("codex")),
        provider("devin-terminal", "Devin for Terminal", List.of()),
        provider("gemini-cli", "Gemini CLI", List.of("gemini")),
        provider("opencode", "OpenCode", List.of("opencode")),
        provider("hermes", "Hermes", List.of()),
        provider("kimi-cli", "Kimi CLI", List.of()),
        provider("cursor-agent", "Cursor Agent", List.of("cursor-agent")),
        provider("qwen-code", "Qwen Code", List.of()),
        provider("qoder-cli", "Qoder CLI", List.of()),
        provider("github-copilot-cli", "GitHub Copilot CLI", List.of()),
        provider("pi", "Pi", List.of()),
        provider("kiro-cli", "Kiro CLI", List.of()),
        provider("kilo", "Kilo", List.of()),
        provider("mistral-vibe-cli", "Mistral Vibe CLI", List.of()),
        provider("deepseek-tui", "DeepSeek TUI", List.of()),
        provider("minimax", "MiniMAX", List.of()));

    private AiCliProviderRegistry() {
    }

    public static List<AiCliProviderDescriptor> providers() {
        return PROVIDERS;
    }

    public static Optional<AiCliProviderDescriptor> find(String providerId) {
        String normalized = normalizeId(providerId);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        return PROVIDERS.stream()
            .filter(provider -> provider.id().equals(normalized))
            .findFirst();
    }

    public static AiCliProviderDescriptor defaultProvider() {
        return PROVIDERS.get(0);
    }

    public static List<AiReasoningEffort> availableReasoningEfforts(String providerId, String model) {
        Optional<AiCliProviderDescriptor> provider = find(providerId);
        String normalizedModel = model != null ? model.trim() : "";
        if (provider.isPresent() && !normalizedModel.isBlank()) {
            for (AiCliModelPreset preset : provider.get().modelPresets()) {
                if (normalizedModel.equalsIgnoreCase(preset.modelName())) {
                    return preset.reasoningEfforts();
                }
            }
        }
        return CUSTOM_MODEL_EFFORTS;
    }

    public static Optional<String> findProviderExecutable(String providerId) {
        return find(providerId).flatMap(provider -> findExecutable(provider.commandCandidates()));
    }

    public static Optional<String> findExecutable(List<String> commandCandidates) {
        if (commandCandidates == null || commandCandidates.isEmpty()) {
            return Optional.empty();
        }
        for (String candidate : commandCandidates) {
            Optional<String> resolved = findExecutable(candidate);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
    }

    public static Optional<String> findExecutable(String command) {
        String candidate = command != null ? command.trim() : "";
        if (candidate.isBlank()) {
            return Optional.empty();
        }
        Path directPath = Path.of(candidate);
        if (candidate.contains("/") || candidate.contains("\\") || directPath.isAbsolute()) {
            return Files.isExecutable(directPath) ? Optional.of(directPath.toString()) : Optional.empty();
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }
        List<String> extensions = executableExtensions();
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            for (String extension : extensions) {
                Path path = Path.of(dir).resolve(candidate + extension);
                if (Files.isExecutable(path)) {
                    return Optional.of(path.toString());
                }
            }
        }
        return Optional.empty();
    }

    static String normalizeId(String providerId) {
        return providerId != null ? providerId.trim().toLowerCase(Locale.ROOT) : "";
    }

    private static AiCliProviderDescriptor provider(String id, String displayName, List<String> commandCandidates) {
        return new AiCliProviderDescriptor(id, displayName, commandCandidates, List.of(), false);
    }

    private static List<String> executableExtensions() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("win")) {
            return List.of("");
        }
        String pathExt = System.getenv("PATHEXT");
        if (pathExt == null || pathExt.isBlank()) {
            return List.of("", ".exe", ".cmd", ".bat");
        }
        return Arrays.stream(pathExt.split(";"))
            .map(String::trim)
            .filter(ext -> !ext.isBlank())
            .map(ext -> ext.startsWith(".") ? ext : "." + ext)
            .distinct()
            .toList();
    }
}
