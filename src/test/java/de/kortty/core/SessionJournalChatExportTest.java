package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.SavedAgentStep;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionJournalChatMessage;
import de.kortty.model.SessionJournalChatSnapshot;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMarker;
import de.kortty.ui.I18n;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;

import static com.google.common.truth.Truth.assertThat;

/**
 * Chats that continue an AGENT entry in the journal: they travel with the entry into Markdown (a sibling
 * file), PDF (an appendix with an outline entry) and the HTML bundle, and stay out of every export when
 * the user unticks "Include AI chats".
 */
class SessionJournalChatExportTest {

    private static final String MARK = "CHATMARK-7731";
    private static final de.kortty.model.SessionJournalMarkerDefinition DEPLOY =
        new de.kortty.model.SessionJournalMarkerDefinition("deploy", "Deployment", "#7c3aed",
            false, SessionJournalMarker.IMPORTANT);

    private Path tempDir;
    private GlobalSettings settings;
    private SessionJournalService service;
    private SessionJournalHtmlRenderer renderer;
    private SessionJournalExportService exportService;

    @BeforeMethod
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("kortty-journal-chat-export-test");
        settings = new GlobalSettings();
        settings.setSessionJournalStoragePath(tempDir.resolve("journals").toString());
        settings.getSessionJournalMarkers().add(DEPLOY);
        service = new SessionJournalService();
        service.setSettingsSupplier(() -> settings);
        renderer = new SessionJournalHtmlRenderer(service);
        exportService = new SessionJournalExportService(service, renderer);
    }

    @AfterMethod
    void tearDown() throws IOException {
        renderer.stop();
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

    /**
     * Two entries: a plain summary on log lines 1-2, and an AGENT entry on lines 3-4 that carries the
     * deploy marker and a linked chat with a marker in its text.
     */
    private Path buildJournalWithChat() throws Exception {
        ServerConnection connection = new ServerConnection("Web03", "192.168.1.52", 22, "daniel");
        connection.getSessionJournalConfig().setEnabled(true);
        SessionJournalSession session = service.createSession(
            connection, "tab-chat0123456789", settings, List.of(), false);
        session.start();
        session.appendOutputChunk("morning-output\n");
        session.appendInputLine("morning-command");
        session.appendOutputChunk("afternoon-output\n");
        session.appendInputLine("afternoon-command");
        session.close();
        Path dir = session.getDirectory();

        SessionJournalEntry morning = new SessionJournalEntry();
        morning.setKind(SessionJournalEntryKind.AI_SUMMARY);
        morning.setTitle("Morning work");
        morning.setMarker(SessionJournalMarker.INFO);
        morning.setLogStartSeq(1L);
        morning.setLogEndSeq(2L);
        service.appendEntry(dir, morning);

        SessionJournalEntry agent = new SessionJournalEntry();
        agent.setKind(SessionJournalEntryKind.AGENT);
        agent.setTitle("Afternoon agent run");
        agent.setText("The unit file was fixed.");
        agent.setAgentModel("Claude (claude-sonnet-5)");
        agent.setAgentDurationMillis(383_000L);
        agent.setAgentTokens(12_040L);
        SessionJournalMarkers.apply(agent, DEPLOY);
        agent.setLogStartSeq(3L);
        agent.setLogEndSeq(4L);
        service.appendEntry(dir, agent);
        service.linkChat(dir, agent.getId(), "chat-" + agent.getId(), chatSnapshot());
        return dir;
    }

    private static SessionJournalChatSnapshot chatSnapshot() {
        SessionJournalChatSnapshot snapshot = new SessionJournalChatSnapshot();
        snapshot.setTitle("Fix the unit file");
        snapshot.setCapturedAt(OffsetDateTime.of(2026, 8, 3, 15, 2, 0, 0, ZoneOffset.ofHours(2)));
        SessionJournalChatMessage user = new SessionJournalChatMessage();
        user.setRole("USER");
        user.setContent(MARK + " check the unit file");
        user.setCreatedAt(OffsetDateTime.of(2026, 8, 3, 15, 0, 0, 0, ZoneOffset.ofHours(2)));
        SessionJournalChatMessage assistant = new SessionJournalChatMessage();
        assistant.setRole("ASSISTANT");
        assistant.setAiProfileName("Claude");
        assistant.setContent("The unit file had a typo and is fixed now.");
        assistant.setCreatedAt(OffsetDateTime.of(2026, 8, 3, 15, 1, 0, 0, ZoneOffset.ofHours(2)));
        SavedAgentStep step = new SavedAgentStep();
        step.setType("ACTION");
        step.setStatus("DONE");
        step.setTitle("Read the unit file");
        step.setCommand("systemctl status nginx");
        step.setExitCode(0);
        step.setOutput("nginx.service - A high performance web server");
        assistant.setSteps(List.of(step));
        snapshot.setMessages(List.of(user, assistant));
        return snapshot;
    }

    private static SessionJournalExportService.Options options(boolean includeChats) {
        return new SessionJournalExportService.Options(true, null, includeChats);
    }

    private static String readZipEntry(Path archive, String entryName) throws Exception {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new AssertionError("no entry " + entryName + " in " + archive.getFileName());
            }
            try (var in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private String entryIdOf(Path dir, String title) throws IOException {
        return service.loadDocument(dir).getEntries().stream()
            .filter(entry -> title.equals(entry.getTitle()))
            .findFirst().orElseThrow().getId();
    }

    @Test
    void markdownWritesTheChatBesideTheFileAndLinksItFromTheEntry() throws Exception {
        Path dir = buildJournalWithChat();
        Path target = tempDir.resolve("chat.md");

        exportService.export(SessionJournalExportService.Format.MARKDOWN, dir, target, options(true));

        String id = entryIdOf(dir, "Afternoon agent run");
        String md = Files.readString(target, StandardCharsets.UTF_8);
        assertThat(md).contains("](chat-files/chat-" + id + ".md)");
        // The AGENT meta line now reaches the Markdown too.
        assertThat(md).contains("Claude (claude-sonnet-5)");
        assertThat(md).contains("6 min 23 s");
        Path chatFile = tempDir.resolve("chat-files").resolve("chat-" + id + ".md");
        assertThat(Files.exists(chatFile)).isTrue();
        String chat = Files.readString(chatFile, StandardCharsets.UTF_8);
        assertThat(chat).contains("Fix the unit file");
        assertThat(chat).contains(MARK);
        assertThat(chat).contains("$ systemctl status nginx");
        assertThat(chat).contains("nginx.service - A high performance web server");
    }

    @Test
    void pdfGetsAChatAppendixWithAnOutlineEntryPerChat() throws Exception {
        Path dir = buildJournalWithChat();
        Path withChat = tempDir.resolve("with-chat.pdf");
        Path withoutChat = tempDir.resolve("without-chat.pdf");

        exportService.export(SessionJournalExportService.Format.PDF, dir, withChat, options(true));
        exportService.export(SessionJournalExportService.Format.PDF, dir, withoutChat, options(false));

        try (PDDocument pdf = Loader.loadPDF(withChat.toFile())) {
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains(MARK);
            assertThat(text).contains("The unit file had a typo and is fixed now.");
            assertThat(text).contains("systemctl status nginx");
            assertThat(text).contains(I18n.get("journal.pdf.chatPointer"));
            assertThat(pdf.getDocumentCatalog().getDocumentOutline()).isNotNull();
            var appendix = pdf.getDocumentCatalog().getDocumentOutline().getFirstChild();
            assertThat(appendix.getFirstChild().getTitle()).isEqualTo("Fix the unit file");
            int pagesWithChat = pdf.getNumberOfPages();
            try (PDDocument plain = Loader.loadPDF(withoutChat.toFile())) {
                assertThat(pagesWithChat).isGreaterThan(plain.getNumberOfPages());
            }
        }
    }

    @Test
    void htmlBundleCarriesTheTranscriptInsideTheRenderedPage() throws Exception {
        Path dir = buildJournalWithChat();
        Path bundle = tempDir.resolve("bundle.zip");

        exportService.export(SessionJournalExportService.Format.HTML_BUNDLE, dir, bundle, options(true));

        String html = readZipEntry(bundle, "journal.html");
        assertThat(html).contains("<details class=\"chat-transcript\">");
        assertThat(html).contains(MARK);
        assertThat(readZipEntry(bundle, "journal.xml")).contains("chatSnapshot");
    }

    @Test
    void withoutChatsNoExportCarriesTheChatText() throws Exception {
        Path dir = buildJournalWithChat();
        Path md = tempDir.resolve("plain.md");
        Path pdf = tempDir.resolve("plain.pdf");
        Path bundle = tempDir.resolve("plain.zip");

        exportService.export(SessionJournalExportService.Format.MARKDOWN, dir, md, options(false));
        exportService.export(SessionJournalExportService.Format.PDF, dir, pdf, options(false));
        exportService.export(SessionJournalExportService.Format.HTML_BUNDLE, dir, bundle, options(false));

        String markdown = Files.readString(md, StandardCharsets.UTF_8);
        assertThat(markdown).doesNotContain(MARK);
        assertThat(markdown).doesNotContain("chat-files");
        assertThat(Files.exists(tempDir.resolve("plain-files"))).isFalse();
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            assertThat(new PDFTextStripper().getText(document)).doesNotContain(MARK);
            assertThat(document.getDocumentCatalog().getDocumentOutline()).isNull();
        }
        String html = readZipEntry(bundle, "journal.html");
        assertThat(html).doesNotContain(MARK);
        assertThat(html).doesNotContain("<details class=\"chat-transcript\">");
        assertThat(html).doesNotContain("chat-open-btn\" data-entry");
        // The verbatim journal.xml copy must not keep the snapshot or the link either.
        String xml = readZipEntry(bundle, "journal.xml");
        assertThat(xml).doesNotContain(MARK);
        assertThat(xml).doesNotContain("chatSnapshot");
        assertThat(xml).doesNotContain("chatId");
        // The entry itself stays; only its chat goes.
        assertThat(xml).contains("Afternoon agent run");
    }

    @Test
    void filteredBundleKeepsTheChatOnlyWithItsEntry() throws Exception {
        Path dir = buildJournalWithChat();
        Path deployOnly = tempDir.resolve("deploy.zip");
        Path infoOnly = tempDir.resolve("info.zip");
        Path deployNoChats = tempDir.resolve("deploy-no-chats.zip");

        exportService.export(SessionJournalExportService.Format.HTML_BUNDLE, dir, deployOnly,
            markerOptions(true, "deploy"));
        exportService.export(SessionJournalExportService.Format.HTML_BUNDLE, dir, infoOnly,
            markerOptions(true, "info"));
        exportService.export(SessionJournalExportService.Format.HTML_BUNDLE, dir, deployNoChats,
            markerOptions(false, "deploy"));

        assertThat(readZipEntry(deployOnly, "journal.html")).contains(MARK);
        assertThat(readZipEntry(deployOnly, "journal.xml")).contains(MARK);
        // The chat sits with its AGENT entry; the entry is not in this bundle, so neither is the chat.
        assertThat(readZipEntry(infoOnly, "journal.html")).doesNotContain(MARK);
        assertThat(readZipEntry(infoOnly, "journal.xml")).doesNotContain(MARK);
        assertThat(readZipEntry(deployNoChats, "journal.html")).doesNotContain(MARK);
        assertThat(readZipEntry(deployNoChats, "journal.xml")).doesNotContain(MARK);
        assertThat(readZipEntry(deployNoChats, "journal.xml")).contains("Afternoon agent run");
    }

    /**
     * The journal manager passes the export dialog's "Include AI chats" choice straight through, for one
     * journal and for an archive. An unticked box must keep the chat text out of the archive.
     */
    @Test
    void aManagerArchiveWithChatsUntickedCarriesNoChatTextAndKeepsTheCard() throws Exception {
        Path dir = buildJournalWithChat();
        Path archive = tempDir.resolve("manager-no-chats.zip");

        exportService.exportArchive(SessionJournalExportService.Format.HTML_BUNDLE, List.of(dir), archive,
            new SessionJournalExportService.Options(true, null, false), null);

        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
            var entries = java.util.Collections.list(zip.entries());
            assertThat(entries.stream().anyMatch(e -> e.getName().endsWith("journal.html"))).isTrue();
            for (ZipEntry entry : entries) {
                if (entry.isDirectory()) {
                    continue;
                }
                String content = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.ISO_8859_1);
                assertThat(content).doesNotContain(MARK);
                assertThat(content).doesNotContain("chatSnapshot");
            }
            String html = readZipEntry(archive, entries.stream()
                .map(ZipEntry::getName).filter(n -> n.endsWith("journal.html")).findFirst().orElseThrow());
            assertThat(html).contains("Afternoon agent run");
            assertThat(html).doesNotContain("<details class=\"chat-transcript\">");
        }
    }

    /**
     * Unticking "Include AI chats" removes the continued chat only; the agent card keeps its question and
     * answer, as the checkbox tooltip says.
     */
    @Test
    void theAgentCardsQuestionAndAnswerStayWhenChatsAreUnticked() throws Exception {
        Path dir = buildJournalWithChat();
        Path md = tempDir.resolve("card-only.md");

        exportService.export(SessionJournalExportService.Format.MARKDOWN, dir, md, options(false));

        String markdown = Files.readString(md, StandardCharsets.UTF_8);
        assertThat(markdown).contains("Afternoon agent run");
        assertThat(markdown).contains("The unit file was fixed.");
        assertThat(markdown).doesNotContain(MARK);
    }

    /** The chat file beside a journal Markdown writes its steps with the same block the chat exports use. */
    @Test
    void theChatFileBesideTheJournalWritesItsStepsLikeTheChatExports() throws Exception {
        Path dir = buildJournalWithChat();
        Path target = tempDir.resolve("chat.md");

        exportService.export(SessionJournalExportService.Format.MARKDOWN, dir, target, options(true));

        String id = entryIdOf(dir, "Afternoon agent run");
        String chat = Files.readString(tempDir.resolve("chat-files").resolve("chat-" + id + ".md"),
            StandardCharsets.UTF_8);
        String steps = AgentChatTranscriptFormatter.markdownSteps(
            chatSnapshot().toSavedMessages().get(1).getAgentSteps());
        assertThat(steps).startsWith("### " + I18n.get("ai.result.agentRun.steps", 1) + "\n\n- [DONE]");
        assertThat(chat).contains(steps);
    }

    private static SessionJournalExportService.Options markerOptions(boolean includeChats, String markerId) {
        de.kortty.core.SessionJournalExportFilter filter = new de.kortty.core.SessionJournalExportFilter(
            List.of(), 0, null, false, false,
            de.kortty.core.SessionJournalExportFilter.MarkerMode.SELECTED, java.util.Set.of(markerId),
            java.time.ZoneId.systemDefault());
        return new SessionJournalExportService.Options(true, filter, includeChats);
    }
}
