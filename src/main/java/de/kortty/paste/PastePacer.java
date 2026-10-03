package de.kortty.paste;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends a paste line by line with a pause after every line, for devices behind SSH that lose input
 * arriving faster than they read it: switches, routers, console servers and serial consoles.
 *
 * <ul>
 *   <li>The payload from {@link PasteSanitizer#encode} is split after every CR ({@link #lines}). A
 *       bracketed paste keeps its start marker on the first line and its end marker on the last, so
 *       the program still receives one block.</li>
 *   <li>The first line goes out at once, every further line {@code lineDelayMs} after the one
 *       before it. A paste of a single line, or a delay of 0, is sent at once in one piece.</li>
 *   <li>While a pane is pacing a paste ({@link #isPacing}), korTTY holds the pane's keyboard input,
 *       so no keystroke lands between two lines (Esc {@link #cancel cancels} instead), and broadcast
 *       mode does not mirror keys into the pane.</li>
 *   <li>A run ends early when it is cancelled, when the pane can no longer receive text, or when the
 *       pane's session was replaced; the remaining lines are dropped. A cancelled bracketed paste is
 *       closed with its end marker, so the program leaves paste mode and the lines it already has
 *       stay unexecuted in its input line.</li>
 * </ul>
 *
 * <p>Confined to the JavaFX thread: the {@link Scheduler} must run its tasks there. Only sizes and
 * counts are logged, at DEBUG; never the text.
 */
public final class PastePacer {

    private static final Logger logger = LoggerFactory.getLogger(PastePacer.class);

    /** The longest pause between two lines a user can choose, in milliseconds. */
    public static final int MAX_LINE_DELAY_MS = 1000;

    /** Runs a task once after a delay, on the thread the pacer is confined to. */
    @FunctionalInterface
    public interface Scheduler {

        /**
         * Runs {@code task} once after {@code delayMs} milliseconds.
         *
         * @return a handle whose {@link Cancellable#cancel()} keeps the task from running
         */
        Cancellable schedule(Runnable task, long delayMs);

        /**
         * Waits on one shared daemon timer thread and then hands each task to {@code thread}, for
         * example {@code Platform::runLater}. A cancelled task never runs, even when its wait is
         * already over.
         */
        static Scheduler sharedTimer(Executor thread) {
            return timer(SharedTimer.INSTANCE, thread);
        }

        /**
         * Waits on {@code timer} and then hands each task to {@code thread}. A cancelled task never
         * runs, even when its wait is already over.
         */
        static Scheduler timer(ScheduledExecutorService timer, Executor thread) {
            Objects.requireNonNull(timer, "timer");
            Objects.requireNonNull(thread, "thread");
            return (task, delayMs) -> {
                AtomicBoolean cancelled = new AtomicBoolean();
                Future<?> wait = timer.schedule(() -> thread.execute(() -> {
                    if (!cancelled.get()) {
                        task.run();
                    }
                }), Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
                return () -> {
                    cancelled.set(true);
                    wait.cancel(false);
                };
            };
        }
    }

    /** Keeps a scheduled task from running. */
    @FunctionalInterface
    public interface Cancellable {

        /** Keeps the task from running; does nothing once it ran or was cancelled. */
        void cancel();
    }

    /** How a paced paste ended. */
    public enum Outcome {
        /** Every line was sent. */
        COMPLETED,
        /** {@link #cancel} stopped it, for example the user pressed Esc or the pane closed. */
        CANCELLED,
        /** The pane lost its session, the session was replaced, or a line could not be sent. */
        INTERRUPTED
    }

    /** Hears about the paced pastes, on the pacer's thread. */
    public interface Listener {

        /** Hears nothing. */
        Listener NONE = new Listener() {
            @Override
            public void progressed(Object key, int sent, int total) {
            }

            @Override
            public void ended(Object key, Outcome outcome, int sent, int total) {
            }
        };

        /**
         * A line went out.
         *
         * @param key the pane's {@link PasteTarget#key()}
         * @param sent how many lines are out, from 1 for the first line
         * @param total how many lines the paste has
         */
        void progressed(Object key, int sent, int total);

        /**
         * The paced paste is over and the pane takes input again.
         *
         * @param key the pane's {@link PasteTarget#key()}
         * @param outcome why it ended
         * @param sent how many lines went out
         * @param total how many lines the paste has
         */
        void ended(Object key, Outcome outcome, int sent, int total);
    }

    private final Scheduler scheduler;

    private final Listener listener;

    /** The panes that are pacing a paste, by key, compared by reference. */
    private final Map<Object, Run> runs = new IdentityHashMap<>();

    /** @param scheduler runs the next line after the pause, on the pacer's thread */
    public PastePacer(Scheduler scheduler) {
        this(scheduler, Listener.NONE);
    }

    /**
     * @param scheduler runs the next line after the pause, on the pacer's thread
     * @param listener hears about progress and the end of every paced paste
     */
    public PastePacer(Scheduler scheduler, Listener listener) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.listener = listener != null ? listener : Listener.NONE;
    }

    /** {@code delayMs} limited to {@code 0..}{@link #MAX_LINE_DELAY_MS}; a negative value means off. */
    public static int clampLineDelayMs(int delayMs) {
        return Math.max(0, Math.min(MAX_LINE_DELAY_MS, delayMs));
    }

    /**
     * The lines a paced paste sends: {@code payload} split after every CR, each line with its CR. A
     * bracketed-paste end marker after the last CR goes with the last line rather than alone, so the
     * end marker always travels with text. Joined again they give {@code payload}.
     *
     * @param payload the text from {@link PasteSanitizer#encode}, line breaks already CR
     * @return the lines, none of them empty; no line for null or empty text
     */
    public static List<String> lines(String payload) {
        List<String> lines = new ArrayList<>();
        if (payload == null || payload.isEmpty()) {
            return lines;
        }
        int start = 0;
        for (int i = 0; i < payload.length(); i++) {
            if (payload.charAt(i) == '\r') {
                lines.add(payload.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < payload.length()) {
            String rest = payload.substring(start);
            if (rest.equals(PasteSanitizer.END_MARKER) && !lines.isEmpty()) {
                lines.set(lines.size() - 1, lines.get(lines.size() - 1) + rest);
            } else {
                lines.add(rest);
            }
        }
        return lines;
    }

    /**
     * Sends {@code payload} to {@code target}: line by line with {@code lineDelayMs} between the
     * lines, or at once when the delay is 0 or the payload is a single line.
     *
     * @param target the pane
     * @param payload the text from {@link PasteSanitizer#encode}
     * @param lineDelayMs the pause after each line, clamped to {@code 0..}{@link #MAX_LINE_DELAY_MS}
     * @return false when nothing was sent because the pane is still pacing an earlier paste
     * @throws RuntimeException when sending at once fails; a failing line of a paced paste ends the
     *     run instead
     */
    public boolean send(PasteTarget target, String payload, int lineDelayMs) {
        Objects.requireNonNull(target, "target");
        if (payload == null || payload.isEmpty()) {
            return true;
        }
        Object key = target.key();
        if (runs.containsKey(key)) {
            logger.debug("Paste dropped: the pane is still pacing a paste ({} chars)", payload.length());
            return false;
        }
        int delayMs = clampLineDelayMs(lineDelayMs);
        List<String> lines = delayMs > 0 ? lines(payload) : List.of(payload);
        if (lines.size() < 2) {
            target.send(payload);
            return true;
        }
        Run run = new Run(key, target, target.session(), lines, delayMs,
            payload.startsWith(PasteSanitizer.START_MARKER) && payload.endsWith(PasteSanitizer.END_MARKER));
        runs.put(key, run);
        logger.debug("Pacing a paste: {} lines, {} ms apart ({} chars)", lines.size(), delayMs, payload.length());
        sendNext(run);
        return true;
    }

    /** Whether the pane with this key is pacing a paste; its keyboard input is held meanwhile. */
    public boolean isPacing(Object key) {
        return key != null && runs.containsKey(key);
    }

    /** Whether any pane is pacing a paste. */
    public boolean isPacingAny() {
        return !runs.isEmpty();
    }

    /**
     * Stops the pane's paced paste: no further line is sent, and a bracketed paste gets its end
     * marker if the pane still runs the session the paste started in.
     *
     * @return whether the pane was pacing a paste
     */
    public boolean cancel(Object key) {
        Run run = key != null ? runs.get(key) : null;
        if (run == null) {
            return false;
        }
        finish(run, Outcome.CANCELLED);
        return true;
    }

    /** Stops every paced paste, as {@link #cancel} does for one pane. */
    public void cancelAll() {
        for (Object key : new ArrayList<>(runs.keySet())) {
            cancel(key);
        }
    }

    private void sendNext(Run run) {
        run.pending = null;
        if (runs.get(run.key) != run) {
            return;
        }
        if (!run.sessionIsCurrent()) {
            logger.debug("Paced paste stopped: the pane's session ended or changed ({} of {} lines sent)",
                run.sent, run.lines.size());
            finish(run, Outcome.INTERRUPTED);
            return;
        }
        try {
            run.target.send(run.lines.get(run.sent));
        } catch (RuntimeException e) {
            logger.warn("Paced paste stopped: a line could not be sent ({} of {} lines sent): {}",
                run.sent, run.lines.size(), e.toString());
            finish(run, Outcome.INTERRUPTED);
            return;
        }
        run.sent++;
        try {
            listener.progressed(run.key, run.sent, run.lines.size());
        } catch (RuntimeException e) {
            logger.debug("Paste pacing listener failed: {}", e.toString());
        }
        if (runs.get(run.key) != run) {
            return;
        }
        if (run.sent == run.lines.size()) {
            logger.debug("Paced paste sent: {} lines", run.sent);
            finish(run, Outcome.COMPLETED);
            return;
        }
        try {
            run.pending = scheduler.schedule(() -> sendNext(run), run.delayMs);
        } catch (RuntimeException e) {
            logger.warn("Paced paste stopped: the next line could not be scheduled ({} of {} lines sent): {}",
                run.sent, run.lines.size(), e.toString());
            finish(run, Outcome.INTERRUPTED);
        }
    }

    private void finish(Run run, Outcome outcome) {
        if (!runs.remove(run.key, run)) {
            return;
        }
        Cancellable pending = run.pending;
        run.pending = null;
        if (pending != null) {
            try {
                pending.cancel();
            } catch (RuntimeException e) {
                logger.debug("A scheduled paste line could not be cancelled: {}", e.toString());
            }
        }
        if (outcome != Outcome.COMPLETED) {
            logger.debug("Paced paste {}: {} of {} lines sent", outcome, run.sent, run.lines.size());
            closeBracketedPaste(run);
        }
        try {
            listener.ended(run.key, outcome, run.sent, run.lines.size());
        } catch (RuntimeException e) {
            logger.debug("Paste pacing listener failed: {}", e.toString());
        }
    }

    /**
     * Ends a bracketed paste that stopped half-way with its end marker, so the program leaves paste
     * mode instead of taking the user's next keys as pasted text. Only into the session the paste
     * started in; a replaced or lost session gets nothing.
     */
    private static void closeBracketedPaste(Run run) {
        if (!run.bracketed || run.sent == 0 || run.sent >= run.lines.size() || !run.sessionIsCurrent()) {
            return;
        }
        try {
            run.target.send(PasteSanitizer.END_MARKER);
        } catch (RuntimeException e) {
            logger.debug("The end marker of a stopped paste could not be sent: {}", e.toString());
        }
    }

    /** One pane's paced paste. */
    private static final class Run {
        final Object key;
        final PasteTarget target;
        final Object session;
        final List<String> lines;
        final int delayMs;
        final boolean bracketed;
        int sent;
        Cancellable pending;

        Run(Object key, PasteTarget target, Object session, List<String> lines, int delayMs, boolean bracketed) {
            this.key = key;
            this.target = target;
            this.session = session;
            this.lines = List.copyOf(lines);
            this.delayMs = delayMs;
            this.bracketed = bracketed;
        }

        boolean sessionIsCurrent() {
            return target.canReceive() && target.session() == session;
        }
    }

    /** The timer thread behind {@link Scheduler#sharedTimer}, started on first use. */
    private static final class SharedTimer {
        static final ScheduledExecutorService INSTANCE = create();

        private static ScheduledExecutorService create() {
            ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "kortty-paste-pacer");
                thread.setDaemon(true);
                return thread;
            });
            timer.setRemoveOnCancelPolicy(true);
            return timer;
        }
    }
}
