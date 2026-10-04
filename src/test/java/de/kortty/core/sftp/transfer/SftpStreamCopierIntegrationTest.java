package de.kortty.core.sftp.transfer;

import de.kortty.core.sftp.SftpLoopbackFixture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.fail;

/**
 * {@link SftpStreamCopier} against a real loopback SSHD: byte-exact round trips, monotonic
 * progress, pipelined reads, cancellation from another thread (cooperative on the shared channel,
 * by closing an owned one), and resume offsets. Runs with a forced SFTP v3 (what OpenSSH speaks)
 * and with MINA's default negotiation.
 */
class SftpStreamCopierIntegrationTest {

    private static final int SIZE = 5 * 1024 * 1024 + 17;
    private static final long CANCEL_AT = 1024 * 1024;

    private Path tmp;
    private SftpLoopbackFixture fixture;
    private ExecutorService executor;
    private byte[] data;

    @BeforeMethod
    void setUp() throws IOException {
        tmp = Files.createTempDirectory("kortty-sftp-copier-it-");
        data = new byte[SIZE];
        new Random(7).nextBytes(data);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "copier-it-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        executor.shutdownNow();
        if (fixture != null) {
            fixture.close();
            fixture = null;
        }
        deleteTree(tmp);
    }

    @DataProvider
    Object[][] versions() {
        return new Object[][] {{3}, {SftpLoopbackFixture.DEFAULT_VERSION}};
    }

    @Test(dataProvider = "versions")
    void roundTripIsByteEqualWithMonotonicProgress(int version) throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        SftpClient client = fixture.openSftp(fixture.connect(), version);
        if (version == 3) {
            assertThat(client.getVersion()).isEqualTo(3);
        }
        Path local = tmp.resolve("source.bin");
        Files.write(local, data);

        List<long[]> upProgress = new CopyOnWriteArrayList<>();
        long uploaded = SftpStreamCopier.upload(client, "/data.bin", local, 0,
            (done, total) -> upProgress.add(new long[] {done, total}), TransferCancellation.create());

        assertThat(uploaded).isEqualTo(SIZE);
        assertThat(Files.mismatch(local, fixture.root().resolve("data.bin"))).isEqualTo(-1L);
        assertMonotonicEndingAt(upProgress, SIZE);

        Path back = tmp.resolve("back.bin");
        List<long[]> downProgress = new CopyOnWriteArrayList<>();
        long downloaded = SftpStreamCopier.download(client, "/data.bin", back, 0,
            (done, total) -> downProgress.add(new long[] {done, total}), TransferCancellation.create());

