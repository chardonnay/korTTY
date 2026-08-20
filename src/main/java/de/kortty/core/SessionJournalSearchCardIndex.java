package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches one {@link SessionJournalSearchCard} per journal, invalidated by the {@code journal.xml}
 * mtime — the same freshness rule as the manager's meta cache. Two layers: an in-memory map for
 * the scan loop, and a {@code search-card.json} file inside the journal directory so a restart
 * does not re-parse hundreds of documents (the file is derived data: losing or deleting it only
 * costs a rebuild, and journal deletion removes it with the directory).
 */
public final class SessionJournalSearchCardIndex {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalSearchCardIndex.class);
    static final String CACHE_FILE_NAME = "search-card.json";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final SessionJournalService service;
    private final Map<Path, SessionJournalSearchCard> memoryCache = new ConcurrentHashMap<>();

    public SessionJournalSearchCardIndex(SessionJournalService service) {
        this.service = service;
    }

    /** The journal's current card, from cache when fresh, rebuilt (and re-persisted) when not. */
    public SessionJournalSearchCard card(SessionJournalMeta meta) throws IOException {
        Path dir = meta.getDirectory();
        Path documentFile = dir.resolve(SessionJournalService.DOCUMENT_FILE_NAME);
        long mtime = Files.getLastModifiedTime(documentFile).toMillis();
        Path key = dir.toAbsolutePath().normalize();

        SessionJournalSearchCard cached = memoryCache.get(key);
        if (cached != null && cached.documentMtimeMillis() == mtime) {
            return cached;
        }
        SessionJournalSearchCard fromDisk = readDiskCache(dir, mtime);
        if (fromDisk != null) {
            memoryCache.put(key, fromDisk);
            return fromDisk;
        }

        SessionJournalDocument document = service.loadDocument(dir);
        SessionJournalSearchCard card = SessionJournalSearchCard.build(
            document.getMeta(), document.getEntries(), document.getId(), mtime);
        memoryCache.put(key, card);
        writeDiskCache(dir, card);
        return card;
    }

    /** Drops the cached card (e.g. after a delete); the disk file goes with the directory. */
    public void invalidate(Path dir) {
        if (dir != null) {
            memoryCache.remove(dir.toAbsolutePath().normalize());
        }
    }

    /** Null when absent, stale, or unreadable — derived data never fails the search. */
    private static SessionJournalSearchCard readDiskCache(Path dir, long expectedMtime) {
        Path cacheFile = dir.resolve(CACHE_FILE_NAME);
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }
        try {
            SessionJournalSearchCard card = GSON.fromJson(
                Files.readString(cacheFile, StandardCharsets.UTF_8), SessionJournalSearchCard.class);
            return card != null && card.documentMtimeMillis() == expectedMtime ? card : null;
        } catch (Exception e) {
            logger.debug("Ignoring unreadable search card cache in {}: {}",
                dir.getFileName(), e.getMessage());
            return null;
        }
    }

    private static void writeDiskCache(Path dir, SessionJournalSearchCard card) {
        try {
            AtomicFileWriter.writeStringAtomically(
                dir.resolve(CACHE_FILE_NAME), GSON.toJson(card));
        } catch (Exception e) {
            logger.debug("Could not persist search card cache in {}: {}",
                dir.getFileName(), e.getMessage());
        }
    }
}
