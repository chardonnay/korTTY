package de.kortty.core;

import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiWorkload;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TranslationApiProvider;
import de.kortty.model.TranslationProfile;

/**
 * Builds the {@link TranslationService} for a {@link TranslationProfile}: one of the API clients,
 * or an AI profile wrapped in {@link LocalAiTranslationService}. Secrets stay encrypted in the
 * settings; the caller passes a {@link SecretResolver} that decrypts with the unlocked vault, so
 * this class never touches the master password itself.
 */
public final class TranslationServiceFactory {

    /** Decrypts a master-password-encrypted value; null when the vault is locked or the value is unreadable. */
    @FunctionalInterface
    public interface SecretResolver {
        String plain(String encrypted);

        /** Never decrypts anything: for a locked vault. */
        SecretResolver NONE = encrypted -> null;
    }

    /** Why a profile cannot translate right now, for the UI. */
    public enum Problem {
        NO_PROFILE, NO_KEY, NO_AI_PROFILE, AI_PROFILE_MISSING, AI_PROFILE_NOT_LOCAL, AI_PROFILE_UNUSABLE
    }

    private TranslationServiceFactory() { }

    /** The service for the default profile, or null when none is configured or it cannot run. */
    public static TranslationService createDefault(GlobalSettings settings, SecretResolver secrets) {
        TranslationProfile profile = settings != null ? settings.getDefaultTranslationProfile() : null;
        return profile != null ? create(profile, settings, secrets) : null;
    }

    /**
     * The service for {@code profile}, with {@code plainKey} overriding the stored key (the editor's
     * unsaved field). Null when the profile cannot translate; {@link #problem} says why.
     */
    public static TranslationService create(TranslationProfile profile, GlobalSettings settings,
                                            SecretResolver secrets, String plainKey) {
        if (profile == null) {
            return null;
        }
        String key = plainKey != null && !plainKey.isBlank() ? plainKey : key(profile, secrets);
        String url = profile.getApiUrl();
        switch (profile.getProvider()) {
            case GOOGLE_TRANSLATE:
                return key == null ? null : new GoogleTranslationService(key, url);
            case DEEPL:
                return key == null ? null : new DeepLTranslationService(key, url);
            case LIBRETRANSLATE:
                return new LibreTranslateTranslationService(key != null ? key : "", url);
            case MICROSOFT:
                return key == null ? null : new MicrosoftTranslationService(key, url, profile.getApiRegion());
            case YANDEX:
                return key == null ? null : new YandexTranslationService(key, url);
            case LOCAL_AI_PROFILE:
                return createAi(aiProfile(profile, settings), secrets);
            default:
                return null;
        }
    }

    public static TranslationService create(TranslationProfile profile, GlobalSettings settings, SecretResolver secrets) {
        return create(profile, settings, secrets, null);
    }

    /** The reason {@link #create} returns null for {@code profile}, or null when it would succeed. */
    public static Problem problem(TranslationProfile profile, GlobalSettings settings, SecretResolver secrets, String plainKey) {
        if (profile == null) {
            return Problem.NO_PROFILE;
        }
        if (profile.isAi()) {
            AiProfile ai = aiProfile(profile, settings);
            if (ai == null) {
                return profile.getAiProfileId() != null ? Problem.AI_PROFILE_MISSING : Problem.NO_AI_PROFILE;
            }
            if (profile.getAiProfileId() == null && !ai.getConnectionMode().isEmbedded()) {
                return Problem.AI_PROFILE_NOT_LOCAL;
            }
            return createAi(ai, secrets) == null ? Problem.AI_PROFILE_UNUSABLE : null;
        }
        if (!profile.isKeyOptional()) {
            String key = plainKey != null && !plainKey.isBlank() ? plainKey : key(profile, secrets);
            if (key == null) {
                return Problem.NO_KEY;
            }
        }
        return null;
    }

    /** The decrypted API key of {@code profile}, or null. */
    public static String key(TranslationProfile profile, SecretResolver secrets) {
        String encrypted = profile != null ? profile.getEncryptedApiKey() : null;
        if (encrypted == null || encrypted.isBlank() || secrets == null) {
            return null;
        }
        String plain = secrets.plain(encrypted);
        return plain == null || plain.isBlank() ? null : plain;
    }

    /**
     * The AI profile a translation profile points at: the named one, or the text-role profile when
     * none is named (the pre-profile default, which only counts when it runs a local model).
     */
    public static AiProfile aiProfile(TranslationProfile profile, GlobalSettings settings) {
        if (settings == null || profile == null) {
            return null;
        }
        if (profile.getAiProfileId() != null) {
            for (AiProfile candidate : settings.getAiProfiles()) {
                if (profile.getAiProfileId().equals(candidate.getId())) {
                    return candidate;
                }
            }
            return null;
        }
        AiProfile text = AiProfileSelectionSupport.workloadProfile(settings.getAiProfiles(), AiWorkload.TEXT,
            settings.getTextAiProfileId(), settings.getCodingAiProfileId(), settings.getDefaultAiProfileId());
        return text != null && text.getConnectionMode().isEmbedded() ? text : null;
    }

    /** Wraps an AI profile as a translation service; null when its service cannot be built. */
    public static TranslationService createAi(AiProfile profile, SecretResolver secrets) {
        if (profile == null) {
            return null;
        }
        // Translation never needs web search; a disabled mode keeps the factory's web-tool checks quiet.
        AiProfile copy = new AiProfile(profile);
        copy.setInternetAccessMode(AiInternetAccessMode.DISABLED);
        AiService service;
        try {
            String key = copy.getConnectionMode().isEmbedded() ? null : profileApiKey(copy, secrets);
            service = AiServiceFactory.create(copy, key, AiInternetAccessConfiguration.disabled(),
                AiSkillPromptSupport.disabled());
        } catch (IllegalStateException e) {
            return null;
        }
        return service instanceof AiPromptService prompt ? new LocalAiTranslationService(prompt) : null;
    }

    private static String profileApiKey(AiProfile profile, SecretResolver secrets) {
        String policyKey = de.kortty.policy.PolicyAiProfileSupport.apiKeyOverride(profile);
        if (policyKey != null) {
            return policyKey;
        }
        String encrypted = profile.getEncryptedApiKey();
        return encrypted == null || encrypted.isBlank() || secrets == null ? null : secrets.plain(encrypted);
    }

    /** The spelled-out provider for lists and the wizard. */
    public static String providerLabel(TranslationApiProvider provider) {
        return GlobalSettings.defaultTranslationProfileName(provider);
    }
}
