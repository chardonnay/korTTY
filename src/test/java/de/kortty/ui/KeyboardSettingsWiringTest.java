package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Where the Keyboard page is hooked into the settings dialog and the main window, which no headless
 * test builds: the tab sits after Window, takes its actions from the menu bar of the window that
 * opened the dialog, a conflict keeps Save from closing the dialog, the overrides are stored only
 * when the page changed them, and saving re-applies the keymap in every open window.
 */
class KeyboardSettingsWiringTest {

    private static final Path UI = Path.of("src/main/java/de/kortty/ui");

    @Test
    void theKeyboardTabFollowsTheWindowTab() throws IOException {
        String dialog = source("SettingsDialog.java");

        assertThat(dialog).contains("keyboardTab = createKeyboardTab();");
        assertThat(dialog).contains("updatesTab, windowTab, keyboardTab, resourcesTab,");
        String create = region(dialog, "private Tab createKeyboardTab() {", "\n    }\n");
        assertThat(create).contains("Tab tab = new Tab(I18n.get(\"settings.tab.keyboard\"));");
        assertWithMessage("built on first selection, from the window's menu bar")
            .that(create).contains("LazyTabContent.defer(tab, () -> {\n"
                + "            KeyboardSettingsModel.Catalog catalog = keymapCatalogSource.get();");
        assertThat(create).contains("return KeyboardSettingsPage.unavailable();");
        assertThat(create).contains("KeymapOverrides.parse(\n"
            + "                globalSettings != null ? globalSettings.getKeyBindingOverrides() : List.of());");
        assertWithMessage("the conflict badge is the tab's graphic").that(create)
            .contains("tab.setGraphic(keyboardPage.badge());");
    }

    @Test
    void aConflictKeepsSaveFromClosingTheDialog() throws IOException {
        String dialog = source("SettingsDialog.java");

        assertThat(dialog).contains("if (getDialogPane().lookupButton(saveButtonType) instanceof Button saveButton) {\n"
            + "            saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {\n"
            + "                if (keyboardPage != null && !keyboardPage.canSave()) {\n"
            + "                    event.consume();\n"
            + "                    mainTabPane.getSelectionModel().select(keyboardTab);\n"
            + "                    keyboardPage.revealConflicts();");
    }

    @Test
    void theOverridesAreStoredOnlyWhenThePageChangedThem() throws IOException {
        String apply = region(source("SettingsDialog.java"), "private boolean applySettings() {", "\n    }\n");

        assertThat(apply).contains("if (keyboardPage != null && keyboardPage.hasChanges()) {\n"
            + "                globalSettings.setKeyBindingOverrides(keyboardPage.overrides().toEntries());\n"
            + "            }");
    }

    @Test
    void theMainWindowHandsItsMenuBarToThePageAndReappliesTheKeymapAfterSaving() throws IOException {
        String mainWindow = source("MainWindow.java");
        String showSettings = region(mainWindow, "private void showSettings() {", "\n    }\n");

        assertThat(showSettings).contains("dialog.setKeymapCatalogSource(this::keymapCatalog);");
        assertWithMessage("every open window, without a restart")
            .that(showSettings).contains("refreshKeymapInAllWindows();");
        String catalog = region(mainWindow, "@Nullable KeyboardSettingsModel.Catalog keymapCatalog() {", "\n    }\n");
        assertThat(catalog).contains("return KeymapSupport.catalog(menuBar.getMenus(), de.kortty.core.KeyChord.Os.current());");
        assertWithMessage("the same menu bar applyKeymap() reads its defaults from")
            .that(region(mainWindow, "void applyKeymap() {", "\n    }\n"))
            .contains("KeymapSupport.defaults(menuBar.getMenus(), os);");
    }

    private static String source(String file) throws IOException {
        // A Windows checkout has CRLF line endings; the markers above are written with \n.
        return Files.readString(UI.resolve(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from {@code startMarker} up to and including the next {@code endMarker}. */
    private static String region(String source, String startMarker, String endMarker) {
        int from = source.indexOf(startMarker);
        assertWithMessage("marker not found: " + startMarker).that(from).isAtLeast(0);
        int to = source.indexOf(endMarker, from + startMarker.length());
        assertWithMessage("end marker not found after " + startMarker).that(to).isAtLeast(0);
        return source.substring(from, to + endMarker.length());
    }
}
