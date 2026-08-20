package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.AiVisionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On-demand vision detection for LM Studio profiles in {@code AUTO} mode. The settings dialogs
 * record the capability during their discovery run, but that snapshot goes stale the moment the
 * profile's model changes — and the name heuristic then misses vision models without a telltale
 * name ("qwen3.8-27b"). This check asks the running LM Studio instance's model metadata directly,
 * with a short-lived cache so a burst of screenshots costs one metadata GET, not one per image.
 *
 * <p>May block on the network — call it from worker threads only; UI enablement uses
 * {@link #probeEligible(AiProfile)}, which is pure.</p>
 */
public final class AiVisionLiveCheck {

    private static final Logger logger = LoggerFactory.getLogger(AiVisionLiveCheck.class);
    static final long CACHE_TTL_MILLIS = 10 * 60_000;

    private record Cached(Boolean visionCapable, long atMillis) {
    }

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    /** Test seam: one LM Studio metadata query for the profile's model. */
    interface MetadataProbe {
        Optional<Boolean> visionCapable(AiProfile profile, String apiKey) throws Exception;
    }

    private static final MetadataProbe LIVE_PROBE = (profile, apiKey) ->
        LocalLmModelResolver.loadLmStudioCapabilities(
                profile.getApiUrl(), profile.getModel(), profile.getModelSelectionMode(), apiKey, null)
            .flatMap(LocalLmModelResolver.LmStudioCapabilities::visionCapable);

    private AiVisionLiveCheck() {
    }

    /**
     * True when a live probe could settle the question: vision on AUTO, an image-capable
     * transport, and an endpoint whose model list carries modality metadata (LM Studio).
     */
    public static boolean probeEligible(AiProfile profile) {
        return profile != null
            && profile.getVisionSupport() == AiVisionMode.AUTO
            && AiVisionSupport.transportSupportsVision(profile)
            && AiReasoningDiscoveryService.usesExactLmStudioMetadata(profile);
    }

    /**
     * The static answer ({@link AiVisionSupport}) first; when that says no and a probe is
     * eligible, the endpoint's own metadata decides. Absent or unreadable metadata counts as
     * "no" — this check widens detection, it never overrides an explicit answer.
     */
    public static boolean isVisionCapable(AiProfile profile, String apiKey) {
        if (AiVisionSupport.isVisionCapable(profile)) {
            return true;
        }
        return probedVisionCapable(profile, apiKey, LIVE_PROBE);
    }

    static boolean probedVisionCapable(AiProfile profile, String apiKey, MetadataProbe probe) {
        if (!probeEligible(profile)) {
            return false;
        }
        String key = profile.getApiUrl() + "|" + profile.getModel();
        Cached cached = CACHE.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.atMillis() < CACHE_TTL_MILLIS) {
            return Boolean.TRUE.equals(cached.visionCapable());
        }
        Boolean vision = null;
        try {
            vision = probe.visionCapable(profile, apiKey).orElse(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false; // interrupted — do not cache, the next call may succeed
        } catch (Exception e) {
            logger.debug("LM Studio vision metadata query failed: {}", e.getMessage());
        }
        CACHE.put(key, new Cached(vision, now));
        return Boolean.TRUE.equals(vision);
    }

    /** Test hook: clears the process-wide cache. */
    static void clearCache() {
        CACHE.clear();
    }
}
