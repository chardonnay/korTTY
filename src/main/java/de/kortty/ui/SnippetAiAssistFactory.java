package de.kortty.ui;

import de.kortty.core.AiAction;
import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiLanguageSupport;
import de.kortty.core.AiRequest;
import de.kortty.core.AiService;
import de.kortty.core.AiSnippetMetadataSupport;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAiWorkflowSupport;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.model.AiProfile;

import java.util.List;

final class SnippetAiAssistFactory {

    private SnippetAiAssistFactory() {
    }

    static SnippetEditDialog.AiAssist create(MainWindow ownerWindow) {
        return create(ownerWindow, null);
    }

    static SnippetEditDialog.AiAssist create(MainWindow ownerWindow, String connectionDisplayName) {
        if (ownerWindow == null) {
            return null;
        }
        AiProfile profile = ownerWindow.getDefaultAiProfile();
        if (profile == null) {
            return null;
        }
        AiService aiService = ownerWindow.createAiServiceForProfile(profile);
        if (aiService == null) {
            return null;
        }
        String contextDisplayName = connectionDisplayName != null && !connectionDisplayName.isBlank()
            ? connectionDisplayName
            : null;
        // Editor-scoped runtime options (currently the forced AI-code skills). Read per request inside the
        // lambdas so the picker's current selection always applies.
        SnippetAiRuntimeOptions runtimeOptions = new SnippetAiRuntimeOptions();
        return new SnippetEditDialog.AiAssist(
            (content, language, responseLanguageCode) -> generateSnippetMetadata(
                ownerWindow, profile, aiService, content, language, responseLanguageCode,
                contextDisplayName, runtimeOptions.forcedSkillIds()),
            (content, language, description, responseLanguageCode) -> correctSnippetDescription(
                ownerWindow, profile, aiService, content, language, description, responseLanguageCode,
                contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> correctSnippetSelectionText(ownerWindow, profile, aiService, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> translateSnippetSelectionText(ownerWindow, profile, aiService, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> describeSnippet(ownerWindow, profile, aiService, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> generateAlternativeSolutions(ownerWindow, profile, aiService, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> completeSnippetCode(ownerWindow, profile, aiService, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> reviewSnippetCode(ownerWindow, profile, aiService, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> improveSnippetCode(ownerWindow, profile, aiService, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> assistSnippetCode(ownerWindow, profile, aiService, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> reviewSnippetSecurity(ownerWindow, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> applySnippetSecurityFixes(ownerWindow, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> generateCompactOneLiner(ownerWindow, profile, aiService, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> generateSnippetMermaid(ownerWindow, profile, aiService, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> analyzeSnippetCode(ownerWindow, profile, aiService, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            request -> applySnippetImprovements(ownerWindow, profile, aiService, request, contextDisplayName, runtimeOptions.forcedSkillIds()),
            true,
            runtimeOptions);
    }

    /**
     * Resolves the AI profile + service for a request: the profile the user picked in the dialog
     * ({@code requestProfileId}) when it exists, otherwise the captured default profile. When
     * {@code forcedSkillIds} is non-empty a fresh service is built with those skills pinned (so the
     * snippet editor's forced skills apply regardless of the skill target), otherwise the captured
     * default service is reused. This is also what lets the review / describe / improve / assist dialogs
     * repeat a run with a different profile without changing the default used elsewhere.
     */
    private static ResolvedProfile resolve(
        MainWindow ownerWindow,
        AiProfile defaultProfile,
        AiService defaultService,
        String requestProfileId,
        java.util.Collection<String> forcedSkillIds) {

        AiProfile profile = defaultProfile;
        if (requestProfileId != null && !requestProfileId.isBlank()) {
            AiProfile chosen = ownerWindow.findAiProfileById(requestProfileId);
            if (chosen != null && chosen.getId() != null && !chosen.getId().equals(defaultProfile.getId())) {
                profile = chosen;
            }
        }
        boolean forced = forcedSkillIds != null && !forcedSkillIds.isEmpty();
        if (profile == defaultProfile && !forced) {
            return new ResolvedProfile(defaultProfile, defaultService);
        }
        AiService service = ownerWindow.createAiServiceForProfile(profile, forced ? forcedSkillIds : null);
        if (service == null) {
            return new ResolvedProfile(defaultProfile, defaultService);
        }
        return new ResolvedProfile(profile, service);
    }

    private record ResolvedProfile(AiProfile profile, AiService service) {
    }

    private static SnippetEditDialog.SuggestedSnippetMetadata generateSnippetMetadata(
        MainWindow ownerWindow,
        AiProfile profile,
        AiService aiService,
        String content,
        String language,
        String responseLanguageCode,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {
        String scriptContent = content != null ? content : "";
        String snippetLanguage = SnippetLanguageSupport.detectSnippetLanguage(language, scriptContent);
        ResolvedProfile resolved = resolve(ownerWindow, profile, aiService, null, forcedSkillIds);
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
        return new SnippetEditDialog.SuggestedSnippetMetadata(metadata.fileName(), metadata.description(), metadata.language());
    }

    private static String correctSnippetDescription(
        MainWindow ownerWindow,
        AiProfile profile,
        AiService aiService,
        String content,
        String language,
        String description,
        String responseLanguageCode,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {
        String scriptContent = content != null ? content : "";
        String snippetLanguage = SnippetLanguageSupport.detectSnippetLanguage(language, scriptContent);
        ResolvedProfile resolved = resolve(ownerWindow, profile, aiService, null, forcedSkillIds);
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
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.SelectionTextTransformRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(ownerWindow, profile, aiService, null, forcedSkillIds);
        return SnippetAiWorkflowSupport.correctSelectionText(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.selectedText(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.additionalInstructions());
    }

    private static String translateSnippetSelectionText(
        MainWindow ownerWindow,
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.SelectionTextTransformRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(ownerWindow, profile, aiService, null, forcedSkillIds);
        return SnippetAiWorkflowSupport.translateSelectionText(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.selectedText(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.targetLanguageCode(),
            request.fallbackLanguageCode(),
            request.additionalInstructions());
    }

    private static String describeSnippet(
        MainWindow ownerWindow,
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.SnippetDescriptionRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(ownerWindow, profile, aiService, request.aiProfileId(), forcedSkillIds);
        return SnippetAiWorkflowSupport.describeSnippet(
            request.wholeSnippet() ? AiAction.DESCRIBE_SNIPPET_FULL : AiAction.DESCRIBE_SNIPPET_SELECTION,
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
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.AlternativeSolutionsRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(ownerWindow, profile, aiService, request.aiProfileId(), forcedSkillIds);
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
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.CompletionRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(ownerWindow, profile, aiService, null, forcedSkillIds);
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
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.CodeReviewRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(ownerWindow, profile, aiService, request.aiProfileId(), forcedSkillIds);
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

    private static SnippetAiResponseSupport.FullCodeAnalysis analyzeSnippetCode(
        MainWindow ownerWindow,
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.CodeAnalysisRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(ownerWindow, profile, aiService, request.aiProfileId(), forcedSkillIds);
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
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.ImprovementApplyRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(ownerWindow, profile, aiService, request.aiProfileId(), forcedSkillIds);
        return SnippetAiWorkflowSupport.applySnippetImprovements(
            resolved.service(),
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(resolved.profile(), aiRequest, result),
            request.fullContent(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.improvements(),
            request.dependencies(),
            request.additionalInstructions());
    }

    private static SnippetAiResponseSupport.CodeImprovement improveSnippetCode(
        MainWindow ownerWindow,
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.CodeImprovementRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(ownerWindow, profile, aiService, request.aiProfileId(), forcedSkillIds);
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
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.CodeAssistantRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(ownerWindow, profile, aiService, request.aiProfileId(), forcedSkillIds);
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
        SnippetEditDialog.SecurityReviewRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        AiProfile profile = resolveSecurityProfile(ownerWindow);
        AiService aiService = securityAiService(ownerWindow, profile, forcedSkillIds);
        return SnippetAiWorkflowSupport.reviewSnippetSecurity(
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
            request.fullContent(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.additionalInstructions());
    }

    private static SnippetAiResponseSupport.SnippetSecurityFix applySnippetSecurityFixes(
        MainWindow ownerWindow,
        SnippetEditDialog.SecurityFixRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        AiProfile profile = resolveSecurityProfile(ownerWindow);
        AiService aiService = securityAiService(ownerWindow, profile, forcedSkillIds);
        return SnippetAiWorkflowSupport.applySnippetSecurityFixes(
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
            request.fullContent(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.selectedFindings(),
            request.additionalInstructions());
    }

    /**
     * Resolves the AI profile for security checks fresh on every call so a change to the dedicated
     * security profile (in the findings dialog or the settings) takes effect immediately, without
     * reopening the snippet editor. Falls back to the default profile.
     */
    private static AiProfile resolveSecurityProfile(MainWindow ownerWindow) {
        AiProfile profile = ownerWindow.getSecurityCheckAiProfile();
        return profile != null ? profile : ownerWindow.getDefaultAiProfile();
    }

    private static AiService securityAiService(MainWindow ownerWindow, AiProfile profile, java.util.Collection<String> forcedSkillIds) {
        if (profile == null) {
            throw new IllegalStateException("No AI profile is available for the security check.");
        }
        AiService aiService = ownerWindow.createAiServiceForProfile(profile, forcedSkillIds);
        if (aiService == null) {
            throw new IllegalStateException("No AI service could be created for the security-check profile.");
        }
        return aiService;
    }

    private static SnippetAiResponseSupport.MermaidDiagram generateSnippetMermaid(
        MainWindow ownerWindow,
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.DiagramRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(ownerWindow, profile, aiService, request.aiProfileId(), forcedSkillIds);
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
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.OneLinerRequest request,
        String connectionDisplayName,
        java.util.Collection<String> forcedSkillIds) throws Exception {

        ResolvedProfile resolved = resolve(ownerWindow, profile, aiService, null, forcedSkillIds);
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
