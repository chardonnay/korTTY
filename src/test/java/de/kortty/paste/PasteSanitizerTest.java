package de.kortty.paste;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.util.List;
import java.util.Random;
import org.testng.annotations.Test;

class PasteSanitizerTest {

    private static final String ESC = "\u001b";

    private static final String CSI = "\u009b";

    private static final List<String> MARKERS =
        List.of(ESC + "[200~", ESC + "[201~", CSI + "200~", CSI + "201~");

    /** The specification: remove markers again and again until none is left. */
    private static String stripNaively(String text) {
        String previous;
        String current = text;
        do {
            previous = current;
            for (String marker : MARKERS) {
                current = current.replace(marker, "");
            }
        } while (!current.equals(previous));
        return current;
    }

    @Test
    void allFourMarkersAreRemoved() {
        assertThat(PasteSanitizer.stripBracketMarkers("a" + ESC + "[200~b")).isEqualTo("ab");
        assertThat(PasteSanitizer.stripBracketMarkers("a" + ESC + "[201~b")).isEqualTo("ab");
        assertThat(PasteSanitizer.stripBracketMarkers("a" + CSI + "200~b")).isEqualTo("ab");
        assertThat(PasteSanitizer.stripBracketMarkers("a" + CSI + "201~b")).isEqualTo("ab");
        assertThat(PasteSanitizer.stripBracketMarkers(ESC + "[200~" + ESC + "[201~" + CSI + "200~" + CSI + "201~"))
            .isEmpty();
    }

    @Test
    void anEmbeddedEndMarkerCannotBreakOutOfThePaste() {
        assertThat(PasteSanitizer.stripBracketMarkers("echo safe" + ESC + "[201~\nrm -rf ~\n"))
            .isEqualTo("echo safe\nrm -rf ~\n");
    }

    @Test
    void markersThatOnlyAppearOnceAnInnerOneIsGoneAreRemovedToo() {
        assertThat(PasteSanitizer.stripBracketMarkers(ESC + "[20" + ESC + "[201~1~")).isEmpty();
        assertThat(PasteSanitizer.stripBracketMarkers(CSI + "20" + CSI + "200~1~")).isEmpty();
        assertThat(PasteSanitizer.stripBracketMarkers(ESC + "[20" + CSI + "201~0~")).isEmpty();
        assertThat(PasteSanitizer.stripBracketMarkers("x" + ESC + "[2" + ESC + "[200~01~y")).isEqualTo("xy");
    }

    @Test
    void otherSequencesAndPartialMarkersAreKept() {
        for (String kept : List.of(ESC + "[202~", ESC + "[2004h", ESC + "[1;5D", ESC + "[200", ESC + "[20~",
                "[201~", "201~", ESC + CSI + "201", ESC + "]201~", ESC + "x" + "[201~", "~~")) {
            assertThat(PasteSanitizer.stripBracketMarkers(kept)).isEqualTo(kept);
        }
    }

    @Test
    void textWithoutAnIntroducerIsReturnedAsIs() {
        String plain = "ls -l\nfor f in *; do echo ~/$f; done\r\n";
        assertThat(PasteSanitizer.stripBracketMarkers(plain)).isSameInstanceAs(plain);
        String noMarker = "colour " + ESC + "[31mred" + ESC + "[0m ~";
        assertThat(PasteSanitizer.stripBracketMarkers(noMarker)).isSameInstanceAs(noMarker);
    }

    @Test
    void nullAndEmpty() {
        assertThat(PasteSanitizer.stripBracketMarkers(null)).isEmpty();
        assertThat(PasteSanitizer.stripBracketMarkers("")).isEmpty();
    }

    @Test
    void matchesRepeatedRemovalOnRandomInput() {
        char[] alphabet = {'\u001b', '\u009b', '[', '2', '0', '1', '~', 'a'};
        Random random = new Random(20_04L);
        for (int round = 0; round < 20_000; round++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(40);
            for (int i = 0; i < length; i++) {
                text.append(alphabet[random.nextInt(alphabet.length)]);
            }
            String input = text.toString();
            assertWithMessage("input %s", input.replace("\u001b", "ESC").replace("\u009b", "CSI"))
                .that(PasteSanitizer.stripBracketMarkers(input))
                .isEqualTo(stripNaively(input));
        }
    }

    @Test
    void twoMegabytesOfNestedHalfMarkersAreStrippedInLinearTime() {
        // ESC[20 ESC[20 ... 1~ 1~: each removal only reveals the next marker, so stripping by repeated
        // String.replace would need one full pass per nesting level, hundreds of thousands of them.
        int depth = 2 * 1024 * 1024 / 6;
        String nested = (ESC + "[20").repeat(depth) + "1~".repeat(depth);
        String mixed = (CSI + "20" + ESC + "[20").repeat(depth / 2) + "0~1~".repeat(depth / 2);

        long start = System.nanoTime();
        String strippedNested = PasteSanitizer.stripBracketMarkers(nested);
        String strippedMixed = PasteSanitizer.stripBracketMarkers("keep" + mixed + "this");
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(nested.length()).isAtLeast(2 * 1024 * 1024 - 6);
        assertThat(strippedNested).isEmpty();
        assertThat(strippedMixed).isEqualTo("keepthis");
        assertWithMessage("stripping took %s ms", millis).that(millis).isLessThan(2_000L);
    }
}
