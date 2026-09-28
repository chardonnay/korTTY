package de.kortty.ui;

import de.kortty.core.AiAction;
import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiLanguageSupport;
import de.kortty.core.AiRequest;
import de.kortty.core.AiReasoningSupport;
import de.kortty.core.AiService;
import de.kortty.core.CodeTextLanguageAiService;
import de.kortty.core.AiSnippetMetadataSupport;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAiWorkflowSupport;
import de.kortty.core.SnippetAnalysisRecord;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.model.AiProfile;
import de.kortty.model.ServerConnection;

import java.util.List;
import java.util.function.Supplier;

final class SnippetAiAssistFactory {

    private SnippetAiAssistFactory() {
    }

    static SnippetEditDialog.AiAssist create(MainWindow ownerWindow) {
        return create(ownerWindow, (ServerConnection) null);
    }

    static SnippetEditDialog.AiAssist create(MainWindow ownerWindow, ServerConnection connection) {
        return create(() -> ownerWindow, connection);
    }

    /**
     * Like {@link #create(MainWindow)}, but resolves the main window per request: an editor tab can
     * be dragged into another main window, whose AI profiles and usage accounting then apply.
     * Returns {@code null} when no main window with AI profiles is available right now.
     */
    static SnippetEditDialog.AiAssist create(Supplier<MainWindow> ownerWindowSupplier) {
        return create(ownerWindowSupplier, null);
    }

    private static SnippetEditDialog.AiAssist create(Supplier<MainWindow> ownerWindowSupplier,
                                                     ServerConnection connection) {
        MainWindow initialOwner = ownerWindowSupplier != null ? ownerWindowSupplier.get() : null;
        if (initialOwner == null || initialOwner.getAvailableAiProfiles().isEmpty()) {
            return null;
        }
        // Falls back to the window the editor was opened from when the supplier has nothing better.
        Supplier<MainWindow> owner = () -> {
            MainWindow current = ownerWindowSupplier.get();
            return current != null ? current : initialOwner;
        };
        String connectionDisplayName = connection != null ? connection.getDisplayName() : null;
        String contextDisplayName = connectionDisplayName != null && !connectionDisplayName.isBlank()
            ? connectionDisplayName.trim()
            : null;
        // Editor-scoped runtime options (currently the explicit AI-code skill allowlist). Read per request inside the
        // lambdas so the picker's current selection always applies.
        SnippetAiRuntimeOptions runtimeOptions = new SnippetAiRuntimeOptions();
        return new SnippetEditDialog.AiAssist(
            (content, language, responseLanguageCode) -> generateSnippetMetadata(
                owner.get(), connection, content, language, responseLanguageCode,
                contextDisplayName, runtimeOptions),
            (content, language, description, responseLanguageCode) -> correctSnippetDescription(
                owner.get(), connection, content, language, description, responseLanguageCode,
                contextDisplayName, runtimeOptions),
            request -> correctSnippetSelectionText(owner.get(), connection, request, contextDisplayName, runtimeOptions),
            request -> translateSnippetSelectionText(owner.get(), connection, request, contextDisplayName, runtimeOptions),
            request -> describeSnippet(owner.get(), connection, request, contextDisplayName, runtimeOptions),
            request -> generateAlternativeSolutions(owner.get(), connection, request, contextDisplayName, runtimeOptions),
            request -> completeSnippetCode(owner.get(), connection, request, contextDisplayName, runtimeOptions),
            request -> reviewSnippetCode(owner.get(), connection, request, contextDisplayName, runtimeOptions),
            request -> improveSnippetCode(owner.get(), connection, request, contextDisplayName, runtimeOptions),
            request -> migrateSnippetLanguage(owner.get(), connection, request, contextDisplayName, runtimeOptions),
            request -> assistSnippetCode(owner.get(), connection, request, contextDisplayName, runtimeOptions),
            request -> reviewSnippetSecurity(owner.get(), connection, request, contextDisplayName, runtimeOptions),
            request -> applySnippetSecurityFixes(owner.get(), connection, request, contextDisplayName, runtimeOptions),
            request -> generateCompactOneLiner(owner.get(), connection, request, contextDisplayName, runtimeOptions),
            request -> generateSnippetMermaid(owner.get(), connection, request, contextDisplayName, runtimeOptions),
            request -> analyzeSnippetCode(owner.get(), connection, request, contextDisplayName, runtimeOptions),
            request -> applySnippetImprovements(owner.get(), connection, request, contextDisplayName, runtimeOptions),
            true,
            runtimeOptions);
    }

