package de.kortty.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import de.kortty.KorTTYApplication;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.security.EncryptionService;

import java.util.List;
import java.util.Locale;

/**
 * AI plumbing for the session journal summarizer: profile resolution (journal profile →
 * TEXT-workload role profile → default), per-call service creation, and lenient parsing of the
 * summarizer's JSON replies. Internet access is always disabled for journal prompts — terminal
 * text must never leave through search tools.
 */
public final class SessionJournalAiSupport {

    /**
     * Parsed summarizer reply; {@code category} maps to a journal marker. {@code keywords} are
     * only requested (and produced) by the closing session summary — empty everywhere else.
     */
    public record SummaryResult(String title, String summary, String category, List<String> keywords) {

        public SummaryResult {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }

        public SummaryResult(String title, String summary, String category) {
            this(title, summary, category, List.of());
        }
    }

    /** Parsed screenshot-analysis reply; both parts optional but never both empty. */
    public record ScreenshotAnalysis(String description, List<String> tags) {
    }

    /** Abstraction the summarizer calls; tests supply a mock, production resolves per call. */
    public interface AiInvoker {
        /** True when an AI profile is resolvable and policy permits AI features. */
        boolean isAvailable();

        AiExecutionResult execute(String systemPrompt, String userPrompt) throws Exception;

        /** True when the journal profile also accepts image input; see {@link AiVisionSupport}. */
        default boolean isVisionAvailable() {
            return false;
        }

        /** Executes a strict-JSON prompt with images; only called when {@link #isVisionAvailable()}. */
        default AiExecutionResult executeVision(
            String systemPrompt, String userPrompt, List<AiImageInput> images) throws Exception {

            throw new UnsupportedOperationException("Vision execution is not available");
        }

        /** Display label of the model behind {@link #executeVision}, for provenance; may be null. */
        default String visionModelLabel() {
            return null;
        }
    }

    private SessionJournalAiSupport() {
    }

    /** Production invoker bound to the running application; resolves profile fresh per call. */
    public static AiInvoker applicationInvoker() {
        return invokerFor(SessionJournalAiSupport::resolveProfile, "session journal summaries");
    }

    /**
     * Invoker bound to the AI manager's <em>Text and translation</em> role profile. Translating a
     * note is a text-language job rather than a journal job, so it follows the model the user
     * assigned to that role; only when the role is unset does the default profile step in.
     */
    public static AiInvoker textProfileInvoker() {
        return invokerFor(SessionJournalAiSupport::resolveTextProfile, "text translation");
    }

