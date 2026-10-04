package de.kortty.core;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Output-token limits for the native Anthropic Messages API ({@link AnthropicAiService}).
 *
 * <p>The per-model caps are Anthropic's documented synchronous Messages API limits ("Max output"
 * on the models overview, checked 2026-10-04). They are keyed by model-id prefix, so a dated
 * snapshot such as {@code claude-haiku-4-5-20251001} or a newer point release in the same family
 * resolves to its family's limit. Models that are not listed — older generations, and future ones
 * korTTY does not know yet — fall back to {@link #UNKNOWN_MODEL_MAX_TOKENS}, the limit korTTY sent
 * to every model before.
 *
 * <p>korTTY talks to Anthropic without streaming. A buffered request sends nothing over the wire
 * until the whole answer exists, so Anthropic's guidance is to stream anything above roughly
 * 16 000 output tokens; larger non-streaming requests risk being cut by HTTP idle timeouts. Every
 * request is therefore held to {@link #NON_STREAMING_MAX_TOKENS}, whatever the model could
 * produce, until {@link AnthropicAiService} streams.
 */
public final class AnthropicModelLimits {

    /** Ceiling for one buffered (non-streaming) Messages API request, per Anthropic's guidance. */
    public static final int NON_STREAMING_MAX_TOKENS = 16_000;
    /** Output limit for a model this table does not know. */
    public static final int UNKNOWN_MODEL_MAX_TOKENS = 4_096;
    /**
     * Rough generation time korTTY allows for: one hour per 128 000 output tokens (about 35 tokens
     * per second). A user-configured request timeout shorter than this is raised to it.
     */
    private static final long SECONDS_PER_128K_TOKENS = 3_600L;

    private record Limit(String prefix, int maxOutputTokens) {
    }

    /** Longest prefixes first, so a more specific family wins over a shorter one. */
    private static final List<Limit> LIMITS = List.of(
        new Limit("claude-sonnet-4-6", 128_000),
        new Limit("claude-sonnet-4-5", 64_000),
        new Limit("claude-haiku-4-5", 64_000),
        new Limit("claude-opus-4-8", 128_000),
        new Limit("claude-opus-4-7", 128_000),
        new Limit("claude-opus-4-6", 128_000),
        new Limit("claude-opus-4-5", 64_000),
        new Limit("claude-mythos-5", 128_000),
        new Limit("claude-sonnet-5", 128_000),
        new Limit("claude-fable-5", 128_000),
        new Limit("claude-opus-5", 128_000));

    private AnthropicModelLimits() {
    }

    /**
     * @return the documented output limit of {@code model}, or {@code null} when the model is not in
     *     the table
     */
    public static Integer knownMaxOutputTokens(String model) {
        if (model == null) {
            return null;
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        // Tolerate a provider prefix such as "anthropic." or "us.anthropic." in front of the id.
        int claude = normalized.indexOf("claude-");
        if (claude < 0) {
            return null;
        }
        normalized = normalized.substring(claude);
        for (Limit limit : LIMITS) {
            if (normalized.startsWith(limit.prefix())) {
                return limit.maxOutputTokens();
            }
        }
        return null;
    }

    /**
     * Resolves {@code max_tokens} for one request.
     *
     * <p>Every limit that applies is a ceiling, and the smallest one wins: the action's safety cap
     * (for example the Mermaid budget from {@link AiOutputTokenLimitSupport}), the profile's
     * {@code maxOutputTokens}, the model's documented limit and the non-streaming ceiling. For a model
     * the table does not know, the profile value replaces the conservative fallback — the user knows
     * their model better than korTTY does — but still never exceeds the action cap or the ceiling.
     *
     * @param request the request, or {@code null} for the prompt paths that carry no action
     * @param profileMaxOutputTokens the profile's own limit, or {@code null} for automatic
     * @param model the configured model id
     */
    public static int effectiveMaxTokens(AiRequest request, Integer profileMaxOutputTokens, String model) {
        Integer known = knownMaxOutputTokens(model);
        Integer profileLimit = profileMaxOutputTokens != null && profileMaxOutputTokens > 0
            ? profileMaxOutputTokens
            : null;
        int limit = NON_STREAMING_MAX_TOKENS;
        if (known != null) {
            limit = Math.min(limit, known);
            if (profileLimit != null) {
                limit = Math.min(limit, profileLimit);
            }
        } else {
            limit = Math.min(limit, profileLimit != null ? profileLimit : UNKNOWN_MODEL_MAX_TOKENS);
        }
        Integer actionLimit = AiOutputTokenLimitSupport.actionLimit(request);
        if (actionLimit != null) {
            limit = Math.min(limit, actionLimit);
        }
        return Math.max(1, limit);
    }

    /**
     * Raises a configured request timeout to what a budget of {@code maxTokens} needs. {@code null}
     * (no timeout) stays {@code null}: korTTY never invents a timeout of its own.
     */
    public static Duration timeoutFor(Duration configured, int maxTokens) {
        if (configured == null) {
            return null;
        }
        long seconds = Math.max(1L, SECONDS_PER_128K_TOKENS * Math.max(0, maxTokens) / 128_000L);
        Duration needed = Duration.ofSeconds(seconds);
        return configured.compareTo(needed) >= 0 ? configured : needed;
    }

    /**
     * Picks a smaller {@code max_tokens} from an HTTP 400 that names {@code max_tokens}, such as
     * "max_tokens: 16000 &gt; 8192, which is the maximum allowed number of output tokens". Returns
     * the largest number in the message that is below {@code current} (at least 1 024, which skips
     * version digits in model ids), else {@link #UNKNOWN_MODEL_MAX_TOKENS} or half of {@code current}.
     *
     * @return the clamped value, or {@code current} when the error does not concern max_tokens or
     *     no smaller value can be derived
     */
    static int clampFromError(String error, int current) {
        if (error == null || !error.toLowerCase(Locale.ROOT).contains("max_tokens")) {
            return current;
        }
        int best = -1;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(error);
        while (matcher.find()) {
            String digits = matcher.group();
            if (digits.length() > 9) {
                continue;
            }
            int value = Integer.parseInt(digits);
            if (value >= 1_024 && value < current && value > best) {
                best = value;
            }
        }
        if (best > 0) {
            return best;
        }
        if (current > UNKNOWN_MODEL_MAX_TOKENS) {
            return UNKNOWN_MODEL_MAX_TOKENS;
        }
        return current > 1 ? current / 2 : current;
    }
}
