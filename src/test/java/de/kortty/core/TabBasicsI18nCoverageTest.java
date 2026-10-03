package de.kortty.core;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The keys of the tab basics (rename, close others, reopen closed tabs, the connection's tab color,
 * its frame and the credential environment colors, and the tab commands that follow) exist in every
 * bundled language. Grows with each tab feature.
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
            "dialog.closeTabs.content",
            "menu.file.reopenClosedTab",
            "menu.file.recentlyClosed",
            "menu.file.recentlyClosed.empty",
            "menu.file.recentlyClosed.window",
            "menu.file.recentlyClosed.clear",
            "tab.contextMenu.reopenClosed",
            "status.noClosedTab",
            "dialog.closeAllTabs.content",
            "connEdit.terminalBehavior",
            "connEdit.tabColor",
            "connEdit.tabColor.enable",
            "connEdit.tabColor.tooltip",
            "tab.tooltip.connection",
            "tab.tooltip.connectionColor",
            "settings.window.tabs.header",
            "settings.window.connectionColorBorder",
            "settings.window.connectionColorBorder.tooltip",
            "settings.window.connectionColorBorder.info",
            "tab.tooltip.environmentColor",
            "credential.environments.color",
            "credential.environments.color.enable",
            "credential.environments.color.info",
            "credential.environments.color.swatch");

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

    @Test
    void aClosedWindowsEntryListsItsTabs() throws Exception {
        for (String bundle : BUNDLES) {
            assertWithMessage(bundle + " drops the tab names from menu.file.recentlyClosed.window")
                    .that(loadBundle(bundle).getProperty("menu.file.recentlyClosed.window")).contains("{0}");
        }
    }

    @Test
    void closeAllTabsPointsToReopenClosedTabInsteadOfSayingItCannotBeUndone() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            String content = localized.getProperty("dialog.closeAllTabs.content");
            assertWithMessage(bundle + ": dialog.closeAllTabs.content should name the File menu")
                    .that(content).contains(localized.getProperty("menu.file"));
            assertWithMessage(bundle + ": dialog.closeAllTabs.content should name Reopen Closed Tab")
                    .that(content).contains(localized.getProperty("menu.file.reopenClosedTab"));
            assertWithMessage(bundle + ": the File menu and the tab menu call it the same")
                    .that(localized.getProperty("tab.contextMenu.reopenClosed"))
                    .isEqualTo(localized.getProperty("menu.file.reopenClosedTab"));
        }
    }

    @Test
    void theTabColorTooltipNamesTheConnectionTheColorAndItsCode() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            assertWithMessage(bundle + " drops the connection from tab.tooltip.connection")
                    .that(localized.getProperty("tab.tooltip.connection")).contains("{0}");
            String color = localized.getProperty("tab.tooltip.connectionColor");
            assertWithMessage(bundle + " drops the color name from tab.tooltip.connectionColor")
                    .that(color).contains("{0}");
            assertWithMessage(bundle + " drops the color code from tab.tooltip.connectionColor")
                    .that(color).contains("{1}");
        }
    }

    @Test
    void theEnvironmentColorTooltipNamesTheColorItsCodeAndTheEnvironment() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            String tooltip = localized.getProperty("tab.tooltip.environmentColor");
            for (String placeholder : List.of("{0}", "{1}", "{2}")) {
                assertWithMessage("%s drops %s from tab.tooltip.environmentColor", bundle, placeholder)
                        .that(tooltip).contains(placeholder);
            }
            String swatch = localized.getProperty("credential.environments.color.swatch");
            assertWithMessage("%s drops the color name from credential.environments.color.swatch", bundle)
                    .that(swatch).contains("{0}");
            assertWithMessage("%s drops the color code from credential.environments.color.swatch", bundle)
                    .that(swatch).contains("{1}");
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
