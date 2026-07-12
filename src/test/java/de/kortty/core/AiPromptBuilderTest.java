package de.kortty.core;

import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;


class AiPromptBuilderTest {

    @Test
    void summarizePromptContainsExpectedSections() {
        AiRequest request = new AiRequest(AiAction.SUMMARIZE, "error: sample", "prod-server", "de");

        String systemPrompt = AiPromptBuilder.buildSystemPrompt(request);
        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertThat(systemPrompt.contains("language code de")).isTrue();
        assertThat(userPrompt.contains("overview")).isTrue();
        assertThat(userPrompt.contains("key findings")).isTrue();
        assertThat(userPrompt.contains("prod-server")).isTrue();
        assertThat(userPrompt.contains("error: sample")).isTrue();
    }

    @Test
    void solveProblemPromptContainsExpectedGuidance() {
        AiRequest request = new AiRequest(AiAction.SOLVE_PROBLEM, "Exception in thread", "stage-box", "en");

        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertThat(userPrompt.contains("likely cause")).isTrue();
        assertThat(userPrompt.contains("concrete fix steps")).isTrue();
        assertThat(userPrompt.contains("safe verification commands")).isTrue();
        assertThat(userPrompt.contains("Exception in thread")).isTrue();
    }

    @Test
    void askPromptContainsCustomUserInstruction() {
        AiRequest request = new AiRequest(AiAction.ASK, "ls -la output", "dev-box", "de", "write a short perl one-liner");

        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertThat(userPrompt.contains("User request")).isTrue();
        assertThat(userPrompt.contains("write a short perl one-liner")).isTrue();
        assertThat(userPrompt.contains("ls -la output")).isTrue();
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

        assertThat(systemPrompt.contains("continuing an existing AI chat")).isTrue();
        assertThat(systemPrompt.contains("language code de")).isTrue();
        assertThat(userPrompt.contains("Continue the existing AI chat")).isTrue();
        assertThat(userPrompt.contains("Latest user message")).isTrue();
        assertThat(userPrompt.contains("What should I check next?")).isTrue();
        assertThat(userPrompt.contains("background context only")).isTrue();
        assertThat(!userPrompt.contains("Treat the selected text as the primary source of truth")).isTrue();
        assertThat(!userPrompt.contains("Answer the user's question or instruction about the selected terminal text")).isTrue();
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

        assertThat(userPrompt.contains("exactly one plain-text line")).isTrue();
        assertThat(userPrompt.contains("Conversation so far")).isTrue();
        assertThat(userPrompt.contains("fatal: unable to access repository")).isTrue();
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

        assertThat(systemPrompt.contains("exactly one JSON object")).isTrue();
        assertThat(systemPrompt.contains("language code de")).isTrue();
        assertThat(userPrompt.contains("fileName")).isTrue();
        assertThat(userPrompt.contains("description")).isTrue();
        assertThat(userPrompt.contains("language")).isTrue();
        assertThat(userPrompt.contains("Detected script language: python")).isTrue();
        assertThat(userPrompt.contains("Conversation so far")).isTrue();
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

        assertThat(systemPrompt.contains("correct spelling and grammar")).isTrue();
        assertThat(systemPrompt.contains("plain text")).isTrue();
        assertThat(systemPrompt.contains("<think> tags")).isTrue();
        assertThat(userPrompt.contains("Description to correct")).isTrue();
        assertThat(userPrompt.contains("Script content for context only")).isTrue();
        assertThat(userPrompt.contains("Detected script language: bash")).isTrue();
        assertThat(userPrompt.contains("<think> tags")).isTrue();
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

        assertThat(systemPrompt.contains("Never modify code")).isTrue();
        assertThat(systemPrompt.contains("segments")).isTrue();
        assertThat(userPrompt.contains("JSON object")).isTrue();
        assertThat(userPrompt.contains("Additional user instructions")).isTrue();
        assertThat(userPrompt.contains("Snippet context")).isTrue();
    }

    @Test
    void describeSnippetPromptForbidsThinkTags() {
        AiRequest request = new AiRequest(
            AiAction.DESCRIBE_SNIPPET_SELECTION,
            "print $file",
            null,
            "en",
            null,
            "Snippet language: perl");

        String systemPrompt = AiPromptBuilder.buildSystemPrompt(request);

        assertThat(systemPrompt).contains("technical description");
        assertThat(systemPrompt).contains("Do not include hidden reasoning");
        assertThat(systemPrompt).contains("<think> tags");
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

        assertThat(systemPrompt.contains("Translate into language code fr")).isTrue();
        assertThat(systemPrompt.contains("Never modify code")).isTrue();
        assertThat(userPrompt.contains("Translate only the provided editable text segments")).isTrue();
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

        assertThat(systemPrompt.contains("solutions array")).isTrue();
        assertThat(systemPrompt.contains("same programming language")).isTrue();
        assertThat(userPrompt.contains("\"solutions\"")).isTrue();
        assertThat(userPrompt.contains("Additional user instructions")).isTrue();
    }

    @Test
    void snippetCompletionPromptRequiresInsertTextOnly() {
        AiRequest request = new AiRequest(
            AiAction.COMPLETE_SNIPPET_CODE,
            "echo start",
            null,
            "en",
            null,
            "Snippet language: bash\nCursor offset: 10");

        String systemPrompt = AiPromptBuilder.buildSystemPrompt(request);
        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertThat(systemPrompt).contains("insertText");
        assertThat(systemPrompt).contains("not the full file");
        assertThat(userPrompt).contains("\"insertText\"");
        assertThat(userPrompt).contains("Script content for context only");
    }

