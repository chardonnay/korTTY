package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Where the session snapshot hooks into korTTY. Quitting, closing windows and opening projects need a
 * shown stage, so like {@link ProjectWindowRestoreWiringTest} this test pins the sources, line-ending
 * agnostic; the rules themselves are tested in {@link SessionAutosaveRuleTest} and
 * {@code SessionSnapshotStoreTest}.
 */
class SessionSnapshotWiringTest {

    private static final Path MAIN_WINDOW = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final Path APPLICATION = Path.of("src/main/java/de/kortty/KorTTYApplication.java");

    @Test
    void quitWritesAndSealsTheSnapshotBeforeTheFirstWindowCloses() throws IOException {
        String quit = methodBody(read(MAIN_WINDOW), "public static void requestApplicationQuit() {");

        int approved = quit.indexOf("applicationQuitRequested = true;\n        // Every window agreed");
        int seal = quit.indexOf("sealSessionSnapshotForExit();");
        int closing = quit.indexOf("window.fireCloseRequest();");
        assertThat(approved).isAtLeast(0);
        assertWithMessage("sealed after every window agreed").that(seal).isGreaterThan(approved);
        assertWithMessage("sealed before the first window closes its tabs").that(seal).isLessThan(closing);
    }

    @Test
    void theWindowThatEndsKorttyWritesTheSnapshotBeforeItsTabsClose() throws IOException {
        String window = read(MAIN_WINDOW);
        String handler = window.substring(window.indexOf("stage.setOnCloseRequest(e -> {"));
        handler = handler.substring(0, handler.indexOf("private static final double DEFAULT_SCENE_WIDTH"));

        int seal = handler.indexOf("if (willCloseApplication()) {\n                    // korTTY ends with this window");
        assertThat(seal).isAtLeast(0);
        assertThat(handler.indexOf("sealSessionSnapshotForExit();")).isGreaterThan(seal);
        assertThat(handler.indexOf("sealSessionSnapshotForExit();")).isLessThan(handler.indexOf("recordClosedWindow(willCloseApplication());"));
        assertThat(handler.indexOf("sealSessionSnapshotForExit();")).isLessThan(handler.indexOf("closeAllTabs();"));
        assertWithMessage("a window closed while korTTY runs leaves the snapshot")
            .that(handler.indexOf("markSessionDirty();")).isGreaterThan(handler.indexOf("openWindows.remove(this);"));
    }

    @Test
    void theSnapshotFollowsTheWindowsTabsAndRecentlyClosedList() throws IOException {
        String window = read(MAIN_WINDOW);

        String tabs = window.substring(window.indexOf("tabPane.getTabs().addListener((javafx.collections.ListChangeListener.Change<? extends Tab> change) -> {"));
        assertThat(tabs.substring(0, 300)).contains("markSessionDirty();");
        assertThat(methodBody(window, "private void toggleDashboard(boolean show) {")).contains("markSessionDirty();");
        assertThat(methodBody(window, "private void onTerminalWidgetSetChanged(TerminalTab terminalTab) {")).contains("markSessionDirty();");
        assertThat(methodBody(window, "private void promptRenameTab(TerminalTab terminalTab) {")).contains("markSessionDirty();");
        assertThat(methodBody(window, "private static void syncRecentlyClosedMenusInAllWindows() {")).contains("markSessionDirty();");
        assertThat(methodBody(window, "private void refreshRestoreAttentionBar() {")).contains("markSessionDirty();");
        assertThat(methodBody(window, "private void installWindowGeometryPersistence() {")).contains("markSessionDirty();");
    }

    @Test
    void aSessionCaptureHoldsNoScreenTextAndKeepsTheTabsAWindowStillWaitsFor() throws IOException {
        String window = read(MAIN_WINDOW);

        String options = methodBody(window, "record CaptureOptions(boolean includeScreen, boolean includeWaitingTabs) {");
        assertThat(options).contains("static final CaptureOptions SESSION = new CaptureOptions(false, true);");
        assertThat(options).contains("static final CaptureOptions PROJECT = new CaptureOptions(true, false);");
        String session = methodBody(window, "private static SessionAutosaveCoordinator.Capture captureSession() {");
        assertThat(session).contains("window.captureWindowState(CaptureOptions.SESSION)");
        assertThat(session).contains("project.setAutoReconnect(true);");
        assertThat(session).contains("ClosedTabSnapshots.toSnapshot(closedTabHistory.entries())");
        assertThat(methodBody(window, "private WindowState captureWindowState(CaptureOptions options) {"))
            .contains("ProjectRestoreOrder.withWaitingTabs(");
        assertWithMessage("a restored tab that has not connected yet keeps the split layout it rebuilds")
            .that(methodBody(window, "private SessionState captureTabState(Tab tab, CaptureOptions options) {"))
            .contains("terminalTab.getPendingSplitLayout()");
        assertThat(methodBody(window, "private void restoreSavedTab(")).contains("restoredTab.setPendingSplitLayout(null);");
    }

