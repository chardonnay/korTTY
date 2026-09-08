package de.kortty.core;

import de.kortty.model.AsciiArtCommentStyle;
import de.kortty.model.AsciiArtPrintStyle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Wraps a copied ASCII picture as a source-code comment or as print statements before it goes to the
 * clipboard.
 *
 * <p>Escaping mistakes here would be invisible until the user runs the pasted program, so every
 * language has its own small escaping function that is tested on its own, and the line wrapper for
 * each style is kept trivial. Deliberately free of JavaFX; the dialog only calls {@link #format} and
 * {@link #previewLine}.
 *
 * <p>The {@code gap} is the number of spaces placed directly in front of each picture line. For
 * comment styles it separates the marker from the picture; for print styles it sits inside the
 * string literal so the printed picture moves right on the screen. Blank picture lines never carry
 * the gap, because trailing spaces after a bare marker are invisible noise in a diff. Two markers
 * ({@code REM}, {@code --}) keep one space even at gap 0, because glued to the picture they would no
 * longer be comments.
 */
public final class AsciiArtCopySupport {

    public static final int MIN_GAP = 0;
    public static final int MAX_GAP = 20;
    public static final int DEFAULT_GAP = 1;

    /**
     * Heredoc terminator. Quoted on the {@code cat} line so the shell neither expands nor unescapes
     * the picture; upper-case and prefixed so it is unlikely to appear inside a picture, and
     * lengthened when it does anyway.
     */
    static final String HEREDOC_DELIMITER = "KORTTY_ASCII_ART";

    /**
     * Characters {@code cmd.exe} interprets and which a caret can neutralise. The double quote is
     * among them because an unescaped quote switches cmd into quote mode, in which the caret stops
     * escaping and every later {@code ^&} would print verbatim; {@code ^"} prints a quote without
     * toggling that mode, so the escaper can stay stateless.
     */
    private static final String BATCH_CARET_ESCAPED = "^&|<>()\"";

    /**
     * Markers that are only a comment when a separator follows: {@code REMabc} is an unknown command
     * in {@code cmd.exe}, {@code --|} is an operator in Haskell and {@code --[[} opens a long comment
     * in Lua. These styles get at least one space even when the gap is 0.
     */
    private static final Set<AsciiArtCommentStyle> MARKERS_NEEDING_A_SEPARATOR =
        EnumSet.of(AsciiArtCommentStyle.REM, AsciiArtCommentStyle.DOUBLE_DASH);

    private AsciiArtCopySupport() {
    }

    // ---- Gap ----

    /** Clamps {@code gap} into {@link #MIN_GAP}..{@link #MAX_GAP}. */
    public static int clampGap(int gap) {
        return Math.max(MIN_GAP, Math.min(MAX_GAP, gap));
    }

    // ---- Formatting ----

    /**
     * Wraps {@code art} in the chosen style. {@code null} styles mean {@link AsciiArtCommentStyle#NONE}
     * and {@link AsciiArtPrintStyle#NONE}; a print style wins over a comment style because the dialog
     * only ever sets one of them and the print form is the more specific request. Line endings are
     * normalised to {@code \n}, trailing newlines are dropped, and the result carries no trailing
     * newline, matching what the preview area hands over. Blank or {@code null} art yields {@code ""}.
     */
    public static String format(String art, AsciiArtCommentStyle comment, AsciiArtPrintStyle print, int gap) {
        if (art == null || art.isBlank()) {
            return "";
        }
        List<String> lines = pictureLines(art);
        String indent = " ".repeat(clampGap(gap));
        AsciiArtPrintStyle printStyle = print != null ? print : AsciiArtPrintStyle.NONE;
        AsciiArtCommentStyle commentStyle = comment != null ? comment : AsciiArtCommentStyle.NONE;
        List<String> out;
        if (printStyle != AsciiArtPrintStyle.NONE) {
            out = printLines(lines, printStyle, indent);
        } else if (commentStyle != AsciiArtCommentStyle.NONE) {
            out = commentLines(lines, commentStyle, indent);
        } else {
            out = lines;
        }
        return String.join("\n", out);
    }

    /**
     * The line {@code firstArtLine} becomes in the chosen style, for the copy button's tooltip. For
     * block styles this is the first content line (e.g. {@code " *    /\\"}), not the opener, because
     * the tooltip should show what happens to the picture rather than the wrapper.
     */
    public static String previewLine(String firstArtLine, AsciiArtCommentStyle comment, AsciiArtPrintStyle print,
                                     int gap) {
        String line = firstArtLine != null ? firstLine(firstArtLine) : "";
        String indent = " ".repeat(clampGap(gap));
        AsciiArtPrintStyle printStyle = print != null ? print : AsciiArtPrintStyle.NONE;
        AsciiArtCommentStyle commentStyle = comment != null ? comment : AsciiArtCommentStyle.NONE;
        List<String> single = List.of(line);
        if (printStyle != AsciiArtPrintStyle.NONE) {
            List<String> out = printLines(single, printStyle, indent);
            return printStyle == AsciiArtPrintStyle.BASH_HEREDOC ? out.get(1) : out.get(0);
        }
        if (commentStyle != AsciiArtCommentStyle.NONE) {
            List<String> out = commentLines(single, commentStyle, indent);
            return commentStyle.isBlock() ? out.get(1) : out.get(0);
        }
        return line;
    }

    /** Splits normalised text into picture lines, dropping the empty lines a trailing newline leaves behind. */
    private static List<String> pictureLines(String art) {
        String normalised = art.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(Arrays.asList(normalised.split("\n", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static String firstLine(String text) {
        String normalised = text.replace("\r\n", "\n").replace('\r', '\n');
        int newline = normalised.indexOf('\n');
        return newline >= 0 ? normalised.substring(0, newline) : normalised;
    }

    // ---- Comment styles ----

    private static List<String> commentLines(List<String> lines, AsciiArtCommentStyle style, String indent) {
        List<String> out = new ArrayList<>(lines.size() + 2);
        switch (style) {
            case C_BLOCK -> {
                out.add("/*");
                for (String line : lines) {
                    // The gap-0 case is broken together with the marker: " *" followed by a line
                    // starting with "/" would end the comment right there.
                    out.add(line.isBlank() ? " *" : breakCBlockTerminators(" *" + indent + line));
                }
                out.add(" */");
            }
            case XML_BLOCK -> {
                out.add("<!--");
                for (String line : lines) {
                    out.add(line.isBlank() ? "" : indent + breakXmlDoubleHyphens(line));
                }
                out.add("-->");
            }
            case JSON_STRINGS -> {
                out.add("[");
                for (int i = 0; i < lines.size(); i++) {
                    String separator = i < lines.size() - 1 ? "," : "";
                    out.add(indent + "\"" + escapeJson(lines.get(i)) + "\"" + separator);
                }
                out.add("]");
            }
            default -> {
                String marker = style.marker();
                String separator = indent.isEmpty() && MARKERS_NEEDING_A_SEPARATOR.contains(style) ? " " : indent;
                for (String line : lines) {
                    out.add(line.isBlank() ? marker : breakLineContinuation(style, marker + separator + line));
                }
                if (style == AsciiArtCommentStyle.DOUBLE_SLASH && endsWithBackslash(out)) {
                    out.add(marker);
                }
            }
        }
        return out;
    }

    /**
     * A {@code REM} line ending in {@code ^} continues onto the next line in {@code cmd.exe} and would
     * swallow the statement that follows the pasted picture. The caret escapes whatever follows it,
     * so a single trailing space breaks the continuation without changing what the picture looks like.
     */
    static String breakLineContinuation(AsciiArtCommentStyle style, String text) {
        return style == AsciiArtCommentStyle.REM && text.endsWith("^") ? text + " " : text;
    }

    /**
     * Whether the last emitted line ends in a backslash. The C and C++ preprocessor splices a
     * {@code //} line ending in {@code \} with the next line; inside the picture that only merges two
     * comment lines, but after the last line it would comment out the following statement. A trailing
     * space is no cure, because GCC and Clang also splice {@code \} followed by whitespace, so the
     * caller appends a bare {@code //} line to absorb the splice instead.
     */
    private static boolean endsWithBackslash(List<String> out) {
        return !out.isEmpty() && out.get(out.size() - 1).endsWith("\\");
    }

    // ---- Print styles ----

    private static List<String> printLines(List<String> lines, AsciiArtPrintStyle style, String indent) {
        List<String> out = new ArrayList<>(lines.size() + 2);
        if (style == AsciiArtPrintStyle.BASH_HEREDOC) {
            String delimiter = uniqueHeredocDelimiter(lines);
            out.add("cat <<'" + delimiter + "'");
            for (String line : lines) {
                out.add(line.isBlank() ? "" : indent + line);
            }
            out.add(delimiter);
            return out;
        }
        for (String line : lines) {
            out.add(line.isBlank() ? blankStatement(style) : statement(style, indent + line));
        }
        return out;
    }

    /**
     * {@link #HEREDOC_DELIMITER}, lengthened with {@code _1}, {@code _2}, … until no picture line
     * equals it — a matching line would end the heredoc early.
     */
    static String uniqueHeredocDelimiter(List<String> lines) {
        String delimiter = HEREDOC_DELIMITER;
        for (int suffix = 1; lines.contains(delimiter); suffix++) {
            delimiter = HEREDOC_DELIMITER + "_" + suffix;
        }
        return delimiter;
    }

    /** The statement that prints {@code text} (gap already applied) followed by a newline. */
    private static String statement(AsciiArtPrintStyle style, String text) {
        return switch (style) {
            case BASH_ECHO -> "echo '" + escapeSingleQuotedShell(text) + "'";
            case SH_PRINTF -> "printf '%s\\n' '" + escapeSingleQuotedShell(text) + "'";
            case PERL -> "print '" + escapeBackslashSingleQuoted(text) + "', \"\\n\";";
            case PYTHON -> "print(\"" + escapeBackslashDoubleQuoted(text) + "\")";
            case RUBY -> "puts '" + escapeBackslashSingleQuoted(text) + "'";
            case PHP -> "echo '" + escapeBackslashSingleQuoted(text) + "', PHP_EOL;";
            case JAVASCRIPT -> "console.log(\"" + escapeBackslashDoubleQuoted(text) + "\");";
            case JAVA -> "System.out.println(\"" + escapeBackslashDoubleQuoted(text) + "\");";
            case C -> "puts(\"" + escapeC(text) + "\");";
            case CSHARP -> "Console.WriteLine(\"" + escapeBackslashDoubleQuoted(text) + "\");";
            case GO -> "fmt.Println(\"" + escapeBackslashDoubleQuoted(text) + "\")";
            case RUST -> "println!(\"" + escapeRustFormat(text) + "\");";
            case KOTLIN -> "println(\"" + escapeKotlinTemplate(text) + "\")";
            case SWIFT, LUA -> "print(\"" + escapeBackslashDoubleQuoted(text) + "\")";
            case POWERSHELL -> "Write-Output '" + escapePowerShellSingleQuoted(text) + "'";
            case BATCH -> "echo(" + escapeBatch(text);
            case BASH_HEREDOC, NONE -> throw new IllegalArgumentException("No single-line statement for " + style);
        };
    }

    /** The statement that prints an empty line; the shortest form each language allows. */
    private static String blankStatement(AsciiArtPrintStyle style) {
        return switch (style) {
            case BASH_ECHO -> "echo ''";
            case SH_PRINTF -> "printf '\\n'";
            case PERL -> "print \"\\n\";";
            case PYTHON -> "print()";
            case RUBY -> "puts";
            case PHP -> "echo PHP_EOL;";
            case JAVASCRIPT -> "console.log(\"\");";
            case JAVA -> "System.out.println();";
            case C -> "puts(\"\");";
            case CSHARP -> "Console.WriteLine();";
            case GO -> "fmt.Println()";
            case RUST -> "println!();";
            case KOTLIN -> "println()";
            case SWIFT, LUA -> "print()";
            case POWERSHELL -> "Write-Output ''";
            case BATCH -> "echo(";
            case BASH_HEREDOC, NONE -> throw new IllegalArgumentException("No single-line statement for " + style);
        };
    }

    // ---- Escaping ----

    /**
     * Inside single quotes the shell interprets nothing, so the only character that needs work is
     * the quote itself: close the string, add a backslash-escaped quote, reopen. Works in every
     * Bourne-style shell, which is why {@code echo} and {@code printf} share it.
     */
    static String escapeSingleQuotedShell(String text) {
        return text.replace("'", "'\\''");
    }

    /** Perl, Ruby and PHP single-quoted strings: only {@code \} and {@code '} have meaning. */
    static String escapeBackslashSingleQuoted(String text) {
        return text.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** The common double-quoted string of Python, JavaScript, Java, C#, Go, Swift and Lua. */
    static String escapeBackslashDoubleQuoted(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * C string literals plus trigraph protection: a {@code ??} pair followed by e.g. {@code =} is a
     * trigraph in C89/C99 and at least a warning everywhere else, so the second of two adjacent
     * question marks becomes {@code \?}, which every C compiler accepts.
     */
    static String escapeC(String text) {
        String escaped = escapeBackslashDoubleQuoted(text);
        StringBuilder out = new StringBuilder(escaped.length() + 8);
        char previous = 0;
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == '?' && previous == '?') {
                out.append("\\?");
            } else {
                out.append(c);
            }
            previous = c;
        }
        return out.toString();
    }

    /** Rust's {@code println!} argument is a format string, so braces must be doubled. */
    static String escapeRustFormat(String text) {
        return escapeBackslashDoubleQuoted(text).replace("{", "{{").replace("}", "}}");
    }

    /** Kotlin strings are templates: a {@code $} would start an interpolation. */
    static String escapeKotlinTemplate(String text) {
        return escapeBackslashDoubleQuoted(text).replace("$", "\\$");
    }

    /** PowerShell single-quoted strings escape the quote by doubling it and nothing else. */
    static String escapePowerShellSingleQuoted(String text) {
        return text.replace("'", "''");
    }

    /**
     * {@code cmd.exe} has no string literal at all: {@code echo} sees the raw command line, so every
     * operator character and the double quote are caret-escaped and {@code %} is doubled so it is
     * not taken for a variable reference. The caret itself is escaped in the same pass, otherwise
     * the escapes would be re-escaped. {@code !} is left alone: it only matters with delayed
     * expansion switched on.
     */
    static String escapeBatch(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (BATCH_CARET_ESCAPED.indexOf(c) >= 0) {
                out.append('^').append(c);
            } else if (c == '%') {
                out.append("%%");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * A JSON string body. Pictures are printable ASCII, but control characters are escaped anyway so
     * the array always parses.
     */
    static String escapeJson(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * XML forbids {@code --} inside a comment, so every second hyphen of a run becomes {@code =}:
     * {@code ----} → {@code -=-=}. A horizontal rule keeps its length and stays readable.
     */
    static String breakXmlDoubleHyphens(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean previousHyphen = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '-' && previousHyphen) {
                out.append('=');
                previousHyphen = false;
            } else {
                out.append(c);
                previousHyphen = c == '-';
            }
        }
        return out.toString();
    }

    /** {@code *&#47;} would end a C block comment, so it becomes {@code +/}, the visually closest safe pair. */
    static String breakCBlockTerminators(String text) {
        return text.replace("*/", "+/");
    }
}
