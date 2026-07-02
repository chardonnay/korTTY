package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiCloudModelCatalogTest {

    @Test
    void returnsCuratedModelsForKnownProviderInOrder() {
        assertThat(AiCloudModelCatalog.suggestedModelsForUrl("https://api.openai.com/v1/chat/completions"))
            .containsExactly("gpt-4o-mini", "gpt-4o", "o4-mini").inOrder();
        assertThat(AiCloudModelCatalog.examplesForUrl("https://api.deepseek.com/v1/chat/completions"))
            .isEqualTo("deepseek-chat, deepseek-reasoner");
    }

    @Test
    void matchesByHostRegardlessOfPath() {
        assertThat(AiCloudModelCatalog.suggestedModelsForUrl("https://api.openai.com/v1"))
            .containsExactly("gpt-4o-mini", "gpt-4o", "o4-mini").inOrder();
    }

    @Test
    void returnsEmptyForUnknownOrBlankUrl() {
        assertThat(AiCloudModelCatalog.suggestedModelsForUrl("https://unknown.example.com/v1/chat/completions")).isEmpty();
        assertThat(AiCloudModelCatalog.suggestedModelsForUrl("")).isEmpty();
        assertThat(AiCloudModelCatalog.suggestedModelsForUrl(null)).isEmpty();
        assertThat(AiCloudModelCatalog.examplesForUrl(null)).isEmpty();
    }
}
