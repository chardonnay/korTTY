package de.kortty.core;

import java.util.List;

/**
 * Interface for translation API services (e.g. Google Cloud Translation, DeepL).
 * Used by {@link DynamicLanguageGenerator} to translate i18n property values.
 */
public interface TranslationService {

    /**
     * Translates a single text from source to target language.
     *
     * @param text         text to translate
     * @param sourceLang   source language code (e.g. "en")
     * @param targetLang   target language code (e.g. "de")
     * @return translated text, or null on failure
     */
    String translate(String text, String sourceLang, String targetLang);

    /**
     * Translates a batch of texts. Placeholders in values should be preserved by the caller.
     *
     * @param texts        list of texts to translate
     * @param sourceLang   source language code (e.g. "en")
     * @param targetLang   target language code (e.g. "de")
     * @return list of translated texts in same order; null or shorter list on partial failure
     */
    List<String> translateBatch(List<String> texts, String sourceLang, String targetLang);

    /**
     * Tests the API connection (e.g. translate a short test string).
     *
     * @return true if connection and authentication succeed
     */
    boolean testConnection();
}
