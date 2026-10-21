package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class LocalModelContextControlTest {

    @Test
    void clampKeepsTheValueBetweenTheMinimumAndTheModelMaximum() {
        assertThat(LocalModelContextControl.clamp(100, 131_072)).isEqualTo(512);
        assertThat(LocalModelContextControl.clamp(16_384, 131_072)).isEqualTo(16_384);
        assertThat(LocalModelContextControl.clamp(200_000, 131_072)).isEqualTo(131_072);
        // A model that declares less than the minimum still gets the minimum.
        assertThat(LocalModelContextControl.clamp(4_096, 256)).isEqualTo(512);
    }

    @Test
    void newChatModelsStartWith32kUnlessTrainedForLess() {
        assertThat(de.kortty.ai.llama.LlamaModel.DEFAULT_CONTEXT_SIZE).isEqualTo(32_768);
        assertThat(de.kortty.ai.llama.LlamaModel.defaultContextSizeFor(-1)).isEqualTo(32_768);
        assertThat(de.kortty.ai.llama.LlamaModel.defaultContextSizeFor(131_072)).isEqualTo(32_768);
        assertThat(de.kortty.ai.llama.LlamaModel.defaultContextSizeFor(8_192)).isEqualTo(8_192);
    }

    @Test
    void sliderSnapsToStepsAndReachesAnOddMaximum() {
        assertThat(LocalModelContextControl.snap(16_400.0, 131_072)).isEqualTo(16_384);
        assertThat(LocalModelContextControl.snap(700.0, 131_072)).isEqualTo(512);
        // 40 960 + 100 is not a multiple of 512; near the right end the slider gives all of it.
        assertThat(LocalModelContextControl.snap(41_000.0, 41_060)).isEqualTo(41_060);
        assertThat(LocalModelContextControl.snap(0.0, 131_072)).isEqualTo(512);
    }
}
