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

    @Test
    void generateSnippetMetadataPromptRequiresJsonFileNameAndDescription() {
        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_METADATA,
            "#!/usr/bin/env python3\nprint('ok')",
            "dev-box",
            "de",
            "python",
            "User asked for a reusable cleanup script.");

        String systemPrompt = AiPromptBuilder.buildSystemPrompt(request);
        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertTrue(systemPrompt.contains("exactly one JSON object"));
        assertTrue(systemPrompt.contains("language code de"));
        assertTrue(userPrompt.contains("fileName"));
        assertTrue(userPrompt.contains("description"));
        assertTrue(userPrompt.contains("language"));
        assertTrue(userPrompt.contains("Detected script language: python"));
        assertTrue(userPrompt.contains("Conversation so far"));
    }

    @Test
    void correctSnippetDescriptionPromptRequiresPlainTextCorrection() {
        AiRequest request = new AiRequest(
            AiAction.CORRECT_SNIPPET_DESCRIPTION,
            "#!/bin/bash\necho ok",
            "prod-shell",
            "de",
            "dieses script erstellt backup von log datein",
            "bash");

        String systemPrompt = AiPromptBuilder.buildSystemPrompt(request);
        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertTrue(systemPrompt.contains("correct spelling and grammar"));
        assertTrue(systemPrompt.contains("plain text"));
        assertTrue(userPrompt.contains("Description to correct"));
        assertTrue(userPrompt.contains("Script content for context only"));
        assertTrue(userPrompt.contains("Detected script language: bash"));
    }

    @Test
    void correctSnippetSelectionPromptRequiresStructuredSegmentResponseAndNoCodeMutation() {
        AiRequest request = new AiRequest(
            AiAction.CORRECT_SNIPPET_SELECTION_TEXT,
            "# backup log filez",
            "prod-shell",
            "de",
            "Keep existing command wording",
            "Snippet language: bash\nEditable text segments JSON:\n[{\"index\":0,\"text\":\"backup log filez\"}]");

        String systemPrompt = AiPromptBuilder.buildSystemPrompt(request);
        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertTrue(systemPrompt.contains("Never modify code"));
        assertTrue(systemPrompt.contains("segments"));
        assertTrue(userPrompt.contains("JSON object"));
        assertTrue(userPrompt.contains("Additional user instructions"));
        assertTrue(userPrompt.contains("Snippet context"));
    }

    @Test
    void translateSnippetSelectionPromptUsesTargetLanguageAndStructuredSegments() {
        AiRequest request = new AiRequest(
            AiAction.TRANSLATE_SNIPPET_SELECTION_TEXT,
            "echo \"Backup complete\"",
            "prod-shell",
            "fr",
            "Translate formal",
            "Snippet language: bash\nEditable text segments JSON:\n[{\"index\":0,\"text\":\"Backup complete\"}]");

        String systemPrompt = AiPromptBuilder.buildSystemPrompt(request);
        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertTrue(systemPrompt.contains("Translate into language code fr"));
        assertTrue(systemPrompt.contains("Never modify code"));
        assertTrue(userPrompt.contains("Translate only the provided editable text segments"));
    }

    @Test
    void alternativeSnippetPromptRequiresJsonSolutionsInSameLanguage() {
        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_ALTERNATIVES,
            "for file in *.log; do gzip \"$file\"; done",
            "prod-shell",
            "de",
            "Use only Bash builtins",
            "Snippet language: bash\nReturn at most 3 solutions.\nFull snippet for context:\n```text\n...\n```");

        String systemPrompt = AiPromptBuilder.buildSystemPrompt(request);
        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertTrue(systemPrompt.contains("solutions array"));
        assertTrue(systemPrompt.contains("same programming language"));
        assertTrue(userPrompt.contains("\"solutions\""));
        assertTrue(userPrompt.contains("Additional user instructions"));
    }
}
