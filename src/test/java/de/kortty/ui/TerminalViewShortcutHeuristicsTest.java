package de.kortty.ui;

import org.testng.annotations.Test;
import javafx.scene.input.KeyCode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static com.google.common.truth.Truth.assertThat;


class TerminalViewShortcutHeuristicsTest {

    @Test
    void doesNotResetPromptReadyForEchoedShortcutWhileTyping() {
        assertThat(TerminalView.shouldResetPromptReady(
            "daniel@fedora:~$ agent-ask how much storage do i have?",
            true)).isFalse();
    }

    @Test
    void doesNotResetPromptReadyForRecognizedShellPrompt() {
        assertThat(TerminalView.shouldResetPromptReady("daniel@fedora:~$", false)).isFalse();
    }

    @Test
    void resetsPromptReadyForRegularOutputWhenNoLocalTypingIsInProgress() {
        assertThat(TerminalView.shouldResetPromptReady("Filesystem      Size  Used Avail Mounted on", false)).isTrue();
    }

    @Test
    void interceptsAgentShortcutWhenPromptWasReadyAtEnter() {
        assertThat(TerminalView.canInterceptAgentShortcut("agent install tmux", true, "agent")).isTrue();
    }

    @Test
    void doesNotInterceptAgentShortcutWhenPromptWasNotReadyAtEnter() {
        assertThat(TerminalView.canInterceptAgentShortcut("agent install tmux", false, "agent")).isFalse();
    }

    @Test
    void connectorInterceptionRecognizesBufferedAgentShortcutWithoutPromptSignal() {
        assertThat(TerminalView.canInterceptBufferedAgentShortcut("agent install tomcat", "agent")).isTrue();
    }

    @Test
    void connectorInterceptionCanMatchAgentShortcutCaseInsensitively() {
        assertThat(TerminalView.canInterceptBufferedAgentShortcut("Agent install tomcat", "agent")).isFalse();
        assertThat(TerminalView.canInterceptBufferedAgentShortcut("Agent install tomcat", "agent", true)).isTrue();
    }

    @Test
    void connectorInterceptionIgnoresRegularShellCommands() {
        assertThat(TerminalView.canInterceptBufferedAgentShortcut("ls -la", "agent")).isFalse();
    }

    @Test
    void doesNotBufferShortcutModifiedTypedCharacters() {
        assertThat(TerminalView.shouldBufferAgentShortcutKeyTyped("v", false, true, false)).isFalse();
        assertThat(TerminalView.shouldBufferAgentShortcutKeyTyped("v", true, false, false)).isFalse();
        assertThat(TerminalView.shouldBufferAgentShortcutKeyTyped("v", false, false, true)).isFalse();
    }

    @Test
    void buffersPlainTypedCharactersForLocalShortcutFallback() {
        assertThat(TerminalView.shouldBufferAgentShortcutKeyTyped("a", false, false, false)).isTrue();
        assertThat(TerminalView.shouldBufferAgentShortcutKeyTyped("\n", false, false, false)).isFalse();
    }

    @Test
    void letsRemoteShellHandleAgentShortcutWhenRemoteHookIsConfigured() {
        assertThat(TerminalView.shouldLetRemoteShellHandleAgentShortcut(
            true,
            "agent install tomcat",
            "agent")).isTrue();
    }

    @Test
    void keepsLocalFallbackForCaseInsensitiveShortcutWhenRemoteAliasWouldNotMatch() {
        assertThat(TerminalView.shouldLetRemoteShellHandleAgentShortcut(
            true,
            "Agent install tomcat",
            "agent",
            true)).isFalse();
    }

    @Test
    void keepsLocalFallbackForAgentShortcutWhenRemoteHookIsMissing() {
        assertThat(TerminalView.shouldLetRemoteShellHandleAgentShortcut(
            false,
            "agent install tomcat",
            "agent")).isFalse();
    }

    @Test
    void buildsRemoteShellAliasesForAgentShortcut() {
        String startup = TerminalView.buildTerminalAgentShellStartupCommand("agent");

        assertThat(startup.contains("alias agent='__kortty_agent_emit execute'")).isTrue();
        assertThat(startup.contains("alias agent-ask='__kortty_agent_emit ask'")).isTrue();
        assertThat(startup.contains("alias agent-plan='__kortty_agent_emit plan'")).isTrue();
        assertThat(startup.contains("case ${PWD-} in /*) __kortty_cwd=$PWD")).isTrue();
        assertThat(startup.contains("pwd -P")).isTrue();
        assertThat(startup.contains("korTTY-agent;%s;%s;%s")).isTrue();
        assertThat(startup.contains("__kortty_agent_clean_history")).isTrue();
        assertThat(startup.contains("history -d \"$__kortty_h\"")).isTrue();
        assertThat(startup.contains("awk 'index($0,\"__kortty_agent_b64(){\")==0' \"$HISTFILE\"")).isTrue();
        assertThat(startup.contains("printf '" + de.kortty.core.SshTtyConnector.SHELL_STARTUP_CLEANUP_MARKER_SHELL_LITERAL + "\\r\\033[K'")).isTrue();
        assertThat(startup.contains("stty echo")).isTrue();
        assertThat(startup.substring(0, startup.length() - 1).contains("\n")).isFalse();
    }

