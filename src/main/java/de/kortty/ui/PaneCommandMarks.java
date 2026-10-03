package de.kortty.ui;

import com.sithtermfx.core.Terminal;
import com.sithtermfx.core.model.TerminalTextBuffer;
import de.kortty.shellintegration.CommandBlockStore;
import de.kortty.shellintegration.PromptNavigator;
import de.kortty.shellintegration.ShellIntegrationEvent;

import java.util.Objects;

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
