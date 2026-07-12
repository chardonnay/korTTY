package de.kortty.core;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Shared request/response workflow helpers for snippet-editor AI actions.
 */
public final class SnippetAiWorkflowSupport {
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s'\"<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOWNLOAD_COMMAND_PATTERN =
        Pattern.compile("(?i)(?:^|[\\s;&|()])(?:curl|wget)(?:\\s|$)");
    private static final Pattern TEMP_FILE_PATTERN =
        Pattern.compile("(?i)(?:/tmp/|\\$TMPDIR\\b|\\$\\{TMPDIR}\\b|\\bmktemp\\b)");

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

    public static String correctSnippetDescription(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String description,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode) throws Exception {

        AiRequest request = new AiRequest(
            AiAction.CORRECT_SNIPPET_DESCRIPTION,
            fullContent != null ? fullContent : "",
            connectionDisplayName,
            fallbackLanguageCode,
            description,
            snippetLanguage);
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        String rawText = result != null ? result.content() : description;
        String sanitized = AiResponseSanitizer.sanitizeForDisplay(rawText);
        return AiSnippetMetadataSupport.normalizeDescription(sanitized);
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
        String sanitized = AiResponseSanitizer.sanitizeForDisplay(result != null ? result.content() : null);
        return SnippetAiTextSupport.normalizePlainText(sanitized);
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

        return improveSnippetCode(
            aiService,
            usageRecorder,
            fullContent,
            selectedText,
            snippetLanguage,
            connectionDisplayName,
            fallbackLanguageCode,
            improvementTheme,
            additionalInstructions,
            false);
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
        String additionalInstructions,
        boolean allowPlainTextFallback) throws Exception {

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
        return SnippetAiResponseSupport.parseCodeImprovement(
            result != null ? result.content() : null,
            allowPlainTextFallback);
    }

    public static SnippetAiResponseSupport.CodeImprovement assistSnippetCode(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        int cursorOffset,
        int cursorLine,
        int cursorColumn,
        String userInstruction,
        String additionalInstructions,
        boolean includeAiSkills) throws Exception {

        String content = fullContent != null ? fullContent : "";
        AiRequest request = new AiRequest(
            AiAction.ASSIST_SNIPPET_CODE,
            content,
            connectionDisplayName,
            fallbackLanguageCode,
            mergeAdditionalInstructions(userInstruction, additionalInstructions),
            buildAssistantContext(content, snippetLanguage, fallbackLanguageCode, cursorOffset, cursorLine, cursorColumn),
            includeAiSkills);
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

    public static SnippetAiResponseSupport.SnippetSecurityFix applySnippetSecurityFixes(
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
        return SnippetAiResponseSupport.parseSecurityFix(result != null ? result.content() : null);
    }

    /** Rich code analysis: summary + external dependencies (with reduce/replace suggestions) + categorized improvements. */
    public static SnippetAiResponseSupport.ScriptAnalysis analyzeSnippetCode(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        String additionalInstructions) throws Exception {

        AiRequest request = new AiRequest(
            AiAction.ANALYZE_SNIPPET_CODE,
            fullContent,
            connectionDisplayName,
            fallbackLanguageCode,
            additionalInstructions,
            buildAnalysisContext(fullContent, snippetLanguage, fallbackLanguageCode));
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        return SnippetAiResponseSupport.parseScriptAnalysis(result != null ? result.content() : null);
    }

    /** Applies the user-selected improvements + dependency suggestions; returns the same shape as the security-fix flow. */
    public static SnippetAiResponseSupport.SnippetSecurityFix applySnippetImprovements(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        List<SnippetAiResponseSupport.ScriptImprovement> improvements,
        List<SnippetAiResponseSupport.ScriptDependency> dependencies,
        String additionalInstructions) throws Exception {

        AiRequest request = new AiRequest(
            AiAction.APPLY_SNIPPET_IMPROVEMENTS,
            fullContent,
            connectionDisplayName,
            fallbackLanguageCode,
            additionalInstructions,
            buildImprovementApplyContext(fullContent, snippetLanguage, fallbackLanguageCode, improvements, dependencies));
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        return SnippetAiResponseSupport.parseSecurityFix(result != null ? result.content() : null);
    }

    public static SnippetAiResponseSupport.MermaidDiagram generateSnippetMermaid(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        String additionalInstructions) throws Exception {

        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_MERMAID,
            fullContent,
            connectionDisplayName,
            fallbackLanguageCode,
            additionalInstructions,
            buildMermaidContext(fullContent, snippetLanguage, fallbackLanguageCode));
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        return SnippetAiResponseSupport.parseMermaidDiagram(result != null ? result.content() : null);
    }

