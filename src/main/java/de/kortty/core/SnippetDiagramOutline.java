package de.kortty.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Condenses a long script into the structural outline the diagram request sends instead of every
 * line.
 *
 * <p>A whole-file copy is the right context for a snippet, but not for a four-thousand-line script:
 * asking a model to fold 4,009 numbered lines into two dozen nodes made it transcribe the script
 * instead — 62,746 prompt tokens in, a 135,445-character answer out, three minutes spent, and a
 * result the validator had to reject (observed with MiniMax-M3 on getssl). The outline keeps what
 * describes the flow — definitions and the top-level path through the file — marks the elided runs,
 * and keeps every original line number, so a returned {@code codeReferences} entry still points at
 * the real snippet and the local hover references keep working.</p>
 */
public final class SnippetDiagramOutline {

    /** Snippets up to this many lines are sent complete; nothing changes for ordinary snippets. */
    public static final int CONDENSE_THRESHOLD_LINES = 400;
    /**
     * Upper bound for a condensed outline. Sized so that a large real-world script fits with its
     * definitions and its complete top-level flow: getssl (4,009 lines) needs 386 of them, and the
     * outline is still an eighth of the full file.
     */
    public static final int MAX_OUTLINE_LINES = 500;
    /** Below this many kept lines the selection is widened — a deeply nested file has no top level. */
    private static final int MIN_OUTLINE_LINES = 24;
    /** Indentation (in expanded columns) that the widened pass still accepts. */
    private static final int WIDENED_INDENT_COLUMNS = 4;
    private static final int TAB_COLUMNS = 4;

    private static final Pattern COMMENT = Pattern.compile("^\\s*(?:#|//|--|;|::|\"\"\"|'''|<#|/\\*|\\*)");
    private static final Pattern DEFINITION = Pattern.compile(
        "^\\s*(?:(?:async\\s+)?(?:function|def|sub|proc|class|interface|module|task|rule)\\b"
            + "|(?:[A-Za-z_][A-Za-z0-9_.:-]*)\\s*\\(\\s*\\)\\s*\\{?"
            + "|[A-Za-z_][A-Za-z0-9_]*\\s*\\(\\)\\s*$)");
    private static final Pattern CONTROL_FLOW = Pattern.compile(
        "^\\s*\\}?\\s*(?:if|elif|elsif|else|fi|case|esac|switch|when|while|until|for|foreach|do|done"
            + "|try|catch|except|finally|return|exit|die|break|continue|trap|end|main)\\b");

    /**
     * The context body for one diagram request. {@code text} is line-numbered exactly like the full
     * block, so the model reads original line numbers in both forms.
     */
    public record Outline(String text, int totalLines, int shownLines, boolean condensed) {
    }

    private SnippetDiagramOutline() {
    }

    public static Outline of(String content) {
        String value = content != null ? content : "";
        String[] lines = value.split("\\R", -1);
        int totalLines = SnippetDiagramSupport.countLines(value);
        if (totalLines <= CONDENSE_THRESHOLD_LINES) {
            return new Outline(numberAll(lines), totalLines, totalLines, false);
        }
        List<Integer> kept = select(lines);
        return new Outline(render(lines, kept), totalLines, kept.size(), true);
    }

    /**
     * Picks the lines that carry the flow: every definition, and the top-level path through the
     * file. A file whose entire body is indented (a class, a deeply nested block) would otherwise
     * yield almost nothing, so the selection widens before the budget is applied.
     */
    private static List<Integer> select(String[] lines) {
        List<Integer> kept = collect(lines, 0);
        if (kept.size() < MIN_OUTLINE_LINES) {
            kept = collect(lines, WIDENED_INDENT_COLUMNS);
        }
        return applyBudget(lines, kept);
    }