    @Test
    void aRestoreKeepsTheSnapshotWaitingUntilItsWindowsHaveTheirTabs() throws IOException {
        String restore = methodBody(read(MAIN_WINDOW), "static void restoreProject(Project project, MainWindow firstWindow) {");

        assertThat(restore).contains("sessionAutosave.beginRestore();");
        String finallyBlock = restore.substring(restore.indexOf("} finally {"));
        assertThat(finallyBlock).contains("sessionAutosave.endRestore();");

        // Connect… and the vault retry take a tab out of the bar before they open it.
        String reopen = methodBody(read(MAIN_WINDOW), "private void reopenDeferredTabs(Runnable reopen) {");
        assertThat(reopen.indexOf("sessionAutosave.beginRestore();")).isLessThan(reopen.indexOf("reopen.run();"));
        assertThat(reopen.substring(reopen.indexOf("} finally {"))).contains("sessionAutosave.endRestore();");
    }

    @Test
    void fileRestorePreviousSessionOpensThePreviousSessionOncePerRun() throws IOException {
        String window = read(MAIN_WINDOW);

        String file = methodBody(window, "private Menu createFileMenu() {");
        assertThat(file).contains("MenuItem restorePreviousSession = menuItem(\"menu.file.restorePreviousSession\");");
        assertThat(file).contains("openProject, openRecent, saveProject, restorePreviousSession, new SeparatorMenuItem(),");
        assertThat(file.substring(file.indexOf("fileMenu.setOnShowing("))).contains("syncRestorePreviousSessionMenuItems();");

        String restore = methodBody(window, "private void restorePreviousSession() {");
        assertThat(restore).contains("sessionAutosave.loadPrevious()");
        assertThat(restore).contains("sessionAutosave.markPreviousRestored();");
        assertThat(restore).contains("restoreProject(project, target);");
        assertWithMessage("a window with tabs keeps them; the previous session gets a window of its own")
            .that(restore).contains("if (!tabPane.getTabs().isEmpty()) {");
    }

    @Test
    void korttyStartsTheSnapshotBeforeTheFirstWindowAndSealsItInTheShutdown() throws IOException {
        String application = read(APPLICATION);

        int start = application.indexOf("sessionAutosave = MainWindow.startSessionAutosave(");
        assertThat(start).isAtLeast(0);
        assertThat(start).isLessThan(application.indexOf("MainWindow mainWindow = new MainWindow(primaryStage);"));
        assertThat(application).contains("() -> !restoredBackupAwaitsRestart);");
        assertThat(methodBody(application, "private synchronized void performShutdown() {"))
            .contains("shutdownStep(\"seal session snapshot\", sessionAutosave::sealOnShutdown);");
        assertWithMessage("the telemetry consent prompt is left as it is")
            .that(application).contains("Platform.runLater(() -> de.kortty.ui.TelemetryConsentDialog.maybeShow(this, primaryStage));");
    }

    @Test
    void theRecentlyClosedListOfTheLastRunSeedsTheHistoryAsIdsOnly() throws IOException {
        String start = methodBody(read(MAIN_WINDOW), "public static SessionAutosaveCoordinator startSessionAutosave(");

        assertThat(start).contains("SessionSnapshotStore.StartupState startup = store.startUp();");
        assertThat(start).contains("closedTabHistory.seed(ClosedTabSnapshots.fromSnapshot(startup.recentlyClosed(),");
        assertThat(start).contains("application.getConfigManager().getConnectionById(id)");
    }

    private static String read(Path path) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from {@code signature} to the brace closing its body. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("method not found: " + signature).that(start).isAtLeast(0);
        int open = source.indexOf('{', start);
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
