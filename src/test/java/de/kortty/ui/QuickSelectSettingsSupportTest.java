package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.core.QuickSelectLabels;
import de.kortty.core.QuickSelectPatterns;
import de.kortty.ui.QuickSelectSettingsSupport.PatternLine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import org.testng.annotations.Test;

/**
 * The quick-select fields of Settings &rarr; Terminal &rarr; Links: how their text becomes what is
 * stored, which problems keep Save from closing the dialog, and that each problem points at its line.
 */
public class QuickSelectSettingsSupportTest {

    /** Formats like the bundles do, without needing them: the key, then the arguments. */
    private static final BiFunction<String, Object[], String> MESSAGES =
        (key, args) -> args.length == 0 ? key : key + Arrays.toString(args);

    @Test
    public void everyNonBlankLineIsOnePatternTakenAsTyped() {
        String text = "INC\\d+\r\n\n   \n web\\d+ \nJIRA-\\d+";

        assertThat(QuickSelectSettingsSupport.patternLines(text)).containsExactly(
            new PatternLine(1, "INC\\d+"), new PatternLine(4, " web\\d+ "), new PatternLine(5, "JIRA-\\d+")).inOrder();
        assertThat(QuickSelectSettingsSupport.patterns(text)).containsExactly("INC\\d+", " web\\d+ ", "JIRA-\\d+")
            .inOrder();
        assertThat(QuickSelectSettingsSupport.patterns("")).isEmpty();
        assertThat(QuickSelectSettingsSupport.patterns(null)).isEmpty();
    }

    @Test
    public void storedPatternsComeBackOnePerLine() {
        List<String> stored = List.of("INC\\d+", "web\\d+\\.example");

        String text = QuickSelectSettingsSupport.patternsText(stored);

        assertThat(text).isEqualTo("INC\\d+\nweb\\d+\\.example");
        assertThat(QuickSelectSettingsSupport.patterns(text)).isEqualTo(stored);
        assertThat(QuickSelectSettingsSupport.patternsText(null)).isEmpty();
    }

    @Test
    public void theLettersAreStoredTrimmedAndEmptyMeansTheDefault() {
        assertThat(QuickSelectSettingsSupport.alphabet("  hjkl ")).isEqualTo("hjkl");
        assertThat(QuickSelectSettingsSupport.alphabet("   ")).isNull();
        assertThat(QuickSelectSettingsSupport.alphabet(null)).isNull();
        assertThat(QuickSelectSettingsSupport.alphabetMessage("", MESSAGES)).isNull();
        assertThat(QuickSelectSettingsSupport.alphabetMessage(" hjkl ", MESSAGES)).isNull();
    }

    @Test
    public void aLetterProblemIsExplainedWithTheLetter() {
        assertThat(QuickSelectSettingsSupport.alphabetMessage("hjKl", MESSAGES))
            .isEqualTo(QuickSelectLabels.KEY_ALPHABET_UPPERCASE + "[K]");
        assertThat(QuickSelectSettingsSupport.alphabetMessage("h", MESSAGES))
            .isEqualTo(QuickSelectLabels.KEY_ALPHABET_TOO_SHORT);
        assertThat(QuickSelectSettingsSupport.canSave("hjkh", "")).isFalse();
    }

