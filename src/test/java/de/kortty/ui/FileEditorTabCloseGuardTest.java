package de.kortty.ui;

import javafx.event.Event;
import javafx.scene.control.Tab;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * Closing a file editor tab with unsaved changes asks first (Save / Discard / Cancel) on every
 * path — its close button, its own Close button and Cmd/Ctrl+W, the main window's Close Tab, Close
 * All, opening a project and closing the window — and Cancel keeps the tab. Pure: a {@link Tab}
 * needs no JavaFX toolkit; the {@link FileEditorTab} itself (a Monaco WebView) is pinned against
 * its source.
 */
class FileEditorTabCloseGuardTest {

    /** Stand-in for a FileEditorTab: the same decision, a scripted answer instead of the alert. */
    private static final class EditorLike extends Tab implements HostedCloseGuard {
        private final boolean modified;
        private final UnsavedChangesClose.Choice answer;
        private final boolean saveSucceeds;
        int asked;
        int saved;

        EditorLike(String name, boolean modified, UnsavedChangesClose.Choice answer, boolean saveSucceeds) {
            super(name);
            this.modified = modified;
            this.answer = answer;
            this.saveSucceeds = saveSucceeds;
            UnsavedChangesClose.guardCloseRequest(this, this);
        }

        static EditorLike clean(String name) {
            return new EditorLike(name, false, null, true);
        }

        static EditorLike dirty(String name, UnsavedChangesClose.Choice answer) {
            return new EditorLike(name, true, answer, true);
        }

        @Override
        public boolean confirmHostedClose() {
            return UnsavedChangesClose.mayClose(modified, () -> {
                asked++;
                return answer;
            }, () -> {
                saved++;
                return saveSucceeds;
            });
        }

        @Override
        public boolean needsCloseConfirmation() {
            return modified;
        }
    }

    // ---- The decision -----------------------------------------------------------------------

    @Test
    void cleanEditorClosesWithoutAsking() {
        int[] asked = {0};
        int[] saved = {0};

        boolean closes = UnsavedChangesClose.mayClose(false, () -> {
            asked[0]++;
            return UnsavedChangesClose.Choice.CANCEL;
        }, () -> {
            saved[0]++;
            return true;
        });

        assertThat(closes).isTrue();
        assertThat(asked[0]).isEqualTo(0);
        assertThat(saved[0]).isEqualTo(0);
    }

    @Test
    void dirtyEditorAsksAndCancelKeepsIt() {
        EditorLike editor = EditorLike.dirty("app.conf *", UnsavedChangesClose.Choice.CANCEL);

        assertThat(editor.confirmHostedClose()).isFalse();
        assertThat(editor.asked).isEqualTo(1);
        assertThat(editor.saved).isEqualTo(0);
    }

    @Test
    void dismissedQuestionCountsAsCancel() {
        EditorLike editor = EditorLike.dirty("app.conf *", null);

        assertThat(editor.confirmHostedClose()).isFalse();
        assertThat(editor.saved).isEqualTo(0);
    }

    @Test
    void discardClosesWithoutSaving() {
        EditorLike editor = EditorLike.dirty("app.conf *", UnsavedChangesClose.Choice.DISCARD);

        assertThat(editor.confirmHostedClose()).isTrue();
        assertThat(editor.saved).isEqualTo(0);
    }

    @Test
    void saveClosesOnlyWhenTheSaveSucceeded() {
        EditorLike saves = EditorLike.dirty("app.conf *", UnsavedChangesClose.Choice.SAVE);
        assertThat(saves.confirmHostedClose()).isTrue();
        assertThat(saves.saved).isEqualTo(1);

        // E.g. the SFTP connection is gone: the error is shown and the changes stay in the tab.
        EditorLike fails = new EditorLike("app.conf *", true, UnsavedChangesClose.Choice.SAVE, false);
        assertThat(fails.confirmHostedClose()).isFalse();
        assertThat(fails.saved).isEqualTo(1);
    }

    // ---- The tab's close button --------------------------------------------------------------

    @Test
    void closeButtonOfADirtyTabAsksAndCancelKeepsTheTab() {
        EditorLike editor = EditorLike.dirty("app.conf *", UnsavedChangesClose.Choice.CANCEL);

        assertThat(closeButtonCloses(editor)).isFalse();
        assertThat(editor.asked).isEqualTo(1);
    }

    @Test
    void closeButtonClosesAfterDiscardOrSaveAndACleanTabWithoutAsking() {
        assertThat(closeButtonCloses(EditorLike.dirty("a *", UnsavedChangesClose.Choice.DISCARD))).isTrue();
        assertThat(closeButtonCloses(EditorLike.dirty("b *", UnsavedChangesClose.Choice.SAVE))).isTrue();

        EditorLike clean = EditorLike.clean("c");
        assertThat(closeButtonCloses(clean)).isTrue();
        assertThat(clean.asked).isEqualTo(0);
    }

    // ---- The main window's close paths -------------------------------------------------------

    @Test
    void closeTabCommandAsksTheEditorAndCancelKeepsIt() {
        // File > Close Tab / Cmd+W remove the tab from the list, which fires no close request.
        EditorLike editor = EditorLike.dirty("app.conf *", UnsavedChangesClose.Choice.CANCEL);

        assertThat(HostedCloseGuards.needsCloseConfirmation(editor)).isTrue();
        assertThat(HostedCloseGuards.confirmTab(editor)).isFalse();
        assertThat(editor.asked).isEqualTo(1);

        Tab plain = new Tab("Terminal");
        assertThat(HostedCloseGuards.needsCloseConfirmation(plain)).isFalse();
        assertThat(HostedCloseGuards.confirmTab(plain)).isTrue();
    }