    @Test
    void snippetAssistantPromptRequiresFullReplacementAndCursorContext() {
        AiRequest request = new AiRequest(
            AiAction.ASSIST_SNIPPET_CODE,
            "def main():\n    print('ok')\nmain()",
            null,
            "de",
            "füge neue Parameter für Verzeichnisnamen ein",
            "Snippet language: python\nCursor offset: 16\nCursor line: 2\nCursor column: 5\nFull snippet:\n```text\n...\n```");

        String systemPrompt = AiPromptBuilder.buildSystemPrompt(request);
        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertThat(systemPrompt).contains("complete code snippet");
        assertThat(systemPrompt).contains("full updated snippet content");
        assertThat(systemPrompt).contains("not a patch");
        assertThat(systemPrompt).contains("Do not invent files");
        assertThat(userPrompt).contains("\"replacement\"");
        assertThat(userPrompt).contains("replacement must be the full updated snippet content");
        assertThat(userPrompt).contains("füge neue Parameter");
        assertThat(userPrompt).contains("Cursor line: 2");
        assertThat(userPrompt).contains("Cursor column: 5");
        assertThat(userPrompt).contains("Treat the provided full snippet as the editable source of truth");
        assertThat(userPrompt).contains("Full script content to update");
    }

    @Test
    void securityFixPromptRequiresSelectedFindingsOnlyAndFullReplacement() {
        AiRequest request = new AiRequest(
            AiAction.APPLY_SNIPPET_SECURITY_FIXES,
            "eval \"$input\"",
            null,
            "de",
            "Keep output text German",
            "Selected security findings to fix:\n```text\nS1 Unsafe eval\n```");

        String systemPrompt = AiPromptBuilder.buildSystemPrompt(request);
        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertThat(systemPrompt).contains("only the selected security findings");
        assertThat(systemPrompt).contains("full updated snippet content");
        assertThat(userPrompt).contains("\"replacement\"");
        assertThat(userPrompt).contains("Additional user instructions");
    }

    @Test
    void mermaidPromptRequiresRestrictedNodeMappedFlowchartJson() {
        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_MERMAID,
            "if ok; then echo yes; fi",
            null,
            "en",
            "Use activity view",
            "Snippet language: bash");

        String systemPrompt = AiPromptBuilder.buildSystemPrompt(request);
        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertThat(systemPrompt).contains("flowchart TD");
        assertThat(systemPrompt).contains("start_1([\"Start\"])");
        assertThat(systemPrompt).contains("stop_1([\"Stop\"])");
        assertThat(systemPrompt).contains("stable descriptive node ids");
        assertThat(systemPrompt).contains("setup, work, success, or failure");
        assertThat(systemPrompt).contains("frontmatter");
        assertThat(systemPrompt).contains("URLs");
        assertThat(systemPrompt).contains("codeReferences");
        assertThat(systemPrompt).contains("nodeId");
        assertThat(systemPrompt).contains("startLine");
        assertThat(systemPrompt).contains("endLine");
        assertThat(systemPrompt).contains("for every action and decision node");
        assertThat(userPrompt).contains("\"mermaid\"");
        assertThat(userPrompt).contains("\"codeReferences\"");
        assertThat(userPrompt).contains("decision_1 -->|yes| success_1");
        assertThat(userPrompt).contains("class failure_1 failure");
        assertThat(userPrompt).contains("excluding start_1 and stop_1");
        assertThat(userPrompt).contains("\"nodeId\": \"work_1\"");
        assertThat(userPrompt).contains("every visible action and decision node");
        assertThat(userPrompt).contains("1-based line numbers");
        assertThat(userPrompt).contains("Snippet context");
    }

    @Test
    void compactOneLinerPromptRequiresSingleJsonCommandWithoutBase64() {
        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_ONE_LINER,
            "def main():\n    print('ok')\nmain()",
            null,
            "en",
            null,
            "Snippet language: python");

        String systemPrompt = AiPromptBuilder.buildSystemPrompt(request);
        String userPrompt = AiPromptBuilder.buildUserPrompt(request);

        assertThat(systemPrompt).contains("one-line shell command");
        assertThat(systemPrompt).contains("key command");
        assertThat(systemPrompt).contains("base64");
        assertThat(systemPrompt).contains("external URLs");
        assertThat(userPrompt).contains("\"command\"");
        assertThat(userPrompt).contains("Do not use base64, heredocs");
        assertThat(userPrompt).contains("Do not invent files");
        assertThat(userPrompt).contains("Script content for context only");
    }

    @Test
    void codePayloadActionsAppendFullCodeAnchorAsLastLine() {
        AiRequest apply = new AiRequest(AiAction.APPLY_SNIPPET_IMPROVEMENTS, "print 1;\nprint 2;\n", "box", "en");
        String applyPrompt = AiPromptBuilder.buildUserPrompt(apply);

        assertThat(applyPrompt).contains("Every code field must contain the actual code");
        // The anchor is the very last content, after the (untrusted) code block.
        assertThat(applyPrompt.strip()).endsWith("less fails.");
    }

    @Test
    void nonCodePayloadActionsDoNotGetTheCodeAnchor() {
        // A findings-only strict-JSON action has no code field to protect → no anchor.
        AiRequest analyze = new AiRequest(AiAction.ANALYZE_SNIPPET_CODE, "print 1;", "box", "en");
        assertThat(AiPromptBuilder.buildUserPrompt(analyze)).doesNotContain("Every code field must contain");

        // A chat action → no anchor.
        AiRequest chat = new AiRequest(AiAction.SUMMARIZE, "text", "box", "en");
        assertThat(AiPromptBuilder.buildUserPrompt(chat)).doesNotContain("Every code field must contain");
    }
}
