package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.PlantUmlRenderService;
import de.kortty.core.SnippetDiagramSupport;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A self-contained, embeddable diagram viewer with the same controls as the standalone
 * {@link SnippetDiagramDialog}: fit-to-viewport auto-scaling, zoom (−/Fit/+), Save SVG / Save PNG,
 * Copy image / Copy PlantUML, a background-colour picker (persisted), an optional Regenerate button,
 * and hover code-reference tooltips that show the matching source lines. It renders a {@link DiagramSource}
 * (PlantUML text + source content + code references), supplied lazily so it can be regenerated. Used as
 * the right-hand pane of {@link SnippetCodeAnalysisDialog}; the standalone dialog is unchanged.
 */
final class SnippetDiagramView extends VBox {

    /** The lazily-supplied diagram input: the PlantUML text plus the source + label→line mappings for hotspots. */
    public record DiagramSource(String plantUml, String content,
                                List<SnippetDiagramSupport.SourceCodeReference> codeReferences) {
    }

    private static final Pattern SVG_VIEW_BOX_PATTERN =
        Pattern.compile("viewBox\\s*=\\s*\"[^\"]*?\\s+([0-9.]+)\\s+([0-9.]+)\"");
    private static final Pattern SVG_WIDTH_HEIGHT_PATTERN =
        Pattern.compile("<svg[^>]*\\swidth\\s*=\\s*\"([0-9.]+)[^\"]*\"[^>]*\\sheight\\s*=\\s*\"([0-9.]+)[^\"]*\"");
    private static final Pattern POLYGON_POINT_PATTERN =
        Pattern.compile("(-?[0-9]+(?:\\.[0-9]+)?),(-?[0-9]+(?:\\.[0-9]+)?)");
    private static final double MIN_ZOOM_FACTOR = 0.25;
    private static final double MAX_ZOOM_FACTOR = 4.0;
    private static final double MIN_ZOOM = 0.05;
    private static final double MAX_ZOOM = 12.0;
    private static final String DEFAULT_BACKGROUND = "#FFFFFF";

    private final Supplier<CompletableFuture<DiagramSource>> diagramSupplier;
    private final WebView diagramView = new WebView();
    private final ScrollPane diagramScroll = new ScrollPane(diagramView);
    private final StackPane diagramStack = new StackPane();
    private final VBox spinnerBox;
    private final Label statusLabel = new Label();
    private final Label zoomLabel = new Label("100%");
    private final ColorPicker backgroundPicker;

    private String currentPlantUml;
    private String currentContent = "";
    private List<SnippetDiagramSupport.SourceCodeReference> currentSourceReferences = List.of();
    private List<SvgHotspot> currentHotspots = List.of();
    private Path renderedSvgPath;
    private double baseWidth = 900.0;
    private double baseHeight = 600.0;
    private double zoomFactor = 1.0;
    private String backgroundColor;
    private Task<PlantUmlRenderService.RenderResult> renderTask;
    private Task<PlantUmlRenderService.RenderResult> exportTask;
    private boolean loadedOnce;

    private record SvgHotspot(String referenceId, double x, double y, double width, double height, String tooltip) {
    }

    private record SvgBounds(double x, double y, double width, double height) {
    }

    SnippetDiagramView(Supplier<CompletableFuture<DiagramSource>> diagramSupplier, boolean showRegenerate) {
        this.diagramSupplier = diagramSupplier;
        this.backgroundColor = SnippetDiagramSupport.normalizeHexColor(loadConfiguredBackground(), DEFAULT_BACKGROUND);
        this.backgroundPicker = new ColorPicker(Color.web(backgroundColor));
        setSpacing(8);

        diagramView.setContextMenuEnabled(false);
        diagramView.setMinSize(1, 1);
        diagramScroll.setPannable(true);
        diagramScroll.setFitToWidth(false);
        diagramScroll.setFitToHeight(false);
        diagramScroll.setVisible(false);
        diagramScroll.setManaged(false);
        diagramScroll.viewportBoundsProperty().addListener((obs, oldValue, newValue) -> renderDiagramToFitViewport());
        applyBackgroundStyle();

        spinnerBox = buildSpinnerBox();
        diagramStack.getChildren().addAll(diagramScroll, spinnerBox);
        VBox.setVgrow(diagramStack, Priority.ALWAYS);

        getChildren().addAll(buildToolbar(showRegenerate), diagramStack);
    }

    // ---- Public API -----------------------------------------------------------------------------

