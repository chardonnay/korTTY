package de.kortty.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Blanks the comments and string contents of a script with spaces, keeping every line's length
 * and every line break, so line-anchored rules (definitions, braces, indentation) do not fire inside
 * a comment, a docstring, a heredoc or a string, and an offset in the masked text is the same offset
 * in the original.
 *
 * <p>Two variants come back. {@link Masked#code()} blanks every string's contents and every heredoc
 * body: it is what definitions and brace depth are read from. {@link Masked#references()} keeps the
 * contents of interpolating strings ({@code "$name"} in a shell, a Perl, Ruby or PowerShell double
 * quote, a JavaScript template literal, an unquoted shell heredoc), because a variable used there is
 * still a use. Quote characters themselves are kept in both.
 *
 * <p>The scan is a single pass over the text, best effort and tolerant: an unterminated
 * single-line string ends at the line break, an unterminated block comment or multi-line string at
 * the end of the text, and a heredoc whose terminator never appears is not treated as one (a shift
 * such as {@code $(( 1 << bits ))} must not swallow the rest of a script). Pure Java.
 */
final class SnippetCodeMask {

    /** Masked lines of the text: same count and lengths as {@code text.split("\n", -1)}. */
    record Masked(String[] code, String[] references) {
    }

    /** The lexical rules of one language family. */
    private record Syntax(
        String lineComment,
        boolean hashNeedsBoundary,
        String blockStart,
        String blockEnd,
        String quotes,
        String rawQuotes,
        String interpolatingQuotes,
        String doubledQuoteEscapes,
        boolean multilineQuotes,
        boolean tripleQuotes,
        boolean heredocs,
        boolean heredocSpace,
        boolean podBlocks,
        boolean rubyBlocks,
        boolean hereStrings,
        boolean dataEnd,
        char escape,
        boolean longBrackets) {
    }

    private static final Syntax SHELL = new Syntax("#", true, null, null, "'\"", "'", "\"", "", true, false,
        true, true, false, false, false, false, '\\', false);
    private static final Syntax PYTHON = new Syntax("#", false, null, null, "'\"", "", "", "", false, true,
        false, false, false, false, false, false, '\\', false);
    private static final Syntax PERL = new Syntax("#", false, null, null, "'\"`", "", "\"`", "", false, false,
        true, false, true, false, false, true, '\\', false);
    private static final Syntax RUBY = new Syntax("#", false, null, null, "'\"`", "", "\"`", "", false, false,
        true, false, false, true, false, true, '\\', false);
    private static final Syntax POWERSHELL = new Syntax("#", true, "<#", "#>", "'\"", "'", "\"", "'", true,
        false, false, false, false, false, true, false, '`', false);
    private static final Syntax C_LIKE = new Syntax("//", false, "/*", "*/", "'\"`", "", "`", "", false, true,
        false, false, false, false, false, false, '\\', false);
    private static final Syntax GROOVY = new Syntax("//", false, "/*", "*/", "'\"", "", "\"", "", false, true,
        false, false, false, false, false, false, '\\', false);
    private static final Syntax SQL = new Syntax("--", false, "/*", "*/", "'\"", "'", "", "'", true, false,
        false, false, false, false, false, false, '\0', false);
    /** Lua: {@code --} comments, {@code --[[ … ]]} block comments and {@code [[ … ]]} long strings. */
    private static final Syntax LUA = new Syntax("--", false, null, null, "'\"", "", "", "", false, false,
        false, false, false, false, false, false, '\\', true);
    private static final Syntax DOCKERFILE = new Syntax("#", true, null, null, "'\"", "'", "\"", "", false,
        false, false, false, false, false, false, false, '\\', false);

    private SnippetCodeMask() {
    }

    /**
     * Masks {@code text} with the rules of {@code language} (a normalized snippet language). A
     * language without rules (plain text, markup, data formats) comes back unchanged.
     */
    static Masked mask(String language, String text) {
        String value = text != null ? text : "";
        Syntax syntax = syntaxOf(language);
        if (syntax == null || value.isEmpty()) {
            String[] lines = value.split("\n", -1);
            return new Masked(lines, lines.clone());
        }
        char[] code = value.toCharArray();
        char[] refs = value.toCharArray();
        new Scanner(value, syntax, code, refs).run();
        return new Masked(new String(code).split("\n", -1), new String(refs).split("\n", -1));
    }

    private static Syntax syntaxOf(String language) {
        if (language == null) {
            return null;
        }
        return switch (language) {
            case "bash" -> SHELL;
            case "python" -> PYTHON;
            case "perl" -> PERL;
            case "ruby" -> RUBY;
            case "powershell" -> POWERSHELL;
            case "java", "javascript", "typescript" -> C_LIKE;
            case "groovy" -> GROOVY;
            case "sql" -> SQL;
            case "dockerfile" -> DOCKERFILE;
            case "lua" -> LUA;
            default -> null;
        };
    }

    /** A heredoc announced on the current line; its body starts on the next one. */
    private record Heredoc(String delimiter, boolean interpolating) {
    }

    private static final class Scanner {

        private final String text;
        private final Syntax syntax;
        private final char[] code;
        private final char[] refs;
        private final int length;
        private final Deque<Heredoc> pendingHeredocs = new ArrayDeque<>();

        Scanner(String text, Syntax syntax, char[] code, char[] refs) {
            this.text = text;
            this.syntax = syntax;
            this.code = code;
            this.refs = refs;
            this.length = text.length();
        }

        void run() {
            int i = 0;
            while (i < length) {
                char c = text.charAt(i);
                if (c == '\n') {
                    i = pendingHeredocs.isEmpty() ? i + 1 : heredocBodies(i + 1);
                    continue;
                }
                boolean lineStart = i == 0 || text.charAt(i - 1) == '\n';
                if (lineStart) {
                    int skipped = lineStartBlock(i);
                    if (skipped > i) {
                        i = skipped;
                        continue;
                    }
                    if (skipped < 0) {
                        return;
                    }
                }
                if (syntax.escape != '\0' && c == syntax.escape && i + 1 < length && text.charAt(i + 1) != '\n') {
                    i += 2;
                    continue;
                }
                if (syntax.longBrackets) {
                    int after = longBracket(i);
                    if (after > i) {
                        i = after;
                        continue;
                    }
                }
                if (isLineComment(i)) {
                    int end = lineEnd(i);
                    blank(i, end, true);
                    i = end;
                    continue;
                }
                if (syntax.blockStart != null && text.startsWith(syntax.blockStart, i)) {
                    int close = text.indexOf(syntax.blockEnd, i + syntax.blockStart.length());
                    int end = close < 0 ? length : close + syntax.blockEnd.length();
                    blank(i, end, true);
                    i = end;
                    continue;
                }
                if (syntax.heredocs && c == '<' && text.startsWith("<<", i)) {
                    int after = heredocIntro(i);
                    if (after > i) {
                        i = after;
                        continue;
                    }
                }
                if (syntax.hereStrings && c == '@' && i + 1 < length
                    && (text.charAt(i + 1) == '"' || text.charAt(i + 1) == '\'')) {
                    int after = hereString(i);
                    if (after > i) {
                        i = after;
                        continue;
                    }
                }
                if (syntax.tripleQuotes && i + 2 < length && (c == '"' || c == '\'')
                    && text.charAt(i + 1) == c && text.charAt(i + 2) == c) {
                    i = tripleQuoted(i, c);
                    continue;
                }
                if (syntax.quotes.indexOf(c) >= 0) {
                    i = quoted(i, c);
                    continue;
                }
                i++;
            }
        }

        /**
         * POD ({@code =head1 … =cut}), Ruby's {@code =begin … =end}, and {@code __END__} /
         * {@code __DATA__}: returns the offset after the block, {@code start} when there is none,
         * or {@code -1} when the rest of the text is data.
         */
        private int lineStartBlock(int start) {
            if (syntax.dataEnd && (text.startsWith("__END__", start) || text.startsWith("__DATA__", start))) {
                blank(start, length, true);
                return -1;
            }
            if (syntax.podBlocks && text.charAt(start) == '=' && start + 1 < length
                && Character.isLetter(text.charAt(start + 1))) {
                return blankThroughLine(start, "=cut");
            }
            if (syntax.rubyBlocks && text.startsWith("=begin", start)) {
                return blankThroughLine(start, "=end");
            }
            return start;
        }

        /** Blanks from {@code start} through the end of the first later line starting with {@code marker}. */
        private int blankThroughLine(int start, String marker) {
            int lineStart = lineEnd(start) + 1;
            while (lineStart < length && !text.startsWith(marker, lineStart)) {
                lineStart = lineEnd(lineStart) + 1;
            }
            int end = lineStart < length ? lineEnd(lineStart) : length;
            blank(start, end, true);
            return end;
        }

        private boolean isLineComment(int i) {
            String marker = syntax.lineComment;
            if (marker == null || !text.startsWith(marker, i)) {
                return false;
            }
            if ("#".equals(marker)) {
                if (i > 0 && text.charAt(i - 1) == '$') {
                    return false; // $# in a shell or Perl
                }
                if (syntax.hashNeedsBoundary && i > 0) {
                    char before = text.charAt(i - 1);
                    return Character.isWhitespace(before) || ";|&()".indexOf(before) >= 0;
                }
            }
            return true;
        }

        /**
         * Lua's long brackets: a {@code --[==[ … ]==]} block comment or a {@code [==[ … ]==]} long
         * string (any number of {@code =}, matched by the closing bracket). Returns the offset after
         * it, or {@code start} when none starts here.
         */
        private int longBracket(int start) {
            boolean comment = text.startsWith("--", start);
            int open = comment ? start + 2 : start;
            if (open >= length || text.charAt(open) != '[') {
                return start;
            }
            int level = 0;
            int i = open + 1;
            while (i < length && text.charAt(i) == '=') {
                level++;
                i++;
            }
            if (i >= length || text.charAt(i) != '[') {
                return start;
            }
            String close = "]" + "=".repeat(level) + "]";
            int closeAt = text.indexOf(close, i + 1);
            int end = closeAt < 0 ? length : closeAt + close.length();
            if (comment) {
                blank(start, end, true);
            } else {
                blank(i + 1, closeAt < 0 ? length : closeAt, true);
            }
            return end;
        }

        /** A single-, double- or back-quoted string starting at {@code start}; returns the offset after it. */
        private int quoted(int start, char quote) {
            boolean raw = syntax.rawQuotes.indexOf(quote) >= 0;
            boolean doubled = syntax.doubledQuoteEscapes.indexOf(quote) >= 0;
            boolean multiline = syntax.multilineQuotes || quote == '`';
            int i = start + 1;
            while (i < length) {
                char c = text.charAt(i);
                if (c == '\n' && !multiline) {
                    break;
                }
                if (!raw && syntax.escape != '\0' && c == syntax.escape && i + 1 < length) {
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    if (doubled && i + 1 < length && text.charAt(i + 1) == quote) {
                        i += 2;
                        continue;
                    }
                    break;
                }
                i++;
            }
            int end = Math.min(i, length);
            blank(start + 1, end, syntax.interpolatingQuotes.indexOf(quote) < 0);
            return end < length && text.charAt(end) == quote ? end + 1 : end;
        }

        private int tripleQuoted(int start, char quote) {
            String delimiter = String.valueOf(quote).repeat(3);
            int i = start + 3;
            while (i < length && !text.startsWith(delimiter, i)) {
                i += text.charAt(i) == '\\' && i + 1 < length ? 2 : 1;
            }
            int end = Math.min(i, length);
            blank(start + 3, end, quote != '"' || syntax.interpolatingQuotes.indexOf('"') < 0);
            return Math.min(length, end + 3);
        }

        /**
         * {@code <<EOF}, {@code <<-EOF}, {@code <<~EOF}, {@code <<'EOF'}, {@code <<"EOF"}: records the
         * heredoc and returns the offset after its introduction, or {@code start} when this is not
         * one (a shift, a here-string {@code <<<}, or no terminator line further down).
         */
        private int heredocIntro(int start) {
            int i = start + 2;
            if (i < length && text.charAt(i) == '<') {
                return start;
            }
            if (i < length && (text.charAt(i) == '-' || text.charAt(i) == '~')) {
                i++;
            }
            int beforeSpace = i;
            while (syntax.heredocSpace && i < length && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
                i++;
            }
            char quote = i < length ? text.charAt(i) : '\0';
            boolean quoted = quote == '\'' || quote == '"';
            if (!quoted && i > beforeSpace && !syntax.heredocSpace) {
                return start;
            }
            if (quoted) {
                i++;
            } else if (i < length && text.charAt(i) == '\\') {
                i++;
                quoted = true;
                quote = '\0';
            }
            int nameStart = i;
            if (i >= length || !(Character.isLetter(text.charAt(i)) || text.charAt(i) == '_')) {
                return start;
            }
            while (i < length && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
                i++;
            }
            String delimiter = text.substring(nameStart, i);
            if (quote == '\'' || quote == '"') {
                if (i >= length || text.charAt(i) != quote) {
                    return start;
                }
                i++;
            }
            if (!terminatorFollows(lineEnd(start) + 1, delimiter)) {
                return start;
            }
            // A shell expands an unquoted heredoc only; Perl and Ruby also expand a double-quoted one.
            boolean interpolating = !quoted || quote == '"' && syntax != SHELL;
            pendingHeredocs.add(new Heredoc(delimiter, interpolating));
            return i;
        }

        private boolean terminatorFollows(int lineStart, String delimiter) {
            int i = lineStart;
            while (i < length) {
                int end = lineEnd(i);
                if (text.substring(i, end).strip().equals(delimiter)) {
                    return true;
                }
                i = end + 1;
            }
            return false;
        }

        /** Blanks the bodies of the pending heredocs, which start at {@code lineStart}; returns the offset after them. */
        private int heredocBodies(int lineStart) {
            int i = lineStart;
            while (!pendingHeredocs.isEmpty()) {
                Heredoc heredoc = pendingHeredocs.poll();
                while (i < length) {
                    int end = lineEnd(i);
                    if (text.substring(i, end).strip().equals(heredoc.delimiter())) {
                        i = Math.min(length, end + 1);
                        break;
                    }
                    blank(i, end, !heredoc.interpolating());
                    i = Math.min(length, end + 1);
                }
            }
            return i;
        }

        /** A PowerShell here-string: {@code @"} or {@code @'} at a line end, closed by {@code "@} / {@code '@} at a line start. */
        private int hereString(int start) {
            char quote = text.charAt(start + 1);
            int afterOpen = start + 2;
            int eol = lineEnd(afterOpen);
            if (!text.substring(afterOpen, eol).isBlank()) {
                return start;
            }
            String close = "\n" + quote + "@";
            int closeAt = text.indexOf(close, eol);
            int end = closeAt < 0 ? length : closeAt + 1;
            blank(afterOpen, end, quote == '\'');
            return closeAt < 0 ? length : closeAt + 3;
        }

        private int lineEnd(int from) {
            int end = text.indexOf('\n', from);
            return end < 0 ? length : end;
        }

        /** Blanks {@code [from, to)} in the code mask, and in the reference mask too when {@code alsoReferences}. */
        private void blank(int from, int to, boolean alsoReferences) {
            for (int i = Math.max(0, from); i < Math.min(to, length); i++) {
                if (code[i] != '\n') {
                    code[i] = ' ';
                    if (alsoReferences) {
                        refs[i] = ' ';
                    }
                }
            }
        }
    }
}
