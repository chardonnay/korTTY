package de.kortty.core;

import de.kortty.core.ScriptLanguageMixSupport.HostFormat;
import de.kortty.core.ScriptLanguageMixSupport.LanguageMix;
import de.kortty.core.ScriptLanguageMixSupport.MigrationMode;
import de.kortty.core.WorkflowScriptSupport.ScriptLanguage;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared request/response workflow helpers for snippet-editor AI actions.
 */
public final class SnippetAiWorkflowSupport {

    private static final Logger logger = LoggerFactory.getLogger(SnippetAiWorkflowSupport.class);
    /**
     * Work items per apply request. Smaller batches mean more round trips, but they bound how much
     * a model has to think about in one answer: reasoning models that bill hidden thinking as
     * completion tokens (MiniMax-M3) burned entire budgets on six-item stages — 54 881 tokens
     * without finishing a 5.7 KB script — while individual items are cheap. Three keeps every
     * stage's reasoning well inside the per-request budget without doubling the stage count for
     * typical selections.
     */
    private static final int MAX_MANDATORY_REQUIREMENTS_PER_APPLY_STAGE = 3;
    private static final int MAX_ANALYSIS_ITEMS_PER_APPLY_STAGE = 3;
    /**
     * Edit-mode stages answer with the changed regions, not the whole script, so the reason for
     * three items per stage — a whole-file answer that a thinking model could not finish — does not
     * apply; every stage fewer saves one copy of the script in the prompt (~64k tokens on getssl).
     * Six rather than more: each extra item is more replacement lines in one answer, and one
     * unescaped quote costs the whole answer.
     */
    static final int MAX_ANALYSIS_ITEMS_PER_EDIT_MODE_STAGE = 6;
    /**
     * Above this length an apply stage asks for edit regions instead of the whole script. A
     * 4,009-line bash script returned complete is ~60,000 output tokens: at the completion cap,
     * twelve minutes per stage, and a single lost quote in the JSON loses all of it (observed with
     * MiniMax-M3: 184,166 characters, no usable JSON, twice). Short snippets keep the proven
     * whole-file answer.
     */
    static final int MAX_WHOLE_FILE_REPLACEMENT_LINES = 400;
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
        /** Runs first: every later stage must operate on the already-migrated script. */
        MIGRATION,
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

    /**
     * Immutable snapshot of everything a staged Full-code-analysis apply completed so far. Emitted
     * after each finished stage so an aborted run can either resume from the next stage or surface
     * the partial rewrite for review; checkpoints never describe half-finished stages.
     */
    public record ImprovementApplyCheckpoint(
        int completedStages,
        int totalStages,
        String content,
        List<String> summaries,
        List<SnippetAiResponseSupport.SecurityChange> changes,
        List<String> completedRequirementIds,
        AiTokenUsage cumulativeUsage) {

        public ImprovementApplyCheckpoint {
            content = content != null ? content : "";
            summaries = summaries != null ? List.copyOf(summaries) : List.of();
            changes = changes != null ? List.copyOf(changes) : List.of();
            completedRequirementIds =
                completedRequirementIds != null ? List.copyOf(completedRequirementIds) : List.of();
        }

        /**
         * The accumulated partial result in the same shape a fully completed run returns. Callers
         * must not run it through the cumulative hardening verification — requirements of stages
         * that never ran are missing by definition.
         */
        public SnippetAiResponseSupport.SnippetSecurityFix toPartialFix() {
            return new SnippetAiResponseSupport.SnippetSecurityFix(
                content,
                String.join("\n\n", summaries),
                changes,
                completedRequirementIds);
        }
    }

    @FunctionalInterface
    public interface ImprovementApplyCheckpointListener {
        void onCheckpoint(ImprovementApplyCheckpoint checkpoint);
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

        return applySnippetSecurityFixes(aiService, usageRecorder, fullContent, snippetLanguage,
            connectionDisplayName, fallbackLanguageCode, selectedFindings, additionalInstructions, null);
    }

    /**
     * @param migrationPlan optional language unification performed <em>before</em> the fixes, so the
     *                      security fixes are written in the target language instead of the old one;
     *                      {@code null} or a no-op plan skips it.
     */
    public static SnippetAiResponseSupport.SnippetSecurityFix applySnippetSecurityFixes(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        List<SnippetAiResponseSupport.SecurityFinding> selectedFindings,
        String additionalInstructions,
        MigrationPlan migrationPlan) throws Exception {

        String content = fullContent;
        String language = snippetLanguage;
        String migrationSummary = null;
        if (migrationPlan != null && !migrationPlan.isNoOp()) {
            SnippetAiResponseSupport.LanguageMigration migration = migrateSnippetLanguage(
                aiService, usageRecorder, content, language, migrationPlan, connectionDisplayName,
                fallbackLanguageCode, additionalInstructions);
            content = migration.replacement();
            migrationSummary = migration.summary();
            if (migrationPlan.targetLanguage() != null && !migrationPlan.changesHostFormat()) {
                language = migrationPlan.targetLanguage().snippetLanguage();
            }
        }
        String fixedContent = content;
        String fixedLanguage = language;
        AiRequest request = new AiRequest(
            AiAction.APPLY_SNIPPET_SECURITY_FIXES,
            fixedContent,
            connectionDisplayName,
            fallbackLanguageCode,
            additionalInstructions,
            buildSecurityFixContext(fixedContent, fixedLanguage, fallbackLanguageCode, selectedFindings));
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        rejectTruncatedReplacement(result);
        SnippetAiResponseSupport.SnippetSecurityFix fix =
            SnippetAiResponseSupport.parseSecurityFix(result != null ? result.content() : null);
        if (migrationSummary == null || migrationSummary.isBlank()) {
            return fix;
        }
        // The migration ran as part of this apply, so its summary belongs in the same report.
        return new SnippetAiResponseSupport.SnippetSecurityFix(
            fix.replacement(),
            fix.summary().isBlank() ? migrationSummary : migrationSummary + "\n\n" + fix.summary(),
            fix.changes(),
            fix.implementedRequirements());
    }

    // ------------------------------------------------------------------ language migration

    /**
     * A migration order: what was detected, plus what the user actually asked for.
     *
     * <p>Detection and choice travel together on purpose. The two can contradict each other — a
     * platform conversion is only ever a deliberate choice and is never derivable from the
     * document — and a caller that passed them separately could let them drift apart.
     *
     * @param targetLanguage   target language of the script parts, {@code null} to leave them alone
     * @param targetHostFormat target platform, {@code null} to keep the current one
     */
    public record MigrationPlan(LanguageMix mix, ScriptLanguage targetLanguage, HostFormat targetHostFormat) {

        public MigrationPlan {
            mix = mix != null ? mix : new LanguageMix(HostFormat.NONE, "plain", List.of());
            targetHostFormat = targetHostFormat == HostFormat.NONE ? null : targetHostFormat;
        }

        /** Whether this order actually changes the platform, as opposed to naming the current one. */
        public boolean changesHostFormat() {
            return targetHostFormat != null && targetHostFormat != mix.hostFormat();
        }