    private static AiInvoker invokerFor(
            java.util.function.Function<GlobalSettings, AiProfile> profileResolver, String purpose) {
        return new AiInvoker() {
            @Override
            public boolean isAvailable() {
                try {
                    if (!de.kortty.policy.PolicyManager.effective().aiAllowed()) {
                        return false;
                    }
                    KorTTYApplication app = KorTTYApplication.getInstance();
                    if (app == null || app.getGlobalSettingsManager() == null) {
                        return false;
                    }
                    return profileResolver.apply(app.getGlobalSettingsManager().getSettings()) != null;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public AiExecutionResult execute(String systemPrompt, String userPrompt) throws Exception {
                KorTTYApplication app = KorTTYApplication.getInstance();
                if (app == null || app.getGlobalSettingsManager() == null) {
                    throw new IllegalStateException("Application not available for AI execution");
                }
                GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
                AiProfile profile = profileResolver.apply(settings);
                if (profile == null) {
                    throw new IllegalStateException("No AI profile available for " + purpose);
                }
                AiPromptService service = createService(app, settings, profile);
                try {
                    return service.executeJsonPrompt(systemPrompt, userPrompt, AiPromptExecutionScope.TEXT);
                } catch (java.io.IOException e) {
                    if (!looksLikeUnsupportedJsonResponseFormat(e.getMessage())) {
                        throw e;
                    }
                    return service.executeJsonPromptWithoutResponseFormat(
                        systemPrompt, userPrompt, AiPromptExecutionScope.TEXT);
                }
            }

            @Override
            public boolean isVisionAvailable() {
                try {
                    if (!de.kortty.policy.PolicyManager.effective().aiAllowed()) {
                        return false;
                    }
                    KorTTYApplication app = KorTTYApplication.getInstance();
                    if (app == null || app.getGlobalSettingsManager() == null) {
                        return false;
                    }
                    AiProfile profile = profileResolver.apply(app.getGlobalSettingsManager().getSettings());
                    return profile != null && AiVisionSupport.isVisionCapable(profile);
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public AiExecutionResult executeVision(
                    String systemPrompt, String userPrompt, List<AiImageInput> images) throws Exception {
                KorTTYApplication app = KorTTYApplication.getInstance();
                if (app == null || app.getGlobalSettingsManager() == null) {
                    throw new IllegalStateException("Application not available for AI execution");
                }
                GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
                AiProfile profile = profileResolver.apply(settings);
                if (profile == null) {
                    throw new IllegalStateException("No AI profile available for " + purpose);
                }
                AiPromptService service = createService(app, settings, profile);
                try {
                    return service.executeVisionJsonPrompt(
                        systemPrompt, userPrompt, images, AiPromptExecutionScope.TEXT);
                } catch (java.io.IOException e) {
                    if (!looksLikeUnsupportedJsonResponseFormat(e.getMessage())) {
                        throw e;
                    }
                    return service.executeVisionJsonPromptWithoutResponseFormat(
                        systemPrompt, userPrompt, images, AiPromptExecutionScope.TEXT);
                }
            }

            @Override
            public String visionModelLabel() {
                try {
                    KorTTYApplication app = KorTTYApplication.getInstance();
                    if (app == null || app.getGlobalSettingsManager() == null) {
                        return null;
                    }
                    AiProfile profile = profileResolver.apply(app.getGlobalSettingsManager().getSettings());
                    if (profile == null) {
                        return null;
                    }
                    String model = profile.getModel();
                    return model != null && !model.isBlank() ? model.trim() : profile.getName();
                } catch (Exception e) {
                    return null;
                }
            }
        };
    }

    /**
     * The <em>Text and translation</em> role profile from the AI manager, falling back to the
     * default profile when that role carries no selection.
     */
    public static AiProfile resolveTextProfile(GlobalSettings settings) {
        if (settings == null || settings.getAiProfiles() == null || settings.getAiProfiles().isEmpty()) {
            return null;
        }
        return AiProfileSelectionSupport.workloadProfile(
            settings.getAiProfiles(),
            de.kortty.model.AiWorkload.TEXT,
            settings.getTextAiProfileId(),
            settings.getCodingAiProfileId(),
            settings.getDefaultAiProfileId());
    }

    /**
     * Journal profile id → default profile → first profile. Deliberately NOT routed through the
     * Text/Coding role profiles: the journal must follow the user's default AI profile unless a
     * dedicated journal profile is selected.
     */
    public static AiProfile resolveProfile(GlobalSettings settings) {
        if (settings == null || settings.getAiProfiles() == null || settings.getAiProfiles().isEmpty()) {
            return null;
        }
        List<AiProfile> profiles = settings.getAiProfiles();
        AiProfile journalProfile = findById(profiles, settings.getSessionJournalAiProfileId());
        if (journalProfile != null) {
            return journalProfile;
        }
        return AiProfileSelectionSupport.defaultProfile(profiles, settings.getDefaultAiProfileId());
    }

    private static AiProfile findById(List<AiProfile> profiles, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (AiProfile profile : profiles) {
            if (profile != null && id.equals(profile.getId())) {
                return profile;
            }
        }
        return null;
    }

    private static AiPromptService createService(
            KorTTYApplication app, GlobalSettings settings, AiProfile profile) {
        String policyKey = de.kortty.policy.PolicyAiProfileSupport.apiKeyOverride(profile);
        String apiKey = policyKey != null
            ? policyKey
            : decryptApiKey(app, profile.getEncryptedApiKey());
        AiService service = AiServiceFactory.create(
            profile,
            apiKey,
            AiInternetAccessConfiguration.disabled(),
            AiSkillPromptSupport.fromSettings(settings));
        if (!(service instanceof AiPromptService promptService)) {
            throw new IllegalStateException("AI profile is not configured for prompt execution.");
        }
        return promptService;
    }

    private static String decryptApiKey(KorTTYApplication app, String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            return null;
        }
        char[] masterPassword = app.getMasterPasswordManager().getMasterPassword();
        if (masterPassword == null) {
            throw new IllegalStateException("AI API key cannot be decrypted because the master password is locked.");
        }
        try {
            String decrypted = new EncryptionService().decryptPassword(encryptedValue, masterPassword);
            return decrypted != null && !decrypted.isBlank() ? decrypted : null;
        } catch (Exception e) {
            throw new IllegalStateException("AI API key could not be decrypted.", e);
        }
    }

    private static boolean looksLikeUnsupportedJsonResponseFormat(String message) {
        String normalized = message != null ? message.toLowerCase(Locale.ROOT) : "";
        return normalized.contains("response_format")
            || normalized.contains("json_object")
            || normalized.contains("json mode");
    }

    /**
     * Parses the summarizer's JSON reply leniently: think-blocks are stripped, an optional
     * markdown fence is unwrapped, and unparsable content degrades to plain-text summary —
     * a malformed reply must never fail the journal entry.
     */
    public static SummaryResult parseSummaryResult(String content) {
        String sanitized = AiResponseSanitizer.sanitizeForDisplay(content);
        if (sanitized == null || sanitized.isBlank()) {
            return null;
        }
        String candidate = stripJsonFence(sanitized.strip());
        try {
            JsonObject json = JsonParser.parseString(candidate).getAsJsonObject();
            String title = json.has("title") && !json.get("title").isJsonNull()
                ? json.get("title").getAsString() : null;
            String summary = json.has("summary") && !json.get("summary").isJsonNull()
                ? json.get("summary").getAsString() : null;
            String category = json.has("category") && !json.get("category").isJsonNull()
                ? json.get("category").getAsString() : null;
            if (summary != null && !summary.isBlank()) {
                return new SummaryResult(title, summary, category,
                    stringList(json, "keywords", MAX_KEYWORDS, MAX_KEYWORD_LENGTH));
            }
        } catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException e) {
            // fall through to the plain-text fallback below
        }
        String firstSentence = sanitized.strip();
        int cut = firstSentence.indexOf('.');
        String title = cut > 0 ? firstSentence.substring(0, Math.min(cut, 60)) : null;
        return new SummaryResult(title, sanitized.strip(), null);
    }

    /**
     * Parses a note-translation reply leniently: the {@code {"translation": …}} object first, then
     * the sanitized reply itself — a model that just answers with the translated text is doing the
     * right thing in the wrong shape. Returns {@code null} when nothing usable is left, so the
     * caller can keep the user's original note rather than overwrite it with noise.
     */
    public static String parseTranslation(String content) {
        String sanitized = AiResponseSanitizer.sanitizeForDisplay(content);
        if (sanitized == null || sanitized.isBlank()) {
            return null;
        }
        String candidate = stripJsonFence(sanitized.strip());
        try {
            JsonObject json = JsonParser.parseString(candidate).getAsJsonObject();
            String translation = json.has("translation") && !json.get("translation").isJsonNull()
                ? json.get("translation").getAsString() : null;
            // Valid JSON without the field is a wrong answer, not a differently shaped one.
            return translation != null && !translation.isBlank() ? translation.strip() : null;
        } catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException e) {
            return candidate.isBlank() ? null : candidate;
        }
    }

    private static final int MAX_SCREENSHOT_TAGS = 8;
    private static final int MAX_SCREENSHOT_TAG_LENGTH = 40;
    private static final int MAX_KEYWORDS = 12;
    private static final int MAX_KEYWORD_LENGTH = 60;

    /**
     * Parses the screenshot-analysis JSON reply leniently, mirroring {@link #parseSummaryResult}:
     * think-blocks are stripped, a markdown fence is unwrapped, and unparsable content degrades to
     * a description-only result. Tags are normalized (markup stripped, lowercased, deduplicated)
     * and capped at {@value #MAX_SCREENSHOT_TAGS}. Returns {@code null} when nothing usable is
     * left — the entry then simply stays unanalyzed.
     */
    public static ScreenshotAnalysis parseScreenshotAnalysis(String content) {
        String sanitized = AiResponseSanitizer.sanitizeForDisplay(content);
        if (sanitized == null || sanitized.isBlank()) {
            return null;
        }
        String candidate = stripJsonFence(sanitized.strip());
        try {
            JsonObject json = JsonParser.parseString(candidate).getAsJsonObject();
            String description = json.has("description") && !json.get("description").isJsonNull()
                ? json.get("description").getAsString() : null;
            List<String> tags = new java.util.ArrayList<>();
            if (json.has("tags") && json.get("tags").isJsonArray()) {
                for (com.google.gson.JsonElement element : json.getAsJsonArray("tags")) {
                    if (tags.size() >= MAX_SCREENSHOT_TAGS) {
                        break;
                    }
                    try {
                        String tag = normalizeScreenshotTag(element.getAsString());
                        if (tag != null && !tags.contains(tag)) {
                            tags.add(tag);
                        }
                    } catch (RuntimeException ignored) {
                        // A non-string element is a model slip, not a reason to discard the answer.
                    }
                }
            }
            description = description != null && !description.isBlank() ? description.strip() : null;
            if (description != null || !tags.isEmpty()) {
                return new ScreenshotAnalysis(description, List.copyOf(tags));
            }
            return null;
        } catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException e) {
            // Prose fallback: the whole reply is the description, there are no tags.
            return new ScreenshotAnalysis(sanitized.strip(), List.of());
        }
    }

