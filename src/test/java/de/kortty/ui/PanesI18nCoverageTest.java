package de.kortty.ui;

import static com.google.common.truth.Truth.assertWithMessage;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.testng.annotations.Test;

/**
 * Every split-pane string exists, translated, in all eight bundles: the View → Panes menu and the
 * accessible name a screen reader reads for each pane of a split tab. The {0} and {1} of the
 * accessible name must survive translation, and an apostrophe is written once, because
 * LanguageManager fills the placeholders with String.replace rather than MessageFormat (a doubled
 * apostrophe would show up doubled).
 */
class PanesI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    /** "Pane {0} of {1}"; TerminalSplitPane.PANE_ACCESSIBLE_NAME_KEY. */
    private static final String ACCESSIBLE_NAME_KEY = "terminal.pane.accessibleName";

    private static List<String> keys() {
        List<String> keys = new ArrayList<>(PaneMenuSupport.KEYS);
        keys.add(ACCESSIBLE_NAME_KEY);
        return keys;
    }

    @Test
    void everyPaneKeyExistsInEveryBundledLocale() throws Exception {
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
    void theAccessibleNameKeepsBothPlaceholders() throws Exception {
        for (String bundle : BUNDLES) {
            String value = loadBundle(bundle).getProperty(ACCESSIBLE_NAME_KEY);
            assertWithMessage(bundle + " lost a placeholder of " + ACCESSIBLE_NAME_KEY)
                .that(value).containsMatch("\\{0\\}.*\\{1\\}");
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
    void theSplitPaneUsesTheCoveredAccessibleNameKey() throws Exception {
        String source = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java"), StandardCharsets.UTF_8);
        assertWithMessage("TerminalSplitPane.PANE_ACCESSIBLE_NAME_KEY")
            .that(source).contains("PANE_ACCESSIBLE_NAME_KEY = \"" + ACCESSIBLE_NAME_KEY + "\"");
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
