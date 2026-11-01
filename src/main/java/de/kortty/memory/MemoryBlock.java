package de.kortty.memory;

import java.util.List;

/**
 * The memory text for one request, already budgeted and escaped.
 *
 * @param text               the rendered block, empty when nothing fits
 * @param tokens             the block's size in tokens under the tokenizer it was built with
 * @param includedEntryIds   the ids of the korTTY entries in the block, for the "sent" markers
 * @param omittedCount       how many entries or paragraphs did not fit the budget
 */
public record MemoryBlock(String text, int tokens, List<String> includedEntryIds, int omittedCount) {

    public MemoryBlock {
        text = text == null ? "" : text;
        includedEntryIds = List.copyOf(includedEntryIds);
    }

    public static MemoryBlock empty() {
        return new MemoryBlock("", 0, List.of(), 0);
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }
}
