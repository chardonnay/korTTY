package de.kortty.ui;

import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.AiChatTerminalActions;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

/**
 * Every string of the AI chat's terminal actions exists, translated, in all eight bundles: the Insert and Run
 * buttons of a code block with their tooltips, verdicts, Run confirmation and status lines, the setting that
 * chooses them, the AI note of the paste confirmation, the live-streaming status, and "Summarize Recent Output"
 * with its no-output message. Placeholders survive translation, and an apostrophe is written once, because
 * LanguageManager fills {0} with String.replace rather than MessageFormat.
 */
class AiI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    /** Families whose every key in the English bundle must exist in every other bundle. */
    private static final List<String> PREFIXES = List.of(
        "ai.result.terminal.",
        "settings.ai.chatTerminalActions");

    private static final List<String> SINGLE_KEYS = List.of(
        "ai.result.streaming",
        "ai.recentOutput.none",
        "terminal.contextMenu.ai.summarizeRecent",
        "terminal.paste.confirm.source.ai");

    /** The placeholders a key must keep; every other key of the families has none. */
    private static final Map<String, Set<String>> PLACEHOLDERS = Map.ofEntries(
        Map.entry("ai.result.terminal.paneName", Set.of("{0}", "{1}")),
        Map.entry("ai.result.terminal.promptUnknown", Set.of("{0}")),
        Map.entry("ai.result.terminal.verdict.ok", Set.of("{0}")),
        Map.entry("ai.result.terminal.verdict.paneBusy", Set.of("{0}")),
        Map.entry("ai.result.terminal.verdict.agentBusy", Set.of("{0}")),
        Map.entry("ai.result.terminal.verdict.codingAgentBlocked", Set.of("{0}")),
        Map.entry("ai.result.terminal.verdict.foreignSessionConfirm", Set.of("{0}")),
        Map.entry("ai.result.terminal.verdict.broadcastOrMultiExec", Set.of("{0}")),
        Map.entry("ai.result.terminal.verdict.runNotAtPrompt", Set.of("{0}")),
        Map.entry("ai.result.terminal.verdict.disconnected", Set.of("{0}")),
        Map.entry("ai.result.terminal.insert.tooltip", Set.of("{0}")),
        Map.entry("ai.result.terminal.run.tooltip", Set.of("{0}")),
        Map.entry("ai.result.terminal.run.confirm.header", Set.of("{0}")),
        Map.entry("ai.result.terminal.status.inserted", Set.of("{0}")),
        Map.entry("ai.result.terminal.status.notInserted", Set.of("{0}")),
        Map.entry("ai.result.terminal.status.ran", Set.of("{0}")),
        Map.entry("ai.result.terminal.status.failed", Set.of("{0}")));

    /** "Terminal" is the same word in German, Spanish, Portuguese, French, Croatian and Dutch. */
    private static final Set<String> MAY_EQUAL_ENGLISH = Set.of("ai.result.terminal.unnamedTab");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d}");

    private List<String> keys() throws Exception {
        Properties english = loadBundle("messages.properties");
        Set<String> keys = new TreeSet<>(SINGLE_KEYS);
        for (String name : english.stringPropertyNames()) {
            for (String prefix : PREFIXES) {
                if (name.startsWith(prefix)) {
                    keys.add(name);
                }
            }
        }
        return new ArrayList<>(keys);
    }

    @Test
    void theFamiliesAreNotEmpty() throws Exception {
        List<String> keys = keys();
        assertWithMessage("terminal action keys").that(keys.size()).isAtLeast(36);
        assertWithMessage("terminal action keys").that(keys).containsAtLeastElementsIn(PLACEHOLDERS.keySet());
    }

    @Test
    void everyKeyExistsInEveryBundledLocale() throws Exception {
        List<String> keys = keys();
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : keys) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for key " + key).that(value.isBlank()).isFalse();
                assertWithMessage(bundle + " doubles an apostrophe in " + key).that(value).doesNotContain("''");
            }
        }
    }

    @Test
    void everyVerdictAndEverySettingHasItsText() throws Exception {
        List<String> keys = keys();
        for (AiCodeBlockTerminalAction.Verdict verdict : AiCodeBlockTerminalAction.Verdict.values()) {
            assertWithMessage("text of " + verdict).that(keys).contains(verdict.messageKey());
        }
        for (AiChatTerminalActions value : AiChatTerminalActions.values()) {
            assertWithMessage("label of " + value).that(keys).contains(SettingsDialog.aiChatTerminalActionsKey(value));
        }
    }

    @Test
    void placeholdersSurviveTranslation() throws Exception {
        List<String> keys = keys();
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : keys) {
                Set<String> expected = PLACEHOLDERS.getOrDefault(key, Set.of());
                assertWithMessage(bundle + " placeholders of " + key)
                    .that(placeholders(localized.getProperty(key))).isEqualTo(expected);
            }
        }
    }

    @Test
    void translationsAreNotLeftInEnglish() throws Exception {
        Properties english = loadBundle("messages.properties");
        List<String> keys = keys();
        for (String bundle : BUNDLES.subList(1, BUNDLES.size())) {
            Properties localized = loadBundle(bundle);
            for (String key : keys) {
                if (MAY_EQUAL_ENGLISH.contains(key)) {
                    continue;
                }
                assertWithMessage(bundle + " still has the English text for " + key)
                    .that(localized.getProperty(key)).isNotEqualTo(english.getProperty(key));
            }
        }
    }

    private static Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value != null ? value : "");
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
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