    private static List<Integer> collect(String[] lines, int indentAllowance) {
        List<Integer> kept = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            // The shebang or leading directive names the interpreter; a license header does not.
            boolean comment = COMMENT.matcher(line).find();
            if (index == 0 && comment) {
                kept.add(index);
                continue;
            }
            if (comment) {
                continue;
            }
            if (DEFINITION.matcher(line).find()
                || indentColumns(line) <= indentAllowance
                || (indentColumns(line) <= WIDENED_INDENT_COLUMNS && CONTROL_FLOW.matcher(line).find())) {
                kept.add(index);
            }
        }
        return kept;
    }

    /**
     * Trims an oversized outline, class by class. Definitions and the top-level path through the
     * file are what a phase-level diagram is drawn from, so branches nested inside a function are
     * dropped first — and dropped whole, because a sampled scattering of {@code fi} lines is noise
     * rather than structure. A class that has to be cut is thinned evenly across the file instead
     * of truncated: taking the first N lines of a script that defines its functions before running
     * them drops precisely the main flow the diagram is about.
     */
    private static List<Integer> applyBudget(String[] lines, List<Integer> kept) {
        if (kept.size() <= MAX_OUTLINE_LINES) {
            return kept;
        }
        Map<Integer, List<Integer>> byPriority = new TreeMap<>();
        for (int index : kept) {
            byPriority.computeIfAbsent(priority(lines[index]), key -> new ArrayList<>()).add(index);
        }
        List<Integer> budgeted = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> group : byPriority.entrySet()) {
            int room = MAX_OUTLINE_LINES - budgeted.size();
            if (room <= 0) {
                break;
            }
            List<Integer> indexes = group.getValue();
            if (indexes.size() <= room) {
                budgeted.addAll(indexes);
            } else if (group.getKey() <= PRIORITY_TOP_LEVEL) {
                budgeted.addAll(thinEvenly(indexes, room));
            }
        }
        budgeted.sort(Integer::compareTo);
        return budgeted;
    }

    /** Keeps {@code keepCount} entries spread evenly over {@code indexes}, preserving order. */
    private static List<Integer> thinEvenly(List<Integer> indexes, int keepCount) {
        Map<Integer, Boolean> picked = new LinkedHashMap<>();
        for (int position = 0; position < keepCount; position++) {
            picked.put(indexes.get((int) ((long) position * indexes.size() / keepCount)), true);
        }
        return new ArrayList<>(picked.keySet());
    }

    private static final int PRIORITY_DEFINITION = 0;
    private static final int PRIORITY_TOP_LEVEL = 1;
    private static final int PRIORITY_NESTED = 2;

    private static int priority(String line) {
        if (DEFINITION.matcher(line).find()) {
            return PRIORITY_DEFINITION;
        }
        return indentColumns(line) == 0 ? PRIORITY_TOP_LEVEL : PRIORITY_NESTED;
    }

    private static int indentColumns(String line) {
        int columns = 0;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == ' ') {
                columns++;
            } else if (character == '\t') {
                columns += TAB_COLUMNS;
            } else {
                break;
            }
        }
        return columns;
    }

    private static String numberAll(String[] lines) {
        StringBuilder builder = new StringBuilder();
        int width = numberWidth(lines.length);
        for (int index = 0; index < lines.length; index++) {
            appendNumbered(builder, width, index + 1, lines[index]);
        }
        return builder.toString();
    }

    private static String render(String[] lines, List<Integer> kept) {
        StringBuilder builder = new StringBuilder();
        int width = numberWidth(lines.length);
        int previous = -1;
        for (int index : kept) {
            int omitted = index - previous - 1;
            if (omitted > 0) {
                builder.append(" ".repeat(width)).append("   … ").append(omitted)
                    .append(omitted == 1 ? " line omitted …" : " lines omitted …").append('\n');
            }
            appendNumbered(builder, width, index + 1, lines[index]);
            previous = index;
        }
        int trailing = lines.length - previous - 1;
        if (trailing > 0) {
            builder.append(" ".repeat(width)).append("   … ").append(trailing)
                .append(trailing == 1 ? " line omitted …" : " lines omitted …").append('\n');
        }
        return builder.toString();
    }

    private static void appendNumbered(StringBuilder builder, int width, int number, String line) {
        builder.append(String.format(Locale.ROOT, "%" + width + "d | %s%n", number, line));
    }

    private static int numberWidth(int lineCount) {
        return String.valueOf(Math.max(1, lineCount)).length();
    }
}
