package de.kortty.core.agent;

import de.kortty.core.agent.AgentCommandRunner.ShellKind;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class LocalShellArgvTest {

    @Test
    void posixRunsTheCommandThroughBinSh() {
        assertThat(LocalShellArgv.argv(ShellKind.POSIX, "op item get 'x' --fields password"))
            .containsExactly("/bin/sh", "-c", "op item get 'x' --fields password")
            .inOrder();
    }

    @Test
    void missingShellKindMeansPosix() {
        assertThat(LocalShellArgv.argv(null, "echo hi")).containsExactly("/bin/sh", "-c", "echo hi").inOrder();
    }

    @Test
    void powerShellEncodesTheCommandWithTheUtf8Prefix() {
        String command = "Write-Output \"päss 'wörd'\"\nexit 0";

        List<String> argv = LocalShellArgv.argv(ShellKind.WINDOWS_POWERSHELL, command);

        assertThat(argv).hasSize(5);
        assertThat(argv.subList(0, 4))
            .containsExactly("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand")
            .inOrder();
        String decoded = new String(Base64.getDecoder().decode(argv.get(4)), StandardCharsets.UTF_16LE);
        assertThat(decoded).isEqualTo(LocalShellArgv.POWERSHELL_UTF8_PREFIX + command);
    }

    @Test
    void cmdRunsTheCommandThroughCmdC() {
        assertThat(LocalShellArgv.argv(ShellKind.WINDOWS_CMD, "echo %USERNAME%"))
            .containsExactly("cmd.exe", "/c", "echo %USERNAME%")
            .inOrder();
    }

    @Test
    void platformDefaultIsPowerShellOnWindowsAndShElsewhere() {
        assertThat(LocalShellArgv.platformDefault("Windows 11")).isEqualTo(ShellKind.WINDOWS_POWERSHELL);
        assertThat(LocalShellArgv.platformDefault("Mac OS X")).isEqualTo(ShellKind.POSIX);
        assertThat(LocalShellArgv.platformDefault("Linux")).isEqualTo(ShellKind.POSIX);
        assertThat(LocalShellArgv.platformDefault(null)).isEqualTo(ShellKind.POSIX);
    }

    @Test
    void nestedPowerShellCommandIsUnwrappedSoVariablesSurvive() {
        assertThat(LocalShellArgv.unwrapNestedPowerShell(
            "powershell -Command \"$content = Get-Content 'Show-Patches.ps1' -Raw; $content.Length\""))
            .isEqualTo("$content = Get-Content 'Show-Patches.ps1' -Raw; $content.Length");
        assertThat(LocalShellArgv.unwrapNestedPowerShell(
            "PowerShell.exe -NoProfile -ExecutionPolicy Bypass -Command \"Write-Output \\\"a\\\" `$x\""))
            .isEqualTo("Write-Output \"a\" $x");
        assertThat(LocalShellArgv.unwrapNestedPowerShell("pwsh -c 'Write-Output ''hi'''"))
            .isEqualTo("Write-Output 'hi'");
        assertThat(LocalShellArgv.unwrapNestedPowerShell("pwsh -Command { Get-Date }"))
            .isEqualTo("Get-Date");
        assertThat(LocalShellArgv.unwrapNestedPowerShell("powershell -c Get-Date"))
            .isEqualTo("Get-Date");
    }

    @Test
    void ordinaryCommandsAreNotUnwrapped() {
        assertThat(LocalShellArgv.unwrapNestedPowerShell("Get-Content -Raw 'Show-Patches.ps1'"))
            .isEqualTo("Get-Content -Raw 'Show-Patches.ps1'");
        assertThat(LocalShellArgv.unwrapNestedPowerShell("powershell -File script.ps1"))
            .isEqualTo("powershell -File script.ps1");
        assertThat(LocalShellArgv.unwrapNestedPowerShell("powershell -Command \"unbalanced"))
            .isEqualTo("powershell -Command \"unbalanced");
    }

    @Test
    void returnedArgvIsUnmodifiable() {
        List<String> argv = LocalShellArgv.argv(ShellKind.POSIX, "true");
        try {
            argv.add("extra");
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("argv must be unmodifiable");
    }
}
