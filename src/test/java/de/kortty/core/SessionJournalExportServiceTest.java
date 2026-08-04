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
        // The custom marker must be resolvable so appendEntry can snapshot it into the journal.
        settings.getSessionJournalMarkers().add(DEPLOY);
        service.setSettingsSupplier(() -> settings);
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
        // The badge now carries the marker's display name, so a custom marker exports under its
        // own name; built-ins keep going through the same i18n key the page and the PDF use.
        assertThat(md).contains("`[" + de.kortty.ui.I18n.get("journal.marker.important") + "]`");
        assertThat(md).contains("```console\n$ systemctl status nginx\n```");
        assertThat(md).contains("](my-journal-files/");
        Path assetDir = tempDir.resolve("my-journal-files");
        assertThat(Files.isDirectory(assetDir)).isTrue();
        try (var files = Files.list(assetDir)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void watermarkIsOffByDefaultAndHonoursCustomTextAndColour() throws Exception {
        Path plain = tempDir.resolve("no-watermark.pdf");
        exportService.export(SessionJournalExportService.Format.PDF, journalDir, plain,
            SessionJournalExportService.Options.defaults());
        try (PDDocument document = Loader.loadPDF(plain.toFile())) {
            assertThat(new PDFTextStripper().getText(document))
                .doesNotContain(SessionJournalExportService.class.getSimpleName());
            assertThat(new PDFTextStripper().getText(document)).doesNotContain("Developed by Daniel Mengel\n");
        }

        SessionJournalExportService branded = new SessionJournalExportService(service, renderer,
            new de.kortty.core.ExportBranding(true, "CONFIDENTIAL", java.awt.Color.RED,
                true, "ACME internal", false));
        Path marked = tempDir.resolve("watermarked.pdf");
        branded.export(SessionJournalExportService.Format.PDF, journalDir, marked,
            SessionJournalExportService.Options.defaults());
        try (PDDocument document = Loader.loadPDF(marked.toFile())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("CONFIDENTIAL");
            assertThat(text).contains("ACME internal");
            // A custom footer is the user's wording: no repository URL appended, no link annotation.
            assertThat(text).doesNotContain(SessionJournalExportService.REPOSITORY_URL);
            assertThat(document.getPage(0).getAnnotations()).isEmpty();
        }
    }

    @Test
    void disabledFooterLeavesEveryFormatWithoutABrandLine() throws Exception {
        SessionJournalExportService bare = new SessionJournalExportService(service, renderer,
            new de.kortty.core.ExportBranding(false, "x", java.awt.Color.GRAY, false, "x", true));
        Path markdown = tempDir.resolve("bare.md");
        bare.export(SessionJournalExportService.Format.MARKDOWN, journalDir, markdown,
            SessionJournalExportService.Options.defaults());
        assertThat(Files.readString(markdown, StandardCharsets.UTF_8))
            .doesNotContain(SessionJournalExportService.REPOSITORY_URL);

        Path pdf = tempDir.resolve("bare.pdf");
        bare.export(SessionJournalExportService.Format.PDF, journalDir, pdf,
            SessionJournalExportService.Options.defaults());
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            assertThat(new PDFTextStripper().getText(document))
                .doesNotContain(SessionJournalExportService.REPOSITORY_URL);
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
        // The gzipped log part is stored decompressed under its plain name, whatever
        // format the journal was captured in.
        assertThat(names).contains(SessionJournalLogReader.BASE_FILE_NAME + "."
            + de.kortty.model.SessionJournalLogFormat.DEFAULT.getExtension());
        assertThat(names.stream().noneMatch(n -> n.endsWith(".gz"))).isTrue();
        assertThat(names.stream().anyMatch(n -> n.startsWith("screenshots/") && n.endsWith(".png"))).isTrue();
    }

    // ==== filtered exports ======================================================================

    private static final de.kortty.model.SessionJournalMarkerDefinition DEPLOY =
        new de.kortty.model.SessionJournalMarkerDefinition("deploy", "Deployment", "#7c3aed",
            false, SessionJournalMarker.IMPORTANT);

    /**
     * A closed journal with four log lines and two entries covering seq 1-2 and 3-4, so a filter
     * that keeps one entry must visibly trim the log.
     */
    private Path buildTwoPartJournal() throws Exception {
        ServerConnection connection = new ServerConnection("Web02", "192.168.1.51", 22, "daniel");
        connection.getSessionJournalConfig().setEnabled(true);
        SessionJournalSession session = service.createSession(
            connection, "tab-abcdef012345", settings, List.of(), false);
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

        SessionJournalEntry afternoon = new SessionJournalEntry();
        afternoon.setKind(SessionJournalEntryKind.AI_SUMMARY);
        afternoon.setTitle("Afternoon deployment");
        SessionJournalMarkers.apply(afternoon, DEPLOY);
        afternoon.setLogStartSeq(3L);
        afternoon.setLogEndSeq(4L);
        service.appendEntry(dir, afternoon);
        return dir;
    }

    private static SessionJournalExportService.Options markerOptions(String... markerIds) {
        SessionJournalExportFilter filter = new SessionJournalExportFilter(
            List.of(), 0, null, false, false,
            SessionJournalExportFilter.MarkerMode.SELECTED, java.util.Set.of(markerIds),
            java.time.ZoneId.systemDefault());
        return new SessionJournalExportService.Options(true, filter);
    }

    private static SessionJournalExportService.Options windowOptions(
            java.time.LocalDate from, java.time.LocalDate to) {
        SessionJournalExportFilter filter = SessionJournalExportFilter.none()
            .withWindows(List.of(SessionJournalExportFilter.TimeWindow.ofDates(from, to)));
        return new SessionJournalExportService.Options(true, filter);
    }

    @Test
    void pdfExportHonoursTheMarkerFilterAndAnnouncesTheExcerpt() throws Exception {
        Path dir = buildTwoPartJournal();
        Path target = tempDir.resolve("filtered.pdf");

        var result = exportService.export(
            SessionJournalExportService.Format.PDF, dir, target, markerOptions("deploy"));

        assertThat(result.exportedEntries()).isEqualTo(1);
        assertThat(result.totalEntries()).isEqualTo(2);
        assertThat(result.filtered()).isTrue();
        try (PDDocument pdf = Loader.loadPDF(target.toFile())) {
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("Afternoon deployment");
            assertThat(text).doesNotContain("Morning work");
            // A filtered document must say that it is one, and name the marker rather than its id.
            assertThat(text).contains("Markers: Deployment · 1 of 2 entries");
            // The badge itself is drawn in caps, like every other marker badge.
            assertThat(text).contains("DEPLOYMENT");
        }
    }

    @Test
    void markdownExportHonoursTheMarkerFilter() throws Exception {
        Path dir = buildTwoPartJournal();
        Path target = tempDir.resolve("filtered.md");

        exportService.export(SessionJournalExportService.Format.MARKDOWN, dir, target,
            markerOptions("deploy"));

        String md = Files.readString(target, StandardCharsets.UTF_8);
        assertThat(md).contains("Afternoon deployment");
        assertThat(md).doesNotContain("Morning work");
        // The custom marker exports under its own name, not under the legacy enum value.
        assertThat(md).contains("`[Deployment]`");
    }

    @Test
    void aFilterThatMatchesNothingFailsBeforeWritingAnyFile() throws Exception {
        Path dir = buildTwoPartJournal();
        Path target = tempDir.resolve("empty.pdf");

        try {
            exportService.export(SessionJournalExportService.Format.PDF, dir, target,
                markerOptions("does-not-exist"));
            throw new AssertionError("expected an EmptyExportSelectionException");
        } catch (SessionJournalExportService.EmptyExportSelectionException expected) {
            assertThat(Files.exists(target)).isFalse();
        }
    }

    @Test
    void anEmptyJournalWithoutFiltersStillExports() throws Exception {
        ServerConnection connection = new ServerConnection("Empty", "10.0.0.1", 22, "daniel");
        connection.getSessionJournalConfig().setEnabled(true);
        SessionJournalSession session = service.createSession(
            connection, "tab-000000000000", settings, List.of(), false);
        session.close();
        Path target = tempDir.resolve("empty-ok.md");

        exportService.export(SessionJournalExportService.Format.MARKDOWN, session.getDirectory(),
            target, SessionJournalExportService.Options.defaults());

        assertThat(Files.exists(target)).isTrue();
    }

    @Test
    void anUnfilteredBundleStaysTheVerbatimCopyItAlwaysWas() throws Exception {
        Path dir = buildTwoPartJournal();
        Path target = tempDir.resolve("verbatim.zip");

        exportService.export(SessionJournalExportService.Format.HTML_BUNDLE, dir, target,
            SessionJournalExportService.Options.defaults());

        String log = readZipEntry(target, SessionJournalLogReader.BASE_FILE_NAME + ".json");
        assertThat(log).contains("morning-command");
        assertThat(log).contains("afternoon-command");
        assertThat(readZipEntry(target, "journal.xml")).contains("Morning work");
    }

    @Test
    void aFilteredBundleTrimsTheLogToWhatTheExportedEntriesReference() throws Exception {
        Path dir = buildTwoPartJournal();
        Path target = tempDir.resolve("filtered-bundle.zip");

        exportService.export(SessionJournalExportService.Format.HTML_BUNDLE, dir, target,
            markerOptions("deploy"));

        String log = readZipEntry(target, SessionJournalLogReader.BASE_FILE_NAME + ".json");
        // Only the sequence range of the surviving entry survives with it.
        assertThat(log).contains("afternoon-command");
        assertThat(log).contains("afternoon-output");
        assertThat(log).doesNotContain("morning-command");
        assertThat(log).doesNotContain("morning-output");

        String xml = readZipEntry(target, "journal.xml");
        assertThat(xml).contains("Afternoon deployment");
        assertThat(xml).doesNotContain("Morning work");
        // The marker definition travels with the bundle so it renders standalone.
        assertThat(xml).contains("#7c3aed");

        String html = readZipEntry(target, "journal.html");
        assertThat(html).contains("Afternoon deployment");
        assertThat(html).doesNotContain("Morning work");
        assertThat(html).contains("excerpt-banner");
    }

    @Test
    void aFilteredBundleRecomputesTheHeaderCountsSoTheyMatchWhatItShows() throws Exception {
        Path dir = buildTwoPartJournal();
        Path target = tempDir.resolve("counted-bundle.zip");

        exportService.export(SessionJournalExportService.Format.HTML_BUNDLE, dir, target,
            markerOptions("deploy"));

        String xml = readZipEntry(target, "journal.xml");
        // One input line survives the trim, so the command count must say one, not two.
        assertThat(xml).contains("<commandCount>1</commandCount>");
    }

    @Test
    void aFilteredBundleKeepsALogPartThatStillHasContentAndDropsTheRest() throws Exception {
        Path dir = buildTwoPartJournal();
        Path target = tempDir.resolve("kept-part.zip");

        exportService.export(SessionJournalExportService.Format.HTML_BUNDLE, dir, target,
            markerOptions("deploy"));

        List<String> names = zipEntryNames(target, null);
        assertThat(names.stream().filter(n -> n.startsWith(SessionJournalLogReader.BASE_FILE_NAME)).count())
            .isEqualTo(1);
        assertThat(names.stream().noneMatch(n -> n.endsWith(".gz"))).isTrue();
    }

    @Test
    void theRewrittenLogIsStillReadableByTheLogReader() throws Exception {
        Path dir = buildTwoPartJournal();
        Path target = tempDir.resolve("readable-bundle.zip");
        exportService.export(SessionJournalExportService.Format.HTML_BUNDLE, dir, target,
            markerOptions("deploy"));

        Path extracted = tempDir.resolve("extracted-bundle");
        try (net.lingala.zip4j.ZipFile zip = new net.lingala.zip4j.ZipFile(target.toFile())) {
            zip.extractAll(extracted.toString());
        }
        List<SessionJournalLogEntry> lines = SessionJournalLogReader.readAfter(extracted, 0);

        assertThat(lines).hasSize(2);
        assertThat(lines.stream().map(SessionJournalLogEntry::seq).toList()).containsExactly(3L, 4L);
    }

    @Test
    void anArchiveSkipsJournalsWhereTheFilterMatchesNothingAndReportsThem() throws Exception {
        Path withDeploy = buildTwoPartJournal();
        Path withoutDeploy = buildSampleJournal();
        Path target = tempDir.resolve("mixed.zip");

        var result = exportService.exportArchive(SessionJournalExportService.Format.MARKDOWN,
            List.of(withDeploy, withoutDeploy), target, markerOptions("deploy"), null);

        assertThat(result.skippedJournals()).containsExactly(withoutDeploy);
        assertThat(zipEntryNames(target, null)).hasSize(1);
    }

    @Test
    void anArchiveFailsOnlyWhenEveryJournalCameOutEmpty() throws Exception {
        Path target = tempDir.resolve("all-empty.zip");

        try {
            exportService.exportArchive(SessionJournalExportService.Format.MARKDOWN,
                List.of(journalDir), target, markerOptions("does-not-exist"), null);
            throw new AssertionError("expected an EmptyExportSelectionException");
        } catch (SessionJournalExportService.EmptyExportSelectionException expected) {
            assertThat(Files.exists(target)).isFalse();
        }
    }

    @Test
    void previewCountsWithoutWritingAnythingOrCallingTheAi() throws Exception {
        Path dir = buildTwoPartJournal();

        var preview = exportService.preview(dir, markerOptions("deploy"));

        assertThat(preview.totalEntries()).isEqualTo(2);
        assertThat(preview.keptEntries()).isEqualTo(1);
    }

    @Test
    void aDateWindowCoveringTodayKeepsEverythingAndOneInThePastKeepsNothing() throws Exception {
        Path dir = buildTwoPartJournal();
        java.time.LocalDate today = java.time.LocalDate.now();

        assertThat(exportService.preview(dir, windowOptions(today, today)).keptEntries()).isEqualTo(2);
        assertThat(exportService.preview(dir,
            windowOptions(today.minusYears(1), today.minusYears(1))).keptEntries()).isEqualTo(0);
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
}
