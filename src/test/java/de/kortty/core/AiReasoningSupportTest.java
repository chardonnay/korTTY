package de.kortty.core;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiModelSelectionMode;
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
    void reasoningLevelsDiscoveredBeforeTheLmStudioMetadataFixAreIgnoredUntilRediscovered() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setApiUrl("http://localhost:1234/v1/chat/completions");
        profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
        profile.setModel("qwen/qwen3-coder-next");
        // What an active probe against LM Studio recorded for a model without any reasoning
        // capability: it never rejects an unsupported value, so every level looked supported.
        profile.setDiscoveredReasoningEfforts(List.of(
            AiReasoningEffort.NONE, AiReasoningEffort.MINIMAL, AiReasoningEffort.LOW,
            AiReasoningEffort.MEDIUM, AiReasoningEffort.HIGH, AiReasoningEffort.XHIGH));
        profile.setReasoningEffort(AiReasoningEffort.NONE);
        // A key in the pre-migration format, exactly as older korTTY versions stored it.
        profile.setReasoningDiscoveryKey(
            "HTTP_API|http://localhost:1234/v1/chat/completions|MANUAL|qwen/qwen3-coder-next|||");

        // Ignored, so the conservative model-name default applies and no reasoning parameter is sent.
        assertThat(AiReasoningSupport.availableEfforts(profile))
            .isEqualTo(List.of(AiReasoningEffort.DISABLED));
        assertThat(AiReasoningSupport.normalizeForProfile(profile)).isEqualTo(AiReasoningEffort.DISABLED);
        assertThat(AiReasoningSupport.profileForAction(profile, AiAction.APPLY_SNIPPET_IMPROVEMENTS)
            .getReasoningEffort()).isEqualTo(AiReasoningEffort.NONE);

        // Discovering again stores the current key and the fresh result counts immediately.
        profile.setDiscoveredReasoningEfforts(List.of(AiReasoningEffort.DISABLED));
        profile.setReasoningDiscoveryKey(AiReasoningSupport.discoveryKey(profile));
        assertThat(AiReasoningSupport.availableEfforts(profile))
            .isEqualTo(List.of(AiReasoningEffort.DISABLED));
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

    @Test
    void mermaidUsesRequestScopedNoneWhenProfileAdvertisesIt() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");
        profile.setModel("qwen-reasoning");
        profile.setDiscoveredReasoningEfforts(List.of(
            AiReasoningEffort.NONE, AiReasoningEffort.MINIMAL));
        profile.setReasoningDiscoveryKey(AiReasoningSupport.discoveryKey(profile));
        profile.setReasoningEffort(AiReasoningEffort.MINIMAL);

        AiProfile executionProfile = AiReasoningSupport.profileForAction(
            profile, AiAction.GENERATE_SNIPPET_MERMAID);

        assertThat(executionProfile).isNotSameInstanceAs(profile);
        assertThat(executionProfile.getReasoningEffort()).isEqualTo(AiReasoningEffort.NONE);
        assertThat(profile.getReasoningEffort()).isEqualTo(AiReasoningEffort.MINIMAL);
    }

    @Test
    void postAnalysisApplyActionsUseRequestScopedNoneWhenProfileAdvertisesIt() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");
        profile.setModel("qwen-reasoning");
        profile.setDiscoveredReasoningEfforts(List.of(
            AiReasoningEffort.NONE, AiReasoningEffort.MINIMAL));
        profile.setReasoningDiscoveryKey(AiReasoningSupport.discoveryKey(profile));
        profile.setReasoningEffort(AiReasoningEffort.MINIMAL);

        assertThat(AiReasoningSupport.profileForAction(
            profile, AiAction.APPLY_SNIPPET_IMPROVEMENTS).getReasoningEffort())
            .isEqualTo(AiReasoningEffort.NONE);
        assertThat(AiReasoningSupport.profileForAction(
            profile, AiAction.APPLY_SNIPPET_SECURITY_FIXES).getReasoningEffort())
            .isEqualTo(AiReasoningEffort.NONE);
        assertThat(AiReasoningSupport.profileForAction(
            profile, AiAction.ANALYZE_SNIPPET_CODE)).isSameInstanceAs(profile);
        assertThat(profile.getReasoningEffort()).isEqualTo(AiReasoningEffort.MINIMAL);
    }

    @Test
    void mermaidKeepsConfiguredEffortWithoutVerifiedNoneSupport() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");
        profile.setModel("unknown-model");
        profile.setReasoningEffort(AiReasoningEffort.MINIMAL);

        assertThat(AiReasoningSupport.profileForAction(
            profile, AiAction.GENERATE_SNIPPET_MERMAID)).isSameInstanceAs(profile);
        assertThat(AiReasoningSupport.profileForAction(
            profile, AiAction.APPLY_SNIPPET_IMPROVEMENTS)).isSameInstanceAs(profile);
        assertThat(AiReasoningSupport.profileForAction(
            profile, AiAction.APPLY_SNIPPET_SECURITY_FIXES)).isSameInstanceAs(profile);
        assertThat(AiReasoningSupport.profileForAction(
            profile, AiAction.ANALYZE_SNIPPET_CODE)).isSameInstanceAs(profile);
    }

    @Test
    void automaticModelSelectionDoesNotForceCapabilitiesCachedForAnEarlierLoadedModel() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");
        profile.setModel("previously-loaded-model");
        profile.setModelSelectionMode(AiModelSelectionMode.AUTO);
        profile.setDiscoveredReasoningEfforts(List.of(AiReasoningEffort.NONE));
        profile.setReasoningDiscoveryKey(AiReasoningSupport.discoveryKey(profile));
        profile.setReasoningEffort(AiReasoningEffort.MINIMAL);

        assertThat(AiReasoningSupport.profileForAction(
            profile, AiAction.APPLY_SNIPPET_IMPROVEMENTS)).isSameInstanceAs(profile);
        assertThat(AiReasoningSupport.profileForAction(
            profile, AiAction.GENERATE_SNIPPET_MERMAID)).isSameInstanceAs(profile);

        profile.setReasoningEffort(AiReasoningEffort.NONE);
        assertThat(AiReasoningSupport.profileForAction(
            profile, AiAction.APPLY_SNIPPET_IMPROVEMENTS)).isSameInstanceAs(profile);
    }
}
