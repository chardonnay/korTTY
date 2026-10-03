package de.kortty.ui;

import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import de.kortty.ui.ClosedTabHistory.ClosedTab;
import de.kortty.ui.ClosedTabHistory.Entry;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Which closes Recently Closed remembers: every close the user asks for, each as one entry, and
 * nothing from quitting, from a session that ended on its own or from the window's own tab
 * shuffling — so neither a quit nor a bulk close floods the history. The decisions are checked on
 * {@link RecentlyClosedRecorder}; the wiring of the close paths is pinned against the sources, since
 * neither {@link MainWindow} nor {@link TerminalTab} can be built without a JavaFX stage.
 */
class RecentlyClosedRecorderTest {

    private static final Path UI_ROOT = Path.of("src/main/java/de/kortty/ui");

    // ---- decisions ---------------------------------------------------------------------------

    @Test
    void eachTabTheUserClosesIsOneEntry() {
        ClosedTabHistory history = new ClosedTabHistory();
        RecentlyClosedRecorder recorder = new RecentlyClosedRecorder(history);
        ClosedTab web = tab("web");
        ClosedTab db = tab("db");

        recorder.tabsClosed(List.of(web));
        recorder.tabsClosed(List.of(db));

        assertThat(history.entries()).containsExactly(
            new Entry(List.of(db), false), new Entry(List.of(web), false)).inOrder();
    }

    @Test
    void aBulkCloseIsOneEntryAndPushesNothingElseOut() {
        ClosedTabHistory history = new ClosedTabHistory();
        RecentlyClosedRecorder recorder = new RecentlyClosedRecorder(history);
        for (int i = 0; i < ClosedTabHistory.MAX_ENTRIES - 1; i++) {
            recorder.tabsClosed(List.of(tab("single-" + i)));
        }
        Entry oldest = history.entries().get(history.size() - 1);
        List<ClosedTab> closeAll = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            closeAll.add(tab("bulk-" + i));
        }

        recorder.tabsClosed(closeAll);

