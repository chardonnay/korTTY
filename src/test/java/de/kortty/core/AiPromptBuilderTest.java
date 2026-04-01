package de.kortty.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPromptBuilderTest {

    @Test
    void summarizePromptContainsExpectedSections() {
        AiRequest request = new AiRequest(AiAction.SUMMARIZE, "error: sample", "prod-server", "de");

        String systemPrompt = AiPromptBuilder.buildSystemPrompt(request);
        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertTrue(systemPrompt.contains("language code de"));
        assertTrue(userPrompt.contains("overview"));
        assertTrue(userPrompt.contains("key findings"));
        assertTrue(userPrompt.contains("prod-server"));
        assertTrue(userPrompt.contains("error: sample"));
    }

    @Test
    void solveProblemPromptContainsExpectedGuidance() {
        AiRequest request = new AiRequest(AiAction.SOLVE_PROBLEM, "Exception in thread", "stage-box", "en");

        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertTrue(userPrompt.contains("likely cause"));
        assertTrue(userPrompt.contains("concrete fix steps"));
        assertTrue(userPrompt.contains("safe verification commands"));
        assertTrue(userPrompt.contains("Exception in thread"));
    }

    @Test
    void askPromptContainsCustomUserInstruction() {
        AiRequest request = new AiRequest(AiAction.ASK, "ls -la output", "dev-box", "de", "write a short perl one-liner");

        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertTrue(userPrompt.contains("User request"));
        assertTrue(userPrompt.contains("write a short perl one-liner"));
        assertTrue(userPrompt.contains("ls -la output"));
    }

    @Test
    void askFollowUpPromptUsesConversationModeInsteadOfReframingOriginalAction() {
        AiRequest request = new AiRequest(
            AiAction.ASK,
            "fatal: repository not found",
            "dev-box",
            "de",
            "What should I check next?",
            "Assistant:\nCheck credentials first.");

        String systemPrompt = AiPromptBuilder.buildSystemPrompt(request);
        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertTrue(systemPrompt.contains("continuing an existing AI chat"));
        assertTrue(systemPrompt.contains("language code de"));
        assertTrue(userPrompt.contains("Continue the existing AI chat"));
        assertTrue(userPrompt.contains("Latest user message"));
        assertTrue(userPrompt.contains("What should I check next?"));
        assertTrue(userPrompt.contains("background context only"));
        assertTrue(!userPrompt.contains("Treat the selected text as the primary source of truth"));
        assertTrue(!userPrompt.contains("Answer the user's question or instruction about the selected terminal text"));
    }

    @Test
    void generateChatTitlePromptContainsSingleLineInstructionAndConversation() {
        AiRequest request = new AiRequest(
            AiAction.GENERATE_CHAT_TITLE,
            "fatal: unable to access repository",
            "prod-shell",
            "de",
            null,
            "You:\nWie loese ich das?\n\nAI:\nPruefe Proxy und DNS.");

        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertTrue(userPrompt.contains("exactly one plain-text line"));
        assertTrue(userPrompt.contains("Conversation so far"));
        assertTrue(userPrompt.contains("fatal: unable to access repository"));
    }
}
