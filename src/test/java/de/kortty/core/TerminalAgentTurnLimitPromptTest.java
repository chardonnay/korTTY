package de.kortty.core;

import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.Test;

import java.util.List;
import static com.google.common.truth.Truth.assertThat;


class TerminalAgentTurnLimitPromptTest {

    @Test
    void finalSystemPromptForTurnLimitForbidsMoreCommands() {
        TerminalAgentService service = new TerminalAgentService();

        String prompt = service.buildAgentTurnLimitFinalSystemPrompt();

        assertThat(prompt.contains("No more commands may be run.")).isTrue();
        assertThat(prompt.contains("Allowed `status` values: `done`, `blocked`.")).isTrue();
        assertThat(prompt.contains("Always return `commands`: []")).isTrue();
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

        assertThat(prompt.contains("Turn limit reached")).isTrue();
        assertThat(prompt.contains("Remote user: daniel")).isTrue();
        assertThat(prompt.contains("Remote home directory: /home/daniel")).isTrue();
        assertThat(prompt.contains("Active terminal working directory: /home/daniel")).isTrue();
        assertThat(prompt.contains("find /etc -type f")).isTrue();
        assertThat(prompt.contains("1284")).isTrue();
        assertThat(prompt.contains("Write the final response now without planning more commands.")).isTrue();
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
