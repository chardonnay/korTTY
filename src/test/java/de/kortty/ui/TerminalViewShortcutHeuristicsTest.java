package de.kortty.ui;

import org.junit.jupiter.api.Test;
import javafx.scene.input.KeyCode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void connectorInterceptionRecognizesBufferedAgentShortcutWithoutPromptSignal() {
        assertTrue(TerminalView.canInterceptBufferedAgentShortcut("agent install tomcat", "agent"));
    }

    @Test
    void connectorInterceptionCanMatchAgentShortcutCaseInsensitively() {
        assertFalse(TerminalView.canInterceptBufferedAgentShortcut("Agent install tomcat", "agent"));
        assertTrue(TerminalView.canInterceptBufferedAgentShortcut("Agent install tomcat", "agent", true));
    }

    @Test
    void connectorInterceptionIgnoresRegularShellCommands() {
        assertFalse(TerminalView.canInterceptBufferedAgentShortcut("ls -la", "agent"));
    }

    @Test
    void letsRemoteShellHandleAgentShortcutWhenRemoteHookIsConfigured() {
        assertTrue(TerminalView.shouldLetRemoteShellHandleAgentShortcut(
            true,
            "agent install tomcat",
            "agent"));
    }

    @Test
    void keepsLocalFallbackForCaseInsensitiveShortcutWhenRemoteAliasWouldNotMatch() {
        assertFalse(TerminalView.shouldLetRemoteShellHandleAgentShortcut(
            true,
            "Agent install tomcat",
            "agent",
            true));
    }

    @Test
    void keepsLocalFallbackForAgentShortcutWhenRemoteHookIsMissing() {
        assertFalse(TerminalView.shouldLetRemoteShellHandleAgentShortcut(
            false,
            "agent install tomcat",
            "agent"));
    }

    @Test
    void buildsRemoteShellAliasesForAgentShortcut() {
        String startup = TerminalView.buildTerminalAgentShellStartupCommand("agent");

        assertTrue(startup.contains("alias agent='__kortty_agent_emit execute'"));
        assertTrue(startup.contains("alias agent-ask='__kortty_agent_emit ask'"));
        assertTrue(startup.contains("alias agent-plan='__kortty_agent_emit plan'"));
        assertTrue(startup.contains("pwd -P"));
        assertTrue(startup.contains("korTTY-agent;%s;%s;%s"));
        assertTrue(startup.contains("__kortty_agent_clean_history"));
        assertTrue(startup.contains("history -d \"$__kortty_h\""));
        assertTrue(startup.contains("awk 'index($0,\"__kortty_agent_b64(){\")==0' \"$HISTFILE\""));
        assertTrue(startup.contains("printf '\\033[1A\\r\\033[K'"));
        assertTrue(startup.contains("stty echo"));
        assertFalse(startup.substring(0, startup.length() - 1).contains("\n"));
    }

    @Test
    void decodesRemoteShellOscPayloadToAgentShortcut() {
        String encoded = Base64.getEncoder().encodeToString("install tomcat".getBytes(StandardCharsets.UTF_8));

        String rawCommand = TerminalView.buildTerminalAgentRawCommandFromOscPayload("execute", encoded, "agent");

        assertEquals("agent install tomcat", rawCommand);
    }

    @Test
    void rejectsMalformedRemoteShellOscPayload() {
        assertNull(TerminalView.buildTerminalAgentRawCommandFromOscPayload("execute", "not base64", "agent"));
        assertNull(TerminalView.buildTerminalAgentRawCommandFromOscPayload("unknown", "aWdub3Jl", "agent"));
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

    @Test
    void extractsAgentShortcutFromVisiblePromptLine() {
        assertEquals("agent install tmux", TerminalView.extractAgentShortcutFromVisibleLine(
            "daniel@fedora ~ $ agent install tmux",
            "agent"));
    }

    @Test
    void extractsAgentShortcutFromVisiblePromptLineCaseInsensitivelyWhenEnabled() {
        assertNull(TerminalView.extractAgentShortcutFromVisibleLine(
            "daniel@fedora ~ $ Agent install tmux",
            "agent"));
        assertEquals("Agent install tmux", TerminalView.extractAgentShortcutFromVisibleLine(
            "daniel@fedora ~ $ Agent install tmux",
            "agent",
            true));
    }

    @Test
    void extractsAgentAskShortcutFromVisiblePromptLine() {
        assertEquals("agent-ask what failed?", TerminalView.extractAgentShortcutFromVisibleLine(
            "daniel@fedora:~$ agent-ask what failed?",
            "agent"));
    }

    @Test
    void doesNotExtractAgentShortcutFromCommandNotFoundOutput() {
        assertFalse("agent install tmux".equals(TerminalView.extractAgentShortcutFromVisibleLine(
            "bash: agent: Befehl nicht gefunden...",
            "agent")));
    }

    @Test
    void recognizesAgentInputCancellationShortcuts() {
        assertTrue(TerminalView.isAgentInputCancelShortcut(KeyCode.ESCAPE, false, false, false));
        assertTrue(TerminalView.isAgentInputCancelShortcut(KeyCode.C, true, false, false));
        assertFalse(TerminalView.isAgentInputCancelShortcut(KeyCode.C, false, false, false));
        assertFalse(TerminalView.isAgentInputCancelShortcut(KeyCode.C, true, true, false));
        assertFalse(TerminalView.isAgentInputCancelShortcut(KeyCode.R, true, false, false));
    }

    @Test
    void normalizesLocalTerminalMessageLines() {
        assertEquals(
            java.util.List.of("Installed Tomcat RPMs:", "- tomcat", "- tomcat-lib"),
            TerminalView.normalizeTerminalMessageLines("Installed Tomcat RPMs:\r\n- tomcat  \n- tomcat-lib"));
    }

    @Test
    void extractsPromptForLocalRedisplayFromScreenWithBlankRows() {
        String screen = "Last login\n"
            + "daniel@fedora:~$ \n"
            + "                  \n"
            + "                  \n";

        assertEquals("daniel@fedora:~$ ", TerminalView.extractPromptForLocalRedisplay(screen, "agent"));
    }

    @Test
    void extractsPromptForLocalRedisplayFromTypedAgentShortcutLine() {
        String screen = "Last login\n"
            + "daniel@fedora:~$ agent install tomcat\n"
            + "                  \n";

        assertEquals("daniel@fedora:~$ ", TerminalView.extractPromptForLocalRedisplay(screen, "agent"));
    }
}
