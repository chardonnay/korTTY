package de.kortty.ui.sftp;

import de.kortty.core.sftp.SftpChannelSource;
import de.kortty.core.sftp.SftpLoopbackFixture;
import de.kortty.core.sftp.transfer.ConflictAction;
import de.kortty.core.sftp.transfer.ConflictResolver;
import de.kortty.core.sftp.transfer.PartFiles;
import de.kortty.core.sftp.transfer.RemoteEntryRef;
import de.kortty.core.sftp.transfer.ResumeIndex;
import de.kortty.core.sftp.transfer.ResumePlanner;
import de.kortty.core.sftp.transfer.TransferBatch;
import de.kortty.core.sftp.transfer.TransferDirection;
import de.kortty.core.sftp.transfer.TransferItem;
import de.kortty.core.sftp.transfer.TransferQueueListener;
import de.kortty.core.sftp.transfer.TransferSettings;
import de.kortty.core.sftp.transfer.TransferState;
import de.kortty.ui.I18n;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The SFTP tab's queue host against a loopback SSHD, without JavaFX: the queue is made on the first
 * session, moved to a new session after a reconnect and closed with the tab; the row model's
 * status and summary texts over real batches.
 */
class SftpTransferQueueHostTest {

    private static final long TIMEOUT_SECONDS = 60;

    private Path tmp;
    private SftpLoopbackFixture fixture;
    private final List<SftpTransferQueueHost> hosts = new ArrayList<>();
    private final List<Source> sources = new ArrayList<>();

    @BeforeMethod
    void setUp() throws IOException {
        tmp = Files.createTempDirectory("kortty-sftp-host-");
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        hosts.forEach(SftpTransferQueueHost::close);
        hosts.clear();
        for (Source source : sources) {
            source.session.close(true);
        }
        sources.clear();
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

    @Test
    void theQueueIsMadeOnTheFirstSessionAndReportsEveryFile() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        Events events = new Events();
        AtomicInteger created = new AtomicInteger();
        SftpTransferQueueHost host = host(events, created);

        assertThat(host.enqueueUpload(List.of(tmp.resolve("x")), "/").isPresent()).isFalse();
        host.onSessionReady(source());
        assertThat(created.get()).isEqualTo(1);
        assertThat(host.queue().isPresent()).isTrue();

        Path folder = Files.createDirectories(tmp.resolve("local/project/sub"));
        Files.writeString(folder.resolve("deep.txt"), "deep");
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            files.add(Files.writeString(tmp.resolve("local/f" + i + ".txt"), "file " + i));
        }
        files.add(tmp.resolve("local/project"));
        Path missing = tmp.resolve("local/missing.txt");
        files.add(missing);
        Files.createDirectories(fixture.root().resolve("dest"));

        TransferBatch batch = host.enqueueUpload(files, "/dest").orElseThrow();
        await(batch);

        assertThat(batch.countFiles(TransferState.DONE)).isEqualTo(4);
        assertThat(Files.readString(fixture.root().resolve("dest/project/sub/deep.txt"))).isEqualTo("deep");
        assertThat(events.finishedBatches).contains(batch);
        assertThat(SftpTransferRowModel.batchFinished(batch)).isEqualTo(I18n.get("sftp.uploadComplete", "4/5"));
        String summary = SftpTransferRowModel.batchSummary(batch);
        assertThat(summary).contains("missing.txt");
        assertThat(summary).startsWith(I18n.get("sftp.queue.summary.failed", "1", "5"));

        TransferItem deep = batch.allItems().stream().filter(item -> "deep.txt".equals(item.name())).findFirst()
            .orElseThrow();
        assertThat(SftpTransferRowModel.displayName(deep)).isEqualTo("project/sub/deep.txt");
        SftpTransferRowModel row = SftpTransferRowModel.of(deep, Locale.ROOT);
        assertThat(row.state()).isEqualTo(I18n.get("sftp.queue.state.done"));
        assertThat(row.progress()).isEqualTo(1.0);
        assertThat(row.direction()).isEqualTo(I18n.get("sftp.queue.direction.upload"));
        assertThat(row.speed()).isEmpty();

