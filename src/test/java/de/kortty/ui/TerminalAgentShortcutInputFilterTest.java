package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

    @Test
    void singleByteCharsetForwardsHighBytesImmediately() {
        List<String> dispatched = new ArrayList<>();
        TerminalAgentShortcutInputFilter filter = newFilter(false, dispatched, StandardCharsets.ISO_8859_1);

        // 0xE4 is "ä" in Latin-1, but a UTF-8 lead byte: it must not wait for a continuation.
        assertThat(filter.filter(new byte[] {(byte) 0xE4})).isEqualTo(new byte[] {(byte) 0xE4});
        assertThat(filter.filter(new byte[] {(byte) 0xDF, (byte) 0xE9})).isEqualTo(new byte[] {(byte) 0xDF, (byte) 0xE9});
        assertThat(dispatched).isEmpty();
    }

    @Test
    void dispatchesLatin1AgentCommand() {
        List<String> dispatched = new ArrayList<>();
        TerminalAgentShortcutInputFilter filter = newFilter(false, dispatched, StandardCharsets.ISO_8859_1);
        byte[] command = "agent prüfe".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream forwarded = new ByteArrayOutputStream();

        for (byte value : command) {
            forwarded.writeBytes(filter.filter(new byte[] {value}));
        }

        assertThat(forwarded.toByteArray()).isEqualTo(command);
        assertThat(filter.filter(bytes("\r")))
            .isEqualTo(new byte[] {TerminalAgentShortcutInputFilter.CLEAR_INPUT_LINE});
        assertThat(dispatched).containsExactly("agent prüfe");
    }

    @Test
    void windows1252BytesBecomeTheirCharactersInTheCommand() {
        List<String> dispatched = new ArrayList<>();
        java.nio.charset.Charset windows1252 = java.nio.charset.Charset.forName("Windows-1252");
        TerminalAgentShortcutInputFilter filter = newFilter(false, dispatched, windows1252);

        // 0x80 is the euro sign in Windows-1252 and a stray continuation byte in UTF-8.
        filter.filter("agent price 5€".getBytes(windows1252));
        filter.filter(bytes("\r"));

        assertThat(dispatched).containsExactly("agent price 5€");
    }

    @Test
    void aMirroredAgentLineIsForwardedAndJournaledButStartsNoRun() throws IOException {
        List<String> dispatched = new ArrayList<>();
        List<String> intercepted = new ArrayList<>();
        List<String> journaled = new ArrayList<>();
        TerminalAgentShortcutInputFilter filter = new TerminalAgentShortcutInputFilter(
            value -> value != null ? value.trim() : "",
            raw -> false,
            raw -> {
                intercepted.add(raw);
                return isAgentShortcut(raw);
            },
            dispatched::add,
            journaled::add);

        // Broadcast mode mirrors the keys one by one, the way they were typed in the source pane.
        ByteArrayOutputStream forwarded = new ByteArrayOutputStream();
        for (byte value : bytes("agent restart nginx\r")) {
            forwarded.writeBytes(mirrored(filter, new byte[] {value}));
        }

        assertThat(forwarded.toByteArray()).isEqualTo(bytes("agent restart nginx\r"));
        assertThat(dispatched).isEmpty();
        assertThat(intercepted).isEmpty();
        assertThat(journaled).containsExactly("agent restart nginx");
    }

    @Test
    void aMirroredEnterLeavesTheNextTypedAgentLineToStartItsRun() throws IOException {
        List<String> dispatched = new ArrayList<>();
        TerminalAgentShortcutInputFilter filter = newFilter(false, dispatched);

        assertThat(mirrored(filter, bytes("agent check disk\r\n"))).isEqualTo(bytes("agent check disk\r\n"));
        assertThat(dispatched).isEmpty();

        // Typed in this pane itself, the shortcut works as before.
        filter.filter(bytes("agent check memory"));
        assertThat(filter.filter(bytes("\r")))
            .isEqualTo(new byte[] {TerminalAgentShortcutInputFilter.CLEAR_INPUT_LINE});
        assertThat(dispatched).containsExactly("agent check memory");
    }

    @Test
    void anAgentLineTypedHereButSubmittedByAMirroredEnterStartsNoRun() throws IOException {
        List<String> dispatched = new ArrayList<>();
        TerminalAgentShortcutInputFilter filter = newFilter(false, dispatched);

        assertThat(filter.filter(bytes("agent explain"))).isEqualTo(bytes("agent explain"));
        assertThat(mirrored(filter, bytes("\r"))).isEqualTo(bytes("\r"));

        assertThat(dispatched).isEmpty();
    }

    @Test
    void mirroredInputNeverStartsARunWhateverItCarries() throws IOException {
        List<String> dispatched = new ArrayList<>();
        TerminalAgentShortcutInputFilter filter = newFilter(false, dispatched);
        // Bracketed-paste markers, an OSC sequence and the shortcut, all arriving mirrored.
        String input = "agent \u001B[200~deploy\u001B[201~ now\u001B]0;title\u0007\r"
            + "agent rollback\n";

        assertThat(mirrored(filter, bytes(input))).isEqualTo(bytes(input));
        assertThat(dispatched).isEmpty();
    }

    private static byte[] mirrored(TerminalAgentShortcutInputFilter filter, byte[] bytes) throws IOException {
        byte[][] out = new byte[1][];
        MirroredInput.run(() -> out[0] = filter.filter(bytes));
        assertThat(MirroredInput.active()).isFalse();
        return out[0];
    }

    private static TerminalAgentShortcutInputFilter newFilter(
        boolean shellHandlesAgentShortcut,
        List<String> dispatched,
        java.nio.charset.Charset charset) {

        return new TerminalAgentShortcutInputFilter(
            value -> value != null ? value.trim() : "",
            raw -> shellHandlesAgentShortcut && isAgentShortcut(raw),
            TerminalAgentShortcutInputFilterTest::isAgentShortcut,
            dispatched::add,
            null,
            charset);
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
