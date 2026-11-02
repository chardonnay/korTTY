package de.kortty.memory;

import de.kortty.memory.MemoryEntriesModel.Row;
import de.kortty.memory.MemoryRowEdit.RowNotFoundException;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class MemoryRowEditTest {

    private static Row row(String text, int occurrence, int position) {
        return new Row(MemoryEntriesModel.Kind.USER_TEXT, text, null, null, null, occurrence, position, false);
    }

    private static List<Row> userRows(String text) {
        return MemoryEntriesModel.rows(text, null, false).stream().filter(Row::isUserText).toList();
    }

    @Test
    void replacesTheParagraphAndKeepsTheRest() {
        String text = "Use tabs.\n\nKeep logs short.\n";

        String edited = MemoryRowEdit.replaceUserParagraph(text, userRows(text).get(1), "Keep logs very short.");

        assertThat(edited).isEqualTo("Use tabs.\n\nKeep logs very short.\n");
    }

    @Test
    void deletesAMiddleParagraphWithItsSeparator() {
        String text = "A\n\nB\n\nC";

        assertThat(MemoryRowEdit.deleteUserParagraph(text, userRows(text).get(1))).isEqualTo("A\n\nC");
    }

    @Test
    void deletesTheLastParagraphWithoutLeavingEmptyLines() {
        String text = "A\n\nB\n";

        assertThat(MemoryRowEdit.deleteUserParagraph(text, userRows(text).get(1))).isEqualTo("A\n");
    }

    @Test
    void deletesTheFirstParagraph() {
        String text = "A\n\nB";

        assertThat(MemoryRowEdit.deleteUserParagraph(text, userRows(text).get(0))).isEqualTo("B");
    }

    @Test
    void identicalParagraphsAreEditedByTheirOwnOccurrence() {
        String text = "Same.\n\nOther.\n\nSame.\n";

        String deleted = MemoryRowEdit.deleteUserParagraph(text, userRows(text).get(2));

        assertThat(deleted).isEqualTo("Same.\n\nOther.\n");
    }

    @Test
    void aParagraphInsideALongerOneIsNotFound() {
        String text = "Use tabs. More detail follows here.\n";

        expectThrows(RowNotFoundException.class,
            () -> MemoryRowEdit.replaceUserParagraph(text, row("Use tabs.", 0, 0), "x"));
    }

    @Test
    void aParagraphOfAnotherLineIsNotFound() {
        String text = "First line\nUse tabs.\n\nLast.\n";

        expectThrows(RowNotFoundException.class,
            () -> MemoryRowEdit.replaceUserParagraph(text, row("Use tabs.", 0, 0), "x"));
    }

    @Test
    void editingAKorttyEntryMakesItUserText() {
        String fence = MemoryFormat.newEntry("ab12", "- Perl: use state.", LocalDate.parse("2026-11-02"), "chat");
        String text = "Notes.\n\n" + fence;

        String edited = MemoryRowEdit.replaceKorttyEntry(text, "ab12", "- Perl: use state, never closures.");

        assertThat(MemoryFormat.parse(edited).entries()).isEmpty();
        assertThat(edited).contains("- Perl: use state, never closures.\n");
        assertThat(edited).doesNotContain("kortty:entry");
    }

    @Test
    void deletingAKorttyEntryKeepsTheUserText() {
        String fence = MemoryFormat.newEntry("ab12", "- Perl: use state.", LocalDate.parse("2026-11-02"), "chat");
        String text = "Notes.\n\n" + fence;

        String deleted = MemoryRowEdit.deleteKorttyEntry(text, "ab12");

        assertThat(MemoryFormat.parse(deleted).entries()).isEmpty();
        assertThat(deleted).isEqualTo("Notes.\n");
        assertThat(MemoryFormat.userSegments(deleted)).isEqualTo(MemoryFormat.userSegments(text));
    }

    @Test
    void anUnknownEntryIsNotFound() {
        expectThrows(RowNotFoundException.class, () -> MemoryRowEdit.deleteKorttyEntry("Notes.\n", "zz99"));
    }
}