        SftpTransferRowModel.Totals totals = SftpTransferRowModel.Totals.of(host.queue().orElseThrow().batches());
        assertThat(totals.files()).isEqualTo(5);
        assertThat(totals.filesDone()).isEqualTo(4);
        assertThat(totals.active()).isEqualTo(0);
        assertThat(SftpTransferRowModel.status(totals, Locale.ROOT)).isNull();
    }

    @Test
    void aReconnectMovesTheQueueToTheNewSession() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        AtomicInteger created = new AtomicInteger();
        SftpTransferQueueHost host = host(new Events(), created);
        Source first = source();
        host.onSessionReady(first);
        host.onSessionLost();
        assertThat(host.isSessionLost()).isTrue();
        first.session.close(true);

        host.onSessionReady(source());
        assertThat(host.isSessionLost()).isFalse();
        assertThat(created.get()).isEqualTo(1);
        Files.writeString(fixture.root().resolve("remote.txt"), "remote");
        Path downloads = Files.createDirectories(tmp.resolve("down"));
        TransferBatch batch = host.enqueueDownload(List.of(RemoteEntryRef.file("/remote.txt", 6)), downloads)
            .orElseThrow();
        await(batch);
        assertThat(batch.countFiles(TransferState.DONE)).isEqualTo(1);
        assertThat(Files.readString(downloads.resolve("remote.txt"))).isEqualTo("remote");
        assertThat(SftpTransferRowModel.batchSummary(batch)).isNull();
        assertThat(SftpTransferRowModel.batchFinished(batch)).isEqualTo(I18n.get("sftp.downloadComplete", "1"));
    }

    @Test
    void aRunningUploadsPartIsActiveAndTheHostCloseCancelsIt() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).requestDelayMillis(20).start();
        SftpTransferQueueHost host = host(new Events(), new AtomicInteger());
        host.onSessionReady(source());
        Path big = Files.write(tmp.resolve("big.bin"), new byte[4 * 1024 * 1024]);
        Files.createDirectories(fixture.root().resolve("dest"));

        TransferBatch batch = host.enqueueUpload(List.of(big), "/dest/").orElseThrow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (batch.items().get(0).state() != TransferState.RUNNING) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(5);
        }
        assertThat(host.activePartNames(TransferDirection.UPLOAD, "/dest")).containsExactly("big.bin.kortty-part");
        assertThat(host.activePartNames(TransferDirection.DOWNLOAD, "/dest")).isEmpty();
        assertThat(host.activePartNames(TransferDirection.UPLOAD, "/other")).isEmpty();

        host.close();
        await(batch);
        assertThat(batch.items().get(0).state()).isEqualTo(TransferState.CANCELLED);
        assertThat(host.enqueueUpload(List.of(big), "/dest").isPresent()).isFalse();
        assertThat(host.activePartNames(TransferDirection.UPLOAD, "/dest")).isEmpty();
    }

    @Test
    void onlyWaitingOrWorkingTransfersMakeTheTabAskBeforeClosing() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).requestDelayMillis(20).start();
        SftpTransferQueueHost host = host(new Events(), new AtomicInteger());
        // No queue yet: nothing to ask about.
        assertThat(host.needsCloseConfirmation()).isFalse();
        assertThat(host.activeTransferCount()).isEqualTo(0);
        host.onSessionReady(source());
        assertThat(host.needsCloseConfirmation()).isFalse();

        Files.createDirectories(fixture.root().resolve("dest"));
        Path small = Files.writeString(tmp.resolve("small.txt"), "small");
        TransferBatch finished = host.enqueueUpload(List.of(small), "/dest").orElseThrow();
        await(finished);
        assertThat(finished.items().get(0).state()).isEqualTo(TransferState.DONE);
        // A finished (or failed) transfer asks nothing.
        Path missing = tmp.resolve("missing.txt");
        TransferBatch failed = host.enqueueUpload(List.of(missing), "/dest").orElseThrow();
        await(failed);
        assertThat(failed.items().get(0).state()).isEqualTo(TransferState.FAILED);
        assertThat(host.needsCloseConfirmation()).isFalse();
        assertThat(host.activeTransferCount()).isEqualTo(0);

        Path big = Files.write(tmp.resolve("big.bin"), new byte[4 * 1024 * 1024]);
        Path folder = Files.createDirectories(tmp.resolve("folder"));
        Files.write(folder.resolve("inner.bin"), new byte[1024 * 1024]);
        TransferBatch running = host.enqueueUpload(List.of(big, folder), "/dest").orElseThrow();
        assertThat(host.needsCloseConfirmation()).isTrue();
        // One per row of the transfer list, a folder once.
        assertThat(host.activeTransferCount()).isEqualTo(2);

        host.queue().orElseThrow().cancelAll();
        await(running);
        assertThat(host.needsCloseConfirmation()).isFalse();

        TransferBatch again = host.enqueueUpload(List.of(big), "/dest").orElseThrow();
        assertThat(host.needsCloseConfirmation()).isTrue();
        host.close();
        await(again);
        // A closed host (the tab is going) asks nothing more.
        assertThat(host.needsCloseConfirmation()).isFalse();
    }

    @Test
    void aDownloadInterruptedByAServerRestartResumesOnTheNewSession() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).requestDelayMillis(20).start();
        TransferSettings settings = TransferSettings.defaults()
            .withResume(new ResumeIndex(tmp.resolve("config").resolve(ResumeIndex.FILE_NAME)), "conn-restart");
        SftpTransferQueueHost host = new SftpTransferQueueHost(() -> settings,
            () -> ConflictResolver.always(ConflictAction.OVERWRITE), new Events(), null);
        hosts.add(host);
        host.onSessionReady(source());

        byte[] content = new byte[4 * 1024 * 1024];
        new Random(7).nextBytes(content);
        Files.write(fixture.root().resolve("big.bin"), content);
        Path downloads = Files.createDirectories(tmp.resolve("down"));
        Path target = downloads.resolve("big.bin");
        Path part = PartFiles.localPart(target);

        TransferBatch batch = host.enqueueDownload(List.of(RemoteEntryRef.file("/big.bin", content.length)), downloads)
            .orElseThrow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!Files.exists(part) || Files.size(part) <= 2L * ResumePlanner.OVERLAP_BYTES) {
            assertWithMessage("part file grows").that(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(5);
        }
        // The server goes away mid-file, as on a restart.
        fixture.close();
        host.onSessionLost();
        await(batch);
        TransferItem item = batch.items().get(0);
        assertThat(item.state()).isEqualTo(TransferState.FAILED);
        assertThat(item.isConnectionLost()).isTrue();
        assertThat(item.isRetryable()).isTrue();
        assertThat(Files.exists(part)).isTrue();
        assertThat(Files.exists(target)).isFalse();

        // The same server (same files) is back; the tab reconnects with a new session.
        fixture = SftpLoopbackFixture.builder(tmp).start();
        host.onSessionReady(source());
        assertThat(host.queue().orElseThrow().retryFailed()).isEqualTo(1);
        await(batch);

        assertWithMessage(String.valueOf(item)).that(item.state()).isEqualTo(TransferState.DONE);
        assertThat(item.resumedFrom()).isGreaterThan(0L);
        assertThat(Files.readAllBytes(target)).isEqualTo(content);
        assertThat(Files.exists(part)).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private SftpTransferQueueHost host(Events events, AtomicInteger created) {
        SftpTransferQueueHost host = new SftpTransferQueueHost(TransferSettings::defaults,
            () -> ConflictResolver.always(ConflictAction.OVERWRITE), events, created::incrementAndGet);
        hosts.add(host);
        return host;
    }

    private Source source() throws IOException {
        Source source = new Source(fixture);
        sources.add(source);
        return source;
    }

    private static void await(TransferBatch batch) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!batch.isFinished()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Batch did not finish: " + batch.allItems());
            }
            Thread.sleep(10);
        }
    }

    private static final class Events implements TransferQueueListener {
        final List<TransferBatch> finishedBatches = new CopyOnWriteArrayList<>();

        @Override
        public void itemsAdded(List<TransferItem> items) {
        }

        @Override
        public void itemChanged(TransferItem item) {
        }

        @Override
        public void batchFinished(TransferBatch batch) {
            finishedBatches.add(batch);
        }
    }

    /** One loopback session as the tab's SFTP session. */
    private static final class Source implements SftpChannelSource {
        final ClientSession session;
        final SftpClient primary;
        private final SftpLoopbackFixture fixture;

        Source(SftpLoopbackFixture fixture) throws IOException {
            this.fixture = fixture;
            this.session = fixture.connect();
            this.primary = fixture.openSftp(session, 3);
        }

        @Override
        public SftpClient primaryClient() {
            return primary;
        }

        @Override
        public SftpClient openChannel() throws IOException {
            return fixture.openSftp(session, 3);
        }

        @Override
        public boolean isOpen() {
            return session.isOpen() && primary.isOpen();
        }

        @Override
        public boolean ownsSession() {
            return true;
        }

        @Override
        public String describe() {
            return "loopback";
        }
    }
}
