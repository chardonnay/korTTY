package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * The bell's way from the emulator to the tab: {@code KorttyTerminalPanel.beep()} tells the pane's
 * listener, {@link TerminalView} coalesces the bells onto the FX thread and hands them to the tab,
 * the tab asks {@link TerminalAttentionNotifier}, and the window clears the mark once the tab is
 * seen. A long command's end takes the same way from its OSC 133 D mark: {@link PaneCommandMarks}
 * reports it, {@link ShellIntegrationController} drops commands under a second, and the view hands
 * the rest to the tab on the FX thread. A program's notification (OSC 9, OSC 777) is cleaned on the
 * emulator thread and waits in the pane's slot until the FX thread takes it for the tab. The widget,
 * the view, the tab and the window need a JavaFX stage, so their wiring is pinned against the
 * source; the decisions themselves are tested in {@code TerminalNotificationPolicyTest},
 * {@code BellCoalescingTest}, {@code CommandFinishedNotificationTest},
 * {@code RemoteNotificationTextTest} and {@code RemoteNotificationFlowTest}.
 */
class TerminalAttentionWiringTest {

    @Test
    void thePanelReportsEveryBellAndStaysSilent() throws IOException {
        String beep = body(source("KorttyTermWidget.java"), "public void beep() {");
        int listener = beep.indexOf("listener.run();");
        int vendor = beep.indexOf("super.beep();");
        assertWithMessage("the listener hears the bell").that(listener).isAtLeast(0);
        assertWithMessage("SithTermFX's own bell, silent through audibleBell() == false, still runs after it")
            .that(vendor).isGreaterThan(listener);
        assertWithMessage("a failing listener must not stop the emulator thread")
            .that(beep).contains("} catch (RuntimeException e) {");
        assertThat(source("TerminalView.java")).contains("public boolean audibleBell() {\n            return false;");
    }

    @Test
    void everyPaneCoalescesItsBellsOntoTheFxThread() throws IOException {
        String view = source("TerminalView.java");
        assertThat(body(view, "private void setupWidgetEventHandlers(SithTermFxWidget widget) {"))
            .contains("installBellListener(widget);");
        String install = body(view, "private void installBellListener(SithTermFxWidget widget) {");
        assertThat(install).contains("new BellCoalescer(Platform::runLater, count -> onPaneBell(widget));");
        assertThat(install).contains("korttyWidget.setBellListener(bells::ring);");
        String onBell = body(view, "private void onPaneBell(SithTermFxWidget widget) {");
        assertWithMessage("a pane that closed while its bell was on the way is ignored")
            .that(onBell).contains("if (listener == null || !getOrderedWidgets().contains(widget)) {");
        assertThat(body(view, "private void releasePaneState(SithTermFxWidget widget) {"))
            .contains("korttyWidget.setBellListener(null);");
        assertWithMessage("a closed tab is neither marked nor announced")
            .that(body(view, "public void cleanup() {")).contains("bellListener = null;");
    }

    @Test
    void theTabAsksTheNotifierAndShowsTheMarkAfterTheAgentStatus() throws IOException {
        String tab = source("TerminalTab.java");
        assertThat(tab).contains(
            "this.terminalView.setBellListener(widget -> TerminalAttentionNotifier.shared().onBell(this, widget));");
        assertThat(tab).contains("List.of(agentStatusBadge, attentionBadge), tabGroup, getEffectiveTitle(), effectiveSuffix)");
        assertThat(tab).contains("connectionColorLine, attentionLine);");
        assertThat(TerminalTab.ATTENTION_BADGE).isEqualTo("🔔");
    }

    @Test
    void theNotifierDecidesOnTheBellAndNeverShowsTerminalText() throws IOException {
        String onBell = body(source("TerminalAttentionNotifier.java"), "public void onBell(TerminalTab tab, SithTermFxWidget widget) {");
        assertWithMessage("a multi-exec member shares the session's slot with the other members")
            .that(onBell).contains("Object session = multiExecSessionOf(widget);");
        assertThat(onBell).contains("policy.decide(Kind.BELL, session != null ? session : widget, state, toggles);");
        assertThat(onBell).contains("Toggles toggles = toggles(settings.get());");
        assertWithMessage("a bell right after mirrored keys answers them")
            .that(onBell).contains("new PaneState(seen.test(tab), hasCodingAgent(tab, widget), false, mirroredInputIn(widget));");
        assertThat(onBell).contains("tab.markAttention(I18n.get(\"terminal.notify.bell.tooltip\"));");
        assertThat(onBell).contains("show(toastTitle(tab.getEffectiveTitle()), I18n.get(\"terminal.notify.bell.body\"));");
    }

