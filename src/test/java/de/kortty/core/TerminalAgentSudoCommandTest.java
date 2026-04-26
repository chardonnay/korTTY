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

    private TerminalAgentModels.ProbeSnapshot sudoPasswordProbe() {
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
            false,
            true,
            false,
            false,
            "",
            "sudo_password");
    }
}
