package de.kortty.ui;

import de.kortty.core.AiAction;
import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiLanguageSupport;
import de.kortty.core.AiRequest;
import de.kortty.core.AiReasoningSupport;
import de.kortty.core.AiService;
import de.kortty.core.AiSnippetMetadataSupport;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAiWorkflowSupport;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.model.AiProfile;
import de.kortty.model.ServerConnection;

import java.util.List;

final class SnippetAiAssistFactory {

    private SnippetAiAssistFactory() {
    }

    static SnippetEditDialog.AiAssist create(MainWindow ownerWindow) {
        return create(ownerWindow, (ServerConnection) null);
    }

    static SnippetEditDialog.AiAssist create(MainWindow ownerWindow, ServerConnection connection) {
        if (ownerWindow == null || ownerWindow.getAvailableAiProfiles().isEmpty()) {
            return null;
        }
        String connectionDisplayName = connection != null ? connection.getDisplayName() : null;
        String contextDisplayName = connectionDisplayName != null && !connectionDisplayName.isBlank()
            ? connectionDisplayName.trim()
            : null;
        // Editor-scoped runtime options (currently the explicit AI-code skill allowlist). Read per request inside the
        // lambdas so the picker's current selection always applies.
        SnippetAiRuntimeOptions runtimeOptions = new SnippetAiRuntimeOptions();
        return new SnippetEditDialog.AiAssist(
            (content, language, responseLanguageCode) -> generateSnippetMetadata(
                ownerWindow, connection, content, language, responseLanguageCode,
                contextDisplayName, runtimeOptions.forcedSkillIds()),
            (content, language, description, responseLanguageCode) -> correctSnippetDescription(
                ownerWindow, connection, content, language, description, responseLanguageCode,
                contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> correctSnippetSelectionText(ownerWindow, connection, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> translateSnippetSelectionText(ownerWindow, connection, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> describeSnippet(ownerWindow, connection, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> generateAlternativeSolutions(ownerWindow, connection, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> completeSnippetCode(ownerWindow, connection, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> reviewSnippetCode(ownerWindow, connection, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> improveSnippetCode(ownerWindow, connection, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> assistSnippetCode(ownerWindow, connection, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> reviewSnippetSecurity(ownerWindow, connection, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> applySnippetSecurityFixes(ownerWindow, connection, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> generateCompactOneLiner(ownerWindow, connection, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> generateSnippetMermaid(ownerWindow, connection, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> analyzeSnippetCode(ownerWindow, connection, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> applySnippetImprovements(ownerWindow, connection, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
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
        java.util.Collection<String> forcedSkillIds) {

        AiProfile profile = ownerWindow.resolveAiProfileForAction(connection, action, requestProfileId);
        if (profile == null) {
            throw new IllegalStateException("No AI profile is available for this snippet action.");
        }
        AiProfile executionProfile = AiReasoningSupport.profileForAction(profile, action);
        AiService service = ownerWindow.createAiServiceForProfile(
            executionProfile, connection, forcedSkillIds);
        if (service == null) {
            throw new IllegalStateException("No AI service could be created for this snippet action.");
        }
        return new ResolvedProfile(profile, service);
    }

    private record ResolvedProfile(AiProfile profile, AiService service) {
    }

    private static SnippetEditDialog.SuggestedSnippetMetadata generateSnippetMetadata(
        MainWindow ownerWindow,
        ServerConnection connection,
        String content,
        String language,
        String responseLanguageCode,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {
        String scriptContent = content != null ? content : "";
        String snippetLanguage = SnippetLanguageSupport.detectSnippetLanguage(language, scriptContent);
        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.GENERATE_SNIPPET_METADATA, null, forcedSkillIds);
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
        java.util.Collection<String> forcedSkillIds) throws Exception {
        String scriptContent = content != null ? content : "";
        String snippetLanguage = SnippetLanguageSupport.detectSnippetLanguage(language, scriptContent);
        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.CORRECT_SNIPPET_DESCRIPTION, null, forcedSkillIds);
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
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.CORRECT_SNIPPET_SELECTION_TEXT, null, forcedSkillIds);
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
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.TRANSLATE_SNIPPET_SELECTION_TEXT, null, forcedSkillIds);
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
        java.util.Collection<String> forcedSkillIds) throws Exception {

        AiAction action = request.wholeSnippet()
            ? AiAction.DESCRIBE_SNIPPET_FULL
            : AiAction.DESCRIBE_SNIPPET_SELECTION;
        ResolvedProfile resolved = resolve(
            ownerWindow, connection, action, request.aiProfileId(), forcedSkillIds);
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
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.GENERATE_SNIPPET_ALTERNATIVES,
            request.aiProfileId(), forcedSkillIds);
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

    private static SnippetAiResponseSupport.CompletionSuggestion completeSnippetCode(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.CompletionRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.COMPLETE_SNIPPET_CODE, null, forcedSkillIds);
        return SnippetAiWorkflowSupport.completeSnippetCode(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.cursorOffset(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.additionalInstructions());
    }

    private static List<SnippetAiResponseSupport.CodeReviewFinding> reviewSnippetCode(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.CodeReviewRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.REVIEW_SNIPPET_CODE,
            request.aiProfileId(), forcedSkillIds);
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
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.ANALYZE_SNIPPET_CODE,
            request.aiProfileId(), forcedSkillIds);
        return SnippetAiWorkflowSupport.analyzeSnippetCode(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
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
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.APPLY_SNIPPET_IMPROVEMENTS,
            request.aiProfileId(), forcedSkillIds);
        return SnippetAiWorkflowSupport.applySnippetImprovements(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.improvements(),
            request.dependencies(),
            request.additionalInstructions(),
            request.classicHardeningInstructions(),
            request.inputHardeningInstructions(),
            request.progressListener());
    }

    private static SnippetAiResponseSupport.CodeImprovement improveSnippetCode(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.CodeImprovementRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.IMPROVE_SNIPPET_CODE,
            request.aiProfileId(), forcedSkillIds);
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
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.ASSIST_SNIPPET_CODE,
            request.aiProfileId(), forcedSkillIds);
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
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.SECURITY_REVIEW_SNIPPET_CODE, null, forcedSkillIds);
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
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.APPLY_SNIPPET_SECURITY_FIXES, null, forcedSkillIds);
        return SnippetAiWorkflowSupport.applySnippetSecurityFixes(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.selectedFindings(),
            request.additionalInstructions());
    }

    private static SnippetAiResponseSupport.MermaidDiagram generateSnippetMermaid(
        MainWindow ownerWindow,
        ServerConnection connection,
        SnippetEditDialog.DiagramRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.GENERATE_SNIPPET_MERMAID,
            request.aiProfileId(), forcedSkillIds);
        return SnippetAiWorkflowSupport.generateSnippetMermaid(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
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
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(
            ownerWindow, connection, AiAction.GENERATE_SNIPPET_ONE_LINER, null, forcedSkillIds);
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
