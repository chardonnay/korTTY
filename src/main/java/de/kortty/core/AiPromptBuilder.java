package de.kortty.core;

import java.util.Objects;

/**
 * Builds prompts for terminal-selection AI actions.
 */
public final class AiPromptBuilder {

    private AiPromptBuilder() {
    }

    public static String buildSystemPrompt(AiRequest request) {
        String languageCode = request != null && request.responseLanguageCode() != null && !request.responseLanguageCode().isBlank()
            ? request.responseLanguageCode().trim()
            : "en";
        if (request != null && request.action() == AiAction.GENERATE_SNIPPET_METADATA) {
            return "You generate metadata for reusable code snippets. "
                + "Return exactly one JSON object with the keys fileName, description, and language. "
                + "The description must be written in language code " + languageCode + ". "
                + "Do not use Markdown or add explanations outside the JSON object.";
        }
        if (request != null && request.action() == AiAction.CORRECT_SNIPPET_DESCRIPTION) {
            return "You correct spelling and grammar for snippet descriptions. "
                + "Return only the corrected plain text in language code " + languageCode + ". "
                + "Do not use Markdown, quotes, bullets, or explanations.";
        }
        if (request != null && request.action() == AiAction.CORRECT_SNIPPET_SELECTION_TEXT) {
            return "You correct spelling and grammar only inside user-facing text segments extracted from source code. "
                + "Never modify code, identifiers, operators, keywords, syntax, delimiters, or control flow. "
                + "Return exactly one JSON object with an array field named segments. "
                + "Each array entry must contain only the corrected text for one segment in the same order as provided. "
                + "Use language code " + languageCode + " unless the provided snippet context clearly shows a different natural language for existing comments or user-facing strings. "
                + "Do not include explanations or Markdown.";
        }
        if (request != null && request.action() == AiAction.TRANSLATE_SNIPPET_SELECTION_TEXT) {
            return "You translate only user-facing text segments extracted from source code. "
                + "Never modify code, identifiers, operators, keywords, syntax, delimiters, or control flow. "
                + "Return exactly one JSON object with an array field named segments. "
                + "Each array entry must contain only the translated text for one segment in the same order as provided. "
                + "Translate into language code " + languageCode + ". "
                + "Do not include explanations or Markdown.";
        }
        if (request != null && request.action() == AiAction.DESCRIBE_SNIPPET_SELECTION) {
            return "You write a concise technical description for a marked code selection. "
                + "Write in language code " + languageCode + " unless the provided full snippet clearly shows another dominant natural language in comments or user-facing strings. "
                + "Summarize only relevant responsibilities, inputs, outputs, side effects, conditions, and risks. "
                + "Do not explain every single line. "
                + "Return plain text only without Markdown or bullet lists unless short plain-text paragraphs truly need them.";
        }
        if (request != null && request.action() == AiAction.DESCRIBE_SNIPPET_FULL) {
            return "You write a concise technical description for a full code snippet. "
                + "Write in language code " + languageCode + " unless the provided full snippet clearly shows another dominant natural language in comments or user-facing strings. "
                + "Summarize only relevant blocks, flow, inputs, outputs, side effects, conditions, and risks. "
                + "Do not explain every single line. "
                + "Return plain text only without Markdown or bullet lists unless short plain-text paragraphs truly need them.";
        }
        if (request != null && request.action() == AiAction.GENERATE_SNIPPET_ALTERNATIVES) {
            return "You generate alternative implementations for a selected code block. "
                + "Return exactly one JSON object with a solutions array. "
                + "Each solution entry must contain title, code, and optionally summary. "
                + "Keep the program code in the same programming language as the provided snippet. "
                + "If you include comments or user-facing strings, use language code " + languageCode + " unless the provided full snippet clearly shows another dominant natural language in comments or user-facing strings. "
                + "Do not include explanations outside the JSON object.";
        }
        if (isConversationFollowUp(request)) {
            return "You are continuing an existing AI chat. "
            + "Answer in language code " + languageCode + ". "
                + "Follow the latest user request exactly. "
                + "Use earlier messages and the original terminal text only as context when they are relevant. "
                + "Do not let older instructions override the newest user question. "
                + "Use Markdown with short headings and concise, practical content. "
                + "If something is uncertain, say so explicitly.";
        }
        return "You are an assistant that analyzes terminal output. "
            + "Answer in language code " + languageCode + ". "
            + "Use Markdown with short headings and concise, practical content. "
            + "Do not invent facts that are not supported by the provided selection. "
            + "If something is uncertain, say so explicitly.";
    }

