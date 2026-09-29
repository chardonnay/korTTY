package de.kortty.core;

import de.kortty.core.SnippetAnalysisRecord.ApplyRequestSnapshot;
import de.kortty.core.SnippetAnalysisRecord.ApplyRun;
import de.kortty.core.SnippetAnalysisRecord.RunOutcome;
import de.kortty.core.SnippetAnalysisRecord.Source;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/**
 * Scripts up to the stored-content limit (5 MiB by default) are stored completely, and the store
 * copes with them: file cap scaling, cheap overviews, bounded memory, per-file content budget and
 * "lowering the limit never deletes".
 */
public class SnippetAnalysisLargeContentTest {

    private static final long MB = 1024L * 1024;

    private Path tempDir;
    private Path dir;
    private final List<SnippetAnalysisStore> stores = new ArrayList<>();

    @BeforeMethod
    public void createTempDir() throws IOException {
        // the default is 1 MiB; these tests are about scripts up to the 5 MiB maximum
        SnippetAnalysisContentLimit.install(() -> SnippetAnalysisContentLimit.compute(5 * MB, null));
        tempDir = Files.createTempDirectory("kortty-snippet-analyses-large");
        dir = tempDir.resolve(SnippetAnalysisStore.DIRECTORY_NAME);
    }

    @AfterMethod(alwaysRun = true)
    public void cleanUp() throws IOException {
        SnippetAnalysisContentLimit.reset();
        stores.forEach(SnippetAnalysisStore::close);
        stores.clear();
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    private SnippetAnalysisStore store() {
        SnippetAnalysisStore store = new SnippetAnalysisStore(dir, id -> true, () -> 5);
        stores.add(store);
        return store;
    }

    /** ASCII text of exactly {@code bytes} bytes; {@code salt} makes texts distinct. */
    static String script(long bytes, String salt) {
        StringBuilder text = new StringBuilder((int) bytes + 100);
        int line = 0;
        while (text.length() < bytes) {
            text.append("echo \"line ").append(line++).append(' ').append(salt).append(" of a generated script\"\n");
        }
        text.setLength((int) bytes);
        return text.toString();
    }

    private static ApplyRun decidedRun(String id, String base, String result, RunOutcome outcome) {
        ApplyRequestSnapshot request = ApplyRequestSnapshot.EMPTY.withBaseContent(base);
        ApplyRun started = ApplyRun.started(id, 10L, request, List.of(), null);
        return started.withResult(20L, false, result, "summary " + id, List.of(), List.of(), List.of(), null, null)
            .withOutcome(outcome, 30L);
    }

    private static SnippetAnalysisRecord record(String id, String snippetId, long at, String source) {
        return SnippetAnalysisTestData.simpleRecord(id, snippetId, at)
            .withSource(Source.of(source, "bash", "en", "en", "big"));
    }

    private static long uniqueContentBytes(SnippetAnalysisHistory history) {
        Set<String> seen = new HashSet<>();
        long total = 0;
        for (SnippetAnalysisRecord record : history.records()) {
            List<String> texts = new ArrayList<>();
            texts.add(record.source().content());
            for (ApplyRun run : record.applyRuns()) {
                texts.add(run.request().baseContent());
                texts.add(run.resultContent());
                texts.add(run.acceptedContent());
                if (run.checkpoint() != null) {
                    texts.add(run.checkpoint().content());
                }
            }
            for (String text : texts) {
                if (text != null && seen.add(text)) {
                    total += SnippetAnalysisContentLimit.utf8Length(text);
                }
            }
        }
        return total;
    }

    @Test
    public void aFiveMebibyteScriptIsStoredCompletelyAndReloads() throws Exception {
        String source = script(5 * MB, "src");
        String proposed = script(5 * MB, "proposed");
        String edited = script(5 * MB, "edited");
        String second = script(5 * MB, "second");
        ApplyRun accepted = decidedRun("run-1", source, proposed, RunOutcome.PENDING_REVIEW)
            .accepted(40L, List.of(), edited);
        ApplyRun other = decidedRun("run-2", second, second + "x".repeat(0), RunOutcome.REJECTED);
        SnippetAnalysisStore writer = store();
        writer.addAnalysis("big", record("r1", "big", 1000L, source).withRun(accepted).withRun(other));
        writer.flush(Duration.ofSeconds(60));

        long fileSize = Files.size(dir.resolve("big.json"));
        assertThat(fileSize).isGreaterThan(SnippetAnalysisContentLimit.MIN_FILE_BYTES);

        SnippetAnalysisHistory loaded = store().load("big").join();
        assertThat(loaded.loadIssue()).isNull();
        SnippetAnalysisRecord reloaded = loaded.find("r1");
        assertThat(reloaded.source().content()).isEqualTo(source);
        assertThat(reloaded.source().contentTruncated()).isFalse();
        ApplyRun run1 = reloaded.findRun("run-1");
        assertThat(run1.request().baseContent()).isEqualTo(source);
        assertThat(run1.resultContent()).isEqualTo(proposed);
        assertThat(run1.acceptedContent()).isEqualTo(edited);
        assertThat(reloaded.findRun("run-2").request().baseContent()).isEqualTo(second);
    }

    @Test
    public void aScriptOneByteOverTheLimitIsDroppedWithItsHash() {
        String over = script(5 * MB + 1, "over");
        Source source = Source.of(over, "bash", "en", "en", "big");
        assertThat(source.content()).isNull();
        assertThat(source.contentTruncated()).isTrue();
        assertThat(source.sha256()).isEqualTo(SnippetDiagramSupport.contentHash(over));
        assertThat(Source.of(script(5 * MB, "exact"), "bash", "en", "en", "big").content()).isNotNull();
    }

    @Test
    public void overviewsSkipTheBlobsAndStayCheap() throws Exception {
        SnippetAnalysisStore writer = store();
        for (int i = 0; i < 3; i++) {
            String source = script(5 * MB, "s" + i);
            String result = script(5 * MB, "r" + i);
            writer.addAnalysis("big", record("r" + i, "big", 1000L + i, source)
                .withRun(decidedRun("run-" + i, source, result, RunOutcome.PENDING_REVIEW)));
        }
        writer.flush(Duration.ofSeconds(60));
        Path file = dir.resolve("big.json");
        assertThat(Files.size(file)).isGreaterThan(20 * MB);

        // allocation on this thread: a full parse of the file would need tens of MiB
        com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        SnippetAnalysisStore.peekFile(file); // warm up
        long before = threads.getThreadAllocatedBytes(Thread.currentThread().getId());
        SnippetAnalysisHistory peeked = SnippetAnalysisStore.peekFile(file);
        long allocated = threads.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
        assertThat(peeked.records()).hasSize(3);
        assertThat(allocated).isLessThan(16 * MB);

        SnippetAnalysisStore reader = store();
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            var overviews = reader.allOverviews().join();
            best = Math.min(best, System.nanoTime() - start);
            assertThat(overviews.get("big").recordCount()).isEqualTo(3);
            assertThat(overviews.get("big").pendingReview()).isTrue();
        }
        assertThat(Duration.ofNanos(best).toMillis()).isLessThan(200L);
        long loadStart = System.nanoTime();
        SnippetAnalysisHistory full = store().load("big").join();
        long loadMillis = Duration.ofNanos(System.nanoTime() - loadStart).toMillis();
        assertThat(full.records()).hasSize(3);
        System.out.printf("[measure] file=%d MiB overview=%d ms peekAllocated=%d MiB fullLoad=%d ms%n",
            Files.size(file) / MB, Duration.ofNanos(best).toMillis(), allocated / MB, loadMillis);
        assertThat(reader.cachedEntryCount()).isEqualTo(0); // an overview never caches the history
    }

