package de.kortty.core;

import de.kortty.model.AiPromptPreset;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiPromptPresetSupportTest {

    @Test
    void autoDetectsSupportedModelFamiliesCaseInsensitively() {
        assertThat(AiPromptPresetSupport.resolve(AiPromptPreset.AUTO, "Qwen2.5-Coder-7B-Q4_K_M"))
            .isEqualTo(AiPromptPreset.QWEN);
        assertThat(AiPromptPresetSupport.resolve(AiPromptPreset.AUTO, "Meta-Llama-3.1"))
            .isEqualTo(AiPromptPreset.LLAMA);
        assertThat(AiPromptPresetSupport.resolve(AiPromptPreset.AUTO, "gpt-oss-20b"))
            .isEqualTo(AiPromptPreset.GPT_OSS);
    }

    @Test
    void explicitPresetOverridesModelDetection() {
        assertThat(AiPromptPresetSupport.resolve(AiPromptPreset.MISTRAL, "qwen3"))
            .isEqualTo(AiPromptPreset.MISTRAL);
    }

    @Test
    void qwenGuidancePreservesStrictOutputAndSuppressesReasoningTrace() {
        String optimized = AiPromptPresetSupport.append("Return strict JSON.", AiPromptPreset.QWEN);

        assertThat(optimized).contains("Return strict JSON.");
        assertThat(optimized).contains("Do not emit <think>");
        assertThat(optimized).contains("Preserve exact JSON/code contracts");
    }
}
