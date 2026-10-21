package de.kortty.ui;

import com.sithtermfx.core.Terminal;
import com.sithtermfx.core.TerminalColor;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalLine;
import com.sithtermfx.core.model.TerminalTextBuffer;

import java.util.List;

/**
 * Writes the in-terminal blocks of KI-Agent design 03 into a pane's screen buffer. A block lives in rows
 * inserted directly above the line the cursor is on, so the shell's own prompt line, where the user keeps
 * typing and where the shell echoes, is never rewritten: rows are inserted, rewritten and removed around
 * it and the cursor is put back exactly where it was. Nothing is sent to the shell.
 *
 * <p>All edits hold the buffer lock, so output arriving from the shell lands before or after an edit,
 * never in the middle. A block is tracked by the object of its first line; once that line has scrolled
 * into the history (or the screen was rebuilt) the block can no longer change and its owner starts a
 * new one. Nothing is written while a full-screen program uses the alternate screen.
 */
final class AgentTerminalBlockWriter {

    /** The rows of one block on the screen. */
    static final class Region {
        private TerminalLine firstLine;
        private int lineCount;

        int lineCount() {
            return lineCount;
        }
    }

    /** What a rewrite did. */
    enum Result {
        WRITTEN,
        /** A full-screen program is showing; try again later. */
        DEFERRED,
        /** The block left the screen; it cannot change any more. */
        LOST
    }

