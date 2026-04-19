package de.kortty.core;

import java.util.List;

/**
 * Shared request/response workflow helpers for snippet-editor AI actions.
 */
public final class SnippetAiWorkflowSupport {

    @FunctionalInterface
    public interface UsageRecorder {
        void record(AiRequest request, AiExecutionResult result);
    }

    private SnippetAiWorkflowSupport() {
    }

    public static String correctSelectionText(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String selectedText,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        String additionalInstructions) throws Exception {

        return transformSelectedText(
            AiAction.CORRECT_SNIPPET_SELECTION_TEXT,
            aiService,
            usageRecorder,
            fullContent,
            selectedText,
            snippetLanguage,
            connectionDisplayName,
            fallbackLanguageCode,
            additionalInstructions);
    }

    public static String translateSelectionText(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String selectedText,
        String snippetLanguage,
        String connectionDisplayName,
        String targetLanguageCode,
        String fallbackLanguageCode,
        String additionalInstructions) throws Exception {

        return transformSelectedText(
            AiAction.TRANSLATE_SNIPPET_SELECTION_TEXT,
            aiService,
            usageRecorder,
            fullContent,
            selectedText,
            snippetLanguage,
            connectionDisplayName,
            targetLanguageCode != null && !targetLanguageCode.isBlank() ? targetLanguageCode : fallbackLanguageCode,
            mergeAdditionalInstructions(additionalInstructions, buildTranslationFallbackNote(fallbackLanguageCode)));
    }

    public static String describeSnippet(
        AiAction action,
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String selectedText,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        String additionalInstructions) throws Exception {

        AiRequest request = new AiRequest(
            action,
            selectedText,
            connectionDisplayName,
            fallbackLanguageCode,
            additionalInstructions,
            buildDescriptionContext(fullContent, snippetLanguage, fallbackLanguageCode));
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        return SnippetAiTextSupport.normalizePlainText(result != null ? result.content() : null);
    }

    public static List<SnippetAiResponseSupport.AlternativeSolution> generateAlternativeSolutions(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String selectedText,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        int maxSolutions,
        String additionalInstructions) throws Exception {

        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_ALTERNATIVES,
            selectedText,
            connectionDisplayName,
            fallbackLanguageCode,
            additionalInstructions,
            buildAlternativeContext(fullContent, snippetLanguage, fallbackLanguageCode, maxSolutions));
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        return SnippetAiResponseSupport.parseAlternativeSolutions(
            result != null ? result.content() : null,
            maxSolutions);
    }

    private static String transformSelectedText(
        AiAction action,
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String selectedText,
        String snippetLanguage,
        String connectionDisplayName,
        String responseLanguageCode,
        String additionalInstructions) throws Exception {

        List<SnippetAiTextSupport.EditableTextSegment> segments =
            SnippetAiTextSupport.extractEditableSegments(selectedText, snippetLanguage);
        if (segments.isEmpty()) {
            return selectedText != null ? selectedText : "";
        }
        AiRequest request = new AiRequest(
            action,
            selectedText,
            connectionDisplayName,
            responseLanguageCode,
            additionalInstructions,
            buildSelectionTransformContext(fullContent, snippetLanguage, responseLanguageCode, segments));
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        List<String> replacements = SnippetAiResponseSupport.parseSegmentReplacements(
            result != null ? result.content() : null,
            segments.size());
        if (replacements.isEmpty()) {
            return selectedText != null ? selectedText : "";
        }
        return SnippetAiTextSupport.applyReplacements(selectedText, segments, replacements);
    }

    private static String buildSelectionTransformContext(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode,
        List<SnippetAiTextSupport.EditableTextSegment> segments) {

        StringBuilder builder = new StringBuilder();
        builder.append("Snippet language: ").append(snippetLanguage).append("\n");
        builder.append("Fallback natural language for comments and user-facing strings: ").append(fallbackLanguageCode).append("\n");
        builder.append("Use the natural language already dominant in existing comments or user-facing strings when it is clear; otherwise use the fallback language.\n");
        builder.append("Editable text segments JSON:\n").append(SnippetAiTextSupport.toSegmentsJson(segments)).append("\n");
        builder.append("Full snippet for context only:\n").append(AiPromptBuilder.toSafeTextCodeBlock(fullContent));
        return builder.toString();
    }

    private static String buildDescriptionContext(String fullContent, String snippetLanguage, String fallbackLanguageCode) {
        return "Snippet language: " + snippetLanguage + "\n"
            + "Fallback natural language for the description: " + fallbackLanguageCode + "\n"
            + "Use the natural language already dominant in existing comments or user-facing strings when it is clear; otherwise use the fallback language.\n"
            + "Full snippet for context:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(fullContent);
    }

    private static String buildAlternativeContext(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode,
        int maxSolutions) {

        return "Snippet language: " + snippetLanguage + "\n"
            + "Return at most " + maxSolutions + " solutions.\n"
            + "Keep the generated code in the same programming language as the snippet language.\n"
            + "If you add comments or user-facing strings, use the natural language already dominant in the snippet when it is clear; otherwise use fallback language " + fallbackLanguageCode + ".\n"
            + "Full snippet for context:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(fullContent);
    }

    private static String buildTranslationFallbackNote(String fallbackLanguageCode) {
        if (fallbackLanguageCode == null || fallbackLanguageCode.isBlank()) {
            return "";
        }
        return "Source-language hint for existing comments and user-facing strings: "
            + fallbackLanguageCode;
    }

    private static String mergeAdditionalInstructions(String additionalInstructions, String extraLine) {
        StringBuilder builder = new StringBuilder();
        if (additionalInstructions != null && !additionalInstructions.isBlank()) {
            builder.append(additionalInstructions.trim());
        }
        if (extraLine != null && !extraLine.isBlank()) {
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(extraLine.trim());
        }
        return builder.isEmpty() ? null : builder.toString();
    }
}
