package de.kortty.core;

import javafx.application.Platform;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Writes realistic before/after sample reports (PDF, HTML, Markdown, JSON) from
 * {@link SnippetAnalysisReportFixtures} with the real Mermaid diagram, plus PNG previews of every
 * PDF page, for reviewing the report design by eye:
 * {@code ./gradlew snippetAnalysisReportSamples -PsamplesDir=/some/dir}.
 */
public final class SnippetAnalysisReportSamples {

    private SnippetAnalysisReportSamples() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length > 0 ? args[0] : "build/snippet-analysis-report-samples");
        Files.createDirectories(directory);
        // Without initialize() every lookup falls back to English.
        LanguageManager.getInstance().initialize(null);
        LanguageManager.getInstance().setLocale(args.length > 1 ? Locale.forLanguageTag(args[1]) : Locale.ENGLISH);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> {
            Thread worker = new Thread(() -> {
                try {
                    write(directory);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    done.countDown();
                }
            }, "report-samples");
            worker.setDaemon(true);
            worker.start();
        });
        boolean finished = done.await(180, TimeUnit.SECONDS);
        MermaidRenderService.dispose();
        Platform.runLater(Platform::exit);
        if (!finished) {
            throw new IllegalStateException("Timed out writing the sample reports");
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Writing the sample reports failed", failure.get());
        }
        System.out.println("Sample reports written to " + directory.toAbsolutePath());
        System.exit(0);
    }

    private static void write(Path directory) throws Exception {
        SnippetAnalysisExportService service = new SnippetAnalysisExportService();
        Locale locale = LanguageManager.getInstance().getCurrentLocale();
        SnippetAnalysisExportService.ExportOptions options = new SnippetAnalysisExportService.ExportOptions(
            true, true, locale, ZoneId.of("Europe/Berlin"), Instant.now(), ExportBranding.defaults());
        for (SnippetAnalysisReport report : List.of(SnippetAnalysisReportFixtures.preReport(),
                SnippetAnalysisReportFixtures.postReport())) {
            String base = report.isPost() ? "post-apply" : "pre-apply";
            for (SnippetAnalysisExportService.Format format : SnippetAnalysisExportService.Format.values()) {
                Path target = directory.resolve(base + format.getExtension());
                SnippetAnalysisExportService.ExportResult result = service.export(target, format, report, options);
                System.out.println(target.getFileName() + ": diagram " + result.diagram().status()
                    + (result.diagram().message().isBlank() ? "" : " (" + result.diagram().message() + ")")
                    + (result.pageCount() > 0 ? ", " + result.pageCount() + " pages" : ""));
            }
            try (var stale = Files.newDirectoryStream(directory, base + "-page-*.png")) {
                for (Path previous : stale) {
                    Files.delete(previous);
                }
            }
            try (PDDocument document = Loader.loadPDF(directory.resolve(base + ".pdf").toFile())) {
                PDFRenderer renderer = new PDFRenderer(document);
                for (int page = 0; page < document.getNumberOfPages(); page++) {
                    BufferedImage image = renderer.renderImageWithDPI(page, 110);
                    ImageIO.write(image, "png", directory.resolve(base + "-page-" + (page + 1) + ".png").toFile());
                }
            }
        }
    }
}
