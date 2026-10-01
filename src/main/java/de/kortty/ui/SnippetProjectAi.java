package de.kortty.ui;

import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetModularizationSupport;
import de.kortty.core.SnippetProjectAiSupport;

import java.util.Map;

/**
 * The AI calls of the folder analysis and of the modularization option, resolved per request
 * against the current main window's profiles (see {@link SnippetAiAssistFactory#createProjectAi}).
 * Every method blocks: call it from a background task.
 */
interface SnippetProjectAi {

    SnippetAiResponseSupport.ScriptAnalysis analyzeProject(SnippetProjectAiSupport.ProjectContext context,
                                                           String aiProfileId, String fallbackLanguageCode,
                                                           String additionalInstructions,
                                                           SnippetEditDialog.AiProvenanceListener provenance)
        throws Exception;

    SnippetModularizationSupport.ModularizationPlan planModularization(String sourceContext, String language,
                                                                       String aiProfileId,
                                                                       String fallbackLanguageCode,
                                                                       String additionalInstructions)
        throws Exception;

    String generateModule(String sourceContext, String originalSource,
                          SnippetModularizationSupport.ModularizationPlan plan,
                          SnippetModularizationSupport.ModuleFile target, Map<String, String> written,
                          String language, String aiProfileId, String fallbackLanguageCode,
                          String additionalInstructions, String repairHint) throws Exception;

    /** The staged apply of selected improvements to one file (the same flow as the single analysis). */
    SnippetAiResponseSupport.SnippetSecurityFix applyImprovements(SnippetEditDialog.ImprovementApplyRequest request)
        throws Exception;
}
