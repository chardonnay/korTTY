package de.kortty.core;

import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMeta;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalSearchCardIndexTest {

    private Path tempDir;
    private Path journalDir;
    private SessionJournalService service;
    private SessionJournalSearchCardIndex index;
    private SessionJournalMeta meta;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-search-card-test");
        journalDir = Files.createDirectories(tempDir.resolve("journal-1"));
        service = new SessionJournalService();
        index = new SessionJournalSearchCardIndex(service);

        meta = new SessionJournalMeta();
        meta.setTitle("Deploy Tuesday");
        meta.setHost("192.168.1.9");
        meta.setUsername("daniel");
        meta.setConnectionName("web");
        meta.setDirectory(journalDir);
        saveDocument("first entry about nginx");
    }

    private void saveDocument(String entryText) throws IOException {
        SessionJournalDocument document = new SessionJournalDocument();
        document.setMeta(new SessionJournalMeta(meta));
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setKind(SessionJournalEntryKind.AI_SUMMARY);
        entry.setTitle("Summary");
        entry.setText(entryText);
        document.getEntries().add(entry);
        service.saveDocument(journalDir, document);
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
    void buildsCardAndWritesDiskCache() throws IOException {
        SessionJournalSearchCard card = index.card(meta);
        assertThat(card.metaText()).contains("Deploy Tuesday");
        assertThat(card.sections()).hasSize(1);
        assertThat(card.sections().get(0).searchText()).contains("nginx");
        assertThat(Files.isRegularFile(
            journalDir.resolve(SessionJournalSearchCardIndex.CACHE_FILE_NAME))).isTrue();
    }

    @Test
    void reusesDiskCacheAcrossInstances() throws IOException {
        SessionJournalSearchCard first = index.card(meta);
        SessionJournalSearchCardIndex fresh = new SessionJournalSearchCardIndex(service);
        SessionJournalSearchCard second = fresh.card(meta);
        assertThat(second.documentMtimeMillis()).isEqualTo(first.documentMtimeMillis());
        assertThat(second.searchText()).isEqualTo(first.searchText());
    }

    @Test
    void rebuildsWhenTheDocumentChanges() throws IOException, InterruptedException {
        index.card(meta);
        // Force a different mtime even on filesystems with coarse timestamps.
        saveDocument("now about result_complex.pl instead");
        Files.setLastModifiedTime(journalDir.resolve(SessionJournalService.DOCUMENT_FILE_NAME),
            FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        SessionJournalSearchCard rebuilt = index.card(meta);
        assertThat(rebuilt.searchText()).contains("result_complex.pl");
        assertThat(rebuilt.searchText()).doesNotContain("first entry about nginx");
    }

    @Test
    void toleratesCorruptDiskCache() throws IOException {
        index.card(meta);
        Files.writeString(journalDir.resolve(SessionJournalSearchCardIndex.CACHE_FILE_NAME),
            "not json {{{");
        SessionJournalSearchCardIndex fresh = new SessionJournalSearchCardIndex(service);
        SessionJournalSearchCard card = fresh.card(meta);
        assertThat(card.sections()).hasSize(1);
    }

    @Test
    void invalidateDropsTheMemoryEntry() throws IOException {
        SessionJournalSearchCard first = index.card(meta);
        index.invalidate(journalDir);
        SessionJournalSearchCard second = index.card(meta);
        assertThat(second.searchText()).isEqualTo(first.searchText());
    }
}
