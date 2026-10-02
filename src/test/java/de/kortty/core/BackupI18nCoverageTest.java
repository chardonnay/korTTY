package de.kortty.core;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertWithMessage;

class BackupI18nCoverageTest {

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
            "backup.import.unknownFormat",
            "backup.import.filter.backups",
            "backup.import.filter.all",
            "backup.import.restartRequired.header",
            "backup.import.restartRequired.message");

    /** Messages whose placeholders the backup dialogs fill in. */
    private static final List<String> PLACEHOLDER_KEYS = List.of(
            "backup.import.unknownFormat",
            "backup.import.restartRequired.message",
            "backup.createdMessage",
            "backup.import.successMessage");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    @Test
    void everyBackupKeyExistsInEveryBundledLocale() throws Exception {
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
    void everyPlaceholderSurvivesTranslation() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : PLACEHOLDER_KEYS) {
                Matcher matcher = PLACEHOLDER.matcher(english.getProperty(key));
                assertWithMessage("messages.properties: " + key + " has no placeholder")
                        .that(PLACEHOLDER.matcher(english.getProperty(key)).find()).isTrue();
                while (matcher.find()) {
                    // A dropped {0} hides the file name or the number of restored files.
                    assertWithMessage(bundle + " lost " + matcher.group() + " from " + key)
                            .that(localized.getProperty(key)).contains(matcher.group());
                }
            }
        }
    }

    private Properties loadBundle(String fileName) throws Exception {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("i18n/" + fileName)) {
            assertWithMessage("Missing i18n bundle " + fileName).that(inputStream).isNotNull();
            Properties properties = new Properties();
            properties.load(new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8));
            return properties;
        }
    }
}
