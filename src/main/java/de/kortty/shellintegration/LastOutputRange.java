package de.kortty.shellintegration;

import de.kortty.shellintegration.CommandBlockStore.CommandBlock;

import java.util.Objects;
import java.util.Optional;

/**
 * What a finished command printed, as a range of its pane's text: from where {@code OSC 133;C}
 * marked the start of the output up to where {@code OSC 133;D} marked its end.
 *
 * <p>Lines are absolute (0 is the oldest scrollback line, as in {@link CommandBlockStore}) and
 * columns 0-based. The end is <em>exclusive</em>, as {@code SelectionUtil.getSelectedText} reads it:
 * the range holds the text before ({@link #endColumn}, {@link #endLine}). SithTermFX's own
 * selections keep their end inclusive; {@link #lastColumn} is the column to give them.
 *
 * <ul>
 *   <li>The output usually ends with a line break, so D stands at the start of the next line. The
 *       range then ends after the last character of the line above, and empty lines at the end are
 *       left out too: the range always ends right after a character, and its text never ends with a
 *       line break.</li>
 *   <li>Output without a line break at its end (such as {@code printf foo}) ends where D stands,
 *       before the next prompt on the same line.</li>
 *   <li>When C stands after the text of its line (a shell that marks the output before the line
 *       break that ends the command line), the range starts on the next line.</li>
 *   <li>When the start of the output has already left the scrollback, the range starts at the
 *       oldest line that is left and is {@link #truncated}.</li>
 * </ul>
 *
 * @param startLine   absolute line of the first character
 * @param startColumn column of the first character
 * @param endLine     absolute line of the exclusive end
 * @param endColumn   column of the exclusive end; always above 0, and above {@code startColumn} on
 *                    the start line
 * @param truncated   whether the start of the output had already left the scrollback
 */
public record LastOutputRange(int startLine, int startColumn, int endLine, int endColumn, boolean truncated) {

    /** What the range needs to know about the pane's lines; read under the buffer lock. */
    public interface Lines {
        /** Lines of scrollback and screen together; absolute lines run from 0 to this minus 1. */
        int count();

        /** The number of characters of text on {@code absoluteLine}, without the empty cells after it. */
        int length(int absoluteLine);
    }

    public LastOutputRange {
        if (startLine < 0 || startColumn < 0 || endLine < startLine || endColumn <= 0
                || (endLine == startLine && endColumn <= startColumn)) {
            throw new IllegalArgumentException("Not a range with text: " + startLine + ':' + startColumn
                + " to " + endLine + ':' + endColumn);
        }
    }

    /**
     * The output of {@code block} as the pane's lines stand now, or empty when the command printed
     * no text, its output left the scrollback entirely, or the block did not finish (see
     * {@link CommandBlock#finished()}).
     *
     * @param block   the command
     * @param trimmed {@link CommandBlockStore#trimmed()}, with the scrollback's trims applied
     * @param lines   the pane's lines
     */
    public static Optional<LastOutputRange> of(CommandBlock block, long trimmed, Lines lines) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(lines, "lines");
        CommandBlockStore.Mark output = block.output();
        CommandBlockStore.Mark end = block.end();
        int count = lines.count();
        if (output == null || end == null || count <= 0) {
            return Optional.empty();
        }
        long endAbsolute = end.line() - trimmed;
        if (endAbsolute < 0) {
            return Optional.empty();
        }

        long startAbsolute = output.line() - trimmed;
        boolean truncated = startAbsolute < 0;
        int startLine;
        int startColumn;
        if (truncated) {
            startLine = 0;
            startColumn = 0;
        } else {
            startLine = (int) Math.min(startAbsolute, count - 1L);
            startColumn = Math.max(0, output.column());
        }

        int endLine;
        int endColumn;
        if (endAbsolute >= count) {
            // The pane got shorter since D (a resize): end with the last line there is.
            endLine = count - 1;
            endColumn = lines.length(endLine);
        } else {
            endLine = (int) endAbsolute;
            endColumn = Math.min(Math.max(0, end.column()), lines.length(endLine));
        }

        if (startColumn > 0 && startColumn >= lines.length(startLine) && startLine < endLine) {
            // C after the text of its line: the output begins below it, not with a line break.
            startLine++;
            startColumn = 0;
        }
        // D at the start of a line, or after empty lines: end after the last character above.
        while (endColumn == 0 && endLine > startLine) {
            endLine--;
            endColumn = lines.length(endLine);
        }
        if (endLine < startLine || (endLine == startLine && endColumn <= startColumn)) {
            return Optional.empty();
        }
        return Optional.of(new LastOutputRange(startLine, startColumn, endLine, endColumn, truncated));
    }

    /** The column of the last character: the inclusive end that SithTermFX's selections use. */
    public int lastColumn() {
        return endColumn - 1;
    }
}
