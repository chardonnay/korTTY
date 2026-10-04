package de.kortty.shellintegration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.Test;

/**
 * What a pane's output clock hands the activity monitor: the first and the last moment output arrived
 * since the monitor's previous look, and nothing new when nothing arrived.
 */
class PaneOutputClockTest {

    @Test
    void aPaneThatNeverReceivedOutputHasNothingToReport() {
        PaneOutputClock clock = new PaneOutputClock(() -> 5L);

        PaneOutputClock.Sample sample = clock.take();

        assertThat(sample.newOutput()).isFalse();
        assertThat(sample.firstNanos()).isEqualTo(PaneOutputClock.NEVER);
        assertThat(sample.lastNanos()).isEqualTo(PaneOutputClock.NEVER);
        assertThat(clock.lastOutputNanos()).isEqualTo(PaneOutputClock.NEVER);
    }

    @Test
    void aTakeReportsTheFirstAndTheLastOutputSinceThePreviousOne() {
        AtomicLong now = new AtomicLong(100);
        PaneOutputClock clock = new PaneOutputClock(now::get);

        clock.outputArrived();
        now.set(150);
        clock.outputArrived();
        now.set(180);
        clock.outputArrived();

        PaneOutputClock.Sample sample = clock.take();
        assertThat(sample.newOutput()).isTrue();
        assertThat(sample.firstNanos()).isEqualTo(100);
        assertThat(sample.lastNanos()).isEqualTo(180);

        PaneOutputClock.Sample quiet = clock.take();
        assertWithMessage("nothing arrived since").that(quiet.newOutput()).isFalse();
        assertWithMessage("the last output is still known").that(quiet.lastNanos()).isEqualTo(180);

        now.set(400);
        clock.outputArrived();
        PaneOutputClock.Sample next = clock.take();
        assertThat(next.firstNanos()).isEqualTo(400);
        assertThat(next.lastNanos()).isEqualTo(400);
    }

    @Test
    void outputReportedFromAnotherThreadIsNeverLost() throws Exception {
        PaneOutputClock clock = new PaneOutputClock();
        int chunks = 50_000;
        CountDownLatch done = new CountDownLatch(1);
        Thread emulator = new Thread(() -> {
            for (int i = 0; i < chunks; i++) {
                clock.outputArrived();
            }
            done.countDown();
        });
        emulator.start();
        long last = PaneOutputClock.NEVER;
        int samplesWithOutput = 0;
        while (done.getCount() > 0) {
            PaneOutputClock.Sample sample = clock.take();
            if (sample.newOutput()) {
                samplesWithOutput++;
                assertThat(sample.lastNanos() - sample.firstNanos()).isAtLeast(0L);
                last = sample.lastNanos();
            }
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        PaneOutputClock.Sample rest = clock.take();
        if (rest.newOutput()) {
            samplesWithOutput++;
            last = rest.lastNanos();
        }
        assertThat(samplesWithOutput).isAtLeast(1);
        assertWithMessage("the latest output always reaches a sample")
            .that(last).isEqualTo(clock.lastOutputNanos());
        assertThat(clock.take().newOutput()).isFalse();
    }
}
