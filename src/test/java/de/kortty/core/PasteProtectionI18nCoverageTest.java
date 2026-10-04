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
 * Every text of the paste protection settings, of the paste confirmation, of a paced paste and of the
 * connection editor's per-connection paste protection exists in all eight bundled languages, with the
 * same placeholders as the English text.
 */
class PasteProtectionI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    private static final List<String> PREFIXES = List.of("settings.terminal.paste.", "terminal.paste.",
        "connEdit.paste.");

    private static final List<String> REQUIRED_KEYS = List.of(
        "settings.terminal.paste.header",
        "settings.terminal.paste.warningMode",
        "settings.terminal.paste.warningMode.tooltip",
        "settings.terminal.paste.mode.off",
        "settings.terminal.paste.mode.unlessBracketed",
        "settings.terminal.paste.mode.always",
        "settings.terminal.paste.largeWarning",
        "settings.terminal.paste.largeWarning.unit",
        "settings.terminal.paste.largeWarning.tooltip",
        "settings.terminal.paste.lineDelay",
        "settings.terminal.paste.lineDelay.unit",
        "settings.terminal.paste.lineDelay.tooltip",
        "settings.terminal.paste.info",
        "terminal.paste.confirm.title",
        "terminal.paste.confirm.header",
        "terminal.paste.confirm.header.unnamed",
        "terminal.paste.confirm.summary",
        "terminal.paste.confirm.size.bytes",
        "terminal.paste.confirm.size.kib",
        "terminal.paste.confirm.size.mib",
        "terminal.paste.confirm.reason.multiLine",
        "terminal.paste.confirm.reason.trailingLineBreak",
        "terminal.paste.confirm.reason.controlCharacters",
        "terminal.paste.confirm.reason.bidiCharacters",
        "terminal.paste.confirm.reason.large",
        "terminal.paste.confirm.bracketed",
        "terminal.paste.confirm.notBracketed",
        "terminal.paste.confirm.source.selection",
        "terminal.paste.confirm.source.drop",
        "terminal.paste.confirm.markersRemoved",
        "terminal.paste.confirm.broadcast",
        "terminal.paste.confirm.preview",
        "terminal.paste.confirm.previewLegend",
        "terminal.paste.confirm.previewTruncated",
        "terminal.paste.confirm.settingsHint",
        "terminal.paste.confirm.settingsHint.connection",
        "terminal.paste.confirm.paste",
        "terminal.paste.pacing.progress",
        // The connection editor's per-connection paste protection.
        "connEdit.paste.warningMode",
        "connEdit.paste.warningMode.tooltip",
        "connEdit.paste.warningMode.default",
        "connEdit.paste.lineDelay",
        "connEdit.paste.lineDelay.override",
        "connEdit.paste.lineDelay.unit",
        "connEdit.paste.lineDelay.tooltip",
        "connEdit.paste.teamwork",
        // Shared with other dialogs and the terminal's context menu.
        "dialog.cancel",
        "terminal.contextMenu.copy");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    @Test
    void everyPasteProtectionKeyExistsInEveryBundle() throws Exception {
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
        assertThat(declared).containsExactlyElementsIn(
            REQUIRED_KEYS.stream().filter(key -> PREFIXES.stream().anyMatch(key::startsWith)).toList());
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
    void theTextsThatNameAValueHaveTheirPlaceholder() throws Exception {
        Properties english = load("messages.properties");
        assertThat(placeholders(english.getProperty("terminal.paste.confirm.header"))).containsExactly("{0}");
        assertThat(placeholders(english.getProperty("terminal.paste.confirm.summary"))).containsExactly("{0}", "{1}");
        assertThat(placeholders(english.getProperty("terminal.paste.confirm.reason.large"))).containsExactly("{0}");
        assertThat(placeholders(english.getProperty("terminal.paste.confirm.reason.controlCharacters")))
            .containsExactly("{0}");
        assertThat(placeholders(english.getProperty("terminal.paste.confirm.reason.bidiCharacters")))
            .containsExactly("{0}");
        assertThat(placeholders(english.getProperty("terminal.paste.pacing.progress"))).containsExactly("{0}", "{1}");
        assertThat(placeholders(english.getProperty("connEdit.paste.warningMode.default"))).containsExactly("{0}");
        assertThat(placeholders(english.getProperty("connEdit.paste.lineDelay.override"))).containsExactly("{0}");
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
