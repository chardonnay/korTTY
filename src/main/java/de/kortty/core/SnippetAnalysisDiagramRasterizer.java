package de.kortty.core;

import de.kortty.core.SnippetAnalysisExportService.DiagramOutcome;
import javafx.application.Platform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Turns the report's stored Mermaid source into the raster image the exports embed. The export
 * service rasterizes once per export and hands the result to every writer; tests substitute a
 * fake that returns a fixed image or outcome.
 */
@FunctionalInterface
interface SnippetAnalysisDiagramRasterizer {

    /** Canvas pixels per diagram unit: a report prints the diagram at about 144 dpi. */
    double DEFAULT_SCALE = 2.0;
    Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /** Never throws for a diagram that cannot be drawn: that is a {@code FAILED} or {@code TIMED_OUT} outcome. */
    RasterizedDiagram rasterize(SnippetAnalysisReport.Diagram diagram);

    /**
     * @param image      the raster ({@code null} unless {@code RENDERED})
     * @param unitWidth  the diagram's size in SVG units — the image is {@code scale} times larger —
     *                   so the writers can print it at its natural size
     */
    record RasterizedDiagram(DiagramOutcome outcome, BufferedImage image, double unitWidth, double unitHeight) {

        static RasterizedDiagram none() {
            return new RasterizedDiagram(DiagramOutcome.none(), null, 0, 0);
        }

        static RasterizedDiagram failed(DiagramOutcome.Status status, String message) {
            return new RasterizedDiagram(new DiagramOutcome(status, message), null, 0, 0);
        }

        boolean rendered() {
            return outcome.status() == DiagramOutcome.Status.RENDERED && image != null;
        }
    }

    /**
     * The bundled Mermaid renderer: always LIGHT on {@code #FFFFFF} (a report is printed on white,
     * whatever theme the panel uses), {@code scale} canvas pixels per unit, and a hard
     * {@code timeout} that counts from enqueueing — the renderer is a FIFO shared with the views —
     * after which the request is cancelled.
     *
     * <p>Must not be called on the JavaFX thread: the render itself runs there, so waiting on it
     * would block the UI for the whole timeout and then export without the diagram.</p>
     */
    static SnippetAnalysisDiagramRasterizer mermaid(double scale, Duration timeout) {
        Duration budget = timeout != null && !timeout.isNegative() && !timeout.isZero() ? timeout : DEFAULT_TIMEOUT;
        return diagram -> {
            if (diagram == null || diagram.mermaidSource().isBlank()) {
                return RasterizedDiagram.none();
            }
            if (isFxThread()) {
                throw new IllegalStateException("Report diagrams must not be rasterized on the JavaFX thread");
            }
            MermaidRenderService.RenderRequest request = MermaidRenderService.RenderRequest.generated(
                    diagram.mermaidSource(), diagram.type(), MermaidRenderService.Theme.LIGHT, "#FFFFFF", true)
                .withRasterScale(scale);
            CompletableFuture<MermaidRenderService.RenderResult> future;
            try {
                future = MermaidRenderService.render(request);
            } catch (RuntimeException | Error e) {
                return RasterizedDiagram.failed(DiagramOutcome.Status.FAILED, messageOf(e));
            }
            MermaidRenderService.RenderResult result;
            try {
                result = future.get(budget.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return RasterizedDiagram.failed(DiagramOutcome.Status.TIMED_OUT,
                    "no result within " + budget.toSeconds() + " s");
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                return RasterizedDiagram.failed(DiagramOutcome.Status.FAILED, "interrupted");
            } catch (ExecutionException e) {
                return RasterizedDiagram.failed(DiagramOutcome.Status.FAILED, messageOf(e.getCause()));
            }
            if (result == null || !result.success()) {
                return RasterizedDiagram.failed(DiagramOutcome.Status.FAILED,
                    result != null && !result.message().isBlank() ? result.message() : "no image");
            }
            byte[] png = result.png();
            if (png == null) {
                return RasterizedDiagram.failed(DiagramOutcome.Status.FAILED, "no image");
            }
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
                if (image == null) {
                    return RasterizedDiagram.failed(DiagramOutcome.Status.FAILED, "unreadable image");
                }
                double unitWidth = result.width() > 0 ? result.width() : image.getWidth() / scale;
                double unitHeight = result.height() > 0 ? result.height() : image.getHeight() / scale;
                return new RasterizedDiagram(DiagramOutcome.rendered(), image, unitWidth, unitHeight);
            } catch (IOException e) {
                return RasterizedDiagram.failed(DiagramOutcome.Status.FAILED, messageOf(e));
            }
        };
    }

    private static boolean isFxThread() {
        try {
            return Platform.isFxApplicationThread();
        } catch (RuntimeException | Error e) {
            return false;
        }
    }

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return message != null && !message.isBlank() ? message : error.getClass().getSimpleName();
    }
}
