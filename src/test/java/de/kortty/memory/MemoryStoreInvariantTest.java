package de.kortty.memory;

import de.kortty.memory.MemoryDocument.KorttyEntry;
import de.kortty.memory.MemoryDocument.Segment;
import de.kortty.memory.MemoryDocument.UserText;
import de.kortty.memory.MemoryStore.HistoryEntry;
import de.kortty.memory.MemoryStore.Snapshot;
import de.kortty.memory.MemoryStore.WriteReason;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

class MemoryStoreInvariantTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String EMPTY_DIGEST = MemoryFormat.sha256("");
    private static final String[] WORDS = {
        "Use", "tabs", "for", "Perl", "state", "variables", "prefer", "ssh", "agent", "forwarding",
        "keep", "logs", "short", "review", "the", "deploy", "notes"};

    private Path root;
    private Path dir;
    private MemoryStore store;

    @BeforeMethod
    void createStore() throws IOException {
        root = Files.createTempDirectory("kortty-memory-invariant-");
        dir = root.resolve("memory");
        store = new MemoryStore(dir, Clock.fixed(NOW, ZoneOffset.UTC));
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

    private static MemoryConsent consent(MemoryScope scope) {
        return MemoryConsent.grant(scope, NOW);
    }

    private static String entry(String id, String body) {
        return MemoryFormat.newEntry(id, body, LocalDate.of(2026, 11, 2), "chat");
    }

    private static List<KorttyEntry> entriesOf(String text) {
        return MemoryFormat.parse(text).entries();
    }

    @Test
    void userSaveWritesAndSnapshotsThePreviousContent() throws IOException {
        MemoryScope global = MemoryScope.global();
        Snapshot first = store.write(global, EMPTY_DIGEST, "Use tabs.\n", WriteReason.USER_SAVE, null);
        assertThat(first.text()).isEqualTo("Use tabs.\n");
        assertThat(store.history(global)).isEmpty();

        store.write(global, first.sha256(), "Use spaces.\n", WriteReason.USER_SAVE, null);

        List<HistoryEntry> history = store.history(global);
        assertThat(history).hasSize(1);
        assertThat(store.readHistory(history.get(0))).isEqualTo("Use tabs.\n");
    }

    @Test
    void staleDigestIsRefusedAndNothingIsOverwritten() throws IOException {
        MemoryScope global = MemoryScope.global();
        store.write(global, EMPTY_DIGEST, "Current.\n", WriteReason.USER_SAVE, null);

        assertThrows(MemoryConflictException.class,
            () -> store.write(global, EMPTY_DIGEST, "Stale editor.\n", WriteReason.USER_SAVE, null));
        assertThat(store.read(global).text()).isEqualTo("Current.\n");
    }

    @Test
    void secretTextIsRefusedAndTheFileStaysAsItWas() throws IOException {
        MemoryScope global = MemoryScope.global();
        Snapshot current = store.write(global, EMPTY_DIGEST, "Keep this.\n", WriteReason.USER_SAVE, null);

        MemorySecretException refused = expectThrows(MemorySecretException.class,
            () -> store.write(global, current.sha256(), "Passwort: hunter2\n", WriteReason.USER_SAVE, null));
        assertThat(refused.finding().category()).isEqualTo(de.kortty.core.AiSecretGuard.Category.PROSE_SECRET);
        assertThat(store.read(global).text()).isEqualTo("Keep this.\n");
    }

    @Test
    void korttyWriteWithoutConsentForThatFileIsRefused() throws IOException {
        MemoryScope global = MemoryScope.global();
        Snapshot current = store.write(global, EMPTY_DIGEST, "Team rule.\n", WriteReason.USER_SAVE, null);
        String withEntry = current.text() + entry("3fa9", "- Use state.");

        assertThrows(MemoryInvariantException.class,
            () -> store.write(global, current.sha256(), withEntry, WriteReason.KORTTY_CONSENTED, null));
        assertThrows(MemoryInvariantException.class,
            () -> store.write(global, current.sha256(), withEntry, WriteReason.KORTTY_CONSENTED,
                consent(MemoryScope.connection("conn-1"))));
        assertThat(store.read(global).text()).isEqualTo("Team rule.\n");
    }

    @Test
    void korttyWriteThatChangesUserTextIsRefusedEvenWithConsent() throws IOException {
        MemoryScope global = MemoryScope.global();
        Snapshot current = store.write(global, EMPTY_DIGEST, "Team rule.\n" + entry("3fa9", "- Korttly."),
            WriteReason.USER_SAVE, null);
        String changed = current.text().replace("Team rule.", "Team rule, rewritten by korTTY.");

        assertThrows(MemoryInvariantException.class,
            () -> store.write(global, current.sha256(), changed, WriteReason.KORTTY_CONSENTED, consent(global)));
        assertThat(store.read(global).text()).isEqualTo(current.text());
    }

    @Test
    void korttyWriteThatOnlyRemovesEntriesIsAcceptedWithConsent() throws IOException {
        MemoryScope global = MemoryScope.global();
        store.write(global, EMPTY_DIGEST, "Team rule.\n", WriteReason.USER_SAVE, null);
        Snapshot appended = store.appendKorttyEntry(global, "Use state.", "chat", consent(global));
        Snapshot withoutEntries = store.write(global, appended.sha256(), "Team rule.\n",
            WriteReason.KORTTY_CONSENTED, consent(global));
        assertThat(withoutEntries.text()).isEqualTo("Team rule.\n");
    }

    @Test
    void appendAddsABulletEntryAndKeepsTheUserText() throws IOException {
        MemoryScope global = MemoryScope.global();
        store.write(global, EMPTY_DIGEST, "My own notes.\n", WriteReason.USER_SAVE, null);

        Snapshot result = store.appendKorttyEntry(global, "Use state\n  for static  variables.", "chat", consent(global));

        assertThat(result.text()).startsWith("My own notes.\n");
        List<KorttyEntry> entries = entriesOf(result.text());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).body()).isEqualTo("- Use state for static variables.");
        assertThat(entries.get(0).source()).isEqualTo("chat");
        assertThat(entries.get(0).created()).isEqualTo("2026-11-02");
    }

    @Test
    void appendRefusesRulesLongerThanTheEntryLimit() {
        assertThrows(MemoryLimitException.class, () -> store.appendKorttyEntry(
            MemoryScope.global(), "x".repeat(MemoryStore.MAX_ENTRY_CHARS + 1), "chat", consent(MemoryScope.global())));
    }

    @Test
    void appendRefusesWhenTheFileWouldPassItsSoftLimit() throws IOException {
        MemoryScope global = MemoryScope.global();
        assertThrows(MemoryLimitException.class, () -> store.appendKorttyEntry(
            global, "Use state for static variables in every Perl module.", "chat", consent(global), 1));
        assertThat(Files.exists(store.fileOf(global))).isFalse();
    }

    @Test
    void appendRetriesOnceWhenTheFileChangedUnderneath() throws IOException {
        MemoryScope global = MemoryScope.global();
        store.write(global, EMPTY_DIGEST, "My notes.\n", WriteReason.USER_SAVE, null);
        store.runBeforeNextCommit(() -> {
            try {
                Files.writeString(store.fileOf(global), "My notes.\nAdded in an editor.\n");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        Snapshot result = store.appendKorttyEntry(global, "Keep logs short.", "chat", consent(global));

        assertThat(result.text()).contains("Added in an editor.");
        assertThat(entriesOf(result.text())).hasSize(1);
    }

    @Test
    void writesAboveTheHardCapAreRefused() throws IOException {
        MemoryScope global = MemoryScope.global();
        String tooBig = "a".repeat((int) MemoryStore.MAX_BYTES + 1);
        assertThrows(MemoryLimitException.class,
            () -> store.write(global, EMPTY_DIGEST, tooBig, WriteReason.USER_SAVE, null));
        assertThat(Files.exists(store.fileOf(global))).isFalse();
    }

    @Test
    void clearWritesAnEmptyFileAndKeepsTheSnapshot() throws IOException {
        MemoryScope global = MemoryScope.global();
        Snapshot current = store.write(global, EMPTY_DIGEST, "Something.\n", WriteReason.USER_SAVE, null);
        Snapshot cleared = store.write(global, current.sha256(), "", WriteReason.USER_CLEAR, null);

        assertThat(Files.exists(store.fileOf(global))).isTrue();
        assertThat(cleared.size()).isEqualTo(0);
        assertThat(store.readHistory(store.history(global).get(0))).isEqualTo("Something.\n");
    }

    @Test
    void historyKeepsTheNewest100SnapshotsPerScope() throws IOException {
        MemoryScope global = MemoryScope.global();
        String digest = EMPTY_DIGEST;
        for (int version = 0; version < 105; version++) {
            digest = store.write(global, digest, "Version " + version + "\n", WriteReason.USER_SAVE, null).sha256();
        }

        List<HistoryEntry> history = store.history(global);
        assertThat(history).hasSize(MemoryStore.HISTORY_KEEP_PER_SCOPE);
        assertThat(store.readHistory(history.get(0))).isEqualTo("Version 103\n");
    }

    @Test
    void historyOlderThanAYearIsPruned() throws IOException {
        MemoryScope global = MemoryScope.global();
        Instant longAgo = NOW.minus(java.time.Duration.ofDays(400));
        MemoryStore old = new MemoryStore(dir, Clock.fixed(longAgo, ZoneOffset.UTC));
        String digest = EMPTY_DIGEST;
        for (int version = 0; version < 3; version++) {
            digest = old.write(global, digest, "Old " + version + "\n", WriteReason.USER_SAVE, null).sha256();
        }
        assertThat(store.history(global)).hasSize(2);

        store.write(global, digest, "Fresh.\n", WriteReason.USER_SAVE, null);

        List<HistoryEntry> history = store.history(global);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).takenAt()).isEqualTo(NOW);
    }

    @Test
    void archiveMovesOnlyTheChosenKorttyEntries() throws IOException {
        MemoryScope global = MemoryScope.global();
        store.write(global, EMPTY_DIGEST, "Team rule.\n", WriteReason.USER_SAVE, null);
        Snapshot first = store.appendKorttyEntry(global, "First rule.", "chat", consent(global));
        Snapshot second = store.appendKorttyEntry(global, "Second rule.", "chat", consent(global));
        String firstId = entriesOf(first.text()).get(0).id();
        String secondId = entriesOf(second.text()).get(1).id();
        String before = MemoryFormat.userSegments(second.text());

        Snapshot after = store.archiveKorttyEntries(global, Set.of(firstId), consent(global));

        assertThat(entriesOf(after.text())).hasSize(1);
        assertThat(entriesOf(after.text()).get(0).id()).isEqualTo(secondId);
        assertThat(after.text()).contains("Team rule.");
        assertThat(MemoryFormat.userSegments(after.text())).isEqualTo(before);
        String archive = Files.readString(dir.resolve(MemoryStore.ARCHIVE_FILE_NAME));
        assertThat(archive).contains("- First rule.");
        assertThat(archive).doesNotContain("- Second rule.");
    }

    @Test
    void aSecretOnTheLineOfAFenceMarkerIsRefused() throws IOException {
        MemoryScope global = MemoryScope.global();
        Snapshot current = store.write(global, EMPTY_DIGEST, "Keep this.\n", WriteReason.USER_SAVE, null);

        assertThrows(MemorySecretException.class, () -> store.write(global, current.sha256(),
            "Passwort ist hunter2hunter2 <!-- kortty:entry -->\n", WriteReason.USER_SAVE, null));
        assertThrows(MemorySecretException.class, () -> store.write(global, current.sha256(),
            "db_password=Hunter2xyz9 <!-- /kortty:entry -->\n", WriteReason.USER_SAVE, null));
        assertThat(store.read(global).text()).isEqualTo("Keep this.\n");
    }

    @Test
    void theStartupScanFindsASecretOnTheLineOfAFenceMarker() throws IOException {
        MemoryScope global = MemoryScope.global();
        Files.createDirectories(store.fileOf(global).getParent());
        Files.writeString(store.fileOf(global), "Passwort ist hunter2hunter2 <!-- kortty:entry -->\n");

        assertThat(store.secretFindings(global)).isNotEmpty();
    }

    @Test
    void aKorttyWriteThatChangesLineEndingsOrBlankLinesOfUserTextIsRefused() throws IOException {
        MemoryScope global = MemoryScope.global();
        Snapshot crlf = store.write(global, EMPTY_DIGEST, "A.\r\nB.\r\n", WriteReason.USER_SAVE, null);
        assertThrows(MemoryInvariantException.class, () -> store.write(global, crlf.sha256(),
            "A.\nB.\n" + entry("3fa9", "- Rule."), WriteReason.KORTTY_CONSENTED, consent(global)));

        Snapshot paragraphs = store.write(global, crlf.sha256(), "Team rule A.\n\nTeam rule B.\n",
            WriteReason.USER_SAVE, null);
        assertThrows(MemoryInvariantException.class, () -> store.write(global, paragraphs.sha256(),
            "Team rule A.\nTeam rule B.\n" + entry("3fa9", "- Rule."), WriteReason.KORTTY_CONSENTED, consent(global)));
        assertThat(store.read(global).text()).isEqualTo("Team rule A.\n\nTeam rule B.\n");
    }

    @Test
    void oneConsentCoversOneKorttyWrite() throws IOException {
        MemoryScope global = MemoryScope.global();
        MemoryConsent once = consent(global);
        Snapshot first = store.appendKorttyEntry(global, "First rule.", "chat", once);

        assertThrows(MemoryInvariantException.class,
            () -> store.appendKorttyEntry(global, "Second rule.", "chat", once));
        assertThat(entriesOf(store.read(global).text())).hasSize(1);
        assertThat(entriesOf(first.text())).hasSize(1);
    }

    @Test
    void connectionMemoryIsAFileOfItsOwn() throws IOException {
        MemoryScope connection = MemoryScope.connection("conn-42");
        store.write(MemoryScope.global(), EMPTY_DIGEST, "Global.\n", WriteReason.USER_SAVE, null);
        store.write(connection, EMPTY_DIGEST, "Host note.\n", WriteReason.USER_SAVE, null);

        assertThat(store.read(MemoryScope.global()).text()).isEqualTo("Global.\n");
        assertThat(store.read(connection).text()).isEqualTo("Host note.\n");
        assertThat(Files.exists(dir.resolve("connections").resolve("conn-42.md"))).isTrue();
    }

    @Test
    void listenersHearAboutEveryWrite() throws IOException {
        List<MemoryScope> heard = new ArrayList<>();
        store.addChangeListener(heard::add);
        store.write(MemoryScope.global(), EMPTY_DIGEST, "One.\n", WriteReason.USER_SAVE, null);
        assertThat(heard).containsExactly(MemoryScope.global());
    }

    @Test
    void stampChangesWhenTheFileChanges() throws IOException {
        MemoryScope global = MemoryScope.global();
        assertThat(store.stamp(global).exists()).isFalse();
        store.write(global, EMPTY_DIGEST, "One.\n", WriteReason.USER_SAVE, null);
        assertThat(store.stamp(global).exists()).isTrue();
        assertThat(store.stamp(global).size()).isEqualTo(5);
    }

    @Test
    void randomDocumentsKeepTheUserTextAcrossKorttyWrites() throws IOException {
        Random random = new Random(0x5EEDL);
        MemoryScope scope = MemoryScope.global();
        for (int round = 0; round < 25; round++) {
            MemoryStore roundStore = new MemoryStore(root.resolve("round-" + round), Clock.fixed(NOW, ZoneOffset.UTC));
            String initial = randomDocument(random);
            assertThat(MemoryFormat.parse(initial).toText()).isEqualTo(initial);
            // An edited entry is saved only after its intact entry was stored: its header must already be in the file.
            String intact = initial.replace("\n- edited ", "\n- ");
            roundStore.write(scope, EMPTY_DIGEST, intact, WriteReason.USER_SAVE, null);
            if (!intact.equals(initial)) {
                roundStore.write(scope, roundStore.read(scope).sha256(), initial, WriteReason.USER_SAVE, null);
            }

            for (int step = 0; step < 6; step++) {
                Snapshot before = roundStore.read(scope);
                String userBefore = MemoryFormat.userSegments(before.text());
                switch (random.nextInt(4)) {
                    case 0 -> appendOrSkipOnSoftLimit(roundStore, scope, randomRule(random));
                    case 1 -> archiveRandomEntries(roundStore, scope, random, before.text());
                    case 2 -> {
                        String mutated = before.text() + "Mutation " + step + " " + random.nextInt(1000) + "\n";
                        assertThrows(MemoryInvariantException.class, () -> roundStore.write(scope, before.sha256(),
                            mutated, WriteReason.KORTTY_CONSENTED, consent(scope)));
                    }
                    default -> roundStore.write(scope, before.sha256(), userOnly(before.text()),
                        WriteReason.KORTTY_CONSENTED, consent(scope));
                }
                Snapshot after = roundStore.read(scope);
                assertWithMessage("round " + round + " step " + step)
                    .that(MemoryFormat.userSegments(after.text())).isEqualTo(userBefore);
                assertWithMessage("raw user lines, round " + round + " step " + step)
                    .that(rawUserLines(after.text())).isEqualTo(rawUserLines(before.text()));
                assertThat(MemoryFormat.parse(after.text()).toText()).isEqualTo(after.text());
            }
        }
    }

    @Test
    void onlyTheHistoryPruningDeletesFiles() throws IOException {
        Path source = Path.of("src/main/java/de/kortty/memory/MemoryStore.java");
        String text = Files.readString(source).replace("\r\n", "\n");
        Matcher deletion = Pattern.compile("Files\\.(delete|deleteIfExists)\\(([^;]*)\\);").matcher(text);
        int found = 0;
        while (deletion.find()) {
            found++;
            assertThat(deletion.group(2)).isEqualTo("entry.file()");
        }
        assertThat(found).isEqualTo(1);
    }

    private static void appendOrSkipOnSoftLimit(MemoryStore store, MemoryScope scope, String rule) throws IOException {
        try {
            store.appendKorttyEntry(scope, rule, "chat", consent(scope));
        } catch (MemoryLimitException softLimit) {
            assertThat(softLimit.getMessage()).contains("soft limit");
        }
    }

    private static void archiveRandomEntries(MemoryStore store, MemoryScope scope, Random random, String text)
        throws IOException {
        Set<String> ids = new java.util.HashSet<>();
        for (KorttyEntry entry : entriesOf(text)) {
            if (random.nextBoolean()) {
                ids.add(entry.id());
            }
        }
        store.archiveKorttyEntries(scope, ids, consent(scope));
    }

    /** The user's text with every fence removed; a blank line stays between two paragraphs a fence separated. */
    private static String userOnly(String text) {
        StringBuilder user = new StringBuilder();
        boolean fenceBetween = false;
        for (Segment segment : MemoryFormat.parse(text).segments()) {
            if (segment instanceof KorttyEntry) {
                fenceBetween = true;
                continue;
            }
            String trimmed = MemoryFormat.trimBlankEdges(((UserText) segment).text());
            if (trimmed.isEmpty()) {
                continue;
            }
            if (fenceBetween && user.length() > 0) {
                user.append('\n');
            }
            user.append(trimmed);
            fenceBetween = false;
        }
        return user.toString();
    }

    /** Every line of user text with its line ending, except the blank ones. */
    private static List<String> rawUserLines(String text) {
        List<String> lines = new ArrayList<>();
        for (Segment segment : MemoryFormat.parse(text).segments()) {
            if (segment instanceof UserText userText) {
                for (String line : userText.text().split("(?<=\\n)")) {
                    if (!line.replace("\r", "").replace("\n", "").isEmpty()) {
                        lines.add(line);
                    }
                }
            }
        }
        return lines;
    }

    private static String randomDocument(Random random) {
        StringBuilder text = new StringBuilder();
        int parts = 1 + random.nextInt(7);
        for (int part = 0; part < parts; part++) {
            switch (random.nextInt(6)) {
                case 0 -> text.append(randomUserLine(random)).append('\n');
                case 1 -> text.append('\n');
                case 2 -> text.append(entry(randomId(random), "- " + randomRule(random)));
                // An edited entry whose header was removed: the body changed and the fence is gone.
                case 3 -> text.append("- edited ").append(randomRule(random)).append('\n');
                // An edited entry whose header stayed: the body changed, the header keeps the old hash.
                case 4 -> text.append(editedFence(random));
                default -> text.append(randomUserLine(random)).append("\r\n");
            }
        }
        return text.toString();
    }

    /** A fence whose body was edited after it was written: the header keeps the hash of the body it was written with. */
    private static String editedFence(Random random) {
        return entry(randomId(random), "- " + randomRule(random)).replace("\n- ", "\n- edited ");
    }

    private static String randomUserLine(Random random) {
        return random.nextInt(4) == 0 ? "# " + randomRule(random) : randomRule(random);
    }

    private static String randomRule(Random random) {
        StringBuilder rule = new StringBuilder();
        int words = 2 + random.nextInt(6);
        for (int word = 0; word < words; word++) {
            if (word > 0) {
                rule.append(' ');
            }
            rule.append(WORDS[random.nextInt(WORDS.length)]);
        }
        return rule.append('.').toString();
    }

    private static String randomId(Random random) {
        return String.format("%06x", random.nextInt(0x1000000));
    }


    @Test
    void archivingAFenceKeepsTheBlankLineThatSeparatesTheUserParagraphs() throws IOException {
        MemoryScope global = MemoryScope.global();
        String text = "Para A: keep this note.\n\n" + entry("old001", "- Old rule.") + "Para B: also keep this note.\n";
        store.write(global, EMPTY_DIGEST, text, WriteReason.USER_SAVE, null);

        Snapshot after = store.archiveKorttyEntries(global, Set.of("old001"), consent(global));

        assertThat(after.text()).isEqualTo("Para A: keep this note.\n\nPara B: also keep this note.\n");
        assertThat(MemoryFormat.userSegments(after.text())).isEqualTo(MemoryFormat.userSegments(text));
    }

    @Test
    void archivingAFenceBetweenTwoNotesOfTheUserKeepsThemApart() throws IOException {
        MemoryScope global = MemoryScope.global();
        store.write(global, EMPTY_DIGEST, "User note one.\n\n" + entry("old002", "- Old rule.")
            + "User note typed later.\n", WriteReason.USER_SAVE, null);

        Snapshot after = store.archiveKorttyEntries(global, Set.of("old002"), consent(global));

        assertThat(after.text()).isEqualTo("User note one.\n\nUser note typed later.\n");
    }

    @Test
    void aRefusedArchiveLeavesTheArchiveAndTheMemoryFileAsTheyWere() throws IOException {
        MemoryScope global = MemoryScope.global();
        Path file = store.fileOf(global);
        Files.createDirectories(file.getParent());
        // Past the store's own limit, as an outside editor could leave it: the commit refuses, so no archive may be written.
        String oversized = "Long user text.\n".repeat(4200) + entry("old003", "- Old rule.");
        Files.writeString(file, oversized);

        assertThrows(MemoryLimitException.class,
            () -> store.archiveKorttyEntries(global, Set.of("old003"), consent(global)));

        assertThat(Files.exists(dir.resolve(MemoryStore.ARCHIVE_FILE_NAME))).isFalse();
        assertThat(Files.readString(file)).isEqualTo(oversized);
    }

    @Test
    void archivingTheSameFenceAgainDoesNotDuplicateItInTheArchive() throws IOException {
        MemoryScope global = MemoryScope.global();
        String fence = entry("old004", "- Old rule.");
        store.write(global, EMPTY_DIGEST, "Team rule.\n\n" + fence, WriteReason.USER_SAVE, null);
        store.archiveKorttyEntries(global, Set.of("old004"), consent(global));
        String archived = Files.readString(dir.resolve(MemoryStore.ARCHIVE_FILE_NAME));

        // As if the commit had failed after the archive was written: the fence is back in the memory file.
        store.write(global, store.read(global).sha256(), "Team rule.\n\n" + fence, WriteReason.USER_SAVE, null);
        store.archiveKorttyEntries(global, Set.of("old004"), consent(global));

        assertThat(Files.readString(dir.resolve(MemoryStore.ARCHIVE_FILE_NAME))).isEqualTo(archived);
        assertThat(archived).contains("- Old rule.");
    }

    @Test
    void aSecretTypedIntoAFenceHeaderIsRefusedAndTheFileStaysAsItWas() throws IOException {
        MemoryScope global = MemoryScope.global();
        Snapshot current = store.write(global, EMPTY_DIGEST, "Keep this.\n", WriteReason.USER_SAVE, null);
        String hash = "0123456789abcdef".repeat(4);
        String typed = "<!-- kortty:entry id=3fa9 origin=korTTY sha256=" + hash
            + " created=2026-11-02 source=chat -->\n- A body the user typed.\n<!-- /kortty:entry -->\n";

        assertThrows(MemorySecretException.class, () -> store.write(global, current.sha256(),
            "Keep this.\n" + typed, WriteReason.USER_SAVE, null));
        assertThat(store.read(global).text()).isEqualTo("Keep this.\n");
    }

    @Test
    void anEditedEntryIsSavedAgainWhileItsStoredHeaderStays() throws IOException {
        MemoryScope global = MemoryScope.global();
        Snapshot current = store.write(global, EMPTY_DIGEST, entry("3fa9", "- The first body."),
            WriteReason.USER_SAVE, null);
        String header = current.text().split("\n")[0];
        String edited = header + "\n- A body the user changed.\n<!-- /kortty:entry -->\n";

        Snapshot saved = store.write(global, current.sha256(), edited, WriteReason.USER_SAVE, null);

        assertThat(saved.text()).isEqualTo(edited);
    }
}
