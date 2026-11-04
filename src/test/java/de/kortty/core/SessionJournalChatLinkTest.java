package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.SavedAgentStep;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionJournalChatMessage;
import de.kortty.model.SessionJournalChatSnapshot;
import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalReplacement;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/** Linking a KI-Agent run's AGENT entry to its chat, and keeping that link through the journal's own writes. */
class SessionJournalChatLinkTest {

    private Path tempDir;
    private GlobalSettings settings;
    private SessionJournalService service;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-session-journal-chat-link-test");
        settings = new GlobalSettings();
        settings.setSessionJournalStoragePath(tempDir.resolve("journals").toString());
        service = new SessionJournalService();
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

    /** A finished journal directory the tests add their entries to. */
    private Path finishedJournal() throws IOException {
        ServerConnection connection = new ServerConnection("Test Server", "192.168.1.9", 22, "daniel");
        connection.getSessionJournalConfig().setEnabled(true);
        SessionJournalSession session = service.createSession(
            connection, "tab-1234567890ab", settings, List.of(), false);
        session.close();
        return session.getDirectory();
    }

    private SessionJournalEntry addAgentEntry(Path dir, String text) throws IOException {
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setKind(SessionJournalEntryKind.AGENT);
        entry.setTitle("Disk check");
        entry.setText(text);
        return service.appendEntry(dir, entry);
    }

    private static SessionJournalChatSnapshot snapshot(String title, String content, String stepOutput) {
        SessionJournalChatSnapshot snapshot = new SessionJournalChatSnapshot();
        snapshot.setTitle(title);
        SessionJournalChatMessage user = new SessionJournalChatMessage();
        user.setRole("USER");
        user.setContent("Check the disk");
        SessionJournalChatMessage answer = new SessionJournalChatMessage();
        answer.setRole("ASSISTANT");
        answer.setContent(content);
        SavedAgentStep step = new SavedAgentStep();
        step.setType("ACTION");
        step.setTitle("Run(df -h)");
        step.setOutput(stepOutput);
        answer.setSteps(List.of(step));
        snapshot.setMessages(List.of(user, answer));
        return snapshot;
    }

    private SessionJournalEntry entryOf(Path dir, String entryId) throws IOException {
        return service.loadDocument(dir).getEntries().stream()
            .filter(entry -> entryId.equals(entry.getId()))
            .findFirst()
            .orElseThrow();
    }

    @Test
    void linkChatStoresTheLinkAndTheCopyWithoutSettingEditedAt() throws IOException {
        Path dir = finishedJournal();
        SessionJournalEntry agent = addAgentEntry(dir, "Run: check disk");

        service.linkChat(dir, agent.getId(), "chat-1", snapshot("Disk", "80% full.", "/dev/disk1"));

        SessionJournalEntry linked = entryOf(dir, agent.getId());
        assertThat(linked.getChatId()).isEqualTo("chat-1");
        assertThat(linked.getChatLinkedAt()).isNotNull();
        assertThat(linked.getEditedAt()).isNull();
        assertThat(linked.getTitle()).isEqualTo("Disk check");
        assertThat(linked.getText()).isEqualTo("Run: check disk");
        assertThat(linked.getChatSnapshot().getTitle()).isEqualTo("Disk");
        assertThat(linked.getChatSnapshot().getMessages()).hasSize(2);
        assertThat(linked.getChatSnapshot().getMessages().get(1).getContent()).isEqualTo("80% full.");
    }

    @Test
    void linkChatTellsTheChangeListenersOnce() throws IOException {
        Path dir = finishedJournal();
        SessionJournalEntry agent = addAgentEntry(dir, "");
        AtomicInteger notified = new AtomicInteger();
        service.addChangeListener(journal -> notified.incrementAndGet());

        service.linkChat(dir, agent.getId(), "chat-1", snapshot("Disk", "ok", null));

        assertThat(notified.get()).isEqualTo(1);
    }

    @Test
    void linkChatRefusesAnEntryThatIsNotInTheJournal() throws IOException {
        Path dir = finishedJournal();

        assertThrows(IllegalStateException.class,
            () -> service.linkChat(dir, "no-such-entry", "chat-1", snapshot("Disk", "ok", null)));
    }

    @Test
    void updateChatSnapshotReplacesTheCopyWithoutSettingEditedAt() throws IOException {
        Path dir = finishedJournal();
        SessionJournalEntry agent = addAgentEntry(dir, "");
        service.linkChat(dir, agent.getId(), "chat-1", snapshot("Disk", "first", null));

        boolean updated = service.updateChatSnapshot(dir, agent.getId(), snapshot("Disk", "second answer", null));

        assertThat(updated).isTrue();
        SessionJournalEntry after = entryOf(dir, agent.getId());
        assertThat(after.getChatSnapshot().getMessages().get(1).getContent()).isEqualTo("second answer");
        assertThat(after.getChatId()).isEqualTo("chat-1");
        assertThat(after.getEditedAt()).isNull();
    }

