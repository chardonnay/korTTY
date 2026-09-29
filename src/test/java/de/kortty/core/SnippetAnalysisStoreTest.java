package de.kortty.core;

import de.kortty.core.SnippetAnalysisHistory.LoadIssue;
import de.kortty.core.SnippetAnalysisRecord.ApplyRequestSnapshot;
import de.kortty.core.SnippetAnalysisRecord.ApplyRun;
import de.kortty.core.SnippetAnalysisRecord.RunOutcome;
import de.kortty.core.SnippetAnalysisRecord.StoredCheckpoint;
import de.kortty.model.Snippet;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

public class SnippetAnalysisStoreTest {

    private Path tempDir;
    private Path dir;
    private final List<SnippetAnalysisStore> stores = new ArrayList<>();

    @BeforeMethod
    public void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-snippet-analyses-test");
        dir = tempDir.resolve(SnippetAnalysisStore.DIRECTORY_NAME);
    }

    @AfterMethod(alwaysRun = true)
    public void cleanUp() throws IOException {
        stores.forEach(SnippetAnalysisStore::close);
        stores.clear();
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    path.toFile().setWritable(true);
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    private SnippetAnalysisStore store(int limit) {
        return store(id -> true, limit);
    }

    private SnippetAnalysisStore store(java.util.function.Predicate<String> persistable, int limit) {
        SnippetAnalysisStore store = new SnippetAnalysisStore(dir, persistable, () -> limit);
        stores.add(store);
        return store;
    }

    private static SnippetAnalysisRecord record(String id, long at) {
        return SnippetAnalysisTestData.simpleRecord(id, "ignored", at);
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
    public void roundTripWritesAtomicallyAndReloads() throws Exception {
        SnippetAnalysisStore store = store(5);
        store.addAnalysis("snip-1", SnippetAnalysisTestData.fullRecord("snip-1"));
        store.addAnalysis("snip-1", record("r2", 2000L));
        store.flush(Duration.ofSeconds(5));

        assertThat(filesIn(dir)).containsExactly("snip-1.json");

        SnippetAnalysisHistory reloaded = store(5).load("snip-1").join();
        assertThat(reloaded.revision()).isEqualTo(2L);
        assertThat(reloaded.records().stream().map(SnippetAnalysisRecord::id).toList())
            .containsExactly("r2", "r1").inOrder();
        assertThat(reloaded.records()).isEqualTo(store.cached("snip-1").records());
        assertThat(reloaded.find("r1").snippetId()).isEqualTo("snip-1");
        assertThat(reloaded.loadIssue()).isNull();
    }

    @Test
    public void runningRunsAreInterruptedOnReload() {
        SnippetAnalysisStore store = store(5);
        ApplyRun running = ApplyRun.started("a", 1L, null, List.of(), null)
            .withCheckpoint(new StoredCheckpoint(1, 3, "partial", null, null, null, null));
        store.addAnalysis("s", record("r", 1L).withRun(running));
        store.flush(Duration.ofSeconds(5));

        SnippetAnalysisHistory reloaded = store(5).load("s").join();

        ApplyRun run = reloaded.current().applyRuns().getFirst();
        assertThat(run.outcome()).isEqualTo(RunOutcome.INTERRUPTED);
        assertThat(run.checkpoint().content()).isEqualTo("partial");
    }

    @Test
    public void unparseableFileIsQuarantinedAndNeverOverwritten() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("s.json"), "{ not json", StandardCharsets.UTF_8);
        SnippetAnalysisStore store = store(5);

        SnippetAnalysisHistory loaded = store.load("s").join();

        assertThat(loaded.isEmpty()).isTrue();
        assertThat(loaded.loadIssue().kind()).isEqualTo(LoadIssue.Kind.QUARANTINED);
        Path backup = Path.of(loaded.loadIssue().detail());
        assertThat(Files.readString(backup)).isEqualTo("{ not json");

        store.addAnalysis("s", record("r", 1L));
        store.flush(Duration.ofSeconds(5));

        assertThat(Files.readString(backup)).isEqualTo("{ not json");
        assertThat(store(5).load("s").join().current().id()).isEqualTo("r");
    }

    @Test
    public void fileForAnotherSnippetIsQuarantined() throws Exception {
        SnippetAnalysisStore writer = store(5);
        writer.addAnalysis("other", record("r", 1L));
        writer.flush(Duration.ofSeconds(5));
        Files.move(dir.resolve("other.json"), dir.resolve("s.json"));

        SnippetAnalysisHistory loaded = store(5).load("s").join();

        assertThat(loaded.isEmpty()).isTrue();
        assertThat(loaded.loadIssue().kind()).isEqualTo(LoadIssue.Kind.QUARANTINED);
    }

    @Test
    public void fileThatCannotBeMovedAsideStaysReadOnly() throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve("s.json");
        Files.writeString(file, "garbage", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
        } catch (UnsupportedOperationException e) {
            return; // not a POSIX filesystem
        }
        try {
            SnippetAnalysisStore store = store(5);
            SnippetAnalysisHistory loaded = store.load("s").join();
            assertThat(loaded.loadIssue().kind()).isEqualTo(LoadIssue.Kind.UNREADABLE_READ_ONLY);
            assertThat(store.isPersistable("s")).isFalse();

            SnippetAnalysisHistory updated = store.addAnalysis("s", record("r", 1L));
            store.flush(Duration.ofSeconds(5));

            assertThat(updated.current().id()).isEqualTo("r");
            assertThat(updated.isReadOnly()).isTrue();
            assertThat(Files.readString(file)).isEqualTo("garbage");
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    public void newerSchemaIsShownReadOnlyAndNeverWritten() throws Exception {
        SnippetAnalysisStore writer = store(5);
        writer.addAnalysis("s", record("r", 1L));
        writer.flush(Duration.ofSeconds(5));
        Path file = dir.resolve("s.json");
        String newer = Files.readString(file).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99");
        Files.writeString(file, newer);

        SnippetAnalysisStore store = store(5);
        SnippetAnalysisHistory loaded = store.load("s").join();
        assertThat(loaded.current().id()).isEqualTo("r");
        assertThat(loaded.loadIssue().kind()).isEqualTo(LoadIssue.Kind.NEWER_SCHEMA_READ_ONLY);

        store.addAnalysis("s", record("r2", 2L));
        store.flush(Duration.ofSeconds(5));

        assertThat(Files.readString(file)).isEqualTo(newer);
        assertThat(filesIn(dir)).containsExactly("s.json");
    }

    @Test
    public void retentionOnlyTrimsUnprotectedRecordsWhenANewAnalysisArrives() {
        SnippetAnalysisStore store = store(2);
        store.addAnalysis("s", record("pinned", 1L).withPinned(true));
        ApplyRun pending = ApplyRun.started("p", 1L, null, List.of(), null)
            .withResult(2L, false, "x", "", null, null, null, null, null);
        store.addAnalysis("s", record("pending", 2L).withRun(pending));
        ApplyRun resumable = ApplyRun.started("i", 1L, new ApplyRequestSnapshot("bash", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, "h", "base"), List.of(), null)
            .withCheckpoint(new StoredCheckpoint(1, 3, "partial", null, null, null, null))
            .withOutcome(RunOutcome.INTERRUPTED, 3L);
        store.addAnalysis("s", record("resumable", 3L).withRun(resumable));
        store.addAnalysis("s", record("plain", 4L));
        store.addAnalysis("s", record("running", 5L).withRun(ApplyRun.started("r", 5L, null, List.of(), null)));

        // Only "plain" was unprotected and older than the newest record.
        assertThat(ids(store.cached("s"))).containsExactly("running", "resumable", "pending", "pinned").inOrder();
        assertThat(store.cached("s").trimmableAt(2)).isEqualTo(0);

        store.addAnalysis("s", record("latest", 6L));
        // "running" is protected as well; the new record is always kept.
        assertThat(ids(store.cached("s")).getFirst()).isEqualTo("latest");
        assertThat(ids(store.cached("s"))).hasSize(5);

        // Other changes never trim, even far above the limit.
        store.update("s", history -> history.update("pinned", r -> r.withPinned(false)));
        assertThat(ids(store.cached("s"))).hasSize(5);
        // The next analysis would drop the unpinned record and "latest" (no longer the newest then).
        assertThat(store.cached("s").trimmableAt(2)).isEqualTo(2);
    }

    @Test
    public void anAppliedButUnsavedResultSurvivesReloadRetentionAndOnlyDiscardRemovesIt() throws Exception {
        SnippetAnalysisStore store = store(2);
        ApplyRun remembered = ApplyRun.started("run", 1L, null, List.of(), null)
            .withResult(2L, false, "fixed", "", null, null, null, null, null)
            .accepted(3L, List.of("SEC-1"), "fixed\n# header\n");
        store.addAnalysis("s", record("keeper", 1L).withRun(remembered));
        for (int i = 0; i < 6; i++) {
            store.addAnalysis("s", record("n" + i, 10L + i));
        }
        store.flush(Duration.ofSeconds(5));

        // Retention never trimmed it, and the exact text is on disk.
        assertThat(ids(store.cached("s"))).contains("keeper");
        SnippetAnalysisHistory reloaded = store(2).load("s").join();
        ApplyRun stored = reloaded.find("keeper").findRun("run");
        assertThat(stored.acceptedContent()).isEqualTo("fixed\n# header\n");
        assertThat(reloaded.find("keeper").isProtectedFromRetention()).isTrue();

        // Saving the snippet with that text releases it; discarding the record removes it for good.
        String savedSha = SnippetDiagramSupport.contentHash("fixed\n# header\n");
        store.update("s", history -> history.withAcceptedRunsSaved(savedSha, 99L));
        assertThat(store.cached("s").find("keeper").isProtectedFromRetention()).isFalse();
        store.update("s", history -> history.remove("keeper"));
        assertThat(ids(store.cached("s"))).doesNotContain("keeper");
    }

    @Test
    public void currentIsAlwaysTheNewestAndDiscardRemovesOneRecord() {
        SnippetAnalysisStore store = store(5);
        store.addAnalysis("s", record("a", 1L));
        store.addAnalysis("s", record("b", 2L));

        SnippetAnalysisHistory history = store.discardRecord("s", "b");

        assertThat(history.current().id()).isEqualTo("a");
        assertThat(history.revision()).isEqualTo(3L);
    }

    @Test
    public void pathGuardHashesUnsafeIdsAndStaysInsideTheDirectory() {
        SnippetAnalysisStore store = store(5);
        String longId = "a".repeat(300);
        List<String> unsafe = List.of("../evil", "..", ".", "a/b", "a\\b", longId, "CON", "con", "Nul.txt",
            "lpt1", ".hidden", "trailing.", "space id", "");

        Set<String> names = new HashSet<>();
        for (String id : unsafe) {
            String name = SnippetAnalysisStore.fileNameFor(id);
            assertThat(name).matches("[0-9a-f]{64}\\.json");
            names.add(name);
            if (!id.isEmpty()) {
                assertThat(store.fileFor(id).getParent()).isEqualTo(dir.toAbsolutePath().normalize());
            }
        }
        assertThat(names).hasSize(unsafe.size());
        assertThat(SnippetAnalysisStore.fileNameFor("3f2a-uuid_1.2")).isEqualTo("3f2a-uuid_1.2.json");
        assertThat(SnippetAnalysisStore.fileNameFor("CONFIG")).isEqualTo("CONFIG.json");
    }

    @Test
    public void unsafeIdsRoundTripThroughTheirHashedFile() throws Exception {
        SnippetAnalysisStore store = store(5);
        store.addAnalysis("../evil", record("r", 1L));
        store.flush(Duration.ofSeconds(5));

        assertThat(filesIn(tempDir)).containsExactly(SnippetAnalysisStore.DIRECTORY_NAME);
        assertThat(filesIn(dir)).containsExactly(SnippetAnalysisStore.fileNameFor("../evil"));
        assertThat(store(5).load("../evil").join().current().id()).isEqualTo("r");
    }

    @Test
    public void rekeyMovesTheHistoryAndItsFile() throws Exception {
        SnippetAnalysisStore store = store(5);
        store.addAnalysis("draft", record("r", 1L));
        store.addAnalysis("live", record("existing", 0L));
        store.flush(Duration.ofSeconds(5));
        List<SnippetAnalysisHistory> seen = new ArrayList<>();
        store.subscribe("draft", seen::add);

        SnippetAnalysisHistory moved = store.rekey("draft", "live");
        store.flush(Duration.ofSeconds(5));

        assertThat(ids(moved)).containsExactly("r", "existing").inOrder();
        assertThat(moved.current().snippetId()).isEqualTo("live");
        assertThat(store.cached("draft").isEmpty()).isTrue();
        assertThat(filesIn(dir)).containsExactly("live.json");
        assertThat(ids(store(5).load("live").join())).containsExactly("r", "existing").inOrder();
        // The draft's subscriber follows the history to its new id.
        store.addAnalysis("live", record("later", 5L));
        assertThat(seen.getLast().snippetId()).isEqualTo("live");
    }

    @Test
    public void rekeyKeepsTheOldFileWhileTheTargetCannotBeWritten() throws Exception {
        Set<String> live = new HashSet<>(Set.of("a"));
        SnippetAnalysisStore store = store(live::contains, 5);
        store.addAnalysis("a", record("r", 1L));
        store.flush(Duration.ofSeconds(5));

        store.rekey("a", "b");
        store.flush(Duration.ofSeconds(5));

        assertThat(filesIn(dir)).containsExactly("a.json");
        assertThat(ids(store.cached("b"))).containsExactly("r");
    }

    @Test
    public void copyKeepsTheSourceAndWritesTheTarget() throws Exception {
        SnippetAnalysisStore store = store(5);
        store.addAnalysis("a", record("r", 1L));

        store.copy("a", "b");
        store.flush(Duration.ofSeconds(5));

        assertThat(ids(store.cached("a"))).containsExactly("r");
        assertThat(ids(store.cached("b"))).containsExactly("r");
        assertThat(store.cached("b").current().snippetId()).isEqualTo("b");
        assertThat(filesIn(dir)).containsExactly("a.json", "b.json");
    }

    @Test
    public void discardAllRemovesTheFileAndKeepsTheRevisionGrowing() throws Exception {
        SnippetAnalysisStore store = store(5);
        store.addAnalysis("s", record("r", 1L));
        store.flush(Duration.ofSeconds(5));
        List<SnippetAnalysisHistory> seen = new ArrayList<>();
        store.subscribe("s", seen::add);

        store.discardAll("s");
        store.flush(Duration.ofSeconds(5));

        assertThat(filesIn(dir)).isEmpty();
        assertThat(store.cached("s").isEmpty()).isTrue();
        assertThat(store.cached("s").revision()).isEqualTo(2L);
        assertThat(seen).hasSize(1);
        assertThat(store(5).load("s").join().isEmpty()).isTrue();
    }

    @Test
    public void draftHistoryIsWrittenOnceTheSnippetManagerSavesItsId() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        SnippetAnalysisStore store = store(id -> manager.findById(id).isPresent(), 5);
        store.attachTo(manager);

        store.addAnalysis("draft-1", record("r", 1L));
        store.flush(Duration.ofSeconds(5));
        assertThat(filesIn(dir)).isEmpty();
        assertThat(store.isPersistable("draft-1")).isFalse();

        Snippet snippet = new Snippet("draft.sh", "echo", "bash");
        snippet.setId("draft-1");
        manager.addSnippet(snippet);
        manager.save();
        store.flush(Duration.ofSeconds(5));

        assertThat(filesIn(dir)).containsExactly("draft-1.json");
        assertThat(store(5).load("draft-1").join().current().id()).isEqualTo("r");
    }

    @Test
    public void persistIfPossibleWritesAWaitingHistory() throws Exception {
        Set<String> live = new HashSet<>();
        SnippetAnalysisStore store = store(live::contains, 5);
        store.addAnalysis("d", record("r", 1L));
        live.add("d");

        store.persistIfPossible("d");
        store.flush(Duration.ofSeconds(5));

        assertThat(filesIn(dir)).containsExactly("d.json");
    }

    @Test
    public void writeErrorIsKeptAndClearedByTheNextSuccessfulWrite() throws Exception {
        Files.writeString(dir, "a file where the directory should be");
        SnippetAnalysisStore store = store(5);

        store.addAnalysis("s", record("r", 1L));
        store.flush(Duration.ofSeconds(5));

        assertThat(store.cached("s").lastWriteError()).isNotNull();
        assertThat(store.cached("s").current().id()).isEqualTo("r");

        Files.delete(dir);
        store.addAnalysis("s", record("r2", 2L));
        store.flush(Duration.ofSeconds(5));

        assertThat(store.cached("s").lastWriteError()).isNull();
        assertThat(ids(store(5).load("s").join())).containsExactly("r2", "r").inOrder();
    }

    @Test
    public void subscribersAreNotifiedUntilTheyClose() {
        SnippetAnalysisStore store = store(5);
        List<Long> revisions = new ArrayList<>();
        SnippetAnalysisStore.Subscription subscription = store.subscribe("s", h -> revisions.add(h.revision()));

        store.addAnalysis("s", record("a", 1L));
        store.update("s", history -> history.update("a", r -> r.withPinned(true)));
        store.update("s", history -> history); // no change, no notification
        subscription.close();
        store.addAnalysis("s", record("b", 2L));

        assertThat(revisions).containsExactly(1L, 2L).inOrder();
    }

    @Test
    public void flushWritesTheLatestCoalescedState() throws Exception {
        SnippetAnalysisStore store = store(20);
        for (int i = 0; i < 15; i++) {
            store.addAnalysis("s", record("r" + i, i));
        }
        store.flush(Duration.ofSeconds(5));

        assertThat(SnippetAnalysisStore.readRevision(dir.resolve("s.json")).getAsLong()).isEqualTo(15L);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.filter(p -> p.toString().endsWith(".tmp")).toList()).isEmpty();
        }
    }

    @Test
    public void runClaimIsExclusivePerSnippet() {
        SnippetAnalysisStore store = store(5);
        Object first = new Object();
        Object second = new Object();

        assertThat(store.tryClaimRun("s", first)).isTrue();
        assertThat(store.tryClaimRun("s", second)).isFalse();
        assertThat(store.isRunClaimedByOther("s", second)).isTrue();
        store.releaseRun("s", second);
        assertThat(store.tryClaimRun("s", second)).isFalse();
        store.releaseRun("s", first);
        assertThat(store.tryClaimRun("s", second)).isTrue();
    }

    @Test
    public void invalidateAllReloadsFilesReplacedOnDisk() throws Exception {
        SnippetAnalysisStore store = store(5);
        store.addAnalysis("s", record("old", 1L));
        store.flush(Duration.ofSeconds(5));
        SnippetAnalysisStore other = store(5);
        other.load("s").join();
        other.addAnalysis("s", record("restored", 2L));
        other.flush(Duration.ofSeconds(5));

        store.invalidateAll();

        assertThat(ids(store.load("s").join())).containsExactly("restored", "old").inOrder();
    }

    @Test
    public void memoryOnlyStoreNeverTouchesTheDisk() throws Exception {
        SnippetAnalysisStore store = new SnippetAnalysisStore(null, id -> true, () -> 5);
        stores.add(store);

        store.addAnalysis("s", record("r", 1L));

        assertThat(store.cached("s").current().id()).isEqualTo("r");
        assertThat(store.isPersistable("s")).isFalse();
        assertThat(filesIn(dir)).isEmpty();
    }

    @Test
    public void sharedUsesTheDirectoryPropertyOutsideTheApp() {
        String previous = System.getProperty(SnippetAnalysisStore.DIRECTORY_PROPERTY);
        try {
            System.clearProperty(SnippetAnalysisStore.DIRECTORY_PROPERTY);
            assertThat(SnippetAnalysisStore.shared().directory()).isNull();

            System.setProperty(SnippetAnalysisStore.DIRECTORY_PROPERTY, dir.toString());
            SnippetAnalysisStore shared = SnippetAnalysisStore.shared();
            assertThat(shared.directory()).isEqualTo(dir.toAbsolutePath().normalize());
            assertThat(SnippetAnalysisStore.shared()).isSameInstanceAs(shared);
        } finally {
            if (previous == null) {
                System.clearProperty(SnippetAnalysisStore.DIRECTORY_PROPERTY);
            } else {
                System.setProperty(SnippetAnalysisStore.DIRECTORY_PROPERTY, previous);
            }
        }
    }

    @Test
    public void overviewsReadFilesWithoutCachingThem() throws Exception {
        SnippetAnalysisStore writer = store(5);
        writer.addAnalysis("a", record("r1", 1L));
        writer.addAnalysis("a", record("r2", 2L).withPinned(true));
        writer.addAnalysis("b", record("r3", 3L));
        writer.flush(Duration.ofSeconds(5));

        SnippetAnalysisStore reader = store(5);
        java.util.Map<String, SnippetAnalysisOverview> overviews =
            reader.overviews(List.of("a", "b", "missing", "a")).get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(overviews.keySet()).containsExactly("a", "b");
        assertThat(overviews.get("a").recordCount()).isEqualTo(2);
        assertThat(overviews.get("a").unprotectedCount()).isEqualTo(1);
        assertThat(overviews.get("a").analyzedAt()).isEqualTo(2L);
        // Summaries only: nothing was loaded into the cache, and no file was touched.
        assertThat(reader.cached("a")).isNull();
        assertThat(reader.cached("b")).isNull();
        assertThat(filesIn(dir)).containsExactly("a.json", "b.json");

        assertThat(reader.allOverviews().get(5, java.util.concurrent.TimeUnit.SECONDS).keySet())
            .containsExactly("a", "b");
    }

    @Test
    public void overviewsPreferTheHistoryInMemoryAndSkipUnreadableFiles() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("broken.json"), "{ not json", StandardCharsets.UTF_8);
        SnippetAnalysisStore store = store(id -> !id.equals("draft"), 5);
        store.addAnalysis("draft", record("r1", 1L));

        java.util.Map<String, SnippetAnalysisOverview> overviews =
            store.overviews(List.of("draft", "broken")).get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(overviews.keySet()).containsExactly("draft");
        // A summary never quarantines: the broken file is only moved aside when its snippet is opened.
        assertThat(filesIn(dir)).containsExactly("broken.json");
    }

    @Test
    public void changeListenersHearEveryChangeUntilClosed() {
        SnippetAnalysisStore store = store(5);
        List<String> changed = new ArrayList<>();
        SnippetAnalysisStore.Subscription subscription = store.addChangeListener(changed::add);

        store.addAnalysis("s", record("r1", 1L));
        store.update("s", h -> h.update("r1", r -> r.withPinned(true)));
        store.discardRecord("s", "r1");
        store.discardAll("t");
        store.invalidateAll();
        subscription.close();
        store.addAnalysis("s", record("r2", 2L));

        assertThat(changed).containsExactly("s", "s", "s", "t", null).inOrder();
    }

    private static List<String> ids(SnippetAnalysisHistory history) {
        return history.records().stream().map(SnippetAnalysisRecord::id).toList();
    }
}
