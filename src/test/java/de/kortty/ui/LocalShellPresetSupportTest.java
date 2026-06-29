package de.kortty.ui;

import org.testng.annotations.Test;

import java.util.List;
import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;

/**
 * Verifies that only OS-appropriate shells are offered in the local-shell preset list, and that
 * presets round-trip to/from stored commands. The Windows-only presets (PowerShell, cmd.exe, Git
 * Bash, Cygwin, WSL) must never appear on a Unix host, and vice-versa.
 */
class LocalShellPresetSupportTest {

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    private static boolean isSolaris() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("sunos") || os.contains("solaris");
    }

    @Test
    void presetListAlwaysEndsWithCustom() {
        List<String> presets = LocalShellPresetSupport.presetsForCurrentOs(null, null, null);
        assertThat(presets).isNotEmpty();
        assertThat(presets.get(presets.size() - 1)).isEqualTo(LocalShellPresetSupport.CUSTOM);
    }

    @Test
    void offersOnlyOsAppropriateShells() {
        List<String> presets = LocalShellPresetSupport.presetsForCurrentOs(null, null, null);
        if (isWindows()) {
            assertThat(presets).containsAtLeast(
                LocalShellPresetSupport.POWERSHELL, LocalShellPresetSupport.CMD);
            // No Unix-only shells on Windows.
            assertThat(presets).containsNoneOf(
                LocalShellPresetSupport.ZSH, LocalShellPresetSupport.KSH);
        } else {
            // The Windows-only shells must never leak onto a Unix host — this is the reported bug.
            assertThat(presets).containsNoneOf(
                LocalShellPresetSupport.POWERSHELL,
                LocalShellPresetSupport.CMD,
                LocalShellPresetSupport.GIT_BASH,
                LocalShellPresetSupport.CYGWIN,
                LocalShellPresetSupport.WSL);
            if (isMac()) {
                assertThat(presets).containsExactly(
                    LocalShellPresetSupport.ZSH,
                    LocalShellPresetSupport.BASH,
                    LocalShellPresetSupport.CUSTOM).inOrder();
            } else if (isSolaris()) {
                assertThat(presets).containsExactly(
                    LocalShellPresetSupport.KSH,
                    LocalShellPresetSupport.CUSTOM).inOrder();
            } else {
                // Linux and other Unix-likes default to Bash.
                assertThat(presets).containsExactly(
                    LocalShellPresetSupport.BASH,
                    LocalShellPresetSupport.CUSTOM).inOrder();
            }
        }
    }

    @Test
    void windowsExtraShellsAppearOnlyWhenDetectedAndOnWindows() {
        List<String> withAll = LocalShellPresetSupport.presetsForCurrentOs(
            "\"C:\\Git\\bin\\bash.exe\" --login -i", "C:\\cygwin64\\bin\\bash.exe", "wsl.exe");
        if (isWindows()) {
            assertThat(withAll).containsAtLeast(
                LocalShellPresetSupport.GIT_BASH,
                LocalShellPresetSupport.CYGWIN,
                LocalShellPresetSupport.WSL);
        } else {
            // Even when commands are "detected", a non-Windows host never offers the Windows shells.
            assertThat(withAll).containsNoneOf(
                LocalShellPresetSupport.GIT_BASH,
                LocalShellPresetSupport.CYGWIN,
                LocalShellPresetSupport.WSL,
                LocalShellPresetSupport.POWERSHELL,
                LocalShellPresetSupport.CMD);
        }
    }

    @Test
    void defaultPresetIsTheFirstOsAppropriateShell() {
        String first = LocalShellPresetSupport.presetsForCurrentOs(null, null, null).get(0);
        if (isWindows()) {
            assertThat(first).isEqualTo(LocalShellPresetSupport.POWERSHELL);
        } else if (isMac()) {
            assertThat(first).isEqualTo(LocalShellPresetSupport.ZSH);
        } else if (isSolaris()) {
            assertThat(first).isEqualTo(LocalShellPresetSupport.KSH);
        } else {
            assertThat(first).isEqualTo(LocalShellPresetSupport.BASH);
        }
    }

    @Test
    void commandForReturnsPresetIdForRealCommandPresets() {
        assertThat(LocalShellPresetSupport.commandFor(
            LocalShellPresetSupport.ZSH, null, null, null, null)).isEqualTo("zsh");
        assertThat(LocalShellPresetSupport.commandFor(
            LocalShellPresetSupport.BASH, null, null, null, null)).isEqualTo("bash");
        assertThat(LocalShellPresetSupport.commandFor(
            LocalShellPresetSupport.KSH, null, null, null, null)).isEqualTo("ksh");
        assertThat(LocalShellPresetSupport.commandFor(
            LocalShellPresetSupport.POWERSHELL, null, null, null, null)).isEqualTo("powershell.exe");
    }

    @Test
    void commandForResolvesSentinelPresetsToDetectedCommands() {
        assertThat(LocalShellPresetSupport.commandFor(
            LocalShellPresetSupport.GIT_BASH, null, "gitbash-cmd", "cygwin-cmd", "wsl.exe"))
            .isEqualTo("gitbash-cmd");
        assertThat(LocalShellPresetSupport.commandFor(
            LocalShellPresetSupport.WSL, null, "gitbash-cmd", "cygwin-cmd", "wsl.exe"))
            .isEqualTo("wsl.exe");
    }

    @Test
    void commandForCustomUsesTrimmedTextOrNullWhenEmpty() {
        assertThat(LocalShellPresetSupport.commandFor(
            LocalShellPresetSupport.CUSTOM, "  /opt/bin/fish  ", null, null, null))
            .isEqualTo("/opt/bin/fish");
        assertThat(LocalShellPresetSupport.commandFor(
            LocalShellPresetSupport.CUSTOM, "   ", null, null, null)).isNull();
        assertThat(LocalShellPresetSupport.commandFor(
            LocalShellPresetSupport.CUSTOM, null, null, null, null)).isNull();
    }

    @Test
    void presetForBlankCommandIsOsDefault() {
        String expected = LocalShellPresetSupport.presetsForCurrentOs(null, null, null).get(0);
        assertThat(LocalShellPresetSupport.presetForCommand(null, null, null, null)).isEqualTo(expected);
        assertThat(LocalShellPresetSupport.presetForCommand("  ", null, null, null)).isEqualTo(expected);
    }

    @Test
    void presetForUnknownCommandFallsBackToCustom() {
        assertThat(LocalShellPresetSupport.presetForCommand("/opt/bin/fish", null, null, null))
            .isEqualTo(LocalShellPresetSupport.CUSTOM);
    }

    @Test
    void presetForCommandOnlyMatchesShellsAvailableOnThisOs() {
        // "powershell.exe" is a valid preset id, but on a Unix host it is not offered, so it must
        // not round-trip to the PowerShell preset — it falls back to Custom instead.
        String preset = LocalShellPresetSupport.presetForCommand("powershell.exe", null, null, null);
        if (isWindows()) {
            assertThat(preset).isEqualTo(LocalShellPresetSupport.POWERSHELL);
        } else {
            assertThat(preset).isEqualTo(LocalShellPresetSupport.CUSTOM);
        }
    }

    @Test
    void presetForUnixShellCommandsRoundTripsOnUnix() {
        if (isWindows()) {
            return; // zsh/bash/ksh are not offered on Windows.
        }
        if (isMac()) {
            assertThat(LocalShellPresetSupport.presetForCommand("zsh", null, null, null))
                .isEqualTo(LocalShellPresetSupport.ZSH);
            assertThat(LocalShellPresetSupport.presetForCommand("BASH", null, null, null))
                .isEqualTo(LocalShellPresetSupport.BASH);
        } else if (isSolaris()) {
            assertThat(LocalShellPresetSupport.presetForCommand("ksh", null, null, null))
                .isEqualTo(LocalShellPresetSupport.KSH);
        } else {
            assertThat(LocalShellPresetSupport.presetForCommand("bash", null, null, null))
                .isEqualTo(LocalShellPresetSupport.BASH);
        }
    }
}
