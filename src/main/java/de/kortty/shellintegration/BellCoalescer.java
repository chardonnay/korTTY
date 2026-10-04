package de.kortty.shellintegration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Turns the bells of one terminal pane into at most one pending call on the UI thread.
 *
 * <p>A program rings the bell with a BEL character, and the emulator reports each one on the
 * pane's emulator thread. Printing a binary file can ring thousands in a second, so every bell
 * must stay cheap there and must not queue a UI task of its own. {@link #ring()} only counts; the
 * first bell after a delivery schedules the next delivery, and that delivery hands over every bell
 * counted until it runs. Between two deliveries there is therefore exactly one scheduled task,
 * however many bells come in.
 *
 * <p>FX-free: the scheduler is {@code Platform::runLater} in the application and a plain queue in
 * tests.
 */
public final class BellCoalescer {

    private final AtomicLong pending = new AtomicLong();

    private final Consumer<Runnable> scheduler;

    private final LongConsumer delivery;

    /**
     * @param scheduler runs a task later on the UI thread, such as {@code Platform::runLater}
     * @param delivery  receives the number of bells since the last delivery, on the UI thread;
     *                  never called with 0
     */
    public BellCoalescer(Consumer<Runnable> scheduler, LongConsumer delivery) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
    }

    /**
     * One bell. Any thread; it never blocks and never throws. When the scheduler refuses the task,
     * for example because the UI toolkit has shut down, the count is dropped so a later bell tries
     * again.
     */
    public void ring() {
        if (pending.getAndIncrement() != 0) {
            return;
        }
        try {
            scheduler.accept(this::deliver);
        } catch (RuntimeException e) {
            pending.set(0);
        }
    }

    /** The bells counted and not delivered yet. */
    public long pending() {
        return pending.get();
    }

    private void deliver() {
        long bells = pending.getAndSet(0);
        if (bells > 0) {
            delivery.accept(bells);
        }
    }
}