    @Test
    void closeAllAsksOnlyDirtyEditorsAndOneCancelKeepsEveryTab() {
        EditorLike clean = EditorLike.clean("clean.txt");
        EditorLike discarded = EditorLike.dirty("first.txt *", UnsavedChangesClose.Choice.DISCARD);
        EditorLike cancelled = EditorLike.dirty("second.txt *", UnsavedChangesClose.Choice.CANCEL);
        EditorLike later = EditorLike.dirty("third.txt *", UnsavedChangesClose.Choice.DISCARD);
        List<Tab> selected = new ArrayList<>();

        boolean closeAll = HostedCloseGuards.confirmTabs(
            List.of(new Tab("Terminal"), clean, discarded, cancelled, later), selected::add);

        assertThat(closeAll).isFalse();
        // Each editor that asks is shown first, in tab order; the question stops at the Cancel.
        assertThat(selected).containsExactly(discarded, cancelled).inOrder();
        assertThat(clean.asked).isEqualTo(0);
        assertThat(later.asked).isEqualTo(0);
    }

    @Test
    void closeAllProceedsOnceEveryDirtyEditorAgreed() {
        EditorLike saved = EditorLike.dirty("first.txt *", UnsavedChangesClose.Choice.SAVE);
        EditorLike discarded = EditorLike.dirty("second.txt *", UnsavedChangesClose.Choice.DISCARD);
        EditorLike pinned = EditorLike.dirty("pinned.txt *", UnsavedChangesClose.Choice.CANCEL);
        pinned.setClosable(false);

        assertThat(HostedCloseGuards.confirmTabs(List.of(saved, discarded, pinned), null)).isTrue();
        assertThat(saved.saved).isEqualTo(1);
        assertThat(discarded.asked).isEqualTo(1);
        // Close All leaves a tab that cannot be closed alone, so it is not asked either.
        assertThat(pinned.asked).isEqualTo(0);
    }

    // ---- Every close path of the real tab goes through the guard ------------------------------

    @Test
    void fileEditorTabAsksOnItsCloseButtonCloseButtonAndShortcut() throws IOException {
        String editor = read("src/main/java/de/kortty/ui/FileEditorTab.java");

        assertThat(editor).contains("public class FileEditorTab extends Tab implements HostedCloseGuard {");
        // Remote and local constructor: the tab's close button asks.
        assertThat(count(editor, "UnsavedChangesClose.guardCloseRequest(this, this);")).isEqualTo(2);
        assertThat(body(editor, "public boolean confirmHostedClose()"))
            .contains("UnsavedChangesClose.mayClose(isModified, this::askAboutUnsavedChanges, this::save)");
        assertThat(body(editor, "public boolean needsCloseConfirmation()")).contains("return isModified;");
        // The editor's Close button and its Cmd+W only remove the tab after the same question.
        String closeTab = body(editor, "private void closeTab()");
        assertThat(closeTab).contains("if (confirmHostedClose()) {");
        assertThat(closeTab.indexOf("confirmHostedClose()")).isLessThan(closeTab.indexOf("removeTabSafely();"));
        // A failed save reports it, so the question keeps the tab.
        String save = body(editor, "private boolean save()");
        assertThat(save).contains("return true;");
        assertThat(save).contains("return false;");
    }

    @Test
    void mainWindowAsksBeforeEveryCloseThatBypassesTheTabsCloseRequest() throws IOException {
        String window = read("src/main/java/de/kortty/ui/MainWindow.java");

        // File > Close Tab / Cmd+W and the Dashboard's Close go through the user-close funnel, which
        // asks every tab before it disposes any (pinned in TerminalTabCloseConfirmationTest).
        String closeCurrent = body(window, "private void closeCurrentTab()");
        assertThat(closeCurrent).contains("closeTabsByUser(List.of(currentTab), CloseCause.CLOSE_TAB_COMMAND)");
        assertThat(body(window, "private static boolean confirmUserClose(Tab tab)"))
            .contains("return HostedCloseGuards.confirmTab(tab);");

        // Every tab of the window: Close All, opening a project, window close and quit.
        assertThat(body(window, "private boolean confirmHostedTabsClose()"))
            .contains("HostedCloseGuards.confirmTabs(tabPane.getTabs(),");
        String closeAll = body(window, "private boolean closeAllTabsGuarded()");
        assertThat(closeAll.indexOf("confirmHostedTabsClose()")).isLessThan(closeAll.indexOf("closeAllTabs();"));
        // File > Open Project... and File > Open Recent both open a project file through openProjectFile.
        assertThat(body(window, "private void openProject()")).contains("openProjectFile(file.toPath());");
        String openProject = body(window, "private void openProjectFile(Path path)");
        assertThat(openProject.indexOf("confirmHostedTabsClose()")).isAtLeast(0);
        assertThat(openProject.indexOf("confirmHostedTabsClose()")).isLessThan(openProject.indexOf("restoreProject(project, this);"));
        assertThat(body(window, "private boolean confirmClose()")).contains("return confirmSnippetEditorsClose(");
        assertThat(body(window, "private boolean confirmSnippetEditorsClose(boolean includeUnownedEditors)"))
            .contains("if (!confirmHostedTabsClose()) {");
    }

    /** What JavaFX's TabPane does when the tab's close button is clicked (TabPaneBehavior.canCloseTab). */
    private static boolean closeButtonCloses(Tab tab) {
        Event request = new Event(tab, tab, Tab.TAB_CLOSE_REQUEST_EVENT);
        Event.fireEvent(tab, request);
        return !request.isConsumed();
    }

    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertThat(start).isAtLeast(0);
        String rest = source.substring(start);
        return rest.substring(0, rest.indexOf("\n    }\n"));
    }

    private static int count(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    /** With LF line endings: Windows CI checks sources out with CRLF. */
    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
