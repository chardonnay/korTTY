package de.kortty.isolation;

import static com.google.common.truth.Truth.assertThat;

import org.testng.annotations.Test;

/** The strict terminal mode drops what a hostile server could misuse and passes everything else unchanged. */
class StrictEscapeFilterTest {

    private static final String ESC = "\u001b";
    private static final String BEL = "\u0007";
    private static final String ST = ESC + "\\";

    private static String filter(String in) {
        return new StrictEscapeFilter().filter(in);
    }

    /** Feeds {@code in} one character per read, as a slow connection might deliver it. */
    private static String filterByteByByte(String in) {
        StrictEscapeFilter filter = new StrictEscapeFilter();
        StringBuilder out = new StringBuilder();
        for (char c : in.toCharArray()) {
            filter.filter(new char[] {c}, 0, 1, out);
        }
        filter.flush(out);
        return out.toString();
    }

    @Test
    void plainTextAndCsiSequencesPass() {
        String text = "hello " + ESC + "[1;31mred" + ESC + "[0m\r\n" + ESC + "7" + ESC + "8";
        assertThat(filter(text)).isEqualTo(text);
        assertThat(filterByteByByte(text)).isEqualTo(text);
    }

    @Test
    void clipboardWritesAreDropped() {
        String in = "a" + ESC + "]52;c;ZXZpbA==" + BEL + "b" + ESC + "]52;c;?" + ST + "c";
        assertThat(filter(in)).isEqualTo("abc");
        assertThat(filterByteByByte(in)).isEqualTo("abc");
    }

    @Test
    void webLinksPassAndOtherSchemesAreDropped() {
        String web = ESC + "]8;;https://example.org" + ST + "link" + ESC + "]8;;" + ST;
        assertThat(filter(web)).isEqualTo(web);
        String file = ESC + "]8;;file:///etc/passwd" + ST + "link" + ESC + "]8;;" + ST;
        assertThat(filter(file)).isEqualTo("link" + ESC + "]8;;" + ST);
    }

    @Test
    void reasonableTitlesPassAndSpoofingOnesAreDropped() {
        String title = ESC + "]0;user@host: ~" + BEL;
        assertThat(filter(title)).isEqualTo(title);
        String longTitle = ESC + "]2;" + "x".repeat(StrictEscapeFilter.MAX_TITLE_LENGTH + 1) + BEL;
        assertThat(filter(longTitle)).isEmpty();
        String withControl = ESC + "]2;bad\u0008title" + BEL;
        assertThat(filter(withControl)).isEmpty();
    }

    @Test
    void shellIntegrationAndWorkingDirectoryPass() {
        String marks = ESC + "]133;A" + BEL + "$ " + ESC + "]7;file://host/home/user" + ST;
        assertThat(filter(marks)).isEqualTo(marks);
    }

    @Test
    void deviceControlAndApplicationStringsAreDropped() {
        String in = "x" + ESC + "P+q544e" + ST + "y" + ESC + "_Gf=100;AAAA" + ST + "z" + ESC + "^pm" + ST + "!";
        assertThat(filter(in)).isEqualTo("xyz!");
        assertThat(filterByteByByte(in)).isEqualTo("xyz!");
    }

    @Test
    void eightBitIntroducersAreRecognized() {
        String in = "a\u009d52;c;Zm9v\u009cb\u0090q\u009cc";
        assertThat(filter(in)).isEqualTo("abc");
    }

    @Test
    void anOverlongOscIsDroppedWhole() {
        String in = "a" + ESC + "]1337;" + "y".repeat(StrictEscapeFilter.MAX_STRING_LENGTH + 10) + BEL + "b";
        assertThat(filter(in)).isEqualTo("ab");
    }

    @Test
    void cancelAbortsAString() {
        String in = "a" + ESC + "]0;title\u0018b";
        assertThat(filter(in)).isEqualTo("ab");
    }

    @Test
    void aLoneEscapeAtTheEndIsHandedBack() {
        StrictEscapeFilter filter = new StrictEscapeFilter();
        StringBuilder out = new StringBuilder();
        filter.filter(("x" + ESC).toCharArray(), 0, 2, out);
        assertThat(out.toString()).isEqualTo("x");
        filter.flush(out);
        assertThat(out.toString()).isEqualTo("x" + ESC);
    }

    @Test
    void itCountsWhatItDropped() {
        StrictEscapeFilter filter = new StrictEscapeFilter();
        filter.filter(ESC + "]52;c;Zg==" + BEL + ESC + "Pq" + ST + ESC + "]0;ok" + BEL);
        assertThat(filter.droppedCount()).isEqualTo(2);
    }
}
