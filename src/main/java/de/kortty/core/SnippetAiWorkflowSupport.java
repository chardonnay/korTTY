package de.kortty.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Shared request/response workflow helpers for snippet-editor AI actions.
 */
public final class SnippetAiWorkflowSupport {
    private static final int MAX_MANDATORY_REQUIREMENTS_PER_APPLY_STAGE = 6;
    private static final int MAX_ANALYSIS_ITEMS_PER_APPLY_STAGE = 6;
    private static final long MAX_COLLAPSED_STAGE_RETRY_COMPLETION_TOKENS = 4_096L;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s'\"<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOWNLOAD_COMMAND_PATTERN =
        Pattern.compile("(?i)(?:^|[\\s;&|()])(?:curl|wget)(?:\\s|$)");
    private static final Pattern TEMP_FILE_PATTERN =
        Pattern.compile("(?i)(?:/tmp/|\\$TMPDIR\\b|\\$\\{TMPDIR}\\b|\\bmktemp\\b)");

    @FunctionalInterface
    public interface UsageRecorder {
        void record(AiRequest request, AiExecutionResult result);
    }

    public enum ImprovementApplyPhase {
        ANALYSIS_ITEMS,
        HARDENING,
        INPUT_HARDENING
    }

    public enum ImprovementApplyProgressState {
        PENDING,
        RUNNING,
        RETRYING,
        COMPLETED,
        FAILED
    }

    /** One visible checklist entry handled by a staged Full-code-analysis apply request. */
    public record ImprovementApplyWorkItem(String id, String label, String category, String severity) {
        public ImprovementApplyWorkItem {
            id = id != null ? id.strip() : "";
            label = label != null ? label.strip() : "";
            category = category != null ? category.strip() : "";
            severity = severity != null ? severity.strip() : "";
        }
    }

    public record ImprovementApplyProgress(
        ImprovementApplyPhase phase,
        int stage,
        int totalStages,
        int firstRequirement,
        int lastRequirement,
        int phaseRequirementCount,
        String detail,
        List<ImprovementApplyWorkItem> workItems,
        ImprovementApplyProgressState state,
        AiTokenUsage cumulativeUsage) {

        public ImprovementApplyProgress {
            detail = detail != null ? detail.strip() : "";
            workItems = workItems != null ? List.copyOf(workItems) : List.of();
            state = state != null ? state : ImprovementApplyProgressState.PENDING;
        }

        /** Compatibility constructor retained for existing callers and progress-text tests. */
        public ImprovementApplyProgress(
                ImprovementApplyPhase phase,
                int stage,
                int totalStages,
                int firstRequirement,
                int lastRequirement,
                int phaseRequirementCount,
                String detail,
                boolean retry) {
            this(
                phase,
                stage,
                totalStages,
                firstRequirement,
                lastRequirement,
                phaseRequirementCount,
                detail,
                List.of(),
                retry ? ImprovementApplyProgressState.RETRYING : ImprovementApplyProgressState.RUNNING,
                null);
        }

        public boolean retry() {
            return state == ImprovementApplyProgressState.RETRYING;
        }
    }

    @FunctionalInterface
    public interface ImprovementApplyProgressListener {
        void onProgress(ImprovementApplyProgress progress);
    }

    private SnippetAiWorkflowSupport() {
    }