    @Test
    void extractsHomeRelativeWorkingDirectoryFromVisiblePrompt() {
        assertThat(TerminalView.extractWorkingDirectoryFromPromptLine(
            "daniel@fedora:~/Dokumente$",
            "/home/daniel")).isEqualTo("/home/daniel/Dokumente");
    }

    @Test
    void extractsHomeRelativeWorkingDirectoryFromPromptLineWithTypedAgentCommand() {
        assertThat(TerminalView.extractWorkingDirectoryFromPromptLine(
            "daniel@fedora:~/Dokumente$ agent schreibe ein perl script",
            "/home/daniel")).isEqualTo("/home/daniel/Dokumente");
    }

    @Test
    void extractsHomeRelativeWorkingDirectoryFromSpaceSeparatedPrompt() {
        assertThat(TerminalView.extractWorkingDirectoryFromPromptLine(
            "daniel@fedora ~/Dokumente $",
            "/home/daniel")).isEqualTo("/home/daniel/Dokumente");
    }

    @Test
    void extractsHomeRelativeWorkingDirectoryFromBracketedSpaceSeparatedPrompt() {
        assertThat(TerminalView.extractWorkingDirectoryFromPromptLine(
            "[daniel@fedora ~/Dokumente]$ agent schreibe ein perl script",
            "/home/daniel")).isEqualTo("/home/daniel/Dokumente");
    }

    @Test
    void extractsWorkingDirectoryFromPreviousVisiblePromptLine() {
        assertThat(TerminalView.extractWorkingDirectoryFromVisibleScreen(
            """
            Last login: Sun May 3 22:39:00 2026 from 10.211.55.2
            daniel@fedora:~/Dokumente$ agent schreibe ein perl script
            um die 10 groessten xml files anzuzeigen
            """,
            "/home/daniel")).isEqualTo("/home/daniel/Dokumente");
    }

    @Test
    void extractsAbsoluteWorkingDirectoryFromVisiblePrompt() {
        assertThat(TerminalView.extractWorkingDirectoryFromPromptLine(
            "root@server:/etc/nginx#",
            "/root")).isEqualTo("/etc/nginx");
    }

    @Test
    void ignoresPromptWithoutDirectoryShape() {
        assertThat(TerminalView.extractWorkingDirectoryFromPromptLine(
            "daniel@fedora$",
            "/home/daniel")).isNull();
    }

    @Test
    void wrapsGeneratedInputWithEchoSuppressionAndRestore() {
        String wrapped = TerminalView.buildEchoSuppressedGeneratedInput(
            "printf '%s\\n' 'KorTTY snippet: check_test_echo.sh' >&2 && echo 'abc' | base64 -d | bash");

        assertThat(wrapped.startsWith("printf '\\033[1A\\r\\033[K\\033[1B\\r\\033[K\\033[1A\\r'; ")).isTrue();
        assertThat(wrapped.contains("KorTTY snippet: check_test_echo.sh")).isTrue();
        assertThat(wrapped.endsWith("; stty echo\n")).isTrue();
    }

    @Test
    void restoresEchoOnFirstLineBeforeHeredocPayload() {
        String wrapped = TerminalView.buildEchoSuppressedGeneratedInput(
            "printf '%s\\n' 'KorTTY snippet: big.sh' >&2 && base64 -d <<'KORTTY_B64_EOF' | bash\n"
                + "YWJj\n"
                + "KORTTY_B64_EOF");

        assertThat(wrapped.contains("base64 -d <<'KORTTY_B64_EOF' | bash; stty echo\nYWJj\n")).isTrue();
        assertThat(wrapped.endsWith("KORTTY_B64_EOF\n")).isTrue();
    }

    @Test
    void decodesRemoteShellOscPayloadToAgentShortcut() {
        String encoded = Base64.getEncoder().encodeToString("install tomcat".getBytes(StandardCharsets.UTF_8));

        String rawCommand = TerminalView.buildTerminalAgentRawCommandFromOscPayload("execute", encoded, "agent");

        assertThat(rawCommand).isEqualTo("agent install tomcat");
    }

