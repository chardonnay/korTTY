package de.kortty.ui;

import de.kortty.model.Project;
import de.kortty.model.SessionState;
import de.kortty.model.WindowState;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Opening a project used to put the tabs of every saved window into the one window it was opened
 * in and to ignore the saved active tab. Now every saved window opens in a window of its own and
 * selects its active tab. That tab is found by its session id, because tab groups sort the restored
 * terminal tabs and remote file tabs arrive late, so an index points at the wrong tab; files saved
 * before the id existed fall back to their index, turned into that tab's key.
 */
class ProjectRestoreOrderTest {

    @Test
    void theActiveTabIsFoundByItsSessionIdAfterTabGroupsSortedTheTabs() {
        // Saved: web (group "prod"), db (no group), the README editor; db was active.
        WindowState window = window(List.of(terminal("s-web"), terminal("s-db"), editor(null)), "s-db", 1);
        List<String> keys = ProjectRestoreOrder.tabKeys(window.getTabs());
        String active = ProjectRestoreOrder.activeTabKey(window, keys);

        // Tab groups put the ungrouped db tab first: index 1 now shows web.
        List<String> live = List.of("s-db", "s-web", keys.get(2));
        ProjectRestoreOrder.Selection selection =
            ProjectRestoreOrder.selectActive(active, live, Set.of(), false, false);

        assertThat(active).isEqualTo("s-db");
        assertThat(selection.step()).isEqualTo(ProjectRestoreOrder.Step.SELECT);
        assertThat(selection.index()).isEqualTo(0);
    }

    @Test
    void aRemoteTabThatArrivesLateIsSelectedOnceItIsThere() {
        WindowState window = window(List.of(terminal("s-web"), remoteEditor("s-log")), "s-log", 1);
        List<String> keys = ProjectRestoreOrder.tabKeys(window.getTabs());
        String active = ProjectRestoreOrder.activeTabKey(window, keys);
        Set<String> pending = new HashSet<>(Set.of("s-log"));

        ProjectRestoreOrder.Selection beforeDownload =
            ProjectRestoreOrder.selectActive(active, List.of("s-web"), pending, false, false);
        pending.remove("s-log");
        ProjectRestoreOrder.Selection afterDownload =
            ProjectRestoreOrder.selectActive(active, List.of("s-web", "s-log"), pending, false, false);

        assertThat(beforeDownload.step()).isEqualTo(ProjectRestoreOrder.Step.WAIT);
        assertThat(afterDownload.step()).isEqualTo(ProjectRestoreOrder.Step.SELECT);
        assertThat(afterDownload.index()).isEqualTo(1);
    }

    @Test
    void theWindowStopsWaitingWhenTheDownloadFailsTimesOutOrTheUserChoseATab() {
        Set<String> pending = Set.of("s-log");
        List<String> live = List.of("s-web");

        assertThat(ProjectRestoreOrder.selectActive("s-log", live, Set.of(), false, false).step())
            .isEqualTo(ProjectRestoreOrder.Step.KEEP);
        assertThat(ProjectRestoreOrder.selectActive("s-log", live, pending, true, false).step())
            .isEqualTo(ProjectRestoreOrder.Step.KEEP);
        assertThat(ProjectRestoreOrder.selectActive("s-log", List.of("s-web", "s-log"), Set.of(), false, true).step())
            .isEqualTo(ProjectRestoreOrder.Step.KEEP);
        assertThat(ProjectRestoreOrder.ACTIVE_TAB_WAIT_MILLIS).isEqualTo(10_000L);
    }

