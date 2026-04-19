package de.kortty.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Converts multi-line snippet scripts into a single line for pasting into a terminal.
 * Two modes: compact (readable, heuristic) and embedded (base64 + pipe, robust).
 */
public final class SnippetOneLiner {

    /**
     * {@code echo '…b64…' | …} exceeds typical shell argv limits for large scripts; use heredoc instead.
     * Conservative limit (bytes of the final single-line command).
     */
    private static final int MAX_EMBEDDED_ECHO_LINE_CHARS = 48_000;

    /** Must not appear in standard Base64 alphabet (no underscore in RFC 4648). */
    static final String EMBEDDED_HEREDOC_DELIM = "KORTTY_B64_EOF";

    private static final Pattern PYTHON_BLOCK = Pattern.compile("(?m)^\\h*(def|class|async\\h+def)\\h");
    private static final Pattern PERL_SUB = Pattern.compile("(?m)^\\h*(sub|package)\\h");
    private static final Pattern RUBY_DEF = Pattern.compile("(?m)^\\h*(def|class)\\h");

    private SnippetOneLiner() {
    }

    /**
     * Result of a one-liner conversion: either a single line or an i18n error key.
     */
    public record OneLinerResult(String line, String errorKey, Object[] errorArgs) {

        public static OneLinerResult ok(String line) {
            return new OneLinerResult(line, null, new Object[0]);
        }

        public static OneLinerResult error(String key, Object... args) {
            return new OneLinerResult(null, key, args != null ? args : new Object[0]);
        }

        public boolean isOk() {
            return errorKey == null;
        }
    }

    public static boolean isCompactSupported(String language) {
        return normalizeLang(language) != null;
    }

    public static boolean isEmbeddedSupported(String language) {
        return normalizeLang(language) != null;
    }

    /**
     * One POSIX-shell line that prints {@code message} to stderr (for pasting before a snippet one-liner).
     * {@code message} may contain any UTF-8; single quotes are escaped for use inside {@code '…'}.
     */
    public static String terminalStderrBannerShellPrefix(String message) {
        String m = message != null ? message : "";
        return "printf '%s\\n' '" + shellEscapeSingleQuoted(m) + "' >&2";
    }

    /**
     * Readable one-liner; may fail for scripts with blocks, heredocs, or multi-line strings.
     */
    public static OneLinerResult toCompact(String text, String language) {
        String lang = normalizeLang(language);
        if (lang == null) {
            return OneLinerResult.error("snippets.oneliner.notSupported", language != null ? language : "plain");
        }
        if (text == null || text.isBlank()) {
            return OneLinerResult.error("snippets.oneliner.empty");
        }
        return switch (lang) {
            case "bash", "shell" -> compactShell(text);
            case "python" -> compactPython(text);
            case "perl" -> compactPerl(text);
            case "ruby" -> compactRuby(text);
            default -> OneLinerResult.error("snippets.oneliner.notSupported", language);
        };
    }

    /**
     * Single-line wrapper: {@code echo '<base64>' | base64 -d | <interpreter>}.
     */
    public static OneLinerResult toEmbedded(String text, String language) {
        String lang = normalizeLang(language);
        if (lang == null) {
            return OneLinerResult.error("snippets.oneliner.notSupported", language != null ? language : "plain");
        }
        if (text == null || text.isBlank()) {
            return OneLinerResult.error("snippets.oneliner.empty");
        }
        String cleaned = stripScriptComments(text, lang);
        if (cleaned.isBlank()) {
            return OneLinerResult.error("snippets.oneliner.empty");
        }
        byte[] utf8 = cleaned.getBytes(StandardCharsets.UTF_8);
        String b64 = Base64.getEncoder().encodeToString(utf8);
        String interpreter = switch (lang) {
            case "bash", "shell" -> "bash";
            case "python" -> "python3";
            case "perl" -> "perl";
            case "ruby" -> "ruby";
            default -> "bash";
        };
        String line = "echo '" + b64 + "' | base64 -d | " + interpreter;
        if (line.length() > MAX_EMBEDDED_ECHO_LINE_CHARS) {
            return OneLinerResult.ok(buildEmbeddedHeredocPayload(b64, interpreter));
        }
        return OneLinerResult.ok(line);
    }

    /**
     * Feeds Base64 via a here-document into {@code base64 -d}, avoiding huge single shell arguments.
     * Delimiter uses {@code _} which is not part of the Base64 alphabet, so it cannot appear in the payload.
     */
    static String buildEmbeddedHeredocPayload(String base64Payload, String interpreter) {
        return "base64 -d <<'" + EMBEDDED_HEREDOC_DELIM + "' | " + interpreter + "\n"
                + base64Payload + "\n"
                + EMBEDDED_HEREDOC_DELIM + "\n";
    }

