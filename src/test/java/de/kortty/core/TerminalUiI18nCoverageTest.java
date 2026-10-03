package de.kortty.core;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The tab context menu and the command-timestamp gutter speak the UI language.
 *
 * <p>Both used to show fixed texts: the tab menu offered German "Duplizieren" and "Keine Gruppe"
 * in every language, the gutter English "Elapsed" and a German-ordered date.
 */
class TerminalUiI18nCoverageTest {

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
            "tab.contextMenu.duplicate",
            "tab.contextMenu.noGroup",
            "terminal.timestamps.dateShortPattern",
            "terminal.timestamps.elapsed",
            "terminal.timestamps.duration.seconds",
            "terminal.timestamps.duration.minutes",
            "terminal.timestamps.duration.hours");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");
    private static final Path MAIN_WINDOW = Path.of("src/main/java/de/kortty/ui/MainWindow.java");

    @Test
    void everyNewKeyExistsInEveryBundledLocale() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for " + key)
                        .that(value.isBlank()).isFalse();
            }
        }
    }

    @Test
    void everyPlaceholderSurvivesTranslation() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                Matcher matcher = PLACEHOLDER.matcher(english.getProperty(key));
                while (matcher.find()) {
                    // A dropped {1} silently loses the minutes from "1 min 5 sec".
                    assertWithMessage(bundle + " lost " + matcher.group() + " from " + key)
                            .that(localized.getProperty(key)).contains(matcher.group());
                }
            }
        }
    }

    @Test
    void germanKeepsTheWordsItAlwaysShowed() throws Exception {
        Properties german = loadBundle("messages_de.properties");
        assertWithMessage("the German tab menu must not change wording with this fix")
                .that(german.getProperty("tab.contextMenu.duplicate")).isEqualTo("Duplizieren");
        assertWithMessage("the German tab menu must not change wording with this fix")
                .that(german.getProperty("tab.contextMenu.noGroup")).isEqualTo("Keine Gruppe");
    }

    @Test
    void theTabContextMenuNoLongerHardCodesGermanLabels() throws Exception {
        String source = Files.readString(MAIN_WINDOW, StandardCharsets.UTF_8);
        assertWithMessage("MainWindow.setupTabContextMenu must use tab.contextMenu.duplicate")
                .that(source).doesNotContain("new MenuItem(\"Duplizieren\")");
        assertWithMessage("MainWindow.setupTabContextMenu must use tab.contextMenu.noGroup")
                .that(source).doesNotContain("new MenuItem(\"Keine Gruppe\")");
        assertWithMessage("the tab context menu must read its labels from the bundle")
                .that(source).contains("I18n.get(\"tab.contextMenu.duplicate\")");
        assertWithMessage("the tab context menu must read its labels from the bundle")
                .that(source).contains("I18n.get(\"tab.contextMenu.noGroup\")");
    }

    private Properties loadBundle(String fileName) throws Exception {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("i18n/" + fileName)) {
            assertWithMessage("Missing i18n bundle " + fileName).that(inputStream).isNotNull();
            Properties properties = new Properties();
            properties.load(new java.io.InputStreamReader(inputStream, StandardCharsets.UTF_8));
            return properties;
        }
    }
}