    @Test
    void oldFilesNameTheActiveTabByIndexWhichFindsThatTabAfterSorting() {
        // Saved before activeSessionId: only the index of the active tab.
        WindowState window = window(List.of(terminal("s-web"), terminal("s-db"), editor(null)), null, 2);
        List<String> keys = ProjectRestoreOrder.tabKeys(window.getTabs());
        String active = ProjectRestoreOrder.activeTabKey(window, keys);

        // The editor had no session id: it goes by its saved index.
        assertThat(keys).containsExactly("s-web", "s-db", "#2").inOrder();
        assertThat(active).isEqualTo("#2");
        ProjectRestoreOrder.Selection selection =
            ProjectRestoreOrder.selectActive(active, List.of("s-db", "s-web", "#2"), Set.of(), false, false);
        assertThat(selection.index()).isEqualTo(2);

        WindowState byIndex = window(List.of(terminal("s-web"), terminal("s-db")), null, 1);
        assertThat(ProjectRestoreOrder.activeTabKey(byIndex, ProjectRestoreOrder.tabKeys(byIndex.getTabs())))
            .isEqualTo("s-db");
    }

    @Test
    void anActiveTabThatIsGoneOrOutOfRangeKeepsTheSelection() {
        WindowState gone = window(List.of(terminal("s-web"), terminal("s-db")), "s-deleted", 1);
        assertWithMessage("an id that names no saved tab does not fall back to the index")
            .that(ProjectRestoreOrder.activeTabKey(gone, ProjectRestoreOrder.tabKeys(gone.getTabs()))).isNull();

        WindowState notSaved = window(List.of(terminal("s-web")), null, -1);
        assertThat(ProjectRestoreOrder.activeTabKey(notSaved, ProjectRestoreOrder.tabKeys(notSaved.getTabs()))).isNull();
        WindowState outOfRange = window(List.of(terminal("s-web")), null, 7);
        assertThat(ProjectRestoreOrder.activeTabKey(outOfRange, ProjectRestoreOrder.tabKeys(outOfRange.getTabs()))).isNull();
        assertThat(ProjectRestoreOrder.activeTabKey(window(List.of(), "s-web", 0), List.of())).isNull();

        assertThat(ProjectRestoreOrder.selectActive(null, List.of("s-web"), Set.of(), false, false).step())
            .isEqualTo(ProjectRestoreOrder.Step.KEEP);
    }

    @Test
    void duplicateBlankOrIndexLikeSessionIdsGetAKeyOfTheirOwn() {
        List<String> keys = ProjectRestoreOrder.tabKeys(Arrays.asList(
            terminal("same"), terminal("same"), terminal("  "), terminal("#0"), null));

        assertThat(keys).containsExactly("same", "#1", "#2", "#3", "#4").inOrder();
        assertThat(new HashSet<>(keys)).hasSize(5);
    }

    @Test
    void restoredTabsTakeTheirSavedOrderBehindTabsTheRestoreDidNotOpen() {
        // A tab the window kept (savedIndex -1) stays ahead.
        Map<String, Integer> saved = Map.of("kept", -1, "db", 1, "web", 0, "readme", 2);
        List<String> live = List.of("db", "kept", "readme", "web");

        assertThat(ProjectRestoreOrder.savedOrder(live, saved::get))
            .containsExactly("kept", "web", "db", "readme").inOrder();
    }

    @Test
    void aLateTabGoesInFrontOfTheFirstTabSavedAfterIt() {
        // Terminal tabs (null) are sorted by tab group and do not count.
        assertThat(ProjectRestoreOrder.lateInsertionIndex(Arrays.asList(null, null, 1, 4), 3)).isEqualTo(3);
        assertThat(ProjectRestoreOrder.lateInsertionIndex(Arrays.asList(null, 1, 2), 5)).isEqualTo(3);
        assertThat(ProjectRestoreOrder.lateInsertionIndex(Arrays.asList(null, 4), 0)).isEqualTo(1);
        assertThat(ProjectRestoreOrder.lateInsertionIndex(List.of(), 0)).isEqualTo(0);
    }