    @Test
    void rejectsMalformedRemoteShellOscPayload() {
        assertThat(TerminalView.buildTerminalAgentRawCommandFromOscPayload("execute", "not base64", "agent")).isNull();
        assertThat(TerminalView.buildTerminalAgentRawCommandFromOscPayload("unknown", "aWdub3Jl", "agent")).isNull();
    }

    @Test
    void detectsVisiblePromptWhenCurrentLineContainsTypedShortcut() {
        assertThat(TerminalView.hasVisiblePromptForCommand(
            "daniel@fedora:~$ agent install tmux",
            "agent install tmux")).isTrue();
    }

    @Test
    void doesNotTreatNonPromptOutputAsVisiblePromptForShortcut() {
        assertThat(TerminalView.hasVisiblePromptForCommand(
            "bash: agent: command not found",
            "agent install tmux")).isFalse();
    }

    @Test
    void extractsAgentShortcutFromVisiblePromptLine() {
        assertThat(TerminalView.extractAgentShortcutFromVisibleLine(
            "daniel@fedora ~ $ agent install tmux",
            "agent")).isEqualTo("agent install tmux");
    }

    @Test
    void extractsAgentShortcutFromVisiblePromptLineCaseInsensitivelyWhenEnabled() {
        assertThat(TerminalView.extractAgentShortcutFromVisibleLine(
            "daniel@fedora ~ $ Agent install tmux",
            "agent")).isNull();
        assertThat(TerminalView.extractAgentShortcutFromVisibleLine(
            "daniel@fedora ~ $ Agent install tmux",
            "agent",
            true)).isEqualTo("Agent install tmux");
    }

    @Test
    void extractsAgentAskShortcutFromVisiblePromptLine() {
        assertThat(TerminalView.extractAgentShortcutFromVisibleLine(
            "daniel@fedora:~$ agent-ask what failed?",
            "agent")).isEqualTo("agent-ask what failed?");
    }

    @Test
    void extractsAgentShortcutWithPastedFilenameFromVisibleScreen() {
        assertThat(TerminalView.extractAgentShortcutFromVisibleScreen(
            """
            daniel@fedora:~/Dokumente$ ll
            -rwxr-xr-x. 1 daniel daniel 1383 3. Mai 23:35 groesste_xml.pl
            daniel@fedora:~/Dokumente$ agent das perl script groesste_xml.pl um den schalter -r erweitern
            """,
            "agent",
            false)).isEqualTo("agent das perl script groesste_xml.pl um den schalter -r erweitern");
    }

    @Test
    void extractsWrappedAgentShortcutFromVisibleScreen() {
        assertThat(TerminalView.extractAgentShortcutFromVisibleScreen(
            """
            daniel@fedora:~/Dokumente$ agent das perl script groesste_xml.pl um den schalter -r erweitern
            um auch die verzeichnisse rekursiv zu durchsuchen
            """,
            "agent",
            false)).isEqualTo("agent das perl script groesste_xml.pl um den schalter -r erweitern um auch die verzeichnisse rekursiv zu durchsuchen");
    }

    @Test
    void doesNotExtractAgentShortcutFromCommandNotFoundOutput() {
        assertThat("agent install tmux".equals(TerminalView.extractAgentShortcutFromVisibleLine(
            "bash: agent: Befehl nicht gefunden...",
            "agent"))).isFalse();
    }

    @Test
    void shortensLongHistoryPromptsForDisplayKeepingShortOnesIntact() {
        assertThat(TerminalView.shortenAgentHistoryDisplay("show me the 5 biggest files"))
            .isEqualTo("show me the 5 biggest files");
        assertThat(TerminalView.shortenAgentHistoryDisplay(null)).isEmpty();

        // Exactly 60 characters is kept as-is; 61+ is shortened with a trailing ellipsis.
        String exactly60 = "x".repeat(60);
        assertThat(TerminalView.shortenAgentHistoryDisplay(exactly60)).isEqualTo(exactly60);

        String longPrompt =
            "migriere das script find_biggest_files.pl nach ansible-playbook mit ansible funktionen";
        String shortened = TerminalView.shortenAgentHistoryDisplay(longPrompt);
        assertThat(shortened).endsWith("…");
        assertThat(shortened.length()).isAtMost(TerminalView.AGENT_HISTORY_DISPLAY_MAX_CHARS + 1);
        assertThat(longPrompt).startsWith(shortened.substring(0, shortened.length() - 1));
    }

