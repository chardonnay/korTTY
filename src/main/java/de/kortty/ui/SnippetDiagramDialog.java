package de.kortty.ui;

import de.kortty.core.PlantUmlRenderService;
import de.kortty.core.SnippetDiagramSupport;
import de.kortty.model.SnippetDiagram;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows persisted snippet PlantUML diagrams and local render results.
 */
public class SnippetDiagramDialog extends ThemeAwareDialog<Void> {

    private final String currentContent;
    private final Consumer<SnippetDiagram> regenerateHandler;
    private final Runnable newDiagramHandler;
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
    private Task<PlantUmlRenderService.RenderResult> renderTask;
    private Task<PlantUmlRenderService.RenderResult> exportTask;
    private Path renderedSvgPath;
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
    private static final String AI_ACTION_PREFIX = "\u2728 ";

    public SnippetDiagramDialog(
        Window owner,
        List<SnippetDiagram> diagrams,
        String currentContent,
        Consumer<SnippetDiagram> regenerateHandler,
        Runnable newDiagramHandler) {

        this.currentContent = currentContent != null ? currentContent : "";
        this.regenerateHandler = regenerateHandler;
        this.newDiagramHandler = newDiagramHandler;

        setTitle(I18n.get("snippets.ai.diagram.title"));
        setResizable(true);
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
        diagramScrollPane = new ScrollPane(diagramView);
        diagramScrollPane.setFitToWidth(false);
        diagramScrollPane.setFitToHeight(false);
        diagramScrollPane.setPannable(true);
        diagramScrollPane.setStyle("-fx-background: #ffffff; -fx-background-color: #ffffff;");
        diagramScrollPane.setMinSize(320, 240);
        diagramScrollPane.setManaged(false);
        diagramScrollPane.setVisible(false);
        diagramScrollPane.viewportBoundsProperty().addListener((obs, oldValue, newValue) -> renderDiagramToFitViewport());

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: gray;");

        regenerateButton = new Button(AI_ACTION_PREFIX + I18n.get("snippets.ai.diagram.regenerate"));
        regenerateButton.setOnAction(event -> {
            SnippetDiagram selected = diagramListView.getSelectionModel().getSelectedItem();
            if (selected != null && regenerateHandler != null) {
                close();
                regenerateHandler.accept(selected);
            }
        });

        zoomOutButton = new Button("-");
        zoomOutButton.setTooltip(new javafx.scene.control.Tooltip(I18n.get("menu.view.zoomOut")));
        zoomOutButton.setOnAction(event -> setDiagramZoomFactor(diagramZoomFactor - 0.15));
        zoomResetButton = new Button(I18n.get("snippets.ai.diagram.zoom.fit"));
        zoomResetButton.setTooltip(new javafx.scene.control.Tooltip(I18n.get("snippets.ai.diagram.zoom.fit")));
        zoomResetButton.setOnAction(event -> setDiagramZoomFactor(1.0));
        zoomInButton = new Button("+");
        zoomInButton.setTooltip(new javafx.scene.control.Tooltip(I18n.get("menu.view.zoomIn")));
        zoomInButton.setOnAction(event -> setDiagramZoomFactor(diagramZoomFactor + 0.15));
        zoomLabel = new Label("100%");
        zoomLabel.setMinWidth(Region.USE_PREF_SIZE);

        saveSvgButton = new Button(I18n.get("snippets.ai.diagram.saveSvg"));
        saveSvgButton.setOnAction(event -> saveCurrentSvg());
        savePngButton = new Button(I18n.get("snippets.ai.diagram.savePng"));
        savePngButton.setOnAction(event -> saveCurrentPng());
        copyImageButton = new Button(I18n.get("snippets.ai.diagram.copyImage"));
        copyImageButton.setOnAction(event -> copyCurrentPngToClipboard());

        Region toolbarSpacer = new Region();
        HBox toolbar = new HBox(
            8,
            regenerateButton,
            saveSvgButton,
            savePngButton,
            copyImageButton,
            toolbarSpacer,
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
        diagramView.getEngine().loadContent("");
        setDiagramImageVisible(false);
        regenerateButton.setDisable(diagram == null);
        if (diagram == null) {
            statusLabel.setText(I18n.get("snippets.ai.diagram.empty"));
            return;
        }
        String staleText = SnippetDiagramSupport.isStale(diagram, currentContent)
            ? I18n.get("snippets.ai.diagram.stale")
            : I18n.get("snippets.ai.diagram.current");
        statusLabel.setText(staleText + " " + I18n.get("snippets.ai.diagram.rendering"));

        renderTask = new Task<>() {
            @Override
            protected PlantUmlRenderService.RenderResult call() {
                return new PlantUmlRenderService().renderSvg(diagram.getPlantUmlSource());
            }
        };
        renderTask.setOnSucceeded(event -> {
            PlantUmlRenderService.RenderResult result = renderTask.getValue();
            if (result != null && result.success() && result.imagePath() != null) {
                renderedSvgPath = result.imagePath();
                diagramZoomFactor = 1.0;
                updateDiagramBaseSize(result.imagePath());
                setDiagramImageVisible(true);
                Platform.runLater(this::renderDiagramToFitViewport);
                statusLabel.setText(staleText);
            } else {
                renderedSvgPath = null;
                diagramView.getEngine().loadContent("");
                setDiagramImageVisible(false);
                statusLabel.setText(staleText + " " + I18n.get(
                    "snippets.ai.diagram.renderFailed",
                    result != null ? result.message() : ""));
            }
        });
        renderTask.setOnFailed(event -> {
            renderedSvgPath = null;
            diagramView.getEngine().loadContent("");
            setDiagramImageVisible(false);
            statusLabel.setText(staleText + " " + I18n.get("snippets.ai.diagram.renderFailed", ""));
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
        diagramView.getEngine().loadContent(buildDiagramHtml(renderedSvgPath, canvasWidth, canvasHeight, displayWidth, displayHeight));
        zoomLabel.setText(Math.round(diagramZoom * 100) + "%");
    }

    private String buildDiagramHtml(Path svgPath, double canvasWidth, double canvasHeight, double displayWidth, double displayHeight) {
        String svgUrl = escapeHtml(svgPath.toUri().toString());
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
                  background: #ffffff;
                }
                body {
                  display: flex;
                  align-items: center;
                  justify-content: center;
                }
                img {
                  display: block;
                  width: %.2fpx;
                  height: %.2fpx;
                }
              </style>
            </head>
            <body><img src="%s" alt=""></body>
            </html>
            """, canvasWidth, canvasHeight, displayWidth, displayHeight, svgUrl);
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
            statusLabel.setText(I18n.get("snippets.ai.diagram.export.saved", targetPath.toString()));
        } catch (IOException e) {
            statusLabel.setText(I18n.get("snippets.ai.diagram.export.failed", errorMessage(e)));
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
                    statusLabel.setText(I18n.get("snippets.ai.diagram.export.saved", targetPath.toString()));
                } catch (IOException e) {
                    statusLabel.setText(I18n.get("snippets.ai.diagram.export.failed", errorMessage(e)));
                }
            } else {
                statusLabel.setText(I18n.get(
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
                statusLabel.setText(I18n.get("snippets.ai.diagram.copy.ready"));
            } else {
                statusLabel.setText(I18n.get(
                    "snippets.ai.diagram.export.failed",
                    result != null ? result.message() : ""));
            }
        });
    }

    private void renderPngAsync(
        SnippetDiagram diagram,
        String runningStatus,
        Consumer<PlantUmlRenderService.RenderResult> resultHandler) {

        cancelExportTask();
        updateDiagramControls(false);
        statusLabel.setText(runningStatus);
        exportTask = new Task<>() {
            @Override
            protected PlantUmlRenderService.RenderResult call() {
                return new PlantUmlRenderService().renderPng(diagram.getPlantUmlSource());
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
            statusLabel.setText(I18n.get("snippets.ai.diagram.export.failed", ""));
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

    private void updateDiagramControls(boolean enabled) {
        boolean exportRunning = exportTask != null && exportTask.isRunning();
        boolean controlsEnabled = enabled && !exportRunning;
        zoomOutButton.setDisable(!enabled);
        zoomResetButton.setDisable(!enabled);
        zoomInButton.setDisable(!enabled);
        zoomLabel.setDisable(!enabled);
        saveSvgButton.setDisable(!controlsEnabled || renderedSvgPath == null);
        savePngButton.setDisable(!controlsEnabled);
        copyImageButton.setDisable(!controlsEnabled);
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

    private record SvgSize(double width, double height) {
    }
}
