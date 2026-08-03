package de.kortty.core;

import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * Writes a rendered journal page to {@code build/journal-page-dump/journal.html} so the page can be
 * opened in a real browser. The page's JavaScript is one block: a syntax error anywhere in it kills
 * every interaction on the page (this happened once already), and no unit test executes it.
 */
class SessionJournalPageDumpTest {

    @Test
    void writesARenderedPageForManualInspection() throws IOException {
        Path tempDir = Files.createTempDirectory("kortty-journal-page-dump");
        try {
            SessionJournalService service = new SessionJournalService();
            de.kortty.model.GlobalSettings settings = new de.kortty.model.GlobalSettings();
            settings.setSessionJournalStoragePath(tempDir.resolve("journals").toString());
            de.kortty.model.ServerConnection connection =
                new de.kortty.model.ServerConnection("Web Server", "192.168.1.9", 22, "daniel");
            connection.getSessionJournalConfig().setEnabled(true);

            SessionJournalSession session = service.createSession(
                connection, "tab-1234567890ab", settings, List.of(), false);
            session.start();
            session.appendOutputChunk("Last login: Mon Aug  3 10:12:44 2026\n");
            session.appendInputLine("systemctl status nginx");
            session.appendOutputChunk("nginx.service - A high performance web server\n");
            session.appendOutputChunk("   Active: active (running)\n");
            session.close();

            Path dir = session.getDirectory();
            SessionJournalEntry entry = new SessionJournalEntry();
            entry.setKind(SessionJournalEntryKind.AI_SUMMARY);
            entry.setTitle("Checked the nginx service");
            entry.setText("The service was queried and reported as running.");
            entry.getInputExcerpt().add("systemctl status nginx");
            entry.getOutputExcerpt().add("Active: active (running)");
            service.appendEntry(dir, entry);

            SessionJournalHtmlRenderer renderer = new SessionJournalHtmlRenderer(service);
            Path rendered = renderer.renderToFile(dir);

            Path outDir = Path.of("build", "journal-page-dump");
            Files.createDirectories(outDir);
            Path target = outDir.resolve(SessionJournalHtmlRenderer.HTML_FILE_NAME);
            Files.copy(rendered, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            String html = Files.readString(target);
            // The replace control ships hidden and is revealed by the app once the bridge is up,
            // so a standalone page in a browser never offers an action it cannot perform.
            assertThat(html).contains("id=\"journalReplace\"");
            assertThat(html).contains("window.korttyEnableReplace");
            assertThat(html).contains("id=\"journalReplace\" hidden");
        } finally {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        // best effort
                    }
                });
            }
        }
    }
}
