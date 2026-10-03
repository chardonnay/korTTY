package de.kortty.core.highlight;

import static com.google.common.truth.Truth.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.testng.annotations.Test;

class LogicalLineProjectionTest {

    /** {@code CharUtils.DWC} in SithTermFX: the second cell of a double-width character. */
    private static final char DWC = '';

    @Test
    void aPlainRowProjectsOneToOne() {
        LogicalLineProjection projection = LogicalLineProjection.of(List.of("ERROR disk full"));
        assertThat(projection.text()).isEqualTo("ERROR disk full");
        assertThat(projection.length()).isEqualTo(15);
        for (int i = 0; i < projection.length(); i++) {
            assertThat(projection.row(i)).isEqualTo(0);
            assertThat(projection.column(i)).isEqualTo(i);
            assertThat(projection.cells(i)).isEqualTo(1);
        }
        assertThat(projection.rowsUsed()).isEqualTo(1);
        assertThat(projection.truncated()).isFalse();
    }

    @Test
    void aWideCharacterLosesItsPlaceholderAndMapsBackToBothCells() {
        // 東京! as the emulator stores it: each CJK char is followed by its placeholder cell.
        String row = "東" + DWC + "京" + DWC + "!";
        LogicalLineProjection projection = LogicalLineProjection.of(List.of(row));

        assertThat(projection.text()).isEqualTo("東京!");
        assertThat(projection.column(0)).isEqualTo(0);
        assertThat(projection.cells(0)).isEqualTo(2);
        assertThat(projection.column(1)).isEqualTo(2);
        assertThat(projection.cells(1)).isEqualTo(2);
        assertThat(projection.column(2)).isEqualTo(4);
        assertThat(projection.cells(2)).isEqualTo(1);

        int[] owners = {0, 0, HighlightMatcher.NO_OWNER};
        assertThat(projection.cellOwners(owners, 0, row.length()))
            .asList().containsExactly(0, 0, 0, 0, -1).inOrder();
    }

    @Test
    void anEmojiKeepsItsSurrogatePairWhereverTheEmulatorPutTheirPlaceholder() {
        String fire = "🔥";
        // SithTerminal.newCharBuf inserts the placeholder after the high surrogate.
        String row = "x" + fire.charAt(0) + DWC + fire.charAt(1) + "y";
        LogicalLineProjection projection = LogicalLineProjection.of(List.of(row));

        assertThat(projection.text()).isEqualTo("x" + fire + "y");
        assertThat(projection.column(1)).isEqualTo(1);
        assertThat(projection.cells(1)).isEqualTo(2);
        assertThat(projection.column(2)).isEqualTo(3);
        assertThat(projection.cells(2)).isEqualTo(1);
        int[] owners = {-1, 4, 4, -1};
        assertThat(projection.cellOwners(owners, 0, row.length()))
            .asList().containsExactly(-1, 4, 4, 4, -1).inOrder();
    }

    @Test
    void nulCellsAreLeftOutButKeepTheirColumns() {
        LogicalLineProjection projection = LogicalLineProjection.of(List.of("ab\0\0cd"));
        assertThat(projection.text()).isEqualTo("abcd");
        assertThat(projection.column(2)).isEqualTo(4);
        assertThat(projection.column(3)).isEqualTo(5);
    }

    @Test
    void aSoftWrappedLineJoinsWithoutNulPadding() {
        // The first row could not fit the wide char, so its last cell stayed NUL.
        LogicalLineProjection projection = LogicalLineProjection.of(List.of("connection ref\0", "used"));
        assertThat(projection.text()).isEqualTo("connection refused");
        int u = projection.text().indexOf("used");
        assertThat(projection.row(u)).isEqualTo(1);
        assertThat(projection.column(u)).isEqualTo(0);
        assertThat(projection.row(u - 1)).isEqualTo(0);
        assertThat(projection.column(u - 1)).isEqualTo(13);
        assertThat(projection.rowsUsed()).isEqualTo(2);

        int[] owners = new int[projection.length()];
        java.util.Arrays.fill(owners, 2);
        assertThat(projection.cellOwners(owners, 0, 15))
            .asList().containsExactly(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, -1).inOrder();
        assertThat(projection.cellOwners(owners, 1, 5)).asList().containsExactly(2, 2, 2, 2, -1).inOrder();
        assertThat(projection.cellOwners(owners, 7, 3)).asList().containsExactly(-1, -1, -1);
    }

    @Test
    void aPlaceholderWithoutALeadOnItsOwnRowIsNotGluedToThePreviousRow() {
        LogicalLineProjection projection = LogicalLineProjection.of(List.of("ab", DWC + "c"));
        assertThat(projection.text()).isEqualTo("abc");
        assertThat(projection.cells(1)).isEqualTo(1);
        assertThat(projection.row(2)).isEqualTo(1);
        assertThat(projection.column(2)).isEqualTo(1);
    }

    @Test
    void nullAndEmptyRowsAreTolerated() {
        List<String> rows = new ArrayList<>();
        rows.add(null);
        rows.add("");
        rows.add("ok");
        LogicalLineProjection projection = LogicalLineProjection.of(rows);
        assertThat(projection.text()).isEqualTo("ok");
        assertThat(projection.row(0)).isEqualTo(2);
        assertThat(LogicalLineProjection.of(List.of()).length()).isEqualTo(0);
    }

    @Test
    void joinsAtMostSixtyFourRows() {
        List<String> rows = Collections.nCopies(LogicalLineProjection.MAX_ROWS + 6, "ab");
        LogicalLineProjection projection = LogicalLineProjection.of(rows);
        assertThat(projection.rowsUsed()).isEqualTo(64);
        assertThat(projection.length()).isEqualTo(128);
        assertThat(projection.truncated()).isTrue();

        LogicalLineProjection exact = LogicalLineProjection.of(Collections.nCopies(64, "ab"));
        assertThat(exact.truncated()).isFalse();
    }

    @Test
    void projectsAtMostEightThousandOneHundredNinetyTwoCharacters() {
        List<String> rows = Collections.nCopies(3, "x".repeat(3_000));
        LogicalLineProjection projection = LogicalLineProjection.of(rows);
        assertThat(projection.length()).isEqualTo(LogicalLineProjection.MAX_CHARS);
        assertThat(projection.length()).isEqualTo(8_192);
        assertThat(projection.truncated()).isTrue();
        assertThat(projection.row(8_191)).isEqualTo(2);
        assertThat(projection.column(8_191)).isEqualTo(2_191);

        LogicalLineProjection exact = LogicalLineProjection.of(List.of("y".repeat(8_192)));
        assertThat(exact.truncated()).isFalse();
    }

    @Test
    void cellOwnersToleratesAShorterOwnerArrayAndAShorterRow() {
        LogicalLineProjection projection = LogicalLineProjection.of(List.of("abcd"));
        assertThat(projection.cellOwners(new int[] {1, 1}, 0, 4)).asList().containsExactly(1, 1, -1, -1).inOrder();
        assertThat(projection.cellOwners(new int[] {1, 1, 1, 1}, 0, 2)).asList().containsExactly(1, 1).inOrder();
    }
}