    @Test
    void recognizesAgentInputCancellationShortcuts() {
        assertThat(TerminalView.isAgentInputCancelShortcut(KeyCode.ESCAPE, false, false, false)).isTrue();
        assertThat(TerminalView.isAgentInputCancelShortcut(KeyCode.C, true, false, false)).isTrue();
        assertThat(TerminalView.isAgentInputCancelShortcut(KeyCode.C, false, false, false)).isFalse();
        assertThat(TerminalView.isAgentInputCancelShortcut(KeyCode.C, true, true, false)).isFalse();
        assertThat(TerminalView.isAgentInputCancelShortcut(KeyCode.R, true, false, false)).isFalse();
    }

    @Test
    void onlyRunControlKeysAreInterceptedWhileAgentRunActive() {
        // Cancel (Esc / Ctrl+C) and toggle-details (Ctrl+R) are intercepted while a run is active.
        assertThat(TerminalView.isAgentRunControlKey(KeyCode.ESCAPE, false, false, false)).isTrue();
        assertThat(TerminalView.isAgentRunControlKey(KeyCode.C, true, false, false)).isTrue();
        assertThat(TerminalView.isAgentRunControlKey(KeyCode.R, true, false, false)).isTrue();
        // Ordinary typing must NOT be intercepted, so it reaches the shell and the shortcut buffer.
        assertThat(TerminalView.isAgentRunControlKey(KeyCode.S, false, false, false)).isFalse();
        assertThat(TerminalView.isAgentRunControlKey(KeyCode.R, false, false, false)).isFalse(); // plain 'r'
        assertThat(TerminalView.isAgentRunControlKey(KeyCode.SPACE, false, false, false)).isFalse();
        assertThat(TerminalView.isAgentRunControlKey(KeyCode.ENTER, false, false, false)).isFalse();
        assertThat(TerminalView.isAgentRunControlKey(KeyCode.R, true, true, false)).isFalse(); // Ctrl+Alt+R
    }

    @Test
    void formatsAgentHistoryTimestampForDisplay() {
        assertThat(TerminalView.formatAgentHistoryTimestamp(0)).isEmpty();
        assertThat(TerminalView.formatAgentHistoryTimestamp(-5)).isEmpty();
        // Timezone-independent shape check: "yyyy-MM-dd HH:mm".
        assertThat(TerminalView.formatAgentHistoryTimestamp(1_718_000_000_000L))
            .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
    }

    @Test
    void identifiesRunControlControlCharacters() {
        assertThat(TerminalView.isAgentRunControlCharacter("\u0003")).isTrue(); // Ctrl+C
        assertThat(TerminalView.isAgentRunControlCharacter("\u0012")).isTrue(); // Ctrl+R
        assertThat(TerminalView.isAgentRunControlCharacter("\u001B")).isTrue(); // Esc
        assertThat(TerminalView.isAgentRunControlCharacter("s")).isFalse();
        assertThat(TerminalView.isAgentRunControlCharacter("\r")).isFalse();
        assertThat(TerminalView.isAgentRunControlCharacter("")).isFalse();
        assertThat(TerminalView.isAgentRunControlCharacter(null)).isFalse();
    }

    @Test
    void normalizesLocalTerminalMessageLines() {
        assertThat(TerminalView.normalizeTerminalMessageLines("Installed Tomcat RPMs:\r\n- tomcat  \n- tomcat-lib")).isEqualTo(java.util.List.of("Installed Tomcat RPMs:", "- tomcat", "- tomcat-lib"));
    }

    @Test
    void extractsPromptForLocalRedisplayFromScreenWithBlankRows() {
        String screen = "Last login\n"
            + "daniel@fedora:~$ \n"
            + "                  \n"
            + "                  \n";

        assertThat(TerminalView.extractPromptForLocalRedisplay(screen, "agent")).isEqualTo("daniel@fedora:~$ ");
    }

    @Test
    void extractsPromptForLocalRedisplayFromTypedAgentShortcutLine() {
        String screen = "Last login\n"
            + "daniel@fedora:~$ agent install tomcat\n"
            + "                  \n";

        assertThat(TerminalView.extractPromptForLocalRedisplay(screen, "agent")).isEqualTo("daniel@fedora:~$ ");
    }

    @Test
    void allowsTerminalAgentRunBelowConcurrencyCap() {
        assertThat(TerminalView.canStartTerminalAgentRun(0, 5)).isTrue();
        assertThat(TerminalView.canStartTerminalAgentRun(4, 5)).isTrue();
    }

    @Test
    void blocksTerminalAgentRunAtOrAboveConcurrencyCap() {
        assertThat(TerminalView.canStartTerminalAgentRun(5, 5)).isFalse();
        assertThat(TerminalView.canStartTerminalAgentRun(6, 5)).isFalse();
        // A non-positive cap disables starting runs entirely.
        assertThat(TerminalView.canStartTerminalAgentRun(0, 0)).isFalse();
    }
}
