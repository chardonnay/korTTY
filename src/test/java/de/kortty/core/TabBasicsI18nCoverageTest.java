package de.kortty.core;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The keys of the tab basics (rename, and the tab commands that follow) exist in every bundled
 * language. Grows with each tab feature.
 */
class TabBasicsI18nCoverageTest {

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
            "menu.file.renameTab",
            "tab.contextMenu.rename",
            "dialog.renameTab.title",
            "dialog.renameTab.header",
            "dialog.renameTab.prompt",
            "menu.file.closeOtherTabs",
            "menu.file.closeTabsToRight",
            "tab.contextMenu.closeOthers",
            "tab.contextMenu.closeToRight",
            "dialog.closeTabs.title",
            "dialog.closeTabs.header",
            "dialog.closeTabs.content");

    @Test
    void everyTabBasicsKeyExistsInEveryBundledLocale() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
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
    void theRenameDialogNamesTheConnectionItFallsBackTo() throws Exception {
        for (String bundle : BUNDLES) {
            assertWithMessage(bundle + " drops the connection name placeholder from dialog.renameTab.header")
                    .that(loadBundle(bundle).getProperty("dialog.renameTab.header")).contains("{0}");
        }
    }

    @Test
    void theCloseTabsQuestionCountsTheTabsAndTheBusyOnes() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            assertWithMessage(bundle + " drops the tab count from dialog.closeTabs.header")
                    .that(localized.getProperty("dialog.closeTabs.header")).contains("{0}");
            assertWithMessage(bundle + " drops the busy-terminal count from dialog.closeTabs.content")
                    .that(localized.getProperty("dialog.closeTabs.content")).contains("{0}");
        }
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
