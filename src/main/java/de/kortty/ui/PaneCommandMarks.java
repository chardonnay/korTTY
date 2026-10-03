package de.kortty.ui;

import com.sithtermfx.core.Terminal;
import com.sithtermfx.core.compatibility.Point;
import com.sithtermfx.core.model.SelectionUtil;
import com.sithtermfx.core.model.TerminalSelection;
import com.sithtermfx.core.model.TerminalTextBuffer;
import de.kortty.shellintegration.CommandBlockStore;
import de.kortty.shellintegration.CommandBlockStore.CommandBlock;
import de.kortty.shellintegration.CommandStatus;
import de.kortty.shellintegration.LastOutputRange;
import de.kortty.shellintegration.PromptNavigator;
import de.kortty.shellintegration.ShellIntegrationEvent;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The shell-integration marks of one terminal pane, kept in step with its scrollback.
 *
 * <p>{@link #record} runs on the pane's emulator thread when {@link ShellIntegrationTtyConnector}
 * delivers an {@code OSC 133} event; SithTermFX has then interpreted every char before the mark, so
 * the cursor stands exactly where the shell put it. A mark that arrives while a full-screen program
 * uses the alternate screen is dropped: it would name a line of a screen that disappears.
 *
 * <p>The marks follow the scrollback through a {@link ScrollbackTrimTracker} of their own, separate
 * from the timestamp gutter's, whose reports go to that gutter. {@link #observe} counts every scroll
 * step from the buffer's model listener while there are marks to move, and {@link #syncTrims} hands
 * what was counted to the {@link CommandBlockStore} before every read and write. Without marks the
 * tracker sleeps, and the first mark re-anchors it, so a pane without shell integration costs
 * nothing per output line.
 *
 * <p>{@link #lastOutput} finds the output of the newest finished command in the buffer, for Select
 * and Copy Last Output.
 *
 * <p>{@link #gutterUpdate} hands the command-timestamp gutter what it shows: the statuses of the
 * commands by line, and the {@code D} marks recorded since the last update, whose times become
 * completion timestamps. {@link #requestGutterUpdate} keeps it to one scheduled update at a time.
 *
 * <p>Lock order, on every thread: the buffer lock, then the tracker or the pending completions, then
 * the store.
 */
final class PaneCommandMarks {

    /** At most this many finished commands wait for the gutter; a flood of marks drops the oldest. */
    static final int MAX_PENDING_COMPLETIONS = 256;

    private final TerminalTextBuffer buffer;
    private final ScrollbackTrimTracker tracker;
    private final CommandBlockStore store = new CommandBlockStore();
    private final PromptNavigator navigator = new PromptNavigator();
    // D marks the gutter has not been given yet, oldest first; guarded by itself.
    private final ArrayDeque<PendingCompletion> pendingCompletions = new ArrayDeque<>();
    private final AtomicBoolean gutterUpdateRequested = new AtomicBoolean();

    PaneCommandMarks(TerminalTextBuffer buffer) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.tracker = ScrollbackTrimTracker.forBuffer(buffer);
    }

    TerminalTextBuffer buffer() {
        return buffer;
    }

    CommandBlockStore store() {
        return store;
    }

    PromptNavigator navigator() {
        return navigator;
    }

    /** The model listener's part, on whatever thread changed the buffer: counts scroll steps while marks exist. */
    void observe() {
        if (!store.isEmpty()) {
            tracker.observe();
        }
    }

    /**
     * Records a shell-integration mark at the cursor of {@code terminal}; other events are ignored.
     * Emulator thread.
     *
     * @return the status of the command a {@code D} mark finished, with its exit status and runtime;
     *         {@code null} for every other mark, and for a {@code D} that finished nothing (no
     *         {@code C} before it, or the alternate screen)
     */
    @Nullable CommandStatus record(ShellIntegrationEvent event, Terminal terminal, long nanos) {
        if (!isMark(event)) {
            return null;
        }
        buffer.lock();
        try {
            if (buffer.isUsingAlternateBuffer()) {
                return null;
            }
            syncTrims();
            int height = Math.max(1, buffer.getHeight());
            int row = Math.max(0, Math.min(height - 1, terminal.getCursorY() - 1));
            int column = Math.max(0, terminal.getCursorX() - 1);
            int line = buffer.getHistoryLinesCount() + row;
            switch (event) {
                case ShellIntegrationEvent.PromptStart prompt -> store.promptStart(line, column);
                case ShellIntegrationEvent.CommandStart command -> store.commandStart(line, column);
                case ShellIntegrationEvent.OutputStart output -> store.outputStart(line, column, nanos);
                case ShellIntegrationEvent.CommandFinished finished -> {
                    if (store.commandFinished(line, column, finished.exitStatus(), nanos)) {
                        queueCompletion(store.lineId(line), nanos);
                        // The block D just closed is the newest finished one.
                        return store.lastFinished().map(CommandStatus::of).orElse(null);
                    }
                }
                case ShellIntegrationEvent.RemoteNotification notification -> {
                    // Not a mark; isMark filtered it out.
                }
                case ShellIntegrationEvent.Oversize oversize -> {
                    // Not a mark; isMark filtered it out.
                }
            }
            return null;
        } finally {
            buffer.unlock();
        }
    }

    /** Whether {@code event} is one of the {@code OSC 133} marks A, B, C and D. */
    static boolean isMark(ShellIntegrationEvent event) {
        return switch (event) {
            case ShellIntegrationEvent.PromptStart prompt -> true;
            case ShellIntegrationEvent.CommandStart command -> true;
            case ShellIntegrationEvent.OutputStart output -> true;
            case ShellIntegrationEvent.CommandFinished finished -> true;
            case ShellIntegrationEvent.RemoteNotification notification -> false;
            case ShellIntegrationEvent.Oversize oversize -> false;
            case null -> false;
        };
    }

    /** What {@link #lastOutput} found. */
    enum LastOutputStatus {
        /** The output of the newest finished command, with its range. */
        FOUND,
        /** The pane has no marks at all: its shell is not set up for shell integration. */
        NO_MARKS,
        /** No command finished yet, or its output left the scrollback. */
        NO_COMMAND,
        /** The newest finished command printed no text. */
        NO_OUTPUT,
        /** A full-screen program uses the alternate screen, which hides the output. */
        FULL_SCREEN
    }

    /**
     * The output of the newest finished command, as {@link #lastOutput} read it.
     *
     * @param status    what was found
     * @param range     where the output stands, when {@link LastOutputStatus#FOUND}
     * @param selection the same range as a SithTermFX selection (screen-relative lines, inclusive
     *                  end), when found
     * @param text      the output's text as Copy reads a selection, when found and asked for
     */
    record LastOutput(LastOutputStatus status, @Nullable LastOutputRange range,
            @Nullable TerminalSelection selection, @Nullable String text) {

        static LastOutput of(LastOutputStatus status) {
            return new LastOutput(status, null, null, null);
        }
    }

    /**
     * The output of the newest finished command (see {@link CommandBlockStore#lastFinished()}) as
     * the pane shows it now, read under the buffer lock after applying the scrollback's trims. The
     * text, which can be long, is read only {@code withText}; it is what SithTermFX's Copy gives
     * for the returned selection. Any thread.
     */
    LastOutput lastOutput(boolean withText) {
        buffer.lock();
        try {
            if (buffer.isUsingAlternateBuffer()) {
                return LastOutput.of(LastOutputStatus.FULL_SCREEN);
            }
            syncTrims();
            if (store.isEmpty()) {
                return LastOutput.of(LastOutputStatus.NO_MARKS);
            }
            Optional<CommandBlock> block = store.lastFinished();
            if (block.isEmpty() || block.get().end() == null
                    || store.absoluteLine(block.get().end().line()) < 0) {
                return LastOutput.of(LastOutputStatus.NO_COMMAND);
            }
            int history = buffer.getHistoryLinesCount();
            int count = history + Math.max(1, buffer.getHeight());
            Optional<LastOutputRange> found = LastOutputRange.of(block.get(), store.trimmed(),
                new LastOutputRange.Lines() {
                    @Override
                    public int count() {
                        return count;
                    }

                    @Override
                    public int length(int absoluteLine) {
                        return buffer.getLine(absoluteLine - history).getText().length();
                    }
                });
            if (found.isEmpty()) {
                return LastOutput.of(LastOutputStatus.NO_OUTPUT);
            }
            LastOutputRange range = found.get();
            Point start = new Point(range.startColumn(), range.startLine() - history);
            TerminalSelection selection = new TerminalSelection(new Point(start),
                new Point(range.lastColumn(), range.endLine() - history));
            String text = withText
                ? SelectionUtil.getSelectedText(new Point(start), new Point(range.endColumn(), range.endLine() - history),
                    buffer)
                : null;
            return new LastOutput(LastOutputStatus.FOUND, range, selection, text);
        } finally {
            buffer.unlock();
        }
    }

    /**
     * Applies what the scrollback dropped since the last look to the marks. Call it with the buffer
     * lock held, so no trim slips in between it and the positions the caller reads next.
     */
    void syncTrims() {
        if (store.isEmpty()) {
            // Nothing to move; start counting from here once the first mark is recorded.
            tracker.reset();
            return;
        }
        ScrollbackTrimTracker.Trim trim = tracker.poll();
        switch (trim.kind()) {
            case SHIFT -> store.shift(trim.lines());
            case CLEARED -> {
                store.clear();
                synchronized (pendingCompletions) {
                    pendingCompletions.clear();
                }
            }
            case UNKNOWN, SUSPENDED -> {
                // A width reflow or the alternate screen: the marks stay where they are.
            }
        }
    }

    /**
     * A command that finished since the previous {@link #gutterUpdate}: the absolute line of its
     * {@code D} mark and the {@link System#nanoTime()} it arrived at.
     */
    record Completion(int absoluteLine, long nanos) {

        /** The wall-clock time of the mark, given the wall clock and {@code nanoTime} of now. */
        LocalDateTime time(LocalDateTime now, long nowNanos) {
            return now.minusNanos(Math.max(0L, nowNanos - nanos));
        }
    }

    /**
     * What the command-timestamp gutter needs: the statuses of the commands by absolute line (see
     * {@link CommandBlockStore#commandStatuses()}) and the commands that finished since the last
     * update, oldest first.
     */
    record GutterUpdate(NavigableMap<Integer, CommandStatus> statuses, List<Completion> completions) {
    }

    /**
     * Asks for a {@link #gutterUpdate}: true when the caller is to schedule one, false while one is
     * scheduled and not taken yet, which will see this change too. Any thread.
     */
    boolean requestGutterUpdate() {
        return gutterUpdateRequested.compareAndSet(false, true);
    }

    /** Withdraws a {@link #requestGutterUpdate} that could not be scheduled, so the next change asks again. */
    void cancelGutterUpdateRequest() {
        gutterUpdateRequested.set(false);
    }

    /**
     * The statuses and new completions as the pane stands now, under the buffer lock after applying
     * the scrollback's trims, so their lines name the lines the gutter shows. A completion whose line
     * left the scrollback meanwhile is dropped. Clears the request first, so a mark recorded while
     * this runs asks for the next update. Any thread; the gutter takes it on the FX thread.
     */
    GutterUpdate gutterUpdate() {
        gutterUpdateRequested.set(false);
        buffer.lock();
        try {
            syncTrims();
            List<Completion> completions = new ArrayList<>();
            synchronized (pendingCompletions) {
                for (PendingCompletion pending : pendingCompletions) {
                    long line = store.absoluteLine(pending.lineId());
                    if (line >= 0 && line <= Integer.MAX_VALUE) {
                        completions.add(new Completion((int) line, pending.nanos()));
                    }
                }
                pendingCompletions.clear();
            }
            return new GutterUpdate(store.commandStatuses(), List.copyOf(completions));
        } finally {
            buffer.unlock();
        }
    }

    private void queueCompletion(long lineId, long nanos) {
        synchronized (pendingCompletions) {
            while (pendingCompletions.size() >= MAX_PENDING_COMPLETIONS) {
                pendingCompletions.pollFirst();
            }
            pendingCompletions.addLast(new PendingCompletion(lineId, nanos));
        }
    }

    /** A {@code D} mark the gutter was not given yet, by its line id (see {@link CommandBlockStore}). */
    private record PendingCompletion(long lineId, long nanos) {
    }
}
