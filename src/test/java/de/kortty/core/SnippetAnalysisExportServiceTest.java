package de.kortty.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.core.SnippetAnalysisDiagramRasterizer.RasterizedDiagram;
import de.kortty.core.SnippetAnalysisExportService.DiagramOutcome;
import de.kortty.core.SnippetAnalysisExportService.ExportOptions;
import de.kortty.core.SnippetAnalysisExportService.ExportResult;
import de.kortty.core.SnippetAnalysisExportService.Format;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.assertThrows;

/**
 * The code-analysis report exporter end to end: the PDF (via PDFTextStripper and its structure),
 * HTML, Markdown and JSON, for reports before and after applying, with a fake diagram rasterizer so
 * no JavaFX toolkit is needed.
 */
class SnippetAnalysisExportServiceTest {

    private static final Instant EXPORTED = Instant.parse("2026-07-10T08:30:00Z");
    private static final ExportBranding BRANDING = ExportBranding.defaults();
    private static final String LEGACY_WATERMARK = "AI code analysis export from korTTY by Daniel Mengel";

    private Locale previous;
    private Path directory;

    @BeforeClass
    void setUp() throws IOException {
        previous = LanguageManager.getInstance().getCurrentLocale();
        LanguageManager.getInstance().setLocale(Locale.ENGLISH);
        directory = Files.createTempDirectory("analysis-export-test-");
    }

    @AfterClass(alwaysRun = true)
    void tearDown() throws IOException {
        if (previous != null) {
            LanguageManager.getInstance().setLocale(previous);
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ---- helpers ----

    private static ExportOptions options() {
        return new ExportOptions(false, false, Locale.ENGLISH, ZoneOffset.UTC, EXPORTED, BRANDING);
    }

    private static BufferedImage image(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(new Color(0x0066CC));
        graphics.fillRect(width / 4, height / 4, width / 2, height / 2);
        graphics.dispose();
        return image;
    }

    /** A rasterizer that "renders" at 2x: the image is twice the unit size. */
    private static SnippetAnalysisDiagramRasterizer fake(int unitWidth, int unitHeight) {
        return diagram -> new RasterizedDiagram(DiagramOutcome.rendered(), image(unitWidth * 2, unitHeight * 2),
            unitWidth, unitHeight);
    }

    private static SnippetAnalysisDiagramRasterizer failing(String message) {
        return diagram -> RasterizedDiagram.failed(DiagramOutcome.Status.FAILED, message);
    }

    private static SnippetAnalysisExportService service() {
        return new SnippetAnalysisExportService(fake(400, 300), BRANDING);
    }

    private Path file(String name) {
        return directory.resolve(name);
    }

    private static String pdfText(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            return lfStripper().getText(document);
        }
    }

    private static List<String> pageTexts(PDDocument document) throws IOException {
        List<String> pages = new ArrayList<>();
        PDFTextStripper stripper = lfStripper();
        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            pages.add(stripper.getText(document));
        }
        return pages;
    }

    private static SnippetAnalysisReport withFindings(SnippetAnalysisReport base,
                                                      List<SnippetAnalysisReport.Finding> findings) {
        return new SnippetAnalysisReport(base.kind(), base.header(), base.summary(), findings, base.dependencies(),
            base.diagram(), base.apply(), base.verification(), base.analysedCode(), base.resultCode());
    }

    private static SnippetAnalysisReport.Finding finding(String id, String category, String title, String detail) {
        return new SnippetAnalysisReport.Finding(id, category, category, AnalysisSeverity.MEDIUM, "medium", title,
            detail, "Do the right thing.", null, false, null, List.of(), List.of(), null);
    }

    // ---- PDF ----

