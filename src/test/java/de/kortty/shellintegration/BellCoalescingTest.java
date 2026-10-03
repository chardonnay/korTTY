package de.kortty.shellintegration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.TerminalNotificationPolicy.Decision;
import de.kortty.shellintegration.TerminalNotificationPolicy.Kind;
import de.kortty.shellintegration.TerminalNotificationPolicy.PaneState;
import de.kortty.shellintegration.TerminalNotificationPolicy.Toggles;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.Test;

/**
 * A pane rings the bell on its emulator thread for every BEL, and printing a binary file rings
 * thousands. {@link BellCoalescer} must turn any number of them into one task on the UI thread per
 * quiet period, and the policy on top of it into one notification per 10 seconds per pane. The UI
 * thread is a plain queue here, run by the test.
 */
class BellCoalescingTest {

    private static final Toggles TOASTS_ON = new Toggles(true, true);
    private static final PaneState UNSEEN = new PaneState(false, false);

    /** Stands in for {@code Platform::runLater}: tasks wait until the test runs them. */
    private static final class UiQueue {
        final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

        void post(Runnable task) {
            tasks.add(task);
        }

        int runAll() {
            int ran = 0;
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
                ran++;
            }
            return ran;
        }
    }

    @Test
    void tenThousandBellsOnAWorkerThreadScheduleExactlyOneUiTask() throws Exception {
        UiQueue ui = new UiQueue();
        List<Long> deliveries = new ArrayList<>();
        BellCoalescer bells = new BellCoalescer(ui::post, deliveries::add);

        Thread emulator = new Thread(() -> {
            for (int i = 0; i < 10_000; i++) {
                bells.ring();
            }
        }, "emulator");
        emulator.start();
        emulator.join();

        assertWithMessage("one task waits on the UI thread, however many bells came in")
            .that(ui.tasks).hasSize(1);
        assertThat(bells.pending()).isEqualTo(10_000L);
        assertThat(ui.runAll()).isEqualTo(1);
        assertWithMessage("the one delivery hands over every bell").that(deliveries).containsExactly(10_000L);
        assertThat(bells.pending()).isEqualTo(0L);
    }

    @Test
    void bellsFromSeveralThreadsStillMakeOneTask() throws Exception {
        UiQueue ui = new UiQueue();
        AtomicLong delivered = new AtomicLong();
        BellCoalescer bells = new BellCoalescer(ui::post, delivered::addAndGet);
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
                for (int i = 0; i < 2_500; i++) {
                    bells.ring();
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(ui.tasks).hasSize(1);
        ui.runAll();
        assertThat(delivered.get()).isEqualTo(10_000L);
    }

    @Test
    void theFirstBellAfterADeliverySchedulesTheNextOne() {
        UiQueue ui = new UiQueue();
        List<Long> deliveries = new ArrayList<>();
        BellCoalescer bells = new BellCoalescer(ui::post, deliveries::add);

        bells.ring();
        bells.ring();
        ui.runAll();
        bells.ring();
        assertThat(ui.tasks).hasSize(1);
        bells.ring();
        bells.ring();
        assertThat(ui.tasks).hasSize(1);
        ui.runAll();

        assertWithMessage("one delivery per quiet period").that(deliveries).containsExactly(2L, 3L).inOrder();
        assertThat(ui.runAll()).isEqualTo(0);
    }

    @Test
    void aRefusedTaskDropsTheBellsAndALaterBellTriesAgain() {
        List<Runnable> accepted = new ArrayList<>();
        boolean[] refuse = {true};
        List<Long> deliveries = new ArrayList<>();
        BellCoalescer bells = new BellCoalescer(task -> {
            if (refuse[0]) {
                throw new IllegalStateException("Toolkit not running");
            }
            accepted.add(task);
        }, deliveries::add);

        bells.ring();
        assertWithMessage("the refusal does not reach the emulator thread").that(bells.pending()).isEqualTo(0L);

        refuse[0] = false;
        bells.ring();
        assertThat(accepted).hasSize(1);
        accepted.get(0).run();
        assertThat(deliveries).containsExactly(1L);
    }

    @Test
    void floodsOfBellsGiveOneNotificationPerTenSecondsPerPane() throws Exception {
        long[] now = {0};
        TerminalNotificationPolicy policy = new TerminalNotificationPolicy(() -> now[0]);
        Object paneA = new Object();
        Object paneB = new Object();
        UiQueue ui = new UiQueue();
        List<String> toasts = new ArrayList<>();
        List<String> badges = new ArrayList<>();
        BellCoalescer bellsA = new BellCoalescer(ui::post, count -> {
            Decision decision = policy.decide(Kind.BELL, paneA, UNSEEN, TOASTS_ON);
            if (decision.badge()) {
                badges.add("A@" + now[0]);
            }
            if (decision.toast()) {
                toasts.add("A@" + now[0]);
            }
        });
        BellCoalescer bellsB = new BellCoalescer(ui::post, count -> {
            if (policy.decide(Kind.BELL, paneB, UNSEEN, TOASTS_ON).toast()) {
                toasts.add("B@" + now[0]);
            }
        });

        // A burst of bells every second for 25 seconds in pane A; pane B rings once at 3 s.
        for (int second = 0; second < 25; second++) {
            now[0] = second * 1_000L;
            Thread emulator = new Thread(() -> {
                for (int i = 0; i < 10_000; i++) {
                    bellsA.ring();
                }
            });
            emulator.start();
            emulator.join();
            if (second == 3) {
                bellsB.ring();
            }
            ui.runAll();
        }

        assertWithMessage("one notification per 10 s for pane A, pane B on its own")
            .that(toasts).containsExactly("A@0", "B@3000", "A@10000", "A@20000").inOrder();
        assertWithMessage("every delivery marks the tab").that(badges).hasSize(25);
    }
}
