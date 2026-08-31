package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiTokenUsage;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.SnippetAiWorkflowSupport;
import de.kortty.core.WorkflowScriptSupport;
import de.kortty.model.GlobalSettings;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Narrow companion window for the staged Full-code-analysis apply workflow. A
 * {@link WindowDockGroup} keeps it beside the analysis window; this class only reports progress.
 *
 * <p>Token usage is rendered exactly as the provider reported it and never guessed — a run against
 * a backend that reports nothing says so rather than showing an estimate that looks like a fact.</p>
 *
 * <p>When the run ends the window does not close itself. It turns into a summary of what was done,
 * how long it took and what it cost, which is only useful if it is still on screen while the
 * reviewer reads the diff next to it.</p>
 */
final class SnippetAiApplyProgressWindow {

    private static final double DEFAULT_WIDTH = 360;
    private static final double MIN_HEIGHT = 420;
    /** Below this a stored width is junk rather than a deliberately tiny window. */
    private static final double MIN_USABLE_WIDTH = 200;
    private static final int MAX_DESCRIPTION_LINES = 3;
    private static final double DESCRIPTION_LINE_HEIGHT_FACTOR = 1.35;

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
    private final String profileName;
    private final VBox summaryBox = new VBox(3);
    private final Label summaryTitle = new Label();
    private final Label summaryDuration = new Label();
    private final Label summaryTokens = new Label();
    private final Label summaryProfile = new Label();
    private final Label summaryItems = new Label();
    private final Label summaryRetries = new Label();
    private final Button reopenPreviewButton = new Button(I18n.get("snippets.ai.analysis.progress.reopenPreview"));
    private final Button tileButton = new Button(I18n.get("snippets.ai.analysis.progress.dock.tile"));
    private final Button copySummaryButton = new Button(I18n.get("snippets.ai.analysis.progress.summary.copy"));
    private final HBox actionBar = new HBox(6);

    private long startedNanos;
    private long finishedSeconds = -1L;
    private AiTokenUsage lastUsage;
    private int retries;
    private String lastStatusKey;
    private boolean disposed;

    SnippetAiApplyProgressWindow(
            Window anchor,
            List<SnippetAiWorkflowSupport.ImprovementApplyProgress> plan,
            String profileName) {
        this.profileName = profileName;

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

        configureSummary();
        buildActionBar();

        VBox root = new VBox(8,
            improvementsProgressGroup,
            hardeningProgressGroup,
            metrics,
            currentStepLabel,
            summaryBox,
            new Separator(),
            scroll,
            actionBar);
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
    }

