package de.kortty.memory;

import de.kortty.memory.MemoryArchive.Archived;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/** The archive format: the scope of each block, the fences kept byte for byte, and the removal of chosen blocks. */
class MemoryArchiveTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 5);

    private static String fence(String id, String body) {
        return MemoryFormat.newEntry(id, body, DAY, "chat");
    }

    @Test
    void theScopeOfEachBlockComesFromItsMarker() {
        String text = MemoryArchive.appended("", MemoryScope.global(), List.of(fence("aaa111", "Global rule.")));
        text = MemoryArchive.appended(text, MemoryScope.connection("conn-1"), List.of(fence("bbb222", "Connection rule.")));

        List<Archived> entries = MemoryArchive.entries(text);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).scope()).isEqualTo(MemoryScope.global());
        assertThat(entries.get(0).entry().body()).isEqualTo("Global rule.");
        assertThat(entries.get(1).scope()).isEqualTo(MemoryScope.connection("conn-1"));
        assertThat(entries.get(1).entry().body()).isEqualTo("Connection rule.");
    }

    @Test
    void aFenceWithoutMarkerIsAnOldBlockOfTheGlobalMemory() {
        String text = fence("ccc333", "Archived before scopes were recorded.");

        List<Archived> entries = MemoryArchive.entries(text);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).scope()).isEqualTo(MemoryScope.global());
    }

    @Test
    void aMarkerOfAnUnknownScopeKeepsTheEntryButNamesNoFile() {
        String text = "<!-- kortty:archived scope=somethingNew -->\n" + fence("ddd444", "From a newer version.");

        List<Archived> entries = MemoryArchive.entries(text);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).scope()).isNull();
    }

    @Test
    void theConnectionIdSurvivesTheMarkerEvenWithSpacesAndUnicode() {
        MemoryScope scope = MemoryScope.connection("Büro Server #2");

        String text = MemoryArchive.appended("", scope, List.of(fence("eee555", "Rule.")));

        assertThat(MemoryArchive.entries(text).get(0).scope()).isEqualTo(scope);
    }

    @Test
    void appendedFencesKeepTheirExactBytes() {
        String fence = fence("fff666", "Keep the exact text.");

        String text = MemoryArchive.appended("", MemoryScope.global(), List.of(fence));

        assertThat(text).contains(fence);
        assertThat(text.startsWith("<!-- kortty:archived scope=global -->\n")).isTrue();
    }

    @Test
    void appendingAFenceThatIsNotOneEntryIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> MemoryArchive.appended("", MemoryScope.global(), List.of("just text\n")));
    }

    @Test
    void withoutRemovesOnlyTheChosenBlocksAndTheirMarkers() {
        String first = fence("ggg777", "First.");
        String second = fence("hhh888", "Second.");
        String text = MemoryArchive.appended("", MemoryScope.global(), List.of(first, second));
        String key = MemoryArchive.entries(text).get(0).key();

        String rest = MemoryArchive.without(text, Set.of(key));

        assertThat(MemoryArchive.entries(rest)).hasSize(1);
        assertThat(MemoryArchive.entries(rest).get(0).entry().body()).isEqualTo("Second.");
        assertThat(rest).doesNotContain("First.");
        assertThat(rest).contains(second);
        assertThat(rest.split("kortty:archived", -1)).hasLength(2);
    }

    @Test
    void removingEverythingLeavesAnEmptyArchive() {
        String text = MemoryArchive.appended("", MemoryScope.global(), List.of(fence("iii999", "Only.")));
        String key = MemoryArchive.entries(text).get(0).key();

        assertThat(MemoryArchive.without(text, Set.of(key))).isEmpty();
    }

    @Test
    void textThatIsNoBlockStaysWhenBlocksAreRemoved() {
        String note = "A note the user wrote in the archive file.\n";
        String text = note + "\n" + MemoryArchive.appended("", MemoryScope.global(), List.of(fence("jjj000", "Gone.")));
        String key = MemoryArchive.entries(text).get(0).key();

        String rest = MemoryArchive.without(text, Set.of(key));

        assertThat(rest).isEqualTo(note);
    }

    @Test
    void theKeyIsTheDigestOfTheFence() {
        String fence = fence("kkk111", "Key.");
        String text = MemoryArchive.appended("", MemoryScope.global(), List.of(fence));

        assertThat(MemoryArchive.entries(text).get(0).key()).isEqualTo(MemoryFormat.sha256(fence));
    }
}
