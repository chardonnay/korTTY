package de.kortty.core;

import java.util.List;
import java.util.Objects;

/**
 * What quick select uses besides what it finds on its own: the letters it labels with and the
 * user's own patterns, as stored in {@code GlobalSettings.terminalQuickSelectAlphabet} and
 * {@code GlobalSettings.terminalQuickSelectPatterns} and checked by
 * {@link QuickSelectLabels#alphabetProblem(String)} and {@link QuickSelectPatterns#problem(String)}.
 *
 * <p>A stored value that does not pass those checks, which only a hand-edited settings file can
 * hold, never breaks quick select: an unusable alphabet falls back to
 * {@link QuickSelectLabels#DEFAULT_ALPHABET} and unusable patterns are left out.
 *
 * @param alphabet the label letters, always usable
 * @param patterns the user's patterns, compiled
 */
public record QuickSelectSettings(String alphabet, QuickSelectPatterns patterns) {

    /** The default alphabet and no patterns of the user's: quick select as it ships. */
    public static final QuickSelectSettings DEFAULTS =
        new QuickSelectSettings(QuickSelectLabels.DEFAULT_ALPHABET, QuickSelectPatterns.NONE);

    public QuickSelectSettings {
        alphabet = QuickSelectLabels.effectiveAlphabet(alphabet);
        patterns = patterns != null ? patterns : QuickSelectPatterns.NONE;
    }

    /**
     * The settings as stored.
     *
     * @param storedAlphabet the stored label letters; {@code null} or blank for the default
     * @param storedPatterns the stored patterns; {@code null} for none
     */
    public static QuickSelectSettings from(String storedAlphabet, List<String> storedPatterns) {
        return new QuickSelectSettings(storedAlphabet, QuickSelectPatterns.compile(storedPatterns));
    }

    /**
     * What is wrong with a setting: an i18n key and the values its message quotes, so the settings
     * show {@code getString(key, arguments)}.
     *
     * @param key       an entry of {@link QuickSelectLabels#ALPHABET_MESSAGE_KEYS} or
     *                  {@link QuickSelectPatterns#MESSAGE_KEYS}
     * @param arguments the message's {@code {0}}, {@code {1}}, ...
     */
    public record Problem(String key, List<Object> arguments) {

        public Problem {
            Objects.requireNonNull(key, "key");
            arguments = List.copyOf(arguments);
        }

        static Problem of(String key, Object... arguments) {
            return new Problem(key, List.of(arguments));
        }

        /** The arguments as the varargs of {@code I18n.get(key, args)}. */
        public Object[] argumentArray() {
            return arguments.toArray();
        }
    }
}