    /** (Re)loads the diagram from the supplier. Safe to call multiple times (e.g. Regenerate). */
    void reload() {
        loadedOnce = true;
        cancelRenderTask();
        renderedSvgPath = null;
        currentHotspots = List.of();
        diagramScroll.setVisible(false);
        diagramScroll.setManaged(false);
        showSpinner(I18n.get("snippets.ai.analysis.diagram.loading"));
        CompletableFuture<DiagramSource> future;
        try {
            future = diagramSupplier != null ? diagramSupplier.get() : null;
        } catch (RuntimeException e) {
            showError(String.valueOf(e.getMessage()));
            return;
        }
        if (future == null) {
            showError(I18n.get("snippets.ai.analysis.diagram.unavailable"));
            return;
        }
        future.whenComplete((source, error) -> Platform.runLater(() -> onSourceReady(source, error)));
    }

    /** Loads once (on first show); subsequent calls are no-ops until {@link #reload()}. */
    void loadIfNeeded() {
        if (!loadedOnce) {
            reload();
        }
    }

    void dispose() {
        cancelRenderTask();
        if (exportTask != null && exportTask.isRunning()) {
            exportTask.cancel(true);
        }
    }

    // ---- Render lifecycle -----------------------------------------------------------------------

    private void onSourceReady(DiagramSource source, Throwable error) {
        if (error != null) {
            showError(error.getMessage());
            return;
        }
        if (source == null || source.plantUml() == null || source.plantUml().isBlank()) {
            showError(I18n.get("snippets.ai.analysis.diagram.unavailable"));
            return;
        }
        currentPlantUml = source.plantUml();
        currentContent = source.content() != null ? source.content() : "";
        currentSourceReferences = source.codeReferences() != null ? source.codeReferences() : List.of();
        renderSvgAsync(currentPlantUml);
    }

    private void renderSvgAsync(String plantUml) {
        renderTask = new Task<>() {
            @Override
            protected PlantUmlRenderService.RenderResult call() {
                return new PlantUmlRenderService().renderSvg(plantUml);
            }
        };
        renderTask.setOnSucceeded(event -> onRendered(renderTask.getValue()));
        renderTask.setOnFailed(event -> showError(
            renderTask.getException() != null ? renderTask.getException().getMessage() : ""));
        Thread thread = new Thread(renderTask, "snippet-diagram-view-render");
        thread.setDaemon(true);
        thread.start();
    }

    private void onRendered(PlantUmlRenderService.RenderResult result) {
        if (result == null || !result.success() || result.imagePath() == null) {
            showError(result != null ? result.message() : "");
            return;
        }
        renderedSvgPath = result.imagePath();
        zoomFactor = 1.0;
        double[] size = readSvgSize(renderedSvgPath);
        baseWidth = size[0];
        baseHeight = size[1];
        currentHotspots = buildSvgHotspots(renderedSvgPath, SnippetDiagramSupport.buildExpandedCodeReferences(
            currentPlantUml, currentContent, currentSourceReferences));
        spinnerBox.setVisible(false);
        spinnerBox.setManaged(false);
        diagramScroll.setVisible(true);
        diagramScroll.setManaged(true);
        Platform.runLater(this::renderDiagramToFitViewport);
    }

