package de.kortty.core;

import de.kortty.core.SnippetDraftStore.SnippetDraft;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

public class SnippetDraftStoreTest {

    private Path tempDir;
    private Path dir;
    private final List<SnippetDraftStore> stores = new ArrayList<>();

    @BeforeMethod
    public void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-snippet-drafts-test");
        dir = tempDir.resolve(SnippetDraftStore.DIRECTORY_NAME);
    }

    @AfterMethod(alwaysRun = true)
    public void cleanUp() throws IOException {
        stores.forEach(SnippetDraftStore::close);
        stores.clear();
        try (Stream<Path> paths = Files.walk(tempDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private SnippetDraftStore store() {
        SnippetDraftStore store = new SnippetDraftStore(dir);
        stores.add(store);
        return store;
    }

    private static SnippetDraft draft(String id, long at, String content) {
        return SnippetDraft.of(id, at, false, "deploy.sh", "bash", "Ops", "ssh, deploy", "Deploys", content,
            SnippetDiagramSupport.contentHash("echo old"));
    }

    private List<String> filesIn(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    public void aDraftRoundTripsThroughItsFile() throws Exception {
        SnippetDraftStore store = store();
        SnippetDraft written = draft("snippet-1", 1_000L, "echo new\n");
        store.save(written);
        store.flush(5_000);

        assertThat(filesIn(dir)).containsExactly("snippet-1.json");
        Optional<SnippetDraft> loaded = store().load("snippet-1").get();
        assertThat(loaded).hasValue(written);
        assertThat(loaded.get().sameFormAs(draft("snippet-1", 9_999L, "echo new\n"))).isTrue();
        assertThat(loaded.get().sameFormAs(draft("snippet-1", 1_000L, "echo other\n"))).isFalse();
        assertThat(store.load("unknown").get()).isEmpty();
    }

    @Test
    public void theNewestQueuedDraftWins() throws Exception {
        SnippetDraftStore store = store();
        store.save(draft("snippet-1", 1L, "one"));
        store.save(draft("snippet-1", 2L, "two"));
        store.save(draft("snippet-1", 3L, "three"));
        store.flush(5_000);
        assertThat(store.load("snippet-1").get().orElseThrow().content()).isEqualTo("three");
    }

    @Test
    public void aDeleteRemovesTheFileAndDropsAQueuedWrite() throws Exception {
        SnippetDraftStore store = store();
        store.save(draft("snippet-1", 1L, "saved"));
        store.flush(5_000);
        assertThat(filesIn(dir)).containsExactly("snippet-1.json");

        store.delete("snippet-1").get();
        assertThat(filesIn(dir)).isEmpty();

        // A save followed by a delete (the editor saved within the debounce) never lands.
        store.save(draft("snippet-2", 1L, "x"));
        store.delete("snippet-2").get();
        store.flush(5_000);
        assertThat(filesIn(dir)).isEmpty();
        assertThat(store.load("snippet-2").get()).isEmpty();
    }

    @Test
    public void filesAreOwnerOnly() throws Exception {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        SnippetDraftStore store = store();
        store.save(draft("snippet-1", 1L, "secret"));
        store.flush(5_000);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("snippet-1.json"))))
            .isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
    }

    @Test
    public void aCorruptFileIsMovedAsideAndReportsNoDraft() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("snippet-1.json"), "{ not json", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("snippet-2.json"),
            "{\"schemaVersion\":1,\"snippetId\":\"someone-else\",\"content\":\"x\"}", StandardCharsets.UTF_8);

        SnippetDraftStore store = store();
        assertThat(store.load("snippet-1").get()).isEmpty();
        assertThat(store.load("snippet-2").get()).isEmpty();
        List<String> files = filesIn(dir);
        assertThat(files).hasSize(2);
        assertThat(files.stream().allMatch(name -> name.contains(".json.corrupt-"))).isTrue();
    }

    @Test
    public void aDraftOfANewerSchemaIsLeftAlone() throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve("snippet-1.json");
        Files.writeString(file, "{\"schemaVersion\":99,\"snippetId\":\"snippet-1\"}", StandardCharsets.UTF_8);
        assertThat(store().load("snippet-1").get()).isEmpty();
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    public void idsNeverEscapeTheDirectory() throws Exception {
        SnippetDraftStore store = store();
        store.save(draft("../evil", 1L, "x"));
        store.save(draft("CON", 2L, "y"));
        store.flush(5_000);

        assertThat(Files.exists(tempDir.resolve("evil.json"))).isFalse();
        assertThat(filesIn(dir)).containsExactly(SnippetAnalysisStore.fileNameFor("../evil"),
            SnippetAnalysisStore.fileNameFor("CON"));
        assertThat(store.load("../evil").get().orElseThrow().content()).isEqualTo("x");
        assertThat(store.fileFor("../evil").startsWith(dir.toAbsolutePath().normalize())).isTrue();
    }

    @Test
    public void loadAllListsTheReadableDraftsNewestFirst() throws Exception {
        SnippetDraftStore store = store();
        store.save(draft("old", 1_000L, "a"));
        store.save(draft("new", 5_000L, "b"));
        store.save(draft("../hashed", 3_000L, "c"));
        store.flush(5_000);
        Files.writeString(dir.resolve("broken.json"), "[]", StandardCharsets.UTF_8);

        List<SnippetDraft> drafts = store.loadAll().get();
        assertThat(drafts.stream().map(SnippetDraft::snippetId).toList())
            .containsExactly("new", "../hashed", "old").inOrder();
        assertThat(filesIn(dir).stream().anyMatch(name -> name.startsWith("broken.json.corrupt-"))).isTrue();
    }

    @Test
    public void aMemoryStoreKeepsDraftsWithoutFiles() throws Exception {
        SnippetDraftStore memory = new SnippetDraftStore(null);
        stores.add(memory);
        memory.save(draft("snippet-1", 1L, "x"));
        assertThat(memory.load("snippet-1").get()).isPresent();
        assertThat(memory.loadAll().get()).hasSize(1);
        memory.delete("snippet-1").get();
        assertThat(memory.load("snippet-1").get()).isEmpty();
        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    public void draftsAreNotPartOfTheBackup() throws Exception {
        java.lang.reflect.Field field = BackupManager.class.getDeclaredField("MANAGED_BACKUP_DIRECTORIES");
        field.setAccessible(true);
        assertThat((List<?>) field.get(null)).doesNotContain(SnippetDraftStore.DIRECTORY_NAME);
    }
}
