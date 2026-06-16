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

    private static final String LEGACY_CODEX_EXEC_READ_ONLY_STDIN_TEMPLATE = """
        exec
        --model
        {model}
        --sandbox
        read-only
        --skip-git-repo-check
        --ephemeral
        -
        {stdinPrompt}
        """;

    private static final String CODEX_EXEC_READ_ONLY_STDIN_TEMPLATE = """
        exec
        --sandbox
        read-only
        --skip-git-repo-check
        --ephemeral
        -
        {stdinPrompt}
        """;

    private static final AiCliArgumentPreset CODEX_EXEC_READ_ONLY_STDIN = new AiCliArgumentPreset(
        "codex exec read-only stdin",
        CODEX_EXEC_READ_ONLY_STDIN_TEMPLATE);

    // `lms chat <model> -p "<instruction>"` with the real prompt piped on stdin. The `-p` flag
    // requires a value, so a fixed instruction is passed there and the conversation arrives via
    // stdin (lms concatenates stdin to the -p value). Empirically verified against lms (the model
    // is a positional argument; -p alone with no value errors out).
    private static final String LMS_CHAT_STDIN_TEMPLATE = """
        chat
        {model}
        -p
        Use the conversation provided on standard input as the complete prompt. Follow its instructions exactly and reply with only the requested answer.
        {stdinPrompt}
        """;

    private static final AiCliArgumentPreset LMS_CHAT_STDIN = new AiCliArgumentPreset(
        "lms chat (stdin)",
        LMS_CHAT_STDIN_TEMPLATE);

    /** Provider id for the LM Studio CLI; the wizard offers a model picker for it. */
    public static final String LM_STUDIO_PROVIDER_ID = "lm-studio-cli";

    // Variant without {model}: lms chat uses whatever model is currently loaded in LM Studio at
    // run time. Used when the user picks "use the currently loaded model".
    private static final String LMS_CHAT_LOADED_TEMPLATE = """
        chat
        -p
        Use the conversation provided on standard input as the complete prompt. Follow its instructions exactly and reply with only the requested answer.
        {stdinPrompt}
        """;

    private static final AiCliArgumentPreset LMS_CHAT_LOADED = new AiCliArgumentPreset(
        "lms chat (loaded model)",
        LMS_CHAT_LOADED_TEMPLATE);

    // `mmx text chat --message "<prompt>" --output text ...` — empirically verified against
    // mmx-cli 1.0.15. The prompt must be the inline --message value (mmx has no raw-stdin form),
    // so {prompt} is used; --output text suppresses the thinking/CoT block. The model defaults to
    // MiniMax-M2.7, and auth comes from mmx's own config (mmx auth login / config.json).
    private static final String MMX_TEXT_CHAT_TEMPLATE = """
        text
        chat
        --message
        {prompt}
        --output
        text
        --no-color
        --quiet
        --non-interactive
        """;

    private static final AiCliArgumentPreset MMX_TEXT_CHAT = new AiCliArgumentPreset(
        "mmx text chat",
        MMX_TEXT_CHAT_TEMPLATE);

    private static final List<AiCliProviderDescriptor> PROVIDERS = List.of(
        provider("claude-code", "Claude Code", List.of("claude")),
        provider("codex-cli", "Codex CLI", List.of("codex"), List.of(CODEX_EXEC_READ_ONLY_STDIN)),
        provider("lm-studio-cli", "LM Studio CLI (lms)", List.of("lms"), List.of(LMS_CHAT_STDIN, LMS_CHAT_LOADED)),
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
        provider("minimax", "MiniMAX", List.of("mmx"), List.of(MMX_TEXT_CHAT)));

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

    public static Optional<AiCliArgumentPreset> defaultArgumentPreset(String providerId) {
        return find(providerId).flatMap(provider -> provider.argumentPresets().stream().findFirst());
    }

    /**
     * LM Studio CLI argument template. With {@code withModel} the model is pinned via {@code {model}};
     * otherwise {@code lms chat} uses whatever model is currently loaded at run time.
     */
    public static String lmStudioCliArgumentsTemplate(boolean withModel) {
        return withModel ? LMS_CHAT_STDIN_TEMPLATE : LMS_CHAT_LOADED_TEMPLATE;
    }

    public static boolean isDeprecatedDefaultArgumentTemplate(String providerId, String template) {
        if (!"codex-cli".equals(normalizeId(providerId))) {
            return false;
        }
        return normalizeTemplate(LEGACY_CODEX_EXEC_READ_ONLY_STDIN_TEMPLATE).equals(normalizeTemplate(template));
    }

    public static boolean isKnownDefaultArgumentTemplate(String providerId, String template) {
        String normalizedTemplate = normalizeTemplate(template);
        if (normalizedTemplate.isBlank()) {
            return false;
        }
        if (isDeprecatedDefaultArgumentTemplate(providerId, template)) {
            return true;
        }
        return find(providerId)
            .map(provider -> provider.argumentPresets().stream()
                .map(AiCliArgumentPreset::argumentsTemplate)
                .map(AiCliProviderRegistry::normalizeTemplate)
                .anyMatch(normalizedTemplate::equals))
            .orElse(false);
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
        // Search the process PATH first, then common install locations. GUI apps launched from the
        // desktop (especially macOS) often inherit a minimal PATH that omits Homebrew, npm, cargo,
        // etc., so scanning the well-known directories lets korTTY still locate installed CLIs.
        Optional<String> onKnownPaths = findInDirectories(candidate, executableSearchDirectories());
        if (onKnownPaths.isPresent()) {
            return onKnownPaths;
        }
        // Fallback for Node.js CLIs (e.g. MiniMax 'mmx', package mmx-cli) installed via `npm -g`
        // into a non-standard prefix such as an nvm-managed Node version. Resolved once and cached.
        String npmBinDir = npmGlobalBinDirectory();
        if (npmBinDir != null) {
            return findInDirectories(candidate, List.of(npmBinDir));
        }
        return Optional.empty();
    }

    private static Optional<String> findInDirectories(String candidate, List<String> directories) {
        List<String> extensions = executableExtensions();
        for (String dir : directories) {
            for (String extension : extensions) {
                Path path = Path.of(dir).resolve(candidate + extension);
                if (Files.isExecutable(path)) {
                    return Optional.of(path.toString());
                }
            }
        }
        return Optional.empty();
    }

    private static volatile boolean npmBinResolved = false;
    private static volatile String npmBinDirectory = null;

    /** The npm global bin directory (or null), resolved at most once per run via `npm config get prefix`. */
    private static String npmGlobalBinDirectory() {
        if (npmBinResolved) {
            return npmBinDirectory;
        }
        synchronized (AiCliProviderRegistry.class) {
            if (!npmBinResolved) {
                npmBinDirectory = resolveNpmGlobalBinDirectory();
                npmBinResolved = true;
            }
            return npmBinDirectory;
        }
    }

    private static String resolveNpmGlobalBinDirectory() {
        // npm itself may not be on the GUI app's minimal PATH, so resolve it from the known dirs.
        Optional<String> npm = findInDirectories("npm", executableSearchDirectories());
        if (npm.isEmpty()) {
            return null;
        }
        try {
            Process process = new ProcessBuilder(npm.get(), "config", "get", "prefix")
                .redirectErrorStream(false)
                .start();
            String output;
            try (java.io.InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            String prefix = output.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .reduce((first, second) -> second)
                .orElse("");
            if (prefix.isBlank()) {
                return null;
            }
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            // On Windows npm puts shims directly in the prefix; on Unix they live in <prefix>/bin.
            Path binDir = osName.contains("win") ? Path.of(prefix) : Path.of(prefix).resolve("bin");
            return Files.isDirectory(binDir) ? binDir.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Ordered, de-duplicated list of directories to search for CLI executables. */
    private static List<String> executableSearchDirectories() {
        java.util.LinkedHashSet<String> dirs = new java.util.LinkedHashSet<>();
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && !pathEnv.isBlank()) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                if (dir != null && !dir.isBlank()) {
                    dirs.add(dir);
                }
            }
        }
        String home = System.getProperty("user.home", "");
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            addIfPresent(dirs, System.getenv("APPDATA"), "npm");
            addIfPresent(dirs, System.getenv("LOCALAPPDATA"), "Programs");
            addIfPresent(dirs, home, ".bun\\bin");
            addIfPresent(dirs, home, ".cargo\\bin");
            addIfPresent(dirs, home, ".lmstudio\\bin");
        } else {
            for (String fixed : List.of(
                "/opt/homebrew/bin", "/opt/homebrew/sbin",
                "/usr/local/bin", "/usr/local/sbin",
                "/usr/bin", "/bin", "/usr/sbin", "/sbin",
                "/opt/local/bin")) {
                dirs.add(fixed);
            }
            for (String rel : List.of(
                ".local/bin", "bin", ".npm-global/bin", ".bun/bin",
                ".cargo/bin", ".deno/bin", "go/bin", ".volta/bin",
                ".lmstudio/bin")) {
                addIfPresent(dirs, home, rel);
            }
        }
        return List.copyOf(dirs);
    }

    private static void addIfPresent(java.util.Set<String> dirs, String base, String child) {
        if (base != null && !base.isBlank()) {
            dirs.add(Path.of(base).resolve(child).toString());
        }
    }

    static String normalizeId(String providerId) {
        return providerId != null ? providerId.trim().toLowerCase(Locale.ROOT) : "";
    }

    private static String normalizeTemplate(String template) {
        if (template == null) {
            return "";
        }
        return String.join("\n", template.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .toList());
    }

    private static AiCliProviderDescriptor provider(String id, String displayName, List<String> commandCandidates) {
        return provider(id, displayName, commandCandidates, List.of());
    }

    private static AiCliProviderDescriptor provider(
        String id,
        String displayName,
        List<String> commandCandidates,
        List<AiCliArgumentPreset> argumentPresets) {

        return new AiCliProviderDescriptor(id, displayName, commandCandidates, List.of(), argumentPresets, false);
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
