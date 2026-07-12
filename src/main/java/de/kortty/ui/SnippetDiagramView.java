package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.MermaidRenderService;
import de.kortty.core.SnippetDiagramSupport;
import de.kortty.core.SystemThemeDetector;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleGroup;
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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Shared Mermaid viewer used by both the standalone snippet-diagram dialog and the code-analysis dialog.
 * Mermaid executes only inside {@link MermaidRenderService}; this view displays the returned sanitized SVG
 * in a separate WebView with JavaScript disabled and keeps SVG/PNG bytes cached for exports.
 */
final class SnippetDiagramView extends VBox {

    public record DiagramSource(
        String mermaid,
        String content,
        List<SnippetDiagramSupport.SourceCodeReference> codeReferences) {
    }

    record CodeNavigationTarget(int startLine, int endLine) {
    }

    private record SvgHotspot(double x, double y, double width, double height,
                              int startLine, int endLine, String tooltip) {
    }

    private static final Pattern SVG_VIEW_BOX_PATTERN =
        Pattern.compile("viewBox\\s*=\\s*\"[^\"]*?\\s+([0-9.]+)\\s+([0-9.]+)\"");
    private static final Pattern SVG_WIDTH_HEIGHT_PATTERN =
        Pattern.compile("<svg[^>]*\\swidth\\s*=\\s*\"([0-9.]+)[^\"]*\"[^>]*\\sheight\\s*=\\s*\"([0-9.]+)[^\"]*\"");
    private static final Pattern NAVIGATION_PATTERN = Pattern.compile("#kortty-code-reference-(\\d+)-(\\d+)$");
    private static final double MIN_ZOOM_FACTOR = 0.25;
    private static final double MAX_ZOOM_FACTOR = 4.0;
    private static final double MIN_ZOOM = 0.05;
    private static final double MAX_ZOOM = 12.0;
    private static final String DEFAULT_BACKGROUND = "#FFFFFF";

    private final Supplier<CompletableFuture<DiagramSource>> diagramSupplier;
    private final Consumer<CodeNavigationTarget> codeNavigationHandler;
    private final WebView diagramView = new WebView();
    private final ScrollPane diagramScroll = new ScrollPane(diagramView);
    private final StackPane diagramStack = new StackPane();
    private final VBox spinnerBox;
    private final Label statusLabel = new Label();
    private final Label zoomLabel = new Label("100%");
    private final ColorPicker backgroundPicker;
    private final MenuButton darkModeButton = new MenuButton();

    private SnippetDiagramSupport.DiagramColorMode colorMode;
    private long sourceGeneration;
    private long renderGeneration;
    private boolean disposed;
    private boolean lastResolvedDark;
    private String currentMermaid;
    private String currentContent = "";
    private List<SnippetDiagramSupport.SourceCodeReference> currentSourceReferences = List.of();
    private List<SvgHotspot> currentHotspots = List.of();
    private String renderedSvg;
    private byte[] renderedPng;
    private double baseWidth = 900.0;
    private double baseHeight = 600.0;
    private double zoomFactor = 1.0;
    private String backgroundColor;
    private CompletableFuture<MermaidRenderService.RenderResult> renderFuture;
    private boolean loadedOnce;

    SnippetDiagramView(Supplier<CompletableFuture<DiagramSource>> diagramSupplier, boolean showRegenerate) {
        this(diagramSupplier, showRegenerate, null);
    }

