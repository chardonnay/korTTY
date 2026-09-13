package de.kortty.core;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertWithMessage;

/** Every coding-agent settings key must exist, be non-blank and keep its placeholders in all bundled locales. */
class CodingAgentI18nCoverageTest {

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
        "settings.codingAgent.header",
        "settings.codingAgent.detectionEnabled",
        "settings.codingAgent.detectionEnabled.tooltip",
        "settings.codingAgent.detectionEnabled.info");

    @Test
    void allCodingAgentKeysExistInEveryBundledLocaleAndKeepPlaceholderCounts() throws Exception {
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

    private int countPlaceholders(String value) {
        int count = 0;
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