        assertThat(downloaded).isEqualTo(SIZE);
        assertThat(Files.mismatch(local, back)).isEqualTo(-1L);
        assertMonotonicEndingAt(downProgress, SIZE);
        awaitZero(() -> fixture.stats().openHandles());
    }

    @Test(dataProvider = "versions")
    void downloadKeepsSeveralReadsInFlight(int version) throws Exception {
        // A tiny delay per READ lets the requests the client sent ahead queue up on the server.
        fixture = SftpLoopbackFixture.builder(tmp).requestDelayMillis(1).start();
        Files.write(fixture.root().resolve("data.bin"), data);
        SftpClient client = fixture.openSftp(fixture.connect(), version);

        SftpStreamCopier.download(client, "/data.bin", tmp.resolve("out.bin"), 0,
            TransferProgressListener.NONE, TransferCancellation.create());

        assertThat(fixture.stats().reads()).isGreaterThan(1);
        assertThat(fixture.stats().maxInFlightReads()).isGreaterThan(1);
        assertThat(Files.mismatch(fixture.root().resolve("data.bin"), tmp.resolve("out.bin"))).isEqualTo(-1L);
    }

    @Test(dataProvider = "versions")
    void cancelOnTheSharedChannelStopsWithinOneBufferAndKeepsTheChannel(int version) throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        Files.write(fixture.root().resolve("data.bin"), data);
        SftpClient primary = fixture.openSftp(fixture.connect(), version);
        TransferCancellation cancel = TransferCancellation.create();
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicLong pausedAt = new AtomicLong(-1);
        AtomicLong last = new AtomicLong();

        Future<Long> transfer = executor.submit(() -> SftpStreamCopier.download(primary, "/data.bin",
            tmp.resolve("partial.bin"), 0, (done, total) -> {
                last.set(done);
                if (done >= CANCEL_AT && pausedAt.compareAndSet(-1, done)) {
                    reached.countDown();
                    awaitQuietly(cancelled);
                }
            }, cancel));

        assertThat(reached.await(30, TimeUnit.SECONDS)).isTrue();
        cancel.cancel();
        cancelled.countDown();

        assertCancelled(transfer);
        int buffer = SftpStreamCopier.bufferSize(primary, true);
        assertThat(last.get() - pausedAt.get()).isAtMost(buffer);
        assertThat(last.get()).isLessThan(SIZE);
        awaitZero(() -> fixture.stats().openHandles());
        // The shared channel survives a cancel: the tab keeps listing with it.
        assertThat(primary.isOpen()).isTrue();
        assertThat(primary.stat("/data.bin").getSize()).isEqualTo(SIZE);
    }

    @Test(dataProvider = "versions")
    void cancelOnAnOwnedChannelClosesOnlyThatChannel(int version) throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        Path local = tmp.resolve("source.bin");
        Files.write(local, data);
        ClientSession session = fixture.connect();
        SftpClient primary = fixture.openSftp(session, version);
        SftpClient owned = fixture.openSftp(session, version);
        assertThat(fixture.stats().openChannels()).isEqualTo(2);
        TransferCancellation cancel = TransferCancellation.create();
        cancel.attachOwnedChannel(owned);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);

        Future<Long> transfer = executor.submit(() -> SftpStreamCopier.upload(owned, "/up.bin", local, 0,
            (done, total) -> {
                if (done >= CANCEL_AT && reached.getCount() > 0) {
                    reached.countDown();
                    awaitQuietly(cancelled);
                }
            }, cancel));

        assertThat(reached.await(30, TimeUnit.SECONDS)).isTrue();
        cancel.cancel();
        cancelled.countDown();

        assertCancelled(transfer);
        assertThat(owned.isOpen()).isFalse();
        awaitZero(() -> fixture.stats().openHandles());
        awaitZero(() -> fixture.stats().openChannels() - 1);
        assertThat(primary.isOpen()).isTrue();
        assertThat(session.isOpen()).isTrue();
    }

    @Test
    void attachingToAnAlreadyCancelledTokenClosesTheChannelAtOnce() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        SftpClient owned = fixture.openSftp();
        TransferCancellation cancel = TransferCancellation.create();
        cancel.cancel();

        cancel.attachOwnedChannel(owned);

        assertThat(owned.isOpen()).isFalse();
        assertThrows(TransferCancelledException.class, () -> SftpStreamCopier.download(owned, "/x",
            tmp.resolve("x"), 0, TransferProgressListener.NONE, cancel));
        assertThat(Files.exists(tmp.resolve("x"))).isFalse();
    }

    @Test(dataProvider = "versions")
    void nonZeroOffsetTransfersOnlyTheTail(int version) throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        SftpClient client = fixture.openSftp(fixture.connect(), version);
        int offset = 3 * 1024 * 1024 + 5;
        Files.write(fixture.root().resolve("data.bin"), data);

        // Download: the local part holds the head plus some garbage beyond it, which is cut away.
        Path part = tmp.resolve("data.part");
        byte[] head = Arrays.copyOf(data, offset + 100);
        Arrays.fill(head, offset, head.length, (byte) 0x55);
        Files.write(part, head);
        List<long[]> progress = new CopyOnWriteArrayList<>();
        long copied = SftpStreamCopier.download(client, "/data.bin", part, offset,
            (done, total) -> progress.add(new long[] {done, total}), TransferCancellation.create());

        assertThat(copied).isEqualTo(SIZE - offset);
        assertThat(progress.get(0)[0]).isEqualTo(offset);
        assertMonotonicEndingAt(progress, SIZE);
        assertThat(Files.mismatch(part, fixture.root().resolve("data.bin"))).isEqualTo(-1L);

        // Upload: the remote part holds the head; only the tail travels.
        Path local = tmp.resolve("source.bin");
        Files.write(local, data);
        Files.write(fixture.root().resolve("up.part"), Arrays.copyOf(data, offset));
        int writesBefore = fixture.stats().writes();
        long uploaded = SftpStreamCopier.upload(client, "/up.part", local, offset,
            TransferProgressListener.NONE, TransferCancellation.create());

        assertThat(uploaded).isEqualTo(SIZE - offset);
        assertThat(Files.mismatch(local, fixture.root().resolve("up.part"))).isEqualTo(-1L);
        int buffer = SftpStreamCopier.bufferSize(client, false);
        int tailWrites = fixture.stats().writes() - writesBefore;
        assertThat(tailWrites).isAtMost((SIZE - offset) / buffer + 1);
    }

    @Test
    void offsetBeyondTheSourceIsRefused() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        SftpClient client = fixture.openSftp();
        Files.write(fixture.root().resolve("small.bin"), new byte[10]);
        Path part = tmp.resolve("small.part");
        Files.write(part, new byte[20]);

        assertThrows(IOException.class, () -> SftpStreamCopier.download(client, "/small.bin", part, 20,
            TransferProgressListener.NONE, TransferCancellation.create()));
        // A local part shorter than the offset cannot be resumed either.
        Files.write(fixture.root().resolve("big.bin"), new byte[100]);
        assertThrows(IOException.class, () -> SftpStreamCopier.download(client, "/big.bin", part, 50,
            TransferProgressListener.NONE, TransferCancellation.create()));
        awaitZero(() -> fixture.stats().openHandles());
    }

    @Test
    void downloadNeverFollowsASymlinkAtTheLocalTarget() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        SftpClient client = fixture.openSftp();
        Files.write(fixture.root().resolve("data.bin"), new byte[] {1, 2, 3});
        Path victim = Files.writeString(tmp.resolve("victim.txt"), "keep");
        Path link = tmp.resolve("link.bin");
        try {
            Files.createSymbolicLink(link, victim);
        } catch (UnsupportedOperationException | IOException e) {
            return; // no symlinks here (Windows without the privilege)
        }

        assertThrows(IOException.class, () -> SftpStreamCopier.download(client, "/data.bin", link, 0,
            TransferProgressListener.NONE, TransferCancellation.create()));
        assertThat(Files.readString(victim)).isEqualTo("keep");
    }

    @Test
    void bufferSizeFollowsTheAnnouncedLimitsAndFallsBackWithoutThem() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        SftpClient withLimits = fixture.openSftp();
        assertThat(withLimits.getServerExtensions()).containsKey("limits@openssh.com");
        int announced = SftpStreamCopier.bufferSize(withLimits, true);
        assertThat(announced).isAtLeast(SftpStreamCopier.MIN_BUFFER_SIZE);
        assertThat(announced).isAtMost(SftpStreamCopier.MAX_BUFFER_SIZE);
        fixture.close();

        fixture = SftpLoopbackFixture.builder(tmp.resolve("bare")).advertiseExtensions(false).start();
        SftpClient bare = fixture.openSftp(fixture.connect(), 3);
        assertThat(bare.getServerExtensions()).doesNotContainKey("limits@openssh.com");
        assertThat(SftpStreamCopier.bufferSize(bare, true)).isEqualTo(SftpStreamCopier.DEFAULT_BUFFER_SIZE);
        assertThat(SftpStreamCopier.bufferSize(bare, false)).isEqualTo(SftpStreamCopier.DEFAULT_BUFFER_SIZE);

        // A bare v3 server still round-trips.
        Path local = tmp.resolve("source.bin");
        Files.write(local, data);
        SftpStreamCopier.upload(bare, "/data.bin", local, 0, TransferProgressListener.NONE,
            TransferCancellation.create());
        Path back = tmp.resolve("back.bin");
        SftpStreamCopier.download(bare, "/data.bin", back, 0, TransferProgressListener.NONE,
            TransferCancellation.create());
        assertThat(Files.mismatch(local, back)).isEqualTo(-1L);
    }

    private static void assertMonotonicEndingAt(List<long[]> progress, long size) {
        assertThat(progress).isNotEmpty();
        long previous = -1;
        for (long[] sample : progress) {
            assertThat(sample[0]).isAtLeast(previous);
            assertThat(sample[1]).isEqualTo(size);
            previous = sample[0];
        }
        assertThat(previous).isEqualTo(size);
    }

    private static void assertCancelled(Future<Long> transfer) throws InterruptedException {
        try {
            transfer.get(30, TimeUnit.SECONDS);
            fail("the transfer finished although it was cancelled");
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(TransferCancelledException.class);
        } catch (java.util.concurrent.TimeoutException e) {
            fail("the cancelled transfer did not stop");
        }
    }

    private static void awaitZero(IntSupplier value) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (value.getAsInt() != 0 && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(value.getAsInt()).isEqualTo(0);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
