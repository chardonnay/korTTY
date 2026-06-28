package de.kortty.core.agent;

import de.kortty.core.agent.AgentCommandRunner.ShellKind;
import org.testng.annotations.Test;

import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;

class LocalAgentCommandRunnerTest {

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Test
    void resolveShellKindMatchesConnectionShellOnWindows() {
        if (isWindows()) {
            assertThat(LocalAgentCommandRunner.resolveShellKind("cmd.exe")).isEqualTo(ShellKind.WINDOWS_CMD);
            assertThat(LocalAgentCommandRunner.resolveShellKind("powershell.exe")).isEqualTo(ShellKind.WINDOWS_POWERSHELL);
            assertThat(LocalAgentCommandRunner.resolveShellKind(null)).isEqualTo(ShellKind.WINDOWS_POWERSHELL);
            // Custom / unknown commands default to PowerShell per the agreed Windows behavior.
            assertThat(LocalAgentCommandRunner.resolveShellKind("wsl.exe -d Ubuntu")).isEqualTo(ShellKind.WINDOWS_POWERSHELL);
        } else {
            assertThat(LocalAgentCommandRunner.resolveShellKind("powershell.exe")).isEqualTo(ShellKind.POSIX);
            assertThat(LocalAgentCommandRunner.resolveShellKind("cmd.exe")).isEqualTo(ShellKind.POSIX);
            assertThat(LocalAgentCommandRunner.resolveShellKind(null)).isEqualTo(ShellKind.POSIX);
        }
    }

    @Test
    void windowsProbeEmitsSharedKeyValueContract() {
        String script = AgentProbeScripts.windowsPowerShell("cmd.exe");
        // Reports the agent's command language so the model generates matching commands.
        assertThat(script).contains("shell=cmd.exe");
        // Emits the same keys the shared probe parser understands.
        assertThat(script).contains("osRelease=");
        assertThat(script).contains("currentUser=");
        assertThat(script).contains("homeDir=");
        assertThat(script).contains("currentDir=");
        // Windows has no sudo/root escalation.
        assertThat(script).contains("sudoAvailable=false");
        assertThat(script).contains("alreadyRoot=false");
        assertThat(script).contains("rootEscalationMode=none");
    }

    @Test
    void posixProbeIsShellScriptWithSudoDetection() {
        assertThat(AgentProbeScripts.POSIX).contains("sudoAvailable=");
        assertThat(AgentProbeScripts.POSIX).contains("currentDir=");
    }
}
