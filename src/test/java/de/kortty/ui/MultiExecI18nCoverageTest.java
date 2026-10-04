package de.kortty.ui;

import static com.google.common.truth.Truth.assertWithMessage;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.testng.annotations.Test;

/**
 * Every multi-exec string exists, translated, in all eight bundles: View → Multi-exec, the pane,
 * tab and dashboard toggles, the pane badges, the tab marker and the status-bar chip. The
 * placeholders must survive translation, and an apostrophe is written once, because LanguageManager
 * fills the placeholders with String.replace rather than MessageFormat (a doubled apostrophe would
 * show up doubled).
 */
class MultiExecI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    /** "Multi-exec"; TerminalSplitPane.MULTI_EXEC_BADGE_KEY. */
    private static final String MULTI_EXEC_BADGE_KEY = "terminal.pane.multiExecBadge";

    /** "Broadcast"; TerminalSplitPane.BROADCAST_BADGE_KEY. */
    private static final String BROADCAST_BADGE_KEY = "terminal.pane.broadcastBadge";

    /** The placeholders each key must keep, in order. */
    private static final Map<String, String> PLACEHOLDERS = Map.of(
        MultiExecMarkers.TAB_MULTI_EXEC_KEY, "\\{0\\}.*\\{1\\}",
        MultiExecMarkers.TAB_BROADCAST_KEY, "\\{0\\}",
        MultiExecMarkers.STATUS_ACTIVE_KEY, "\\{0\\}.*\\{1\\}.*\\{2\\}",
        MultiExecMarkers.STATUS_SKIPPED_KEY, "\\{0\\}");

    private static List<String> keys() {
        List<String> keys = new ArrayList<>(MultiExecMenuSupport.KEYS);
        keys.addAll(MultiExecMarkers.KEYS);
        keys.add(MULTI_EXEC_BADGE_KEY);
        keys.add(BROADCAST_BADGE_KEY);
        return keys;
    }

    @Test
    void everyMultiExecKeyExistsInEveryBundledLocale() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : keys()) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for key " + key).that(value.isBlank()).isFalse();
                assertWithMessage(bundle + " doubles an apostrophe in " + key).that(value).doesNotContain("''");
            }
        }
    }

    @Test
    void thePlaceholdersSurviveTranslation() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (Map.Entry<String, String> placeholders : PLACEHOLDERS.entrySet()) {
                assertWithMessage(bundle + " lost a placeholder of " + placeholders.getKey())
                    .that(localized.getProperty(placeholders.getKey())).containsMatch(placeholders.getValue());
            }
            for (String key : keys()) {
                if (!PLACEHOLDERS.containsKey(key)) {
                    assertWithMessage(bundle + " has a placeholder nobody fills in " + key)
                        .that(localized.getProperty(key)).doesNotContain("{0}");
                }
            }
        }
    }

    @Test
    void translationsAreNotLeftInEnglish() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (String bundle : BUNDLES.subList(1, BUNDLES.size())) {
            Properties localized = loadBundle(bundle);
            for (String key : keys()) {
                assertWithMessage(bundle + " still has the English text for " + key)
                    .that(localized.getProperty(key)).isNotEqualTo(english.getProperty(key));
            }
        }
    }

    @Test
    void theCodeUsesTheCoveredKeys() throws Exception {
        String splitPane = Files.readString(
            Path.of("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java"), StandardCharsets.UTF_8);
        assertWithMessage("TerminalSplitPane.MULTI_EXEC_BADGE_KEY")
            .that(splitPane).contains("MULTI_EXEC_BADGE_KEY = \"" + MULTI_EXEC_BADGE_KEY + "\"");
        assertWithMessage("TerminalSplitPane.BROADCAST_BADGE_KEY")
            .that(splitPane).contains("BROADCAST_BADGE_KEY = \"" + BROADCAST_BADGE_KEY + "\"");
        assertWithMessage("every key of the menu is a menu.view.multiExec key")
            .that(MultiExecMenuSupport.KEYS.stream().allMatch(key -> key.startsWith("menu.view.multiExec"))).isTrue();
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
