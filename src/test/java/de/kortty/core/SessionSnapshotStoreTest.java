package de.kortty.core;

import de.kortty.model.Project;
import de.kortty.model.SessionSnapshot;
import de.kortty.model.SessionState;
import de.kortty.model.SplitPaneState;
import de.kortty.model.TerminalTimestampEntry;
import de.kortty.model.WindowState;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.assertThrows;

/**
 * The session snapshot files in {@code ~/.kortty/session/}: a snapshot written and read back is the
 * same, the file is replaced atomically and owner-only, a corrupt file is moved aside, the last
 * session becomes the previous one only when it has a tab to restore, a second korTTY without the
 * lock neither rotates nor writes, and no screen text or session-foreign field survives a write or a
 * read.
 */
class SessionSnapshotStoreTest {

    private Path configDir;
    private final List<SessionSnapshotStore> stores = new ArrayList<>();

    @BeforeMethod
    void createConfigDir() throws IOException {
        configDir = Files.createTempDirectory("kortty-session-store-");
    }

    @AfterMethod(alwaysRun = true)
    void cleanUp() throws IOException {
        for (SessionSnapshotStore store : stores) {
            store.close();
        }
        stores.clear();
        try (Stream<Path> paths = Files.walk(configDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setReadable(true, true);
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void aWrittenSnapshotComesBackAsThePreviousSessionOnTheNextStart() throws IOException {
        SessionSnapshotStore first = open();
        assertThat(first.startUp().last()).isNull();
        assertThat(first.canWrite()).isTrue();
        first.write(snapshot(2, 3));
        first.close();

        SessionSnapshotStore second = open();
        SessionSnapshotStore.StartupState startup = second.startUp();

        assertThat(startup.rotated()).isTrue();
        assertThat(startup.keptLast()).isNull();
        assertThat(SessionSnapshotStore.restorableTabs(startup.last())).isEqualTo(6);
        assertThat(startup.recentlyClosed()).hasSize(1);
        assertThat(startup.recentlyClosed().get(0).getTabs().get(0).getConnectionId()).isEqualTo("conn-closed");
        assertThat(Files.exists(sessionDir().resolve(SessionSnapshotStore.SNAPSHOT_FILE))).isFalse();
        assertThat(second.isPreviousAvailable()).isTrue();

        SessionSnapshot previous = second.loadPrevious().orElseThrow();
        assertThat(SessionSnapshotStore.restorableWindows(previous)).isEqualTo(2);
        assertThat(previous.getAppVersion()).isEqualTo("9.9.9");
        assertThat(previous.isCleanExit()).isTrue();
        WindowState window = previous.getProject().getWindows().get(0);
        assertThat(window.getTabs().get(0).getConnectionId()).isEqualTo("conn-0-0");
        assertThat(window.getActiveSessionId()).isEqualTo("s-0-1");
        assertThat(window.getTabs().get(0).getTabTitle()).isEqualTo("web 0-0");
    }

    @Test
    void writingReplacesTheFileAtomicallyAndLeavesNoTemporaryFile() throws IOException {
        SessionSnapshotStore store = open();
        store.startUp();

        store.write(snapshot(1, 1));
        store.write(snapshot(1, 4));

        SessionSnapshot onDisk = readLast();
        assertThat(SessionSnapshotStore.restorableTabs(onDisk)).isEqualTo(4);
        try (Stream<Path> files = Files.list(sessionDir())) {
            assertThat(files.map(path -> path.getFileName().toString()).sorted().toList())
                .containsExactly(SessionSnapshotStore.SNAPSHOT_FILE, SessionSnapshotStore.LOCK_FILE_NAME);
        }
    }

    @Test
    void theDirectoryAndTheSnapshotAreOwnerOnly() throws IOException {
        if (Files.getFileAttributeView(configDir, PosixFileAttributeView.class) == null) {
            throw new SkipException("no POSIX permissions on this file system");
        }
        SessionSnapshotStore store = open();
        store.startUp();
        store.write(snapshot(1, 1));

        assertThat(Files.getPosixFilePermissions(sessionDir()))
            .containsExactlyElementsIn(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(sessionDir().resolve(SessionSnapshotStore.SNAPSHOT_FILE)))
            .containsExactlyElementsIn(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void aCorruptSnapshotIsMovedAsideAndTheNextWriteStartsFresh() throws IOException {
        Files.createDirectories(sessionDir());
        Path last = sessionDir().resolve(SessionSnapshotStore.SNAPSHOT_FILE);
        Files.writeString(last, "<sessionSnapshot><project>", StandardCharsets.UTF_8);

        SessionSnapshotStore store = open();
        SessionSnapshotStore.StartupState startup = store.startUp();

        assertThat(startup.last()).isNull();
        assertThat(startup.rotated()).isFalse();
        try (Stream<Path> files = Files.list(sessionDir())) {
            assertWithMessage("the corrupt file is kept aside")
                .that(files.anyMatch(path -> path.getFileName().toString()
                    .startsWith(SessionSnapshotStore.SNAPSHOT_FILE + ".corrupt-"))).isTrue();
        }
        assertThat(store.canWrite()).isTrue();
        store.write(snapshot(1, 2));
        assertThat(SessionSnapshotStore.restorableTabs(readLast())).isEqualTo(2);
    }

    @Test
    void anUnreadableSnapshotStaysInPlaceAndIsNeverWrittenOver() throws IOException {
        if (Files.getFileAttributeView(configDir, PosixFileAttributeView.class) == null) {
            throw new SkipException("no POSIX permissions on this file system");
        }
        SessionSnapshotStore writer = open();
        writer.startUp();
        writer.write(snapshot(0, 0));
        writer.close();
        Path last = sessionDir().resolve(SessionSnapshotStore.SNAPSHOT_FILE);
        Files.setPosixFilePermissions(last, Set.<PosixFilePermission>of());
        if (Files.isReadable(last)) {
            throw new SkipException("running with rights that read any file");
        }

        SessionSnapshotStore store = open();
        store.startUp();

        assertThat(store.ownsLock()).isTrue();
        assertThat(store.canWrite()).isFalse();
        assertThrows(IllegalStateException.class, () -> store.write(snapshot(1, 1)));
        assertThat(Files.exists(last)).isTrue();
    }

    @Test
    void aStartWithoutATabToRestoreKeepsThePreviousSession() throws IOException {
        SessionSnapshotStore first = open();
        first.startUp();
        first.write(snapshot(1, 2));
        first.close();
        SessionSnapshotStore second = open();
        assertThat(second.startUp().rotated()).isTrue();
        // The second run opened nothing; it only remembers its Recently Closed list.
        second.write(snapshot(0, 0));
        second.close();

        SessionSnapshotStore third = open();
        SessionSnapshotStore.StartupState startup = third.startUp();

        assertThat(startup.rotated()).isFalse();
        assertThat(startup.keptLast()).isNotNull();
        assertThat(SessionSnapshotStore.restorableTabs(third.loadPrevious().orElseThrow())).isEqualTo(2);
        assertWithMessage("the closed list of the newest run")
            .that(startup.recentlyClosed()).hasSize(1);
    }

    @Test
    void theClosedListComesFromThePreviousSessionWhenTheLastRunWroteNothing() throws IOException {
        SessionSnapshotStore first = open();
        first.startUp();
        first.write(snapshot(1, 1));
        first.close();
        open().startUp();
        stores.get(1).close();

        SessionSnapshotStore third = open();
        SessionSnapshotStore.StartupState startup = third.startUp();

        assertThat(startup.last()).isNull();
        assertThat(startup.recentlyClosed()).hasSize(1);
    }

    @Test
    void aSecondKorttyWithoutTheLockNeitherRotatesNorWrites() throws IOException {
        SessionSnapshotStore writer = open();
        writer.startUp();
        writer.write(snapshot(1, 3));

        SessionSnapshotStore second = open();
        SessionSnapshotStore.StartupState startup = second.startUp();

        assertThat(second.ownsLock()).isFalse();
        assertThat(second.canWrite()).isFalse();
        assertThat(startup.rotated()).isFalse();
        assertThat(Files.exists(sessionDir().resolve(SessionSnapshotStore.SNAPSHOT_FILE))).isTrue();
        assertThat(Files.exists(sessionDir().resolve(SessionSnapshotStore.PREVIOUS_FILE))).isFalse();
        assertThrows(IllegalStateException.class, () -> second.write(snapshot(1, 1)));
        assertThat(SessionSnapshotStore.restorableTabs(readLast())).isEqualTo(3);

        writer.close();
        SessionSnapshotStore next = open();
        assertWithMessage("the lock is free again once the writer closed").that(next.ownsLock()).isTrue();
    }

    @Test
    void noScreenTextHistoryFileOrForeignFieldIsWrittenAndTheGivenSnapshotStaysUntouched() throws IOException {
        SessionSnapshot snapshot = snapshot(1, 1);
        Project project = snapshot.getProject();
        project.setAutoReconnect(false);
        project.setProjectFilePath("/tmp/shared.kortty");
        SessionState tab = project.getWindows().get(0).getTabs().get(0);
        tab.setTerminalHistory("secret screen text");
        tab.setHistoryFilePath("../../etc/passwd");
        tab.setTerminalTimestamps(List.of(new TerminalTimestampEntry()));
        SplitPaneState left = SplitPaneState.createLeaf(0);
        left.setCurrentDirectory("/home/me/src");
        left.setScrollbackRef("../outside");
        SplitPaneState right = SplitPaneState.createLeaf(1, "conn-other");
        right.setScrollbackRef("1f0e-ab");
        tab.setSplitPaneState(SplitPaneState.createSplit(javafx.geometry.Orientation.HORIZONTAL, 0.5, left, right));
        snapshot.getRecentlyClosed().add(new SessionSnapshot.ClosedEntry(false,
            List.of(new SessionSnapshot.ClosedTab(null, false, null, "no id", null, null))));
        snapshot.getRecentlyClosed().get(0).getTabs().get(0).setCustomTitle("x".repeat(500));

        SessionSnapshotStore store = open();
        store.startUp();
        store.write(snapshot);

        String xml = Files.readString(sessionDir().resolve(SessionSnapshotStore.SNAPSHOT_FILE), StandardCharsets.UTF_8);
        assertThat(xml).doesNotContain("secret screen text");
        assertThat(xml).doesNotContain("passwd");
        assertThat(xml).doesNotContain("terminalTimestamps");
        assertThat(xml).doesNotContain("shared.kortty");
        assertThat(xml).doesNotContain("../outside");
        assertThat(xml).contains("/home/me/src");
        assertThat(xml).contains("1f0e-ab");
        SessionSnapshot onDisk = readLast();
        assertThat(onDisk.getProject().isAutoReconnect()).isTrue();
        assertWithMessage("a closed tab without a connection id is not kept")
            .that(onDisk.getRecentlyClosed()).hasSize(1);
        assertThat(onDisk.getRecentlyClosed().get(0).getTabs().get(0).getCustomTitle())
            .hasLength(SessionSnapshotStore.MAX_LABEL_LENGTH);

        assertWithMessage("the store cleans a copy, not the live objects")
            .that(tab.getTerminalHistory()).isEqualTo("secret screen text");
        assertThat(left.getScrollbackRef()).isEqualTo("../outside");
        assertThat(snapshot.getRecentlyClosed()).hasSize(2);
    }

    @Test
    void aHandMadeFileIsCleanedWhenItIsRead() throws IOException {
        SessionSnapshotStore writer = open();
        writer.startUp();
        writer.write(snapshot(1, 1));
        writer.close();
        Path last = sessionDir().resolve(SessionSnapshotStore.SNAPSHOT_FILE);
        String xml = Files.readString(last, StandardCharsets.UTF_8)
            .replace("<tabTitle>", "<terminalHistory>planted text</terminalHistory><historyFilePath>../x</historyFilePath><tabTitle>");
        Files.writeString(last, xml, StandardCharsets.UTF_8);

        SessionSnapshotStore store = open();
        SessionSnapshot loaded = store.startUp().last();

        SessionState tab = loaded.getProject().getWindows().get(0).getTabs().get(0);
        assertThat(tab.getTerminalHistory()).isNull();
        assertThat(tab.getHistoryFilePath()).isNull();
        assertThat(tab.getTabTitle()).isEqualTo("web 0-0");
    }

    @Test
    void tabsAndWindowsAreCountedOverTheWholeSnapshot() {
        assertThat(SessionSnapshotStore.restorableTabs((SessionSnapshot) null)).isEqualTo(0);
        assertThat(SessionSnapshotStore.restorableTabs(new SessionSnapshot())).isEqualTo(0);
        SessionSnapshot snapshot = snapshot(3, 2);
        snapshot.getProject().getWindows().get(1).getTabs().clear();

        assertThat(SessionSnapshotStore.restorableTabs(snapshot)).isEqualTo(4);
        assertThat(SessionSnapshotStore.restorableWindows(snapshot)).isEqualTo(2);
    }

    private SessionSnapshotStore open() {
        SessionSnapshotStore store = SessionSnapshotStore.open(configDir);
        stores.add(store);
        return store;
    }

    private Path sessionDir() {
        return configDir.resolve(SessionSnapshotStore.DIRECTORY_NAME);
    }

    private SessionSnapshot readLast() throws IOException {
        try {
            return SessionSnapshotStore.unmarshal(Files.readAllBytes(sessionDir().resolve(SessionSnapshotStore.SNAPSHOT_FILE)));
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** A snapshot with {@code windows} windows of {@code tabsPerWindow} terminal tabs and one closed tab. */
    static SessionSnapshot snapshot(int windows, int tabsPerWindow) {
        Project project = new Project("Session");
        project.setAutoReconnect(true);
        for (int w = 0; w < windows; w++) {
            WindowState window = new WindowState("w" + w);
            for (int t = 0; t < tabsPerWindow; t++) {
                SessionState tab = new SessionState("s-" + w + "-" + t, "conn-" + w + "-" + t);
                tab.setTabTitle("web " + w + "-" + t);
                window.addTab(tab);
            }
            window.setActiveSessionId(tabsPerWindow > 1 ? "s-" + w + "-1" : null);
            project.addWindow(window);
        }
        SessionSnapshot snapshot = new SessionSnapshot();
        snapshot.setAppVersion("9.9.9");
        snapshot.setSavedAtMillis(1_700_000_000_000L);
        snapshot.setCleanExit(true);
        snapshot.setProject(project);
        snapshot.getRecentlyClosed().add(new SessionSnapshot.ClosedEntry(false,
            List.of(new SessionSnapshot.ClosedTab("conn-closed", false, "prod", "db", null, null))));
        return snapshot;
    }
}
