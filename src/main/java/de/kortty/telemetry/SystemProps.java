package de.kortty.telemetry;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;

import java.util.Locale;

/**
 * Aptabase {@code systemProps}, detected once per app run. Deliberately
 * minimal: no deviceModel, no build fingerprints.
 */
record SystemProps(boolean isDebug, String locale, String osName, String osVersion,
                   String appVersion, String sdkVersion) {

    static final String SDK_VERSION = "kortty-aptabase@1.0.0";

    static SystemProps detect() {
        return new SystemProps(
            detectIsDebug(),
            detectLocale(),
            mapOsName(System.getProperty("os.name", "")),
            System.getProperty("os.version", ""),
            KorTTYApplication.getAppVersion(),
            SDK_VERSION);
    }

    /** Debug = not launched from a jpackage bundle → events land in Aptabase's debug stream. */
    static boolean detectIsDebug() {
        String jpackagePath = System.getProperty("jpackage.app-path");
        return jpackagePath == null || jpackagePath.isBlank();
    }

    private static String detectLocale() {
        try {
            Locale current = LanguageManager.getInstance().getCurrentLocale();
            if (current != null) {
                return current.toLanguageTag();
            }
        } catch (RuntimeException e) {
            // LanguageManager not initialized yet (tests, very early startup)
        }
        return Locale.getDefault().toLanguageTag();
    }

    static String mapOsName(String rawOsName) {
        String lower = rawOsName.toLowerCase(Locale.ROOT);
        if (lower.contains("mac")) {
            return "macOS";
        }
        if (lower.contains("win")) {
            return "Windows";
        }
        if (lower.contains("linux")) {
            return "Linux";
        }
        return rawOsName;
    }
}
