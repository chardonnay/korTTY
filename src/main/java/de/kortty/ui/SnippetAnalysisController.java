package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.ScriptLanguageMixSupport;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAiWorkflowSupport;
import de.kortty.core.SnippetAnalysisComparison;
import de.kortty.core.SnippetAnalysisReport;
import de.kortty.core.SnippetAnalysisHistory;
import de.kortty.core.SnippetAnalysisRecord;
import de.kortty.core.SnippetAnalysisRecord.ApplyRun;
import de.kortty.core.SnippetAnalysisRecord.RecordStatus;
import de.kortty.core.SnippetAnalysisRecord.RunOutcome;
import de.kortty.core.SnippetAnalysisStore;
import de.kortty.core.SnippetDiagramSupport;
import de.kortty.core.WorkflowScriptSupport;
import de.kortty.core.WorkflowScriptSupport.HardeningOption;
import de.kortty.core.WorkflowScriptSupport.InputHardeningConfig;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetDiagramType;
import de.kortty.telemetry.Telemetry;
import de.kortty.telemetry.TelemetryEvents;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The integrated, persisted Full-code analysis of one snippet editor: runs the analysis, keeps the
 * result in {@link SnippetAnalysisStore} the moment it arrives (independent of the editor's own
 * Save/Cancel), shows it in a side panel inside the editor, drives the staged apply with its
 * progress in that panel and puts the change review into the editor area instead of a window.
 *
 * <p>One controller per editor instance. It talks to the editor only through {@link Host}; the
 * editor ({@link SnippetEditDialog}) implements that in an inner adapter, so the workspace's
 * embedded editors and every standalone editor window get the same behaviour.</p>
 *
 * <p>Nothing here opens a window or blocks: the result, the progress, the recovery offer after a
 * failed or cancelled run and the "content changed" guard are all inline. State that must survive
 * the side panel being hidden (a running apply, its progress, the diagram job) lives in this
 * controller, never in the panel, so hiding the panel cancels nothing.</p>
 *
 * <p>Everything runs on the JavaFX thread, except the provider calls inside the tasks.</p>
 */
final class SnippetAnalysisController {

    private static final Logger logger = LoggerFactory.getLogger(SnippetAnalysisController.class);

    static final String SIDE_PANEL_ID = "snippet-analysis-side-panel";
    static final String APPLY_BUTTON_ID = "snippet-analysis-apply";
    static final String BANNERS_ID = "snippet-analysis-banners";
    static final String HISTORY_COMBO_ID = "snippet-analysis-history";
    static final String EMPTY_STATE_ID = "snippet-analysis-empty";
    static final String PROGRESS_HOLDER_ID = "snippet-analysis-progress-holder";
    static final String STALE_BANNER_ID = "snippet-analysis-stale-banner";
    static final String REVIEW_BANNER_ID = "snippet-analysis-review-banner";
    static final String RERUNNING_BANNER_ID = "snippet-analysis-rerunning-banner";
    static final String NOT_PERSISTED_BANNER_ID = "snippet-analysis-not-persisted-banner";
    static final String VERIFY_BUTTON_ID = "snippet-analysis-verify";
    static final String VERIFY_BANNER_ID = "snippet-analysis-verify-banner";
    static final String HISTORY_ACTIONS_ID = "snippet-analysis-history-actions";
    static final String HISTORY_PIN_ID = "snippet-analysis-history-pin";
    static final String HISTORY_DISCARD_ID = "snippet-analysis-history-discard";
    static final String HISTORY_DELETE_ALL_ID = "snippet-analysis-history-delete-all";
    static final String CONFIRM_STRIP_ID = "snippet-analysis-confirm-strip";
    static final String CONFIRM_YES_ID = "snippet-analysis-confirm-yes";
    static final String CONFIRM_NO_ID = "snippet-analysis-confirm-no";
    /** Marks a pinned entry in the history picker. */
    static final String PIN_MARKER = "\uD83D\uDCCC";

    static final String BADGE_OPEN = "●";
    static final String BADGE_STALE = "⚠";
    static final String BADGE_RUNNING = "⏳";

    /** Default and minimum width of the side panel, in px. */
    static final double DEFAULT_PANEL_WIDTH = 520;
    static final double MIN_PANEL_WIDTH = 360;

