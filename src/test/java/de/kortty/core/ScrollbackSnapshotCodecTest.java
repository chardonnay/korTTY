package de.kortty.core;

import de.kortty.model.Project;
import de.kortty.model.SessionState;
import de.kortty.model.SplitPaneState;
import de.kortty.model.WindowState;
import de.kortty.security.MasterPasswordManager;
import org.testng.annotations.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.assertThrows;

/**
 * The saved output of terminal panes may hold secrets (a token echoed by a script, a password typed
 * at a prompt that echoes). These cases pin that it is only ever stored encrypted with the
 * master-password key, bounded, owner-only, and gone once that key changes.
 */
class ScrollbackSnapshotCodecTest {

    private static SecretKey key(int seed) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) seed);
        return new SecretKeySpec(bytes, "AES");
    }

    private static List<String> rows(int count) {
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add("line " + i);
        }
        return rows;
    }

    // --- codec ---------------------------------------------------------------------------------

    @Test
    void roundTripsTheRowsAndTheSaveTime() throws IOException {
        ScrollbackSnapshotCodec codec = new ScrollbackSnapshotCodec();
        List<String> rows = List.of("$ echo hällo", "hällo", "", "  indented  ", "\u001B[31mred");

        String file = codec.encode(rows, 1_700_000_000_000L, 1000, key(1));
        ScrollbackSnapshotCodec.Decoded decoded = codec.decode(file, key(1));

        assertThat(decoded.lines()).containsExactlyElementsIn(rows).inOrder();
        assertThat(decoded.savedAtMillis()).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void theFileHoldsNoPlainText() throws IOException {
        String file = new ScrollbackSnapshotCodec().encode(List.of("export TOKEN=s3cr3t-value"), 1L, 1000, key(1));

        assertThat(file).startsWith(ScrollbackSnapshotCodec.PREFIX);
        assertThat(file).doesNotContain("s3cr3t");
        assertThat(file).doesNotContain("TOKEN");
    }

    @Test
    void keepsOnlyTheNewestLinesUpToTheLimit() throws IOException {
        ScrollbackSnapshotCodec codec = new ScrollbackSnapshotCodec();

        List<String> kept = codec.decode(codec.encode(rows(250), 1L, 100, key(1)), key(1)).lines();

        assertThat(kept).hasSize(100);
        assertThat(kept.get(0)).isEqualTo("line 151");
        assertThat(kept.get(99)).isEqualTo("line 250");
    }

    @Test
    void theLineLimitStaysWithinItsRange() {
        assertThat(ScrollbackSnapshotCodec.clampLines(5)).isEqualTo(ScrollbackSnapshotCodec.MIN_LINES);
        assertThat(ScrollbackSnapshotCodec.clampLines(1_000_000)).isEqualTo(ScrollbackSnapshotCodec.MAX_LINES);
        assertThat(ScrollbackSnapshotCodec.clampLines(1234)).isEqualTo(1234);
    }

    @Test
    void aRowCannotSplitIntoTwoAndIsCutAtTheLimit() throws IOException {
        ScrollbackSnapshotCodec codec = new ScrollbackSnapshotCodec();
        String longRow = "x".repeat(ScrollbackSnapshotCodec.MAX_LINE_LENGTH + 50);

        List<String> kept = codec.decode(codec.encode(List.of("a\nb", longRow), 1L, 1000, key(1)), key(1)).lines();

        assertThat(kept).hasSize(2);
        assertThat(kept.get(0)).isEqualTo("a b");
        assertThat(kept.get(1)).hasLength(ScrollbackSnapshotCodec.MAX_LINE_LENGTH);
    }

    @Test
    void nothingIsEncodedWithoutAKey() {
        assertThrows(IllegalStateException.class,
            () -> new ScrollbackSnapshotCodec().encode(List.of("secret"), 1L, 1000, null));
    }

    @Test
    void anotherKeyDoesNotDecode() throws IOException {
        ScrollbackSnapshotCodec codec = new ScrollbackSnapshotCodec();
        String file = codec.encode(List.of("secret"), 1L, 1000, key(1));

        assertThrows(IOException.class, () -> codec.decode(file, key(2)));
    }

    @Test
    void anEditedFileDoesNotDecode() throws IOException {
        ScrollbackSnapshotCodec codec = new ScrollbackSnapshotCodec();
        String file = codec.encode(List.of("secret"), 1L, 1000, key(1));
        int at = ScrollbackSnapshotCodec.PREFIX.length() + 24;
        String edited = file.substring(0, at) + (file.charAt(at) == 'A' ? 'B' : 'A') + file.substring(at + 1);

        assertThrows(IOException.class, () -> codec.decode(edited, key(1)));
        assertThrows(IOException.class, () -> codec.decode("plain text", key(1)));
    }

    // --- store ---------------------------------------------------------------------------------

    @Test
    void storeWritesNothingWithoutAKey() throws IOException {
        Path config = Files.createTempDirectory("kortty-scrollback-");
        SessionScrollbackStore store = new SessionScrollbackStore(config);

        assertThat(store.write("pane-1", List.of("secret"), 1L, 1000, null)).isFalse();

        assertThat(store.files()).isEmpty();
        assertThat(store.read("pane-1", null)).isEmpty();
    }

    @Test
    void storeRoundTripsAndKeepsTheFileOwnerOnly() throws IOException {
        Path config = Files.createTempDirectory("kortty-scrollback-");
        SessionScrollbackStore store = new SessionScrollbackStore(config);

        assertThat(store.write("pane-1", List.of("one", "two"), 42L, 1000, key(1))).isTrue();

        Path file = store.directory().resolve("pane-1" + SessionScrollbackStore.FILE_SUFFIX);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).doesNotContain("two");
        assertThat(store.read("pane-1", key(1)).orElseThrow().lines()).containsExactly("one", "two").inOrder();
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            assertThat(posix.readAttributes().permissions())
                .containsExactly(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            assertThat(Files.getFileAttributeView(store.directory(), PosixFileAttributeView.class)
                .readAttributes().permissions())
                .containsExactly(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
        }
    }

    @Test
    void storeRejectsAFileNameThatIsNotAPlainName() {
        Path config = Path.of(System.getProperty("java.io.tmpdir"));
        SessionScrollbackStore store = new SessionScrollbackStore(config);

        assertThrows(IllegalArgumentException.class,
            () -> store.write("../escape", List.of("x"), 1L, 1000, key(1)));
        assertThat(store.read("../escape", key(1))).isEmpty();
    }

    @Test
    void aFileWrittenWithAnotherKeyIsNotRestoredAndDeleted() throws IOException {
        Path config = Files.createTempDirectory("kortty-scrollback-");
        SessionScrollbackStore store = new SessionScrollbackStore(config);
        store.write("pane-1", List.of("secret"), 1L, 1000, key(1));

        assertThat(store.read("pane-1", key(2))).isEmpty();

        assertThat(store.files()).isEmpty();
    }

    @Test
    void retainOnlyKeepsTheFilesASavedSessionNames() throws IOException {
        Path config = Files.createTempDirectory("kortty-scrollback-");
        SessionScrollbackStore store = new SessionScrollbackStore(config);
        store.write("kept", List.of("a"), 1L, 1000, key(1));
        store.write("gone", List.of("b"), 1L, 1000, key(1));
        Path other = Files.writeString(store.directory().resolve("notes.txt"), "not ours");

        assertThat(store.retainOnly(Set.of("kept"))).isEqualTo(1);

        assertThat(store.read("kept", key(1))).isPresent();
        assertThat(store.read("gone", key(1))).isEmpty();
        assertWithMessage("only scrollback files are touched").that(Files.exists(other)).isTrue();
    }

    @Test
    void purgeDeletesEveryFileAndIsCounted() throws IOException {
        Path config = Files.createTempDirectory("kortty-scrollback-");
        SessionScrollbackStore store = new SessionScrollbackStore(config);
        store.write("pane-1", List.of("a"), 1L, 1000, key(1));
        store.write("pane-2", List.of("b"), 1L, 1000, key(1));
        long before = SessionScrollbackStore.purgeCount();

        assertThat(SessionScrollbackStore.purge(config)).isEqualTo(2);

        assertThat(store.files()).isEmpty();
        assertThat(SessionScrollbackStore.purgeCount()).isGreaterThan(before);
    }

    @Test
    void aMasterPasswordChangePurgesTheSavedOutput() throws Exception {
        Path config = Files.createTempDirectory("kortty-scrollback-");
        MasterPasswordManager manager = new MasterPasswordManager(config);
        manager.setupPassword("old-password".toCharArray());
        SessionScrollbackStore store = new SessionScrollbackStore(config);
        store.write("pane-1", List.of("secret"), 1L, 1000, manager.getDerivedKey());
        assertThat(store.files()).hasSize(1);

        manager.changePassword("old-password".toCharArray(), "new-password".toCharArray());

        assertThat(store.files()).isEmpty();
    }

    @Test
    void aNewMasterPasswordPurgesTheSavedOutput() throws Exception {
        Path config = Files.createTempDirectory("kortty-scrollback-");
        SessionScrollbackStore store = new SessionScrollbackStore(config);
        store.write("pane-1", List.of("secret"), 1L, 1000, key(1));

        new MasterPasswordManager(config).setupPassword("fresh".toCharArray());

        assertThat(store.files()).isEmpty();
    }

    @Test
    void referencedByListsTheTabAndEverySplitPane() {
        SessionState tab = new SessionState("s1", "c1");
        tab.setScrollbackRef("first");
        SplitPaneState left = SplitPaneState.createLeaf(0, null);
        left.setScrollbackRef("first");
        SplitPaneState right = SplitPaneState.createLeaf(1, null);
        right.setScrollbackRef("second");
        SplitPaneState bad = SplitPaneState.createLeaf(2, null);
        bad.setScrollbackRef("../etc/passwd");
        tab.setSplitPaneState(SplitPaneState.createSplit(javafx.geometry.Orientation.HORIZONTAL, 0.5, left,
            SplitPaneState.createSplit(javafx.geometry.Orientation.VERTICAL, 0.5, right, bad)));
        Project project = new Project("p");
        WindowState window = new WindowState("w");
        window.addTab(tab);
        project.addWindow(window);

        assertThat(SessionScrollbackStore.referencedBy(project)).containsExactly("first", "second");
    }
}
