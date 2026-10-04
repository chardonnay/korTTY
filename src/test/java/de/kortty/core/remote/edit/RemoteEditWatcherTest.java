package de.kortty.core.remote.edit;

import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * The save detection of the external editor: a fake clock drives the polls, and a fake uploader
 * counts what would go to the server.
 */
class RemoteEditWatcherTest {

    private Path dir;
    private Path file;
    private long now;
    private RemoteEditWatcher watcher;
    private final List<String> uploads = new ArrayList<>();

    @BeforeMethod
    void setUp() throws IOException {
        dir = Files.createTempDirectory("kortty-remote-edit-watch-");
        file = Files.writeString(dir.resolve("app.conf"), "port=80\n");
        now = 1_000_000;
        watcher = new RemoteEditWatcher(() -> now);
        uploads.clear();
        watcher.track(file, RemoteEditHashes.sha256(file), (saved, hash) -> uploads.add(hash));
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() {
        watcher.close();
        RemoteEditTempDirs.deleteTree(dir);
    }

    @Test
    void oneSaveGivesOneUpload() throws IOException {
        save("port=8080\n", 1);
        pollAfter(0);
        assertThat(uploads).isEmpty();
        pollAfter(RemoteEditWatcher.POLL_INTERVAL.toMillis());

        assertThat(uploads).containsExactly(RemoteEditHashes.sha256(file));
        pollAfter(1_000);
        pollAfter(1_000);
        assertThat(uploads).hasSize(1);
    }

    @Test
    void aBurstOfFiveWritesGivesOneUpload() throws IOException {
        for (int i = 1; i <= 5; i++) {
            save("port=" + "8".repeat(i) + "\n", i);
            pollAfter(200);
        }
        assertThat(uploads).isEmpty();
        pollAfter(RemoteEditWatcher.DEBOUNCE.toMillis());

        assertThat(uploads).hasSize(1);
        assertThat(uploads.get(0)).isEqualTo(RemoteEditHashes.sha256(file));
        pollAfter(1_000);
        assertThat(uploads).hasSize(1);
    }

    @Test
    void anAtomicRenameSaveIsDetectedEvenWithTheSameSizeAndTime() throws IOException {
        if (Files.readAttributes(file, BasicFileAttributes.class).fileKey() == null) {
            throw new SkipException("This file system reports no file key (inode)");
        }
        FileTime original = Files.getLastModifiedTime(file);
        Path temp = Files.writeString(dir.resolve(".app.conf.tmp"), "port=90\n");
        Files.setLastModifiedTime(temp, original);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        assertThat(Files.size(file)).isEqualTo("port=80\n".length());

        pollAfter(0);
        pollAfter(1_000);

        assertThat(uploads).containsExactly(RemoteEditHashes.sha256(file));
    }

    @Test
    void swapAndBackupFilesNextToTheFileTriggerNothing() throws IOException {
        Files.writeString(dir.resolve(".app.conf.swp"), "swap");
        Files.writeString(dir.resolve("app.conf~"), "backup");
        Files.writeString(dir.resolve(".#app.conf"), "lock");
        Files.writeString(dir.resolve("4913"), "");
        for (int i = 0; i < 4; i++) {
            pollAfter(1_000);
        }
        assertThat(uploads).isEmpty();
    }

    @Test
    void aSaveWithIdenticalContentTriggersNothing() throws IOException {
        save("port=80\n", 3);
        pollAfter(0);
        pollAfter(1_000);
        pollAfter(1_000);
        assertThat(uploads).isEmpty();
    }

    @Test
    void anUntrackedFileIsNoLongerWatched() throws IOException {
        watcher.untrack(file);
        save("port=1\n", 1);
        pollAfter(0);
        pollAfter(1_000);
        assertThat(uploads).isEmpty();
        assertThat(watcher.isTracked(file)).isFalse();
    }

    @Test
    void aFileThatIsMissingForAMomentIsWaitedFor() throws IOException {
        Files.delete(file);
        pollAfter(1_000);
        pollAfter(1_000);
        assertThat(uploads).isEmpty();
        Files.writeString(file, "port=443\n");
        pollAfter(1_000);
        pollAfter(1_000);
        assertThat(uploads).hasSize(1);
    }

    /** Writes {@code content} and moves the modification time forward by {@code seconds}. */
    private void save(String content, int seconds) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_700_000_000_000L + seconds * 1_000L));
    }

    private void pollAfter(long millis) {
        now += millis;
        watcher.poll();
    }
}
