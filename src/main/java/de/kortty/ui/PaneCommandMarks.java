package de.kortty.ui;

import com.sithtermfx.core.Terminal;
import com.sithtermfx.core.compatibility.Point;
import com.sithtermfx.core.model.SelectionUtil;
import com.sithtermfx.core.model.TerminalSelection;
import com.sithtermfx.core.model.TerminalTextBuffer;
import de.kortty.shellintegration.CommandBlockStore;
import de.kortty.shellintegration.CommandBlockStore.CommandBlock;
import de.kortty.shellintegration.LastOutputRange;
import de.kortty.shellintegration.PromptNavigator;
import de.kortty.shellintegration.ShellIntegrationEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

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
 * <p>Lock order, on every thread: the buffer lock, then the tracker, then the store.
 */
final class PaneCommandMarks {

    private final TerminalTextBuffer buffer;
    private final ScrollbackTrimTracker tracker;
    private final CommandBlockStore store = new CommandBlockStore();
    private final PromptNavigator navigator = new PromptNavigator();

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
     */
    void record(ShellIntegrationEvent event, Terminal terminal, long nanos) {
        if (!isMark(event)) {
            return;
        }
        buffer.lock();
        try {
            if (buffer.isUsingAlternateBuffer()) {
                return;
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
                case ShellIntegrationEvent.CommandFinished finished ->
                    store.commandFinished(line, column, finished.exitStatus(), nanos);
                case ShellIntegrationEvent.RemoteNotification notification -> {
                    // Not a mark; isMark filtered it out.
                }
                case ShellIntegrationEvent.Oversize oversize -> {
                    // Not a mark; isMark filtered it out.
                }
            }
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
            case CLEARED -> store.clear();
            case UNKNOWN, SUSPENDED -> {
                // A width reflow or the alternate screen: the marks stay where they are.
            }
        }
    }
}
