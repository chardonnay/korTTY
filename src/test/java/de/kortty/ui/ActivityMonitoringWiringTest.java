package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

/**
 * The way of a pane's output to the tab's activity and silence monitoring: the colour filter stamps
 * the pane's output clock for every read, never for what is typed; {@link TerminalView} keeps one
 * clock per pane and hands them to {@link TerminalActivityWatcher}; the tab's right-click menu and
 * the command palette switch the watch, from the tab's state and never from the check mark; the
 * notifier marks the tab and notifies by the same rules as a bell; and a closed tab is no longer
 * watched. The widget, the view, the tab and the window need a JavaFX stage, so their wiring is
 * pinned against the source; the decisions are tested in {@code PaneActivityMonitorTest},
 * {@code TerminalActivityWatcherTest} and {@code TerminalNotificationPolicyTest}.
 */
class ActivityMonitoringWiringTest {

    @Test
    void theColourFilterReportsOutputButNeverWhatIsTyped() throws IOException {
        AtomicInteger activity = new AtomicInteger();
        AtomicInteger output = new AtomicInteger();
        for (boolean colors : List.of(true, false)) {
            activity.set(0);
            output.set(0);
            OscEmulatorHarness.ScriptedConnector session = new OscEmulatorHarness.ScriptedConnector(
                List.of("\u001b[31mred\u001b[0m", "plain"));
            TerminalView.TerminalColorFilteringTtyConnector filter = new TerminalView.TerminalColorFilteringTtyConnector(
                session, () -> colors, activity::incrementAndGet, output::incrementAndGet);

            filter.write("ls\r");
            filter.write(new byte[] {'q'});
            assertWithMessage("typing is power management's activity, but no output (colors " + colors + ")")
                .that(output.get()).isEqualTo(0);
            assertThat(activity.get()).isEqualTo(2);

            char[] buffer = new char[64];
            while (filter.read(buffer, 0, buffer.length) > 0) {
                // drain the session
            }
            assertWithMessage("every chunk the pane read is output (colors " + colors + ")")
                .that(output.get()).isEqualTo(2);
            assertThat(activity.get()).isEqualTo(4);
        }
    }

    @Test
    void everyPaneGetsOneOutputClockThatSurvivesItsReDecoration() throws IOException {
        String view = source("TerminalView.java");
        String decorate = EmulationGateTest.methodBody(view, "private TtyConnector decorateTerminalConnector(");
        assertThat(decorate).contains(
            "? paneOutputClocks.computeIfAbsent(widget, unused -> new PaneOutputClock())");
        assertThat(decorate).contains("this::reportTerminalActivity,\n            outputClock::outputArrived));");
        String read = EmulationGateTest.methodBody(view, "public int read(char[] buf, int offset, int length) throws IOException {");
        assertWithMessage("both read paths stamp the clock").that(read.split("outputArrived\\(\\);", -1)).hasLength(3);
        assertThat(EmulationGateTest.methodBody(view, "private void releasePaneState(SithTermFxWidget widget) {"))
            .contains("paneOutputClocks.remove(widget);");
        assertThat(EmulationGateTest.methodBody(view, "public void cleanup() {")).contains("paneOutputClocks.clear();");
        String clocks = EmulationGateTest.methodBody(view, "Map<SithTermFxWidget, PaneOutputClock> paneOutputClocks() {");
        assertWithMessage("in split order, and only for the panes the tab still has")
            .that(clocks).contains("for (SithTermFxWidget widget : getOrderedWidgets()) {");
    }

    @Test
    void theWatcherAsksTheViewTheWindowsAndTheMirroredInputWriter() throws IOException {
        String watcher = source("TerminalActivityWatcher.java");
        assertThat(watcher).contains("return view != null ? view.paneOutputClocks() : Map.of();");
        assertThat(watcher).contains("return PaneSeenOracle.seenTab(MainWindow.getOpenWindows());");
        assertWithMessage("the connector the split pane writes mirrored keys into is the pane's own")
            .that(watcher).contains("return MirroredInputWriter.shared().wroteWithin(pane.getTtyConnector(),\n"
                + "                PaneActivityMonitor.MIRRORED_OUTPUT_WINDOW);");
        assertThat(watcher).contains("settings != null ? settings.getTerminalSilenceSeconds()");
        assertThat(watcher).contains("TerminalAttentionNotifier.shared().onActivity(tab, pane);");
        assertThat(watcher).contains("TerminalAttentionNotifier.shared().onSilence(tab, pane, silence);");
        assertWithMessage("the timer polls once a second")
            .that(watcher).contains("javafx.util.Duration.millis(PaneActivityMonitor.POLL_INTERVAL.toMillis())");
    }

    @Test
    void theTabsSwitchesLiveInTheWatcherAndACloseForgetsThem() throws IOException {
        String tab = source("TerminalTab.java");
        assertThat(tab).contains("return TerminalActivityWatcher.shared().watchesActivity(this);");
        assertThat(tab).contains("TerminalActivityWatcher.shared().setActivity(this, on);");
        assertThat(tab).contains("return TerminalActivityWatcher.shared().watchesSilence(this);");
        assertThat(tab).contains("TerminalActivityWatcher.shared().setSilence(this, on);");
        assertWithMessage("every close path releases the tab, so the timer stops with the last watched tab")
            .that(EmulationGateTest.methodBody(tab, "void releaseResources() {"))
            .contains("TerminalActivityWatcher.shared().forget(this);");
    }

