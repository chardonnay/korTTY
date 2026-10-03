package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
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
        assertThat(edit).contains("new SeparatorMenuItem(), previousPrompt, nextPrompt, selectLastOutput, copyLastOutput);");
        assertThat(edit).contains(
            "shellIntegrationMenuItems.addAll(List.of(previousPrompt, nextPrompt, selectLastOutput, copyLastOutput));");
        assertWithMessage("greyed out outside terminal tabs, like Quick Select")
            .that(body(window, "private void updateEditMenuItemsForSelection() {"))
            .contains("for (MenuItem item : shellIntegrationMenuItems) {\n            item.setDisable(!(currentTab instanceof TerminalTab));");
        String jump = body(window, "private void jumpToPromptInCurrentTab(PromptNavigator.Direction direction) {");
        assertThat(jump).contains("case NO_PROMPTS -> I18n.get(\"terminal.shellIntegration.status.noPrompts\");");
        assertThat(jump).contains("case FULL_SCREEN -> I18n.get(\"terminal.shellIntegration.status.fullScreen\");");
        assertThat(jump).contains("case DISABLED -> I18n.get(\"terminal.shellIntegration.status.disabled\");");
    }

    @Test
    void theEditMenuSelectsAndCopiesTheLastOutputWithoutAKey() throws IOException {
        String window = source("MainWindow.java");
        String edit = body(window, "private Menu createEditMenu(MenuBarTarget target) {");
        assertThat(edit).contains("ActionIds.tag(new MenuItem(I18n.get(\"menu.edit.selectLastOutput\")),\n"
            + "            \"menu.edit.selectLastOutput\");");
        assertThat(edit).contains("ActionIds.tag(new MenuItem(I18n.get(\"menu.edit.copyLastOutput\")),\n"
            + "            \"menu.edit.copyLastOutput\");");
        assertThat(edit).contains(
            "selectLastOutput.setOnAction(e -> lastOutputInCurrentTab(ShellIntegrationController.LastOutputAction.SELECT));");
        assertThat(edit).contains(
            "copyLastOutput.setOnAction(e -> lastOutputInCurrentTab(ShellIntegrationController.LastOutputAction.COPY));");
        assertWithMessage("no default key (design decision)").that(edit).doesNotContain("selectLastOutput.setAccelerator");
        assertWithMessage("no default key (design decision)").that(edit).doesNotContain("copyLastOutput.setAccelerator");
        assertWithMessage("the status line says what happened, or why nothing did")
            .that(body(window, "private void lastOutputInCurrentTab(ShellIntegrationController.LastOutputAction action) {"))
            .contains("updateStatus(I18n.get(terminalTab.lastOutput(action).statusKey()));");
    }

    @Test
    void copyLeavesTheSelectionAloneAndSelectSetsIt() throws IOException {
        String controller = source("ShellIntegrationController.java");
        String lastOutput = body(controller,
            "LastOutputResult lastOutput(@NotNull SithTermFxWidget widget, @NotNull LastOutputAction action) {");
        assertWithMessage("the text, which can be long, is read only to copy it")
            .that(lastOutput).contains("marks.lastOutput(action == LastOutputAction.COPY);");
        assertWithMessage("copy goes through the policy-aware clipboard and returns before the selection is touched")
            .that(lastOutput).contains("if (action == LastOutputAction.COPY) {\n"
                + "                    KorttyClipboard.setText(output.text());\n"
                + "                    yield truncated ? LastOutputResult.COPIED_TRUNCATED : LastOutputResult.COPIED;\n"
                + "                }\n"
                + "                panel.selectionProperty().set(output.selection());\n"
                + "                panel.repaint();");
        assertWithMessage("the setting is honoured first")
            .that(lastOutput).contains("if (!isEnabled()) {\n            return LastOutputResult.DISABLED;");

        String marks = source("PaneCommandMarks.java");
        assertWithMessage("the range and its text are read under the buffer lock, after applying the trims")
            .that(body(marks, "LastOutput lastOutput(boolean withText) {"))
            .contains("buffer.lock();\n        try {\n            if (buffer.isUsingAlternateBuffer()) {\n"
                + "                return LastOutput.of(LastOutputStatus.FULL_SCREEN);\n            }\n            syncTrims();");
    }

    @Test
    void everyLastOutputResultHasItsStatusText() throws IOException {
        Properties messages = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("i18n/messages.properties")) {
            assertThat(in).isNotNull();
            messages.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (ShellIntegrationController.LastOutputResult result : ShellIntegrationController.LastOutputResult.values()) {
            assertWithMessage(result.name()).that(messages.getProperty(result.statusKey())).isNotNull();
        }
    }

    @Test
    void theContextMenuOffersTheJumpsOrTheSetup() throws IOException {
        String view = source("TerminalView.java");
        String entries = body(view, "private List<javafx.scene.control.MenuItem> buildShellIntegrationMenuItems(SithTermFxWidget widget) {");
        assertThat(entries).contains("previous.setOnAction(e -> shellIntegration.jump(widget, PromptNavigator.Direction.PREVIOUS));");
        assertThat(entries).contains("next.setOnAction(e -> shellIntegration.jump(widget, PromptNavigator.Direction.NEXT));");
        assertThat(entries).contains("setup.setOnAction(e -> openShellIntegrationGuide());");
        assertThat(entries).contains("selectOutput.setOnAction(e -> showShellIntegrationStatus(\n"
            + "                    shellIntegration.lastOutput(widget, ShellIntegrationController.LastOutputAction.SELECT)));");
        assertThat(entries).contains("copyOutput.setOnAction(e -> showShellIntegrationStatus(\n"
            + "                    shellIntegration.lastOutput(widget, ShellIntegrationController.LastOutputAction.COPY)));");
        assertWithMessage("the output entries wait for a finished command and the normal screen")
            .that(entries).contains("boolean outputAvailable = available && shellIntegration.hasFinishedCommand(widget);");
        assertThat(entries).contains("yield List.of(previous, next, selectOutput, copyOutput);");
        assertThat(view).contains("List<javafx.scene.control.MenuItem> shellIntegrationItems = buildShellIntegrationMenuItems(widget);");
    }

    @Test
    void theMarksTellTheTimestampGutterHowEachCommandEnded() throws IOException {
        String controller = source("ShellIntegrationController.java");
        assertWithMessage("A, C and D can change a status; each change asks for one gutter update")
            .that(body(controller, "void onEvent(@NotNull SithTermFxWidget widget, @NotNull ShellIntegrationEvent event) {"))
            .contains("if (!(event instanceof ShellIntegrationEvent.CommandStart)) {\n"
                + "            notifyStatusesChanged(widget, marks);");
        String notify = body(controller, "private void notifyStatusesChanged(SithTermFxWidget widget, PaneCommandMarks marks) {");
        assertWithMessage("coalesced: at most one scheduled update per pane")
            .that(notify).contains("if (listener == null || !marks.requestGutterUpdate()) {");
        assertWithMessage("a refused schedule is asked again on the next change")
            .that(notify).contains("marks.cancelGutterUpdateRequest();");
        assertWithMessage("the 500 ms guess waits only for commands the shell marked, and only while it is on")
            .that(body(controller, "boolean awaitsCompletionMark(@Nullable SithTermFxWidget widget, long enterNanos) {"))
            .contains("return marks != null && isEnabled() && marks.store().commandRunningSince(enterNanos);");

        String view = source("TerminalView.java");
        assertWithMessage("the update runs on the FX thread")
            .that(view).contains(
                "shellIntegration.setStatusesChangedListener(widget -> Platform.runLater(() -> updateCommandStatuses(widget)));");
        String update = body(view, "private void updateCommandStatuses(SithTermFxWidget widget) {");
        assertWithMessage("the gutter's trims and the marks are read under one lock, so they name the same lines")
            .that(update).contains("trim = tracker.poll();\n            }\n            update = shellIntegration.gutterUpdate(widget);");
        assertThat(update).contains(
            "recordTimestampForLine(widget, completion.absoluteLine(), completion.time(now, nowNanos));");
        assertWithMessage("a D mark after the latest Enter ends the 500 ms wait for that command")
            .that(update).contains("if (enterNanos == null || completion.nanos() - enterNanos >= 0) {\n"
                + "                    awaitingCommandCompletionByWidget.put(widget, false);");
        assertThat(update).contains("gutter.setCommandStatuses(update.statuses());");
        assertWithMessage("a command the shell marked as running is not ended by a pause in its output")
            .that(body(view, "private void recordCommandCompletionTimestamp(SithTermFxWidget widget) {"))
            .contains("if (enterNanos != null && shellIntegration.awaitsCompletionMark(widget, enterNanos)) {\n"
                + "            // The shell marked this command as running");
        String trims = body(view, "private void applyScrollbackTrim(SithTermFxWidget widget, ScrollbackTrimTracker.Trim trim) {");
        assertWithMessage("the statuses follow the scrollback with the timestamps")
            .that(trims).contains("gutter.clearCommandStatuses();");
        assertThat(trims).contains("gutter.shiftCommandStatuses(trim.lines());");
        assertWithMessage("the statuses hide while shell integration is off")
            .that(body(view, "private void setupTimestampGutter(SithTermFxWidget widget) {"))
            .contains("gutter.setCommandStatusesShown(shellIntegration::isEnabled);");
        assertThat(body(view, "private void releasePaneState(SithTermFxWidget widget) {"))
            .contains("commandEnterNanosByWidget.remove(widget);");
        assertWithMessage("statuses are runtime-only: the project keeps the timestamps alone")
            .that(body(view, "public java.util.List<de.kortty.model.TerminalTimestampEntry> getPrimaryTimestampEntries() {"))
            .doesNotContain("Status");
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
