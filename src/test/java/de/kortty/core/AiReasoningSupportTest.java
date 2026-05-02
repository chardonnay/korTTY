package de.kortty.core;

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
}
