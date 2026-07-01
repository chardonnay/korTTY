package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AiModelComboSupportTest {

    @Test
    void cloudEndpointOffersCuratedSuggestionsWithoutAuto() {
        String url = "https://api.openai.com/v1/chat/completions";
        assertThat(AiModelComboSupport.supportsAutoModel(url)).isFalse();
        assertThat(AiModelComboSupport.buildModelItems("Default", "Auto", url, List.of(), null))
            .containsExactly("Default", "gpt-4o-mini", "gpt-4o", "o4-mini").inOrder();
    }

    @Test
    void localLmStudioEndpointOffersAutoAndLoadedModels() {
        String url = "http://127.0.0.1:1234";
        assertThat(AiModelComboSupport.supportsAutoModel(url)).isTrue();
        assertThat(AiModelComboSupport.buildModelItems("Default", "Auto", url, List.of("qwen/qwen3-coder"), null))
            .containsExactly("Default", "Auto", "qwen/qwen3-coder").inOrder();
    }

    @Test
    void mergesLiveModelsAndCurrentValueWithoutDuplicates() {
        String url = "https://api.openai.com/v1/chat/completions";
        List<String> items = AiModelComboSupport.buildModelItems(
            "Default", "Auto", url, List.of("gpt-4o", "gpt-4.1"), "my-custom-model");
        assertThat(items)
            .containsExactly("Default", "gpt-4o-mini", "gpt-4o", "o4-mini", "gpt-4.1", "my-custom-model")
            .inOrder();
    }

    @Test
    void unknownCloudEndpointOffersOnlyDefault() {
        String url = "https://unknown.example.com/v1/chat/completions";
        assertThat(AiModelComboSupport.buildModelItems("Default", "Auto", url, List.of(), null))
            .containsExactly("Default");
    }
}
