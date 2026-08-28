package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiReasoningEffort;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Verifies which reasoning efforts a profile accepts by issuing connection-test requests.
 */
public final class AiReasoningDiscoveryService {

    private static final List<AiReasoningEffort> PROBE_CANDIDATES = List.of(
        AiReasoningEffort.NONE,
        AiReasoningEffort.MINIMAL,
        AiReasoningEffort.LOW,
        AiReasoningEffort.MEDIUM,
        AiReasoningEffort.HIGH,
        AiReasoningEffort.XHIGH);

    private AiReasoningDiscoveryService() {
    }

    /**
     * One capability discovery run. {@code visionCapable} is {@code null} when the endpoint's
     * metadata could not determine it (cloud endpoints, unidentifiable model) — image support then
     * stays a heuristics question, see {@link AiVisionSupport}.
     */
    public record AiCapabilityDiscovery(List<AiReasoningEffort> reasoningEfforts, Boolean visionCapable) {
    }

    public static AiCapabilityDiscovery discover(
        AiProfile profile,
        String apiKey,
        AiInternetAccessConfiguration internetConfig,
        AiSkillPromptSupport skillPromptSupport) throws Exception {

        if (profile == null) {
            throw new IllegalStateException("AI profile must be configured.");
        }
        Optional<LocalLmModelResolver.LmStudioCapabilities> lmStudio = Optional.empty();
        if (usesExactLmStudioMetadata(profile)) {
            try {
                lmStudio = LocalLmModelResolver.loadLmStudioCapabilities(
                    profile.getApiUrl(),
                    profile.getModel(),
                    profile.getModelSelectionMode(),
                    apiKey,
                    null);
            } catch (java.io.IOException ignored) {
                lmStudio = Optional.empty();
            }
        }
        Boolean visionCapable = lmStudio.flatMap(LocalLmModelResolver.LmStudioCapabilities::visionCapable)
            .orElse(null);
        Optional<List<AiReasoningEffort>> lmStudioEfforts =
            lmStudio.flatMap(LocalLmModelResolver.LmStudioCapabilities::reasoningEfforts);
        if (lmStudioEfforts.isPresent()) {
            if (!testEffort(profile, apiKey, internetConfig, skillPromptSupport, AiReasoningEffort.DISABLED)) {
                throw new IllegalStateException("AI connection test failed.");
            }
            return new AiCapabilityDiscovery(
                AiReasoningSupport.normalizeOptions(lmStudioEfforts.get()), visionCapable);
        }
        return new AiCapabilityDiscovery(
            discover(effort -> testEffort(profile, apiKey, internetConfig, skillPromptSupport, effort)),
            visionCapable);
    }

    /**
     * Reads the profile's reasoning levels from its endpoint's model metadata alone — one GET, no
     * chat completion and no probing. Returns empty whenever the levels cannot be established that
     * way (not an LM Studio-style endpoint, model not identifiable, endpoint unreachable), which is
     * the signal to leave the stored result alone rather than to record "no reasoning".
     *
     * <p>This is what keeps the picker in step with the profile. The manual refresh is a deliberate
     * user action that may cost a request, so it cannot run on every edit; changing the model in the
     * editor would otherwise drop the discovered levels and leave only the model-name defaults,
     * which know nothing about locally served models.
     */
    public static Optional<AiCapabilityDiscovery> discoverFromMetadata(AiProfile profile, String apiKey) {
        if (!canDiscoverFromMetadata(profile)) {
            return Optional.empty();
        }
        Optional<LocalLmModelResolver.LmStudioCapabilities> lmStudio;
        try {
            lmStudio = LocalLmModelResolver.loadLmStudioCapabilities(
                profile.getApiUrl(),
                profile.getModel(),
                profile.getModelSelectionMode(),
                apiKey,
                null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (java.io.IOException | RuntimeException ex) {
            return Optional.empty();
        }
        Optional<List<AiReasoningEffort>> efforts =
            lmStudio.flatMap(LocalLmModelResolver.LmStudioCapabilities::reasoningEfforts);
        if (efforts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AiCapabilityDiscovery(
            AiReasoningSupport.normalizeOptions(efforts.get()),
            lmStudio.flatMap(LocalLmModelResolver.LmStudioCapabilities::visionCapable).orElse(null)));
    }

    /**
     * True when {@link #discoverFromMetadata} could return a result for this profile, so callers can
     * skip the background call for profiles it can never answer for.
     */
    public static boolean canDiscoverFromMetadata(AiProfile profile) {
        return usesExactLmStudioMetadata(profile)
            && LocalLmModelResolver.canReadLmStudioMetadata(profile.getApiUrl());
    }

    static boolean usesExactLmStudioMetadata(AiProfile profile) {
        return profile != null
            && profile.getConnectionMode() == AiConnectionMode.HTTP_API
            && profile.getModelSelectionMode() != AiModelSelectionMode.DEFAULT;
    }

    static List<AiReasoningEffort> discover(ReasoningProbe probe) throws Exception {
        if (probe == null) {
            throw new IllegalStateException("AI reasoning probe must be configured.");
        }
        if (!probe.accepts(AiReasoningEffort.DISABLED)) {
            throw new IllegalStateException("AI connection test failed.");
        }
        List<AiReasoningEffort> accepted = new ArrayList<>();
        accepted.add(AiReasoningEffort.DISABLED);
        for (AiReasoningEffort candidate : PROBE_CANDIDATES) {
            if (accepts(probe, candidate) && !accepted.contains(candidate)) {
                accepted.add(candidate);
            }
        }
        return accepted;
    }

    private static boolean testEffort(
        AiProfile profile,
        String apiKey,
        AiInternetAccessConfiguration internetConfig,
        AiSkillPromptSupport skillPromptSupport,
        AiReasoningEffort effort) throws Exception {

        AiProfile probeProfile = new AiProfile(profile);
        probeProfile.setReasoningEffort(effort);
        AiService service = AiServiceFactory.createForReasoningProbe(
            probeProfile,
            apiKey,
            internetConfig,
            skillPromptSupport,
            effort);
        return service != null && service.testConnection();
    }

    private static boolean accepts(ReasoningProbe probe, AiReasoningEffort candidate) {
        try {
            return probe.accepts(candidate);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    @FunctionalInterface
    interface ReasoningProbe {
        boolean accepts(AiReasoningEffort effort) throws Exception;
    }
}
