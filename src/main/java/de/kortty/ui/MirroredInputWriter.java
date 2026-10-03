package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes the keys that broadcast mode mirrors into the other panes, off the JavaFX thread.
 *
 * <p>A write to a pane's connector can block: an SSH channel whose window is full, or whose peer
 * stopped answering, holds the writing thread until the remote side catches up or the connection
 * drops. Broadcast mode used to write from the JavaFX key filter, so one stalled pane froze the
 * whole window on every keystroke. Each target connector now gets its own queue, drained in order
 * by one task at a time on a shared pool of daemon threads:
 *
 * <ul>
 *   <li>every target receives the keys in the order they were pressed;</li>
 *   <li>a stalled target holds up only its own queue, never the other targets or the caller;</li>
 *   <li>a queue is dropped as soon as it runs empty, so a closed pane leaves nothing behind.</li>
 * </ul>
 *
 * <p>Every write goes through the connector the pane holds, decorators included, inside a
 * {@link MirroredInput} scope. The pane the user types in is not written through here: its own
 * keys keep going through its terminal starter.
 *
 * <p>Thread-safe.
 */
public final class MirroredInputWriter {

    private static final Logger logger = LoggerFactory.getLogger(MirroredInputWriter.class);

    private final Executor executor;
    // Guarded by itself. Keyed by identity: a target is the connector instance a pane holds.
    private final Map<TtyConnector, TargetQueue> queues = new IdentityHashMap<>();

    /**
     * @param executor runs one drain task per target that has pending writes; it needs a free thread
     *     for every target that is draining at the same time, or a stalled target delays the others
     */
    MirroredInputWriter(@NotNull Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** The writer all split panes share. */
    public static @NotNull MirroredInputWriter shared() {
        return Shared.INSTANCE;
    }

    /** Queues {@code data} for {@code target}, behind the writes queued for it before. Never blocks. */
    public void write(@NotNull TtyConnector target, @NotNull String data) {
        Objects.requireNonNull(data, "data");
        enqueue(target, () -> target.write(data));
    }

    /**
     * Queues {@code bytes} for {@code target}, behind the writes queued for it before. Never blocks.
     * The bytes are copied, so the caller may reuse the array.
     */
    public void write(@NotNull TtyConnector target, byte @NotNull [] bytes) {
        byte[] copy = bytes.clone();
        enqueue(target, () -> target.write(copy));
    }

    /** The number of targets with writes still queued or in progress. For tests. */
    int busyTargetCount() {
        synchronized (queues) {
            return queues.size();
        }
    }

    private void enqueue(@NotNull TtyConnector target, @NotNull MirroredInput.Write write) {
        Objects.requireNonNull(target, "target");
        TargetQueue started;
        synchronized (queues) {
            TargetQueue queue = queues.get(target);
            if (queue != null) {
                queue.pending.add(write);
                return;
            }
            started = new TargetQueue(target);
            started.pending.add(write);
            queues.put(target, started);
        }
        try {
            executor.execute(started);
        } catch (RejectedExecutionException e) {
            synchronized (queues) {
                queues.remove(target);
            }
            logger.debug("Mirrored input dropped, the writer no longer runs: {}", e.getMessage());
        }
    }

    /** The pending writes of one target, drained by one task at a time. */
    private final class TargetQueue implements Runnable {
        private final TtyConnector target;
        // Guarded by MirroredInputWriter.this.queues.
        private final ArrayDeque<MirroredInput.Write> pending = new ArrayDeque<>();

        private TargetQueue(@NotNull TtyConnector target) {
            this.target = target;
        }

        @Override
        public void run() {
            while (true) {
                MirroredInput.Write next;
                synchronized (queues) {
                    next = pending.poll();
                    if (next == null) {
                        // Removed under the same lock enqueue() takes, so a write queued from now on
                        // starts a new drain task instead of landing in a queue nobody drains.
                        queues.remove(target);
                        return;
                    }
                }
                try {
                    MirroredInput.run(next);
                } catch (IOException e) {
                    logger.debug("Failed to broadcast to widget: {}", e.getMessage());
                } catch (RuntimeException e) {
                    logger.warn("Failed to broadcast to widget", e);
                } catch (Error e) {
                    // This task ends here. Drop its queue, so the next key starts a new drain task
                    // instead of piling up behind one that no longer runs.
                    synchronized (queues) {
                        queues.remove(target);
                    }
                    throw e;
                }
            }
        }
    }

    /** Holder, so the pool is only created once broadcast mode first writes. */
    private static final class Shared {
        private static final MirroredInputWriter INSTANCE = new MirroredInputWriter(newPool());

        private static ExecutorService newPool() {
            AtomicInteger counter = new AtomicInteger();
            // Cached: one thread per target that is draining at the same time, so a stalled target
            // never waits for a free thread or takes one away from another; idle threads end after 60 s.
            return Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "kortty-mirrored-input-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        }
    }
}
