package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * Broadcast mode writes the keys it mirrors into the other panes through {@link MirroredInputWriter}
 * instead of on the JavaFX key filter, so one stalled pane can no longer freeze the window.
 */
class MirroredInputWriterTest {

    private static final long WAIT_SECONDS = 5;

    private ExecutorService pool;

    @BeforeMethod
    void startPool() {
        pool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "mirrored-input-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterMethod(alwaysRun = true)
    void stopPool() {
        pool.shutdownNow();
    }

    @Test(timeOut = 10_000)
    void keepsTheOrderOfEveryTarget() throws Exception {
        MirroredInputWriter writer = new MirroredInputWriter(pool);
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        RecordingConnector target = new RecordingConnector(firstWriteStarted, releaseFirstWrite);
        RecordingConnector other = new RecordingConnector();

        writer.write(target, "l");
        // The first write is still in progress while the rest of the keys are queued behind it.
        assertThat(firstWriteStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        byte[] reused = "s".getBytes(StandardCharsets.US_ASCII);
        writer.write(target, reused);
        reused[0] = 'X';
        writer.write(other, "pwd");
        writer.write(target, "\u001B[A");
        writer.write(target, "\r");
        releaseFirstWrite.countDown();

        target.awaitWrites(4);
        other.awaitWrites(1);
        assertThat(target.writes()).containsExactly("l", "s", "\u001B[A", "\r").inOrder();
        assertThat(other.writes()).containsExactly("pwd");
        awaitIdle(writer);
    }

    @Test(timeOut = 10_000)
    void aBlockedTargetDelaysNeitherTheOtherTargetsNorTheCaller() throws Exception {
        MirroredInputWriter writer = new MirroredInputWriter(pool);
        CountDownLatch blockedWriteStarted = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        RecordingConnector stalled = new RecordingConnector(blockedWriteStarted, unblock);
        RecordingConnector healthy = new RecordingConnector();

        // The caller is the JavaFX key filter in the app; here it must come back while a write hangs.
        CompletableFuture<Void> caller = CompletableFuture.runAsync(() -> {
            for (String key : List.of("e", "x", "i", "t")) {
                writer.write(stalled, key);
                writer.write(healthy, key);
            }
        });
        caller.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(blockedWriteStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        healthy.awaitWrites(4);
        assertThat(healthy.writes()).containsExactly("e", "x", "i", "t").inOrder();
        assertThat(stalled.writes()).isEmpty();

        unblock.countDown();
        stalled.awaitWrites(4);
        assertThat(stalled.writes()).containsExactly("e", "x", "i", "t").inOrder();
        awaitIdle(writer);
    }

    @Test(timeOut = 10_000)
    void theMirroredInputScopeIsActiveOnlyDuringTheWrite() throws Exception {
        List<Boolean> afterEachDrain = new ArrayList<>();
        CountDownLatch drained = new CountDownLatch(1);
        // Records the scope on the writer's own thread once the drain task has returned.
        MirroredInputWriter writer = new MirroredInputWriter(task -> pool.execute(() -> {
            task.run();
            synchronized (afterEachDrain) {
                afterEachDrain.add(MirroredInput.active());
            }
            drained.countDown();
        }));
        RecordingConnector target = new RecordingConnector();

        assertThat(MirroredInput.active()).isFalse();
        writer.write(target, "ls");
        writer.write(target, new byte[] {'\r'});

        target.awaitWrites(2);
        assertThat(drained.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(target.activeDuringWrites()).containsExactly(true, true);
        synchronized (afterEachDrain) {
            // One drain task, or two when the first ran empty before the second key was queued.
            assertThat(afterEachDrain).isNotEmpty();
            assertThat(afterEachDrain).doesNotContain(true);
        }
        assertThat(MirroredInput.active()).isFalse();
    }

    @Test(timeOut = 10_000)
    void aFailingWriteDoesNotStopTheTargetsLaterWrites() throws Exception {
        MirroredInputWriter writer = new MirroredInputWriter(pool);
        RecordingConnector target = new RecordingConnector();
        target.failNextWrite();

        writer.write(target, "lost");
        writer.write(target, "kept");

        target.awaitWrites(1);
        assertThat(target.writes()).containsExactly("kept");
        awaitIdle(writer);
    }

    @Test
    void runRestoresTheScopeAfterNestedAndFailingWrites() {
        List<Boolean> seen = new ArrayList<>();

        assertThrows(IOException.class, () -> MirroredInput.run(() -> {
            MirroredInput.run(() -> seen.add(MirroredInput.active()));
            seen.add(MirroredInput.active());
            throw new IOException("channel closed");
        }));

        assertThat(seen).containsExactly(true, true).inOrder();
        assertThat(MirroredInput.active()).isFalse();
    }

    /** The queue of a target is dropped once it runs empty, so closed panes leave nothing behind. */
    private static void awaitIdle(MirroredInputWriter writer) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (writer.busyTargetCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(writer.busyTargetCount()).isEqualTo(0);
    }

    /** Records what reaches it and whether the mirrored-input scope was active at the time. */
    private static final class RecordingConnector implements TtyConnector {
        private final CountDownLatch firstWriteStarted;
        private final CountDownLatch releaseFirstWrite;
        private final List<String> writes = new ArrayList<>();
        private final List<Boolean> activeDuringWrites = new ArrayList<>();
        private boolean firstWrite = true;
        private boolean failNext;

        RecordingConnector() {
            this(null, null);
        }

        /** A connector whose first write blocks until {@code releaseFirstWrite} opens. */
        RecordingConnector(CountDownLatch firstWriteStarted, CountDownLatch releaseFirstWrite) {
            this.firstWriteStarted = firstWriteStarted;
            this.releaseFirstWrite = releaseFirstWrite;
        }

        synchronized void failNextWrite() {
            failNext = true;
        }

        @Override
        public void write(String string) throws IOException {
            boolean block;
            synchronized (this) {
                if (failNext) {
                    failNext = false;
                    throw new IOException("channel closed");
                }
                block = firstWrite && releaseFirstWrite != null;
                firstWrite = false;
            }
            if (block) {
                firstWriteStarted.countDown();
                try {
                    releaseFirstWrite.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
            synchronized (this) {
                writes.add(string);
                activeDuringWrites.add(MirroredInput.active());
                notifyAll();
            }
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            write(new String(bytes, StandardCharsets.US_ASCII));
        }

        synchronized List<String> writes() {
            return List.copyOf(writes);
        }

        synchronized List<Boolean> activeDuringWrites() {
            return List.copyOf(activeDuringWrites);
        }

        synchronized void awaitWrites(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (writes.size() < count) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
            assertThat(writes.size()).isAtLeast(count);
        }

        @Override
        public int read(char[] buf, int offset, int length) {
            return -1;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public String getName() {
            return "recording";
        }

        @Override
        public void close() {
        }
    }
}
