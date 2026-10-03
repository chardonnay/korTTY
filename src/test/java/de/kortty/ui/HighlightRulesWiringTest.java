package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Where the rule-set editor is hooked in, which no headless test can build: the three ways to open it
 * (View → Highlighting, the pane's context menu, Settings → Terminal), saving through the settings
 * manager before the highlighting service reloads, and OK storing nothing that was not changed.
 */
class HighlightRulesWiringTest {

    private static final Path UI = Path.of("src/main/java/de/kortty/ui");

    @Test
    void theViewMenuHasAHarvestedManageItemThatOpensTheEditor() throws IOException {
        String window = source("MainWindow.java");
        String menu = region(window, "private Menu createHighlightingMenu(MenuBarTarget target) {", "\n    }\n");
        String open = region(window, "private void openHighlightRulesEditor() {", "\n    }\n");

        assertThat(menu).contains("HighlightMenuSupport.createManageItem(this::openHighlightRulesEditor)");
        assertThat(menu).contains("ActionIds.tag(manage, HighlightMenuSupport.MANAGE_KEY);");
        assertThat(menu).contains("HighlightMenuSupport.createViewMenu(toggle, manage,");
        assertThat(open).contains("HighlightRulesDialog.showAndSave(stage, app, activeHighlightMenuState().shownSetId());");
        assertWithMessage("the toggle must show what the focused pane shows after a save").that(open)
            .contains("syncHighlightingToggleItems();");
    }

    @Test
    void thePaneMenuOpensTheEditorOnThePanesSet() throws IOException {
        String view = source("TerminalView.java");
        String menu = region(view, "private javafx.scene.control.Menu buildPaneHighlightMenu(SithTermFxWidget widget) {",
            "\n    }\n");
        String open = region(view, "private void openHighlightRulesEditor(SithTermFxWidget widget) {", "\n    }\n");

        assertWithMessage("the editor opens after the context menu has closed").that(menu)
            .contains("() -> Platform.runLater(() -> openHighlightRulesEditor(widget))");
        assertThat(open).contains(
            "HighlightRulesDialog.showAndSave(owner, KorTTYApplication.getInstance(), getEffectiveHighlightSetId(widget));");
    }

    @Test
    void settingsTerminalOpensTheEditorAndRebuildsTheDefaultSetDropdown() throws IOException {
        String dialog = source("SettingsDialog.java");
        String edit = region(dialog, "private void editHighlightRules() {", "\n    }\n");

        assertThat(dialog).contains("editHighlightRulesButton.setOnAction(event -> editHighlightRules());");
        assertWithMessage("rule sets can be prepared while the master switch is off").that(dialog)
            .doesNotContain("editHighlightRulesButton.disableProperty()");
        assertThat(edit).contains("HighlightRulesDialog.showAndSave(owner, globalSettings, manager, service, selection)");
        assertThat(edit).contains("HighlightSettingsSupport.selectionAfterRuleEdit(selection, idsBefore, idsAfter);");
        assertThat(edit).contains("defaultHighlightSetCombo.getItems().setAll(choices);");
    }

    @Test
    void savingWritesTheSettingsFileBeforeThePanesReload() throws IOException {
        String editor = source("HighlightRulesDialog.java");
        String save = region(editor, "static void saveAndReload(", "\n    }\n");

        int written = save.indexOf("manager.save();");
        int reloaded = save.indexOf("service.reload(settings);");
        assertThat(written).isAtLeast(0);
        assertThat(reloaded).isGreaterThan(written);
        assertWithMessage("a failing save or reload must not break the caller").that(save).contains("catch (Exception e)");
        assertThat(save).contains("catch (RuntimeException e)");
    }

    @Test
    void okStoresOnlyChangesAndCancelStoresNothing() throws IOException {
        String show = region(source("HighlightRulesDialog.java"), "public static boolean show(", "\n    }\n");

        int confirmed = show.indexOf("if (!confirmed || !editor.model.isModified()) {");
        int applied = show.indexOf("editor.model.applyTo(settings);");
        assertThat(confirmed).isAtLeast(0);
        assertThat(applied).isGreaterThan(confirmed);
    }

    @Test
    void okIsDisabledWhileAnythingIsInvalid() throws IOException {
        String editor = source("HighlightRulesDialog.java");

        assertThat(editor).contains("dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(invalid);");
        String problems = region(editor, "private void updateProblems() {", "\n    }\n");
        assertThat(problems).contains("model.firstProblem()");
        assertThat(problems).contains("invalid.set(!problem.isEmpty());");
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
