package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * How Save Project and Open Project handle windows. Save Project used to keep only the window it was
 * chosen in, with the tabs' live connection settings, and Open Project put the tabs of every saved
 * window into one window, sorted the tab groups after every tab, set the bounds without checking the
 * screens and ignored the active tab. Proving the windows live needs a shown stage, so like
 * {@link SplitLayoutRestoreWiringTest} this test pins the sources, line-ending agnostic; the order
 * decisions themselves are tested in {@link ProjectRestoreOrderTest}.
 */
class ProjectWindowRestoreWiringTest {

    private static final Path MAIN_WINDOW = Path.of("src/main/java/de/kortty/ui/MainWindow.java");

    @Test
    void saveProjectKeepsEveryWindowWithItsActiveTab() throws IOException {
        String window = read();

        assertThat(methodBody(window, "private void saveProject() {"))
            .contains("captureAllWindows(this, CaptureOptions.PROJECT)");
        assertThat(methodBody(window, "static Project captureAllWindows(MainWindow first, CaptureOptions options) {"))
            .contains("ProjectRestoreOrder.captureOrder(first, openWindows)");

        String capture = methodBody(window, "private WindowState captureWindowState(CaptureOptions options) {");
        assertWithMessage("a maximized window is saved with its normal bounds")
            .that(capture).contains("MainWindowGeometrySupport.capture(");
        assertThat(capture).contains("lastNormalGeometry");
        assertThat(capture).contains("windowState.setActiveSessionId(");
        assertThat(capture).contains("sessionState.setSessionId(UUID.randomUUID().toString());");
    }

    @Test
    void aSavedTerminalTabHoldsACopyOfItsSettingsAndTheScreenOnlyWhenAsked() throws IOException {
        String tab = methodBody(read(), "private SessionState captureTabState(Tab tab, CaptureOptions options) {");

        assertThat(tab).contains("new ConnectionSettings(connection.getSettings())");
        assertThat(tab).doesNotContain("sessionState.setSettings(connection.getSettings());");
        String screen = tab.substring(tab.indexOf("if (options.includeScreen()) {"));
        screen = screen.substring(0, screen.indexOf("}"));
        assertThat(screen).contains("sessionState.setTerminalHistory(");
        assertThat(screen).contains("sessionState.setTerminalTimestamps(");
        assertThat(count(tab, "setTerminalHistory(")).isEqualTo(1);
    }

    @Test
    void openProjectGivesEveryFurtherWindowAWindowOfItsOwn() throws IOException {
        String window = read();

        assertThat(methodBody(window, "private void openProjectFile(Path path) {"))
            .contains("restoreProject(project, this);");
        String restore = methodBody(window, "static void restoreProject(Project project, MainWindow firstWindow) {");
        assertThat(restore).contains("ProjectRestoreOrder.windowsToRestore(project)");
        assertThat(restore).contains("firstWindow.restoreWindowState(windows.get(0), project, true);");
        assertThat(restore).contains("new MainWindow(new Stage())");
        assertThat(restore).contains("window.show(windowState.getGeometry(), windowState.getDashboardVisible() == null);");
        assertThat(restore).contains("window.restoreWindowState(windowState, project, false);");

        assertWithMessage("the saved bounds are checked against the screens attached now")
            .that(methodBody(window, "private void applyProjectGeometry(WindowGeometry stored) {"))
            .contains("MainWindowGeometrySupport.plan(stored, unifiedTitleBarEnabled)");
        String show = methodBody(window, "private void show(WindowGeometry projectGeometry, boolean dashboardFromSettings) {");
        assertThat(show).contains("MainWindowGeometrySupport.plan(geoToUse, unifiedTitleBarEnabled)");
        assertThat(show).contains("if (dashboardFromSettings && shouldRestoreDashboardOnStartup()) {");
    }

    @Test
    void tabGroupsAreSortedOnceAndTheActiveTabIsSelectedByItsKey() throws IOException {
        String window = read();
        String restoreWindow = methodBody(window,
            "private void restoreWindowState(WindowState windowState, Project project, boolean moveWindow) {");
        String restoreTab = methodBody(window, "private void restoreSavedTab(");

        assertThat(restoreWindow).contains("ProjectRestoreOrder.activeTabKey(windowState, keys)");
        assertThat(restoreWindow).contains("restore.syncTabsDone();");
        assertThat(restoreWindow).doesNotContain("organizeTabsByGroup");
        assertWithMessage("no regrouping after every restored tab").that(restoreTab).doesNotContain("organizeTabsByGroup");
        assertThat(count(restoreTab, "restore.opened(")).isEqualTo(4);

        String windowRestore = methodBody(window, "private final class WindowRestore {");
        assertThat(windowRestore).contains("reorganizeTabs(() -> sortTabsByGroup(ProjectRestoreOrder.savedOrder(");
        assertThat(windowRestore).contains("ProjectRestoreOrder.selectActive(");
        assertThat(windowRestore).contains("ProjectRestoreOrder.ACTIVE_TAB_WAIT_MILLIS");
        assertThat(windowRestore).contains("ProjectRestoreOrder.lateInsertionIndex(");
    }

    @Test
    void remoteTabsThatArriveLateAreAwaitedAndDroppedWhenAnotherProjectOpened() throws IOException {
        String window = read();
        String restoreTab = methodBody(window, "private void restoreSavedTab(");

        assertThat(count(restoreTab, "restore.pending(index);")).isEqualTo(2);
        assertThat(count(restoreTab, "restore.lateTabReady(owned, index, () -> {")).isEqualTo(2);
        assertWithMessage("a failed download stops the wait for it")
            .that(count(restoreTab, "Platform.runLater(() -> restore.lateTabDone(index));")).isEqualTo(2);

        String late = methodBody(window, "void lateTabReady(de.kortty.core.SFTPSession session, int index,");
        assertThat(late).contains("if (activeRestore == this) {");
        assertThat(late).contains("closeOwnedSftpSessionInBackground(session);");
        assertThat(methodBody(window, "static void restoreProject(Project project, MainWindow firstWindow) {"))
            .contains("firstWindow.activeRestore = null;");
    }

    private static String read() throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(MAIN_WINDOW, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
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
        throw new AssertionError("unbalanced braces after: " + signature);
    }
}
