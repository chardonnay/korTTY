package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

import java.util.Objects;
import java.util.UUID;

/**
 * One way of translating text: a translation API (Google, DeepL, LibreTranslate, Microsoft,
 * Yandex) with its key, endpoint and region, or an AI profile ({@link TranslationApiProvider#LOCAL_AI_PROFILE}).
 * Settings → Translation keeps a list of these; exactly one is the default
 * ({@link GlobalSettings#getDefaultTranslationProfileId()}) and is what korTTY translates with.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class TranslationProfile {

    @XmlElement
    private String id;
    @XmlElement
    private String name;
    @XmlElement
    private TranslationApiProvider provider = TranslationApiProvider.GOOGLE_TRANSLATE;
    /** Master-password-encrypted API key; null for providers that need none. */
    @XmlElement
    private String encryptedApiKey;
    /** Custom endpoint, or null for the provider's default. */
    @XmlElement
    private String apiUrl;
    /** Azure region, Microsoft only. */
    @XmlElement
    private String apiRegion;
    /** The AI profile that translates, for {@link TranslationApiProvider#LOCAL_AI_PROFILE}; null = the text role profile. */
    @XmlElement
    private String aiProfileId;

    public TranslationProfile() {
    }

    public TranslationProfile(String name, TranslationApiProvider provider) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.provider = provider;
    }

    /** Copy constructor; keeps the id. */
    public TranslationProfile(TranslationProfile other) {
        this.id = other.id;
        this.name = other.name;
        this.provider = other.provider;
        this.encryptedApiKey = other.encryptedApiKey;
        this.apiUrl = other.apiUrl;
        this.apiRegion = other.apiRegion;
        this.aiProfileId = other.aiProfileId;
    }

    /** True for the AI kind: the text comes from an AI profile, not a translation API. */
    public boolean isAi() {
        return provider == TranslationApiProvider.LOCAL_AI_PROFILE;
    }

    /** Whether this provider works without an API key (LibreTranslate public instances, AI profiles). */
    public boolean isKeyOptional() {
        return provider == TranslationApiProvider.LIBRETRANSLATE || isAi();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TranslationApiProvider getProvider() {
        return provider != null ? provider : TranslationApiProvider.GOOGLE_TRANSLATE;
    }

    public void setProvider(TranslationApiProvider provider) {
        this.provider = provider != null ? provider : TranslationApiProvider.GOOGLE_TRANSLATE;
    }

    public String getEncryptedApiKey() {
        return encryptedApiKey;
    }

    public void setEncryptedApiKey(String encryptedApiKey) {
        this.encryptedApiKey = blankToNull(encryptedApiKey);
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = blankToNull(apiUrl);
    }

    public String getApiRegion() {
        return apiRegion;
    }

    public void setApiRegion(String apiRegion) {
        this.apiRegion = blankToNull(apiRegion);
    }

    public String getAiProfileId() {
        return aiProfileId;
    }

    public void setAiProfileId(String aiProfileId) {
        this.aiProfileId = blankToNull(aiProfileId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof TranslationProfile other && Objects.equals(id, other.id));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return name != null ? name : String.valueOf(provider);
    }
}
