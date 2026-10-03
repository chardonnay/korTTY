package de.kortty.shellintegration;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * The commands of one terminal pane as its shell marked them with {@code OSC 133}: where each prompt
 * starts (A), where the command line starts (B), where the output starts (C), and where and how the
 * command finished (D). Only the primary screen is marked; the caller drops marks that arrive while
 * a full-screen program uses the alternate screen.
 *
 * <p><b>Lines.</b> The pane's text is numbered from the first scrollback line ({@code absolute
 * line} = history line count + screen row). Once the scrollback is full, every new line drops the
 * oldest one and the absolute numbers of all lines shift up by one. The store therefore keys its
 * marks by a <em>line id</em> that never changes: {@code id = trimmed + absolute line}, where
 * {@code trimmed} counts every line dropped from the top so far ({@link #shift}). A mark whose id
 * is below {@code trimmed} has left the scrollback.
 *
 * <p><b>Life of a block.</b>
 * <ul>
 *   <li>A opens a block. A prompt redrawn on the same line (a resize, Ctrl+L) replaces it, and so
 *       does one drawn above blocks that are still there: their lines were overwritten.</li>
 *   <li>A while the previous block is still open closes that one without an exit status (Ctrl+C at
 *       the prompt, a shell without a D mark).</li>
 *   <li>B and C go to the open block; D closes it with its status, but only once C was seen: a D
 *       after an empty command line belongs to no command.</li>
 * </ul>
 *
 * <p>Thread-safe: the emulator thread records, the FX thread reads. {@link #hasPrompts()} is a
 * lock-free read for the key actions, which SithTermFX asks on every key press.
 */
public final class CommandBlockStore implements PromptNavigator.Prompts {

    /** At most this many blocks are kept; the oldest go first. A full 100,000-line scrollback of prompts fits. */
    static final int MAX_BLOCKS = 100_000;

    /** A position in the pane: a line id (see the class comment) and a 0-based column. */
    public record Mark(long line, int column) {
    }

    /**
     * One command as the shell marked it; an immutable snapshot.
     *
     * @param prompt           where the prompt starts (A)
     * @param command          where the command line starts (B), or {@code null}
     * @param output           where the output starts (C), or {@code null} before the command ran
     * @param end              where the command finished (D), or {@code null}
     * @param exitStatus       the status D reported, or {@code null} when it reported none
     * @param outputStartNanos {@link System#nanoTime()} at C, or 0
     * @param endNanos         {@link System#nanoTime()} at D, or 0
     * @param closed           whether the block is over: D arrived, or the next prompt did
     */
    public record CommandBlock(Mark prompt, @Nullable Mark command, @Nullable Mark output, @Nullable Mark end,
            @Nullable Integer exitStatus, long outputStartNanos, long endNanos, boolean closed) {

        public CommandBlock {
            Objects.requireNonNull(prompt, "prompt");
        }

        /** The command was submitted and has not finished: C without D and no prompt after it. */
        public boolean running() {
            return output != null && !closed;
        }

        /** Whether D closed the block, so {@link #exitStatus()} and {@link #endNanos()} are its own. */
        public boolean finished() {
            return end != null;
        }

        /** The last line the block marked. */
        long lastLine() {
            long last = prompt.line();
            if (command != null) {
                last = Math.max(last, command.line());
            }
            if (output != null) {
                last = Math.max(last, output.line());
            }
            if (end != null) {
                last = Math.max(last, end.line());
            }
            return last;
        }
    }

    // Blocks by the line id of their prompt; guarded by this.
    private final TreeMap<Long, CommandBlock> blocks = new TreeMap<>();
    // Lines dropped from the top of the scrollback so far; guarded by this.
    private long trimmed;
    private volatile boolean hasPrompts;
    private volatile boolean empty = true;

    /**
     * Whether a prompt mark is still in the scrollback, so prompt navigation has somewhere to go. A
     * lock-free read; it can lag a trim that was not applied yet, which the navigation itself rechecks.
     */
    public boolean hasPrompts() {
        return hasPrompts;
    }

    /** Whether the store holds no block at all; a lock-free read. */
    public boolean isEmpty() {
        return empty;
    }

    /** The line id of an absolute line, as things stand now. */
    public synchronized long lineId(int absoluteLine) {
        return trimmed + absoluteLine;
    }

    /** The absolute line of a line id as things stand now; negative once it left the scrollback. */
    public synchronized long absoluteLine(long lineId) {
        return lineId - trimmed;
    }

    /** {@code OSC 133;A} with the cursor on {@code absoluteLine}, {@code column}. */
    public synchronized void promptStart(int absoluteLine, int column) {
        long line = trimmed + absoluteLine;
        // A prompt on or above existing prompts: their lines were overwritten (a redraw, a clear).
        blocks.tailMap(line, true).clear();
        Map.Entry<Long, CommandBlock> previous = blocks.lastEntry();
        if (previous != null && !previous.getValue().closed()) {
            CommandBlock open = previous.getValue();
            blocks.put(previous.getKey(), new CommandBlock(open.prompt(), open.command(), open.output(), open.end(),
                open.exitStatus(), open.outputStartNanos(), open.endNanos(), true));
        }
        blocks.put(line, new CommandBlock(new Mark(line, column), null, null, null, null, 0L, 0L, false));
        while (blocks.size() > MAX_BLOCKS) {
            blocks.pollFirstEntry();
        }
        published();
    }

    /** {@code OSC 133;B}: the command line of the open block starts here. */
    public synchronized void commandStart(int absoluteLine, int column) {
        Map.Entry<Long, CommandBlock> entry = openBlock();
        if (entry == null || entry.getValue().output() != null) {
            return;
        }
        CommandBlock open = entry.getValue();
        blocks.put(entry.getKey(), new CommandBlock(open.prompt(), new Mark(trimmed + absoluteLine, column), null,
            null, null, 0L, 0L, false));
    }

    /** {@code OSC 133;C}: the open block's command was submitted; its output starts here. */
    public synchronized void outputStart(int absoluteLine, int column, long nanos) {
        Map.Entry<Long, CommandBlock> entry = openBlock();
        if (entry == null || entry.getValue().output() != null) {
            return;
        }
        CommandBlock open = entry.getValue();
        blocks.put(entry.getKey(), new CommandBlock(open.prompt(), open.command(),
            new Mark(trimmed + absoluteLine, column), null, null, nanos, 0L, false));
    }

    /**
     * {@code OSC 133;D}: the open block's command finished here with {@code exitStatus} (null when
     * the shell sent none). Ignored unless the block saw C.
     */
    public synchronized void commandFinished(int absoluteLine, int column, @Nullable Integer exitStatus, long nanos) {
        Map.Entry<Long, CommandBlock> entry = openBlock();
        if (entry == null || entry.getValue().output() == null) {
            return;
        }
        CommandBlock open = entry.getValue();
        blocks.put(entry.getKey(), new CommandBlock(open.prompt(), open.command(), open.output(),
            new Mark(trimmed + absoluteLine, column), exitStatus, open.outputStartNanos(), nanos, true));
    }

    /**
     * {@code lines} lines left the top of the scrollback: every absolute line moved up by that many.
     * Blocks that are over and lie wholly above the scrollback go; an open block stays, since its
     * output still runs.
     */
    public synchronized void shift(int lines) {
        if (lines <= 0) {
            return;
        }
        trimmed += lines;
        Map.Entry<Long, CommandBlock> first;
        while ((first = blocks.firstEntry()) != null
                && first.getValue().closed() && first.getValue().lastLine() < trimmed) {
            blocks.pollFirstEntry();
        }
        published();
    }

    /** The scrollback was cleared (Clear Buffer, {@code ESC[3J}, a reset): every mark is gone. */
    public synchronized void clear() {
        blocks.clear();
        published();
    }

    /** The closest prompt strictly above {@code lineId} that is still in the scrollback. */
    @Override
    public synchronized OptionalLong promptAbove(long lineId) {
        Long key = blocks.lowerKey(lineId);
        return key != null && key >= trimmed ? OptionalLong.of(key) : OptionalLong.empty();
    }

    /** The closest prompt strictly below {@code lineId} that is still in the scrollback. */
    @Override
    public synchronized OptionalLong promptBelow(long lineId) {
        Long key = blocks.higherKey(lineId);
        if (key != null && key < trimmed) {
            key = blocks.ceilingKey(trimmed);
        }
        return key != null ? OptionalLong.of(key) : OptionalLong.empty();
    }

    /**
     * The prompt the user is typing at: the newest block when its command has not been submitted,
     * or empty.
     */
    public synchronized OptionalLong currentPrompt() {
        Map.Entry<Long, CommandBlock> last = blocks.lastEntry();
        if (last == null || last.getValue().closed() || last.getValue().output() != null || last.getKey() < trimmed) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(last.getKey());
    }

    /**
     * The newest command that finished: its block saw C and then D. A command that runs now, and a
     * prompt left without a command, do not count; the one before them does.
     */
    public synchronized Optional<CommandBlock> lastFinished() {
        for (CommandBlock block : blocks.descendingMap().values()) {
            if (block.finished()) {
                return Optional.of(block);
            }
        }
        return Optional.empty();
    }

    /** Every block still kept, oldest first. */
    public synchronized List<CommandBlock> blocks() {
        return List.copyOf(blocks.values());
    }

    /** Lines dropped from the top of the scrollback since the store began. */
    public synchronized long trimmed() {
        return trimmed;
    }

    private @Nullable Map.Entry<Long, CommandBlock> openBlock() {
        Map.Entry<Long, CommandBlock> last = blocks.lastEntry();
        return last != null && !last.getValue().closed() ? last : null;
    }

    private void published() {
        Map.Entry<Long, CommandBlock> last = blocks.lastEntry();
        empty = last == null;
        hasPrompts = last != null && last.getKey() >= trimmed;
    }
}
