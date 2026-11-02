package de.kortty.memory;

import de.kortty.memory.MemoryArchive.Archived;
import de.kortty.memory.MemoryDocument.KorttyEntry;
import de.kortty.memory.MemoryStore.Snapshot;
import de.kortty.memory.MemoryStore.WriteReason;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/** Restoring and deleting archived entries: the file gets only the chosen entries, and every write check still holds. */
class MemoryArchiveRestoreTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String EMPTY_DIGEST = MemoryFormat.sha256("");
    private static final LocalDate DAY = LocalDate.of(2026, 1, 5);

    private Path root;
    private Path dir;
    private MemoryStore store;
    private MemoryCleanupService cleanup;

    @BeforeMethod
    void createStore() throws IOException {
        root = Files.createTempDirectory("kortty-archive-restore-");
        dir = root.resolve("memory");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        store = new MemoryStore(dir, clock);
        cleanup = new MemoryCleanupService(clock);
    }

    @AfterMethod(alwaysRun = true)
    void deleteStore() throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static MemoryConsent consent(MemoryScope scope) {
        return MemoryConsent.grant(scope, NOW);
    }

    private static List<KorttyEntry> entriesOf(String text) {
        return MemoryFormat.parse(text).entries();
    }

    /** The archive keys of the entries that the archive holds for {@code scope}. */
    private Set<String> archiveKeys(MemoryScope scope) throws IOException {
        return MemoryArchive.entries(store.readArchive().text()).stream()
            .filter(item -> scope.equals(item.scope()))
            .map(Archived::key)
            .collect(Collectors.toSet());
    }

    /** Adds a korTTY entry with the rule text given, and archives it. The store writes the rule as a bullet. */
    private void archiveNewEntry(MemoryScope scope, String rule) throws IOException {
        Snapshot added = store.appendKorttyEntry(scope, rule, "chat", consent(scope));
        String id = entriesOf(added.text()).stream()
            .filter(entry -> entry.body().equals("- " + rule))
            .findFirst()
            .orElseThrow()
            .id();
        store.archiveKorttyEntries(scope, Set.of(id), consent(scope));
    }

    @Test
    void archivedEntriesGoBackIntoTheirFileAndLeaveTheArchive() throws IOException {
        MemoryScope global = MemoryScope.global();
        store.write(global, EMPTY_DIGEST, "Team rule.\n", WriteReason.USER_SAVE, null);
        archiveNewEntry(global, "First rule.");
        String userBefore = MemoryFormat.userSegments(store.read(global).text());
        assertThat(entriesOf(store.read(global).text())).isEmpty();
        assertThat(store.readArchive().text()).contains("<!-- kortty:archived scope=global -->");

        Snapshot restored = cleanup.restoreFromArchive(store, global, archiveKeys(global), 1500);

        assertThat(entriesOf(restored.text())).hasSize(1);
        assertThat(entriesOf(restored.text()).get(0).body()).isEqualTo("- First rule.");
        assertThat(restored.text()).contains("Team rule.");
        assertThat(MemoryFormat.userSegments(restored.text())).isEqualTo(userBefore);
        assertThat(MemoryArchive.entries(store.readArchive().text())).isEmpty();
    }

    @Test
    void aRestoreTouchesOnlyTheScopeOfTheArchivedEntries() throws IOException {
        MemoryScope global = MemoryScope.global();
        MemoryScope connection = MemoryScope.connection("conn-1");
        archiveNewEntry(global, "Global rule.");
        archiveNewEntry(connection, "Connection rule.");
        assertThat(MemoryArchive.entries(store.readArchive().text())).hasSize(2);

        cleanup.restoreFromArchive(store, global, archiveKeys(global), 1500);

        List<Archived> left = MemoryArchive.entries(store.readArchive().text());
        assertThat(left).hasSize(1);
        assertThat(left.get(0).scope()).isEqualTo(connection);
        assertThat(entriesOf(store.read(global).text())).hasSize(1);
        assertThat(entriesOf(store.read(connection).text())).isEmpty();
    }

    @Test
    void theConsentMustBeForTheScopeThatGetsTheEntries() throws IOException {
        MemoryScope global = MemoryScope.global();
        archiveNewEntry(global, "Global rule.");
        Set<String> keys = archiveKeys(global);

        assertThrows(MemoryInvariantException.class, () -> store.restoreArchivedEntries(global, keys,
            consent(MemoryScope.connection("conn-1")), 1500));
        assertThat(entriesOf(store.read(global).text())).isEmpty();
    }

    @Test
    void aReadOnlyMemoryRefusesRestoreAndDeleteAndChangesNothing() throws IOException {
        MemoryScope global = MemoryScope.global();
        archiveNewEntry(global, "Global rule.");
        Set<String> keys = archiveKeys(global);
        String archiveBefore = store.readArchive().text();
        store.setWriteGate(() -> false);

        assertThrows(MemoryReadOnlyException.class, () -> cleanup.restoreFromArchive(store, global, keys, 1500));
        assertThrows(MemoryReadOnlyException.class, () -> cleanup.deleteFromArchive(store, keys));

        assertThat(store.readArchive().text()).isEqualTo(archiveBefore);
        assertThat(entriesOf(store.read(global).text())).isEmpty();
    }

    @Test
    void theSoftLimitRefusesARestoreAndChangesNothing() throws IOException {
        MemoryScope global = MemoryScope.global();
        archiveNewEntry(global, "A rule that takes more than two estimated tokens to restore.");
        Set<String> keys = archiveKeys(global);
        String archiveBefore = store.readArchive().text();

        assertThrows(MemoryLimitException.class, () -> cleanup.restoreFromArchive(store, global, keys, 1));

        assertThat(store.readArchive().text()).isEqualTo(archiveBefore);
        assertThat(entriesOf(store.read(global).text())).isEmpty();
    }

    @Test
    void aSecretInAnArchivedEntryIsRefusedWithoutChangingAnything() throws IOException {
        MemoryScope global = MemoryScope.global();
        String fence = MemoryFormat.newEntry("sec001", "db_password=Hunter2xyz9", DAY, "chat");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(MemoryStore.ARCHIVE_FILE_NAME), MemoryArchive.appended("", global, List.of(fence)));
        Set<String> keys = archiveKeys(global);
        assertThat(keys).hasSize(1);

        assertThrows(MemorySecretException.class, () -> cleanup.restoreFromArchive(store, global, keys, 1500));

        assertThat(entriesOf(store.read(global).text())).isEmpty();
        assertThat(MemoryArchive.entries(store.readArchive().text())).hasSize(1);
    }

    @Test
    void aRetryAfterAFailedArchiveRemovalDoesNotDuplicateTheEntry() throws IOException {
        MemoryScope global = MemoryScope.global();
        Snapshot added = store.appendKorttyEntry(global, "Retry rule.", "chat", consent(global));
        KorttyEntry entry = entriesOf(added.text()).get(0);
        store.archiveKorttyEntries(global, Set.of(entry.id()), consent(global));
        // The file write that came before a failed archive removal: the fence is back in the file.
        store.write(global, store.read(global).sha256(), entry.raw(), WriteReason.USER_SAVE, null);

        cleanup.restoreFromArchive(store, global, archiveKeys(global), 1500);

        assertThat(entriesOf(store.read(global).text())).hasSize(1);
        assertThat(MemoryArchive.entries(store.readArchive().text())).isEmpty();
    }

    @Test
    void anEntryWhoseIdTheFileUsesComesBackWithANewId() throws IOException {
        MemoryScope global = MemoryScope.global();
        Snapshot other = store.appendKorttyEntry(global, "Other rule.", "chat", consent(global));
        String sharedId = entriesOf(other.text()).get(0).id();
        String archivedFence = MemoryFormat.newEntry(sharedId, "- Archived rule.", DAY, "chat");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(MemoryStore.ARCHIVE_FILE_NAME), MemoryArchive.appended("", global, List.of(archivedFence)));

        cleanup.restoreFromArchive(store, global, archiveKeys(global), 1500);

        List<KorttyEntry> entries = entriesOf(store.read(global).text());
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(KorttyEntry::id).distinct().count()).isEqualTo(2);
        assertThat(entries.stream().map(KorttyEntry::body).toList()).containsExactly("- Other rule.", "- Archived rule.")
            .inOrder();
    }

    @Test
    void deletingRemovesOnlyTheChosenEntriesFromTheArchive() throws IOException {
        MemoryScope global = MemoryScope.global();
        archiveNewEntry(global, "Keep this one.");
        archiveNewEntry(global, "Delete this one.");
        List<Archived> before = MemoryArchive.entries(store.readArchive().text());
        String keyToDelete = before.stream()
            .filter(item -> item.entry().body().equals("- Delete this one."))
            .findFirst()
            .orElseThrow()
            .key();

        int removed = cleanup.deleteFromArchive(store, Set.of(keyToDelete));

        assertThat(removed).isEqualTo(1);
        List<Archived> left = MemoryArchive.entries(store.readArchive().text());
        assertThat(left).hasSize(1);
        assertThat(left.get(0).entry().body()).isEqualTo("- Keep this one.");
        assertThat(entriesOf(store.read(global).text())).isEmpty();
    }

    @Test
    void keysThatAreNotInTheArchiveChangeNothing() throws IOException {
        MemoryScope global = MemoryScope.global();
        archiveNewEntry(global, "Global rule.");
        String archiveBefore = store.readArchive().text();

        Snapshot unchanged = cleanup.restoreFromArchive(store, global, Set.of("0123456789abcdef"), 1500);

        assertThat(unchanged.text()).isEmpty();
        assertThat(store.readArchive().text()).isEqualTo(archiveBefore);
        assertThat(cleanup.deleteFromArchive(store, Set.of("0123456789abcdef"))).isEqualTo(0);
    }
}
