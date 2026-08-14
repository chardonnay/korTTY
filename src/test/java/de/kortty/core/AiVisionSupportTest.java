package de.kortty.core;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiVisionMode;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiVisionSupportTest {

    private static AiProfile httpProfile(String apiUrl, String model) {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setApiUrl(apiUrl);
        profile.setModel(model);
        return profile;
    }

    @Test
    void explicitOverrideBeatsHeuristics() {
        AiProfile enabled = httpProfile("https://example.test/v1/chat/completions", "totally-text-model");
        enabled.setVisionSupport(AiVisionMode.ENABLED);
        assertThat(AiVisionSupport.isVisionCapable(enabled)).isTrue();

        AiProfile disabled = httpProfile("https://api.openai.com/v1/chat/completions", "gpt-4o");
        disabled.setVisionSupport(AiVisionMode.DISABLED);
        assertThat(AiVisionSupport.isVisionCapable(disabled)).isFalse();
    }

    @Test
    void transportGateWinsOverAnEnabledOverride() {
        AiProfile cli = new AiProfile();
        cli.setConnectionMode(AiConnectionMode.LOCAL_CLI);
        cli.setVisionSupport(AiVisionMode.ENABLED);
        assertThat(AiVisionSupport.isVisionCapable(cli)).isFalse();

        AiProfile embedded = new AiProfile();
        embedded.setConnectionMode(AiConnectionMode.EMBEDDED_LLAMA_CPP);
        embedded.setVisionSupport(AiVisionMode.ENABLED);
        assertThat(AiVisionSupport.isVisionCapable(embedded)).isFalse();

        AiProfile blankUrl = new AiProfile();
        blankUrl.setConnectionMode(AiConnectionMode.HTTP_API);
        blankUrl.setVisionSupport(AiVisionMode.ENABLED);
        assertThat(AiVisionSupport.isVisionCapable(blankUrl)).isFalse();
    }

    @Test
    void discoveredCapabilityWinsOnKeyMatchOnly() {
        AiProfile profile = httpProfile("http://127.0.0.1:1234/v1/chat/completions", "some-model");
        profile.setDiscoveredVisionCapable(true);
        profile.setReasoningDiscoveryKey(AiReasoningSupport.discoveryKey(profile));
        assertThat(AiVisionSupport.isVisionCapable(profile)).isTrue();

        // Changing the model invalidates the stored discovery; heuristics say no for this name.
        profile.setModel("other-model");
        assertThat(AiVisionSupport.isVisionCapable(profile)).isFalse();
    }

    @Test
    void authoritativeDiscoveredNoBeatsTheNameHeuristic() {
        AiProfile profile = httpProfile("http://127.0.0.1:1234/v1/chat/completions", "gpt-4o");
        profile.setDiscoveredVisionCapable(false);
        profile.setReasoningDiscoveryKey(AiReasoningSupport.discoveryKey(profile));
        assertThat(AiVisionSupport.isVisionCapable(profile)).isFalse();
    }

    @Test
    void autoFallsBackToHeuristicsWithoutDiscovery() {
        assertThat(AiVisionSupport.isVisionCapable(
            httpProfile("https://api.openai.com/v1/chat/completions", "gpt-4o"))).isTrue();
        assertThat(AiVisionSupport.isVisionCapable(
            httpProfile("https://api.openai.com/v1/chat/completions", "gpt-3.5-turbo"))).isFalse();
    }

    @Test
    void heuristicsRecognizeCommonVisionModels() {
        String url = "https://example.test/v1/chat/completions";
        assertThat(AiVisionSupport.modelSuggestsVision(url, "gpt-4o")).isTrue();
        assertThat(AiVisionSupport.modelSuggestsVision(url, "gpt-5.1")).isTrue();
        assertThat(AiVisionSupport.modelSuggestsVision(url, "openai/gpt-4.1-mini")).isTrue();
        assertThat(AiVisionSupport.modelSuggestsVision(url, "claude-sonnet-4-5")).isTrue();
        assertThat(AiVisionSupport.modelSuggestsVision(url, "gemini-2.5-flash")).isTrue();
        assertThat(AiVisionSupport.modelSuggestsVision(url, "google/gemma-3-12b")).isTrue();
        assertThat(AiVisionSupport.modelSuggestsVision(url, "qwen2.5-vl-7b-instruct")).isTrue();
        assertThat(AiVisionSupport.modelSuggestsVision(url, "MiniMax-VL-01")).isTrue();
        assertThat(AiVisionSupport.modelSuggestsVision(url, "llava-1.6-mistral")).isTrue();
        assertThat(AiVisionSupport.modelSuggestsVision(url, "pixtral-12b")).isTrue();
        assertThat(AiVisionSupport.modelSuggestsVision(url, "nvidia/nemotron-nano-vl-8b")).isTrue();
        assertThat(AiVisionSupport.modelSuggestsVision(url, "llama-3.2-11b-vision-instruct")).isTrue();
        assertThat(AiVisionSupport.modelSuggestsVision(url, "o4-mini")).isTrue();

        assertThat(AiVisionSupport.modelSuggestsVision(url, "gpt-3.5-turbo")).isFalse();
        assertThat(AiVisionSupport.modelSuggestsVision(url, "o3-mini")).isFalse();
        assertThat(AiVisionSupport.modelSuggestsVision(url, "deepseek-r1")).isFalse();
        assertThat(AiVisionSupport.modelSuggestsVision(url, "claude-2.1")).isFalse();
        assertThat(AiVisionSupport.modelSuggestsVision(url, "")).isFalse();
        assertThat(AiVisionSupport.modelSuggestsVision(url, null)).isFalse();
    }

    @Test
    void anthropicHostImpliesVisionRegardlessOfModelName() {
        assertThat(AiVisionSupport.modelSuggestsVision(
            "https://api.anthropic.com/v1/messages", "some-future-model")).isTrue();
    }
}
