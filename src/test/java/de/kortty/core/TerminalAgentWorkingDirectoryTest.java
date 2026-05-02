package de.kortty.core;

import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;


class TerminalAgentWorkingDirectoryTest {

    @Test
    void wrapsCommandInTrackedWorkingDirectory() {
        String command = TerminalAgentService.wrapCommandForWorkingDirectory(
            "printf test > script.sh",
            "/home/daniel/project");

        assertThat(command).isEqualTo("cd '/home/daniel/project' && printf test > script.sh");
    }

    @Test
    void quotesTrackedWorkingDirectory() {
        String command = TerminalAgentService.wrapCommandForWorkingDirectory(
            "pwd",
            "/home/daniel/project's files");

        assertThat(command).isEqualTo("cd '/home/daniel/project'\"'\"'s files' && pwd");
    }

    @Test
    void leavesCommandUnwrappedWhenWorkingDirectoryIsUnknown() {
        assertThat(TerminalAgentService.wrapCommandForWorkingDirectory("pwd", null)).isEqualTo("pwd");
        assertThat(TerminalAgentService.wrapCommandForWorkingDirectory("pwd", "")).isEqualTo("pwd");
        assertThat(TerminalAgentService.wrapCommandForWorkingDirectory("pwd", "~")).isEqualTo("pwd");
        assertThat(TerminalAgentService.wrapCommandForWorkingDirectory("pwd", "relative/path")).isEqualTo("pwd");
    }
}
