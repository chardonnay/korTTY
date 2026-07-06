package de.kortty.ui;

import de.kortty.core.AiAction;
import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiRequest;
import de.kortty.core.AiService;
import de.kortty.core.AiSnippetMetadataSupport;
import de.kortty.core.LanguageManager;
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
        return new SnippetEditDialog.AiAssist(
            (content, language) -> generateSnippetMetadata(ownerWindow, profile, aiService, content, language, contextDisplayName),
            (content, language, description) -> correctSnippetDescription(
                ownerWindow, profile, aiService, content, language, description, contextDisplayName),
            request -> correctSnippetSelectionText(ownerWindow, profile, aiService, request, contextDisplayName),
            request -> translateSnippetSelectionText(ownerWindow, profile, aiService, request, contextDisplayName),
            request -> describeSnippet(ownerWindow, profile, aiService, request, contextDisplayName),
            request -> generateAlternativeSolutions(ownerWindow, profile, aiService, request, contextDisplayName),
            request -> completeSnippetCode(ownerWindow, profile, aiService, request, contextDisplayName),
            request -> reviewSnippetCode(ownerWindow, profile, aiService, request, contextDisplayName),
            request -> improveSnippetCode(ownerWindow, profile, aiService, request, contextDisplayName),
            request -> assistSnippetCode(ownerWindow, profile, aiService, request, contextDisplayName),
            request -> reviewSnippetSecurity(ownerWindow, request, contextDisplayName),
            request -> applySnippetSecurityFixes(ownerWindow, request, contextDisplayName),
            request -> generateCompactOneLiner(ownerWindow, profile, aiService, request, contextDisplayName),
            request -> generateSnippetPlantUml(ownerWindow, profile, aiService, request, contextDisplayName),
            true);
    }

    /**
     * Resolves the AI profile + service for a single re-runnable request: the profile the user picked in
     * the dialog ({@code requestProfileId}) when it exists, otherwise the captured default profile. This
     * is what lets the review / describe / alternatives / improve / assist dialogs repeat a run with a
     * different profile without changing the default used elsewhere.
     */
    private static ResolvedProfile resolveForRequest(
        MainWindow ownerWindow,
        AiProfile defaultProfile,
        AiService defaultService,
        String requestProfileId) {

        if (requestProfileId == null || requestProfileId.isBlank()) {
            return new ResolvedProfile(defaultProfile, defaultService);
        }
        AiProfile chosen = ownerWindow.findAiProfileById(requestProfileId);
        if (chosen == null || chosen.getId() == null || chosen.getId().equals(defaultProfile.getId())) {
            return new ResolvedProfile(defaultProfile, defaultService);
        }
        AiService chosenService = ownerWindow.createAiServiceForProfile(chosen);
        if (chosenService == null) {
            return new ResolvedProfile(defaultProfile, defaultService);
        }
        return new ResolvedProfile(chosen, chosenService);
    }

    private record ResolvedProfile(AiProfile profile, AiService service) {
    }

    private static SnippetEditDialog.SuggestedSnippetMetadata generateSnippetMetadata(
        MainWindow ownerWindow,
        AiProfile profile,
        AiService aiService,
        String content,
        String language,
        String connectionDisplayName) throws Exception {
        String scriptContent = content != null ? content : "";
        String snippetLanguage = SnippetLanguageSupport.detectSnippetLanguage(language, scriptContent);
        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_METADATA,
            scriptContent,
            connectionDisplayName,
            currentLanguageCode(),
            snippetLanguage,
            null);
        AiExecutionResult result = aiService.execute(request);
        if (result != null) {
            ownerWindow.recordAiUsageForProfile(profile, request, result);
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
        String connectionDisplayName) throws Exception {
        String scriptContent = content != null ? content : "";
        String snippetLanguage = SnippetLanguageSupport.detectSnippetLanguage(language, scriptContent);
        return SnippetAiWorkflowSupport.correctSnippetDescription(
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
            scriptContent,
            description,
            snippetLanguage,
            connectionDisplayName,
            currentLanguageCode());
    }

    private static String correctSnippetSelectionText(
        MainWindow ownerWindow,
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.SelectionTextTransformRequest request,
        String connectionDisplayName) throws Exception {

        return SnippetAiWorkflowSupport.correctSelectionText(
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
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
        String connectionDisplayName) throws Exception {

        return SnippetAiWorkflowSupport.translateSelectionText(
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
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
        String connectionDisplayName) throws Exception {

        ResolvedProfile resolved = resolveForRequest(ownerWindow, profile, aiService, request.aiProfileId());
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
        String connectionDisplayName) throws Exception {

        ResolvedProfile resolved = resolveForRequest(ownerWindow, profile, aiService, request.aiProfileId());
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
        String connectionDisplayName) throws Exception {

        return SnippetAiWorkflowSupport.completeSnippetCode(
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
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
        String connectionDisplayName) throws Exception {

        ResolvedProfile resolved = resolveForRequest(ownerWindow, profile, aiService, request.aiProfileId());
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

    private static SnippetAiResponseSupport.CodeImprovement improveSnippetCode(
        MainWindow ownerWindow,
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.CodeImprovementRequest request,
        String connectionDisplayName) throws Exception {

        ResolvedProfile resolved = resolveForRequest(ownerWindow, profile, aiService, request.aiProfileId());
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
        String connectionDisplayName) throws Exception {

        ResolvedProfile resolved = resolveForRequest(ownerWindow, profile, aiService, request.aiProfileId());
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
        String connectionDisplayName) throws Exception {

        AiProfile profile = resolveSecurityProfile(ownerWindow);
        AiService aiService = securityAiService(ownerWindow, profile);
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
        String connectionDisplayName) throws Exception {

        AiProfile profile = resolveSecurityProfile(ownerWindow);
        AiService aiService = securityAiService(ownerWindow, profile);
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

    private static AiService securityAiService(MainWindow ownerWindow, AiProfile profile) {
        if (profile == null) {
            throw new IllegalStateException("No AI profile is available for the security check.");
        }
        AiService aiService = ownerWindow.createAiServiceForProfile(profile);
        if (aiService == null) {
            throw new IllegalStateException("No AI service could be created for the security-check profile.");
        }
        return aiService;
    }

    private static SnippetAiResponseSupport.PlantUmlDiagram generateSnippetPlantUml(
        MainWindow ownerWindow,
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.DiagramRequest request,
        String connectionDisplayName) throws Exception {

        return SnippetAiWorkflowSupport.generateSnippetPlantUml(
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
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
        String connectionDisplayName) throws Exception {

        return SnippetAiWorkflowSupport.generateCompactOneLiner(
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
            request.fullContent(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.additionalInstructions());
    }

    private static String currentLanguageCode() {
        return LanguageManager.getInstance().getCurrentLanguageCode();
    }
}
