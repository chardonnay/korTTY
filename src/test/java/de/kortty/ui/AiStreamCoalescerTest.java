package de.kortty.ui;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.truth.Truth.assertThat;

public class AiStreamCoalescerTest {

    /** Records scheduled drains instead of running them, so the test decides when the "UI thread" runs. */
    private static final class ManualScheduler implements AiStreamCoalescer.Scheduler {
        final List<Runnable> drains = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        @Override
        public void schedule(Runnable drain, long delayMillis) {
            drains.add(drain);
            delays.add(delayMillis);
        }

        void runAll() {
            List<Runnable> pending = new ArrayList<>(drains);
            drains.clear();
            pending.forEach(Runnable::run);
        }
    }

    private static final class RecordingSink implements AiStreamCoalescer.Sink {
        final List<String> events = new ArrayList<>();

        @Override
        public void show(String content, String reasoning) {
            events.add("show:" + content + "|" + reasoning);
        }

        @Override
        public void clear() {
            events.add("clear");
        }
    }

    // TestNG reuses one instance for every test method, so the fixtures are rebuilt per method.
    private AtomicLong now;
    private ManualScheduler scheduler;
    private RecordingSink sink;

    @BeforeMethod
    public void freshFixtures() {
        now = new AtomicLong(1_000L);
        scheduler = new ManualScheduler();
        sink = new RecordingSink();
    }

    private AiStreamCoalescer coalescer() {
        return new AiStreamCoalescer(sink, scheduler, now::get, 50L);
    }

    @Test
    public void lastSnapshotWins() {
        AiStreamCoalescer coalescer = coalescer();
        coalescer.onProgress("a", "");
        coalescer.onProgress("ab", "r");
        coalescer.onProgress("abc", "re");

        scheduler.runAll();

        assertThat(sink.events).containsExactly("show:abc|re");
    }

    @Test
    public void atMostOneDrainIsPending() {
        AiStreamCoalescer coalescer = coalescer();
        for (int i = 0; i < 100; i++) {
            coalescer.onProgress("x".repeat(i + 1), "");
        }
        coalescer.onRestart();
        coalescer.onComplete();

        assertThat(scheduler.drains).hasSize(1);
        assertThat(scheduler.delays).containsExactly(0L);
    }

    @Test
    public void laterDrainsKeepTheIntervalApart() {
        AiStreamCoalescer coalescer = coalescer();
        coalescer.onProgress("a", "");
        scheduler.runAll();
        now.addAndGet(20L);
        coalescer.onProgress("ab", "");

        assertThat(scheduler.delays).containsExactly(0L, 30L).inOrder();

        scheduler.runAll();
        now.addAndGet(200L);
        coalescer.onProgress("abc", "");
        assertThat(scheduler.delays.get(2)).isEqualTo(0L);
    }

    @Test
    public void restartClearsAndDropsTheOldSnapshot() {
        AiStreamCoalescer coalescer = coalescer();
        coalescer.onProgress("first try", "");
        scheduler.runAll();
        coalescer.onProgress("first try, longer", "");
        coalescer.onRestart();
        scheduler.runAll();
        coalescer.onProgress("second", "");
        scheduler.runAll();

        assertThat(sink.events).containsExactly("show:first try|", "clear", "show:second|").inOrder();
    }

    @Test
    public void restartThenNewSnapshotInOneDrainClearsBeforeShowing() {
        AiStreamCoalescer coalescer = coalescer();
        coalescer.onProgress("old", "");
        coalescer.onRestart();
        coalescer.onProgress("new", "");
        scheduler.runAll();

        assertThat(sink.events).containsExactly("clear", "show:new|").inOrder();
    }

    @Test
    public void completionFlushesWithoutWaitingForTheInterval() {
        AiStreamCoalescer coalescer = coalescer();
        coalescer.onProgress("a", "");
        scheduler.runAll();
        now.addAndGet(5L);
        coalescer.onComplete();
        coalescer.onProgress("final", "why");

        assertThat(scheduler.delays).containsExactly(0L, 0L).inOrder();
        scheduler.runAll();
        assertThat(sink.events).containsExactly("show:a|", "show:final|why").inOrder();
    }

    @Test
    public void closeTurnsQueuedAndLaterDrainsIntoNoOps() {
        AiStreamCoalescer coalescer = coalescer();
        coalescer.onProgress("shown late?", "");
        coalescer.close();
        scheduler.runAll();
        coalescer.onProgress("after close", "");
        coalescer.onRestart();
        coalescer.onComplete();
        scheduler.runAll();

        assertThat(sink.events).isEmpty();
        assertThat(coalescer.isClosed()).isTrue();
    }

    @Test
    public void nullSnapshotsBecomeEmptyStrings() {
        AiStreamCoalescer coalescer = coalescer();
        coalescer.onProgress(null, null);
        scheduler.runAll();

        assertThat(sink.events).containsExactly("show:|");
    }

    @Test
    public void concurrentProducersNeverQueueMoreThanOneDrain() throws Exception {
        List<Runnable> drains = java.util.Collections.synchronizedList(new ArrayList<>());
        AiStreamCoalescer coalescer =
            new AiStreamCoalescer(sink, (drain, delay) -> drains.add(drain), now::get, 50L);
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        coalescer.onProgress("t" + id + "-" + i, "");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(drains).hasSize(1);
        drains.get(0).run();
        assertThat(sink.events).hasSize(1);
        assertThat(sink.events.get(0)).endsWith("-499|");
    }

    @Test
    public void previewKeepsTheTailFromALineBoundary() {
        String longText = "head line\n" + "x".repeat(AiChatStreamingView.MAX_PREVIEW_CHARS) + "\ntail";
        String preview = AiChatStreamingView.previewText(longText);

        assertThat(preview).startsWith("…\n");
        assertThat(preview).endsWith("tail");
        assertThat(preview).doesNotContain("head line");
        assertThat(AiChatStreamingView.previewText("short")).isEqualTo("short");
        assertThat(AiChatStreamingView.previewText(null)).isEmpty();
    }
}
