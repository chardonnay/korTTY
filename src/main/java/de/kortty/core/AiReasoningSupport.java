package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.AiReasoningEffort;

import java.util.List;
import java.util.Locale;

/**
 * Determines the reasoning effort options KorTTY can safely expose for a profile.
 */
public final class AiReasoningSupport {

    private static final List<AiReasoningEffort> DISABLED_ONLY = List.of(AiReasoningEffort.DISABLED);
    private static final List<AiReasoningEffort> GPT_5_PRE_51 = List.of(
        AiReasoningEffort.DISABLED,
        AiReasoningEffort.MINIMAL,
        AiReasoningEffort.LOW,
        AiReasoningEffort.MEDIUM,
        AiReasoningEffort.HIGH);
    private static final List<AiReasoningEffort> GPT_51_PLUS = List.of(
        AiReasoningEffort.DISABLED,
        AiReasoningEffort.NONE,
        AiReasoningEffort.LOW,
        AiReasoningEffort.MEDIUM,
        AiReasoningEffort.HIGH);
    private static final List<AiReasoningEffort> GPT_52_PLUS = List.of(
        AiReasoningEffort.DISABLED,
        AiReasoningEffort.NONE,
        AiReasoningEffort.LOW,
        AiReasoningEffort.MEDIUM,
        AiReasoningEffort.HIGH,
        AiReasoningEffort.XHIGH);
    private static final List<AiReasoningEffort> HIGH_ONLY = List.of(
        AiReasoningEffort.DISABLED,
        AiReasoningEffort.HIGH);
    private static final List<AiReasoningEffort> LOW_MEDIUM_HIGH = List.of(
        AiReasoningEffort.DISABLED,
        AiReasoningEffort.LOW,
        AiReasoningEffort.MEDIUM,
        AiReasoningEffort.HIGH);

    private AiReasoningSupport() {
    }

    public static List<AiReasoningEffort> availableEfforts(String apiUrl, String model) {
        String normalizedModel = normalizeModel(model);
        if (normalizedModel.isBlank()) {
            return DISABLED_ONLY;
        }
        if (normalizedModel.startsWith("gpt-5.1-codex-max")) {
            return GPT_52_PLUS;
        }
        if (normalizedModel.startsWith("gpt-5-pro")) {
            return HIGH_ONLY;
        }
        if (normalizedModel.startsWith("gpt-5.2")
            || normalizedModel.startsWith("gpt-5.3")
            || normalizedModel.startsWith("gpt-5.4")
            || normalizedModel.startsWith("gpt-5.5")) {
            return GPT_52_PLUS;
        }
        if (normalizedModel.startsWith("gpt-5.1")) {
            return GPT_51_PLUS;
        }
        if (normalizedModel.startsWith("gpt-5")) {
            return GPT_5_PRE_51;
        }
        if (isOSeriesReasoningModel(normalizedModel) || normalizedModel.startsWith("gpt-oss-")) {
            return LOW_MEDIUM_HIGH;
        }
        return DISABLED_ONLY;
    }

    public static AiReasoningEffort normalizeForProfile(AiProfile profile) {
        if (profile == null) {
            return AiReasoningEffort.DISABLED;
        }
        return normalize(profile.getReasoningEffort(), availableEfforts(profile.getApiUrl(), profile.getModel()));
    }

    public static AiReasoningEffort normalizeForProfile(
        String apiUrl,
        String model,
        AiReasoningEffort requestedEffort) {

        return normalize(requestedEffort, availableEfforts(apiUrl, model));
    }

    public static AiReasoningEffort normalize(AiReasoningEffort requestedEffort, List<AiReasoningEffort> availableEfforts) {
        AiReasoningEffort requested = requestedEffort != null ? requestedEffort : AiReasoningEffort.DISABLED;
        List<AiReasoningEffort> safeOptions = availableEfforts != null && !availableEfforts.isEmpty()
            ? availableEfforts
            : DISABLED_ONLY;
        return safeOptions.contains(requested) ? requested : AiReasoningEffort.DISABLED;
    }

    public static String exportStatus(AiProfile profile) {
        return normalizeForProfile(profile).exportLabel();
    }

    private static boolean isOSeriesReasoningModel(String normalizedModel) {
        return normalizedModel.startsWith("o1")
            || normalizedModel.startsWith("o3")
            || normalizedModel.startsWith("o4");
    }

    private static String normalizeModel(String model) {
        return model != null ? model.trim().toLowerCase(Locale.ROOT) : "";
    }
}
