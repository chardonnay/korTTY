package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.PlantUmlRenderService;
import de.kortty.core.SnippetDiagramSupport;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SnippetDiagram;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.SplitPane;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
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
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Shows persisted snippet PlantUML diagrams and local render results.
 */
public class SnippetDiagramDialog extends ThemeAwareDialog<Void> {

    private final String snippetName;
    private final String currentContent;
    private final Consumer<SnippetDiagram> regenerateHandler;
    private final Runnable newDiagramHandler;
    private final Consumer<CodeNavigationTarget> codeNavigationHandler;
    private final ListView<SnippetDiagram> diagramListView;
    private final ScrollPane diagramScrollPane;
    private final WebView diagramView;
    private final Label statusLabel;
    private final Label zoomLabel;
    private final Button regenerateButton;
    private final Button zoomOutButton;
    private final Button zoomResetButton;
    private final Button zoomInButton;
    private final Button saveSvgButton;
    private final Button savePngButton;
    private final Button copyImageButton;
    private final Button copyPlantUmlButton;
    private final ColorPicker backgroundColorPicker;
    private Task<PlantUmlRenderService.RenderResult> renderTask;
    private Task<PlantUmlRenderService.RenderResult> exportTask;
    private Path renderedSvgPath;
    private List<SnippetDiagramSupport.CodeReference> currentCodeReferences = List.of();
    private List<SvgHotspot> currentSvgHotspots = List.of();
    private String diagramBackgroundColor;
    private double diagramZoom = 1.0;
    private double diagramZoomFactor = 1.0;
    private double diagramBaseWidth = 900.0;
    private double diagramBaseHeight = 600.0;
    private static final Pattern SVG_VIEW_BOX_PATTERN =
        Pattern.compile("viewBox\\s*=\\s*\"[^\"]*?\\s+([0-9.]+)\\s+([0-9.]+)\"");
    private static final Pattern SVG_WIDTH_HEIGHT_PATTERN =
        Pattern.compile("<svg[^>]*\\swidth\\s*=\\s*\"([0-9.]+)[^\"]*\"[^>]*\\sheight\\s*=\\s*\"([0-9.]+)[^\"]*\"");
    private static final double DIAGRAM_LIST_WIDTH_PADDING = 42.0;
    private static final double DIAGRAM_LIST_EMPTY_WIDTH = 140.0;
    private static final double DIAGRAM_MIN_ZOOM_FACTOR = 0.25;
    private static final double DIAGRAM_MAX_ZOOM_FACTOR = 4.0;
    private static final double DIAGRAM_MIN_ZOOM = 0.05;
    private static final double DIAGRAM_MAX_ZOOM = 12.0;
    private static final String DEFAULT_DIAGRAM_BACKGROUND_COLOR = "#FFFFFF";
    private static final String CODE_REFERENCE_ALERT_PREFIX = "kortty-code-reference:";
    private static final String AI_ACTION_PREFIX = "\u2728 ";
    private static final String ICON_REFRESH =
        "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-8 3.58-8 8s3.58 8 8 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h8V3z";
    private static final String ICON_SAVE =
        "M17 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V7l-4-4zM12 19c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3zM15 9H5V5h10z";
    private static final String ICON_IMAGE =
        "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 11.5l2.5 3.01L14.5 10l4.5 6H5l3.5-4.5z";
    private static final String ICON_CODE =
        "M9.4 16.6 4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0L19.2 12l-4.6-4.6L16 6l6 6-6 6-1.4-1.4z";
    private static final String ICON_ZOOM_OUT =
        "M9.5 3a6.5 6.5 0 0 1 5.16 10.45l4.45 4.44-1.42 1.42-4.44-4.45A6.5 6.5 0 1 1 9.5 3zm-3 5.5v2h6v-2h-6z";
    private static final String ICON_ZOOM_IN =
        "M9.5 3a6.5 6.5 0 0 1 5.16 10.45l4.45 4.44-1.42 1.42-4.44-4.45A6.5 6.5 0 1 1 9.5 3zm-1 4.5v2h-2v2h2v2h2v-2h2v-2h-2v-2h-2z";
    private static final String ICON_FIT =
        "M4 4h6v2H7.41l3.3 3.29-1.42 1.42L6 7.41V10H4V4zm10 0h6v6h-2V7.41l-3.29 3.3-1.42-1.42 3.3-3.29H14V4zM4 14h2v2.59l3.29-3.3 1.42 1.42L7.41 18H10v2H4v-6zm14 0h2v6h-6v-2h2.59l-3.3-3.29 1.42-1.42 3.29 3.3V14z";

