package de.kortty.codingagent.desktop;

import de.kortty.platform.FlatpakSupport;

import java.util.Locale;

/**
 * Immutable snapshot of the platform facts that select the badge and notifier backends.
 *
 * <p>Values are injected so tests never read {@code System.*}; {@link #fromSystem()} builds the
 * live probe from {@code os.name}, {@code jpackage.app-path}, the Flatpak environment and
 * {@code java.awt.headless}.
 *
 * @param osName the raw {@code os.name} system property (matched case-insensitively)
 * @param jpackageAppPath the {@code jpackage.app-path} system property, {@code null} outside a
 *     jpackage image (for example under {@code ./gradlew run})
 * @param flatpak whether the process runs inside the Flatpak sandbox
 * @param awtHeadless whether {@code java.awt.headless} is set to {@code true}
 */
public record PlatformProbe(String osName, String jpackageAppPath, boolean flatpak, boolean awtHeadless) {

    /** Reads the live platform facts of this JVM. */
    public static PlatformProbe fromSystem() {
        return new PlatformProbe(
            System.getProperty("os.name", ""),
            System.getProperty("jpackage.app-path"),
            FlatpakSupport.isRunningInFlatpak(),
            Boolean.getBoolean("java.awt.headless"));
    }

    /** True on macOS ({@code os.name} contains "mac"). */
    public boolean isMac() {
        return normalizedOsName().contains("mac");
    }

    /** True on Windows ({@code os.name} contains "win"). */
    public boolean isWindows() {
        return normalizedOsName().contains("win");
    }

    /** True on every platform that is neither macOS nor Windows (Linux and the BSDs). */
    public boolean isLinux() {
        return !isMac() && !isWindows();
    }

    /** True when running from a jpackage application image ({@code jpackage.app-path} is set). */
    public boolean isPackaged() {
        return jpackageAppPath != null && !jpackageAppPath.isBlank();
    }

    private String normalizedOsName() {
        return osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    }
}
