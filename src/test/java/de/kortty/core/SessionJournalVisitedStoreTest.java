package de.kortty.core;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalVisitedStoreTest {

    private Path tempDir;
    private Path file;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-visited-store-test");
        file = tempDir.resolve("journal-search-visited.xml");
    }

    @AfterMethod
    void deleteTempDir() throws IOException {
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
    void roundTripsVisitsAcrossInstances() {
        SessionJournalVisitedStore store = new SessionJournalVisitedStore(file);
        String entryKey = SessionJournalVisitedStore.entryKey("journal-1", "entry-a");
        String seqKey = SessionJournalVisitedStore.seqKey("journal-1", 42);

        assertThat(store.isVisited(entryKey)).isFalse();
        store.markVisited(entryKey);
        store.markVisited(seqKey);
        store.flush();

        SessionJournalVisitedStore reloaded = new SessionJournalVisitedStore(file);
        assertThat(reloaded.isVisited(entryKey)).isTrue();
        assertThat(reloaded.isVisited(seqKey)).isTrue();
        assertThat(reloaded.isVisited(SessionJournalVisitedStore.seqKey("journal-1", 43))).isFalse();
    }

    @Test
    void evictsOldestBeyondCap() {
        SessionJournalVisitedStore store = new SessionJournalVisitedStore(file);
        for (int i = 0; i < 2001; i++) {
            store.markVisited(SessionJournalVisitedStore.seqKey("journal-1", i));
        }
        store.flush();

        SessionJournalVisitedStore reloaded = new SessionJournalVisitedStore(file);
        assertThat(reloaded.isVisited(SessionJournalVisitedStore.seqKey("journal-1", 0))).isFalse();
        assertThat(reloaded.isVisited(SessionJournalVisitedStore.seqKey("journal-1", 2000))).isTrue();
    }

    @Test
    void revisitingRefreshesRecency() {
        SessionJournalVisitedStore store = new SessionJournalVisitedStore(file);
        store.markVisited("a");
        for (int i = 0; i < 2000; i++) {
            store.markVisited("filler-" + i);
            if (i == 0) {
                store.markVisited("a"); // touch — must survive the fillers pushing out the oldest
            }
        }
        assertThat(store.isVisited("a")).isTrue();
        assertThat(store.isVisited("filler-0")).isFalse();
    }

    @Test
    void toleratesCorruptFile() throws IOException {
        Files.writeString(file, "this is not xml <<<");
        SessionJournalVisitedStore store = new SessionJournalVisitedStore(file);
        assertThat(store.isVisited("anything")).isFalse();
        store.markVisited("recovered");
        store.flush();
        assertThat(new SessionJournalVisitedStore(file).isVisited("recovered")).isTrue();
    }

    @Test
    void ignoresNullAndBlankKeys() {
        SessionJournalVisitedStore store = new SessionJournalVisitedStore(file);
        store.markVisited(null);
        store.markVisited("  ");
        assertThat(store.isVisited(null)).isFalse();
        store.flush();
        assertThat(Files.exists(file)).isTrue();
    }
}
