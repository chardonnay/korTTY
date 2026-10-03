package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

class DisplayTextSanitizerTest {

    @Test
    void stripsEveryC0C1AndDelControl() {
        StringBuilder controls = new StringBuilder("a");
        for (int c = 0; c < 0x20; c++) {
            if (!isBreak(c)) {
                controls.append((char) c);
            }
        }
        controls.append('\u007F');
        for (int c = 0x80; c <= 0x9F; c++) {
            if (c != 0x85) {
                controls.append((char) c);
            }
        }
        controls.append('b');

        assertThat(DisplayTextSanitizer.stripControlsAndBidi(controls.toString())).isEqualTo("ab");
    }

    @Test
    void stripsEveryBidiControl() {
        int[] bidi = {0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069};
        for (int codePoint : bidi) {
            String text = "prod" + Character.toString(codePoint) + "db";
            String name = "U+" + Integer.toHexString(codePoint).toUpperCase(java.util.Locale.ROOT);
            assertWithMessage(name).that(DisplayTextSanitizer.stripControlsAndBidi(text)).isEqualTo("proddb");
            assertWithMessage(name).that(DisplayTextSanitizer.isBidiControl(codePoint)).isTrue();
        }
        // The classic spoof: an override that makes "exe.txt" read as "txt.exe".
        assertThat(DisplayTextSanitizer.stripControlsAndBidi("invoice‮txt.exe")).isEqualTo("invoicetxt.exe");
    }

    @Test
    void keepsLettersEmojiAndOtherFormatCharactersThatAreNotBidiControls() {
        String text = "Prüfung ✓ 生产 🚀 a‍b"; // ZWJ is a format character, not a bidi control
        assertThat(DisplayTextSanitizer.stripControlsAndBidi(text)).isSameInstanceAs(text);
        assertThat(DisplayTextSanitizer.isUnsafe(' ')).isFalse();
        assertThat(DisplayTextSanitizer.isUnsafe(' ')).isFalse();
    }

    @Test
    void lineBreaksAndTabsBecomeOneSpaceSoWordsStayApart() {
        assertThat(DisplayTextSanitizer.stripControlsAndBidi("web\tserver")).isEqualTo("web server");
        assertThat(DisplayTextSanitizer.stripControlsAndBidi("line1\r\nline2")).isEqualTo("line1 line2");
        assertThat(DisplayTextSanitizer.stripControlsAndBidi("a \nb")).isEqualTo("a b");
        assertThat(DisplayTextSanitizer.stripControlsAndBidi("\nlead")).isEqualTo(" lead");
        assertThat(DisplayTextSanitizer.stripControlsAndBidi("next\u0085line\u000Bvt\fff")).isEqualTo("next line vt ff");
    }

    @Test
    void escapeSequencesLoseTheirIntroducer() {
        // The text after ESC is harmless once the escape character itself is gone.
        assertThat(DisplayTextSanitizer.stripControlsAndBidi("\u001B[31mred\u001B[0m")).isEqualTo("[31mred[0m");
        assertThat(DisplayTextSanitizer.stripControlsAndBidi("\u009B31mred")).isEqualTo("31mred");
    }

    @Test
    void nullAndEmptyBecomeEmpty() {
        assertThat(DisplayTextSanitizer.stripControlsAndBidi(null)).isEmpty();
        assertThat(DisplayTextSanitizer.stripControlsAndBidi("")).isEmpty();
        assertThat(DisplayTextSanitizer.truncate(null, 5)).isEmpty();
        assertThat(DisplayTextSanitizer.sanitize(null, 5)).isEmpty();
        assertThat(DisplayTextSanitizer.toVisible(null)).isEmpty();
    }

    @Test
    void truncateCountsCharactersAndNeverSplitsASurrogatePair() {
        assertThat(DisplayTextSanitizer.truncate("abcdef", 3)).isEqualTo("abc");
        assertThat(DisplayTextSanitizer.truncate("abc", 3)).isEqualTo("abc");
        assertThat(DisplayTextSanitizer.truncate("abc", 10)).isEqualTo("abc");
        assertThat(DisplayTextSanitizer.truncate("abc", 0)).isEmpty();
        assertThat(DisplayTextSanitizer.truncate("abc", -1)).isEmpty();

        String rockets = "🚀🚀🚀"; // three characters, six chars
        assertThat(DisplayTextSanitizer.truncate(rockets, 2)).isEqualTo("🚀🚀");
        assertThat(DisplayTextSanitizer.truncate(rockets, 3)).isEqualTo(rockets);
        assertThat(DisplayTextSanitizer.truncate("a🚀b", 2)).isEqualTo("a🚀");
    }

    @Test
    void sanitizeStripsTrimsAndCapsWithoutATrailingSpace() {
        assertThat(DisplayTextSanitizer.sanitize("  ‮prod\u0007 db \n", 120)).isEqualTo("prod db");
        assertThat(DisplayTextSanitizer.sanitize("abc def", 4)).isEqualTo("abc");
        assertThat(DisplayTextSanitizer.sanitize("\u0000‮ \t", 120)).isEmpty();
    }

    @Test
    void toVisibleShowsControlPicturesAndTagsInsteadOfHidingThem() {
        assertThat(DisplayTextSanitizer.toVisible("a\u001Bb")).isEqualTo("a␛b");
        assertThat(DisplayTextSanitizer.toVisible("x\ny")).isEqualTo("x␊y");
        assertThat(DisplayTextSanitizer.toVisible("\u0000")).isEqualTo("␀");
        assertThat(DisplayTextSanitizer.toVisible("\u007F")).isEqualTo("␡");
        assertThat(DisplayTextSanitizer.toVisible("\u009B")).isEqualTo("<U+009B>");
        assertThat(DisplayTextSanitizer.toVisible("abc‮def")).isEqualTo("abc<U+202E>def");
        assertThat(DisplayTextSanitizer.toVisible("plain ✓ 🚀")).isEqualTo("plain ✓ 🚀");
    }

    private static boolean isBreak(int c) {
        return c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
    }
}
