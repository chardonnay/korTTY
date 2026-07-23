package de.kortty.core;

import java.util.Locale;
import java.util.Set;

/**
 * Normalizes code-block languages for snippet storage, syntax highlighting, and file naming.
 */
public final class SnippetLanguageSupport {

    private static final Set<String> SCRIPT_LANGUAGES = Set.of(
        "bash",
        "python",
        "perl",
        "ruby",
        "javascript",
        "groovy",
        "powershell");

    /**
     * The complete set of normalized tokens {@link #normalizeSnippetLanguage} maps to.
     * Anything else (its raw-passthrough default branch) is off-list, unbounded text.
     */
    private static final Set<String> KNOWN_LANGUAGES = Set.of(
        "bash", "python", "perl", "ruby", "javascript", "typescript", "powershell", "groovy", "java",
        "json", "yaml", "xml", "markdown", "asciidoctor", "sql", "dockerfile",
        "properties", "html", "plain");

    private SnippetLanguageSupport() {
    }

    /**
     * A stable, bounded language token for anonymous telemetry: one of the known
     * normalized languages, or {@code "other"} for anything off-list. Never returns
     * free-form text (AI-generated or user-typed language names must not leak).
     */
    public static String telemetryLanguageToken(String language, String content) {
        String detected = detectSnippetLanguage(language, content);
        return detected != null && KNOWN_LANGUAGES.contains(detected) ? detected : "other";
    }

    public static String detectSnippetLanguage(String language, String content) {
        String normalized = normalizeSnippetLanguage(language);
        if (!"plain".equals(normalized)) {
            return normalized;
        }
        String shebangLanguage = detectShebangLanguage(content);
        return shebangLanguage != null ? shebangLanguage : "plain";
    }

    public static String detectFileLanguage(String fileName, String content) {
        String normalizedFileName = fileName != null ? fileName.trim() : "";
        if (!normalizedFileName.isBlank()) {
            String lowerFileName = normalizedFileName.toLowerCase(Locale.ROOT);
            if ("dockerfile".equals(lowerFileName) || lowerFileName.endsWith(".dockerfile")) {
                return "dockerfile";
            }

            int extensionStart = lowerFileName.lastIndexOf('.');
            if (extensionStart > 0 && extensionStart + 1 < lowerFileName.length()) {
                String language = languageForKnownExtension(lowerFileName.substring(extensionStart + 1));
                if (language != null) {
                    return language;
                }
            }
        }

        return detectSnippetLanguage("", content);
    }

