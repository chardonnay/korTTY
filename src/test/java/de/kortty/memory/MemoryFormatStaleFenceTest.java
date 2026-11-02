package de.kortty.memory;

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
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * An entry the user edited keeps its fence header with the old hash. That header is user text now, and it must not stop
 * the later writes of the file: a korTTY append, a save of the whole file, and the header itself stays as the user left it.
 */
class MemoryFormatStaleFenceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String EMPTY_DIGEST = MemoryFormat.sha256("");
    private static final String HEX_64 = "3f9a7c2e1b8d4f6a9c0e5b7d1f3a9c2e8b4d6f0a1c3e5b7d9f2a4c6e8b0d1f3a";

    private Path root;
    private MemoryStore store;

    @BeforeMethod
    void createStore() throws IOException {
        root = Files.createTempDirectory("kortty-stale-fence-");
        store = new MemoryStore(root.resolve("memory"), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterMethod(alwaysRun = true)
    void deleteStore() throws IOException {
        if (root == null) {
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

    @Test
    void aStaleFenceHeaderDoesNotBlockLaterWritesAndStaysUserText() throws IOException {
        MemoryScope global = MemoryScope.global();
        store.write(global, EMPTY_DIGEST, "Team rule.\n", WriteReason.USER_SAVE, null);
        Snapshot appended = store.appendKorttyEntry(global, "Use state for static variables.", "chat", consent(global));
        String entryId = MemoryFormat.parse(appended.text()).entries().get(0).id();

        String edited = appended.text().replace("- Use state for static variables.",
            "- Use state for static variables in Perl.");
        Snapshot afterEdit = store.write(global, appended.sha256(), edited, WriteReason.USER_SAVE, null);
        assertThat(MemoryFormat.parse(afterEdit.text()).entries()).isEmpty();

        // (a) a consented append of another entry succeeds with the stale header in the file.
        Snapshot second = store.appendKorttyEntry(global, "Keep logs short.", "chat", consent(global));
        List<KorttyEntry> entries = MemoryFormat.parse(second.text()).entries();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).id()).isNotEqualTo(entryId);

        // (b) a save of the whole file by the user succeeds.
        Snapshot saved = store.write(global, second.sha256(), second.text() + "A note I typed.\n",
            WriteReason.USER_SAVE, null);
        assertThat(saved.text()).contains("A note I typed.");

        // (c) the edited fence is user text: its body and its header stay as the user left them.
        String text = saved.text();
        assertThat(MemoryFormat.parse(text).entries().stream().map(KorttyEntry::id).toList())
            .doesNotContain(entryId);
        assertThat(MemoryFormat.userSegments(text)).contains("- Use state for static variables in Perl.");
        assertThat(text).contains("<!-- kortty:entry id=" + entryId + " ");
        assertThat(text).contains("sha256=" + MemoryFormat.sha256("- Use state for static variables.") + " ");

        // (d) archiving the intact entry of the append works too, and the edited fence stays where it was.
        Snapshot archived = store.archiveKorttyEntries(global, Set.of(entries.get(0).id()), consent(global));
        assertThat(MemoryFormat.parse(archived.text()).entries()).isEmpty();
        assertThat(MemoryFormat.userSegments(archived.text())).contains("- Use state for static variables in Perl.");
        assertThat(archived.text()).contains("<!-- kortty:entry id=" + entryId + " ");
    }

    @Test
    void aHashOutsideAFenceHeaderStillBlocksTheWrite() throws IOException {
        MemoryScope global = MemoryScope.global();
        Snapshot current = store.write(global, EMPTY_DIGEST, "Team rule.\n", WriteReason.USER_SAVE, null);

        assertThrows(MemorySecretException.class, () -> store.write(global, current.sha256(),
            "Team rule.\ndata=" + HEX_64 + "\n", WriteReason.USER_SAVE, null));
        assertThat(store.read(global).text()).isEqualTo("Team rule.\n");
    }
}
