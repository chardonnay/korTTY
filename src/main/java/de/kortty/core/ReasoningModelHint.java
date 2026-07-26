package de.kortty.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Recognises models that think out loud, because translating with one is startlingly expensive.
 *
 * <p>Measured on this project's own guide: a reasoning model emitted about 4.4 times more tokens
 * than it was given — roughly 4,400 completion tokens to translate 1,000 characters — nearly all
 * of it deliberation that contributes nothing to a translation. That is the difference between a
 * run of about an hour and one of six or more, on identical hardware at an identical token rate.
 * It is worth one sentence of warning before someone commits a night to it.
 *
 * <p>A name-based heuristic on purpose. The alternative — send a prompt and measure the answer —
 * costs a full generation on the very models this is meant to warn about, which is the expense
 * being avoided. Names are what the model publishers themselves use to advertise the capability,
 * so a false negative simply means no warning, and the run still works.
 */
public final class ReasoningModelHint {

    /**
     * Fragments that appear in the identifiers of reasoning models. Matched against a normalised
     * id with separators collapsed, so "Phi-4-mini-reasoning" and "phi_4_mini_reasoning" both hit.
     */
    private static final List<String> MARKERS = List.of(
        "reasoning", "reasoner", "thinking", "think", "cot", "chain-of-thought",
        "r1", "qwq", "gpt-oss", "o1", "openthinker");

    private static final Pattern SEPARATORS = Pattern.compile("[^a-z0-9]+");

    private ReasoningModelHint() {
    }

    /** True when {@code modelId} advertises reasoning in its name. */
    public static boolean likelyReasoningModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        // Wrapped in separators so a marker can match at either end without special cases, and
        // so "think" cannot fire inside an unrelated word.
        String normalised = "-" + SEPARATORS.matcher(modelId.toLowerCase(Locale.ROOT))
            .replaceAll("-") + "-";
        // Every marker must be a whole token, delimited on both sides. Allowing a marker to match
        // as a prefix flagged "r10-model" as a DeepSeek-R1 variant.
        for (String marker : MARKERS) {
            if (normalised.contains("-" + marker + "-")) {
                return true;
            }
        }
        return false;
    }

    /** True when the profile's embedded model advertises reasoning. */
    public static boolean likelyReasoningModel(de.kortty.model.AiProfile profile) {
        if (profile == null) {
            return false;
        }
        return likelyReasoningModel(profile.getEmbeddedModelId())
            || likelyReasoningModel(profile.getModel());
    }
}
