package de.kortty.core;

import de.kortty.model.GlobalSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Manages internationalization (i18n) for the application.
 * Supports multiple languages and provides translation functionality.
 */
public class LanguageManager {
    
    private static final Logger logger = LoggerFactory.getLogger(LanguageManager.class);
    private static final String BUNDLE_BASE_NAME = "i18n.messages";
    
    private static LanguageManager instance;
    private Locale currentLocale;
    private ResourceBundle resourceBundle;
    
    // Supported locales
    public static final Locale[] SUPPORTED_LOCALES = {
        Locale.ENGLISH,           // en
        Locale.GERMAN,            // de
        Locale.ITALIAN,           // it
        new Locale("es"),         // es (Spanish)
        new Locale("pt"),         // pt (Portuguese)
        Locale.FRENCH,            // fr
        new Locale("hr"),         // hr (Croatian/Serbo-Croatian)
        new Locale("nl")          // nl (Dutch)
    };
    
    private LanguageManager() {
        // Detect system locale
        Locale systemLocale = Locale.getDefault();
        currentLocale = detectSupportedLocale(systemLocale);
        loadResourceBundle();
    }
    
    /**
     * Gets the singleton instance of LanguageManager.
     */
    public static synchronized LanguageManager getInstance() {
        if (instance == null) {
            instance = new LanguageManager();
        }
        return instance;
    }
    
    /**
     * Initializes the language manager with settings from GlobalSettings.
     * Should be called after GlobalSettings are loaded.
     */
    public void initialize(GlobalSettings settings) {
        if (settings != null && settings.getLanguage() != null && !settings.getLanguage().isEmpty()) {
            try {
                String[] parts = settings.getLanguage().split("_");
                if (parts.length == 2) {
                    setLocale(new Locale(parts[0], parts[1]));
                } else {
                    setLocale(new Locale(parts[0]));
                }
            } catch (Exception e) {
                logger.warn("Failed to set locale from settings: {}", settings.getLanguage(), e);
            }
        }
    }
    
    /**
     * Detects if the system locale is supported, otherwise returns English.
     */
    private Locale detectSupportedLocale(Locale systemLocale) {
        // Check exact match first
        for (Locale supported : SUPPORTED_LOCALES) {
            if (supported.getLanguage().equals(systemLocale.getLanguage())) {
                return supported;
            }
        }
        // Default to English
        return Locale.ENGLISH;
    }
    
    /**
     * Sets the current locale and reloads the resource bundle.
     */
    public void setLocale(Locale locale) {
        if (locale == null) {
            locale = Locale.ENGLISH;
        }
        
        // Verify locale is supported
        boolean supported = false;
        for (Locale supportedLocale : SUPPORTED_LOCALES) {
            if (supportedLocale.getLanguage().equals(locale.getLanguage())) {
                supported = true;
                currentLocale = supportedLocale;
                break;
            }
        }
        
        if (!supported) {
            logger.warn("Locale {} is not supported, falling back to English", locale);
            currentLocale = Locale.ENGLISH;
        }
        
        loadResourceBundle();
    }
    
    /**
     * Sets the locale from a language code (e.g., "en", "de", "fr").
     */
    public void setLocale(String languageCode) {
        if (languageCode == null || languageCode.isEmpty()) {
            setLocale(Locale.ENGLISH);
            return;
        }
        
        Locale locale = new Locale(languageCode);
        setLocale(locale);
    }
    
    /**
     * Loads the resource bundle for the current locale.
     */
    private void loadResourceBundle() {
        try {
            resourceBundle = ResourceBundle.getBundle(BUNDLE_BASE_NAME, currentLocale);
            logger.info("Loaded resource bundle for locale: {}", currentLocale);
        } catch (MissingResourceException e) {
            logger.error("Failed to load resource bundle for locale: {}, falling back to English", currentLocale, e);
            resourceBundle = ResourceBundle.getBundle(BUNDLE_BASE_NAME, Locale.ENGLISH);
        }
    }
    
    /**
     * Gets a translated string for the given key.
     * Returns the key itself if translation is not found.
     */
    public String getString(String key) {
        if (resourceBundle == null) {
            logger.warn("Resource bundle not loaded, returning key: {}", key);
            return key;
        }
        
        try {
            return resourceBundle.getString(key);
        } catch (MissingResourceException e) {
            logger.warn("Translation key not found: {}", key);
            return key;
        }
    }
    
    /**
     * Gets a translated string with parameters.
     * Uses {0}, {1}, etc. as placeholders.
     */
    public String getString(String key, Object... args) {
        String template = getString(key);
        if (template == null || template.equals(key)) {
            return template;
        }
        
        for (int i = 0; i < args.length; i++) {
            template = template.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return template;
    }
    
    /**
     * Gets the current locale.
     */
    public Locale getCurrentLocale() {
        return currentLocale;
    }
    
    /**
     * Gets the current locale as a string (e.g., "en", "de").
     */
    public String getCurrentLanguageCode() {
        return currentLocale.getLanguage();
    }
    
    /**
     * Gets the current locale as a full string (e.g., "en_US", "de_DE").
     */
    public String getCurrentLocaleString() {
        if (currentLocale.getCountry().isEmpty()) {
            return currentLocale.getLanguage();
        }
        return currentLocale.getLanguage() + "_" + currentLocale.getCountry();
    }
    
    /**
     * Gets all supported locales.
     */
    public static Locale[] getSupportedLocales() {
        return SUPPORTED_LOCALES.clone();
    }
    
    /**
     * Gets the display name for a locale in its own language.
     */
    public static String getLocaleDisplayName(Locale locale) {
        return locale.getDisplayLanguage(locale);
    }
}
