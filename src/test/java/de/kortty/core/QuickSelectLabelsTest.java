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

    @Test
    public void theUsersLettersAreAcceptedWhenAssignCanUseThem() {
        for (String alphabet : List.of("jk", "hjkl", "aoeuidhtns", ALPHABET)) {
            assertThat(QuickSelectLabels.alphabetProblem(alphabet)).isNull();
            // The settings and the labels agree: what the settings accept never throws later.
            assertThat(QuickSelectLabels.assign(3, alphabet)).hasSize(3);
            assertThat(QuickSelectLabels.effectiveAlphabet(alphabet)).isEqualTo(alphabet);
        }
        // Empty means the default, so it is no problem either.
        assertThat(QuickSelectLabels.alphabetProblem(null)).isNull();
        assertThat(QuickSelectLabels.alphabetProblem("  ")).isNull();
    }

    @Test
    public void aSingleLetterIsTooShort() {
        assertThat(QuickSelectLabels.alphabetProblem("a").key()).isEqualTo(QuickSelectLabels.KEY_ALPHABET_TOO_SHORT);
        assertThat(QuickSelectLabels.alphabetProblem("a").arguments()).isEmpty();
    }

    @Test
    public void anUppercaseLetterWouldCollideWithShiftWhichOpensAMatch() {
        QuickSelectSettings.Problem problem = QuickSelectLabels.alphabetProblem("asdF");

        assertThat(problem.key()).isEqualTo(QuickSelectLabels.KEY_ALPHABET_UPPERCASE);
        assertThat(problem.arguments()).containsExactly("F");
    }

    @Test
    public void everyKeyThatIsNoLetterAToZWouldEndQuickSelectAndIsRejected() {
        // Digits, Space, Backspace and Escape are quick select's own keys or end it; letters of
        // other scripts and accented letters have no A-Z key code.
        for (String character : List.of("1", "-", ";", "ä", "é", "ж", "ß")) {
            QuickSelectSettings.Problem problem = QuickSelectLabels.alphabetProblem("as" + character + "d");
            assertThat(problem.key()).isEqualTo(QuickSelectLabels.KEY_ALPHABET_INVALID_CHARACTER);
            assertThat(problem.arguments()).containsExactly(character);
        }
        // An invisible character is quoted by its code, never raw.
        assertThat(QuickSelectLabels.alphabetProblem("as d").arguments()).containsExactly("U+0020");
        assertThat(QuickSelectLabels.alphabetProblem("as\td").arguments()).containsExactly("U+0009");
        assertThat(QuickSelectLabels.alphabetProblem("as‮d").arguments()).containsExactly("U+202E");
        // A character outside the BMP is quoted whole.
        assertThat(QuickSelectLabels.alphabetProblem("as😀").arguments()).containsExactly("😀");
    }

    @Test
    public void aRepeatedLetterWouldMakeTwoLabelsTheSameAndIsRejected() {
        QuickSelectSettings.Problem problem = QuickSelectLabels.alphabetProblem("asdfa");

        assertThat(problem.key()).isEqualTo(QuickSelectLabels.KEY_ALPHABET_DUPLICATE);
        assertThat(problem.arguments()).containsExactly("a");
    }

    @Test
    public void anUnusableStoredAlphabetFallsBackToTheDefault() {
        for (String broken : List.of("a", "ABC", "aab", "a1b", "")) {
            assertThat(QuickSelectLabels.effectiveAlphabet(broken)).isEqualTo(ALPHABET);
        }
        assertThat(QuickSelectLabels.effectiveAlphabet(null)).isEqualTo(ALPHABET);
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
