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
            request -> reviewSnippetSecurity(ownerWindow, profile, aiService, request, contextDisplayName),
            request -> applySnippetSecurityFixes(ownerWindow, profile, aiService, request, contextDisplayName),
            request -> generateCompactOneLiner(ownerWindow, profile, aiService, request, contextDisplayName),
            request -> generateSnippetPlantUml(ownerWindow, profile, aiService, request, contextDisplayName));
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

        return SnippetAiWorkflowSupport.describeSnippet(
            request.wholeSnippet() ? AiAction.DESCRIBE_SNIPPET_FULL : AiAction.DESCRIBE_SNIPPET_SELECTION,
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
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

        return SnippetAiWorkflowSupport.generateAlternativeSolutions(
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
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

        return SnippetAiWorkflowSupport.reviewSnippetCode(
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
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

        return SnippetAiWorkflowSupport.improveSnippetCode(
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
            request.fullContent(),
            request.selectedText(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.improvementTheme(),
            request.additionalInstructions());
    }

    private static List<SnippetAiResponseSupport.SecurityFinding> reviewSnippetSecurity(
        MainWindow ownerWindow,
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.SecurityReviewRequest request,
        String connectionDisplayName) throws Exception {

        return SnippetAiWorkflowSupport.reviewSnippetSecurity(
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
            request.fullContent(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.additionalInstructions());
    }

    private static SnippetAiResponseSupport.CodeImprovement applySnippetSecurityFixes(
        MainWindow ownerWindow,
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.SecurityFixRequest request,
        String connectionDisplayName) throws Exception {

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
