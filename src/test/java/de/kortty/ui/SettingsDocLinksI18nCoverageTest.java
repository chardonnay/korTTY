package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static com.google.common.truth.Truth.assertWithMessage;

/** The manual links and the new section texts in Global Settings exist in every bundled locale. */
class SettingsDocLinksI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    private static final List<String> KEYS = List.of(
        "docLink.open",
        "docLink.open.tooltip",
        "settings.logging.scope.info",
        "settings.logging.scope.terminalLink",
        "settings.security.temporarySshKey.header",
        "settings.security.temporarySshKey.info");

    @Test
    void everyKeyExistsInEveryBundledLocale() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = new Properties();
            try (InputStream in = getClass().getResourceAsStream("/i18n/" + bundle)) {
                assertWithMessage("bundle %s", bundle).that(in).isNotNull();
                localized.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (String key : KEYS) {
                assertWithMessage("%s in %s", key, bundle).that(localized.getProperty(key)).isNotEmpty();
            }
            assertWithMessage("removed key in %s", bundle)
                .that(localized.getProperty("settings.telemetry.learnMore.tooltip")).isNull();
        }
    }
}