    /** The window itself, so the host can hand it to a {@link WindowDockGroup}. */
    Stage stage() {
        return stage;
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
        List<SnippetAiWorkflowSupport.ImprovementApplyWorkItem> items = progress.workItems();
        for (int index = 0; index < items.size(); index++) {
            WorkRow row = rows.get(new WorkKey(progress.stage(), rowKeyId(index, items.get(index))));
            if (row != null) {
                row.setState(progress.state());
            }
        }
        refreshProgress();
        refreshTokens(progress.cumulativeUsage());
        if (progress.state() == SnippetAiWorkflowSupport.ImprovementApplyProgressState.RETRYING) {
            retries++;
        }
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
            freezeElapsed();
            currentStepLabel.setText(I18n.get("snippets.ai.analysis.progress.complete"));
            showSummary("snippets.ai.analysis.progress.complete");
        });
    }

    void markFailed() {
        runOnFx(() -> {
            elapsedTimeline.stop();
            freezeElapsed();
            String key = failedStatusKey(rows.values().stream().map(WorkRow::state).toList());
            currentStepLabel.setText(I18n.get(key));
            showSummary(key);
        });
    }

    /**
     * The generic failure text points at "the marked work item", but a run can also fail after
     * every stage completed — the final cumulative verification or the degenerate-replacement
     * guard rejected the combined result. An all-green checklist with that text reads like a
     * contradiction, so the header names the final verification instead.
     */
    static String failedStatusKey(
            java.util.Collection<SnippetAiWorkflowSupport.ImprovementApplyProgressState> rowStates) {
        boolean anyFailed = rowStates.stream()
            .anyMatch(state -> state == SnippetAiWorkflowSupport.ImprovementApplyProgressState.FAILED);
        boolean allCompleted = !rowStates.isEmpty() && rowStates.stream()
            .allMatch(state -> state == SnippetAiWorkflowSupport.ImprovementApplyProgressState.COMPLETED);
        return !anyFailed && allCompleted
            ? "snippets.ai.analysis.progress.failedFinalVerification"
            : "snippets.ai.analysis.progress.failed";
    }

    void markCancelled() {
        runOnFx(() -> {
            elapsedTimeline.stop();
            freezeElapsed();
            currentStepLabel.setText(I18n.get("snippets.ai.analysis.progress.cancelled"));
            showSummary("snippets.ai.analysis.progress.cancelled");
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

    /**
     * Re-opens the change preview after it was closed. The button only appears when the host offers
     * one — closing the preview by accident should not mean re-running the whole analysis.
     */
    void setReopenPreviewHandler(Runnable handler) {
        runOnFx(() -> {
            reopenPreviewButton.setOnAction(handler == null ? null : event -> handler.run());
            setVisibleManaged(reopenPreviewButton, handler != null);
            refreshActionBar();
        });
    }

    /** Enables the "arrange windows" action, which re-tiles the docked trio. */
    void setTileHandler(Runnable handler) {
        runOnFx(() -> {
            tileButton.setOnAction(handler == null ? null : event -> handler.run());
            setVisibleManaged(tileButton, handler != null);
            refreshActionBar();
        });
    }

    private void configureSummary() {
        summaryTitle.setStyle("-fx-font-weight: bold; -fx-padding: 4 0 2 0;");
        summaryTitle.setWrapText(true);
        for (Label detail : List.of(summaryDuration, summaryTokens, summaryProfile,
                summaryItems, summaryRetries)) {
            detail.setStyle("-fx-font-size: 0.9231em; -fx-opacity: 0.85;");
            detail.setWrapText(true);
        }
        summaryBox.getChildren().setAll(
            summaryTitle, summaryDuration, summaryTokens, summaryProfile, summaryItems, summaryRetries);
        summaryBox.setStyle("-fx-border-color: rgba(128,128,128,0.28); -fx-border-radius: 6;"
            + " -fx-background-radius: 6; -fx-padding: 8 10 9 10;");
        setVisibleManaged(summaryBox, false);
    }

    private void buildActionBar() {
        copySummaryButton.setOnAction(event -> copySummary());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        actionBar.getChildren().setAll(tileButton, spacer, reopenPreviewButton, copySummaryButton);
        actionBar.setAlignment(Pos.CENTER_LEFT);
        setVisibleManaged(reopenPreviewButton, false);
        setVisibleManaged(tileButton, false);
        setVisibleManaged(copySummaryButton, false);
        refreshActionBar();
    }

    /** Keeps the bar out of the layout entirely while it holds nothing, rather than as a blank strip. */
    private void refreshActionBar() {
        setVisibleManaged(actionBar,
            tileButton.isManaged() || reopenPreviewButton.isManaged() || copySummaryButton.isManaged());
    }

    /** Swaps the live "current step" line for the finished run's numbers. */
    private void showSummary(String statusKey) {
        lastStatusKey = statusKey;
        RunSummary summary = currentSummary(statusKey);
        summaryTitle.setText(I18n.get("snippets.ai.analysis.progress.summary.title"));
        summaryDuration.setText(I18n.get(
            "snippets.ai.analysis.progress.summary.duration", formatDuration(summary.elapsedSeconds())));
        summaryTokens.setText(tokenSummaryText(summary.usage()));
        setVisibleManaged(summaryProfile, summary.profileName() != null && !summary.profileName().isBlank());
        summaryProfile.setText(I18n.get(
            "snippets.ai.analysis.progress.summary.profile", String.valueOf(summary.profileName())));
        summaryItems.setText(I18n.get("snippets.ai.analysis.progress.summary.items",
            summary.completedItems(), summary.totalItems()));
        setVisibleManaged(summaryRetries, summary.retries() > 0);
        summaryRetries.setText(I18n.get("snippets.ai.analysis.progress.summary.retries", summary.retries()));
        setVisibleManaged(summaryBox, true);
        setVisibleManaged(copySummaryButton, true);
        refreshActionBar();
    }

    private RunSummary currentSummary(String statusKey) {
        List<WorkRow> all = List.copyOf(rows.values());
        return new RunSummary(
            statusKey,
            elapsedSeconds(),
            lastUsage,
            profileName,
            (int) all.stream().filter(WorkRow::isCompleted).count(),
            all.size(),
            retries);
    }

    private void copySummary() {
        de.kortty.core.KorttyClipboard.setText(summaryText(currentSummary(lastStatusKey)));
        currentStepLabel.setText(I18n.get("snippets.ai.analysis.progress.summary.copied"));
    }

    /** "Tokens: 1,204 prompt / 388 completion / 1,592 total", or the honest "not reported". */
    static String tokenSummaryText(AiTokenUsage usage) {
        if (usage == null) {
            return I18n.get("snippets.ai.analysis.progress.tokens",
                I18n.get("snippets.ai.analysis.progress.tokensUnavailable"));
        }
        NumberFormat format = NumberFormat.getIntegerInstance();
        return I18n.get("snippets.ai.analysis.progress.summary.tokens",
            format.format(usage.promptTokens()),
            format.format(usage.completionTokens()),
            format.format(usage.totalTokens()));
    }

    /**
     * The finished run as plain text for the clipboard — the same numbers the window shows, in the
     * order it shows them, so a pasted summary matches what the reviewer was looking at.
     */
    static String summaryText(RunSummary summary) {
        List<String> lines = new java.util.ArrayList<>();
        lines.add(I18n.get("snippets.ai.analysis.progress.summary.title"));
        lines.add(I18n.get(summary.statusKey() != null
            ? summary.statusKey()
            : "snippets.ai.analysis.progress.complete"));
        lines.add(I18n.get("snippets.ai.analysis.progress.summary.duration",
            formatDuration(summary.elapsedSeconds())));
        lines.add(tokenSummaryText(summary.usage()));
        if (summary.profileName() != null && !summary.profileName().isBlank()) {
            lines.add(I18n.get("snippets.ai.analysis.progress.summary.profile", summary.profileName()));
        }
        lines.add(I18n.get("snippets.ai.analysis.progress.summary.items",
            summary.completedItems(), summary.totalItems()));
        if (summary.retries() > 0) {
            lines.add(I18n.get("snippets.ai.analysis.progress.summary.retries", summary.retries()));
        }
        return String.join(System.lineSeparator(), lines);
    }

    /** What one finished apply run cost and achieved. */
    record RunSummary(
        String statusKey,
        long elapsedSeconds,
        AiTokenUsage usage,
        String profileName,
        int completedItems,
        int totalItems,
        int retries) {
    }

    private void registerRows(SnippetAiWorkflowSupport.ImprovementApplyProgress progress) {
        if (progress == null) {
            return;
        }
        VBox target = progress.phase() == SnippetAiWorkflowSupport.ImprovementApplyPhase.ANALYSIS_ITEMS
            ? improvementRows
            : hardeningRows;
        List<SnippetAiWorkflowSupport.ImprovementApplyWorkItem> items = progress.workItems();
        for (int index = 0; index < items.size(); index++) {
            SnippetAiWorkflowSupport.ImprovementApplyWorkItem item = items.get(index);
            WorkKey key = new WorkKey(progress.stage(), rowKeyId(index, item));
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
        elapsedLabel.setText(
            I18n.get("snippets.ai.analysis.progress.elapsed", formatDuration(elapsedSeconds())));
    }

    /** Stops the clock at its final value, so the summary keeps reporting the run, not the wait. */
    private void freezeElapsed() {
        finishedSeconds = elapsedSeconds();
        refreshElapsed();
    }

    private long elapsedSeconds() {
        if (finishedSeconds >= 0L) {
            return finishedSeconds;
        }
        return startedNanos > 0L
            ? Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000_000L)
            : 0L;
    }

    /** {@code mm:ss}, growing to {@code h:mm:ss} only once the run actually passed an hour. */
    static String formatDuration(long seconds) {
        long safe = Math.max(0L, seconds);
        long hours = safe / 3_600L;
        long minutes = (safe % 3_600L) / 60L;
        long remaining = safe % 60L;
        return hours > 0
            ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remaining)
            : String.format(Locale.ROOT, "%02d:%02d", minutes, remaining);
    }

    private void refreshTokens(AiTokenUsage usage) {
        if (usage != null) {
            lastUsage = usage;
        }
        String value = usage != null
            ? NumberFormat.getIntegerInstance().format(usage.totalTokens())
            : I18n.get("snippets.ai.analysis.progress.tokensUnavailable");
        tokenLabel.setText(I18n.get("snippets.ai.analysis.progress.tokens", value));
    }

    private void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        elapsedTimeline.stop();
        persistDockedWidth();
    }

    /**
     * Remembers how wide the user made this window, so the next apply run opens it at that width
     * instead of the designed default. Position and height belong to the dock, not to the user.
     */
    private void persistDockedWidth() {
        double width = stage.getWidth();
        if (Double.isNaN(width) || width < MIN_USABLE_WIDTH) {
            return;
        }
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setAiApplyProgressDockedWidth(width);
                manager.save();
            }
        } catch (Exception ignored) {
            // No application instance (isolated JavaFX tests) or an unwritable profile: the width is
            // a convenience, never worth failing a window close over.
        }
    }

    private static void applyTheme(Scene scene) {
        AppDesignStyleSupport.registerApplicationBaseStyles(scene);
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
        label.setStyle("-fx-font-size: 1.0769em; -fx-font-weight: bold; -fx-padding: 5 0 1 0;");
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
        valueLabel.setStyle("-fx-font-size: 0.7692em; -fx-opacity: 0.78;");
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

    /**
     * The user-facing text for one work item. Hardening rules arrive as the English prompt sentence
     * that is sent to the model, so they are shown through their own localized option label instead;
     * an analysis finding already carries a title in the user's language and is shown as it is.
     */
    private static String describeWorkItem(SnippetAiWorkflowSupport.ImprovementApplyWorkItem item) {
        String labelKey = WorkflowScriptSupport.ruleLabelKey(item.label());
        return labelKey != null ? I18n.get(labelKey) : item.label();
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
        if (phase == SnippetAiWorkflowSupport.ImprovementApplyPhase.MIGRATION) {
            return I18n.get("snippets.ai.analysis.progress.migration");
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
        // The glyphs carry cut-outs (the padlock's keyhole, the module hexagon's centre) as nested
        // subpaths; without even-odd they fill solid and the cut-out disappears.
        icon.setFillRule(FillRule.EVEN_ODD);
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
            case MIGRATION -> I18n.get("snippets.ai.analysis.progress.migration");
        };
    }

    private static void setVisibleManaged(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }


    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /** Item ids are model-supplied and not guaranteed unique within a batched stage; key rows by
     *  list position + id so duplicate ids cannot swallow each other's checklist rows. */
    private static String rowKeyId(int index, SnippetAiWorkflowSupport.ImprovementApplyWorkItem item) {
        return index + ":" + item.id();
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
            identifier.setStyle("-fx-font-size: 0.7692em; -fx-opacity: 0.72;");
            HBox identifierLine = new HBox(5, identifier);
            identifierLine.setAlignment(Pos.CENTER_LEFT);
            SVGPath categoryIcon = categoryIcon(item, phase);
            if (categoryIcon != null) {
                identifierLine.getChildren().add(categoryIcon);
            }
            Label text = new Label(describeWorkItem(item));
            text.setWrapText(true);
            text.setTextOverrun(OverrunStyle.ELLIPSIS);
            text.setEllipsisString("…");
            text.getStyleClass().add("snippet-analysis-progress-description");
            text.maxHeightProperty().bind(Bindings.createDoubleBinding(
                () -> Math.ceil(text.getFont().getSize()
                    * DESCRIPTION_LINE_HEIGHT_FACTOR * MAX_DESCRIPTION_LINES),
                text.fontProperty()));
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

        SnippetAiWorkflowSupport.ImprovementApplyProgressState state() {
            return state;
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
            status.setStyle("-fx-font-size: 1.3077em; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
            status.setTooltip(new Tooltip(I18n.get(tooltipKey)));
        }
    }
}
