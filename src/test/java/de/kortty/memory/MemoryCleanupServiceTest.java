package de.kortty.memory;

import de.kortty.memory.MemoryCleanupService.CleanupException;
import de.kortty.memory.MemoryCleanupService.Problem;
import de.kortty.memory.MemoryCleanupService.Proposal;
import de.kortty.memory.MemoryDocument.KorttyEntry;
import de.kortty.memory.MemoryStore.Snapshot;
import de.kortty.memory.MemoryStore.WriteReason;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class MemoryCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final MemoryScope GLOBAL = MemoryScope.global();
    private static final String USER_NOTE = "My own note about tabs.";

    private Path root;
    private MemoryStore store;
    private final MemoryCleanupService service = new MemoryCleanupService(Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeMethod
    void createStore() throws IOException {
        root = Files.createTempDirectory("kortty-memory-cleanup-");
        store = new MemoryStore(root.resolve("memory"), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterMethod(alwaysRun = true)
    void deleteStore() throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String fence(String id, String created, String body) {
        return MemoryFormat.newEntry(id, body, LocalDate.parse(created), "chat");
    }

    private static String fileWithThreeEntries() {
        return USER_NOTE + "\n\n"
            + fence("a1a1", "2026-11-02", "- Use tabs in Perl.") + "\n"
            + fence("b2b2", "2026-11-01", "- Use tabs in Perl code.") + "\n"
            + fence("c3c3", "2026-10-30", "- Keep logs short.");
    }

    private static Snapshot snapshotOf(String text) {
        return new Snapshot(text, MemoryFormat.sha256(text), true, text.length(), null);
    }

    /** An assistant that answers with {@code reply} and records the prompts it was given. */
    private static final class FakeAssistant implements MemoryCleanupService.Assistant {

        private final String reply;
        private final List<String> userPrompts = new ArrayList<>();

        FakeAssistant(String reply) {
            this.reply = reply;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            userPrompts.add(userPrompt);
            return reply;
        }
    }

    @Test
    void mergeKeepsTheUserTextAndReplacesTheKorttyEntries() throws Exception {
        String current = fileWithThreeEntries();
        FakeAssistant assistant = new FakeAssistant("{\"rules\": [\"Use tabs in Perl.\", \"Keep logs short.\"]}");

        Proposal proposal = service.proposeMerge(GLOBAL, snapshotOf(current), assistant);

        assertThat(proposal.kind()).isEqualTo(MemoryCleanupService.Kind.MERGE_KORTTY_ENTRIES);
        assertThat(proposal.baseDigest()).isEqualTo(MemoryFormat.sha256(current));
        assertThat(MemoryFormat.userSegments(proposal.proposedText()))
            .isEqualTo(MemoryFormat.userSegments(current));
        List<String> bodies = MemoryFormat.parse(proposal.proposedText()).entries().stream()
            .map(KorttyEntry::body).toList();
        assertThat(bodies).containsExactly("- Use tabs in Perl.", "- Keep logs short.").inOrder();
    }

    @Test
    void theMergePromptCarriesOnlyTheKorttyEntries() throws Exception {
        FakeAssistant assistant = new FakeAssistant("{\"rules\": [\"Keep logs short.\"]}");

        service.proposeMerge(GLOBAL, snapshotOf(fileWithThreeEntries()), assistant);

        assertThat(assistant.userPrompts).hasSize(1);
        assertThat(assistant.userPrompts.get(0)).contains("Keep logs short.");
        assertThat(assistant.userPrompts.get(0)).doesNotContain(USER_NOTE);
    }

    @Test
    void theConfirmedMergeIsWrittenThroughTheStore() throws Exception {
        Snapshot written = store.write(GLOBAL, MemoryFormat.sha256(""), fileWithThreeEntries(),
            WriteReason.USER_SAVE, null);
        Proposal proposal = service.proposeMerge(GLOBAL, written,
            new FakeAssistant("{\"rules\": [\"Use tabs in Perl.\", \"Keep logs short.\"]}"));

        service.applyMerge(store, proposal);

        Snapshot now = store.read(GLOBAL);
        assertThat(MemoryFormat.parse(now.text()).entries()).hasSize(2);
        assertThat(MemoryFormat.userSegments(now.text())).isEqualTo(MemoryFormat.userSegments(written.text()));
        // The first write created the file, so only the merge has a previous version to keep.
        assertThat(store.history(GLOBAL)).hasSize(1);
    }

    @Test
    void aMergeAgainstAChangedFileIsRefusedByTheStore() throws Exception {
        Snapshot written = store.write(GLOBAL, MemoryFormat.sha256(""), fileWithThreeEntries(),
            WriteReason.USER_SAVE, null);
        Proposal proposal = service.proposeMerge(GLOBAL, written,
            new FakeAssistant("{\"rules\": [\"Keep logs short.\"]}"));
        store.write(GLOBAL, written.sha256(), "Changed meanwhile.\n", WriteReason.USER_SAVE, null);

        expectThrows(MemoryConflictException.class, () -> service.applyMerge(store, proposal));
    }

    @Test
    void aMalformedReplyChangesNothing() {
        CleanupException failure = expectThrows(CleanupException.class,
            () -> service.proposeMerge(GLOBAL, snapshotOf(fileWithThreeEntries()),
                new FakeAssistant("I cannot do that.")));

        assertThat(failure.problem()).isEqualTo(Problem.MALFORMED_REPLY);
    }

    @Test
    void anEmptyRuleListIsMalformed() {
        CleanupException failure = expectThrows(CleanupException.class,
            () -> service.proposeMerge(GLOBAL, snapshotOf(fileWithThreeEntries()),
                new FakeAssistant("{\"rules\": []}")));

        assertThat(failure.problem()).isEqualTo(Problem.MALFORMED_REPLY);
    }

    @Test
    void aMergeThatAddsRulesIsRefused() {
        CleanupException failure = expectThrows(CleanupException.class,
            () -> service.proposeMerge(GLOBAL, snapshotOf(fileWithThreeEntries()),
                new FakeAssistant("[\"One.\", \"Two.\", \"Three.\", \"Four.\"]")));

        assertThat(failure.problem()).isEqualTo(Problem.COUNT_MISMATCH);
    }

    @Test
    void aFileWithoutKorttyEntriesHasNothingToMerge() {
        CleanupException failure = expectThrows(CleanupException.class,
            () -> service.proposeMerge(GLOBAL, snapshotOf("Only my text.\n"),
                new FakeAssistant("[\"One.\"]")));

        assertThat(failure.problem()).isEqualTo(Problem.NOTHING_TO_MERGE);
    }

    @Test
    void anOverlongRuleIsShortenedToTheLimit() throws Exception {
        String longRule = "word ".repeat(80).strip();

        Proposal proposal = service.proposeMerge(GLOBAL, snapshotOf(fileWithThreeEntries()),
            new FakeAssistant("[\"" + longRule + "\"]"));

        assertThat(proposal.changedCount()).isEqualTo(1);
        String body = MemoryFormat.parse(proposal.proposedText()).entries().get(0).body();
        assertThat(body.length()).isAtMost(MemoryCleanupService.MAX_RULE_CHARS + 2);
        assertThat(body).startsWith("- word");
    }

    @Test
    void aRuleWithASecretIsRefused() {
        expectThrows(MemorySecretException.class,
            () -> service.proposeMerge(GLOBAL, snapshotOf(fileWithThreeEntries()),
                new FakeAssistant("[\"The password is Tr0ub4dor-Horse.\"]")));
    }

    @Test
    void aReplyWithACodeFenceAroundTheJsonIsAccepted() throws Exception {
        Proposal proposal = service.proposeMerge(GLOBAL, snapshotOf(fileWithThreeEntries()),
            new FakeAssistant("Here you go:\n```json\n{\"rules\": [\"Keep logs short.\"]}\n```"));

        assertThat(MemoryFormat.parse(proposal.proposedText()).entries()).hasSize(1);
    }

    @Test
    void archiveCandidatesAreTheEntriesOlderThanSixMonths() {
        String text = USER_NOTE + "\n\n"
            + fence("old1", "2026-01-01", "- Old rule.") + "\n"
            + fence("old2", "2026-05-05", "- Older rule.") + "\n"
            + fence("new1", "2026-06-01", "- Recent rule.");

        List<KorttyEntry> candidates = MemoryCleanupService.archiveCandidates(text, LocalDate.parse("2026-11-02"));

        assertThat(candidates.stream().map(KorttyEntry::id)).containsExactly("old1", "old2").inOrder();
    }

    @Test
    void archivedEntriesLeaveTheFileAndGoToTheArchive() throws Exception {
        store.write(GLOBAL, MemoryFormat.sha256(""), fileWithThreeEntries(), WriteReason.USER_SAVE, null);

        service.archive(store, GLOBAL, Set.of("b2b2"));

        String remaining = store.read(GLOBAL).text();
        assertThat(MemoryFormat.parse(remaining).entries().stream().map(KorttyEntry::id))
            .containsExactly("a1a1", "c3c3").inOrder();
        assertThat(Files.readString(root.resolve("memory").resolve("archive.md"))).contains("b2b2");
        assertThat(MemoryFormat.userSegments(remaining)).isEqualTo(MemoryFormat.userSegments(fileWithThreeEntries()));
    }

    @Test
    void theUserTextIsShortenedOnlyWhereTheReplyIsShorter() throws Exception {
        String current = "My own long note about how to use tabs in Perl scripts.\n\nSecond paragraph stays.\n";
        Proposal proposal = service.proposeUserShortening(GLOBAL, snapshotOf(current),
            new FakeAssistant("{\"paragraphs\": [\"Tabs in Perl.\", \"Second paragraph stays.\"]}"));

        assertThat(proposal.kind()).isEqualTo(MemoryCleanupService.Kind.SHORTEN_USER_TEXT);
        assertThat(proposal.proposedText()).isEqualTo("Tabs in Perl.\n\nSecond paragraph stays.\n");
        assertThat(proposal.changedCount()).isEqualTo(1);
    }

    @Test
    void aShorteningWithAnotherParagraphCountIsRefused() {
        CleanupException failure = expectThrows(CleanupException.class,
            () -> service.proposeUserShortening(GLOBAL,
                snapshotOf("One paragraph.\n\nAnother paragraph.\n"),
                new FakeAssistant("{\"paragraphs\": [\"Short.\"]}")));

        assertThat(failure.problem()).isEqualTo(Problem.COUNT_MISMATCH);
    }

    @Test
    void aShortenedParagraphNeverCarriesAFenceMarker() throws Exception {
        String fence = "<!-- kortty:entry id=abc123 origin=korTTY sha256=" + "0".repeat(64)
            + " created=2026-01-01 source=chat -->";
        String longNote = "My own note. ".repeat(25).strip();
        String current = "Short first note that is long enough to shorten.\n\n" + longNote + "\n";

        Proposal proposal = service.proposeUserShortening(GLOBAL, snapshotOf(current),
            new FakeAssistant("{\"paragraphs\": [\"Short.\", \"" + fence + "\"]}"));

        assertThat(proposal.proposedText()).isEqualTo("Short.\n\n" + longNote + "\n");
        assertThat(proposal.changedCount()).isEqualTo(1);
        assertThat(MemoryFormat.parse(proposal.proposedText()).entries()).isEmpty();
    }

    @Test
    void aReplyThatOnlyAddsAFenceMarkerChangesNothing() {
        String fence = "<!-- kortty:entry id=abc123 origin=korTTY sha256=" + "0".repeat(64)
            + " created=2026-01-01 source=chat -->";
        CleanupException failure = expectThrows(CleanupException.class,
            () -> service.proposeUserShortening(GLOBAL, snapshotOf("My own note. ".repeat(25).strip() + "\n"),
                new FakeAssistant("{\"paragraphs\": [\"" + fence + "\"]}")));

        assertThat(failure.problem()).isEqualTo(Problem.NOTHING_TO_CHANGE);
    }

    @Test
    void aProposalIsMadeOnlyByTheProposalMethods() {
        for (Constructor<?> constructor : Proposal.class.getDeclaredConstructors()) {
            assertThat(Modifier.isPublic(constructor.getModifiers())).isFalse();
        }
    }

    @Test
    void aLongerOrMultiParagraphReplyChangesNothing() {
        CleanupException failure = expectThrows(CleanupException.class,
            () -> service.proposeUserShortening(GLOBAL, snapshotOf("Short.\n"),
                new FakeAssistant("{\"paragraphs\": [\"A much longer version of the note.\"]}")));

        assertThat(failure.problem()).isEqualTo(Problem.NOTHING_TO_CHANGE);
    }

    @Test
    void theShorteningIsSavedAsTheUsersOwnSave() throws Exception {
        Snapshot written = store.write(GLOBAL, MemoryFormat.sha256(""),
            "My own long note about tabs.\n", WriteReason.USER_SAVE, null);
        Proposal proposal = service.proposeUserShortening(GLOBAL, written,
            new FakeAssistant("{\"paragraphs\": [\"Tabs.\"]}"));

        service.applyUserShortening(new MemoryUserActions(store), proposal);

        assertThat(store.read(GLOBAL).text()).isEqualTo("Tabs.\n");
    }

    @Test
    void aShorteningThatShowsTheMaskOfAHiddenValueIsNotTakenAsTheUsersText() {
        String current = "Secret: rotate the keys weekly. Keep the logs short and plain.\n";
        CleanupException failure = expectThrows(CleanupException.class,
            () -> service.proposeUserShortening(GLOBAL, snapshotOf(current),
                new FakeAssistant("{\"paragraphs\": [\"Secret: *** weekly. Keep the logs short.\"]}")));
        assertThat(failure.problem()).isEqualTo(Problem.NOTHING_TO_CHANGE);
    }
}