    @Test
    void updateChatSnapshotLogsAndReturnsFalseForAnEntryThatIsGone() throws IOException {
        Path dir = finishedJournal();

        assertThat(service.updateChatSnapshot(dir, "no-such-entry", snapshot("Disk", "x", null))).isFalse();
    }

    @Test
    void replaceRewritesTheChatCopyAndDryRunCountsItWithoutWriting() throws IOException {
        Path dir = finishedJournal();
        SessionJournalEntry agent = addAgentEntry(dir, "");
        service.linkChat(dir, agent.getId(), "chat-1",
            snapshot("Disk", "Use internal-token-42 to log in.", "token internal-token-42"));
        SessionJournalReplacement rule = SessionJournalReplacement.literal("internal-token-42", "<token>");

        SessionJournalService.RedactionResult preview = service.replace(dir, rule, false, true);
        assertThat(preview.entryHits()).isEqualTo(2);
        assertThat(entryOf(dir, agent.getId()).getChatSnapshot().getMessages().get(1).getContent())
            .contains("internal-token-42");

        SessionJournalService.RedactionResult applied = service.replace(dir, rule, false, false);

        assertThat(applied.entryHits()).isEqualTo(2);
        SessionJournalChatSnapshot rewritten = entryOf(dir, agent.getId()).getChatSnapshot();
        assertThat(rewritten.getMessages().get(1).getContent()).isEqualTo("Use <token> to log in.");
        assertThat(rewritten.getMessages().get(1).getSteps().get(0).getOutput()).isEqualTo("token <token>");
    }

    @Test
    void updateEntryKeepsTheChatLinkEvenWhenTheCallerHoldsAnOlderCopy() throws IOException {
        Path dir = finishedJournal();
        SessionJournalEntry agent = addAgentEntry(dir, "");
        SessionJournalEntry staleCopy = entryOf(dir, agent.getId());
        service.linkChat(dir, agent.getId(), "chat-1", snapshot("Disk", "answer", null));

        staleCopy.setUserNote("Marked by hand");
        service.updateEntry(dir, staleCopy);

        SessionJournalEntry after = entryOf(dir, agent.getId());
        assertThat(after.getUserNote()).isEqualTo("Marked by hand");
        assertThat(after.getChatId()).isEqualTo("chat-1");
        assertThat(after.getChatSnapshot().getMessages()).hasSize(2);
        assertThat(after.getEditedAt()).isNotNull();
    }

    @Test
    void resetAiEvaluationKeepsTheAgentEntryWithItsChat() throws IOException {
        Path dir = finishedJournal();
        SessionJournalEntry agent = addAgentEntry(dir, "");
        service.linkChat(dir, agent.getId(), "chat-1", snapshot("Disk", "answer", null));
        SessionJournalEntry summary = new SessionJournalEntry();
        summary.setKind(SessionJournalEntryKind.AI_SUMMARY);
        summary.setTitle("Summary");
        service.appendEntry(dir, summary);

        int removed = service.resetAiEvaluation(dir);

        assertThat(removed).isEqualTo(1);
        SessionJournalDocument document = service.loadDocument(dir);
        assertThat(document.getEntries()).hasSize(1);
        SessionJournalEntry kept = document.getEntries().get(0);
        assertThat(kept.getKind()).isEqualTo(SessionJournalEntryKind.AGENT);
        assertThat(kept.getChatId()).isEqualTo("chat-1");
        assertThat(kept.getChatSnapshot().getMessages()).hasSize(2);
    }

    @Test
    void freezeChatSnapshotMarksTheCopyFinalAndKeepsItsText() throws IOException {
        Path dir = finishedJournal();
        SessionJournalEntry agent = addAgentEntry(dir, "");
        service.linkChat(dir, agent.getId(), "chat-1", snapshot("Disk", "the last answer", null));

        boolean frozen = service.freezeChatSnapshot(dir, agent.getId());

        assertThat(frozen).isTrue();
        SessionJournalEntry after = entryOf(dir, agent.getId());
        assertThat(after.getChatSnapshot().isFrozen()).isTrue();
        assertThat(after.getChatSnapshot().getMessages().get(1).getContent()).isEqualTo("the last answer");
        assertThat(after.getChatId()).isEqualTo("chat-1");
        assertThat(after.getEditedAt()).isNull();
    }

    @Test
    void freezeChatSnapshotReturnsFalseForAnEntryThatIsGoneOrHasNoCopy() throws IOException {
        Path dir = finishedJournal();
        SessionJournalEntry agent = addAgentEntry(dir, "");

        assertThat(service.freezeChatSnapshot(dir, "no-such-entry")).isFalse();
        assertThat(service.freezeChatSnapshot(dir, agent.getId())).isFalse();
    }
}
