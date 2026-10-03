package de.kortty.core;

import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * Quick select's labels: prefix-free, so a typed letter always either completes a label or
 * narrows the choice; the first labels for the first (bottom-most) matches; one label per distinct
 * text; nothing past the alphabet's capacity.
 */
public class QuickSelectLabelsTest {

    private static final String ALPHABET = QuickSelectLabels.DEFAULT_ALPHABET;

    @Test
    public void theDefaultAlphabetHoldsEveryLetterOnce() {
        assertThat(ALPHABET).hasLength(26);
        assertThat(new HashSet<>(Arrays.asList(ALPHABET.split("")))).hasSize(26);
        assertThat(QuickSelectLabels.capacity(ALPHABET)).isEqualTo(676);
    }

    @Test
    public void upToOneLabelPerLetterEveryLabelIsOneLetterInAlphabetOrder() {
        assertThat(QuickSelectLabels.assign(1, ALPHABET)).containsExactly("a");
        assertThat(QuickSelectLabels.assign(3, ALPHABET)).containsExactly("a", "s", "d").inOrder();

        List<String> full = QuickSelectLabels.assign(26, ALPHABET);
        assertThat(full).hasSize(26);
        assertThat(String.join("", full)).isEqualTo(ALPHABET);
        assertPrefixFree(full);
    }

    @Test
    public void pastOneLabelPerLetterEveryLabelIsTwoLetters() {
        List<String> labels = QuickSelectLabels.assign(27, ALPHABET);

        assertThat(labels).hasSize(27);
        assertThat(labels.subList(0, 3)).containsExactly("aa", "as", "ad").inOrder();
        assertThat(labels.stream().allMatch(label -> label.length() == 2)).isTrue();
        assertPrefixFree(labels);
        assertPrefixFree(QuickSelectLabels.assign(400, ALPHABET));
    }

    @Test
    public void noMoreLabelsThanTheAlphabetHasPairs() {
        List<String> labels = QuickSelectLabels.assign(1000, ALPHABET);

        assertThat(labels).hasSize(676);
        assertThat(new HashSet<>(labels)).hasSize(676);
        assertThat(QuickSelectLabels.assign(0, ALPHABET)).isEmpty();
        assertThat(QuickSelectLabels.assign(-1, ALPHABET)).isEmpty();
    }

    @Test
    public void theSameTextSharesItsLabelAndTheFirstTextsGetTheFirstLabels() {
        List<String> labels = QuickSelectLabels.forTexts(
            List.of("10.0.0.1", "https://example.com", "10.0.0.1", "deadbeef1"), ALPHABET);

        assertThat(labels).containsExactly("a", "s", "a", "d").inOrder();
    }

    @Test
    public void textsPastTheCapacityGetNoLabel() {
        List<String> texts = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            texts.add("text" + i);
        }

        List<String> labels = QuickSelectLabels.forTexts(texts, "ab");

        // Two letters give four two-letter labels; the fifth distinct text gets none.
        assertThat(labels).containsExactly("aa", "ab", "ba", "bb", null).inOrder();
    }

    @Test
    public void theAssignmentIsDeterministic() {
        List<String> texts = List.of("b", "a", "c", "a");

        assertThat(QuickSelectLabels.forTexts(texts, ALPHABET)).isEqualTo(QuickSelectLabels.forTexts(texts, ALPHABET));
        assertThat(QuickSelectLabels.assign(300, ALPHABET)).isEqualTo(QuickSelectLabels.assign(300, ALPHABET));
    }

    @Test
    public void anAlphabetIsTwoOrMoreDistinctLowercaseLetters() {
        assertThrows(IllegalArgumentException.class, () -> QuickSelectLabels.assign(1, "a"));
        assertThrows(IllegalArgumentException.class, () -> QuickSelectLabels.assign(1, "aa"));
        assertThrows(IllegalArgumentException.class, () -> QuickSelectLabels.assign(1, "aB"));
        assertThrows(IllegalArgumentException.class, () -> QuickSelectLabels.assign(1, "a1"));
        assertThrows(NullPointerException.class, () -> QuickSelectLabels.assign(1, null));
    }

    private static void assertPrefixFree(List<String> labels) {
        Set<String> all = new HashSet<>(labels);
        assertThat(all).hasSize(labels.size());
        for (String label : labels) {
            for (int length = 1; length < label.length(); length++) {
                assertThat(all).doesNotContain(label.substring(0, length));
            }
        }
    }
}
