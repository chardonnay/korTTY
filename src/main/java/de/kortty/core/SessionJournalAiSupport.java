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

    /** Parsed summarizer reply; {@code category} maps to a journal marker. */
    public record SummaryResult(String title, String summary, String category) {
    }

    /** Abstraction the summarizer calls; tests supply a mock, production resolves per call. */
    public interface AiInvoker {
        /** True when an AI profile is resolvable and policy permits AI features. */
        boolean isAvailable();

        AiExecutionResult execute(String systemPrompt, String userPrompt) throws Exception;
    }

    private SessionJournalAiSupport() {
    }

    /** Production invoker bound to the running application; resolves profile fresh per call. */
    public static AiInvoker applicationInvoker() {
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
                    return resolveProfile(app.getGlobalSettingsManager().getSettings()) != null;
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
                AiProfile profile = resolveProfile(settings);
                if (profile == null) {
                    throw new IllegalStateException("No AI profile available for session journal summaries");
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
        };
    }

    /** Journal profile id → TEXT workload role profile → default profile → first profile. */
    public static AiProfile resolveProfile(GlobalSettings settings) {
        if (settings == null || settings.getAiProfiles() == null || settings.getAiProfiles().isEmpty()) {
            return null;
        }
        List<AiProfile> profiles = settings.getAiProfiles();
        AiProfile journalProfile = findById(profiles, settings.getSessionJournalAiProfileId());
        if (journalProfile != null) {
            return journalProfile;
        }
        return AiProfileSelectionSupport.workloadProfile(
            profiles,
            de.kortty.model.AiWorkload.TEXT,
            settings.getTextAiProfileId(),
            settings.getCodingAiProfileId(),
            settings.getDefaultAiProfileId());
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
                return new SummaryResult(title, summary, category);
            }
        } catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException e) {
            // fall through to the plain-text fallback below
        }
        String firstSentence = sanitized.strip();
        int cut = firstSentence.indexOf('.');
        String title = cut > 0 ? firstSentence.substring(0, Math.min(cut, 60)) : null;
        return new SummaryResult(title, sanitized.strip(), null);
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