    @Test
    void theApplicationsNotifierAsksMultiExecAndTheMirroredInputWriter() throws IOException {
        String notifier = source("TerminalAttentionNotifier.java");
        assertThat(body(notifier, "static TerminalAttentionNotifier shared() {")).contains(
            "TerminalAttentionNotifier::codingAgentRegistry, MultiExecCoordinator.shared()::sessionOf,\n"
                + "                TerminalAttentionNotifier::receivedMirroredInput);");
        assertWithMessage("the connector the split pane writes mirrored keys into is the pane's own")
            .that(body(notifier, "static boolean receivedMirroredInput(SithTermFxWidget widget) {"))
            .contains("return MirroredInputWriter.shared().wroteWithin(connector, "
                + "TerminalNotificationPolicy.MIRRORED_ECHO_WINDOW);");
        String splitPane = Files.readString(Path.of("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(splitPane).contains("TtyConnector connector = widget.getTtyConnector();");
        assertThat(splitPane).contains("MirroredInputWriter.shared().write(connector, bytes);");
        assertWithMessage("a program's own notification keeps the pane as its slot")
            .that(body(notifier, "public void onRemoteNotification(TerminalTab tab, SithTermFxWidget widget, "
                + "RemoteNotificationText notification) {")).doesNotContain("multiExecSessionOf");
    }

    @Test
    void theWindowClearsTheMarkOnceTheTabIsSeen() throws IOException {
        String window = source("MainWindow.java");
        String clear = body(window, "private void clearAttentionOfSeenTab() {");
        assertThat(clear).contains("TerminalTab seen = isForegroundWindow() ? getActiveTerminalTab() : null;");
        assertThat(clear).contains("seen.clearAttention();");
        String foreground = body(window, "private void updateForegroundActivity() {");
        assertWithMessage("a window coming to the front shows its selected tab again")
            .that(foreground).contains("if (foreground) {\n            // The window came to the front: its selected tab is seen again.\n            clearAttentionOfSeenTab();");
        int selection = window.indexOf("tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {");
        int selectionEnd = window.indexOf("});", selection);
        assertWithMessage("selecting a tab shows it").that(window.substring(selection, selectionEnd))
            .contains("clearAttentionOfSeenTab();");
    }

    @Test
    void theSettingIsSavedAndReported() throws IOException {
        String dialog = source("SettingsDialog.java");
        assertThat(dialog).contains(
            "globalSettings.setTerminalBellNotificationsEnabled(terminalBellNotificationsCheck.isSelected());");
        assertThat(dialog).contains(
            "tracked.add(new TrackedSetting(\"terminal\", \"bell_notifications\", gs::isTerminalBellNotificationsEnabled, true));");
        assertThat(dialog).contains("terminalGrid.add(terminalBellNotificationsCheck, 0, terminalRow++, 2, 1);");
    }

    @Test
    void aFinishedCommandTravelsFromItsDMarkToTheTab() throws IOException {
        String marks = source("PaneCommandMarks.java");
        assertWithMessage("the block D just closed is reported with its exit status and runtime")
            .that(marks).contains("return store.lastFinished().map(CommandStatus::of).orElse(null);");

        String controller = source("ShellIntegrationController.java");
        String onEvent = body(controller, "void onEvent(@NotNull SithTermFxWidget widget, @NotNull ShellIntegrationEvent event) {");
        assertThat(onEvent).contains("CommandStatus finished = marks.record(event, terminal, System.nanoTime());");
        assertThat(onEvent).contains("notifyCommandFinished(widget, finished);");
        String notify = body(controller, "private void notifyCommandFinished(SithTermFxWidget widget, CommandStatus status) {");
        assertWithMessage("commands under a second never reach the FX thread, so a flood of marks costs nothing there")
            .that(notify).contains("if (listener == null || !TerminalNotificationPolicy.mayNotify(status.runtime())) {");
        assertWithMessage("a failing listener must not stop the emulator thread")
            .that(notify).contains("} catch (RuntimeException e) {");

        String view = source("TerminalView.java");
        assertThat(view).contains("shellIntegration.setCommandFinishedListener(\n"
            + "            (widget, status) -> Platform.runLater(() -> onPaneCommandFinished(widget, status)));");
        String onFinished = body(view, "private void onPaneCommandFinished(SithTermFxWidget widget, CommandStatus status) {");
        assertWithMessage("a pane that closed while its command's end was on the way is ignored")
            .that(onFinished).contains("if (listener == null || !getOrderedWidgets().contains(widget)) {");
        assertWithMessage("a closed tab is neither marked nor announced")
            .that(body(view, "public void cleanup() {")).contains("commandFinishedListener = null;");

        assertThat(source("TerminalTab.java")).contains(
            "(widget, status) -> TerminalAttentionNotifier.shared().onCommandFinished(this, widget, status));");
    }

    @Test
    void theNotifierDecidesOnTheCommandAndNeverShowsIt() throws IOException {
        String notifier = source("TerminalAttentionNotifier.java");
        String onFinished = body(notifier,
            "public void onCommandFinished(TerminalTab tab, SithTermFxWidget widget, CommandStatus status) {");
        assertWithMessage("a terminal-agent run's commands are suppressed")
            .that(onFinished).contains("new PaneState(seen.test(tab), codingAgentPane, agentRunIn(tab, widget));");
        assertWithMessage("the tab is the notification slot, so broadcast panes notify once, and a multi-exec "
                + "member's run is reported once for every member")
            .that(onFinished).contains("policy.decideCommandFinished(tab, run, runtime, state, toggles(settings.get()));");
        assertThat(onFinished).contains("Object session = multiExecSessionOf(widget);");
        assertWithMessage("the run starts at the command's C mark, the moment the mirrored Enter reached the shell")
            .that(onFinished).contains(
                "MultiExecRun run = session != null ? new MultiExecRun(session, status.outputStartNanos()) : null;");
        assertWithMessage("the text comes from the exit status and the runtime alone")
            .that(onFinished).contains("String text = commandFinishedText(status.exitStatus(),\n"
                + "            TimestampGutter.currentFormats().verboseRuntime(runtime), I18n::get);");
        assertThat(onFinished).contains("tab.markAttention(text);");
        assertThat(onFinished).contains("show(toastTitle(tab.getEffectiveTitle()), text);");
        assertThat(body(notifier, "private boolean agentRunIn(TerminalTab tab, SithTermFxWidget widget) {"))
            .contains("return view != null && view.terminalAgentRunCount(widget) > 0;");
    }

    @Test
    void theLongCommandSettingsAreShownSavedAndReported() throws IOException {
        String dialog = source("SettingsDialog.java");
        assertThat(dialog).contains(
            "globalSettings.setCommandFinishedNotificationsEnabled(commandFinishedNotificationsCheck.isSelected());");
        assertThat(dialog).contains("globalSettings.setCommandFinishedNotificationSeconds(commandFinishedSecondsSpinner.getValue() != null");
        assertThat(dialog).contains("tracked.add(new TrackedSetting(\"terminal\", \"command_finished_notifications\",");
        assertThat(dialog).contains("tracked.add(new TrackedSetting(\"terminal\", \"command_finished_notification_seconds\",");
        assertThat(dialog).contains("terminalGrid.add(commandFinishedNotificationsCheck, 0, terminalRow++, 2, 1);");
        assertThat(dialog).contains("terminalGrid.add(commandFinishedSecondsBox, 1, terminalRow++);");
        assertThat(dialog).contains("commandFinishedSecondsSpinner = new Spinner<>(TerminalNotificationPolicy.MIN_COMMAND_FINISHED_SECONDS,\n"
            + "            TerminalNotificationPolicy.MAX_COMMAND_FINISHED_SECONDS,");
        assertWithMessage("without shell integration no command ends, so its controls are greyed out")
            .that(dialog).contains("shellIntegrationCheck.selectedProperty().addListener((obs, was, now) -> syncCommandFinishedControls.run());");
    }

    @Test
    void aProgramsNotificationTravelsFromTheOutputToTheTabOneAtATime() throws IOException {
        String controller = source("ShellIntegrationController.java");
        String onEvent = body(controller, "void onEvent(@NotNull SithTermFxWidget widget, @NotNull ShellIntegrationEvent event) {");
        int remote = onEvent.indexOf("if (event instanceof ShellIntegrationEvent.RemoteNotification notification) {");
        assertWithMessage("a notification needs no shell integration, so it comes before the marks' setting check")
            .that(remote).isAtLeast(0);
        assertThat(remote).isLessThan(onEvent.indexOf("if (!PaneCommandMarks.isMark(event) || !isEnabled()) {"));
        String offer = body(controller,
            "private void offerRemoteNotification(SithTermFxWidget widget, ShellIntegrationEvent.RemoteNotification event) {");
        assertWithMessage("cleaned on the emulator thread, and only the first of a burst schedules the FX thread")
            .that(offer).contains("RemoteNotificationText notification = RemoteNotificationText.of(event);\n"
                + "        if (notification == null || !slot.offer(notification)) {\n            return;\n        }");
        assertWithMessage("a failing listener frees the slot and never stops the emulator thread")
            .that(offer).contains("} catch (RuntimeException e) {\n            slot.take();");
        assertThat(body(controller, "void attach(@NotNull SithTermFxWidget widget) {"))
            .contains("remoteNotifications.putIfAbsent(widget, new RemoteNotificationSlot());");
        assertThat(body(controller, "void detach(@Nullable SithTermFxWidget widget) {"))
            .contains("remoteNotifications.remove(widget);");

        String view = source("TerminalView.java");
        assertThat(view).contains(
            "shellIntegration.setRemoteNotificationListener(widget -> Platform.runLater(() -> onPaneRemoteNotification(widget)));");
        String onRemote = body(view, "private void onPaneRemoteNotification(SithTermFxWidget widget) {");
        assertWithMessage("taken first, so the pane's next notification can come even when this one is ignored")
            .that(onRemote.indexOf("shellIntegration.takeRemoteNotification(widget);"))
            .isLessThan(onRemote.indexOf("if (notification == null || listener == null || !getOrderedWidgets().contains(widget)) {"));
        assertWithMessage("a closed tab is neither marked nor announced")
            .that(body(view, "public void cleanup() {")).contains("remoteNotificationListener = null;");

        assertThat(source("TerminalTab.java")).contains(
            "(widget, notification) -> TerminalAttentionNotifier.shared().onRemoteNotification(this, widget, notification));");
    }

    @Test
    void theNotifierDecidesOnAProgramsNotificationPerPaneAndTitlesItWithTheTab() throws IOException {
        String onRemote = body(source("TerminalAttentionNotifier.java"),
            "public void onRemoteNotification(TerminalTab tab, SithTermFxWidget widget, RemoteNotificationText notification) {");
        assertWithMessage("a coding agent's own notification wins")
            .that(onRemote).contains("new PaneState(seen.test(tab), hasCodingAgent(tab, widget), false);");
        assertWithMessage("the pane is the slot: one every 5 s per pane")
            .that(onRemote).contains("policy.decide(Kind.REMOTE, widget, state, toggles(settings.get()));");
        assertThat(onRemote).contains("tab.markAttention(remoteTooltip(notification, I18n::get));");
        assertWithMessage("korTTY's own title with the tab's name, the program's text below it")
            .that(onRemote).contains("show(toastTitle(tab.getEffectiveTitle()), notification.text());");
    }

    @Test
    void theProgramsNotificationSettingIsShownSavedAndReported() throws IOException {
        String dialog = source("SettingsDialog.java");
        assertThat(dialog).contains(
            "globalSettings.setRemoteTerminalNotificationsEnabled(remoteTerminalNotificationsCheck.isSelected());");
        assertThat(dialog).contains("tracked.add(new TrackedSetting(\"terminal\", \"remote_notifications\",");
        assertThat(dialog).contains("terminalGrid.add(remoteTerminalNotificationsCheck, 0, terminalRow++, 2, 1);");
        assertWithMessage("on unless the settings say otherwise")
            .that(dialog).contains("remoteTerminalNotificationsCheck.setSelected(globalSettings == null\n"
                + "            || globalSettings.isRemoteTerminalNotificationsEnabled());");
    }

    @Test
    void aNotificationIsTitledWithKorttyAndTheCleanedTabName() {
        assertThat(TerminalAttentionNotifier.toastTitle("prod-db")).isEqualTo("korTTY · prod-db");
        assertWithMessage("the server can set the tab's name, so its controls go")
            .that(TerminalAttentionNotifier.toastTitle("prod‮bd-gnitset\u001B[31m")).isEqualTo("korTTY · prodbd-gnitset[31m");
        String longName = "x".repeat(TerminalAttentionNotifier.MAX_TAB_NAME_CHARS + 20);
        assertThat(TerminalAttentionNotifier.toastTitle(longName))
            .isEqualTo("korTTY · " + "x".repeat(TerminalAttentionNotifier.MAX_TAB_NAME_CHARS));
        assertThat(TerminalAttentionNotifier.toastTitle(null)).isEqualTo("korTTY");
        assertThat(TerminalAttentionNotifier.toastTitle(" ‎ ")).isEqualTo("korTTY");
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
