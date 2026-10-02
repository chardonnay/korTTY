package de.kortty.core;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static com.google.common.truth.Truth.assertWithMessage;

class TerminalEncodingI18nCoverageTest {

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
            "connEdit.encoding",
            "connEdit.encoding.inherit",
            "connEdit.encoding.tooltip",
            "connEdit.encoding.moshUtf8Only",
            "settings.terminal.encoding.tooltip",
            "settings.terminal.encoding.pendingHint");

    @Test
    void everyTerminalEncodingKeyExistsInEveryBundledLocale() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for " + key).that(value.isBlank()).isFalse();
                // LanguageManager substitutes with String.replace, so a MessageFormat-style '' would show doubled.
                assertWithMessage(bundle + " doubles an apostrophe in " + key).that(value).doesNotContain("''");
            }
        }
    }

    @Test
    void theMoshHintAndTheTooltipsNameUtf8Everywhere() throws Exception {
        // UTF-8 is the fact these texts exist to state; a translation that lost it would mislead.
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : List.of("connEdit.encoding.moshUtf8Only", "connEdit.encoding.tooltip",
                    "settings.terminal.encoding.tooltip", "settings.terminal.encoding.pendingHint")) {
                assertWithMessage(bundle + " does not name UTF-8 in " + key)
                        .that(localized.getProperty(key)).contains("UTF-8");
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