    private static String normalizeLang(String language) {
        if (language == null) {
            return null;
        }
        return switch (language.toLowerCase()) {
            case "bash", "shell" -> "bash";
            case "python" -> "python";
            case "perl" -> "perl";
            case "ruby" -> "ruby";
            default -> null;
        };
    }

    private static OneLinerResult compactShell(String text) {
        List<String> logical = logicalLinesAfterMerge(text, false);
        if (logical.isEmpty()) {
            return OneLinerResult.error("snippets.oneliner.empty");
        }
        StringBuilder sb = new StringBuilder();
        String lastLine = null;
        for (String raw : logical) {
            String line = stripCommentsShellLine(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (sb.length() > 0 && lastLine != null) {
                if (lastLineEndsWithPipeOrLogicalAnd(lastLine)) {
                    sb.append(' ');
                } else {
                    sb.append("; ");
                }
            }
            sb.append(line);
            lastLine = line;
        }
        String one = sb.toString().strip();
        return one.isEmpty() ? OneLinerResult.error("snippets.oneliner.empty") : OneLinerResult.ok(one);
    }

    private static boolean lastLineEndsWithPipeOrLogicalAnd(String line) {
        String t = line.stripTrailing();
        if (t.isEmpty()) {
            return false;
        }
        char last = t.charAt(t.length() - 1);
        if (last == '|') {
            return true;
        }
        return t.endsWith("&&") || t.endsWith("||");
    }

    private static OneLinerResult compactPython(String text) {
        if (PYTHON_BLOCK.matcher(text).find()) {
            return OneLinerResult.error("snippets.oneliner.compact.blocks");
        }
        List<String> logical = logicalLinesAfterMerge(text, false);
        if (logical.isEmpty()) {
            return OneLinerResult.error("snippets.oneliner.empty");
        }
        StringBuilder sb = new StringBuilder();
        for (String raw : logical) {
            String line = stripCommentsPythonLine(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(line);
        }
        String one = sb.toString().strip();
        return one.isEmpty() ? OneLinerResult.error("snippets.oneliner.empty") : OneLinerResult.ok(one);
    }

    private static OneLinerResult compactPerl(String text) {
        if (PERL_SUB.matcher(text).find()) {
            return OneLinerResult.error("snippets.oneliner.compact.blocks");
        }
        List<String> logical = logicalLinesAfterMerge(text, false);
        if (logical.isEmpty()) {
            return OneLinerResult.error("snippets.oneliner.empty");
        }
        StringBuilder inner = new StringBuilder();
        for (String raw : logical) {
            String line = stripCommentsHashLangLine(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (inner.length() > 0) {
                inner.append("; ");
            }
            inner.append(line);
        }
        String code = inner.toString().strip();
        if (code.isEmpty()) {
            return OneLinerResult.error("snippets.oneliner.empty");
        }
        return OneLinerResult.ok("perl -e '" + shellEscapeSingleQuoted(code) + "'");
    }

    private static OneLinerResult compactRuby(String text) {
        if (RUBY_DEF.matcher(text).find()) {
            return OneLinerResult.error("snippets.oneliner.compact.blocks");
        }
        List<String> logical = logicalLinesAfterMerge(text, false);
        if (logical.isEmpty()) {
            return OneLinerResult.error("snippets.oneliner.empty");
        }
        StringBuilder inner = new StringBuilder();
        for (String raw : logical) {
            String line = stripCommentsHashLangLine(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (inner.length() > 0) {
                inner.append("; ");
            }
            inner.append(line);
        }
        String code = inner.toString().strip();
        if (code.isEmpty()) {
            return OneLinerResult.error("snippets.oneliner.empty");
        }
        return OneLinerResult.ok("ruby -e '" + shellEscapeSingleQuoted(code) + "'");
    }

    /**
     * Removes comment lines and {@code #}… to end-of-line outside strings, then joins non-empty lines with newlines.
     */
    static String stripScriptComments(String text, String normalizedLang) {
        List<String> logical = logicalLinesAfterMerge(text, false);
        List<String> kept = new ArrayList<>();
        for (String raw : logical) {
            String line = switch (normalizedLang) {
                case "bash", "shell" -> stripCommentsShellLine(raw);
                case "python" -> stripCommentsPythonLine(raw);
                case "perl", "ruby" -> stripCommentsHashLangLine(raw);
                default -> raw;
            };
            String t = line.strip();
            if (!t.isEmpty()) {
                kept.add(t);
            }
        }
        return String.join("\n", kept);
    }

    /**
     * Removes {@code #} shell comments outside single- and double-quoted segments (double quotes honor {@code \} escapes).
     */
    static String stripCommentsShellLine(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == '\'') {
                int j = i + 1;
                while (j < n && line.charAt(j) != '\'') {
                    j++;
                }
                out.append(line, i, Math.min(j + 1, n));
                i = j < n ? j + 1 : n;
                continue;
            }
            if (c == '"') {
                out.append(c);
                i++;
                while (i < n) {
                    char d = line.charAt(i);
                    out.append(d);
                    if (d == '\\' && i + 1 < n) {
                        out.append(line.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (d == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '#') {
                break;
            }
            out.append(c);
            i++;
        }
        return out.toString().stripTrailing();
    }

    /**
     * Python {@code #} comments: respects {@code '}, {@code "}, {@code '''}, {@code """} and common escapes in strings.
     */
    static String stripCommentsPythonLine(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = line.length();
        while (i < n) {
            if (i + 2 < n && line.startsWith("\"\"\"", i)) {
                int j = line.indexOf("\"\"\"", i + 3);
                if (j < 0) {
                    out.append(line, i, n);
                    return out.toString().stripTrailing();
                }
                out.append(line, i, j + 3);
                i = j + 3;
                continue;
            }
            if (i + 2 < n && line.startsWith("'''", i)) {
                int j = line.indexOf("'''", i + 3);
                if (j < 0) {
                    out.append(line, i, n);
                    return out.toString().stripTrailing();
                }
                out.append(line, i, j + 3);
                i = j + 3;
                continue;
            }
            char c = line.charAt(i);
            if (c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    char d = line.charAt(i);
                    out.append(d);
                    if (d == '\\' && i + 1 < n) {
                        char next = line.charAt(i + 1);
                        if (next == '\\' || next == '\'') {
                            out.append(next);
                            i += 2;
                            continue;
                        }
                        i++;
                        continue;
                    }
                    if (d == '\'') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '"') {
                out.append(c);
                i++;
                while (i < n) {
                    char d = line.charAt(i);
                    out.append(d);
                    if (d == '\\' && i + 1 < n) {
                        out.append(line.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (d == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '#') {
                break;
            }
            out.append(c);
            i++;
        }
        return out.toString().stripTrailing();
    }

    /**
     * Perl/Ruby style {@code #} to EOL outside {@code '} and {@code "} (with {@code \} escapes in double quotes).
     */
    static String stripCommentsHashLangLine(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    char d = line.charAt(i);
                    out.append(d);
                    if (d == '\\' && i + 1 < n) {
                        out.append(line.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (d == '\'') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '"') {
                out.append(c);
                i++;
                while (i < n) {
                    char d = line.charAt(i);
                    out.append(d);
                    if (d == '\\' && i + 1 < n) {
                        out.append(line.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (d == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '#') {
                break;
            }
            out.append(c);
            i++;
        }
        return out.toString().stripTrailing();
    }

    /**
     * Shell single-quoted string: {@code '} → {@code '\''}.
     */
    static String shellEscapeSingleQuoted(String s) {
        return s.replace("'", "'\\''");
    }

    /**
     * Splits on any newline, merges trailing-{@code \} continuations, optionally drops full-line {@code #} comments.
     */
    static List<String> logicalLinesAfterMerge(String text, boolean stripFullLineHashComments) {
        String[] rawLines = text.split("\\R", -1);
        List<String> merged = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String rawLine : rawLines) {
            ContinuationParse cp = stripContinuationBackslash(rawLine);
            String piece = cp.leadingPart();
            if (cur.length() > 0) {
                cur.append(' ').append(piece.stripLeading());
            } else {
                cur.append(piece);
            }
            if (!cp.continuesNext()) {
                merged.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            merged.add(cur.toString());
        }

        List<String> out = new ArrayList<>();
        for (String line : merged) {
            String t = line.strip();
            if (stripFullLineHashComments && !t.isEmpty() && t.charAt(0) == '#') {
                continue;
            }
            out.add(line);
        }
        return out;
    }

    private record ContinuationParse(String leadingPart, boolean continuesNext) {
    }

    /**
     * If the line ends with {@code \} (after trailing spaces), split into leading part without the backslash
     * and signal that the next line continues.
     */
    private static ContinuationParse stripContinuationBackslash(String rawLine) {
        int end = rawLine.length();
        while (end > 0 && Character.isWhitespace(rawLine.charAt(end - 1))) {
            end--;
        }
        if (end > 0 && rawLine.charAt(end - 1) == '\\') {
            String withoutBackslash = rawLine.substring(0, end - 1).stripTrailing();
            return new ContinuationParse(withoutBackslash, true);
        }
        return new ContinuationParse(rawLine, false);
    }
}