    @Test
    public void eachBadPatternIsReportedWithItsLineNumber() {
        String text = "INC\\d+\n\n(open\na*\n" + "x".repeat(QuickSelectPatterns.MAX_PATTERN_CHARS + 1);

        List<String> messages = QuickSelectSettingsSupport.patternMessages(text, MESSAGES);

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).startsWith(QuickSelectSettingsSupport.PATTERN_LINE_KEY + "[3, "
            + QuickSelectPatterns.KEY_INVALID + "[");
        assertThat(messages.get(1)).isEqualTo(QuickSelectSettingsSupport.PATTERN_LINE_KEY + "[4, "
            + QuickSelectPatterns.KEY_MATCHES_EMPTY + "]");
        assertThat(messages.get(2)).isEqualTo(QuickSelectSettingsSupport.PATTERN_LINE_KEY + "[5, "
            + QuickSelectPatterns.KEY_TOO_LONG + "[" + QuickSelectPatterns.MAX_PATTERN_CHARS + "]]");
        assertThat(QuickSelectSettingsSupport.canSave("", text)).isFalse();
    }

    @Test
    public void tooManyPatternsIsReportedOnceForTheList() {
        String text = String.join("\n", Collections.nCopies(QuickSelectPatterns.MAX_PATTERNS + 1, "INC\\d+"));

        assertThat(QuickSelectSettingsSupport.patternMessages(text, MESSAGES))
            .containsExactly(QuickSelectPatterns.KEY_TOO_MANY + "[" + QuickSelectPatterns.MAX_PATTERNS + "]");
        // Blank lines do not count.
        String withBlanks = String.join("\n\n", Collections.nCopies(QuickSelectPatterns.MAX_PATTERNS, "INC\\d+"));
        assertThat(QuickSelectSettingsSupport.canSave("", withBlanks)).isTrue();
    }

    @Test
    public void validFieldsCanBeSaved() {
        assertThat(QuickSelectSettingsSupport.canSave("", "")).isTrue();
        assertThat(QuickSelectSettingsSupport.canSave("hjkl", "INC\\d+\n[A-Z]+-\\d+")).isTrue();
    }

    @Test
    public void everyMessageTheFieldsShowExistsInEveryBundle() throws IOException {
        List<String> keys = new java.util.ArrayList<>();
        keys.addAll(QuickSelectLabels.ALPHABET_MESSAGE_KEYS);
        keys.addAll(QuickSelectPatterns.MESSAGE_KEYS);
        keys.addAll(List.of(QuickSelectSettingsSupport.ALPHABET_KEY, QuickSelectSettingsSupport.ALPHABET_TOOLTIP_KEY,
            QuickSelectSettingsSupport.PATTERNS_KEY, QuickSelectSettingsSupport.PATTERNS_TOOLTIP_KEY,
            QuickSelectSettingsSupport.PATTERNS_PROMPT_KEY, QuickSelectSettingsSupport.PATTERN_LINE_KEY,
            QuickSelectSettingsSupport.INFO_KEY));
        for (String bundle : List.of("messages.properties", "messages_de.properties", "messages_it.properties",
                "messages_es.properties", "messages_pt.properties", "messages_fr.properties", "messages_hr.properties",
                "messages_nl.properties")) {
            java.util.Properties properties = new java.util.Properties();
            try (var reader = Files.newBufferedReader(Path.of("src/main/resources/i18n", bundle), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            for (String key : keys) {
                assertThat(properties.getProperty(key, "")).isNotEmpty();
            }
            // The messages fill in the letter, the limit or the line; a translation must keep the place.
            for (String key : List.of(QuickSelectLabels.KEY_ALPHABET_UPPERCASE, QuickSelectLabels.KEY_ALPHABET_DUPLICATE,
                    QuickSelectLabels.KEY_ALPHABET_INVALID_CHARACTER, QuickSelectPatterns.KEY_TOO_LONG,
                    QuickSelectPatterns.KEY_INVALID, QuickSelectPatterns.KEY_TOO_MANY)) {
                assertThat(properties.getProperty(key)).contains("{0}");
            }
            assertThat(properties.getProperty(QuickSelectSettingsSupport.PATTERN_LINE_KEY)).contains("{0}");
            assertThat(properties.getProperty(QuickSelectSettingsSupport.PATTERN_LINE_KEY)).contains("{1}");
            // A backslash survives the properties escaping: the example must show \d, not d.
            assertThat(properties.getProperty(QuickSelectSettingsSupport.PATTERNS_PROMPT_KEY)).contains("\\d+");
        }
    }
}