        /** Everything this order does. Empty when it would change nothing. */
        public EnumSet<MigrationMode> modes() {
            EnumSet<MigrationMode> modes = EnumSet.noneOf(MigrationMode.class);
            if (changesHostFormat()) {
                modes.add(MigrationMode.HOST_FORMAT_CONVERSION);
            }
            if (targetLanguage != null) {
                if (mix.hostFormat() != HostFormat.NONE) {
                    // A host document's own body is never migrated; only its script steps are, and
                    // only when they do not already all speak the target language.
                    Set<String> steps = mix.stepLanguages();
                    if (!steps.isEmpty() && !steps.equals(Set.of(targetLanguage.snippetLanguage()))) {
                        modes.add(MigrationMode.EMBEDDED_STEPS_ONLY);
                    }
                } else if (!targetLanguage.snippetLanguage().equals(mix.dominantLanguage())
                    || !mix.embedded().isEmpty()) {
                    modes.add(MigrationMode.WHOLE_SCRIPT);
                }
            }
            return modes;
        }

        public boolean isNoOp() {
            return modes().isEmpty();
        }
    }

    /** Why a migration result was refused. The UI maps this to the message it shows. */
    public enum MigrationRejection {
        NO_USABLE_SCRIPT,
        DEGENERATE,
        SCAFFOLD_CHANGED,
        TARGET_FORMAT_NOT_REACHED
    }

    /** A migration result that must not be applied. */
    public static final class MigrationRejectedException extends IllegalStateException {
        private final MigrationRejection reason;

        public MigrationRejectedException(MigrationRejection reason, String message) {
            super(message);
            this.reason = reason;
        }

        public MigrationRejection reason() {
            return reason;
        }
    }

    /**
     * Rewrites a snippet so that it speaks one language, or converts a host document to another
     * platform. Always a whole-file rewrite: an anchor-based patch cannot address target text that
     * does not exist yet.
     *
     * <p>The result is verified before it is returned. Which check applies follows from the plan —
     * a whole-script migration legitimately rewrites almost every line, while a steps-only
     * migration must leave the host document's scaffold byte-identical.
     */
    public static SnippetAiResponseSupport.LanguageMigration migrateSnippetLanguage(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String snippetLanguage,
        MigrationPlan plan,
        String connectionDisplayName,
        String fallbackLanguageCode,
        String additionalInstructions) throws Exception {

        if (plan == null || plan.isNoOp()) {
            throw new IllegalArgumentException("Language migration was requested without a target.");
        }
        AiRequest request = new AiRequest(
            AiAction.MIGRATE_SNIPPET_LANGUAGE,
            fullContent,
            connectionDisplayName,
            fallbackLanguageCode,
            additionalInstructions,
            buildMigrationContext(fullContent, snippetLanguage, fallbackLanguageCode, plan));
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        rejectTruncatedReplacement(result);
        SnippetAiResponseSupport.LanguageMigration migration =
            SnippetAiResponseSupport.parseLanguageMigration(result != null ? result.content() : null);
        rejectUnusableMigration(fullContent, plan, migration);
        return migration;
    }

    private static void rejectUnusableMigration(String fullContent, MigrationPlan plan,
                                                SnippetAiResponseSupport.LanguageMigration migration) {
        if (migration == null || !migration.isUsable()) {
            throw new MigrationRejectedException(MigrationRejection.NO_USABLE_SCRIPT,
                "Language migration returned no usable script.");
        }
        EnumSet<MigrationMode> modes = plan.modes();
        if (modes.contains(MigrationMode.HOST_FORMAT_CONVERSION)) {
            // Let the very detector that classified the input decide whether the target was reached.
            HostFormat reached = ScriptLanguageMixSupport.detectHostFormat(
                plan.targetHostFormat().snippetLanguage(), migration.replacement());
            if (reached != plan.targetHostFormat()) {
                throw new MigrationRejectedException(MigrationRejection.TARGET_FORMAT_NOT_REACHED,
                    "Migration did not produce a " + plan.targetHostFormat().displayName() + " document.");
            }
            return;
        }
        if (modes.contains(MigrationMode.EMBEDDED_STEPS_ONLY)) {
            if (!ScriptLanguageMixSupport.scaffoldPreserved(
                plan.mix().hostFormat(), fullContent, migration.replacement())) {
                throw new MigrationRejectedException(MigrationRejection.SCAFFOLD_CHANGED,
                    "Migration changed the host document outside its script steps.");
            }
            return;
        }
        if (SnippetAiResponseSupport.isDegenerateMigration(fullContent, migration.replacement())) {
            throw new MigrationRejectedException(MigrationRejection.DEGENERATE,
                "Migration returned an incomplete script.");
        }
    }

    private static String buildMigrationContext(String fullContent, String snippetLanguage,
                                                String fallbackLanguageCode, MigrationPlan plan) {
        LanguageMix mix = plan.mix();
        EnumSet<MigrationMode> modes = plan.modes();
        StringBuilder context = new StringBuilder("Snippet language: ").append(snippetLanguage).append('\n');
        if (mix.hostFormat() != HostFormat.NONE) {
            context.append("Document format: ").append(mix.hostFormat().displayName()).append('\n');
        }
        context.append("Natural language for the summary and notes: ").append(fallbackLanguageCode).append('\n')
            .append(codeTextLanguageInstruction(fallbackLanguageCode, "full returned script"));

        if (modes.contains(MigrationMode.HOST_FORMAT_CONVERSION)) {
            context.append(hostFormatConversionParagraph(mix.hostFormat(), plan.targetHostFormat()));
        }
        if (modes.contains(MigrationMode.EMBEDDED_STEPS_ONLY)) {
            context.append(embeddedStepsParagraph(mix, plan.targetLanguage(),
                !modes.contains(MigrationMode.HOST_FORMAT_CONVERSION)));
        }
        if (modes.contains(MigrationMode.WHOLE_SCRIPT)) {
            context.append(wholeScriptParagraph(mix, plan.targetLanguage()));
        }
        return context.append("Line-numbered snippet:\n")
            .append(lineNumberedTextBlock(fullContent))
            .toString();
    }

    private static String wholeScriptParagraph(LanguageMix mix, ScriptLanguage target) {
        StringBuilder paragraph = new StringBuilder("MIGRATION SCOPE: the complete script.\n")
            .append("Rewrite it so that all of it is ").append(target.displayName()).append(".\n");
        if (target.leadLine() != null) {
            paragraph.append("The first line must be exactly: ").append(target.leadLine()).append('\n');
        }
        paragraph.append("Use ").append(target.commentPrefix()).append(" for line comments.\n");
        if (!mix.embedded().isEmpty()) {
            paragraph.append("These foreign-language parts were detected and must all be gone afterwards:\n")
                .append(describeRanges(mix.embedded()));
        }
        return paragraph.append(WorkflowScriptSupport.languageIdioms(target)).append('\n').toString();
    }

    private static String embeddedStepsParagraph(LanguageMix mix, ScriptLanguage target,
                                                 boolean scaffoldMustSurvive) {
        StringBuilder paragraph = new StringBuilder("MIGRATION SCOPE: only the script-step bodies of this ")
            .append(mix.hostFormat().displayName()).append(".\n")
            .append("Rewrite every step body listed below so that all of them are ")
            .append(target.displayName()).append(".\n");
        if (scaffoldMustSurvive) {
            paragraph.append("Every other line — structure, keys, indentation, display names, conditions, "
                + "task invocations, comments — must be reproduced character for character from the input. "
                + "Adjust the step type where the format requires it for the target language "
                + "(- pwsh: instead of - bash:, shell: pwsh, powershell '...' instead of sh '...'). "
                + "Do not add steps and do not remove any.\n");
        }
        return paragraph.append("Step bodies to rewrite:\n").append(describeRanges(mix.embedded())).toString();
    }

