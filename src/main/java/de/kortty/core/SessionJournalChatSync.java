package de.kortty.core;

import de.kortty.model.SavedAiChat;
import de.kortty.model.SessionJournalChatSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Keeps the copy of a KI-chat that a session journal holds up to date while the journal records.
 *
 * <p>A chat linked to an AGENT entry of a live journal is registered here. Every save of the chat
 * schedules one rewrite of the journal's copy after {@link #DEFAULT_DELAY} of quiet; a burst of saves
 * therefore writes once. When the journal stops, the pending copy is written with {@code frozen=true}
 * and the chat is released. A chat registered for a journal that already stopped gets its frozen copy
 * at once. Deleting the chat keeps the copy the journal already has and marks it final, since the chat cannot
 * change any more. The sync never saves a chat itself, so it cannot trigger another change.</p>
 *
 * <p>Timing and the write are injected, so the rules can be tested without a clock or a file. Writes
 * run on one daemon thread, never on the caller's thread; a failed write is logged and dropped, since
 * the next change writes the copy again.</p>
 */
public final class SessionJournalChatSync {

    /** Quiet time after the last save of a chat before its journal copy is rewritten. */
    public static final Duration DEFAULT_DELAY = Duration.ofSeconds(2);

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalChatSync.class);

    /** Finds a chat by id; empty when it no longer exists. */
    @FunctionalInterface
    public interface ChatLookup {
        Optional<SavedAiChat> find(String chatId);
    }

    /** Writes the chat copy of one AGENT entry. */
    public interface SnapshotWriter {

        /** Replaces the chat copy of one AGENT entry; {@code false} when the journal has no such entry. */
        boolean write(Path journalDir, String entryId, SessionJournalChatSnapshot snapshot) throws IOException;

        /**
         * Marks the entry's existing chat copy frozen and keeps its text; used when the journal stopped and
         * the chat is gone. {@code false} when the journal has no such entry. The default writes nothing.
         */
        default boolean freeze(Path journalDir, String entryId) throws IOException {
            return false;
        }
    }

    /** Runs tasks on one thread: {@link #execute} as soon as possible, {@link #schedule} after a delay. */
    public interface Scheduler {
        void schedule(Duration delay, Runnable task);

        void execute(Runnable task);

        void shutdown();
    }

    /** A chat linked to an AGENT entry of a journal. */
    private static final class Registration {
        private final Path journalDir;
        private final String entryId;
        private final SessionJournalRedactor redactor;
        private long generation;

        Registration(Path journalDir, String entryId, SessionJournalRedactor redactor) {
            this.journalDir = journalDir;
            this.entryId = entryId;
            this.redactor = redactor;
        }
    }

    private final ChatLookup chats;
    private final SnapshotWriter writer;
    private final Scheduler scheduler;
    private final Duration delay;
    private final Clock clock;
    // Guarded by registrations: the chats followed, and the journals that stopped (a chat linked later
    // to a stopped journal is frozen at once).
    private final Map<String, Registration> registrations = new HashMap<>();
    private final Set<Path> stoppedJournals = new HashSet<>();

    public SessionJournalChatSync(ChatLookup chats, SnapshotWriter writer, Scheduler scheduler,
                                  Duration delay, Clock clock) {
        this.chats = Objects.requireNonNull(chats, "chats");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.delay = delay != null ? delay : DEFAULT_DELAY;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /** The production wiring: a daemon thread for the writes and the default delay. */
    public static SessionJournalChatSync create(ChatLookup chats, SnapshotWriter writer) {
        return new SessionJournalChatSync(chats, writer, daemonScheduler(), DEFAULT_DELAY, Clock.systemUTC());
    }

    /**
     * Starts following a chat whose AGENT entry {@code entryId} lives in {@code journalDir}. The entry's
     * copy is already written by the link; this only keeps it current from now on. When the journal has
     * stopped already, the copy is frozen at once and the chat is not followed.
     */
    public void register(String chatId, Path journalDir, String entryId, SessionJournalRedactor redactor) {
        if (chatId == null || journalDir == null || entryId == null) {
            return;
        }
        Registration registration = new Registration(journalDir, entryId, redactor);
        boolean stopped;
        synchronized (registrations) {
            stopped = stoppedJournals.contains(journalDir);
            if (!stopped) {
                registrations.put(chatId, registration);
            }
        }
        if (stopped) {
            executeSafely(chatId, () -> writeCopy(chatId, registration, true));
        }
    }

    /** Whether the chat is being followed. Test seam: production code asks {@link #onChatChanged} instead. */
    boolean isRegistered(String chatId) {
        synchronized (registrations) {
            return chatId != null && registrations.containsKey(chatId);
        }
    }

    /**
     * Called for every chat the manager saves or deletes. A followed chat gets its copy rewritten after
     * the quiet delay; a chat that is gone when the rewrite runs is released and keeps its last copy.
     */
    public void onChatChanged(String chatId) {
        long generation;
        synchronized (registrations) {
            Registration registration = chatId != null ? registrations.get(chatId) : null;
            if (registration == null) {
                return;
            }
            generation = ++registration.generation;
        }
        try {
            scheduler.schedule(delay, () -> rewriteIfCurrent(chatId, generation));
        } catch (RuntimeException e) {
            logger.warn("Could not schedule the journal copy of chat {}: {}", chatId, e.getMessage());
        }
    }

    /**
     * Called when the journal stops recording: the journal is remembered as stopped, and the pending copies
     * of its chats are written with {@code frozen=true} and the chats are released. Returns at once; the
     * write runs on the sync thread.
     */
    public void journalStopped(Path journalDir) {
        if (journalDir == null) {
            return;
        }
        Map<String, Registration> stopped = new HashMap<>();
        synchronized (registrations) {
            stoppedJournals.add(journalDir);
            var iterator = registrations.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Registration> entry = iterator.next();
                if (journalDir.equals(entry.getValue().journalDir)) {
                    stopped.put(entry.getKey(), entry.getValue());
                    iterator.remove();
                }
            }
        }
        for (Map.Entry<String, Registration> entry : stopped.entrySet()) {
            String chatId = entry.getKey();
            Registration registration = entry.getValue();
            executeSafely(chatId, () -> writeCopy(chatId, registration, true));
        }
    }

    /**
     * Writes the pending copies of every followed chat with {@code frozen=true}, releases the chats and
     * stops the sync thread. Used at application shutdown: no chat is re-registered after a restart, so
     * the journals it followed count as stopped.
     */
    public void close() {
        List<Map.Entry<String, Registration>> all;
        synchronized (registrations) {
            all = new ArrayList<>(registrations.entrySet());
            registrations.clear();
        }
        for (Map.Entry<String, Registration> entry : all) {
            String chatId = entry.getKey();
            Registration registration = entry.getValue();
            executeSafely(chatId, () -> writeCopy(chatId, registration, true));
        }
        scheduler.shutdown();
    }

    private void rewriteIfCurrent(String chatId, long generation) {
        Registration registration;
        synchronized (registrations) {
            registration = registrations.get(chatId);
            if (registration == null || registration.generation != generation) {
                // Released meanwhile, or a newer save scheduled its own rewrite.
                return;
            }
        }
        writeCopy(chatId, registration, false);
    }

    private void writeCopy(String chatId, Registration registration, boolean frozen) {
        Optional<SavedAiChat> chat;
        try {
            chat = chats.find(chatId);
        } catch (RuntimeException e) {
            logger.warn("Could not read chat {} for its journal copy: {}", chatId, e.getMessage());
            return;
        }
        if (chat.isEmpty()) {
            // Deleted: the journal keeps the copy it has, and that copy is final now, since the chat cannot change
            // any more, whether the journal still records or has stopped.
            release(chatId, registration);
            freezeExisting(chatId, registration);
            return;
        }
        SessionJournalChatSnapshot snapshot = AgentRunChatSeeder.journalSnapshot(
            chat.get(), registration.redactor, OffsetDateTime.now(clock));
        snapshot.setFrozen(frozen);
        try {
            if (!writer.write(registration.journalDir, registration.entryId, snapshot)) {
                // The AGENT entry is gone (its card was deleted): there is no copy left to keep up to date.
                logger.debug("Journal copy of chat {} has no entry {} to update", chatId, registration.entryId);
                release(chatId, registration);
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not update the journal copy of chat {}: {}", chatId, e.getMessage());
        }
    }

    private void freezeExisting(String chatId, Registration registration) {
        try {
            if (!writer.freeze(registration.journalDir, registration.entryId)) {
                logger.debug("Journal copy of chat {} has no entry {} to mark final", chatId, registration.entryId);
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not mark the journal copy of chat {} final: {}", chatId, e.getMessage());
        }
    }

    /** Forgets the chat, unless it was registered again for another entry meanwhile. */
    private void release(String chatId, Registration registration) {
        synchronized (registrations) {
            if (registrations.get(chatId) == registration) {
                registrations.remove(chatId);
            }
        }
    }

    private void executeSafely(String chatId, Runnable task) {
        try {
            scheduler.execute(task);
        } catch (RuntimeException e) {
            logger.warn("Could not write the journal copy of chat {}: {}", chatId, e.getMessage());
        }
    }

    private static Scheduler daemonScheduler() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SessionJournal-ChatSync");
            thread.setDaemon(true);
            return thread;
        });
        return new Scheduler() {
            @Override
            public void schedule(Duration delay, Runnable task) {
                executor.schedule(task, delay.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            }

            @Override
            public void execute(Runnable task) {
                executor.execute(task);
            }

            @Override
            public void shutdown() {
                executor.shutdown();
            }
        };
    }
}