    /**
     * Resolves profile and service afresh for every action. This preserves explicit dialog choices,
     * security and connection assignments, and TEXT/CODING role changes made while the editor is open.
     */
    private static ResolvedProfile resolve(
        MainWindow ownerWindow,
        ServerConnection connection,
        AiAction action,
        String requestProfileId,
        SnippetAiRuntimeOptions options) {

        AiProfile profile = ownerWindow.resolveAiProfileForAction(connection, action, requestProfileId);
        if (profile == null) {
            throw new IllegalStateException("No AI profile is available for this snippet action.");
        }
        AiProfile executionProfile = AiReasoningSupport.profileForAction(profile, action);
        AiService service = ownerWindow.createAiServiceForProfile(
            executionProfile, connection, options.forcedSkillIds());
        if (service == null) {
            throw new IllegalStateException("No AI service could be created for this snippet action.");
        }
        // Every action that returns code carries the snippet's own prose language, so applying an AI
        // result never silently translates the user's comments into the interface language. Wrapping
        // here rather than at each request keeps a future action from being forgotten.
        return new ResolvedProfile(profile, new CodeTextLanguageAiService(
            service, options::codeTextLanguage));
    }

    private record ResolvedProfile(AiProfile profile, AiService service) {
    }

    /**
     * Reports the profile and model that actually served a stored analysis or apply run, then the
     * usage after each AI call. A missing or failing listener never affects the AI request.
     */
    static final class ProvenanceReporter {
        private final SnippetEditDialog.AiProvenanceListener listener;
        private final SnippetAnalysisRecord.Provenance base;
        private SnippetAnalysisRecord.Usage usage = SnippetAnalysisRecord.Usage.ZERO;

        ProvenanceReporter(SnippetEditDialog.AiProvenanceListener listener, AiProfile profile,
                           SnippetAiRuntimeOptions options, String additionalInstructions) {
            this.listener = listener;
            List<String> skillIds = options != null
                ? options.forcedSkillIds().stream().sorted().toList()
                : List.of();
            this.base = new SnippetAnalysisRecord.Provenance(
                profile != null ? profile.getId() : null,
                profile != null ? profile.getName() : null,
                profile != null ? profile.getModel() : null,
                skillIds, List.of(), additionalInstructions, null);
            report();
        }

        synchronized void recordUsage(AiExecutionResult result) {
            if (result != null && result.usage() != null) {
                usage = usage.plus(SnippetAnalysisRecord.Usage.from(result.usage()));
            }
            report();
        }

        private void report() {
            if (listener == null) {
                return;
            }
            try {
                listener.onProvenance(base.withUsage(usage));
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(SnippetAiAssistFactory.class)
                    .warn("AI provenance listener failed: {}", e.toString());
            }
        }
    }

