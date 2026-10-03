package de.kortty.core;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

/**
 * Every text of the terminal notifications, in Settings → Terminal and in the notifications and tab
 * tooltips themselves, exists in all eight bundled languages with the placeholders of the English text.
 */
class TerminalNotificationsI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    private static final List<String> PREFIXES = List.of("settings.terminal.notify.", "terminal.notify.");

    private static final List<String> REQUIRED_KEYS = List.of(
        "settings.terminal.notify.header",
        "settings.terminal.notify.bell",
        "settings.terminal.notify.bell.tooltip",
        "settings.terminal.notify.info",
        "terminal.notify.bell.body",
        "terminal.notify.bell.tooltip");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    @Test
    void everyKeyExistsInEveryBundle() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = load(bundle);
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
    void theListAboveNamesEveryKeyOfTheFeature() throws Exception {
        // A new text under one of the prefixes must be added here, so its translations are checked too.
        Set<String> declared = new TreeSet<>();
        for (String key : load("messages.properties").stringPropertyNames()) {
            if (PREFIXES.stream().anyMatch(key::startsWith)) {
                declared.add(key);
            }
        }
        assertThat(declared).containsExactlyElementsIn(REQUIRED_KEYS);
    }

    @Test
    void everyTranslationKeepsThePlaceholdersOfTheEnglishText() throws Exception {
        Properties english = load("messages.properties");
        for (String bundle : BUNDLES) {
            Properties localized = load(bundle);
            for (String key : REQUIRED_KEYS) {
                assertWithMessage(bundle + " changes the placeholders of " + key)
                    .that(placeholders(localized.getProperty(key)))
                    .isEqualTo(placeholders(english.getProperty(key)));
            }
        }
    }

    @Test
    void theSettingsNoteShowsTheMarkTheTabGets() throws Exception {
        for (String bundle : BUNDLES) {
            assertWithMessage(bundle).that(load(bundle).getProperty("settings.terminal.notify.info")).contains("🔔");
        }
    }

    private static Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private Properties load(String fileName) throws Exception {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("i18n/" + fileName)) {
            assertWithMessage("Missing i18n bundle " + fileName).that(inputStream).isNotNull();
            Properties properties = new Properties();
            properties.load(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            return properties;
        }
    }
}