    public static SnippetAiResponseSupport.OneLinerSuggestion generateCompactOneLiner(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        String additionalInstructions) throws Exception {

        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_ONE_LINER,
            fullContent,
            connectionDisplayName,
            fallbackLanguageCode,
            additionalInstructions,
            buildOneLinerContext(fullContent, snippetLanguage));
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        SnippetAiResponseSupport.OneLinerSuggestion suggestion =
            SnippetAiResponseSupport.parseOneLinerSuggestion(result != null ? result.content() : null);
        return isAllowedGeneratedOneLiner(suggestion, fullContent)
            ? suggestion
            : new SnippetAiResponseSupport.OneLinerSuggestion("");
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

    private static String buildAssistantContext(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode,
        int cursorOffset,
        int cursorLine,
        int cursorColumn) {

        String content = fullContent != null ? fullContent : "";
        int safeOffset = Math.max(0, Math.min(cursorOffset, content.length()));
        int safeLine = Math.max(1, cursorLine);
        int safeColumn = Math.max(1, cursorColumn);
        return "Snippet language: " + snippetLanguage + "\n"
            + "Natural language for summary: " + fallbackLanguageCode + "\n"
            + "Cursor offset: " + safeOffset + "\n"
            + "Cursor line: " + safeLine + "\n"
            + "Cursor column: " + safeColumn + "\n"
            + "The cursor marks the user's focal point. Apply the user instruction to the whole snippet when needed, "
            + "but avoid unrelated rewrites.\n"
            + "Full snippet:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(content);
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

    private static String buildAnalysisContext(String fullContent, String snippetLanguage, String fallbackLanguageCode) {
        return "Snippet language: " + snippetLanguage + "\n"
            + "Natural language for the analysis: " + fallbackLanguageCode + "\n"
            + "Explain in plain language what the script does. List external dependencies (other scripts, "
            + "programs or services) with a reduce-or-replace suggestion for each. List concrete, individually-"
            + "applicable improvements categorized as security, optimization or design. Only report what this code supports.\n"
            + "Line-numbered snippet:\n"
            + lineNumberedTextBlock(fullContent)
            + "\nFull snippet:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(fullContent);
    }

    private static String buildImprovementApplyContext(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode,
        List<SnippetAiResponseSupport.ScriptImprovement> improvements,
        List<SnippetAiResponseSupport.ScriptDependency> dependencies) {

        StringBuilder items = new StringBuilder();
        if (improvements != null) {
            for (SnippetAiResponseSupport.ScriptImprovement improvement : improvements) {
                items.append(improvement.id()).append(" [").append(improvement.category())
                    .append('/').append(improvement.severity()).append("] ").append(improvement.title())
                    .append("\nRecommendation: ").append(improvement.recommendation()).append("\n\n");
            }
        }
        if (dependencies != null) {
            for (SnippetAiResponseSupport.ScriptDependency dependency : dependencies) {
                items.append(dependency.id()).append(" [dependency] ").append(dependency.name())
                    .append(" (").append(dependency.kind()).append(")\nReduce/replace: ")
                    .append(dependency.suggestion()).append("\n\n");
            }
        }
        return "Snippet language: " + snippetLanguage + "\n"
            + "Natural language for the summary: " + fallbackLanguageCode + "\n"
            + "Selected items to apply (each tagged with its id — echo the id back in changes[].finding):\n"
            + AiPromptBuilder.toSafeTextCodeBlock(items.toString().strip())
            + "\nFull snippet to update:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(fullContent);
    }

    private static String buildMermaidContext(String fullContent, String snippetLanguage, String fallbackLanguageCode) {
        return "Snippet language: " + snippetLanguage + "\n"
            + "Diagram label language: " + fallbackLanguageCode + "\n"
            + "Generate one compact logical-structure Mermaid flowchart for this snippet. "
            + "Use only relationships visible in the code. "
            + "Use only flowchart TD, stable start_1([\"Start\"]) and stop_1([\"Stop\"]) terminal nodes, separately declared quoted action/decision nodes, "
            + "--> edges, optional yes/no edge labels, and class statements. "
            + "Assign every node exactly one of the semantic classes setup, work, success, and failure. "
            + "Use stable descriptive node ids and do not copy raw source lines into labels; summarize them. "
            + "Do not use frontmatter, directives, comments, custom styles/colors, callbacks, URLs, images, icons, HTML, or other Mermaid syntax.\n"
            + "Also return codeReferences. Each entry must map a declared nodeId and its exact visible label to a small relevant source range. "
            + "Create one codeReferences entry for every visible action and decision node, excluding start_1 and stop_1. "
            + "Use only the 1-based line numbers shown in the line-numbered snippet. "
            + "When one diagram element summarizes a block, use the smallest source range that covers that block.\n"
            + "Line-numbered snippet:\n"
            + lineNumberedTextBlock(fullContent)
            + "\n"
            + "Full snippet:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(fullContent);
    }

    private static String buildOneLinerContext(String fullContent, String snippetLanguage) {
        return "Snippet language: " + snippetLanguage + "\n"
            + "Generate a compact one-liner, not an embedded/base64 wrapper. "
            + "Use only the provided snippet content. Do not download code, do not reference external URLs, and do not invent files or endpoints. "
            + "For shell snippets, use shell syntax on one line. "
            + "For Python, Perl, or Ruby snippets, use an interpreter command such as python3 -c, perl -e, or ruby -e when needed. "
            + "Preserve behavior and quote safely.\n"
            + "Full snippet:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(fullContent);
    }

    private static boolean isAllowedGeneratedOneLiner(
        SnippetAiResponseSupport.OneLinerSuggestion suggestion,
        String fullContent) {

        if (suggestion == null || !suggestion.isUsable()) {
            return false;
        }
        String command = suggestion.command();
        String source = fullContent != null ? fullContent : "";
        if (command.contains("<<")) {
            return false;
        }
        if (containsIntroducedPattern(DOWNLOAD_COMMAND_PATTERN, command, source)
            || containsIntroducedPattern(TEMP_FILE_PATTERN, command, source)) {
            return false;
        }
        Matcher matcher = URL_PATTERN.matcher(command);
        while (matcher.find()) {
            if (!source.contains(matcher.group())) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsIntroducedPattern(Pattern pattern, String command, String source) {
        Matcher matcher = pattern.matcher(command);
        if (!matcher.find()) {
            return false;
        }
        return !pattern.matcher(source).find();
    }

    private static String lineNumberedTextBlock(String text) {
        String value = text != null ? text : "";
        String[] lines = value.split("\\R", -1);
        int width = String.valueOf(Math.max(1, lines.length)).length();
        StringBuilder builder = new StringBuilder("```text\n");
        for (int i = 0; i < lines.length; i++) {
            builder.append(String.format(java.util.Locale.ROOT, "%" + width + "d | %s%n", i + 1, lines[i]));
        }
        builder.append("```");
        return builder.toString();
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
