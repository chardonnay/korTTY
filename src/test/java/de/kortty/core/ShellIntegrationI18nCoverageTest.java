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
import org.testng.annotations.Test;

/**
 * Every text of shell integration, in the Edit menu, the terminal's right-click menu, Settings →
 * Terminal and the status line, exists in all eight bundled languages.
 */
class ShellIntegrationI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    private static final List<String> PREFIXES = List.of(
        "settings.terminal.shellIntegration.", "terminal.contextMenu.shellIntegration.", "terminal.shellIntegration.",
        "menu.edit.previousPrompt", "menu.edit.nextPrompt", "menu.edit.selectLastOutput", "menu.edit.copyLastOutput");

    private static final List<String> REQUIRED_KEYS = List.of(
        "menu.edit.previousPrompt",
        "menu.edit.nextPrompt",
        "menu.edit.selectLastOutput",
        "menu.edit.copyLastOutput",
        "settings.terminal.shellIntegration.header",
        "settings.terminal.shellIntegration.enabled",
        "settings.terminal.shellIntegration.enabled.tooltip",
        "settings.terminal.shellIntegration.setup",
        "settings.terminal.shellIntegration.setup.tooltip",
        "settings.terminal.shellIntegration.info",
        "terminal.contextMenu.shellIntegration.previousPrompt",
        "terminal.contextMenu.shellIntegration.nextPrompt",
        "terminal.contextMenu.shellIntegration.selectLastOutput",
        "terminal.contextMenu.shellIntegration.copyLastOutput",
        "terminal.contextMenu.shellIntegration.setup",
        "terminal.shellIntegration.status.noPrompts",
        "terminal.shellIntegration.status.fullScreen",
        "terminal.shellIntegration.status.disabled",
        "terminal.shellIntegration.status.lastOutputSelected",
        "terminal.shellIntegration.status.lastOutputSelectedTruncated",
        "terminal.shellIntegration.status.lastOutputCopied",
        "terminal.shellIntegration.status.lastOutputCopiedTruncated",
        "terminal.shellIntegration.status.noOutput",
        "terminal.shellIntegration.status.noFinishedCommand",
        "terminal.shellIntegration.status.lastOutputFullScreen",
        "terminal.shellIntegration.setup.title",
        "terminal.shellIntegration.setup.header",
        "terminal.shellIntegration.setup.intro",
        "terminal.shellIntegration.setup.bash",
        "terminal.shellIntegration.setup.zsh",
        "terminal.shellIntegration.setup.fish",
        "terminal.shellIntegration.setup.copy",
        "terminal.shellIntegration.setup.copied",
        "terminal.shellIntegration.setup.copiedInternal",
        "terminal.shellIntegration.setup.check",
        "terminal.shellIntegration.setup.local",
        "terminal.shellIntegration.setup.manual");

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
                assertWithMessage(bundle + " has a placeholder in " + key).that(value).doesNotContain("{0}");
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
    void theSetupEntryAndTheSettingsNoteNameTheProtocolOrTheGuide() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = load(bundle);
            assertWithMessage(bundle).that(localized.getProperty("settings.terminal.shellIntegration.enabled"))
                .contains("OSC 133");
            assertWithMessage(bundle).that(localized.getProperty("terminal.contextMenu.shellIntegration.setup"))
                .endsWith("…");
            for (String shell : List.of("bash", "zsh", "fish")) {
                assertWithMessage(bundle).that(localized.getProperty("settings.terminal.shellIntegration.info"))
                    .contains(shell);
            }
        }
    }

    @Test
    void theSetupWindowAndItsButtonCarryTheNameOfTheRightClickEntry() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = load(bundle);
            String entry = localized.getProperty("terminal.contextMenu.shellIntegration.setup");
            assertWithMessage(bundle + ": the Settings button opens the same window as the right-click entry")
                .that(localized.getProperty("settings.terminal.shellIntegration.setup")).isEqualTo(entry);
            assertWithMessage(bundle + ": the window is titled after the entry, without its ellipsis")
                .that(localized.getProperty("terminal.shellIntegration.setup.title"))
                .isEqualTo(entry.substring(0, entry.length() - 1));
            assertWithMessage(bundle + ": the settings note points to the button")
                .that(localized.getProperty("settings.terminal.shellIntegration.info")).contains(entry);
            assertWithMessage(bundle + ": the check names the entry that disappears once the marks arrive")
                .that(localized.getProperty("terminal.shellIntegration.setup.check")).contains(entry);
            assertWithMessage(bundle + ": the check names the entry that appears instead")
                .that(localized.getProperty("terminal.shellIntegration.setup.check"))
                .contains(localized.getProperty("terminal.contextMenu.shellIntegration.previousPrompt"));
        }
    }

    @Test
    void everyTranslationKeepsThePathsTheSnippetsGoTo() throws Exception {
        // Translators must not touch file names, commands and variables: they are typed as they are.
        List<List<String>> literals = List.of(
            List.of("terminal.shellIntegration.setup.bash", "bash 4.4", "/bin/bash", "~/.kortty-shell-integration.bash",
                "source ~/.kortty-shell-integration.bash", "~/.bashrc", "PROMPT_COMMAND", "PS1"),
            List.of("terminal.shellIntegration.setup.zsh", "zsh 5.1", "~/.kortty-shell-integration.zsh",
                "source ~/.kortty-shell-integration.zsh", "~/.zshrc", "POWERLEVEL9K_TERM_SHELL_INTEGRATION=true",
                "~/.p10k.zsh"),
            List.of("terminal.shellIntegration.setup.fish", "fish 3.1", "~/.config/fish/conf.d/kortty.fish"));
        for (String bundle : BUNDLES) {
            Properties localized = load(bundle);
            for (List<String> expected : literals) {
                String value = localized.getProperty(expected.get(0));
                for (String literal : expected.subList(1, expected.size())) {
                    assertWithMessage(bundle + " " + expected.get(0)).that(value).contains(literal);
                }
            }
        }
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
