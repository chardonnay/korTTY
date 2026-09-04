package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiInternetAccessConfiguration;
import de.kortty.core.AiPromptService;
import de.kortty.core.AiProfileSelectionSupport;
import de.kortty.core.AiReasoningSupport;
import de.kortty.core.AiService;
import de.kortty.core.AiServiceFactory;
import de.kortty.core.AiSkillPromptSupport;
import de.kortty.core.FailingAiService;
import de.kortty.core.TerminalAgentActivityExportService;
import de.kortty.core.WorkflowContextBuilder;
import de.kortty.core.WorkflowScriptSupport;
import de.kortty.core.WorkflowScriptSupport.HeaderFacts;
import de.kortty.core.WorkflowScriptSupport.ScriptLanguage;
import de.kortty.core.WorkflowScriptSupport.WorkflowContext;
import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiWorkload;
import de.kortty.model.GlobalSettings;
import de.kortty.security.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates one-shot script generation from a finished terminal-agent run: resolves the AI
 * profile, decrypts its key, injects relevant agent skills, calls the model and post-processes the
 * result. Lives in the UI package because it touches the application singletons; all deterministic
 * logic is delegated to {@link WorkflowScriptSupport}.
 *
 * <p>{@link #generate} is synchronous and intended to be called inside
 * {@code CompletableFuture.supplyAsync(...)}.
 */
public final class WorkflowScriptGenerator {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowScriptGenerator.class);

    private final KorTTYApplication app;

    public WorkflowScriptGenerator(KorTTYApplication app) {
        this.app = app;
    }

    /** Immutable carrier for the finished run, decoupled from the panel's private snapshot. */
    public record RunExportData(String profileId, String profileName, String sourcePrompt,
                                TerminalAgentActivityExportService.Run run, String detectedOs) {
    }

    /** @param singleLanguage forbid embedded foreign-language parts (heredocs, -e/-c one-liners). */
    public record Request(ScriptLanguage language, EnumSet<WorkflowScriptSupport.HardeningOption> options,
                          String extraInstructions, HeaderFacts headerFacts, String headerOverride,
                          WorkflowScriptSupport.InputHardeningConfig inputHardening,
                          boolean singleLanguage) {

        public Request(ScriptLanguage language, EnumSet<WorkflowScriptSupport.HardeningOption> options,
                       String extraInstructions, HeaderFacts headerFacts, String headerOverride,
                       WorkflowScriptSupport.InputHardeningConfig inputHardening) {
            this(language, options, extraInstructions, headerFacts, headerOverride, inputHardening, false);
        }

        public Request {
            inputHardening = inputHardening != null
                ? inputHardening
                : WorkflowScriptSupport.InputHardeningConfig.disabled();
        }
    }

    public record Outcome(String script, List<String> loadedSkills) {
    }

    public enum FailureKind {
        NO_PROFILE,
        VAULT_LOCKED,
        NOT_PROMPT_SERVICE,
        AI_ERROR
    }

    /** Typed failure so the dialog can show a localized, actionable message. */
    public static final class GenerationException extends RuntimeException {
        private final FailureKind kind;

        public GenerationException(FailureKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public GenerationException(FailureKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public FailureKind kind() {
            return kind;
        }
    }

    public Outcome generate(RunExportData data, Request request) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(request, "request");

        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
        if (settings == null) {
            throw new GenerationException(FailureKind.NO_PROFILE, "Settings unavailable");
        }

        AiProfile profile = resolveProfile(settings, data.profileId());
        if (profile == null) {
            throw new GenerationException(FailureKind.NO_PROFILE, "No AI profile available");
        }

        // Script generation is a pure text transformation — it never needs web access.
        // LM-Studio-MCP modes must survive on the copy because the factory routes those profiles
        // to the native LM Studio service by mode — rerouting them to the OpenAI-compatible client
        // would hit the incompatible /api/v1/chat schema. Their internet use is switched off at
        // request time by the disabled() configuration passed below. Every other mode is forced
        // off on the copy so a Tavily-style profile does not demand its API key.
        AiProfile generationProfile = new AiProfile(profile);
        if (generationProfile.getInternetAccessMode() == null
            || !generationProfile.getInternetAccessMode().usesLmStudioMcp()) {
            generationProfile.setInternetAccessMode(AiInternetAccessMode.DISABLED);
        }

        String apiKey = resolveApiKey(generationProfile);

        WorkflowContext context = WorkflowContextBuilder.build(
            data.run(), WorkflowContextBuilder.DEFAULT_MAX_CONTEXT_CHARS);
        // null override -> AUTO (deterministic header); blank -> NONE (no header); text -> CUSTOM snippet.
        String override = request.headerOverride();
        WorkflowScriptSupport.HeaderMode headerMode = override == null
            ? WorkflowScriptSupport.HeaderMode.AUTO
            : override.isBlank() ? WorkflowScriptSupport.HeaderMode.NONE
            : WorkflowScriptSupport.HeaderMode.CUSTOM;
        String systemPrompt = WorkflowScriptSupport.buildSystemPrompt(
            request.language(), request.options(), headerMode, request.inputHardening());
        if (request.singleLanguage()) {
            systemPrompt += WorkflowScriptSupport.singleLanguageRuleText(request.language());
        }
        String userPrompt = WorkflowScriptSupport.buildUserPrompt(
            request.language(), request.headerFacts(), context, request.options(), request.extraInstructions(), headerMode);

        // Inject relevant agent skills ourselves (language name in the prompt steers the selection),
        // and create the service with skills disabled so they are not appended a second time.
        AiSkillPromptSupport skills = AiSkillPromptSupport.fromSettings(settings, null);
        String systemWithSkills = skills.appendAgentSkills(systemPrompt, userPrompt);

        AiService service = AiServiceFactory.create(
            generationProfile, apiKey, AiInternetAccessConfiguration.disabled(), AiSkillPromptSupport.disabled());
        if (service == null) {
            throw new GenerationException(FailureKind.NO_PROFILE, "AI service could not be created");
        }
        if (service instanceof FailingAiService failing) {
            throw new GenerationException(FailureKind.AI_ERROR, failing.message());
        }
        if (!(service instanceof AiPromptService promptService)) {
            throw new GenerationException(FailureKind.NOT_PROMPT_SERVICE,
                "The selected AI profile cannot run prompts");
        }

        AiExecutionResult result;
        try {
            result = promptService.executePrompt(systemWithSkills, userPrompt);
        } catch (Exception e) {
            logger.warn("Workflow script generation failed", e);
            throw new GenerationException(FailureKind.AI_ERROR,
                e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.toString());
        }

        if (result == null || result.content() == null || result.content().isBlank()) {
            throw new GenerationException(FailureKind.AI_ERROR, "The AI returned an empty response");
        }
        String stripped = WorkflowScriptSupport.stripCodeFences(result.content());
        String script = switch (headerMode) {
            case AUTO -> WorkflowScriptSupport.ensureHeaderInjected(stripped, request.language(), request.headerFacts());
            case CUSTOM -> WorkflowScriptSupport.injectHeaderOverride(stripped, request.language(), override);
            case NONE -> stripped;
        };

        List<String> loadedSkills = skills.drainSkillUsages().stream()
            .map(AiSkillPromptSupport.SkillUsage::name)
            .filter(name -> name != null && !name.isBlank())
            .distinct()
            .toList();

        return new Outcome(script, loadedSkills);
    }

    /** Immutable carrier for a multi-server (swarm) workflow generation. */
    public record SwarmRunExportData(String profileId, String profileName, String userQuery,
                                     List<WorkflowScriptSupport.SwarmHost> hosts,
                                     TerminalAgentActivityExportService.Run representativeRun,
                                     String detectedOs) {
        public SwarmRunExportData {
            hosts = hosts != null ? List.copyOf(hosts) : List.of();
        }
    }

    public record SwarmRequest(ScriptLanguage language,
                               EnumSet<WorkflowScriptSupport.HardeningOption> hardening,
                               EnumSet<WorkflowScriptSupport.SwarmScriptOption> swarmOptions,
                               String extraInstructions, HeaderFacts headerFacts, String headerOverride,
                               WorkflowScriptSupport.InputHardeningConfig inputHardening,
                               boolean singleLanguage) {

        public SwarmRequest(ScriptLanguage language,
                            EnumSet<WorkflowScriptSupport.HardeningOption> hardening,
                            EnumSet<WorkflowScriptSupport.SwarmScriptOption> swarmOptions,
                            String extraInstructions, HeaderFacts headerFacts, String headerOverride,
                            WorkflowScriptSupport.InputHardeningConfig inputHardening) {
            this(language, hardening, swarmOptions, extraInstructions, headerFacts, headerOverride,
                inputHardening, false);
        }

        public SwarmRequest {
            hardening = hardening != null
                ? hardening.clone()
                : EnumSet.noneOf(WorkflowScriptSupport.HardeningOption.class);
            swarmOptions = swarmOptions != null
                ? swarmOptions.clone()
                : EnumSet.noneOf(WorkflowScriptSupport.SwarmScriptOption.class);
            inputHardening = inputHardening != null
                ? inputHardening
                : WorkflowScriptSupport.InputHardeningConfig.disabled();
        }

        @Override
        public EnumSet<WorkflowScriptSupport.HardeningOption> hardening() {
            return hardening.clone();
        }

        @Override
        public EnumSet<WorkflowScriptSupport.SwarmScriptOption> swarmOptions() {
            return swarmOptions.clone();
        }
    }

    /** Generates a multi-host workflow script that runs the per-host work across all target hosts. */
    public Outcome generateSwarm(SwarmRunExportData data, SwarmRequest request) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(request, "request");

        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
        if (settings == null) {
            throw new GenerationException(FailureKind.NO_PROFILE, "Settings unavailable");
        }
        AiProfile profile = resolveProfile(settings, data.profileId());
        if (profile == null) {
            throw new GenerationException(FailureKind.NO_PROFILE, "No AI profile available");
        }
        // Same LM-Studio-MCP guard as generate() above: forcing DISABLED unconditionally would route
        // an LM-Studio-MCP profile through the OpenAI-compatible service instead of the native one.
        AiProfile generationProfile = new AiProfile(profile);
        if (generationProfile.getInternetAccessMode() == null
            || !generationProfile.getInternetAccessMode().usesLmStudioMcp()) {
            generationProfile.setInternetAccessMode(AiInternetAccessMode.DISABLED);
        }
        String apiKey = resolveApiKey(generationProfile);

        WorkflowContext context = data.representativeRun() != null
            ? WorkflowContextBuilder.build(data.representativeRun(), WorkflowContextBuilder.DEFAULT_MAX_CONTEXT_CHARS)
            : new WorkflowContext(
                "(no recorded per-host commands — infer the per-host steps from the originating request)",
                false, 0, 0);

        String override = request.headerOverride();
        WorkflowScriptSupport.HeaderMode headerMode = override == null
            ? WorkflowScriptSupport.HeaderMode.AUTO
            : override.isBlank() ? WorkflowScriptSupport.HeaderMode.NONE
            : WorkflowScriptSupport.HeaderMode.CUSTOM;

        String systemPrompt = WorkflowScriptSupport.buildSwarmSystemPrompt(
            request.language(), request.hardening(), request.swarmOptions(), headerMode, request.inputHardening());
        if (request.singleLanguage()) {
            systemPrompt += WorkflowScriptSupport.singleLanguageRuleText(request.language());
        }
        String userPrompt = WorkflowScriptSupport.buildSwarmUserPrompt(
            request.language(), request.headerFacts(), context, data.hosts(),
            request.swarmOptions(), request.extraInstructions(), headerMode);

        AiSkillPromptSupport skills = AiSkillPromptSupport.fromSettings(settings, null);
        String systemWithSkills = skills.appendAgentSkills(systemPrompt, userPrompt);

        AiService service = AiServiceFactory.create(
            generationProfile, apiKey, AiInternetAccessConfiguration.disabled(), AiSkillPromptSupport.disabled());
        if (service == null) {
            throw new GenerationException(FailureKind.NO_PROFILE, "AI service could not be created");
        }
        if (service instanceof FailingAiService failing) {
            throw new GenerationException(FailureKind.AI_ERROR, failing.message());
        }
        if (!(service instanceof AiPromptService promptService)) {
            throw new GenerationException(FailureKind.NOT_PROMPT_SERVICE, "The selected AI profile cannot run prompts");
        }

        AiExecutionResult result;
        try {
            result = promptService.executePrompt(systemWithSkills, userPrompt);
        } catch (Exception e) {
            logger.warn("Swarm workflow script generation failed", e);
            throw new GenerationException(FailureKind.AI_ERROR,
                e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.toString(), e);
        }
        if (result == null || result.content() == null || result.content().isBlank()) {
            throw new GenerationException(FailureKind.AI_ERROR, "The AI returned an empty response");
        }
        String stripped = WorkflowScriptSupport.stripCodeFences(result.content());
        String script = switch (headerMode) {
            case AUTO -> WorkflowScriptSupport.ensureHeaderInjected(stripped, request.language(), request.headerFacts());
            case CUSTOM -> WorkflowScriptSupport.injectHeaderOverride(stripped, request.language(), override);
            case NONE -> stripped;
        };
        List<String> loadedSkills = skills.drainSkillUsages().stream()
            .map(AiSkillPromptSupport.SkillUsage::name)
            .filter(name -> name != null && !name.isBlank())
            .distinct()
            .toList();
        return new Outcome(script, loadedSkills);
    }

    /**
     * Generates a Mermaid logical-structure diagram for a generated script, reusing the snippet
     * diagram pipeline. Internet access is forced off (same as script generation).
     */
    public de.kortty.model.SnippetDiagram generateDiagram(
        RunExportData data, String scriptContent, ScriptLanguage language, String instructions,
        de.kortty.model.SnippetDiagram existing) {
        Objects.requireNonNull(data, "data");
        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings() : null;
        if (settings == null) {
            return buildFallbackDiagram(scriptContent, language, instructions, existing);
        }
        AiProfile profile = resolveProfile(settings, data.profileId());
        if (profile == null) {
            return buildFallbackDiagram(scriptContent, language, instructions, existing);
        }
        // Same routing rule as generate(): keep LM-Studio-MCP modes on the copy, disable the rest.
        AiProfile generationProfile = new AiProfile(profile);
        if (generationProfile.getInternetAccessMode() == null
            || !generationProfile.getInternetAccessMode().usesLmStudioMcp()) {
            generationProfile.setInternetAccessMode(AiInternetAccessMode.DISABLED);
        }
        String apiKey;
        AiService service;
        try {
            apiKey = resolveApiKey(generationProfile);
            AiProfile executionProfile = AiReasoningSupport.profileForAction(
                generationProfile, de.kortty.core.AiAction.GENERATE_SNIPPET_MERMAID);
            service = AiServiceFactory.create(
                executionProfile, apiKey, AiInternetAccessConfiguration.disabled(), AiSkillPromptSupport.disabled());
        } catch (Exception e) {
            logger.warn("Workflow AI diagram provider could not be initialized; using the local Mermaid fallback", e);
            return buildFallbackDiagram(scriptContent, language, instructions, existing);
        }
        if (service == null) {
            return buildFallbackDiagram(scriptContent, language, instructions, existing);
        }
        if (service instanceof FailingAiService failing) {
            logger.warn("Workflow AI diagram provider is unavailable ({}); using the local Mermaid fallback",
                failing.message());
            return buildFallbackDiagram(scriptContent, language, instructions, existing);
        }
        try {
            de.kortty.core.SnippetAiResponseSupport.MermaidDiagram generated =
                de.kortty.core.SnippetAiWorkflowSupport.generateSnippetMermaid(
                    service, null, scriptContent, language.snippetLanguage(), "", "",
                    instructions != null ? instructions : "");
            if (generated == null || !generated.isUsable()) {
                logger.warn("Workflow AI diagram was rejected ({}); using the local Mermaid fallback",
                    generated != null ? generated.rejectionReason() : "no diagram returned");
                return buildFallbackDiagram(scriptContent, language, instructions, existing);
            }
            return buildPersistedDiagram(generated, scriptContent, instructions, existing);
        } catch (Exception e) {
            logger.warn("Workflow AI diagram generation failed; using the local Mermaid fallback", e);
            return buildFallbackDiagram(scriptContent, language, instructions, existing);
        }
    }

    private de.kortty.model.SnippetDiagram buildFallbackDiagram(
        String scriptContent,
        ScriptLanguage language,
        String instructions,
        de.kortty.model.SnippetDiagram existing) {

        String mermaid = de.kortty.core.SnippetDiagramSupport.buildFallbackLogicalStructureMermaid(
            scriptContent, language != null ? language.snippetLanguage() : "plain");
        de.kortty.core.SnippetAiResponseSupport.MermaidDiagram fallback =
            new de.kortty.core.SnippetAiResponseSupport.MermaidDiagram(
                I18n.get("snippets.ai.diagram.title"), mermaid);
        return buildPersistedDiagram(fallback, scriptContent, instructions, existing);
    }

    private de.kortty.model.SnippetDiagram buildPersistedDiagram(
        de.kortty.core.SnippetAiResponseSupport.MermaidDiagram generated,
        String scriptContent,
        String instructions,
        de.kortty.model.SnippetDiagram existing) {

        // Regenerating an existing diagram must keep its id so the caller replaces it in place
        // (the copy constructor preserves the id); a fresh diagram gets a new id.
        de.kortty.model.SnippetDiagram diagram = existing != null
            ? new de.kortty.model.SnippetDiagram(existing)
            : new de.kortty.model.SnippetDiagram();
        diagram.setTitle(generated.title());
        diagram.setType(de.kortty.model.SnippetDiagram.TYPE_LOGICAL_STRUCTURE);
        diagram.setMermaidSource(generated.mermaid());
        diagram.setSourceContentSha256(de.kortty.core.SnippetDiagramSupport.contentHash(scriptContent));
        diagram.setCustomInstructions(instructions);
        diagram.setCodeReferences(de.kortty.core.SnippetDiagramSupport.buildExpandedCodeReferences(
                generated.mermaid(), scriptContent, generated.codeReferences()).stream()
            .map(reference -> new de.kortty.model.SnippetDiagram.CodeReference(
                reference.nodeId(), reference.label(), reference.startLine(), reference.endLine()))
            .toList());
        diagram.setUpdatedAt(System.currentTimeMillis());
        return diagram;
    }

    private AiProfile resolveProfile(GlobalSettings settings, String profileId) {
        List<AiProfile> profiles = settings.getAiProfiles();
        AiProfile byId = AiProfileSelectionSupport.findById(profiles, profileId);
        if (byId != null) {
            return byId;
        }
        return AiProfileSelectionSupport.workloadProfile(
            profiles,
            AiWorkload.CODING,
            settings.getTextAiProfileId(),
            settings.getCodingAiProfileId(),
            settings.getDefaultAiProfileId());
    }

    private String resolveApiKey(AiProfile profile) {
        String policyKey = de.kortty.policy.PolicyAiProfileSupport.apiKeyOverride(profile);
        if (policyKey != null) {
            return policyKey;
        }
        String encrypted = profile.getEncryptedApiKey();
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        char[] masterPassword = app.getMasterPasswordManager() != null
            ? app.getMasterPasswordManager().getMasterPassword()
            : null;
        if (masterPassword == null) {
            throw new GenerationException(FailureKind.VAULT_LOCKED, "Master password vault is locked");
        }
        try {
            String decrypted = new EncryptionService().decryptPassword(encrypted, masterPassword);
            return decrypted != null && !decrypted.isBlank() ? decrypted : null;
        } catch (Exception e) {
            logger.warn("Could not decrypt AI API key for workflow generation", e);
            throw new GenerationException(FailureKind.AI_ERROR, "Could not decrypt the API key");
        }
    }
}
