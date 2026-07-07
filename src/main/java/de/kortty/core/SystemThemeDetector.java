package de.kortty.core;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort detection of the operating-system's light/dark appearance, used by the diagram viewers'
 * "Dark mode: Auto" option. JavaFX 21 has no {@code Platform.getPreferences().getColorScheme()} (added in
 * JavaFX 22), so the appearance is probed via a short-lived native command per platform:
 * <ul>
 *   <li>macOS: {@code defaults read -g AppleInterfaceStyle} prints {@code Dark} in dark mode and fails/empty otherwise.</li>
 *   <li>Windows: the {@code AppsUseLightTheme} registry value is {@code 0x0} in dark mode.</li>
 *   <li>Linux (GNOME): {@code gsettings get org.gnome.desktop.interface color-scheme} contains {@code dark}.</li>
 * </ul>
 * The probe is cached for a couple of seconds so a burst of renders does not spawn repeated processes, and
 * any failure/uncertainty resolves to <em>light</em> so "Auto" can never make a diagram worse than the
 * previous always-light behaviour. Call {@link #invalidateCache()} to force a fresh probe (e.g. when a
 * window regains focus after the user may have toggled the OS appearance).
 */
public final class SystemThemeDetector {

    private static final Logger logger = LoggerFactory.getLogger(SystemThemeDetector.class);
    private static final long CACHE_TTL_MS = 2000;
    private static final long PROCESS_TIMEOUT_MS = 800;

    private static volatile boolean cachedDark;
    private static volatile long cacheExpiresAt;

    private SystemThemeDetector() {
    }

    /** @return {@code true} when the OS is currently in dark appearance; {@code false} on light or any failure. */
    public static boolean isSystemDarkMode() {
        long now = System.currentTimeMillis();
        if (now < cacheExpiresAt) {
            return cachedDark;
        }
        boolean dark = detect();
        cachedDark = dark;
        cacheExpiresAt = now + CACHE_TTL_MS;
        return dark;
    }

    /** Drops the cached result so the next {@link #isSystemDarkMode()} re-probes the OS. */
    public static void invalidateCache() {
        cacheExpiresAt = 0;
    }

    private static boolean detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                String out = run(List.of("defaults", "read", "-g", "AppleInterfaceStyle"));
                return out != null && out.toLowerCase(Locale.ROOT).contains("dark");
            }
            if (os.contains("win")) {
                String out = run(List.of("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme"));
                // AppsUseLightTheme == 0x0 means "apps use dark theme".
                return out != null && out.matches("(?s).*AppsUseLightTheme\\s+REG_DWORD\\s+0x0\\b.*");
            }
            // Linux / other: GNOME exposes the preference via gsettings.
            String scheme = run(List.of("gsettings", "get", "org.gnome.desktop.interface", "color-scheme"));
            if (scheme != null && scheme.toLowerCase(Locale.ROOT).contains("dark")) {
                return true;
            }
            String theme = run(List.of("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme"));
            return theme != null && theme.toLowerCase(Locale.ROOT).contains("dark");
        } catch (Exception e) {
            logger.debug("OS dark-mode detection failed; assuming light", e);
            return false;
        }
    }

    private static String run(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // Close stdin so a probe that reads input cannot stall. Bound the wait on waitFor() FIRST — reading
            // the stream before waiting would block indefinitely on a child that never closes stdout, and since
            // this runs on the FX thread that would freeze the UI. The probes emit only a few bytes, well under
            // the pipe buffer, so the child never blocks on write; once it exits, readAllBytes returns at once.
            process.getOutputStream().close();
            if (!process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return new String(process.getInputStream().readAllBytes()).trim();
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            logger.debug("OS appearance probe '{}' failed", command.isEmpty() ? "" : command.get(0), e);
            return null;
        }
    }
}
