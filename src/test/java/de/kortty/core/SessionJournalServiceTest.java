package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMarker;
import de.kortty.model.SessionJournalMeta;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

class SessionJournalServiceTest {

    private Path tempDir;
    private GlobalSettings settings;
    private SessionJournalService service;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-session-journal-service-test");
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

    private ServerConnection sampleConnection() {
        ServerConnection connection = new ServerConnection("Test Server", "192.168.1.9", 22, "daniel");
        connection.getSessionJournalConfig().setEnabled(true);
        return connection;
    }

    @Test
    void createSessionWritesDocumentAndRegistersLive() throws IOException {
        SessionJournalSession session = service.createSession(
            sampleConnection(), "tab-1234567890ab", settings, List.of(), false);
        Path dir = session.getDirectory();
        assertThat(Files.isRegularFile(dir.resolve(SessionJournalService.DOCUMENT_FILE_NAME))).isTrue();
        assertThat(service.isLive(dir)).isTrue();

        SessionJournalDocument document = service.loadDocument(dir);
        assertThat(document.getMeta().getConnectionName()).isEqualTo("Test Server");
        assertThat(document.getMeta().getHost()).isEqualTo("192.168.1.9");
        assertThat(document.getMeta().getUsername()).isEqualTo("daniel");
        assertThat(document.getMeta().getStartedAt()).isNotNull();
        assertThat(document.getMeta().getEndedAt()).isNull();
        session.close();
        assertThat(service.isLive(dir)).isFalse();
    }

