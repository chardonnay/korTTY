package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class TerminalColorControlSequenceFilterTest {

    @Test
    void stripsAnsiColorCodesButKeepsTextAndReset() {
        TerminalColorControlSequenceFilter filter = new TerminalColorControlSequenceFilter();

        String result = filter.filter("a\u001B[31mred\u001B[0m b\u001B[44mblue-bg\u001B[39m");

        assertThat(result).isEqualTo("ared\u001B[0m bblue-bg");
    }

    @Test
    void preservesNonColorSgrAttributes() {
        TerminalColorControlSequenceFilter filter = new TerminalColorControlSequenceFilter();

        String result = filter.filter("\u001B[1;31;4mstrong\u001B[22;24m");

        assertThat(result).isEqualTo("\u001B[1;4mstrong\u001B[22;24m");
    }

    @Test
    void stripsIndexedAndTrueColorSequences() {
        TerminalColorControlSequenceFilter filter = new TerminalColorControlSequenceFilter();

        String result = filter.filter("\u001B[38;2;255;0;0;48;5;4;1mtext");

        assertThat(result).isEqualTo("\u001B[1mtext");
    }

    @Test
    void handlesSplitControlSequencesAcrossReads() {
        TerminalColorControlSequenceFilter filter = new TerminalColorControlSequenceFilter();

        assertThat(filter.filter("\u001B[3")).isEmpty();
        assertThat(filter.filter("1mred")).isEqualTo("red");
    }

    @Test
    void leavesNonSgrControlSequencesUntouched() {
        TerminalColorControlSequenceFilter filter = new TerminalColorControlSequenceFilter();

        String result = filter.filter("a\u001B[2Jb");

        assertThat(result).isEqualTo("a\u001B[2Jb");
    }
}
