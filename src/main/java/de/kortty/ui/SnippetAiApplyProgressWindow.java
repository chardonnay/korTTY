package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiTokenUsage;
import de.kortty.core.SnippetAiWorkflowSupport;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Narrow companion window for the staged Full-code-analysis apply workflow. It stays docked beside
 * the analysis window and renders only provider-reported token usage; missing usage is never guessed.
 */
final class SnippetAiApplyProgressWindow {

    private static final double DEFAULT_WIDTH = 360;
    private static final double MIN_HEIGHT = 420;
    private static final double DOCK_GAP = 8;

    private final Window anchor;
    private final Stage stage = new Stage();
    private final ProgressBar improvementsProgressBar = new ProgressBar(0);
    private final Label improvementsProgressLabel = new Label();
    private final VBox improvementsProgressGroup = new VBox(4);
    private final ProgressBar hardeningProgressBar = new ProgressBar(0);
    private final Label hardeningProgressLabel = new Label();
    private final VBox hardeningProgressGroup = new VBox(4);
    private final Label elapsedLabel = new Label();
    private final Label tokenLabel = new Label();
    private final Label currentStepLabel = new Label();
    private final VBox improvementRows = new VBox(6);
    private final VBox hardeningRows = new VBox(6);
    private final Label improvementsHeading = sectionHeading("snippets.ai.analysis.progress.improvements");
    private final Label hardeningHeading = sectionHeading("snippets.ai.analysis.progress.hardening");
    private final Map<WorkKey, WorkRow> rows = new LinkedHashMap<>();
    private final Timeline elapsedTimeline;
    private final ChangeListener<Number> dockListener = (observable, oldValue, newValue) -> positionDocked();

    private long startedNanos;
    private boolean disposed;

