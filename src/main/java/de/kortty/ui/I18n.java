package de.kortty.ui;

import de.kortty.core.LanguageManager;

/**
 * Helper class for easy access to translations.
 * Provides static methods to get translated strings.
 */
public class I18n {
    
    private static LanguageManager languageManager = LanguageManager.getInstance();
    
    /**
     * Gets a translated string for the given key.
     */
    public static String get(String key) {
        return languageManager.getString(key);
    }
    
    /**
     * Gets a translated string with parameters.
     */
    public static String get(String key, Object... args) {
        return languageManager.getString(key, args);
    }
    
    /**
     * Updates the language manager reference (used when language changes).
     */
    public static void updateLanguageManager() {
        languageManager = LanguageManager.getInstance();
    }
}