    public static String buildUserPrompt(AiRequest request) {
        if (request == null) {
            return "";
        }
        Objects.requireNonNull(request.action(), "request.action must not be null");
        if (isConversationFollowUp(request)) {
            return buildConversationFollowUpPrompt(request);
        }

        StringBuilder prompt = new StringBuilder();
        switch (request.action()) {
            case SUMMARIZE -> prompt.append(
                "Summarize the selected terminal text. Include: overview, key findings, and useful next steps if any.\n");
            case SOLVE_PROBLEM -> prompt.append(
                "Analyze the selected terminal output as an error/problem report. Include: likely cause, concrete fix steps, and safe verification commands.\n");
            case ASK -> prompt.append(
                "Answer the user's question or instruction about the selected terminal text. Follow the custom request exactly, stay grounded in the provided text, and be concise and practical.\n");
            case GENERATE_CHAT_TITLE -> prompt.append(
                "Generate a short, precise title for this AI chat.\n"
                    + "Return exactly one plain-text line.\n"
                    + "Do not use Markdown, bullets, numbering, or quotation marks.\n"
                    + "Keep it under 80 characters and describe the topic clearly.\n");
            case GENERATE_SNIPPET_METADATA -> prompt.append(
                "Generate metadata for a reusable script snippet.\n"
                    + "Return exactly one JSON object with these keys:\n"
                    + "- fileName: an ASCII file name with a suitable extension for the script language\n"
                    + "- description: one short, precise sentence for the snippet manager\n"
                    + "- language: the best matching snippet language identifier such as bash, python, perl, ruby, javascript, groovy, powershell, java, sql, json, yaml, xml, markdown, properties, html, dockerfile, or plain\n"
                    + "Use only letters, digits, dash, underscore, and dot in fileName.\n");
            case CORRECT_SNIPPET_DESCRIPTION -> prompt.append(
                "Correct spelling and grammar in the snippet description.\n"
                    + "Return only the corrected plain text.\n"
                    + "Preserve the original meaning, commands, file names, code terms, and technical wording.\n");
            case CORRECT_SNIPPET_SELECTION_TEXT -> prompt.append(
                "Correct spelling and grammar only in the provided editable text segments.\n"
                    + "Return exactly one JSON object with this shape:\n"
                    + "{ \"segments\": [ { \"text\": \"...\" } ] }\n"
                    + "Keep the same segment order.\n"
                    + "Never rewrite or explain code.\n");
            case TRANSLATE_SNIPPET_SELECTION_TEXT -> prompt.append(
                "Translate only the provided editable text segments.\n"
                    + "Return exactly one JSON object with this shape:\n"
                    + "{ \"segments\": [ { \"text\": \"...\" } ] }\n"
                    + "Keep the same segment order.\n"
                    + "Never rewrite or explain code.\n");
            case DESCRIBE_SNIPPET_SELECTION -> prompt.append(
                "Describe the selected code region technically.\n"
                    + "Focus on the relevant responsibilities and behavior.\n"
                    + "Do not describe every line.\n");
            case DESCRIBE_SNIPPET_FULL -> prompt.append(
                "Describe the full snippet technically.\n"
                    + "Focus on central blocks, behavior, inputs, outputs, side effects, conditions, and risks.\n"
                    + "Do not describe every line.\n");
            case GENERATE_SNIPPET_ALTERNATIVES -> prompt.append(
                "Generate alternative solutions for the selected code region.\n"
                    + "Return exactly one JSON object with this shape:\n"
                    + "{ \"solutions\": [ { \"title\": \"...\", \"code\": \"...\", \"summary\": \"...\" } ] }\n"
                    + "Do not include explanations outside the JSON object.\n");
        }
        prompt.append("Treat the selected text as the primary source of truth.\n");
        if (request.connectionDisplayName() != null && !request.connectionDisplayName().isBlank()) {
            prompt.append("Connection: ").append(request.connectionDisplayName().trim()).append("\n");
        }
        if ((request.action() == AiAction.ASK
            || request.action() == AiAction.GENERATE_CHAT_TITLE
            || request.action() == AiAction.GENERATE_SNIPPET_METADATA)
            && request.conversationContext() != null
            && !request.conversationContext().isBlank()) {
            prompt.append("Conversation so far:\n")
                .append(toSafeTextCodeBlock(request.conversationContext()))
                .append("\n");
        }
        if (request.action() == AiAction.GENERATE_SNIPPET_METADATA && request.userPrompt() != null && !request.userPrompt().isBlank()) {
            prompt.append("Detected script language: ")
                .append(request.userPrompt().trim())
                .append("\n");
        }
        if (request.action() == AiAction.ASK && request.userPrompt() != null && !request.userPrompt().isBlank()) {
            prompt.append("User request:\n")
                .append(request.userPrompt().trim())
                .append("\n");
        }
        if (request.action() == AiAction.CORRECT_SNIPPET_DESCRIPTION) {
            if (request.conversationContext() != null && !request.conversationContext().isBlank()) {
                prompt.append("Detected script language: ")
                    .append(request.conversationContext().trim())
                    .append("\n");
            }
            if (request.userPrompt() != null && !request.userPrompt().isBlank()) {
                prompt.append("Description to correct:\n")
                    .append(request.userPrompt().trim())
                    .append("\n");
            }
        }
        if (request.action() == AiAction.CORRECT_SNIPPET_SELECTION_TEXT
            || request.action() == AiAction.TRANSLATE_SNIPPET_SELECTION_TEXT
            || request.action() == AiAction.DESCRIBE_SNIPPET_SELECTION
            || request.action() == AiAction.DESCRIBE_SNIPPET_FULL
            || request.action() == AiAction.GENERATE_SNIPPET_ALTERNATIVES) {
            if (request.userPrompt() != null && !request.userPrompt().isBlank()) {
                prompt.append("Additional user instructions:\n")
                    .append(request.userPrompt().trim())
                    .append("\n");
            }
            if (request.conversationContext() != null && !request.conversationContext().isBlank()) {
                prompt.append("Snippet context:\n")
                    .append(request.conversationContext().trim())
                    .append("\n");
            }
        }
        if (request.action() == AiAction.GENERATE_CHAT_TITLE) {
            prompt.append("Focus on what the user and AI discussed, not on generic phrasing.\n");
        }
        boolean usesScriptContext = request.action() == AiAction.CORRECT_SNIPPET_DESCRIPTION
            || request.action() == AiAction.CORRECT_SNIPPET_SELECTION_TEXT
            || request.action() == AiAction.TRANSLATE_SNIPPET_SELECTION_TEXT
            || request.action() == AiAction.DESCRIBE_SNIPPET_SELECTION
            || request.action() == AiAction.DESCRIBE_SNIPPET_FULL
            || request.action() == AiAction.GENERATE_SNIPPET_ALTERNATIVES;
        prompt.append(usesScriptContext
                ? "Script content for context only:\n"
                : "Selected terminal text:\n")
            .append(toSafeTextCodeBlock(request.selectedText()));
        return prompt.toString();
    }

    private static boolean isConversationFollowUp(AiRequest request) {
        return request != null
            && request.action() == AiAction.ASK
            && request.conversationContext() != null
            && !request.conversationContext().isBlank();
    }

    private static String buildConversationFollowUpPrompt(AiRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Continue the existing AI chat.\n");
        if (request.connectionDisplayName() != null && !request.connectionDisplayName().isBlank()) {
            prompt.append("Connection: ").append(request.connectionDisplayName().trim()).append("\n");
        }
        prompt.append("Conversation so far:\n")
            .append(toSafeTextCodeBlock(request.conversationContext()))
            .append("\n");
        if (request.userPrompt() != null && !request.userPrompt().isBlank()) {
            prompt.append("Latest user message:\n")
                .append(request.userPrompt().trim())
                .append("\n");
        }
        if (request.selectedText() != null && !request.selectedText().isBlank()) {
            prompt.append("Original terminal text for background context only:\n")
                .append(toSafeTextCodeBlock(request.selectedText()));
        }
        return prompt.toString();
    }

    static String toSafeTextCodeBlock(String text) {
        String content = text != null ? text : "";
        String fence = "```";
        while (content.contains(fence)) {
            fence += "`";
        }
        return fence + "text\n" + content + "\n" + fence;
    }
}