    SnippetDiagramView(
        Supplier<CompletableFuture<DiagramSource>> diagramSupplier,
        boolean showRegenerate,
        Consumer<CodeNavigationTarget> codeNavigationHandler) {

        this.diagramSupplier = diagramSupplier;
        this.codeNavigationHandler = codeNavigationHandler;
        this.backgroundColor = SnippetDiagramSupport.normalizeHexColor(loadConfiguredBackground(), DEFAULT_BACKGROUND);
        this.colorMode = SnippetDiagramSupport.DiagramColorMode.fromKey(loadConfiguredColorMode());
        this.lastResolvedDark = colorMode.isDarkActive();
        this.backgroundPicker = new ColorPicker(Color.web(backgroundColor));
        setSpacing(8);
        installSystemThemeFocusWatcher();

        diagramView.setContextMenuEnabled(false);
        diagramView.getEngine().setJavaScriptEnabled(false);
        diagramView.setMinSize(1, 1);
        diagramView.getEngine().locationProperty().addListener((observable, oldLocation, newLocation) ->
            handleNavigation(newLocation));
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

    void reload() {
        if (disposed) {
            return;
        }
        long generation = ++sourceGeneration;
        loadedOnce = true;
        cancelRender();
        renderedSvg = null;
        renderedPng = null;
        currentHotspots = List.of();
        diagramScroll.setVisible(false);
        diagramScroll.setManaged(false);
        showSpinner(I18n.get("snippets.ai.analysis.diagram.loading"));
        CompletableFuture<DiagramSource> future;
        try {
            future = diagramSupplier != null ? diagramSupplier.get() : null;
        } catch (RuntimeException e) {
            showError(e.getMessage());
            return;
        }
        if (future == null) {
            showError(I18n.get("snippets.ai.analysis.diagram.unavailable"));
            return;
        }
        future.whenComplete((source, error) -> Platform.runLater(() -> {
            if (!disposed && generation == sourceGeneration) {
                onSourceReady(source, error);
            }
        }));
    }

    void loadIfNeeded() {
        if (!loadedOnce) {
            reload();
        }
    }

    void clear() {
        sourceGeneration++;
        cancelRender();
        currentMermaid = null;
        currentContent = "";
        currentSourceReferences = List.of();
        currentHotspots = List.of();
        renderedSvg = null;
        renderedPng = null;
        diagramScroll.setVisible(false);
        diagramScroll.setManaged(false);
        spinnerBox.setVisible(false);
        spinnerBox.setManaged(false);
        diagramView.getEngine().loadContent("");
    }

    void dispose() {
        disposed = true;
        sourceGeneration++;
        clear();
    }

    MermaidRenderService.RenderRequest currentRenderRequest(boolean includePng) {
        if (currentMermaid == null || currentMermaid.isBlank()) {
            return null;
        }
        return MermaidRenderService.RenderRequest.generatedFlow(
            currentMermaid,
            isDarkActive() ? MermaidRenderService.Theme.DARK : MermaidRenderService.Theme.LIGHT,
            effectiveBackgroundColor(),
            includePng);
    }

    private void onSourceReady(DiagramSource source, Throwable error) {
        if (error != null) {
            showError(error.getMessage());
            return;
        }
        if (source == null || source.mermaid() == null || source.mermaid().isBlank()) {
            showError(I18n.get("snippets.ai.analysis.diagram.unavailable"));
            return;
        }
        currentMermaid = source.mermaid();
        currentContent = source.content() != null ? source.content() : "";
        currentSourceReferences = source.codeReferences() != null ? List.copyOf(source.codeReferences()) : List.of();
        renderAsync(true);
    }

    private void renderAsync(boolean resetZoom) {
        MermaidRenderService.RenderRequest request = currentRenderRequest(true);
        if (request == null) {
            return;
        }
        cancelRender();
        long generation = ++renderGeneration;
        renderFuture = MermaidRenderService.render(request);
        renderFuture.whenComplete((result, error) -> Platform.runLater(() -> {
            if (generation != renderGeneration) {
                return;
            }
            if (error != null) {
                showError(error.getMessage());
            } else {
                onRendered(result, resetZoom);
            }
        }));
    }

    private void onRendered(MermaidRenderService.RenderResult result, boolean resetZoom) {
        if (result == null || !result.success() || result.svg().isBlank()) {
            showError(result != null ? result.message() : "");
            return;
        }
        renderedSvg = result.svg();
        renderedPng = result.png();
        if (resetZoom) {
            zoomFactor = 1.0;
        }
        double[] parsedSize = readSvgSize(renderedSvg);
        baseWidth = result.width() > 0 ? result.width() : parsedSize[0];
        baseHeight = result.height() > 0 ? result.height() : parsedSize[1];
        currentHotspots = buildHotspots(result.nodeBounds(), SnippetDiagramSupport.buildExpandedCodeReferences(
            currentMermaid, currentContent, currentSourceReferences));
        spinnerBox.setVisible(false);
        spinnerBox.setManaged(false);
        diagramScroll.setVisible(true);
        diagramScroll.setManaged(true);
        Platform.runLater(this::renderDiagramToFitViewport);
    }

    private List<SvgHotspot> buildHotspots(
        List<MermaidRenderService.NodeBounds> nodeBounds,
        List<SnippetDiagramSupport.CodeReference> references) {

        if (nodeBounds == null || references == null || references.isEmpty()) {
            return List.of();
        }
        Map<String, SnippetDiagramSupport.CodeReference> byNodeId = new LinkedHashMap<>();
        for (SnippetDiagramSupport.CodeReference reference : references) {
            if (reference != null && reference.nodeId() != null && !reference.nodeId().isBlank()) {
                byNodeId.putIfAbsent(reference.nodeId(), reference);
            }
        }
        return nodeBounds.stream()
            .map(bounds -> {
                SnippetDiagramSupport.CodeReference reference = byNodeId.get(bounds.nodeId());
                return reference == null ? null : new SvgHotspot(
                    bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                    reference.startLine(), reference.endLine(), codeReferenceTooltip(reference));
            })
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    private void renderDiagramToFitViewport() {
        if (renderedSvg == null || !diagramScroll.isVisible()) {
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
        diagramView.getEngine().loadContent(buildDiagramHtml(
            renderedSvg, canvasWidth, canvasHeight, displayWidth, displayHeight));
        zoomLabel.setText(Math.round(zoom * 100) + "%");
    }

    private String buildDiagramHtml(
        String svg,
        double canvasWidth,
        double canvasHeight,
        double displayWidth,
        double displayHeight) {

        double imageLeft = Math.max(0.0, (canvasWidth - displayWidth) / 2.0);
        double imageTop = Math.max(0.0, (canvasHeight - displayHeight) / 2.0);
        double scaleX = displayWidth / Math.max(1.0, baseWidth);
        double scaleY = displayHeight / Math.max(1.0, baseHeight);
        return "<!doctype html><html><head><meta charset=\"UTF-8\">"
            + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">"
            + "<style>html,body{margin:0;width:" + fmt(canvasWidth) + "px;height:" + fmt(canvasHeight)
            + "px;overflow:hidden;background:" + effectiveBackgroundColor() + ";}body{position:relative;}"
            + ".diagram-svg{position:absolute;left:" + fmt(imageLeft) + "px;top:" + fmt(imageTop)
            + "px;width:" + fmt(displayWidth) + "px;height:" + fmt(displayHeight) + "px;pointer-events:none;}"
            + ".diagram-svg>svg{display:block;width:100%;height:100%;}"
            + ".diagram-hotspot{position:absolute;display:block;border-radius:8px;cursor:pointer;}"
            + ".diagram-hotspot:hover{background:rgba(37,99,235,.08);outline:2px solid rgba(37,99,235,.5);}</style>"
            + "</head><body><div class=\"diagram-svg\">" + svg + "</div>"
            + buildHotspotHtml(imageLeft, imageTop, scaleX, scaleY)
            + "</body></html>";
    }

    private String buildHotspotHtml(double imageLeft, double imageTop, double scaleX, double scaleY) {
        StringBuilder html = new StringBuilder();
        for (SvgHotspot hotspot : currentHotspots) {
            double width = hotspot.width() * scaleX;
            double height = hotspot.height() * scaleY;
            if (width <= 0 || height <= 0) {
                continue;
            }
            String tag = codeNavigationHandler != null ? "a" : "div";
            html.append('<').append(tag).append(" class=\"diagram-hotspot\" title=\"")
                .append(escapeHtml(hotspot.tooltip())).append('"');
            if (codeNavigationHandler != null) {
                html.append(" href=\"#kortty-code-reference-").append(hotspot.startLine())
                    .append('-').append(hotspot.endLine()).append("\"");
            }
            html.append(" style=\"left:").append(fmt(imageLeft + hotspot.x() * scaleX))
                .append("px;top:").append(fmt(imageTop + hotspot.y() * scaleY))
                .append("px;width:").append(fmt(width)).append("px;height:").append(fmt(height)).append("px\"></")
                .append(tag).append('>');
        }
        return html.toString();
    }

    private void handleNavigation(String location) {
        if (codeNavigationHandler == null || location == null) {
            return;
        }
        Matcher matcher = NAVIGATION_PATTERN.matcher(location);
        if (!matcher.find()) {
            return;
        }
        int startLine = Integer.parseInt(matcher.group(1));
        int endLine = Integer.parseInt(matcher.group(2));
        codeNavigationHandler.accept(new CodeNavigationTarget(startLine, endLine));
        Platform.runLater(this::renderDiagramToFitViewport);
    }

    private FlowPane buildToolbar(boolean showRegenerate) {
        FlowPane toolbar = new FlowPane(6, 6);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        if (showRegenerate) {
            Button regenerate = new Button(SnippetAiDialogSupport.AI_ACTION_PREFIX + I18n.get("snippets.ai.diagram.regenerate"));
            regenerate.setOnAction(event -> reload());
            toolbar.getChildren().add(regenerate);
        }
        buildDarkModeButton();
        toolbar.getChildren().add(darkModeButton);
        backgroundPicker.setOnAction(event -> changeBackground(toHex(backgroundPicker.getValue())));
        toolbar.getChildren().addAll(new Label(I18n.get("snippets.ai.diagram.backgroundColor")), backgroundPicker);
        updateBackgroundPickerState();

        Button saveSvg = new Button(I18n.get("snippets.ai.diagram.saveSvg"));
        saveSvg.setOnAction(event -> saveSvg());
        Button savePng = new Button(I18n.get("snippets.ai.diagram.savePng"));
        savePng.setOnAction(event -> savePng());
        Button copyImage = new Button(I18n.get("snippets.ai.diagram.copyImage"));
        copyImage.setOnAction(event -> copyImage());
        Button copyMermaid = new Button(I18n.get("snippets.ai.diagram.copyMermaid"));
        copyMermaid.setOnAction(event -> copyMermaid());
        Button zoomOut = new Button("−");
        zoomOut.setTooltip(new Tooltip(I18n.get("menu.view.zoomOut")));
        zoomOut.setOnAction(event -> setZoomFactor(zoomFactor - 0.15));
        Button zoomFit = new Button(I18n.get("snippets.ai.diagram.zoom.fit"));
        zoomFit.setOnAction(event -> setZoomFactor(1.0));
        Button zoomIn = new Button("+");
        zoomIn.setTooltip(new Tooltip(I18n.get("menu.view.zoomIn")));
        zoomIn.setOnAction(event -> setZoomFactor(zoomFactor + 0.15));
        zoomLabel.setMinWidth(Region.USE_PREF_SIZE);
        toolbar.getChildren().addAll(saveSvg, savePng, copyImage, copyMermaid, zoomOut, zoomLabel, zoomIn, zoomFit);
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
        spinnerBox.getChildren().stream()
            .filter(ProgressIndicator.class::isInstance)
            .map(ProgressIndicator.class::cast)
            .forEach(indicator -> { indicator.setVisible(true); indicator.setManaged(true); });
        statusLabel.setText(message);
        spinnerBox.setVisible(true);
        spinnerBox.setManaged(true);
    }

    private void showError(String message) {
        diagramScroll.setVisible(false);
        diagramScroll.setManaged(false);
        spinnerBox.getChildren().stream()
            .filter(ProgressIndicator.class::isInstance)
            .map(ProgressIndicator.class::cast)
            .forEach(indicator -> { indicator.setVisible(false); indicator.setManaged(false); });
        statusLabel.setText(I18n.get("snippets.ai.diagram.renderFailed",
            message != null && !message.isBlank() ? message : "?"));
        spinnerBox.setVisible(true);
        spinnerBox.setManaged(true);
    }

    private void saveSvg() {
        if (renderedSvg == null) {
            return;
        }
        File target = chooseSaveFile("svg");
        if (target == null) {
            return;
        }
        Path targetPath = ensureExtension(target.toPath(), ".svg");
        try {
            Files.writeString(targetPath, renderedSvg, StandardCharsets.UTF_8);
            statusLabel.setText(I18n.get("snippets.ai.diagram.export.saved", targetPath.toString()));
        } catch (Exception e) {
            statusLabel.setText(I18n.get("snippets.ai.diagram.export.failed", String.valueOf(e.getMessage())));
        }
    }

    private void savePng() {
        if (renderedPng == null) {
            return;
        }
        File target = chooseSaveFile("png");
        if (target == null) {
            return;
        }
        Path targetPath = ensureExtension(target.toPath(), ".png");
        try {
            Files.write(targetPath, renderedPng);
            statusLabel.setText(I18n.get("snippets.ai.diagram.export.saved", targetPath.toString()));
        } catch (Exception e) {
            statusLabel.setText(I18n.get("snippets.ai.diagram.export.failed", String.valueOf(e.getMessage())));
        }
    }

    private void copyImage() {
        if (renderedPng == null) {
            return;
        }
        Image image = new Image(new ByteArrayInputStream(renderedPng));
        if (image.isError()) {
            statusLabel.setText(I18n.get("snippets.ai.diagram.export.failed", "invalid PNG"));
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putImage(image);
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText(I18n.get("snippets.ai.diagram.copy.ready"));
    }

    private void copyMermaid() {
        if (currentMermaid == null || currentMermaid.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(currentMermaid);
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText(I18n.get("snippets.ai.diagram.copyMermaid.ready"));
    }

    private File chooseSaveFile(String extension) {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName("diagram." + extension);
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(extension.toUpperCase(Locale.ROOT) + " (*." + extension + ")", "*." + extension));
        Window window = getScene() != null ? getScene().getWindow() : null;
        return chooser.showSaveDialog(window);
    }

    private static Path ensureExtension(Path path, String dottedExtension) {
        String name = path.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(dottedExtension)
            ? path
            : path.resolveSibling(name + dottedExtension);
    }

    private void setZoomFactor(double factor) {
        zoomFactor = Math.max(MIN_ZOOM_FACTOR, Math.min(MAX_ZOOM_FACTOR, factor));
        renderDiagramToFitViewport();
    }

    private void changeBackground(String color) {
        String normalized = SnippetDiagramSupport.normalizeHexColor(color, DEFAULT_BACKGROUND);
        if (normalized.equals(backgroundColor)) {
            return;
        }
        backgroundColor = normalized;
        saveConfiguredBackground(backgroundColor);
        if (!isDarkActive()) {
            applyBackgroundStyle();
            renderAsync(false);
        }
    }

    private void buildDarkModeButton() {
        darkModeButton.setTooltip(new Tooltip(I18n.get("snippets.ai.diagram.darkMode.tooltip")));
        ToggleGroup group = new ToggleGroup();
        for (SnippetDiagramSupport.DiagramColorMode mode : SnippetDiagramSupport.DiagramColorMode.values()) {
            RadioMenuItem item = new RadioMenuItem(colorModeLabel(mode));
            item.setToggleGroup(group);
            item.setSelected(mode == colorMode);
            item.setOnAction(event -> changeColorMode(mode));
            darkModeButton.getItems().add(item);
        }
        updateDarkModeButtonText();
    }

    private static String colorModeLabel(SnippetDiagramSupport.DiagramColorMode mode) {
        return switch (mode) {
            case AUTO -> I18n.get("snippets.ai.diagram.darkMode.auto");
            case LIGHT -> I18n.get("snippets.ai.diagram.darkMode.light");
            case DARK -> I18n.get("snippets.ai.diagram.darkMode.dark");
        };
    }

    private void updateDarkModeButtonText() {
        darkModeButton.setText(I18n.get("snippets.ai.diagram.darkMode") + ": " + colorModeLabel(colorMode));
    }

    private void changeColorMode(SnippetDiagramSupport.DiagramColorMode mode) {
        if (mode == null || mode == colorMode) {
            return;
        }
        colorMode = mode;
        saveConfiguredColorMode(mode.key());
        updateDarkModeButtonText();
        reapplyAppearance();
    }

    private void reapplyAppearance() {
        lastResolvedDark = isDarkActive();
        updateBackgroundPickerState();
        applyBackgroundStyle();
        renderAsync(false);
    }

    private boolean isDarkActive() {
        return colorMode != null && colorMode.isDarkActive();
    }

    private String effectiveBackgroundColor() {
        return isDarkActive() ? SnippetDiagramSupport.DARK_BACKGROUND_COLOR : backgroundColor;
    }

    private void updateBackgroundPickerState() {
        backgroundPicker.setDisable(isDarkActive());
    }

    private void installSystemThemeFocusWatcher() {
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.windowProperty().addListener((o, oldWindow, newWindow) -> {
                    if (newWindow != null) {
                        newWindow.focusedProperty().addListener((f, wasFocused, isFocused) -> {
                            if (Boolean.TRUE.equals(isFocused)) {
                                onWindowFocused();
                            }
                        });
                    }
                });
            }
        });
    }

    private void onWindowFocused() {
        if (colorMode != SnippetDiagramSupport.DiagramColorMode.AUTO) {
            return;
        }
        SystemThemeDetector.invalidateCache();
        if (isDarkActive() != lastResolvedDark) {
            reapplyAppearance();
        }
    }

    private void applyBackgroundStyle() {
        String color = effectiveBackgroundColor();
        diagramScroll.setStyle("-fx-background: " + color + "; -fx-background-color: " + color + ";");
    }

    private String loadConfiguredBackground() {
        GlobalSettings settings = currentSettings();
        return settings != null ? settings.getSnippetDiagramBackgroundColor() : DEFAULT_BACKGROUND;
    }

    private String loadConfiguredColorMode() {
        GlobalSettings settings = currentSettings();
        return settings != null ? settings.getSnippetDiagramColorMode() : "auto";
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

    private void saveConfiguredColorMode(String mode) {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            if (manager != null && manager.getSettings() != null) {
                manager.getSettings().setSnippetDiagramColorMode(mode);
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

    private void cancelRender() {
        renderGeneration++;
        if (renderFuture != null && !renderFuture.isDone()) {
            renderFuture.cancel(true);
        }
        renderFuture = null;
    }

    private static String codeReferenceTooltip(SnippetDiagramSupport.CodeReference reference) {
        String header = reference.startLine() == reference.endLine()
            ? I18n.get("snippets.ai.diagram.codeReference.line", reference.startLine())
            : I18n.get("snippets.ai.diagram.codeReference.lines", reference.startLine(), reference.endLine());
        return header + "\n" + reference.excerpt();
    }

    private static double[] readSvgSize(String svg) {
        Matcher viewBox = SVG_VIEW_BOX_PATTERN.matcher(svg != null ? svg : "");
        if (viewBox.find()) {
            return new double[] {parseLength(viewBox.group(1), 900.0), parseLength(viewBox.group(2), 600.0)};
        }
        Matcher widthHeight = SVG_WIDTH_HEIGHT_PATTERN.matcher(svg != null ? svg : "");
        if (widthHeight.find()) {
            return new double[] {parseLength(widthHeight.group(1), 900.0), parseLength(widthHeight.group(2), 600.0)};
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
        return SnippetAiDialogSupport.escapeHtml(value != null ? value : "");
    }
}