    @Test
    void preApplyPdfHasCoverContentsOutlineAndMetadata() throws Exception {
        Path pdf = file("pre.pdf");
        ExportResult result = service().export(pdf, Format.PDF, SnippetAnalysisReportFixtures.preReport(), options());
        assertThat(result.writtenFiles()).containsExactly(pdf);
        assertThat(result.diagram().status()).isEqualTo(DiagramOutcome.Status.RENDERED);
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            assertThat(document.getNumberOfPages()).isAtLeast(2);
            assertThat(result.pageCount()).isEqualTo(document.getNumberOfPages());
            String text = lfStripper().getText(document);
            assertThat(text).contains("SEC-1");
            assertThat(text).contains("Unquoted variable expansion in rm -rf");
            assertThat(text).contains("Contents");
            assertThat(text).contains("REPORT BEFORE APPLYING");
            assertThat(text).contains("Before applying");
            assertThat(text).contains("Selected for applying");
            assertThat(text).contains("Findings by category and severity");
            assertThat(text).contains("deploy_release.sh");
            assertThat(text).contains("Code at line 12");
            assertThat(text).contains("rm -rf $TARGET_DIR/*");
            assertThat(text).contains("Page 1 of " + document.getNumberOfPages());
            assertThat(text).doesNotContain(LEGACY_WATERMARK);
            // The three times are separate: analysed and exported; nothing applied yet.
            assertThat(text).contains("Jul 9, 2026");
            assertThat(text).contains("Jul 10, 2026");

            PDOutlineItem root = document.getDocumentCatalog().getDocumentOutline().getFirstChild();
            assertThat(root.getTitle()).contains("deploy_release.sh");
            List<String> children = new ArrayList<>();
            PDOutlineItem security = null;
            for (PDOutlineItem child : root.children()) {
                children.add(child.getTitle());
                if (child.getTitle().startsWith("Security")) {
                    security = child;
                }
            }
            assertThat(children).contains("Overview");
            assertThat(children).contains("Summary");
            assertThat(children).contains("Flow diagram");
            assertThat(security).isNotNull();
            List<String> grandchildren = new ArrayList<>();
            security.children().forEach(item -> grandchildren.add(item.getTitle()));
            assertThat(grandchildren.stream().anyMatch(title -> title.startsWith("SEC-1"))).isTrue();

            assertThat(document.getDocumentInformation().getTitle()).contains("deploy_release.sh");
            assertThat(document.getDocumentInformation().getProducer()).isEqualTo("korTTY");
            assertThat(document.getDocumentInformation().getCreator()).startsWith("korTTY");
            assertThat(document.getDocumentCatalog().getLanguage()).isEqualTo("en");
            // The contents rows are links.
            assertThat(document.getPage(0).getAnnotations().size()).isAtLeast(3);
        }
    }

    @Test
    void postApplyPdfShowsResultStatusesTokensAndDiff() throws Exception {
        Path pdf = file("post.pdf");
        service().export(pdf, Format.PDF, SnippetAnalysisReportFixtures.postReport(), options());
        String text = pdfText(pdf);
        assertThat(text).contains("REPORT AFTER APPLYING");
        assertThat(text).contains("Apply result");
        assertThat(text).contains("Accepted and applied");
        assertThat(text).contains("Applied");
        assertThat(text).contains("Not selected");
        assertThat(text).contains("Unconfirmed");
        assertThat(text).contains("01:34");
        assertThat(text).contains("1,204 prompt / 3,388 completion / 4,592 total (512 cached)");
        assertThat(text).contains("@@ -");
        assertThat(text).contains("sha256sum --check install.sh.sha256");
        assertThat(text).contains("Changes to the script");
        assertThat(text).contains("Why it changed");
        assertThat(text).contains("Verification");
        assertThat(text).contains("Still present");
        assertThat(text).contains("Selected findings by result");

        SnippetAnalysisRecord rejected = SnippetAnalysisReportFixtures.analysed()
            .withRun(SnippetAnalysisReportFixtures.run(SnippetAnalysisRecord.RunOutcome.REJECTED, false));
        Path rejectedPdf = file("rejected.pdf");
        service().export(rejectedPdf, Format.PDF, SnippetAnalysisReports.postApply(rejected, null, List.of(),
            SnippetAnalysisReportFixtures.context()), options());
        String rejectedText = pdfText(rejectedPdf);
        assertThat(rejectedText).contains("Rejected");
        assertThat(rejectedText).contains("Proposed changes (not applied)");
    }

    @Test
    void pdfKeepsCardsTogether() throws Exception {
        List<SnippetAnalysisReport.Finding> findings = new ArrayList<>();
        String longDetail = ("This finding has a long explanation that wraps over several lines of the card, "
            + "so that thirty of them need many pages. ").repeat(5);
        for (int index = 1; index <= 30; index++) {
            String category = index % 3 == 0 ? "security" : index % 3 == 1 ? "optimization" : "design";
            findings.add(finding(String.format(Locale.ROOT, "K%02d", index), category, "Finding number " + index,
                longDetail + "\n\n" + longDetail));
        }
        SnippetAnalysisReport report = withFindings(SnippetAnalysisReportFixtures.preReport(), findings);
        Path pdf = file("keep.pdf");
        service().export(pdf, Format.PDF, report, options());
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            List<String> pages = pageTexts(document);
            assertThat(pages.size()).isGreaterThan(8);
            for (int index = 1; index <= 30; index++) {
                String id = String.format(Locale.ROOT, "K%02d", index);
                Pattern pattern = Pattern.compile("\\b" + id + "\\b");
                long occurrences = pages.stream().filter(page -> pattern.matcher(page).find()).count();
                assertWithMessage("pages showing %s", id).that(occurrences).isEqualTo(1L);
                // The whole card is on the page of its id: both detail paragraphs and the recommendation.
                String page = pages.stream().filter(p -> pattern.matcher(p).find()).findFirst().orElseThrow();
                assertWithMessage("card %s split", id).that(page).contains("Finding number " + index + "\n");
                assertWithMessage("card %s split", id).that(page).contains("Do the right thing.");
            }
        }
    }

    @Test
    void pdfBranding() throws Exception {
        Color grey = ExportBranding.DEFAULT_WATERMARK_COLOR;
        ExportBranding custom = new ExportBranding(false, ExportBranding.DEFAULT_WATERMARK_TEXT, grey, true,
            "ACME internal", false);
        Path customPdf = file("brand-custom.pdf");
        // options() carries the default branding; the service's branding applies only without one.
        ExportOptions noBranding = new ExportOptions(false, false, Locale.ENGLISH, ZoneOffset.UTC, EXPORTED, null);
        new SnippetAnalysisExportService(fake(400, 300), custom).export(customPdf, Format.PDF,
            SnippetAnalysisReportFixtures.preReport(), noBranding);
        String customText = pdfText(customPdf);
        assertThat(customText).contains("ACME internal");
        assertThat(customText).doesNotContain(LEGACY_WATERMARK);
        assertThat(customText).doesNotContain(ExportBranding.REPOSITORY_URL);

        ExportBranding off = new ExportBranding(false, ExportBranding.DEFAULT_WATERMARK_TEXT, grey, false,
            "ACME internal", false);
        Path offPdf = file("brand-off.pdf");
        new SnippetAnalysisExportService(fake(400, 300), off).export(offPdf, Format.PDF,
            SnippetAnalysisReportFixtures.preReport(), noBranding);
        String offText = pdfText(offPdf);
        assertThat(offText).doesNotContain("ACME internal");
        assertThat(offText).doesNotContain(ExportBranding.defaultFooterText());

        ExportBranding watermark = new ExportBranding(true, "CONFIDENTIAL DRAFT", grey, true,
            ExportBranding.defaultFooterText(), true);
        Path watermarkPdf = file("brand-watermark.pdf");
        new SnippetAnalysisExportService(fake(400, 300), watermark).export(watermarkPdf, Format.PDF,
            SnippetAnalysisReportFixtures.preReport(), noBranding);
        try (PDDocument document = Loader.loadPDF(watermarkPdf.toFile())) {
            for (String page : pageTexts(document)) {
                assertThat(page).contains("CONFIDENTIAL DRAFT");
                assertThat(page).contains(ExportBranding.defaultFooterText());
            }
        }
    }

    @Test
    void wideDiagramGetsALandscapePage() throws Exception {
        Path pdf = file("wide.pdf");
        new SnippetAnalysisExportService(fake(1500, 300), BRANDING).export(pdf, Format.PDF,
            SnippetAnalysisReportFixtures.preReport(), options());
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            boolean landscape = false;
            for (PDPage page : document.getPages()) {
                if (page.getMediaBox().getWidth() > page.getMediaBox().getHeight()) {
                    landscape = true;
                    assertThat(page.getResources().getXObjectNames().iterator().hasNext()).isTrue();
                }
            }
            assertThat(landscape).isTrue();
        }
    }

    @Test
    void tallDiagramIsSlicedAcrossPages() throws Exception {
        Path pdf = file("tall.pdf");
        new SnippetAnalysisExportService(fake(300, 3000), BRANDING).export(pdf, Format.PDF,
            SnippetAnalysisReportFixtures.preReport(), options());
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            List<String> pages = pageTexts(document);
            long diagramPages = pages.stream().filter(page -> page.contains("Flow diagram (part ")).count();
            assertThat(diagramPages).isGreaterThan(1L);
            int images = 0;
            for (PDPage page : document.getPages()) {
                for (COSName name : page.getResources().getXObjectNames()) {
                    if (page.getResources().isImageXObject(name)) {
                        images++;
                    }
                }
            }
            assertThat((long) images).isEqualTo(diagramPages);
        }
    }

    @Test
    void failedDiagramIsNotedNotDropped() throws Exception {
        SnippetAnalysisExportService service = new SnippetAnalysisExportService(failing("boom"), BRANDING);
        SnippetAnalysisReport report = SnippetAnalysisReportFixtures.preReport();

        Path pdf = file("failed.pdf");
        ExportResult result = service.export(pdf, Format.PDF, report, options());
        assertThat(result.diagram().status()).isEqualTo(DiagramOutcome.Status.FAILED);
        assertThat(result.diagram().failed()).isTrue();
        assertThat(pdfText(pdf)).contains("The diagram could not be rendered: boom");

        Path html = file("failed.html");
        service.export(html, Format.HTML, report, options());
        String page = Files.readString(html, StandardCharsets.UTF_8);
        assertThat(page).contains("The diagram could not be rendered: boom");
        assertThat(page).contains("<details><summary>Mermaid</summary>");
        assertThat(page).doesNotContain("data:image/png;base64,");

        Path markdown = file("failed.md");
        ExportResult markdownResult = service.export(markdown, Format.MARKDOWN, report, options());
        String md = Files.readString(markdown, StandardCharsets.UTF_8);
        assertThat(md).contains("```mermaid\nflowchart TD");
        assertThat(md).contains("The diagram could not be rendered: boom");
        assertThat(Files.exists(file("failed.diagram.png"))).isFalse();
        assertThat(markdownResult.writtenFiles()).containsExactly(markdown);
    }

    @Test
    void renderedDiagramIsEmbeddedEverywhere() throws Exception {
        SnippetAnalysisReport report = SnippetAnalysisReportFixtures.preReport();
        Path pdf = file("image.pdf");
        service().export(pdf, Format.PDF, report, options());
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            boolean image = false;
            for (PDPage page : document.getPages()) {
                for (COSName name : page.getResources().getXObjectNames()) {
                    image |= page.getResources().isImageXObject(name);
                }
            }
            assertThat(image).isTrue();
        }
        Path html = file("image.html");
        service().export(html, Format.HTML, report, options());
        // A 2x image shown at its 1x unit size stays crisp.
        assertThat(Files.readString(html)).contains("width=\"400\" height=\"300\" src=\"data:image/png;base64,");

        Path markdown = file("image report.md");
        ExportResult result = service().export(markdown, Format.MARKDOWN, report, options());
        Path png = file("image report.diagram.png");
        assertThat(result.writtenFiles()).containsExactly(markdown, png).inOrder();
        assertThat(Files.size(png)).isGreaterThan(8L);
        String md = Files.readString(markdown);
        assertThat(md).contains("![Flow diagram](<image report.diagram.png>)");
        assertThat(md).contains("```mermaid\n");
        // JSON never renders the diagram.
        ExportResult json = service().export(file("image.json"), Format.JSON, report, options());
        assertThat(json.diagram().status()).isEqualTo(DiagramOutcome.Status.NONE);
    }

    @Test
    void symbolsAndParagraphs() throws Exception {
        SnippetAnalysisReport report = withFindings(SnippetAnalysisReportFixtures.preReport(), List.of(
            finding("SYM-1", "security", "Symbols", "Arrows → ticks ✓ bounds ≥ 3\n\nSecond paragraph here.")));
        Path pdf = file("symbols.pdf");
        service().export(pdf, Format.PDF, report, options());
        String line = pdfText(pdf).lines().filter(value -> value.contains("Arrows")).findFirst().orElseThrow();
        assertThat(line).contains("→");
        assertThat(line).contains("✓");
        assertThat(line).contains(">= 3");
        assertThat(line).doesNotContain("?");

        List<ReportTextBlocks.Block> blocks = ReportTextBlocks.split("First paragraph.\n\nSecond paragraph.");
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(1).text()).isEqualTo("Second paragraph.");
        Path html = file("symbols.html");
        service().export(html, Format.HTML, report, options());
        assertThat(Files.readString(html)).contains("<p>Arrows → ticks ✓ bounds ≥ 3</p><p>Second paragraph here.</p>");
        Path markdown = file("symbols.md");
        service().export(markdown, Format.MARKDOWN, report, options());
        assertThat(Files.readString(markdown)).contains("Arrows → ticks ✓ bounds ≥ 3\n\nSecond paragraph here.\n");
    }

    // ---- Markdown ----

    @Test
    void markdownEscapesTextAndFencesCode() throws Exception {
        SnippetAnalysisReport base = SnippetAnalysisReportFixtures.postReport();
        SnippetAnalysisReport.Header header = base.header();
        SnippetAnalysisReport.Header renamed = new SnippetAnalysisReport.Header("my_script_v2.sh", header.snippetId(),
            header.scriptLanguage(), header.aiProfileName(), header.aiModel(), header.includedSkills(),
            header.analysisLanguageCode(), header.codeTextLanguageCode(), header.analysedAt(), header.appliedAt(),
            header.sourceSha256(), header.stale(), header.runNumber(), header.runCount());
        List<SnippetAnalysisReport.Finding> findings = new ArrayList<>();
        SnippetAnalysisReport.Finding first = base.findings().getFirst();
        findings.add(new SnippetAnalysisReport.Finding(first.id(), first.displayCategory(), first.category(),
            first.severity(), first.severityLabel(), "Use `set -e` *always* | a_b # x", first.detail(),
            first.recommendation(), first.line(), first.selected(), first.status(), first.changes(),
            first.hunkIndexes(), first.excerpt()));
        findings.addAll(base.findings().subList(1, base.findings().size()));
        SnippetAnalysisReport report = new SnippetAnalysisReport(base.kind(), renamed, base.summary(), findings,
            base.dependencies(), base.diagram(), base.apply(), base.verification(), base.analysedCode(),
            new SnippetAnalysisReport.CodeSnapshot("echo ```x```\n", "bash"));

        Path markdown = file("escape.md");
        service().export(markdown, Format.MARKDOWN, report, options().withIncludeFullCode(true));
        String md = Files.readString(markdown, StandardCharsets.UTF_8);
        assertThat(md).contains("my\\_script\\_v2.sh");
        assertThat(md).contains("Use \\`set -e\\` \\*always\\* \\| a\\_b \\# x");
        // In the status table the title is one escaped cell.
        assertThat(md).contains("| Use \\`set -e\\` \\*always\\* \\| a\\_b \\# x |");
        // The appendix contains a backtick run of 3, so its fence is longer.
        assertThat(md).contains("````bash\necho ```x```\n````");
        // The AI's own code fence in the recommendation is re-fenced.
        assertThat(md).contains("> ```bash");

        assertThat(SnippetAnalysisMarkdownWriter.fence("a ```` b", "")).startsWith("`````\n");
        assertThat(SnippetAnalysisMarkdownWriter.inline("- item")).isEqualTo("\\- item");
        assertThat(SnippetAnalysisMarkdownWriter.inline("1. step")).isEqualTo("1\\. step");
        assertThat(SnippetAnalysisMarkdownWriter.inline("<b>&")).isEqualTo("\\<b\\>&");
        assertThat(SnippetAnalysisMarkdownWriter.cell("a|b\nc")).isEqualTo("a\\|b<br>c");
    }

    @Test
    void markdownPostHasStatusTableAndDiff() throws Exception {
        Path markdown = file("post.md");
        service().export(markdown, Format.MARKDOWN, SnippetAnalysisReportFixtures.postReport(), options());
        String md = Files.readString(markdown, StandardCharsets.UTF_8);
        assertThat(md).contains("| ID | Severity | Category | Title | Line | Selected | Status |");
        assertThat(md).contains("```diff\n@@ -");
        assertThat(md).contains("\n+set -euo pipefail\n");
        assertThat(md).contains("\n-rm -rf $TARGET_DIR/*\n");
        assertThat(md).contains("**Accepted and applied**");
        // Inline code in AI prose stays a code span (its content unescaped); the rest is escaped.
        assertThat(md).contains("`$TARGET_DIR` is expanded unquoted in `rm -rf $TARGET_DIR/*`.");
        assertThat(md).contains("`curl … | bash` executes");
        assertThat(md).contains("## Verification");
        assertThat(md).contains(ExportBranding.REPOSITORY_URL);
    }

    @Test
    void markdownNeverOverwritesTheDiagramSilently() throws Exception {
        Path markdown = file("guarded.md");
        Path png = file("guarded.diagram.png");
        Files.writeString(png, "keep me");
        SnippetAnalysisReport report = SnippetAnalysisReportFixtures.preReport();
        assertThat(SnippetAnalysisExportService.assetFiles(markdown, Format.MARKDOWN, report)).containsExactly(png);
        assertThrows(FileAlreadyExistsException.class,
            () -> service().export(markdown, Format.MARKDOWN, report, options()));
        assertThat(Files.exists(markdown)).isFalse();
        assertThat(Files.exists(file(".guarded.md.tmp"))).isFalse();
        assertThat(Files.readString(png)).isEqualTo("keep me");

        service().export(markdown, Format.MARKDOWN, report, options().withOverwriteAssets(true));
        assertThat(Files.exists(markdown)).isTrue();
        assertThat(Files.readString(png, StandardCharsets.ISO_8859_1)).startsWith("\u0089PNG");
        // No diagram, no asset; other formats never have one.
        assertThat(SnippetAnalysisExportService.assetFiles(markdown, Format.PDF, report)).isEmpty();
        assertThat(SnippetAnalysisExportService.assetFiles(markdown, Format.MARKDOWN, false)).isEmpty();
    }

    // ---- HTML ----

    @Test
    void htmlUsesTheLocaleAndLinksEverything() throws Exception {
        Path html = file("post.html");
        ExportOptions german = new ExportOptions(false, false, Locale.GERMAN, ZoneId.of("Europe/Berlin"), EXPORTED,
            BRANDING);
        service().export(html, Format.HTML, SnippetAnalysisReportFixtures.postReport(), german);
        String page = Files.readString(html, StandardCharsets.UTF_8);
        assertThat(page).startsWith("<!doctype html><html lang=\"de\"");
        assertThat(page).contains("09.07.2026, 17:11");
        assertThat(page).contains("10.07.2026, 10:30");
        assertThat(page).contains("SEC-1");
        assertThat(page).contains("class=\"add\"");
        assertThat(page).contains("class=\"del\"");
        assertThat(page).contains("href=\"#f-SEC-1\"");
        assertThat(page).contains("id=\"f-SEC-1\"");
        assertThat(page).contains("href=\"#changes\"");
        assertThat(page).contains("<svg role=\"img\"");
        assertThat(page).contains("<code>$TARGET_DIR</code> is expanded unquoted");
        assertThat(page).contains("@media print");
        assertThat(page).contains("break-inside:avoid");
        assertThat(page).doesNotContain(LEGACY_WATERMARK);
        assertThat(page).contains("<a href=\"" + ExportBranding.REPOSITORY_URL + "\">");
    }

    // ---- JSON ----

    @Test
    void jsonCarriesSchemaKindAndStatuses() throws Exception {
        Path json = file("post.json");
        ExportResult result = service().export(json, Format.JSON, SnippetAnalysisReportFixtures.postReport(), options());
        assertThat(result.writtenFiles()).containsExactly(json);
        JsonObject root = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
        assertThat(root.get("schema").getAsString()).isEqualTo("kortty.snippetAnalysisReport/1");
        assertThat(root.get("kind").getAsString()).isEqualTo("POST_APPLY");
        assertThat(root.get("exportedAt").getAsString()).isEqualTo(EXPORTED.toString());
        assertThat(root.getAsJsonObject("header").get("analysedAt").getAsString())
            .isEqualTo(Instant.ofEpochMilli(SnippetAnalysisReportFixtures.ANALYSED_AT).toString());
        List<String> statuses = new ArrayList<>();
        root.getAsJsonArray("findings").forEach(item -> statuses.add(
            item.getAsJsonObject().get("id").getAsString() + "=" + item.getAsJsonObject().get("status").getAsString()));
        assertThat(statuses).containsAtLeast("SEC-1=applied", "OPT-1=unconfirmed", "DES-2=not-selected");
        JsonObject apply = root.getAsJsonObject("apply");
        assertThat(apply.get("outcome").getAsString()).isEqualTo("accepted");
        assertThat(apply.getAsJsonObject("tokens").get("total").getAsLong()).isEqualTo(4592L);
        assertThat(apply.getAsJsonObject("diff").getAsJsonArray("hunks").size()).isGreaterThan(0);
        assertThat(root.getAsJsonObject("diagram").get("mermaid").getAsString()).contains("flowchart TD");
        assertThat(root.getAsJsonObject("verification").getAsJsonArray("new").size()).isEqualTo(1);

        Path pre = file("pre.json");
        service().export(pre, Format.JSON, SnippetAnalysisReportFixtures.preReport(), options());
        JsonObject preRoot = JsonParser.parseString(Files.readString(pre)).getAsJsonObject();
        assertThat(preRoot.get("kind").getAsString()).isEqualTo("PRE_APPLY");
        assertThat(preRoot.get("apply").isJsonNull()).isTrue();
    }

    // ---- file names ----

    @Test
    void suggestFileNameSanitizesAndFallsBack() {
        assertThat(SnippetAnalysisExportService.suggestFileName(SnippetAnalysisReportFixtures.preReport(), Format.PDF,
            ZoneOffset.UTC)).isEqualTo("deploy_release.sh-analysis-20260709-1511.pdf");
        assertThat(SnippetAnalysisExportService.suggestFileName(SnippetAnalysisReportFixtures.postReport(),
            Format.MARKDOWN, ZoneOffset.UTC)).isEqualTo("deploy_release.sh-result-20260709-1553.md");
        assertThat(SnippetAnalysisExportService.suggestFileName("my script: v2/<prod>", false, null, Format.HTML,
            ZoneOffset.UTC)).isEqualTo("my_script_v2_prod-analysis.html");
        assertThat(SnippetAnalysisExportService.suggestFileName("  ///  ", false, null, Format.JSON, ZoneOffset.UTC))
            .isEqualTo("code-analysis.json");
        assertThat(SnippetAnalysisExportService.suggestFileName("Über Skript ä", false, null, Format.PDF, ZoneOffset.UTC))
            .isEqualTo("Über_Skript_ä-analysis.pdf");
        String longName = "x".repeat(200);
        assertThat(SnippetAnalysisExportService.suggestFileName(longName, false, null, Format.PDF, ZoneOffset.UTC))
            .isEqualTo("x".repeat(80) + "-analysis.pdf");
    }

    /** PDFTextStripper ends lines with the platform separator; the assertions expect "\n" on every OS. */
    private static PDFTextStripper lfStripper() throws java.io.IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setLineSeparator("\n");
        return stripper;
    }
}
