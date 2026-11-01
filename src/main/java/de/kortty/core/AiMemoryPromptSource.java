package de.kortty.core;

import de.kortty.memory.MemoryBlock;
import de.kortty.memory.MemoryService;
import de.kortty.model.AiProfile;
import de.kortty.model.AiTokenizerType;

/**
 * The memory of one prompt: the global memory and, when a connection id is given, that connection's
 * memory, budgeted for the profile's tokenizer and masked for the profile.
 *
 * <p>Nothing is read at construction. {@link #block()} runs each time a prompt is built, so an edit to
 * a memory file reaches the next request without rebuilding the AI service.</p>
 */
public final class AiMemoryPromptSource {

    private final String connectionId;
    private final AiProfile profile;
    private final int budgetTokens;
    private final boolean cachedOnly;

    /**
     * @param connectionId the connection whose memory joins the global one, or {@code null} for the
     *                     global memory alone
     * @param profile      the profile the prompt is sent to; its tokenizer counts the budget and its
     *                     masking rules apply to the block
     * @param budgetTokens the memory budget of one request
     */
    public AiMemoryPromptSource(String connectionId, AiProfile profile, int budgetTokens) {
        this(connectionId, profile, budgetTokens, false);
    }

    private AiMemoryPromptSource(String connectionId, AiProfile profile, int budgetTokens, boolean cachedOnly) {
        this.connectionId = connectionId;
        this.profile = profile;
        this.budgetTokens = budgetTokens;
        this.cachedOnly = cachedOnly;
    }

    /**
     * The memory of a token preview, which runs on the UI thread: the block last computed in the background (see
     * {@link MemoryService#cachedBlockFor}), so the preview reads no file.
     */
    public static AiMemoryPromptSource preview(String connectionId, AiProfile profile, int budgetTokens) {
        return new AiMemoryPromptSource(connectionId, profile, budgetTokens, true);
    }

    /** The masked, budgeted memory for one request; empty when memory is off. */
    public MemoryBlock block() {
        AiTokenizerType tokenizer = tokenizer();
        MemoryService service = MemoryService.get();
        MemoryBlock raw = cachedOnly
            ? service.cachedBlockFor(connectionId, tokenizer, budgetTokens)
            : service.blockFor(connectionId, tokenizer, budgetTokens);
        if (raw.isEmpty()) {
            return raw;
        }
        String masked = AiOutboundRedaction.redactFor(profile, raw.text(), null).text();
        if (masked.equals(raw.text())) {
            return raw;
        }
        return new MemoryBlock(masked, AiTokenCounter.countTextTokens(masked, tokenizer),
            raw.includedEntryIds(), raw.omittedCount());
    }

    private AiTokenizerType tokenizer() {
        return profile != null && profile.getTokenizerType() != null
            ? profile.getTokenizerType()
            : AiTokenizerType.ESTIMATE;
    }
}
