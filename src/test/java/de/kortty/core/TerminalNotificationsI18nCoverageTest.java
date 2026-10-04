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
 * tooltips themselves, and of programs writing the clipboard (OSC 52), exists in all eight bundled
 * languages with the placeholders of the English text.
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

    private static final List<String> PREFIXES = List.of("settings.terminal.notify.", "terminal.notify.",
        "settings.terminal.osc52.", "terminal.osc52.");

    private static final List<String> REQUIRED_KEYS = List.of(
        "settings.terminal.notify.header",
        "settings.terminal.notify.bell",
        "settings.terminal.notify.bell.tooltip",
        "settings.terminal.notify.commandFinished",
        "settings.terminal.notify.commandFinished.tooltip",
        "settings.terminal.notify.commandFinishedSeconds",
        "settings.terminal.notify.commandFinishedSeconds.unit",
        "settings.terminal.notify.commandFinishedSeconds.tooltip",
        "settings.terminal.notify.remote",
        "settings.terminal.notify.remote.tooltip",
        "settings.terminal.notify.info",
        "terminal.notify.bell.body",
        "terminal.notify.bell.tooltip",
        "terminal.notify.commandFinished.succeeded",
        "terminal.notify.commandFinished.failed",
        "terminal.notify.commandFinished.noStatus",
        "terminal.notify.remote.tooltip",
        "settings.terminal.osc52.enabled",
        "settings.terminal.osc52.enabled.tooltip",
        "terminal.osc52.copied",
        "terminal.osc52.blocked",
        "terminal.osc52.rejected");

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

    @Test
    void aFinishedCommandIsDescribedByItsExitStatusAndRuntimeOnly() throws Exception {
        // {0} the exit status, {1} the runtime; there is no slot for the command line.
        Properties english = load("messages.properties");
        assertThat(placeholders(english.getProperty("terminal.notify.commandFinished.succeeded")))
            .containsExactly("{0}", "{1}");
        assertThat(placeholders(english.getProperty("terminal.notify.commandFinished.failed")))
            .containsExactly("{0}", "{1}");
        assertThat(placeholders(english.getProperty("terminal.notify.commandFinished.noStatus")))
            .containsExactly("{0}");
    }

    @Test
    void aProgramsNotificationFillsTheTooltipThroughOnePlaceholder() throws Exception {
        // {0} is the program's cleaned text; LanguageManager replaces placeholders one after another,
        // so a second one could be filled from inside that text.
        assertThat(placeholders(load("messages.properties").getProperty("terminal.notify.remote.tooltip")))
            .containsExactly("{0}");
        for (String bundle : BUNDLES) {
            assertWithMessage(bundle + " names the sequences in the setting")
                .that(load(bundle).getProperty("settings.terminal.notify.remote")).contains("OSC 9");
        }
    }

    @Test
    void theTabsNameIsTheLastPlaceholderOfEveryClipboardMessage() throws Exception {
        // The tab's name can come from the server (OSC 0/2); LanguageManager replaces placeholders one
        // after another, so a placeholder after the name could be filled from inside the name.
        Properties english = load("messages.properties");
        assertThat(placeholders(english.getProperty("terminal.osc52.copied"))).containsExactly("{0}", "{1}");
        assertThat(placeholders(english.getProperty("terminal.osc52.blocked"))).containsExactly("{0}");
        assertThat(placeholders(english.getProperty("terminal.osc52.rejected"))).containsExactly("{0}");
        for (String bundle : BUNDLES) {
            assertWithMessage(bundle + " names the sequence in the setting")
                .that(load(bundle).getProperty("settings.terminal.osc52.enabled")).contains("OSC 52");
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
