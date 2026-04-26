package de.kortty.core;

import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.model.TerminalAgentModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalAgentTurnLimitPromptTest {

    @Test
    void finalSystemPromptForTurnLimitForbidsMoreCommands() {
        TerminalAgentService service = new TerminalAgentService();

        String prompt = service.buildAgentTurnLimitFinalSystemPrompt();

        assertTrue(prompt.contains("No more commands may be run."));
        assertTrue(prompt.contains("Allowed `status` values: `done`, `blocked`."));
        assertTrue(prompt.contains("Always return `commands`: []"));
    }

    @Test
    void finalUserPromptIncludesExistingCommandResults() {
        TerminalAgentService service = new TerminalAgentService();
        TerminalAgentModels.Request request = new TerminalAgentModels.Request(
            "session-1",
            "profile-1",
            "how many files are under directory /etc?",
            "Fedora43",
            "",
            TerminalAgentExecutionTarget.TERMINAL_WINDOW,
            false,
            false,
            false,
            false,
            false);
        TerminalAgentModels.CommandResult commandResult = new TerminalAgentModels.CommandResult(
            "find /etc -type f 2>/dev/null | wc -l",
            "Count files under /etc.",
            TerminalAgentModels.Risk.READ_ONLY,
            0,
            null,
            "1284\n",
            "",
            false,
            false,
            false,
            false);

        String prompt = service.buildAgentTurnLimitFinalUserPrompt(
            request,
            probe(),
            List.of(commandResult));

        assertTrue(prompt.contains("Turn limit reached"));
        assertTrue(prompt.contains("Active terminal working directory: /home/daniel"));
        assertTrue(prompt.contains("find /etc -type f"));
        assertTrue(prompt.contains("1284"));
        assertTrue(prompt.contains("Write the final response now without planning more commands."));
    }

    private TerminalAgentModels.ProbeSnapshot probe() {
        return new TerminalAgentModels.ProbeSnapshot(
            "Fedora Linux 43",
            "kernel",
            "aarch64",
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