    SnippetAiApplyProgressWindow(
            Window anchor,
            List<SnippetAiWorkflowSupport.ImprovementApplyProgress> plan) {
        this.anchor = anchor;

        List<SnippetAiWorkflowSupport.ImprovementApplyProgress> safePlan = plan != null ? plan : List.of();
        for (SnippetAiWorkflowSupport.ImprovementApplyProgress progress : safePlan) {
            registerRows(progress);
        }
        refreshSectionVisibility();

        configureProgressGroup(
            improvementsProgressGroup,
            improvementsProgressBar,
            improvementsProgressLabel,
            "snippets.ai.analysis.progress.improvements",
            "snippet-analysis-progress-improvements");
        configureProgressGroup(
            hardeningProgressGroup,
            hardeningProgressBar,
            hardeningProgressLabel,
            "snippets.ai.analysis.progress.hardening",
            "snippet-analysis-progress-hardening");
        elapsedLabel.setStyle("-fx-opacity: 0.82;");
        tokenLabel.setStyle("-fx-opacity: 0.82;");
        currentStepLabel.setWrapText(true);
        currentStepLabel.setStyle("-fx-font-weight: bold; -fx-padding: 6 0 2 0;");

        Region metricsSpacer = new Region();
        HBox.setHgrow(metricsSpacer, Priority.ALWAYS);
        HBox metrics = new HBox(8, elapsedLabel, metricsSpacer, tokenLabel);
        metrics.setAlignment(Pos.CENTER_LEFT);

        VBox list = new VBox(8,
            improvementsHeading,
            improvementRows,
            hardeningHeading,
            hardeningRows);
        list.setPadding(new Insets(2, 4, 8, 2));
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox root = new VBox(8,
            improvementsProgressGroup,
            hardeningProgressGroup,
            metrics,
            currentStepLabel,
            new Separator(),
            scroll);
        root.setPadding(new Insets(12));

        Scene scene = new Scene(root, DEFAULT_WIDTH, MIN_HEIGHT);
        applyTheme(scene);
        stage.setScene(scene);
        stage.setTitle(I18n.get("snippets.ai.analysis.progress.title"));
        stage.setMinWidth(320);
        stage.setMinHeight(MIN_HEIGHT);
        stage.initModality(Modality.NONE);
        if (anchor != null) {
            stage.initOwner(anchor);
            installDockListeners();
        }
        stage.setOnHidden(event -> dispose());

        elapsedTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> refreshElapsed()));
        elapsedTimeline.setCycleCount(Timeline.INDEFINITE);
        refreshProgress();
        refreshElapsed();
        refreshTokens(null);
        currentStepLabel.setText(I18n.get("snippets.ai.analysis.progress.preparing"));
    }

    void show() {
        if (disposed || stage.isShowing()) {
            return;
        }
        startedNanos = System.nanoTime();
        elapsedTimeline.playFromStart();
        stage.show();
        positionDocked();
    }

    void accept(SnippetAiWorkflowSupport.ImprovementApplyProgress progress) {
        if (progress == null || disposed) {
            return;
        }
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> accept(progress));
            return;
        }
        registerRows(progress);
        for (SnippetAiWorkflowSupport.ImprovementApplyWorkItem item : progress.workItems()) {
            WorkRow row = rows.get(new WorkKey(progress.stage(), item.id()));
            if (row != null) {
                row.setState(progress.state());
            }
        }
        refreshProgress();
        refreshTokens(progress.cumulativeUsage());
        if (progress.state() == SnippetAiWorkflowSupport.ImprovementApplyProgressState.RUNNING
                || progress.state() == SnippetAiWorkflowSupport.ImprovementApplyProgressState.RETRYING) {
            String step = progress.detail().isBlank()
                ? phaseSummary(progress)
                : progress.detail();
            String key = progress.state() == SnippetAiWorkflowSupport.ImprovementApplyProgressState.RETRYING
                ? "snippets.ai.analysis.progress.currentRetry"
                : "snippets.ai.analysis.progress.current";
            currentStepLabel.setText(I18n.get(key, step));
        } else if (progress.state() == SnippetAiWorkflowSupport.ImprovementApplyProgressState.FAILED) {
            currentStepLabel.setText(I18n.get("snippets.ai.analysis.progress.failed"));
        }
    }

    void markSucceeded() {
        runOnFx(() -> {
            elapsedTimeline.stop();
            rows.values().forEach(row -> row.setState(
                SnippetAiWorkflowSupport.ImprovementApplyProgressState.COMPLETED));
            refreshProgress();
            refreshElapsed();
            currentStepLabel.setText(I18n.get("snippets.ai.analysis.progress.complete"));
        });
    }

    void markFailed() {
        runOnFx(() -> {
            elapsedTimeline.stop();
            refreshElapsed();
            currentStepLabel.setText(I18n.get("snippets.ai.analysis.progress.failed"));
        });
    }

    void markCancelled() {
        runOnFx(() -> {
            elapsedTimeline.stop();
            refreshElapsed();
            currentStepLabel.setText(I18n.get("snippets.ai.analysis.progress.cancelled"));
        });
    }

    void close() {
        runOnFx(() -> {
            elapsedTimeline.stop();
            if (stage.isShowing()) {
                stage.close();
            } else {
                dispose();
            }
        });
    }

    private void registerRows(SnippetAiWorkflowSupport.ImprovementApplyProgress progress) {
        if (progress == null) {
            return;
        }
        VBox target = progress.phase() == SnippetAiWorkflowSupport.ImprovementApplyPhase.ANALYSIS_ITEMS
            ? improvementRows
            : hardeningRows;
        for (SnippetAiWorkflowSupport.ImprovementApplyWorkItem item : progress.workItems()) {
            WorkKey key = new WorkKey(progress.stage(), item.id());
            if (rows.containsKey(key)) {
                continue;
            }
            WorkRow row = new WorkRow(item, progress.phase());
            rows.put(key, row);
            target.getChildren().add(row.root());
        }
        refreshSectionVisibility();
    }

    private void refreshSectionVisibility() {
        setVisibleManaged(improvementsHeading, !improvementRows.getChildren().isEmpty());
        setVisibleManaged(improvementRows, !improvementRows.getChildren().isEmpty());
        setVisibleManaged(hardeningHeading, !hardeningRows.getChildren().isEmpty());
        setVisibleManaged(hardeningRows, !hardeningRows.getChildren().isEmpty());
    }

    private void refreshProgress() {
        refreshProgressGroup(
            improvementsProgressGroup,
            improvementsProgressBar,
            improvementsProgressLabel,
            true);
        refreshProgressGroup(
            hardeningProgressGroup,
            hardeningProgressBar,
            hardeningProgressLabel,
            false);
    }

    private void refreshProgressGroup(
            VBox group,
            ProgressBar bar,
            Label label,
            boolean improvements) {

        List<WorkRow> matchingRows = rows.values().stream()
            .filter(row -> improvements
                == (row.phase() == SnippetAiWorkflowSupport.ImprovementApplyPhase.ANALYSIS_ITEMS))
            .toList();
        int total = matchingRows.size();
        int completed = (int) matchingRows.stream().filter(WorkRow::isCompleted).count();
        bar.setProgress(total > 0 ? (double) completed / total : 0.0);
        label.setText(I18n.get("snippets.ai.analysis.progress.overall", completed, total));
        setVisibleManaged(group, total > 0);
    }

    private void refreshElapsed() {
        long seconds = startedNanos > 0L
            ? Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000_000L)
            : 0L;
        long hours = seconds / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        long remaining = seconds % 60L;
        String formatted = hours > 0
            ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remaining)
            : String.format(Locale.ROOT, "%02d:%02d", minutes, remaining);
        elapsedLabel.setText(I18n.get("snippets.ai.analysis.progress.elapsed", formatted));
    }

    private void refreshTokens(AiTokenUsage usage) {
        String value = usage != null
            ? NumberFormat.getIntegerInstance().format(usage.totalTokens())
            : I18n.get("snippets.ai.analysis.progress.tokensUnavailable");
        tokenLabel.setText(I18n.get("snippets.ai.analysis.progress.tokens", value));
    }

    private void installDockListeners() {
        anchor.xProperty().addListener(dockListener);
        anchor.yProperty().addListener(dockListener);
        anchor.widthProperty().addListener(dockListener);
        anchor.heightProperty().addListener(dockListener);
    }

    private void positionDocked() {
        if (disposed || anchor == null || !stage.isShowing()) {
            return;
        }
        Rectangle2D bounds = Screen.getScreensForRectangle(
                anchor.getX(), anchor.getY(), Math.max(1, anchor.getWidth()), Math.max(1, anchor.getHeight()))
            .stream()
            .findFirst()
            .orElse(Screen.getPrimary())
            .getVisualBounds();
        double desiredHeight = Math.max(MIN_HEIGHT, Math.min(anchor.getHeight(), bounds.getHeight()));
        stage.setHeight(desiredHeight);
        double y = clamp(anchor.getY(), bounds.getMinY(), bounds.getMaxY() - desiredHeight);
        double rightX = anchor.getX() + anchor.getWidth() + DOCK_GAP;
        double leftX = anchor.getX() - stage.getWidth() - DOCK_GAP;
        double x;
        if (rightX + stage.getWidth() <= bounds.getMaxX()) {
            x = rightX;
        } else if (leftX >= bounds.getMinX()) {
            x = leftX;
        } else {
            x = bounds.getMaxX() - stage.getWidth();
        }
        stage.setX(clamp(x, bounds.getMinX(), bounds.getMaxX() - stage.getWidth()));
        stage.setY(y);
    }

    private void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        elapsedTimeline.stop();
        if (anchor != null) {
            anchor.xProperty().removeListener(dockListener);
            anchor.yProperty().removeListener(dockListener);
            anchor.widthProperty().removeListener(dockListener);
            anchor.heightProperty().removeListener(dockListener);
        }
    }

    private static void applyTheme(Scene scene) {
        var baseCss = SnippetAiApplyProgressWindow.class.getResource("/styles/terminal.css");
        if (baseCss != null) {
            scene.getStylesheets().add(baseCss.toExternalForm());
        }
        try {
            String dynamic = ThemeCssSupport.getDynamicStylesheetUrl(
                ThemeCssSupport.resolveThemeColors(KorTTYApplication.getInstance()));
            if (dynamic != null) {
                scene.getStylesheets().add(dynamic);
            }
        } catch (RuntimeException ignored) {
            // The base stylesheet remains available in isolated JavaFX tests without an application instance.
        }
        AppDesignStyleSupport.applyToScene(scene);
    }

    private static Label sectionHeading(String key) {
        Label label = new Label(I18n.get(key));
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 5 0 1 0;");
        return label;
    }

    private static void configureProgressGroup(
            VBox group,
            ProgressBar bar,
            Label valueLabel,
            String headingKey,
            String barId) {

        Label heading = new Label(I18n.get(headingKey));
        heading.setStyle("-fx-font-weight: bold;");
        valueLabel.setStyle("-fx-font-size: 10px; -fx-opacity: 0.78;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, heading, spacer, valueLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        bar.setId(barId);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setMinHeight(10);
        bar.setPrefHeight(10);
        group.getChildren().setAll(header, bar);
    }

    private static String localizedCategory(
            SnippetAiWorkflowSupport.ImprovementApplyWorkItem item,
            SnippetAiWorkflowSupport.ImprovementApplyPhase phase) {
        if (phase == SnippetAiWorkflowSupport.ImprovementApplyPhase.HARDENING) {
            return I18n.get("snippets.ai.analysis.progress.category.hardening");
        }
        if (phase == SnippetAiWorkflowSupport.ImprovementApplyPhase.INPUT_HARDENING) {
            return I18n.get("snippets.ai.analysis.progress.category.inputHardening");
        }
        return switch (item.category()) {
            case "security" -> I18n.get("snippets.ai.analysis.section.security");
            case "optimization" -> I18n.get("snippets.ai.analysis.section.optimization");
            case "design" -> I18n.get("snippets.ai.analysis.section.design");
            case "dependencies" -> I18n.get("snippets.ai.analysis.section.dependencies");
            default -> I18n.get("snippets.ai.analysis.progress.improvements");
        };
    }

    private static String analysisCategory(
            SnippetAiWorkflowSupport.ImprovementApplyWorkItem item,
            SnippetAiWorkflowSupport.ImprovementApplyPhase phase) {
        if (phase != SnippetAiWorkflowSupport.ImprovementApplyPhase.ANALYSIS_ITEMS) {
            return null;
        }
        return switch (item.category()) {
            case "security", "optimization", "design", "dependencies" -> item.category();
            default -> "design";
        };
    }

    private static SVGPath categoryIcon(
            SnippetAiWorkflowSupport.ImprovementApplyWorkItem item,
            SnippetAiWorkflowSupport.ImprovementApplyPhase phase) {
        String category = analysisCategory(item, phase);
        if (category == null) {
            return null;
        }
        SVGPath icon = new SVGPath();
        icon.setContent(SnippetAiDialogSupport.sectionIconPath(category));
        icon.setFill(Color.web(SnippetAiDialogSupport.sectionColor(category)));
        icon.setAccessibleText(localizedCategory(item, phase));
        icon.setUserData(item.id());
        return icon;
    }

    private static String phaseSummary(SnippetAiWorkflowSupport.ImprovementApplyProgress progress) {
        return switch (progress.phase()) {
            case HARDENING -> I18n.get(
                "snippets.ai.analysis.fix.progress.hardening",
                progress.firstRequirement(),
                progress.lastRequirement(),
                progress.phaseRequirementCount());
            case INPUT_HARDENING -> I18n.get(
                "snippets.ai.analysis.fix.progress.inputHardening",
                progress.firstRequirement(),
                progress.lastRequirement(),
                progress.phaseRequirementCount());
            case ANALYSIS_ITEMS -> I18n.get("snippets.ai.analysis.progress.improvements");
        };
    }

    private static void setVisibleManaged(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(value, Math.max(minimum, maximum)));
    }

    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private record WorkKey(int stage, String id) {
    }

    private static final class WorkRow {
        private final HBox root;
        private final Label status = new Label("○");
        private final SnippetAiWorkflowSupport.ImprovementApplyPhase phase;
        private SnippetAiWorkflowSupport.ImprovementApplyProgressState state =
            SnippetAiWorkflowSupport.ImprovementApplyProgressState.PENDING;

        WorkRow(
                SnippetAiWorkflowSupport.ImprovementApplyWorkItem item,
                SnippetAiWorkflowSupport.ImprovementApplyPhase phase) {
            this.phase = phase;
            Label identifier = new Label(item.id());
            identifier.setStyle("-fx-font-size: 10px; -fx-opacity: 0.72;");
            HBox identifierLine = new HBox(5, identifier);
            identifierLine.setAlignment(Pos.CENTER_LEFT);
            SVGPath categoryIcon = categoryIcon(item, phase);
            if (categoryIcon != null) {
                identifierLine.getChildren().add(categoryIcon);
            }
            Label text = new Label(item.label());
            text.setWrapText(true);
            VBox copy = new VBox(1, identifierLine, text);
            HBox.setHgrow(copy, Priority.ALWAYS);
            status.setMinWidth(24);
            status.setAlignment(Pos.CENTER);
            root = new HBox(8, copy, status);
            root.setUserData(item.id());
            root.setAlignment(Pos.CENTER_LEFT);
            root.setPadding(new Insets(6, 7, 6, 8));
            root.setStyle("-fx-border-color: rgba(128,128,128,0.28); -fx-border-radius: 4; -fx-background-radius: 4;");
            setState(state);
        }

        HBox root() {
            return root;
        }

        boolean isCompleted() {
            return state == SnippetAiWorkflowSupport.ImprovementApplyProgressState.COMPLETED;
        }

        SnippetAiWorkflowSupport.ImprovementApplyPhase phase() {
            return phase;
        }

        void setState(SnippetAiWorkflowSupport.ImprovementApplyProgressState next) {
            state = next != null ? next : SnippetAiWorkflowSupport.ImprovementApplyProgressState.PENDING;
            String symbol;
            String color;
            String tooltipKey;
            switch (state) {
                case RUNNING -> {
                    symbol = "●";
                    color = "#38bdf8";
                    tooltipKey = "snippets.ai.analysis.progress.status.running";
                }
                case RETRYING -> {
                    symbol = "↻";
                    color = "#f59e0b";
                    tooltipKey = "snippets.ai.analysis.progress.status.retrying";
                }
                case COMPLETED -> {
                    symbol = "✓";
                    color = "#22c55e";
                    tooltipKey = "snippets.ai.analysis.progress.status.completed";
                }
                case FAILED -> {
                    symbol = "✕";
                    color = "#ef4444";
                    tooltipKey = "snippets.ai.analysis.progress.status.failed";
                }
                default -> {
                    symbol = "○";
                    color = "#94a3b8";
                    tooltipKey = "snippets.ai.analysis.progress.status.pending";
                }
            }
            status.setText(symbol);
            status.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
            status.setTooltip(new Tooltip(I18n.get(tooltipKey)));
        }
    }
}
