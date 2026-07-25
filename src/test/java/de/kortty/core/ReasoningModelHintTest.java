package de.kortty.core;

import de.kortty.model.AiProfile;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

class ReasoningModelHintTest {

    /** The two models that prompted this: both were configured for translation by mistake. */
    @Test
    void recognisesTheModelsMeasuredOnThisProject() {
        assertThat(ReasoningModelHint.likelyReasoningModel(
            "lmstudio-community-Phi-4-mini-reasoning-MLX-4bit-4BIT")).isTrue();
        assertThat(ReasoningModelHint.likelyReasoningModel(
            "mlx-community-gpt-oss-20b-MXFP4-Q8-4BIT")).isTrue();
    }

    @Test
    void recognisesTheCommonReasoningFamilies() {
        for (String id : List.of("deepseek-r1-distill-qwen-7b", "DeepSeek-R1", "QwQ-32B-Preview",
                "qwen3-8b-thinking", "openthinker-7b", "marco-o1", "phi-4-reasoning-plus")) {
            assertWithMessage(id).that(ReasoningModelHint.likelyReasoningModel(id)).isTrue();
        }
    }

    @Test
    void leavesPlainInstructModelsAlone() {
        for (String id : List.of("Phi-4-mini-instruct-4bit", "qwen2.5-7b-instruct",
                "mistral-7b-instruct-v0.3", "llama-3.1-8b-instruct", "gemma-2-9b-it",
                "nllb-200-distilled-600M", "opus-mt-en-de")) {
            assertWithMessage(id).that(ReasoningModelHint.likelyReasoningModel(id)).isFalse();
        }
    }

    /** A marker must be a token of its own, not a fragment of an unrelated word. */
    @Test
    void doesNotFireOnWordsThatMerelyContainAMarker() {
        for (String id : List.of("cotton-classifier-7b", "rethinking-base", "r10-model",
                "thinktank")) {
            assertWithMessage(id).that(ReasoningModelHint.likelyReasoningModel(id)).isFalse();
        }
    }

    @Test
    void separatorStyleDoesNotMatter() {
        assertThat(ReasoningModelHint.likelyReasoningModel("phi_4_mini_reasoning")).isTrue();
        assertThat(ReasoningModelHint.likelyReasoningModel("Phi.4.Mini.Reasoning")).isTrue();
        assertThat(ReasoningModelHint.likelyReasoningModel("PHI-4-MINI-REASONING")).isTrue();
    }

    @Test
    void missingInputIsNotAWarning() {
        assertThat(ReasoningModelHint.likelyReasoningModel((String) null)).isFalse();
        assertThat(ReasoningModelHint.likelyReasoningModel("")).isFalse();
        assertThat(ReasoningModelHint.likelyReasoningModel("  ")).isFalse();
        assertThat(ReasoningModelHint.likelyReasoningModel((AiProfile) null)).isFalse();
    }

    @Test
    void aProfileIsJudgedByItsEmbeddedModel() {
        AiProfile profile = new AiProfile();
        profile.setName("phi-4-mini-text");
        profile.setEmbeddedModelId("lmstudio-community-Phi-4-mini-reasoning-MLX-4bit-4BIT");

        // The profile NAME says "text"; only the model id gives it away.
        assertThat(ReasoningModelHint.likelyReasoningModel(profile)).isTrue();

        profile.setEmbeddedModelId("Phi-4-mini-instruct-4bit");
        assertThat(ReasoningModelHint.likelyReasoningModel(profile)).isFalse();
    }
}
