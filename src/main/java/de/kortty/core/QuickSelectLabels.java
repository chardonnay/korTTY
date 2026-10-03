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

    private QuickSelectLabels() {
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
