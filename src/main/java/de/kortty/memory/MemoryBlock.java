package de.kortty.memory;

import de.kortty.core.AiTokenCounter;
import de.kortty.model.AiTokenizerType;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The memory text for one request, already budgeted and escaped.
 *
 * @param text               the rendered block, empty when nothing fits
 * @param tokens             the block's size in tokens under the tokenizer it was built with
 * @param includedEntryIds   the ids of the korTTY entries in the block, for the "sent" markers
 * @param sentParts          the user paragraphs and korTTY entries of the global and connection files in the block
 * @param omittedCount       how many entries or paragraphs did not fit the budget
 * @param sentPieces         every part of the block with the text it carries, in rendering order, for the view that
 *                           shows what was sent
 */
public record MemoryBlock(String text, int tokens, List<String> includedEntryIds, List<SentPart> sentParts,
                          int omittedCount, List<SentPiece> sentPieces) {

    /**
     * A user paragraph or a korTTY entry of a memory file that the block carries.
     *
     * @param connection true for the connection file, false for the global file
     * @param position   the paragraph's position in that file, numbered as {@link MemoryBlockBuilder#documentParagraphs}
     * @param entryId    the id of a korTTY entry, or null for user text
     */
    public record SentPart(boolean connection, int position, String entryId) {
    }

    /** Where a part of the block comes from: the organisation, the global or connection file, or an external file. */
    public enum Origin {
        ORGANIZATION, GLOBAL, CONNECTION, EXTERNAL
    }

    /**
     * One part of the block with the text it sends: an organisation rule, a user paragraph or korTTY entry of the global
     * or connection file, or one external reference file.
     *
     * @param origin    where the part comes from
     * @param position  the paragraph's position in its file, or the rule's position among the organisation rules;
     *                  0 for an external file
     * @param entryId   the id of a korTTY entry, or null
     * @param label     the label of an external file, or null
     * @param text      the text as the block renders it; for an external file, all of its paragraphs that were sent
     * @param tokens    the text's size in tokens
     * @param truncated for an external file: whether the file was shortened or some of its paragraphs did not fit
     */
    public record SentPiece(Origin origin, int position, String entryId, String label, String text, int tokens,
                            boolean truncated) {

        public SentPiece {
            text = text == null ? "" : text;
        }

        /** This part with its text and label passed through {@code mask}, and its tokens counted again. */
        SentPiece masked(UnaryOperator<String> mask, AiTokenizerType tokenizer) {
            String maskedText = mask.apply(text);
            return new SentPiece(origin, position, entryId, label == null ? null : mask.apply(label), maskedText,
                AiTokenCounter.countTextTokens(maskedText, tokenizer), truncated);
        }
    }

    public MemoryBlock {
        text = text == null ? "" : text;
        includedEntryIds = List.copyOf(includedEntryIds);
        sentParts = List.copyOf(sentParts);
        sentPieces = sentPieces == null ? List.of() : List.copyOf(sentPieces);
    }

    /** A block without reported pieces, as before the pieces were reported. */
    public MemoryBlock(String text, int tokens, List<String> includedEntryIds, List<SentPart> sentParts,
                       int omittedCount) {
        this(text, tokens, includedEntryIds, sentParts, omittedCount, List.of());
    }

    /** A block without reported parts, as before the parts were reported. */
    public MemoryBlock(String text, int tokens, List<String> includedEntryIds, int omittedCount) {
        this(text, tokens, includedEntryIds, List.of(), omittedCount);
    }

    public static MemoryBlock empty() {
        return new MemoryBlock("", 0, List.of(), List.of(), 0, List.of());
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }

    /**
     * A copy whose text and the text of every part pass through {@code mask}, with the token counts taken again under
     * {@code tokenizer}. The copy keeps the entry ids, the reported parts and the omitted count, so the masked block
     * still says which entries it carries.
     */
    public MemoryBlock masked(UnaryOperator<String> mask, AiTokenizerType tokenizer) {
        String maskedText = mask.apply(text);
        return new MemoryBlock(maskedText, AiTokenCounter.countTextTokens(maskedText, tokenizer), includedEntryIds,
            sentParts, omittedCount, sentPieces.stream().map(piece -> piece.masked(mask, tokenizer)).toList());
    }
}
