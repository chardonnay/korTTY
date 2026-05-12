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
            AiAction.SECURITY_REVIEW_SNIPPET_CODE,
            AiAction.APPLY_SNIPPET_SECURITY_FIXES,
            AiAction.GENERATE_SNIPPET_PLANTUML
        }) {
            assertThat(AiInternetPromptSupport.isInternetEligible(new AiRequest(action, "code", null, "en"))).isFalse();
        }
    }
}
