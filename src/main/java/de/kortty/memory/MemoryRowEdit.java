package de.kortty.memory;

import de.kortty.memory.MemoryDocument.KorttyEntry;
import de.kortty.memory.MemoryDocument.Segment;
import de.kortty.memory.MemoryDocument.UserText;

/**
 * Edits one row of the entries view and returns the new file text. Pure: nothing is written here, the caller saves
 * the result through {@link MemoryUserActions} with the digest it loaded. A row that can no longer be found where
 * the view showed it is refused with {@link RowNotFoundException}; the file is never guessed at.
 *
 * <p>Editing a korTTY entry replaces its fence with the text, so the entry becomes the user's own text, as the
 * format intends.</p>
 */
public final class MemoryRowEdit {

    /** The row is not in the text any more: the file changed, or the paragraph is split by a stray fence marker. */
    public static final class RowNotFoundException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        public RowNotFoundException() {
            super("The entry is no longer where it was shown; reload the memory file.");
        }
    }

    private MemoryRowEdit() {
    }

    /** The file with the user paragraph of {@code row} replaced by {@code replacement}. */
    public static String replaceUserParagraph(String text, MemoryEntriesModel.Row row, String replacement) {
        int[] range = locateUserParagraph(text, row);
        String clean = replacement == null ? "" : replacement.strip();
        return text.substring(0, range[0]) + clean + text.substring(range[1]);
    }

    /** The file without the user paragraph of {@code row}, and the blank lines that separated it. */
    public static String deleteUserParagraph(String text, MemoryEntriesModel.Row row) {
        int[] range = locateUserParagraph(text, row);
        return cut(text, range[0], range[1]);
    }

    /** The file with the korTTY entry {@code entryId} replaced by {@code body} as the user's own text. */
    public static String replaceKorttyEntry(String text, String entryId, String body) {
        int[] range = locateEntry(text, entryId);
        String clean = body == null ? "" : body.strip();
        return text.substring(0, range[0]) + (clean.isEmpty() ? "" : clean + "\n") + text.substring(range[1]);
    }

    /** The file without the korTTY entry {@code entryId}. */
    public static String deleteKorttyEntry(String text, String entryId) {
        int[] range = locateEntry(text, entryId);
        return cut(text, range[0], range[1]);
    }

    /** The character range of the user paragraph, found as the {@code occurrence}-th whole paragraph with its text. */
    private static int[] locateUserParagraph(String text, MemoryEntriesModel.Row row) {
        String paragraph = row.text();
        if (!row.isUserText() || paragraph == null || paragraph.isEmpty()) {
            throw new RowNotFoundException();
        }
        int seen = 0;
        int offset = 0;
        for (Segment segment : MemoryFormat.parse(text).segments()) {
            if (segment instanceof UserText user) {
                String raw = user.text();
                for (int at = raw.indexOf(paragraph); at >= 0; at = raw.indexOf(paragraph, at + 1)) {
                    int end = at + paragraph.length();
                    if (startsParagraph(raw, at) && endsParagraph(raw, end)) {
                        if (seen == row.occurrence()) {
                            return new int[] {offset + at, offset + end};
                        }
                        seen++;
                    }
                }
            }
            offset += rawLength(segment);
        }
        throw new RowNotFoundException();
    }

    private static int[] locateEntry(String text, String entryId) {
        int offset = 0;
        for (Segment segment : MemoryFormat.parse(text).segments()) {
            if (segment instanceof KorttyEntry entry && entry.id().equals(entryId)) {
                return new int[] {offset, offset + entry.raw().length()};
            }
            offset += rawLength(segment);
        }
        throw new RowNotFoundException();
    }

    private static int rawLength(Segment segment) {
        return segment instanceof UserText user ? user.text().length() : ((KorttyEntry) segment).raw().length();
    }

    /** Whether {@code at} begins a paragraph: the start of the text, or a line that follows a blank line. */
    private static boolean startsParagraph(String raw, int at) {
        int before = skipBackwardSpaces(raw, at - 1);
        if (before < 0) {
            return true;
        }
        if (raw.charAt(before) != '\n') {
            return false;
        }
        int line = before - 1;
        if (line >= 0 && raw.charAt(line) == '\r') {
            line--;
        }
        int previous = skipBackwardSpaces(raw, line);
        return previous < 0 || raw.charAt(previous) == '\n';
    }

    /** Whether {@code end} ends a paragraph: the end of the text, or a line break followed by a blank line or the end. */
    private static boolean endsParagraph(String raw, int end) {
        int after = skipForwardSpaces(raw, end);
        if (after >= raw.length()) {
            return true;
        }
        if (raw.charAt(after) == '\r') {
            after++;
        }
        if (after >= raw.length()) {
            return true;
        }
        if (raw.charAt(after) != '\n') {
            return false;
        }
        int next = skipForwardSpaces(raw, after + 1);
        return next >= raw.length() || raw.charAt(next) == '\n' || raw.charAt(next) == '\r';
    }

    /**
     * Removes {@code [start, end)} together with the line break that ends its last line and the blank lines after it. When
     * nothing follows, the blank lines before it go instead, and the text keeps one line break at its end if it had one.
     */
    private static String cut(String text, int start, int end) {
        int tail = end;
        int after = skipForwardSpaces(text, end);
        if (after < text.length() && text.charAt(after) == '\r') {
            after++;
        }
        if (after >= text.length()) {
            tail = text.length();
        } else if (text.charAt(after) == '\n') {
            tail = after + 1;
            int blank = blankLineEnd(text, tail);
            while (blank >= 0) {
                tail = blank;
                blank = blankLineEnd(text, tail);
            }
        }
        if (tail < text.length()) {
            return text.substring(0, start) + text.substring(tail);
        }
        int from = start;
        while (from > 0 && Character.isWhitespace(text.charAt(from - 1))) {
            from--;
        }
        return text.substring(0, from) + (from > 0 && text.endsWith("\n") ? "\n" : "");
    }

    /** The index after the line starting at {@code index} when that line is blank, otherwise -1. */
    private static int blankLineEnd(String text, int index) {
        int position = skipForwardSpaces(text, index);
        if (position < text.length() && text.charAt(position) == '\r') {
            position++;
        }
        return position < text.length() && text.charAt(position) == '\n' ? position + 1 : -1;
    }

    private static int skipForwardSpaces(String text, int index) {
        int position = index;
        while (position < text.length() && (text.charAt(position) == ' ' || text.charAt(position) == '\t')) {
            position++;
        }
        return position;
    }

    /** The index of the last character at or before {@code index} that is not a space or a tab, or -1. */
    private static int skipBackwardSpaces(String text, int index) {
        int position = index;
        while (position >= 0 && (text.charAt(position) == ' ' || text.charAt(position) == '\t')) {
            position--;
        }
        return position;
    }
}
