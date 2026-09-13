package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;
import org.testng.annotations.Test;

class ScreenSnapshotTest {

    @Test
    void ofTextSplitsRowsDropsTerminatingNewlineAndRightTrims() {
        ScreenSnapshot snapshot = ScreenSnapshot.ofText("first   \n  second\n\nlast\n", null, false);

        assertThat(snapshot.lines()).containsExactly("first", "  second", "", "last").inOrder();
        assertThat(snapshot.rows()).isEqualTo(4);
        // columns = longest RAW row, i.e. before right-trimming ("first   " has 8 chars)
        assertThat(snapshot.columns()).isEqualTo(8);
    }

    @Test
    void ofTextKeepsAnEmptyMiddleRowButNotOnlyTheTrailingOne() {
        ScreenSnapshot snapshot = ScreenSnapshot.ofText("a\n\nb", null, false);
        assertThat(snapshot.lines()).containsExactly("a", "", "b").inOrder();
    }

    @Test
    void ofTextWithNullOrEmptyTextIsEmpty() {
        assertThat(ScreenSnapshot.ofText(null, null, false).lines()).isEmpty();
        assertThat(ScreenSnapshot.ofText("", null, false).lines()).isEmpty();
        assertThat(ScreenSnapshot.EMPTY.lines()).isEmpty();
        assertThat(ScreenSnapshot.EMPTY.rows()).isEqualTo(0);
        assertThat(ScreenSnapshot.EMPTY.hasOscTitle()).isFalse();
    }

    @Test
    void ofLinesNormalisesPaddedTerminalRows() {
        List<String> padded = List.of("$ ls      ", "file.txt  ", "          ", "");
        ScreenSnapshot snapshot = ScreenSnapshot.ofLines(padded, 10, "Terminal", false);

        assertThat(snapshot.lines()).containsExactly("$ ls", "file.txt", "").inOrder();
        assertThat(snapshot.columns()).isEqualTo(10);
        assertThat(snapshot.rows()).isEqualTo(3);
    }

    @Test
    void defaultBlankAndNullTitlesMeanNoOscTitle() {
        assertThat(ScreenSnapshot.ofText("x", "Terminal", false).oscTitle()).isNull();
        assertThat(ScreenSnapshot.ofText("x", "   ", false).oscTitle()).isNull();
        assertThat(ScreenSnapshot.ofText("x", null, false).oscTitle()).isNull();
        assertThat(ScreenSnapshot.ofText("x", "Terminal", false).hasOscTitle()).isFalse();
    }

    @Test
    void realTitlesAndAlternateScreenFlagArePreserved() {
        ScreenSnapshot snapshot = ScreenSnapshot.ofText("x", "✳ Claude Code", true);
        assertThat(snapshot.oscTitle()).isEqualTo("✳ Claude Code");
        assertThat(snapshot.hasOscTitle()).isTrue();
        assertThat(snapshot.alternateScreen()).isTrue();
    }

    @Test
    void bottomNonEmptyLinesSkipsBlankRowsAndKeepsScreenOrder() {
        ScreenSnapshot snapshot = ScreenSnapshot.ofText("one\ntwo\n\nthree\n   \nfour\n\n", null, false);

        assertThat(snapshot.bottomNonEmptyLines(3)).containsExactly("two", "three", "four").inOrder();
        assertThat(snapshot.bottomNonEmptyLines(1)).containsExactly("four");
        assertThat(snapshot.bottomNonEmptyLines(10)).containsExactly("one", "two", "three", "four").inOrder();
    }

    @Test
    void bottomNonEmptyLinesWithZeroOrNegativeCountReturnsEveryRow() {
        ScreenSnapshot snapshot = ScreenSnapshot.ofText("one\n\ntwo", null, false);
        assertThat(snapshot.bottomNonEmptyLines(0)).containsExactly("one", "", "two").inOrder();
        assertThat(snapshot.bottomNonEmptyLines(-1)).containsExactly("one", "", "two").inOrder();
    }

    @Test
    void linesAreImmutable() {
        ScreenSnapshot snapshot = ScreenSnapshot.ofText("a\nb", null, false);
        org.testng.Assert.expectThrows(UnsupportedOperationException.class, () -> snapshot.lines().add("c"));
    }

    @Test
    void joinedUsesNewlines() {
        assertThat(ScreenSnapshot.joined(List.of("a", "b"))).isEqualTo("a\nb");
        assertThat(ScreenSnapshot.joined(List.of())).isEmpty();
    }
}
