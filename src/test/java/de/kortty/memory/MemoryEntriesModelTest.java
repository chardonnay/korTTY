package de.kortty.memory;

import de.kortty.memory.MemoryBlockBuilder.Input;
import de.kortty.memory.MemoryEntriesModel.Kind;
import de.kortty.memory.MemoryEntriesModel.Row;
import de.kortty.model.AiTokenizerType;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class MemoryEntriesModelTest {

    private static String entry(String id, String created, String source, String body) {
        return MemoryFormat.newEntry(id, body, LocalDate.parse(created), source);
    }

    @Test
    void userParagraphsAndKorttyEntriesFormRowsInFileOrder() {
        String text = "Use tabs.\n\nKeep logs short.\n\n" + entry("aa11", "2026-11-02", "chat", "- Perl: use state.");

        List<Row> rows = MemoryEntriesModel.rows(text, MemoryBlock.empty(), false);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).kind()).isEqualTo(Kind.USER_TEXT);
        assertThat(rows.get(0).text()).isEqualTo("Use tabs.");
        assertThat(rows.get(1).text()).isEqualTo("Keep logs short.");
        assertThat(rows.get(2).kind()).isEqualTo(Kind.KORTTY_ENTRY);
        assertThat(rows.get(2).isUserText()).isFalse();
        assertThat(rows.get(2).entryId()).isEqualTo("aa11");
        assertThat(rows.get(2).created()).isEqualTo("2026-11-02");
        assertThat(rows.get(2).source()).isEqualTo("chat");
        assertThat(rows.get(2).text()).isEqualTo("- Perl: use state.");
    }

    @Test
    void identicalUserParagraphsAreToldApartByOccurrence() {
        List<Row> rows = MemoryEntriesModel.rows("Same.\n\nOther.\n\nSame.\n", MemoryBlock.empty(), false);

        assertThat(rows.get(0).occurrence()).isEqualTo(0);
        assertThat(rows.get(1).occurrence()).isEqualTo(0);
        assertThat(rows.get(2).occurrence()).isEqualTo(1);
    }

    @Test
    void nothingIsSentWithoutABlock() {
        String text = "Use tabs.\n\n" + entry("aa11", "2026-11-02", "chat", "- Perl: use state.");

        List<Row> rows = MemoryEntriesModel.rows(text, MemoryBlock.empty(), false);

        assertThat(rows).isNotEmpty();
        assertThat(rows.stream().noneMatch(Row::sent)).isTrue();
    }

    @Test
    void rowsFollowTheBlockOfTheirOwnScope() {
        String globalText = "Global note.\n\n" + entry("g001", "2026-11-02", "chat", "- Global rule.");
        String connectionText = "Host note.\n\n" + entry("c001", "2026-11-02", "agent", "- Host rule.");
        MemoryBlock connectionBlock = MemoryBlockBuilder.build(
            new Input(List.of(), globalText, connectionText, List.of()), 1500, AiTokenizerType.ESTIMATE);

        List<Row> connectionRows = MemoryEntriesModel.rows(connectionText, connectionBlock, true);
        List<Row> globalRows = MemoryEntriesModel.rows(globalText, connectionBlock, false);

        assertThat(connectionRows.stream().map(Row::sent)).containsExactly(true, true).inOrder();
        assertThat(globalRows.stream().map(Row::sent)).containsExactly(true, true).inOrder();
        MemoryBlock globalOnly = MemoryBlockBuilder.build(
            new Input(List.of(), globalText, null, List.of()), 1500, AiTokenizerType.ESTIMATE);
        assertThat(MemoryEntriesModel.rows(connectionText, globalOnly, true).stream().noneMatch(Row::sent)).isTrue();
    }

    @Test
    void aTightBudgetLeavesSomeKorttyEntriesUnsent() {
        StringBuilder text = new StringBuilder("Keep it short.\n\n");
        for (int index = 1; index <= 8; index++) {
            text.append(entry(String.format("e%02d", index), String.format("2026-0%d-01", Math.min(index, 9)),
                "chat", "- Rule number " + index + " about the deployment checklist for the staging hosts."));
            text.append("\n");
        }
        MemoryBlock block = MemoryBlockBuilder.build(
            new Input(List.of(), text.toString(), null, List.of()), 150, AiTokenizerType.ESTIMATE);

        List<Row> rows = MemoryEntriesModel.rows(text.toString(), block, false);

        for (Row row : rows) {
            boolean expected = row.isUserText() || block.includedEntryIds().contains(row.entryId());
            assertThat(row.sent()).isEqualTo(expected);
        }
        assertThat(rows.stream().anyMatch(row -> !row.isUserText() && !row.sent())).isTrue();
        assertThat(rows.stream().anyMatch(row -> !row.isUserText() && row.sent())).isTrue();
    }

    @Test
    void blankKorttyEntriesAreLeftOut() {
        String text = "Note.\n\n" + entry("b001", "2026-11-02", "chat", "");

        List<Row> rows = MemoryEntriesModel.rows(text, MemoryBlock.empty(), false);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isUserText()).isTrue();
    }

    @Test
    void anEmptyFileHasNoRows() {
        assertThat(MemoryEntriesModel.rows("", MemoryBlock.empty(), false)).isEmpty();
        assertThat(MemoryEntriesModel.rows(null, null, true)).isEmpty();
    }
}