    private static SnippetEditDialog.SuggestedSnippetMetadata generateSnippetMetadata(
        MainWindow ownerWindow,
        ServerConnection connection,
        String content,
        String language,
        String responseLanguageCode,
        String connectionDisplayName,
        SnippetAiRuntimeOptions options) throws Exception {
        String scriptContent = content != null ? content : "";
        String snippetLanguage = SnippetLanguageSupport.detectSnippetLanguage(language, scriptContent);
        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.GENERATE_SNIPPET_METADATA, null, options);
        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_METADATA,
            scriptContent,
            connectionDisplayName,
            AiLanguageSupport.resolveFallbackLanguageCode(responseLanguageCode),
            snippetLanguage,
            null);
        AiExecutionResult result = resolved.service().execute(request);
        if (result != null) {
            ownerWindow.recordAiUsageForProfile(resolved.profile(), request, result);
        }
        AiSnippetMetadataSupport.SuggestedSnippetMetadata metadata = AiSnippetMetadataSupport.parseMetadataResponse(
            result != null ? result.content() : null,
            snippetLanguage,
            scriptContent);
        return new SnippetEditDialog.SuggestedSnippetMetadata(
            metadata.fileName(), metadata.description(), metadata.language(), metadata.textLanguage());
    }

    private static String correctSnippetDescription(
        MainWindow ownerWindow,
        ServerConnection connection,
        String content,
        String language,
        String description,
        String responseLanguageCode,
        String connectionDisplayName,
        SnippetAiRuntimeOptions options) throws Exception {
        String scriptContent = content != null ? content : "";
        String snippetLanguage = SnippetLanguageSupport.detectSnippetLanguage(language, scriptContent);
        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.CORRECT_SNIPPET_DESCRIPTION, null, options);
        return SnippetAiWorkflowSupport.correctSnippetDescription(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            scriptContent,
            description,
            snippetLanguage,
            connectionDisplayName,
            AiLanguageSupport.resolveFallbackLanguageCode(responseLanguageCode));
    }

    private static String correctSnippetSelectionText(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.SelectionTextTransformRequest request,
        String connectionDisplayName,
        SnippetAiRuntimeOptions options) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.CORRECT_SNIPPET_SELECTION_TEXT, null, options);
        return SnippetAiWorkflowSupport.correctSelectionText(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.selectedText(),
            request.selectionStart(),
            request.selectionEnd(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.additionalInstructions());
    }

    private static String translateSnippetSelectionText(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.SelectionTextTransformRequest request,
        String connectionDisplayName,
        SnippetAiRuntimeOptions options) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.TRANSLATE_SNIPPET_SELECTION_TEXT, null, options);
        return SnippetAiWorkflowSupport.translateSelectionText(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.selectedText(),
            request.selectionStart(),
            request.selectionEnd(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.targetLanguageCode(),
            request.fallbackLanguageCode(),
            request.additionalInstructions());
    }

    private static String describeSnippet(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.SnippetDescriptionRequest request,
        String connectionDisplayName,
        SnippetAiRuntimeOptions options) throws Exception {

        AiAction action = request.wholeSnippet()
            ? AiAction.DESCRIBE_SNIPPET_FULL
            : AiAction.DESCRIBE_SNIPPET_SELECTION;
        ResolvedProfile resolved = resolve(
            ownerWindow, connection, action, request.aiProfileId(), options);
        return SnippetAiWorkflowSupport.describeSnippet(
            action,
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.selectedText(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.additionalInstructions());
    }

    private static List<SnippetAiResponseSupport.AlternativeSolution> generateAlternativeSolutions(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.AlternativeSolutionsRequest request,
        String connectionDisplayName,
        SnippetAiRuntimeOptions options) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.GENERATE_SNIPPET_ALTERNATIVES,
            request.aiProfileId(), options);
        return SnippetAiWorkflowSupport.generateAlternativeSolutions(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.selectedText(),
            request.wholeSnippet(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.maxSolutions(),
            request.additionalInstructions());
    }

    private static List<SnippetAiResponseSupport.CompletionSuggestion> completeSnippetCode(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.CompletionRequest request,
        String connectionDisplayName,
        SnippetAiRuntimeOptions options) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.COMPLETE_SNIPPET_CODE, null, options);
        return SnippetAiWorkflowSupport.completeSnippetCode(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.cursorOffset(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.additionalInstructions(),
            request.maxCandidates(),
            request.localContext());
    }

    private static List<SnippetAiResponseSupport.CodeReviewFinding> reviewSnippetCode(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.CodeReviewRequest request,
        String connectionDisplayName,
        SnippetAiRuntimeOptions options) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.REVIEW_SNIPPET_CODE,
            request.aiProfileId(), options);
        return SnippetAiWorkflowSupport.reviewSnippetCode(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.selectedText(),
            request.wholeSnippet(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.reviewTheme(),
            request.additionalInstructions());
    }

    private static SnippetAiResponseSupport.ScriptAnalysis analyzeSnippetCode(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.CodeAnalysisRequest request,
        String connectionDisplayName,
        SnippetAiRuntimeOptions options) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.ANALYZE_SNIPPET_CODE,
            request.aiProfileId(), options);
        ProvenanceReporter provenance = new ProvenanceReporter(
            request.provenanceListener(), resolved.profile(), options, request.additionalInstructions());
        return SnippetAiWorkflowSupport.analyzeSnippetCode(
            resolved.service(),
            (aiRequest, result) -> {
                ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result);
                provenance.recordUsage(result);
            },
            request.fullContent(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.additionalInstructions());
    }

    private static SnippetAiResponseSupport.SnippetSecurityFix applySnippetImprovements(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.ImprovementApplyRequest request,
        String connectionDisplayName,
        SnippetAiRuntimeOptions options) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.APPLY_SNIPPET_IMPROVEMENTS,
            request.aiProfileId(), options);
        ProvenanceReporter provenance = new ProvenanceReporter(
            request.provenanceListener(), resolved.profile(), options, request.additionalInstructions());
        return SnippetAiWorkflowSupport.applySnippetImprovements(
            resolved.service(),
            (aiRequest, result) -> {
                ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result);
                provenance.recordUsage(result);
            },
            request.fullContent(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.improvements(),
            request.dependencies(),
            request.additionalInstructions(),
            request.classicHardeningInstructions(),
            request.inputHardeningInstructions(),
            request.progressListener(),
            request.checkpointListener(),
            request.resumeFrom(),
            request.migration());
    }

    private static SnippetAiResponseSupport.LanguageMigration migrateSnippetLanguage(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.LanguageMigrationRequest request,
        String connectionDisplayName,
        SnippetAiRuntimeOptions options) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.MIGRATE_SNIPPET_LANGUAGE,
            request.aiProfileId(), options);
        return SnippetAiWorkflowSupport.migrateSnippetLanguage(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.snippetLanguage(),
            request.plan(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.additionalInstructions());
    }

    private static SnippetAiResponseSupport.CodeImprovement improveSnippetCode(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.CodeImprovementRequest request,
        String connectionDisplayName,
        SnippetAiRuntimeOptions options) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.IMPROVE_SNIPPET_CODE,
            request.aiProfileId(), options);
        return SnippetAiWorkflowSupport.improveSnippetCode(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.selectedText(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.improvementTheme(),
            request.additionalInstructions(),
            request.allowPlainTextFallback());
    }

    private static SnippetAiResponseSupport.CodeImprovement assistSnippetCode(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.CodeAssistantRequest request,
        String connectionDisplayName,
        SnippetAiRuntimeOptions options) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.ASSIST_SNIPPET_CODE,
            request.aiProfileId(), options);
        return SnippetAiWorkflowSupport.assistSnippetCode(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.cursorOffset(),
            request.cursorLine(),
            request.cursorColumn(),
            request.userInstruction(),
            request.additionalInstructions(),
            request.includeAiSkills());
    }

    private static List<SnippetAiResponseSupport.SecurityFinding> reviewSnippetSecurity(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.SecurityReviewRequest request,
        String connectionDisplayName,
        SnippetAiRuntimeOptions options) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.SECURITY_REVIEW_SNIPPET_CODE, null, options);
        return SnippetAiWorkflowSupport.reviewSnippetSecurity(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.additionalInstructions());
    }

    private static SnippetAiResponseSupport.SnippetSecurityFix applySnippetSecurityFixes(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.SecurityFixRequest request,
        String connectionDisplayName,
        SnippetAiRuntimeOptions options) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.APPLY_SNIPPET_SECURITY_FIXES, null, options);
        return SnippetAiWorkflowSupport.applySnippetSecurityFixes(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.selectedFindings(),
            request.additionalInstructions(),
            request.migration());
    }

    private static SnippetAiResponseSupport.MermaidDiagram generateSnippetMermaid(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.DiagramRequest request,
        String connectionDisplayName,
        SnippetAiRuntimeOptions options) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.GENERATE_SNIPPET_MERMAID,
            request.aiProfileId(), options);
        return SnippetAiWorkflowSupport.generateSnippetMermaid(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.diagramType(),
            request.generationContent(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.additionalInstructions());
    }

    private static SnippetAiResponseSupport.OneLinerSuggestion generateCompactOneLiner(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.OneLinerRequest request,
        String connectionDisplayName,
        SnippetAiRuntimeOptions options) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.GENERATE_SNIPPET_ONE_LINER, null, options);
        return SnippetAiWorkflowSupport.generateCompactOneLiner(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.additionalInstructions());
    }
}
