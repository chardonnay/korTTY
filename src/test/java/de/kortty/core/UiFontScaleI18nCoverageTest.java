package de.kortty.core;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * A missing translation for the UI font size does not fail anywhere — the label just renders as its
 * raw key, in an Appearance tab most users open exactly once. This guards all nine bundles.
 */
class UiFontScaleI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_en.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    private static final List<String> REQUIRED_KEYS = List.of(
        "settings.appearance.uiFontScale",
        "settings.appearance.uiFontScale.tooltip",
        "settings.appearance.uiFontScale.auto",
        "settings.appearance.uiFontScale.auto.tooltip",
        "settings.appearance.uiFontScale.info",
        "guide.fontSize.decrease",
        "guide.fontSize.increase",
        "guide.fontSize.reset");

    @Test
    void allUiFontScaleKeysExistInEveryBundledLocale() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for key " + key)
                    .that(value.isBlank()).isFalse();
            }
        }
    }

    /**
     * A copy-paste of the English text into a localized bundle is easy to miss in review, and the
     * two label keys are the ones a user actually reads. The longer explanatory texts are exempt:
     * a few of them legitimately share wording across related languages.
     */
    @Test
    void labelKeysAreActuallyTranslated() throws Exception {
        Properties base = loadBundle("messages.properties");
        List<String> labelKeys = List.of(
            "settings.appearance.uiFontScale",
            "settings.appearance.uiFontScale.auto");

        for (String bundle : BUNDLES) {
            if (bundle.equals("messages.properties") || bundle.equals("messages_en.properties")) {
                continue;
            }
            Properties localized = loadBundle(bundle);
            for (String key : labelKeys) {
                assertWithMessage(bundle + " left key " + key + " at the English wording")
                    .that(localized.getProperty(key)).isNotEqualTo(base.getProperty(key));
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
