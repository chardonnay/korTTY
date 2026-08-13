package de.kortty.ui;

import de.kortty.core.SessionJournalLogEntry;
import de.kortty.core.SessionJournalLogReader;
import de.kortty.core.SessionJournalSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Streams one journal session into a UI consumer: an initial backfill of the newest persisted
 * entries, then live entries pushed from the session's writer thread, coalesced into batches on the
 * UI executor. Deliberately free of JavaFX types so it is unit-testable with a direct executor.
 *
 * <p>Deduplication is by seq <b>value</b> (a set over the backfilled entries), not a high-water
 * mark: rotation and capture-stop notes are assigned sequence numbers above entries still waiting
 * in the writer queue, so sink delivery is not strictly monotonic (see
 * {@code SessionJournalSession#notifyLiveSinks}).</p>
 */
public class SessionJournalLiveFeed {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalLiveFeed.class);

    /** One shared daemon scheduler paces the coalesced flushes of all live feeds. */
    private static final ScheduledExecutorService FLUSH_SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "JournalLivePanel-Flush");
            t.setDaemon(true);
            return t;
        });

    public static final int DEFAULT_MAX_ENTRIES = 5000;
    public static final long DEFAULT_COALESCE_MILLIS = 100;

    private final SessionJournalSession session;
    private final int maxEntries;
    private final long coalesceMillis;
    private final Executor uiExecutor;
    /** Receives the initial backfill (replaces any previous content). Runs on the UI executor. */
    private final Consumer<List<SessionJournalLogEntry>> onBackfill;
    /** Receives each coalesced batch of live entries. Runs on the UI executor. */
    private final Consumer<List<SessionJournalLogEntry>> onLiveBatch;

    private final ConcurrentLinkedQueue<SessionJournalLogEntry> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final Consumer<SessionJournalLogEntry> sink = this::onEntry;
    private volatile boolean started;
    private volatile boolean stopped;
    private volatile boolean backfillDone;
    private Set<Long> backfillSeqs = Set.of();

    public SessionJournalLiveFeed(
            SessionJournalSession session,
            int maxEntries,
            long coalesceMillis,
            Executor uiExecutor,
            Consumer<List<SessionJournalLogEntry>> onBackfill,
            Consumer<List<SessionJournalLogEntry>> onLiveBatch) {
        this.session = session;
        this.maxEntries = Math.max(1, maxEntries);
        this.coalesceMillis = Math.max(0, coalesceMillis);
        this.uiExecutor = uiExecutor;
        this.onBackfill = onBackfill;
        this.onLiveBatch = onLiveBatch;
    }

    public SessionJournalSession getSession() {
        return session;
    }

    /** Registers the sink first, then backfills; the seq set reconciles the overlap. Idempotent. */
    public synchronized void start() {
        if (started || stopped) {
            return;
        }
        started = true;
        session.addLiveEntrySink(sink);
        Thread backfillThread = new Thread(this::runBackfill, "JournalLivePanel-Backfill");
        backfillThread.setDaemon(true);
        backfillThread.start();
    }

    /** Detaches from the session and drops undelivered entries. Idempotent, callable from any thread. */
    public synchronized void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        session.removeLiveEntrySink(sink);
        pending.clear();
    }

    public boolean isStopped() {
        return stopped;
    }

    private void runBackfill() {
        List<SessionJournalLogEntry> result = List.of();
        try {
            long last = session.getLastSequence();
            if (last > 0) {
                long from = Math.max(1, last - maxEntries + 1);
                result = SessionJournalLogReader.readRange(session.getDirectory(), from, last);
            }
        } catch (IOException e) {
            logger.warn("Live journal backfill failed for {}: {}",
                session.getDirectory().getFileName(), e.getMessage());
        }
        List<SessionJournalLogEntry> backfill = result;
        uiExecutor.execute(() -> {
            if (stopped) {
                return;
            }
            Set<Long> seqs = new HashSet<>();
            for (SessionJournalLogEntry entry : backfill) {
                seqs.add(entry.seq());
            }
            backfillSeqs = seqs;
            onBackfill.accept(backfill);
            backfillDone = true;
            flushPending();
        });
    }

    private void onEntry(SessionJournalLogEntry entry) {
        if (stopped) {
            return;
        }
        pending.add(entry);
        if (backfillDone) {
            scheduleFlush();
        }
        // else: the backfill's completion task drains the queue.
    }

    private void scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) {
            return;
        }
        if (coalesceMillis == 0) {
            uiExecutor.execute(this::flushNow);
        } else {
            FLUSH_SCHEDULER.schedule(() -> uiExecutor.execute(this::flushNow), coalesceMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void flushNow() {
        flushScheduled.set(false);
        flushPending();
    }

    private void flushPending() {
        if (stopped) {
            return;
        }
        List<SessionJournalLogEntry> batch = new ArrayList<>();
        SessionJournalLogEntry entry;
        while ((entry = pending.poll()) != null) {
            if (!backfillSeqs.contains(entry.seq())) {
                batch.add(entry);
            }
        }
        if (!batch.isEmpty()) {
            onLiveBatch.accept(batch);
        }
    }
}
