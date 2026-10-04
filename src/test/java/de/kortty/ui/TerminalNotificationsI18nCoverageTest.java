package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.TerminalNotificationPolicy.AiRunEvent;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

/**
 * Every terminal-notification string exists in all eight bundles: the notification and tab-tooltip texts
 * ({@code terminal.notify.*}, including the AI agent and AI Swarm run texts) and the settings on
 * Terminal → Notifications ({@code settings.terminal.notify.*}). A translation keeps exactly the
 * placeholders of the English text, and an apostrophe is written once, because LanguageManager fills
 * the placeholders with String.replace rather than MessageFormat.
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

    private static final List<String> PREFIXES = List.of("terminal.notify.", "settings.terminal.notify.");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d}");

    private List<String> keys() throws Exception {
        Properties english = loadBundle("messages.properties");
        Set<String> keys = new TreeSet<>();
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
    void theAiRunKeysArePartOfTheFamily() throws Exception {
        assertThat(keys()).containsAtLeast("settings.terminal.notify.aiRun", "settings.terminal.notify.aiRun.tooltip",
            "terminal.notify.aiRun.finished", "terminal.notify.aiRun.failed", "terminal.notify.aiRun.needsApproval",
            "terminal.notify.aiRun.needsPassword");
    }

    @Test
    void everyAiRunEventHasItsOwnText() throws Exception {
        List<String> keys = keys();
        Set<String> used = new TreeSet<>();
        for (AiRunEvent event : AiRunEvent.values()) {
            String key = TerminalAttentionNotifier.aiRunText(event, (k, args) -> k);
            assertWithMessage("text key of " + event).that(keys).contains(key);
            used.add(key);
        }
        assertWithMessage("each event says something different").that(used).hasSize(AiRunEvent.values().length);
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
    void placeholdersSurviveTranslation() throws Exception {
        Properties english = loadBundle("messages.properties");
        List<String> keys = keys();
        for (String bundle : BUNDLES.subList(1, BUNDLES.size())) {
            Properties localized = loadBundle(bundle);
            for (String key : keys) {
                assertWithMessage(bundle + " placeholders of " + key)
                    .that(placeholders(localized.getProperty(key)))
                    .isEqualTo(placeholders(english.getProperty(key)));
            }
        }
    }

    @Test
    void theAiRunTextsAreTranslated() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (String bundle : BUNDLES.subList(1, BUNDLES.size())) {
            Properties localized = loadBundle(bundle);
            for (String key : keys()) {
                if (key.contains(".aiRun")) {
                    assertWithMessage(bundle + " still has the English text for " + key)
                        .that(localized.getProperty(key)).isNotEqualTo(english.getProperty(key));
                }
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
