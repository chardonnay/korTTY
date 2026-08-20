package de.kortty.core;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiVisionMode;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

class AiVisionLiveCheckTest {

    @BeforeMethod
    void resetCache() {
        AiVisionLiveCheck.clearCache();
    }

    private static AiProfile lmStudioProfile(String model) {
        AiProfile profile = new AiProfile();
        profile.setName("lmstudio");
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setApiUrl("http://localhost:1234/v1/chat/completions");
        profile.setModel(model);
        profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
        profile.setVisionSupport(AiVisionMode.AUTO);
        return profile;
    }

    @Test
    void probeEligibilityRequiresAutoModeAndLmStudioMetadata() {
        assertThat(AiVisionLiveCheck.probeEligible(lmStudioProfile("qwen3.8-27b"))).isTrue();

        AiProfile explicit = lmStudioProfile("qwen3.8-27b");
        explicit.setVisionSupport(AiVisionMode.ENABLED);
        assertThat(AiVisionLiveCheck.probeEligible(explicit)).isFalse(); // override needs no probe

        AiProfile cli = lmStudioProfile("qwen3.8-27b");
        cli.setConnectionMode(AiConnectionMode.LOCAL_CLI);
        assertThat(AiVisionLiveCheck.probeEligible(cli)).isFalse();

        AiProfile defaultModel = lmStudioProfile("qwen3.8-27b");
        defaultModel.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
        assertThat(AiVisionLiveCheck.probeEligible(defaultModel)).isFalse();

        assertThat(AiVisionLiveCheck.probeEligible(null)).isFalse();
    }

    @Test
    void metadataAnswerDecidesAndIsCached() {
        AiProfile profile = lmStudioProfile("qwen3.8-27b");
        AtomicInteger probes = new AtomicInteger();

        boolean first = AiVisionLiveCheck.probedVisionCapable(profile, null, (p, key) -> {
            probes.incrementAndGet();
            return Optional.of(true);
        });
        boolean second = AiVisionLiveCheck.probedVisionCapable(profile, null,
            (p, key) -> {
                throw new AssertionError("cached result must be reused");
            });

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(probes.get()).isEqualTo(1);
    }

    @Test
    void absentOrNegativeMetadataMeansNo() {
        assertThat(AiVisionLiveCheck.probedVisionCapable(
            lmStudioProfile("text-model"), null, (p, key) -> Optional.of(false))).isFalse();
        AiVisionLiveCheck.clearCache();
        assertThat(AiVisionLiveCheck.probedVisionCapable(
            lmStudioProfile("text-model"), null, (p, key) -> Optional.empty())).isFalse();
    }

    @Test
    void probeFailuresAreCachedAsUnknown() {
        AiProfile profile = lmStudioProfile("qwen3.8-27b");
        AtomicInteger probes = new AtomicInteger();

        boolean result = AiVisionLiveCheck.probedVisionCapable(profile, null, (p, key) -> {
            probes.incrementAndGet();
            throw new java.io.IOException("endpoint down");
        });

        assertThat(result).isFalse();
        // The failure is cached — a screenshot burst must not hammer a dead endpoint.
        assertThat(AiVisionLiveCheck.probedVisionCapable(profile, null, (p, key) -> {
            throw new AssertionError("failure must be cached");
        })).isFalse();
        assertThat(probes.get()).isEqualTo(1);
    }
}
