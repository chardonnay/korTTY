package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiInternetAccessConfiguration;
import de.kortty.core.AiPromptService;
import de.kortty.core.AiProfileSelectionSupport;
import de.kortty.core.AiService;
import de.kortty.core.AiServiceFactory;
import de.kortty.core.AiSkillPromptSupport;
import de.kortty.core.FailingAiService;
import de.kortty.core.GuideAskPromptSupport;
import de.kortty.core.GuideDocsRetriever;
import de.kortty.core.GuideSearchIndex;
import de.kortty.core.LanguageManager;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.security.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Answers a question about the bundled guide: retrieves the best documentation excerpts from
 * the offline search index, then asks the user's default AI profile to answer from them only.
 * Mirrors {@link WorkflowScriptGenerator} (profile resolution, key decryption, forced-offline
 * profile copy, typed failures); lives in the UI package because it touches the application
 * singletons. {@link #ask} is synchronous and intended to run inside
 * {@code CompletableFuture.supplyAsync(...)}.
 */
public final class GuideAskService {

    private static final Logger logger = LoggerFactory.getLogger(GuideAskService.class);

    // Context budgets in characters; small local models get a tighter budget than cloud models.
    private static final int CLOUD_CONTEXT_CHARS = 16_000;
    private static final int LOCAL_CONTEXT_CHARS = 8_000;
    private static final int MIN_CONTEXT_CHARS = 1_000;
    private static final int CLOUD_MAX_EXCERPTS = 12;
    private static final int LOCAL_MAX_EXCERPTS = 7;

    private final KorTTYApplication app;

    public GuideAskService(KorTTYApplication app) {
        this.app = app;
    }

    /** {@code nothingRetrieved} means no excerpt matched — no model call was made. */
    public record Answer(String markdown, List<GuideDocsRetriever.Excerpt> excerpts,
                         boolean nothingRetrieved) {
    }

    public enum FailureKind {
        NO_PROFILE,
        VAULT_LOCKED,
        NOT_PROMPT_SERVICE,
        AI_ERROR,
        NO_INDEX
    }

    /** Typed failure so the panel can show a localized, actionable message. */
    public static final class AskException extends RuntimeException {
        private final FailureKind kind;

        public AskException(FailureKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public FailureKind kind() {
            return kind;
        }
    }

    public Answer ask(String question, String guideLang) {
        Objects.requireNonNull(question, "question");

        GuideSearchIndex primary = GuideSearchIndex.load(guideLang);
        if (primary == null && !"en".equals(guideLang)) {
            primary = GuideSearchIndex.load("en");
        }
        if (primary == null) {
            throw new AskException(FailureKind.NO_INDEX, "Bundled guide search index missing");
        }
        GuideSearchIndex fallback = "de".equals(primary.language())
            ? GuideSearchIndex.load("en")
            : GuideSearchIndex.load("de");

        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
        if (settings == null) {
            throw new AskException(FailureKind.NO_PROFILE, "Settings unavailable");
        }
        AiProfile profile = AiProfileSelectionSupport.defaultProfile(
            settings.getAiProfiles(), settings.getDefaultAiProfileId());
        if (profile == null) {
            throw new AskException(FailureKind.NO_PROFILE, "No AI profile available");
        }

        // Answering from bundled docs never needs web access. Force the internet mode off on a
        // copy so a profile configured for Tavily/MCP search does not demand its API key (the
        // factory derives the mode from the profile, not the passed config).
        AiProfile askProfile = new AiProfile(profile);
        askProfile.setInternetAccessMode(AiInternetAccessMode.DISABLED);

        int budget = contextBudget(askProfile);
        int maxExcerpts = budget <= LOCAL_CONTEXT_CHARS ? LOCAL_MAX_EXCERPTS : CLOUD_MAX_EXCERPTS;
        GuideDocsRetriever.RetrievalResult retrieval =
            GuideDocsRetriever.retrieve(primary, fallback, question, budget, maxExcerpts);
        if (retrieval.excerpts().isEmpty()) {
            // Deterministic "not covered by the manual" — skip the model call entirely.
            return new Answer("", List.of(), true);
        }

        String apiKey = resolveApiKey(askProfile);
        AiService service = AiServiceFactory.create(
            askProfile, apiKey, AiInternetAccessConfiguration.disabled(), AiSkillPromptSupport.disabled());
        if (service == null) {
            throw new AskException(FailureKind.NO_PROFILE, "AI service could not be created");
        }
        if (service instanceof FailingAiService failing) {
            throw new AskException(FailureKind.AI_ERROR, failing.message());
        }
        if (!(service instanceof AiPromptService promptService)) {
            throw new AskException(FailureKind.NOT_PROMPT_SERVICE,
                "The selected AI profile cannot run prompts");
        }

        String systemPrompt = GuideAskPromptSupport.buildSystemPrompt(
            answerLanguageDisplayName(), I18n.get("guide.ask.notFound"));
        String userPrompt = GuideAskPromptSupport.buildUserPrompt(question, retrieval.excerpts());

        AiExecutionResult result;
        try {
            result = promptService.executePrompt(systemPrompt, userPrompt);
        } catch (Exception e) {
            logger.warn("Guide AI ask failed", e);
            throw new AskException(FailureKind.AI_ERROR,
                e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.toString());
        }
        if (result == null || result.content() == null || result.content().isBlank()) {
            throw new AskException(FailureKind.AI_ERROR, "The AI returned an empty response");
        }

        List<String> allowedLocations = new ArrayList<>();
        for (GuideDocsRetriever.Excerpt excerpt : retrieval.excerpts()) {
            allowedLocations.add(excerpt.location());
        }
        String sanitized = GuideAskPromptSupport.sanitizeAnswer(result.content(), allowedLocations);
        return new Answer(sanitized, retrieval.excerpts(), false);
    }

    /** Char budget for excerpts: provider-dependent default clamped by the profile's own limit. */
    private static int contextBudget(AiProfile profile) {
        boolean local = profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI
            || isLocalEndpoint(profile.getApiUrl());
        int budget = local ? LOCAL_CONTEXT_CHARS : CLOUD_CONTEXT_CHARS;
        Integer maxSelection = profile.getMaxSelectionChars();
        if (maxSelection != null && maxSelection > 0) {
            budget = Math.min(budget, Math.max(MIN_CONTEXT_CHARS, maxSelection));
        }
        return budget;
    }

    private static boolean isLocalEndpoint(String apiUrl) {
        if (apiUrl == null) {
            return false;
        }
        String lower = apiUrl.toLowerCase(Locale.ROOT);
        return lower.contains("localhost") || lower.contains("127.0.0.1");
    }

    private String answerLanguageDisplayName() {
        try {
            Locale locale = LanguageManager.getInstance().getCurrentLocale();
            String display = locale != null ? locale.getDisplayLanguage(Locale.ENGLISH) : null;
            return display != null && !display.isBlank() ? display : "English";
        } catch (RuntimeException e) {
            return "English";
        }
    }

    private String resolveApiKey(AiProfile profile) {
        String encrypted = profile.getEncryptedApiKey();
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        char[] masterPassword = app.getMasterPasswordManager() != null
            ? app.getMasterPasswordManager().getMasterPassword()
            : null;
        if (masterPassword == null) {
            throw new AskException(FailureKind.VAULT_LOCKED, "Master password vault is locked");
        }
        try {
            String decrypted = new EncryptionService().decryptPassword(encrypted, masterPassword);
            return decrypted != null && !decrypted.isBlank() ? decrypted : null;
        } catch (Exception e) {
            logger.warn("Could not decrypt AI API key for guide ask", e);
            throw new AskException(FailureKind.AI_ERROR, "Could not decrypt the API key");
        }
    }
}
