package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Every text of the restore bar ({@code session.restore.*}) and of File › Restore Previous Session
 * exists, translated, in all eight bundles,
 * with the same placeholders as the English text and no doubled apostrophe: LanguageManager fills
 * placeholders with String.replace rather than MessageFormat, so a doubled apostrophe would show up
 * doubled, and a lost {0} would hide the count or the tab name.
 */
class SessionRestoreI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    /** Texts that read the same in several languages: a separator pattern, a protocol name, a loanword. */
    private static final Set<String> SAME_IN_SOME_LANGUAGES = Set.of(
        RestoreAttention.ITEM_KEY,
        RestoreAttention.DETAILS_KEY,
        "session.restore.tab.sftp");

    /** File › Restore Previous Session and the status lines it leaves. */
    private static final List<String> PREVIOUS_SESSION_KEYS = List.of(
        "menu.file.restorePreviousSession",
        "session.restore.previous.none",
        "session.restore.previous.done");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    @Test
    void everyKeyExistsInEveryBundledLocale() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : keys()) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for key " + key).that(value.isBlank()).isFalse();
                assertWithMessage(bundle + " doubles an apostrophe in " + key).that(value).doesNotContain("''");
            }
        }
    }

    @Test
    void everyTranslationKeepsThePlaceholdersOfTheEnglishText() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : keys()) {
                assertWithMessage(bundle + " changes the placeholders of " + key)
                    .that(placeholders(localized.getProperty(key)))
                    .isEqualTo(placeholders(english.getProperty(key)));
            }
        }
    }

    @Test
    void theCountsAndTheBlockedReasonCarryTheirNumberOrTarget() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (String key : List.of(RestoreAttention.SUMMARY_KEY, RestoreAttention.COUNT_CREDENTIALS_KEY,
                RestoreAttention.COUNT_UNLOCK_KEY, RestoreAttention.COUNT_BLOCKED_KEY,
                RestoreAttention.COUNT_MISSING_KEY, TabRestoreTriage.Reason.BLOCKED.i18nKey())) {
            assertWithMessage(key).that(english.getProperty(key)).contains("{0}");
        }
        assertWithMessage(RestoreAttention.ITEM_KEY).that(placeholders(english.getProperty(RestoreAttention.ITEM_KEY)))
            .containsExactly("{0}", "{1}");
    }

    @Test
    void translationsAreNotLeftInEnglish() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (String bundle : BUNDLES.subList(1, BUNDLES.size())) {
            Properties localized = loadBundle(bundle);
            for (String key : keys()) {
                if (SAME_IN_SOME_LANGUAGES.contains(key)) {
                    continue;
                }
                assertWithMessage(bundle + " still has the English text for " + key)
                    .that(localized.getProperty(key)).isNotEqualTo(english.getProperty(key));
            }
        }
    }

    @Test
    void theRestorePreviousSessionTextsTakeNoPlaceholder() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : PREVIOUS_SESSION_KEYS) {
                // Shown with I18n.get(key) and no arguments; a stray {0} would be printed raw.
                assertWithMessage(bundle + " has a placeholder in " + key)
                    .that(PLACEHOLDER.matcher(localized.getProperty(key)).find()).isFalse();
            }
        }
    }

    private static List<String> keys() {
        List<String> keys = new java.util.ArrayList<>(RestoreAttention.KEYS);
        keys.addAll(PREVIOUS_SESSION_KEYS);
        return keys;
    }

    private static Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private Properties loadBundle(String fileName) throws Exception {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("i18n/" + fileName)) {
            assertWithMessage("Missing i18n bundle " + fileName).that(inputStream).isNotNull();
            Properties properties = new Properties();
            properties.load(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            return properties;
        }
    }
}