    /** Text colours by {@link AgentInlineBlockLayout.Role}; {@code null} keeps the terminal's colour. */
    record Palette(
        TerminalColor border,
        TerminalColor accent,
        TerminalColor text,
        TerminalColor muted,
        TerminalColor ok,
        TerminalColor warn,
        TerminalColor error,
        TerminalColor think) {

        TextStyle styleFor(AgentInlineBlockLayout.Role role) {
            TerminalColor foreground = switch (role) {
                case BORDER -> border;
                case ACCENT, KEY -> accent;
                case TITLE, TEXT -> text;
                case MUTED -> muted;
                case OK -> ok;
                case WARN -> warn;
                case ERROR -> error;
                case THINK -> think;
            };
            TextStyle.Builder builder = new TextStyle(foreground, null).toBuilder();
            if (role == AgentInlineBlockLayout.Role.TITLE || role == AgentInlineBlockLayout.Role.KEY) {
                builder.setOption(TextStyle.Option.BOLD, true);
            }
            return builder.build();
        }

        /**
         * The block colours for an agent palette ({@code #RRGGBB} accent, muted and error) on a dark or
         * light terminal. Status colours are fixed per brightness so they read on any theme.
         */
        static Palette of(String accentHex, String mutedHex, String errorHex, boolean darkBackground) {
            int[] accent = rgb(accentHex, darkBackground ? new int[]{79, 156, 240} : new int[]{11, 99, 206});
            int[] muted = rgb(mutedHex, darkBackground ? new int[]{134, 150, 171} : new int[]{94, 107, 122});
            int[] error = rgb(errorHex, darkBackground ? new int[]{227, 106, 77} : new int[]{180, 35, 24});
            int[] border = darkBackground
                ? mix(accent, new int[]{0, 0, 0}, 0.30)
                : mix(accent, new int[]{255, 255, 255}, 0.25);
            return new Palette(
                color(border),
                color(accent),
                null,
                color(muted),
                darkBackground ? TerminalColor.rgb(63, 182, 139) : TerminalColor.rgb(31, 138, 95),
                darkBackground ? TerminalColor.rgb(229, 168, 75) : TerminalColor.rgb(183, 121, 31),
                color(error),
                darkBackground ? TerminalColor.rgb(164, 140, 255) : TerminalColor.rgb(107, 70, 193));
        }

        private static TerminalColor color(int[] rgb) {
            return TerminalColor.rgb(rgb[0], rgb[1], rgb[2]);
        }

        private static int[] rgb(String hex, int[] fallback) {
            if (hex == null) {
                return fallback;
            }
            String value = hex.trim().startsWith("#") ? hex.trim().substring(1) : hex.trim();
            if (value.length() != 6) {
                return fallback;
            }
            try {
                return new int[]{
                    Integer.parseInt(value.substring(0, 2), 16),
                    Integer.parseInt(value.substring(2, 4), 16),
                    Integer.parseInt(value.substring(4, 6), 16)};
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static int[] mix(int[] left, int[] right, double ratio) {
            return new int[]{
                (int) Math.round(left[0] * (1 - ratio) + right[0] * ratio),
                (int) Math.round(left[1] * (1 - ratio) + right[1] * ratio),
                (int) Math.round(left[2] * (1 - ratio) + right[2] * ratio)};
        }
    }

    private final Terminal terminal;
    private final TerminalTextBuffer buffer;
    private final StyleState styleState;

    AgentTerminalBlockWriter(Terminal terminal, TerminalTextBuffer buffer, StyleState styleState) {
        this.terminal = terminal;
        this.buffer = buffer;
        this.styleState = styleState;
    }

    int columns() {
        return buffer.getWidth();
    }

    /**
     * Inserts a block directly above the cursor's line and writes {@code lines} into it, scrolling older
     * output into the history when the screen has no free rows below. Returns {@code null} while a
     * full-screen program uses the alternate screen.
     */
    Region open(List<AgentInlineBlockLayout.Line> lines, Palette palette) {
        buffer.lock();
        try {
            if (buffer.isUsingAlternateBuffer()) {
                return null;
            }
            int height = buffer.getHeight();
            int cursorRow = terminal.getCursorY() - 1;
            int cursorColumn = terminal.getCursorX() - 1;
            int count = Math.max(1, Math.min(lines.size(), height - 1));
            int scrolled = makeRoomBelow(Math.max(cursorRow, lastUsedRow()), count);
            cursorRow = Math.max(0, cursorRow - scrolled);
            terminal.cursorPosition(1, cursorRow + 1);
            terminal.insertLines(count);
            Region region = new Region();
            region.firstLine = buffer.getLine(cursorRow);
            region.lineCount = count;
            writeRows(cursorRow, lines.subList(0, count), palette);
            terminal.cursorPosition(cursorColumn + 1, cursorRow + count + 1);
            return region;
        } finally {
            buffer.unlock();
        }
    }

    /**
     * Replaces the block's rows with {@code lines}, growing or shrinking it; the rows below move along.
     * Rows equal to the same row of {@code previous}, what the block showed so far, are left alone.
     */
    Result rewrite(Region region, List<AgentInlineBlockLayout.Line> lines,
                   List<AgentInlineBlockLayout.Line> previous, Palette palette) {
        if (region == null || region.firstLine == null) {
            return Result.LOST;
        }
        buffer.lock();
        try {
            if (buffer.isUsingAlternateBuffer()) {
                return Result.DEFERRED;
            }
            int startRow = buffer.findScreenLineIndex(region.firstLine);
            if (startRow < 0) {
                return Result.LOST;
            }
            TextStyle previousStyle = styleState.getCurrent();
            int height = buffer.getHeight();
            int cursorRow = terminal.getCursorY() - 1;
            int cursorColumn = terminal.getCursorX() - 1;
            if (cursorRow >= startRow && cursorRow < startRow + region.lineCount) {
                // The cursor went into the block (the screen was cleared or rebuilt): leave those rows alone.
                return Result.LOST;
            }
            int newCount = Math.max(1, Math.min(lines.size(), height - 1));
            int oldEnd = startRow + region.lineCount;
            if (newCount > region.lineCount) {
                int extra = newCount - region.lineCount;
                int scrolled = makeRoomBelow(Math.max(cursorRow, lastUsedRow()), extra);
                startRow -= scrolled;
                cursorRow -= scrolled;
                oldEnd -= scrolled;
                if (startRow < 0) {
                    terminal.cursorPosition(cursorColumn + 1, Math.max(0, cursorRow) + 1);
                    return Result.LOST;
                }
                terminal.cursorPosition(1, oldEnd + 1);
                terminal.insertLines(extra);
                if (cursorRow >= oldEnd) {
                    cursorRow += extra;
                }
            } else if (newCount < region.lineCount) {
                int removed = region.lineCount - newCount;
                terminal.cursorPosition(1, startRow + newCount + 1);
                terminal.deleteLines(removed);
                if (cursorRow >= startRow + region.lineCount) {
                    cursorRow -= removed;
                } else if (cursorRow >= startRow + newCount) {
                    cursorRow = startRow + newCount;
                }
            }
            int firstChanged = 0;
            if (previous != null) {
                int common = Math.min(Math.min(previous.size(), region.lineCount), newCount);
                while (firstChanged < common && previous.get(firstChanged).equals(lines.get(firstChanged))) {
                    firstChanged++;
                }
            }
            boolean sameShape = previous != null && previous.size() == region.lineCount && newCount == region.lineCount;
            for (int i = firstChanged; i < newCount; i++) {
                if (sameShape && previous.get(i).equals(lines.get(i))) {
                    continue;
                }
                writeRows(startRow + i, lines.subList(i, i + 1), palette);
            }
            region.lineCount = newCount;
            terminal.characterAttributes(previousStyle);
            terminal.cursorPosition(cursorColumn + 1, Math.min(height - 1, Math.max(0, cursorRow)) + 1);
            return Result.WRITTEN;
        } finally {
            buffer.unlock();
        }
    }

    private void writeRows(int startRow, List<AgentInlineBlockLayout.Line> lines, Palette palette) {
        TextStyle previousStyle = styleState.getCurrent();
        int width = buffer.getWidth();
        for (int i = 0; i < lines.size(); i++) {
            terminal.cursorPosition(1, startRow + i + 1);
            // Erase with the terminal's own colours, not whatever the shell left set.
            terminal.characterAttributes(TextStyle.EMPTY);
            terminal.eraseInLine(2);
            int written = 0;
            for (AgentInlineBlockLayout.Segment segment : lines.get(i).segments()) {
                String text = segment.text();
                if (text.isEmpty() || written >= width - 1) {
                    continue;
                }
                if (written + text.length() > width - 1) {
                    text = text.substring(0, width - 1 - written);
                }
                terminal.characterAttributes(palette.styleFor(segment.role()));
                terminal.writeCharacters(text);
                written += text.length();
            }
        }
        terminal.characterAttributes(previousStyle);
    }

    /**
     * Makes sure {@code rows} blank rows follow {@code lastRow}, scrolling the screen up into the history
     * when it is full. Returns how many rows the screen moved up.
     */
    private int makeRoomBelow(int lastRow, int rows) {
        int height = buffer.getHeight();
        int free = height - 1 - lastRow;
        if (free >= rows) {
            return 0;
        }
        int scroll = Math.min(rows - free, Math.max(0, lastRow));
        if (scroll > 0) {
            buffer.scrollArea(1, -scroll, height);
        }
        return scroll;
    }

    /** The lowest screen row holding any text, or 0 on an empty screen. */
    private int lastUsedRow() {
        for (int row = buffer.getHeight() - 1; row > 0; row--) {
            if (!buffer.getLine(row).getText().isBlank()) {
                return row;
            }
        }
        return 0;
    }
}
