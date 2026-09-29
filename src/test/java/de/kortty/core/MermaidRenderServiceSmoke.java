package de.kortty.core;

import javafx.application.Platform;
import org.apache.pdfbox.Loader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** End-to-end smoke for the pinned Mermaid browser API, Java bridge, SVG and PNG output. */
public final class MermaidRenderServiceSmoke {

    private MermaidRenderServiceSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> {
            Thread worker = new Thread(() -> {
                try {
                    verifyFlowchartAndRaster();
                    verifyDiagramReportExports();
                    verifySupportedDiagramFamilies();
                    verifyGeneratedDiagramFamilies();
                    verifyArchivedModelAnswersRender();
                    verifySyntaxError();
                    verifyRendererRecoversAfterFailure();
                    verifyCancellationRestartsEngine();
                    verifyTimeoutRestartsEngine();
                    verifyDisposeCompletesRequests();
                } catch (Throwable throwable) {
                    failure.set(throwable);
                } finally {
                    complete.countDown();
                }
            }, "mermaid-render-smoke-worker");
            worker.setDaemon(true);
            worker.start();
        });

        boolean finished = complete.await(90, TimeUnit.SECONDS);
        MermaidRenderService.dispose();
        Platform.runLater(Platform::exit);
        if (!finished) {
            throw new IllegalStateException("Timed out waiting for Mermaid render smoke");
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Mermaid render smoke failed", failure.get());
        }
        System.out.println("Bundled Mermaid renderer smoke passed.");
    }

    private static void verifyFlowchartAndRaster() throws Exception {
        String source = """
            flowchart TD
              start_1(["Start"])
              work["Run command"]
              decision{"Succeeded?"}
              success["Complete"]
              failure["Handle failure"]
              stop_1(["Stop"])
              start_1 --> work
              work --> decision
              decision -->|yes| success
              decision -->|no| failure
              success --> stop_1
              failure --> stop_1
              class start_1,stop_1 setup
              class work,decision work
              class success success
              class failure failure
            """;
        MermaidRenderService.RenderResult result = MermaidRenderService.render(
            MermaidRenderService.RenderRequest.generatedFlow(
                source, MermaidRenderService.Theme.LIGHT, "#F8FAFC", true))
            .get(45, TimeUnit.SECONDS);
        if (!result.success() || !result.svg().contains("<svg")) {
            throw new AssertionError("Flowchart did not render: " + result.message());
        }
        byte[] png = result.png();
        if (png == null || png.length < 8
            || !new String(png, 1, 3, StandardCharsets.ISO_8859_1).equals("PNG")) {
            throw new AssertionError("Mermaid PNG output is missing or invalid");
        }
        if (result.nodeBounds().stream().noneMatch(node -> "work".equals(node.nodeId()))) {
            throw new AssertionError("Stable Mermaid node bounds were not returned: " + result.nodeBounds());
        }
        boolean boundsOutsideCanvas = result.nodeBounds().stream().anyMatch(node ->
            node.x() < 0 || node.y() < 0
                || node.x() + node.width() > result.width() + 0.01
                || node.y() + node.height() > result.height() + 0.01);
        if (boundsOutsideCanvas) {
            throw new AssertionError("Mermaid node bounds are outside the SVG canvas: " + result.nodeBounds());
        }
        if (!result.svg().contains("data-kortty-background")) {
            throw new AssertionError("Rendered SVG has no explicit background");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        int sampleX = image != null ? Math.min(4, image.getWidth() - 1) : 0;
        int sampleY = image != null ? Math.min(4, image.getHeight() - 1) : 0;
        int backgroundSample = image != null ? image.getRGB(sampleX, sampleY) : 0;
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0
            || !isCloseOpaqueColor(backgroundSample, 0xF8FAFC, 4)) {
            throw new AssertionError("Mermaid PNG dimensions/background are invalid: 0x"
                + Integer.toHexString(backgroundSample));
        }

        MermaidRenderService.RenderResult dark = MermaidRenderService.render(
            MermaidRenderService.RenderRequest.generatedFlow(
                source, MermaidRenderService.Theme.DARK, "#1E1E1E", false))
            .get(45, TimeUnit.SECONDS);
        if (!dark.success() || !dark.svg().contains("fill=\"#1E1E1E\"")) {
            throw new AssertionError("Dark Mermaid background was not applied consistently");
        }
    }

    private static boolean isCloseOpaqueColor(int argb, int expectedRgb, int tolerance) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        return alpha >= 250
            && Math.abs(red - ((expectedRgb >>> 16) & 0xFF)) <= tolerance
            && Math.abs(green - ((expectedRgb >>> 8) & 0xFF)) <= tolerance
            && Math.abs(blue - (expectedRgb & 0xFF)) <= tolerance;
    }

    private static void verifyDiagramReportExports() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mermaid-export-smoke-");
        try {
            String source = """
                flowchart TD
                  start_1(["Start"])
                  work_1["Analyze code"]
                  stop_1(["Stop"])
                  start_1 --> work_1
                  work_1 --> stop_1
                  class start_1,stop_1 setup
                  class work_1 work
                """;
            // The export renders LIGHT on white at 2x from the stored source, whatever the view shows.
            MermaidRenderService.RenderResult reference = MermaidRenderService.render(
                    MermaidRenderService.RenderRequest.generatedFlow(
                        source, MermaidRenderService.Theme.LIGHT, "#FFFFFF", true))
                .get(45, TimeUnit.SECONDS);
            if (!reference.success()) {
                throw new AssertionError("Reference diagram did not render: " + reference.message());
            }
            SnippetAiResponseSupport.ScriptAnalysis analysis =
                new SnippetAiResponseSupport.ScriptAnalysis("Analyzes code.", List.of(), List.of());
            SnippetAnalysisRecord record = SnippetAnalysisRecord.fromAnalysis("smoke", "smoke", analysis,
                    SnippetAnalysisRecord.Source.of("echo smoke\n", "bash", "en", "en", "smoke.sh"),
                    SnippetAnalysisRecord.Provenance.EMPTY, SnippetAnalysisRecord.Purpose.ANALYSIS, null,
                    System.currentTimeMillis())
                .withDiagram(new SnippetAnalysisRecord.AnalysisDiagram("logical-structure", source, List.of(), "",
                    false, "", "", System.currentTimeMillis()));
            SnippetAnalysisReport report = SnippetAnalysisReports.preApply(record,
                new SnippetAnalysisReports.ReportContext("smoke.sh", "bash", "echo smoke\n"));
            SnippetAnalysisExportService exporter = new SnippetAnalysisExportService();
            SnippetAnalysisExportService.ExportOptions options = SnippetAnalysisExportService.ExportOptions.defaults();

            Path html = directory.resolve("analysis.html");
            SnippetAnalysisExportService.ExportResult htmlResult =
                exporter.export(html, SnippetAnalysisExportService.Format.HTML, report, options);
            if (htmlResult.diagram().status() != SnippetAnalysisExportService.DiagramOutcome.Status.RENDERED
                || !Files.readString(html).contains("data:image/png;base64,")) {
                throw new AssertionError("HTML analysis export did not embed the Mermaid PNG: " + htmlResult.diagram());
            }

            Path markdown = directory.resolve("analysis.md");
            exporter.export(markdown, SnippetAnalysisExportService.Format.MARKDOWN, report, options);
            Path markdownPng = directory.resolve("analysis.diagram.png");
            byte[] markdownBytes = Files.readAllBytes(markdownPng);
            String markdownText = Files.readString(markdown);
            if (!markdownText.contains("analysis.diagram.png") || !markdownText.contains("```mermaid\n")
                || markdownBytes.length < 8
                || !new String(markdownBytes, 1, 3, StandardCharsets.ISO_8859_1).equals("PNG")) {
                throw new AssertionError("Markdown analysis export did not write its Mermaid fence and PNG");
            }
            BufferedImage exported = ImageIO.read(new ByteArrayInputStream(markdownBytes));
            long expectedWidth = 2L * Math.round(reference.width());
            if (Math.abs(exported.getWidth() - expectedWidth) > 2) {
                throw new AssertionError("Report diagram is not rendered at 2x: " + exported.getWidth()
                    + " px for " + reference.width() + " units");
            }
            if (!isCloseOpaqueColor(exported.getRGB(1, 1), 0xFFFFFF, 6)) {
                throw new AssertionError(String.format(
                    "Report diagram background is not white (LIGHT theme): #%06X", exported.getRGB(1, 1) & 0xFFFFFF));
            }

            Path pdf = directory.resolve("analysis.pdf");
            exporter.export(pdf, SnippetAnalysisExportService.Format.PDF, report, options);
            try (org.apache.pdfbox.pdmodel.PDDocument document = Loader.loadPDF(pdf.toFile())) {
                if (document.getNumberOfPages() < 2) {
                    throw new AssertionError("PDF analysis export did not add a Mermaid diagram page");
                }
                boolean image = false;
                for (org.apache.pdfbox.pdmodel.PDPage page : document.getPages()) {
                    for (org.apache.pdfbox.cos.COSName name : page.getResources().getXObjectNames()) {
                        image |= page.getResources().isImageXObject(name);
                    }
                }
                if (!image) {
                    throw new AssertionError("PDF analysis export has no diagram image");
                }
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    private static void verifySyntaxError() throws Exception {
        MermaidRenderService.SyntaxCheckResult result = MermaidRenderService.checkSyntax("flowchart TD\nA-->")
            .get(45, TimeUnit.SECONDS);
        if (result.valid()) {
            throw new AssertionError("Invalid Mermaid unexpectedly passed syntax validation");
        }
    }

    private static void verifySupportedDiagramFamilies() throws Exception {
        String[] sources = {
            "sequenceDiagram\nAlice->>Bob: Hello",
            "classDiagram\nclass Session\nSession : +String host",
            "stateDiagram-v2\n[*] --> Ready\nReady --> [*]",
            "erDiagram\nUSER ||--o{ SESSION : opens",
            "mindmap\n  root((korTTY))\n    Terminal\n    Snippets",
            "architecture-beta\n  group api(cloud)[API]\n  service db(database)[Database] in api"
        };
        for (String source : sources) {
            MermaidRenderService.RenderResult result = MermaidRenderService.render(
                MermaidRenderService.RenderRequest.chat(source, MermaidRenderService.Theme.DARK))
                .get(45, TimeUnit.SECONDS);
            if (!result.success() || !result.svg().contains("<svg")) {
                throw new AssertionError("Supported Mermaid diagram did not render: " + result.message());
            }
            verifyRootSvgIsFreelyScalable(source, result.svg());
        }
    }

    /**
     * Mermaid caps the root SVG with an inline {@code max-width} for every family whose
     * {@code useMaxWidth} option is on. That cap outranks the viewer's own width, which used to
     * pin every non-flowchart diagram to its natural size while the zoom label happily climbed.
     * The host script strips those declarations, so the sizes are the viewer's alone.
     */
    private static void verifyRootSvgIsFreelyScalable(String source, String svg) {
        int rootEnd = svg.indexOf('>');
        String rootTag = rootEnd > 0 ? svg.substring(0, rootEnd) : svg;
        if (rootTag.toLowerCase(java.util.Locale.ROOT).contains("max-width")) {
            throw new AssertionError("Rendered SVG keeps a max-width cap and cannot be resized: "
                + source.split("\n")[0] + " -> " + rootTag);
        }
    }

    /** The typed generated snippet path must render every family the AI can now produce. */
    /**
     * The archived answers of small local models korTTY used to reject for the local fallback
     * (see {@code SnippetDiagramArchivedAnswersTest}): after the local repair they are accepted,
     * and the bundled Mermaid parses and renders what korTTY would show — with the gate that asks
     * the real parser switched on, exactly as the editor runs it.
     */
    private static void verifyArchivedModelAnswersRender() throws Exception {
        String[] answers = {
            "nemotron-fan-out-and-loops.json",
            "nemotron-chain-past-stop.json",
            "nemotron-redeclared-decisions.json",
            "gpt-oss-unclosed-multiline-label.json",
        };
        for (String resource : answers) {
            String answer = SnippetDiagramArchivedAnswersTest.read(resource);
            int[] requests = {0};
            AiService service = new AiService() {
                @Override
                public AiExecutionResult execute(AiRequest request) {
                    requests[0]++;
                    return new AiExecutionResult(answer, null, null);
                }

                @Override
                public boolean testConnection() {
                    return true;
                }
            };
            SnippetAiResponseSupport.MermaidDiagram diagram = SnippetAiWorkflowSupport.generateSnippetMermaid(
                service, null, de.kortty.model.SnippetDiagramType.LOGICAL_STRUCTURE, "echo line\n".repeat(130),
                "bash", null, "de", "", SnippetAiWorkflowSupport.bundledMermaidSyntaxGate());
            if (!diagram.isUsable() || requests[0] != 1) {
                throw new IllegalStateException("Archived answer " + resource + " was not accepted at once: "
                    + diagram.rejectionReason() + " (requests: " + requests[0] + ")");
            }
            MermaidRenderService.SyntaxCheckResult syntax = MermaidRenderService.checkSyntax(diagram.mermaid())
                .get(45, TimeUnit.SECONDS);
            if (!syntax.available() || !syntax.valid()) {
                throw new IllegalStateException("Mermaid rejected the repaired " + resource + ": " + syntax.message());
            }
            for (MermaidRenderService.Theme theme : MermaidRenderService.Theme.values()) {
                MermaidRenderService.RenderResult result = MermaidRenderService.render(
                    MermaidRenderService.RenderRequest.generated(
                        diagram.mermaid(), de.kortty.model.SnippetDiagramType.LOGICAL_STRUCTURE, theme,
                        theme == MermaidRenderService.Theme.DARK ? "#1E1E1E" : "#FFFFFF", false))
                    .get(45, TimeUnit.SECONDS);
                if (!result.success() || !result.svg().contains("<svg")) {
                    throw new IllegalStateException("Repaired " + resource + " did not render: " + result.message());
                }
                long drawnNodes = result.nodeBounds().size();
                long expected = SnippetDiagramSupport.flowchartStatistics(diagram.mermaid()).nonterminalNodes() + 2;
                if (drawnNodes < expected) {
                    throw new IllegalStateException("Repaired " + resource + " rendered " + drawnNodes
                        + " node bounds, expected " + expected);
                }
            }
            System.out.println("Archived answer " + resource + " renders: "
                + SnippetDiagramSupport.flowchartStatistics(diagram.mermaid()));
        }
    }

    private static void verifyGeneratedDiagramFamilies() throws Exception {
        record TypedSource(de.kortty.model.SnippetDiagramType type, String source) {
        }
        TypedSource[] sources = {
            new TypedSource(de.kortty.model.SnippetDiagramType.SEQUENCE, """
                sequenceDiagram
                participant script as Script
                participant server as Server
                script ->> server: Upload archive
                server -->> script: Confirmation
                """),
            new TypedSource(de.kortty.model.SnippetDiagramType.STATE, """
                stateDiagram-v2
                [*] --> idle
                idle --> running : started
                running --> [*]
                """),
            new TypedSource(de.kortty.model.SnippetDiagramType.CLASS, """
                classDiagram
                class Session {
                +String host
                +connect() int
                }
                Session <|-- SshSession
                """),
            new TypedSource(de.kortty.model.SnippetDiagramType.ER, """
                erDiagram
                USER ||--o{ SESSION : opens
                USER {
                int id PK
                varchar(80) name
                }
                """),
        };
        for (TypedSource typedSource : sources) {
            MermaidRenderService.RenderResult result = MermaidRenderService.render(
                MermaidRenderService.RenderRequest.generated(
                    typedSource.source(), typedSource.type(),
                    MermaidRenderService.Theme.DARK, "#1E1E1E", false))
                .get(45, TimeUnit.SECONDS);
            if (!result.success() || !result.svg().contains("<svg")) {
                throw new AssertionError("Generated " + typedSource.type()
                    + " diagram did not render: " + result.message());
            }
            verifyRootSvgIsFreelyScalable(typedSource.source(), result.svg());
        }
    }

    private static void verifyRendererRecoversAfterFailure() throws Exception {
        MermaidRenderService.RenderResult invalid = MermaidRenderService.render(
            MermaidRenderService.RenderRequest.chat("flowchart TD\nA-->", MermaidRenderService.Theme.LIGHT))
            .get(45, TimeUnit.SECONDS);
        if (invalid.success()) {
            throw new AssertionError("Invalid Mermaid unexpectedly rendered");
        }
        MermaidRenderService.RenderResult recovered = MermaidRenderService.render(
            MermaidRenderService.RenderRequest.chat(
                "sequenceDiagram\nAlice->>Bob: Hello", MermaidRenderService.Theme.LIGHT))
            .get(45, TimeUnit.SECONDS);
        if (!recovered.success()) {
            throw new AssertionError("Mermaid renderer did not recover after failure: " + recovered.message());
        }
    }

    private static void verifyCancellationRestartsEngine() throws Exception {
        long dispatchedBefore = MermaidRenderService.testHangDispatchCount();
        System.setProperty("kortty.internal.test.mermaidHangRequest", "true");
        CompletableFuture<MermaidRenderService.RenderResult> cancelled = MermaidRenderService.render(
            MermaidRenderService.RenderRequest.chat("flowchart TD\nA-->B", MermaidRenderService.Theme.LIGHT));
        System.clearProperty("kortty.internal.test.mermaidHangRequest");
        awaitHungDispatch(dispatchedBefore);
        if (!cancelled.cancel(true)) {
            throw new AssertionError("Mermaid request could not be cancelled");
        }
        verifySimpleRenderAfterReset("cancellation");
    }

    private static void verifyTimeoutRestartsEngine() throws Exception {
        long dispatchedBefore = MermaidRenderService.testHangDispatchCount();
        System.setProperty("kortty.internal.test.mermaidTimeoutMillis", "5000");
        System.setProperty("kortty.internal.test.mermaidHangRequest", "true");
        CompletableFuture<MermaidRenderService.RenderResult> timedOut = MermaidRenderService.render(
            MermaidRenderService.RenderRequest.chat("flowchart TD\nA-->B", MermaidRenderService.Theme.LIGHT));
        System.clearProperty("kortty.internal.test.mermaidTimeoutMillis");
        System.clearProperty("kortty.internal.test.mermaidHangRequest");
        awaitHungDispatch(dispatchedBefore);
        MermaidRenderService.RenderResult result = timedOut.get(8, TimeUnit.SECONDS);
        if (result.success() || !result.message().toLowerCase().contains("timed out")) {
            throw new AssertionError("Mermaid request did not time out through the test hook: " + result);
        }
        verifySimpleRenderAfterReset("timeout");
    }

    private static void verifySimpleRenderAfterReset(String reason) throws Exception {
        MermaidRenderService.RenderResult recovered = MermaidRenderService.render(
            MermaidRenderService.RenderRequest.chat(
                "flowchart TD\nrestart[\"Restarted\"]", MermaidRenderService.Theme.LIGHT))
            .get(45, TimeUnit.SECONDS);
        if (!recovered.success()) {
            throw new AssertionError("Mermaid renderer did not recover after " + reason + ": " + recovered.message());
        }
    }

    private static void verifyDisposeCompletesRequests() throws Exception {
        long dispatchedBefore = MermaidRenderService.testHangDispatchCount();
        System.setProperty("kortty.internal.test.mermaidHangRequest", "true");
        CompletableFuture<MermaidRenderService.RenderResult> active = MermaidRenderService.render(
            MermaidRenderService.RenderRequest.chat("flowchart TD\nA-->B", MermaidRenderService.Theme.LIGHT));
        CompletableFuture<MermaidRenderService.RenderResult> queued = MermaidRenderService.render(
            MermaidRenderService.RenderRequest.chat("flowchart TD\nC-->D", MermaidRenderService.Theme.LIGHT));
        System.clearProperty("kortty.internal.test.mermaidHangRequest");
        awaitHungDispatch(dispatchedBefore);
        MermaidRenderService.dispose();
        MermaidRenderService.RenderResult activeResult = active.get(5, TimeUnit.SECONDS);
        MermaidRenderService.RenderResult queuedResult = queued.get(5, TimeUnit.SECONDS);
        if (activeResult.success() || queuedResult.success()
            || !activeResult.message().contains("disposed") || !queuedResult.message().contains("disposed")) {
            throw new AssertionError("Disposing Mermaid did not complete active and queued requests");
        }
    }

    private static void awaitHungDispatch(long previousCount) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (MermaidRenderService.testHangDispatchCount() == previousCount
            && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        if (MermaidRenderService.testHangDispatchCount() == previousCount) {
            throw new AssertionError("Mermaid hang test request was never dispatched");
        }
    }
}
