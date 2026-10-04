package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Where a local shell's working directory enters the session snapshot and where a restore starts the
 * shell there again. A live tab needs a shown window and a shell, so like
 * {@link SessionSnapshotWiringTest} this test pins the sources, line-ending agnostic; the rules are
 * tested in {@code SessionWorkingDirectoryTest}, {@link PaneWorkingDirectoryTrackerTest} and
 * {@link SplitLayoutRestorePlanTest}.
 */
class SessionWorkingDirectoryWiringTest {

    private static final Path MAIN_WINDOW = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final Path TERMINAL_VIEW = Path.of("src/main/java/de/kortty/ui/TerminalView.java");

    @Test
    void onlyTheSessionCaptureSavesDirectoriesAndAProjectSaveStripsACopyOfThePendingLayout() throws IOException {
        String capture = methodBody(read(MAIN_WINDOW), "private SessionState captureTabState(Tab tab, CaptureOptions options) {");

        assertThat(capture).contains("options.includeWorkingDirectories()\n"
            + "                    ? view.getSessionSplitState(scrollbackRefOf)\n"
            + "                    : view.getSplitState();");
        assertWithMessage("a project save must not strip the layout a restored tab still rebuilds")
            .that(capture).contains("splitState = terminalTab.getPendingSplitLayout().deepCopy();");
        assertThat(capture).contains("ProjectLeafFieldSanitizer.sanitize(splitState, ProjectLeafFieldSanitizer.Source.PROJECT_FILE);");
        assertThat(capture).contains("sessionState.setCurrentDirectory(terminalTab.getTerminalView().getSessionWorkingDirectory());");
        int guard = capture.indexOf("if (options.includeWorkingDirectories()) {\n                // The first pane's");
        assertWithMessage("the tab's directory is set for the session snapshot only").that(guard).isAtLeast(0);
        assertWithMessage("the capture runs on the JavaFX thread and never reads the OS directory")
            .that(capture).doesNotContain("refreshCurrentWorkingDirectory");
        assertThat(capture).doesNotContain("readLiveWorkingDirectory");
    }

    @Test
    void theSessionCaptureReadsNoLiveDirectoryOnTheFxThread() throws IOException {
        String view = read(TERMINAL_VIEW);

        String paneDirectory = methodBody(view, "private @Nullable String sessionWorkingDirectoryOf(@Nullable SithTermFxWidget pane) {");
        assertThat(paneDirectory).contains("local.getCurrentWorkingDirectory()");
        assertThat(paneDirectory).contains("liveWorkingDirectoryRead(local)");
        assertWithMessage("the live read is only handed to the tracker, which runs it in the background")
            .that(paneDirectory).doesNotContain("readLiveWorkingDirectory()");
        assertThat(methodBody(view, "public de.kortty.model.SplitPaneState getSessionSplitState() {"))
            .contains("getSessionSplitState(pane -> null)");
        assertThat(methodBody(view, "public de.kortty.model.SplitPaneState getSessionSplitState(\n"))
            .contains("this::sessionWorkingDirectoryOf");
        assertThat(methodBody(view, "public de.kortty.model.SplitPaneState getSplitState() {"))
            .doesNotContain("sessionWorkingDirectoryOf");
        assertThat(view).contains("Thread thread = new Thread(runnable, \"kortty-pane-cwd\");");
    }

    @Test
    void aRestoredLocalShellStartsInItsDirectoryAndARemoteTabNeverGetsACd() throws IOException {
        String window = read(MAIN_WINDOW);
        String view = read(TERMINAL_VIEW);

        assertThat(methodBody(window, "private void restoreSavedTab(")).contains("sessionState.getCurrentDirectory(),");
        String open = window.substring(window.indexOf("            java.util.function.Consumer<TerminalView> beforeConnect) {"));
        open = open.substring(0, open.indexOf("// Connect in background"));
        assertThat(open).contains("if (restoredWorkingDirectory != null\n"
            + "                    && connection.getProtocol() == de.kortty.model.ConnectionProtocol.LOCAL_SHELL) {");
        assertWithMessage("the directory is queued before the background connect starts")
            .that(open).contains("terminalTab.getTerminalView().setRestoredWorkingDirectory(restoredWorkingDirectory);");

        assertThat(view).contains("localShell.setRestoredStartDirectory(restoredWorkingDirectory.get());");
        assertThat(view).contains("localShell.setRestoredStartDirectory(directory);");
        assertThat(view).contains("String directory = plan.directoryOf(step.newLeafId());");
        assertThat(view).contains("prepared = prepareRestoredSplitPane(step, directory, tab, tabConnectionId);");
        assertWithMessage("used once: a reconnect starts where the connection says")
            .that(view).contains("restoredWorkingDirectory.set(null);");
        assertWithMessage("a restore never types a cd into any shell")
            .that(view).doesNotContain("\"cd ");
        assertThat(window).doesNotContain("\"cd ");
    }

    @Test
    void aCdMarksTheSessionDirtyAndClosedPanesAreForgotten() throws IOException {
        String window = read(MAIN_WINDOW);
        String view = read(TERMINAL_VIEW);

        assertThat(methodBody(window, "private void registerTerminalTabForAiAgentDock(TerminalTab terminalTab) {"))
            .contains("terminalTab.getTerminalView().setOnSessionStateChanged(MainWindow::markSessionDirty);");
        assertThat(methodBody(view, "private TtyConnector decorateTerminalConnector(SithTermFxWidget widget, TtyConnector connector) {"))
            .contains("trackLocalWorkingDirectory(widget, baseConnector);");
        assertThat(methodBody(view, "private void trackLocalWorkingDirectory(")).contains(
            "local.setWorkingDirectoryChangeListener(() -> paneWorkingDirectories.directoryMayHaveChanged(widget, liveRead));");
        assertThat(methodBody(view, "private void releasePaneState(SithTermFxWidget widget) {"))
            .contains("paneWorkingDirectories.forget(widget);");
        assertThat(methodBody(view, "public void cleanup() {")).contains("paneWorkingDirectories.clear();");
    }

    private static String read(Path path) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from {@code signature} to the brace closing its body. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("method not found: " + signature).that(start).isAtLeast(0);
        int open = source.indexOf('{', start + signature.length() - 1);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
        }
        throw new AssertionError("unbalanced method: " + signature);
    }
}
