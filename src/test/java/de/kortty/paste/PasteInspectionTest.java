package de.kortty.paste;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import org.testng.annotations.Test;

class PasteInspectionTest {

    private static final String ESC = "\u001b";

    private static final String CSI = "\u009b";

    private static final String RLO = "\u202e";

    private static final String ZWJ = "\u200d";

    private static final String EMOJI = "\ud83d\ude00";

    private static String lines(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                text.append('\n');
            }
            text.append("line ").append(i);
        }
        return text.toString();
    }

    // --- line breaks and lines ---

    @Test
    void aSingleLineHasNoLineBreak() {
        PasteInspection inspection = PasteInspection.of("ls -la");
        assertThat(inspection.containsLineBreak()).isFalse();
        assertThat(inspection.endsWithLineBreak()).isFalse();
        assertThat(inspection.lineCount()).isEqualTo(1);
    }

    @Test
    void aLoneTrailingNewlineCountsAsALineBreak() {
        PasteInspection inspection = PasteInspection.of("ls -la\n");
        assertThat(inspection.containsLineBreak()).isTrue();
        assertThat(inspection.endsWithLineBreak()).isTrue();
        assertThat(inspection.lineCount()).isEqualTo(1);
    }

    @Test
    void crLfIsOneLineBreakAndALoneCrIsOneToo() {
        assertThat(PasteInspection.of("a\r\nb\r\nc").lineCount()).isEqualTo(3);
        assertThat(PasteInspection.of("a\rb").lineCount()).isEqualTo(2);
        assertThat(PasteInspection.of("a\rb").containsLineBreak()).isTrue();
        assertThat(PasteInspection.of("a\n\rb").lineCount()).isEqualTo(3);
        assertThat(PasteInspection.of("a\r\n").lineCount()).isEqualTo(1);
        assertThat(PasteInspection.of("a\r\n").endsWithLineBreak()).isTrue();
        assertThat(PasteInspection.of("a\r").endsWithLineBreak()).isTrue();
    }

    @Test
    void emptyLinesCount() {
        assertThat(PasteInspection.of("a\n\nb").lineCount()).isEqualTo(3);
        assertThat(PasteInspection.of("a\n\n").lineCount()).isEqualTo(2);
        assertThat(PasteInspection.of("\n").lineCount()).isEqualTo(1);
        assertThat(PasteInspection.of("\n").containsLineBreak()).isTrue();
    }

    @Test
    void nullAndEmptyHoldNothing() {
        for (String nothing : new String[] {null, ""}) {
            PasteInspection inspection = PasteInspection.of(nothing);
            assertThat(inspection.lineCount()).isEqualTo(0);
            assertThat(inspection.containsLineBreak()).isFalse();
            assertThat(inspection.endsWithLineBreak()).isFalse();
            assertThat(inspection.utf8Bytes()).isEqualTo(0L);
            assertThat(inspection.controlCharCount()).isEqualTo(0);
            assertThat(inspection.bidiControlCount()).isEqualTo(0);
            assertThat(inspection.containsControlCharacters()).isFalse();
            assertThat(inspection.containsBracketMarker()).isFalse();
            assertThat(inspection.preview()).isEqualTo(new PasteInspection.Preview("", false));
        }
    }

    // --- size ---

    @Test
    void theUtf8SizeCountsEveryCharacterAsItIsEncoded() {
        assertThat(PasteInspection.of("abc").utf8Bytes()).isEqualTo(3L);
        assertThat(PasteInspection.of("\u00e4").utf8Bytes()).isEqualTo(2L);
        assertThat(PasteInspection.of("\u20ac").utf8Bytes()).isEqualTo(3L);
        assertThat(PasteInspection.of(EMOJI).utf8Bytes()).isEqualTo(4L);
        assertThat(PasteInspection.of("a\u00e4\u20ac" + EMOJI + "\r\n").utf8Bytes()).isEqualTo(12L);
    }

    @Test
    void theUtf8SizeMatchesWhatJavaEncodesEvenForLoneSurrogates() {
        char[] alphabet = {'a', '\n', '\u001b', '\u00e4', '\u07ff', '\u0800', '\u20ac', '\ud83d', '\ude00',
            '\ud800', '\udfff', '\uffff', '\u009b'};
        Random random = new Random(20261003L);
        for (int round = 0; round < 2_000; round++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(30);
            for (int i = 0; i < length; i++) {
                text.append(alphabet[random.nextInt(alphabet.length)]);
            }
            String sample = text.toString();
            assertWithMessage("%s", sample.chars().mapToObj(Integer::toHexString).toList())
                .that(PasteInspection.of(sample).utf8Bytes())
                .isEqualTo((long) sample.getBytes(StandardCharsets.UTF_8).length);
        }
    }

    // --- control and bidi characters ---

    @Test
    void tabLineFeedAndCarriageReturnAreNotControlCharacters() {
        PasteInspection inspection = PasteInspection.of("a\tb\nc\rd\r\n");
        assertThat(inspection.controlCharCount()).isEqualTo(0);
        assertThat(inspection.containsControlCharacters()).isFalse();
    }

    @Test
    void c0DelAndC1AreControlCharacters() {
        assertThat(PasteInspection.of("\u0000").controlCharCount()).isEqualTo(1);
        assertThat(PasteInspection.of("stop\u0003").controlCharCount()).isEqualTo(1);
        assertThat(PasteInspection.of("x" + ESC + "[31mred").controlCharCount()).isEqualTo(1);
        assertThat(PasteInspection.of("\u001f\u007f").controlCharCount()).isEqualTo(2);
        assertThat(PasteInspection.of("\u0080\u0085\u009b\u009f").controlCharCount()).isEqualTo(4);
        assertThat(PasteInspection.of("\u0003").containsControlCharacters()).isTrue();
    }

    @Test
    void printableNeighboursOfTheControlRangesAreNotControlCharacters() {
        assertThat(PasteInspection.of(" ~\u00a0\u00e4\u00ff").controlCharCount()).isEqualTo(0);
        assertThat(PasteInspection.isControlCharacter(0x20)).isFalse();
        assertThat(PasteInspection.isControlCharacter(0x7e)).isFalse();
        assertThat(PasteInspection.isControlCharacter(0xa0)).isFalse();
        assertThat(PasteInspection.isControlCharacter(0x1f)).isTrue();
        assertThat(PasteInspection.isControlCharacter(0x7f)).isTrue();
        assertThat(PasteInspection.isControlCharacter(0x80)).isTrue();
        assertThat(PasteInspection.isControlCharacter(0x9f)).isTrue();
    }

    @Test
    void anEmbeddedBracketMarkerCountsAsAControlCharacter() {
        PasteInspection inspection = PasteInspection.of("echo hi" + ESC + "[201~");
        assertThat(inspection.controlCharCount()).isEqualTo(1);
        assertThat(inspection.containsBracketMarker()).isTrue();
    }

    @Test
    void everyBidiControlIsCounted() {
        List<Integer> bidi = List.of(0x061c, 0x200e, 0x200f, 0x202a, 0x202b, 0x202c, 0x202d, 0x202e, 0x2066,
            0x2067, 0x2068, 0x2069);
        for (int codePoint : bidi) {
            String text = "a" + Character.toString(codePoint) + "b";
            PasteInspection inspection = PasteInspection.of(text);
            assertWithMessage(Integer.toHexString(codePoint)).that(inspection.bidiControlCount()).isEqualTo(1);
            assertWithMessage(Integer.toHexString(codePoint)).that(inspection.controlCharCount()).isEqualTo(0);
            assertWithMessage(Integer.toHexString(codePoint)).that(inspection.containsControlCharacters()).isTrue();
        }
        assertThat(PasteInspection.of(RLO + "gnp.exe" + "\u202c" + "\u2066").bidiControlCount()).isEqualTo(3);
    }

    @Test
    void zeroWidthAndOtherInvisibleCharactersAreNotCounted() {
        PasteInspection inspection = PasteInspection.of("a" + ZWJ + "\u200b\u200c\u2060\ufeff\u00ad\u2028" + "b");
        assertThat(inspection.bidiControlCount()).isEqualTo(0);
        assertThat(inspection.controlCharCount()).isEqualTo(0);
        assertThat(inspection.containsControlCharacters()).isFalse();
        assertThat(PasteInspection.isBidiControl(0x200d)).isFalse();
        assertThat(PasteInspection.isBidiControl(0x2065)).isFalse();
        assertThat(PasteInspection.isBidiControl(0x202f)).isFalse();
    }

    // --- bracketed-paste markers ---

    @Test
    void allFourMarkerFormsAreFound() {
        for (String marker : List.of(ESC + "[200~", ESC + "[201~", CSI + "200~", CSI + "201~")) {
            assertWithMessage("%s", marker.chars().mapToObj(Integer::toHexString).toList())
                .that(PasteInspection.of("a" + marker + "b").containsBracketMarker()).isTrue();
            assertThat(PasteInspection.of(marker).containsBracketMarker()).isTrue();
        }
    }

    @Test
    void otherSequencesAreNotMarkers() {
        for (String other : List.of(ESC + "[202~", ESC + "[2004h", "[201~", "201~", ESC + "]201~",
                ESC + "x[201~", ESC + "[20~", "~~", "~")) {
            assertWithMessage("%s", other.chars().mapToObj(Integer::toHexString).toList())
                .that(PasteInspection.of(other).containsBracketMarker()).isFalse();
        }
    }

    @Test
    void aMarkerIsFoundExactlyWhenTheSanitizerRemovesSomething() {
        char[] alphabet = {'\u001b', '\u009b', '[', '2', '0', '1', '~', 'x'};
        Random random = new Random(42L);
        for (int round = 0; round < 5_000; round++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(24);
            for (int i = 0; i < length; i++) {
                text.append(alphabet[random.nextInt(alphabet.length)]);
            }
            String sample = text.toString();
            boolean stripped = !PasteSanitizer.stripBracketMarkers(sample).equals(sample);
            assertWithMessage("%s", sample.chars().mapToObj(Integer::toHexString).toList())
                .that(PasteInspection.of(sample).containsBracketMarker()).isEqualTo(stripped);
        }
    }

    // --- preview ---

    @Test
    void thePreviewShowsC0ControlsAsControlPictures() {
        assertThat(PasteInspection.of("a" + ESC + "[31m").preview().text()).isEqualTo("a\u241b[31m");
        assertThat(PasteInspection.of("\u0000\u0003\u001f").preview().text()).isEqualTo("\u2400\u2403\u241f");
        assertThat(PasteInspection.of("x\u007fy").preview().text()).isEqualTo("x\u2421y");
    }

    @Test
    void thePreviewShowsC1BidiAndInvisibleCharactersAsTags() {
        assertThat(PasteInspection.of("ls " + RLO + "txt.sh").preview().text()).isEqualTo("ls <U+202E>txt.sh");
        assertThat(PasteInspection.of(CSI + "201~").preview().text()).isEqualTo("<U+009B>201~");
        assertThat(PasteInspection.of("\u0085").preview().text()).isEqualTo("<U+0085>");
        assertThat(PasteInspection.of("a" + ZWJ + "b\u200bc\ufeff").preview().text())
            .isEqualTo("a<U+200D>b<U+200B>c<U+FEFF>");
        assertThat(PasteInspection.of("x\u2028y").preview().text()).isEqualTo("x<U+2028>y");
        assertThat(PasteInspection.of("hi" + Character.toString(0xe0041)).preview().text())
            .isEqualTo("hi<U+E0041>");
        assertThat(PasteInspection.of("a\ud800b").preview().text()).isEqualTo("a<U+D800>b");
    }

    @Test
    void thePreviewKeepsTabsAndOrdinaryText() {
        assertThat(PasteInspection.of("a\tb \u00e4\u20ac" + EMOJI).preview().text())
            .isEqualTo("a\tb \u00e4\u20ac" + EMOJI);
    }

    @Test
    void thePreviewShowsEveryLineBreakAsOneLineFeedAndDropsATrailingOne() {
        PasteInspection.Preview preview = PasteInspection.of("a\r\nb\rc\nd\r\n").preview();
        assertThat(preview.text()).isEqualTo("a\nb\nc\nd");
        assertThat(preview.truncated()).isFalse();
        assertThat(PasteInspection.of("a\n\n").preview().text()).isEqualTo("a\n");
        assertThat(PasteInspection.of("\n").preview().text()).isEmpty();
    }

    @Test
    void thePreviewStopsAfterTheLineLimit() {
        PasteInspection.Preview preview = PasteInspection.of(lines(20)).preview(15, 4000);
        assertThat(preview.truncated()).isTrue();
        assertThat(preview.text()).isEqualTo(lines(15));

        assertThat(PasteInspection.of(lines(15)).preview(15, 4000))
            .isEqualTo(new PasteInspection.Preview(lines(15), false));
        assertThat(PasteInspection.of(lines(15) + "\n").preview(15, 4000))
            .isEqualTo(new PasteInspection.Preview(lines(15), false));
        assertThat(PasteInspection.of(lines(16)).preview()).isEqualTo(new PasteInspection.Preview(lines(15), true));
    }

    @Test
    void thePreviewStopsAtTheCharacterLimit() {
        assertThat(PasteInspection.of("abcdef").preview(15, 4)).isEqualTo(new PasteInspection.Preview("abcd", true));
        assertThat(PasteInspection.of("abcd").preview(15, 4)).isEqualTo(new PasteInspection.Preview("abcd", false));
        assertThat(PasteInspection.of("abcd\n").preview(15, 4)).isEqualTo(new PasteInspection.Preview("abcd", false));
        assertThat(PasteInspection.of("abcd\ne").preview(15, 4)).isEqualTo(new PasteInspection.Preview("abcd", true));
        String big = "x".repeat(10_000);
        PasteInspection.Preview preview = PasteInspection.of(big).preview();
        assertThat(preview.text()).hasLength(PasteInspection.DEFAULT_PREVIEW_CHARS);
        assertThat(preview.truncated()).isTrue();
    }

    @Test
    void thePreviewNeverCutsATagOrASurrogatePairInHalf() {
        assertThat(PasteInspection.of("ab" + RLO + "c").preview(15, 9))
            .isEqualTo(new PasteInspection.Preview("ab", true));
        assertThat(PasteInspection.of("ab" + RLO + "c").preview(15, 10))
            .isEqualTo(new PasteInspection.Preview("ab<U+202E>", true));
        assertThat(PasteInspection.of("a" + EMOJI).preview(15, 2)).isEqualTo(new PasteInspection.Preview("a", true));
        assertThat(PasteInspection.of("a" + EMOJI).preview(15, 3))
            .isEqualTo(new PasteInspection.Preview("a" + EMOJI, false));
    }

    @Test
    void nonPositiveLimitsShowAtLeastOneLineAndOneCharacter() {
        assertThat(PasteInspection.of("ab\ncd").preview(0, 0)).isEqualTo(new PasteInspection.Preview("a", true));
        assertThat(PasteInspection.of("a\nb").preview(-3, 100)).isEqualTo(new PasteInspection.Preview("a", true));
    }

    @Test
    void theDefaultPreviewLimitsAre15LinesAnd4000Characters() {
        assertThat(PasteInspection.DEFAULT_PREVIEW_LINES).isEqualTo(15);
        assertThat(PasteInspection.DEFAULT_PREVIEW_CHARS).isEqualTo(4000);
    }

    @Test
    void visibleFormLeavesOrdinaryCharactersAlone() {
        assertThat(PasteInspection.visibleForm('a')).isNull();
        assertThat(PasteInspection.visibleForm('\t')).isNull();
        assertThat(PasteInspection.visibleForm('\n')).isNull();
        assertThat(PasteInspection.visibleForm('\r')).isNull();
        assertThat(PasteInspection.visibleForm(0x00e4)).isNull();
        assertThat(PasteInspection.visibleForm(0x1f600)).isNull();
        assertThat(PasteInspection.visibleForm(0x1b)).isEqualTo("\u241b");
    }

    @Test
    void aMultiMegabyteInspectionStaysLinear() {
        String big = ("echo " + ESC + "[20" + ESC + "[201~1~ " + RLO + "\u00e4\r\n").repeat(200_000);
        long started = System.nanoTime();
        PasteInspection inspection = PasteInspection.of(big);
        PasteInspection.Preview preview = inspection.preview();
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(inspection.lineCount()).isEqualTo(200_000);
        assertThat(inspection.controlCharCount()).isEqualTo(400_000);
        assertThat(inspection.bidiControlCount()).isEqualTo(200_000);
        assertThat(inspection.containsBracketMarker()).isTrue();
        assertThat(preview.truncated()).isTrue();
        assertThat(elapsedMillis).isLessThan(5_000L);
    }
}
