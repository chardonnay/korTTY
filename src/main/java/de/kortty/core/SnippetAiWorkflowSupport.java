package de.kortty.core;

import java.util.List;
import java.util.stream.Collectors;

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
        boolean wholeSnippet,
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
            buildAlternativeContext(fullContent, selectedText, wholeSnippet, snippetLanguage, fallbackLanguageCode, maxSolutions));
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        return SnippetAiResponseSupport.parseAlternativeSolutions(
            result != null ? result.content() : null,
            maxSolutions);
    }

    public static SnippetAiResponseSupport.CompletionSuggestion completeSnippetCode(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        int cursorOffset,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        String additionalInstructions) throws Exception {

        AiRequest request = new AiRequest(
            AiAction.COMPLETE_SNIPPET_CODE,
            fullContent,
            connectionDisplayName,
            fallbackLanguageCode,
            additionalInstructions,
            buildCompletionContext(fullContent, cursorOffset, snippetLanguage));
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        return SnippetAiResponseSupport.parseCompletionSuggestion(result != null ? result.content() : null);
    }

    public static List<SnippetAiResponseSupport.CodeReviewFinding> reviewSnippetCode(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String selectedText,
        boolean wholeSnippet,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        String reviewTheme,
        String additionalInstructions) throws Exception {

        AiRequest request = new AiRequest(
            AiAction.REVIEW_SNIPPET_CODE,
            wholeSnippet ? fullContent : selectedText,
            connectionDisplayName,
            fallbackLanguageCode,
            mergeAdditionalInstructions(reviewTheme, additionalInstructions),
            buildSelectedCodeContext(fullContent, selectedText, wholeSnippet, snippetLanguage, fallbackLanguageCode));
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        return SnippetAiResponseSupport.parseCodeReviewFindings(result != null ? result.content() : null);
    }

    public static SnippetAiResponseSupport.CodeImprovement improveSnippetCode(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String selectedText,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        String improvementTheme,
        String additionalInstructions) throws Exception {

        AiRequest request = new AiRequest(
            AiAction.IMPROVE_SNIPPET_CODE,
            selectedText,
            connectionDisplayName,
            fallbackLanguageCode,
            mergeAdditionalInstructions(improvementTheme, additionalInstructions),
            buildSelectedCodeContext(fullContent, selectedText, false, snippetLanguage, fallbackLanguageCode));
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        return SnippetAiResponseSupport.parseCodeImprovement(result != null ? result.content() : null);
    }

    public static List<SnippetAiResponseSupport.SecurityFinding> reviewSnippetSecurity(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        String additionalInstructions) throws Exception {

        AiRequest request = new AiRequest(
            AiAction.SECURITY_REVIEW_SNIPPET_CODE,
            fullContent,
            connectionDisplayName,
            fallbackLanguageCode,
            additionalInstructions,
            buildSecurityContext(fullContent, snippetLanguage, fallbackLanguageCode));
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        return SnippetAiResponseSupport.parseSecurityFindings(result != null ? result.content() : null);
    }

    public static SnippetAiResponseSupport.CodeImprovement applySnippetSecurityFixes(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        List<SnippetAiResponseSupport.SecurityFinding> selectedFindings,
        String additionalInstructions) throws Exception {

        AiRequest request = new AiRequest(
            AiAction.APPLY_SNIPPET_SECURITY_FIXES,
            fullContent,
            connectionDisplayName,
            fallbackLanguageCode,
            additionalInstructions,
            buildSecurityFixContext(fullContent, snippetLanguage, fallbackLanguageCode, selectedFindings));
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        return SnippetAiResponseSupport.parseCodeImprovement(result != null ? result.content() : null);
    }

    public static SnippetAiResponseSupport.PlantUmlDiagram generateSnippetPlantUml(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        String additionalInstructions) throws Exception {

        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_PLANTUML,
            fullContent,
            connectionDisplayName,
            fallbackLanguageCode,
            additionalInstructions,
            buildPlantUmlContext(fullContent, snippetLanguage, fallbackLanguageCode));
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        return SnippetAiResponseSupport.parsePlantUmlDiagram(result != null ? result.content() : null);
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
        String targetText,
        boolean wholeSnippet,
        String snippetLanguage,
        String fallbackLanguageCode,
        int maxSolutions) {

        return "Snippet language: " + snippetLanguage + "\n"
            + "Alternative target scope: " + (wholeSnippet ? "full snippet" : "selected code region") + "\n"
            + "Return at most " + maxSolutions + " solutions.\n"
            + "Keep the generated code in the same programming language as the snippet language.\n"
            + "Each solution code must replace exactly the target scope, not any surrounding context.\n"
            + "If you add comments or user-facing strings, use the natural language already dominant in the snippet when it is clear; otherwise use fallback language " + fallbackLanguageCode + ".\n"
            + "Target scope to replace:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(targetText)
            + "\n"
            + "Full snippet for context:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(fullContent);
    }

    private static String buildCompletionContext(String fullContent, int cursorOffset, String snippetLanguage) {
        String content = fullContent != null ? fullContent : "";
        int safeOffset = Math.max(0, Math.min(cursorOffset, content.length()));
        return "Snippet language: " + snippetLanguage + "\n"
            + "Cursor offset: " + safeOffset + "\n"
            + "Text before cursor:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(content.substring(0, safeOffset))
            + "\nText after cursor:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(content.substring(safeOffset));
    }

    private static String buildSelectedCodeContext(
        String fullContent,
        String selectedText,
        boolean wholeSnippet,
        String snippetLanguage,
        String fallbackLanguageCode) {

        return "Snippet language: " + snippetLanguage + "\n"
            + "Natural language for report text: " + fallbackLanguageCode + "\n"
            + "Scope: " + (wholeSnippet ? "full snippet" : "selected code region") + "\n"
            + "Full snippet for context:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(fullContent)
            + "\nSelected code region:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(wholeSnippet ? fullContent : selectedText);
    }

    private static String buildSecurityContext(String fullContent, String snippetLanguage, String fallbackLanguageCode) {
        return "Snippet language: " + snippetLanguage + "\n"
            + "Natural language for the security report: " + fallbackLanguageCode + "\n"
            + "Use secure-by-default guidance for the snippet language, but only report issues supported by this code.\n"
            + "Full snippet:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(fullContent);
    }

    private static String buildSecurityFixContext(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode,
        List<SnippetAiResponseSupport.SecurityFinding> selectedFindings) {

        String findingsText = selectedFindings != null
            ? selectedFindings.stream()
                .map(finding -> finding.id() + " [" + finding.severity() + "] "
                    + finding.title() + "\nImpact: " + finding.impact()
                    + "\nRecommendation: " + finding.recommendation())
                .collect(Collectors.joining("\n\n"))
            : "";
        return "Snippet language: " + snippetLanguage + "\n"
            + "Natural language for the summary: " + fallbackLanguageCode + "\n"
            + "Selected security findings to fix:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(findingsText)
            + "\nFull snippet to update:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(fullContent);
    }

    private static String buildPlantUmlContext(String fullContent, String snippetLanguage, String fallbackLanguageCode) {
        return "Snippet language: " + snippetLanguage + "\n"
            + "Diagram label language: " + fallbackLanguageCode + "\n"
            + "Generate one compact logical-structure PlantUML diagram for this snippet. "
            + "Use only relationships visible in the code. "
            + "For scripts and imperative code, generate only a simple activity diagram with start, activity lines, if/else branches, and stop. "
            + "Do not use component/package/class/object/actor/usecase blocks for script variables or commands. "
            + "Do not copy raw source lines into PlantUML; summarize them as activity labels.\n"
            + "Every action line between start and stop must use :Action label; syntax.\n"
            + "Full snippet:\n"
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
