package de.kortty.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The labels quick select puts on the matches it finds on screen: the letters to type to copy or
 * open a match.
 *
 * <p>Labels are prefix-free, so a typed letter either completes a label or narrows the choice and
 * never has to wait for a second one: while there are no more matches than letters in the alphabet,
 * every label is one letter; otherwise every label is two letters. The first labels go to the first
 * matches, which quick select orders from the bottom row up, so the latest output gets the labels
 * that come first in the alphabet. The same text shown in several places shares one label.
 *
 * <p>Pure and thread-safe.
 */
public final class QuickSelectLabels {

    /**
     * The default alphabet: the home row first, then the rows above and below it, so the most-used
     * labels are the ones the fingers rest on.
     */
    public static final String DEFAULT_ALPHABET = "asdfqwerzxcvjklmiuopghtybn";

    public static final String KEY_ALPHABET_TOO_SHORT = "settings.terminal.quickSelect.alphabet.tooShort";
    public static final String KEY_ALPHABET_UPPERCASE = "settings.terminal.quickSelect.alphabet.uppercase";
    public static final String KEY_ALPHABET_INVALID_CHARACTER = "settings.terminal.quickSelect.alphabet.invalidCharacter";
    public static final String KEY_ALPHABET_DUPLICATE = "settings.terminal.quickSelect.alphabet.duplicate";

    /** Every key {@link #alphabetProblem(String)} can return. */
    public static final List<String> ALPHABET_MESSAGE_KEYS = List.of(KEY_ALPHABET_TOO_SHORT, KEY_ALPHABET_UPPERCASE,
        KEY_ALPHABET_INVALID_CHARACTER, KEY_ALPHABET_DUPLICATE);

    private QuickSelectLabels() {
    }

    /**
     * What is wrong with {@code alphabet} as the user's label letters, or {@code null} when it can be
     * used. Quick select decides by the key pressed, not the character typed, so a label letter must
     * be a key that no quick-select control already means:
     * <ul>
     *   <li>only the letters {@code a} to {@code z}: every other key (a digit, punctuation, Space,
     *       Enter, a letter of another script) ends quick select;</li>
     *   <li>lowercase: an uppercase letter needs Shift, and Shift with a label opens its match;</li>
     *   <li>each letter once, and at least two, so the labels stay prefix-free.</li>
     * </ul>
     * The first problem from the left is reported, quoting the character. {@code null} and blank are
     * no problem: they mean {@link #DEFAULT_ALPHABET}.
     */
    public static QuickSelectSettings.Problem alphabetProblem(String alphabet) {
        if (alphabet == null || alphabet.isBlank()) {
            return null;
        }
        for (int i = 0; i < alphabet.length(); ) {
            int letter = alphabet.codePointAt(i);
            String shown = new String(Character.toChars(letter));
            if (letter >= 'A' && letter <= 'Z') {
                return QuickSelectSettings.Problem.of(KEY_ALPHABET_UPPERCASE, shown);
            }
            if (letter < 'a' || letter > 'z') {
                return QuickSelectSettings.Problem.of(KEY_ALPHABET_INVALID_CHARACTER, visible(letter, shown));
            }
            if (alphabet.indexOf(letter) != i) {
                return QuickSelectSettings.Problem.of(KEY_ALPHABET_DUPLICATE, shown);
            }
            i += Character.charCount(letter);
        }
        if (alphabet.length() < 2) {
            return QuickSelectSettings.Problem.of(KEY_ALPHABET_TOO_SHORT);
        }
        return null;
    }

    /**
     * The alphabet quick select labels with for the stored {@code alphabet}: the stored one when
     * {@link #alphabetProblem} accepts it, otherwise (blank, or broken by hand) {@link #DEFAULT_ALPHABET}.
     */
    public static String effectiveAlphabet(String alphabet) {
        if (alphabet == null || alphabet.isBlank() || alphabetProblem(alphabet) != null) {
            return DEFAULT_ALPHABET;
        }
        return alphabet;
    }

    /** A character a message can quote: a space or a control character by its code, never raw. */
    private static String visible(int codePoint, String shown) {
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT) {
            return String.format(java.util.Locale.ROOT, "U+%04X", codePoint);
        }
        return shown;
    }

    /**
     * How many labels {@code alphabet} has at most: one per letter, or one per pair of letters.
     */
    public static int capacity(String alphabet) {
        int letters = validate(alphabet).length();
        return letters * letters;
    }

    /**
     * {@code count} prefix-free labels in order, or {@link #capacity} of them when {@code count} is
     * larger.
     *
     * @param count    how many labels are needed
     * @param alphabet distinct lowercase letters {@code a} to {@code z}, at least two
     */
    public static List<String> assign(int count, String alphabet) {
        String letters = validate(alphabet);
        if (count <= 0) {
            return List.of();
        }
        int size = letters.length();
        List<String> labels = new ArrayList<>(Math.min(count, size * size));
        if (count <= size) {
            for (int i = 0; i < count; i++) {
                labels.add(String.valueOf(letters.charAt(i)));
            }
            return Collections.unmodifiableList(labels);
        }
        for (int first = 0; first < size && labels.size() < count; first++) {
            for (int second = 0; second < size && labels.size() < count; second++) {
                labels.add(new String(new char[] {letters.charAt(first), letters.charAt(second)}));
            }
        }
        return Collections.unmodifiableList(labels);
    }

    /**
     * One label per text, for texts in the order they get labels: the same text gets the same label
     * wherever it occurs, and a text beyond the alphabet's {@link #capacity} gets {@code null}.
     *
     * @param texts    the texts of the matches, first label first
     * @param alphabet distinct lowercase letters {@code a} to {@code z}, at least two
     */
    public static List<String> forTexts(List<String> texts, String alphabet) {
        Objects.requireNonNull(texts, "texts");
        Map<String, Integer> distinct = new HashMap<>();
        for (String text : texts) {
            distinct.putIfAbsent(Objects.requireNonNull(text, "text"), distinct.size());
        }
        List<String> labels = assign(distinct.size(), alphabet);
        List<String> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            int index = distinct.get(text);
            result.add(index < labels.size() ? labels.get(index) : null);
        }
        return Collections.unmodifiableList(result);
    }

    private static String validate(String alphabet) {
        Objects.requireNonNull(alphabet, "alphabet");
        if (alphabet.length() < 2) {
            throw new IllegalArgumentException("A label alphabet needs at least two letters: " + alphabet);
        }
        for (int i = 0; i < alphabet.length(); i++) {
            char letter = alphabet.charAt(i);
            if (letter < 'a' || letter > 'z' || alphabet.indexOf(letter) != i) {
                throw new IllegalArgumentException("A label alphabet holds distinct letters a to z: " + alphabet);
            }
        }
        return alphabet;
    }
}
