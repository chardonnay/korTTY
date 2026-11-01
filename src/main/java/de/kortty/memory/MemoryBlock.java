package de.kortty.memory;

import java.util.List;

/**
 * The memory text for one request, already budgeted and escaped.
 *
 * @param text               the rendered block, empty when nothing fits
 * @param tokens             the block's size in tokens under the tokenizer it was built with
 * @param includedEntryIds   the ids of the korTTY entries in the block, for the "sent" markers
 * @param sentParts          the user paragraphs and korTTY entries of the global and connection files in the block
 * @param omittedCount       how many entries or paragraphs did not fit the budget
 */
public record MemoryBlock(String text, int tokens, List<String> includedEntryIds, List<SentPart> sentParts,
                          int omittedCount) {

    /**
     * A user paragraph or a korTTY entry of a memory file that the block carries.
     *
     * @param connection true for the connection file, false for the global file
     * @param position   the paragraph's position in that file, numbered as {@link MemoryBlockBuilder#documentParagraphs}
     * @param entryId    the id of a korTTY entry, or null for user text
     */
    public record SentPart(boolean connection, int position, String entryId) {
    }

    public MemoryBlock {
        text = text == null ? "" : text;
        includedEntryIds = List.copyOf(includedEntryIds);
        sentParts = List.copyOf(sentParts);
    }

    /** A block without reported parts, as before the parts were reported. */
    public MemoryBlock(String text, int tokens, List<String> includedEntryIds, int omittedCount) {
        this(text, tokens, includedEntryIds, List.of(), omittedCount);
    }

    public static MemoryBlock empty() {
        return new MemoryBlock("", 0, List.of(), List.of(), 0);
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }
}
