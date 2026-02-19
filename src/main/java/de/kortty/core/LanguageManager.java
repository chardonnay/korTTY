package de.kortty.core;

import de.kortty.KorTTYApplication;
import de.kortty.model.GlobalSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * Manages internationalization (i18n) for the application.
 * Supports multiple languages and provides translation functionality.
 */
public class LanguageManager {
    
    private static final Logger logger = LoggerFactory.getLogger(LanguageManager.class);
    private static final String BUNDLE_BASE_NAME = "i18n.messages";
    
    private static final ResourceBundle.Control UTF8_CONTROL = new ResourceBundle.Control() {
        @Override
        public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader, boolean reload)
                throws IllegalAccessException, InstantiationException, IOException {
            String bundleName = toBundleName(baseName, locale);
            String resourceName = toResourceName(bundleName, "properties");
            try (InputStream stream = loader.getResourceAsStream(resourceName)) {
                if (stream != null) {
                    return new PropertyResourceBundle(new InputStreamReader(stream, StandardCharsets.UTF_8));
                }
            }
            return super.newBundle(baseName, locale, format, loader, reload);
        }
    };

    private static LanguageManager instance;
    private Locale currentLocale;
    private ResourceBundle resourceBundle;
    private boolean initialized = false;
    
    // Supported locales
    public static final Locale[] SUPPORTED_LOCALES = {
        Locale.ENGLISH,           // en
        Locale.GERMAN,            // de
        Locale.ITALIAN,           // it
        Locale.forLanguageTag("es"),  // es (Spanish)
        Locale.forLanguageTag("pt"),  // pt (Portuguese)
        Locale.FRENCH,            // fr
        Locale.forLanguageTag("hr"),  // hr (Croatian/Serbo-Croatian)
        Locale.forLanguageTag("nl")   // nl (Dutch)
    };
    
    private LanguageManager() {
        // Don't set locale in constructor - wait for initialize() to be called
        // Use English as temporary fallback until initialize() is called
        currentLocale = Locale.ENGLISH;
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
     * If language is null or empty, uses system locale (auto-detect).
     */
    public void initialize(GlobalSettings settings) {
        initialized = true;
        
        if (settings != null) {
            String languageSetting = settings.getLanguage();
            logger.debug("Initializing LanguageManager with language setting: '{}' (null: {}, empty: {})", 
                languageSetting, languageSetting == null, languageSetting != null && languageSetting.isEmpty());
            
            if (languageSetting != null && !languageSetting.isEmpty()) {
                try {
                    String[] parts = languageSetting.split("_");
                    Locale localeToSet;
                    if (parts.length == 2) {
                        localeToSet = Locale.forLanguageTag(parts[0] + "-" + parts[1]);
                    } else {
                        localeToSet = Locale.forLanguageTag(parts[0]);
                    }
                    logger.info("Language set from settings: '{}' -> locale: {}", languageSetting, localeToSet);
                    setLocale(localeToSet);
                } catch (Exception e) {
                    logger.warn("Failed to set locale from settings: {}", languageSetting, e);
                    // Fall back to system locale
                    Locale systemLocale = Locale.getDefault();
                    setLocale(detectSupportedLocale(systemLocale));
                }
            } else {
                // Auto-detect: use system locale
                Locale systemLocale = Locale.getDefault();
                Locale detectedLocale = detectSupportedLocale(systemLocale);
                logger.info("Language auto-detected from system: {} (language setting was null or empty)", detectedLocale);
                setLocale(detectedLocale);
            }
        } else {
            logger.warn("GlobalSettings is null, using system locale");
            Locale systemLocale = Locale.getDefault();
            Locale detectedLocale = detectSupportedLocale(systemLocale);
            setLocale(detectedLocale);
        }
    }
    
    /**
     * Checks if the language manager has been initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Detects if the system locale is supported (static or dynamic), otherwise returns English.
     */
    private Locale detectSupportedLocale(Locale systemLocale) {
        String lang = systemLocale.getLanguage();
        for (Locale supported : SUPPORTED_LOCALES) {
            if (supported.getLanguage().equals(lang)) {
                return supported;
            }
        }
        for (Locale dynamic : getAvailableDynamicLocales()) {
            if (dynamic.getLanguage().equals(lang)) {
                return dynamic;
            }
        }
        return Locale.ENGLISH;
    }
    
    /**
     * Sets the current locale and reloads the resource bundle.
     * Accepts both static (SUPPORTED_LOCALES) and dynamic (generated) locales.
     */
    public void setLocale(Locale locale) {
        if (locale == null) {
            locale = Locale.ENGLISH;
        }
        
        boolean supported = false;
        Locale previousLocale = currentLocale;
        for (Locale supportedLocale : SUPPORTED_LOCALES) {
            if (supportedLocale.getLanguage().equals(locale.getLanguage())) {
                supported = true;
                currentLocale = supportedLocale;
                break;
            }
        }
        if (!supported) {
            for (Locale dynamic : getAvailableDynamicLocales()) {
                if (dynamic.getLanguage().equals(locale.getLanguage())) {
                    supported = true;
                    currentLocale = dynamic;
                    break;
                }
            }
        }
        if (!supported) {
            logger.warn("Locale {} is not supported, falling back to English", locale);
            currentLocale = Locale.ENGLISH;
        }
        
        if (previousLocale == null || !previousLocale.getLanguage().equals(currentLocale.getLanguage())) {
            logger.debug("Locale changed from {} to {}, reloading resource bundle", previousLocale, currentLocale);
            loadResourceBundle();
        } else {
            logger.debug("Locale unchanged: {}, skipping resource bundle reload", currentLocale);
        }
    }
    
    /**
     * Sets the locale from a language code (e.g., "en", "de", "fr").
     */
    public void setLocale(String languageCode) {
        if (languageCode == null || languageCode.isEmpty()) {
            setLocale(Locale.ENGLISH);
            return;
        }
        
        Locale locale = Locale.forLanguageTag(languageCode);
        setLocale(locale);
    }
    
    /**
     * Loads the resource bundle for the current locale.
     * First tries dynamic file under ~/.kortty/i18n/messages_XX.properties, then classpath.
     * Clears the ResourceBundle cache to force reload with new locale.
     */
    private void loadResourceBundle() {
        java.util.ResourceBundle.clearCache();
        try {
            ResourceBundle fromFile = loadBundleFromConfigDir(currentLocale);
            if (fromFile != null) {
                resourceBundle = fromFile;
                logger.info("Loaded resource bundle from config dir for locale: {} (language: {})", currentLocale, currentLocale.getLanguage());
                return;
            }
            resourceBundle = ResourceBundle.getBundle(BUNDLE_BASE_NAME, currentLocale, UTF8_CONTROL);
            logger.info("Loaded resource bundle for locale: {} (language: {})", currentLocale, currentLocale.getLanguage());
        } catch (MissingResourceException e) {
            logger.error("Failed to load resource bundle for locale: {}, falling back to English", currentLocale, e);
            java.util.ResourceBundle.clearCache();
            ResourceBundle fromFile = loadBundleFromConfigDir(Locale.ENGLISH);
            if (fromFile != null) {
                resourceBundle = fromFile;
            } else {
                resourceBundle = ResourceBundle.getBundle(BUNDLE_BASE_NAME, Locale.ENGLISH, UTF8_CONTROL);
            }
        }
    }

    /**
     * Tries to load a properties bundle from config directory (~/.kortty/i18n/messages_XX.properties).
     * Returns null if file does not exist or cannot be read.
     */
    private ResourceBundle loadBundleFromConfigDir(Locale locale) {
        try {
            Path configDir = KorTTYApplication.getConfigDirectory();
            Path file = configDir.resolve("i18n").resolve("messages_" + locale.getLanguage() + ".properties");
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try (InputStream in = Files.newInputStream(file)) {
                return new PropertyResourceBundle(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            logger.debug("Could not load bundle from config dir for {}: {}", locale, e.getMessage());
            return null;
        }
    }

    /**
     * Returns locales that have a dynamically generated properties file in ~/.kortty/i18n/.
     */
    public static List<Locale> getAvailableDynamicLocales() {
        List<Locale> list = new ArrayList<>();
        try {
            Path i18nDir = KorTTYApplication.getConfigDirectory().resolve("i18n");
            if (!Files.isDirectory(i18nDir)) {
                return list;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(i18nDir, "messages_*.properties")) {
                for (Path p : stream) {
                    String name = p.getFileName().toString();
                    String lang = name.replace("messages_", "").replace(".properties", "");
                    if (!lang.isEmpty()) {
                        list.add(Locale.forLanguageTag(lang));
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not list dynamic i18n dir: {}", e.getMessage());
        }
        return list;
    }

    /**
     * Returns dynamic locales whose generated file was created with an older app version
     * (or has no version header). These should be regenerated after an upgrade to include new keys.
     */
    public static List<Locale> getOutdatedDynamicLocales() {
        String currentVersion = KorTTYApplication.getAppVersion();
        if (currentVersion == null) return new ArrayList<>();
        List<Locale> outdated = new ArrayList<>();
        try {
            Path i18nDir = KorTTYApplication.getConfigDirectory().resolve("i18n");
            if (!Files.isDirectory(i18nDir)) return outdated;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(i18nDir, "messages_*.properties")) {
                for (Path p : stream) {
                    String name = p.getFileName().toString();
                    String lang = name.replace("messages_", "").replace(".properties", "");
                    if (lang.isEmpty()) continue;
                    String fileVersion = DynamicLanguageGenerator.readGeneratedVersion(p);
                    if (fileVersion == null || !fileVersion.equals(currentVersion)) {
                        outdated.add(Locale.forLanguageTag(lang));
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not check outdated dynamic locales: {}", e.getMessage());
        }
        return outdated;
    }

    /**
     * Returns true if the given locale is either in SUPPORTED_LOCALES or has a dynamic properties file.
     */
    public static boolean isLocaleAvailable(Locale locale) {
        if (locale == null) return false;
        String lang = locale.getLanguage();
        for (Locale supported : SUPPORTED_LOCALES) {
            if (supported.getLanguage().equals(lang)) return true;
        }
        for (Locale dynamic : getAvailableDynamicLocales()) {
            if (dynamic.getLanguage().equals(lang)) return true;
        }
        return false;
    }

    /**
     * Gets a translated string for the given key.
     * Returns the key itself if translation is not found.
     * If not yet initialized, uses English as fallback (will be overridden by initialize()).
     */
    public String getString(String key) {
        // If not initialized yet, this should not happen in normal flow
        // but we use English as safe fallback
        if (!initialized) {
            logger.warn("LanguageManager.getString() called before initialize() - using English fallback. Key: {}", key);
            // Don't set initialized=true here, let initialize() handle it properly
            if (resourceBundle == null || !currentLocale.equals(Locale.ENGLISH)) {
                currentLocale = Locale.ENGLISH;
                loadResourceBundle();
            }
        }
        
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
