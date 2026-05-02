package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.AiReasoningEffort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiReasoningSupportTest {

    @Test
    void availableEffortsAreDisabledOnlyForUnknownModels() {
        List<AiReasoningEffort> options = AiReasoningSupport.availableEfforts(
            "http://127.0.0.1:1234/v1/chat/completions",
            "local-model");

        assertEquals(List.of(AiReasoningEffort.DISABLED), options);
    }

    @Test
    void availableEffortsIncludeNoneForGpt51AndLaterModels() {
        List<AiReasoningEffort> options = AiReasoningSupport.availableEfforts(
            "https://api.openai.com/v1/chat/completions",
            "gpt-5.1");

        assertTrue(options.contains(AiReasoningEffort.DISABLED));
        assertTrue(options.contains(AiReasoningEffort.NONE));
        assertTrue(options.contains(AiReasoningEffort.HIGH));
        assertTrue(!options.contains(AiReasoningEffort.XHIGH));
    }

    @Test
    void availableEffortsIncludeXhighForGpt52AndLaterModels() {
        List<AiReasoningEffort> options = AiReasoningSupport.availableEfforts(
            "https://api.openai.com/v1/chat/completions",
            "gpt-5.5");

        assertTrue(options.contains(AiReasoningEffort.XHIGH));
    }

    @Test
    void availableEffortsIncludeMinimalForGpt5Before51() {
        List<AiReasoningEffort> options = AiReasoningSupport.availableEfforts(
            "https://api.openai.com/v1/chat/completions",
            "gpt-5");

        assertTrue(options.contains(AiReasoningEffort.MINIMAL));
        assertTrue(!options.contains(AiReasoningEffort.NONE));
    }

    @Test
    void normalizeFallsBackToDisabledWhenRequestedEffortIsUnavailable() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");
        profile.setModel("local-model");
        profile.setReasoningEffort(AiReasoningEffort.HIGH);

        assertEquals(AiReasoningEffort.DISABLED, AiReasoningSupport.normalizeForProfile(profile));
        assertEquals("Disabled", AiReasoningSupport.exportStatus(profile));
    }
}
