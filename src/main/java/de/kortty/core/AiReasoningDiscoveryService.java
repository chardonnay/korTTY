package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.AiReasoningEffort;

import java.util.ArrayList;
import java.util.List;

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

    public static List<AiReasoningEffort> discover(
        AiProfile profile,
        String apiKey,
        AiInternetAccessConfiguration internetConfig,
        AiSkillPromptSupport skillPromptSupport) throws Exception {

        if (profile == null) {
            throw new IllegalStateException("AI profile must be configured.");
        }
        return discover(effort -> testEffort(profile, apiKey, internetConfig, skillPromptSupport, effort));
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
