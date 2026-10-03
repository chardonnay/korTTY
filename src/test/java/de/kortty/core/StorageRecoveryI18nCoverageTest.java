package de.kortty.core;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static com.google.common.truth.Truth.assertWithMessage;

/** The startup notice about unreadable data files must speak every bundled language. */
class StorageRecoveryI18nCoverageTest {

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
            "storage.loadFailed.title",
            "storage.loadFailed.header",
            "storage.loadFailed.content",
            "storage.loadFailed.blocked");

    /** The notice lists the affected files through {0}. */
    private static final List<String> PLACEHOLDER_KEYS = List.of(
            "storage.loadFailed.content",
            "storage.loadFailed.blocked");

    @Test
    void everyStorageRecoveryKeyExistsInEveryBundledLocale() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for " + key).that(value.isBlank()).isFalse();
            }
        }
    }

    @Test
    void theFileListPlaceholderSurvivesTranslation() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : PLACEHOLDER_KEYS) {
                assertWithMessage(bundle + " lost {0} from " + key)
                        .that(localized.getProperty(key)).contains("{0}");
            }
        }
    }

    @Test
    void translationsDiffer() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (String bundle : BUNDLES.subList(1, BUNDLES.size())) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                assertWithMessage(bundle + " still has the English text for " + key)
                        .that(localized.getProperty(key)).isNotEqualTo(english.getProperty(key));
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