    @Test
    void aProjectIsSavedFromTheWindowItWasChosenInFirstThenTheOthersOnce() {
        Object first = "second window";
        List<Object> open = List.of("first window", "second window", "third window");

        assertThat(ProjectRestoreOrder.captureOrder(first, open))
            .containsExactly("second window", "first window", "third window").inOrder();
        assertThat(ProjectRestoreOrder.captureOrder(null, open)).containsExactlyElementsIn(open).inOrder();

        assertWithMessage("the window it was saved from, for its geometry")
            .that(ProjectRestoreOrder.savesWindow(true, 0)).isTrue();
        assertWithMessage("a window of AI and tool tabs only").that(ProjectRestoreOrder.savesWindow(false, 0)).isFalse();
        assertThat(ProjectRestoreOrder.savesWindow(false, 2)).isTrue();
    }

    @Test
    void everySavedWindowOpensUpToTheLimit() {
        Project project = new Project("many");
        for (int i = 0; i < ProjectRestoreOrder.MAX_WINDOWS + 3; i++) {
            project.addWindow(new WindowState("w" + i));
        }
        project.getWindows().add(1, null);

        List<WindowState> windows = ProjectRestoreOrder.windowsToRestore(project);

        assertThat(windows).hasSize(ProjectRestoreOrder.MAX_WINDOWS);
        assertThat(windows.get(0).getWindowId()).isEqualTo("w0");
        assertThat(windows.get(1).getWindowId()).isEqualTo("w1");
        assertThat(ProjectRestoreOrder.windowsToRestore(new Project("empty"))).isEmpty();
        assertThat(ProjectRestoreOrder.windowsToRestore(null)).isEmpty();
    }

    @Test
    void tabsStillWaitingGoBackToTheirSavedPlacesInTheSessionSnapshot() {
        // Saved: a(0) b(1) c(2) d(3) e(4). Open: a, c, e (and a new tab x the restore did not open);
        // b waits for a password in the restore bar, d is still downloading.
        java.util.SortedMap<Integer, String> waiting = new java.util.TreeMap<>(Map.of(1, "b", 3, "d"));

        List<String> tabs = ProjectRestoreOrder.withWaitingTabs(
            List.of("a", "c", "e", "x"), Arrays.asList(0, 2, 4, null), waiting);

        assertThat(tabs).containsExactly("a", "b", "c", "d", "e", "x").inOrder();
    }

    @Test
    void waitingTabsAfterEveryOpenTabGoLastAndNothingWaitingChangesNothing() {
        java.util.SortedMap<Integer, String> waiting = new java.util.TreeMap<>(Map.of(5, "f", 0, "a"));

        assertThat(ProjectRestoreOrder.withWaitingTabs(List.of("c", "x"), Arrays.asList(2, null), waiting))
            .containsExactly("a", "c", "x", "f").inOrder();
        assertThat(ProjectRestoreOrder.withWaitingTabs(List.of("c", "x"), Arrays.asList(2, null), new java.util.TreeMap<>()))
            .containsExactly("c", "x").inOrder();
        assertWithMessage("a window whose every tab waits keeps them all, in saved order")
            .that(ProjectRestoreOrder.withWaitingTabs(List.<String>of(), List.of(), waiting))
            .containsExactly("a", "f").inOrder();
    }

    private static WindowState window(List<SessionState> tabs, String activeSessionId, int activeTabIndex) {
        WindowState window = new WindowState("window");
        window.setTabs(new ArrayList<>(tabs));
        window.setActiveSessionId(activeSessionId);
        window.setActiveTabIndex(activeTabIndex);
        return window;
    }

    private static SessionState terminal(String sessionId) {
        SessionState state = new SessionState(sessionId, "connection");
        state.setTabType(SessionState.TabType.TERMINAL);
        return state;
    }

    private static SessionState editor(String sessionId) {
        SessionState state = new SessionState();
        state.setSessionId(sessionId);
        state.setTabType(SessionState.TabType.FILE_EDITOR);
        state.setEditorFilePath("/home/me/README.md");
        state.setEditorIsRemote(false);
        return state;
    }

    private static SessionState remoteEditor(String sessionId) {
        SessionState state = editor(sessionId);
        state.setEditorIsRemote(true);
        state.setEditorFilePath("/var/log/syslog");
        return state;
    }
}
