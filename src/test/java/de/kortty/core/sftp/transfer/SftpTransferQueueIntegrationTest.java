package de.kortty.core.sftp.transfer;

import de.kortty.core.sftp.SftpChannelSource;
import de.kortty.core.sftp.SftpLoopbackFixture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * {@link SftpTransferQueue} against a loopback SSHD: parallel channels, a pool that degrades when
 * the server refuses channels, per-item cancel, retry with resume, a connection lost mid-batch, the
 * per-target lock, and folder expansion that never follows linked folders.
 */
class SftpTransferQueueIntegrationTest {

    private static final long TIMEOUT_SECONDS = 60;

    private Path tmp;
    private SftpLoopbackFixture fixture;
    private final List<SftpTransferQueue> queues = new ArrayList<>();

    @BeforeMethod
    void setUp() throws IOException {
        tmp = Files.createTempDirectory("kortty-sftp-queue-it-");
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        queues.forEach(SftpTransferQueue::close);
        queues.clear();
        if (fixture != null) {
            fixture.close();
            fixture = null;
        }
        try (Stream<Path> paths = Files.walk(tmp)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ------------------------------------------------------------------ parallel channels

    @Test
    void twelveFilesTravelOverAtMostThreeChannelsByteForByte() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).requestDelayMillis(2).start();
        FixtureSource source = new FixtureSource(fixture, true);
        SftpTransferQueue queue = queue(source, TransferSettings.defaults(), ConflictResolver.always(ConflictAction.OVERWRITE));
        List<Path> files = localFiles("up", 12, 200 * 1024 + 3);
        Files.createDirectories(fixture.root().resolve("dest"));

        TransferBatch upload = queue.enqueueUpload(files, "/dest");
        await(upload);

        assertThat(upload.countFiles(TransferState.DONE)).isEqualTo(12);
        for (Path file : files) {
            assertThat(Files.mismatch(file, fixture.root().resolve("dest").resolve(file.getFileName()))).isEqualTo(-1L);
        }
        assertThat(fixture.stats().maxOpenFiles()).isAtMost(3);
        assertThat(fixture.stats().maxOpenFiles()).isAtLeast(2);
        assertThat(source.opened.get()).isAtMost(3);
        assertThat(queue.mostWorkers()).isAtMost(3);

        // And back again, in parallel too.
        Path downloads = Files.createDirectories(tmp.resolve("down"));
        List<RemoteEntryRef> refs = new ArrayList<>();
        for (Path file : files) {
            refs.add(RemoteEntryRef.file("/dest/" + file.getFileName(), Files.size(file)));
        }
        TransferBatch download = queue.enqueueDownload(refs, downloads);
        await(download);
        assertThat(download.countFiles(TransferState.DONE)).isEqualTo(12);
        for (Path file : files) {
            assertThat(Files.mismatch(file, downloads.resolve(file.getFileName()))).isEqualTo(-1L);
        }
        assertThat(fixture.stats().maxOpenFiles()).isAtMost(3);
    }

