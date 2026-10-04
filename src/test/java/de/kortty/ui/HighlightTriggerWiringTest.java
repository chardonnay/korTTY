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
 * {@link TerminalAttentionNotifier}, which lets {@link HighlightTriggerDispatcher} decide, and
 * {@link HighlightSnippetTrigger}, which runs a rule's snippet in the matching pane once
 * {@link HighlightSnippetTriggerGuard} agrees. A project's restored screen and korTTY's own messages are
 * made old output right after they are written. The view, the tab and the dialogs need a JavaFX stage, so
 * their wiring is pinned against the source; the decisions are tested in
 * {@code HighlightTriggerDispatcherTest}, {@code HighlightSnippetTriggerGuardTest},
 * {@code HighlightSnippetTriggerTest} and {@code TerminalOutputHighlighterTriggerTest}.
 */
class HighlightTriggerWiringTest {

    @Test
    void everyPaneReportsItsTriggersOnTheFxThread() throws IOException {
        String view = source("TerminalView.java");
        String attach = body(view, "private void attachTerminalHighlighter(SithTermFxWidget widget) {");
        assertThat(attach).contains("TerminalOutputHighlighter.TriggerSink.of(\n"
            + "                        matches -> Platform.runLater(() -> onPaneHighlightTrigger(pane, matches)),\n"
            + "                        () -> cursorRowOf(pane)));");
        assertWithMessage("the cursor is read where the highlighter holds the buffer lock, 1-based in the emulator")
            .that(body(view, "private static int cursorRowOf(SithTermFxWidget pane) {"))
            .contains("return terminal != null ? terminal.getCursorY() - 1 : -1;");
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
    void theTabAsksTheNotifierAndTheSnippetRunner() throws IOException {
        assertThat(source("TerminalTab.java")).contains("this.terminalView.setHighlightTriggerListener((widget, matches) -> {\n"
            + "            TerminalAttentionNotifier.shared().onHighlightTrigger(this, widget, matches);\n"
            + "            HighlightSnippetTrigger.shared().onHighlightTrigger(this, widget, matches);\n"
            + "        });");
    }

    @Test
    void korttysOwnMessagesInAPaneAreMadeOldOutput() throws IOException {
        String view = source("TerminalView.java");
        for (String signature : java.util.List.of("public void showError(String message) {",
                "public void showMessage(String message) {",
                "public void showAgentMessage(@Nullable TerminalAgentRunContext runContext, String message) {",
                "public void showMessageInPane(SithTermFxWidget widget, String message) {")) {
            String method = body(view, signature);
            int baseline = method.indexOf("markHighlightBaseline(");
            assertWithMessage(signature + " makes what it wrote old output").that(baseline).isAtLeast(0);
            assertWithMessage(signature + " takes the baseline after writing")
                .that(baseline).isGreaterThan(Math.max(method.indexOf("writeCharacters("), method.indexOf("writeLocalMessageToTerminal(")));
        }
    }

    @Test
    void theSnippetRunnerAsksTheGuardAndTypesIntoTheMatchingPaneOnly() throws IOException {
        String runner = source("HighlightSnippetTrigger.java");
        String onTrigger = body(runner, "void onHighlightTrigger(TerminalTab tab, SithTermFxWidget widget, List<LineMatch> matches) {");
        assertThat(onTrigger).contains("rule.action() != HighlightRule.Action.RUN_SNIPPET");
        assertWithMessage("the cursor line, mirrored keys, agents, full-screen programs and paced pastes reach the guard")
            .that(onTrigger).contains("match.cursorLine(),\n"
                + "                mirroredInput(widget), agentBusy(tab, widget), inputBusy(tab, widget));");
        assertThat(onTrigger).contains("handle(tab, widget, rule, request, guard.decide(request));");
        String run = body(runner, "private void run(TerminalTab tab, SithTermFxWidget widget, CompiledHighlightSet.Rule rule) {");
        assertThat(run).contains("view.sendInputLineToPane(widget, preparation.line(), preparation.generatedOneLiner())");
        assertWithMessage("the cooldown and the loop guard count only a run that reached the pane")
            .that(run.indexOf("guard.ran(widget, rule.ruleId());"))
            .isGreaterThan(run.indexOf("sendInputLineToPane("));
        assertThat(run).contains("guard.tellOnce(widget, rule.ruleId() + '\\u0000' + preparation.problemKey())");
        String confirm = body(runner, "private void confirm(TerminalTab tab, SithTermFxWidget widget, CompiledHighlightSet.Rule rule,");
        assertWithMessage("the answer is recorded before anything runs, and a failed dialog asks again later")
            .that(confirm).contains("guard.answer(request, allowed);");
        assertThat(confirm).contains("guard.withdraw(request);");
        assertWithMessage("the toolkit's event loop is not nested: the answer arrives when the dialog closes")
            .that(confirm).contains("alert.show();");
        assertThat(confirm).doesNotContain("showAndWait");
        assertWithMessage("the question can appear while the user types: Enter or Space must refuse, not allow")
            .that(confirm).contains("Button denyButton = refuseByDefault(alert, allow, deny);");
        assertThat(confirm).contains("alert.setOnShown(event -> denyButton.requestFocus());");
        String refuse = body(runner, "private static @Nullable Button refuseByDefault(Alert alert, ButtonType allow, ButtonType deny) {");
        assertThat(refuse).contains("allowButton.setDefaultButton(false);");
        assertThat(refuse).contains("denyButton.setDefaultButton(true);");
        assertWithMessage("an allow is checked against the pane as it is when the question closes")
            .that(confirm).contains("request.withPaneFacts(mirroredInput(widget),\n"
                + "                        agentBusy(tab, widget), inputBusy(tab, widget));");
        assertThat(confirm).contains("handle(tab, widget, rule, now, guard.decide(now));");
        assertThat(body(runner, "private static boolean inputBusy(TerminalTab tab, SithTermFxWidget widget) {"))
            .contains("view != null && view.isPaneBusyWithInput(widget)");
        assertThat(runner).contains("() -> HighlightTriggerDispatcher.triggersAllowed(\n"
            + "                        TerminalAttentionNotifier.currentSettings(), PolicyManager.effective())),");

        String send = body(source("TerminalView.java"),
            "public boolean sendInputLineToPane(SithTermFxWidget widget, String line, boolean generatedOneLiner) {");
        assertWithMessage("a pane that closed meanwhile gets nothing").that(send).contains("!getOrderedWidgets().contains(widget)");
        assertWithMessage("straight to the pane's own session, so broadcast and multi-exec never mirror it")
            .that(send).contains("unwrapTerminalEffectConnector(widget.getTtyConnector())");
        assertThat(send).doesNotContain("MirroredInputWriter");
        assertWithMessage("never between the lines of a paste the pane is still sending")
            .that(send).contains("if (pastePacer.isPacing(widget)) {");
        String busy = body(source("TerminalView.java"), "public boolean isPaneBusyWithInput(SithTermFxWidget widget) {");
        assertThat(busy).contains("pastePacer.isPacing(widget)");
        assertWithMessage("a full-screen program would take the snippet as keystrokes")
            .that(busy).contains("return buffer.isUsingAlternateBuffer();");
    }

    @Test
    void theRuleEditorOffersTheSnippetOnlyForARuleThatRunsOne() throws IOException {
        String editor = source("HighlightRulesDialog.java");
        String controls = body(editor, "private void updateTriggerControls() {");
        assertThat(controls).contains(
            "snippetCombo.setDisable(!editable || action != HighlightRule.Action.RUN_SNIPPET || triggersForbidden);");
        assertThat(controls).contains(
            "notifyWithTextCheck.setDisable(!editable || action != HighlightRule.Action.NOTIFY || triggersForbidden);");
        assertThat(editor).contains("editRule(rule -> rule.setSnippetId(value != null ? value.id() : null)));");
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
    void theTallerRuleEditorStillFitsASmallScreen() throws IOException {
        assertThat(HighlightRulesDialog.fittedContentHeight(1_400)).isEqualTo(HighlightRulesDialog.PREF_HEIGHT);
        assertWithMessage("a 768-pixel laptop: the title and the OK button stay on screen")
            .that(HighlightRulesDialog.fittedContentHeight(728) + HighlightRulesDialog.DIALOG_CHROME_HEIGHT)
            .isAtMost(728.0);
        assertThat(HighlightRulesDialog.fittedContentHeight(300)).isEqualTo(HighlightRulesDialog.MIN_FITTED_HEIGHT);
        assertThat(HighlightRulesDialog.fittedContentHeight(0)).isEqualTo(HighlightRulesDialog.PREF_HEIGHT);
        assertThat(HighlightRulesDialog.fittedContentHeight(Double.NaN)).isEqualTo(HighlightRulesDialog.PREF_HEIGHT);

        String show = body(source("HighlightRulesDialog.java"),
            "public static boolean show(@Nullable Window owner, @NotNull GlobalSettings settings, @Nullable String initialSetId) {");
        assertWithMessage("fitted before the first show, so a stored size still wins")
            .that(show.indexOf("fitToScreen(dialog, owner);")).isIn(com.google.common.collect.Range.open(0,
                show.indexOf("dialog.showAndWait()")));
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