        assertThat(history.size()).isEqualTo(ClosedTabHistory.MAX_ENTRIES);
        assertThat(history.latest().orElseThrow().tabs()).containsExactlyElementsIn(closeAll).inOrder();
        assertThat(history.latest().orElseThrow().window()).isFalse();
        assertWithMessage("Close All Tabs on 40 tabs must not push the single closes out")
            .that(history.entries()).contains(oldest);
    }

    @Test
    void aCloseWithoutTerminalTabsRecordsNothing() {
        ClosedTabHistory history = new ClosedTabHistory();
        RecentlyClosedRecorder recorder = new RecentlyClosedRecorder(history);

        recorder.tabsClosed(List.of());
        recorder.windowClosed(List.of(), false);

        assertThat(history.isEmpty()).isTrue();
    }

    @Test
    void aWindowClosedWhileKorttyKeepsRunningIsOneEntry() {
        ClosedTabHistory history = new ClosedTabHistory();
        RecentlyClosedRecorder recorder = new RecentlyClosedRecorder(history);
        List<ClosedTab> windowTabs = List.of(tab("web"), tab("db"), tab("cache"));

        recorder.windowClosed(windowTabs, false);

        assertThat(history.size()).isEqualTo(1);
        Entry entry = history.latest().orElseThrow();
        assertThat(entry.window()).isTrue();
        assertThat(entry.tabs()).containsExactlyElementsIn(windowTabs).inOrder();
    }

    @Test
    void quittingRecordsNothingHoweverManyWindowsCloseWithIt() {
        ClosedTabHistory history = new ClosedTabHistory();
        RecentlyClosedRecorder recorder = new RecentlyClosedRecorder(history);
        recorder.tabsClosed(List.of(tab("before-quit")));

        for (int window = 0; window < 5; window++) {
            List<ClosedTab> tabs = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                tabs.add(tab("w" + window + "-" + i));
            }
            recorder.windowClosed(tabs, true);
        }

        assertThat(history.size()).isEqualTo(1);
        assertThat(history.latest().orElseThrow().tabs().get(0).snapshot().getHost()).isEqualTo("before-quit");
    }

    // ---- wiring ------------------------------------------------------------------------------

    @Test
    void theCloseButtonRecordsAfterItsQuestionAndBeforeItReleasesAnything() throws IOException {
        String tab = source("TerminalTab.java");
        String request = tab.substring(tab.indexOf("setOnCloseRequest(event -> {"));
        request = request.substring(0, request.indexOf("});"));

        int confirm = request.indexOf("if (!confirmUserClose()) {");
        int record = request.indexOf("notifyUserCloseApproved();");
        int release = request.indexOf("releaseResources();");
        assertThat(confirm).isAtLeast(0);
        assertThat(record).isGreaterThan(confirm);
        assertWithMessage("the group, name and effect are read before the tab releases its panes")
            .that(record).isLessThan(release);
    }

    @Test
    void aSessionThatEndedOnItsOwnIsNotRemembered() throws IOException {
        String tab = source("TerminalTab.java");
        String silently = methodBody(tab, "private void closeTabSilently() {");

        assertThat(silently).doesNotContain("notifyUserCloseApproved");
        assertThat(silently).doesNotContain("onUserCloseApproved");
        assertWithMessage("only the close request runs the callback")
            .that(occurrences(tab, "notifyUserCloseApproved();")).isEqualTo(1);
    }

    @Test
    void everyTerminalTabTheWindowCreatesRecordsItsCloseButton() throws IOException {
        String window = source("MainWindow.java");

        int created = occurrences(window, "new TerminalTab(");
        assertThat(created).isAtLeast(3);
        assertWithMessage("a terminal tab without the callback would close without a Recently Closed entry")
            .that(occurrences(window, ".setOnUserCloseApproved(MainWindow::recordClosedByButton);"))
            .isEqualTo(created);
    }

    @Test
    void theUserCloseFunnelAndCloseAllTabsRecordBeforeTheyDispose() throws IOException {
        String window = source("MainWindow.java");

        String funnel = methodBody(window, "private boolean closeTabsByUser(List<Tab> tabs, Tab keepSelected, CloseCause cause) {");
        assertThat(funnel.indexOf("recordUserClosedTabs(targets, cause);"))
            .isLessThan(funnel.indexOf("disposeTabContent(tab);"));

        String closeAll = methodBody(window, "private boolean closeAllTabsGuarded() {");
        int asked = closeAll.indexOf("if (!confirmHostedTabsClose()) {");
        int record = closeAll.indexOf("recordUserClosedTabs(new ArrayList<>(tabPane.getTabs()), CloseCause.CLOSE_ALL);");
        int close = closeAll.indexOf("closeAllTabs();");
        assertThat(asked).isAtLeast(0);
        assertThat(record).isGreaterThan(asked);
        assertThat(record).isLessThan(close);

        assertThat(methodBody(window, "private void recordUserClosedTabs(List<Tab> tabs, CloseCause cause) {"))
            .contains("recordClosedTabs(tabs, cause);");
        assertThat(methodBody(window, "private static void recordClosedTabs(List<? extends Tab> tabs, CloseCause cause) {"))
            .contains("recentlyClosedRecorder.tabsClosed(closed);");
    }

    @Test
    void windowTeardownProjectsAndRegroupingRecordNothing() throws IOException {
        String window = source("MainWindow.java");

        for (String signature : List.of(
                "private void closeAllTabs() {",
                "private void loadProject(Project project) {",
                "private void organizeTabsByGroup() {")) {
            String body = methodBody(window, signature);
            assertWithMessage(signature).that(body).doesNotContain("recordUserClosedTabs");
            assertWithMessage(signature).that(body).doesNotContain("recordClosedTabs");
            assertWithMessage(signature).that(body).doesNotContain("recentlyClosedRecorder");
            assertWithMessage(signature).that(body).doesNotContain("recordClosedWindow");
        }
    }

    @Test
    void aClosedWindowRecordsOnceUnlessKorttyEndsWithIt() throws IOException {
        String window = source("MainWindow.java");

        int handler = window.indexOf("stage.setOnCloseRequest(e -> {");
        assertThat(handler).isAtLeast(0);
        String close = window.substring(handler);
        int confirmed = close.indexOf("boolean closeConfirmed =");
        int record = close.indexOf("recordClosedWindow(willCloseApplication());");
        int teardown = close.indexOf("closeAllTabs();");
        assertThat(confirmed).isAtLeast(0);
        assertWithMessage("only a close every question agreed to is remembered")
            .that(record).isGreaterThan(confirmed);
        assertWithMessage("the tabs are read before they are released")
            .that(record).isLessThan(teardown);
        assertThat(occurrences(window, "recordClosedWindow(")).isEqualTo(2); // the call and the method

        assertWithMessage("a quit (all windows) and the last window on Windows and Linux end korTTY")
            .that(methodBody(window, "private boolean willCloseApplication() {"))
            .contains("return applicationQuitRequested || (openWindows.size() <= 1 && !app.shouldKeepRunningAfterLastWindowClosed());");
        assertThat(methodBody(window, "private void recordClosedWindow(boolean endsApplication) {"))
            .contains("recentlyClosedRecorder.windowClosed(captureClosedTabs(tabPane.getTabs()), endsApplication);");
    }

    @Test
    void reopenSignsInThroughTheSharedFlowAndAsksForATemporaryKeyOnlyAfterThePolicy() throws IOException {
        String window = source("MainWindow.java");

        String here = methodBody(window, "private boolean reopenClosedTabHere(ClosedTabHistory.ClosedTab closed) {");
        int policy = here.indexOf("ServerAccessPolicy.firstBlockedTarget(connection)");
        int key = here.indexOf("requestNewTemporarySSHKey(connection)");
        int connect = here.indexOf("connectSavedConnection(connection, false,");
        assertThat(policy).isAtLeast(0);
        assertThat(key).isGreaterThan(policy);
        assertThat(connect).isGreaterThan(key);
        assertThat(here).contains("return ClosedTabHistory.keepsEntry(status);");
        assertWithMessage("reopen must not bypass the policy gate and sign-in of connectSavedConnection")
            .that(here).doesNotContain("openConnectionAndReturnTab(");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static ClosedTab tab(String host) {
        ServerConnection connection = new ServerConnection(null, host, 22, "me");
        connection.setAuthMethod(AuthMethod.PASSWORD);
        return ClosedTab.capture(connection, false, null, null, null, null);
    }

    private static String source(String fileName) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(UI_ROOT.resolve(fileName), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static int occurrences(String source, String text) {
        Matcher matcher = Pattern.compile(Pattern.quote(text)).matcher(source);
        int count = 0;
        while (matcher.find()) {
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
        throw new AssertionError("unbalanced method: " + signature);
    }
}
