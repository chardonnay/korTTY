package de.kortty.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalViewShortcutHeuristicsTest {

    @Test
    void doesNotResetPromptReadyForEchoedShortcutWhileTyping() {
        assertFalse(TerminalView.shouldResetPromptReady(
            "daniel@fedora:~$ agent-ask how much storage do i have?",
            true));
    }

    @Test
    void doesNotResetPromptReadyForRecognizedShellPrompt() {
        assertFalse(TerminalView.shouldResetPromptReady("daniel@fedora:~$", false));
    }

    @Test
    void resetsPromptReadyForRegularOutputWhenNoLocalTypingIsInProgress() {
        assertTrue(TerminalView.shouldResetPromptReady("Filesystem      Size  Used Avail Mounted on", false));
    }

    @Test
    void interceptsAgentShortcutWhenPromptWasReadyAtEnter() {
        assertTrue(TerminalView.canInterceptAgentShortcut("agent install tmux", true, "agent"));
    }

    @Test
    void doesNotInterceptAgentShortcutWhenPromptWasNotReadyAtEnter() {
        assertFalse(TerminalView.canInterceptAgentShortcut("agent install tmux", false, "agent"));
    }

    @Test
    void detectsVisiblePromptWhenCurrentLineContainsTypedShortcut() {
        assertTrue(TerminalView.hasVisiblePromptForCommand(
            "daniel@fedora:~$ agent install tmux",
            "agent install tmux"));
    }

    @Test
    void doesNotTreatNonPromptOutputAsVisiblePromptForShortcut() {
        assertFalse(TerminalView.hasVisiblePromptForCommand(
            "bash: agent: command not found",
            "agent install tmux"));
    }
}
