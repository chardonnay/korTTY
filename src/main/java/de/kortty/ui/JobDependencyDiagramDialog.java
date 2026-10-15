package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.MermaidRenderService;
import de.kortty.core.SnippetDiagramSupport.DiagramColorMode;
import de.kortty.core.SystemThemeDetector;
import de.kortty.jobscheduler.JobDependencyGraph;
import de.kortty.jobscheduler.JobDependencyMermaidBuilder;
import de.kortty.jobscheduler.JobRunStatus;
import de.kortty.jobscheduler.JobSchedulerService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Non-modal window showing the JobScheduler's job dependencies as a Mermaid flowchart, colored by
 * each job's last run status. Vertical by default; the orientation choice is remembered.
 */
final class JobDependencyDiagramDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(JobDependencyDiagramDialog.class);
    private static final String I18N_PREFIX = "jobscheduler.dialog.diagram.";
    private static final double MIN_ZOOM = 0.25;
    private static final double MAX_ZOOM = 4.0;

    private final KorTTYApplication app;
    private final JobSchedulerService schedulerService;
    private final String focusJobId;
    private final WebView webView = new WebView();
    private final TextArea sourceFallback = new TextArea();
    private final Label placeholder = new Label();
    private final StackPane content = new StackPane();
    private final Label statusLabel = new Label();
    private final ToggleButton verticalButton = new ToggleButton(text("vertical"));
    private final ToggleButton horizontalButton = new ToggleButton(text("horizontal"));
    private final ComboBox<JobDependencyMermaidBuilder.Scope> scopeCombo = new ComboBox<>();
    private final ComboBox<DiagramColorMode> colorModeCombo = new ComboBox<>();
    private final HBox legend = new HBox(12);
    private final Button saveSvgButton = new Button(I18n.get("snippets.ai.diagram.saveSvg"));
    private final Button savePngButton = new Button(I18n.get("snippets.ai.diagram.savePng"));
    private final Button copyMermaidButton = new Button(I18n.get("snippets.ai.diagram.copyMermaid"));
    private final PauseTransition refreshDebounce = new PauseTransition(Duration.millis(400));
    private final Runnable schedulerListener = () -> Platform.runLater(refreshDebounce::playFromStart);

    private String currentSource;
    private String renderedSvg;
    private byte[] renderedPng;
    private long renderGeneration;
    private CompletableFuture<MermaidRenderService.RenderResult> pendingRender;
    private boolean renderedDark;

    JobDependencyDiagramDialog(KorTTYApplication app, Window owner, String focusJobId) {
        this.app = app;
        this.schedulerService = app.getJobSchedulerService();
        this.focusJobId = focusJobId;
        if (owner != null) {
            initOwner(owner);
        }
        initModality(Modality.NONE);
        setResizable(true);
        setTitle(text("title"));
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefSize(980, 760);
        getDialogPane().setContent(buildContent());
        refreshDebounce.setOnFinished(event -> render());
        schedulerService.addListener(schedulerListener);
        setOnHidden(event -> dispose());
        // The design's colors are only known once the pane is styled; re-render if Auto now resolves differently.
        setOnShown(event -> {
            if (isDark() != renderedDark) {
                render();
            }
        });
        render();
    }

    private BorderPane buildContent() {
        ToggleGroup orientation = new ToggleGroup();
        verticalButton.setToggleGroup(orientation);
        horizontalButton.setToggleGroup(orientation);
        boolean horizontal = loadHorizontalPreference();
        (horizontal ? horizontalButton : verticalButton).setSelected(true);
        orientation.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                // Keep exactly one orientation selected.
                oldToggle.setSelected(true);
                return;
            }
            saveHorizontalPreference(newToggle == horizontalButton);
            render();
        });
        verticalButton.setGraphic(iconLabel("↕"));
        horizontalButton.setGraphic(iconLabel("↔"));
        HBox orientationBox = new HBox(0, verticalButton, horizontalButton);

        scopeCombo.getItems().setAll(JobDependencyMermaidBuilder.Scope.values());
        if (focusJobId == null) {
            scopeCombo.getItems().remove(JobDependencyMermaidBuilder.Scope.NEIGHBORHOOD);
        }
        scopeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(JobDependencyMermaidBuilder.Scope scope) {
                return scope != null ? text("scope." + scope.name().toLowerCase(Locale.ROOT)) : "";
            }

            @Override
            public JobDependencyMermaidBuilder.Scope fromString(String string) {
                return null;
            }
        });
        scopeCombo.getSelectionModel().select(JobDependencyMermaidBuilder.Scope.ALL);
        scopeCombo.valueProperty().addListener((obs, oldScope, newScope) -> render());

        colorModeCombo.getItems().setAll(DiagramColorMode.values());
        colorModeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(DiagramColorMode mode) {
                return mode != null ? I18n.get("snippets.ai.diagram.darkMode." + mode.key()) : "";
            }

            @Override
            public DiagramColorMode fromString(String string) {
                return null;
            }
        });
        colorModeCombo.getSelectionModel().select(loadColorModePreference());
        colorModeCombo.setTooltip(new javafx.scene.control.Tooltip(text("darkMode.tooltip")));
        colorModeCombo.valueProperty().addListener((obs, oldMode, newMode) -> {
            saveColorModePreference(newMode);
            render();
        });

        Button refreshButton = new Button(I18n.get("jobscheduler.dialog.button.refresh"));
        refreshButton.setOnAction(event -> render());
        Button zoomOut = new Button("−");
        zoomOut.setOnAction(event -> setZoom(webView.getZoom() / 1.25));
        Button zoomReset = new Button(I18n.get("snippets.ai.diagram.zoom.actualSize"));
        zoomReset.setOnAction(event -> setZoom(1.0));
        Button zoomIn = new Button("+");
        zoomIn.setOnAction(event -> setZoom(webView.getZoom() * 1.25));
        saveSvgButton.setOnAction(event -> saveSvg());
        savePngButton.setOnAction(event -> savePng());
        copyMermaidButton.setOnAction(event -> copyMermaid());

        FlowPane toolbar = new FlowPane(8, 8,
            new Label(text("orientation")), orientationBox,
            new Label(text("scope")), scopeCombo,
            new Label(I18n.get("snippets.ai.diagram.darkMode") + ":"), colorModeCombo,
            refreshButton, zoomOut, zoomReset, zoomIn,
            saveSvgButton, savePngButton, copyMermaidButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        webView.setContextMenuEnabled(false);
        webView.getEngine().setJavaScriptEnabled(false);
        sourceFallback.setEditable(false);
        sourceFallback.setWrapText(false);
        placeholder.setWrapText(true);
        content.getChildren().setAll(placeholder);
        VBox.setVgrow(content, Priority.ALWAYS);

        statusLabel.setWrapText(true);
        legend.setAlignment(Pos.CENTER_LEFT);
        VBox bottom = new VBox(6, legend, statusLabel);
        bottom.setPadding(new Insets(8, 0, 0, 0));

        BorderPane root = new BorderPane();
        root.setTop(toolbar);
        BorderPane.setMargin(toolbar, new Insets(0, 0, 8, 0));
        root.setCenter(content);
        root.setBottom(bottom);
        root.setPadding(new Insets(10));
        return root;
    }

    private void updateLegend(boolean dark) {
        legend.getChildren().setAll(new Label(text("legend")));
        legend.getChildren().add(swatch(JobRunStatus.SUCCESS, dark, I18n.get("jobscheduler.dialog.status.success")));
        legend.getChildren().add(swatch(JobRunStatus.FAILED, dark, I18n.get("jobscheduler.dialog.status.failed")));
        legend.getChildren().add(swatch(JobRunStatus.BLOCKED, dark, I18n.get("jobscheduler.dialog.status.blocked")));
        legend.getChildren().add(swatch(JobRunStatus.CANCELLED, dark, I18n.get("jobscheduler.dialog.status.cancelled")));
        legend.getChildren().add(swatch(JobRunStatus.RUNNING, dark, I18n.get("jobscheduler.dialog.status.running")));
        legend.getChildren().add(swatch(null, dark, text("neverRan")));
        legend.getChildren().add(new Label(text("legend.edges")));
    }

    private static HBox swatch(JobRunStatus status, boolean dark, String label) {
        JobDependencyMermaidBuilder.StatusColors colors = JobDependencyMermaidBuilder.colors(status, dark);
        String fill = colors.fill();
        String stroke = colors.stroke();
        Region box = new Region();
        box.setMinSize(14, 14);
        box.setPrefSize(14, 14);
        box.setStyle("-fx-background-color: " + fill + "; -fx-border-color: " + stroke + "; -fx-border-width: 2;");
        HBox item = new HBox(4, box, new Label(label));
        item.setAlignment(Pos.CENTER_LEFT);
        return item;
    }

    private void render() {
        long generation = ++renderGeneration;
        boolean dark = isDark();
        renderedDark = dark;
        updateLegend(dark);
        webView.setPageFill(javafx.scene.paint.Color.web(canvasColor(dark)));
        if (pendingRender != null) {
            pendingRender.cancel(true);
            pendingRender = null;
        }
        JobDependencyGraph graph = schedulerService.getDependencyGraph();
        JobDependencyMermaidBuilder.Scope scope = scopeCombo.getValue() != null
            ? scopeCombo.getValue()
            : JobDependencyMermaidBuilder.Scope.ALL;
        currentSource = JobDependencyMermaidBuilder.build(
            graph,
            horizontalButton.isSelected() ? JobDependencyMermaidBuilder.Direction.LR : JobDependencyMermaidBuilder.Direction.TB,
            scope,
            focusJobId,
            schedulerService::findLastFinishedStatus,
            schedulerService::isJobActive,
            labels(),
            dark);
        renderedSvg = null;
        renderedPng = null;
        updateExportButtons();
        if (currentSource == null) {
            placeholder.setText(scope == JobDependencyMermaidBuilder.Scope.ALL ? text("empty") : text("emptyScope"));
            content.getChildren().setAll(placeholder);
            statusLabel.setText("");
            return;
        }
        long edgeCount = graph.jobs().stream().mapToLong(job -> graph.predecessorsOf(job.getId()).size()).sum();
        if (edgeCount == 0 && scope == JobDependencyMermaidBuilder.Scope.ALL) {
            statusLabel.setText(text("noDependencies"));
        } else {
            statusLabel.setText(text("rendering"));
        }
        if (!MermaidRenderService.isBundledAvailable()) {
            showSourceFallback(text("notBundled"));
            return;
        }
        String source = currentSource;
        CompletableFuture<MermaidRenderService.RenderResult> future = MermaidRenderService.render(
            new MermaidRenderService.RenderRequest(
                source,
                dark ? MermaidRenderService.Theme.DARK : MermaidRenderService.Theme.LIGHT,
                canvasColor(dark),
                true,
                null));
        pendingRender = future;
        future.whenComplete((result, error) -> Platform.runLater(() -> {
            if (generation != renderGeneration) {
                return;
            }
            pendingRender = null;
            if (error == null && result != null && result.success() && !result.svg().isBlank()) {
                renderedSvg = result.svg();
                renderedPng = result.png();
                webView.getEngine().loadContent(svgHtml(result.svg(), canvasColor(dark)));
                content.getChildren().setAll(webView);
                if (edgeCount > 0 || scope != JobDependencyMermaidBuilder.Scope.ALL) {
                    statusLabel.setText("");
                }
                updateExportButtons();
                return;
            }
            String message = error != null ? error.getMessage() : result != null ? result.message() : null;
            showSourceFallback(text("renderFailed", message != null && !message.isBlank() ? message : "?"));
        }));
    }

    private JobDependencyMermaidBuilder.Labels labels() {
        return new JobDependencyMermaidBuilder.Labels(
            text("edge.gate"),
            text("edge.chain"),
            text("neverRan"),
            I18n.get("jobscheduler.dialog.status.running"),
            text("disabled"),
            (JobRunStatus status) -> I18n.get("jobscheduler.dialog.status." + status.name().toLowerCase(Locale.ROOT)));
    }

    private void showSourceFallback(String message) {
        statusLabel.setText(message);
        sourceFallback.setText(currentSource != null ? currentSource : "");
        content.getChildren().setAll(sourceFallback);
        updateExportButtons();
    }

    private static String canvasColor(boolean dark) {
        return dark ? de.kortty.core.SnippetDiagramSupport.DARK_BACKGROUND_COLOR : "#FFFFFF";
    }

    /** Light or dark as chosen; Auto follows korTTY's design, or the system before the pane is styled. */
    private boolean isDark() {
        DiagramColorMode mode = colorModeCombo.getValue() != null ? colorModeCombo.getValue() : DiagramColorMode.AUTO;
        if (mode != DiagramColorMode.AUTO) {
            return mode == DiagramColorMode.DARK;
        }
        javafx.scene.layout.Background background = getDialogPane().getBackground();
        if (background != null) {
            for (javafx.scene.layout.BackgroundFill fill : background.getFills()) {
                if (fill.getFill() instanceof javafx.scene.paint.Color color && color.getOpacity() > 0.5) {
                    double luminance = 0.2126 * color.getRed() + 0.7152 * color.getGreen() + 0.0722 * color.getBlue();
                    return luminance < 0.5;
                }
            }
        }
        return SystemThemeDetector.isSystemDarkMode();
    }

    private DiagramColorMode loadColorModePreference() {
        try {
            return DiagramColorMode.fromKey(app.getGlobalSettingsManager().getSettings().getJobSchedulerDependencyDiagramColorMode());
        } catch (Exception e) {
            logger.debug("Could not read the dependency diagram color mode", e);
            return DiagramColorMode.AUTO;
        }
    }

    private void saveColorModePreference(DiagramColorMode mode) {
        try {
            app.getGlobalSettingsManager().getSettings().setJobSchedulerDependencyDiagramColorMode(
                (mode != null ? mode : DiagramColorMode.AUTO).key());
            app.getGlobalSettingsManager().save();
        } catch (Exception e) {
            logger.debug("Could not save the dependency diagram color mode", e);
        }
    }

    /** The rendered SVG on a canvas of the given color at its natural size, so WebView zoom and scrolling work. */
    private static String svgHtml(String svg, String canvas) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
            + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
            + "style-src 'unsafe-inline'; object-src 'none'; frame-src 'none'; connect-src 'none'\">"
            + "<style>html,body{margin:0;padding:12px;background:" + canvas + ";}"
            + "svg{display:block;margin:0 auto;max-width:none !important;}</style></head><body>"
            + svg
            + "</body></html>";
    }

    private void setZoom(double zoom) {
        webView.setZoom(Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom)));
    }

    private void updateExportButtons() {
        saveSvgButton.setDisable(renderedSvg == null);
        savePngButton.setDisable(renderedPng == null || renderedPng.length == 0);
        copyMermaidButton.setDisable(currentSource == null);
    }

    private void saveSvg() {
        if (renderedSvg == null) {
            return;
        }
        Path target = chooseSaveFile("svg");
        if (target == null) {
            return;
        }
        try {
            Files.writeString(target, renderedSvg, StandardCharsets.UTF_8);
            statusLabel.setText(I18n.get("snippets.ai.diagram.export.saved", target.toString()));
        } catch (Exception e) {
            statusLabel.setText(I18n.get("snippets.ai.diagram.export.failed", String.valueOf(e.getMessage())));
        }
    }

    private void savePng() {
        if (renderedPng == null || renderedPng.length == 0) {
            return;
        }
        Path target = chooseSaveFile("png");
        if (target == null) {
            return;
        }
        try {
            Files.write(target, renderedPng);
            statusLabel.setText(I18n.get("snippets.ai.diagram.export.saved", target.toString()));
        } catch (Exception e) {
            statusLabel.setText(I18n.get("snippets.ai.diagram.export.failed", String.valueOf(e.getMessage())));
        }
    }

    private void copyMermaid() {
        if (currentSource == null) {
            return;
        }
        de.kortty.core.KorttyClipboard.setText(currentSource);
        statusLabel.setText(I18n.get("snippets.ai.diagram.copyMermaid.ready"));
    }

    private Path chooseSaveFile(String extension) {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName("job-dependencies." + extension);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            extension.toUpperCase(Locale.ROOT) + " (*." + extension + ")", "*." + extension));
        Window window = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
        File file = chooser.showSaveDialog(window);
        if (file == null) {
            return null;
        }
        Path path = file.toPath();
        String name = path.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith("." + extension) ? path : path.resolveSibling(name + "." + extension);
    }

    private boolean loadHorizontalPreference() {
        try {
            return app.getGlobalSettingsManager().getSettings().isJobSchedulerDependencyDiagramHorizontal();
        } catch (Exception e) {
            logger.debug("Could not read the dependency diagram orientation", e);
            return false;
        }
    }

    private void saveHorizontalPreference(boolean horizontal) {
        try {
            app.getGlobalSettingsManager().getSettings().setJobSchedulerDependencyDiagramHorizontal(horizontal);
            app.getGlobalSettingsManager().save();
        } catch (Exception e) {
            logger.debug("Could not save the dependency diagram orientation", e);
        }
    }

    private void dispose() {
        schedulerService.removeListener(schedulerListener);
        refreshDebounce.stop();
        renderGeneration++;
        if (pendingRender != null) {
            pendingRender.cancel(true);
            pendingRender = null;
        }
        webView.getEngine().loadContent("");
    }

    private static Label iconLabel(String symbol) {
        Label label = new Label(symbol);
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    private static String text(String suffix, Object... args) {
        return I18n.get(I18N_PREFIX + suffix, args);
    }
}
