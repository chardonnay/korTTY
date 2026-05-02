package de.kortty.core;

import de.kortty.model.TerminalAgentModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalAgentSudoCommandTest {

    @Test
    void normalizesPlainSudoToNonInteractiveSudo() {
        assertEquals(
            "sudo -n dnf install -y tomcat",
            TerminalAgentService.normalizeSudoForAgentExecution("sudo dnf install -y tomcat"));
    }

    @Test
    void keepsAlreadyNonInteractiveSudoUnchanged() {
        assertEquals(
            "sudo -n dnf install -y tomcat",
            TerminalAgentService.normalizeSudoForAgentExecution("sudo -n dnf install -y tomcat"));
    }

    @Test
    void normalizesSudoAfterShellConnector() {
        assertEquals(
            "rpm -q tomcat || sudo -n dnf install -y tomcat",
            TerminalAgentService.normalizeSudoForAgentExecution("rpm -q tomcat || sudo dnf install -y tomcat"));
    }

    @Test
    void normalizesPlannerSudoStdinModeToRuntimeControlledNonInteractiveSudo() {
        assertEquals(
            "sudo -n dnf install -y tomcat",
            TerminalAgentService.normalizeSudoForAgentExecution("sudo -S dnf install -y tomcat"));
    }

    @Test
    void normalizesPlannerLongSudoStdinModeToRuntimeControlledNonInteractiveSudo() {
        assertEquals(
            "sudo -n find /etc -type f | wc -l",
            TerminalAgentService.normalizeSudoForAgentExecution("sudo --stdin find /etc -type f | wc -l"));
    }

    @Test
    void rejectsSudoShellModeFromPlanner() {
        assertThrows(IllegalArgumentException.class, () ->
            TerminalAgentService.normalizeSudoForAgentExecution("sudo -i"));
    }

    @Test
    void rejectsLowercaseSudoShellModeFromPlanner() {
        assertThrows(IllegalArgumentException.class, () ->
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

        assertFalse(TerminalAgentService.isInteractiveCommand(command));
    }

    @Test
    void stillRejectsInteractiveCommandBeforeHereDocumentBody() {
        String command = """
            top << 'EOF'
            ignored
            EOF
            """;

        assertTrue(TerminalAgentService.isInteractiveCommand(command));
    }

    @Test
    void promptsForPasswordWhenPlannerBlocksOnSudoPassword() {
        assertTrue(TerminalAgentService.shouldPromptForSudoPasswordAfterBlockedDecision(
            "Need sudo password to proceed with installation",
            "Cannot install Tomcat without sudo password",
            sudoPasswordProbe(),
            null));
    }

    @Test
    void doesNotPromptForPasswordWhenPasswordAlreadyCached() {
        assertFalse(TerminalAgentService.shouldPromptForSudoPasswordAfterBlockedDecision(
            "Need sudo password",
            "Cannot continue without sudo password",
            sudoPasswordProbe(),
            "secret"));
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

        assertFalse(TerminalAgentService.shouldPromptForSudoPasswordAfterBlockedDecision(
            "Need sudo password",
            "Cannot continue without sudo password",
            probe,
            null));
    }

    @Test
    void clearsCachedSudoPasswordOnlyForAuthenticationFailures() {
        assertTrue(TerminalAgentService.shouldClearCachedSudoPassword(
            "",
            "[sudo] password for daniel: Sorry, try again."));

        assertFalse(TerminalAgentService.shouldClearCachedSudoPassword(
            "",
            "dnf: no package matches not-a-real-package"));
    }

    @Test
    void sudoPreflightIsNeededOnlyForPasswordProtectedSudo() {
        assertTrue(TerminalAgentService.needsSudoPasswordPreflight(sudoPasswordProbe()));
        assertFalse(TerminalAgentService.needsSudoPasswordPreflight(probe(true, true, false, "already_root")));
        assertFalse(TerminalAgentService.needsSudoPasswordPreflight(probe(false, false, false, "none")));
        assertFalse(TerminalAgentService.needsSudoPasswordPreflight(probe(false, true, true, "passwordless_sudo")));
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