    @Test
    public void theBlobsSectionStaysLastBecauseTheOverviewStopsThere() {
        String json = SnippetAnalysisStore.toJson(SnippetAnalysisHistory.empty("h")
            .withRecords(List.of(record("r", "h", 1L, "echo hi\n"))));
        List<String> keys = new ArrayList<>(com.google.gson.JsonParser.parseString(json).getAsJsonObject().keySet());
        assertThat(keys.getLast()).isEqualTo("blobs");
    }

    @Test
    public void unusedHistoriesAreEvictedButReloadFromTheirFile() throws Exception {
        SnippetAnalysisStore store = store();
        for (int i = 0; i < 8; i++) {
            store.addAnalysis("snip-" + i, record("r", "snip-" + i, 1000L, "echo " + i + "\n"));
        }
        store.flush(Duration.ofSeconds(10));
        store.flush(Duration.ofSeconds(10));
        assertThat(store.cachedEntryCount()).isAtMost(SnippetAnalysisStore.MAX_IDLE_CACHED);
        SnippetAnalysisHistory again = store.load("snip-0").join();
        assertThat(again.current().source().content()).isEqualTo("echo 0\n");
    }

    @Test
    public void aSubscribedHistoryIsNeverEvicted() throws Exception {
        SnippetAnalysisStore store = store();
        store.addAnalysis("open", record("r", "open", 1000L, "echo open\n"));
        try (var subscription = store.subscribe("open", history -> { })) {
            for (int i = 0; i < 8; i++) {
                store.addAnalysis("other-" + i, record("r", "other-" + i, 1000L, "echo\n"));
            }
            store.flush(Duration.ofSeconds(10));
            store.flush(Duration.ofSeconds(10));
            assertThat(store.cached("open")).isNotNull();
        }
    }

