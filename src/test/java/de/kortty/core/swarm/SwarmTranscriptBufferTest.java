package de.kortty.core.swarm;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static com.google.common.truth.Truth.assertThat;

class SwarmTranscriptBufferTest {

    @Test
    void appendBelowCapNeverTrims() {
        SwarmTranscriptBuffer buffer = new SwarmTranscriptBuffer(100, 50);
        assertThat(buffer.append("hello ")).isFalse();
        assertThat(buffer.append("world")).isFalse();
        assertThat(buffer.snapshot()).isEqualTo("hello world");
    }

    @Test
    void exceedingCapTrimsToTrimToAndSignalsOnce() {
        SwarmTranscriptBuffer buffer = new SwarmTranscriptBuffer(10, 6);
        assertThat(buffer.append("0123456789")).isFalse();
        assertThat(buffer.append("AB")).isTrue();
        assertThat(buffer.snapshot()).isEqualTo("…\n6789AB");
        assertThat(buffer.append("C")).isFalse();
        assertThat(buffer.snapshot()).isEqualTo("…\n6789ABC");
    }

    @Test
    void nullAndEmptyChunksAreIgnored() {
        SwarmTranscriptBuffer buffer = new SwarmTranscriptBuffer(10, 5);
        assertThat(buffer.append(null)).isFalse();
        assertThat(buffer.append("")).isFalse();
        assertThat(buffer.snapshot()).isEmpty();
    }

    @Test
    void rejectsInvalidBounds() {
        List<Runnable> invalid = List.of(
            () -> new SwarmTranscriptBuffer(0, 1),
            () -> new SwarmTranscriptBuffer(10, 0),
            () -> new SwarmTranscriptBuffer(10, 11));
        for (Runnable ctor : invalid) {
            try {
                ctor.run();
                throw new AssertionError("expected IllegalArgumentException");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    void concurrentAppendsNeverCorruptTheBuffer() throws Exception {
        SwarmTranscriptBuffer buffer = new SwarmTranscriptBuffer(5_000, 2_500);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < 500; i++) {
                    buffer.append("chunk-");
                }
            });
            thread.start();
            threads.add(thread);
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(5_000);
        }
        String snapshot = buffer.snapshot();
        assertThat(snapshot.length()).isAtMost(5_000 + "…\n".length() + "chunk-".length());
        assertThat(snapshot).contains("chunk-");
    }
}
