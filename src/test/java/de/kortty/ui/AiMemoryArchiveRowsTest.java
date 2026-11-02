package de.kortty.ui;

import de.kortty.memory.MemoryArchive;
import de.kortty.memory.MemoryFormat;
import de.kortty.memory.MemoryScope;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The archive view's rows: the scope filter, the newest-first order and the first line of each text. */
class AiMemoryArchiveRowsTest {

    private static String fence(String id, String body, String created, String source) {
        return MemoryFormat.newEntry(id, body, LocalDate.parse(created), source);
    }

    @Test
    void theScopeFilterKeepsOnlyItsEntriesNewestFirst() {
        String text = MemoryArchive.appended("", MemoryScope.global(),
            List.of(fence("a00001", "Old global.", "2026-01-05", "chat")));
        text = MemoryArchive.appended(text, MemoryScope.connection("c1"),
            List.of(fence("a00002", "Connection.", "2026-03-01", "agent")));
        text = MemoryArchive.appended(text, MemoryScope.global(),
            List.of(fence("a00003", "New global.", "2026-02-01", "chat")));

        List<AiMemoryArchiveRows.Row> rows = AiMemoryArchiveRows.forScope(text, MemoryScope.global());

        assertThat(rows.stream().map(AiMemoryArchiveRows.Row::firstLine).toList())
            .containsExactly("New global.", "Old global.").inOrder();
    }

    @Test
    void entriesOfTheSameDayKeepTheLaterArchivedOneFirst() {
        String text = MemoryArchive.appended("", MemoryScope.global(),
            List.of(fence("b00001", "First archived.", "2026-02-01", "chat")));
        text = MemoryArchive.appended(text, MemoryScope.global(),
            List.of(fence("b00002", "Second archived.", "2026-02-01", "chat")));

        List<AiMemoryArchiveRows.Row> rows = AiMemoryArchiveRows.forScope(text, MemoryScope.global());

        assertThat(rows.stream().map(AiMemoryArchiveRows.Row::firstLine).toList())
            .containsExactly("Second archived.", "First archived.").inOrder();
    }

    @Test
    void oldBlocksWithoutAMarkerAreListedUnderTheGlobalMemoryOnly() {
        String legacy = fence("c00001", "Archived before scopes were recorded.", "2026-01-01", "chat");

        assertThat(AiMemoryArchiveRows.forScope(legacy, MemoryScope.global())).hasSize(1);
        assertThat(AiMemoryArchiveRows.forScope(legacy, MemoryScope.connection("x"))).isEmpty();
    }

    @Test
    void theRowShowsTheFirstLineAndKeepsTheWholeTextForTheTooltip() {
        String text = MemoryArchive.appended("", MemoryScope.global(),
            List.of(fence("d00001", "Line one.\nLine two.", "2026-02-01", "chat")));

        AiMemoryArchiveRows.Row row = AiMemoryArchiveRows.forScope(text, MemoryScope.global()).get(0);

        assertThat(row.firstLine()).isEqualTo("Line one.");
        assertThat(row.text()).isEqualTo("Line one.\nLine two.");
        assertThat(row.created()).isEqualTo("2026-02-01");
        assertThat(row.source()).isEqualTo("chat");
        assertThat(row.key()).isEqualTo(MemoryFormat.sha256(fence("d00001", "Line one.\nLine two.", "2026-02-01", "chat")));
    }

    @Test
    void firstLineToleratesEmptyAndMissingText() {
        assertThat(AiMemoryArchiveRows.firstLine("")).isEqualTo("");
        assertThat(AiMemoryArchiveRows.firstLine(null)).isEqualTo("");
        assertThat(AiMemoryArchiveRows.firstLine("  Padded.  \nNext")).isEqualTo("Padded.");
    }
}
