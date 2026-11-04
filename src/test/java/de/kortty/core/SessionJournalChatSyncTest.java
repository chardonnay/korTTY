package de.kortty.core;

import de.kortty.model.SavedAiChat;
import de.kortty.model.SavedAiChatMessage;
import de.kortty.model.SessionJournalChatSnapshot;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.NoSuchFileException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;

/** The live copy of a KI-chat in its session journal: debounced writes, the freeze on stop, deleted chats. */
class SessionJournalChatSyncTest {

    private static final Path JOURNAL = Paths.get("/tmp/journal-under-test");
    private static final String ENTRY = "entry-1";

    private Map<String, SavedAiChat> chats;
    private List<Written> writes;
    private List<String> freezes;
    private ManualScheduler scheduler;
    private SessionJournalChatSync sync;
    private IOException nextFailure;
    private boolean entryMissing;

    /** One call of the journal writer, with the title and frozen flag it stored. */
    private record Written(Path dir, String entryId, String title, boolean frozen) {
    }

    @BeforeMethod
    void setUp() {
        chats = new HashMap<>();
        writes = new ArrayList<>();
        freezes = new ArrayList<>();
        scheduler = new ManualScheduler();
        nextFailure = null;
        entryMissing = false;
        sync = new SessionJournalChatSync(
            chatId -> Optional.ofNullable(chats.get(chatId)).map(SavedAiChat::new),
            new SessionJournalChatSync.SnapshotWriter() {
                @Override
                public boolean write(Path dir, String entryId, SessionJournalChatSnapshot snapshot) throws IOException {
                    if (nextFailure != null) {
                        IOException failure = nextFailure;
                        nextFailure = null;
                        throw failure;
                    }
                    if (entryMissing) {
                        return false;
                    }
                    writes.add(new Written(dir, entryId, snapshot.getTitle(), snapshot.isFrozen()));
                    return true;
                }

                @Override
                public boolean freeze(Path dir, String entryId) {
                    freezes.add(entryId);
                    return true;
                }
            },
            scheduler,
            SessionJournalChatSync.DEFAULT_DELAY,
            Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void aBurstOfSavesWritesTheCopyOnce() {
        SavedAiChat chat = chat("first");
        chats.put(chat.getId(), chat);
        sync.register(chat.getId(), JOURNAL, ENTRY, null);

        for (int i = 1; i <= 5; i++) {
            chat.setTitle("save " + i);
            sync.onChatChanged(chat.getId());
            scheduler.advance(Duration.ofMillis(300));
        }
        assertThat(writes).isEmpty();

        scheduler.advance(SessionJournalChatSync.DEFAULT_DELAY);
        assertThat(writes).hasSize(1);
        assertThat(writes.get(0).title()).isEqualTo("save 5");
        assertThat(writes.get(0).entryId()).isEqualTo(ENTRY);
        assertThat(writes.get(0).frozen()).isFalse();
    }

    @Test
    void unregisteredChatsAreNotWritten() {
        SavedAiChat chat = chat("not followed");
        chats.put(chat.getId(), chat);

        sync.onChatChanged(chat.getId());
        scheduler.advance(Duration.ofSeconds(10));

        assertThat(writes).isEmpty();
        assertThat(sync.isRegistered(chat.getId())).isFalse();
    }

    @Test
    void stoppingTheJournalFlushesAFrozenCopyAndReleasesTheChat() {
        SavedAiChat chat = chat("before stop");
        chats.put(chat.getId(), chat);
        sync.register(chat.getId(), JOURNAL, ENTRY, null);
        chat.setTitle("latest");
        sync.onChatChanged(chat.getId());

        sync.journalStopped(JOURNAL);
        scheduler.advance(Duration.ZERO);

        assertThat(writes).hasSize(1);
        assertThat(writes.get(0).title()).isEqualTo("latest");
        assertThat(writes.get(0).frozen()).isTrue();
        assertThat(sync.isRegistered(chat.getId())).isFalse();

        chat.setTitle("after stop");
        sync.onChatChanged(chat.getId());
        scheduler.advance(Duration.ofSeconds(10));
        assertThat(writes).hasSize(1);
    }

    @Test
    void stoppingAnotherJournalLeavesTheChatFollowed() {
        SavedAiChat chat = chat("followed");
        chats.put(chat.getId(), chat);
        sync.register(chat.getId(), JOURNAL, ENTRY, null);

        sync.journalStopped(Paths.get("/tmp/some-other-journal"));
        scheduler.advance(Duration.ZERO);

        assertThat(writes).isEmpty();
        assertThat(sync.isRegistered(chat.getId())).isTrue();
    }

    @Test
    void aDeletedChatKeepsTheLastCopyAndIsReleased() {
        SavedAiChat chat = chat("kept");
        chats.put(chat.getId(), chat);
        sync.register(chat.getId(), JOURNAL, ENTRY, null);
        sync.onChatChanged(chat.getId());
        scheduler.advance(SessionJournalChatSync.DEFAULT_DELAY);
        assertThat(writes).hasSize(1);

        chats.remove(chat.getId());
        sync.onChatChanged(chat.getId());
        scheduler.advance(Duration.ofSeconds(10));

        assertThat(writes).hasSize(1);
        // The chat cannot change any more, so its copy is final even while the journal keeps recording.
        assertThat(freezes).containsExactly(ENTRY);
        assertThat(sync.isRegistered(chat.getId())).isFalse();
    }

    @Test
    void aJournalDirectoryThatIsGoneDoesNotThrow() {
        SavedAiChat chat = chat("orphan");
        chats.put(chat.getId(), chat);
        sync.register(chat.getId(), JOURNAL, ENTRY, null);
        nextFailure = new NoSuchFileException(JOURNAL.toString());

        sync.onChatChanged(chat.getId());
        scheduler.advance(SessionJournalChatSync.DEFAULT_DELAY);
        sync.journalStopped(JOURNAL);
        scheduler.advance(Duration.ZERO);

        assertThat(writes).hasSize(1);
        assertThat(writes.get(0).frozen()).isTrue();
    }

    @Test
    void closeWritesThePendingCopiesFrozenAndReleasesTheChats() {
        SavedAiChat chat = chat("at shutdown");
        chats.put(chat.getId(), chat);
        sync.register(chat.getId(), JOURNAL, ENTRY, null);

        sync.close();
        scheduler.advance(Duration.ZERO);

        assertThat(writes).hasSize(1);
        assertThat(writes.get(0).frozen()).isTrue();
        assertThat(sync.isRegistered(chat.getId())).isFalse();
        assertThat(scheduler.shutdown).isTrue();
    }

    @Test
    void aChatLinkedAfterTheJournalStoppedIsFrozenAtOnceAndNotFollowed() {
        SavedAiChat chat = chat("linked too late");
        chats.put(chat.getId(), chat);
        sync.journalStopped(JOURNAL);
        scheduler.advance(Duration.ZERO);

        sync.register(chat.getId(), JOURNAL, ENTRY, null);
        scheduler.advance(Duration.ZERO);

        assertThat(writes).hasSize(1);
        assertThat(writes.get(0).frozen()).isTrue();
        assertThat(sync.isRegistered(chat.getId())).isFalse();

        chat.setTitle("after the stop");
        sync.onChatChanged(chat.getId());
        scheduler.advance(Duration.ofSeconds(10));
        assertThat(writes).hasSize(1);
    }

    @Test
    void aChatDeletedBeforeTheJournalStopsIsMarkedFinalWithoutItsText() {
        SavedAiChat chat = chat("deleted first");
        chats.put(chat.getId(), chat);
        sync.register(chat.getId(), JOURNAL, ENTRY, null);
        chats.remove(chat.getId());

        sync.journalStopped(JOURNAL);
        scheduler.advance(Duration.ZERO);

        assertThat(writes).isEmpty();
        assertThat(freezes).containsExactly(ENTRY);
        assertThat(sync.isRegistered(chat.getId())).isFalse();
    }

    @Test
    void aChatWhoseJournalEntryIsGoneIsReleasedAndNotWrittenAgain() {
        SavedAiChat chat = chat("card deleted");
        chats.put(chat.getId(), chat);
        sync.register(chat.getId(), JOURNAL, ENTRY, null);
        entryMissing = true;

        sync.onChatChanged(chat.getId());
        scheduler.advance(SessionJournalChatSync.DEFAULT_DELAY);

        assertThat(sync.isRegistered(chat.getId())).isFalse();
        assertThat(writes).isEmpty();
        sync.journalStopped(JOURNAL);
        scheduler.advance(Duration.ZERO);
        assertThat(freezes).isEmpty();
    }

    private static SavedAiChat chat(String title) {
        SavedAiChat chat = new SavedAiChat();
        chat.setTitle(title);
        SavedAiChatMessage message = new SavedAiChatMessage();
        message.setRole(SavedAiChatMessage.ROLE_USER);
        message.setContent("question");
        chat.setMessages(List.of(message));
        return chat;
    }

    /** Runs tasks in due order when the test advances the clock; nothing runs on its own. */
    private static final class ManualScheduler implements SessionJournalChatSync.Scheduler {

        private record Task(long dueMillis, long sequence, Runnable run) {
        }

        private final List<Task> tasks = new ArrayList<>();
        private long nowMillis;
        private long sequence;
        private boolean shutdown;

        @Override
        public void schedule(Duration delay, Runnable task) {
            tasks.add(new Task(nowMillis + delay.toMillis(), sequence++, task));
        }

        @Override
        public void execute(Runnable task) {
            tasks.add(new Task(nowMillis, sequence++, task));
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        void advance(Duration duration) {
            nowMillis += duration.toMillis();
            while (true) {
                Task next = null;
                for (Task task : tasks) {
                    if (task.dueMillis() <= nowMillis && (next == null
                        || task.dueMillis() < next.dueMillis()
                        || (task.dueMillis() == next.dueMillis() && task.sequence() < next.sequence()))) {
                        next = task;
                    }
                }
                if (next == null) {
                    return;
                }
                tasks.remove(next);
                next.run().run();
            }
        }
    }
}
