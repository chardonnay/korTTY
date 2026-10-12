package de.kortty.core;

import de.kortty.core.remote.edit.RemoteEditTempDirs;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class TerminalLogScratchTest {

    private static final int ONE_MIB = 1024 * 1024;

    private Path workDir;
    private final List<Path> scratchDirs = new ArrayList<>();

    @BeforeMethod
    void setUp() throws IOException {
        workDir = Files.createTempDirectory("terminal-log-scratch-test");
    }

    @AfterMethod
    void tearDown() throws IOException {
        TerminalLogScratch.deleteAllLive();
        for (Path dir : scratchDirs) {
            TerminalLogScratch.delete(dir);
        }
        scratchDirs.clear();
        if (workDir != null && Files.exists(workDir)) {
            try (var paths = Files.walk(workDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to delete " + path, e);
                    }
                });
            }
        }
    }

    private Path newScratch() throws IOException {
        Path dir = TerminalLogScratch.create();
        scratchDirs.add(dir);
        return dir;
    }

    /** About a mebibyte of seeded, log-like text, so every run compresses the same bytes. */
    private static byte[] sample(int size) {
        Random random = new Random(20260804L);
        StringBuilder text = new StringBuilder(size + 256);
        String[] words = {"connected", "ssh", "exit status 0", "grüße", "日本語", "ok", "retry 3"};
        int line = 0;
        while (text.length() < size) {
            text.append(line++).append(' ').append(words[random.nextInt(words.length)]).append('\n');
        }
        return text.substring(0, size).getBytes(StandardCharsets.UTF_8);
    }

    private Path writeArchive(String name, byte[] content, TerminalLogNaming.Compression compression)
            throws IOException {
        Path archive = workDir.resolve(name);
        try (OutputStream out = TerminalLogCodecs.openOutput(archive, compression)) {
            out.write(content);
        }
        return archive;
    }

    @Test
    void createMakesAnOwnerOnlyFolderInTheTempRoot() throws IOException {
        Path dir = newScratch();

        assertThat(dir.getParent()).isEqualTo(RemoteEditTempDirs.defaultRoot().toAbsolutePath().normalize());
        assertThat(dir.getFileName().toString()).startsWith(TerminalLogScratch.PREFIX);
        assertThat(Files.isDirectory(dir)).isTrue();
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void decompressesGzipZstdAndBzip2ToByteEqualPrivateCopies() throws IOException {
        byte[] content = sample(ONE_MIB + 4096);
        Path scratch = newScratch();
        for (TerminalLogNaming.Compression compression : List.of(
                TerminalLogNaming.Compression.GZIP,
                TerminalLogNaming.Compression.ZSTD,
                TerminalLogNaming.Compression.BZIP2)) {
            Path archive = writeArchive("2026-08-04-14-30-12-web01_1.log" + compression.suffix, content, compression);

            Path copy = TerminalLogScratch.decompress(archive, scratch, bytes -> { }, () -> false);

            assertThat(copy).isEqualTo(scratch.resolve("2026-08-04-14-30-12-web01_1.log"));
            assertThat(Files.readAllBytes(copy)).isEqualTo(content);
        }
    }

    @Test
    void aPlainFileIsCopiedUnderItsOwnName() throws IOException {
        byte[] content = "plain\n".getBytes(StandardCharsets.UTF_8);
        Path plain = Files.write(workDir.resolve("2026-08-04-14-30-12-web01_1.txt"), content);
        Path scratch = newScratch();

        Path copy = TerminalLogScratch.decompress(plain, scratch, null, null);

        assertThat(Files.readAllBytes(copy)).isEqualTo(content);
    }

    @Test
    void reportsTheBytesWrittenAsItGoes() throws IOException {
        byte[] content = sample(3 * ONE_MIB);
        Path archive = writeArchive("2026-08-04-14-30-12-web01_1.log.gz", content,
            TerminalLogNaming.Compression.GZIP);
        Path scratch = newScratch();
        List<Long> reported = new ArrayList<>();

        TerminalLogScratch.decompress(archive, scratch, reported::add, () -> false);

        assertThat(reported).isNotEmpty();
        assertThat(reported).isInOrder();
        assertThat(reported.get(reported.size() - 1)).isEqualTo((long) content.length);
    }

    @Test
    void cancelledBeforeTheFirstBlockLeavesNothingBehind() throws IOException {
        Path archive = writeArchive("2026-08-04-14-30-12-web01_1.log.gz", sample(ONE_MIB),
            TerminalLogNaming.Compression.GZIP);
        Path scratch = newScratch();

        expectThrows(TerminalLogScratch.CancelledException.class,
            () -> TerminalLogScratch.decompress(archive, scratch, bytes -> { }, () -> true));

        assertThat(Files.exists(scratch.resolve("2026-08-04-14-30-12-web01_1.log"))).isFalse();
    }

    @Test
    void cancelledHalfWayRemovesThePartialCopy() throws IOException {
        Path archive = writeArchive("2026-08-04-14-30-12-web01_1.log.gz", sample(3 * ONE_MIB),
            TerminalLogNaming.Compression.GZIP);
        Path scratch = newScratch();
        AtomicLong written = new AtomicLong();

        expectThrows(TerminalLogScratch.CancelledException.class,
            () -> TerminalLogScratch.decompress(archive, scratch, written::set, () -> written.get() > 0));

        assertThat(Files.exists(scratch.resolve("2026-08-04-14-30-12-web01_1.log"))).isFalse();
    }

    @Test
    void aMissingViewerFolderIsAnIOExceptionNotAnUnpack() throws IOException {
        Path archive = writeArchive("2026-08-04-14-30-12-web01_1.log.gz", sample(1024),
            TerminalLogNaming.Compression.GZIP);

        expectThrows(IOException.class,
            () -> TerminalLogScratch.decompress(archive, workDir.resolve("gone"), null, null));
    }

    @Test
    void anUnpackThatWouldOverwriteItsSourceIsRefused() throws IOException {
        Path scratch = newScratch();
        byte[] content = sample(1024);
        Path plain = Files.write(scratch.resolve("2026-08-04-14-30-12-web01_1.log"), content);

        expectThrows(IllegalArgumentException.class,
            () -> TerminalLogScratch.decompress(plain, scratch, null, null));
        assertThat(Files.size(plain)).isEqualTo(content.length);
    }

    @Test
    void deleteClosesTheRegisteredReadersAndRemovesTheFolder() throws IOException {
        Path scratch = newScratch();
        Files.write(scratch.resolve("copy.log"), sample(1024));
        AtomicBoolean closed = new AtomicBoolean();
        TerminalLogScratch.register(scratch, () -> closed.set(true));

        TerminalLogScratch.delete(scratch);

        assertThat(closed.get()).isTrue();
        assertThat(Files.exists(scratch)).isFalse();
    }

    @Test
    void anUnregisteredReaderIsNotClosedByDelete() throws IOException {
        Path scratch = newScratch();
        AtomicBoolean closed = new AtomicBoolean();
        AutoCloseable reader = () -> closed.set(true);
        TerminalLogScratch.register(scratch, reader);
        TerminalLogScratch.unregister(scratch, reader);

        TerminalLogScratch.delete(scratch);

        assertThat(closed.get()).isFalse();
    }

    @Test
    void deleteRefusesAFolderThatIsNotAViewerFolder() throws IOException {
        Path foreign = Files.createDirectory(workDir.resolve("my-own-notes"));
        Files.write(foreign.resolve("keep.txt"), new byte[] {1});

        TerminalLogScratch.delete(foreign);

        assertThat(Files.exists(foreign.resolve("keep.txt"))).isTrue();
    }

    @Test
    void deleteAllLiveRemovesEveryFolderThisProcessCreated() throws IOException {
        Path first = TerminalLogScratch.create();
        Path second = TerminalLogScratch.create();

        TerminalLogScratch.deleteAllLive();

        assertThat(Files.exists(first)).isFalse();
        assertThat(Files.exists(second)).isFalse();
    }

    @Test
    void sweepRemovesOnlyOldFoldersOfOurNameAndOwner() throws IOException {
        Path root = Files.createDirectory(workDir.resolve("temp"));
        UserPrincipal owner = Files.getOwner(workDir);
        Instant now = Instant.now();
        Path old = makeFolder(root, TerminalLogScratch.PREFIX + UUID.randomUUID(), now.minus(Duration.ofDays(2)));
        Path fresh = makeFolder(root, TerminalLogScratch.PREFIX + UUID.randomUUID(), now);
        Path foreignName = makeFolder(root, "kortty-logview-notes", now.minus(Duration.ofDays(2)));
        Path remoteEdit = makeFolder(root, RemoteEditTempDirs.PREFIX + UUID.randomUUID(), now.minus(Duration.ofDays(2)));

        int removed = TerminalLogScratch.sweep(root, owner, now, Duration.ofHours(24));

        assertThat(removed).isEqualTo(1);
        assertThat(Files.exists(old)).isFalse();
        assertThat(Files.exists(fresh)).isTrue();
        assertThat(Files.exists(foreignName)).isTrue();
        assertThat(Files.exists(remoteEdit)).isTrue();
    }

    @Test
    void sweepKeepsAnOldFolderWhoseLockThisProcessHolds() throws IOException {
        Path root = Files.createDirectory(workDir.resolve("temp"));
        UserPrincipal owner = Files.getOwner(workDir);
        Instant now = Instant.now();
        // A folder created the real way holds its lock file; make it look a day old.
        Path inUse = TerminalLogScratch.create();
        Path moved = root.resolve(inUse.getFileName());
        Files.move(inUse, moved);
        Files.setLastModifiedTime(moved, FileTime.from(now.minus(Duration.ofDays(2))));
        Files.setLastModifiedTime(moved.resolve(TerminalLogScratch.LOCK_NAME), FileTime.from(now.minus(Duration.ofDays(2))));
        Path abandoned = makeFolder(root, TerminalLogScratch.PREFIX + UUID.randomUUID(), now.minus(Duration.ofDays(2)));
        Files.writeString(abandoned.resolve(TerminalLogScratch.LOCK_NAME), "");
        // The sweep ages a folder by its newest entry, so the stale lock file must be old as well.
        Files.setLastModifiedTime(abandoned.resolve(TerminalLogScratch.LOCK_NAME), FileTime.from(now.minus(Duration.ofDays(2))));
        Files.setLastModifiedTime(abandoned, FileTime.from(now.minus(Duration.ofDays(2))));

        int removed = TerminalLogScratch.sweep(root, owner, now, Duration.ofHours(24));

        assertThat(removed).isEqualTo(1);
        assertThat(Files.exists(moved)).isTrue();
        assertThat(Files.exists(abandoned)).isFalse();
        TerminalLogScratch.delete(inUse);
        RemoteEditTempDirs.deleteTree(moved);
    }

    @Test
    void sweepLeavesAFolderTheCurrentUserDoesNotOwn() throws IOException {
        Path root = Files.createDirectory(workDir.resolve("temp"));
        Instant now = Instant.now();
        Path old = makeFolder(root, TerminalLogScratch.PREFIX + UUID.randomUUID(), now.minus(Duration.ofDays(2)));
        UserPrincipal someoneElse = new UserPrincipal() {
            @Override
            public String getName() {
                return "not-the-current-user-" + UUID.randomUUID();
            }
        };

        int removed = TerminalLogScratch.sweep(root, someoneElse, now, Duration.ofHours(24));

        assertThat(removed).isEqualTo(0);
        assertThat(Files.exists(old)).isTrue();
    }

    private static Path makeFolder(Path root, String name, Instant modified) throws IOException {
        Path folder = Files.createDirectory(root.resolve(name));
        Files.setLastModifiedTime(folder, FileTime.from(modified));
        return folder;
    }
}
