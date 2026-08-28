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

    /**
     * Bumped whenever discovery results recorded by an older korTTY can no longer be trusted. It is
     * part of the discovery key, so every key stored before the bump mismatches exactly once and its
     * efforts are ignored until the profile is discovered again — no separate migration flag, nothing
     * is deleted from the settings file, and a downgrade keeps working.
     *
     * <p>v2: results recorded before LM Studio's model metadata became authoritative came from an
     * active probe, and LM Studio never rejects an unsupported request-time reasoning value — it only
     * logs that it skipped it. A model without any reasoning capability therefore ended up with the
     * complete level list, and picking one of those levels made korTTY send a parameter the model
     * ignores. Until the profile is re-discovered, the conservative model-name defaults apply.
     *
     * <p>v3: the key mixed every connection mode's fields into one string, so an HTTP profile's
     * result was thrown away when its unused CLI provider or argument template changed, and a CLI
     * profile's result when its unused API URL changed. Both happen on their own — the editor fills
     * those fields with defaults for whichever mode is not in use. {@link #discoveryKey} now covers
     * only the fields that actually select the endpoint and model for the profile's own mode.
     */
    private static final String DISCOVERY_SCHEMA = "v3";

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

    /**
     * Identifies the endpoint-and-model combination a discovery result belongs to. Only the fields
     * that select that combination for the profile's own connection mode take part: a profile keeps
     * fields for the modes it does not use (the editor fills them with defaults), and letting those
     * into the key silently invalidated a perfectly good result.
     */
    public static String discoveryKey(AiProfile profile) {
        if (profile == null) {
            return "";
        }
        AiConnectionMode connectionMode = profile.getConnectionMode();
        AiModelSelectionMode modelSelectionMode = profile.getModelSelectionMode();
        String mode = connectionMode != null ? connectionMode.name() : "";
        if (connectionMode != null && connectionMode.isEmbedded()) {
            // An embedded profile is pinned to one bundled model; its apiUrl/model/CLI fields are
            // unused placeholders.
            return String.join("|", DISCOVERY_SCHEMA, mode, normalize(profile.getEmbeddedModelId()));
        }
        if (connectionMode == AiConnectionMode.LOCAL_CLI) {
            return String.join("|",
                DISCOVERY_SCHEMA,
                mode,
                normalize(modelSelectionMode != null ? modelSelectionMode.name() : ""),
                normalize(profile.getModel()),
                normalize(profile.getCliProviderId()),
                normalize(profile.getCliExecutablePath()),
                normalize(profile.getCliArgumentsTemplate()));
        }
        return String.join("|",
            DISCOVERY_SCHEMA,
            mode,
            normalize(profile.getApiUrl()),
            normalize(modelSelectionMode != null ? modelSelectionMode.name() : ""),
            normalize(profile.getModel()));
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
            // An embedded profile is pinned to one embedded model, so its discovered efforts stay
            // valid as long as a discovery ran at all. Any recorded key counts, including the
            // pre-v3 keys built from the unused apiUrl/model/CLI placeholders: comparing those
            // flipped between save and reload and silently reset the user's chosen level, and
            // re-checking them now would only throw away results that are still correct.
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
