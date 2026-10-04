package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * A highlight trigger's way from the pane to the desktop: the pane's highlighter reports it on its own
 * thread, {@link TerminalView} hands it to the FX thread and on to the tab, the tab asks
 * {@link TerminalAttentionNotifier}, which lets {@link HighlightTriggerDispatcher} decide. A project's
 * restored screen is made old output right after it is written. The view, the tab and the dialogs need a
 * JavaFX stage, so their wiring is pinned against the source; the decisions are tested in
 * {@code HighlightTriggerDispatcherTest} and {@code TerminalOutputHighlighterTriggerTest}.
 */
class HighlightTriggerWiringTest {

    @Test
    void everyPaneReportsItsTriggersOnTheFxThread() throws IOException {
        String view = source("TerminalView.java");
        String attach = body(view, "private void attachTerminalHighlighter(SithTermFxWidget widget) {");
        assertThat(attach).contains("matches -> Platform.runLater(() -> onPaneHighlightTrigger(pane, matches)));");
        String onTrigger = body(view,
            "private void onPaneHighlightTrigger(SithTermFxWidget widget, List<TerminalOutputHighlighter.LineMatch> matches) {");
        assertWithMessage("a pane that closed while its trigger was on the way is ignored")
            .that(onTrigger).contains("!getOrderedWidgets().contains(widget)");
        assertWithMessage("a closed tab is neither marked nor announced")
            .that(body(view, "public void cleanup() {")).contains("highlightTriggerListener = null;");
    }

    @Test
    void aRestoredScreenIsMadeOldOutputRightAfterItIsWritten() throws IOException {
        String replay = body(source("TerminalView.java"), "private void replayPendingRestoredHistory(SithTermFxWidget widget) {");
        int write = replay.indexOf("RestoredHistoryReplay.replay(widget.getTerminal()");
        int baseline = replay.indexOf("markHighlightBaseline(widget);");
        assertThat(write).isAtLeast(0);
        assertWithMessage("the baseline is taken after the restored rows are written").that(baseline).isGreaterThan(write);
        assertWithMessage("also when writing them failed halfway")
            .that(replay.substring(0, baseline)).contains("} finally {");
        assertThat(body(source("TerminalView.java"), "private void markHighlightBaseline(SithTermFxWidget widget) {"))
            .contains("highlighter.markBaseline();");
    }

    @Test
    void theTabAsksTheNotifier() throws IOException {
        assertThat(source("TerminalTab.java")).contains("this.terminalView.setHighlightTriggerListener(\n"
            + "            (widget, matches) -> TerminalAttentionNotifier.shared().onHighlightTrigger(this, widget, matches));");
    }

    @Test
    void theNotifierUsesTheSharedPolicyTheMultiExecSlotAndTheMirroredInputDiscount() throws IOException {
        String notifier = source("TerminalAttentionNotifier.java");
        assertThat(notifier).contains("this.triggers = new HighlightTriggerDispatcher(policy, () -> HighlightTriggerDispatcher.triggersAllowed(\n"
            + "            this.settings.get(), PolicyManager.effective()));");
        String onTrigger = body(notifier,
            "public void onHighlightTrigger(TerminalTab tab, SithTermFxWidget widget, List<LineMatch> matches) {");
        assertThat(onTrigger).contains("new PaneState(seen.test(tab), false, false, mirroredInputIn(widget));");
        assertThat(onTrigger).contains("triggers.dispatch(session != null ? session : widget, state,");
        assertThat(onTrigger).contains("tab.markAttention(notice.tooltip());");
        assertThat(onTrigger).contains("show(notice.title(), notice.body());");
    }

    @Test
    void theSettingsSwitchIsLockedByThePolicyAndSaved() throws IOException {
        String dialog = source("SettingsDialog.java");
        assertThat(dialog).contains("if (!de.kortty.policy.PolicyUiSupport.lockIfManaged(\n"
            + "                terminalTriggersEnabledCheck, de.kortty.policy.ManagedSetting.TERMINAL_TRIGGERS)) {");
        assertWithMessage("the tooltip is set first, so the managed hint replaces it")
            .that(dialog.indexOf("terminalTriggersEnabledCheck.setTooltip("))
            .isLessThan(dialog.indexOf("terminalTriggersEnabledCheck, de.kortty.policy.ManagedSetting.TERMINAL_TRIGGERS"));
        assertThat(dialog).contains("globalSettings.setTerminalTriggersEnabled(terminalTriggersEnabledCheck.isSelected());");
        assertThat(dialog).contains("terminalGrid.add(terminalTriggersEnabledCheck, 0, terminalRow++, 2, 1);");
        assertThat(dialog).contains(
            "tracked.add(new TrackedSetting(\"terminal\", \"highlighting_triggers\", gs::isTerminalTriggersEnabled, true));");
    }

    @Test
    void theRuleEditorLocksTheActionWhileThePolicyForbidsTriggers() throws IOException {
        String editor = source("HighlightRulesDialog.java");
        assertThat(editor).contains("this.triggersForbidden = !de.kortty.policy.PolicyManager.effective().terminalTriggersAllowed();");
        String controls = body(editor, "private void updateTriggerControls() {");
        assertWithMessage("a rule cannot get an action, but one it has can still be removed")
            .that(controls).contains("actionCombo.setDisable(!editable || (triggersForbidden && !acting));");
        assertThat(controls).contains("PolicyUiSupport.managedByOrganizationText()");
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
