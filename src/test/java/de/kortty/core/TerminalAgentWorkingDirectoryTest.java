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

    @Test
    void detectsMissingTrackedWorkingDirectoryInLocalizedCdError() {
        assertThat(TerminalAgentService.isMissingTrackedWorkingDirectory(
            "bash: Zeile 1: cd: /home/daniel/Doku: Datei oder Verzeichnis nicht gefunden",
            "/home/daniel/Doku")).isTrue();
    }

    @Test
    void ignoresUnrelatedCdErrorsForTrackedWorkingDirectoryFallback() {
        assertThat(TerminalAgentService.isMissingTrackedWorkingDirectory(
            "bash: Zeile 1: cd: /tmp/other: Datei oder Verzeichnis nicht gefunden",
            "/home/daniel/Doku")).isFalse();
    }
}
