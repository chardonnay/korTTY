package de.kortty.ui;

import de.kortty.core.SFTPSession;
import de.kortty.model.ServerConnection;
import de.kortty.model.TemporarySSHKey;
import javafx.event.Event;
import javafx.scene.control.Tab;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/**
 * Reopening a saved SFTP Manager tab: the connection is found by id (or, for older projects, by a
 * unique name), the saved folders are used while they still exist, and a restored tab's own SFTP
 * session closes with the tab. Pure: a {@link Tab} needs no JavaFX toolkit.
 */
class SftpSessionRestoreSupportTest {

    private Path scratch;

    @BeforeMethod
    void createScratch() throws IOException {
        scratch = Files.createTempDirectory("kortty-sftp-restore-test");
    }

    @AfterMethod(alwaysRun = true)
    void deleteScratch() throws IOException {
        if (scratch == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(scratch)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static ServerConnection connection(String name) {
        return new ServerConnection(name, name + ".example.test", 22, "demo");
    }

    private static Function<String, ServerConnection> byId(List<ServerConnection> all) {
        return id -> all.stream().filter(c -> c.getId().equals(id)).findFirst().orElse(null);
    }

    @Test
    void findsConnectionByUuid() {
        ServerConnection web = connection("web");
        ServerConnection db = connection("db");
        List<ServerConnection> all = List.of(web, db);

        assertThat(SftpSessionRestoreSupport.findConnection(db.getId(), byId(all), all)).isSameInstanceAs(db);
    }

    @Test
    void idWinsOverAConnectionNamedLikeIt() {
        ServerConnection target = connection("target");
        // A connection that happens to be named like the other one's id must not take its place.
        ServerConnection impostor = connection(target.getId());
        List<ServerConnection> all = List.of(impostor, target);

        assertThat(SftpSessionRestoreSupport.findConnection(target.getId(), byId(all), all)).isSameInstanceAs(target);
    }

    @Test
    void fallsBackToUniqueNameForLegacyProjects() {
        // Projects saved before the fix stored the connection's name as its id.
        ServerConnection web = connection("web");
        ServerConnection db = connection("db");
        List<ServerConnection> all = List.of(web, db);

        assertThat(SftpSessionRestoreSupport.findConnection("web", byId(all), all)).isSameInstanceAs(web);
    }

    @Test
    void ambiguousOrUnknownNameReturnsNull() {
        ServerConnection first = connection("web");
        ServerConnection second = connection("web");
        List<ServerConnection> all = List.of(first, second);

        assertThat(SftpSessionRestoreSupport.findConnection("web", byId(all), all)).isNull();
        assertThat(SftpSessionRestoreSupport.findConnection("unknown", byId(all), all)).isNull();
        assertThat(SftpSessionRestoreSupport.findConnection(null, byId(all), all)).isNull();
        assertThat(SftpSessionRestoreSupport.findConnection(" ", byId(all), all)).isNull();
        // The name is compared exactly.
        assertThat(SftpSessionRestoreSupport.findConnection("WEB", byId(all), all)).isNull();
    }

    @Test
    void initialLocalPathKeepsExistingDirectory() throws IOException {
        Path home = Files.createDirectory(scratch.resolve("home"));
        Path downloads = Files.createDirectory(scratch.resolve("downloads"));

        assertThat(SftpSessionRestoreSupport.initialLocalPath(downloads.toString(), home)).isEqualTo(downloads);
    }

    @Test
    void initialLocalPathFallsBackToHomeForMissingOrRegularFile() throws IOException {
        Path home = Files.createDirectory(scratch.resolve("home"));
        Path file = Files.writeString(scratch.resolve("notes.txt"), "x", StandardCharsets.UTF_8);

        assertThat(SftpSessionRestoreSupport.initialLocalPath(scratch.resolve("gone").toString(), home))
            .isEqualTo(home);
        assertThat(SftpSessionRestoreSupport.initialLocalPath(file.toString(), home)).isEqualTo(home);
        assertThat(SftpSessionRestoreSupport.initialLocalPath(null, home)).isEqualTo(home);
        assertThat(SftpSessionRestoreSupport.initialLocalPath("", home)).isEqualTo(home);
        // A relative path would depend on the working directory of the next start.
        assertThat(SftpSessionRestoreSupport.initialLocalPath(".", home)).isEqualTo(home);
    }

    @Test
    void blankRemotePathBecomesHome() {
        assertThat(SftpSessionRestoreSupport.initialRemotePath(null)).isEqualTo("~");
        assertThat(SftpSessionRestoreSupport.initialRemotePath("")).isEqualTo("~");
        assertThat(SftpSessionRestoreSupport.initialRemotePath("  ")).isEqualTo("~");
        assertThat(SftpSessionRestoreSupport.initialRemotePath("/var/log")).isEqualTo("/var/log");
        assertThat(SftpSessionRestoreSupport.initialRemotePath(" /home/demo/upload ")).isEqualTo("/home/demo/upload");
    }

    @Test
    void missingFolderIsRecognisedBehindTheListingFuture() {
        // The listing runs in a CompletableFuture and SSHD's directory iterator throws an unchecked
        // wrapper, so the SFTP status arrives wrapped twice.
        assertThat(SftpSessionRestoreSupport.isMissingFolder(new CompletionException(new UncheckedIOException(
            new SftpException(SftpConstants.SSH_FX_NO_SUCH_FILE, "No such file"))))).isTrue();
        assertThat(SftpSessionRestoreSupport.isMissingFolder(new CompletionException(
            new SftpException(SftpConstants.SSH_FX_NO_SUCH_FILE, "No such file")))).isTrue();
        assertThat(SftpSessionRestoreSupport.isMissingFolder(
            new SftpException(SftpConstants.SSH_FX_NO_SUCH_PATH, "No such path"))).isTrue();
        assertThat(SftpSessionRestoreSupport.isMissingFolder(
            new SftpException(SftpConstants.SSH_FX_NOT_A_DIRECTORY, "Not a directory"))).isTrue();

        // Missing rights or a dead connection are not a missing folder.
        assertThat(SftpSessionRestoreSupport.isMissingFolder(new CompletionException(
            new SftpException(SftpConstants.SSH_FX_PERMISSION_DENIED, "Permission denied")))).isFalse();
        assertThat(SftpSessionRestoreSupport.isMissingFolder(new CompletionException(
            new IOException("Connection reset")))).isFalse();
        // Only wrappers are looked through, not any exception that happens to carry an SFTP cause.
        assertThat(SftpSessionRestoreSupport.isMissingFolder(new IllegalStateException(
            new SftpException(SftpConstants.SSH_FX_NO_SUCH_FILE, "No such file")))).isFalse();
        assertThat(SftpSessionRestoreSupport.isMissingFolder(null)).isFalse();
    }

    @Test
    void ownedSessionClosesOnceWhenTheTabIsClosedWithItsButton() {
        Tab tab = new Tab("image.png (Remote)");
        AtomicInteger closed = new AtomicInteger();

        SftpSessionRestoreSupport.closeWithTab(tab, closed::incrementAndGet);

        // JavaFX fires CLOSED_EVENT from the close button only for a tab with an onClosed handler.
        assertThat(tab.getOnClosed()).isNotNull();
        Event.fireEvent(tab, new Event(Tab.CLOSED_EVENT));
        assertThat(closed.get()).isEqualTo(1);
        // A programmatic close path afterwards (close all, window close) does not close it again.
        SftpSessionRestoreSupport.closeOwnedSession(tab);
        assertThat(closed.get()).isEqualTo(1);
    }

    @Test
    void ownedSessionClosesOnTheProgrammaticClosePaths() {
        Tab tab = new Tab("notes.txt (Remote)");
        AtomicInteger closed = new AtomicInteger();
        SftpSessionRestoreSupport.closeWithTab(tab, closed::incrementAndGet);

        SftpSessionRestoreSupport.closeOwnedSession(tab);
        assertThat(closed.get()).isEqualTo(1);
        Event.fireEvent(tab, new Event(Tab.CLOSED_EVENT));
        assertThat(closed.get()).isEqualTo(1);
    }

    @Test
    void existingOnClosedHandlerOfTheTabIsKept() {
        // FileEditorTab disposes its editor in onClosed.
        Tab tab = new Tab("notes.txt (Remote)");
        AtomicInteger disposed = new AtomicInteger();
        tab.setOnClosed(event -> disposed.incrementAndGet());
        AtomicInteger closed = new AtomicInteger();

        SftpSessionRestoreSupport.closeWithTab(tab, closed::incrementAndGet);
        Event.fireEvent(tab, new Event(Tab.CLOSED_EVENT));

        assertThat(disposed.get()).isEqualTo(1);
        assertThat(closed.get()).isEqualTo(1);
    }

    @Test
    void tabWithoutOwnSessionIsLeftAlone() {
        // An image tab opened from an SFTP tab shares that tab's session and must not close it.
        Tab tab = new Tab("shared.png (Remote)");
        SftpSessionRestoreSupport.closeOwnedSession(tab);
        SftpSessionRestoreSupport.closeOwnedSession(null);
        assertThat(tab.getOnClosed()).isNull();
    }

    @Test
    void remoteTabIsSavedWithTheConnectionOfItsOwnSession() {
        // Saving used to take the id of whichever SFTP tab came first: with two open, a remote
        // file of the second server was reopened from the first one.
        ServerConnection web = connection("web");
        ServerConnection db = connection("db");

        assertThat(SftpSessionRestoreSupport.savedConnectionId(new SFTPSession(db, "pw"))).isEqualTo(db.getId());
        // An image opened from an SFTP tab with a temporary key: its session runs over a copy.
        ServerConnection temporaryKeyCopy = SftpConnectionSupport.connectionForSftp(
            web, new TemporarySSHKey("temporary-private-key", 5));
        assertThat(temporaryKeyCopy).isNotSameInstanceAs(web);
        assertThat(SftpSessionRestoreSupport.savedConnectionId(new SFTPSession(temporaryKeyCopy, null)))
            .isEqualTo(web.getId());
        assertThat(SftpSessionRestoreSupport.savedConnectionId(null)).isNull();
    }

    @Test
    void sftpTabsAreSavedByIdAndRestoredAtTheirFolders() throws IOException {
        String tab = read("src/main/java/de/kortty/ui/SFTPManagerTab.java");
        String saved = tab.substring(tab.indexOf("public SessionState createSessionState()"));
        assertThat(saved).contains("state.setConnectionId(connection.getId());");
        assertThat(saved).doesNotContain("connection.getName()");

        String window = read("src/main/java/de/kortty/ui/MainWindow.java");
        String restore = window.substring(window.indexOf("case SFTP_MANAGER -> {"),
            window.indexOf("case FILE_EDITOR -> {"));
        assertThat(restore).contains("SftpSessionRestoreSupport.findConnection(");
        assertThat(restore).contains("sessionState.getSftpLocalPath(), sessionState.getSftpRemotePath()");

        // Restored remote editor and image tabs open their session like an SFTP tab and own it.
        String editorAndImage = window.substring(window.indexOf("case FILE_EDITOR -> {"),
            window.indexOf("private final class WindowRestore {"));
        assertThat(editorAndImage).doesNotContain("new de.kortty.core.SFTPSession(");
        assertThat(editorAndImage).contains("openOwnedSftpSession(connection, password)");
        assertThat(editorAndImage).contains("restore.lateTabReady(owned, index, ");
        String lateTab = window.substring(window.indexOf("void lateTabReady(de.kortty.core.SFTPSession session, int index,"));
        lateTab = lateTab.substring(0, lateTab.indexOf("\n        }\n"));
        assertThat(lateTab).contains("addTabOwningSftpSession(session, createTab, ");
        assertThat(window).contains("SftpConnectionSupport.configureVault(session, app.getSSHKeyManager(), masterPassword, null);");
        String dispose = window.substring(window.indexOf("private void disposeTabContent(Tab tab)"));
        dispose = dispose.substring(0, dispose.indexOf("\n    }\n"));
        assertThat(dispose).contains("SftpSessionRestoreSupport.closeOwnedSession(tab);");
        // Cmd+W, close all, window close and opening a project end an SFTP tab like its close button.
        assertThat(dispose).contains("sftpTab.cleanup();");
        // A restored tab whose download finishes after its window closed does not keep its session.
        String addOwned = window.substring(window.indexOf("private void addTabOwningSftpSession("));
        addOwned = addOwned.substring(0, addOwned.indexOf("\n    }\n"));
        assertThat(addOwned).contains("if (!stage.isShowing()) {");

        // Remote editor and image tabs are saved with the connection of their own session.
        String save = window.substring(window.indexOf("private SessionState captureTabState("),
            window.indexOf("private void reportSplitLayoutRestore("));
        assertThat(save).contains(
            "sessionState.setConnectionId(SftpSessionRestoreSupport.savedConnectionId(editorTab.getSftpSession()));");
        assertThat(save).contains(
            "sessionState.setConnectionId(SftpSessionRestoreSupport.savedConnectionId(viewerTab.getSftpSession()));");
        assertThat(save).doesNotContain("sftpTab.getConnection().getId()");

        // The editor's own Close button and its Cmd+W only remove the tab; they must still close it.
        String editor = read("src/main/java/de/kortty/ui/FileEditorTab.java");
        String removeTab = editor.substring(editor.indexOf("private void removeTabSafely()"));
        removeTab = removeTab.substring(0, removeTab.indexOf("\n    }\n"));
        assertThat(removeTab).contains("javafx.event.Event.fireEvent(this, new javafx.event.Event(Tab.CLOSED_EVENT));");
    }

    /** With LF line endings: Windows CI checks sources out with CRLF. */
    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
