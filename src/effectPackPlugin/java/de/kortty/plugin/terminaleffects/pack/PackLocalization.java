package de.kortty.plugin.terminaleffects.pack;

/**
 * Localizes plugin descriptions through the host's i18n bundles with an English fallback.
 *
 * <p>The pack jar is loaded with the application classloader as parent, so
 * {@code de.kortty.ui.I18n} is normally reachable. If the jar is exported and runs inside a host
 * without the key (or without the class), the English fallback is returned instead of failing.</p>
 */
final class PackLocalization {

    private PackLocalization() {
    }

    static String localized(String key, String englishFallback) {
        try {
            String value = de.kortty.ui.I18n.get(key);
            if (value == null || value.isBlank() || value.equals(key)) {
                return englishFallback;
            }
            return value;
        } catch (Throwable t) {
            return englishFallback;
        }
    }
}
