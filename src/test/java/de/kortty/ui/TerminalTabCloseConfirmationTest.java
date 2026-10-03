package de.kortty.ui;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Closing a busy terminal asks first, whichever way the user closes it. The tab's close button
 * fires the tab's close request, but Cmd/Ctrl+W and the Dashboard's Close remove the tab from the
 * list, which fires no close event at all — so on macOS Cmd+W used to close a running command
 * without the question the guide promises. The decision is pinned as a truth table, and the
 * wiring of the three close paths against the source, since neither {@link TerminalTab} nor
 * {@link MainWindow} can be built without a JavaFX stage.
 */
class TerminalTabCloseConfirmationTest {

    private static final Path UI_ROOT = Path.of("src/main/java/de/kortty/ui");

    @DataProvider
    Object[][] closeStates() {
        // connected, closeWithoutConfirmation, busy, asks
        return new Object[][] {
            {true, false, true, true},
            {true, false, false, false},
            {true, true, true, false},
            {true, true, false, false},
            {false, false, true, false},
            {false, false, false, false},
            {false, true, true, false},
            {false, true, false, false},
        };
    }

    @Test(dataProvider = "closeStates")
    void asksOnlyForAConnectedBusyTerminalThatDoesNotSkipTheQuestion(
            boolean connected, boolean closeWithoutConfirmation, boolean busy, boolean asks) {
        assertWithMessage("connected=%s, closeWithoutConfirmation=%s, busy=%s", connected, closeWithoutConfirmation, busy)
            .that(TerminalTab.closeNeedsConfirmation(connected, closeWithoutConfirmation, busy))
            .isEqualTo(asks);
    }

    @Test
    void theCloseButtonAsksThroughTheSharedConfirmationBeforeItReleasesAnything() throws IOException {
        String tab = source("TerminalTab.java");
        String request = tab.substring(tab.indexOf("setOnCloseRequest(event -> {"));
        request = request.substring(0, request.indexOf("});"));

        assertThat(request).contains("if (!confirmUserClose()) {");
        assertThat(request.indexOf("confirmUserClose()")).isLessThan(request.indexOf("releaseResources();"));
        assertWithMessage("the close button must not keep a second copy of the question")
            .that(request).doesNotContain("new Alert(");

        String confirm = methodBody(tab, "boolean confirmUserClose() {");
        assertThat(confirm.indexOf("needsCloseConfirmation()")).isLessThan(confirm.indexOf("new Alert("));
        assertThat(methodBody(tab, "boolean needsCloseConfirmation() {")).contains("closeNeedsConfirmation(");
    }

    @Test
    void closeTabAndTheDashboardsCloseGoThroughTheUserCloseFunnel() throws IOException {
        String window = source("MainWindow.java");

        String closeCurrent = methodBody(window, "private void closeCurrentTab() {");
        assertThat(closeCurrent).contains("closeTabsByUser(List.of(currentTab), CloseCause.CLOSE_TAB_COMMAND)");
        assertThat(closeCurrent).doesNotContain("getTabs().remove(");

        String dashboard = methodBody(window, "private void handleDashboardAction(");
        String close = dashboard.substring(dashboard.indexOf("case CLOSE:"), dashboard.indexOf("case RECONNECT:"));
        assertThat(close).contains("closeTabsByUser(List.of(terminalTab), CloseCause.DASHBOARD)");
        assertThat(close).doesNotContain("getTabs().remove(");
    }

    @Test
    void theFunnelAsksFirstAndClosesNothingUnlessEveryTabAgreed() throws IOException {
        String window = source("MainWindow.java");
        String funnel = methodBody(window, "private boolean closeTabsByUser(List<Tab> tabs, CloseCause cause) {");

        int confirm = funnel.indexOf("if (!confirmUserClose(tab)) {");
        int stillOpen = funnel.indexOf("targets.removeIf(tab -> !tabPane.getTabs().contains(tab));");
        int record = funnel.indexOf("recordUserClosedTabs(targets, cause);");
        int dispose = funnel.indexOf("disposeTabContent(tab);");
        int remove = funnel.indexOf("tabPane.getTabs().removeAll(targets);");
        assertThat(confirm).isAtLeast(0);
        assertWithMessage("a tab that closed on its own while the question was open is neither recorded nor released twice")
            .that(stillOpen).isGreaterThan(confirm);
        assertThat(stillOpen).isLessThan(record);
        assertThat(record).isLessThan(dispose);
        assertThat(dispose).isLessThan(remove);
        assertWithMessage("a Cancel must leave every tab open and intact")
            .that(funnel.substring(confirm, record)).contains("return false;");

        String perTab = methodBody(window, "private static boolean confirmUserClose(Tab tab) {");
        assertThat(perTab).contains("terminalTab.confirmUserClose()");
        assertWithMessage("a hosted snippet editor still asks about unsaved changes on Cmd/Ctrl+W")
            .that(perTab).contains("hostTab.confirmClose()");
    }

    @Test
    void everyClosePathReleasesTheTerminalLikeItsCloseButton() throws IOException {
        String tab = source("TerminalTab.java");
        String release = methodBody(tab, "void releaseResources() {");
        assertThat(release).contains("closeRecordingResources();");
        assertThat(release).contains("terminalView.cleanup();");
        assertWithMessage("a pending automatic reconnect must not outlive the tab")
            .that(release).contains("cancelAutoReconnectTimer();");
        assertWithMessage("the status-bar timeline ticks forever and keeps a closed tab in memory")
            .that(release).contains("stopStatusBarTimer();");

        assertThat(methodBody(tab, "private void closeTabSilently() {")).contains("releaseResources();");
        String dispose = methodBody(source("MainWindow.java"), "private void disposeTabContent(Tab tab) {");
        String terminalBranch = dispose.substring(dispose.indexOf("if (tab instanceof TerminalTab terminalTab) {"),
            dispose.indexOf("} else if (tab instanceof FileEditorTab"));
        assertWithMessage("Cmd/Ctrl+W, the Dashboard's Close and Close All release what the close button releases")
            .that(terminalBranch).contains("terminalTab.releaseResources();");
    }

    private static String source(String fileName) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(UI_ROOT.resolve(fileName), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text of the method whose declaration contains {@code signature}, up to its closing brace. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("method not found: " + signature).that(start).isAtLeast(0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced braces in " + signature);
    }
}