    private static String normalizeScreenshotTag(String tag) {
        if (tag == null) {
            return null;
        }
        String normalized = tag
            .replace("`", "")
            .replace("\"", "")
            .replace("#", "")
            .replaceAll("\\s+", " ")
            .strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > MAX_SCREENSHOT_TAG_LENGTH) {
            normalized = normalized.substring(0, MAX_SCREENSHOT_TAG_LENGTH).stripTrailing();
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    /** Normalizes an AI-produced title: strips markup characters, collapses whitespace, caps length. */
    public static String normalizeTitle(String title, String fallback, int maxLength) {
        if (title == null) {
            return fallback;
        }
        String normalized = title
            .replace("`", "")
            .replace("\"", "")
            .replace("#", "")
            .replace("*", "")
            .replaceAll("\\s+", " ")
            .strip();
        if (normalized.isEmpty()) {
            return fallback;
        }
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength - 3).stripTrailing() + "...";
        }
        return normalized;
    }

    /**
     * Parses a {@code {"ids":[1,4,9]}} selection reply into ordinals in {@code 1..maxOrdinal},
     * dropping duplicates and anything out of range. Returns {@code null} when the reply cannot be
     * parsed at all — unlike a summary, a selection has no meaningful degraded form, so the caller
     * must be able to tell "nothing matched" from "the model did not answer".
     */
    public static java.util.List<Integer> parseIdSelection(String content, int maxOrdinal) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String candidate = stripJsonFence(AiResponseSanitizer.sanitizeForDisplay(content).strip());
        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            com.google.gson.JsonObject json = com.google.gson.JsonParser
                .parseString(candidate.substring(start, end + 1)).getAsJsonObject();
            if (!json.has("ids") || !json.get("ids").isJsonArray()) {
                return null;
            }
            java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>();
            for (com.google.gson.JsonElement element : json.getAsJsonArray("ids")) {
                try {
                    int value = element.getAsInt();
                    if (value >= 1 && value <= maxOrdinal) {
                        ids.add(value);
                    }
                } catch (RuntimeException ignored) {
                    // A non-numeric element is a model slip, not a reason to discard the answer.
                }
            }
            return java.util.List.copyOf(ids);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Parsed journal-Q&amp;A reply; {@code sources} are ordinals into the numbered context. */
    public record AskAnswer(String answer, List<Integer> sources, List<String> logSearchTerms) {
    }

    private static final int MAX_LOG_SEARCH_TERMS = 4;
    private static final int MAX_LOG_SEARCH_TERM_LENGTH = 120;

    /**
     * Parses the journal-Q&amp;A JSON reply leniently: think-blocks stripped, fences unwrapped,
     * out-of-range source ordinals dropped, and bare prose degrades to an answer without sources
     * or search terms — a model answering in the wrong shape is still answering. Returns
     * {@code null} only when nothing usable is left.
     */
    public static AskAnswer parseAskAnswer(String content, int maxOrdinal) {
        String sanitized = AiResponseSanitizer.sanitizeForDisplay(content);
        if (sanitized == null || sanitized.isBlank()) {
            return null;
        }
        String candidate = stripJsonFence(sanitized.strip());
        try {
            JsonObject json = JsonParser.parseString(candidate).getAsJsonObject();
            String answer = json.has("answer") && !json.get("answer").isJsonNull()
                ? json.get("answer").getAsString() : null;
            if (answer == null || answer.isBlank()) {
                return null;
            }
            return new AskAnswer(answer.strip(),
                ordinals(json, "sources", maxOrdinal),
                stringList(json, "logSearchTerms", MAX_LOG_SEARCH_TERMS, MAX_LOG_SEARCH_TERM_LENGTH));
        } catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException e) {
            return new AskAnswer(sanitized.strip(), List.of(), List.of());
        }
    }

    /**
     * Parses a {@code {"terms":[...]}} reply. Returns {@code null} when the reply cannot be
     * parsed at all — the caller then extracts terms deterministically instead; an empty list is
     * the model's valid "nothing to search for".
     */
    public static List<String> parseSearchTerms(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String candidate = stripJsonFence(AiResponseSanitizer.sanitizeForDisplay(content).strip());
        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(candidate.substring(start, end + 1)).getAsJsonObject();
            if (!json.has("terms") || !json.get("terms").isJsonArray()) {
                return null;
            }
            return stringList(json, "terms", MAX_LOG_SEARCH_TERMS, MAX_LOG_SEARCH_TERM_LENGTH);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** One journal the cross-search model selected, by its ordinal in the prompt. */
    public record CrossSearchSelection(int ordinal, String reason) {
    }

    /** Parsed cross-journal search reply. */
    public record CrossSearchResult(String answer, List<CrossSearchSelection> selections) {
    }

    /**
     * Parses the cross-journal search JSON reply leniently, in the {@link #parseAskAnswer}
     * style: out-of-range or duplicate ordinals dropped, bare prose degrades to an answer with
     * no selections (the caller then keeps its prefilter ranking). Null when nothing usable.
     */
    public static CrossSearchResult parseCrossSearchResult(String content, int maxOrdinal) {
        String sanitized = AiResponseSanitizer.sanitizeForDisplay(content);
        if (sanitized == null || sanitized.isBlank()) {
            return null;
        }
        String candidate = stripJsonFence(sanitized.strip());
        try {
            JsonObject json = JsonParser.parseString(candidate).getAsJsonObject();
            String answer = json.has("answer") && !json.get("answer").isJsonNull()
                ? json.get("answer").getAsString() : null;
            if (answer == null || answer.isBlank()) {
                return null;
            }
            java.util.LinkedHashMap<Integer, CrossSearchSelection> selections = new java.util.LinkedHashMap<>();
            if (json.has("journals") && json.get("journals").isJsonArray()) {
                for (com.google.gson.JsonElement element : json.getAsJsonArray("journals")) {
                    try {
                        JsonObject selection = element.getAsJsonObject();
                        int ordinal = selection.get("ordinal").getAsInt();
                        if (ordinal < 1 || ordinal > maxOrdinal) {
                            continue;
                        }
                        String reason = selection.has("reason") && !selection.get("reason").isJsonNull()
                            ? selection.get("reason").getAsString().strip() : null;
                        selections.putIfAbsent(ordinal, new CrossSearchSelection(ordinal, reason));
                    } catch (RuntimeException ignored) {
                        // A malformed element is a model slip, not a reason to discard the answer.
                    }
                }
            }
            return new CrossSearchResult(answer.strip(), List.copyOf(selections.values()));
        } catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException e) {
            return new CrossSearchResult(sanitized.strip(), List.of());
        }
    }

    /** In-range, deduplicated ordinals from a JSON int array; empty when absent or malformed. */
    private static List<Integer> ordinals(JsonObject json, String field, int maxOrdinal) {
        if (!json.has(field) || !json.get(field).isJsonArray()) {
            return List.of();
        }
        java.util.LinkedHashSet<Integer> values = new java.util.LinkedHashSet<>();
        for (com.google.gson.JsonElement element : json.getAsJsonArray(field)) {
            try {
                int value = element.getAsInt();
                if (value >= 1 && value <= maxOrdinal) {
                    values.add(value);
                }
            } catch (RuntimeException ignored) {
                // A non-numeric element is a model slip, not a reason to discard the answer.
            }
        }
        return List.copyOf(values);
    }

    /** Deduplicated, trimmed, capped strings from a JSON string array; empty when absent. */
    private static List<String> stringList(JsonObject json, String field, int maxItems, int maxLength) {
        if (!json.has(field) || !json.get(field).isJsonArray()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        for (com.google.gson.JsonElement element : json.getAsJsonArray(field)) {
            if (values.size() >= maxItems) {
                break;
            }
            try {
                String value = element.getAsString().strip();
                if (!value.isEmpty()) {
                    values.add(value.length() > maxLength ? value.substring(0, maxLength) : value);
                }
            } catch (RuntimeException ignored) {
                // A non-string element is a model slip, not a reason to discard the answer.
            }
        }
        return List.copyOf(values);
    }

    private static String stripJsonFence(String content) {
        String result = content;
        if (result.startsWith("```")) {
            int firstLineEnd = result.indexOf('\n');
            if (firstLineEnd > 0) {
                result = result.substring(firstLineEnd + 1);
            }
            int fenceEnd = result.lastIndexOf("```");
            if (fenceEnd >= 0) {
                result = result.substring(0, fenceEnd);
            }
        }
        return result.strip();
    }
}
