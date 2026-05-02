package de.kortty.core;

import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.Test;

import java.util.List;
import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;


class TerminalAgentSudoCommandTest {

    @Test
    void normalizesPlainSudoToNonInteractiveSudo() {
        assertThat(TerminalAgentService.normalizeSudoForAgentExecution("sudo dnf install -y tomcat")).isEqualTo("sudo -n dnf install -y tomcat");
    }

    @Test
    void keepsAlreadyNonInteractiveSudoUnchanged() {
        assertThat(TerminalAgentService.normalizeSudoForAgentExecution("sudo -n dnf install -y tomcat")).isEqualTo("sudo -n dnf install -y tomcat");
    }

    @Test
    void normalizesSudoAfterShellConnector() {
        assertThat(TerminalAgentService.normalizeSudoForAgentExecution("rpm -q tomcat || sudo dnf install -y tomcat")).isEqualTo("rpm -q tomcat || sudo -n dnf install -y tomcat");
    }

    @Test
    void normalizesPlannerSudoStdinModeToRuntimeControlledNonInteractiveSudo() {
        assertThat(TerminalAgentService.normalizeSudoForAgentExecution("sudo -S dnf install -y tomcat")).isEqualTo("sudo -n dnf install -y tomcat");
    }

    @Test
    void normalizesPlannerLongSudoStdinModeToRuntimeControlledNonInteractiveSudo() {
        assertThat(TerminalAgentService.normalizeSudoForAgentExecution("sudo --stdin find /etc -type f | wc -l")).isEqualTo("sudo -n find /etc -type f | wc -l");
    }

    @Test
    void rejectsSudoShellModeFromPlanner() {
        expectThrows(IllegalArgumentException.class, () ->
            TerminalAgentService.normalizeSudoForAgentExecution("sudo -i"));
    }

    @Test
    void rejectsLowercaseSudoShellModeFromPlanner() {
        expectThrows(IllegalArgumentException.class, () ->
            TerminalAgentService.normalizeSudoForAgentExecution("sudo -s"));
    }

    @Test
    void ignoresInteractiveTokensInsideHereDocumentBody() {
        String command = """
            cat << 'EOF' > top_files.py
            import os
            top = get_top_files()
            for path, size in top:
                print(path, size)
            EOF
            """;

        assertThat(TerminalAgentService.isInteractiveCommand(command)).isFalse();
    }

    @Test
    void stillRejectsInteractiveCommandBeforeHereDocumentBody() {
        String command = """
            top << 'EOF'
            ignored
            EOF
            """;

        assertThat(TerminalAgentService.isInteractiveCommand(command)).isTrue();
    }

    @Test
    void promptsForPasswordWhenPlannerBlocksOnSudoPassword() {
        assertThat(TerminalAgentService.shouldPromptForSudoPasswordAfterBlockedDecision(
            "Need sudo password to proceed with installation",
            "Cannot install Tomcat without sudo password",
            sudoPasswordProbe(),
            null)).isTrue();
    }

    @Test
    void doesNotPromptForPasswordWhenPasswordAlreadyCached() {
        assertThat(TerminalAgentService.shouldPromptForSudoPasswordAfterBlockedDecision(
            "Need sudo password",
            "Cannot continue without sudo password",
            sudoPasswordProbe(),
            "secret")).isFalse();
    }

    @Test
    void doesNotPromptForPasswordWhenSudoIsUnavailable() {
        TerminalAgentModels.ProbeSnapshot probe = new TerminalAgentModels.ProbeSnapshot(
            "Fedora Linux 43",
            "kernel",
            "x86_64",
            "bash",
            "daniel",
            "1000",
            "1000",
            List.of("wheel"),
            "/home/daniel",
            "/home/daniel",
            null,
            "",
            List.of("dnf"),
            List.of("systemctl"),
            false,
            false,
            false,
            false,
            "",
            "none");

        assertThat(TerminalAgentService.shouldPromptForSudoPasswordAfterBlockedDecision(
            "Need sudo password",
            "Cannot continue without sudo password",
            probe,
            null)).isFalse();
    }

    @Test
    void clearsCachedSudoPasswordOnlyForAuthenticationFailures() {
        assertThat(TerminalAgentService.shouldClearCachedSudoPassword(
            "",
            "[sudo] password for daniel: Sorry, try again.")).isTrue();

        assertThat(TerminalAgentService.shouldClearCachedSudoPassword(
            "",
            "dnf: no package matches not-a-real-package")).isFalse();
    }

    @Test
    void sudoPreflightIsNeededOnlyForPasswordProtectedSudo() {
        assertThat(TerminalAgentService.needsSudoPasswordPreflight(sudoPasswordProbe())).isTrue();
        assertThat(TerminalAgentService.needsSudoPasswordPreflight(probe(true, true, false, "already_root"))).isFalse();
        assertThat(TerminalAgentService.needsSudoPasswordPreflight(probe(false, false, false, "none"))).isFalse();
        assertThat(TerminalAgentService.needsSudoPasswordPreflight(probe(false, true, true, "passwordless_sudo"))).isFalse();
    }

    private TerminalAgentModels.ProbeSnapshot sudoPasswordProbe() {
        return probe(false, true, false, "sudo_password");
    }

    private TerminalAgentModels.ProbeSnapshot probe(
        boolean alreadyRoot,
        boolean sudoAvailable,
        boolean passwordlessSudo,
        String rootEscalationMode) {
        return new TerminalAgentModels.ProbeSnapshot(
            "Fedora Linux 43",
            "kernel",
            "x86_64",
            "bash",
            "daniel",
            "1000",
            "1000",
            List.of("wheel"),
            "/home/daniel",
            "/home/daniel",
            null,
            "",
            List.of("dnf"),
            List.of("systemctl"),
            alreadyRoot,
            sudoAvailable,
            passwordlessSudo,
            false,
            "",
            rootEscalationMode);
    }
}
