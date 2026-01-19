package de.kortty.ui;

import de.kortty.core.LanguageManager;

/**
 * Helper class for easy access to translations.
 * Provides static methods to get translated strings.
 */
public class I18n {
    
    /**
     * Gets a translated string for the given key.
     * Always uses the current LanguageManager instance to ensure
     * the correct language is used even after language changes.
     */
    public static String get(String key) {
        return LanguageManager.getInstance().getString(key);
    }
    
    /**
     * Gets a translated string with parameters.
     * Always uses the current LanguageManager instance to ensure
     * the correct language is used even after language changes.
     */
    public static String get(String key, Object... args) {
        return LanguageManager.getInstance().getString(key, args);
    }
    
    /**
     * Updates the language manager reference (used when language changes).
     * This method is kept for backwards compatibility but is no longer needed
     * since get() always uses the current instance.
     */
    @Deprecated
    public static void updateLanguageManager() {
        // No-op: get() always uses LanguageManager.getInstance() directly
    }
}
