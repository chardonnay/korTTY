package de.kortty.core;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The marker rows around a project's restored screen output are the only thing telling the user
 * that the dimmed block above the new session is old, local output — every locale needs them.
 */
class ProjectRestoreI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
            "messages.properties",
            "messages_de.properties",
            "messages_it.properties",
            "messages_es.properties",
            "messages_pt.properties",
            "messages_fr.properties",
            "messages_hr.properties",
            "messages_nl.properties");

    private static final List<String> REQUIRED_KEYS = List.of(
            "terminal.restoredHistory.header",
            "terminal.restoredHistory.headerUndated",
            "terminal.restoredHistory.footer");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    @Test
    void everyRestoredHistoryKeyExistsInEveryBundledLocale() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for " + key)
                        .that(value.isBlank()).isFalse();
            }
        }
    }

    @Test
    void theSavedAtPlaceholderSurvivesTranslation() throws Exception {
        Properties english = loadBundle("messages.properties");
        assertWithMessage("the English header must carry the saved-at date")
                .that(english.getProperty("terminal.restoredHistory.header")).contains("{0}");
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                Matcher matcher = PLACEHOLDER.matcher(english.getProperty(key));
                while (matcher.find()) {
                    assertWithMessage(bundle + " lost " + matcher.group() + " from " + key)
                            .that(localized.getProperty(key)).contains(matcher.group());
                }
            }
        }
    }

    @Test
    void theUndatedHeaderAndTheFooterTakeNoArgument() throws Exception {
        // Both are shown with I18n.get(key) and no arguments; a stray {0} would be printed raw.
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : List.of("terminal.restoredHistory.headerUndated", "terminal.restoredHistory.footer")) {
                assertWithMessage(bundle + " has a placeholder in " + key)
                        .that(PLACEHOLDER.matcher(localized.getProperty(key)).find()).isFalse();
            }
        }
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
