package de.kortty.ui;

import de.kortty.core.AiTokenUsage;
import de.kortty.core.AnalysisRunFormatting;
import de.kortty.core.SnippetAiWorkflowSupport;
import de.kortty.core.SnippetAnalysisRecord;
import de.kortty.core.WorkflowScriptSupport;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
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
import javafx.util.Duration;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The progress view of the staged Full-code-analysis apply workflow: a checklist of work items, a
 * progress bar per phase, the running clock and the token usage, and — once the run ends — a summary
 * of what was done, how long it took and what it cost.
 *
 * <p>This is only a view. It can be dropped and rebuilt at any time (an embedded host disposes it
 * when its panel hides), so a host that needs the run's state keeps it itself and rebuilds the view
 * from it — {@link #restored(SnippetAnalysisRecord.ApplyRun)} does exactly that for a persisted
 * run. The pane never opens a window; the snippet editor's analysis panel hosts it.</p>
 *
 * <p>Token usage is rendered exactly as the provider reported it and never guessed — a run against
 * a backend that reports nothing says so rather than showing an estimate that looks like a fact.</p>
 */
final class SnippetAiApplyProgressPane extends VBox {

    private static final int MAX_DESCRIPTION_LINES = 3;
    private static final double DESCRIPTION_LINE_HEIGHT_FACTOR = 1.35;

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
    private final VBox recoveryBox = new VBox(6);
    private final Button reviewChangesButton = new Button(I18n.get("snippets.ai.analysis.progress.reopenPreview"));
    private final Button cancelButton = AiStopRetrySupport.stopButton(null);
    private final Button copySummaryButton = new Button(I18n.get("snippets.ai.analysis.progress.summary.copy"));
    private final HBox leadingActions = new HBox(6);
    private final HBox actionBar = new HBox(6);

    private long startedNanos;
    private long finishedSeconds = -1L;
    private AiTokenUsage lastUsage;
    private int retries;
    private String lastStatusKey;
    private boolean running;
    private boolean disposed;
    private Runnable onCancel;

    SnippetAiApplyProgressPane(
            List<SnippetAiWorkflowSupport.ImprovementApplyProgress> plan,
            String profileName) {
        this.profileName = profileName;
        setId("snippet-analysis-progress-pane");

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
        configureRecovery();
        buildActionBar();

        setSpacing(8);
        getChildren().setAll(
            improvementsProgressGroup,
            hardeningProgressGroup,
            metrics,
            currentStepLabel,
            summaryBox,
            recoveryBox,
            new Separator(),
            scroll,
            actionBar);
        setPadding(new Insets(12));

        elapsedTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> refreshElapsed()));
        elapsedTimeline.setCycleCount(Timeline.INDEFINITE);
        refreshProgress();
        refreshElapsed();
        refreshTokens(null);
        currentStepLabel.setText(I18n.get("snippets.ai.analysis.progress.preparing"));
    }

    /**
     * A finished (or interrupted) run, rebuilt from what was persisted: the checklist with each
     * item's last state, and the summary with the stored duration, usage, profile and retries. The
     * clock does not run.
     */
    static SnippetAiApplyProgressPane restored(SnippetAnalysisRecord.ApplyRun run) {
        SnippetAnalysisRecord.ApplyRun safe = run != null
            ? run
            : SnippetAnalysisRecord.ApplyRun.started("", 0L, null, List.of(), null);
        String profile = safe.provenance().profileName();
        SnippetAiApplyProgressPane pane = new SnippetAiApplyProgressPane(List.of(), profile);
        pane.restore(safe);
        return pane;
    }

    private void restore(SnippetAnalysisRecord.ApplyRun run) {
        Map<Integer, Integer> nextIndexPerStage = new HashMap<>();
        boolean anyStoredState = false;
        for (SnippetAnalysisRecord.WorkItemState stored : run.items()) {
            int index = nextIndexPerStage.merge(stored.stage(), 1, Integer::sum) - 1;
            SnippetAiWorkflowSupport.ImprovementApplyPhase phase = parsePhase(stored.phase());
            SnippetAiWorkflowSupport.ImprovementApplyWorkItem item = new SnippetAiWorkflowSupport.ImprovementApplyWorkItem(
                stored.id(), stored.label(), stored.category(), stored.severity());
            WorkRow row = registerRow(stored.stage(), index, item, phase);
            SnippetAiWorkflowSupport.ImprovementApplyProgressState state = parseState(stored.state());
            anyStoredState |= state != null;
            row.setState(state);
        }
        refreshSectionVisibility();

        SnippetAnalysisRecord.RunOutcome outcome = run.outcome();
        boolean succeeded = outcome == SnippetAnalysisRecord.RunOutcome.PENDING_REVIEW
            || outcome == SnippetAnalysisRecord.RunOutcome.ACCEPTED
            || outcome == SnippetAnalysisRecord.RunOutcome.REJECTED;
        if (succeeded && !anyStoredState) {
            rows.values().forEach(row -> row.setState(
                SnippetAiWorkflowSupport.ImprovementApplyProgressState.COMPLETED));
        }
        SnippetAnalysisRecord.RunStats stats = run.stats();
        retries = stats.retries();
        SnippetAnalysisRecord.Usage usage = stats.usage();
        refreshTokens(usage != null && usage.totalTokens() > 0 ? usage.toAiTokenUsage() : null);
        long elapsed = stats.elapsedSeconds();
        if (elapsed <= 0 && run.finishedAt() > run.startedAt() && run.startedAt() > 0) {
            elapsed = (run.finishedAt() - run.startedAt()) / 1000L;
        }
        finishedSeconds = Math.max(0L, elapsed);
        refreshProgress();
        refreshElapsed();

        String statusKey = stats.statusKey();
        if (statusKey == null) {
            statusKey = switch (outcome) {
                case PENDING_REVIEW, ACCEPTED, REJECTED -> "snippets.ai.analysis.progress.complete";
                case FAILED -> failedStatusKey(rows.values().stream().map(WorkRow::state).toList());
                default -> "snippets.ai.analysis.progress.cancelled";
            };
        }
        currentStepLabel.setText(I18n.get(statusKey));
        showSummary(statusKey);
    }

    /** Starts the clock; the host calls this when the run actually begins. */
    void start() {
        if (disposed || running) {
            return;
        }
        running = true;
        startedNanos = System.nanoTime();
        finishedSeconds = -1L;
        elapsedTimeline.playFromStart();
        refreshCancelButton();
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
            finishRun();
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
            finishRun();
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
            finishRun();
            freezeElapsed();
            currentStepLabel.setText(I18n.get("snippets.ai.analysis.progress.cancelled"));
            showSummary("snippets.ai.analysis.progress.cancelled");
        });
    }

    /**
     * Shows a Cancel button while the run is active; {@code null} removes it. The handler only asks
     * the host to cancel — the pane reports the outcome once the host calls {@link #markCancelled()}.
     */
    void setOnCancel(Runnable handler) {
        runOnFx(() -> {
            onCancel = handler;
            cancelButton.setOnAction(handler == null ? null : event -> handler.run());
            refreshCancelButton();
        });
    }

    /**
     * Offers "review the changes" once the result is waiting for a decision — closing the preview
     * by accident should not mean re-running the whole analysis. {@code null} removes the action.
     */
    void setOnReviewChanges(Runnable handler) {
        setOnReviewChanges(handler, false);
    }

    /**
     * Like {@link #setOnReviewChanges(Runnable)}; {@code viewOnly} labels the action "View changes"
     * for a result that was already decided (a read-only look at the stored diff).
     */
    void setOnReviewChanges(Runnable handler, boolean viewOnly) {
        runOnFx(() -> {
            reviewChangesButton.setText(I18n.get(viewOnly
                ? "snippets.ai.analysis.progress.viewChanges"
                : "snippets.ai.analysis.progress.reopenPreview"));
            reviewChangesButton.setOnAction(handler == null ? null : event -> handler.run());
            setVisibleManaged(reviewChangesButton, handler != null);
            refreshActionBar();
        });
    }

    /**
     * Puts a host-specific action at the left end of the action bar (the window host's "arrange
     * windows"); {@code null} removes it.
     */
    void setLeadingAction(Node action) {
        runOnFx(() -> {
            if (action == null) {
                leadingActions.getChildren().clear();
            } else {
                leadingActions.getChildren().setAll(action);
            }
            setVisibleManaged(leadingActions, !leadingActions.getChildren().isEmpty());
            refreshActionBar();
        });
    }

    /**
     * An interrupted run's way forward, inline instead of an alert: resume the remaining stages,
     * review the partial result, or discard it. {@code null} hides the strip.
     */
    void setRecovery(Recovery recovery) {
        runOnFx(() -> {
            recoveryBox.getChildren().clear();
            if (recovery == null) {
                setVisibleManaged(recoveryBox, false);
                return;
            }
            // Nothing finished yet: there is nothing to resume or preview, only the run to repeat.
            boolean nothingCompleted = recovery.completedStages() <= 0;
            Label header = new Label(nothingCompleted
                ? I18n.get(recovery.cancelled()
                    ? "snippets.ai.analysis.fix.recovery.header.cancelledEarly"
                    : "snippets.ai.analysis.fix.recovery.header.failedEarly")
                : I18n.get(recovery.cancelled()
                    ? "snippets.ai.analysis.fix.recovery.header.cancelled"
                    : "snippets.ai.analysis.fix.recovery.header.failed",
                recovery.completedStages(), recovery.totalStages()));
            header.setWrapText(true);
            header.setStyle("-fx-font-weight: bold;");
            Label content = new Label(I18n.get("snippets.ai.analysis.fix.recovery.content"));
            content.setWrapText(true);
            content.setStyle("-fx-font-size: 0.9231em; -fx-opacity: 0.85;");
            HBox buttons = new HBox(6);
            buttons.setAlignment(Pos.CENTER_LEFT);
            addRecoveryButton(buttons, "snippets.ai.analysis.fix.recovery.resume", recovery.onResume());
            if (recovery.onRetry() != null) {
                Button retry = AiStopRetrySupport.retryButton(recovery.onRetry());
                retry.setId("snippet-analysis-apply-retry");
                retry.setText(AiStopRetrySupport.RETRY_PREFIX
                    + I18n.get(nothingCompleted ? "snippets.ai.retry" : "snippets.ai.analysis.fix.recovery.restart"));
                buttons.getChildren().add(retry);
            }
            addRecoveryButton(buttons, "snippets.ai.analysis.fix.recovery.partial", recovery.onPreviewPartial());
            addRecoveryButton(buttons, "snippets.ai.analysis.fix.recovery.discard", recovery.onDiscard());
            recoveryBox.getChildren().setAll(header);
            if (!nothingCompleted) {
                recoveryBox.getChildren().add(content);
            }
            if (recovery.note() != null && !recovery.note().isBlank()) {
                Label note = new Label(recovery.note());
                note.setId("snippet-analysis-recovery-note");
                note.setWrapText(true);
                note.setStyle("-fx-font-size: 0.9231em; -fx-font-style: italic;");
                recoveryBox.getChildren().add(note);
            }
            recoveryBox.getChildren().add(buttons);
            setVisibleManaged(recoveryBox, true);
        });
    }

    private static void addRecoveryButton(HBox target, String key, Runnable action) {
        if (action == null) {
            return;
        }
        Button button = new Button(I18n.get(key));
        button.setOnAction(event -> action.run());
        target.getChildren().add(button);
    }

    /**
     * What an interrupted run offers; a {@code null} action leaves its button out. {@code onRetry}
     * repeats the whole run from the first stage with the same selection.
     */
    record Recovery(int completedStages, int totalStages, boolean cancelled,
                    Runnable onResume, Runnable onPreviewPartial, Runnable onDiscard, String note,
                    Runnable onRetry) {

        /** Without Retry. */
        Recovery(int completedStages, int totalStages, boolean cancelled,
                 Runnable onResume, Runnable onPreviewPartial, Runnable onDiscard, String note) {
            this(completedStages, totalStages, cancelled, onResume, onPreviewPartial, onDiscard, note, null);
        }

        /** Without a note or Retry. */
        Recovery(int completedStages, int totalStages, boolean cancelled,
                 Runnable onResume, Runnable onPreviewPartial, Runnable onDiscard) {
            this(completedStages, totalStages, cancelled, onResume, onPreviewPartial, onDiscard, null, null);
        }
    }

    /** Ids of the work items that finished, in checklist order (duplicates collapsed). */
    List<String> completedWorkItemIds() {
        List<String> ids = new ArrayList<>();
        for (WorkRow row : rows.values()) {
            if (row.isCompleted() && !row.item().id().isBlank() && !ids.contains(row.item().id())) {
                ids.add(row.item().id());
            }
        }
        return List.copyOf(ids);
    }

    /** Every checklist row with its current state, in the shape a persisted run stores. */
    List<SnippetAnalysisRecord.WorkItemState> workItemStates() {
        List<SnippetAnalysisRecord.WorkItemState> states = new ArrayList<>();
        for (Map.Entry<WorkKey, WorkRow> entry : rows.entrySet()) {
            WorkRow row = entry.getValue();
            states.add(new SnippetAnalysisRecord.WorkItemState(
                entry.getKey().stage(),
                row.phase().name(),
                row.item().id(),
                row.item().label(),
                row.item().category(),
                row.item().severity(),
                row.state().name()));
        }
        return List.copyOf(states);
    }

    /** The run's numbers as they stand now (final once a {@code mark…} call ended it). */
    RunSummary currentSummary() {
        return currentSummary(lastStatusKey);
    }

    boolean isRunning() {
        return running;
    }

    /** Stops the clock for good; the pane ignores progress afterwards. Safe to call more than once. */
    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        running = false;
        elapsedTimeline.stop();
    }

    boolean isDisposed() {
        return disposed;
    }

    private void finishRun() {
        running = false;
        elapsedTimeline.stop();
        refreshCancelButton();
    }

    private void refreshCancelButton() {
        setVisibleManaged(cancelButton, running && onCancel != null);
        refreshActionBar();
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

    private void configureRecovery() {
        recoveryBox.setId("snippet-analysis-progress-recovery");
        recoveryBox.setStyle("-fx-border-color: rgba(245,158,11,0.55); -fx-border-radius: 6;"
            + " -fx-background-color: rgba(245,158,11,0.10); -fx-background-radius: 6; -fx-padding: 8 10 9 10;");
        setVisibleManaged(recoveryBox, false);
    }

    private void buildActionBar() {
        copySummaryButton.setOnAction(event -> copySummary());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        leadingActions.setAlignment(Pos.CENTER_LEFT);
        actionBar.getChildren().setAll(leadingActions, spacer, cancelButton, reviewChangesButton, copySummaryButton);
        actionBar.setAlignment(Pos.CENTER_LEFT);
        setVisibleManaged(leadingActions, false);
        setVisibleManaged(cancelButton, false);
        setVisibleManaged(reviewChangesButton, false);
        setVisibleManaged(copySummaryButton, false);
        refreshActionBar();
    }

    /** Keeps the bar out of the layout entirely while it holds nothing, rather than as a blank strip. */
    private void refreshActionBar() {
        setVisibleManaged(actionBar,
            leadingActions.isManaged() || cancelButton.isManaged() || reviewChangesButton.isManaged()
                || copySummaryButton.isManaged());
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
        return AnalysisRunFormatting.tokenSummary(usage);
    }

    /**
     * The finished run as plain text for the clipboard — the same numbers the pane shows, in the
     * order it shows them, so a pasted summary matches what the reviewer was looking at.
     */
    static String summaryText(RunSummary summary) {
        List<String> lines = new ArrayList<>();
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
        List<SnippetAiWorkflowSupport.ImprovementApplyWorkItem> items = progress.workItems();
        for (int index = 0; index < items.size(); index++) {
            registerRow(progress.stage(), index, items.get(index), progress.phase());
        }
        refreshSectionVisibility();
    }

    private WorkRow registerRow(
            int stage,
            int index,
            SnippetAiWorkflowSupport.ImprovementApplyWorkItem item,
            SnippetAiWorkflowSupport.ImprovementApplyPhase phase) {
        WorkKey key = new WorkKey(stage, rowKeyId(index, item));
        WorkRow existing = rows.get(key);
        if (existing != null) {
            return existing;
        }
        VBox target = phase == SnippetAiWorkflowSupport.ImprovementApplyPhase.ANALYSIS_ITEMS
            ? improvementRows
            : hardeningRows;
        WorkRow row = new WorkRow(item, phase);
        rows.put(key, row);
        target.getChildren().add(row.root());
        return row;
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
        return AnalysisRunFormatting.formatDuration(seconds);
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

    /** A stored phase name, falling back to the analysis items for anything unknown. */
    static SnippetAiWorkflowSupport.ImprovementApplyPhase parsePhase(String name) {
        if (name != null && !name.isBlank()) {
            try {
                return SnippetAiWorkflowSupport.ImprovementApplyPhase.valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // unknown (newer) phase: shown with the analysis items
            }
        }
        return SnippetAiWorkflowSupport.ImprovementApplyPhase.ANALYSIS_ITEMS;
    }

    /** A stored item state, or {@code null} when none (or an unknown one) was stored. */
    static SnippetAiWorkflowSupport.ImprovementApplyProgressState parseState(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return SnippetAiWorkflowSupport.ImprovementApplyProgressState.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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

    private static void setVisibleManaged(Node node, boolean visible) {
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
        private final SnippetAiWorkflowSupport.ImprovementApplyWorkItem item;
        private final SnippetAiWorkflowSupport.ImprovementApplyPhase phase;
        private SnippetAiWorkflowSupport.ImprovementApplyProgressState state =
            SnippetAiWorkflowSupport.ImprovementApplyProgressState.PENDING;

        WorkRow(
                SnippetAiWorkflowSupport.ImprovementApplyWorkItem item,
                SnippetAiWorkflowSupport.ImprovementApplyPhase phase) {
            this.item = item;
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

        SnippetAiWorkflowSupport.ImprovementApplyWorkItem item() {
            return item;
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
