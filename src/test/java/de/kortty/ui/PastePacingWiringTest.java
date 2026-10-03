package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.GlobalSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Line pacing is wired where the design puts it: every tab paces through one {@code PastePacer} with
 * the delay from the Settings, holds the keys of a pacing pane before any other key handling, keeps
 * broadcast mode out of it, and stops a paced paste when its pane closes, rebinds or the tab closes.
 * Building a tab needs a JavaFX toolkit, so the wiring is checked in the source (CRLF-safe); the
 * delay lookup itself runs.
 */
class PastePacingWiringTest {

    private static final Path TERMINAL_VIEW = Path.of("src/main/java/de/kortty/ui/TerminalView.java");

    private static final Path SETTINGS_DIALOG = Path.of("src/main/java/de/kortty/ui/SettingsDialog.java");

    @Test
    void theLineDelayFollowsTheGlobalSettings() {
        GlobalSettings settings = new GlobalSettings();
        settings.setPasteLineDelayMs(300);

        assertThat(TerminalView.pasteLineDelayMs(() -> settings)).isEqualTo(300);
    }

    @Test
    void withoutReadableSettingsPastesAreNotPaced() {
        assertThat(TerminalView.pasteLineDelayMs(() -> null)).isEqualTo(0);
        assertThat(TerminalView.pasteLineDelayMs(() -> {
            throw new IllegalStateException("no application");
        })).isEqualTo(0);
    }

    @Test
    void everyTabPacesThroughOnePacerOnTheFxThread() throws IOException {
        String view = source(TERMINAL_VIEW);

        assertThat(view).contains("private final PastePacer pastePacer = new PastePacer("
            + "PastePacer.Scheduler.sharedTimer(Platform::runLater),\n"
            + "        new PastePacingIndicators(this::pastePacingIndicatorHost));");
        assertThat(view).contains("private final PasteInputHold pasteInputHold = new PasteInputHold(pastePacer);");
        // Declared before the guard, which takes it.
        assertThat(view.indexOf("private final PastePacer pastePacer"))
            .isLessThan(view.indexOf("private final PasteGuard pasteGuard"));
        assertThat(body(view, "private StackPane pastePacingIndicatorHost(Object paneKey) {"))
            .contains("splitPane.getWidgetOverlayHost(widget)");
    }

    @Test
    void thePacingPaneHoldsItsKeysBeforeAnyOtherKeyHandling() throws IOException {
        String view = source(TERMINAL_VIEW);

        assertThat(view).contains("splitPane.addEventFilter(KeyEvent.KEY_TYPED, this::holdKeyWhilePacingPaste);");
        assertThat(view).contains("splitPane.addEventFilter(KeyEvent.KEY_PRESSED, event -> {\n"
            + "            if (event.isConsumed() || holdKeyWhilePacingPaste(event)) {\n"
            + "                return;\n"
            + "            }\n"
            + "            if (isEventTargetWithinAgentActivityPanel(event)) {");
        String hold = body(view, "private boolean holdKeyWhilePacingPaste(KeyEvent event) {");
        assertThat(hold).contains("if (pasteInputHold.isIdle() ||");
        // Only keys aimed inside a pane; the fallback to the focused pane would catch the agent panel too.
        assertThat(hold).contains("findWidgetContainingNode(target)");
        assertThat(hold).doesNotContain("resolveWidgetForKeyEvent");
        assertThat(hold).contains("return pasteInputHold.filter(findWidgetContainingNode(target), event);");
        assertWithMessage("keys for the find bar never reach the session, and its Esc closes the bar")
            .that(hold).contains("isInsideTextInput(target)");
    }

    @Test
    void broadcastModeSkipsAPaneThatIsPacing() throws IOException {
        assertThat(source(TERMINAL_VIEW)).contains("splitPane.setMirrorTargetGuard(widget -> !pastePacer.isPacing(widget));");
    }

    @Test
    void aPacedPasteStopsWhenItsPaneClosesRebindsOrTheTabCloses() throws IOException {
        String view = source(TERMINAL_VIEW);

        String closed = body(view, "private void onPaneClosed(SithTermFxWidget widget) {");
        assertThat(closed).contains("cancelPastePacing(widget);");
        assertThat(closed).contains("pasteInputHold.release(widget);");

        String rebind = body(view, "private TtyConnector decorateTerminalConnector(SithTermFxWidget widget, TtyConnector connector) {");
        assertWithMessage("cancel before the next session is bound")
            .that(rebind.indexOf("cancelPastePacing(widget);")).isAtLeast(0);

        assertThat(body(view, "public void cleanup() {")).contains("pastePacer.cancelAll();");

        String cancel = body(view, "private void cancelPastePacing(SithTermFxWidget widget) {");
        assertThat(cancel).contains("Platform.isFxApplicationThread()");
        assertThat(cancel).contains("Platform.runLater(() -> pastePacer.cancel(widget));");
    }

    @Test
    void theSettingsPageSavesAndReportsTheLineDelay() throws IOException {
        String settings = source(SETTINGS_DIALOG);

        assertThat(settings).contains("pasteLineDelaySpinner = new Spinner<>(0, PastePacer.MAX_LINE_DELAY_MS,");
        assertThat(settings).contains("globalSettings.setPasteLineDelayMs(pasteLineDelaySpinner.getValue() != null");
        assertThat(settings).contains(
            "new TrackedSetting(\"terminal\", \"paste_line_delay_ms\", gs::getPasteLineDelayMs, true)");
        // The spinner sits in the Paste protection section, before its info line.
        assertThat(settings.indexOf("terminalGrid.add(pasteLineDelayBox, 1, terminalRow++);"))
            .isGreaterThan(settings.indexOf("terminalGrid.add(pasteLargeWarningBox, 1, terminalRow++);"));
        assertThat(settings.indexOf("terminalGrid.add(pasteLineDelayBox, 1, terminalRow++);"))
            .isLessThan(settings.indexOf("terminalGrid.add(pasteProtectionInfo, 0, terminalRow++, 2, 1);"));
    }

    private static String source(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from {@code signature} to the first line that closes a member at four spaces. */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("missing: %s", signature).that(start).isAtLeast(0);
        int end = source.indexOf("\n    }\n", start);
        assertWithMessage("no end for: %s", signature).that(end).isGreaterThan(start);
        return source.substring(start, end);
    }
}
