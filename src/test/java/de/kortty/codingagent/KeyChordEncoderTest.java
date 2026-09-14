package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.testng.annotations.Test;

class KeyChordEncoderTest {

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    @Test
    void singleByteChords() {
        assertThat(KeyChordEncoder.encode(KeyChord.ENTER)).isEqualTo(new byte[] {'\r'});
        assertThat(KeyChordEncoder.encode(KeyChord.ENTER)).isNotEqualTo(new byte[] {'\n'});
        assertThat(KeyChordEncoder.encode(KeyChord.ESC)).isEqualTo(new byte[] {0x1b});
        assertThat(KeyChordEncoder.encode(KeyChord.CTRL_C)).isEqualTo(new byte[] {0x03});
        assertThat(KeyChordEncoder.encode(KeyChord.CTRL_D)).isEqualTo(new byte[] {0x04});
        assertThat(KeyChordEncoder.encode(KeyChord.CTRL_U)).isEqualTo(new byte[] {0x15});
        assertThat(KeyChordEncoder.encode(KeyChord.BACKSPACE)).isEqualTo(new byte[] {0x7f});
        assertThat(KeyChordEncoder.encode(KeyChord.TAB)).isEqualTo(new byte[] {0x09});
        assertThat(KeyChordEncoder.encode(KeyChord.SPACE)).isEqualTo(new byte[] {' '});
        assertThat(KeyChordEncoder.encode(KeyChord.Y)).isEqualTo(new byte[] {'y'});
        assertThat(KeyChordEncoder.encode(KeyChord.N)).isEqualTo(new byte[] {'n'});
    }

    @Test
    void arrowsAreSs3Sequences() {
        assertThat(text(KeyChordEncoder.encode(KeyChord.UP))).isEqualTo("\u001bOA");
        assertThat(text(KeyChordEncoder.encode(KeyChord.DOWN))).isEqualTo("\u001bOB");
        assertThat(text(KeyChordEncoder.encode(KeyChord.RIGHT))).isEqualTo("\u001bOC");
        assertThat(text(KeyChordEncoder.encode(KeyChord.LEFT))).isEqualTo("\u001bOD");
    }

    @Test
    void chordBytesAreDefensiveCopies() {
        byte[] first = KeyChord.ENTER.bytes();
        first[0] = 'x';
        assertThat(KeyChord.ENTER.bytes()).isEqualTo(new byte[] {'\r'});
    }

    @Test
    void listEncodingConcatenates() {
        assertThat(text(KeyChordEncoder.encode(List.of(KeyChord.Y, KeyChord.ENTER)))).isEqualTo("y\r");
        assertThat(text(KeyChordEncoder.encode(List.of(KeyChord.ESC, KeyChord.CTRL_C)))).isEqualTo("\u001b");
        assertThat(KeyChordEncoder.encode(List.of())).isEmpty();
        assertThat(KeyChordEncoder.encode((List<KeyChord>) null)).isEmpty();
    }

    @Test
    void parseIsCaseAndDashTolerant() {
        assertThat(KeyChord.parse("Ctrl-C")).hasValue(KeyChord.CTRL_C);
        assertThat(KeyChord.parse("CTRL+C")).hasValue(KeyChord.CTRL_C);
        assertThat(KeyChord.parse("ctrl_u")).hasValue(KeyChord.CTRL_U);
        assertThat(KeyChord.parse(" Enter ")).hasValue(KeyChord.ENTER);
        assertThat(KeyChord.parse("Y")).hasValue(KeyChord.Y);
        assertThat(KeyChord.parse("nope")).isEmpty();
        assertThat(KeyChord.parse("")).isEmpty();
        assertThat(KeyChord.parse(null)).isEmpty();
        for (KeyChord chord : KeyChord.values()) {
            assertThat(KeyChord.parse(chord.id())).hasValue(chord);
        }
    }

    @Test
    void parseAllSplitsOnWhitespaceAndRejectsUnknownIds() {
        assertThat(KeyChordEncoder.parseAll("y enter")).containsExactly(KeyChord.Y, KeyChord.ENTER).inOrder();
        assertThat(KeyChordEncoder.parseAll("  Ctrl-C \t esc ")).containsExactly(KeyChord.CTRL_C, KeyChord.ESC).inOrder();
        assertThat(KeyChordEncoder.parseAll("")).isEmpty();
        assertThat(KeyChordEncoder.parseAll(null)).isEmpty();
        assertThrows(IllegalArgumentException.class, () -> KeyChordEncoder.parseAll("y bogus"));
    }

    @Test
    void promptPayloadSingleLine() {
        assertThat(KeyChordEncoder.promptPayload("fix the tests", false)).isEqualTo("fix the tests\r");
        assertThat(KeyChordEncoder.promptPayload("fix the tests", true)).isEqualTo("fix the tests\r");
        assertThat(KeyChordEncoder.promptPayload("fix the tests\n", true)).isEqualTo("fix the tests\r");
        assertThat(KeyChordEncoder.promptPayload("fix the tests\r\n", false)).isEqualTo("fix the tests\r");
        assertThat(KeyChordEncoder.promptPayload("", true)).isEmpty();
        assertThat(KeyChordEncoder.promptPayload("  \n", true)).isEmpty();
        assertThat(KeyChordEncoder.promptPayload(null, false)).isEmpty();
    }

    @Test
    void promptPayloadMultiLineBracketed() {
        assertThat(KeyChordEncoder.promptPayload("line one\r\nline two", true))
            .isEqualTo("\u001b[200~line one\rline two\u001b[201~\r");
        assertThat(KeyChordEncoder.promptPayload("a\nb\nc\n", true))
            .isEqualTo("\u001b[200~a\rb\rc\u001b[201~\r");
    }

    @Test
    void promptPayloadMultiLineUnbracketed() {
        assertThat(KeyChordEncoder.promptPayload("line one\r\nline two", false)).isEqualTo("line one\rline two\r");
        assertThat(KeyChordEncoder.promptPayload("a\nb\nc\n", false)).isEqualTo("a\rb\rc\r");
    }

    @Test
    void isMultiLine() {
        assertThat(KeyChordEncoder.isMultiLine("one")).isFalse();
        assertThat(KeyChordEncoder.isMultiLine("one\n")).isFalse();
        assertThat(KeyChordEncoder.isMultiLine("one\r\n")).isFalse();
        assertThat(KeyChordEncoder.isMultiLine("one\ntwo")).isTrue();
        assertThat(KeyChordEncoder.isMultiLine("one\r\ntwo")).isTrue();
        assertThat(KeyChordEncoder.isMultiLine("one\rtwo")).isTrue();
        assertThat(KeyChordEncoder.isMultiLine(null)).isFalse();
        assertThat(KeyChordEncoder.isMultiLine("")).isFalse();
    }
}
