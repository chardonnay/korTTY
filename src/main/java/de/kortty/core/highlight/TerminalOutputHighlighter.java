package de.kortty.core.highlight;

import com.sithtermfx.core.HyperlinkStyle;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.model.CharBuffer;
import com.sithtermfx.core.model.LinesBuffer;
import com.sithtermfx.core.model.TerminalLine;
import com.sithtermfx.core.model.TerminalModelListener;
import com.sithtermfx.core.model.TerminalTextBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Restyles one terminal pane's output with a {@link CompiledHighlightSet}: every character a rule
 * claims gets a {@link HighlightTextStyle} stored in its cell, and every cell no rule claims any more
 * gets the program's own style back.
 *
 * <p><b>Cost while off.</b> {@link #markDirty()} is the buffer's model listener and runs on the
 * emulator thread for every write. While no set is active and nothing is left to restore it returns
 * after two volatile reads, so a pane without highlighting pays nothing measurable.
 *
 * <p><b>Passes.</b> Otherwise the first change of a burst schedules one pass
 * {@value #COALESCE_MILLIS} ms later on the shared highlighter thread. A pass has three phases:
 * <ol>
 *   <li><em>Snapshot</em>, under the buffer lock: pick the rows to evaluate and copy their text.</li>
 *   <li><em>Match</em>, without the lock: {@link HighlightMatcher} over each logical line (soft-wrapped
 *       rows joined, see {@link LogicalLineProjection}), within {@value #PASS_MATCH_BUDGET_NANOS} ns per
 *       pass and the matcher's budget per rule and line.</li>
 *   <li><em>Apply</em>, under the lock again in chunks of {@value #APPLY_CHUNK_ROWS} rows: a row whose
 *       text changed since the snapshot is skipped and evaluated again later; otherwise only the runs
 *       whose style differs are rewritten, in place with {@link TerminalLine#writeString}, so the line
 *       objects (and every tracker keyed by them) stay the same.</li>
 * </ol>
 * Cells drawn with a {@link HyperlinkStyle} are never touched.
 *
 * <p><b>What a pass looks at.</b>
 * <ul>
 *   <li><em>Screen</em>: every row is fingerprinted (its characters and the identity of every cell's
 *       style) and only rows that changed since the previous pass are evaluated. The fingerprints are
 *       keyed by line identity and rebuilt every pass, so they never hold more than one screen.</li>
 *   <li><em>History</em>: the emulator moves lines into the scrollback without copying them, so the
 *       highlighter anchors on the newest history line it has accounted for, as
 *       {@code ScrollbackTrimTracker} does, and finds the lines after it by scanning back from the end.
 *       A line that was already evaluated on the screen is recognised by its fingerprint; the rest is
 *       backlog, evaluated newest first with at most {@value #MAX_ROWS_PER_PASS} rows per pass. While
 *       backlog remains the pass reschedules itself every {@value #BACKLOG_INTERVAL_MILLIS} ms, so a
 *       flood that has stopped still gets finished. Backlog lines more than
 *       {@value #FLOOD_HORIZON_ROWS} rows above the bottom are skipped: in a flood nobody reads them,
 *       and catching up on them would only compete with the output still coming in.</li>
 *   <li><em>Width reflow</em>: a reflow rebuilds every line but carries each cell's style, and the
 *       logical text does not change, so nothing in the history is matched again — the highlighter
 *       only re-anchors (backlog not yet evaluated at that moment stays unhighlighted). The same
 *       happens after the scrollback is cleared.</li>
 *   <li><em>Alternate screen</em> (vim, less, htop): skipped unless allowed, because such programs
 *       redraw constantly; while it is active the history is left alone and the anchor kept for the
 *       return.</li>
 * </ul>
 *
 * <p><b>Switching sets.</b> {@link #setRuleSet} starts a new generation: the screen is evaluated at
 * once and the whole history is swept newest first, which also restores what the previous set
 * colored. The history sweep waits while the Find bar shows results, because SithTermFX keys its
 * find hits by the identity of the text buffers a restyle replaces.
 *
 * <p><b>Rules that are too slow.</b> A rule that runs out of its budget on
 * {@value #OVERRUNS_BEFORE_DISABLE} lines is switched off for this pane until the set changes, and
 * logged once — by rule id, never by pattern or matched text.
 *
 * <p>Thread-safety: {@link #markDirty()}, {@link #setRuleSet} and {@link #close()} may be called from
 * any thread; passes are serialised.
 */
public final class TerminalOutputHighlighter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(TerminalOutputHighlighter.class);

    /** Delay between the first change of a burst and the pass that absorbs it. */
    public static final long COALESCE_MILLIS = 50L;

    /** Interval of the follow-up passes while backlog remains (at most 20 per second). */
    public static final long BACKLOG_INTERVAL_MILLIS = 50L;

    /** How often a history sweep that waits for the Find bar checks again. */
    public static final long FIND_POLL_MILLIS = 500L;

    /** Matching time one pass may spend over all its lines. */
    public static final long PASS_MATCH_BUDGET_NANOS = 12_000_000L;

    /** Most rows one pass evaluates. */
    public static final int MAX_ROWS_PER_PASS = 2_000;

    /** Most rows restyled under one acquisition of the buffer lock. */
    public static final int APPLY_CHUNK_ROWS = 200;

    /** Backlog lines further above the bottom than this are not evaluated. */
    public static final int FLOOD_HORIZON_ROWS = 10_000;

    /** Budget overruns after which a rule is switched off for the pane. */
    public static final int OVERRUNS_BEFORE_DISABLE = 3;

    /**
     * Where passes run. The application backs it with the one {@code kortty-highlighter} thread and a
     * duty cap shared by every pane; tests run the queued passes by hand.
     */
    public interface PassScheduler {

        /**
         * Runs {@code task} once after {@code delayMillis}.
         *
         * @throws RejectedExecutionException when the scheduler is shut down
         */
        void schedule(Runnable task, long delayMillis);

        /** Nanoseconds the next scheduled pass must still wait (the duty cap across panes); 0 = now. */
        default long dutyDelayNanos(long nowNanos) {
            return 0L;
        }

        /** Reports how long a scheduled pass kept the thread busy. */
        default void passFinished(long startNanos, long endNanos) {
        }
    }

    /**
     * What one pass did.
     *
     * @param rowsEvaluated rows whose logical line was matched (or checked for restoring)
     * @param rowsWritten rows in which at least one cell was restyled
     * @param followUpMillis delay of the pass this one asks for, or {@code -1} when nothing is left
     */
    public record PassStats(int rowsEvaluated, int rowsWritten, long followUpMillis) {

        static final PassStats IDLE = new PassStats(0, 0, -1L);

        /** True when the pass changed what the pane shows. */
        public boolean restyled() {
            return rowsWritten > 0;
        }

        /** True when work is left for a later pass. */
        public boolean backlog() {
            return followUpMillis >= 0L;
        }
    }

    /** The active set and the generation it started; swapped as one value so a pass never mixes two. */
    private record Selection(CompiledHighlightSet set, int generation) {
    }

    /** Consecutive history lines still to evaluate, located by the identity of the newest one. */
    private static final class Range {

        private TerminalLine newest;

        private int newestIndex;

        private int length;

        /** True for a generation sweep (waits for Find), false for new output. */
        private final boolean sweep;

        private Range(TerminalLine newest, int newestIndex, int length, boolean sweep) {
            this.newest = newest;
            this.newestIndex = newestIndex;
            this.length = length;
            this.sweep = sweep;
        }
    }

    /** History plus screen as one index space: history rows first, oldest at 0. */
    private record View(LinesBuffer history, int historyRows, LinesBuffer screen, int screenRows) {

        static View of(TerminalTextBuffer buffer, boolean alternate) {
            LinesBuffer screen = buffer.getScreenBuffer();
            int screenRows = Math.min(screen.getLineCount(), buffer.getHeight());
            if (alternate) {
                return new View(null, 0, screen, screenRows);
            }
            LinesBuffer history = buffer.getHistoryBuffer();
            return new View(history, history.getLineCount(), screen, screenRows);
        }

        int total() {
            return historyRows + screenRows;
        }

        /** Only ever called for an existing row, so the vendor never pads the buffer with empty lines. */
        TerminalLine line(int index) {
            return index < historyRows ? history.getLine(index) : screen.getLine(index - historyRows);
        }
    }

    /** One logical line to evaluate. */
    private static final class Work {

        private final TerminalLine[] lines;

        private final String[] texts;

        private final boolean[] onScreen;

        private Range range;

        private TerminalLine rangeNext;

        private int rangeNextIndex;

        private int rangeNextLength;

        private LogicalLineProjection projection;

        private int[] owners;

        private Work(int rows) {
            lines = new TerminalLine[rows];
            texts = new String[rows];
            onScreen = new boolean[rows];
        }
    }

    private final TerminalTextBuffer buffer;

    private final Runnable repaint;

    private final Runnable onRestyled;

    private final BooleanSupplier findActive;

    private final BooleanSupplier alternateScreenAllowed;

    private final PassScheduler scheduler;

    private final TerminalModelListener modelListener = this::markDirty;

    private final AtomicReference<Selection> selection;

    private final AtomicBoolean scheduled = new AtomicBoolean();

    private final AtomicBoolean closed = new AtomicBoolean();

    private final AtomicBoolean reflowSeen = new AtomicBoolean();

    /** Width last seen by {@link #markDirty()}; written on the emulator thread under the buffer lock. */
    private volatile int observedWidth;

    /** True while cells may still carry a highlight; false only after a completed restore. */
    private volatile boolean everStyled;

    // ---- Pass state: guarded by passLock. ----

    private final Object passLock = new Object();

    private int passGeneration = Integer.MIN_VALUE;

    private boolean historyResetPending;

    private IdentityHashMap<TerminalLine, Long> screenPrints = new IdentityHashMap<>();

    private final List<Range> ranges = new ArrayList<>();

    private TerminalLine anchorLine;

    private int anchorIndex = -1;

    private int historyWidth = -1;

    private final HighlightTextStyle.Cache styles = new HighlightTextStyle.Cache();

    private final BitSet disabledRules = new BitSet();

    private int[] overruns = new int[0];

    private long rowsEvaluatedTotal;

    private boolean passFailureLogged;

    /**
     * Creates the highlighter and registers it as a model listener of {@code buffer}.
     *
     * @param initial the set to start with ({@code null} = none)
     * @param repaint asks the pane to redraw; called from the highlighter thread (TerminalPanel's
     *                repaint only sets a flag)
     * @param onRestyled called after a pass changed what the pane shows, outside the buffer lock (the
     *                   pane takes a recording snapshot when a recording targets it)
     * @param findActive whether the pane's Find bar currently shows results
     * @param alternateScreenAllowed whether full-screen programs are highlighted too; re-read often
     */
    public TerminalOutputHighlighter(TerminalTextBuffer buffer, CompiledHighlightSet initial, Runnable repaint,
                                     Runnable onRestyled, BooleanSupplier findActive,
                                     BooleanSupplier alternateScreenAllowed, PassScheduler scheduler) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.repaint = Objects.requireNonNull(repaint, "repaint");
        this.onRestyled = Objects.requireNonNull(onRestyled, "onRestyled");
        this.findActive = Objects.requireNonNull(findActive, "findActive");
        this.alternateScreenAllowed = Objects.requireNonNull(alternateScreenAllowed, "alternateScreenAllowed");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        CompiledHighlightSet start = initial != null ? initial : CompiledHighlightSet.NONE;
        this.selection = new AtomicReference<>(new Selection(start, 1));
        this.observedWidth = buffer.getWidth();
        buffer.addModelListener(modelListener);
        if (!start.isEmpty()) {
            requestPass(0L);
        }
    }

    /** The set this pane currently shows ({@link CompiledHighlightSet#NONE} when off). */
    public CompiledHighlightSet ruleSet() {
        return selection.get().set();
    }

    /**
     * Switches the pane to {@code next} ({@code null} = none). Starts a new generation unless the set
     * is the same instance; the screen follows in the next pass, the history over the passes after it.
     */
    public void setRuleSet(CompiledHighlightSet next) {
        CompiledHighlightSet target = next != null ? next : CompiledHighlightSet.NONE;
        while (true) {
            Selection previous = selection.get();
            if (previous.set() == target) {
                return;
            }
            if (selection.compareAndSet(previous, new Selection(target, previous.generation() + 1))) {
                break;
            }
        }
        observedWidth = buffer.getWidth();
        if (closed.get() || (target.isEmpty() && !everStyled)) {
            return;
        }
        requestPass(0L);
    }

    /**
     * The model listener: O(1), allocation-free and lock-free, because the emulator calls it for every
     * buffer change while holding the buffer lock. Schedules at most one pass per burst.
     */
    public void markDirty() {
        if (closed.get() || (selection.get().set().isEmpty() && !everStyled)) {
            return;
        }
        int width = buffer.getWidth();
        if (width != observedWidth) {
            observedWidth = width;
            reflowSeen.set(true);
        }
        if (buffer.isUsingAlternateBuffer() && !alternateScreenAllowed.getAsBoolean()) {
            return;
        }
        requestPass(COALESCE_MILLIS);
    }

    /**
     * Runs one pass on the calling thread, without the duty cap and without scheduling a follow-up.
     * For tests and for callers that need the result at once.
     */
    public PassStats runPassNow() {
        return runPass();
    }

    /** Rows evaluated over the highlighter's life (a test probe for "nothing was rescanned"). */
    public long rowsEvaluatedTotal() {
        synchronized (passLock) {
            return rowsEvaluatedTotal;
        }
    }

    /** Rules switched off for this pane after repeated budget overruns (a test probe). */
    int disabledRuleCount() {
        synchronized (passLock) {
            return disabledRules.cardinality();
        }
    }

    /** Whether a pass is queued. */
    public boolean isPassScheduled() {
        return scheduled.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Unregisters the model listener; queued passes become no-ops. The cells keep their styles. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            buffer.removeModelListener(modelListener);
        }
    }

    // ---- Scheduling ----

    private void requestPass(long delayMillis) {
        if (closed.get() || !scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.schedule(this::scheduledPass, Math.max(0L, delayMillis));
        } catch (RejectedExecutionException e) {
            scheduled.set(false);
        }
    }

    private void scheduledPass() {
        scheduled.set(false);
        if (closed.get()) {
            return;
        }
        long start = System.nanoTime();
        long wait = scheduler.dutyDelayNanos(start);
        if (wait > 0L) {
            requestPass(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(wait + 999_999L)));
            return;
        }
        PassStats stats;
        try {
            stats = runPass();
        } catch (RuntimeException | StackOverflowError e) {
            logPassFailure(e);
            return;
        } finally {
            scheduler.passFinished(start, System.nanoTime());
        }
        if (stats.followUpMillis() >= 0L) {
            requestPass(stats.followUpMillis());
        }
    }

    private void logPassFailure(Throwable e) {
        boolean first;
        synchronized (passLock) {
            first = !passFailureLogged;
            passFailureLogged = true;
        }
        if (first) {
            logger.warn("Keyword highlighting pass failed: {}", e.toString());
        } else {
            logger.debug("Keyword highlighting pass failed again: {}", e.toString());
        }
    }

    // ---- The pass ----

    private PassStats runPass() {
        synchronized (passLock) {
            if (closed.get()) {
                return PassStats.IDLE;
            }
            Selection current = selection.get();
            CompiledHighlightSet set = current.set();
            int generation = current.generation();
            if (generation != passGeneration) {
                beginGeneration(set, generation);
            }
            long passStart = System.nanoTime();
            boolean find = findActive.getAsBoolean();
            boolean alternateAllowed = alternateScreenAllowed.getAsBoolean();
            List<Work> work = new ArrayList<>();
            IdentityHashMap<TerminalLine, Long> prints = new IdentityHashMap<>();
            boolean screenPending;
            boolean alternate;
            buffer.lock();
            try {
                alternate = buffer.isUsingAlternateBuffer();
                if (alternate && !alternateAllowed) {
                    // Nothing to do until the program leaves the alternate screen, which fires a
                    // model change and so schedules the next pass.
                    return new PassStats(0, 0, generation != selection.get().generation() ? 0L : -1L);
                }
                View view = View.of(buffer, alternate);
                if (!alternate) {
                    trackHistory(view, set);
                }
                int rowsLeft = MAX_ROWS_PER_PASS;
                screenPending = collectScreen(view, work, prints, rowsLeft);
                for (Work item : work) {
                    rowsLeft -= item.lines.length;
                }
                if (!alternate) {
                    collectBacklog(view, work, find, rowsLeft);
                }
            } finally {
                buffer.unlock();
            }

            int completed = match(set, work, passStart);
            int rowsEvaluated = 0;
            for (int i = 0; i < completed; i++) {
                rowsEvaluated += work.get(i).lines.length;
            }
            rowsEvaluatedTotal += rowsEvaluated;

            int[] written = new int[2]; // [rows written, rows that got a highlight]
            boolean stale = apply(set, generation, work, completed, prints, written);
            for (int i = 0; i < work.size(); i++) {
                Work item = work.get(i);
                if (i >= completed) {
                    if (item.range == null) {
                        screenPending = true;
                    }
                    continue;
                }
                if (item.range != null) {
                    commit(item);
                }
            }
            ranges.removeIf(range -> range.length <= 0 || range.newest == null);
            screenPending |= stale;
            screenPrints = prints;
            if (written[1] > 0) {
                everStyled = true;
            }

            boolean freshPending = false;
            boolean sweepPending = false;
            for (Range range : ranges) {
                if (range.sweep) {
                    sweepPending = true;
                } else {
                    freshPending = true;
                }
            }
            long followUp;
            if (generation != selection.get().generation()) {
                followUp = 0L;
            } else if (screenPending || freshPending || (sweepPending && !find)) {
                followUp = BACKLOG_INTERVAL_MILLIS;
            } else if (sweepPending) {
                followUp = FIND_POLL_MILLIS;
            } else {
                followUp = -1L;
            }
            if (set.isEmpty() && followUp < 0L && !alternate && !historyResetPending) {
                everStyled = false;
            }
            if (written[0] > 0) {
                notifyRestyled();
            }
            return new PassStats(rowsEvaluated, written[0], followUp);
        }
    }

    private void beginGeneration(CompiledHighlightSet set, int generation) {
        passGeneration = generation;
        screenPrints = new IdentityHashMap<>();
        ranges.clear();
        historyResetPending = true;
        styles.clear();
        disabledRules.clear();
        overruns = new int[set.size()];
    }

    /** Re-anchors on the history, locates the backlog and queues the lines that arrived since the last pass. */
    private void trackHistory(View view, CompiledHighlightSet set) {
        LinesBuffer history = view.history();
        int historyRows = view.historyRows();
        boolean reflow = reflowSeen.getAndSet(false);
        int width = buffer.getWidth();
        if (width != historyWidth) {
            reflow |= historyWidth >= 0;
            historyWidth = width;
        }
        if (historyResetPending) {
            historyResetPending = false;
            ranges.clear();
            if (historyRows > 0 && (!set.isEmpty() || everStyled)) {
                ranges.add(new Range(history.getLine(historyRows - 1), historyRows - 1, historyRows, true));
            }
            anchor(history, historyRows);
            return;
        }
        if (historyRows == 0) {
            ranges.clear();
            anchor(history, 0);
            return;
        }
        if (reflow) {
            // Every line was rebuilt with its styles carried over: nothing to evaluate again.
            ranges.clear();
            anchor(history, historyRows);
            return;
        }
        int horizon = Math.max(0, view.total() - FLOOD_HORIZON_ROWS);
        for (Iterator<Range> it = ranges.iterator(); it.hasNext(); ) {
            Range range = it.next();
            int index = find(history, range.newest, Math.min(range.newestIndex, historyRows - 1));
            if (index < 0) {
                // Lines only leave the history from the top: the newest gone means all of it is gone.
                it.remove();
                continue;
            }
            int oldest = Math.max(index - range.length + 1, range.sweep ? 0 : horizon);
            range.newestIndex = index;
            range.length = index - oldest + 1;
            if (range.length <= 0) {
                it.remove();
            }
        }
        int firstNew = anchorLine == null ? 0
            : find(history, anchorLine, Math.min(anchorIndex, historyRows - 1)) + 1;
        if (!set.isEmpty()) {
            int from = Math.max(firstNew, horizon);
            Range open = null;
            if (!ranges.isEmpty()) {
                Range last = ranges.get(ranges.size() - 1);
                if (!last.sweep && last.newestIndex == from - 1) {
                    open = last;
                }
            }
            for (int index = from; index < historyRows; index++) {
                TerminalLine line = history.getLine(index);
                Long print = screenPrints.get(line);
                if (print != null && print == fingerprint(line)) {
                    open = null; // evaluated while it was on the screen
                    continue;
                }
                if (open == null) {
                    open = new Range(line, index, 0, false);
                    ranges.add(open);
                }
                open.newest = line;
                open.newestIndex = index;
                open.length++;
            }
        }
        anchor(history, historyRows);
    }

    private void anchor(LinesBuffer history, int historyRows) {
        if (historyRows <= 0) {
            anchorLine = null;
            anchorIndex = -1;
        } else {
            anchorIndex = historyRows - 1;
            anchorLine = history.getLine(anchorIndex);
        }
    }

    /** Index of {@code line} at or below {@code from}, or -1. Lines only ever move up, so it never searches down. */
    private static int find(LinesBuffer history, TerminalLine line, int from) {
        for (int index = from; index >= 0; index--) {
            if (history.getLine(index) == line) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Queues the screen rows that changed since the previous pass, bottom up; unchanged rows go
     * straight into {@code prints}. Returns true when changed rows did not fit into the budget.
     */
    private boolean collectScreen(View view, List<Work> work, IdentityHashMap<TerminalLine, Long> prints,
                                  int rowsLeft) {
        boolean pending = false;
        int coveredFrom = Integer.MAX_VALUE;
        for (int y = view.screenRows() - 1; y >= 0; y--) {
            TerminalLine line = view.screen().getLine(y);
            long print = fingerprint(line);
            Long previous = screenPrints.get(line);
            if (previous != null && previous == print) {
                prints.put(line, print);
                continue;
            }
            int index = view.historyRows() + y;
            if (index >= coveredFrom) {
                continue; // part of a logical line already queued
            }
            if (rowsLeft <= 0) {
                pending = true;
                continue;
            }
            Work item = snapshot(view, index);
            work.add(item);
            coveredFrom = index - indexInLine(view, index);
            rowsLeft -= item.lines.length;
        }
        return pending;
    }

    /** Queues backlog lines newest first: new output before sweeps, sweeps only while Find is closed. */
    private void collectBacklog(View view, List<Work> work, boolean find, int rowsLeft) {
        for (int pass = 0; pass < 2 && rowsLeft > 0; pass++) {
            boolean sweeps = pass == 1;
            if (sweeps && find) {
                return;
            }
            for (int r = ranges.size() - 1; r >= 0 && rowsLeft > 0; r--) {
                Range range = ranges.get(r);
                if (range.sweep != sweeps) {
                    continue;
                }
                int index = range.newestIndex;
                int remaining = range.length;
                while (remaining > 0 && rowsLeft > 0 && index >= 0) {
                    Work item = snapshot(view, index);
                    int start = index - indexInLine(view, index);
                    int consumed = index - start + 1;
                    item.range = range;
                    item.rangeNextLength = remaining - consumed;
                    item.rangeNextIndex = start - 1;
                    item.rangeNext = item.rangeNextLength > 0 && start > 0 ? view.line(start - 1) : null;
                    work.add(item);
                    rowsLeft -= item.lines.length;
                    remaining -= consumed;
                    index = start - 1;
                }
            }
        }
    }

    private static void commit(Work item) {
        Range range = item.range;
        range.newest = item.rangeNext;
        range.newestIndex = item.rangeNextIndex;
        range.length = item.rangeNextLength;
    }

    /** Rows of {@code index}'s logical line above it (bounded by {@link LogicalLineProjection#MAX_ROWS}). */
    private static int indexInLine(View view, int index) {
        int start = index;
        while (start > 0 && index - start < LogicalLineProjection.MAX_ROWS - 1 && view.line(start - 1).isWrapped()) {
            start--;
        }
        return index - start;
    }

    /** Copies the logical line around {@code index}: soft-wrapped rows above and below join it. */
    private static Work snapshot(View view, int index) {
        int start = index - indexInLine(view, index);
        int end = index;
        int last = view.total() - 1;
        while (end < last && end - start < LogicalLineProjection.MAX_ROWS - 1 && view.line(end).isWrapped()) {
            end++;
        }
        Work item = new Work(end - start + 1);
        for (int row = start; row <= end; row++) {
            TerminalLine line = view.line(row);
            item.lines[row - start] = line;
            item.texts[row - start] = cellText(line);
            item.onScreen[row - start] = row >= view.historyRows();
        }
        return item;
    }

    /** Matches the queued lines in order until the pass budget runs out; returns how many completed. */
    private int match(CompiledHighlightSet set, List<Work> work, long passStart) {
        if (set.isEmpty()) {
            return work.size(); // restoring needs no matching
        }
        long passDeadline = passStart + PASS_MATCH_BUDGET_NANOS;
        int completed = 0;
        for (Work item : work) {
            LogicalLineProjection projection = LogicalLineProjection.of(Arrays.asList(item.texts));
            long deadline = passDeadline;
            if (completed == 0) {
                // Every pass finishes at least one line, or a line too long for one pass's budget
                // would be retried forever. The floor is twice the rules' budgets together: a rule
                // that runs out of time stops a little after its own budget (the deadline is sampled,
                // not read on every character), so with exactly one budget per rule the last rule of
                // a line on which every rule runs out of time would hit this deadline instead of its
                // own. The line would then never complete, and its overruns never switch a rule off.
                long floor = System.nanoTime() + 2L * set.size() * HighlightMatcher.RULE_BUDGET_NANOS;
                if (floor - deadline > 0L) {
                    deadline = floor;
                }
            }
            HighlightMatcher.Result result = HighlightMatcher.match(set, projection.text(), deadline,
                HighlightMatcher.RULE_BUDGET_NANOS, disabledRules);
            if (!result.complete()) {
                break;
            }
            noteOverruns(set, result.overrunRules());
            item.projection = projection;
            item.owners = result.owners();
            completed++;
        }
        return completed;
    }

    private void noteOverruns(CompiledHighlightSet set, int[] overrunRules) {
        for (int index : overrunRules) {
            if (index < 0 || index >= overruns.length || disabledRules.get(index)) {
                continue;
            }
            if (++overruns[index] >= OVERRUNS_BEFORE_DISABLE) {
                disabledRules.set(index);
                logger.warn("Highlight rule {} of set {} ran out of its time budget {} times and is switched off "
                        + "for this pane until the set changes",
                    set.rule(index).ruleId(), set.setId(), OVERRUNS_BEFORE_DISABLE);
            }
        }
    }

    /**
     * Restyles the completed lines under the buffer lock, {@link #APPLY_CHUNK_ROWS} rows at a time,
     * and fingerprints their screen rows. Returns true when a screen row changed since the snapshot.
     */
    private boolean apply(CompiledHighlightSet set, int generation, List<Work> work, int completed,
                          IdentityHashMap<TerminalLine, Long> prints, int[] written) {
        boolean stale = false;
        int next = 0;
        while (next < completed) {
            buffer.lock();
            try {
                int rows = 0;
                while (next < completed && (rows == 0 || rows + work.get(next).lines.length <= APPLY_CHUNK_ROWS)) {
                    Work item = work.get(next++);
                    rows += item.lines.length;
                    for (int row = 0; row < item.lines.length; row++) {
                        TerminalLine line = item.lines[row];
                        int length = rowLength(line);
                        char[] chars = new char[length];
                        TextStyle[] cellStyles = new TextStyle[length];
                        readCells(line, chars, cellStyles);
                        if (!item.texts[row].contentEquals(java.nio.CharBuffer.wrap(chars))) {
                            stale |= item.onScreen[row];
                            continue;
                        }
                        int[] owners = item.owners != null
                            ? item.projection.cellOwners(item.owners, row, length) : null;
                        int result = restyle(line, chars, cellStyles, owners, set, generation);
                        if (result != 0) {
                            written[0]++;
                            if ((result & 2) != 0) {
                                written[1]++;
                            }
                        }
                        if (item.onScreen[row]) {
                            prints.put(line, fingerprint(line));
                        }
                    }
                }
            } finally {
                buffer.unlock();
            }
        }
        return stale;
    }

    /**
     * Rewrites the runs of {@code line} whose style is not what the owners ask for. Returns 0 when
     * nothing was written, otherwise bit 1 set, plus bit 2 when a highlight was written.
     */
    private int restyle(TerminalLine line, char[] chars, TextStyle[] cellStyles, int[] owners,
                        CompiledHighlightSet set, int generation) {
        int result = 0;
        int runStart = -1;
        TextStyle runStyle = null;
        TextStyle lastBase = null;
        int lastOwner = HighlightMatcher.NO_OWNER;
        TextStyle lastDerived = null;
        int length = chars.length;
        for (int x = 0; x <= length; x++) {
            TextStyle wanted = null;
            boolean change = false;
            if (x < length) {
                TextStyle current = cellStyles[x];
                int owner = owners != null && x < owners.length ? owners[x] : HighlightMatcher.NO_OWNER;
                if (current == null || current instanceof HyperlinkStyle) {
                    wanted = current;
                } else if (owner == HighlightMatcher.NO_OWNER) {
                    wanted = HighlightTextStyle.baseOf(current);
                } else if (current instanceof HighlightTextStyle highlight && highlight.isFor(owner, generation)) {
                    wanted = current;
                } else {
                    TextStyle base = HighlightTextStyle.baseOf(current);
                    if (base != lastBase || owner != lastOwner) {
                        lastBase = base;
                        lastOwner = owner;
                        lastDerived = styles.derive(base, set.rule(owner), generation);
                    }
                    wanted = lastDerived;
                }
                change = wanted != current;
            }
            if (runStart >= 0 && (!change || wanted != runStyle)) {
                line.writeString(runStart, new CharBuffer(chars, runStart, x - runStart), runStyle);
                result |= runStyle instanceof HighlightTextStyle ? 3 : 1;
                runStart = -1;
            }
            if (change && runStart < 0) {
                runStart = x;
                runStyle = wanted;
            }
        }
        return result;
    }

    private void notifyRestyled() {
        try {
            repaint.run();
        } catch (RuntimeException e) {
            logger.debug("Repaint after highlighting failed: {}", e.toString());
        }
        try {
            onRestyled.run();
        } catch (RuntimeException e) {
            logger.debug("Restyle callback failed: {}", e.toString());
        }
    }

    // ---- Line access (callers hold the buffer lock) ----

    private static int rowLength(TerminalLine line) {
        int length = 0;
        for (TerminalLine.TextEntry entry : line.getEntries()) {
            length += entry.getLength();
        }
        return length;
    }

    private static void readCells(TerminalLine line, char[] chars, TextStyle[] cellStyles) {
        int x = 0;
        for (TerminalLine.TextEntry entry : line.getEntries()) {
            CharBuffer text = entry.getText();
            TextStyle style = entry.getStyle();
            for (int i = 0; i < text.length() && x < chars.length; i++, x++) {
                chars[x] = text.charAt(i);
                cellStyles[x] = style;
            }
        }
    }

    /** Every cell's character, unwritten {@code NUL}s and wide-character placeholders included. */
    private static String cellText(TerminalLine line) {
        StringBuilder text = new StringBuilder();
        for (TerminalLine.TextEntry entry : line.getEntries()) {
            text.append(entry.getText());
        }
        return text.toString();
    }

    /**
     * A row's fingerprint: every character, the identity of every cell's style and the wrap flag. A
     * program that rewrites a cell — even with the same character — replaces its style object, so
     * the fingerprint changes; restyling a run in a way that leaves every cell's style as it was
     * does not.
     */
    static long fingerprint(TerminalLine line) {
        long hash = 1L;
        for (TerminalLine.TextEntry entry : line.getEntries()) {
            int style = System.identityHashCode(entry.getStyle());
            CharBuffer text = entry.getText();
            for (int i = 0; i < text.length(); i++) {
                hash = hash * 31L + text.charAt(i);
                hash = hash * 31L + style;
            }
        }
        return hash * 31L + (line.isWrapped() ? 1L : 2L);
    }
}