    private static final DateTimeFormatter HISTORY_TIME =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT);

    /** How a stored analysis relates to the editor's current content. */
    enum Staleness {
        /** No analysis, or one without a recorded content hash. */
        NONE,
        /** The analysis describes exactly the current content. */
        FRESH,
        /** The content changed since the analysis. */
        STALE
    }

    /** What the controller needs from the editor it lives in. */
    interface Host {
        /** The store key: the snippet's id, or the editor's draft id before the first save. */
        String snippetId();

        String snippetName();

        String currentContent();

        String snippetLanguage();

        /** The language the report is written in (the application language). */
        String reportLanguageCode();

        /** The code-text fallback language (the editor's text-language picker). */
        String codeTextFallbackLanguageCode();

        String additionalInstructions();

        SnippetEditDialog.AiAssist aiAssist();

        boolean hasCodeAnalysisProviders();

        boolean profileSwitchingSupported();

        SnippetAnalysisPanel.SkillContext skillContext();

        void autoDetectAiSkills();

        boolean ensureDataNoticeAccepted();

        boolean isAnyAiTaskRunning();

        void beginAiAction(Task<?> task);

        void finishAiAction(Task<?> task);

        void handleAiActionFailure(Task<?> task, String genericStatus);

        void showAiHint(String message);

        /** Mirrors the task's message and progress into the editor's hint bar while it is the active task. */
        void followTaskProgress(Task<?> task);

        void setStatus(String message);

        /** Resolves the code-text language for a rewrite; may ask. {@code false} = the user declined. */
        boolean applyCodeTextLanguage(boolean mayAsk);

        String hardeningInstructions(EnumSet<HardeningOption> options);

        String inputHardeningInstructions(InputHardeningConfig config);

        String injectSelectedHeader(SnippetAnalysisPanel.ApplySelection selection, String content);

        boolean contentUnchangedSince(String original);

        void applyFullReplacement(String original, String replacement, String actionLabel);

        CompletableFuture<SnippetDiagramView.DiagramSource> generateDiagram(
            String content, String language, String aiProfileId);

        void showSidePanel(Region panel);

        void hideSidePanel();

        /**
         * Shows the review instead of the editor form. While another review holds the editor area,
         * the host keeps this one waiting and shows it once that one is decided.
         */
        void showInEditorArea(SnippetAiDiffPane pane);

        /** The review is decided: gives the editor area back (or drops it from the waiting line). */
        void restoreEditorArea(SnippetAiDiffPane pane);

        EditorSettingsHelper.Settings editorSettings();

        /** Updates the editor's "Analysis" toggle: selected state, badge suffix and tooltip. */
        void panelStateChanged(boolean visible, String badge, String tooltip);

        /** The content of the snippet as last saved, or {@code null} for a never-saved draft. */
        String savedSnippetContent();

        /** An external file or a policy-managed snippet: its analyses are never written to disk. */
        boolean isTransientSnippet();
    }

    /** The one apply run of this editor that is in flight. */
    private static final class ActiveRun {
        final Task<SnippetAiResponseSupport.SnippetSecurityFix> task;
        final String recordId;
        final String runId;
        final SnippetAiApplyProgressPane pane;
        final long startedAt;
        boolean silentCancel;

        ActiveRun(Task<SnippetAiResponseSupport.SnippetSecurityFix> task, String recordId, String runId,
                  SnippetAiApplyProgressPane pane, long startedAt) {
            this.task = task;
            this.recordId = recordId;
            this.runId = runId;
            this.pane = pane;
            this.startedAt = startedAt;
        }
    }

    /** What a finished in-session run needs to be resumed or previewed partially. */
    private record RunContext(SnippetAnalysisPanel.ApplySelection selection, String baseContent,
                              SnippetAiWorkflowSupport.ImprovementApplyCheckpoint checkpoint) {
    }

    /** An Apply that waits for the user's answer to "the analysis is older than the content". */
    private record StalePrompt(String recordId, SnippetAnalysisPanel.ApplySelection selection) {
    }

    /** A destructive history action waiting for its inline confirmation ({@code recordId} null = all). */
    private record PendingConfirm(String recordId) {
        boolean deletesAll() {
            return recordId == null;
        }
    }

    /** The change review currently replacing the editor area. */
    private record Review(String recordId, String runId, String baseContent, String replacement,
                          boolean partial, List<String> appliedFindingIds, SnippetAiDiffPane pane,
                          SnippetAnalysisPanel.ApplySelection selection) {
    }

    private final Host host;
    private final SnippetAnalysisStore store;
    private String key;
    private SnippetAnalysisStore.Subscription subscription;
    private SnippetAnalysisHistory history;
    private boolean disposed;
    private boolean panelVisible;
    private boolean initialLoadHandled;

    // ---- side panel (built lazily, dropped when hidden) ----
    private VBox sidePanel;
    private VBox bannerBox;
    private ScrollPane bannerScroll;
    private ComboBox<String> historyCombo;
    private boolean updatingHistoryCombo;
    private List<String> renderedHistoryLabels = List.of();
    private StackPane contentHolder;
    private VBox progressHolder;
    private Button applyButton;
    private Button plainRerunButton;
    private Button verifyButton;
    private MenuButton historyActions;
    private Label headerStatusLabel;
    private SnippetAnalysisPanel analysisPanel;
    private String renderedRecordId;
    /** {@code null} follows the current record; otherwise the history entry the user picked. */
    private String shownRecordId;
    private boolean restoringSelection;
    private final PauseTransition selectionDebounce = new PauseTransition(Duration.millis(500));
    private final PauseTransition contentDebounce = new PauseTransition(Duration.millis(400));

    /** A Discard / Delete all waiting for the inline confirmation strip; {@code null} = none. */
    private PendingConfirm pendingConfirm;

    // ---- work ----
    private Task<SnippetAiResponseSupport.ScriptAnalysis> analysisTask;
    /** Why {@link #analysisTask} runs (a verification shows its own banner). */
    private SnippetAnalysisRecord.Purpose analysisPurpose;
    private ActiveRun activeRun;
    /** Finished in-session runs by id: the live pane (with its recovery offer) and how to resume. */
    private final Map<String, SnippetAiApplyProgressPane> livePanes = new HashMap<>();
    private final Map<String, RunContext> runContexts = new HashMap<>();
    private final Set<String> dismissedRecoveries = new HashSet<>();
    private SnippetAiApplyProgressPane restoredPane;
    private String restoredPaneKey;
    private Review review;
    /** Diagram jobs by record id; owned here, so hiding the panel does not cancel them. */
    private final Map<String, CompletableFuture<SnippetDiagramView.DiagramSource>> diagramJobs = new HashMap<>();
    /** Stale-content confirmations already given, by record id. */
    private final Set<String> staleConfirmed = new HashSet<>();
    private StalePrompt stalePrompt;

    SnippetAnalysisController(Host host, SnippetAnalysisStore store) {
        this.host = Objects.requireNonNull(host, "host");
        this.store = Objects.requireNonNull(store, "store");
        selectionDebounce.setOnFinished(event -> persistSelection());
        contentDebounce.setOnFinished(event -> refreshState());
    }

    // =====================================================================================
    // Lifecycle
    // =====================================================================================

    /**
     * Starts following the stored analyses of this editor's snippet. Only reads the store (no
     * WebView, no AI call); the side panel opens by itself when the user left it open last time.
     */
    void attach() {
        if (subscription != null || disposed) {
            return;
        }
        key = host.snippetId();
        if (key == null || key.isBlank()) {
            return;
        }
        subscription = store.subscribe(key, this::onHistoryChanged);
        SnippetAnalysisHistory cached = store.cached(key);
        if (cached != null) {
            onHistoryChanged(cached);
        }
        store.load(key).whenComplete((loaded, error) -> runOnFx(() -> {
            if (error != null) {
                logger.warn("Could not load the stored analyses of {}", key, error);
                return;
            }
            if (loaded != null && !disposed) {
                onHistoryChanged(loaded);
                if (!initialLoadHandled) {
                    initialLoadHandled = true;
                    if (history != null && history.current() != null && !panelVisible && panelVisibleByDefault()) {
                        showPanel();
                    }
                }
            }
        }));
    }

    /**
     * The editor closes: a running apply is recorded as INTERRUPTED (its checkpoint kept for a later
     * resume) and cancelled, a pending review stays PENDING_REVIEW, the selection is flushed and every
     * view is released. Safe to call more than once.
     */
    void dispose() {
        if (disposed) {
            return;
        }
        flushSelection();
        persistPanelWidth();
        ActiveRun run = activeRun;
        if (run != null) {
            run.silentCancel = true;
            long now = System.currentTimeMillis();
            updateRun(run.recordId, run.runId, current -> current.withItems(run.pane.workItemStates())
                .withFailure(RunOutcome.INTERRUPTED, now, null, stats(run, "snippets.ai.analysis.progress.cancelled")));
            if (!run.task.isDone()) {
                run.task.cancel(true);
            }
            run.pane.dispose();
            activeRun = null;
        }
        disposed = true;
        if (analysisTask != null && !analysisTask.isDone()) {
            analysisTask.cancel(true);
        }
        diagramJobs.values().forEach(job -> job.cancel(true));
        diagramJobs.clear();
        if (review != null) {
            review.pane().dispose();
            review = null;
        }
        disposePanelViews();
        livePanes.values().forEach(SnippetAiApplyProgressPane::dispose);
        livePanes.clear();
        selectionDebounce.stop();
        contentDebounce.stop();
        if (key != null) {
            store.releaseRun(key, this);
        }
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }

    boolean isDisposed() {
        return disposed;
    }

    // =====================================================================================
    // Editor events
    // =====================================================================================

    /** The editor text changed; the stale badge follows after a short pause. */
    void onContentChanged() {
        if (!disposed) {
            contentDebounce.playFromStart();
        }
    }

    /** Busy state or availability changed in the editor. */
    void onAvailabilityChanged() {
        if (!disposed) {
            refreshControls();
        }
    }

    /**
     * The editor saved its snippet: a draft history becomes persistent (moved to the saved id when
     * the host assigned another one), and accepted runs whose result is exactly what was saved are
     * stamped as saved.
     */
    void onSnippetSaved(Snippet saved) {
        if (disposed || saved == null) {
            return;
        }
        String savedId = saved.getId();
        if (key != null && savedId != null && !savedId.isBlank() && !savedId.equals(key)) {
            store.rekey(key, savedId);
            key = savedId;
        }
        if (key == null) {
            return;
        }
        store.persistIfPossible(key);
        String savedSha = SnippetDiagramSupport.contentHash(saved.getContent() != null ? saved.getContent() : "");
        long now = System.currentTimeMillis();
        SnippetAnalysisHistory current = store.cached(key);
        if (current != null && current.records().stream().anyMatch(r -> needsSavedStamp(r, savedSha))) {
            store.update(key, h -> {
                SnippetAnalysisHistory next = h;
                for (SnippetAnalysisRecord record : h.records()) {
                    if (needsSavedStamp(record, savedSha)) {
                        next = next.update(record.id(), r -> r.withApplyRuns(r.applyRuns().stream()
                            .map(run -> run.isAccepted() && run.savedToSnippetAt() <= 0
                                && savedSha.equals(run.acceptedContentSha256())
                                ? run.withSavedToSnippetAt(now) : run)
                            .toList()));
                    }
                }
                return next;
            });
        }
        refreshState();
    }

    private static boolean needsSavedStamp(SnippetAnalysisRecord record, String savedSha) {
        return record.applyRuns().stream().anyMatch(run -> run.isAccepted() && run.savedToSnippetAt() <= 0
            && savedSha.equals(run.acceptedContentSha256()));
    }

    // =====================================================================================
    // Panel visibility
    // =====================================================================================

    void togglePanel() {
        if (panelVisible) {
            hidePanel();
        } else {
            showPanel();
        }
    }

    boolean isPanelVisible() {
        return panelVisible;
    }

    void showPanel() {
        if (disposed) {
            return;
        }
        ensureSidePanel();
        if (!panelVisible) {
            panelVisible = true;
            host.showSidePanel(sidePanel);
            persistPanelVisible(true);
        }
        render();
    }

    /** Hides the panel and unloads its WebViews; a running apply and the diagram job keep going. */
    void hidePanel() {
        if (!panelVisible) {
            return;
        }
        flushSelection();
        persistPanelWidth();
        panelVisible = false;
        host.hideSidePanel();
        persistPanelVisible(false);
        disposePanelViews();
        refreshBadge();
    }

    /** The side panel node while it exists (tests, screenshots). */
    VBox sidePanel() {
        return sidePanel;
    }

    /** The analysis view currently shown in the side panel, or {@code null}. */
    SnippetAnalysisPanel analysisPanel() {
        return analysisPanel;
    }

    /** The history this controller last saw. */
    SnippetAnalysisHistory history() {
        return history;
    }

    private void disposePanelViews() {
        if (analysisPanel != null) {
            analysisPanel.dispose();
            analysisPanel = null;
        }
        renderedRecordId = null;
        if (restoredPane != null) {
            restoredPane.dispose();
            restoredPane = null;
            restoredPaneKey = null;
        }
        sidePanel = null;
        bannerBox = null;
        bannerScroll = null;
        historyCombo = null;
        renderedHistoryLabels = List.of();
        contentHolder = null;
        progressHolder = null;
        applyButton = null;
        plainRerunButton = null;
        verifyButton = null;
        historyActions = null;
        headerStatusLabel = null;
        pendingConfirm = null;
    }

    // =====================================================================================
    // Analysis
    // =====================================================================================

    /**
     * Runs the Full-code analysis. A shown result stays visible (with a "re-running" banner) until
     * the new one arrives; the new one then becomes current and the old one stays in the history.
     */
    void runAnalysis(String aiProfileId) {
        runAnalysis(aiProfileId, null, null);
    }

    /**
     * Verify: analyses the editor's current content again and stores the result with its
     * {@link SnippetAnalysisComparison comparison} against {@code recordId} (the analysis whose
     * changes were accepted), so the panel and the after-apply report show what was resolved.
     */
    void verify(String recordId) {
        SnippetAnalysisRecord verified = findRecord(recordId);
        if (disposed || verified == null) {
            return;
        }
        trackAction("code_review_verify", Map.of());
        runAnalysis(blankToNull(verified.provenance().profileId()), SnippetAnalysisRecord.Purpose.VERIFY,
            verified.id());
    }

    /**
     * @param purpose           {@code VERIFY} for a verification, else {@code null} (derived:
     *                          {@code ANALYSIS} for the first record, {@code RERUN} after it)
     * @param previousRecordId  the record a verification compares against
     */
    private void runAnalysis(String aiProfileId, SnippetAnalysisRecord.Purpose purpose, String previousRecordId) {
        if (disposed) {
            return;
        }
        if (!host.hasCodeAnalysisProviders() || !aiAllowed()) {
            host.setStatus(I18n.get("snippets.ai.analysis.panel.aiUnavailable"));
            return;
        }
        String content = host.currentContent();
        if (content == null || content.isBlank()) {
            return;
        }
        if (host.isAnyAiTaskRunning() || activeRun != null) {
            host.setStatus(I18n.get("snippets.ai.analysis.panel.busy"));
            return;
        }
        if (!host.ensureDataNoticeAccepted()) {
            return;
        }
        // Preselect the skills relevant to this snippet (unless the user already edited the set).
        host.autoDetectAiSkills();
        String language = host.snippetLanguage();
        String reportLanguage = host.reportLanguageCode();
        String codeTextLanguage = host.codeTextFallbackLanguageCode();
        String extra = host.additionalInstructions();
        String name = host.snippetName();
        SnippetEditDialog.AiAssist assist = host.aiAssist();
        AtomicReference<SnippetAnalysisRecord.Provenance> provenance = new AtomicReference<>();

        java.util.concurrent.atomic.AtomicLong elapsedMillis = new java.util.concurrent.atomic.AtomicLong();
        Task<SnippetAiResponseSupport.ScriptAnalysis> task = new Task<>() {
            @Override
            protected SnippetAiResponseSupport.ScriptAnalysis call() throws Exception {
                long started = System.nanoTime();
                try {
                    return assist.codeAnalysisProvider().analyze(new SnippetEditDialog.CodeAnalysisRequest(
                        content, language, reportLanguage, extra, aiProfileId, provenance::set));
                } finally {
                    elapsedMillis.set((System.nanoTime() - started) / 1_000_000L);
                }
            }
        };
        analysisTask = task;
        analysisPurpose = purpose;
        host.beginAiAction(task);
        task.setOnRunning(event -> {
            host.showAiHint(I18n.get("snippets.ai.review.running"));
            host.setStatus(I18n.get("snippets.ai.review.running"));
            refreshState();
        });
        task.setOnSucceeded(event -> {
            host.finishAiAction(task);
            analysisTask = null;
            analysisPurpose = null;
            if (disposed) {
                return;
            }
            SnippetAiResponseSupport.ScriptAnalysis result = task.getValue();
            if (result == null || !result.isUsable()) {
                host.setStatus(I18n.get("snippets.ai.review.failed"));
                refreshState();
                return;
            }
            SnippetAnalysisRecord.Provenance reported = provenance.get();
            storeAnalysis(result, content, language, reportLanguage, codeTextLanguage, name, extra,
                aiProfileId, reported, elapsedMillis.get(), purpose, previousRecordId);
            host.setStatus(I18n.get("snippets.ai.review.ready"));
        });
        task.setOnFailed(event -> {
            analysisTask = null;
            analysisPurpose = null;
            host.handleAiActionFailure(task, I18n.get("snippets.ai.review.failed"));
            refreshState();
        });
        task.setOnCancelled(event -> {
            analysisTask = null;
            analysisPurpose = null;
            host.finishAiAction(task);
            refreshState();
        });
        refreshState();
        AiTaskRunner.start(task, "snippet-ai-analysis");
    }

    private void storeAnalysis(SnippetAiResponseSupport.ScriptAnalysis result, String content, String language,
                               String reportLanguage, String codeTextLanguage, String name, String extra,
                               String requestedProfileId, SnippetAnalysisRecord.Provenance reported,
                               long elapsedMillis, SnippetAnalysisRecord.Purpose requestedPurpose,
                               String verifiedRecordId) {
        SnippetAnalysisHistory before = key != null ? store.cached(key) : null;
        boolean verifying = requestedPurpose == SnippetAnalysisRecord.Purpose.VERIFY;
        SnippetAnalysisRecord previous = before == null ? null
            : verifying && before.find(verifiedRecordId) != null ? before.find(verifiedRecordId) : before.current();
        SnippetAnalysisRecord.Provenance provenance = reported != null
            ? reported
            : new SnippetAnalysisRecord.Provenance(requestedProfileId,
                SnippetAiDialogSupport.resolveProfileDisplayName(requestedProfileId), null, null, null, extra, null);
        if (provenance.profileName().isBlank()) {
            provenance = new SnippetAnalysisRecord.Provenance(provenance.profileId(),
                SnippetAiDialogSupport.resolveProfileDisplayName(blankToNull(provenance.profileId())),
                provenance.model(), provenance.skillIds(), provenance.skillNames(),
                provenance.additionalInstructions(), provenance.usage());
        }
        if (elapsedMillis > 0 && provenance.durationMillis() <= 0) {
            provenance = provenance.withDurationMillis(elapsedMillis);
        }
        SnippetAnalysisRecord record = newRecord(UUID.randomUUID().toString(), key, result,
            SnippetAnalysisRecord.Source.of(content, language, reportLanguage, codeTextLanguage, name),
            provenance, previous, verifying, System.currentTimeMillis());
        shownRecordId = null;
        store.addAnalysis(key, record);
        showPanel();
    }

    /**
     * The record of a fresh analysis: {@code VERIFY} (against {@code previous}, the analysis whose
     * changes were accepted) when {@code verifying}, else {@code RERUN} after an earlier record or
     * {@code ANALYSIS} for the first one. Every follow-up carries its comparison with
     * {@code previous}, which the panel and the after-apply report render.
     */
    static SnippetAnalysisRecord newRecord(String id, String snippetId, SnippetAiResponseSupport.ScriptAnalysis result,
                                           SnippetAnalysisRecord.Source source,
                                           SnippetAnalysisRecord.Provenance provenance,
                                           SnippetAnalysisRecord previous, boolean verifying, long now) {
        SnippetAnalysisRecord record = SnippetAnalysisRecord.fromAnalysis(id, snippetId, result, source, provenance,
            verifying && previous != null ? SnippetAnalysisRecord.Purpose.VERIFY
                : previous != null ? SnippetAnalysisRecord.Purpose.RERUN : SnippetAnalysisRecord.Purpose.ANALYSIS,
            previous != null ? previous.id() : null,
            now);
        if (previous != null) {
            try {
                record = record.withVerification(SnippetAnalysisComparison.compare(previous, record));
            } catch (RuntimeException e) {
                logger.debug("Could not compare the new analysis with the previous one", e);
            }
        }
        return record;
    }

    // =====================================================================================
    // Diagram
    // =====================================================================================

    /**
     * The diagram for {@code recordId}: an in-flight job is shared, otherwise a new one is started
     * from the editor's current content (the first load and every Regenerate). The returned future is a copy, so a view that drops it
     * (panel hidden, Regenerate) never cancels the job; the job persists its result on arrival.
     */
    private CompletableFuture<SnippetDiagramView.DiagramSource> diagramFor(String recordId) {
        CompletableFuture<SnippetDiagramView.DiagramSource> job = diagramJobs.get(recordId);
        if (job == null) {
            SnippetAnalysisRecord record = findRecord(recordId);
            String profileId = record != null ? blankToNull(record.provenance().profileId()) : null;
            String content = host.currentContent();
            String language = host.snippetLanguage();
            CompletableFuture<SnippetDiagramView.DiagramSource> started =
                host.generateDiagram(content, language, profileId);
            if (started == null) {
                return null;
            }
            job = started;
            diagramJobs.put(recordId, job);
            CompletableFuture<SnippetDiagramView.DiagramSource> own = job;
            job.whenComplete((source, error) -> runOnFx(() -> {
                if (diagramJobs.get(recordId) == own) {
                    diagramJobs.remove(recordId);
                }
                if (!disposed && error == null && source != null) {
                    persistDiagram(recordId, source, content, profileId);
                }
            }));
        }
        return job.thenApply(source -> source);
    }

    private void persistDiagram(String recordId, SnippetDiagramView.DiagramSource source, String content,
                                String profileId) {
        if (key == null || findRecord(recordId) == null) {
            return;
        }
        List<SnippetAnalysisRecord.CodeRef> refs = source.codeReferences() == null ? List.of()
            : source.codeReferences().stream()
                .map(ref -> new SnippetAnalysisRecord.CodeRef(ref.nodeId(), ref.label(), ref.startLine(), ref.endLine()))
                .toList();
        SnippetAnalysisRecord.AnalysisDiagram diagram = new SnippetAnalysisRecord.AnalysisDiagram(
            source.diagramType().id(), source.mermaid(), refs, source.notice(),
            source.notice() != null, SnippetDiagramSupport.contentHash(content != null ? content : ""),
            profileId, System.currentTimeMillis());
        store.update(key, h -> h.update(recordId, r -> r.withDiagram(diagram)));
    }

    static SnippetDiagramView.DiagramSource toDiagramSource(SnippetAnalysisRecord record, String fallbackContent) {
        SnippetAnalysisRecord.AnalysisDiagram diagram = record != null ? record.diagram() : null;
        if (diagram == null || diagram.mermaid().isBlank()) {
            return null;
        }
        String content = record.source().content() != null ? record.source().content() : fallbackContent;
        SnippetDiagramType type = diagram.diagramType();
        return new SnippetDiagramView.DiagramSource(
            diagram.mermaid(),
            content != null ? content : "",
            diagram.codeReferences().stream()
                .map(ref -> new SnippetDiagramSupport.SourceCodeReference(
                    ref.nodeId(), ref.label(), ref.startLine(), ref.endLine()))
                .toList(),
            type != null ? type : SnippetDiagramType.LOGICAL_STRUCTURE,
            diagram.notice().isBlank() ? null : diagram.notice());
    }

    // =====================================================================================
    // Apply
    // =====================================================================================

    /** Apply selected, from the panel button. */
    private void applyFromPanel() {
        if (analysisPanel == null) {
            return;
        }
        apply(analysisPanel.readSelection());
    }

    /**
     * Applies {@code selection} to the editor content, against the shown analysis (or none). Order
     * matters: the code-text language question comes before any plan, run record or progress, so
     * dismissing it leaves nothing half-started behind.
     */
    void apply(SnippetAnalysisPanel.ApplySelection selection) {
        startApply(selection, null, null, false);
    }

    private void startApply(SnippetAnalysisPanel.ApplySelection selection,
                            SnippetAiWorkflowSupport.ImprovementApplyCheckpoint resumeFrom,
                            String resumeBaseContent, boolean staleAccepted) {
        startApply(selection, resumeFrom, resumeBaseContent, staleAccepted, null);
    }

    /**
     * @param storedRequest a resume after a restart: the exact request strings of the stored run
     *                      (instructions, hardening texts, languages, profile) instead of the
     *                      editor's current ones, so the plan matches the checkpoint
     */
    private void startApply(SnippetAnalysisPanel.ApplySelection selection,
                            SnippetAiWorkflowSupport.ImprovementApplyCheckpoint resumeFrom,
                            String resumeBaseContent, boolean staleAccepted,
                            SnippetAnalysisRecord.ApplyRequestSnapshot storedRequest) {
        if (disposed || selection == null || selection.isEmpty()) {
            host.setStatus(I18n.get("snippets.ai.analysis.panel.nothingSelected"));
            return;
        }
        if (host.isAnyAiTaskRunning() || activeRun != null) {
            host.setStatus(I18n.get("snippets.ai.analysis.panel.busy"));
            return;
        }
        if (key != null && store.isRunClaimedByOther(key, this)) {
            host.setStatus(I18n.get("snippets.ai.analysis.run.otherWindow"));
            return;
        }
        SnippetAnalysisRecord record = shownRecord();
        String baseContent = resumeBaseContent != null ? resumeBaseContent : safe(host.currentContent());
        if (record != null && resumeFrom == null && !staleAccepted && !staleConfirmed.contains(record.id())
                && staleness(record, SnippetDiagramSupport.contentHash(baseContent)) == Staleness.STALE) {
            showStalePrompt(record, selection);
            return;
        }
        // The selection is part of the result: keep what was applied with the analysis.
        flushSelection();

        String language = storedRequest != null && !storedRequest.snippetLanguage().isBlank()
            ? storedRequest.snippetLanguage()
            : host.snippetLanguage();
        boolean inputHardeningApplies = selection.inputHardening().isEnabled()
            && WorkflowScriptSupport.supportsInputHardeningForSnippet(language);
        boolean hasAiWork = !selection.improvements().isEmpty()
            || !selection.dependencies().isEmpty()
            || !selection.hardening().isEmpty()
            || inputHardeningApplies
            || selection.migrates();
        if (!hasAiWork) {
            applyHeaderOnly(record, selection, baseContent);
            return;
        }
        SnippetEditDialog.AiAssist assist = host.aiAssist();
        if (!aiAllowed() || assist == null || assist.improvementFixProvider() == null) {
            host.setStatus(I18n.get("snippets.ai.analysis.panel.aiUnavailable"));
            return;
        }
        // Fix: the question comes first. It used to come after the progress window had opened,
        // so dismissing it left a window stuck on "Preparing…".
        if (storedRequest == null && !host.applyCodeTextLanguage(true)) {
            refreshControls();
            return;
        }
        if (key != null && !store.tryClaimRun(key, this)) {
            host.setStatus(I18n.get("snippets.ai.analysis.run.otherWindow"));
            return;
        }
        // Everything the worker needs is read here, on the FX thread.
        String codeTextLanguageCode = selection.codeTextLanguageCode() != null
            && !selection.codeTextLanguageCode().isBlank()
            ? selection.codeTextLanguageCode()
            : host.codeTextFallbackLanguageCode();
        String extra = storedRequest != null ? storedRequest.additionalInstructions() : host.additionalInstructions();
        String classicHardening = storedRequest != null
            ? storedRequest.classicHardeningInstructions()
            : host.hardeningInstructions(selection.hardening());
        String inputHardening = storedRequest != null
            ? storedRequest.inputHardeningInstructions()
            : host.inputHardeningInstructions(selection.inputHardening());
        List<SnippetAiWorkflowSupport.ImprovementApplyProgress> plan =
            SnippetAiWorkflowSupport.planSnippetImprovements(
                selection.improvements(), selection.dependencies(), classicHardening, inputHardening,
                selection.migration(), baseContent);
        // Fix: the apply runs on the profile that produced the analysis, not on the default one.
        String profileId = storedRequest != null && storedRequest.aiProfileId() != null
            ? storedRequest.aiProfileId()
            : record != null ? blankToNull(record.provenance().profileId()) : null;
        String profileName = record != null && !record.provenance().profileName().isBlank()
            ? record.provenance().profileName()
            : SnippetAiDialogSupport.resolveProfileDisplayName(profileId);

        SnippetAiApplyProgressPane pane = new SnippetAiApplyProgressPane(plan, profileName);
        String runId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();
        AtomicReference<SnippetAiWorkflowSupport.ImprovementApplyCheckpoint> checkpointRef =
            new AtomicReference<>(resumeFrom);
        AtomicReference<SnippetAnalysisRecord.Provenance> provenanceRef = new AtomicReference<>();
        String recordId = record != null ? record.id() : null;

        Task<SnippetAiResponseSupport.SnippetSecurityFix> task = new Task<>() {
            @Override
            protected SnippetAiResponseSupport.SnippetSecurityFix call() throws Exception {
                return assist.improvementFixProvider().applyFixes(new SnippetEditDialog.ImprovementApplyRequest(
                    baseContent,
                    language,
                    codeTextLanguageCode,
                    selection.improvements(),
                    selection.dependencies(),
                    extra,
                    classicHardening,
                    inputHardening,
                    progress -> {
                        pane.accept(progress);
                        if (progress.state() != SnippetAiWorkflowSupport.ImprovementApplyProgressState.PENDING) {
                            updateMessage(SnippetEditDialog.improvementApplyProgressText(progress));
                        }
                        long completedStages = progress.state()
                                == SnippetAiWorkflowSupport.ImprovementApplyProgressState.COMPLETED
                            ? progress.stage()
                            : progress.stage() - 1L;
                        updateProgress(completedStages, Math.max(1, progress.totalStages()));
                    },
                    checkpoint -> {
                        checkpointRef.set(checkpoint);
                        Platform.runLater(() -> persistCheckpoint(recordId, runId, checkpoint));
                    },
                    resumeFrom,
                    profileId,
                    selection.migration(),
                    provenanceRef::set));
            }
        };
        ActiveRun run = new ActiveRun(task, recordId, runId, pane, startedAt);
        activeRun = run;
        runContexts.put(runId, new RunContext(selection, baseContent, resumeFrom));
        if (recordId != null) {
            ApplyRun started = ApplyRun.started(runId, startedAt,
                snapshot(selection, language, codeTextLanguageCode, extra, classicHardening, inputHardening,
                    profileId, baseContent),
                pane.workItemStates(), null);
            updateRecord(recordId, r -> r.withRun(started));
        }
        pane.setOnCancel(this::cancelActiveRun);
        pane.start();
        host.followTaskProgress(task);
        host.beginAiAction(task);
        showPanel();

        task.setOnRunning(event -> {
            String message = task.getMessage() != null && !task.getMessage().isBlank()
                ? task.getMessage()
                : I18n.get("snippets.ai.analysis.fix.running");
            host.showAiHint(message);
            host.setStatus(message);
        });
        task.setOnSucceeded(event -> {
            host.finishAiAction(task);
            finishRun(run);
            if (run.silentCancel) {
                return;
            }
            SnippetAiResponseSupport.SnippetSecurityFix fix = task.getValue();
            long now = System.currentTimeMillis();
            if (fix == null || !fix.isUsable()) {
                pane.markFailed();
                recordFailure(run, RunOutcome.FAILED, "snippets.ai.analysis.fix.empty", now);
                host.setStatus(I18n.get("snippets.ai.analysis.fix.empty"));
                refreshState();
                return;
            }
            // Reject incomplete full replacements, including omission comments such as "rest unchanged".
            if (SnippetAiResponseSupport.isDegenerateFullReplacement(baseContent, fix.replacement())) {
                pane.markFailed();
                recordFailure(run, RunOutcome.FAILED, "snippets.ai.fix.degenerate", now);
                host.setStatus(I18n.get("snippets.ai.fix.degenerate"));
                refreshState();
                return;
            }
            pane.markSucceeded();
            String replacement = host.injectSelectedHeader(selection, fix.replacement());
            List<String> applied = selectedFindingIds(selection);
            SnippetAnalysisRecord.Provenance resolved = provenanceRef.get();
            if (recordId != null) {
                updateRun(recordId, runId, current -> current.withItems(pane.workItemStates()).withResult(
                    now, false, replacement, fix.summary(),
                    fix.changes().stream().map(SnippetAnalysisRecord.Change::from).toList(),
                    fix.implementedRequirements(), pane.completedWorkItemIds(), stats(run, null), resolved));
            }
            pane.setOnReviewChanges(() -> openReview(recordId, runId));
            openReviewPane(new Review(recordId, runId, baseContent, replacement, false, applied, null, selection),
                fix.summary(), fix.changes());
            host.setStatus(I18n.get("snippets.ai.analysis.review.waiting"));
            refreshState();
        });
        task.setOnFailed(event -> {
            finishRun(run);
            host.handleAiActionFailure(task, I18n.get("snippets.ai.analysis.fix.failed"));
            if (resumeFrom != null && task.getException() instanceof IllegalArgumentException) {
                // WF checks that the rebuilt plan still has the checkpoint's stage count.
                host.setStatus(I18n.get("snippets.ai.analysis.fix.resume.replan"));
            }
            if (run.silentCancel) {
                return;
            }
            pane.markFailed();
            SnippetAiWorkflowSupport.ImprovementApplyCheckpoint checkpoint = checkpointRef.get();
            runContexts.put(runId, new RunContext(selection, baseContent, checkpoint));
            recordFailure(run, RunOutcome.FAILED, "snippets.ai.analysis.fix.failed", System.currentTimeMillis());
            offerRecovery(recordId, runId, pane, checkpoint, false);
            refreshState();
        });
        task.setOnCancelled(event -> {
            finishRun(run);
            host.finishAiAction(task);
            if (run.silentCancel) {
                return;
            }
            pane.markCancelled();
            SnippetAiWorkflowSupport.ImprovementApplyCheckpoint checkpoint = checkpointRef.get();
            runContexts.put(runId, new RunContext(selection, baseContent, checkpoint));
            recordFailure(run, RunOutcome.CANCELLED, "snippets.ai.analysis.progress.cancelled",
                System.currentTimeMillis());
            offerRecovery(recordId, runId, pane, checkpoint, true);
            refreshState();
        });
        refreshState();
        AiTaskRunner.start(task, "snippet-ai-improvement-fix");
    }

    /** Cancels the running apply (the progress pane's Cancel); the recovery offer follows inline. */
    void cancelActiveRun() {
        ActiveRun run = activeRun;
        if (run != null && !run.task.isDone()) {
            run.task.cancel(true);
        }
    }

    boolean isApplyRunning() {
        return activeRun != null;
    }

    private void finishRun(ActiveRun run) {
        if (activeRun == run) {
            activeRun = null;
        }
        livePanes.put(run.runId, run.pane);
        if (key != null) {
            store.releaseRun(key, this);
        }
    }

    private void recordFailure(ActiveRun run, RunOutcome outcome, String failureKey, long at) {
        if (run.recordId == null) {
            return;
        }
        updateRun(run.recordId, run.runId, current -> current.withItems(run.pane.workItemStates())
            .withFailure(outcome, at, failureKey, stats(run, failureKey)));
    }

    private void persistCheckpoint(String recordId, String runId,
                                   SnippetAiWorkflowSupport.ImprovementApplyCheckpoint checkpoint) {
        if (disposed || recordId == null || checkpoint == null) {
            return;
        }
        SnippetAnalysisRecord.StoredCheckpoint stored = SnippetAnalysisRecord.StoredCheckpoint.from(checkpoint);
        updateRun(recordId, runId, current -> current.withCheckpoint(stored));
    }

    private SnippetAnalysisRecord.RunStats stats(ActiveRun run, String statusKey) {
        SnippetAiApplyProgressPane.RunSummary summary = run.pane.currentSummary();
        return new SnippetAnalysisRecord.RunStats(
            summary.elapsedSeconds(),
            SnippetAnalysisRecord.Usage.from(summary.usage()),
            summary.retries(),
            summary.completedItems(),
            summary.totalItems(),
            statusKey);
    }

    private SnippetAnalysisRecord.ApplyRequestSnapshot snapshot(
            SnippetAnalysisPanel.ApplySelection selection, String language, String codeTextLanguageCode,
            String extra, String classicHardening, String inputHardening, String profileId, String baseContent) {
        SnippetAiWorkflowSupport.MigrationPlan migration = selection.migration();
        return new SnippetAnalysisRecord.ApplyRequestSnapshot(
            language,
            codeTextLanguageCode,
            selection.improvements().stream().map(SnippetAiResponseSupport.ScriptImprovement::id).toList(),
            selection.dependencies().stream().map(SnippetAiResponseSupport.ScriptDependency::id).toList(),
            extra,
            selection.hardening().stream().map(Enum::name).toList(),
            classicHardening,
            selection.inputHardening().isEnabled()
                ? selection.inputHardening().options().stream().map(Enum::name).toList()
                : List.of(),
            inputHardening,
            migration != null && migration.targetLanguage() != null ? migration.targetLanguage().name() : null,
            migration != null && migration.targetHostFormat() != null ? migration.targetHostFormat().name() : null,
            null,
            null,
            null,
            selection.headerText(),
            profileId,
            SnippetDiagramSupport.contentHash(baseContent),
            SnippetAnalysisRecord.capContent(baseContent));
    }

    /** A chosen header alone: a deterministic prepend, still reviewed in the editor area. */
    private void applyHeaderOnly(SnippetAnalysisRecord record, SnippetAnalysisPanel.ApplySelection selection,
                                 String baseContent) {
        String updated = host.injectSelectedHeader(selection, baseContent);
        if (updated.equals(baseContent)) {
            host.setStatus(I18n.get("snippets.ai.analysis.fix.empty"));
            return;
        }
        String runId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        if (record != null) {
            ApplyRun run = ApplyRun.started(runId, now,
                snapshot(selection, host.snippetLanguage(), selection.codeTextLanguageCode(),
                    host.additionalInstructions(), "", "", null, baseContent),
                List.of(), null)
                .withResult(now, false, updated, I18n.get("snippets.ai.analysis.header.applied"),
                    List.of(), List.of(), List.of(), SnippetAnalysisRecord.RunStats.EMPTY, null);
            updateRecord(record.id(), r -> r.withRun(run));
        }
        openReviewPane(new Review(record != null ? record.id() : null, runId, baseContent, updated, false,
                List.of(), null, selection),
            I18n.get("snippets.ai.analysis.header.applied"), List.of());
    }

    /** The finding ids a selection covers (what an accepted full run counts as applied). */
    static List<String> selectedFindingIds(SnippetAnalysisPanel.ApplySelection selection) {
        if (selection == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        selection.improvements().forEach(item -> ids.add(item.id()));
        selection.dependencies().forEach(item -> ids.add(item.id()));
        return List.copyOf(ids);
    }

    // =====================================================================================
    // Recovery (inline, never an alert)
    // =====================================================================================

    private void offerRecovery(String recordId, String runId, SnippetAiApplyProgressPane pane,
                               SnippetAiWorkflowSupport.ImprovementApplyCheckpoint checkpoint, boolean cancelled) {
        if (!SnippetEditDialog.shouldOfferImprovementApplyRecovery(checkpoint, false, disposed)) {
            return;
        }
        RunContext context = runContexts.get(runId);
        Runnable onResume = context != null && SnippetEditDialog.improvementApplyResumeOffered(checkpoint)
            ? () -> resume(runId)
            : null;
        pane.setRecovery(new SnippetAiApplyProgressPane.Recovery(
            checkpoint.completedStages(), checkpoint.totalStages(), cancelled,
            onResume,
            () -> previewPartial(recordId, runId, checkpoint, context),
            () -> {
                dismissedRecoveries.add(runId);
                pane.setRecovery(null);
            }));
    }

    private void resume(String runId) {
        RunContext context = runContexts.get(runId);
        if (context == null || context.checkpoint() == null) {
            return;
        }
        SnippetAiApplyProgressPane old = livePanes.remove(runId);
        if (old != null) {
            old.setRecovery(null);
        }
        host.setStatus(I18n.get("snippets.ai.analysis.fix.resuming",
            context.checkpoint().completedStages() + 1, context.checkpoint().totalStages()));
        startApply(context.selection(), context.checkpoint(), context.baseContent(), true);
    }

    /** Why a stored run can (or cannot) be resumed after a restart. */
    enum ResumeVerdict { OK, NOT_ELIGIBLE, CONTENT_CHANGED, PLAN_CHANGED }

    /**
     * A stored run rebuilt for resuming: the selection (findings by id, options by name, the
     * migration re-detected from the base content plus the stored targets), the stored request
     * strings and the checkpoint. Only {@link ResumeVerdict#OK} carries the parts.
     */
    record StoredResume(ResumeVerdict verdict, SnippetAnalysisPanel.ApplySelection selection,
                        SnippetAnalysisRecord.ApplyRequestSnapshot request,
                        SnippetAiWorkflowSupport.ImprovementApplyCheckpoint checkpoint, String baseContent) {
        static StoredResume of(ResumeVerdict verdict) {
            return new StoredResume(verdict, null, null, null, null);
        }
    }

    /**
     * Whether {@code run} can continue where it stopped: INTERRUPTED/FAILED/CANCELLED between two
     * stages with its base content stored, the editor holding exactly that base, every selected
     * finding still in the record and the rebuilt plan having the checkpoint's stage count.
     */
    static StoredResume storedResume(SnippetAnalysisRecord record, ApplyRun run, String currentContent) {
        if (record == null || run == null || !run.isResumable()) {
            return StoredResume.of(ResumeVerdict.NOT_ELIGIBLE);
        }
        if (!resumeAllowed(run, SnippetDiagramSupport.contentHash(currentContent != null ? currentContent : ""))) {
            return StoredResume.of(ResumeVerdict.CONTENT_CHANGED);
        }
        SnippetAnalysisRecord.ApplyRequestSnapshot request = run.request();
        String base = request.baseContent();
        SnippetAiWorkflowSupport.MigrationPlan migration;
        try {
            migration = migrationFromSnapshot(request, base);
        } catch (IllegalArgumentException e) {
            return StoredResume.of(ResumeVerdict.PLAN_CHANGED);
        }
        SnippetAnalysisPanel.ApplySelection stored = selectionFromSnapshot(record, run);
        if (stored == null || stored.improvements().size() != request.improvementIds().size()
                || stored.dependencies().size() != request.dependencyIds().size()) {
            return StoredResume.of(ResumeVerdict.PLAN_CHANGED);
        }
        SnippetAnalysisPanel.ApplySelection selection = new SnippetAnalysisPanel.ApplySelection(
            stored.improvements(), stored.dependencies(), stored.hardening(), stored.inputHardening(),
            stored.headerText(), migration, stored.codeTextLanguageCode());
        SnippetAiWorkflowSupport.ImprovementApplyCheckpoint checkpoint = run.checkpoint().toCheckpoint();
        int stages;
        try {
            stages = SnippetAiWorkflowSupport.planSnippetImprovements(selection.improvements(),
                selection.dependencies(), request.classicHardeningInstructions(),
                request.inputHardeningInstructions(), migration, base).size();
        } catch (IllegalArgumentException e) {
            return StoredResume.of(ResumeVerdict.PLAN_CHANGED);
        }
        if (stages != checkpoint.totalStages()) {
            return StoredResume.of(ResumeVerdict.PLAN_CHANGED);
        }
        return new StoredResume(ResumeVerdict.OK, selection, request, checkpoint, base);
    }

    /** The migration order of a stored request: detected from the base content plus the stored targets. */
    static SnippetAiWorkflowSupport.MigrationPlan migrationFromSnapshot(
            SnippetAnalysisRecord.ApplyRequestSnapshot request, String baseContent) {
        if (request == null || (request.migrationTargetLanguage() == null
                && request.migrationTargetHostFormat() == null)) {
            return null;
        }
        WorkflowScriptSupport.ScriptLanguage target = request.migrationTargetLanguage() != null
            ? WorkflowScriptSupport.ScriptLanguage.valueOf(request.migrationTargetLanguage())
            : null;
        ScriptLanguageMixSupport.HostFormat host = request.migrationTargetHostFormat() != null
            ? ScriptLanguageMixSupport.HostFormat.valueOf(request.migrationTargetHostFormat())
            : null;
        return new SnippetAiWorkflowSupport.MigrationPlan(
            ScriptLanguageMixSupport.detect(request.snippetLanguage(), baseContent != null ? baseContent : ""),
            target, host);
    }

    /** Resume after a restart: rebuilt from the stored request, re-checked right before starting. */
    private void resumeStored(String recordId, String runId) {
        SnippetAnalysisRecord record = findRecord(recordId);
        ApplyRun run = record != null ? record.findRun(runId) : null;
        StoredResume resume = storedResume(record, run, safe(host.currentContent()));
        switch (resume.verdict()) {
            case OK -> {
                host.setStatus(I18n.get("snippets.ai.analysis.fix.resuming",
                    resume.checkpoint().completedStages() + 1, resume.checkpoint().totalStages()));
                trackAction("code_review_resume", Map.of("after_restart", true));
                startApply(resume.selection(), resume.checkpoint(), resume.baseContent(), true, resume.request());
            }
            case CONTENT_CHANGED -> host.setStatus(I18n.get("snippets.ai.analysis.fix.resume.contentChanged"));
            case PLAN_CHANGED -> host.setStatus(I18n.get("snippets.ai.analysis.fix.resume.replan"));
            case NOT_ELIGIBLE -> host.setStatus(I18n.get("snippets.ai.analysis.panel.partialUnavailable"));
        }
        refreshState();
    }

    /**
     * Reviews an unfinished run's partial rewrite. It skips the cumulative verification (the
     * requirements of stages that never ran are missing by definition) but keeps the
     * degenerate-replacement guard; Accept still requires the content it started from.
     */
    private void previewPartial(String recordId, String runId,
                                SnippetAiWorkflowSupport.ImprovementApplyCheckpoint checkpoint, RunContext context) {
        if (checkpoint == null) {
            return;
        }
        String base = context != null ? context.baseContent() : baseContentOf(recordId, runId);
        if (base == null) {
            host.setStatus(I18n.get("snippets.ai.analysis.panel.partialUnavailable"));
            return;
        }
        SnippetAiResponseSupport.SnippetSecurityFix partial = checkpoint.toPartialFix();
        if (!partial.isUsable() || partial.replacement().equals(base)) {
            host.setStatus(I18n.get("snippets.ai.analysis.fix.empty"));
            return;
        }
        if (SnippetAiResponseSupport.isDegenerateFullReplacement(base, partial.replacement())) {
            host.setStatus(I18n.get("snippets.ai.fix.degenerate"));
            return;
        }
        SnippetAnalysisPanel.ApplySelection selection = context != null ? context.selection() : null;
        String replacement = selection != null
            ? host.injectSelectedHeader(selection, partial.replacement())
            : partial.replacement();
        List<String> completed = partialFindingIds(findRecord(recordId), checkpoint);
        long now = System.currentTimeMillis();
        if (recordId != null) {
            updateRun(recordId, runId, current -> current.withResult(now, true, replacement, partial.summary(),
                partial.changes().stream().map(SnippetAnalysisRecord.Change::from).toList(),
                partial.implementedRequirements(), completed, current.stats(), current.provenance()));
        }
        openReviewPane(new Review(recordId, runId, base, replacement, true, completed, null, selection),
            partial.summary(), partial.changes());
    }

    /** The finding ids a partial run completed: its checkpoint's requirement ids that name a finding. */
    static List<String> partialFindingIds(SnippetAnalysisRecord record,
                                          SnippetAiWorkflowSupport.ImprovementApplyCheckpoint checkpoint) {
        if (record == null || checkpoint == null) {
            return List.of();
        }
        Set<String> findings = record.allFindingIds();
        return checkpoint.completedRequirementIds().stream().filter(findings::contains).distinct().toList();
    }

    // =====================================================================================
    // Review (the diff replaces the editor area)
    // =====================================================================================

    /** Re-opens the stored result of a run that waits for review (also after a restart). */
    void openReview(String recordId, String runId) {
        if (disposed) {
            return;
        }
        if (review != null && Objects.equals(review.runId(), runId)) {
            host.showInEditorArea(review.pane());
            return;
        }
        SnippetAnalysisRecord record = findRecord(recordId);
        ApplyRun run = record != null ? record.findRun(runId) : null;
        if (run == null || run.outcome() != RunOutcome.PENDING_REVIEW || run.resultContent() == null) {
            host.setStatus(I18n.get("snippets.ai.analysis.review.unavailable"));
            return;
        }
        String base = baseContentOf(recordId, runId);
        if (base == null) {
            host.setStatus(I18n.get("snippets.ai.analysis.review.unavailable"));
            return;
        }
        trackAction("code_review_reopen", Map.of());
        RunContext context = runContexts.get(runId);
        List<String> applied = run.partial()
            ? run.completedWorkItemIds().stream().filter(record.allFindingIds()::contains).toList()
            : concat(run.request().improvementIds(), run.request().dependencyIds());
        openReviewPane(new Review(recordId, runId, base, run.resultContent(), run.partial(), applied, null,
                context != null ? context.selection() : null),
            run.summary(), run.changes().stream().map(SnippetAnalysisRecord.Change::toSecurityChange).toList());
    }

    private void openReviewPane(Review pending, String summary, List<SnippetAiResponseSupport.SecurityChange> changes) {
        if (review != null) {
            closeReview();
        }
        SnippetAiDiffPane pane = new SnippetAiDiffPane(summary, pending.baseContent(), pending.replacement(),
            host.snippetLanguage(), host.editorSettings(), true);
        if (changes != null && !changes.isEmpty()) {
            pane.setChangeExplanations(changes);
        }
        Review shown = new Review(pending.recordId(), pending.runId(), pending.baseContent(), pending.replacement(),
            pending.partial(), pending.appliedFindingIds(), pane, pending.selection());
        review = shown;
        pane.setOnDecision(decision -> decide(shown, decision));
        guardReviewContent(shown);
        host.showInEditorArea(pane);
        Platform.runLater(pane::fitSummaryHeight);
        refreshState();
    }

    /** Accept only applies to the content the run started from; otherwise offer a re-plan. */
    private void guardReviewContent(Review shown) {
        if (host.contentUnchangedSince(shown.baseContent())) {
            shown.pane().showBlockingNotice(null);
            return;
        }
        Runnable replan = replanAction(shown);
        shown.pane().showBlockingNotice(I18n.get("snippets.ai.analysis.review.contentChanged"),
            replan != null ? I18n.get("snippets.ai.analysis.review.replan") : null, replan);
    }

    private Runnable replanAction(Review shown) {
        SnippetAnalysisPanel.ApplySelection selection = shown.selection();
        if (selection == null) {
            SnippetAnalysisRecord record = findRecord(shown.recordId());
            ApplyRun run = record != null ? record.findRun(shown.runId()) : null;
            selection = run != null ? selectionFromSnapshot(record, run) : null;
        }
        if (selection == null || selection.isEmpty()) {
            return null;
        }
        SnippetAnalysisPanel.ApplySelection chosen = selection;
        return () -> {
            Review current = review;
            if (current != null) {
                if (current.recordId() != null) {
                    updateRun(current.recordId(), current.runId(),
                        run -> run.withOutcome(RunOutcome.REJECTED, System.currentTimeMillis()));
                }
                closeReview();
            }
            if (shown.recordId() != null) {
                staleConfirmed.add(shown.recordId());
            }
            startApply(chosen, null, null, true);
        };
    }

    /** Rebuilds an apply selection from a stored run (findings by id, options by name). */
    static SnippetAnalysisPanel.ApplySelection selectionFromSnapshot(SnippetAnalysisRecord record, ApplyRun run) {
        if (record == null || run == null) {
            return null;
        }
        SnippetAnalysisRecord.ApplyRequestSnapshot request = run.request();
        List<SnippetAiResponseSupport.ScriptImprovement> improvements = record.improvements().stream()
            .filter(finding -> request.improvementIds().contains(finding.id()))
            .map(SnippetAnalysisRecord.Finding::toImprovement)
            .toList();
        List<SnippetAiResponseSupport.ScriptDependency> dependencies = record.dependencies().stream()
            .filter(finding -> request.dependencyIds().contains(finding.id()))
            .map(SnippetAnalysisRecord.DependencyFinding::toDependency)
            .toList();
        EnumSet<HardeningOption> hardening =
            SnippetAnalysisPanel.parseEnums(HardeningOption.class, request.hardeningOptions());
        InputHardeningConfig input = request.inputHardeningOptions().isEmpty()
            ? InputHardeningConfig.disabled()
            : new InputHardeningConfig(SnippetAnalysisPanel.parseEnums(
                WorkflowScriptSupport.InputHardeningOption.class, request.inputHardeningOptions()),
                InputHardeningConfig.DEFAULT_MAX_FILE_SIZE_BYTES);
        return new SnippetAnalysisPanel.ApplySelection(improvements, dependencies, hardening, input,
            request.headerText().isBlank() ? null : request.headerText(), null, request.codeTextLanguageCode());
    }

    private void decide(Review shown, SnippetAiDiffPane.Decision decision) {
        if (review != shown || disposed) {
            return;
        }
        long now = System.currentTimeMillis();
        switch (decision) {
            case ACCEPT -> {
                if (!host.contentUnchangedSince(shown.baseContent())) {
                    guardReviewContent(shown);
                    return;
                }
                host.applyFullReplacement(shown.baseContent(), shown.replacement(),
                    I18n.get("snippets.ai.toggle.action.improve"));
                String editorContent = safe(host.currentContent());
                if (shown.recordId() != null) {
                    updateRun(shown.recordId(), shown.runId(),
                        run -> run.accepted(now, shown.appliedFindingIds(), editorContent));
                }
                closeReview();
                host.setStatus(I18n.get(shown.partial()
                    ? "snippets.ai.analysis.review.partialAccepted"
                    : "snippets.ai.analysis.fix.applied"));
            }
            case REJECT -> {
                if (shown.recordId() != null) {
                    updateRun(shown.recordId(), shown.runId(), run -> run.withOutcome(RunOutcome.REJECTED, now));
                }
                closeReview();
                host.setStatus(I18n.get("snippets.ai.analysis.review.rejected"));
            }
            case REVIEW_LATER -> {
                trackAction("code_review_review_later", Map.of("stored", shown.recordId() != null));
                closeReview();
                host.setStatus(I18n.get(shown.recordId() != null
                    ? "snippets.ai.analysis.review.later"
                    : "snippets.ai.analysis.review.laterNotStored"));
            }
        }
        refreshState();
    }

    private void closeReview() {
        Review shown = review;
        review = null;
        if (shown != null) {
            host.restoreEditorArea(shown.pane());
            shown.pane().dispose();
        }
    }

    /** Whether a change review currently replaces the editor area. */
    boolean isReviewShowing() {
        return review != null;
    }

    /** The review pane currently shown, or {@code null}. */
    SnippetAiDiffPane reviewPane() {
        return review != null ? review.pane() : null;
    }

    private String baseContentOf(String recordId, String runId) {
        SnippetAnalysisRecord record = findRecord(recordId);
        ApplyRun run = record != null ? record.findRun(runId) : null;
        if (run == null) {
            return null;
        }
        if (run.request().baseContent() != null) {
            return run.request().baseContent();
        }
        String sourceContent = record.source().content();
        return sourceContent != null && run.request().baseSha256().equals(record.source().sha256())
            ? sourceContent
            : null;
    }

    // =====================================================================================
    // Pure helpers (unit-tested)
    // =====================================================================================

    /** Whether {@code record} still describes the content with hash {@code currentSha}. */
    static Staleness staleness(SnippetAnalysisRecord record, String currentSha) {
        if (record == null || record.source().sha256().isBlank()) {
            return Staleness.NONE;
        }
        return record.source().sha256().equals(currentSha) ? Staleness.FRESH : Staleness.STALE;
    }

    /**
     * Whether a stored run may be resumed against the current content: it stopped between stages
     * with its base content kept, and the editor still holds exactly that base.
     */
    static boolean resumeAllowed(ApplyRun run, String currentSha) {
        return run != null && run.isResumable() && currentSha != null
            && currentSha.equals(run.request().baseSha256());
    }

    /** Every finding id the accepted runs of {@code record} count as applied. */
    static Set<String> appliedFindingIds(SnippetAnalysisRecord record) {
        Set<String> ids = new LinkedHashSet<>();
        if (record != null) {
            for (ApplyRun run : record.applyRuns()) {
                if (run.isAccepted()) {
                    ids.addAll(run.appliedFindingIds());
                }
            }
        }
        return ids;
    }

    /** The finding tokens ({@code imp:ID}, {@code dep:ID}) a stored selection ticks. */
    static List<String> selectionTokens(SnippetAnalysisRecord.SelectionState state) {
        if (state == null) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        state.improvementIds().forEach(id -> tokens.add("imp:" + id));
        state.dependencyIds().forEach(id -> tokens.add("dep:" + id));
        return List.copyOf(tokens);
    }

    /**
     * Drops ids from a stored selection that the record does not contain (a finding the model no
     * longer reports), keeping the order.
     */
    static SnippetAnalysisRecord.SelectionState retainKnownFindings(SnippetAnalysisRecord record,
                                                                   SnippetAnalysisRecord.SelectionState state) {
        if (record == null || state == null) {
            return state;
        }
        Set<String> improvements = new HashSet<>();
        record.improvements().forEach(f -> improvements.add(f.id()));
        Set<String> dependencies = new HashSet<>();
        record.dependencies().forEach(d -> dependencies.add(d.id()));
        return new SnippetAnalysisRecord.SelectionState(
            state.improvementIds().stream().filter(improvements::contains).toList(),
            state.dependencyIds().stream().filter(dependencies::contains).toList(),
            state.hardening(), state.inputHardeningEnabled(), state.inputHardeningOptions(),
            state.inputHardeningMaxFileSizeBytes(), state.headerSnippetId(), state.migrationTargetLanguage(),
            state.migrationTargetHostFormat(), state.codeTextLanguageCode(), state.updatedAt());
    }

    /** The toggle badge: running beats stale beats "an analysis exists". */
    static String badge(boolean running, SnippetAnalysisRecord current, String currentSha) {
        if (running) {
            return BADGE_RUNNING;
        }
        if (current == null) {
            return "";
        }
        return staleness(current, currentSha) == Staleness.STALE ? BADGE_STALE : BADGE_OPEN;
    }

    // =====================================================================================
    // Rendering
    // =====================================================================================

    private void onHistoryChanged(SnippetAnalysisHistory next) {
        if (disposed || next == null) {
            return;
        }
        if (key != null && !next.snippetId().isBlank() && !key.equals(next.snippetId())) {
            return;
        }
        history = next;
        if (shownRecordId != null && next.find(shownRecordId) == null) {
            shownRecordId = null;
        }
        render();
    }

    private void refreshState() {
        if (disposed) {
            return;
        }
        render();
    }

    private void render() {
        refreshBadge();
        if (!panelVisible || sidePanel == null) {
            return;
        }
        SnippetAnalysisRecord record = shownRecord();
        renderHistoryCombo();
        renderContent(record);
        renderBanners(record);
        renderProgress(record);
        refreshControls();
    }

    private void refreshBadge() {
        SnippetAnalysisRecord current = history != null ? history.current() : null;
        boolean running = activeRun != null || analysisTask != null;
        String currentSha = SnippetDiagramSupport.contentHash(safe(host.currentContent()));
        String badge = badge(running, current, currentSha);
        String tooltip;
        if (running) {
            tooltip = I18n.get(activeRun != null
                ? "snippets.ai.analysis.panel.toggle.applying"
                : "snippets.ai.analysis.panel.toggle.analyzing");
        } else if (current == null) {
            tooltip = I18n.get("snippets.ai.analysis.panel.toggle.tooltip");
        } else if (BADGE_STALE.equals(badge)) {
            tooltip = I18n.get("snippets.ai.analysis.stale.tooltip", formatTime(current.analyzedAt()));
        } else {
            tooltip = I18n.get("snippets.ai.analysis.panel.toggle.stored", formatTime(current.analyzedAt()));
        }
        host.panelStateChanged(panelVisible, badge, tooltip);
    }

    private void ensureSidePanel() {
        if (sidePanel != null) {
            return;
        }
        Label title = new Label(I18n.get("snippets.ai.analysis.panel.title"));
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 1.08em;");
        historyCombo = new ComboBox<>();
        historyCombo.setId(HISTORY_COMBO_ID);
        historyCombo.setTooltip(new Tooltip(I18n.get("snippets.ai.analysis.history.tooltip")));
        historyCombo.setMaxWidth(Double.MAX_VALUE);
        historyCombo.setMinWidth(80);
        historyCombo.setCellFactory(list -> new HistoryCell());
        historyCombo.setButtonCell(new HistoryCell());
        historyCombo.valueProperty().addListener((obs, was, isNow) -> {
            if (!updatingHistoryCombo && isNow != null) {
                selectRecord(isNow);
            }
        });
        HBox.setHgrow(historyCombo, Priority.ALWAYS);
        headerStatusLabel = new Label();
        headerStatusLabel.setStyle("-fx-opacity: 0.8; -fx-font-size: 0.9231em;");
        plainRerunButton = new Button(I18n.get("snippets.ai.rerun"));
        plainRerunButton.setId("snippet-analysis-plain-rerun");
        plainRerunButton.setTooltip(new Tooltip(I18n.get("snippets.ai.rerun.hint")));
        plainRerunButton.setOnAction(event -> {
            SnippetAnalysisRecord record = shownRecord();
            runAnalysis(record != null ? blankToNull(record.provenance().profileId()) : null);
        });
        historyActions = buildHistoryActions();
        Button closeButton = new Button("✕");
        closeButton.setId("snippet-analysis-close");
        closeButton.setTooltip(new Tooltip(I18n.get("snippets.ai.analysis.panel.hide")));
        closeButton.setOnAction(event -> hidePanel());
        HBox header = new HBox(8, title, historyCombo, historyActions, plainRerunButton, closeButton);
        header.setAlignment(Pos.CENTER_LEFT);

        bannerBox = new VBox(6);
        bannerBox.setId(BANNERS_ID);
        // Banners never push the report and Apply out of a short panel: past a few lines they scroll.
        bannerScroll = new ScrollPane(bannerBox);
        bannerScroll.setFitToWidth(true);
        bannerScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        bannerScroll.setMaxHeight(150);
        bannerScroll.setMinHeight(0);
        bannerScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-padding: 0;");
        // Shrink-wrap the banners up to the cap; a ScrollPane would otherwise ask for its default height.
        bannerScroll.prefHeightProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
            () -> Math.min(150, bannerBox.getHeight() + 2), bannerBox.heightProperty()));

        contentHolder = new StackPane();
        contentHolder.setMinHeight(0);
        VBox.setVgrow(contentHolder, Priority.ALWAYS);

        progressHolder = new VBox();
        progressHolder.setId(PROGRESS_HOLDER_ID);
        progressHolder.setPrefHeight(300);
        progressHolder.setMinHeight(0);
        progressHolder.setMaxHeight(360);

        applyButton = new Button(SnippetAiDialogSupport.AI_ACTION_PREFIX + I18n.get("snippets.ai.analysis.applySelected"));
        applyButton.setId(APPLY_BUTTON_ID);
        applyButton.setOnAction(event -> applyFromPanel());
        applyButton.setMinWidth(Region.USE_PREF_SIZE);
        verifyButton = new Button(SnippetAiDialogSupport.AI_ACTION_PREFIX + I18n.get("snippets.ai.analysis.verify"));
        verifyButton.setId(VERIFY_BUTTON_ID);
        verifyButton.setTooltip(new Tooltip(I18n.get("snippets.ai.analysis.verify.tooltip")));
        verifyButton.setMinWidth(Region.USE_PREF_SIZE);
        verifyButton.setOnAction(event -> {
            SnippetAnalysisRecord record = shownRecord();
            if (record != null) {
                verify(record.id());
            }
        });
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(8, headerStatusLabel, footerSpacer, verifyButton, applyButton);
        footer.setAlignment(Pos.CENTER_LEFT);

        sidePanel = new VBox(8, header, bannerScroll, contentHolder, progressHolder, footer);
        sidePanel.setId(SIDE_PANEL_ID);
        sidePanel.setPadding(new Insets(8, 10, 10, 10));
        sidePanel.setMinWidth(MIN_PANEL_WIDTH);
        sidePanel.setPrefWidth(storedPanelWidth());
        sidePanel.getStyleClass().add("snippet-analysis-side-panel");
    }

    private final class HistoryCell extends ListCell<String> {
        @Override
        protected void updateItem(String recordId, boolean empty) {
            super.updateItem(recordId, empty);
            setText(empty || recordId == null ? "" : historyLabel(recordId));
        }
    }

    private String historyLabel(String recordId) {
        SnippetAnalysisRecord record = findRecord(recordId);
        if (record == null) {
            return "";
        }
        return historyEntryLabel(record, formatTime(record.analyzedAt()), statusText(statusOf(record)));
    }

    /**
     * One history picker entry: "date · profile · status · tokens · duration"; tokens and duration
     * only when they were reported, and a pin marker in front of a pinned record.
     */
    static String historyEntryLabel(SnippetAnalysisRecord record, String time, String status) {
        StringBuilder label = new StringBuilder();
        if (record.pinned()) {
            label.append(PIN_MARKER).append(' ');
        }
        String profile = record.provenance().profileName().isBlank()
            ? I18n.get("snippets.ai.profile.default")
            : record.provenance().profileName();
        label.append(time).append(" · ").append(profile).append(" · ").append(status);
        long tokens = record.provenance().usage().totalTokens();
        if (tokens > 0) {
            label.append(" · ").append(I18n.get("snippets.ai.analysis.history.tokens",
                java.text.NumberFormat.getIntegerInstance().format(tokens)));
        }
        long millis = record.provenance().durationMillis();
        if (millis > 0) {
            label.append(" · ").append(de.kortty.core.AnalysisRunFormatting.formatDuration(
                Math.max(1L, Math.round(millis / 1000.0))));
        }
        return label.toString();
    }

    private RecordStatus statusOf(SnippetAnalysisRecord record) {
        if (history == null || record == null) {
            return RecordStatus.OPEN;
        }
        String saved = host.savedSnippetContent();
        return history.statusOf(record, saved != null ? SnippetDiagramSupport.contentHash(saved) : null);
    }

    private static String statusText(RecordStatus status) {
        return switch (status) {
            case OPEN -> I18n.get("snippets.ai.analysis.status.open");
            case ACCEPTED_NOT_SAVED -> I18n.get("snippets.ai.analysis.status.acceptedNotSaved");
            case PARTIALLY_APPLIED -> I18n.get("snippets.ai.analysis.status.partial");
            case APPLIED -> I18n.get("snippets.ai.analysis.status.applied");
            case SUPERSEDED -> I18n.get("snippets.ai.analysis.status.superseded");
        };
    }

    private void renderHistoryCombo() {
        if (historyCombo == null) {
            return;
        }
        updatingHistoryCombo = true;
        try {
            List<String> ids = history != null
                ? history.records().stream().map(SnippetAnalysisRecord::id).toList()
                : List.of();
            // The entries carry derived text (status, pin marker): re-set them when that changes too,
            // so the open list never shows a stale label.
            List<String> labels = ids.stream().map(this::historyLabel).toList();
            if (!historyCombo.getItems().equals(ids) || !labels.equals(renderedHistoryLabels)) {
                historyCombo.getItems().setAll(ids);
                renderedHistoryLabels = labels;
            }
            SnippetAnalysisRecord shown = shownRecord();
            historyCombo.setValue(shown != null ? shown.id() : null);
            // Labels carry the derived status; refresh the button cell too.
            historyCombo.setButtonCell(new HistoryCell());
            historyCombo.setVisible(!ids.isEmpty());
            historyCombo.setManaged(!ids.isEmpty());
        } finally {
            updatingHistoryCombo = false;
        }
    }

    /** Shows a history entry; the current one is followed again when picked. */
    void selectRecord(String recordId) {
        SnippetAnalysisRecord current = history != null ? history.current() : null;
        String next = current != null && current.id().equals(recordId) ? null : recordId;
        if (Objects.equals(next, shownRecordId)) {
            return;
        }
        flushSelection();
        shownRecordId = next;
        render();
    }

    private void renderContent(SnippetAnalysisRecord record) {
        if (record == null) {
            if (analysisPanel != null) {
                analysisPanel.dispose();
                analysisPanel = null;
                renderedRecordId = null;
            }
            contentHolder.getChildren().setAll(buildEmptyState());
            return;
        }
        if (analysisPanel != null && record.id().equals(renderedRecordId)) {
            analysisPanel.setAppliedFindings(appliedFindingIds(record));
            return;
        }
        if (analysisPanel != null) {
            flushSelection();
            analysisPanel.dispose();
        }
        String recordId = record.id();
        String profileId = blankToNull(record.provenance().profileId());
        boolean canRerun = aiAllowed() && host.hasCodeAnalysisProviders() && host.profileSwitchingSupported();
        SnippetAnalysisPanel panel = new SnippetAnalysisPanel(
            host.snippetName(),
            host.snippetLanguage(),
            record.toScriptAnalysis(),
            () -> diagramFor(recordId),
            profileId,
            canRerun ? this::runAnalysis : null,
            null,
            host.skillContext(),
            ScriptLanguageMixSupport.detect(host.snippetLanguage(), safe(host.currentContent())),
            record.selection() != null && record.selection().codeTextLanguageCode() != null
                ? record.selection().codeTextLanguageCode()
                : host.codeTextFallbackLanguageCode());
        panel.setExportSubjectSupplier(() -> exportSubject(recordId));
        panel.setExportListener((kind, runId, format, file) -> {
            boolean after = kind == SnippetAnalysisReport.Kind.POST_APPLY;
            trackAction("code_review_export", Map.of(
                "format", format.name().toLowerCase(java.util.Locale.ROOT),
                "phase", after ? "after" : "before"));
            updateRecord(recordId, r -> r.withExport(
                new SnippetAnalysisRecord.ExportEntry(System.currentTimeMillis(), format.name(),
                    after
                        ? SnippetAnalysisRecord.ExportEntry.PHASE_AFTER_APPLY
                        : SnippetAnalysisRecord.ExportEntry.PHASE_BEFORE_APPLY,
                    runId, file.getFileName().toString())));
        });
        panel.setVerification(verificationView(record, history));
        analysisPanel = panel;
        renderedRecordId = recordId;
        restoringSelection = true;
        try {
            panel.applySelectionState(retainKnownFindings(record, record.selection()));
        } finally {
            restoringSelection = false;
        }
        panel.setAppliedFindings(appliedFindingIds(record));
        panel.addSelectionListener(() -> {
            if (!restoringSelection && analysisPanel == panel) {
                selectionDebounce.playFromStart();
            }
        });
        ScrollPane scroll = new ScrollPane(panel);
        scroll.setId("snippet-analysis-scroll");
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMinHeight(0);
        contentHolder.getChildren().setAll(scroll);
        // Cached first: a stored diagram is shown as it is, never regenerated behind the user's back.
        SnippetDiagramView.DiagramSource cached = toDiagramSource(record, host.currentContent());
        if (cached != null) {
            panel.diagramView().showCached(cached);
        } else if (diagramJobs.containsKey(recordId)) {
            panel.diagramView().loadIfNeeded();
        } else {
            panel.startDiagramIfAutoEnabled();
        }
    }

    private Node buildEmptyState() {
        Label text = new Label(I18n.get("snippets.ai.analysis.panel.empty"));
        text.setWrapText(true);
        Button run = new Button(SnippetAiDialogSupport.AI_ACTION_PREFIX + I18n.get("snippets.ai.analysis.panel.run"));
        run.setId("snippet-analysis-run");
        run.setDisable(!aiAllowed() || !host.hasCodeAnalysisProviders() || analysisTask != null
            || host.isAnyAiTaskRunning());
        run.setOnAction(event -> runAnalysis(null));
        VBox box = new VBox(10, text, run);
        box.setId(EMPTY_STATE_ID);
        box.setPadding(new Insets(12, 4, 12, 4));
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    private void renderBanners(SnippetAnalysisRecord record) {
        if (bannerBox == null) {
            return;
        }
        List<Node> banners = new ArrayList<>();
        PendingConfirm confirm = pendingConfirm;
        if (confirm != null && history != null
                && (confirm.deletesAll() ? !history.isEmpty() : history.find(confirm.recordId()) != null)) {
            banners.add(confirmStrip(confirm));
        } else {
            pendingConfirm = null;
        }
        if (analysisTask != null && record != null) {
            banners.add(banner(RERUNNING_BANNER_ID, I18n.get(analysisPurpose == SnippetAnalysisRecord.Purpose.VERIFY
                ? "snippets.ai.analysis.verify.running"
                : "snippets.ai.analysis.rerunning"), BannerKind.INFO));
        }
        VerifySummary summary = record != null ? verifySummary(record) : null;
        if (summary != null) {
            SnippetAnalysisRecord verified = findRecord(record.verification().previousRecordId());
            banners.add(banner(VERIFY_BANNER_ID, I18n.get("snippets.ai.analysis.verify.banner",
                verified != null ? formatTime(verified.analyzedAt()) : "–",
                summary.resolved(), summary.persisting(), summary.introduced()), BannerKind.INFO));
        }
        if (record != null && history != null && !history.current().id().equals(record.id())) {
            Button back = new Button(I18n.get("snippets.ai.analysis.history.showCurrent"));
            back.setOnAction(event -> selectRecord(history.current().id()));
            banners.add(banner("snippet-analysis-superseded-banner",
                I18n.get("snippets.ai.analysis.history.superseded"), BannerKind.INFO, back));
        }
        boolean stale = record != null
            && staleness(record, SnippetDiagramSupport.contentHash(safe(host.currentContent()))) == Staleness.STALE;
        StalePrompt prompt = stalePrompt;
        if (stale && prompt != null && prompt.recordId().equals(record.id()) && activeRun == null) {
            banners.add(stalePromptBanner(record, prompt));
        } else if (stale) {
            banners.add(banner(STALE_BANNER_ID,
                I18n.get("snippets.ai.analysis.stale.banner", formatTime(record.analyzedAt())), BannerKind.WARNING));
        }
        if (record != null) {
            ApplyRun pending = record.applyRuns().stream()
                .filter(run -> run.outcome() == RunOutcome.PENDING_REVIEW)
                .reduce((first, second) -> second)
                .orElse(null);
            if (pending != null && (review == null || !pending.id().equals(review.runId()))
                    && (activeRun == null || !pending.id().equals(activeRun.runId))) {
                Button reviewButton = new Button(I18n.get("snippets.ai.analysis.review.open"));
                reviewButton.setId("snippet-analysis-review-open");
                String recordId = record.id();
                reviewButton.setOnAction(event -> openReview(recordId, pending.id()));
                banners.add(banner(REVIEW_BANNER_ID, I18n.get("snippets.ai.analysis.review.pending",
                    formatTime(pending.finishedAt())), BannerKind.INFO, reviewButton));
            }
            if (statusOf(record) == RecordStatus.ACCEPTED_NOT_SAVED) {
                banners.add(banner("snippet-analysis-unsaved-banner",
                    I18n.get("snippets.ai.analysis.status.acceptedNotSaved.banner"), BannerKind.INFO));
            }
        }
        if (key != null && store.isRunClaimedByOther(key, this)) {
            banners.add(banner("snippet-analysis-other-window-banner",
                I18n.get("snippets.ai.analysis.run.otherWindow"), BannerKind.WARNING));
        }
        if (!aiAllowed() || !host.hasCodeAnalysisProviders()) {
            banners.add(banner("snippet-analysis-policy-banner",
                I18n.get("snippets.ai.analysis.panel.viewOnly"), BannerKind.INFO));
        }
        if (history != null && history.lastWriteError() != null) {
            banners.add(banner("snippet-analysis-write-error-banner",
                I18n.get("snippets.ai.analysis.panel.writeError", history.lastWriteError()), BannerKind.WARNING));
        } else if (history != null && history.isReadOnly()) {
            banners.add(banner("snippet-analysis-read-only-banner",
                I18n.get("snippets.ai.analysis.panel.readOnly"), BannerKind.WARNING));
        }
        if (record != null && key != null && !store.isPersistable(key)) {
            banners.add(banner(NOT_PERSISTED_BANNER_ID, I18n.get(host.isTransientSnippet()
                ? "snippets.ai.analysis.notPersisted.transient"
                : "snippets.ai.analysis.notPersisted.draft"), BannerKind.INFO));
        }
        bannerBox.getChildren().setAll(banners);
        bannerScroll.setVisible(!banners.isEmpty());
        bannerScroll.setManaged(!banners.isEmpty());

    }

    private enum BannerKind { INFO, WARNING }

    private static Node banner(String id, String text, BannerKind kind, Button... actions) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(label, Priority.ALWAYS);
        HBox row = new HBox(8, label);
        row.getChildren().addAll(actions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setId(id);
        row.setStyle(kind == BannerKind.WARNING
            ? "-fx-background-color: rgba(245,158,11,0.14); -fx-border-color: rgba(245,158,11,0.55);"
                + " -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 6 8 6 8;"
            : "-fx-background-color: rgba(59,130,246,0.12); -fx-border-color: rgba(59,130,246,0.45);"
                + " -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 6 8 6 8;");
        return row;
    }

    /** Stale analysis: re-run first, or knowingly plan against the current content. */
    private void showStalePrompt(SnippetAnalysisRecord record, SnippetAnalysisPanel.ApplySelection selection) {
        stalePrompt = new StalePrompt(record.id(), selection);
        showPanel();
        host.setStatus(I18n.get("snippets.ai.analysis.stale.status"));
    }

    private Node stalePromptBanner(SnippetAnalysisRecord record, StalePrompt prompt) {
        Button rerun = new Button(I18n.get("snippets.ai.analysis.stale.rerunFirst"));
        rerun.setId("snippet-analysis-stale-rerun");
        rerun.setDisable(!aiAllowed() || !host.hasCodeAnalysisProviders());
        rerun.setOnAction(event -> {
            stalePrompt = null;
            runAnalysis(blankToNull(record.provenance().profileId()));
        });
        Button applyAnyway = new Button(I18n.get("snippets.ai.analysis.stale.applyCurrent"));
        applyAnyway.setId("snippet-analysis-stale-apply");
        applyAnyway.setOnAction(event -> {
            stalePrompt = null;
            staleConfirmed.add(record.id());
            startApply(prompt.selection(), null, null, true);
        });
        return banner("snippet-analysis-stale-prompt",
            I18n.get("snippets.ai.analysis.stale.prompt", formatTime(record.analyzedAt())), BannerKind.WARNING,
            rerun, applyAnyway);
    }

    private void renderProgress(SnippetAnalysisRecord record) {
        if (progressHolder == null) {
            return;
        }
        Node shown = null;
        if (activeRun != null && (record == null || Objects.equals(activeRun.recordId, record.id()))) {
            shown = activeRun.pane;
        } else if (record != null && !record.applyRuns().isEmpty()) {
            ApplyRun last = record.applyRuns().get(record.applyRuns().size() - 1);
            SnippetAiApplyProgressPane live = livePanes.get(last.id());
            if (live != null) {
                shown = live;
                if (last.outcome() != RunOutcome.PENDING_REVIEW) {
                    live.setOnReviewChanges(null);
                }
            } else {
                shown = restoredPaneFor(record, last);
            }
        } else if (activeRun != null && record == null) {
            shown = activeRun.pane;
        }
        if (shown == null) {
            progressHolder.getChildren().clear();
            progressHolder.setVisible(false);
            progressHolder.setManaged(false);
            return;
        }
        if (progressHolder.getChildren().size() != 1 || progressHolder.getChildren().get(0) != shown) {
            if (shown.getParent() instanceof javafx.scene.layout.Pane parent && parent != progressHolder) {
                parent.getChildren().remove(shown);
            }
            progressHolder.getChildren().setAll(shown);
            VBox.setVgrow(shown, Priority.ALWAYS);
        }
        progressHolder.setVisible(true);
        progressHolder.setManaged(true);
    }

    /** A stored run rebuilt as a (static) progress view, with its review or recovery action. */
    private SnippetAiApplyProgressPane restoredPaneFor(SnippetAnalysisRecord record, ApplyRun run) {
        ResumeVerdict verdict = run.isResumable()
            ? storedResume(record, run, safe(host.currentContent())).verdict()
            : ResumeVerdict.NOT_ELIGIBLE;
        String paneKey = run.id() + "|" + run.outcome() + "|" + run.decidedAt() + "|" + run.finishedAt()
            + "|" + verdict + "|" + (activeRun != null);
        if (restoredPane != null && paneKey.equals(restoredPaneKey)) {
            return restoredPane;
        }
        if (restoredPane != null) {
            restoredPane.dispose();
        }
        SnippetAiApplyProgressPane pane = SnippetAiApplyProgressPane.restored(run);
        String recordId = record.id();
        if (run.outcome() == RunOutcome.PENDING_REVIEW) {
            pane.setOnReviewChanges(() -> openReview(recordId, run.id()));
        }
        SnippetAnalysisRecord.StoredCheckpoint stored = run.checkpoint();
        if ((run.outcome() == RunOutcome.INTERRUPTED || run.outcome() == RunOutcome.FAILED
                || run.outcome() == RunOutcome.CANCELLED)
                && stored != null && stored.content() != null && stored.completedStages() >= 1
                && !dismissedRecoveries.contains(run.id())) {
            SnippetAiWorkflowSupport.ImprovementApplyCheckpoint checkpoint = stored.toCheckpoint();
            // After a restart the run is resumed from its stored request; the in-session context is gone.
            Runnable onResume = verdict == ResumeVerdict.OK && activeRun == null
                ? () -> resumeStored(recordId, run.id())
                : null;
            String note = switch (verdict) {
                case CONTENT_CHANGED -> I18n.get("snippets.ai.analysis.fix.resume.contentChanged");
                case PLAN_CHANGED -> I18n.get("snippets.ai.analysis.fix.resume.replan");
                default -> null;
            };
            pane.setRecovery(new SnippetAiApplyProgressPane.Recovery(
                stored.completedStages(), stored.totalStages(), run.outcome() != RunOutcome.FAILED,
                onResume,
                () -> previewPartial(recordId, run.id(), checkpoint, runContexts.get(run.id())),
                () -> {
                    dismissedRecoveries.add(run.id());
                    pane.setRecovery(null);
                },
                note));
        }
        restoredPane = pane;
        restoredPaneKey = paneKey;
        return pane;
    }

    /** Enables and disables the panel's actions for the busy state and the policy (viewing always works). */
    private void refreshControls() {
        if (!panelVisible || sidePanel == null) {
            return;
        }
        boolean busy = activeRun != null || analysisTask != null || host.isAnyAiTaskRunning();
        boolean aiUsable = aiAllowed() && host.hasCodeAnalysisProviders();
        boolean otherWindow = key != null && store.isRunClaimedByOther(key, this);
        SnippetAnalysisRecord record = shownRecord();
        if (applyButton != null) {
            applyButton.setDisable(record == null || busy || !aiUsable || otherWindow || review != null);
            applyButton.setVisible(record != null);
            applyButton.setManaged(record != null);
        }
        if (verifyButton != null) {
            boolean show = record != null && record.applyRuns().stream().anyMatch(ApplyRun::isAccepted);
            verifyButton.setVisible(show);
            verifyButton.setManaged(show);
            verifyButton.setDisable(busy || !aiUsable || review != null);
        }
        refreshHistoryActions(record, busy);
        if (plainRerunButton != null) {
            boolean show = record != null && !host.profileSwitchingSupported();
            plainRerunButton.setVisible(show);
            plainRerunButton.setManaged(show);
            plainRerunButton.setDisable(busy || !aiUsable);
        }
        if (analysisPanel != null) {
            Node rerun = analysisPanel.lookup("#snippet-analysis-rerun");
            if (rerun != null) {
                rerun.setDisable(busy || !aiUsable);
            }
        }
        if (headerStatusLabel != null) {
            headerStatusLabel.setText(record != null ? statusText(statusOf(record)) : "");
        }
        Node run = contentHolder != null ? contentHolder.lookup("#snippet-analysis-run") : null;
        if (run != null) {
            run.setDisable(busy || !aiUsable);
        }
    }

    // =====================================================================================
    // History actions: pin, discard, delete all (inline confirmation, never an Alert)
    // =====================================================================================

    private MenuButton buildHistoryActions() {
        MenuItem pin = new MenuItem(I18n.get("snippets.ai.analysis.history.pin"));
        pin.setId(HISTORY_PIN_ID);
        pin.setOnAction(event -> togglePinned());
        MenuItem discard = new MenuItem(I18n.get("snippets.ai.analysis.history.discard"));
        discard.setId(HISTORY_DISCARD_ID);
        discard.setOnAction(event -> {
            SnippetAnalysisRecord record = shownRecord();
            if (record != null) {
                requestConfirm(new PendingConfirm(record.id()));
            }
        });
        MenuItem deleteAll = new MenuItem(I18n.get("snippets.ai.analysis.history.deleteAll"));
        deleteAll.setId(HISTORY_DELETE_ALL_ID);
        deleteAll.setOnAction(event -> requestConfirm(new PendingConfirm(null)));
        MenuButton button = new MenuButton("⋯");
        button.setId(HISTORY_ACTIONS_ID);
        button.setTooltip(new Tooltip(I18n.get("snippets.ai.analysis.history.actions")));
        button.getItems().setAll(pin, discard, new SeparatorMenuItem(), deleteAll);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private void refreshHistoryActions(SnippetAnalysisRecord record, boolean busy) {
        MenuButton button = historyActions;
        if (button == null) {
            return;
        }
        boolean show = record != null;
        button.setVisible(show);
        button.setManaged(show);
        boolean readOnly = history == null || history.isReadOnly() || key == null;
        boolean recordInUse = record != null && ((activeRun != null && activeRun.recordId.equals(record.id()))
            || (review != null && record.id().equals(review.recordId())));
        for (MenuItem item : button.getItems()) {
            if (HISTORY_PIN_ID.equals(item.getId())) {
                item.setText(I18n.get(record != null && record.pinned()
                    ? "snippets.ai.analysis.history.unpin"
                    : "snippets.ai.analysis.history.pin"));
                item.setDisable(readOnly || record == null);
            } else if (HISTORY_DISCARD_ID.equals(item.getId())) {
                item.setDisable(readOnly || record == null || recordInUse);
            } else if (HISTORY_DELETE_ALL_ID.equals(item.getId())) {
                item.setDisable(readOnly || busy || review != null);
            }
        }
    }

    /** Pins or unpins the shown record: a pinned record is never removed by retention. */
    void togglePinned() {
        SnippetAnalysisRecord record = shownRecord();
        if (record == null || disposed) {
            return;
        }
        boolean pinned = !record.pinned();
        updateRecord(record.id(), r -> r.withPinned(pinned));
        host.setStatus(I18n.get(pinned
            ? "snippets.ai.analysis.history.pinned.status"
            : "snippets.ai.analysis.history.unpinned.status"));
    }

    private void requestConfirm(PendingConfirm confirm) {
        pendingConfirm = confirm;
        render();
    }

    private Node confirmStrip(PendingConfirm confirm) {
        Button yes = new Button(I18n.get(confirm.deletesAll()
            ? "snippets.ai.analysis.history.confirm.deleteAll"
            : "snippets.ai.analysis.history.confirm.discard"));
        yes.setId(CONFIRM_YES_ID);
        yes.setOnAction(event -> confirmPending());
        Button no = new Button(I18n.get("snippets.ai.analysis.history.confirm.cancel"));
        no.setId(CONFIRM_NO_ID);
        no.setOnAction(event -> requestConfirm(null));
        String text;
        if (confirm.deletesAll()) {
            text = I18n.get("snippets.ai.analysis.history.deleteAll.confirm", history.records().size());
        } else {
            SnippetAnalysisRecord record = history.find(confirm.recordId());
            text = I18n.get("snippets.ai.analysis.history.discard.confirm",
                formatTime(record != null ? record.analyzedAt() : 0L));
        }
        return banner(CONFIRM_STRIP_ID, text, BannerKind.WARNING, yes, no);
    }

    /** The user confirmed the pending Discard / Delete all. */
    private void confirmPending() {
        PendingConfirm confirm = pendingConfirm;
        pendingConfirm = null;
        if (confirm == null || key == null || disposed) {
            render();
            return;
        }
        flushSelection();
        if (confirm.deletesAll()) {
            trackAction("code_review_discard", Map.of("scope", "all"));
            shownRecordId = null;
            store.discardAll(key);
            host.setStatus(I18n.get("snippets.ai.analysis.history.deletedAll"));
        } else {
            trackAction("code_review_discard", Map.of("scope", "record"));
            if (confirm.recordId().equals(shownRecordId)) {
                shownRecordId = null;
            }
            store.discardRecord(key, confirm.recordId());
            host.setStatus(I18n.get("snippets.ai.analysis.history.discarded"));
        }
        render();
    }

    // =====================================================================================
    // Verification
    // =====================================================================================

    /** The counts of a verification: resolved, still open, new. */
    record VerifySummary(int resolved, int persisting, int introduced) {
    }

    /** The counts shown for a Verify record; {@code null} for any other record. */
    static VerifySummary verifySummary(SnippetAnalysisRecord record) {
        if (record == null || record.purpose() != SnippetAnalysisRecord.Purpose.VERIFY
                || record.verification() == null) {
            return null;
        }
        SnippetAnalysisRecord.Verification verification = record.verification();
        return new VerifySummary(verification.resolvedPreviousIds().size(),
            verification.persistingCurrentToPrevious().size(), verification.newIds().size());
    }

    /**
     * What the report page marks for a Verify record: "still open (was X)" and "new" chips on its
     * findings, and the findings of the verified record that are gone. {@code null} for any other
     * record. A verified record that is no longer stored leaves the resolved list with bare ids.
     */
    static SnippetAnalysisPanel.VerificationView verificationView(SnippetAnalysisRecord record,
                                                                  SnippetAnalysisHistory history) {
        if (verifySummary(record) == null) {
            return null;
        }
        SnippetAnalysisRecord.Verification verification = record.verification();
        SnippetAnalysisRecord previous = history != null ? history.find(verification.previousRecordId()) : null;
        List<SnippetAnalysisPanel.ResolvedFinding> resolved = new ArrayList<>();
        for (String id : verification.resolvedPreviousIds()) {
            resolved.add(resolvedFinding(previous, id));
        }
        return new SnippetAnalysisPanel.VerificationView(new LinkedHashMap<>(verification.persistingCurrentToPrevious()),
            new LinkedHashSet<>(verification.newIds()), resolved, previous != null);
    }

    private static SnippetAnalysisPanel.ResolvedFinding resolvedFinding(SnippetAnalysisRecord previous, String id) {
        if (previous != null) {
            for (SnippetAnalysisRecord.Finding finding : previous.improvements()) {
                if (finding.id().equals(id)) {
                    return new SnippetAnalysisPanel.ResolvedFinding(id, finding.title(), finding.severity(),
                        finding.category());
                }
            }
            for (SnippetAnalysisRecord.DependencyFinding dependency : previous.dependencies()) {
                if (dependency.id().equals(id)) {
                    return new SnippetAnalysisPanel.ResolvedFinding(id, dependency.name(), "", "dependencies");
                }
            }
        }
        return new SnippetAnalysisPanel.ResolvedFinding(id, "", "", "");
    }

    /** One {@code snippet_ai_action} event; props are enum/number/bool literals only, never content. */
    private static void trackAction(String action, Map<String, ?> props) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("action", action);
        all.putAll(props);
        Telemetry.track(TelemetryEvents.SNIPPET_AI_ACTION, all);
    }

    // =====================================================================================
    // Selection persistence
    // =====================================================================================

    private void flushSelection() {
        if (selectionDebounce.getStatus() == javafx.animation.Animation.Status.RUNNING) {
            selectionDebounce.stop();
        }
        persistSelection();
    }

    private void persistSelection() {
        SnippetAnalysisPanel panel = analysisPanel;
        String recordId = renderedRecordId;
        if (disposed || panel == null || recordId == null || key == null || findRecord(recordId) == null) {
            return;
        }
        SnippetAnalysisRecord.SelectionState state = panel.selectionState(System.currentTimeMillis());
        SnippetAnalysisRecord record = findRecord(recordId);
        if (record != null && sameSelection(record.selection(), state)) {
            return;
        }
        updateRecord(recordId, r -> r.withSelection(state));
    }

    static boolean sameSelection(SnippetAnalysisRecord.SelectionState a, SnippetAnalysisRecord.SelectionState b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.improvementIds().equals(b.improvementIds())
            && a.dependencyIds().equals(b.dependencyIds())
            && a.hardening().equals(b.hardening())
            && a.inputHardeningEnabled() == b.inputHardeningEnabled()
            && a.inputHardeningOptions().equals(b.inputHardeningOptions())
            && a.inputHardeningMaxFileSizeBytes() == b.inputHardeningMaxFileSizeBytes()
            && Objects.equals(a.headerSnippetId(), b.headerSnippetId())
            && Objects.equals(a.migrationTargetLanguage(), b.migrationTargetLanguage())
            && Objects.equals(a.migrationTargetHostFormat(), b.migrationTargetHostFormat())
            && Objects.equals(a.codeTextLanguageCode(), b.codeTextLanguageCode());
    }

    // =====================================================================================
    // Export
    // =====================================================================================

    /**
     * The immutable copy an export works from, taken at click time: the stored record with the
     * selection the user just made (flushed first), the whole history and the editor's content.
     */
    private SnippetAnalysisExportController.ExportSubject exportSubject(String recordId) {
        flushSelection();
        SnippetAnalysisHistory latest = key != null ? store.cached(key) : null;
        if (latest == null) {
            latest = history;
        }
        SnippetAnalysisRecord record = latest != null ? latest.find(recordId) : null;
        if (record == null) {
            return null;
        }
        return new SnippetAnalysisExportController.ExportSubject(host.snippetName(), host.snippetLanguage(), record,
            latest.records(), host.currentContent());
    }

    // =====================================================================================
    // Store helpers
    // =====================================================================================

    private SnippetAnalysisRecord shownRecord() {
        if (history == null) {
            return null;
        }
        if (shownRecordId != null) {
            SnippetAnalysisRecord shown = history.find(shownRecordId);
            if (shown != null) {
                return shown;
            }
        }
        return history.current();
    }

    private SnippetAnalysisRecord findRecord(String recordId) {
        return history != null && recordId != null ? history.find(recordId) : null;
    }

    private void updateRecord(String recordId, java.util.function.UnaryOperator<SnippetAnalysisRecord> change) {
        if (key == null || recordId == null) {
            return;
        }
        store.update(key, h -> h.update(recordId, change));
    }

    private void updateRun(String recordId, String runId, java.util.function.UnaryOperator<ApplyRun> change) {
        updateRecord(recordId, record -> {
            ApplyRun run = record.findRun(runId);
            return run == null ? record : record.withRun(change.apply(run));
        });
    }

    private static boolean aiAllowed() {
        try {
            return de.kortty.policy.PolicyManager.effective().aiAllowed();
        } catch (RuntimeException e) {
            return true;
        }
    }

    // =====================================================================================
    // Settings: panel width and visibility
    // =====================================================================================

    private static boolean panelVisibleByDefault() {
        GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
        return settings != null && settings.isSnippetAnalysisPanelVisible();
    }

    static double storedPanelWidth() {
        GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
        Double width = settings != null ? settings.getSnippetAnalysisPanelWidth() : null;
        return clampPanelWidth(width);
    }

    static double clampPanelWidth(Double width) {
        if (width == null || width.isNaN() || width <= 0) {
            return DEFAULT_PANEL_WIDTH;
        }
        return Math.max(MIN_PANEL_WIDTH, Math.min(1600, width));
    }

    private void persistPanelWidth() {
        VBox panel = sidePanel;
        if (panel == null || !panelVisible || panel.getWidth() < MIN_PANEL_WIDTH - 1) {
            return;
        }
        double width = panel.getWidth();
        updateSettings(settings -> settings.setSnippetAnalysisPanelWidth(width));
    }

    private static void persistPanelVisible(boolean visible) {
        updateSettings(settings -> settings.setSnippetAnalysisPanelVisible(visible));
    }

    private static void updateSettings(java.util.function.Consumer<GlobalSettings> change) {
        try {
            KorTTYApplication app = KorTTYApplication.getInstance();
            GlobalSettingsManager manager = app != null ? app.getGlobalSettingsManager() : null;
            GlobalSettings settings = manager != null ? manager.getSettings() : null;
            if (settings != null) {
                change.accept(settings);
                manager.scheduleSave();
            }
        } catch (Exception ignored) {
            // No application (isolated JavaFX tests) or an unwritable profile: a layout preference.
        }
    }

    // =====================================================================================
    // Small helpers
    // =====================================================================================

    private static String formatTime(long epochMillis) {
        if (epochMillis <= 0) {
            return "–";
        }
        try {
            return HISTORY_TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()));
        } catch (RuntimeException e) {
            return Instant.ofEpochMilli(epochMillis).toString();
        }
    }

    private static List<String> concat(Collection<String> first, Collection<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            try {
                Platform.runLater(action);
            } catch (IllegalStateException e) {
                action.run();
            }
        }
    }
}
