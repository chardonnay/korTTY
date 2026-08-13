package de.kortty.core;

import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalDocument;
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

    /** A tiny opaque PNG; the dump only needs something a browser will actually render. */
    private static byte[] samplePngBytes() throws IOException {
        java.awt.image.BufferedImage image =
            new java.awt.image.BufferedImage(320, 160, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(0x1e, 0x28, 0x36));
        graphics.fillRect(0, 0, 320, 160);
        graphics.setColor(new java.awt.Color(0x7e, 0xe7, 0x87));
        graphics.drawString("Active: active (running)", 20, 80);
        graphics.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

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
            // A screenshot so the dump also exercises the thumbnail, the lightbox and the
            // image-only context menu actions.
            session.attachScreenshot(samplePngBytes(), "nginx status output");
            session.close();

            Path dir = session.getDirectory();
            SessionJournalEntry entry = new SessionJournalEntry();
            entry.setKind(SessionJournalEntryKind.AI_SUMMARY);
            entry.setTitle("Checked the nginx service");
            entry.setText("The service was queried and reported as running.");
            entry.getInputExcerpt().add("systemctl status nginx");
            entry.getOutputExcerpt().add("Active: active (running)");
            entry.setMarker(de.kortty.model.SessionJournalMarker.INFO);
            service.appendEntry(dir, entry);

            // A second, custom-marked entry so the dump exercises the marker bar with more than
            // one option — the marker JS is only emitted when a journal actually uses markers.
            SessionJournalEntry deployed = new SessionJournalEntry();
            deployed.setKind(SessionJournalEntryKind.AI_SUMMARY);
            deployed.setTitle("Rolled out the new configuration");
            deployed.setText("The updated nginx configuration was deployed and reloaded.");
            SessionJournalMarkers.apply(deployed, new de.kortty.model.SessionJournalMarkerDefinition(
                "deploy", "Deployment", "#7c3aed", false,
                de.kortty.model.SessionJournalMarker.IMPORTANT));
            service.appendEntry(dir, deployed);

            // A terminal-agent run so the dump exercises the AGENT card, its badge and the
            // collapse-long-answers wiring (the answer is long enough to trigger it).
            SessionJournalEntry agentRun = new SessionJournalEntry();
            agentRun.setKind(SessionJournalEntryKind.AGENT);
            agentRun.setTitle("check script server_auslastung.pl if there is any failure");
            agentRun.setText("The script has several potential failure points:\n"
                + "missing sysstat logs.\n".repeat(40));
            agentRun.setAgentModel("Claude (claude-sonnet-5)");
            agentRun.setAgentDurationMillis(383_000L);
            agentRun.setAgentTokens(12_040L);
            service.appendEntry(dir, agentRun);

            SessionJournalDocument document = service.loadDocument(dir);
            SessionJournalMarkers.snapshot(document, new de.kortty.model.SessionJournalMarkerDefinition(
                "deploy", "Deployment", "#7c3aed", false,
                de.kortty.model.SessionJournalMarker.IMPORTANT));
            service.saveDocument(dir, document);

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
            // Live-tail hooks for the docked panel's push path.
            assertThat(html).contains("window.korttyAppendLog");
            assertThat(html).contains("window.korttyOpenLiveTail");
            assertThat(html).contains("window.korttyCloseLiveTail");
            assertThat(html).contains("window.korttySetLiveTailHeight");
            assertThat(html).contains("id=\"logResize\"");
            // The terminal-agent run renders as its own badged card, with the collapse wiring
            // for long answers on board.
            assertThat(html).contains("agent-entry");
            assertThat(html).contains("agent-tag");
            assertThat(html).contains("summary-toggle");
            assertThat(html).contains("showMore:");
            // Cards must be able to shrink with the docked panel: a plain 1fr track would keep
            // the pre-formatted excerpts from ever letting the grid narrow.
            assertThat(html).contains("clamp(44px,7vw,64px) minmax(0,1fr)");
            // Jump-to-a-time bar.
            assertThat(html).contains("id=\"timeBar\"");
            assertThat(html).contains("id=\"timeJump\"");
            assertThat(html).contains("id=\"timeToggle\"");
            // Model, duration and token count render as one muted meta line.
            assertThat(html).contains("class=\"agent-meta\"");
            assertThat(html).contains("Claude (claude-sonnet-5) · 6 min 23 s · 12.0k tokens");
            assertThat(html).contains("l-line");
            assertThat(html).contains("id=\"journalReplace\" hidden");
            // The marker bar and the navigator it shares with the search must both be present.
            assertThat(html).contains("id=\"markerBar\"");
            assertThat(html).contains("id=\"markerToggle\"");
            assertThat(html).contains("makeNav");
            assertThat(html).contains(".entry[data-marker=\"deploy\"]{--mk:#7c3aed");
            // Both markers of the journal are offered in the selector.
            assertThat(html).contains("<option value=\"deploy\">Deployment</option>");
            assertThat(html).contains("<option value=\"info\">");
            // Edit and Export only make sense inside korTTY, so they ship in the menu but are
            // revealed by the script only when the bridge answers.
            assertThat(html).contains("id=\"ctxAnnotate\"");
            assertThat(html).contains("id=\"ctxSaveImage\"");
            assertThat(html).contains("id=\"ctxRename\"");
            assertThat(html).contains("class=\"thumb\"");
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