    @Test
    public void budgetDropsTheOldestUnprotectedContentFirst() {
        // three records: each a source and a decided run with base + result (4 distinct texts of 400 bytes)
        List<SnippetAnalysisRecord> records = new ArrayList<>();
        for (int i = 2; i >= 0; i--) {
            String source = script(400, "s" + i);
            records.add(record("r" + i, "h", 1000L + i, source)
                .withRun(decidedRun("run" + i, script(400, "b" + i), script(400, "x" + i), RunOutcome.REJECTED)));
        }
        SnippetAnalysisHistory history = SnippetAnalysisHistory.empty("h").withRecords(records);
        assertThat(uniqueContentBytes(history)).isEqualTo(3 * 3 * 400);

        SnippetAnalysisHistory trimmed = history.withContentBudget(2000);

        assertThat(uniqueContentBytes(trimmed)).isAtMost(2000L);
        // the oldest record (last in the list) lost its run content before anything else
        SnippetAnalysisRecord oldest = trimmed.records().get(2);
        SnippetAnalysisRecord newest = trimmed.records().get(0);
        assertThat(oldest.findRun("run0").resultContent()).isNull();
        assertThat(newest.source().content()).isNotNull();
        assertThat(newest.findRun("run2").resultContent()).isNotNull();
        // hashes and findings survive
        assertThat(oldest.source().sha256()).isNotEmpty();
        assertThat(oldest.improvements()).isNotEmpty();
    }

    @Test
    public void budgetKeepsPendingReviewsUntilNothingElseIsLeft() {
        String pendingBase = script(400, "pb");
        ApplyRun pending = decidedRun("pending", pendingBase, script(400, "pr"), RunOutcome.PENDING_REVIEW);
        SnippetAnalysisRecord old = record("old", "h", 1000L, script(400, "old-source")).withRun(pending)
            .withRun(decidedRun("decided", script(400, "db"), script(400, "dr"), RunOutcome.REJECTED));
        SnippetAnalysisRecord current = record("current", "h", 2000L, script(400, "current-source"));
        SnippetAnalysisHistory history = SnippetAnalysisHistory.empty("h").withRecords(List.of(current, old));

        SnippetAnalysisHistory trimmed = history.withContentBudget(1300);

        SnippetAnalysisRecord trimmedOld = trimmed.find("old");
        assertThat(trimmedOld.findRun("decided").resultContent()).isNull();
        assertThat(trimmedOld.findRun("pending").resultContent()).isNotNull();
        assertThat(trimmedOld.findRun("pending").request().baseContent()).isEqualTo(pendingBase);
        assertThat(uniqueContentBytes(trimmed)).isAtMost(1300L);

        // a budget of zero sheds everything, including the protected content (last resort)
        assertThat(uniqueContentBytes(history.withContentBudget(0))).isEqualTo(0L);
        // and a budget that fits changes nothing
        assertThat(history.withContentBudget(1_000_000)).isSameInstanceAs(history);
    }

    @Test
    public void identicalTextIsCountedAndStoredOnce() throws Exception {
        String shared = script(3 * MB, "shared");
        SnippetAnalysisRecord record = record("r", "dedupe", 1000L, shared)
            .withRun(decidedRun("a", shared, shared, RunOutcome.REJECTED))
            .withRun(decidedRun("b", shared, shared, RunOutcome.REJECTED));
        SnippetAnalysisStore writer = store();
        writer.addAnalysis("dedupe", record);
        writer.flush(Duration.ofSeconds(30));
        assertThat(Files.size(dir.resolve("dedupe.json"))).isLessThan(4 * MB);
        assertThat(uniqueContentBytes(writer.cached("dedupe"))).isEqualTo(3 * MB);
    }

