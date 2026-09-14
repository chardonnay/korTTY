package de.kortty.codingagent.desktop;

import static com.google.common.truth.Truth.assertThat;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Predicate;
import org.testng.annotations.Test;

class LinuxDesktopIdTest {

    private static final Map<String, String> HOME_ONLY = Map.of("HOME", "/home/me");
    private static final PlatformProbe DEB = new PlatformProbe("Linux", "/opt/kortty/bin/korTTY", false, false);
    private static final PlatformProbe PACMAN = new PlatformProbe("Linux", "/usr/lib/kortty/bin/korTTY", false, false);
    private static final PlatformProbe UNPACKAGED = new PlatformProbe("Linux", null, false, false);

    /**
     * Compares {@link Path} values, not their strings. A path renders with the host's separator, so
     * comparing {@code path.toString()} against a POSIX literal silently asserts "this test is
     * running on a POSIX host" as well — which is why these Linux-only rules failed on the Windows
     * runner while the logic under test was fine.
     */
    private static Predicate<Path> filesPresent(String... paths) {
        Set<Path> present = Arrays.stream(paths).map(LinuxPathsTestSupport::canonical)
            .collect(Collectors.toUnmodifiableSet());
        return path -> present.contains(LinuxPathsTestSupport.canonical(path));
    }

    @Test
    void flatpakResolvesWithoutTouchingTheDisk() {
        List<Path> probed = new ArrayList<>();
        PlatformProbe flatpak = new PlatformProbe("Linux", null, true, false);

        Optional<String> id = LinuxDesktopId.resolve(flatpak, Map.of(), path -> {
            probed.add(path);
            return true;
        });

        assertThat(id).hasValue("io.github.chardonnay.korTTY.desktop");
        assertThat(LinuxDesktopId.FLATPAK_DESKTOP_ID).isEqualTo("io.github.chardonnay.korTTY.desktop");
        assertThat(probed).isEmpty();
    }

    @Test
    void debRpmInstallResolvesTheJpackageDesktopId() {
        Optional<String> id = LinuxDesktopId.resolve(DEB, HOME_ONLY,
            filesPresent("/usr/share/applications/kortty-korTTY.desktop"));

        assertThat(id).hasValue("kortty-korTTY.desktop");
    }

    @Test
    void pacmanInstallResolvesThePackagedDesktopId() {
        Optional<String> id = LinuxDesktopId.resolve(PACMAN, HOME_ONLY,
            filesPresent("/usr/share/applications/kortty.desktop"));

        assertThat(id).hasValue("kortty.desktop");
    }

    @Test
    void jpackageHintOnlyOrdersTheCandidates() {
        // A pacman-style path with only the deb id on disk still resolves, and vice versa.
        assertThat(LinuxDesktopId.resolve(PACMAN, HOME_ONLY,
            filesPresent("/usr/share/applications/kortty-korTTY.desktop"))).hasValue("kortty-korTTY.desktop");
        assertThat(LinuxDesktopId.resolve(DEB, HOME_ONLY,
            filesPresent("/usr/local/share/applications/kortty.desktop"))).hasValue("kortty.desktop");
        // Both present: the hint decides.
        Predicate<Path> both = filesPresent(
            "/usr/share/applications/kortty-korTTY.desktop", "/usr/share/applications/kortty.desktop");
        assertThat(LinuxDesktopId.resolve(PACMAN, HOME_ONLY, both)).hasValue("kortty.desktop");
        assertThat(LinuxDesktopId.resolve(DEB, HOME_ONLY, both)).hasValue("kortty-korTTY.desktop");
    }

    @Test
    void bothCandidatesAreProbedInEveryXdgDirectoryAndTheUserDirectory() {
        List<Path> probed = new ArrayList<>();
        Map<String, String> env = Map.of("HOME", "/home/me", "XDG_DATA_DIRS", "/var/lib/flatpak/exports/share:/usr/share");

        Optional<String> id = LinuxDesktopId.resolve(UNPACKAGED, env, path -> {
            probed.add(path);
            return false;
        });

        assertThat(id).isEmpty();
        assertThat(probed).containsExactly(
            Path.of("/home/me/.local/share/applications/kortty-korTTY.desktop"),
            Path.of("/var/lib/flatpak/exports/share/applications/kortty-korTTY.desktop"),
            Path.of("/usr/share/applications/kortty-korTTY.desktop"),
            Path.of("/home/me/.local/share/applications/kortty.desktop"),
            Path.of("/var/lib/flatpak/exports/share/applications/kortty.desktop"),
            Path.of("/usr/share/applications/kortty.desktop"));
    }

    @Test
    void applicationDirsDefaultToTheXdgSpecification() {
        assertThat(LinuxDesktopId.applicationDirs(HOME_ONLY)).containsExactly(
            Path.of("/home/me/.local/share/applications"),
            Path.of("/usr/local/share/applications"),
            Path.of("/usr/share/applications")).inOrder();
    }

    @Test
    void applicationDirsHonourXdgDataHomeAndSkipDuplicates() {
        Map<String, String> env = Map.of(
            "HOME", "/home/me",
            "XDG_DATA_HOME", "/home/me/data",
            "XDG_DATA_DIRS", "/usr/share::/usr/share:/opt/share");

        assertThat(LinuxDesktopId.applicationDirs(env)).containsExactly(
            Path.of("/home/me/data/applications"),
            Path.of("/usr/share/applications"),
            Path.of("/opt/share/applications")).inOrder();
    }

    @Test
    void absentCandidateResolvesToEmpty() {
        assertThat(LinuxDesktopId.resolve(DEB, HOME_ONLY, path -> false)).isEmpty();
    }

    @Test
    void unpackagedRunStillResolvesWhenADesktopFileExists() {
        Optional<String> id = LinuxDesktopId.resolve(UNPACKAGED, HOME_ONLY,
            filesPresent("/home/me/.local/share/applications/kortty.desktop"));

        assertThat(id).hasValue("kortty.desktop");
    }

    @Test
    void objectPathIsTheUnsignedCrc32OfTheDesktopId() {
        assertThat(LinuxDesktopId.objectPath("io.github.chardonnay.korTTY.desktop"))
            .isEqualTo("/com/canonical/unity/launcherentry/2830777151");
        assertThat(LinuxDesktopId.objectPath("kortty-korTTY.desktop"))
            .isEqualTo("/com/canonical/unity/launcherentry/2080713640");
        assertThat(LinuxDesktopId.objectPath("kortty.desktop"))
            .isEqualTo("/com/canonical/unity/launcherentry/1711539872");
        assertThat(LinuxDesktopId.objectPath("kortty.desktop")).isEqualTo(LinuxDesktopId.objectPath("kortty.desktop"));
    }

    @Test
    void applicationUriNamesTheDesktopFile() {
        assertThat(LinuxDesktopId.applicationUri("kortty.desktop")).isEqualTo("application://kortty.desktop");
        assertThat(LinuxDesktopId.applicationUri(LinuxDesktopId.FLATPAK_DESKTOP_ID))
            .isEqualTo("application://io.github.chardonnay.korTTY.desktop");
    }

    @Test
    void signalNameIsTheInterfaceAndMemberTheConnectionSends() {
        assertThat(LinuxDesktopId.LAUNCHER_ENTRY_INTERFACE + "." + LinuxDesktopId.LAUNCHER_ENTRY_MEMBER)
            .isEqualTo("com.canonical.Unity.LauncherEntry.Update");
    }
}
