package de.kortty.ui;

import de.kortty.core.QuickSelectLabels;
import de.kortty.core.QuickSelectPatterns;
import de.kortty.core.QuickSelectSettings.Problem;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * The quick-select part of Settings &rarr; Terminal &rarr; Links without the controls: how the text
 * fields map to {@code GlobalSettings.terminalQuickSelectAlphabet} and
 * {@code GlobalSettings.terminalQuickSelectPatterns}, and the messages that explain what keeps them
 * from being saved. The patterns field holds one regular expression per line; blank lines are
 * skipped and every other line is taken exactly as typed, because a space can be part of a pattern.
 *
 * <p>Toolkit-free, so it is tested headless; the messages come from a formatter such as
 * {@link I18n#get(String, Object...)}.
 */
final class QuickSelectSettingsSupport {

    static final String ALPHABET_KEY = "settings.terminal.quickSelect.alphabet";
    static final String ALPHABET_TOOLTIP_KEY = "settings.terminal.quickSelect.alphabet.tooltip";
    static final String PATTERNS_KEY = "settings.terminal.quickSelect.patterns";
    static final String PATTERNS_TOOLTIP_KEY = "settings.terminal.quickSelect.patterns.tooltip";
    static final String PATTERNS_PROMPT_KEY = "settings.terminal.quickSelect.patterns.prompt";
    static final String PATTERN_LINE_KEY = "settings.terminal.quickSelect.patterns.line";
    static final String INFO_KEY = "settings.terminal.quickSelect.info";

    /**
     * One pattern of the field.
     *
     * @param lineNumber its line in the field, counted from 1, so a message can point at it
     * @param pattern    the line as typed
     */
    record PatternLine(int lineNumber, String pattern) {
    }

    private QuickSelectSettingsSupport() {
    }

    /** The field's text for the stored patterns: one per line. */
    static String patternsText(List<String> patterns) {
        return patterns == null ? "" : String.join("\n", patterns);
    }

    /** The patterns of the field's text, with their line numbers; blank lines are skipped. */
    static List<PatternLine> patternLines(String text) {
        List<PatternLine> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        String[] physical = text.split("\\R", -1);
        for (int i = 0; i < physical.length; i++) {
            if (!physical[i].isBlank()) {
                lines.add(new PatternLine(i + 1, physical[i]));
            }
        }
        return lines;
    }

    /** The patterns to store for the field's text. */
    static List<String> patterns(String text) {
        return patternLines(text).stream().map(PatternLine::pattern).toList();
    }

    /** The label letters to store for the field's text: trimmed, {@code null} for empty (the default). */
    static String alphabet(String text) {
        String trimmed = text != null ? text.strip() : "";
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** What keeps the label letters from being saved, or {@code null} when they can be. */
    static String alphabetMessage(String text, BiFunction<String, Object[], String> messages) {
        Objects.requireNonNull(messages, "messages");
        Problem problem = QuickSelectLabels.alphabetProblem(alphabet(text));
        return problem != null ? messages.apply(problem.key(), problem.argumentArray()) : null;
    }

    /**
     * What keeps the patterns from being saved, one message per problem, the list as a whole first and
     * then each bad line in order; empty when they can be saved.
     */
    static List<String> patternMessages(String text, BiFunction<String, Object[], String> messages) {
        Objects.requireNonNull(messages, "messages");
        List<String> result = new ArrayList<>();
        List<PatternLine> lines = patternLines(text);
        Problem tooMany = QuickSelectPatterns.countProblem(lines.size());
        if (tooMany != null) {
            result.add(messages.apply(tooMany.key(), tooMany.argumentArray()));
        }
        for (PatternLine line : lines) {
            Problem problem = QuickSelectPatterns.problem(line.pattern());
            if (problem != null) {
                result.add(messages.apply(PATTERN_LINE_KEY,
                    new Object[] {line.lineNumber(), messages.apply(problem.key(), problem.argumentArray())}));
            }
        }
        return result;
    }

    /** Whether both fields can be saved as they are. */
    static boolean canSave(String alphabetText, String patternsText) {
        BiFunction<String, Object[], String> keyOnly = (key, args) -> key;
        return alphabetMessage(alphabetText, keyOnly) == null && patternMessages(patternsText, keyOnly).isEmpty();
    }
}
