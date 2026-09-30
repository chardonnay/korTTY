package de.kortty.core;

import de.kortty.KorTTYApplication;
import de.kortty.model.AiProfile;
import de.kortty.model.AiTokenizerType;

/**
 * Books AI token usage against an {@link AiProfile}'s quota. Every AI call korTTY makes on the
 * user's behalf should end up here — interactive answers, the terminal agent, swarm agents and
 * the swarm aggregation, and every session journal call — so the quota bars show what was
 * really spent.
 */
public interface AiUsageRecorder {

    /** Recorder that drops everything; for tests and for code running without an application. */
    AiUsageRecorder NOOP = (profile, usage) -> { };

    /**
     * Adds {@code usage} to the stored profile with the same id as {@code profile}. The argument
     * may be a detached copy; implementations resolve the live profile themselves.
     */
    void record(AiProfile profile, AiTokenUsage usage);

    /** The running application's recorder, or {@link #NOOP} outside the application. */
    static AiUsageRecorder application() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        AiUsageRecorder recorder = app != null ? app.getAiUsageRecorder() : null;
        return recorder != null ? recorder : NOOP;
    }

    /**
     * The usage the provider reported, or — when it reported none — an estimate from the prompt
     * and answer text with the profile's tokenizer. Never null.
     */
    static AiTokenUsage usageOrEstimate(
            AiExecutionResult result, String systemPrompt, String userPrompt, AiProfile profile) {
        if (result != null && result.usage() != null) {
            return result.usage();
        }
        AiTokenizerType tokenizer = profile != null && profile.getTokenizerType() != null
            ? profile.getTokenizerType()
            : AiTokenizerType.ESTIMATE;
        long prompt = AiTokenCounter.countTextTokens(systemPrompt != null ? systemPrompt : "", tokenizer)
            + AiTokenCounter.countTextTokens(userPrompt != null ? userPrompt : "", tokenizer);
        long completion = AiTokenCounter.countTextTokens(
            result != null && result.content() != null ? result.content() : "", tokenizer);
        return new AiTokenUsage(prompt, completion, prompt + completion);
    }

    /** {@code result} with {@code usage} filled in; returns the same instance when it already has one. */
    static AiExecutionResult withUsage(AiExecutionResult result, AiTokenUsage usage) {
        if (result == null || result.usage() != null || usage == null) {
            return result;
        }
        return new AiExecutionResult(
            result.content(),
            usage,
            result.reasoning(),
            result.outputTruncated(),
            result.streamInterrupted(),
            result.webToolCalls());
    }
}
