package de.kortty.codingagent.desktop;

import de.kortty.platform.FlatpakSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.CRC32;

/**
 * Resolves the {@code .desktop} id korTTY was installed under on Linux and builds the
 * {@code com.canonical.Unity.LauncherEntry.Update} signal (honoured by KDE Plasma, Ubuntu Dock and
 * Dash to Dock) that carries the application-icon counter.
 *
 * <p>Inside Flatpak the id is fixed ({@link #FLATPAK_DESKTOP_ID}); the deb/rpm packages install
 * {@code kortty-korTTY.desktop} below {@code /opt/kortty} and the Arch package installs
 * {@code kortty.desktop} below {@code /usr/lib/kortty}. A candidate counts only when the file exists
 * in one of the XDG application directories — the plain tar.gz image and {@code ./gradlew run}
 * have no desktop file, so they resolve to {@link Optional#empty()} and fall back to the window
 * title.
 */
public final class LinuxDesktopId {

    /** Desktop id of the Flatpak build ({@code FlatpakSupport.APP_ID + ".desktop"}). */
    public static final String FLATPAK_DESKTOP_ID = FlatpakSupport.APP_ID + ".desktop";

    static final String DEB_RPM_DESKTOP_ID = "kortty-korTTY.desktop";
    static final String PACMAN_DESKTOP_ID = "kortty.desktop";
    static final String LAUNCHER_ENTRY_OBJECT_PREFIX = "/com/canonical/unity/launcherentry/";
    static final String LAUNCHER_ENTRY_SIGNAL = "com.canonical.Unity.LauncherEntry.Update";
    static final String DEFAULT_XDG_DATA_DIRS = "/usr/local/share:/usr/share";

    private static final String PACMAN_INSTALL_PREFIX = "/usr/lib/kortty";

    private LinuxDesktopId() {
    }

    /**
     * Resolves the installed desktop id.
     *
     * @param probe the platform facts (Flatpak flag and jpackage path hint)
     * @param env the process environment ({@code XDG_DATA_DIRS}, {@code XDG_DATA_HOME}, {@code HOME})
     * @param exists the file probe, normally {@code Files::isRegularFile}
     * @return the confirmed desktop id, or empty when no desktop file is installed
     */
    public static Optional<String> resolve(PlatformProbe probe, Map<String, String> env, Predicate<Path> exists) {
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(exists, "exists");
        if (probe.flatpak()) {
            return Optional.of(FLATPAK_DESKTOP_ID);
        }
        List<Path> dirs = applicationDirs(env);
        for (String candidate : candidates(probe.jpackageAppPath())) {
            for (Path dir : dirs) {
                if (exists.test(dir.resolve(candidate))) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The {@code applications} directories to probe: {@code $XDG_DATA_HOME} (default
     * {@code ~/.local/share}) first, then every entry of {@code $XDG_DATA_DIRS} (default
     * {@code /usr/local/share:/usr/share}).
     */
    public static List<Path> applicationDirs(Map<String, String> env) {
        Map<String, String> environment = env == null ? Map.of() : env;
        Set<Path> dirs = new LinkedHashSet<>();
        String dataHome = environment.get("XDG_DATA_HOME");
        if (dataHome == null || dataHome.isBlank()) {
            String home = environment.get("HOME");
            if (home == null || home.isBlank()) {
                home = System.getProperty("user.home", "");
            }
            dataHome = home.isBlank() ? null : Path.of(home, ".local", "share").toString();
        }
        if (dataHome != null) {
            dirs.add(Path.of(dataHome, "applications"));
        }
        String dataDirs = environment.get("XDG_DATA_DIRS");
        if (dataDirs == null || dataDirs.isBlank()) {
            dataDirs = DEFAULT_XDG_DATA_DIRS;
        }
        for (String dir : dataDirs.split(":")) {
            if (!dir.isBlank()) {
                dirs.add(Path.of(dir, "applications"));
            }
        }
        return List.copyOf(dirs);
    }

    /**
     * The LauncherEntry object path: {@code /com/canonical/unity/launcherentry/} followed by the
     * unsigned CRC32 of the desktop id (the convention libunity established).
     */
    public static String objectPath(String desktopId) {
        CRC32 crc = new CRC32();
        crc.update(Objects.requireNonNull(desktopId, "desktopId").getBytes(StandardCharsets.UTF_8));
        return LAUNCHER_ENTRY_OBJECT_PREFIX + Long.toUnsignedString(crc.getValue());
    }

    /**
     * The {@code gdbus emit} argv for one counter update. The property dictionary is a single argv
     * element; {@code count-visible} is {@code true} only for a positive count.
     */
    public static List<String> gdbusCommand(String desktopId, int count, boolean urgent) {
        Objects.requireNonNull(desktopId, "desktopId");
        int visibleCount = Math.max(0, count);
        String dictionary = "{'count': <int64 " + visibleCount + ">, 'count-visible': <" + (visibleCount > 0)
            + ">, 'urgent': <" + urgent + ">}";
        return List.of(
            "gdbus", "emit", "--session",
            "--object-path", objectPath(desktopId),
            "--signal", LAUNCHER_ENTRY_SIGNAL,
            "application://" + desktopId,
            dictionary);
    }

    private static List<String> candidates(String jpackageAppPath) {
        List<String> candidates = new ArrayList<>(2);
        String hint = jpackageAppPath == null ? "" : jpackageAppPath;
        if (hint.startsWith(PACMAN_INSTALL_PREFIX)) {
            candidates.add(PACMAN_DESKTOP_ID);
            candidates.add(DEB_RPM_DESKTOP_ID);
        } else {
            // /opt/kortty (deb/rpm) and unknown locations probe the jpackage id first.
            candidates.add(DEB_RPM_DESKTOP_ID);
            candidates.add(PACMAN_DESKTOP_ID);
        }
        return candidates;
    }
}
