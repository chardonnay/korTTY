package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.AiReasoningEffort;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiModelSelectionMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
        return normalize(profile.getReasoningEffort(), availableEfforts(profile));
    }

    /**
     * Returns the request-scoped profile used for an AI action. Mermaid generation and the two
     * post-analysis full-script apply actions have strict, machine-parsed contracts and do not
     * benefit from spending their bounded completion budget on a hidden chain-of-thought, so they
     * explicitly request {@link AiReasoningEffort#NONE} when that value is available for the
     * profile. Profiles without an explicit-off value keep the configured effort instead of
     * receiving an unsupported override. The stored profile is never mutated.
     */
    public static AiProfile profileForAction(AiProfile profile, AiAction action) {
        if (profile == null || !prefersExplicitReasoningOff(action)) {
            return profile;
        }
        // AUTO can switch to another loaded LM Studio model without changing the persisted profile
        // key. Do not force a capability discovered for an earlier model; an explicitly selected
        // NONE value still passes through unchanged below because it is already the user setting.
        if (profile.getModelSelectionMode() == AiModelSelectionMode.AUTO
            && profile.getReasoningEffort() != AiReasoningEffort.NONE) {
            return profile;
        }
        if (!availableEfforts(profile).contains(AiReasoningEffort.NONE)
            || profile.getReasoningEffort() == AiReasoningEffort.NONE) {
            return profile;
        }
        AiProfile copy = new AiProfile(profile);
        copy.setReasoningEffort(AiReasoningEffort.NONE);
        return copy;
    }

    private static boolean prefersExplicitReasoningOff(AiAction action) {
        return action == AiAction.GENERATE_SNIPPET_MERMAID
            || action == AiAction.APPLY_SNIPPET_IMPROVEMENTS
            || action == AiAction.APPLY_SNIPPET_SECURITY_FIXES;
    }

    public static List<AiReasoningEffort> availableEfforts(AiProfile profile) {
        if (profile == null) {
            return DISABLED_ONLY;
        }
        List<AiReasoningEffort> discoveredEfforts = discoveredEfforts(profile);
        if (!discoveredEfforts.isEmpty()) {
            return discoveredEfforts;
        }
        if (profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI) {
            return AiCliProviderRegistry.availableReasoningEfforts(profile.getCliProviderId(), profile.getModel());
        }
        return availableEfforts(profile.getApiUrl(), profile.getModel());
    }

    public static String discoveryKey(AiProfile profile) {
        if (profile == null) {
            return "";
        }
        AiConnectionMode connectionMode = profile.getConnectionMode();
        AiModelSelectionMode modelSelectionMode = profile.getModelSelectionMode();
        return String.join("|",
            normalize(connectionMode != null ? connectionMode.name() : ""),
            normalize(profile.getApiUrl()),
            normalize(modelSelectionMode != null ? modelSelectionMode.name() : ""),
            normalize(profile.getModel()),
            normalize(profile.getCliProviderId()),
            normalize(profile.getCliExecutablePath()),
            normalize(profile.getCliArgumentsTemplate()));
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

    private static List<AiReasoningEffort> discoveredEfforts(AiProfile profile) {
        AiConnectionMode connectionMode = profile.getConnectionMode();
        if (connectionMode != null && connectionMode.isEmbedded()) {
            // An embedded profile is pinned to one embedded model, so its discovered efforts stay valid
            // as long as a discovery ran. The connection key that HTTP/CLI profiles rely on is
            // meaningless here: an embedded profile's apiUrl/model/CLI fields are unused placeholders
            // whose stray values flip the key between save and reload and would otherwise silently
            // reset the user's chosen reasoning level. The embedded model itself never changes without
            // a fresh discovery.
            String discoveryKey = profile.getReasoningDiscoveryKey();
            return discoveryKey != null && !discoveryKey.isBlank()
                ? normalizeOptions(profile.getDiscoveredReasoningEfforts())
                : List.of();
        }
        if (!Objects.equals(profile.getReasoningDiscoveryKey(), discoveryKey(profile))) {
            return List.of();
        }
        return normalizeOptions(profile.getDiscoveredReasoningEfforts());
    }

    public static List<AiReasoningEffort> normalizeOptions(List<AiReasoningEffort> efforts) {
        List<AiReasoningEffort> normalized = new ArrayList<>();
        normalized.add(AiReasoningEffort.DISABLED);
        if (efforts != null) {
            for (AiReasoningEffort effort : efforts) {
                if (effort != null && !normalized.contains(effort)) {
                    normalized.add(effort);
                }
            }
        }
        return normalized;
    }

    private static boolean isOSeriesReasoningModel(String normalizedModel) {
        return normalizedModel.startsWith("o1")
            || normalizedModel.startsWith("o3")
            || normalizedModel.startsWith("o4");
    }

    private static String normalizeModel(String model) {
        return model != null ? model.trim().toLowerCase(Locale.ROOT) : "";
    }

    private static String normalize(String value) {
        return value != null ? value.trim() : "";
    }
}
