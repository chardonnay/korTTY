package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMarker;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalExportServiceTest {

    private Path tempDir;
    private GlobalSettings settings;
    private SessionJournalService service;
    private SessionJournalHtmlRenderer renderer;
    private SessionJournalExportService exportService;
    private Path journalDir;

    @BeforeMethod
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("kortty-session-journal-export-test");
        settings = new GlobalSettings();
        settings.setSessionJournalStoragePath(tempDir.resolve("journals").toString());
        service = new SessionJournalService();
        renderer = new SessionJournalHtmlRenderer(service);
        exportService = new SessionJournalExportService(service, renderer);
        journalDir = buildSampleJournal();
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

    /** Creates a closed journal with output, input, a screenshot and one AI summary entry. */
    private Path buildSampleJournal() throws Exception {
        ServerConnection connection = new ServerConnection("Web01", "192.168.1.50", 22, "daniel");
        connection.getSessionJournalConfig().setEnabled(true);
        SessionJournalSession session = service.createSession(
            connection, "tab-1234567890ab", settings, List.of(), false);
        session.start();
        session.appendOutputChunk("Active: active (running)\n");
        session.appendInputLine("systemctl status nginx");
        session.attachScreenshot(samplePngBytes(), "before restart");
        session.close();

        Path dir = session.getDirectory();
        SessionJournalEntry summary = new SessionJournalEntry();
        summary.setKind(SessionJournalEntryKind.AI_SUMMARY);
        summary.setTitle("Checked nginx status");
        summary.setText("The nginx service is running and healthy.");
        summary.setMarker(SessionJournalMarker.IMPORTANT);
        summary.setLogStartSeq(1L);
        summary.setLogEndSeq(3L);
        summary.setInputExcerpt(List.of("systemctl status nginx"));
        summary.setOutputExcerpt(List.of("Active: active (running)"));
        service.appendEntry(dir, summary);
        service.updateDescription(dir, "Nginx outage debugging");
        return dir;
    }

    private static byte[] samplePngBytes() throws IOException {
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.DARK_GRAY);
        graphics.fillRect(0, 0, 80, 40);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void pdfExportContainsHeaderEntriesAndEmbeddedScreenshot() throws Exception {
        Path target = tempDir.resolve("journal-export.pdf");
        exportService.export(SessionJournalExportService.Format.PDF, journalDir, target,
            SessionJournalExportService.Options.defaults());
        assertThat(Files.size(target)).isGreaterThan(0);
        try (PDDocument pdf = Loader.loadPDF(target.toFile())) {
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("daniel@192.168.1.50:22");
            assertThat(text).contains("Checked nginx status");
            assertThat(text).contains("The nginx service is running and healthy.");
            assertThat(text).contains("$ systemctl status nginx");
            assertThat(text).contains("Nginx outage debugging");
            // The screenshot page area exists: at least one image XObject in the document.
            boolean hasImage = false;
            for (var page : pdf.getPages()) {
                Iterable<org.apache.pdfbox.cos.COSName> names = page.getResources().getXObjectNames();
                for (var name : names) {
                    if (page.getResources().isImageXObject(name)) {
                        hasImage = true;
                    }
                }
            }
            assertThat(hasImage).isTrue();
        }
    }

    @Test
    void pdfExportCanOmitScreenshots() throws Exception {
        Path target = tempDir.resolve("journal-noshots.pdf");
        exportService.export(SessionJournalExportService.Format.PDF, journalDir, target,
            new SessionJournalExportService.Options(false));
        try (PDDocument pdf = Loader.loadPDF(target.toFile())) {
            boolean hasImage = false;
            for (var page : pdf.getPages()) {
                for (var name : page.getResources().getXObjectNames()) {
                    if (page.getResources().isImageXObject(name)) {
                        hasImage = true;
                    }
                }
            }
            assertThat(hasImage).isFalse();
        }
    }

    @Test
    void markdownExportWritesSectionsAndCopiesScreenshots() throws Exception {
        Path target = tempDir.resolve("my-journal.md");
        exportService.export(SessionJournalExportService.Format.MARKDOWN, journalDir, target,
            SessionJournalExportService.Options.defaults());
        String md = Files.readString(target, StandardCharsets.UTF_8);
        assertThat(md).contains("# Web01 — ");
        // The title already names the connection, so the line carries only the endpoint.
        assertThat(md).contains("**Connection:** daniel@192.168.1.50:22\n");
        assertThat(md).doesNotContain("Web01 (daniel@192.168.1.50:22)");
        assertThat(md).contains("Checked nginx status");
        assertThat(md).contains("`[IMPORTANT]`");
        assertThat(md).contains("```console\n$ systemctl status nginx\n```");
        assertThat(md).contains("](my-journal-files/");
        Path assetDir = tempDir.resolve("my-journal-files");
        assertThat(Files.isDirectory(assetDir)).isTrue();
        try (var files = Files.list(assetDir)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void everyFormatStatesItWasCreatedWithKorTTY() throws Exception {
        Path markdown = tempDir.resolve("brand.md");
        exportService.export(SessionJournalExportService.Format.MARKDOWN, journalDir, markdown,
            SessionJournalExportService.Options.defaults());
        String md = Files.readString(markdown, StandardCharsets.UTF_8);
        assertThat(md).contains("korTTY");
        assertThat(md).contains(SessionJournalExportService.REPOSITORY_URL);

        Path pdf = tempDir.resolve("brand.pdf");
        exportService.export(SessionJournalExportService.Format.PDF, journalDir, pdf,
            SessionJournalExportService.Options.defaults());
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("korTTY");
            assertThat(text).contains(SessionJournalExportService.REPOSITORY_URL);
            // The watermark names the developer, and the footer URL is a clickable link.
            assertThat(text).contains("Daniel Mengel");
            assertThat(document.getPage(0).getAnnotations()).isNotEmpty();
        }
    }

    @Test
    void multipleJournalsGoIntoOneArchiveEachKeptSeparate() throws Exception {
        Path second = buildSampleJournal();
        service.renameJournal(second, "Second journal");
        Path target = tempDir.resolve("journals.zip");
        exportService.exportArchive(SessionJournalExportService.Format.PDF,
            List.of(journalDir, second), target, SessionJournalExportService.Options.defaults(), null);

        List<String> names = zipEntryNames(target, null);
        assertThat(names).hasSize(2);
        assertThat(names.stream().allMatch(n -> n.endsWith(".pdf"))).isTrue();
        assertThat(names).contains("Second_journal.pdf");
    }

    @Test
    void htmlBundleArchiveKeepsOneFolderPerJournal() throws Exception {
        Path second = buildSampleJournal();
        service.renameJournal(second, "Second journal");
        Path target = tempDir.resolve("bundles.zip");
        exportService.exportArchive(SessionJournalExportService.Format.HTML_BUNDLE,
            List.of(journalDir, second), target, null, null);

        List<String> names = zipEntryNames(target, null);
        assertThat(names.stream().anyMatch(n -> n.endsWith("Second_journal/journal.html"))).isTrue();
        assertThat(names.stream().filter(n -> n.endsWith("journal.html")).count()).isEqualTo(2);
        assertThat(names.stream().noneMatch(n -> n.endsWith(".gz"))).isTrue();
    }

    @Test
    void archivesCanBeEncryptedWithAPassword() throws Exception {
        Path target = tempDir.resolve("secret.zip");
        exportService.exportArchive(SessionJournalExportService.Format.MARKDOWN,
            List.of(journalDir), target, SessionJournalExportService.Options.defaults(),
            "s3cret-pass".toCharArray());

        try (net.lingala.zip4j.ZipFile zip = new net.lingala.zip4j.ZipFile(target.toFile())) {
            assertThat(zip.isEncrypted()).isTrue();
        }
        // Reading it needs the password; extraction with the right one succeeds.
        Path out = tempDir.resolve("extracted");
        try (net.lingala.zip4j.ZipFile zip =
                 new net.lingala.zip4j.ZipFile(target.toFile(), "s3cret-pass".toCharArray())) {
            zip.extractAll(out.toString());
        }
        try (var files = Files.walk(out)) {
            assertThat(files.anyMatch(p -> p.getFileName().toString().endsWith(".md"))).isTrue();
        }
    }

    private static List<String> zipEntryNames(Path archive, char[] password) throws Exception {
        List<String> names = new ArrayList<>();
        try (net.lingala.zip4j.ZipFile zip = password == null
            ? new net.lingala.zip4j.ZipFile(archive.toFile())
            : new net.lingala.zip4j.ZipFile(archive.toFile(), password)) {
            for (var header : zip.getFileHeaders()) {
                if (!header.isDirectory()) {
                    names.add(header.getFileName());
                }
            }
        }
        return names;
    }

    @Test
    void htmlBundleContainsPageDecompressedLogAndScreenshots() throws Exception {
        Path target = tempDir.resolve("journal-bundle.zip");
        exportService.export(SessionJournalExportService.Format.HTML_BUNDLE, journalDir, target, null);
        List<String> names = new ArrayList<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(target.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
        }
        assertThat(names).contains("journal.html");
        assertThat(names).contains("journal.xml");
        // The gzipped log part is stored decompressed under its plain name.
        assertThat(names).contains("session-log.xml");
        assertThat(names.stream().noneMatch(n -> n.endsWith(".gz"))).isTrue();
        assertThat(names.stream().anyMatch(n -> n.startsWith("screenshots/") && n.endsWith(".png"))).isTrue();
    }
}
