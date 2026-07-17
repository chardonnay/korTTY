package de.kortty.core;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiReasoningEffort;
import org.testng.annotations.Test;

import java.util.List;
import static com.google.common.truth.Truth.assertThat;


class AiReasoningSupportTest {

    @Test
    void availableEffortsAreDisabledOnlyForUnknownModels() {
        List<AiReasoningEffort> options = AiReasoningSupport.availableEfforts(
            "http://127.0.0.1:1234/v1/chat/completions",
            "local-model");

        assertThat(options).isEqualTo(List.of(AiReasoningEffort.DISABLED));
    }

    @Test
    void availableEffortsIncludeNoneForGpt51AndLaterModels() {
        List<AiReasoningEffort> options = AiReasoningSupport.availableEfforts(
            "https://api.openai.com/v1/chat/completions",
            "gpt-5.1");

        assertThat(options.contains(AiReasoningEffort.DISABLED)).isTrue();
        assertThat(options.contains(AiReasoningEffort.NONE)).isTrue();
        assertThat(options.contains(AiReasoningEffort.HIGH)).isTrue();
        assertThat(!options.contains(AiReasoningEffort.XHIGH)).isTrue();
    }

    @Test
    void availableEffortsIncludeXhighForGpt52AndLaterModels() {
        List<AiReasoningEffort> options = AiReasoningSupport.availableEfforts(
            "https://api.openai.com/v1/chat/completions",
            "gpt-5.5");

        assertThat(options.contains(AiReasoningEffort.XHIGH)).isTrue();
    }

    @Test
    void availableEffortsIncludeMinimalForGpt5Before51() {
        List<AiReasoningEffort> options = AiReasoningSupport.availableEfforts(
            "https://api.openai.com/v1/chat/completions",
            "gpt-5");

        assertThat(options.contains(AiReasoningEffort.MINIMAL)).isTrue();
        assertThat(!options.contains(AiReasoningEffort.NONE)).isTrue();
    }

    @Test
    void normalizeFallsBackToDisabledWhenRequestedEffortIsUnavailable() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");
        profile.setModel("local-model");
        profile.setReasoningEffort(AiReasoningEffort.HIGH);

        assertThat(AiReasoningSupport.normalizeForProfile(profile)).isEqualTo(AiReasoningEffort.DISABLED);
        assertThat(AiReasoningSupport.exportStatus(profile)).isEqualTo("Disabled");
    }

    @Test
    void availableEffortsUseDiscoveredOptionsWhenProfileKeyMatches() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("https://api.example.test/v1/chat/completions");
        profile.setDiscoveredReasoningEfforts(List.of(AiReasoningEffort.HIGH));
        profile.setReasoningDiscoveryKey(AiReasoningSupport.discoveryKey(profile));
        profile.setReasoningEffort(AiReasoningEffort.HIGH);

        assertThat(AiReasoningSupport.availableEfforts(profile))
            .containsExactly(AiReasoningEffort.DISABLED, AiReasoningEffort.HIGH)
            .inOrder();
        assertThat(AiReasoningSupport.normalizeForProfile(profile)).isEqualTo(AiReasoningEffort.HIGH);
    }

    @Test
    void availableEffortsIgnoreDiscoveredOptionsWhenProfileKeyChanges() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("https://api.example.test/v1/chat/completions");
        profile.setDiscoveredReasoningEfforts(List.of(AiReasoningEffort.HIGH));
        profile.setReasoningDiscoveryKey(AiReasoningSupport.discoveryKey(profile));

        profile.setApiUrl("https://api.changed.example.test/v1/chat/completions");

        assertThat(AiReasoningSupport.availableEfforts(profile)).isEqualTo(List.of(AiReasoningEffort.DISABLED));
    }

    @Test
    void discoveryKeyHandlesProfileWithoutStoredModelSelectionMode() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("https://api.example.test/v1/chat/completions");

        assertThat(AiReasoningSupport.discoveryKey(profile)).isNotEmpty();
    }

    @Test
    void embeddedProfileKeepsDiscoveredReasoningLevelDespiteStaleDiscoveryKey() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.EMBEDDED_LLAMA_CPP);
        profile.setEmbeddedModelId("lmstudio-community-gpt-oss-20b-GGUF-MXFP4");
        profile.setDiscoveredReasoningEfforts(List.of(
            AiReasoningEffort.NONE, AiReasoningEffort.MINIMAL, AiReasoningEffort.LOW,
            AiReasoningEffort.MEDIUM, AiReasoningEffort.HIGH, AiReasoningEffort.XHIGH));
        // A discovery ran, but the stored key is the old embedded form full of unused apiUrl/CLI
        // placeholders that no longer matches the recomputed key. Embedded discovery must survive it,
        // otherwise the user's chosen reasoning level is silently reset to Disabled on save/reload.
        profile.setReasoningDiscoveryKey(
            "EMBEDDED_LLAMA_CPP|https://api.openai.com/v1/chat/completions|MANUAL||claude-code||");
        profile.setReasoningEffort(AiReasoningEffort.MINIMAL);

        assertThat(AiReasoningSupport.availableEfforts(profile)).contains(AiReasoningEffort.MINIMAL);
        assertThat(AiReasoningSupport.normalizeForProfile(profile)).isEqualTo(AiReasoningEffort.MINIMAL);
    }

    @Test
    void embeddedProfileWithoutDiscoveryDoesNotTrustStoredEfforts() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.EMBEDDED_LLAMA_CPP);
        profile.setEmbeddedModelId("some-embedded-model");
        profile.setDiscoveredReasoningEfforts(List.of(AiReasoningEffort.HIGH));
        // No discovery key means no discovery has run: the stored efforts must not be trusted.
        profile.setReasoningEffort(AiReasoningEffort.HIGH);

        assertThat(AiReasoningSupport.normalizeForProfile(profile)).isEqualTo(AiReasoningEffort.DISABLED);
    }
}