    @Test
    void theTabsRightClickMenuSwitchesFromTheTabsStateAndShowsIt() throws IOException {
        String menu = EmulationGateTest.methodBody(source("MainWindow.java"),
            "private void setupTabContextMenu(TerminalTab terminalTab) {");
        assertThat(menu).contains("new CheckMenuItem(I18n.get(TerminalActivityWatcher.MONITOR_ACTIVITY_KEY));");
        assertThat(menu).contains("new CheckMenuItem(I18n.get(TerminalActivityWatcher.MONITOR_SILENCE_KEY));");
        assertWithMessage("JavaFX flips the check mark before the action runs, so the tab decides")
            .that(menu).contains(
                "monitorActivityItem.setOnAction(e -> terminalTab.setMonitoringActivity(!terminalTab.isMonitoringActivity()));");
        assertThat(menu).contains(
            "monitorSilenceItem.setOnAction(e -> terminalTab.setMonitoringSilence(!terminalTab.isMonitoringSilence()));");
        int showing = menu.indexOf("contextMenu.setOnShowing(e -> {");
        assertWithMessage("the check marks follow the tab as the menu opens, also after the palette switched them")
            .that(menu.indexOf("monitorActivityItem.setSelected(terminalTab.isMonitoringActivity());", showing))
            .isGreaterThan(showing);
        assertThat(menu.indexOf("monitorSilenceItem.setSelected(terminalTab.isMonitoringSilence());", showing))
            .isGreaterThan(showing);
        assertWithMessage("outside the effects block, which only exists with terminal effects on")
            .that(menu.indexOf("monitorActivityItem")).isLessThan(menu.indexOf("isTerminalEffectsEnabled()"));
    }

    @Test
    void thePaletteSwitchesTheSameWay() throws IOException {
        String target = source("TerminalPaletteTarget.java");
        assertThat(target).contains("tab.setMonitoringActivity(!tab.isMonitoringActivity());");
        assertThat(target).contains("tab.setMonitoringSilence(!tab.isMonitoringSilence());");
    }

    @Test
    void theNotifierDecidesOnActivityAndSilenceLikeOnABell() throws IOException {
        String notifier = source("TerminalAttentionNotifier.java");
        String onActivity = EmulationGateTest.methodBody(notifier,
            "public void onActivity(TerminalTab tab, SithTermFxWidget widget) {");
        assertWithMessage("the echo of mirrored keys is no activity")
            .that(onActivity).contains("new PaneState(seen.test(tab), false, false, mirroredInputIn(widget));");
        assertWithMessage("a multi-exec member shares the session's slot")
            .that(onActivity).contains("policy.decide(Kind.ACTIVITY, session != null ? session : widget, state,");
        assertThat(onActivity).contains("tab.markAttention(I18n.get(\"terminal.notify.activity.tooltip\"));");
        assertWithMessage("never the output itself")
            .that(onActivity).contains("show(toastTitle(tab.getEffectiveTitle()), I18n.get(\"terminal.notify.activity.body\"), tab, widget);");
        String onSilence = EmulationGateTest.methodBody(notifier,
            "public void onSilence(TerminalTab tab, SithTermFxWidget widget, Duration silence) {");
        assertThat(onSilence).contains("policy.decide(Kind.SILENCE, session != null ? session : widget, state,");
        assertThat(onSilence).contains(
            "String text = silenceText(TimestampGutter.currentFormats().verboseRuntime(silence), I18n::get);");
        assertThat(onSilence).contains("tab.markAttention(text);");
        assertThat(onSilence).contains("show(toastTitle(tab.getEffectiveTitle()), text, tab, widget);");
    }

    @Test
    void theSilenceTextNamesTheDurationOnly() {
        assertThat(TerminalAttentionNotifier.silenceText("30 sec",
            (key, args) -> key + "|" + String.join(",", java.util.Arrays.stream(args).map(String::valueOf).toList())))
            .isEqualTo("terminal.notify.silence.body|30 sec");
    }

    @Test
    void theSilenceThresholdIsShownSavedAndReported() throws IOException {
        String dialog = source("SettingsDialog.java");
        assertThat(dialog).contains("terminalSilenceSecondsSpinner = new Spinner<>(PaneActivityMonitor.MIN_SILENCE_SECONDS,\n"
            + "            PaneActivityMonitor.MAX_SILENCE_SECONDS,");
        assertThat(dialog).contains("terminalGrid.add(terminalSilenceSecondsBox, 1, terminalRow++);");
        assertThat(dialog).contains("globalSettings.setTerminalSilenceSeconds(terminalSilenceSecondsSpinner.getValue() != null");
        assertThat(dialog).contains(
            "tracked.add(new TrackedSetting(\"terminal\", \"silence_seconds\", gs::getTerminalSilenceSeconds, true));");
    }

    private static String source(String file) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(Path.of("src/main/java/de/kortty/ui", file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
