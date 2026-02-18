package de.kortty.core;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Built-in syntax checker (linter) for snippet content.
 * Supports JSON and XML validation without external tools.
 */
public final class SnippetLinter {

    /**
     * Result of a lint check: either success or a list of error messages.
     */
    public static class LintResult {
        private final boolean success;
        private final List<String> errors;

        public LintResult(boolean success, List<String> errors) {
            this.success = success;
            this.errors = errors != null ? new ArrayList<>(errors) : List.of();
        }

        public boolean isSuccess() {
            return success;
        }

        public List<String> getErrors() {
            return errors;
        }

        public String getMessage() {
            if (success) return null;
            return String.join("\n", errors);
        }
    }

    /**
     * Runs the linter for the given text and language.
     *
     * @param text     snippet content
     * @param language language key (e.g. "json", "xml")
     * @return lint result; if language is not supported, returns a result with one error message
     */
    public static LintResult lint(String text, String language) {
        if (text == null) {
            return new LintResult(true, List.of());
        }
        if (language == null) language = "plain";

        return switch (language.toLowerCase()) {
            case "json" -> lintJson(text);
            case "xml" -> lintXml(text);
            default -> new LintResult(false, List.of("No linter available for language: " + language));
        };
    }

    public static boolean isSupported(String language) {
        if (language == null) return false;
        return "json".equalsIgnoreCase(language) || "xml".equalsIgnoreCase(language);
    }

    private static LintResult lintJson(String text) {
        List<String> errors = new ArrayList<>();
        text = text.trim();
        if (text.isEmpty()) {
            return new LintResult(true, List.of());
        }

        // Basic bracket/brace balance and string matching
        int braceDepth = 0;
        int bracketDepth = 0;
        boolean inString = false;
        char stringChar = 0;
        boolean escaped = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == stringChar) {
                    inString = false;
                }
                i++;
                continue;
            }
            switch (c) {
                case '"' -> {
                    if (!inString) {
                        inString = true;
                        stringChar = c;
                    }
                    i++;
                }
                case '{' -> { braceDepth++; i++; }
                case '}' -> {
                    braceDepth--;
                    if (braceDepth < 0) errors.add("Unexpected '}' at position " + i);
                    i++;
                }
                case '[' -> { bracketDepth++; i++; }
                case ']' -> {
                    bracketDepth--;
                    if (bracketDepth < 0) errors.add("Unexpected ']' at position " + i);
                    i++;
                }
                default -> i++;
            }
        }
        if (inString) {
            errors.add("Unclosed string (missing closing quote)");
        }
        if (braceDepth != 0) {
            errors.add("Unbalanced curly braces");
        }
        if (bracketDepth != 0) {
            errors.add("Unbalanced square brackets");
        }

        return new LintResult(errors.isEmpty(), errors);
    }

    private static LintResult lintXml(String text) {
        List<String> errors = new ArrayList<>();
        text = text.trim();
        if (text.isEmpty()) {
            return new LintResult(true, List.of());
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
            return new LintResult(true, List.of());
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.length() > 200) {
                msg = msg.substring(0, 200) + "...";
            }
            errors.add(msg != null ? msg : e.getClass().getSimpleName());
            return new LintResult(false, errors);
        }
    }

    private SnippetLinter() {}
}