    private void renderDiagramToFitViewport() {
        if (renderedSvgPath == null || !diagramScroll.isVisible()) {
            return;
        }
        Bounds viewport = diagramScroll.getViewportBounds();
        double viewportWidth = viewport.getWidth();
        double viewportHeight = viewport.getHeight();
        if (viewportWidth <= 1.0 || viewportHeight <= 1.0) {
            return;
        }
        double fitZoom = Math.min(viewportWidth / baseWidth, viewportHeight / baseHeight);
        double zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, fitZoom * zoomFactor));
        double displayWidth = Math.max(1.0, baseWidth * zoom);
        double displayHeight = Math.max(1.0, baseHeight * zoom);
        double canvasWidth = Math.max(viewportWidth, displayWidth);
        double canvasHeight = Math.max(viewportHeight, displayHeight);
        diagramView.setZoom(1.0);
        diagramView.setPrefSize(canvasWidth, canvasHeight);
        diagramView.getEngine().loadContent(
            buildDiagramHtml(renderedSvgPath.toUri().toString(), canvasWidth, canvasHeight, displayWidth, displayHeight));
        zoomLabel.setText(Math.round(zoom * 100) + "%");
    }

    private String buildDiagramHtml(String svgUri, double canvasWidth, double canvasHeight,
                                    double displayWidth, double displayHeight) {
        double imageLeft = Math.max(0.0, (canvasWidth - displayWidth) / 2.0);
        double imageTop = Math.max(0.0, (canvasHeight - displayHeight) / 2.0);
        double scaleX = displayWidth / Math.max(1.0, baseWidth);
        double scaleY = displayHeight / Math.max(1.0, baseHeight);
        String hotspots = buildHotspotHtml(currentHotspots, imageLeft, imageTop, scaleX, scaleY);
        return "<!doctype html><html><head><meta charset=\"UTF-8\"><style>"
            + "html,body{margin:0;width:" + fmt(canvasWidth) + "px;height:" + fmt(canvasHeight)
            + "px;overflow:hidden;background:" + backgroundColor + ";}"
            + "body{position:relative;}"
            + "img{display:block;position:absolute;left:" + fmt(imageLeft) + "px;top:" + fmt(imageTop)
            + "px;width:" + fmt(displayWidth) + "px;height:" + fmt(displayHeight) + "px;}"
            + ".diagram-hotspot{position:absolute;border-radius:8px;cursor:default;}"
            + ".diagram-hotspot:hover{background:rgba(37,99,235,0.08);outline:2px solid rgba(37,99,235,0.5);}"
            + "#code-tooltip{display:none;position:absolute;z-index:20;max-width:560px;max-height:240px;overflow:auto;"
            + "padding:8px 10px;border-radius:6px;background:rgba(24,24,27,0.94);color:#f8fafc;"
            + "font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;font-size:12px;line-height:1.35;"
            + "white-space:pre;pointer-events:none;box-shadow:0 8px 24px rgba(15,23,42,0.25);}"
            + "</style></head><body><img src=\"" + escapeHtml(svgUri) + "\" alt=\"\">" + hotspots
            + "<div id=\"code-tooltip\"></div><script>"
            + "(function(){var t=document.getElementById('code-tooltip');"
            + "function show(e,h){t.textContent=h.getAttribute('data-tooltip')||'';t.style.display='block';move(e);}"
            + "function move(e){var m=14,b=t.getBoundingClientRect(),l=e.clientX+m,tp=e.clientY+m;"
            + "if(l+b.width>window.innerWidth){l=Math.max(0,e.clientX-b.width-m);}"
            + "if(tp+b.height>window.innerHeight){tp=Math.max(0,e.clientY-b.height-m);}"
            + "t.style.left=l+'px';t.style.top=tp+'px';}"
            + "function hide(){t.style.display='none';}"
            + "document.querySelectorAll('.diagram-hotspot').forEach(function(h){"
            + "h.addEventListener('mouseenter',function(e){show(e,h);});"
            + "h.addEventListener('mousemove',move);h.addEventListener('mouseleave',hide);});"
            + "}());</script></body></html>";
    }

    private String buildHotspotHtml(List<SvgHotspot> hotspots, double imageLeft, double imageTop,
                                    double scaleX, double scaleY) {
        if (hotspots == null || hotspots.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (SvgHotspot hotspot : hotspots) {
            double left = imageLeft + (hotspot.x() * scaleX);
            double top = imageTop + (hotspot.y() * scaleY);
            double width = hotspot.width() * scaleX;
            double height = hotspot.height() * scaleY;
            if (width <= 0.0 || height <= 0.0) {
                continue;
            }
            builder.append("<div class=\"diagram-hotspot\" data-tooltip=\"").append(escapeHtml(hotspot.tooltip()))
                .append("\" style=\"left:").append(fmt(left)).append("px;top:").append(fmt(top))
                .append("px;width:").append(fmt(width)).append("px;height:").append(fmt(height)).append("px;\"></div>");
        }
        return builder.toString();
    }

    // ---- Hotspot building (matches PlantUML activity labels to SVG shapes) -----------------------

    private List<SvgHotspot> buildSvgHotspots(Path svgPath, List<SnippetDiagramSupport.CodeReference> codeReferences) {
        if (svgPath == null || codeReferences == null || codeReferences.isEmpty()) {
            return List.of();
        }
        Map<String, Deque<SnippetDiagramSupport.CodeReference>> byLabel = new LinkedHashMap<>();
        for (SnippetDiagramSupport.CodeReference reference : codeReferences) {
            byLabel.computeIfAbsent(normalizeSvgText(reference.label()), ignored -> new ArrayDeque<>()).add(reference);
        }
        try {
            Document document = readSvgDocument(svgPath);
            NodeList textNodes = document.getElementsByTagName("text");
            List<SvgHotspot> hotspots = new ArrayList<>();
            for (int i = 0; i < textNodes.getLength(); i++) {
                if (!(textNodes.item(i) instanceof Element textElement)) {
                    continue;
                }
                Deque<SnippetDiagramSupport.CodeReference> candidates = byLabel.get(normalizeSvgText(textElement.getTextContent()));
                if (candidates == null || candidates.isEmpty()) {
                    continue;
                }
                Element shape = precedingSvgShape(textElement);
                if (shape == null) {
                    continue;
                }
                SvgBounds bounds = svgShapeBounds(shape);
                if (bounds == null || bounds.width() <= 0.0 || bounds.height() <= 0.0) {
                    continue;
                }
                SnippetDiagramSupport.CodeReference reference = candidates.removeFirst();
                hotspots.add(new SvgHotspot(reference.id(), bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                    codeReferenceTooltip(reference)));
            }
            return List.copyOf(hotspots);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static Document readSvgDocument(Path svgPath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try (InputStream inputStream = Files.newInputStream(svgPath)) {
            return factory.newDocumentBuilder().parse(inputStream);
        }
    }

    private static Element precedingSvgShape(Element textElement) {
        org.w3c.dom.Node sibling = textElement.getPreviousSibling();
        while (sibling != null) {
            if (sibling instanceof Element element
                && ("rect".equals(element.getTagName()) || "polygon".equals(element.getTagName()))) {
                return element;
            }
            sibling = sibling.getPreviousSibling();
        }
        return null;
    }

    private static SvgBounds svgShapeBounds(Element shape) {
        return switch (shape.getTagName()) {
            case "rect" -> new SvgBounds(
                parseSvgNumber(shape.getAttribute("x"), 0.0),
                parseSvgNumber(shape.getAttribute("y"), 0.0),
                parseSvgNumber(shape.getAttribute("width"), 0.0),
                parseSvgNumber(shape.getAttribute("height"), 0.0));
            case "polygon" -> polygonBounds(shape);
            default -> null;
        };
    }

    private static SvgBounds polygonBounds(Element polygon) {
        Matcher matcher = POLYGON_POINT_PATTERN.matcher(polygon.getAttribute("points"));
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        while (matcher.find()) {
            double x = parseSvgNumber(matcher.group(1), 0.0);
            double y = parseSvgNumber(matcher.group(2), 0.0);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(maxX) || !Double.isFinite(maxY)) {
            return null;
        }
        return new SvgBounds(minX, minY, maxX - minX, maxY - minY);
    }

    private static double parseSvgNumber(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String codeReferenceTooltip(SnippetDiagramSupport.CodeReference reference) {
        String header = reference.startLine() == reference.endLine()
            ? I18n.get("snippets.ai.diagram.codeReference.line", reference.startLine())
            : I18n.get("snippets.ai.diagram.codeReference.lines", reference.startLine(), reference.endLine());
        return header + "\n" + reference.excerpt();
    }

    private static String normalizeSvgText(String value) {
        return value != null ? value.replaceAll("\\s+", " ").trim() : "";
    }

    private static double[] readSvgSize(Path svgPath) {
        try {
            String svg = Files.readString(svgPath, StandardCharsets.UTF_8);
            Matcher viewBox = SVG_VIEW_BOX_PATTERN.matcher(svg);
            if (viewBox.find()) {
                return new double[] {parseLength(viewBox.group(1), 900.0), parseLength(viewBox.group(2), 600.0)};
            }
            Matcher widthHeight = SVG_WIDTH_HEIGHT_PATTERN.matcher(svg);
            if (widthHeight.find()) {
                return new double[] {parseLength(widthHeight.group(1), 900.0), parseLength(widthHeight.group(2), 600.0)};
            }
        } catch (Exception ignored) {
            // Fall through to defaults.
        }
        return new double[] {900.0, 600.0};
    }

    private static double parseLength(String value, double fallback) {
        try {
            double parsed = Double.parseDouble(value);
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    // ---- Toolbar --------------------------------------------------------------------------------

    private FlowPane buildToolbar(boolean showRegenerate) {
        FlowPane toolbar = new FlowPane(6, 6);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        if (showRegenerate) {
            Button regenerate = new Button(SnippetAiDialogSupport.AI_ACTION_PREFIX + I18n.get("snippets.ai.diagram.regenerate"));
            regenerate.setOnAction(event -> reload());
            toolbar.getChildren().add(regenerate);
        }

        backgroundPicker.setOnAction(event -> changeBackground(toHex(backgroundPicker.getValue())));
        toolbar.getChildren().addAll(new Label(I18n.get("snippets.ai.diagram.backgroundColor")), backgroundPicker);

        Button saveSvg = new Button(I18n.get("snippets.ai.diagram.saveSvg"));
        saveSvg.setOnAction(event -> saveSvg());
        Button savePng = new Button(I18n.get("snippets.ai.diagram.savePng"));
        savePng.setOnAction(event -> savePng());
        Button copyImage = new Button(I18n.get("snippets.ai.diagram.copyImage"));
        copyImage.setOnAction(event -> copyImage());
        Button copyPlantUml = new Button(I18n.get("snippets.ai.diagram.copyPlantUml"));
        copyPlantUml.setOnAction(event -> copyPlantUml());

        Button zoomOut = new Button("−");
        zoomOut.setTooltip(new Tooltip(I18n.get("menu.view.zoomOut")));
        zoomOut.setOnAction(event -> setZoomFactor(zoomFactor - 0.15));
        Button zoomFit = new Button(I18n.get("snippets.ai.diagram.zoom.fit"));
        zoomFit.setOnAction(event -> setZoomFactor(1.0));
        Button zoomIn = new Button("+");
        zoomIn.setTooltip(new Tooltip(I18n.get("menu.view.zoomIn")));
        zoomIn.setOnAction(event -> setZoomFactor(zoomFactor + 0.15));
        zoomLabel.setMinWidth(Region.USE_PREF_SIZE);

        toolbar.getChildren().addAll(saveSvg, savePng, copyImage, copyPlantUml, zoomOut, zoomLabel, zoomIn, zoomFit);
        return toolbar;
    }

    private VBox buildSpinnerBox() {
        ProgressIndicator indicator = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
        indicator.setPrefSize(38, 38);
        indicator.setMaxSize(38, 38);
        statusLabel.setStyle("-fx-text-fill: gray;");
        statusLabel.setWrapText(true);
        VBox box = new VBox(10, indicator, statusLabel);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(20));
        return box;
    }

    private void showSpinner(String message) {
        for (javafx.scene.Node node : spinnerBox.getChildren()) {
            if (node instanceof ProgressIndicator indicator) {
                indicator.setVisible(true);
                indicator.setManaged(true);
            }
        }
        statusLabel.setText(message);
        spinnerBox.setVisible(true);
        spinnerBox.setManaged(true);
    }

    private void showError(String message) {
        diagramScroll.setVisible(false);
        diagramScroll.setManaged(false);
        for (javafx.scene.Node node : spinnerBox.getChildren()) {
            if (node instanceof ProgressIndicator indicator) {
                indicator.setVisible(false);
                indicator.setManaged(false);
            }
        }
        statusLabel.setText(I18n.get("snippets.ai.diagram.renderFailed", message != null && !message.isBlank() ? message : "?"));
        spinnerBox.setVisible(true);
        spinnerBox.setManaged(true);
    }

    private void setZoomFactor(double factor) {
        zoomFactor = Math.max(MIN_ZOOM_FACTOR, Math.min(MAX_ZOOM_FACTOR, factor));
        renderDiagramToFitViewport();
    }

    // ---- Save / copy ----------------------------------------------------------------------------

    private void saveSvg() {
        if (renderedSvgPath == null) {
            return;
        }
        File target = chooseSaveFile("svg");
        if (target == null) {
            return;
        }
        try {
            Files.copy(renderedSvgPath, ensureExtension(target.toPath(), ".svg"), StandardCopyOption.REPLACE_EXISTING);
            statusLabel.setText(I18n.get("snippets.ai.diagram.export.saved", target.toString()));
        } catch (Exception e) {
            statusLabel.setText(I18n.get("snippets.ai.diagram.export.failed", String.valueOf(e.getMessage())));
        }
    }

    private void savePng() {
        if (currentPlantUml == null) {
            return;
        }
        File target = chooseSaveFile("png");
        if (target == null) {
            return;
        }
        Path targetPath = ensureExtension(target.toPath(), ".png");
        renderPngAsync(I18n.get("snippets.ai.diagram.export.rendering"), result -> {
            if (result != null && result.success() && result.imagePath() != null) {
                try {
                    Files.copy(result.imagePath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                    statusLabel.setText(I18n.get("snippets.ai.diagram.export.saved", targetPath.toString()));
                } catch (Exception e) {
                    statusLabel.setText(I18n.get("snippets.ai.diagram.export.failed", String.valueOf(e.getMessage())));
                }
            } else {
                statusLabel.setText(I18n.get("snippets.ai.diagram.export.failed", result != null ? result.message() : "?"));
            }
        });
    }

    private void copyImage() {
        if (currentPlantUml == null) {
            return;
        }
        renderPngAsync(I18n.get("snippets.ai.diagram.copy.rendering"), result -> {
            if (result != null && result.success() && result.imagePath() != null) {
                Image image = new Image(result.imagePath().toUri().toString());
                ClipboardContent content = new ClipboardContent();
                content.putImage(image);
                Clipboard.getSystemClipboard().setContent(content);
                statusLabel.setText(I18n.get("snippets.ai.diagram.copy.ready"));
            } else {
                statusLabel.setText(I18n.get("snippets.ai.diagram.export.failed", result != null ? result.message() : "?"));
            }
        });
    }

    private void copyPlantUml() {
        if (currentPlantUml == null || currentPlantUml.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(currentPlantUml);
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText(I18n.get("snippets.ai.diagram.copy.ready"));
    }

    private void renderPngAsync(String runningStatus, Consumer<PlantUmlRenderService.RenderResult> handler) {
        String plantUml = currentPlantUml;
        if (plantUml == null) {
            return;
        }
        statusLabel.setText(runningStatus);
        exportTask = new Task<>() {
            @Override
            protected PlantUmlRenderService.RenderResult call() {
                return new PlantUmlRenderService().renderPng(plantUml);
            }
        };
        exportTask.setOnSucceeded(event -> handler.accept(exportTask.getValue()));
        exportTask.setOnFailed(event -> statusLabel.setText(I18n.get("snippets.ai.diagram.export.failed",
            exportTask.getException() != null ? exportTask.getException().getMessage() : "?")));
        Thread thread = new Thread(exportTask, "snippet-diagram-view-export");
        thread.setDaemon(true);
        thread.start();
    }

    private File chooseSaveFile(String extension) {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName("diagram." + extension);
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(extension.toUpperCase() + " (*." + extension + ")", "*." + extension));
        Window window = getScene() != null ? getScene().getWindow() : null;
        return chooser.showSaveDialog(window);
    }

    private static Path ensureExtension(Path path, String dottedExtension) {
        String name = path.getFileName().toString();
        if (name.toLowerCase().endsWith(dottedExtension)) {
            return path;
        }
        return path.resolveSibling(name + dottedExtension);
    }

    // ---- Background -----------------------------------------------------------------------------

    private void changeBackground(String color) {
        backgroundColor = SnippetDiagramSupport.normalizeHexColor(color, DEFAULT_BACKGROUND);
        applyBackgroundStyle();
        saveConfiguredBackground(backgroundColor);
        if (renderedSvgPath != null) {
            renderDiagramToFitViewport();
        }
    }

    private void applyBackgroundStyle() {
        diagramScroll.setStyle("-fx-background: " + backgroundColor + "; -fx-background-color: " + backgroundColor + ";");
    }

    private String loadConfiguredBackground() {
        GlobalSettings settings = currentSettings();
        return settings != null ? settings.getSnippetDiagramBackgroundColor() : DEFAULT_BACKGROUND;
    }

    private void saveConfiguredBackground(String color) {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            if (manager != null && manager.getSettings() != null) {
                manager.getSettings().setSnippetDiagramBackgroundColor(color);
                manager.save();
            }
        } catch (Exception ignored) {
        }
    }

    private static GlobalSettings currentSettings() {
        try {
            return KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void cancelRenderTask() {
        if (renderTask != null && renderTask.isRunning()) {
            renderTask.cancel(true);
        }
    }

    private static String toHex(Color color) {
        if (color == null) {
            return DEFAULT_BACKGROUND;
        }
        return String.format("#%02X%02X%02X",
            (int) Math.round(color.getRed() * 255),
            (int) Math.round(color.getGreen() * 255),
            (int) Math.round(color.getBlue() * 255));
    }

    private static String escapeHtml(String value) {
        return SnippetAiDialogSupport.escapeHtml(value);
    }
}