    public static String normalizeSnippetLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "plain";
        }
        return switch (language.trim().toLowerCase(Locale.ROOT)) {
            case "sh", "shell", "zsh", "bash" -> "bash";
            case "py", "python", "python3" -> "python";
            case "pl", "perl" -> "perl";
            case "rb", "ruby" -> "ruby";
            case "js", "javascript", "node", "nodejs" -> "javascript";
            case "ts", "typescript" -> "typescript";
            case "ps", "ps1", "pwsh", "powershell" -> "powershell";
            case "groovy" -> "groovy";
            case "java" -> "java";
            case "json" -> "json";
            case "yaml", "yml" -> "yaml";
            case "xml" -> "xml";
            case "markdown", "md" -> "markdown";
            case "asciidoctor", "asciidoc", "adoc" -> "asciidoctor";
            case "sql" -> "sql";
            case "dockerfile" -> "dockerfile";
            case "properties", "ini" -> "properties";
            case "html" -> "html";
            case "plain", "text", "txt" -> "plain";
            default -> language.trim().toLowerCase(Locale.ROOT);
        };
    }

    private static String languageForKnownExtension(String extension) {
        return switch (extension) {
            case "sh", "shell", "zsh", "bash" -> "bash";
            case "py" -> "python";
            case "pl" -> "perl";
            case "rb" -> "ruby";
            case "js" -> "javascript";
            case "ts" -> "typescript";
            case "ps", "ps1", "pwsh" -> "powershell";
            case "groovy" -> "groovy";
            case "java" -> "java";
            case "json" -> "json";
            case "yaml", "yml" -> "yaml";
            case "xml" -> "xml";
            case "markdown", "md" -> "markdown";
            case "asciidoctor", "asciidoc", "adoc" -> "asciidoctor";
            case "sql" -> "sql";
            case "properties", "ini" -> "properties";
            case "html" -> "html";
            case "txt", "text" -> "plain";
            default -> null;
        };
    }

    public static boolean isScriptSnippetCandidate(String language, String content) {
        return SCRIPT_LANGUAGES.contains(detectSnippetLanguage(language, content));
    }

    public static String defaultFileExtension(String language) {
        return switch (detectSnippetLanguage(language, null)) {
            case "bash" -> ".sh";
            case "python" -> ".py";
            case "perl" -> ".pl";
            case "ruby" -> ".rb";
            case "javascript" -> ".js";
            case "typescript" -> ".ts";
            case "groovy" -> ".groovy";
            case "powershell" -> ".ps1";
            case "java" -> ".java";
            case "json" -> ".json";
            case "yaml" -> ".yml";
            case "xml" -> ".xml";
            case "markdown" -> ".md";
            case "asciidoctor" -> ".adoc";
            case "sql" -> ".sql";
            case "dockerfile" -> ".Dockerfile";
            case "properties" -> ".properties";
            case "html" -> ".html";
            default -> ".txt";
        };
    }

    public static String sanitizeFileName(String proposedFileName, String language) {
        String candidate = proposedFileName != null ? proposedFileName.trim() : "";
        candidate = candidate.replace('\\', '-').replace('/', '-');
        candidate = candidate.replaceAll("[^A-Za-z0-9._-]+", "-");
        candidate = candidate.replaceAll("-{2,}", "-");
        candidate = candidate.replaceAll("^[._-]+|[._-]+$", "");
        if (candidate.isBlank()) {
            candidate = "snippet";
        }
        String requiredExtension = defaultFileExtension(language);
        if (!requiredExtension.isBlank() && !candidate.toLowerCase(Locale.ROOT).endsWith(requiredExtension.toLowerCase(Locale.ROOT))) {
            if (candidate.contains(".") && !candidate.endsWith(".")) {
                candidate = candidate.replaceAll("\\.[A-Za-z0-9]+$", "");
            }
            candidate = candidate + requiredExtension;
        }
        return candidate;
    }

    private static String detectShebangLanguage(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String firstLine = content.lines()
            .findFirst()
            .map(String::trim)
            .orElse("");
        if (!firstLine.startsWith("#!")) {
            return null;
        }
        String interpreterToken = resolveShebangInterpreterToken(firstLine.substring(2).trim());
        if (interpreterToken == null) {
            return null;
        }
        String interpreterName = interpreterToken;
        int lastSlash = interpreterName.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash + 1 < interpreterName.length()) {
            interpreterName = interpreterName.substring(lastSlash + 1);
        }
        String normalized = interpreterName.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("python")) {
            return "python";
        }
        if (normalized.startsWith("perl")) {
            return "perl";
        }
        if (normalized.startsWith("ruby")) {
            return "ruby";
        }
        if ("pwsh".equals(normalized) || "powershell".equals(normalized)) {
            return "powershell";
        }
        if ("bash".equals(normalized) || "sh".equals(normalized) || "zsh".equals(normalized)) {
            return "bash";
        }
        return null;
    }

    private static String resolveShebangInterpreterToken(String shebangCommand) {
        if (shebangCommand == null || shebangCommand.isBlank()) {
            return null;
        }
        String[] tokens = shebangCommand.trim().split("\\s+");
        if (tokens.length == 0) {
            return null;
        }
        String command = tokens[0];
        String normalizedCommand = command.toLowerCase(Locale.ROOT);
        if ("env".equals(normalizedCommand) || normalizedCommand.endsWith("/env")) {
            for (int i = 1; i < tokens.length; i++) {
                String token = tokens[i];
                if (token == null || token.isBlank() || token.startsWith("-")) {
                    continue;
                }
                return token;
            }
            return null;
        }
        return command;
    }
}