    public static String correctSelectionText(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String selectedText,
        int selectionStart,
        int selectionEnd,
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
            selectionStart,
            selectionEnd,
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
        int selectionStart,
        int selectionEnd,
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
            selectionStart,
            selectionEnd,
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
            buildCompletionContext(fullContent, cursorOffset, snippetLanguage, fallbackLanguageCode));
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
            buildSelectedCodeContext(
                fullContent, selectedText, wholeSnippet, snippetLanguage, fallbackLanguageCode, false));
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

        String content = fullContent != null ? fullContent : "";
        String selection = selectedText != null ? selectedText : "";
        boolean wholeSnippet = content.equals(selection);
        AiRequest request = new AiRequest(
            AiAction.IMPROVE_SNIPPET_CODE,
            selectedText,
            connectionDisplayName,
            fallbackLanguageCode,
            mergeAdditionalInstructions(improvementTheme, additionalInstructions),
            buildSelectedCodeContext(
                fullContent, selectedText, wholeSnippet, snippetLanguage, fallbackLanguageCode, true));
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        rejectTruncatedReplacement(result);
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
        rejectTruncatedReplacement(result);
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
        rejectTruncatedReplacement(result);
        return SnippetAiResponseSupport.parseSecurityFix(result != null ? result.content() : null);
    }

    /**
     * Rich code analysis without any Mermaid payload. The flowchart is fetched by a separate dedicated
     * {@link AiAction#GENERATE_SNIPPET_MERMAID} request once the analysis dialog opens: the focused
     * diagram prompt yields markedly better flowcharts on local models than the former combined request,
     * and the diagram loads asynchronously while the analysis is already visible.
     */
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
        SnippetAiResponseSupport.ScriptAnalysis analysis =
            SnippetAiResponseSupport.parseScriptAnalysis(result != null ? result.content() : null);
        if (!analysis.isUsable()) {
            throw new IllegalStateException("AI code analysis returned no usable analysis.");
        }
        return analysis;
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
        String additionalInstructions,
        String mandatoryHardeningInstructions) throws Exception {

        return applySnippetImprovements(
            aiService,
            usageRecorder,
            fullContent,
            snippetLanguage,
            connectionDisplayName,
            fallbackLanguageCode,
            improvements,
            dependencies,
            additionalInstructions,
            mandatoryHardeningInstructions,
            null,
            null);
    }

    /**
     * Applies a Full-code-analysis selection in bounded sequential stages. Each stage receives the
     * complete result of the previous stage, while the editor remains untouched until every stage and
     * the final cumulative hardening verification succeed.
     */
    public static SnippetAiResponseSupport.SnippetSecurityFix applySnippetImprovements(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        List<SnippetAiResponseSupport.ScriptImprovement> improvements,
        List<SnippetAiResponseSupport.ScriptDependency> dependencies,
        String additionalInstructions,
        String classicHardeningInstructions,
        String inputHardeningInstructions,
        ImprovementApplyProgressListener progressListener) throws Exception {

        List<MandatoryRequirement> classicRequirements =
            extractMandatoryRequirements(classicHardeningInstructions, 0);
        List<MandatoryRequirement> inputRequirements =
            extractMandatoryRequirements(inputHardeningInstructions, classicRequirements.size());
        List<MandatoryRequirement> allRequirements = new ArrayList<>(classicRequirements.size() + inputRequirements.size());
        allRequirements.addAll(classicRequirements);
        allRequirements.addAll(inputRequirements);

        List<ImprovementApplyStagePlan> stagePlans = buildImprovementApplyStagePlans(
            improvements, dependencies, classicRequirements, inputRequirements);
        for (ImprovementApplyStagePlan stagePlan : stagePlans) {
            notifyImprovementProgress(progressListener, stagePlan.progress());
        }

        String currentContent = fullContent != null ? fullContent : "";
        Set<String> completedRequirementIds = new LinkedHashSet<>();
        Set<String> summaries = new LinkedHashSet<>();
        List<SnippetAiResponseSupport.SecurityChange> mergedChanges = new ArrayList<>();
        UsageAccumulator usageAccumulator = new UsageAccumulator();

        for (ImprovementApplyStagePlan stagePlan : stagePlans) {
            checkImprovementApplyInterrupted();
            ImprovementApplyProgress running = progressWithState(
                stagePlan.progress(), ImprovementApplyProgressState.RUNNING, usageAccumulator.total());
            notifyImprovementProgress(progressListener, running);
            try {
                SnippetAiResponseSupport.SnippetSecurityFix fix = executeImprovementApplyStage(
                    aiService, usageRecorder, currentContent, snippetLanguage, connectionDisplayName,
                    fallbackLanguageCode,
                    stagePlan.analysisStage().improvements(),
                    stagePlan.analysisStage().dependencies(),
                    additionalInstructions,
                    stagePlan.requirements(),
                    stagePlan.progress().stage() > 1,
                    progressListener,
                    running,
                    usageAccumulator);
                currentContent = fix.replacement();
                completedRequirementIds.addAll(
                    stagePlan.requirements().stream().map(MandatoryRequirement::id).toList());
                addStageResult(fix, summaries, mergedChanges);
                notifyImprovementProgress(
                    progressListener,
                    progressWithState(running, ImprovementApplyProgressState.COMPLETED, usageAccumulator.total()));
            } catch (Exception e) {
                notifyImprovementProgress(
                    progressListener,
                    progressWithState(running, ImprovementApplyProgressState.FAILED, usageAccumulator.total()));
                throw e;
            }
        }

        SnippetAiResponseSupport.SnippetSecurityFix combined = new SnippetAiResponseSupport.SnippetSecurityFix(
            currentContent,
            String.join("\n\n", summaries),
            List.copyOf(mergedChanges),
            List.copyOf(completedRequirementIds));
        rejectIncompleteMandatoryRequirements(combined, allRequirements);
        return combined;
    }

    private static SnippetAiResponseSupport.SnippetSecurityFix executeImprovementApplyStage(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        List<SnippetAiResponseSupport.ScriptImprovement> improvements,
        List<SnippetAiResponseSupport.ScriptDependency> dependencies,
        String additionalInstructions,
        List<MandatoryRequirement> mandatoryRequirements,
        boolean preservePriorStageWork,
        ImprovementApplyProgressListener progressListener,
        ImprovementApplyProgress progress,
        UsageAccumulator usageAccumulator) throws Exception {

        checkImprovementApplyInterrupted();

        String attemptContent = fullContent;
        StageRepairReason repairReason = StageRepairReason.NONE;
        List<MandatoryRequirement> requirementsNeedingRepair = List.of();
        List<String> analysisIdsNeedingRepair = List.of();
        // Set when the single repair attempt was triggered ONLY by unechoed analysis ids: the
        // first attempt's replacement already met the stage's acceptance contract, so a failed
        // repair round-trip must fall back to it instead of aborting the whole apply.
        SnippetAiResponseSupport.SnippetSecurityFix echoOnlyRepairFallback = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            checkImprovementApplyInterrupted();
            boolean repairAttempt = repairReason != StageRepairReason.NONE;
            if (repairAttempt) {
                notifyImprovementProgress(
                    progressListener,
                    progressWithState(progress, ImprovementApplyProgressState.RETRYING, usageAccumulator.total()));
            }
            AiRequest request = new AiRequest(
                AiAction.APPLY_SNIPPET_IMPROVEMENTS,
                attemptContent,
                connectionDisplayName,
                fallbackLanguageCode,
                additionalInstructions,
                buildImprovementApplyContext(
                    snippetLanguage, fallbackLanguageCode, improvements, dependencies,
                    mandatoryRequirements,
                    preservePriorStageWork || repairReason == StageRepairReason.MISSING_REQUIREMENTS,
                    repairReason,
                    requirementsNeedingRepair,
                    analysisIdsNeedingRepair));
            AiExecutionResult result = aiService.execute(request);
            if (result != null && usageRecorder != null) {
                usageRecorder.record(request, result);
            }
            usageAccumulator.add(result != null ? result.usage() : null);
            checkImprovementApplyInterrupted();
            if (echoOnlyRepairFallback != null && result != null && result.outputTruncated()) {
                return echoOnlyRepairFallback;
            }
            rejectTruncatedReplacement(result);
            SnippetAiResponseSupport.SnippetSecurityFix fix =
                SnippetAiResponseSupport.parseSecurityFix(result != null ? result.content() : null);
            boolean rejectedReplacement = fix == null || !fix.isUsable()
                || isIncompleteStagedReplacement(attemptContent, fix.replacement());
            if (rejectedReplacement) {
                if (!repairAttempt && isShortCollapsedStageResult(result, attemptContent)) {
                    repairReason = StageRepairReason.COLLAPSED_REPLACEMENT;
                    continue;
                }
                if (echoOnlyRepairFallback != null) {
                    return echoOnlyRepairFallback;
                }
                throw new FullReplacementRejectedException();
            }
            List<MandatoryRequirement> missingRequirements =
                missingMandatoryRequirements(fix, mandatoryRequirements);
            List<String> unechoedAnalysisIds = unechoedAnalysisItemIds(fix, improvements, dependencies);
            if (!missingRequirements.isEmpty() || !unechoedAnalysisIds.isEmpty()) {
                if (!repairAttempt) {
                    attemptContent = fix.replacement();
                    repairReason = StageRepairReason.MISSING_REQUIREMENTS;
                    requirementsNeedingRepair = missingRequirements;
                    analysisIdsNeedingRepair = unechoedAnalysisIds;
                    echoOnlyRepairFallback = missingRequirements.isEmpty() ? fix : null;
                    continue;
                }
                if (!missingRequirements.isEmpty()) {
                    throw new IncompleteMandatoryRequirementsException(
                        missingRequirements.stream().map(MandatoryRequirement::id).toList());
                }
                // An analysis item still unechoed after the targeted repair attempt is accepted:
                // the item may legitimately require no code change, and the pre-batching per-item
                // stages carried no echo contract either.
            }
            return echoOnlyRepairFallback != null ? mergeRepairedStageFix(echoOnlyRepairFallback, fix) : fix;
        }
        throw new FullReplacementRejectedException();
    }

    /**
     * Staged Full-code-analysis rewrites have a stricter completeness contract than a free-form
     * assistant rewrite: every stage must preserve unrelated source before the next stage receives
     * it. Besides the generic collapse checks, require at least half of a substantial source's
     * non-blank lines so transports without strict JSON-schema support cannot advance a fragment.
     */
    private static boolean isIncompleteStagedReplacement(String original, String replacement) {
        if (SnippetAiResponseSupport.isDegenerateFullReplacement(original, replacement)) {
            return true;
        }
        String source = original != null ? original : "";
        String candidate = replacement != null ? replacement : "";
        long sourceLines = source.lines().filter(line -> !line.isBlank()).count();
        if (sourceLines < 12) {
            return false;
        }
        long candidateLines = candidate.lines().filter(line -> !line.isBlank()).count();
        return candidateLines < Math.max(3L, (sourceLines + 1L) / 2L);
    }

    private static boolean isShortCollapsedStageResult(AiExecutionResult result, String fullContent) {
        if (result == null || result.outputTruncated()) {
            return false;
        }
        AiTokenUsage usage = result.usage();
        if (usage != null && usage.completionTokens() > 0) {
            return usage.completionTokens() <= MAX_COLLAPSED_STAGE_RETRY_COMPLETION_TOKENS;
        }
        String response = result.content() != null ? result.content() : "";
        int sourceLength = fullContent != null ? fullContent.length() : 0;
        int fallbackLimit = Math.max(2_048, Math.min(8_192, sourceLength / 2));
        return response.length() <= fallbackLimit;
    }

    private static void addStageResult(
        SnippetAiResponseSupport.SnippetSecurityFix fix,
        Set<String> summaries,
        List<SnippetAiResponseSupport.SecurityChange> mergedChanges) {

        if (fix == null) {
            return;
        }
        if (fix.summary() != null && !fix.summary().isBlank()) {
            summaries.add(fix.summary().strip());
        }
        for (SnippetAiResponseSupport.SecurityChange change : fix.changes()) {
            if (change != null && !mergedChanges.contains(change)) {
                mergedChanges.add(change);
            }
        }
    }

    private static List<List<MandatoryRequirement>> partitionRequirements(List<MandatoryRequirement> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return List.of();
        }
        List<List<MandatoryRequirement>> batches = new ArrayList<>();
        for (int start = 0; start < requirements.size(); start += MAX_MANDATORY_REQUIREMENTS_PER_APPLY_STAGE) {
            int end = Math.min(requirements.size(), start + MAX_MANDATORY_REQUIREMENTS_PER_APPLY_STAGE);
            batches.add(List.copyOf(requirements.subList(start, end)));
        }
        return List.copyOf(batches);
    }

    /**
     * Builds the exact visible work plan used by the staged apply workflow. The UI can call this before
     * starting the provider task so every pending improvement and hardening rule is visible immediately.
     */
    public static List<ImprovementApplyProgress> planSnippetImprovements(
        List<SnippetAiResponseSupport.ScriptImprovement> improvements,
        List<SnippetAiResponseSupport.ScriptDependency> dependencies,
        String classicHardeningInstructions,
        String inputHardeningInstructions) {

        List<MandatoryRequirement> classicRequirements =
            extractMandatoryRequirements(classicHardeningInstructions, 0);
        List<MandatoryRequirement> inputRequirements =
            extractMandatoryRequirements(inputHardeningInstructions, classicRequirements.size());
        return buildImprovementApplyStagePlans(
            improvements, dependencies, classicRequirements, inputRequirements).stream()
            .map(ImprovementApplyStagePlan::progress)
            .toList();
    }

    private static List<ImprovementApplyStagePlan> buildImprovementApplyStagePlans(
        List<SnippetAiResponseSupport.ScriptImprovement> improvements,
        List<SnippetAiResponseSupport.ScriptDependency> dependencies,
        List<MandatoryRequirement> classicRequirements,
        List<MandatoryRequirement> inputRequirements) {

        List<AnalysisApplyStage> analysisStages = buildAnalysisApplyStages(improvements, dependencies);
        List<List<MandatoryRequirement>> classicBatches = partitionRequirements(classicRequirements);
        List<List<MandatoryRequirement>> inputBatches = partitionRequirements(inputRequirements);
        // Preserve the former single-request behaviour for direct callers that provide no work lists.
        if (analysisStages.isEmpty() && classicBatches.isEmpty() && inputBatches.isEmpty()) {
            analysisStages = List.of(new AnalysisApplyStage(List.of(), List.of(), "", List.of()));
        }
        int totalStages = analysisStages.size() + classicBatches.size() + inputBatches.size();
        List<ImprovementApplyStagePlan> plans = new ArrayList<>(totalStages);
        int stage = 0;
        int analysisItemCount = analysisStages.stream()
            .mapToInt(analysisStage -> analysisStage.workItems().size())
            .sum();
        int analysisOffset = 0;
        for (AnalysisApplyStage analysisStage : analysisStages) {
            int firstItem = analysisOffset + 1;
            analysisOffset += Math.max(1, analysisStage.workItems().size());
            plans.add(new ImprovementApplyStagePlan(
                new ImprovementApplyProgress(
                    ImprovementApplyPhase.ANALYSIS_ITEMS,
                    ++stage,
                    totalStages,
                    firstItem,
                    analysisOffset,
                    analysisItemCount,
                    analysisStage.detail(),
                    analysisStage.workItems(),
                    ImprovementApplyProgressState.PENDING,
                    null),
                analysisStage,
                List.of()));
        }
        int classicOffset = 0;
        for (List<MandatoryRequirement> batch : classicBatches) {
            int first = classicOffset + 1;
            classicOffset += batch.size();
            plans.add(new ImprovementApplyStagePlan(
                new ImprovementApplyProgress(
                    ImprovementApplyPhase.HARDENING,
                    ++stage,
                    totalStages,
                    first,
                    classicOffset,
                    classicRequirements.size(),
                    "",
                    workItemsForRequirements(batch, "hardening"),
                    ImprovementApplyProgressState.PENDING,
                    null),
                new AnalysisApplyStage(List.of(), List.of(), "", List.of()),
                batch));
        }
        int inputOffset = 0;
        for (List<MandatoryRequirement> batch : inputBatches) {
            int first = inputOffset + 1;
            inputOffset += batch.size();
            plans.add(new ImprovementApplyStagePlan(
                new ImprovementApplyProgress(
                    ImprovementApplyPhase.INPUT_HARDENING,
                    ++stage,
                    totalStages,
                    first,
                    inputOffset,
                    inputRequirements.size(),
                    "",
                    workItemsForRequirements(batch, "inputHardening"),
                    ImprovementApplyProgressState.PENDING,
                    null),
                new AnalysisApplyStage(List.of(), List.of(), "", List.of()),
                batch));
        }
        return List.copyOf(plans);
    }

    private static List<ImprovementApplyWorkItem> workItemsForRequirements(
        List<MandatoryRequirement> requirements,
        String category) {

        return requirements.stream()
            .map(requirement -> new ImprovementApplyWorkItem(
                requirement.id(), requirement.instruction(), category, ""))
            .toList();
    }

    /**
     * Groups the selected analysis items (improvements first, then dependencies) into bounded
     * batches so a single apply request can address several findings at once. Every stage is a
     * full-file round-trip that re-sends and regenerates the entire snippet, so fewer, larger
     * stages directly cut apply latency; {@link #MAX_ANALYSIS_ITEMS_PER_APPLY_STAGE} bounds the
     * instruction load one stage may carry.
     */
    private static List<AnalysisApplyStage> buildAnalysisApplyStages(
        List<SnippetAiResponseSupport.ScriptImprovement> improvements,
        List<SnippetAiResponseSupport.ScriptDependency> dependencies) {

        List<SnippetAiResponseSupport.ScriptImprovement> safeImprovements = improvements != null
            ? improvements.stream().filter(java.util.Objects::nonNull).toList()
            : List.of();
        List<SnippetAiResponseSupport.ScriptDependency> safeDependencies = dependencies != null
            ? dependencies.stream().filter(java.util.Objects::nonNull).toList()
            : List.of();
        int totalItems = safeImprovements.size() + safeDependencies.size();
        List<AnalysisApplyStage> stages = new ArrayList<>();
        for (int start = 0; start < totalItems; start += MAX_ANALYSIS_ITEMS_PER_APPLY_STAGE) {
            int end = Math.min(totalItems, start + MAX_ANALYSIS_ITEMS_PER_APPLY_STAGE);
            List<SnippetAiResponseSupport.ScriptImprovement> stageImprovements = new ArrayList<>();
            List<SnippetAiResponseSupport.ScriptDependency> stageDependencies = new ArrayList<>();
            List<ImprovementApplyWorkItem> workItems = new ArrayList<>();
            for (int index = start; index < end; index++) {
                if (index < safeImprovements.size()) {
                    SnippetAiResponseSupport.ScriptImprovement improvement = safeImprovements.get(index);
                    stageImprovements.add(improvement);
                    workItems.add(new ImprovementApplyWorkItem(
                        improvement.id(), improvement.title(), improvement.category(), improvement.severity()));
                } else {
                    SnippetAiResponseSupport.ScriptDependency dependency =
                        safeDependencies.get(index - safeImprovements.size());
                    stageDependencies.add(dependency);
                    workItems.add(new ImprovementApplyWorkItem(
                        dependency.id(), dependency.name(), "dependencies", ""));
                }
            }
            stages.add(new AnalysisApplyStage(
                List.copyOf(stageImprovements),
                List.copyOf(stageDependencies),
                analysisStageDetail(workItems),
                List.copyOf(workItems)));
        }
        return List.copyOf(stages);
    }

    private static String analysisStageDetail(List<ImprovementApplyWorkItem> workItems) {
        if (workItems.size() == 1) {
            ImprovementApplyWorkItem item = workItems.get(0);
            return item.id() + " — " + item.label();
        }
        return workItems.stream().map(ImprovementApplyWorkItem::id).collect(Collectors.joining(", "));
    }

    private static void notifyImprovementProgress(
        ImprovementApplyProgressListener listener,
        ImprovementApplyProgress progress) {

        if (listener != null) {
            listener.onProgress(progress);
        }
    }

    private static ImprovementApplyProgress progressWithState(
        ImprovementApplyProgress progress,
        ImprovementApplyProgressState state,
        AiTokenUsage cumulativeUsage) {

        if (progress == null) {
            return null;
        }
        return new ImprovementApplyProgress(
            progress.phase(),
            progress.stage(),
            progress.totalStages(),
            progress.firstRequirement(),
            progress.lastRequirement(),
            progress.phaseRequirementCount(),
            progress.detail(),
            progress.workItems(),
            state,
            cumulativeUsage);
    }

    private static void checkImprovementApplyInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Full-code-analysis apply was cancelled.");
        }
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
        if (result != null && result.outputTruncated()) {
            return new SnippetAiResponseSupport.MermaidDiagram("", "");
        }
        SnippetAiResponseSupport.MermaidDiagram diagram =
            SnippetAiResponseSupport.parseMermaidDiagram(result != null ? result.content() : null);
        if (diagram.isUsable()
            && !SnippetDiagramSupport.validateMermaidForSnippet(
                diagram.mermaid(), fullContent, diagram.codeReferences(), fallbackLanguageCode).valid()) {
            return new SnippetAiResponseSupport.MermaidDiagram("", "");
        }
        return diagram;
    }

    /**
     * Refuses any incomplete replacement, and names the reason. Both cases are fail-closed, but
     * reporting a dropped connection as an output-token limit sends the next reader after the
     * wrong fix — raising a budget that was never the constraint.
     */
    private static void rejectTruncatedReplacement(AiExecutionResult result) {
        if (result == null || !result.outputTruncated()) {
            return;
        }
        if (result.streamInterrupted()) {
            throw new ResponseStreamInterruptedException();
        }
        throw new OutputTokenLimitReachedException();
    }

    /** Signals a fail-closed code-replacement response that ended at its output-token limit. */
    public static final class OutputTokenLimitReachedException extends IllegalStateException {
        public OutputTokenLimitReachedException() {
            super("AI response reached its output-token safety limit.");
        }
    }

    /** Signals a fail-closed code-replacement response whose connection was cut mid-answer. */
    public static final class ResponseStreamInterruptedException extends IllegalStateException {
        public ResponseStreamInterruptedException() {
            super("The connection to the AI provider was lost before the response was complete.");
        }
    }

    /** Signals an unusable or incomplete whole-snippet stage; no intermediate result may be applied. */
    public static final class FullReplacementRejectedException extends IllegalStateException {
        public FullReplacementRejectedException() {
            super("AI response did not contain a complete full-snippet replacement.");
        }
    }

    /** Signals that the response did not prove implementation of every selected hardening rule. */
    public static final class IncompleteMandatoryRequirementsException extends IllegalStateException {
        private final List<String> missingRequirementIds;

        public IncompleteMandatoryRequirementsException(List<String> missingRequirementIds) {
            super("AI response did not implement every selected hardening requirement. Missing requirement IDs: "
                + String.join(", ", missingRequirementIds != null ? missingRequirementIds : List.of()));
            this.missingRequirementIds = missingRequirementIds != null
                ? List.copyOf(missingRequirementIds)
                : List.of();
        }

        public List<String> missingRequirementIds() {
            return missingRequirementIds;
        }
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
            buildOneLinerContext(fullContent, snippetLanguage, fallbackLanguageCode));
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
        int selectionStart,
        int selectionEnd,
        String snippetLanguage,
        String connectionDisplayName,
        String responseLanguageCode,
        String additionalInstructions) throws Exception {

        String content = fullContent != null ? fullContent : "";
        String selection = selectedText != null ? selectedText : "";
        boolean selectionMatchesContent = selectionStart >= 0
            && selectionEnd >= selectionStart
            && selectionEnd <= content.length()
            && content.substring(selectionStart, selectionEnd).equals(selection);
        List<SnippetAiTextSupport.EditableTextSegment> segments = selectionMatchesContent
            ? SnippetAiTextSupport.extractEditableSegments(content, selectionStart, selectionEnd, snippetLanguage)
            : SnippetAiTextSupport.extractEditableSegments(selection, snippetLanguage);
        if (segments.isEmpty()) {
            return selection;
        }
        AiRequest request = new AiRequest(
            action,
            selection,
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
            return selection;
        }
        return SnippetAiTextSupport.applyReplacements(selection, segments, replacements);
    }

    private static String buildSelectionTransformContext(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode,
        List<SnippetAiTextSupport.EditableTextSegment> segments) {

        StringBuilder builder = new StringBuilder();
        builder.append("Snippet language: ").append(snippetLanguage).append("\n");
        builder.append("Required natural language for editable text: ").append(fallbackLanguageCode).append("\n");
        builder.append("For spelling correction, use that language without translating. For translation, use it as the target language.\n");
        builder.append("Editable text segments JSON:\n").append(SnippetAiTextSupport.toSegmentsJson(segments)).append("\n");
        builder.append("Full snippet for context only:\n").append(AiPromptBuilder.toSafeTextCodeBlock(fullContent));
        return builder.toString();
    }

    private static String buildDescriptionContext(String fullContent, String snippetLanguage, String fallbackLanguageCode) {
        return "Snippet language: " + snippetLanguage + "\n"
            + "Required natural language for the description: " + fallbackLanguageCode + "\n"
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
            + codeTextLanguageInstruction(fallbackLanguageCode, "each returned solution code")
            + "Target scope to replace:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(targetText)
            + "\n"
            + "Full snippet for context:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(fullContent);
    }

    private static String buildCompletionContext(
        String fullContent, int cursorOffset, String snippetLanguage, String fallbackLanguageCode) {
        String content = fullContent != null ? fullContent : "";
        int safeOffset = Math.max(0, Math.min(cursorOffset, content.length()));
        return "Snippet language: " + snippetLanguage + "\n"
            + "Write any generated comments or user-facing strings in language " + fallbackLanguageCode + ".\n"
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
        String fallbackLanguageCode,
        boolean returnsReplacement) {

        StringBuilder builder = new StringBuilder()
            .append("Snippet language: ").append(snippetLanguage).append("\n")
            .append("Natural language for report text: ").append(fallbackLanguageCode).append("\n");
        if (returnsReplacement) {
            builder.append(codeTextLanguageInstruction(
                fallbackLanguageCode,
                wholeSnippet ? "full returned snippet" : "returned selected-code replacement"));
        }
        builder.append("Scope: ").append(wholeSnippet ? "full snippet" : "selected code region").append("\n")
            .append(wholeSnippet ? "Full snippet to replace:\n" : "Full snippet for context:\n")
            .append(AiPromptBuilder.toSafeTextCodeBlock(fullContent));
        if (!wholeSnippet) {
            builder.append("\nSelected code region:\n")
                .append(AiPromptBuilder.toSafeTextCodeBlock(selectedText));
        }
        return builder.toString();
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
            + codeTextLanguageInstruction(fallbackLanguageCode, "full returned snippet")
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
            + codeTextLanguageInstruction(fallbackLanguageCode, "full returned snippet")
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
            + lineNumberedTextBlock(fullContent);
    }

    private static String buildImprovementApplyContext(
        String snippetLanguage,
        String fallbackLanguageCode,
        List<SnippetAiResponseSupport.ScriptImprovement> improvements,
        List<SnippetAiResponseSupport.ScriptDependency> dependencies,
        List<MandatoryRequirement> mandatoryRequirements,
        boolean preservePriorStageWork,
        StageRepairReason repairReason,
        List<MandatoryRequirement> requirementsNeedingRepair,
        List<String> analysisIdsNeedingRepair) {

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
        StringBuilder context = new StringBuilder("Snippet language: ").append(snippetLanguage).append('\n')
            .append("Natural language for the summary: ").append(fallbackLanguageCode).append('\n')
            .append(codeTextLanguageInstruction(fallbackLanguageCode, "full returned snippet"))
            .append(preservePriorStageWork
                ? "This is a later stage of one atomic rewrite. The provided snippet already contains completed work from earlier stages. Preserve every existing behavior and hardening measure unless the current requirements strictly require an adjustment; never revert, remove, or abbreviate earlier work.\n"
                : "")
            .append(repairReason == StageRepairReason.COLLAPSED_REPLACEMENT
                ? "The preceding attempt for this same stage was discarded because it returned an empty or severely collapsed script. This is the single repair attempt: copy the complete input into replacementLines, one source line per array entry, then make only the current requested change. Do not close the JSON object after the header or a partial function.\n"
                : "")
            .append(missingWorkRepairParagraph(repairReason, requirementsNeedingRepair, analysisIdsNeedingRepair))
            .append("Selected analysis items to apply (echo each id in changes[].finding):\n")
            .append(AiPromptBuilder.toSafeTextCodeBlock(items.toString().strip()));
        if (mandatoryRequirements != null && !mandatoryRequirements.isEmpty()) {
            String requirementsText = mandatoryRequirements.stream()
                .map(requirement -> requirement.id() + " " + requirement.instruction())
                .collect(Collectors.joining("\n"));
            context.append("\nMandatory hardening requirements (implement every entry even when the selected-analysis-items block is empty; "
                    + "after implementation echo every requirement id once in implementedRequirements):\n")
                .append(AiPromptBuilder.toSafeTextCodeBlock(requirementsText));
        }
        return context.toString();
    }

    /**
     * A repair attempt triggered only by unechoed analysis ids replaces an already acceptable
     * stage result. Keep the first attempt's user-visible metadata: its summary and change
     * annotations describe work that is still present in the repaired replacement.
     */
    private static SnippetAiResponseSupport.SnippetSecurityFix mergeRepairedStageFix(
        SnippetAiResponseSupport.SnippetSecurityFix first,
        SnippetAiResponseSupport.SnippetSecurityFix repaired) {

        Set<String> summaries = new LinkedHashSet<>();
        if (!first.summary().isBlank()) {
            summaries.add(first.summary());
        }
        if (!repaired.summary().isBlank()) {
            summaries.add(repaired.summary());
        }
        List<SnippetAiResponseSupport.SecurityChange> changes = new ArrayList<>(first.changes());
        for (SnippetAiResponseSupport.SecurityChange change : repaired.changes()) {
            if (change != null && !changes.contains(change)) {
                changes.add(change);
            }
        }
        Set<String> implemented = new LinkedHashSet<>(first.implementedRequirements());
        implemented.addAll(repaired.implementedRequirements());
        return new SnippetAiResponseSupport.SnippetSecurityFix(
            repaired.replacement(),
            String.join("\n\n", summaries),
            List.copyOf(changes),
            List.copyOf(implemented));
    }

    private static String missingWorkRepairParagraph(
        StageRepairReason repairReason,
        List<MandatoryRequirement> requirementsNeedingRepair,
        List<String> analysisIdsNeedingRepair) {

        if (repairReason != StageRepairReason.MISSING_REQUIREMENTS) {
            return "";
        }
        StringBuilder paragraph = new StringBuilder(
            "The preceding attempt returned a complete script but did not verify every requested work item. "
                + "This is the single repair attempt. Re-check and implement every item listed below while preserving all other code.");
        if (!requirementsNeedingRepair.isEmpty()) {
            paragraph.append(" Do not merely echo identifiers: verify the actual behavior and required literals first, "
                    + "then include every requirement id from this stage exactly once in implementedRequirements. "
                    + "Requirements not verified in the preceding answer: ")
                .append(requirementsNeedingRepair.stream().map(MandatoryRequirement::id)
                    .collect(Collectors.joining(", ")))
                .append('.');
        }
        String sanitizedIds = analysisIdsNeedingRepair.stream()
            .map(SnippetAiWorkflowSupport::sanitizedPromptId)
            .filter(id -> !id.isBlank())
            .collect(Collectors.joining(", "));
        if (!sanitizedIds.isEmpty()) {
            paragraph.append(" Selected analysis items whose ids were missing from changes[].finding: ")
                .append(sanitizedIds)
                .append(". Apply each of these items and echo its id in changes[].finding.");
        }
        paragraph.append('\n');
        return paragraph.toString();
    }

    /**
     * Batched analysis stages ask the model to echo every applied item id in changes[].finding. Ids
     * missing from that echo trigger the stage's single targeted repair attempt. Single-item stages
     * keep the pre-batching contract (no echo verification), and a batch item that stays unechoed
     * after the repair attempt is accepted rather than failed — see the caller.
     */
    private static List<String> unechoedAnalysisItemIds(
        SnippetAiResponseSupport.SnippetSecurityFix fix,
        List<SnippetAiResponseSupport.ScriptImprovement> improvements,
        List<SnippetAiResponseSupport.ScriptDependency> dependencies) {

        List<String> expectedIds = new ArrayList<>();
        if (improvements != null) {
            improvements.forEach(improvement -> expectedIds.add(improvement.id()));
        }
        if (dependencies != null) {
            dependencies.forEach(dependency -> expectedIds.add(dependency.id()));
        }
        if (expectedIds.size() < 2 || fix == null) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String id : expectedIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            boolean covered = fix.changes().stream()
                .anyMatch(change -> changeCoversAnalysisId(change.finding(), id));
            if (!covered) {
                missing.add(id);
            }
        }
        return List.copyOf(missing);
    }

    private static boolean changeCoversAnalysisId(String finding, String id) {
        if (finding == null || finding.isBlank()) {
            return false;
        }
        if (finding.equalsIgnoreCase(id)) {
            return true;
        }
        Pattern idPattern = Pattern.compile(
            "(?<![\\p{L}\\p{N}_-])" + Pattern.quote(id) + "(?![\\p{L}\\p{N}_-])",
            Pattern.CASE_INSENSITIVE);
        return idPattern.matcher(finding).find();
    }

    /**
     * Analysis-item ids originate from a model response. Inside the fenced selected-items block
     * they may stay verbatim, but before interpolating one into the unfenced repair instruction
     * paragraph it is reduced to a compact identifier-safe form.
     */
    private static String sanitizedPromptId(String id) {
        if (id == null) {
            return "";
        }
        String sanitized = id.replaceAll("[^\\p{L}\\p{N}_.:-]", "");
        return sanitized.length() > 64 ? sanitized.substring(0, 64) : sanitized;
    }

    private static List<MandatoryRequirement> extractMandatoryRequirements(String instructions, int idOffset) {
        if (instructions == null || instructions.isBlank()) {
            return List.of();
        }
        List<String> rules = instructions.lines()
            .map(String::strip)
            .filter(line -> line.startsWith("- ") && line.length() > 2)
            .map(line -> line.substring(2).strip())
            .filter(line -> !line.isBlank())
            .toList();
        java.util.ArrayList<MandatoryRequirement> requirements = new java.util.ArrayList<>(rules.size());
        for (int index = 0; index < rules.size(); index++) {
            requirements.add(new MandatoryRequirement(
                String.format("HARDENING-%02d", idOffset + index + 1), rules.get(index)));
        }
        return List.copyOf(requirements);
    }

    private static void rejectIncompleteMandatoryRequirements(
            SnippetAiResponseSupport.SnippetSecurityFix fix,
            List<MandatoryRequirement> mandatoryRequirements) {
        List<String> missing = missingMandatoryRequirements(fix, mandatoryRequirements).stream()
            .map(MandatoryRequirement::id)
            .toList();
        if (!missing.isEmpty()) {
            throw new IncompleteMandatoryRequirementsException(missing);
        }
    }

    private static List<MandatoryRequirement> missingMandatoryRequirements(
            SnippetAiResponseSupport.SnippetSecurityFix fix,
            List<MandatoryRequirement> mandatoryRequirements) {
        if (mandatoryRequirements == null || mandatoryRequirements.isEmpty()) {
            return List.of();
        }
        if (fix == null || !fix.isUsable()) {
            return List.copyOf(mandatoryRequirements);
        }
        return mandatoryRequirements.stream()
            .filter(requirement -> !fix.implementedRequirements().contains(requirement.id())
                || !containsRequiredHardeningLiterals(fix.replacement(), requirement.instruction()))
            .toList();
    }

    private record MandatoryRequirement(String id, String instruction) {
    }

    private record AnalysisApplyStage(
        List<SnippetAiResponseSupport.ScriptImprovement> improvements,
        List<SnippetAiResponseSupport.ScriptDependency> dependencies,
        String detail,
        List<ImprovementApplyWorkItem> workItems) {
    }

    private record ImprovementApplyStagePlan(
        ImprovementApplyProgress progress,
        AnalysisApplyStage analysisStage,
        List<MandatoryRequirement> requirements) {
    }

    private static final class UsageAccumulator {
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private boolean reported;

        void add(AiTokenUsage usage) {
            if (usage == null) {
                return;
            }
            reported = true;
            promptTokens = saturatedAdd(promptTokens, usage.promptTokens());
            completionTokens = saturatedAdd(completionTokens, usage.completionTokens());
            totalTokens = saturatedAdd(totalTokens, usage.totalTokens());
        }

        AiTokenUsage total() {
            return reported ? new AiTokenUsage(promptTokens, completionTokens, totalTokens) : null;
        }

        private static long saturatedAdd(long left, long right) {
            long safeRight = Math.max(0L, right);
            return left > Long.MAX_VALUE - safeRight ? Long.MAX_VALUE : left + safeRight;
        }
    }

    private enum StageRepairReason {
        NONE,
        COLLAPSED_REPLACEMENT,
        MISSING_REQUIREMENTS
    }

    /** Verifies language-independent option/configuration literals explicitly promised by selected rules. */
    private static boolean containsRequiredHardeningLiterals(String replacement, String instruction) {
        if (replacement == null || instruction == null) {
            return false;
        }
        List<String> required = new java.util.ArrayList<>();
        if (instruction.contains("--dry-run")) {
            required.add("--dry-run");
        }
        if (instruction.contains("--yes")) {
            required.add("--yes");
        }
        if (instruction.contains("--help/usage")) {
            required.add("--help");
        }
        if (instruction.contains("--verbose/-v")) {
            required.add("--verbose");
            required.add("-v");
        }
        if (instruction.contains("MAX_FILE_SIZE=")) {
            required.add("MAX_FILE_SIZE");
        }
        if (instruction.contains("FORCE")) {
            required.add("FORCE");
        }
        if (instruction.contains("--force")) {
            required.add("--force");
        }
        if (instruction.contains("\"SECURITY:\"")) {
            required.add("SECURITY:");
        }
        return required.stream().allMatch(replacement::contains);
    }

    private static String buildMermaidContext(String fullContent, String snippetLanguage, String fallbackLanguageCode) {
        // The line-numbered block is the request's only source copy; AiPromptBuilder intentionally
        // suppresses its generic raw-script block for this action.
        return "Snippet language: " + snippetLanguage + "\n"
            + "Diagram label language: " + fallbackLanguageCode + "\n"
            + "Line-numbered snippet:\n"
            + lineNumberedTextBlock(fullContent);
    }

    private static String buildOneLinerContext(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode) {

        return "Snippet language: " + snippetLanguage + "\n"
            + codeTextLanguageInstruction(fallbackLanguageCode, "returned command")
            + "Generate a compact one-liner, not an embedded/base64 wrapper. "
            + "Use only the provided snippet content. Do not download code, do not reference external URLs, and do not invent files or endpoints. "
            + "For shell snippets, use shell syntax on one line. "
            + "For Python, Perl, or Ruby snippets, use an interpreter command such as python3 -c, perl -e, or ruby -e when needed. "
            + "Preserve behavior and quote safely.\n"
            + "Full snippet:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(fullContent);
    }

    private static String codeTextLanguageInstruction(String languageCode, String returnedScope) {
        return "Every existing and new natural-language comment and every user-facing, log, or help string in the "
            + returnedScope + " must use language " + languageCode
            + "; translate existing text as needed without translating code tokens.\n";
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
