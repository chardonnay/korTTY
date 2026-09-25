package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiInternetPromptSupportTest {

    @Test
    void snippetEditorAiActionsAreNotInternetEligible() {
        for (AiAction action : new AiAction[] {
            AiAction.GENERATE_SNIPPET_METADATA,
            AiAction.CORRECT_SNIPPET_DESCRIPTION,
            AiAction.CORRECT_SNIPPET_SELECTION_TEXT,
            AiAction.TRANSLATE_SNIPPET_SELECTION_TEXT,
            AiAction.DESCRIBE_SNIPPET_SELECTION,
            AiAction.DESCRIBE_SNIPPET_FULL,
            AiAction.GENERATE_SNIPPET_ALTERNATIVES,
            AiAction.COMPLETE_SNIPPET_CODE,
            AiAction.REVIEW_SNIPPET_CODE,
            AiAction.IMPROVE_SNIPPET_CODE,
            AiAction.ASSIST_SNIPPET_CODE,
            AiAction.SECURITY_REVIEW_SNIPPET_CODE,
            AiAction.APPLY_SNIPPET_SECURITY_FIXES,
            AiAction.GENERATE_SNIPPET_MERMAID
        }) {
            assertThat(AiInternetPromptSupport.isInternetEligible(new AiRequest(action, "code", null, "en"))).isFalse();
        }
    }

    @Test
    void researchAndDocumentationWordsMakeAgentTasksInternetEligible() {
        assertThat(AiInternetPromptSupport.isPromptInternetEligible(
            "User task: recherchiere die Nginx Doku zu rate limiting\nConnection: box")).isTrue();
        assertThat(AiInternetPromptSupport.isPromptInternetEligible(
            "User task: read the changelog on www.example.org\nConnection: box")).isTrue();
        assertThat(AiInternetPromptSupport.isPromptInternetEligible(
            "User task: zeige die groesste xml datei\nConnection: box")).isFalse();
    }

    @Test
    void offerForEveryAgentTaskOnlyWidensAgentPrompts() {
        String agentPrompt = "User task: zeige die groesste xml datei\nConnection: box";
        assertThat(AiInternetPromptSupport.isPromptInternetEligible(agentPrompt, false)).isFalse();
        assertThat(AiInternetPromptSupport.isPromptInternetEligible(agentPrompt, true)).isTrue();
        // Non-agent prompts (translation, journal, …) carry no "User task:" line and stay gated.
        assertThat(AiInternetPromptSupport.isPromptInternetEligible("Translate this text to German.", true)).isFalse();
    }
}
