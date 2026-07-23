package de.kortty.ui;

import de.kortty.core.SnippetLanguageSupport;

import java.util.Locale;

/**
 * Maps KorTTY snippet/file language names to Monaco language ids.
 */
public final class MonacoLanguageSupport {

    private MonacoLanguageSupport() {
    }

    public static String toMonacoLanguage(String language) {
        String normalized = SnippetLanguageSupport.normalizeSnippetLanguage(language);
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "plain", "text", "plaintext" -> "plaintext";
            case "bash", "shell", "sh", "zsh" -> "shell";
            case "yml", "ansible_yaml" -> "yaml";
            case "properties", "cfg" -> "ini";
            case "terraform" -> "hcl";
            case "groovy" -> "java";
            case "javascript", "js" -> "javascript";
            case "typescript", "ts" -> "typescript";
            case "powershell", "ps1" -> "powershell";
            case "dockerfile" -> "dockerfile";
            case "cfengine3" -> "cfengine3";
            case "jinja2" -> "jinja2";
            case "puppet" -> "puppet";
            case "toml" -> "toml";
            case "python", "perl", "ruby", "java", "css", "go", "rust", "sql", "xml", "json", "yaml", "html", "markdown", "ini", "hcl" -> normalized;
            default -> "plaintext";
        };
    }
}