    public SnippetDiagramDialog(
        Window owner,
        List<SnippetDiagram> diagrams,
        String currentContent,
        String snippetName,
        Consumer<SnippetDiagram> regenerateHandler,
        Runnable newDiagramHandler,
        Consumer<CodeNavigationTarget> codeNavigationHandler) {

        this.snippetName = snippetName != null && !snippetName.isBlank()
            ? snippetName.trim()
            : I18n.get("snippets.ai.diagram.script.unnamed");
        this.currentContent = currentContent != null ? currentContent : "";
        this.regenerateHandler = regenerateHandler;
        this.newDiagramHandler = newDiagramHandler;
        this.codeNavigationHandler = codeNavigationHandler;
        this.diagramBackgroundColor = loadConfiguredDiagramBackgroundColor();

        setTitle(I18n.get("snippets.ai.diagram.title"));
        setResizable(true);
        initModality(Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }

        List<SnippetDiagram> safeDiagrams = diagrams != null ? diagrams : List.of();
        diagramListView = new ListView<>(FXCollections.observableArrayList(safeDiagrams));
        diagramListView.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(SnippetDiagram diagram, boolean empty) {
                super.updateItem(diagram, empty);
                setText(empty || diagram == null ? null : getDiagramTypeLabel(diagram));
            }
        });
        double diagramListWidth = setFixedDiagramListWidth(safeDiagrams);
        diagramListView.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> showDiagram(newValue));

        diagramView = new WebView();
        diagramView.setContextMenuEnabled(false);
        diagramView.setMinSize(1, 1);
        diagramView.setManaged(false);
        diagramView.setVisible(false);
        diagramView.getEngine().setOnAlert(event -> handleDiagramAlert(event.getData()));
        diagramScrollPane = new ScrollPane(diagramView);
        diagramScrollPane.setFitToWidth(false);
        diagramScrollPane.setFitToHeight(false);
        diagramScrollPane.setPannable(true);
        applyDiagramBackgroundStyle();
        diagramScrollPane.setMinSize(320, 240);
        diagramScrollPane.setManaged(false);
        diagramScrollPane.setVisible(false);
        diagramScrollPane.viewportBoundsProperty().addListener((obs, oldValue, newValue) -> renderDiagramToFitViewport());

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: gray;");

        regenerateButton = new Button(AI_ACTION_PREFIX + I18n.get("snippets.ai.diagram.regenerate"));
        setButtonIcon(regenerateButton, ICON_REFRESH);
        regenerateButton.setOnAction(event -> {
            SnippetDiagram selected = diagramListView.getSelectionModel().getSelectedItem();
            if (selected != null && regenerateHandler != null) {
                close();
                regenerateHandler.accept(selected);
            }
        });

        zoomOutButton = new Button("-");
        setButtonIcon(zoomOutButton, ICON_ZOOM_OUT);
        zoomOutButton.setTooltip(new javafx.scene.control.Tooltip(I18n.get("menu.view.zoomOut")));
        zoomOutButton.setOnAction(event -> setDiagramZoomFactor(diagramZoomFactor - 0.15));
        zoomResetButton = new Button(I18n.get("snippets.ai.diagram.zoom.fit"));
        setButtonIcon(zoomResetButton, ICON_FIT);
        zoomResetButton.setTooltip(new javafx.scene.control.Tooltip(I18n.get("snippets.ai.diagram.zoom.fit")));
        zoomResetButton.setOnAction(event -> setDiagramZoomFactor(1.0));
        zoomInButton = new Button("+");
        setButtonIcon(zoomInButton, ICON_ZOOM_IN);
        zoomInButton.setTooltip(new javafx.scene.control.Tooltip(I18n.get("menu.view.zoomIn")));
        zoomInButton.setOnAction(event -> setDiagramZoomFactor(diagramZoomFactor + 0.15));
        zoomLabel = new Label("100%");
        zoomLabel.setMinWidth(Region.USE_PREF_SIZE);

        saveSvgButton = new Button(I18n.get("snippets.ai.diagram.saveSvg"));
        setButtonIcon(saveSvgButton, ICON_SAVE);
        saveSvgButton.setOnAction(event -> saveCurrentSvg());
        savePngButton = new Button(I18n.get("snippets.ai.diagram.savePng"));
        setButtonIcon(savePngButton, ICON_SAVE);
        savePngButton.setOnAction(event -> saveCurrentPng());
        copyImageButton = new Button(I18n.get("snippets.ai.diagram.copyImage"));
        setButtonIcon(copyImageButton, ICON_IMAGE);
        copyImageButton.setOnAction(event -> copyCurrentPngToClipboard());
        copyPlantUmlButton = new Button(I18n.get("snippets.ai.diagram.copyPlantUml"));
        setButtonIcon(copyPlantUmlButton, ICON_CODE);
        copyPlantUmlButton.setOnAction(event -> copyCurrentPlantUmlToClipboard());
        backgroundColorPicker = new ColorPicker(Color.web(diagramBackgroundColor));
        backgroundColorPicker.setPrefWidth(120);
        backgroundColorPicker.setTooltip(new Tooltip(I18n.get("snippets.ai.diagram.backgroundColor")));
        backgroundColorPicker.setOnAction(event -> changeDiagramBackgroundColor(toHex(backgroundColorPicker.getValue())));
        Label backgroundColorLabel = new Label(I18n.get("snippets.ai.diagram.backgroundColor"));

        Region toolbarSpacer = new Region();
        HBox toolbar = new HBox(
            8,
            regenerateButton,
            saveSvgButton,
            savePngButton,
            copyImageButton,
            copyPlantUmlButton,
            toolbarSpacer,
            backgroundColorLabel,
            backgroundColorPicker,
            zoomOutButton,
            zoomLabel,
            zoomResetButton,
            zoomInButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);
        VBox rightPane = new VBox(8, statusLabel, toolbar, diagramScrollPane);
        VBox.setVgrow(diagramScrollPane, Priority.ALWAYS);

        SplitPane splitPane = new SplitPane(diagramListView, rightPane);
        SplitPane.setResizableWithParent(diagramListView, false);
        splitPane.widthProperty().addListener((obs, oldValue, newValue) ->
            setFixedDiagramListDivider(splitPane, diagramListWidth));
        Platform.runLater(() -> setFixedDiagramListDivider(splitPane, diagramListWidth));
        VBox root = new VBox(splitPane);
        root.setPadding(new Insets(14));
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        setResultConverter(buttonType -> null);
        getDialogPane().setPrefWidth(980);
        getDialogPane().setPrefHeight(700);
        setOnHidden(event -> cancelTasks());
        updateDiagramControls(false);
        if (!diagramListView.getItems().isEmpty()) {
            diagramListView.getSelectionModel().selectFirst();
        } else {
            showDiagram(null);
        }
    }

    private double setFixedDiagramListWidth(List<SnippetDiagram> diagrams) {
        double listWidth = calculateDiagramListWidth(diagrams);
        diagramListView.setMinWidth(listWidth);
        diagramListView.setPrefWidth(listWidth);
        diagramListView.setMaxWidth(listWidth);
        return listWidth;
    }

    private void setFixedDiagramListDivider(SplitPane splitPane, double listWidth) {
        double splitPaneWidth = splitPane.getWidth();
        if (splitPaneWidth <= 0.0) {
            return;
        }
        splitPane.setDividerPositions(Math.min(0.9, listWidth / splitPaneWidth));
    }

    private double calculateDiagramListWidth(List<SnippetDiagram> diagrams) {
        if (diagrams == null || diagrams.isEmpty()) {
            return DIAGRAM_LIST_EMPTY_WIDTH;
        }
        double widestText = 0.0;
        for (SnippetDiagram diagram : diagrams) {
            Text text = new Text(getDiagramTypeLabel(diagram));
            widestText = Math.max(widestText, text.getLayoutBounds().getWidth());
        }
        return Math.ceil(widestText + DIAGRAM_LIST_WIDTH_PADDING);
    }

    private String getDiagramTypeLabel(SnippetDiagram diagram) {
        if (diagram == null) {
            return "";
        }
        String type = diagram.getType();
        if (SnippetDiagram.TYPE_LOGICAL_STRUCTURE.equals(type)) {
            return I18n.get("snippets.ai.diagram.type.logicalStructure");
        }
        return type != null && !type.isBlank() ? type : I18n.get("snippets.ai.diagram.type.unknown");
    }

    private void showDiagram(SnippetDiagram diagram) {
        cancelRenderTask();
        renderedSvgPath = null;
        currentCodeReferences = List.of();
        currentSvgHotspots = List.of();
        diagramView.getEngine().loadContent("");
        setDiagramImageVisible(false);
        regenerateButton.setDisable(diagram == null);
        if (diagram == null) {
            setStatusText(I18n.get("snippets.ai.diagram.empty"));
            return;
        }
        String staleText = SnippetDiagramSupport.isStale(diagram, currentContent)
            ? I18n.get("snippets.ai.diagram.stale")
            : I18n.get("snippets.ai.diagram.current");
        String plantUmlSource = plantUmlSourceForDisplay(diagram);
        String renderPlantUmlSource = plantUmlSourceForRender(diagram);
        currentCodeReferences = codeReferencesForDisplay(diagram, plantUmlSource);
        setStatusText(staleText + " " + I18n.get("snippets.ai.diagram.rendering"));

        renderTask = new Task<>() {
            @Override
            protected PlantUmlRenderService.RenderResult call() {
                return new PlantUmlRenderService().renderSvg(renderPlantUmlSource);
            }
        };
        renderTask.setOnSucceeded(event -> {
            PlantUmlRenderService.RenderResult result = renderTask.getValue();
            if (result != null && result.success() && result.imagePath() != null) {
                renderedSvgPath = result.imagePath();
                diagramZoomFactor = 1.0;
                updateDiagramBaseSize(result.imagePath());
                currentSvgHotspots = buildSvgHotspots(result.imagePath(), currentCodeReferences);
                setDiagramImageVisible(true);
                Platform.runLater(this::renderDiagramToFitViewport);
                setStatusText(staleText);
            } else {
                renderedSvgPath = null;
                currentSvgHotspots = List.of();
                diagramView.getEngine().loadContent("");
                setDiagramImageVisible(false);
                setStatusText(staleText + " " + I18n.get(
                    "snippets.ai.diagram.renderFailed",
                    result != null ? result.message() : ""));
            }
        });
        renderTask.setOnFailed(event -> {
            renderedSvgPath = null;
            currentSvgHotspots = List.of();
            diagramView.getEngine().loadContent("");
            setDiagramImageVisible(false);
            setStatusText(staleText + " " + I18n.get("snippets.ai.diagram.renderFailed", ""));
        });
        Thread thread = new Thread(renderTask, "snippet-diagram-render");
        thread.setDaemon(true);
        thread.start();
    }

    private void setDiagramImageVisible(boolean visible) {
        diagramScrollPane.setManaged(visible);
        diagramScrollPane.setVisible(visible);
        diagramView.setManaged(visible);
        diagramView.setVisible(visible);
        updateDiagramControls(visible);
    }

    private void setDiagramZoomFactor(double zoomFactor) {
        diagramZoomFactor = Math.max(DIAGRAM_MIN_ZOOM_FACTOR, Math.min(DIAGRAM_MAX_ZOOM_FACTOR, zoomFactor));
        renderDiagramToFitViewport();
    }

    private void renderDiagramToFitViewport() {
        if (renderedSvgPath == null || !diagramScrollPane.isVisible()) {
            return;
        }
        Bounds viewportBounds = diagramScrollPane.getViewportBounds();
        double viewportWidth = viewportBounds.getWidth();
        double viewportHeight = viewportBounds.getHeight();
        if (viewportWidth <= 1.0 || viewportHeight <= 1.0) {
            return;
        }
        double fitZoom = Math.min(viewportWidth / diagramBaseWidth, viewportHeight / diagramBaseHeight);
        diagramZoom = Math.max(DIAGRAM_MIN_ZOOM, Math.min(DIAGRAM_MAX_ZOOM, fitZoom * diagramZoomFactor));
        double displayWidth = Math.max(1.0, diagramBaseWidth * diagramZoom);
        double displayHeight = Math.max(1.0, diagramBaseHeight * diagramZoom);
        double canvasWidth = Math.max(viewportWidth, displayWidth);
        double canvasHeight = Math.max(viewportHeight, displayHeight);
        diagramView.setZoom(1.0);
        diagramView.setPrefSize(canvasWidth, canvasHeight);
        diagramView.getEngine().loadContent(buildDiagramHtml(
            renderedSvgPath,
            currentSvgHotspots,
            canvasWidth,
            canvasHeight,
            displayWidth,
            displayHeight));
        zoomLabel.setText(Math.round(diagramZoom * 100) + "%");
    }

    private String buildDiagramHtml(
        Path svgPath,
        List<SvgHotspot> hotspots,
        double canvasWidth,
        double canvasHeight,
        double displayWidth,
        double displayHeight) {

        String svgUrl = escapeHtml(svgPath.toUri().toString());
        double imageLeft = Math.max(0.0, (canvasWidth - displayWidth) / 2.0);
        double imageTop = Math.max(0.0, (canvasHeight - displayHeight) / 2.0);
        double scaleX = displayWidth / Math.max(1.0, diagramBaseWidth);
        double scaleY = displayHeight / Math.max(1.0, diagramBaseHeight);
        String hotspotHtml = buildHotspotHtml(hotspots, imageLeft, imageTop, scaleX, scaleY);
        return String.format(Locale.ROOT, """
            <!doctype html>
            <html>
            <head>
              <meta charset="UTF-8">
              <style>
                html, body {
                  margin: 0;
                  width: %.2fpx;
                  height: %.2fpx;
                  overflow: hidden;
                  background: %s;
                }
                body {
                  position: relative;
                }
                img {
                  display: block;
                  position: absolute;
                  left: %.2fpx;
                  top: %.2fpx;
                  width: %.2fpx;
                  height: %.2fpx;
                }
                .diagram-hotspot {
                  position: absolute;
                  border-radius: 8px;
                  cursor: pointer;
                }
                .diagram-hotspot:hover {
                  background: rgba(37, 99, 235, 0.08);
                  outline: 2px solid rgba(37, 99, 235, 0.5);
                }
                #code-tooltip {
                  display: none;
                  position: absolute;
                  z-index: 20;
                  max-width: 560px;
                  max-height: 240px;
                  overflow: auto;
                  padding: 8px 10px;
                  border-radius: 6px;
                  background: rgba(24, 24, 27, 0.94);
                  color: #f8fafc;
                  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
                  font-size: 12px;
                  line-height: 1.35;
                  white-space: pre;
                  pointer-events: none;
                  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.25);
                }
              </style>
            </head>
            <body>
              <img src="%s" alt="">
              %s
              <div id="code-tooltip"></div>
              <script>
                (function () {
                  const tooltip = document.getElementById('code-tooltip');
                  function showTooltip(event, hotspot) {
                    tooltip.textContent = hotspot.getAttribute('data-tooltip') || '';
                    tooltip.style.display = 'block';
                    moveTooltip(event);
                  }
                  function moveTooltip(event) {
                    const margin = 14;
                    const bounds = tooltip.getBoundingClientRect();
                    let left = event.clientX + margin;
                    let top = event.clientY + margin;
                    if (left + bounds.width > window.innerWidth) {
                      left = Math.max(0, event.clientX - bounds.width - margin);
                    }
                    if (top + bounds.height > window.innerHeight) {
                      top = Math.max(0, event.clientY - bounds.height - margin);
                    }
                    tooltip.style.left = left + 'px';
                    tooltip.style.top = top + 'px';
                  }
                  function hideTooltip() {
                    tooltip.style.display = 'none';
                  }
                  document.querySelectorAll('.diagram-hotspot').forEach(function (hotspot) {
                    hotspot.addEventListener('mouseenter', function (event) { showTooltip(event, hotspot); });
                    hotspot.addEventListener('mousemove', moveTooltip);
                    hotspot.addEventListener('mouseleave', hideTooltip);
                    hotspot.addEventListener('click', function (event) {
                      event.preventDefault();
                      const referenceId = hotspot.getAttribute('data-reference-id');
                      if (referenceId) {
                        window.alert('%s' + referenceId);
                      }
                    });
                  });
                }());
              </script>
            </body>
            </html>
            """,
            canvasWidth,
            canvasHeight,
            escapeHtml(diagramBackgroundColor),
            imageLeft,
            imageTop,
            displayWidth,
            displayHeight,
            svgUrl,
            hotspotHtml,
            CODE_REFERENCE_ALERT_PREFIX);
    }

    private void handleDiagramAlert(String data) {
        if (data == null || !data.startsWith(CODE_REFERENCE_ALERT_PREFIX)) {
            return;
        }
        openCodeReference(data.substring(CODE_REFERENCE_ALERT_PREFIX.length()));
    }

    private void openCodeReference(String referenceId) {
        if (referenceId == null || codeNavigationHandler == null) {
            return;
        }
        currentCodeReferences.stream()
            .filter(reference -> referenceId.equals(reference.id()))
            .findFirst()
            .ifPresent(reference -> codeNavigationHandler.accept(
                new CodeNavigationTarget(reference.startLine(), reference.endLine())));
    }

    private String buildHotspotHtml(
        List<SvgHotspot> hotspots,
        double imageLeft,
        double imageTop,
        double scaleX,
        double scaleY) {

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
            builder.append(String.format(Locale.ROOT,
                """
                  <div class="diagram-hotspot" data-reference-id="%s" data-tooltip="%s" style="left: %.2fpx; top: %.2fpx; width: %.2fpx; height: %.2fpx;"></div>
                """,
                escapeHtml(hotspot.referenceId()),
                escapeHtml(hotspot.tooltip()),
                left,
                top,
                width,
                height));
        }
        return builder.toString();
    }

    private List<SvgHotspot> buildSvgHotspots(
        Path svgPath,
        List<SnippetDiagramSupport.CodeReference> codeReferences) {

        if (svgPath == null || codeReferences == null || codeReferences.isEmpty()) {
            return List.of();
        }
        Map<String, Deque<SnippetDiagramSupport.CodeReference>> referencesByLabel = new LinkedHashMap<>();
        for (SnippetDiagramSupport.CodeReference reference : codeReferences) {
            referencesByLabel
                .computeIfAbsent(normalizeSvgText(reference.label()), ignored -> new ArrayDeque<>())
                .add(reference);
        }
        try {
            Document document = readSvgDocument(svgPath);
            NodeList textNodes = document.getElementsByTagName("text");
            List<SvgHotspot> hotspots = new ArrayList<>();
            for (int i = 0; i < textNodes.getLength(); i++) {
                if (!(textNodes.item(i) instanceof Element textElement)) {
                    continue;
                }
                Deque<SnippetDiagramSupport.CodeReference> candidates =
                    referencesByLabel.get(normalizeSvgText(textElement.getTextContent()));
                if (candidates == null || candidates.isEmpty()) {
                    continue;
                }
                Element shapeElement = precedingSvgShape(textElement);
                if (shapeElement == null) {
                    continue;
                }
                SvgBounds bounds = svgShapeBounds(shapeElement);
                if (bounds == null || bounds.width() <= 0.0 || bounds.height() <= 0.0) {
                    continue;
                }
                SnippetDiagramSupport.CodeReference reference = candidates.removeFirst();
                hotspots.add(new SvgHotspot(
                    reference.id(),
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    codeReferenceTooltip(reference)));
            }
            return List.copyOf(hotspots);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Document readSvgDocument(Path svgPath) throws Exception {
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

    private Element precedingSvgShape(Element textElement) {
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

    private SvgBounds svgShapeBounds(Element shapeElement) {
        return switch (shapeElement.getTagName()) {
            case "rect" -> rectBounds(shapeElement);
            case "polygon" -> polygonBounds(shapeElement);
            default -> null;
        };
    }

    private SvgBounds rectBounds(Element rect) {
        return new SvgBounds(
            parseSvgNumber(rect.getAttribute("x"), 0.0),
            parseSvgNumber(rect.getAttribute("y"), 0.0),
            parseSvgNumber(rect.getAttribute("width"), 0.0),
            parseSvgNumber(rect.getAttribute("height"), 0.0));
    }

    private SvgBounds polygonBounds(Element polygon) {
        Matcher matcher = Pattern
            .compile("(-?[0-9]+(?:\\.[0-9]+)?),(-?[0-9]+(?:\\.[0-9]+)?)")
            .matcher(polygon.getAttribute("points"));
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

    private double parseSvgNumber(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String codeReferenceTooltip(SnippetDiagramSupport.CodeReference reference) {
        String header = reference.startLine() == reference.endLine()
            ? I18n.get("snippets.ai.diagram.codeReference.line", reference.startLine())
            : I18n.get("snippets.ai.diagram.codeReference.lines", reference.startLine(), reference.endLine());
        return header + "\n" + reference.excerpt();
    }

    private String normalizeSvgText(String value) {
        return value != null ? value.replaceAll("\\s+", " ").trim() : "";
    }

    private String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private void updateDiagramBaseSize(Path svgPath) {
        SvgSize svgSize = readSvgSize(svgPath);
        diagramBaseWidth = svgSize.width();
        diagramBaseHeight = svgSize.height();
    }

    private SvgSize readSvgSize(Path svgPath) {
        try {
            String svg = Files.readString(svgPath, StandardCharsets.UTF_8);
            Matcher viewBoxMatcher = SVG_VIEW_BOX_PATTERN.matcher(svg);
            if (viewBoxMatcher.find()) {
                return new SvgSize(
                    parseSvgLength(viewBoxMatcher.group(1), 900.0),
                    parseSvgLength(viewBoxMatcher.group(2), 600.0));
            }
            Matcher widthHeightMatcher = SVG_WIDTH_HEIGHT_PATTERN.matcher(svg);
            if (widthHeightMatcher.find()) {
                return new SvgSize(
                    parseSvgLength(widthHeightMatcher.group(1), 900.0),
                    parseSvgLength(widthHeightMatcher.group(2), 600.0));
            }
        } catch (Exception ignored) {
        }
        return new SvgSize(900.0, 600.0);
    }

    private double parseSvgLength(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1.0, Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void saveCurrentSvg() {
        SnippetDiagram selected = diagramListView.getSelectionModel().getSelectedItem();
        if (selected == null || renderedSvgPath == null) {
            return;
        }
        File targetFile = chooseDiagramSaveFile(selected, "svg");
        if (targetFile == null) {
            return;
        }
        Path targetPath = ensureExtension(targetFile.toPath(), ".svg");
        try {
            Files.copy(renderedSvgPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            setStatusText(I18n.get("snippets.ai.diagram.export.saved", targetPath.toString()));
        } catch (IOException e) {
            setStatusText(I18n.get("snippets.ai.diagram.export.failed", errorMessage(e)));
        }
    }

    private void saveCurrentPng() {
        SnippetDiagram selected = diagramListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        File targetFile = chooseDiagramSaveFile(selected, "png");
        if (targetFile == null) {
            return;
        }
        Path targetPath = ensureExtension(targetFile.toPath(), ".png");
        renderPngAsync(selected, I18n.get("snippets.ai.diagram.export.rendering"), result -> {
            if (result != null && result.success() && result.imagePath() != null) {
                try {
                    Files.copy(result.imagePath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                    setStatusText(I18n.get("snippets.ai.diagram.export.saved", targetPath.toString()));
                } catch (IOException e) {
                    setStatusText(I18n.get("snippets.ai.diagram.export.failed", errorMessage(e)));
                }
            } else {
                setStatusText(I18n.get(
                    "snippets.ai.diagram.export.failed",
                    result != null ? result.message() : ""));
            }
        });
    }

    private void copyCurrentPngToClipboard() {
        SnippetDiagram selected = diagramListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        renderPngAsync(selected, I18n.get("snippets.ai.diagram.copy.rendering"), result -> {
            if (result != null && result.success() && result.imagePath() != null) {
                Image image = new Image(result.imagePath().toUri().toString());
                ClipboardContent clipboardContent = new ClipboardContent();
                clipboardContent.putImage(image);
                Clipboard.getSystemClipboard().setContent(clipboardContent);
                setStatusText(I18n.get("snippets.ai.diagram.copy.ready"));
            } else {
                setStatusText(I18n.get(
                    "snippets.ai.diagram.export.failed",
                    result != null ? result.message() : ""));
            }
        });
    }

    private void copyCurrentPlantUmlToClipboard() {
        SnippetDiagram selected = diagramListView.getSelectionModel().getSelectedItem();
        String plantUmlSource = plantUmlSourceForDisplay(selected);
        if (plantUmlSource == null || plantUmlSource.isBlank()) {
            return;
        }
        ClipboardContent clipboardContent = new ClipboardContent();
        clipboardContent.putString(plantUmlSource);
        Clipboard.getSystemClipboard().setContent(clipboardContent);
        setStatusText(I18n.get("snippets.ai.diagram.copyPlantUml.ready"));
    }

    private void renderPngAsync(
        SnippetDiagram diagram,
        String runningStatus,
        Consumer<PlantUmlRenderService.RenderResult> resultHandler) {

        cancelExportTask();
        updateDiagramControls(false);
        setStatusText(runningStatus);
        exportTask = new Task<>() {
            @Override
            protected PlantUmlRenderService.RenderResult call() {
                return new PlantUmlRenderService().renderPng(plantUmlSourceForRender(diagram));
            }
        };
        exportTask.setOnSucceeded(event -> {
            PlantUmlRenderService.RenderResult result = exportTask.getValue();
            exportTask = null;
            updateDiagramControls(renderedSvgPath != null);
            resultHandler.accept(result);
        });
        exportTask.setOnFailed(event -> {
            exportTask = null;
            updateDiagramControls(renderedSvgPath != null);
            setStatusText(I18n.get("snippets.ai.diagram.export.failed", ""));
        });
        Thread thread = new Thread(exportTask, "snippet-diagram-export");
        thread.setDaemon(true);
        thread.start();
    }

    private File chooseDiagramSaveFile(SnippetDiagram diagram, String extension) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("snippets.ai.diagram.export.title"));
        String normalizedExtension = extension.startsWith(".") ? extension.substring(1) : extension;
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            normalizedExtension.toUpperCase(Locale.ROOT) + " (*." + normalizedExtension + ")",
            "*." + normalizedExtension));
        chooser.setInitialFileName(buildExportFileName(diagram, normalizedExtension));
        Window window = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
        return chooser.showSaveDialog(window);
    }

    private String buildExportFileName(SnippetDiagram diagram, String extension) {
        String title = diagram != null && diagram.getTitle() != null && !diagram.getTitle().isBlank()
            ? diagram.getTitle()
            : getDiagramTypeLabel(diagram);
        String sanitized = title
            .replaceAll("[^A-Za-z0-9._-]+", "-")
            .replaceAll("^-+|-+$", "");
        if (sanitized.isBlank()) {
            sanitized = "snippet-diagram";
        }
        return sanitized + "." + extension;
    }

    private Path ensureExtension(Path path, String extension) {
        String fileName = path.getFileName() != null ? path.getFileName().toString() : "";
        if (fileName.toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT))) {
            return path;
        }
        Path parent = path.getParent();
        Path fileWithExtension = Path.of(fileName + extension);
        return parent != null ? parent.resolve(fileWithExtension) : fileWithExtension;
    }

    private String errorMessage(Exception e) {
        return e != null && e.getMessage() != null && !e.getMessage().isBlank()
            ? e.getMessage()
            : "";
    }

    private void changeDiagramBackgroundColor(String color) {
        String normalized = SnippetDiagramSupport.normalizeHexColor(color, DEFAULT_DIAGRAM_BACKGROUND_COLOR);
        if (normalized.equals(diagramBackgroundColor)) {
            return;
        }
        diagramBackgroundColor = normalized;
        applyDiagramBackgroundStyle();
        boolean saved = saveConfiguredDiagramBackgroundColor(normalized);
        SnippetDiagram selected = diagramListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            showDiagram(selected);
        } else if (saved) {
            setStatusText(I18n.get("snippets.ai.diagram.backgroundColor.saved", normalized));
        }
    }

    private void applyDiagramBackgroundStyle() {
        String color = SnippetDiagramSupport.normalizeHexColor(diagramBackgroundColor, DEFAULT_DIAGRAM_BACKGROUND_COLOR);
        diagramScrollPane.setStyle("-fx-background: " + color + "; -fx-background-color: " + color + ";");
    }

    private String loadConfiguredDiagramBackgroundColor() {
        GlobalSettingsManager manager = globalSettingsManager();
        GlobalSettings settings = manager != null ? manager.getSettings() : null;
        return SnippetDiagramSupport.normalizeHexColor(
            settings != null ? settings.getSnippetDiagramBackgroundColor() : null,
            DEFAULT_DIAGRAM_BACKGROUND_COLOR);
    }

    private boolean saveConfiguredDiagramBackgroundColor(String color) {
        GlobalSettingsManager manager = globalSettingsManager();
        if (manager == null || manager.getSettings() == null) {
            setStatusText(I18n.get("snippets.ai.diagram.backgroundColor.saveFailed", ""));
            return false;
        }
        try {
            manager.getSettings().setSnippetDiagramBackgroundColor(color);
            manager.save();
            return true;
        } catch (Exception e) {
            setStatusText(I18n.get("snippets.ai.diagram.backgroundColor.saveFailed", errorMessage(e)));
            return false;
        }
    }

    private GlobalSettingsManager globalSettingsManager() {
        KorTTYApplication application = KorTTYApplication.getInstance();
        return application != null ? application.getGlobalSettingsManager() : null;
    }

    private void setStatusText(String message) {
        String statusMessage = message != null ? message : "";
        statusLabel.setText(I18n.get("snippets.ai.diagram.script", snippetName) + " - " + statusMessage);
    }

    private void updateDiagramControls(boolean enabled) {
        boolean exportRunning = exportTask != null && exportTask.isRunning();
        boolean controlsEnabled = enabled && !exportRunning;
        SnippetDiagram selected = diagramListView.getSelectionModel().getSelectedItem();
        String plantUmlSource = plantUmlSourceForDisplay(selected);
        boolean hasPlantUmlSource = plantUmlSource != null && !plantUmlSource.isBlank();
        zoomOutButton.setDisable(!enabled);
        zoomResetButton.setDisable(!enabled);
        zoomInButton.setDisable(!enabled);
        zoomLabel.setDisable(!enabled);
        backgroundColorPicker.setDisable(exportRunning);
        saveSvgButton.setDisable(!controlsEnabled || renderedSvgPath == null);
        savePngButton.setDisable(!controlsEnabled);
        copyImageButton.setDisable(!controlsEnabled);
        copyPlantUmlButton.setDisable(!hasPlantUmlSource);
    }

    private String plantUmlSourceForDisplay(SnippetDiagram diagram) {
        return diagram != null
            ? SnippetDiagramSupport.ensureReadableActivityColors(diagram.getPlantUmlSource())
            : "";
    }

    private String plantUmlSourceForRender(SnippetDiagram diagram) {
        return SnippetDiagramSupport.applyBackgroundColor(plantUmlSourceForDisplay(diagram), diagramBackgroundColor);
    }

    private List<SnippetDiagramSupport.CodeReference> codeReferencesForDisplay(
        SnippetDiagram diagram,
        String plantUmlSource) {

        return SnippetDiagramSupport.buildExpandedCodeReferences(
            plantUmlSource,
            currentContent,
            sourceCodeReferences(diagram));
    }

    private List<SnippetDiagramSupport.SourceCodeReference> sourceCodeReferences(SnippetDiagram diagram) {
        if (diagram == null || diagram.getCodeReferences().isEmpty()) {
            return List.of();
        }
        List<SnippetDiagramSupport.SourceCodeReference> references = new ArrayList<>();
        for (SnippetDiagram.CodeReference reference : diagram.getCodeReferences()) {
            if (reference != null) {
                references.add(new SnippetDiagramSupport.SourceCodeReference(
                    reference.getLabel(),
                    reference.getStartLine(),
                    reference.getEndLine()));
            }
        }
        return List.copyOf(references);
    }

    private static String toHex(Color color) {
        if (color == null) {
            return DEFAULT_DIAGRAM_BACKGROUND_COLOR;
        }
        return String.format(Locale.ROOT, "#%02X%02X%02X",
            colorComponent(color.getRed()),
            colorComponent(color.getGreen()),
            colorComponent(color.getBlue()));
    }

    private static int colorComponent(double component) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, component)) * 255.0);
    }

    private static void setButtonIcon(Button button, String iconPath) {
        button.setGraphic(icon(iconPath));
        button.setGraphicTextGap(6);
    }

    private static Node icon(String path) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.setStyle("-fx-fill: -fx-text-base-color;");
        icon.setScaleX(0.72);
        icon.setScaleY(0.72);
        return icon;
    }

    private void cancelRenderTask() {
        if (renderTask != null && renderTask.isRunning()) {
            renderTask.cancel(true);
        }
        renderTask = null;
    }

    private void cancelExportTask() {
        if (exportTask != null && exportTask.isRunning()) {
            exportTask.cancel(true);
        }
        exportTask = null;
    }

    private void cancelTasks() {
        cancelRenderTask();
        cancelExportTask();
    }

    public record CodeNavigationTarget(int startLine, int endLine) {
    }

    private record SvgHotspot(
        String referenceId,
        double x,
        double y,
        double width,
        double height,
        String tooltip) {
    }

    private record SvgBounds(double x, double y, double width, double height) {
    }

    private record SvgSize(double width, double height) {
    }
}