    @Test
    public void loweringTheLimitKeepsAlreadyStoredContentButAppliesToNewText() {
        String stored = script(3 * MB, "stored");
        SnippetAnalysisRecord record = record("r", "h", 1000L, stored);
        assertThat(record.source().content()).isNotNull();

        SnippetAnalysisContentLimit.install(() -> SnippetAnalysisContentLimit.compute(1 * MB, null));

        assertThat(record.compact().source().content()).isEqualTo(stored);
        assertThat(SnippetAnalysisHistory.empty("h").withRecords(List.of(record)).compact().current()
            .source().content()).isEqualTo(stored);
        assertThat(Source.of(stored, "bash", "en", "en", "big").content()).isNull();
    }

    @Test
    public void aPolicyCapAlsoShedsWhatWasStoredBeforeIt() {
        String stored = script(3 * MB, "stored");
        SnippetAnalysisRecord record = record("r", "h", 1000L, stored);

        SnippetAnalysisContentLimit.install(() -> SnippetAnalysisContentLimit.compute(5 * MB, 1 * MB));
        assertThat(record.compact().source().content()).isNull();
        assertThat(record.compact().source().contentTruncated()).isTrue();

        SnippetAnalysisContentLimit.install(() -> SnippetAnalysisContentLimit.compute(5 * MB, 0L));
        assertThat(record("r2", "h", 1000L, "tiny").source().content()).isNull();
    }

    @Test
    public void loweringTheSettingNeverOrphansAFileWrittenUnderAHigherOne() throws Exception {
        SnippetAnalysisStore writer = store();
        for (int i = 0; i < 4; i++) {
            String text = script(5 * MB, "part" + i);
            writer.addAnalysis("large", record("r" + i, "large", 1000L + i, text));
        }
        writer.flush(Duration.ofSeconds(60));
        assertThat(Files.size(dir.resolve("large.json"))).isGreaterThan(16 * MB);

        // the file cap follows the 5 MiB hard maximum, not the setting: still loads after lowering it
        SnippetAnalysisContentLimit.install(() -> SnippetAnalysisContentLimit.compute(256L * 1024, null));
        SnippetAnalysisHistory loaded = store().load("large").join();
        assertThat(loaded.loadIssue()).isNull();
        assertThat(loaded.records()).hasSize(4);
        assertThat(loaded.find("r3").source().content()).hasLength((int) (5 * MB));
    }

    @Test
    public void aFileOverTheFileCapIsMovedAsideNotOverwritten() throws Exception {
        SnippetAnalysisStore writer = store();
        writer.addAnalysis("large", record("r", "large", 1000L, script(300_000, "x")));
        writer.flush(Duration.ofSeconds(10));
        long size = Files.size(dir.resolve("large.json"));

        SnippetAnalysisContentLimit.overrideMaxFileBytesForTests(size - 1);
        SnippetAnalysisHistory loaded = store().load("large").join();

        assertThat(loaded.loadIssue()).isNotNull();
        assertThat(loaded.loadIssue().kind()).isEqualTo(SnippetAnalysisHistory.LoadIssue.Kind.QUARANTINED);
        assertThat(loaded.records()).isEmpty();
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.contains("large")).count())
                .isAtLeast(1L);
        }
        assertThat(Files.exists(dir.resolve("large.json"))).isFalse();
    }

    @Test
    public void aWrittenFileNeverExceedsTheFileLimitEvenWithEscapeHeavyContent() throws Exception {
        // control characters escape to 6 bytes each: the JSON grows far beyond the raw content budget
        SnippetAnalysisContentLimit.overrideMaxFileBytesForTests(2 * MB);
        List<SnippetAnalysisRecord> records = new ArrayList<>();
        for (int r = 5; r >= 0; r--) {
            SnippetAnalysisRecord record = record("r" + r, "escapes", 1000L + r,
                String.valueOf((char) (1 + r)).repeat(20_000));
            for (int i = 0; i < 10; i++) {
                String text = String.valueOf((char) (10 + i)).repeat(20_000 - r);
                record = record.withRun(decidedRun("run" + i, text, text.substring(1), RunOutcome.REJECTED));
            }
            records.add(record);
        }
        SnippetAnalysisHistory history = SnippetAnalysisHistory.empty("escapes").withRecords(records);
        String json = SnippetAnalysisStore.toJson(history);
        assertThat(SnippetAnalysisContentLimit.utf8Length(json)).isAtMost(2 * MB);
        // the result still parses and keeps every record
        assertThat(SnippetAnalysisStore.fromJson(json).records()).hasSize(6);
    }
}
