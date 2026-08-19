package de.kortty.core;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Remembers which journal-search hits the user has already opened, so a longer investigation
 * across many results stays orientable — across queries and application restarts. Keys are
 * query-independent ({@code journalId} plus the hit's entry id or log seq); the store is a
 * bounded LRU so it never grows without limit.
 */
public final class SessionJournalVisitedStore {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalVisitedStore.class);
    private static final String FILE_NAME = "journal-search-visited.xml";
    private static final int MAX_ENTRIES = 2000;
    private static final long SAVE_DELAY_MS = 500;

    private static SessionJournalVisitedStore shared;

    private final Path file;
    /** key → visited-at epoch millis, in insertion (= recency) order. */
    private final Map<String, Long> visited = new LinkedHashMap<>();
    private boolean loaded;
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SessionJournal-VisitedStore");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> pendingSave;

    SessionJournalVisitedStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** The application-wide store backed by {@code ~/.kortty/journal-search-visited.xml}. */
    public static synchronized SessionJournalVisitedStore shared() {
        if (shared == null) {
            shared = new SessionJournalVisitedStore(
                Path.of(System.getProperty("user.home"), ".kortty", FILE_NAME));
        }
        return shared;
    }

    /** Key for a hit that targets a curated journal entry. */
    public static String entryKey(String journalId, String entryId) {
        return journalId + "#entry:" + entryId;
    }

    /** Key for a hit that targets a capture-log position. */
    public static String seqKey(String journalId, long seq) {
        return journalId + "#seq:" + seq;
    }

    public synchronized boolean isVisited(String key) {
        ensureLoaded();
        return key != null && visited.containsKey(key);
    }

    public synchronized void markVisited(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        ensureLoaded();
        visited.remove(key);
        visited.put(key, System.currentTimeMillis());
        while (visited.size() > MAX_ENTRIES) {
            visited.remove(visited.keySet().iterator().next());
        }
        scheduleSave();
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JAXBContext context = JAXBContext.newInstance(Wrapper.class);
            Unmarshaller unmarshaller = context.createUnmarshaller();
            Wrapper wrapper = (Wrapper) unmarshaller.unmarshal(
                new StringReader(Files.readString(file)));
            if (wrapper != null && wrapper.visits != null) {
                wrapper.visits.stream()
                    .filter(visit -> visit != null && visit.key != null && !visit.key.isBlank())
                    .sorted(Comparator.comparingLong(visit -> visit.at))
                    .forEach(visit -> visited.put(visit.key, visit.at));
                while (visited.size() > MAX_ENTRIES) {
                    visited.remove(visited.keySet().iterator().next());
                }
            }
        } catch (Exception e) {
            // A corrupt state file must never break search — orientation state is expendable.
            logger.warn("Could not read {}: {}", file.getFileName(), e.getMessage());
            visited.clear();
        }
    }

    private synchronized void scheduleSave() {
        if (pendingSave != null) {
            pendingSave.cancel(false);
        }
        pendingSave = saver.schedule(this::save, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void save() {
        Wrapper wrapper = new Wrapper();
        synchronized (this) {
            wrapper.visits = new ArrayList<>(visited.size());
            visited.forEach((key, at) -> {
                Visit visit = new Visit();
                visit.key = key;
                visit.at = at;
                wrapper.visits.add(visit);
            });
        }
        try {
            Files.createDirectories(file.getParent());
            JAXBContext context = JAXBContext.newInstance(Wrapper.class);
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            StringWriter writer = new StringWriter();
            marshaller.marshal(wrapper, writer);
            AtomicFileWriter.writeStringAtomically(file, writer.toString());
        } catch (Exception e) {
            logger.warn("Could not save {}: {}", file.getFileName(), e.getMessage());
        }
    }

    /** Blocks until a pending debounced save has been written — for tests and shutdown. */
    synchronized void flush() {
        if (pendingSave != null) {
            pendingSave.cancel(false);
            pendingSave = null;
        }
        save();
    }

    @XmlRootElement(name = "journal-search-visited")
    @XmlAccessorType(XmlAccessType.FIELD)
    static final class Wrapper {

        @XmlElement(name = "visit")
        List<Visit> visits;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    static final class Visit {

        @XmlAttribute(name = "key")
        String key;

        @XmlAttribute(name = "at")
        long at;
    }
}
