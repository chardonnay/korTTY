package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The status-bar note of a restored tab that could not reopen all its split panes names the tab,
 * the counts and the reasons; every bundled locale needs it and every reason.
 */
class SplitLayoutRestoreI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    private static final String INCOMPLETE_KEY = "project.splitRestore.incomplete";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    @Test
    void everyKeyExistsInEveryBundledLocale() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : keys()) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for " + key).that(value.isBlank()).isFalse();
            }
        }
    }

    @Test
    void theNoteKeepsItsFourPlaceholdersAndTheReasonsHaveNone() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            String note = localized.getProperty(INCOMPLETE_KEY);
            for (String placeholder : List.of("{0}", "{1}", "{2}", "{3}")) {
                assertWithMessage(bundle + " lost " + placeholder + " from " + INCOMPLETE_KEY)
                    .that(note).contains(placeholder);
            }
            for (SplitLayoutRestorePlan.SkipReason reason : SplitLayoutRestorePlan.SkipReason.values()) {
                // Shown with I18n.get(key) and no arguments; a stray {0} would be printed raw.
                assertWithMessage(bundle + " has a placeholder in " + reason.i18nKey())
                    .that(PLACEHOLDER.matcher(localized.getProperty(reason.i18nKey())).find()).isFalse();
            }
        }
    }

    @Test
    void noBundleUsesDoubledApostrophes() throws Exception {
        // LanguageManager fills the placeholders with String.replace, not MessageFormat.
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : keys()) {
                assertWithMessage(bundle + " doubles an apostrophe in " + key)
                    .that(localized.getProperty(key)).doesNotContain("''");
            }
        }
    }

    private static List<String> keys() {
        List<String> keys = new ArrayList<>();
        keys.add(INCOMPLETE_KEY);
        for (SplitLayoutRestorePlan.SkipReason reason : SplitLayoutRestorePlan.SkipReason.values()) {
            keys.add(reason.i18nKey());
        }
        return keys;
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
