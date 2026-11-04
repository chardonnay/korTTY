package de.kortty.core;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in syntax checker (linter) for snippet content.
 * Supports JSON and XML validation without external tools, and Lua with an installed {@code luac}
 * ({@code luac -p}, which only parses and never runs the code).
 */
public final class SnippetLinter {

    /** {@code luac: stdin:2: unexpected symbol near 'if'}: the line and the message of a luac error. */
    private static final Pattern LUAC_ERROR = Pattern.compile("^(?:\\S*luac\\S*:\\s*)?stdin:(\\d+):\\s*(.*)$");
    private static final long LUAC_TIMEOUT_SECONDS = 10;
    /** How long a luac lookup is reused; the button state asks on every keystroke. */
    private static final long LUAC_LOOKUP_TTL_MILLIS = 30_000;
    private static volatile Optional<Path> luacPath = Optional.empty();
    private static volatile long luacCheckedAt;

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
            case "lua" -> lintLua(text);
            default -> new LintResult(false, List.of("No linter available for language: " + language));
        };
    }

    public static boolean isSupported(String language) {
        if (language == null) return false;
        return "json".equalsIgnoreCase(language) || "xml".equalsIgnoreCase(language)
            || "lua".equalsIgnoreCase(language) && luac().isPresent();
    }

    /** The {@code luac} found in the PATH or a usual install location, looked up at most every 30 s. */
    static Optional<Path> luac() {
        long now = System.currentTimeMillis();
        if (now - luacCheckedAt > LUAC_LOOKUP_TTL_MILLIS) {
            luacPath = CodeFormatterService.findCommandOnPath("luac");
            luacCheckedAt = now;
        }
        return luacPath;
    }

    private static LintResult lintLua(String text) {
        Optional<Path> luac = luac();
        if (luac.isEmpty()) {
            return new LintResult(false, List.of("No linter available for language: lua (luac was not found)"));
        }
        Process process = null;
        try {
            process = new ProcessBuilder(luac.get().toString(), "-p", "-").redirectErrorStream(true).start();
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(LUAC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return new LintResult(false, List.of("luac did not answer within " + LUAC_TIMEOUT_SECONDS + " seconds"));
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 ? new LintResult(true, List.of()) : new LintResult(false, luacErrors(output));
        } catch (IOException e) {
            return new LintResult(false, List.of("Could not run luac: " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LintResult(false, List.of("The Lua syntax check was interrupted"));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** {@code luac: stdin:2: unexpected symbol near 'if'} becomes {@code Line 2: unexpected symbol near 'if'}. */
    static List<String> luacErrors(String output) {
        List<String> errors = new ArrayList<>();
        for (String line : (output != null ? output : "").split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher matcher = LUAC_ERROR.matcher(trimmed);
            errors.add(matcher.matches() ? "Line " + matcher.group(1) + ": " + matcher.group(2) : trimmed);
        }
        if (errors.isEmpty()) {
            errors.add("luac reported a syntax error");
        }
        return errors;
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