    @Test
    void endToEndCaptureRedactsSecretsAndSuppressesPasswordInput() throws IOException {
        SessionJournalSession session = service.createSession(
            sampleConnection(), "tab-1234567890ab", settings, List.of("vault-secret-pw"), false);
        session.start();
        session.appendOutputChunk("Welcome to [32mweb01[0m\r\n");
        session.appendInputLine("echo vault-secret-pw");
        session.appendOutputChunk("[sudo] password for daniel:");
        assertThat(session.isInputSuppressed()).isTrue();
        session.appendInputLine("mySecretTyped123");
        assertThat(session.isInputSuppressed()).isFalse();
        session.appendOutputChunk("\r\nAccess granted\r\n");
        long screenshotSeq = session.attachScreenshot(new byte[] {1, 2, 3, 4}, "before restart");
        session.close();

        Path dir = session.getDirectory();
        Path logFile = SessionJournalLogReader.findPartFile(dir, 1);
        assertThat(logFile).isNotNull();
        assertThat(logFile.getFileName().toString()).endsWith(".gz");

        String rawContent;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(logFile))) {
            rawContent = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(rawContent).doesNotContain("mySecretTyped123");
        assertThat(rawContent).doesNotContain("vault-secret-pw");

        List<SessionJournalLogEntry> entries = SessionJournalLogReader.readPart(logFile);
        assertThat(entries.stream()
            .filter(e -> e.kind() == SessionJournalLogEntry.Kind.IN && !e.redacted())
            .map(SessionJournalLogEntry::text)
            .toList()).containsExactly("echo ***");
        assertThat(entries.stream()
            .filter(e -> e.kind() == SessionJournalLogEntry.Kind.IN && e.redacted())
            .count()).isEqualTo(1);
        assertThat(entries.stream()
            .filter(e -> e.kind() == SessionJournalLogEntry.Kind.OUT)
            .map(SessionJournalLogEntry::text)
            .toList()).containsAtLeast("Welcome to web01", "Access granted");
        assertThat(entries.stream()
            .filter(e -> e.kind() == SessionJournalLogEntry.Kind.SCREENSHOT)
            .map(SessionJournalLogEntry::file)
            .toList()).containsExactly("screenshots/shot-%06d.png".formatted(screenshotSeq));
        assertThat(Files.isRegularFile(dir.resolve("screenshots/shot-%06d.png".formatted(screenshotSeq)))).isTrue();

        SessionJournalDocument document = service.loadDocument(dir);
        SessionJournalMeta meta = document.getMeta();
        assertThat(meta.getEndedAt()).isNotNull();
        assertThat(meta.getCommandCount()).isEqualTo(2);
        assertThat(meta.getScreenshotCount()).isEqualTo(1);
        assertThat(meta.getLogEntryCount()).isGreaterThan(0);
        assertThat(document.getEntries().stream()
            .filter(e -> e.getKind() == SessionJournalEntryKind.SCREENSHOT)
            .count()).isEqualTo(1);
    }

    @Test
    void listJournalsReturnsMetadataSortedByStartDescending() throws IOException {
        SessionJournalSession first = service.createSession(
            sampleConnection(), "tab-aaaaaaaaaaaa", settings, List.of(), false);
        first.start();
        first.close();
        SessionJournalSession second = service.createSession(
            sampleConnection(), "tab-bbbbbbbbbbbb", settings, List.of(), false);
        second.start();
        second.close();

        List<SessionJournalMeta> journals = service.listJournals(settings);
        assertThat(journals).hasSize(2);
        assertThat(journals.get(0).getStartedAt()).isAtLeast(journals.get(1).getStartedAt());
        assertThat(journals.get(0).getDirectory()).isNotNull();
        assertThat(journals.get(0).isLive()).isFalse();
        assertThat(journals.get(0).getJournalId()).isNotEmpty();
    }

    @Test
    void entryLifecycleAppendUpdateRenameDescribe() throws IOException {
        SessionJournalSession session = service.createSession(
            sampleConnection(), "tab-1234567890ab", settings, List.of(), false);
        Path dir = session.getDirectory();

        SessionJournalEntry note = new SessionJournalEntry();
        note.setKind(SessionJournalEntryKind.USER_NOTE);
        note.setText("Root cause: stale pid file.");
        SessionJournalEntry stored = service.appendEntry(dir, note);
        assertThat(stored.getId()).isEqualTo(note.getId());

        stored.setMarker(SessionJournalMarker.ERROR);
        stored.setMarkerSource(SessionJournalEntry.MarkerSource.USER);
        service.updateEntry(dir, stored);

        service.renameJournal(dir, "My investigation");
        service.updateDescription(dir, "Debugging the nginx outage");
        service.updateLastSummarizedSeq(dir, 42);

        SessionJournalDocument document = service.loadDocument(dir);
        assertThat(document.getMeta().getTitle()).isEqualTo("My investigation");
        assertThat(document.getMeta().getDescription()).isEqualTo("Debugging the nginx outage");
        assertThat(document.getMeta().getLastSummarizedSeq()).isEqualTo(42);
        assertThat(document.getEntries()).hasSize(1);
        assertThat(document.getEntries().get(0).getMarker()).isEqualTo(SessionJournalMarker.ERROR);
        assertThat(document.getEntries().get(0).getEditedAt()).isNotNull();
        session.close();
    }

    @Test
    void deleteRefusesLiveJournalsAndForeignPaths() throws IOException {
        SessionJournalSession session = service.createSession(
            sampleConnection(), "tab-1234567890ab", settings, List.of(), false);
        Path dir = session.getDirectory();
        assertThrows(IOException.class, () -> service.deleteJournal(settings, dir));

        Path foreign = Files.createTempDirectory("kortty-foreign");
        try {
            assertThrows(IOException.class, () -> service.deleteJournal(settings, foreign));
        } finally {
            Files.deleteIfExists(foreign);
        }

        session.close();
        service.deleteJournal(settings, dir);
        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    void readLogTailWorksOnLiveUncompressedLog() throws IOException {
        SessionJournalSession session = service.createSession(
            sampleConnection(), "tab-1234567890ab", settings, List.of(), false);
        session.start();
        for (int i = 0; i < 5; i++) {
            session.appendOutputChunk("output line " + i + "\n");
        }
        session.appendInputLine("ls -l");
        // Give the writer thread a moment to drain the queue to disk.
        waitForEntries(session.getDirectory(), 6);

        SessionJournalLogTail tail = service.readLogTail(session.getDirectory(), 3, 10);
        assertThat(tail.output().stream().map(SessionJournalLogEntry::text).toList())
            .containsExactly("output line 2", "output line 3", "output line 4").inOrder();
        assertThat(tail.input().stream().map(SessionJournalLogEntry::text).toList())
            .containsExactly("ls -l");
        session.close();
    }

    @Test
    void redactRemovesTheSecretFromEntriesAndFromTheCompressedLog() throws IOException {
        SessionJournalSession session = service.createSession(
            sampleConnection(), "tab-1234567890ab", settings, List.of(), false);
        session.start();
        session.appendOutputChunk("connecting\n");
        // A password pasted into a visible command — exactly what capture-time protection misses.
        session.appendInputLine("mysql -u root -phunter2-leaked");
        session.appendOutputChunk("Welcome to MySQL\n");
        session.close();
        Path dir = session.getDirectory();

        SessionJournalEntry note = new SessionJournalEntry();
        note.setKind(SessionJournalEntryKind.USER_NOTE);
        note.setTitle("Login with hunter2-leaked");
        note.setText("Used hunter2-leaked for the database");
        note.setUserNote("hunter2-leaked again");
        note.getInputExcerpt().add("mysql -u root -phunter2-leaked");
        service.appendEntry(dir, note);

        SessionJournalService.RedactionResult result = service.redact(dir, "hunter2-leaked", "***");

        assertThat(result.entryHits()).isEqualTo(4);
        assertThat(result.logHits()).isEqualTo(1);

        String documentXml = Files.readString(
            dir.resolve(SessionJournalService.DOCUMENT_FILE_NAME), StandardCharsets.UTF_8);
        assertThat(documentXml).doesNotContain("hunter2-leaked");
        assertThat(documentXml).contains("Login with ***");

        Path logFile = SessionJournalLogReader.findPartFile(dir, 1);
        assertThat(logFile).isNotNull();
        assertThat(logFile.getFileName().toString()).endsWith(".gz");
        String rawContent;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(logFile))) {
            rawContent = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(rawContent).doesNotContain("hunter2-leaked");

        // The rewrite must keep every other line — the header with its metadata included.
        assertThat(rawContent).contains("tabSessionId=\"tab-1234567890ab\"");
        List<SessionJournalLogEntry> entries = SessionJournalLogReader.readPart(logFile);
        assertThat(entries.stream().map(SessionJournalLogEntry::text).toList())
            .containsExactly("connecting", "mysql -u root -p***", "Welcome to MySQL").inOrder();
    }

    @Test
    void redactRefusesALiveJournal() throws IOException {
        SessionJournalSession session = service.createSession(
            sampleConnection(), "tab-1234567890ab", settings, List.of(), false);
        session.start();
        assertThrows(IOException.class, () -> service.redact(session.getDirectory(), "secret", "***"));
        session.close();
    }

    @Test
    void deleteEntryRemovesTheEntryAndItsScreenshotFile() throws IOException {
        SessionJournalSession session = service.createSession(
            sampleConnection(), "tab-1234567890ab", settings, List.of(), false);
        session.start();
        session.attachScreenshot(new byte[] {1, 2, 3, 4}, "before restart");
        session.close();
        Path dir = session.getDirectory();

        SessionJournalDocument document = service.loadDocument(dir);
        SessionJournalEntry screenshot = document.getEntries().stream()
            .filter(e -> e.getKind() == SessionJournalEntryKind.SCREENSHOT)
            .findFirst()
            .orElseThrow();
        Path image = dir.resolve(screenshot.getScreenshotFile());
        assertThat(Files.isRegularFile(image)).isTrue();

        service.deleteEntry(dir, screenshot.getId());

        assertThat(service.loadDocument(dir).getEntries().stream()
            .map(SessionJournalEntry::getId)
            .toList()).doesNotContain(screenshot.getId());
        assertThat(Files.exists(image)).isFalse();
    }

    private void waitForEntries(Path dir, int minimum) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try {
                Path file = SessionJournalLogReader.findPartFile(dir, 1);
                if (file != null && SessionJournalLogReader.readPart(file).size() >= minimum) {
                    return;
                }
                Thread.sleep(50);
            } catch (Exception e) {
                // retry until deadline
            }
        }
    }
}
