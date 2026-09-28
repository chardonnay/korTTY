package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import de.kortty.core.SnippetAnalysisBatchExport.Item;
import de.kortty.core.SnippetAnalysisBatchExport.Packaging;
import de.kortty.core.SnippetAnalysisBatchExport.Result;
import de.kortty.core.SnippetAnalysisBatchExport.Skipped;
import de.kortty.core.SnippetAnalysisDiagramRasterizer.RasterizedDiagram;
import de.kortty.core.SnippetAnalysisExportService.DiagramOutcome;
import de.kortty.core.SnippetAnalysisExportService.ExportOptions;
import de.kortty.core.SnippetAnalysisExportService.Format;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/** Batch export of several snippets' analyses: combined PDF/HTML/Markdown/JSON and ZIP, with a fake rasterizer. */
class SnippetAnalysisBatchExportTest {

    private static final Instant EXPORTED = Instant.parse("2026-09-28T08:30:00Z");
    private static final ExportBranding BRANDING = ExportBranding.defaults();

    private Locale previous;
    private Path directory;

    @BeforeClass
    void setUp() throws IOException {
        previous = LanguageManager.getInstance().getCurrentLocale();
        LanguageManager.getInstance().setLocale(Locale.ENGLISH);
        directory = Files.createTempDirectory("analysis-batch-export-test-");
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

    private static ExportOptions options() {
        return new ExportOptions(false, false, Locale.ENGLISH, ZoneOffset.UTC, EXPORTED, BRANDING);
    }

    private static SnippetAnalysisExportService service() {
        return new SnippetAnalysisExportService(diagram -> {
            BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 800, 600);
            graphics.dispose();
            return new RasterizedDiagram(DiagramOutcome.rendered(), image, 400, 300);
        }, BRANDING);
    }

    /** Two reports: "deploy_release.sh" before applying and "cleanup.sh" after applying. */
    private static List<Item> items() {
        SnippetAnalysisReport pre = SnippetAnalysisReportFixtures.preReport();
        SnippetAnalysisReport post = SnippetAnalysisReportFixtures.postReport();
        return List.of(new Item("snippet-deploy", "deploy_release.sh", pre), new Item("snippet-cleanup", "cleanup.sh", post));
    }

    private static List<Skipped> skipped() {
        return List.of(new Skipped("snippet-empty", "never-analysed.sh"));
    }

    private Result export(String name, Format format, Packaging packaging) throws IOException {
        return service().exportBatch(directory.resolve(name), format, packaging, items(), skipped(), options(),
            null, null);
    }

    @Test
    void theNewestAcceptedRunDecidesTheKindOfReport() {
        SnippetAnalysisHistory applied = SnippetAnalysisHistory.empty("snippet-deploy")
            .withRecords(SnippetAnalysisReportFixtures.history());
        assertThat(SnippetAnalysisBatchExport.reportFor(applied, SnippetAnalysisReportFixtures.context()).isPost())
            .isTrue();
        SnippetAnalysisHistory analysed = SnippetAnalysisHistory.empty("snippet-deploy")
            .withRecords(List.of(SnippetAnalysisReportFixtures.analysed()));
        assertThat(SnippetAnalysisBatchExport.reportFor(analysed, SnippetAnalysisReportFixtures.context()).isPost())
            .isFalse();
        assertThat(SnippetAnalysisBatchExport.reportFor(SnippetAnalysisHistory.empty("x"), null)).isNull();
        assertThat(SnippetAnalysisBatchExport.reportFor(null, null)).isNull();
    }

    @Test
    void aCombinedPdfHasACoverWithContentsAndOneChapterPerSnippet() throws IOException {
        Result result = export("combined.pdf", Format.PDF, Packaging.COMBINED);
        assertThat(result.exported()).isEqualTo(2);
        assertThat(result.skipped()).hasSize(1);
        try (PDDocument document = Loader.loadPDF(directory.resolve("combined.pdf").toFile())) {
            assertThat(result.pageCount()).isEqualTo(document.getNumberOfPages());
            PDFTextStripper stripper = lfStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String cover = stripper.getText(document);
            assertThat(cover).contains("Code analysis reports");
            assertThat(cover).contains("deploy_release.sh");
            assertThat(cover).contains("cleanup.sh");
            assertThat(cover).contains("never-analysed.sh");
            assertThat(cover).contains("Skipped");
            // Cover links jump to the chapters.
            long goTo = document.getPage(0).getAnnotations().stream()
                .filter(annotation -> annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link
                    && link.getAction() instanceof org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo)
                .count();
            assertThat(goTo).isEqualTo(2);
            PDOutlineItem root = document.getDocumentCatalog().getDocumentOutline().getFirstChild();
            List<String> chapters = new ArrayList<>();
            for (PDOutlineItem child : root.children()) {
                chapters.add(child.getTitle());
            }
            assertThat(chapters).containsExactly("deploy_release.sh", "cleanup.sh").inOrder();
            String all = lfStripper().getText(document);
            assertThat(all).contains("REPORT BEFORE APPLYING");
            assertThat(all).contains("REPORT AFTER APPLYING");
        }
    }

    @Test
    void aCombinedHtmlPageHasATableOfContentsAndPrefixedSections() throws IOException {
        export("combined.html", Format.HTML, Packaging.COMBINED);
        String html = Files.readString(directory.resolve("combined.html"), StandardCharsets.UTF_8);
        assertThat(html).startsWith("<!doctype html><html lang=\"en\">");
        assertThat(html).contains("<a href=\"#r1\">deploy_release.sh</a>");
        assertThat(html).contains("<a href=\"#r2\">cleanup.sh</a>");
        assertThat(html).contains("<article class=\"report\" id=\"r1\">");
        assertThat(html).contains("<article class=\"report\" id=\"r2\">");
        assertThat(html).contains("id=\"r1-overview\"");
        assertThat(html).contains("id=\"r2-overview\"");
        assertThat(html).contains("href=\"#r2-changes\"");
        assertThat(html).doesNotContain("id=\"overview\"");
        assertThat(html).contains("never-analysed.sh");
        // Self-contained: the diagrams are inline images.
        assertThat(html).contains("data:image/png;base64,");
        assertThat(html.split("<footer>", -1)).hasLength(2);
    }

