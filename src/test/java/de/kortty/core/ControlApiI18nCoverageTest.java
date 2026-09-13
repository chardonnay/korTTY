package de.kortty.core;

import static com.google.common.truth.Truth.assertWithMessage;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

/** Every control-API key must exist, be non-blank and keep its placeholders in all bundled locales. */
class ControlApiI18nCoverageTest {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\d+\\}");

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
        // Settings > Terminal
        "settings.controlApi.header",
        "settings.controlApi.enabled",
        "settings.controlApi.enabled.tooltip",
        "settings.controlApi.enabled.info",
        // The live status line, rendered from ControlApiServer.status()
        "settings.controlApi.status.disabled",
        "settings.controlApi.status.blockedByPolicy",
        "settings.controlApi.status.running",
        "settings.controlApi.status.failed",
        // Runtime: the one desktop notification per run that makes a takeover impossible to miss
        "controlApi.notify.takeover.title",
        "controlApi.notify.takeover.body");

    @Test
    void allControlApiKeysExistInEveryBundledLocaleAndKeepPlaceholderCounts() throws Exception {
        Properties base = loadBundle("messages.properties");

        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                String baseValue = base.getProperty(key);
                String localizedValue = localized.getProperty(key);
                assertWithMessage("Base bundle is missing key " + key).that(baseValue).isNotNull();
                assertWithMessage(bundle + " is missing key " + key).that(localizedValue).isNotNull();
                assertWithMessage(bundle + " has different placeholder count for key " + key)
                    .that(countPlaceholders(localizedValue)).isEqualTo(countPlaceholders(baseValue));
                assertWithMessage(bundle + " has blank value for key " + key)
                    .that(!localizedValue.isBlank()).isTrue();
                // LanguageManager.getString substitutes {n} with a plain String.replace and never runs
                // MessageFormat, so a MessageFormat-style doubled apostrophe is shown literally.
                assertWithMessage(bundle + " uses a MessageFormat-style doubled apostrophe for key " + key
                    + " (korTTY substitutes placeholders with a plain replace): " + localizedValue)
                    .that(localizedValue).doesNotContain("''");
            }
        }
    }

    @Test
    void noControlApiKeyIsOrphanedInALocalisedBundle() throws Exception {
        Properties base = loadBundle("messages.properties");
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : localized.stringPropertyNames()) {
                if (!key.startsWith("settings.controlApi.") && !key.startsWith("controlApi.")) {
                    continue;
                }
                assertWithMessage(bundle + " carries " + key + ", which the base bundle does not have; "
                    + "a translated key nobody reads is dead weight that silently rots")
                    .that(base.getProperty(key)).isNotNull();
                assertWithMessage(bundle + " carries " + key + ", which no code reads")
                    .that(REQUIRED_KEYS).contains(key);
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

    private int countPlaceholders(String value) {
        int count = 0;
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
