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
        }
        prompt.append("Treat the selected text as the primary source of truth.\n");
        if (request.connectionDisplayName() != null && !request.connectionDisplayName().isBlank()) {
            prompt.append("Connection: ").append(request.connectionDisplayName().trim()).append("\n");
        }
        if ((request.action() == AiAction.ASK || request.action() == AiAction.GENERATE_CHAT_TITLE)
            && request.conversationContext() != null
            && !request.conversationContext().isBlank()) {
            prompt.append("Conversation so far:\n")
                .append(toSafeTextCodeBlock(request.conversationContext()))
                .append("\n");
        }
        if (request.action() == AiAction.ASK && request.userPrompt() != null && !request.userPrompt().isBlank()) {
            prompt.append("User request:\n")
                .append(request.userPrompt().trim())
                .append("\n");
        }
        if (request.action() == AiAction.GENERATE_CHAT_TITLE) {
            prompt.append("Focus on what the user and AI discussed, not on generic phrasing.\n");
        }
        prompt.append("Selected terminal text:\n")
            .append(toSafeTextCodeBlock(request.selectedText()));
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