    @Test
    void aCombinedMarkdownFileHasSectionsAndDiagramImagesNextToIt() throws IOException {
        Result result = export("combined.md", Format.MARKDOWN, Packaging.COMBINED);
        String md = Files.readString(directory.resolve("combined.md"), StandardCharsets.UTF_8);
        assertThat(md).startsWith("# Code analysis reports\n");
        assertThat(md).contains("1. deploy\\_release.sh");
        assertThat(md).contains("## AI Code Analysis");
        assertThat(md).doesNotContain("\n# AI Code Analysis");
        assertThat(md).contains("```mermaid");
        assertThat(md).contains("never-analysed.sh");
        assertThat(md).contains("(<combined.01-diagram.png>)");
        assertThat(md).contains("(<combined.02-diagram.png>)");
        assertThat(Files.exists(directory.resolve("combined.01-diagram.png"))).isTrue();
        assertThat(result.writtenFiles()).hasSize(3);

        // A second export must not replace the images without asking.
        assertThrows(FileAlreadyExistsException.class, () -> export("combined.md", Format.MARKDOWN, Packaging.COMBINED));
    }

    @Test
    void aCombinedJsonFileIsAnArrayOfReports() throws IOException {
        export("combined.json", Format.JSON, Packaging.COMBINED);
        JsonArray array = JsonParser.parseString(Files.readString(directory.resolve("combined.json"),
            StandardCharsets.UTF_8)).getAsJsonArray();
        assertThat(array.size()).isEqualTo(2);
        assertThat(array.get(0).getAsJsonObject().get("schema").getAsString())
            .isEqualTo(SnippetAnalysisJsonWriter.SCHEMA);
        assertThat(array.get(0).getAsJsonObject().get("kind").getAsString()).isEqualTo("PRE_APPLY");
        assertThat(array.get(1).getAsJsonObject().get("kind").getAsString()).isEqualTo("POST_APPLY");
    }

    @Test
    void aZipHoldsOneReportPerSnippetAndTheSkippedList() throws IOException {
        export("reports-md.zip", Format.MARKDOWN, Packaging.ZIP);
        List<String> names = zipEntries(directory.resolve("reports-md.zip"));
        assertThat(names).hasSize(5);
        assertThat(names.get(0)).startsWith("01-deploy_release.sh-analysis-");
        assertThat(names.get(0)).endsWith(".md");
        assertThat(names.get(1)).endsWith(".diagram.png");
        assertThat(names.get(2)).startsWith("02-");
        assertThat(names).contains("skipped.txt");

        export("reports-pdf.zip", Format.PDF, Packaging.ZIP);
        List<String> pdfs = zipEntries(directory.resolve("reports-pdf.zip"));
        assertThat(pdfs.stream().filter(name -> name.endsWith(".pdf")).count()).isEqualTo(2);

        export("reports-html.zip", Format.HTML, Packaging.ZIP);
        assertThat(zipEntries(directory.resolve("reports-html.zip")).stream()
            .filter(name -> name.endsWith(".html")).count()).isEqualTo(2);
    }

    @Test
    void progressIsReportedAndACancelWritesNothing() throws IOException {
        List<String> seen = new ArrayList<>();
        service().exportBatch(directory.resolve("progress.json"), Format.JSON, Packaging.COMBINED, items(), List.of(),
            options(), (done, total, name) -> seen.add(done + "/" + total + ":" + name), null);
        assertThat(seen).containsExactly("0/2:", "1/2:deploy_release.sh", "2/2:cleanup.sh").inOrder();

        AtomicInteger calls = new AtomicInteger();
        Path target = directory.resolve("cancelled.pdf");
        assertThrows(CancellationException.class, () -> service().exportBatch(target, Format.PDF, Packaging.COMBINED,
            items(), List.of(), options(), null, () -> calls.incrementAndGet() > 1));
        assertThat(Files.exists(target)).isFalse();
        assertThrows(IllegalArgumentException.class, () -> service().exportBatch(target, Format.PDF,
            Packaging.COMBINED, List.of(), skipped(), options(), null, null));
    }

    @Test
    void headingsAreShiftedOutsideCodeFencesOnly() {
        String shifted = SnippetAnalysisBatchExport.shiftHeadings("# Title\n## Sub\n```bash\n# comment\n```\ntext");
        assertThat(shifted).isEqualTo("## Title\n### Sub\n```bash\n# comment\n```\ntext");
        assertThat(SnippetAnalysisBatchExport.shiftHeadings("````\n```\n# inside\n````\n# out"))
            .isEqualTo("````\n```\n# inside\n````\n## out");
    }

    @Test
    void theCoverGrowsWithTheNumberOfRows() {
        assertThat(SnippetAnalysisBatchExport.coverPages(1)).isEqualTo(1);
        assertThat(SnippetAnalysisBatchExport.coverPages(200)).isGreaterThan(1);
    }

    private static List<String> zipEntries(Path zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    /** PDFTextStripper ends lines with the platform separator; the assertions expect "\n" on every OS. */
    private static PDFTextStripper lfStripper() throws java.io.IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setLineSeparator("\n");
        return stripper;
    }
}
