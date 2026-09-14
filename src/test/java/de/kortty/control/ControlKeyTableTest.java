package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.expectThrows;

import de.kortty.codingagent.KeyChord;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

class ControlKeyTableTest {

    private static final String ESC = "\u001b";

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void everyKeyChordIdEncodesByteIdenticallyToTheChordItself() throws Exception {
        for (KeyChord chord : KeyChord.values()) {
            assertWithMessage("encoding of %s", chord.id())
                .that(ControlKeyTable.encode(chord.id()))
                .isEqualTo(chord.bytes());
        }
    }

    @Test
    void theDocumentedExtensionsEncodeToTheExactWireSequences() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("return", "\r");
        expected.put("escape", ESC);
        expected.put("shift+tab", ESC + "[Z");
        expected.put("home", ESC + "[H");
        expected.put("end", ESC + "[F");
        expected.put("pageup", ESC + "[5~");
        expected.put("pagedown", ESC + "[6~");
        expected.put("delete", ESC + "[3~");
        expected.put("insert", ESC + "[2~");
        expected.put("f1", ESC + "OP");
        expected.put("f2", ESC + "OQ");
        expected.put("f3", ESC + "OR");
        expected.put("f4", ESC + "OS");
        expected.put("f5", ESC + "[15~");
        expected.put("f6", ESC + "[17~");
        expected.put("f7", ESC + "[18~");
        expected.put("f8", ESC + "[19~");
        expected.put("f9", ESC + "[20~");
        expected.put("f10", ESC + "[21~");
        expected.put("f11", ESC + "[23~");
        expected.put("f12", ESC + "[24~");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            assertWithMessage("encoding of %s", entry.getKey())
                .that(text(ControlKeyTable.encode(entry.getKey())))
                .isEqualTo(entry.getValue());
        }
    }

    @Test
    void theWholeControlRowEncodesToItsControlByte() throws Exception {
        for (char letter = 'a'; letter <= 'z'; letter++) {
            assertWithMessage("encoding of ctrl+%s", letter)
                .that(ControlKeyTable.encode("ctrl+" + letter))
                .isEqualTo(new byte[] {(byte) (letter - 'a' + 1)});
        }
    }

    @Test
    void anyPrintableCharacterIsSentAsItself() throws Exception {
        assertThat(text(ControlKeyTable.encode("q"))).isEqualTo("q");
        assertThat(text(ControlKeyTable.encode("7"))).isEqualTo("7");
        assertThat(text(ControlKeyTable.encode("/"))).isEqualTo("/");
        assertThat(text(ControlKeyTable.encode("-"))).isEqualTo("-");
        assertThat(text(ControlKeyTable.encode("_"))).isEqualTo("_");
        assertThat(text(ControlKeyTable.encode("+"))).isEqualTo("+");
        assertThat(text(ControlKeyTable.encode("ä"))).isEqualTo("ä");
    }

    @Test
    void parsingIsCaseInsensitiveAndAcceptsDashAndUnderscoreForPlus() throws Exception {
        byte[] expected = ControlKeyTable.encode("ctrl+c");
        for (String spelling : List.of("CTRL+C", "Ctrl-c", "ctrl_C", "  ctrl+c  ")) {
            assertWithMessage("encoding of %s", spelling)
                .that(ControlKeyTable.encode(spelling))
                .isEqualTo(expected);
        }
        assertThat(ControlKeyTable.encode("SHIFT-TAB")).isEqualTo(ControlKeyTable.encode("shift+tab"));
        assertThat(ControlKeyTable.encode("Enter")).isEqualTo(ControlKeyTable.encode("enter"));
    }

    @Test
    void anUnknownNameIsASyntaxErrorThatPublishesTheVocabulary() {
        ControlApiException failure = expectThrows(ControlApiException.class,
            () -> ControlKeyTable.encode("hyperspace"));
        assertThat(failure.code()).isEqualTo(ControlErrorCode.UNKNOWN_KEY);
        assertThat(failure.data()).containsEntry("key", "hyperspace");
        Object known = failure.data().get("known");
        assertThat(known).isInstanceOf(List.class);
        assertThat((List<?>) known).isNotEmpty();
        assertThat((List<?>) known).containsAtLeast("enter", "ctrl+c", "f12");
    }

    @Test
    void anUnknownNameNeverSurfacesAsAnIllegalArgumentException() {
        for (String name : List.of("hyperspace", "ctrl+", "ctrl++", "meta+x", "f13", "", "\u0001")) {
            Throwable failure = expectThrows(Throwable.class, () -> ControlKeyTable.encode(name));
            assertWithMessage("failure type for %s", name)
                .that(failure)
                .isInstanceOf(ControlApiException.class);
        }
        Throwable nullFailure = expectThrows(Throwable.class, () -> ControlKeyTable.encode(null));
        assertThat(nullFailure).isInstanceOf(ControlApiException.class);
    }

    @Test
    void encodeAllConcatenatesInOrderAndRefusesAnEmptyList() throws Exception {
        assertThat(text(ControlKeyTable.encodeAll(List.of("y", "enter")))).isEqualTo("y\r");
        ControlApiException failure = expectThrows(ControlApiException.class,
            () -> ControlKeyTable.encodeAll(List.of()));
        assertThat(failure.code()).isEqualTo(ControlErrorCode.EMPTY_INPUT);
        ControlApiException nullFailure = expectThrows(ControlApiException.class,
            () -> ControlKeyTable.encodeAll(null));
        assertThat(nullFailure.code()).isEqualTo(ControlErrorCode.EMPTY_INPUT);
    }

    @Test
    void normaliseReportsTheCanonicalSpelling() throws Exception {
        assertThat(ControlKeyTable.normalise(List.of("RETURN", "Escape", "Ctrl-C", "q")))
            .containsExactly("enter", "esc", "ctrl+c", "q")
            .inOrder();
    }

    @Test
    void splitBreaksASpaceSeparatedForm() {
        assertThat(ControlKeyTable.split("ctrl+c enter")).containsExactly("ctrl+c", "enter").inOrder();
        assertThat(ControlKeyTable.split("  y   enter ")).containsExactly("y", "enter").inOrder();
        assertThat(ControlKeyTable.split("")).isEmpty();
        assertThat(ControlKeyTable.split(null)).isEmpty();
    }

    @Test
    void asKeyChordDelegatesTheCommonCaseAndDeclinesTheRest() {
        for (KeyChord chord : KeyChord.values()) {
            assertWithMessage("asKeyChord of %s", chord.id())
                .that(ControlKeyTable.asKeyChord(chord.id()))
                .hasValue(chord);
        }
        assertThat(ControlKeyTable.asKeyChord("CTRL-C")).hasValue(KeyChord.CTRL_C);
        assertThat(ControlKeyTable.asKeyChord("return")).hasValue(KeyChord.ENTER);
        assertThat(ControlKeyTable.asKeyChord("shift+tab")).isEmpty();
        assertThat(ControlKeyTable.asKeyChord("f7")).isEmpty();
        assertThat(ControlKeyTable.asKeyChord("hyperspace")).isEmpty();
        assertThat(ControlKeyTable.asKeyChord("Y")).isEmpty();
    }

    @Test
    void theVocabularyIsPublishedWithoutDuplicates() {
        List<String> known = ControlKeyTable.knownKeys();
        assertThat(known).containsNoDuplicates();
        assertThat(known).containsAtLeast("enter", "return", "esc", "escape", "shift+tab",
            "backspace", "space", "up", "down", "left", "right", "home", "end", "pageup",
            "pagedown", "delete", "insert", "f1", "f12", "ctrl+a", "ctrl+z", "y", "n");
    }

    @Test
    void encodeHandsOutDefensiveCopies() throws Exception {
        byte[] first = ControlKeyTable.encode("enter");
        first[0] = 'X';
        assertThat(ControlKeyTable.encode("enter")).isEqualTo(new byte[] {'\r'});
    }
}
