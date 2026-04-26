package de.kortty.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalAgentWorkingDirectoryTest {

    @Test
    void wrapsCommandInTrackedWorkingDirectory() {
        String command = TerminalAgentService.wrapCommandForWorkingDirectory(
            "printf test > script.sh",
            "/home/daniel/project");

        assertEquals("cd '/home/daniel/project' && printf test > script.sh", command);
    }

    @Test
    void quotesTrackedWorkingDirectory() {
        String command = TerminalAgentService.wrapCommandForWorkingDirectory(
            "pwd",
            "/home/daniel/project's files");

        assertEquals("cd '/home/daniel/project'\"'\"'s files' && pwd", command);
    }

    @Test
    void leavesCommandUnwrappedWhenWorkingDirectoryIsUnknown() {
        assertEquals("pwd", TerminalAgentService.wrapCommandForWorkingDirectory("pwd", null));
        assertEquals("pwd", TerminalAgentService.wrapCommandForWorkingDirectory("pwd", ""));
        assertEquals("pwd", TerminalAgentService.wrapCommandForWorkingDirectory("pwd", "~"));
        assertEquals("pwd", TerminalAgentService.wrapCommandForWorkingDirectory("pwd", "relative/path"));
    }
}
