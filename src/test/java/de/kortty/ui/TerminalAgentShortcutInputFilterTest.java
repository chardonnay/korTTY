package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class TerminalAgentShortcutInputFilterTest {

    @Test
    void dispatchesTypedCommandWithPlainPastedFilenameExactlyOnce() {
        List<String> dispatched = new ArrayList<>();
        TerminalAgentShortcutInputFilter filter = newFilter(false, dispatched);

        assertThat(filter.filter(bytes("agent update "))).isEqualTo(bytes("agent update "));
        assertThat(filter.filter(bytes("invoice final.txt"))).isEqualTo(bytes("invoice final.txt"));

        assertThat(filter.filter(bytes("\r")))
            .isEqualTo(new byte[] {TerminalAgentShortcutInputFilter.CLEAR_INPUT_LINE});
        assertThat(dispatched).containsExactly("agent update invoice final.txt");

        // A trailing LF from a separately-written CRLF is swallowed and cannot launch again.
        assertThat(filter.filter(bytes("\n"))).isEmpty();
        assertThat(dispatched).hasSize(1);
    }

    @Test
    void bracketedPasteMarkersAreExcludedAndPastedNewlineDoesNotSubmit() {
        List<String> dispatched = new ArrayList<>();
        TerminalAgentShortcutInputFilter filter = newFilter(false, dispatched);
        String input = "agent explain \u001B[200~first line\nsecond ü.txt\u001B[201~\r\n";

        assertThat(filter.filter(bytes(input)))
            .isEqualTo(new byte[] {TerminalAgentShortcutInputFilter.CLEAR_INPUT_LINE});
        assertThat(dispatched).containsExactly("agent explain first line\nsecond ü.txt");
    }

    @Test
    void preservesUtf8CharactersSplitAcrossConnectorWrites() {
        List<String> dispatched = new ArrayList<>();
        TerminalAgentShortcutInputFilter filter = newFilter(false, dispatched);
        byte[] command = bytes("agent prüfe résumé-📄.txt");
        ByteArrayOutputStream forwarded = new ByteArrayOutputStream();

        for (byte value : command) {
            forwarded.writeBytes(filter.filter(new byte[] {value}));
        }

        assertThat(forwarded.toByteArray()).isEqualTo(command);
        assertThat(filter.filter(bytes("\r")))
            .isEqualTo(new byte[] {TerminalAgentShortcutInputFilter.CLEAR_INPUT_LINE});
        assertThat(dispatched).containsExactly("agent prüfe résumé-📄.txt");
    }

    @Test
    void backspaceAndCtrlUUpdateTheBufferedCommand() {
        List<String> dispatched = new ArrayList<>();
        TerminalAgentShortcutInputFilter filter = newFilter(false, dispatched);

        filter.filter(bytes("agent stale"));
        filter.filter(new byte[] {0x15});
        filter.filter(bytes("agent check filx"));
        filter.filter(new byte[] {0x7F});

        assertThat(filter.filter(bytes("e.txt\r")))
            .isEqualTo(new byte[] {TerminalAgentShortcutInputFilter.CLEAR_INPUT_LINE});
        assertThat(dispatched).containsExactly("agent check file.txt");
    }

    @Test
    void ctrlCResetsBufferedInputWithoutSwallowingTheControlCharacter() {
        List<String> dispatched = new ArrayList<>();
        TerminalAgentShortcutInputFilter filter = newFilter(false, dispatched);

        filter.filter(bytes("agent cancelled"));
        assertThat(filter.filter(new byte[] {0x03})).isEqualTo(new byte[] {0x03});
        filter.filter(bytes("agent replacement"));
        filter.filter(bytes("\n"));

        assertThat(dispatched).containsExactly("agent replacement");
    }

    @Test
    void ordinaryShellCommandsAndCrLfPassThroughUnchanged() {
        List<String> dispatched = new ArrayList<>();
        TerminalAgentShortcutInputFilter filter = newFilter(false, dispatched);
        byte[] command = bytes("printf 'ok'\r\nls -la\n");

        assertThat(filter.filter(command)).isEqualTo(command);
        assertThat(dispatched).isEmpty();
    }

    @Test
    void remoteHandledShortcutPassesThroughWithoutLocalDispatch() {
        List<String> dispatched = new ArrayList<>();
        TerminalAgentShortcutInputFilter filter = newFilter(true, dispatched);
        byte[] command = bytes("agent install tmux\r\n");

        assertThat(filter.filter(command)).isEqualTo(command);
        assertThat(dispatched).isEmpty();
    }

    @Test
    void sameWriteKeepsCompletedNormalLineButRemovesAgentLineAndEnter() {
        List<String> dispatched = new ArrayList<>();
        TerminalAgentShortcutInputFilter filter = newFilter(false, dispatched);

        assertThat(filter.filter(bytes("pwd\nagent inspect pasted.txt\r\n")))
            .isEqualTo(concat(bytes("pwd\n"), TerminalAgentShortcutInputFilter.CLEAR_INPUT_LINE));
        assertThat(dispatched).containsExactly("agent inspect pasted.txt");
    }

    private static TerminalAgentShortcutInputFilter newFilter(
        boolean shellHandlesAgentShortcut,
        List<String> dispatched) {

        return new TerminalAgentShortcutInputFilter(
            value -> value != null ? value.trim() : "",
            raw -> shellHandlesAgentShortcut && isAgentShortcut(raw),
            TerminalAgentShortcutInputFilterTest::isAgentShortcut,
            dispatched::add);
    }

    private static boolean isAgentShortcut(String raw) {
        return raw != null && raw.startsWith("agent ") && raw.length() > "agent ".length();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] prefix, byte suffix) {
        byte[] result = java.util.Arrays.copyOf(prefix, prefix.length + 1);
        result[result.length - 1] = suffix;
        return result;
    }
}