    private static String hostFormatConversionParagraph(HostFormat source, HostFormat target) {
        return "MIGRATION SCOPE: convert this " + source.displayName() + " into a valid "
            + target.displayName() + " definition.\n"
            + "Preserve the order, the conditions and the intent of every step. Carry over triggers, "
            + "agent or pool selection, variables, secrets, dependencies, artifacts and conditions only "
            + "where the target platform has a real equivalent. Do not invent a construct. When something "
            + "has no equivalent — approvals, environments, platform-specific tasks, matrix semantics, "
            + "plugin calls — leave it out and add exactly one note naming what could not be carried over "
            + "and what the user has to redo by hand.\n";
    }

    private static String describeRanges(List<ScriptLanguageMixSupport.EmbeddedLanguage> ranges) {
        StringBuilder text = new StringBuilder();
        for (ScriptLanguageMixSupport.EmbeddedLanguage range : ranges) {
            text.append("- lines ").append(range.startLine()).append('-').append(range.endLine())
                .append(": ").append(range.language()).append(" (").append(range.trigger()).append(")\n");
        }
        return text.toString();
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
            String answer = result != null && result.content() != null ? result.content() : "";
            String failure = SnippetAiResponseSupport.describeJsonFailure(answer);
            java.nio.file.Path archived = AiAnswerArchive.save(AiAction.ANALYZE_SNIPPET_CODE, "no-usable-analysis", answer);
            logger.warn("AI code analysis returned no usable analysis [answer chars={}, {}] Full answer: {}",
                answer.length(),
                failure != null ? failure : "JSON parses but holds no improvement",
                archived != null ? archived : "not archived");
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
            classicHardeningInstructions,
            inputHardeningInstructions,
            progressListener,
            null,
            null);
    }

    /**
     * Staged apply with abort recovery: after every completed stage the optional
     * {@code checkpointListener} receives the accumulated partial result, and a checkpoint from an
     * aborted run can be passed as {@code resumeFrom} to skip its completed stages and continue with
     * the first unfinished one. The stage plan must be rebuilt from the same selection the
     * checkpoint was recorded against; a mismatching checkpoint is rejected.
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
        ImprovementApplyProgressListener progressListener,
        ImprovementApplyCheckpointListener checkpointListener,
        ImprovementApplyCheckpoint resumeFrom) throws Exception {

        return applySnippetImprovements(
            aiService, usageRecorder, fullContent, snippetLanguage, connectionDisplayName,
            fallbackLanguageCode, improvements, dependencies, additionalInstructions,
            classicHardeningInstructions, inputHardeningInstructions, progressListener,
            checkpointListener, resumeFrom, null);
    }

    /**
     * @param migrationPlan optional language unification run as the very first stage, so every
     *                      improvement and hardening stage afterwards works on the migrated script;
     *                      {@code null} or a no-op plan adds no stage.
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
        ImprovementApplyProgressListener progressListener,
        ImprovementApplyCheckpointListener checkpointListener,
        ImprovementApplyCheckpoint resumeFrom,
        MigrationPlan migrationPlan) throws Exception {

        List<MandatoryRequirement> classicRequirements =
            extractMandatoryRequirements(classicHardeningInstructions, 0);
        List<MandatoryRequirement> inputRequirements =
            extractMandatoryRequirements(inputHardeningInstructions, classicRequirements.size());
        List<MandatoryRequirement> allRequirements = new ArrayList<>(classicRequirements.size() + inputRequirements.size());
        allRequirements.addAll(classicRequirements);
        allRequirements.addAll(inputRequirements);

        List<ImprovementApplyStagePlan> stagePlans = buildImprovementApplyStagePlans(
            improvements, dependencies, classicRequirements, inputRequirements, migrationPlan,
            usesEditMode(fullContent));
        int completedStageCount = resumeFrom != null ? resumeFrom.completedStages() : 0;
        if (resumeFrom != null
            && (resumeFrom.totalStages() != stagePlans.size()
                || completedStageCount < 1
                || completedStageCount >= stagePlans.size())) {
            throw new IllegalArgumentException("Resume checkpoint does not match the requested apply plan.");
        }

        String currentContent = resumeFrom != null
            ? resumeFrom.content()
            : fullContent != null ? fullContent : "";
        Set<String> completedRequirementIds = new LinkedHashSet<>(
            resumeFrom != null ? resumeFrom.completedRequirementIds() : List.of());
        Set<String> summaries = new LinkedHashSet<>(
            resumeFrom != null ? resumeFrom.summaries() : List.of());
        List<SnippetAiResponseSupport.SecurityChange> mergedChanges = new ArrayList<>(
            resumeFrom != null ? resumeFrom.changes() : List.of());
        UsageAccumulator usageAccumulator = new UsageAccumulator();
        if (resumeFrom != null) {
            usageAccumulator.add(resumeFrom.cumulativeUsage());
        }

        for (int index = 0; index < stagePlans.size(); index++) {
            ImprovementApplyStagePlan stagePlan = stagePlans.get(index);
            notifyImprovementProgress(progressListener, index < completedStageCount
                ? progressWithState(
                    stagePlan.progress(), ImprovementApplyProgressState.COMPLETED, usageAccumulator.total())
                : stagePlan.progress());
        }

        for (int index = completedStageCount; index < stagePlans.size(); index++) {
            ImprovementApplyStagePlan stagePlan = stagePlans.get(index);
            checkImprovementApplyInterrupted();
            ImprovementApplyProgress running = progressWithState(
                stagePlan.progress(), ImprovementApplyProgressState.RUNNING, usageAccumulator.total());
            notifyImprovementProgress(progressListener, running);
            // Requirements earlier stages already delivered — including those carried in by a resume
            // checkpoint. This stage must not silently drop their literals while implementing its own.
            List<MandatoryRequirement> earlierRequirements = allRequirements.stream()
                .filter(requirement -> completedRequirementIds.contains(requirement.id()))
                .toList();
            try {
                SnippetAiResponseSupport.SnippetSecurityFix fix = stagePlan.migration() != null
                    ? executeMigrationStage(
                        aiService, usageRecorder, currentContent, snippetLanguage, connectionDisplayName,
                        fallbackLanguageCode, additionalInstructions, stagePlan.migration(), usageAccumulator)
                    : executeImprovementApplyStage(
                    aiService, usageRecorder, currentContent, snippetLanguage, connectionDisplayName,
                    fallbackLanguageCode,
                    stagePlan.analysisStage().improvements(),
                    stagePlan.analysisStage().dependencies(),
                    additionalInstructions,
                    stagePlan.requirements(),
                    earlierRequirements,
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
                notifyImprovementCheckpoint(checkpointListener, new ImprovementApplyCheckpoint(
                    index + 1,
                    stagePlans.size(),
                    currentContent,
                    List.copyOf(summaries),
                    List.copyOf(mergedChanges),
                    List.copyOf(completedRequirementIds),
                    usageAccumulator.total()));
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

    /**
     * Runs the migration as one apply stage and reshapes its result into the staged pipeline's
     * currency. It contributes no {@code changes} entries: the whole file changed, so per-region
     * annotations would be noise rather than information.
     */
    private static SnippetAiResponseSupport.SnippetSecurityFix executeMigrationStage(
        AiService aiService,
        UsageRecorder usageRecorder,
        String fullContent,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        String additionalInstructions,
        MigrationPlan migrationPlan,
        UsageAccumulator usageAccumulator) throws Exception {

        checkImprovementApplyInterrupted();
        SnippetAiResponseSupport.LanguageMigration migration = migrateSnippetLanguage(
            aiService,
            (request, result) -> {
                usageAccumulator.add(result != null ? result.usage() : null);
                if (usageRecorder != null) {
                    usageRecorder.record(request, result);
                }
            },
            fullContent, snippetLanguage, migrationPlan, connectionDisplayName,
            fallbackLanguageCode, additionalInstructions);
        checkImprovementApplyInterrupted();
        String summary = migration.notes().isEmpty()
            ? migration.summary()
            : migration.summary() + "\n" + String.join("\n", migration.notes());
        return new SnippetAiResponseSupport.SnippetSecurityFix(
            migration.replacement(), summary, List.of(), List.of());
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
        List<MandatoryRequirement> earlierRequirements,
        boolean preservePriorStageWork,
        ImprovementApplyProgressListener progressListener,
        ImprovementApplyProgress progress,
        UsageAccumulator usageAccumulator) throws Exception {

        checkImprovementApplyInterrupted();

        String attemptContent = fullContent;
        StageRepairReason repairReason = StageRepairReason.NONE;
        List<MandatoryRequirement> requirementsNeedingRepair = List.of();
        List<MandatoryRequirement> requirementsNeedingRestore = List.of();
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
                    requirementsNeedingRestore,
                    analysisIdsNeedingRepair,
                    attemptContent));
            AiExecutionResult result = aiService.execute(request);
            if (result != null && usageRecorder != null) {
                usageRecorder.record(request, result);
            }
            usageAccumulator.add(result != null ? result.usage() : null);
            checkImprovementApplyInterrupted();
            if (echoOnlyRepairFallback != null && result != null && result.outputTruncated()) {
                return echoOnlyRepairFallback;
            }
            boolean editMode = usesEditMode(attemptContent);
            // An edit-mode answer holds the changed regions only, so its output limit is never the
            // stage's real size — it is a runaway answer, and how long an answer the model writes
            // varies per attempt. Seen live: 32,768 completion tokens against the limit, then
            // 3,239 for the byte-identical request the user resumed with. So the stage gets the
            // same single second attempt every other failure gets instead of ending the run.
            // A whole-file answer keeps failing here: for a large script the limit is the
            // constraint, and asking again would only spend the budget twice.
            if (editMode && !repairAttempt && result != null && result.outputTruncated()
                && !result.streamInterrupted()) {
                logger.warn("AI apply stage answer reached the output-token limit ({} completion tokens); "
                    + "one second attempt for the changed regions only.",
                    result.usage() != null ? result.usage().completionTokens() : -1L);
                repairReason = StageRepairReason.TRUNCATED_ANSWER;
                continue;
            }
            rejectTruncatedReplacement(result);
            SnippetAiResponseSupport.SnippetSecurityFix fix = editMode
                ? fixFromEdits(attemptContent, result)
                : SnippetAiResponseSupport.parseSecurityFix(result != null ? result.content() : null);
            boolean rejectedReplacement = fix == null || !fix.isUsable()
                || isIncompleteStagedReplacement(attemptContent, fix.replacement(), editMode);
            if (rejectedReplacement) {
                // In edit mode a short answer is the normal shape, not a collapsed script.
                if (!repairAttempt && !editMode && isShortCollapsedStageResult(result, attemptContent)) {
                    repairReason = StageRepairReason.COLLAPSED_REPLACEMENT;
                    continue;
                }
                // An edit-mode answer with nothing to apply — or whose edits collapse the script,
                // seen live as two edits "covering" 1,199 lines with an omission marker — gets the
                // same single second chance a collapsed whole-file answer gets: the stage input is
                // unchanged, the request says what was wrong with the first answer.
                if (!repairAttempt && editMode) {
                    if (fix != null) {
                        String answer = result != null && result.content() != null ? result.content() : "";
                        java.nio.file.Path archived = AiAnswerArchive.save(
                            AiAction.APPLY_SNIPPET_IMPROVEMENTS, "collapsing-edits", answer);
                        logger.warn("AI apply stage result refused as incomplete ({} -> {} non-blank lines); "
                            + "one second attempt. Full answer: {}",
                            attemptContent.lines().filter(line -> !line.isBlank()).count(),
                            fix.replacement().lines().filter(line -> !line.isBlank()).count(),
                            archived != null ? archived : "not archived");
                    }
                    repairReason = StageRepairReason.UNUSABLE_EDITS;
                    continue;
                }
                if (echoOnlyRepairFallback != null) {
                    return echoOnlyRepairFallback;
                }
                throw new FullReplacementRejectedException();
            }
            List<MandatoryRequirement> missingRequirements =
                missingMandatoryRequirements(fix, mandatoryRequirements);
            List<MandatoryRequirement> droppedRequirements =
                droppedEarlierRequirements(fix, earlierRequirements);
            List<String> unechoedAnalysisIds = unechoedAnalysisItemIds(fix, improvements, dependencies);
            if (!missingRequirements.isEmpty() || !droppedRequirements.isEmpty() || !unechoedAnalysisIds.isEmpty()) {
                if (!repairAttempt) {
                    attemptContent = fix.replacement();
                    repairReason = StageRepairReason.MISSING_REQUIREMENTS;
                    requirementsNeedingRepair = missingRequirements;
                    requirementsNeedingRestore = droppedRequirements;
                    analysisIdsNeedingRepair = unechoedAnalysisIds;
                    echoOnlyRepairFallback =
                        missingRequirements.isEmpty() && droppedRequirements.isEmpty() ? fix : null;
                    continue;
                }
                if (!missingRequirements.isEmpty() || !droppedRequirements.isEmpty()) {
                    List<MandatoryRequirement> unmet = Stream.concat(
                            missingRequirements.stream(), droppedRequirements.stream())
                        .distinct()
                        .toList();
                    throw new IncompleteMandatoryRequirementsException(
                        unmet.stream().map(MandatoryRequirement::id).toList(),
                        unmet.stream().map(SnippetAiWorkflowSupport::describeRequirement).toList());
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
    /** Whether a stage input is long enough for edit regions instead of a whole-file answer. */
    static boolean usesEditMode(String content) {
        return SnippetDiagramSupport.countLines(content) > MAX_WHOLE_FILE_REPLACEMENT_LINES;
    }

    /**
     * Turns an edit-mode answer into the whole-file fix the rest of the pipeline verifies:
     * applied locally, then checked exactly like a returned script. {@code null} when the answer
     * carries no usable edits or their ranges cannot be trusted.
     */
    private static SnippetAiResponseSupport.SnippetSecurityFix fixFromEdits(String content, AiExecutionResult result) {
        String answer = result != null && result.content() != null ? result.content() : "";
        SnippetAiResponseSupport.SnippetEdits edits = SnippetAiResponseSupport.parseSnippetEdits(answer, content);
        if (edits.recoveredFromBrokenJson()) {
            logger.info("AI apply stage edits read from an answer whose JSON did not parse: {} edit(s) "
                + "recovered line by line, no retry needed [answer chars={}]", edits.edits().size(), answer.length());
        }
        if (!edits.isUsable()) {
            String failure = SnippetAiResponseSupport.describeJsonFailure(answer);
            java.nio.file.Path archived = AiAnswerArchive.save(AiAction.APPLY_SNIPPET_IMPROVEMENTS, "no-usable-edits", answer);
            logger.warn("AI apply stage returned no usable edits [answer chars={}, {}; starts with: {}] Full answer: {}",
                answer.length(),
                failure != null ? failure : "JSON parses but holds no edit with a range",
                answer.strip().replaceAll("\\s+", " ").substring(0, Math.min(160, answer.strip().length())),
                archived != null ? archived : "not archived");
            return null;
        }
        // Seen live: a whole-script scan for omission comments refused a stage over a comment
        // that merely mentioned unchanged code among real lines. In edit mode the marker is an
        // edit of its own: a range of code "replaced" by nothing but such a comment.
        for (SnippetAiResponseSupport.SnippetEdit edit : edits.edits()) {
            List<String> lines = edit.replacementLines();
            boolean placeholder = !lines.isEmpty() && lines.size() <= 2
                && lines.stream().allMatch(SnippetAiResponseSupport::isOmittedCodeMarker)
                && Math.abs(edit.endLine() - edit.startLine()) + 1 >= 3;
            if (placeholder) {
                logger.warn("AI apply stage edit {}-{} replaces its range with an omission marker: {}",
                    edit.startLine(), edit.endLine(), lines.get(0).strip());
                return null;
            }
        }
        SnippetAiResponseSupport.AppliedEdits applied =
            SnippetAiResponseSupport.applySnippetEditsLeniently(content, edits.edits());
        if (applied == null) {
            logger.warn("AI apply stage edits rejected: none of the {} edit(s) has a usable range in the {}-line "
                + "snippet [answer chars={}]", edits.edits().size(), SnippetDiagramSupport.countLines(content), answer.length());
            return null;
        }
        List<SnippetAiResponseSupport.SecurityChange> changes = edits.changes();
        if (!applied.dropped().isEmpty()) {
            // The stage goes on with what could be applied; the repair round below asks for the
            // items the dropped edits were meant to cover, instead of the whole stage failing.
            // For that the echo of such an item must go: a change anchored in a dropped edit's
            // lines — and in no applied edit's — would otherwise hide that the item was not applied.
            java.util.Set<String> appliedLines = new java.util.HashSet<>();
            java.util.Set<String> droppedLines = new java.util.HashSet<>();
            for (SnippetAiResponseSupport.SnippetEdit edit : edits.edits()) {
                (applied.applied().contains(edit) ? appliedLines : droppedLines)
                    .addAll(edit.replacementLines().stream().map(String::strip).toList());
            }
            List<SnippetAiResponseSupport.SecurityChange> anchored = new ArrayList<>();
            List<String> unanchored = new ArrayList<>();
            for (SnippetAiResponseSupport.SecurityChange change : changes) {
                String anchor = change.anchor() != null ? change.anchor().strip() : "";
                if (!anchor.isEmpty() && droppedLines.contains(anchor) && !appliedLines.contains(anchor)) {
                    unanchored.add(change.finding());
                } else {
                    anchored.add(change);
                }
            }
            if (!unanchored.isEmpty()) {
                logger.warn("AI apply stage ignores the echo of {}: anchored in a dropped edit; the repair round asks for them",
                    unanchored);
                changes = anchored;
            }
            logger.warn("AI apply stage left {} of {} edit(s) out: {}", applied.dropped().size(),
                edits.edits().size(), applied.dropped());
        }
        int editedLines = applied.applied().stream().mapToInt(edit -> edit.endLine() - edit.startLine() + 1).sum();
        for (SnippetAiResponseSupport.SnippetEdit edit : applied.applied()) {
            int range = edit.endLine() - edit.startLine() + 1;
            if (range >= 100 && edit.replacementLines().size() * 10 < range) {
                logger.warn("AI apply stage edit {}-{} replaces {} lines with {}: a large region shrunk to little",
                    edit.startLine(), edit.endLine(), range, edit.replacementLines().size());
            }
        }
        logger.info("AI apply stage applied {} edit(s) covering {} original line(s) of {} [answer chars={}]",
            applied.applied().size(), editedLines, SnippetDiagramSupport.countLines(content), answer.length());
        return new SnippetAiResponseSupport.SnippetSecurityFix(
            applied.replacement(), edits.summary(), changes, edits.implementedRequirements());
    }

    private static boolean isIncompleteStagedReplacement(String original, String replacement) {
        return isIncompleteStagedReplacement(original, replacement, false);
    }

    private static boolean isIncompleteStagedReplacement(String original, String replacement, boolean editMode) {
        if (SnippetAiResponseSupport.isDegenerateFullReplacement(original, replacement, !editMode)) {
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

        return planSnippetImprovements(improvements, dependencies,
            classicHardeningInstructions, inputHardeningInstructions, null);
    }

    /** @param migrationPlan optional first stage; see {@link #applySnippetImprovements}. */
    public static List<ImprovementApplyProgress> planSnippetImprovements(
        List<SnippetAiResponseSupport.ScriptImprovement> improvements,
        List<SnippetAiResponseSupport.ScriptDependency> dependencies,
        String classicHardeningInstructions,
        String inputHardeningInstructions,
        MigrationPlan migrationPlan) {
        return planSnippetImprovements(improvements, dependencies, classicHardeningInstructions,
            inputHardeningInstructions, migrationPlan, null);
    }

    /**
     * @param snippetContent the script the plan is for; a long one gets the larger edit-mode
     *     stages, so the preview shows the stage count the run will actually have
     */
    public static List<ImprovementApplyProgress> planSnippetImprovements(
        List<SnippetAiResponseSupport.ScriptImprovement> improvements,
        List<SnippetAiResponseSupport.ScriptDependency> dependencies,
        String classicHardeningInstructions,
        String inputHardeningInstructions,
        MigrationPlan migrationPlan,
        String snippetContent) {

        List<MandatoryRequirement> classicRequirements =
            extractMandatoryRequirements(classicHardeningInstructions, 0);
        List<MandatoryRequirement> inputRequirements =
            extractMandatoryRequirements(inputHardeningInstructions, classicRequirements.size());
        return buildImprovementApplyStagePlans(
            improvements, dependencies, classicRequirements, inputRequirements, migrationPlan,
            usesEditMode(snippetContent)).stream()
            .map(ImprovementApplyStagePlan::progress)
            .toList();
    }

    private static List<ImprovementApplyStagePlan> buildImprovementApplyStagePlans(
        List<SnippetAiResponseSupport.ScriptImprovement> improvements,
        List<SnippetAiResponseSupport.ScriptDependency> dependencies,
        List<MandatoryRequirement> classicRequirements,
        List<MandatoryRequirement> inputRequirements) {

        return buildImprovementApplyStagePlans(
            improvements, dependencies, classicRequirements, inputRequirements, null);
    }

    private static List<ImprovementApplyStagePlan> buildImprovementApplyStagePlans(
        List<SnippetAiResponseSupport.ScriptImprovement> improvements,
        List<SnippetAiResponseSupport.ScriptDependency> dependencies,
        List<MandatoryRequirement> classicRequirements,
        List<MandatoryRequirement> inputRequirements,
        MigrationPlan migrationPlan) {
        return buildImprovementApplyStagePlans(
            improvements, dependencies, classicRequirements, inputRequirements, migrationPlan, false);
    }

    private static List<ImprovementApplyStagePlan> buildImprovementApplyStagePlans(
        List<SnippetAiResponseSupport.ScriptImprovement> improvements,
        List<SnippetAiResponseSupport.ScriptDependency> dependencies,
        List<MandatoryRequirement> classicRequirements,
        List<MandatoryRequirement> inputRequirements,
        MigrationPlan migrationPlan,
        boolean editMode) {

        boolean migrates = migrationPlan != null && !migrationPlan.isNoOp();
        // A migration rewrites the whole file first and its length is unknown here, while every
        // later stage picks edit mode from the script it actually receives; six items are only
        // justified by an edit-mode answer, so a run with a migration keeps the whole-file cap.
        int itemsPerStage = editMode && !migrates
            ? MAX_ANALYSIS_ITEMS_PER_EDIT_MODE_STAGE
            : MAX_ANALYSIS_ITEMS_PER_APPLY_STAGE;
        List<AnalysisApplyStage> analysisStages = buildAnalysisApplyStages(improvements, dependencies, itemsPerStage);
        List<List<MandatoryRequirement>> classicBatches = partitionRequirements(classicRequirements);
        List<List<MandatoryRequirement>> inputBatches = partitionRequirements(inputRequirements);
        // Preserve the former single-request behaviour for direct callers that provide no work lists.
        if (analysisStages.isEmpty() && classicBatches.isEmpty() && inputBatches.isEmpty()) {
            analysisStages = List.of(new AnalysisApplyStage(List.of(), List.of(), "", List.of()));
        }
        int totalStages = analysisStages.size() + classicBatches.size() + inputBatches.size()
            + (migrates ? 1 : 0);
        List<ImprovementApplyStagePlan> plans = new ArrayList<>(totalStages);
        int stage = 0;
        if (migrates) {
            // Deliberately the first stage: the stages are incremental, so every improvement and
            // hardening stage after this one must see the already-migrated script. Running it later
            // would write Bash idioms into what is by then a Perl script.
            plans.add(new ImprovementApplyStagePlan(
                new ImprovementApplyProgress(
                    ImprovementApplyPhase.MIGRATION,
                    ++stage,
                    totalStages,
                    1,
                    1,
                    1,
                    migrationDetail(migrationPlan),
                    List.of(),
                    ImprovementApplyProgressState.PENDING,
                    null),
                new AnalysisApplyStage(List.of(), List.of(), "", List.of()),
                List.of(),
                migrationPlan));
        }
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

    private static String migrationDetail(MigrationPlan plan) {
        if (plan.changesHostFormat()) {
            return plan.targetHostFormat().displayName();
        }
        return plan.targetLanguage() != null ? plan.targetLanguage().displayName() : "";
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
     * stages cut apply latency — but a larger stage also multiplies how much a reasoning model
     * thinks before answering, and a stage whose thinking exhausts the completion budget delivers
     * nothing at all. {@link #MAX_ANALYSIS_ITEMS_PER_APPLY_STAGE} balances the two; see its
     * documentation before raising it again.
     */
    private static List<AnalysisApplyStage> buildAnalysisApplyStages(
        List<SnippetAiResponseSupport.ScriptImprovement> improvements,
        List<SnippetAiResponseSupport.ScriptDependency> dependencies,
        int itemsPerStage) {

        List<SnippetAiResponseSupport.ScriptImprovement> safeImprovements = improvements != null
            ? improvements.stream().filter(java.util.Objects::nonNull).toList()
            : List.of();
        List<SnippetAiResponseSupport.ScriptDependency> safeDependencies = dependencies != null
            ? dependencies.stream().filter(java.util.Objects::nonNull).toList()
            : List.of();
        int totalItems = safeImprovements.size() + safeDependencies.size();
        List<AnalysisApplyStage> stages = new ArrayList<>();
        for (int start = 0; start < totalItems; start += itemsPerStage) {
            int end = Math.min(totalItems, start + itemsPerStage);
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

    private static void notifyImprovementCheckpoint(
        ImprovementApplyCheckpointListener listener,
        ImprovementApplyCheckpoint checkpoint) {

        if (listener != null) {
            listener.onCheckpoint(checkpoint);
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

        return generateSnippetMermaid(
            aiService, usageRecorder, de.kortty.model.SnippetDiagramType.LOGICAL_STRUCTURE,
            fullContent, snippetLanguage, connectionDisplayName, fallbackLanguageCode,
            additionalInstructions);
    }

    /**
     * Generates one snippet diagram of the requested family. {@code scopedContent} is either the
     * full snippet or the selected part of it; every produced line number is 1-based relative to
     * this content, and the caller shifts them when persisting a selection-scoped diagram.
     */
    public static SnippetAiResponseSupport.MermaidDiagram generateSnippetMermaid(
        AiService aiService,
        UsageRecorder usageRecorder,
        de.kortty.model.SnippetDiagramType diagramType,
        String scopedContent,
        String snippetLanguage,
        String connectionDisplayName,
        String fallbackLanguageCode,
        String additionalInstructions) throws Exception {

        de.kortty.model.SnippetDiagramType type = diagramType != null
            ? diagramType
            : de.kortty.model.SnippetDiagramType.LOGICAL_STRUCTURE;
        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_MERMAID,
            scopedContent,
            connectionDisplayName,
            fallbackLanguageCode,
            additionalInstructions,
            buildMermaidContext(scopedContent, snippetLanguage, fallbackLanguageCode),
            true,
            null,
            null,
            null,
            type);
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        // A cut-off answer carries no usable diagram, and the reason matters: a thinking model can
        // spend the whole completion budget on hidden reasoning and emit no JSON at all. Reporting
        // that as an ordinary failed generation sends the next reader after the wrong fix, so this
        // is signalled like the other bounded, machine-parsed snippet answers.
        if (result != null && result.outputTruncated()) {
            if (result.streamInterrupted()) {
                throw new ResponseStreamInterruptedException();
            }
            throw new OutputTokenLimitReachedException();
        }
        String answer = result != null ? result.content() : null;
        int snippetLines = SnippetDiagramSupport.countLines(scopedContent);
        SnippetAiResponseSupport.MermaidDiagram diagram =
            SnippetAiResponseSupport.parseMermaidDiagram(type, answer, scopedContent);
        int strippedStatements = SnippetDiagramSupport.countPresentationStatements(answer);
        if (strippedStatements > 0) {
            logger.info("AI diagram styling ignored: {} classDef/style/linkStyle statement(s) removed; "
                + "korTTY styles the semantic classes itself [type={}, snippet lines={}]",
                strippedStatements, type, snippetLines);
        }
        boolean salvaged = false;
        if (!diagram.isUsable()) {
            SnippetAiResponseSupport.MermaidDiagram recovered = recoverDiagramFromText(type, answer, scopedContent);
            if (recovered != null) {
                salvaged = recovered.isUsable();
                diagram = recovered;
            }
        }
        if (!diagram.isUsable()) {
            // This used to be silent: the generic local fallback replaced the diagram and nobody
            // could tell an AI diagram from the fallback, let alone why the answer was thrown away.
            logger.warn("AI diagram rejected: {} [type={}, snippet lines={}, answer chars={}, {}] Full answer: {}",
                diagram.rejectionReason(), type, snippetLines, answer != null ? answer.length() : 0,
                describeAnswer(type, answer), archiveRejectedDiagram(answer));
            return diagram;
        }
        int answerChars = answer != null ? answer.length() : 0;
        if (salvaged) {
            logger.info("AI diagram recovered from an answer whose JSON envelope was unusable "
                + "[type={}, snippet lines={}, answer chars={}]", type, snippetLines, answerChars);
        }
        SnippetDiagramSupport.MermaidValidation validation = SnippetTypedDiagramSupport.validateForSnippet(
            type, diagram.mermaid(), scopedContent, diagram.codeReferences(), fallbackLanguageCode);
        String summary = SnippetTypedDiagramSupport.summarize(type, diagram.mermaid());
        if (!validation.valid()) {
            logger.warn("AI diagram rejected: {} [type={}, snippet lines={}, {}] Full answer: {}",
                validation.message(), type, snippetLines, summary, archiveRejectedDiagram(answer));
            return SnippetAiResponseSupport.MermaidDiagram.rejected(type, validation.message());
        }
        String acceptedSummary = summary;
        String canonical = null;
        if (type == de.kortty.model.SnippetDiagramType.LOGICAL_STRUCTURE) {
            // Rendered and saved in the strict dialect, whatever shorthand the model used.
            canonical = SnippetDiagramSupport.canonicalizeGeneratedFlowchart(diagram.mermaid(), fallbackLanguageCode);
            SnippetDiagramSupport.FlowchartStatistics drawn = SnippetDiagramSupport.flowchartStatistics(diagram.mermaid());
            SnippetDiagramSupport.FlowchartStatistics kept = SnippetDiagramSupport.flowchartStatistics(canonical);
            int droppedNodes = Math.max(0, drawn.nonterminalNodes() - kept.nonterminalNodes());
            int droppedEdges = Math.max(0, drawn.edges() - kept.edges());
            // The repairs may trim a diagram, never hollow it out: a start-to-stop stub, or a
            // diagram that lost more than half of what the model drew, is a rejection with a
            // reason — the generic fallback says the same thing honestly. This is the last gate,
            // so nothing may call the diagram accepted before it.
            if (kept.nonterminalNodes() == 0
                || (drawn.nonterminalNodes() > 0 && kept.nonterminalNodes() * 2 < drawn.nonterminalNodes())) {
                String reason = "The diagram's repairs would drop " + droppedNodes + " of " + drawn.nonterminalNodes()
                    + " nodes (parallel branches or unreachable nodes the dialect cannot show); not enough remains.";
                logger.warn("AI diagram rejected: {} [type={}, snippet lines={}, {}] Full answer: {}",
                    reason, type, snippetLines, summary, archiveRejectedDiagram(answer));
                return SnippetAiResponseSupport.MermaidDiagram.rejected(type, reason);
            }
            if (droppedNodes > 0 || droppedEdges > 0) {
                logger.warn("AI diagram reduced to a single path: {} nodes and {} edges kept, {} nodes and {} edges "
                        + "dropped; the model drew parallel branches or unreachable nodes the dialect cannot show",
                    kept.nonterminalNodes(), kept.edges(), droppedNodes, droppedEdges);
            }
            // What the diagram window will show, not what the model drew.
            acceptedSummary = SnippetTypedDiagramSupport.summarize(type, canonical);
            SnippetDiagramSupport.SourceMappingReport mapping = SnippetDiagramSupport.reportSourceMapping(
                diagram.mermaid(), scopedContent, diagram.codeReferences());
            if (!mapping.complete()) {
                logger.warn("AI diagram accepted with an incomplete source mapping: {} of {} nodes have no "
                        + "valid reference and lose their hover link: {} [snippet lines={}, {}]",
                    mapping.unmappedNodeIds().size(), mapping.expectedNodes(), mapping.unmappedNodeIds(),
                    snippetLines, acceptedSummary);
            }
        }
        // The answer length beside the token count tells hidden reasoning (billed, never returned)
        // from a verbose reply: a compact diagram JSON is a few thousand characters, whatever the
        // completion count says.
        logger.info("AI diagram accepted [type={}, snippet lines={}, node cap={}, {}, answer chars={}]",
            type, snippetLines, SnippetDiagramSupport.maxGeneratedNonterminalNodes(scopedContent), acceptedSummary,
            answerChars);
        if (canonical != null) {
            return new SnippetAiResponseSupport.MermaidDiagram(diagram.title(), canonical, diagram.codeReferences(), type);
        }
        return diagram;
    }

    /**
     * Keeps a rejected diagram answer whole, beside the unusable apply answers. A rejection names
     * one broken rule, and the log can only carry the first line or two — but which shorthand the
     * model wrote, and whether the grammar could learn it, is only decidable on the whole answer.
     *
     * @return the archived file, or a short note when nothing could be written
     */
    private static Object archiveRejectedDiagram(String answer) {
        java.nio.file.Path archived = AiAnswerArchive.save(
            AiAction.GENERATE_SNIPPET_MERMAID, "rejected-diagram", answer);
        return archived != null ? archived : "not archived";
    }

    /**
     * Rebuilds a diagram from an answer the JSON parser could not use. Returns {@code null} when
     * the answer holds no diagram at all, so the caller keeps the original parse failure; when a
     * diagram was found but breaks a generation rule, the returned result names that rule instead
     * of the JSON complaint, because that is the reason a reader has to act on. Never sends a
     * second request.
     */
    private static SnippetAiResponseSupport.MermaidDiagram recoverDiagramFromText(
        de.kortty.model.SnippetDiagramType type, String answer, String scopedContent) {

        String recoveredSource = SnippetTypedDiagramSupport.extractDiagramSource(type, answer);
        if (recoveredSource.isBlank()) {
            return null;
        }
        SnippetAiResponseSupport.MermaidDiagram recovered =
            new SnippetAiResponseSupport.MermaidDiagram("", recoveredSource, java.util.List.of(), type);
        if (!recovered.isUsable()) {
            return null;
        }
        SnippetDiagramSupport.MermaidValidation generated =
            SnippetTypedDiagramSupport.validateGenerated(type, recovered.mermaid(), scopedContent);
        return generated.valid()
            ? recovered
            : SnippetAiResponseSupport.MermaidDiagram.rejected(type, generated.message());
    }

    /**
     * A short, structural description of a rejected answer for the log. Without it the only way to
     * tell "the model wrote prose", "the model wrote a diagram korTTY could not read" and "the
     * model answered something else entirely" apart was to reproduce the run.
     */
    private static String describeAnswer(de.kortty.model.SnippetDiagramType type, String answer) {
        if (answer == null || answer.isBlank()) {
            return "answer empty";
        }
        String header = SnippetTypedDiagramSupport.header(type);
        boolean carriesHeader = answer.toLowerCase(java.util.Locale.ROOT)
            .contains(header.toLowerCase(java.util.Locale.ROOT));
        String excerpt = answer.strip().replaceAll("\\s+", " ");
        if (excerpt.length() > 160) {
            excerpt = excerpt.substring(0, 160) + "…";
        }
        return "'" + header + "' present=" + carriesHeader
            + ", fenced=" + answer.contains("```")
            + ", starts with: " + excerpt;
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
        private final List<String> missingRequirementLabels;

        public IncompleteMandatoryRequirementsException(List<String> missingRequirementIds) {
            this(missingRequirementIds, List.of());
        }

        /**
         * @param missingRequirementLabels the same requirements as {@code id (rule)}. An
         *     identifier alone leaves the reader counting checkboxes to find out which option the
         *     model would not implement, and this failure ends the run.
         */
        public IncompleteMandatoryRequirementsException(
            List<String> missingRequirementIds, List<String> missingRequirementLabels) {
            super("AI response did not implement every selected hardening requirement. Missing requirements: "
                + String.join("; ", missingRequirementLabels != null && !missingRequirementLabels.isEmpty()
                    ? missingRequirementLabels
                    : missingRequirementIds != null ? missingRequirementIds : List.of()));
            this.missingRequirementIds = missingRequirementIds != null
                ? List.copyOf(missingRequirementIds)
                : List.of();
            this.missingRequirementLabels = missingRequirementLabels != null
                ? List.copyOf(missingRequirementLabels)
                : List.of();
        }

        public List<String> missingRequirementIds() {
            return missingRequirementIds;
        }

        /** The missing requirements as {@code id (rule)}; empty when only identifiers were known. */
        public List<String> missingRequirementLabels() {
            return missingRequirementLabels.isEmpty() ? missingRequirementIds : missingRequirementLabels;
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
            + "Natural language for summary: "+fallbackLanguageCode + "\n"
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
            + "Natural language for the security report: "+fallbackLanguageCode + "\n"
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
            + "Natural language for the summary: "+fallbackLanguageCode + "\n"
            + codeTextLanguageInstruction(fallbackLanguageCode, "full returned snippet")
            + "Selected security findings to fix:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(findingsText)
            + "\nFull snippet to update:\n"
            + AiPromptBuilder.toSafeTextCodeBlock(fullContent);
    }

    private static String buildAnalysisContext(String fullContent, String snippetLanguage, String fallbackLanguageCode) {
        return "Snippet language: " + snippetLanguage + "\n"
            + "Natural language for the analysis: "+fallbackLanguageCode + "\n"
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
        List<MandatoryRequirement> requirementsNeedingRestore,
        List<String> analysisIdsNeedingRepair,
        String stageContent) {

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
            .append(repairReason == StageRepairReason.TRUNCATED_ANSWER
                ? "The preceding attempt for this same stage ran into its output-token limit and was cut off. "
                    + "This is the single repair attempt. Return only the regions that change, never the "
                    + "unchanged code around them, and keep the answer no longer than those regions require.\n"
                : "")
            .append(repairReason == StageRepairReason.UNUSABLE_EDITS
                ? "The preceding attempt for this same stage was discarded because it contained no edit that could be applied: "
                    + "its JSON was unreadable, its ranges lay outside the snippet, its replacementLines held only the first "
                    + "line of the range instead of the range's complete new content, or an edit replaced a large region "
                    + "with a placeholder or an omission marker instead of that region's complete new code. This is the "
                    + "single repair attempt. Return every changed region as one edit whose replacementLines contain the "
                    + "entire new text of startLine..endLine — every line that stays must be repeated, nothing may be "
                    + "summarized — and make the JSON valid: escape every double quote inside code.\n"
                : "")
            .append(repairReason == StageRepairReason.COLLAPSED_REPLACEMENT
                ? "The preceding attempt for this same stage was discarded because it returned an empty or severely collapsed script. This is the single repair attempt: copy the complete input into replacementLines, one source line per array entry, then make only the current requested change. Do not close the JSON object after the header or a partial function.\n"
                : "")
            .append(missingWorkRepairParagraph(
                repairReason, requirementsNeedingRepair, requirementsNeedingRestore, analysisIdsNeedingRepair))
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
        if (usesEditMode(stageContent)) {
            // The literal heading is what switches AiPromptBuilder to edit mode and suppresses its
            // raw-script block; the numbered lines are what the edits' ranges refer to.
            context.append("\nThe snippet is ").append(SnippetDiagramSupport.countLines(stageContent))
                .append(" lines long; return edit regions against these line numbers, not the whole script.\n")
                .append("Line-numbered snippet:\n")
                .append(lineNumberedTextBlock(stageContent));
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
        List<MandatoryRequirement> requirementsNeedingRestore,
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
        if (!requirementsNeedingRestore.isEmpty()) {
            paragraph.append(" The preceding attempt also removed hardening work an earlier stage had already"
                    + " completed. Restore it exactly as it was, in addition to this stage's own requirements,"
                    + " and keep every literal those rules require present in the script. Requirements to restore: ")
                .append(requirementsNeedingRestore.stream().map(MandatoryRequirement::id)
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
        List<MandatoryRequirement> missing = missingMandatoryRequirements(fix, mandatoryRequirements);
        if (!missing.isEmpty()) {
            throw new IncompleteMandatoryRequirementsException(
                missing.stream().map(MandatoryRequirement::id).toList(),
                missing.stream().map(SnippetAiWorkflowSupport::describeRequirement).toList());
        }
    }

    /**
     * Requirements an earlier stage already delivered whose required literals are gone from this
     * stage's answer. Every stage rewrites the complete script, so a later stage can drop an
     * earlier stage's work while implementing its own — without the ids: they stay echoed in the
     * accumulated set, so only the literals expose the regression. Checking here rather than in the
     * final cumulative verification alone makes the offending stage fail immediately, which earns
     * it the stage's repair attempt and keeps the remaining stages resumable.
     */
    private static List<MandatoryRequirement> droppedEarlierRequirements(
            SnippetAiResponseSupport.SnippetSecurityFix fix,
            List<MandatoryRequirement> earlierRequirements) {
        if (earlierRequirements == null || earlierRequirements.isEmpty() || fix == null || !fix.isUsable()) {
            return List.of();
        }
        return earlierRequirements.stream()
            .filter(requirement ->
                !containsRequiredHardeningLiterals(fix.replacement(), requirement.instruction()))
            .toList();
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

    /** {@code HARDENING-10 (Add a --help usage message …)}: the identifier plus what it asks for. */
    private static String describeRequirement(MandatoryRequirement requirement) {
        String rule = requirement.instruction() != null ? requirement.instruction().strip() : "";
        rule = rule.replaceAll("\\s+", " ");
        if (rule.length() > 90) {
            rule = rule.substring(0, 90).strip() + "…";
        }
        return rule.isEmpty() ? requirement.id() : requirement.id() + " (" + rule + ")";
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
        List<MandatoryRequirement> requirements,
        MigrationPlan migration) {

        ImprovementApplyStagePlan(ImprovementApplyProgress progress, AnalysisApplyStage analysisStage,
                                  List<MandatoryRequirement> requirements) {
            this(progress, analysisStage, requirements, null);
        }
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
        /** Edit mode: the answer ran into its output-token limit and was cut off. */
        TRUNCATED_ANSWER,
        /** Edit mode: the answer held no edit korTTY could apply (unreadable, out of range, hollow). */
        UNUSABLE_EDITS,
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
        // suppresses its generic raw-script block for this action. A long script is condensed to
        // its structure first — sending every line of a four-thousand-line file made models
        // transcribe it rather than summarize it.
        SnippetDiagramOutline.Outline outline = SnippetDiagramOutline.of(fullContent);
        StringBuilder context = new StringBuilder("Snippet language: ").append(snippetLanguage).append('\n')
            .append("Diagram label language: ").append(fallbackLanguageCode).append('\n');
        if (outline.condensed()) {
            context.append("The snippet is ").append(outline.totalLines())
                .append(" lines long and is shown below as a condensed structural outline: only its ")
                .append("definitions and top-level flow are listed, elided runs are marked with '… n lines ")
                .append("omitted …', and every number is the original 1-based line number of the complete ")
                .append("snippet. Diagram the overall flow at the level of these phases, and use original ")
                .append("line numbers in codeReferences.\n");
        }
        // The literal heading also tells AiPromptBuilder that this request already carries its
        // source copy; renaming it would append the whole raw script a second time.
        return context.append("Line-numbered snippet:\n")
            .append("```text\n").append(outline.text()).append("```")
            .toString();
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
