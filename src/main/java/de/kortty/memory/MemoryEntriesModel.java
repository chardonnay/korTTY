package de.kortty.memory;

import de.kortty.memory.MemoryBlock.SentPart;
import de.kortty.memory.MemoryBlockBuilder.Paragraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rows of the Memory tab's entries view: one row per user paragraph and per intact korTTY entry of one memory
 * file, each with the flag whether the block of that scope sends it. UI-free, the pane only renders the rows.
 */
public final class MemoryEntriesModel {

    /** Whose text a row shows. */
    public enum Kind {
        /** A paragraph the user wrote. */
        USER_TEXT,
        /** An intact korTTY entry. */
        KORTTY_ENTRY
    }

    /**
     * One row of the entries view.
     *
     * @param kind       whose text the row shows
     * @param text       the paragraph, or the body of a korTTY entry
     * @param entryId    the korTTY entry's id, or null for user text
     * @param created    the korTTY entry's creation date as yyyy-MM-dd, or null for user text
     * @param source     the korTTY entry's source, such as chat or agent, or null for user text
     * @param occurrence how many earlier user paragraphs have the same text, to tell identical paragraphs apart
     * @param position   the paragraph's position in the file, see {@link MemoryBlockBuilder#documentParagraphs}
     * @param sent       whether the block of this scope carries the row
     */
    public record Row(Kind kind, String text, String entryId, String created, String source, int occurrence,
                      int position, boolean sent) {

        public boolean isUserText() {
            return kind == Kind.USER_TEXT;
        }
    }

    private MemoryEntriesModel() {
    }

    /**
     * The rows of one memory file in file order.
     *
     * @param documentText the file's text
     * @param block        the block built for the scope of this file; its sent parts mark the rows
     * @param connection   true when the file is a connection file, false for the global file
     */
    public static List<Row> rows(String documentText, MemoryBlock block, boolean connection) {
        List<SentPart> sent = block == null ? List.of() : block.sentParts();
        List<Row> rows = new ArrayList<>();
        Map<String, Integer> seenText = new HashMap<>();
        for (Paragraph paragraph : MemoryBlockBuilder.documentParagraphs(documentText)) {
            if (paragraph.korttyEntry()) {
                boolean isSent = sent.contains(new SentPart(connection, paragraph.position(), paragraph.id()));
                rows.add(new Row(Kind.KORTTY_ENTRY, paragraph.text(), paragraph.id(), paragraph.created(),
                    paragraph.source(), 0, paragraph.position(), isSent));
            } else {
                int occurrence = seenText.merge(paragraph.text(), 1, Integer::sum) - 1;
                boolean isSent = sent.contains(new SentPart(connection, paragraph.position(), null));
                rows.add(new Row(Kind.USER_TEXT, paragraph.text(), null, null, null, occurrence,
                    paragraph.position(), isSent));
            }
        }
        return rows;
    }
}
