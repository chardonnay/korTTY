package de.kortty.core;

import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMeta;
import de.kortty.rag.CancellationToken;
import de.kortty.rag.EmbeddingService;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalSemanticIndexTest {

    /** Deterministic keyword-axis embeddings — similarity is real, no model needed. */
    private static final class FakeEmbeddings implements EmbeddingService {
        final AtomicInteger embedCalls = new AtomicInteger();

        @Override
        public String modelId() {
            return "fake-embed";
        }

        @Override
        public int dimensions() {
            return 3;
        }

        @Override
        public List<float[]> embed(List<String> texts, CancellationToken cancellation) {
            embedCalls.incrementAndGet();
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                String lower = text.toLowerCase(Locale.ROOT);
                float[] v = new float[] {
                    lower.contains("nginx") ? 1f : 0f,
                    lower.contains("perl") ? 1f : 0f,
                    0.1f};
                vectors.add(v);
            }
            return vectors;
        }
    }

    private Path tempDir;
    private SessionJournalService service;
    private FakeEmbeddings embeddings;
    private SessionJournalSemanticIndex index;
    private final List<SessionJournalMeta> journals = new ArrayList<>();

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-semantic-index-test");
        service = new SessionJournalService();
        embeddings = new FakeEmbeddings();
        index = new SessionJournalSemanticIndex(
            new SessionJournalSearchCardIndex(service),
            tempDir.resolve(SessionJournalSemanticIndex.INDEX_DIR_NAME),
            embeddings);
        journals.clear();
        journals.add(journal("web", "Nginx work", "nginx restart and reload"));
        journals.add(journal("batch", "Perl batch", "perl scripts crunching data"));
    }

    private SessionJournalMeta journal(String name, String title, String text) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve(name));
        SessionJournalMeta meta = new SessionJournalMeta();
        meta.setTitle(title);
        meta.setConnectionName(name);
        meta.setDirectory(dir);
        SessionJournalDocument document = new SessionJournalDocument();
        document.setMeta(new SessionJournalMeta(meta));
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setKind(SessionJournalEntryKind.AI_SUMMARY);
        entry.setTitle(title);
        entry.setText(text);
        document.getEntries().add(entry);
        service.saveDocument(dir, document);
        return meta;
    }

    @AfterMethod
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to delete temp path " + path, e);
                }
            });
        }
    }

    @Test
    void ranksTheSemanticallyClosestJournalHighest() {
        Map<Path, Double> scores = index.score("nginx trouble", journals, () -> false);

        Path web = journals.get(0).getDirectory().toAbsolutePath().normalize();
        Path batch = journals.get(1).getDirectory().toAbsolutePath().normalize();
        assertThat(scores.keySet()).contains(web);
        assertThat(scores.get(web)).isGreaterThan(scores.getOrDefault(batch, 0.0));
    }

    @Test
    void secondQueryReusesTheIndexWithoutReembedding() {
        index.score("nginx", journals, () -> false);
        int callsAfterFirst = embeddings.embedCalls.get();

        index.score("perl", journals, () -> false);

        // Only the query itself is embedded again — the journal chunks are mtime-fresh.
        assertThat(embeddings.embedCalls.get()).isEqualTo(callsAfterFirst + 1);
    }

    @Test
    void changedJournalIsReembedded() throws IOException, InterruptedException {
        index.score("nginx", journals, () -> false);
        int callsAfterFirst = embeddings.embedCalls.get();

        journal("web", "Nginx work", "now about certificates instead");
        Files.setLastModifiedTime(
            journals.get(0).getDirectory().resolve(SessionJournalService.DOCUMENT_FILE_NAME),
            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        index.score("nginx", journals, () -> false);

        // One journal re-embedded plus the query.
        assertThat(embeddings.embedCalls.get()).isEqualTo(callsAfterFirst + 2);
    }

    @Test
    void failuresYieldEmptyScores() {
        SessionJournalSemanticIndex broken = new SessionJournalSemanticIndex(
            new SessionJournalSearchCardIndex(service),
            tempDir.resolve("other-index"),
            new EmbeddingService() {
                @Override
                public String modelId() {
                    return "broken";
                }

                @Override
                public int dimensions() {
                    return 3;
                }

                @Override
                public List<float[]> embed(List<String> texts, CancellationToken cancellation)
                        throws Exception {
                    throw new IOException("no runtime");
                }
            });
        assertThat(broken.score("nginx", journals, () -> false)).isEmpty();
    }
}
