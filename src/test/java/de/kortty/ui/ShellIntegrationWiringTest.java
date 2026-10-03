package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * How shell integration is wired into the terminal tab, the window and the settings. The view, the
 * window and the dialog need a JavaFX stage, so the wiring is pinned against the source; the parts
 * themselves are tested in {@code CommandBlockStoreTest}, {@code PromptNavigatorTest},
 * {@code TerminalScrollSupportTest} and {@code PaneCommandMarksEmulatorTest}.
 */
class ShellIntegrationWiringTest {

    @Test
    void everyPaneGetsItsMarksAndReleasesThem() throws IOException {
        String view = source("TerminalView.java");
        assertThat(body(view, "private void setupWidgetEventHandlers(SithTermFxWidget widget) {"))
            .contains("shellIntegration.attach(widget);");
        assertWithMessage("the OSC 133 events of a pane go to its marks, on the emulator thread")
            .that(body(view, "private void onShellIntegrationEvent(SithTermFxWidget widget, ShellIntegrationEvent event) {"))
            .contains("shellIntegration.onEvent(widget, event);");
        assertThat(body(view, "private void releasePaneState(SithTermFxWidget widget) {"))
            .contains("shellIntegration.detach(widget);");
        assertThat(body(view, "public void cleanup() {")).contains("shellIntegration.detachAll();");
        assertWithMessage("the setting is read live")
            .that(body(view, "private static boolean isShellIntegrationEnabled() {"))
            .contains("return gs == null || gs.isShellIntegrationEnabled();");
    }

    @Test
    void theControllerKeepsTheMarksInStepAndPutsItsKeysFirst() throws IOException {
        String controller = source("ShellIntegrationController.java");
        String attach = body(controller, "void attach(@NotNull SithTermFxWidget widget) {");
        assertThat(attach).contains("buffer.addModelListener(listener);");
        assertThat(attach).contains("korttyWidget.setLeadingTerminalActions(promptActions(previousPromptKey, nextPromptKey,");
        assertThat(attach).contains("() -> canNavigate(widget), direction -> jump(widget, direction)));");
        String detach = body(controller, "void detach(@Nullable SithTermFxWidget widget) {");
        assertThat(detach).contains("marks.buffer().removeModelListener(listener);");
        assertThat(detach).contains("korttyWidget.setLeadingTerminalActions(List.of());");
        assertWithMessage("marks are recorded only while shell integration is on")
            .that(body(controller, "void onEvent(@NotNull SithTermFxWidget widget, @NotNull ShellIntegrationEvent event) {"))
            .contains("if (!PaneCommandMarks.isMark(event) || !isEnabled()) {");
        assertWithMessage("a jump reads the pane under the buffer lock, after applying the trims")
            .that(body(controller, "JumpResult jump(@NotNull SithTermFxWidget widget, @NotNull Direction direction) {"))
            .contains("buffer.lock();\n        try {\n            if (buffer.isUsingAlternateBuffer()) {\n"
                + "                return JumpResult.FULL_SCREEN;\n            }\n            marks.syncTrims();");
    }

    @Test
    void theEditMenuOffersBothJumpsInTerminalTabs() throws IOException {
        String window = source("MainWindow.java");
        String edit = body(window, "private Menu createEditMenu(MenuBarTarget target) {");
        assertThat(edit).contains("ActionIds.tag(new MenuItem(I18n.get(\"menu.edit.previousPrompt\")),");
        assertThat(edit).contains("ActionIds.tag(new MenuItem(I18n.get(\"menu.edit.nextPrompt\")), \"menu.edit.nextPrompt\");");
        assertThat(edit).contains("previousPrompt.setOnAction(e -> jumpToPromptInCurrentTab(PromptNavigator.Direction.PREVIOUS));");
        assertThat(edit).contains("nextPrompt.setOnAction(e -> jumpToPromptInCurrentTab(PromptNavigator.Direction.NEXT));");
        assertThat(edit).contains("new SeparatorMenuItem(), previousPrompt, nextPrompt);");
        assertWithMessage("greyed out outside terminal tabs, like Quick Select")
            .that(body(window, "private void updateEditMenuItemsForSelection() {"))
            .contains("for (MenuItem item : promptNavigationMenuItems) {\n            item.setDisable(!(currentTab instanceof TerminalTab));");
        String jump = body(window, "private void jumpToPromptInCurrentTab(PromptNavigator.Direction direction) {");
        assertThat(jump).contains("case NO_PROMPTS -> I18n.get(\"terminal.shellIntegration.status.noPrompts\");");
        assertThat(jump).contains("case FULL_SCREEN -> I18n.get(\"terminal.shellIntegration.status.fullScreen\");");
        assertThat(jump).contains("case DISABLED -> I18n.get(\"terminal.shellIntegration.status.disabled\");");
    }

    @Test
    void theContextMenuOffersTheJumpsOrTheSetup() throws IOException {
        String view = source("TerminalView.java");
        String entries = body(view, "private List<javafx.scene.control.MenuItem> buildShellIntegrationMenuItems(SithTermFxWidget widget) {");
        assertThat(entries).contains("previous.setOnAction(e -> shellIntegration.jump(widget, PromptNavigator.Direction.PREVIOUS));");
        assertThat(entries).contains("next.setOnAction(e -> shellIntegration.jump(widget, PromptNavigator.Direction.NEXT));");
        assertThat(entries).contains("setup.setOnAction(e -> openShellIntegrationGuide());");
        assertThat(view).contains("List<javafx.scene.control.MenuItem> shellIntegrationItems = buildShellIntegrationMenuItems(widget);");
    }

    @Test
    void theSettingIsShownSavedAndReported() throws IOException {
        String dialog = source("SettingsDialog.java");
        assertThat(dialog).contains("shellIntegrationCheck.setSelected(globalSettings == null || globalSettings.isShellIntegrationEnabled());");
        assertThat(dialog).contains("terminalGrid.add(shellIntegrationCheck, 0, terminalRow++, 2, 1);");
        assertThat(dialog).contains("globalSettings.setShellIntegrationEnabled(shellIntegrationCheck.isSelected());");
        assertThat(dialog).contains(
            "tracked.add(new TrackedSetting(\"terminal\", \"shell_integration\", gs::isShellIntegrationEnabled, true));");
    }

    private static String source(String file) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(Path.of("src/main/java/de/kortty/ui", file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text of the method whose declaration is {@code signature}, up to its closing brace at that indent. */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("missing " + signature).that(start).isAtLeast(0);
        int lineStart = source.lastIndexOf('\n', start) + 1;
        String indent = source.substring(lineStart, start);
        int end = source.indexOf("\n" + indent + "}\n", start);
        assertWithMessage("no end of " + signature).that(end).isGreaterThan(start);
        return source.substring(start, end);
    }
}