    @Test
    void aBorrowedSessionUsesAtMostTwoChannels() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).requestDelayMillis(2).start();
        FixtureSource source = new FixtureSource(fixture, false);
        SftpTransferQueue queue = queue(source, TransferSettings.defaults().withParallelTransfers(8),
            ConflictResolver.always(ConflictAction.OVERWRITE));
        Files.createDirectories(fixture.root().resolve("dest"));

        TransferBatch batch = queue.enqueueUpload(localFiles("up", 8, 64 * 1024), "/dest");
        await(batch);

        assertThat(batch.countFiles(TransferState.DONE)).isEqualTo(8);
        assertThat(fixture.stats().maxOpenFiles()).isAtMost(2);
        assertThat(queue.mostWorkers()).isAtMost(2);
    }

    @Test
    void aRefusedChannelShrinksThePoolAndTheBatchStillFinishes() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).requestDelayMillis(2).start();
        FixtureSource source = new FixtureSource(fixture, true);
        source.allowedChannels = 1; // like MaxSessions: the second extra channel is refused
        SftpTransferQueue queue = queue(source, TransferSettings.defaults(), ConflictResolver.always(ConflictAction.OVERWRITE));
        List<Path> files = localFiles("up", 12, 100 * 1024);
        Files.createDirectories(fixture.root().resolve("dest"));

        TransferBatch batch = queue.enqueueUpload(files, "/dest");
        await(batch);

        assertThat(batch.countFiles(TransferState.DONE)).isEqualTo(12);
        for (Path file : files) {
            assertThat(Files.mismatch(file, fixture.root().resolve("dest").resolve(file.getFileName()))).isEqualTo(-1L);
        }
        assertThat(source.opened.get()).isEqualTo(1);
        assertThat(source.attempts.get()).isAtLeast(2);
        // One own channel plus the primary one.
        assertThat(fixture.stats().maxOpenFiles()).isAtMost(2);
    }

    // ------------------------------------------------------------------ cancel, retry, connection lost

    @Test
    void cancellingOneItemLeavesTheOthersRunning() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).requestDelayMillis(1).start();
        SftpTransferQueue queue = queue(new FixtureSource(fixture, true), TransferSettings.defaults(),
            ConflictResolver.always(ConflictAction.OVERWRITE));
        List<Path> files = localFiles("up", 4, 512 * 1024);
        Files.createDirectories(fixture.root().resolve("dest"));
        queue.progressHook = (item, done) -> {
            if (item.name().equals("up-0.bin") && done > 64 * 1024) {
                queue.cancel(item);
            }
        };

        TransferBatch batch = queue.enqueueUpload(files, "/dest");
        await(batch);

        TransferItem cancelled = batch.items().get(0);
        assertThat(cancelled.state()).isEqualTo(TransferState.CANCELLED);
        assertThat(cancelled.isRetryable()).isTrue();
        assertThat(batch.countFiles(TransferState.DONE)).isEqualTo(3);
        Path dest = fixture.root().resolve("dest");
        assertThat(Files.exists(dest.resolve("up-0.bin"))).isFalse();
        assertThat(Files.exists(dest.resolve(PartFiles.partName("up-0.bin")))).isFalse();
        for (Path file : files.subList(1, 4)) {
            assertThat(Files.mismatch(file, dest.resolve(file.getFileName()))).isEqualTo(-1L);
        }
    }

    @Test
    void retryAfterAFailureResumesBothWays() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        TransferSettings settings = TransferSettings.defaults()
            .withResume(new ResumeIndex(tmp.resolve("config").resolve(ResumeIndex.FILE_NAME)), "conn-1");
        SftpTransferQueue queue = queue(new FixtureSource(fixture, true), settings,
            ConflictResolver.always(ConflictAction.OVERWRITE));
        int size = 1024 * 1024 + 17;
        List<Path> files = localFiles("big", 1, size);
        Files.createDirectories(fixture.root().resolve("dest"));
        AtomicBoolean failNext = new AtomicBoolean(true);
        queue.progressHook = (item, done) -> {
            if (done >= size * 4L / 10 && failNext.compareAndSet(true, false)) {
                throw new UncheckedIOException(new IOException("simulated drop"));
            }
        };

        TransferBatch upload = queue.enqueueUpload(files, "/dest");
        await(upload);
        TransferItem item = upload.items().get(0);
        assertThat(item.state()).isEqualTo(TransferState.FAILED);
        assertThat(item.message()).contains("simulated drop");
        assertThat(Files.exists(fixture.root().resolve("dest").resolve(PartFiles.partName("big-0.bin")))).isTrue();

        assertThat(queue.retry(item)).isTrue();
        await(upload);
        assertThat(item.state()).isEqualTo(TransferState.DONE);
        assertThat(item.resumedFrom()).isGreaterThan(0L);
        assertThat(Files.mismatch(files.get(0), fixture.root().resolve("dest/big-0.bin"))).isEqualTo(-1L);

        failNext.set(true);
        Path downloads = Files.createDirectories(tmp.resolve("down"));
        TransferBatch download = queue.enqueueDownload(List.of(RemoteEntryRef.file("/dest/big-0.bin", size)), downloads);
        await(download);
        TransferItem back = download.items().get(0);
        assertThat(back.state()).isEqualTo(TransferState.FAILED);
        assertThat(Files.exists(downloads.resolve(PartFiles.partName("big-0.bin")))).isTrue();

        assertThat(queue.retry(back)).isTrue();
        await(download);
        assertThat(back.state()).isEqualTo(TransferState.DONE);
        assertThat(back.resumedFrom()).isGreaterThan(0L);
        assertThat(Files.mismatch(files.get(0), downloads.resolve("big-0.bin"))).isEqualTo(-1L);
    }

    @Test
    void aClosedSourceFailsTheRestAsConnectionLostAndARetryOnANewSessionFinishes() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).requestDelayMillis(1).start();
        FixtureSource first = new FixtureSource(fixture, true);
        TransferSettings settings = TransferSettings.defaults().withParallelTransfers(1)
            .withResume(new ResumeIndex(tmp.resolve("config").resolve(ResumeIndex.FILE_NAME)), "conn-1");
        SftpTransferQueue queue = queue(first, settings, ConflictResolver.always(ConflictAction.OVERWRITE));
        List<Path> files = localFiles("up", 6, 256 * 1024);
        Files.createDirectories(fixture.root().resolve("dest"));
        queue.progressHook = (item, done) -> {
            Path part = fixture.root().resolve("dest").resolve(PartFiles.partName("up-2.bin"));
            try {
                // Mid-file, wait until the server holds a resumable part (more than the overlap), then drop.
                if (item.name().equals("up-2.bin") && done > 128 * 1024 && first.session.isOpen()) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while ((!Files.exists(part) || Files.size(part) <= ResumePlanner.OVERLAP_BYTES)
                            && System.nanoTime() < deadline) {
                        Thread.sleep(5);
                    }
                    first.closeSession();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };

        TransferBatch batch = queue.enqueueUpload(files, "/dest");
        await(batch);

        List<TransferItem> items = batch.items();
        assertThat(items.get(0).state()).isEqualTo(TransferState.DONE);
        assertThat(items.get(1).state()).isEqualTo(TransferState.DONE);
        for (TransferItem item : items.subList(2, 6)) {
            assertThat(item.state()).isEqualTo(TransferState.FAILED);
            assertThat(item.isConnectionLost()).isTrue();
            assertThat(item.isRetryable()).isTrue();
        }

        queue.progressHook = null;
        queue.useSource(new FixtureSource(fixture, true));
        assertThat(queue.retryFailed()).isEqualTo(4);
        await(batch);
        assertWithMessage(describe(batch)).that(batch.countFiles(TransferState.DONE)).isEqualTo(6);
        // The interrupted file continued from its part, although the dead connection could not record it.
        assertThat(items.get(2).resumedFrom()).isGreaterThan(0L);
        for (Path file : files) {
            assertThat(Files.mismatch(file, fixture.root().resolve("dest").resolve(file.getFileName()))).isEqualTo(-1L);
        }
    }

    // ------------------------------------------------------------------ per-target lock

    @Test
    void theSameTargetIsNeverWrittenTwiceAtOnceAndTheSecondGoesThroughConflictHandling() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).requestDelayMillis(2).start();
        Path dest = Files.createDirectories(fixture.root().resolve("dest"));
        int size = 512 * 1024;
        List<Path> files = localFiles("same", 1, size);
        AtomicInteger asked = new AtomicInteger();
        List<String> problems = new ArrayList<>();
        ConflictResolver resolver = (info, policy) -> {
            asked.incrementAndGet();
            try {
                // The first writer finished before the second may look: whole target, no part.
                if (Files.size(dest.resolve("same-0.bin")) != size) {
                    problems.add("target incomplete while the second item ran");
                }
                if (Files.exists(dest.resolve(PartFiles.partName("same-0.bin")))) {
                    problems.add("part still present while the second item ran");
                }
            } catch (IOException e) {
                problems.add(e.toString());
            }
            return new ConflictResolver.Resolution(ConflictAction.OVERWRITE, false);
        };
        SftpTransferQueue queue = queue(new FixtureSource(fixture, true), TransferSettings.defaults(), resolver);

        TransferBatch one = queue.enqueueUpload(files, "/dest");
        TransferBatch two = queue.enqueueUpload(files, "/dest/");
        await(one);
        await(two);

        assertThat(problems).isEmpty();
        assertThat(asked.get()).isEqualTo(1);
        assertThat(one.items().get(0).state()).isEqualTo(TransferState.DONE);
        assertThat(two.items().get(0).state()).isEqualTo(TransferState.DONE);
        assertThat(Files.mismatch(files.get(0), dest.resolve("same-0.bin"))).isEqualTo(-1L);
    }

    @Test
    void keepBothDownloadsUnderAFreeName() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        Files.writeString(fixture.root().resolve("a.txt"), "remote");
        Path downloads = Files.createDirectories(tmp.resolve("down"));
        Files.writeString(downloads.resolve("a.txt"), "local");
        SftpTransferQueue queue = queue(new FixtureSource(fixture, true), TransferSettings.defaults(),
            ConflictResolver.always(ConflictAction.RENAME));

        TransferBatch batch = queue.enqueueDownload(List.of(RemoteEntryRef.file("/a.txt", 6)), downloads);
        await(batch);

        TransferItem item = batch.items().get(0);
        assertThat(item.state()).isEqualTo(TransferState.DONE);
        assertThat(item.targetName()).isEqualTo("a (1).txt");
        assertThat(Files.readString(downloads.resolve("a.txt"))).isEqualTo("local");
        assertThat(Files.readString(downloads.resolve("a (1).txt"))).isEqualTo("remote");
    }

    // ------------------------------------------------------------------ expansion

    @Test
    void localFolderExpansionUploadsLinkedFilesSkipsLinkedFoldersAndSurvivesALoop() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        Path tree = Files.createDirectories(tmp.resolve("local/project"));
        Files.writeString(tree.resolve("a.txt"), "a");
        Files.createDirectories(tree.resolve("sub/deeper"));
        Files.writeString(tree.resolve("sub/deeper/b.txt"), "b");
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.writeString(tmp.resolve("linked.txt"), "linked content");
        try {
            Files.createSymbolicLink(tree.resolve("file-link"), tmp.resolve("linked.txt"));
            Files.createSymbolicLink(tree.resolve("dir-link"), outside);
            Files.createSymbolicLink(tree.resolve("sub/loop"), tree);
        } catch (UnsupportedOperationException | IOException e) {
            throw new SkipException("Symbolic links are not available here: " + e);
        }
        SftpTransferQueue queue = queue(new FixtureSource(fixture, true), TransferSettings.defaults(),
            ConflictResolver.always(ConflictAction.OVERWRITE));

        TransferBatch batch = queue.enqueueUpload(List.of(tree), "/");
        await(batch);

        Path remote = fixture.root().resolve("project");
        TransferItem top = batch.items().get(0);
        assertThat(top.kind()).isEqualTo(TransferItem.Kind.FOLDER);
        assertThat(top.state()).isEqualTo(TransferState.DONE);
        assertThat(Files.readString(remote.resolve("a.txt"))).isEqualTo("a");
        assertThat(Files.readString(remote.resolve("sub/deeper/b.txt"))).isEqualTo("b");
        assertThat(Files.isSymbolicLink(remote.resolve("file-link"))).isFalse();
        assertThat(Files.readString(remote.resolve("file-link"))).isEqualTo("linked content");
        assertThat(Files.exists(remote.resolve("dir-link"), LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.exists(remote.resolve("sub/loop"), LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(batch.skippedLinks()).containsExactly(
            tree.resolve("dir-link").toAbsolutePath().normalize().toString(),
            tree.resolve("sub/loop").toAbsolutePath().normalize().toString());
        assertThat(batch.countFiles(TransferState.SKIPPED)).isEqualTo(2);
    }

    @Test
    void remoteFolderExpansionNeverFollowsALinkedFolder() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        Path root = fixture.root();
        Files.createDirectories(root.resolve("proj/inner"));
        Files.writeString(root.resolve("proj/x.txt"), "x");
        Files.writeString(root.resolve("proj/inner/y.txt"), "y");
        Files.createDirectories(root.resolve("secret"));
        Files.writeString(root.resolve("secret/s.txt"), "s");
        try {
            Files.createSymbolicLink(root.resolve("proj/link"), Path.of("../secret"));
            Files.createSymbolicLink(root.resolve("proj/flink"), Path.of("../secret/s.txt"));
        } catch (UnsupportedOperationException | IOException e) {
            throw new SkipException("Symbolic links are not available here: " + e);
        }
        Path downloads = Files.createDirectories(tmp.resolve("down"));
        SftpTransferQueue queue = queue(new FixtureSource(fixture, true), TransferSettings.defaults(),
            ConflictResolver.always(ConflictAction.OVERWRITE));

        TransferBatch batch = queue.enqueueDownload(List.of(RemoteEntryRef.directory("/proj")), downloads);
        await(batch);

        Path local = downloads.resolve("proj");
        assertThat(batch.items().get(0).state()).isEqualTo(TransferState.DONE);
        assertThat(Files.readString(local.resolve("x.txt"))).isEqualTo("x");
        assertThat(Files.readString(local.resolve("inner/y.txt"))).isEqualTo("y");
        assertThat(Files.readString(local.resolve("flink"))).isEqualTo("s");
        assertThat(Files.exists(local.resolve("link"), LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(batch.skippedLinks()).containsExactly("/proj/link");
    }

    // ------------------------------------------------------------------ helpers

    private SftpTransferQueue queue(SftpChannelSource source, TransferSettings settings, ConflictResolver resolver) {
        SftpTransferQueue queue = new SftpTransferQueue(source, settings, resolver);
        queue.idleMillis = 500;
        queues.add(queue);
        return queue;
    }

    private List<Path> localFiles(String prefix, int count, int size) throws IOException {
        Path folder = Files.createDirectories(tmp.resolve("local-" + prefix));
        Random random = new Random(prefix.hashCode());
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] data = new byte[size + i];
            random.nextBytes(data);
            files.add(Files.write(folder.resolve(prefix + "-" + i + ".bin"), data));
        }
        return files;
    }

    private static String describe(TransferBatch batch) {
        StringBuilder text = new StringBuilder();
        for (TransferItem item : batch.allItems()) {
            text.append(item).append(": ").append(item.message()).append('\n');
        }
        return text.toString();
    }

    private static void await(TransferBatch batch) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        // A retried batch is busy again at once; give the workers a moment to pick it up.
        while (!batch.isFinished()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Batch did not finish: " + batch.allItems());
            }
            Thread.sleep(10);
        }
    }

    /** A source over one loopback session; can refuse extra channels and lose its session. */
    static final class FixtureSource implements SftpChannelSource {
        final ClientSession session;
        final SftpClient primary;
        final boolean owns;
        private final SftpLoopbackFixture fixture;
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger opened = new AtomicInteger();
        volatile int allowedChannels = Integer.MAX_VALUE;

        FixtureSource(SftpLoopbackFixture fixture, boolean owns) throws IOException {
            this.fixture = fixture;
            this.session = fixture.connect();
            this.primary = fixture.openSftp(session, 3);
            this.owns = owns;
        }

        @Override
        public SftpClient primaryClient() {
            return primary;
        }

        @Override
        public SftpClient openChannel() throws IOException {
            if (attempts.incrementAndGet() > allowedChannels) {
                throw new IOException("channel open failed: MaxSessions reached");
            }
            SftpClient channel = fixture.openSftp(session, 3);
            opened.incrementAndGet();
            return channel;
        }

        @Override
        public boolean isOpen() {
            return session.isOpen() && primary.isOpen();
        }

        @Override
        public boolean ownsSession() {
            return owns;
        }

        @Override
        public String describe() {
            return "loopback";
        }

        void closeSession() {
            session.close(true);
        }
    }
}
