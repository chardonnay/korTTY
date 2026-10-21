package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.agentdesign.AgentDesignCombination;
import de.kortty.agentdesign.AgentDesignPresets;
import de.kortty.agentdesign.AgentModuleCatalog;
import de.kortty.agentdesign.AgentRole;
import de.kortty.agentdesign.AgentRunRegistry;
import de.kortty.agentdesign.MetroStationModel;
import de.kortty.core.AiTokenUsageManager;
import de.kortty.core.TerminalAgentService;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TerminalAgentModels;
import de.kortty.model.Theme;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Shows the KI-Agent runs of one terminal pane in the design combination each run was started with
 * (Plugins → KI-Agent Design). It sits in the pane's bottom slot and holds the parts the combinations
 * need: the classic activity panel, the status displays (01, 08, 11, 12, 18) in the slots around the
 * terminal, the approval row, and the blocks drawn into the terminal itself (03). Runs started with the
 * classic history are handed to the classic panel unchanged; for the others the host keeps the run state
 * and answers approvals itself.
 *
 * <p>Methods that block for an answer ({@link #requestApproval}, {@link #requestPassword}) run on the
 * agent's worker thread; everything else may be called from any thread and runs on the JavaFX thread.
 */
public class AgentDesignHost extends VBox implements com.sithtermfx.ui.split.TerminalSplitPane.PaneSideSlots {

    private static final Logger logger = LoggerFactory.getLogger(AgentDesignHost.class);
    private static final int KEPT_FINISHED_RUNS = 5;

    /** How one run is shown. */
    private record RunDesign(boolean classic, boolean inline, @Nullable String statusId) {
        boolean hasStatus() {
            return statusId != null;
        }
    }

    /** An approval or password request waiting for the user. */
    private record PendingPrompt(
        String runId,
        @Nullable TerminalAgentModels.Approval approval,
        @Nullable CompletableFuture<TerminalAgentService.ApprovalDecision> approvalFuture,
        @Nullable CompletableFuture<TerminalAgentModels.PasswordResponse> passwordFuture) {
    }

    private final @Nullable AgentTerminalSurface surface;
    private final Map<String, RunDesign> runDesigns = new ConcurrentHashMap<>();
    private final Map<String, Runnable> runCancels = new ConcurrentHashMap<>();
    private final Map<String, Consumer<Boolean>> runPauses = new ConcurrentHashMap<>();
    private final Map<String, MetroStationModel> metroModels = new HashMap<>();
    private final Map<String, Long> runTokens = new HashMap<>();
    private final Map<String, Set<String>> runTokenIds = new HashMap<>();
    private final Map<String, PendingPrompt> pendingPrompts = new ConcurrentHashMap<>();
    private final AgentRunRegistry registry = new AgentRunRegistry();
    private final AgentDesignApprovalStrip approvalStrip = new AgentDesignApprovalStrip();
    // Status displays by module id, created on first use, and the run state they show.
    private final Map<String, AgentStatusPresenter> statusPresenters = new LinkedHashMap<>();
    private final Map<String, AgentInlineBlockLayout.BlockState> traces = new HashMap<>();
    private final AgentStatusPresenter.Actions statusActions;
    private final VBox topSlot = new VBox();
    private final HBox rightSlot = new HBox();
    private @Nullable String activeStatusId;
    private @Nullable AiAgentActivityTabsPanel classicPanel;
    private @Nullable AgentDesignInlineBlocks inlineBlocks;
    private @Nullable Theme theme;
    private @Nullable String activeStylesheetUrl;
    private boolean sideDocked;
    private boolean statusBarDismissed;
    private boolean swallowTypedDigit;
    private javafx.animation.Timeline metaRefresh;

    public AgentDesignHost(@Nullable AgentTerminalSurface surface) {
        this.surface = surface;
        getStyleClass().add("ai-agent-design-host");
        setFillWidth(true);
        statusActions = new AgentStatusPresenter.Actions() {
            @Override
            public void togglePause() {
                togglePauseOfFocusedRun();
            }

            @Override
            public void cancel() {
                cancelFocusedRun();
            }

            @Override
            public void hide() {
                statusBarDismissed = true;
                AgentStatusPresenter active = activeStatus();
                if (active != null) {
                    active.hideNow();
                }
            }
        };
        getChildren().addAll(approvalStrip);
        approvalStrip.managedProperty().addListener((obs, oldValue, newValue) -> refreshOwnVisibility());
        topSlot.setFillWidth(true);
        topSlot.setVisible(false);
        topSlot.setManaged(false);
        rightSlot.setVisible(false);
        rightSlot.setManaged(false);
        metaRefresh = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
            javafx.util.Duration.seconds(1), event -> refreshStatus()));
        metaRefresh.setCycleCount(javafx.animation.Animation.INDEFINITE);
        applyDesignSettings();
        refreshOwnVisibility();
    }

    // ------------------------------------------------------------------ run lifecycle

    public void beginRun(
        String runId,
        String prompt,
        Runnable cancel,
        Consumer<Boolean> pause,
        Runnable reload,
        AiAgentActivityPanel.RunMetadata metadata,
        @Nullable AgentDesignCombination combination) {
        if (runId == null) {
            return;
        }
        AgentDesignCombination design = combination != null ? combination : AgentDesignPresets.STANDARD;
        boolean inline = design.usesInlineBlocks() && surface != null && surface.blockWriter() != null;
        String statusId = AgentModuleCatalog.isUsable(design.statusId(), AgentRole.STATUS)
            && !AgentModuleCatalog.NO_STATUS.equals(design.statusId()) ? design.statusId().trim() : null;
        RunDesign runDesign = new RunDesign(!inline, inline, statusId);
        runDesigns.put(runId, runDesign);
        if (cancel != null) {
            runCancels.put(runId, cancel);
        }
        if (pause != null) {
            runPauses.put(runId, pause);
        }
        if (runDesign.classic()) {
            ensureClassicPanel().beginRun(runId, prompt, cancel, pause, reload, metadata);
        }
        runOnFx(() -> {
            applyDesignSettings();
            registry.begin(runId, prompt, System.currentTimeMillis());
            if (runDesign.hasStatus()) {
                metroModels.put(runId, new MetroStationModel());
                traces.put(runId, new AgentInlineBlockLayout.BlockState(prompt, System.currentTimeMillis()));
                ensureStatusPresenter(runDesign.statusId());
                statusBarDismissed = false;
            }
            // The status display first: it changes the terminal's size, which the block must not see.
            refreshStatus();
            if (runDesign.inline()) {
                ensureInlineBlocks().beginRun(runId, prompt);
            }
            startMetaRefresh();
        });
    }

    public void publishActivity(String runId, TerminalAgentModels.AgentActivity activity) {
        RunDesign design = runDesigns.get(runId);
        if (design == null || activity == null) {
            return;
        }
        if (design.classic() && classicPanel != null) {
            classicPanel.publishActivity(runId, activity);
        }
        runOnFx(() -> {
            countTokens(runId, activity);
            if (design.inline() && inlineBlocks != null) {
                inlineBlocks.publishActivity(runId, activity);
            }
            AgentInlineBlockLayout.BlockState trace = traces.get(runId);
            if (trace != null) {
                trace.accept(activity, System.currentTimeMillis());
            }
            MetroStationModel model = metroModels.get(runId);
            if (model != null) {
                model.accept(activity);
                refreshStatus();
            }
        });
    }

    /** The run's phase as the agent service reports it; the classic panel does without. */
    public void updateState(String runId, TerminalAgentModels.RunState state) {
        if (state == null || !runDesigns.containsKey(runId)) {
            return;
        }
        runOnFx(() -> {
            AgentInlineBlockLayout.BlockState trace = traces.get(runId);
            if (trace != null && isFinal(state.phase())) {
                trace.finalPhase = state.phase();
            }
            MetroStationModel model = metroModels.get(runId);
            if (model != null) {
                model.acceptPhase(state.phase());
                refreshStatus();
            }
            if (inlineBlocks != null) {
                inlineBlocks.updatePhase(runId, state.phase());
            }
        });
    }

    /**
     * Shows the run's final answer in the design itself when it draws into the terminal. Returns
     * {@code false} when the caller should write the answer into the terminal as before.
     */
    public boolean showFinalMessage(String runId, String message, @Nullable TerminalAgentModels.Phase phase) {
        RunDesign design = runDesigns.get(runId);
        if (design == null || !design.inline()) {
            return false;
        }
        runOnFx(() -> {
            if (inlineBlocks != null) {
                inlineBlocks.setFinalMessage(runId, message, phase);
            }
        });
        return true;
    }

    public TerminalAgentService.ApprovalDecision requestApproval(String runId, TerminalAgentModels.Approval approval) {
        RunDesign design = runDesigns.get(runId);
        if (design == null) {
            return TerminalAgentService.ApprovalDecision.CANCEL;
        }
        if (design.classic()) {
            runOnFx(() -> markAwaitingInput(runId, true));
            try {
                return classicPanel != null
                    ? classicPanel.requestApproval(runId, approval)
                    : TerminalAgentService.ApprovalDecision.CANCEL;
            } finally {
                runOnFx(() -> {
                    markAwaitingInput(runId, false);
                    questionAnswered(runId);
                });
            }
        }
        CompletableFuture<TerminalAgentService.ApprovalDecision> future = new CompletableFuture<>();
        PendingPrompt prompt = new PendingPrompt(runId, approval, future, null);
        pendingPrompts.put(runId, prompt);
        runOnFx(() -> openApproval(prompt));
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TerminalAgentService.ApprovalDecision.CANCEL;
        } catch (ExecutionException e) {
            return TerminalAgentService.ApprovalDecision.CANCEL;
        } finally {
            pendingPrompts.remove(runId, prompt);
            runOnFx(() -> closePrompt(runId));
        }
    }

    public TerminalAgentModels.PasswordResponse requestPassword(String runId, TerminalAgentModels.PasswordRequest request) {
        RunDesign design = runDesigns.get(runId);
        if (design == null) {
            return null;
        }
        if (design.classic()) {
            runOnFx(() -> markAwaitingInput(runId, true));
            try {
                return classicPanel != null ? classicPanel.requestPassword(runId, request) : null;
            } finally {
                runOnFx(() -> {
                    markAwaitingInput(runId, false);
                    questionAnswered(runId);
                });
            }
        }
        CompletableFuture<TerminalAgentModels.PasswordResponse> future = new CompletableFuture<>();
        PendingPrompt prompt = new PendingPrompt(runId, null, null, future);
        pendingPrompts.put(runId, prompt);
        runOnFx(() -> openPassword(prompt, request));
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        } finally {
            pendingPrompts.remove(runId, prompt);
            runOnFx(() -> closePrompt(runId));
        }
    }

    public void finishRun(String runId) {
        RunDesign design = runDesigns.get(runId);
        if (design == null) {
            return;
        }
        if (design.classic() && classicPanel != null) {
            classicPanel.finishRun(runId);
        }
        runOnFx(() -> {
            answerPendingPrompt(runId, true);
            registry.finish(runId, System.currentTimeMillis());
            if (design.inline() && inlineBlocks != null) {
                inlineBlocks.finishRun(runId);
            }
            MetroStationModel model = metroModels.get(runId);
            if (model != null) {
                model.finish();
            }
            AgentInlineBlockLayout.BlockState trace = traces.get(runId);
            if (trace != null) {
                trace.finished = true;
                trace.finishedAtMillis = System.currentTimeMillis();
                trace.pendingApproval = null;
                trace.pendingPassword = null;
                trace.settleRunningSteps(trace.finalPhase == TerminalAgentModels.Phase.CANCELLED);
            }
            pruneFinishedRuns();
            refreshStatus();
            if (!registry.hasActiveRuns()) {
                metaRefresh.stop();
                AgentStatusPresenter active = activeStatus();
                if (active != null) {
                    active.scheduleAutoHide(globalSettings() != null
                        ? globalSettings().getAiAgentDesignAutoHideSeconds()
                        : GlobalSettings.AI_AGENT_DESIGN_AUTO_HIDE_DEFAULT_SECONDS);
                }
            }
        });
    }

    public void toggleThinkingDetails(String runId) {
        RunDesign design = runDesigns.get(runId);
        if (design == null) {
            return;
        }
        if (design.classic() && classicPanel != null) {
            classicPanel.toggleThinkingDetails(runId);
        }
        if (design.inline()) {
            runOnFx(() -> {
                if (inlineBlocks != null) {
                    inlineBlocks.toggleDetails(runId);
                }
            });
        }
    }

    // ------------------------------------------------------------------ queries used by TerminalView/MainWindow

    /** The run the run controls (Esc, Ctrl+C, Ctrl+R) act on, or {@code null}. */
    public @Nullable String selectedRunId() {
        for (PendingPrompt prompt : pendingPrompts.values()) {
            return prompt.runId();
        }
        if (classicPanel != null) {
            String classicRun = classicPanel.selectedRunId();
            if (classicRun != null && classicPanel.hasActiveRuns()) {
                return classicRun;
            }
        }
        String focus = focusRunOfOwnRuns();
        if (focus != null) {
            return focus;
        }
        return classicPanel != null ? classicPanel.selectedRunId() : null;
    }

    public boolean hasActiveRuns() {
        return (classicPanel != null && classicPanel.hasActiveRuns()) || hasActiveOwnRuns();
    }

    /** Counts of runs by state: [awaitingInput, working, paused, done]. */
    public int[] runCounts() {
        int[] total = classicPanel != null ? classicPanel.runCounts() : new int[4];
        int[] own = ownRunCounts();
        for (int i = 0; i < total.length && i < own.length; i++) {
            total[i] += own[i];
        }
        return total;
    }

    /** Cancels every run of this pane (the pane is closing). */
    public void cancelAllRuns() {
        if (classicPanel != null) {
            classicPanel.cancelAllRuns();
        }
        for (Map.Entry<String, Runnable> entry : runCancels.entrySet()) {
            RunDesign design = runDesigns.get(entry.getKey());
            if (design != null && design.classic()) {
                continue;
            }
            try {
                entry.getValue().run();
            } catch (Exception e) {
                logger.debug("Failed to cancel terminal agent run: {}", e.getMessage());
            }
        }
        for (PendingPrompt prompt : pendingPrompts.values()) {
            completePrompt(prompt, TerminalAgentService.ApprovalDecision.CANCEL, null);
        }
    }

    public void applyTheme(Theme theme) {
        runOnFx(() -> {
            this.theme = theme;
            if (activeStylesheetUrl != null) {
                getStylesheets().remove(activeStylesheetUrl);
            }
            activeStylesheetUrl = ThemeCssSupport.getAgentActivityStylesheetUrl(theme);
            if (activeStylesheetUrl != null) {
                getStylesheets().add(activeStylesheetUrl);
            }
            AgentDesignPalette palette = AgentDesignPalette.of(theme);
            for (AgentStatusPresenter presenter : statusPresenters.values()) {
                presenter.applyPalette(palette);
            }
            topSlot.getStylesheets().setAll(activeStylesheetUrl != null ? java.util.List.of(activeStylesheetUrl) : java.util.List.of());
            rightSlot.getStylesheets().setAll(topSlot.getStylesheets());
            if (classicPanel != null) {
                classicPanel.applyTheme(theme);
            }
            if (inlineBlocks != null) {
                inlineBlocks.applyTheme(theme);
            }
        });
    }

    /** Docks the host into a side container; only the classic panel changes its layout for that. */
    public void setSideDocked(boolean docked) {
        runOnFx(() -> {
            sideDocked = docked;
            if (docked) {
                ensureClassicPanel();
            }
            if (classicPanel != null) {
                classicPanel.setSideDocked(docked);
            }
            refreshOwnVisibility();
        });
    }

    /** Shows the status bar again after the user hid it, or hides it. */
    public void toggleStatusBar() {
        runOnFx(() -> {
            AgentStatusPresenter active = activeStatus();
            if (active != null && active.isShowing()) {
                statusBarDismissed = true;
                active.hideNow();
            } else {
                statusBarDismissed = false;
                refreshStatus(true);
            }
        });
    }

    /** Whether any run of this pane uses the status bar, so a menu can offer to show or hide it. */
    public boolean hasStatusBarRuns() {
        return !metroModels.isEmpty();
    }

    /**
     * Answers an open approval of this pane with the keys 1, 2 and 3 typed into the terminal. Returns
     * whether the key was used; it then must not reach the shell. FX thread.
     */
    public boolean handlePromptKey(KeyEvent event) {
        PendingPrompt prompt = firstApprovalPrompt();
        if (prompt == null || event == null || event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
            return false;
        }
        TerminalAgentService.ApprovalDecision decision =
            approvalDecisionForKey(event.getCode(), prompt.approval() != null && prompt.approval().allowAlways());
        if (decision == null) {
            return false;
        }
        // The key's typed character follows after the prompt is gone; it must not reach the shell either.
        swallowTypedDigit = true;
        completePrompt(prompt, decision, null);
        return true;
    }

    /** Whether {@code character} belongs to a key {@link #handlePromptKey} answered with (or would). */
    public boolean isPromptKeyCharacter(String character) {
        if (character == null || character.length() != 1) {
            return false;
        }
        char c = character.charAt(0);
        boolean digit = c == '1' || c == '2' || c == '3';
        if (digit && swallowTypedDigit) {
            swallowTypedDigit = false;
            return true;
        }
        return digit && firstApprovalPrompt() != null;
    }

    /** The decision a digit key stands for: 1 once, 2 always (or cancel without "always"), 3 cancel. */
    static TerminalAgentService.ApprovalDecision approvalDecisionForKey(KeyCode code, boolean allowsAlways) {
        if (code == KeyCode.DIGIT1 || code == KeyCode.NUMPAD1) {
            return TerminalAgentService.ApprovalDecision.APPROVE_ONCE;
        }
        if (code == KeyCode.DIGIT2 || code == KeyCode.NUMPAD2) {
            return allowsAlways
                ? TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS
                : TerminalAgentService.ApprovalDecision.CANCEL;
        }
        if (allowsAlways && (code == KeyCode.DIGIT3 || code == KeyCode.NUMPAD3)) {
            return TerminalAgentService.ApprovalDecision.CANCEL;
        }
        return null;
    }

    /** The classic panel, when one was created for this pane. */
    @Nullable AiAgentActivityTabsPanel classicPanel() {
        return classicPanel;
    }

    // ------------------------------------------------------------------ internals (FX thread)

    private AiAgentActivityTabsPanel ensureClassicPanel() {
        AiAgentActivityTabsPanel panel = classicPanel;
        if (panel != null) {
            return panel;
        }
        AiAgentActivityTabsPanel created = new AiAgentActivityTabsPanel();
        classicPanel = created;
        runOnFx(() -> {
            if (theme != null) {
                created.applyTheme(theme);
            }
            if (sideDocked) {
                created.setSideDocked(true);
            }
            VBox.setVgrow(created, Priority.ALWAYS);
            getChildren().add(0, created);
            created.managedProperty().addListener((obs, oldValue, newValue) -> refreshOwnVisibility());
            refreshOwnVisibility();
        });
        return created;
    }

    private AgentDesignInlineBlocks ensureInlineBlocks() {
        if (inlineBlocks == null) {
            inlineBlocks = new AgentDesignInlineBlocks(surface);
            inlineBlocks.applyTheme(theme);
            GlobalSettings settings = globalSettings();
            inlineBlocks.setReducedAnimations(settings != null && settings.isAiAgentDesignReducedAnimations());
        }
        return inlineBlocks;
    }

    private void applyDesignSettings() {
        GlobalSettings settings = globalSettings();
        boolean reduced = settings != null && settings.isAiAgentDesignReducedAnimations();
        for (AgentStatusPresenter presenter : statusPresenters.values()) {
            presenter.setReducedAnimations(reduced);
        }
        if (inlineBlocks != null) {
            inlineBlocks.setReducedAnimations(reduced);
        }
    }

    private void openApproval(PendingPrompt prompt) {
        String runId = prompt.runId();
        markAwaitingInput(runId, true);
        RunDesign design = runDesigns.get(runId);
        if (design != null && design.inline() && inlineBlocks != null) {
            inlineBlocks.showApproval(runId, prompt.approval());
        }
        AgentInlineBlockLayout.BlockState trace = traces.get(runId);
        if (trace != null) {
            trace.pendingApproval = prompt.approval();
        }
        approvalStrip.showApproval(prompt.approval(), decision -> completePrompt(prompt, decision, null));
        statusBarDismissed = false;
        refreshStatus();
    }

    private void openPassword(PendingPrompt prompt, TerminalAgentModels.PasswordRequest request) {
        String runId = prompt.runId();
        markAwaitingInput(runId, true);
        RunDesign design = runDesigns.get(runId);
        if (design != null && design.inline() && inlineBlocks != null) {
            inlineBlocks.showPassword(runId, request);
        }
        AgentInlineBlockLayout.BlockState trace = traces.get(runId);
        if (trace != null) {
            trace.pendingPassword = request;
        }
        approvalStrip.showPassword(request, response -> completePrompt(prompt, null, response));
        statusBarDismissed = false;
        refreshStatus();
    }

    private void closePrompt(String runId) {
        markAwaitingInput(runId, false);
        if (inlineBlocks != null) {
            inlineBlocks.clearPrompt(runId);
        }
        AgentInlineBlockLayout.BlockState trace = traces.get(runId);
        if (trace != null) {
            trace.pendingApproval = null;
            trace.pendingPassword = null;
            trace.completeOpenQuestions();
        }
        questionAnswered(runId);
        PendingPrompt next = pendingPrompts.values().stream().findFirst().orElse(null);
        if (next == null) {
            approvalStrip.hideStrip();
        } else if (next.approval() != null) {
            approvalStrip.showApproval(next.approval(), decision -> completePrompt(next, decision, null));
        }
        refreshStatus();
    }

    private void questionAnswered(String runId) {
        MetroStationModel model = metroModels.get(runId);
        if (model != null) {
            model.questionAnswered();
        }
    }

    private void completePrompt(PendingPrompt prompt,
                                @Nullable TerminalAgentService.ApprovalDecision decision,
                                @Nullable TerminalAgentModels.PasswordResponse response) {
        if (prompt.approvalFuture() != null && !prompt.approvalFuture().isDone()) {
            prompt.approvalFuture().complete(decision != null ? decision : TerminalAgentService.ApprovalDecision.CANCEL);
        }
        if (prompt.passwordFuture() != null && !prompt.passwordFuture().isDone()) {
            prompt.passwordFuture().complete(response);
        }
    }

    private void answerPendingPrompt(String runId, boolean cancel) {
        PendingPrompt prompt = pendingPrompts.get(runId);
        if (prompt != null && cancel) {
            completePrompt(prompt, TerminalAgentService.ApprovalDecision.CANCEL, null);
        }
    }

    private @Nullable PendingPrompt firstApprovalPrompt() {
        for (PendingPrompt prompt : pendingPrompts.values()) {
            if (prompt.approvalFuture() != null && !prompt.approvalFuture().isDone()) {
                return prompt;
            }
        }
        return null;
    }

    private void markAwaitingInput(String runId, boolean awaiting) {
        registry.setAwaitingInput(runId, awaiting);
    }

    private void countTokens(String runId, TerminalAgentModels.AgentActivity activity) {
        TerminalAgentModels.AgentActivityTokenUsage usage = activity.tokenUsage();
        if (usage != null && usage.known() && usage.totalTokens() > 0
            && runTokenIds.computeIfAbsent(runId, id -> new HashSet<>()).add(activity.id())) {
            runTokens.merge(runId, usage.totalTokens(), Long::sum);
        }
    }

    /** Forgets all but the newest finished runs; their blocks and the bar no longer change. */
    private void pruneFinishedRuns() {
        java.util.List<String> finished = new java.util.ArrayList<>();
        for (String runId : registry.runIds()) {
            if (registry.state(runId) == AgentRunRegistry.State.DONE) {
                finished.add(runId);
            }
        }
        for (int i = 0; i < finished.size() - KEPT_FINISHED_RUNS; i++) {
            String runId = finished.get(i);
            registry.remove(runId);
            runCancels.remove(runId);
            runPauses.remove(runId);
            metroModels.remove(runId);
            traces.remove(runId);
            runTokens.remove(runId);
            runTokenIds.remove(runId);
            RunDesign design = runDesigns.get(runId);
            if (design != null && !design.classic()) {
                runDesigns.remove(runId);
            }
            if (inlineBlocks != null) {
                inlineBlocks.removeRun(runId);
            }
        }
    }

    private void togglePauseOfFocusedRun() {
        String runId = focusRunOfOwnRuns();
        if (runId == null || registry.state(runId) == AgentRunRegistry.State.DONE
            || registry.state(runId) == AgentRunRegistry.State.AWAITING_INPUT) {
            return;
        }
        boolean pause = registry.state(runId) != AgentRunRegistry.State.PAUSED;
        registry.setPaused(runId, pause);
        Consumer<Boolean> handler = runPauses.get(runId);
        if (handler != null) {
            handler.accept(pause);
        }
        if (inlineBlocks != null) {
            inlineBlocks.setPaused(runId, pause);
        }
        AgentInlineBlockLayout.BlockState trace = traces.get(runId);
        if (trace != null) {
            trace.paused = pause;
        }
        refreshStatus();
    }

    private void cancelFocusedRun() {
        String runId = focusRunOfOwnRuns();
        if (runId == null) {
            return;
        }
        PendingPrompt prompt = pendingPrompts.get(runId);
        if (prompt != null) {
            completePrompt(prompt, TerminalAgentService.ApprovalDecision.CANCEL, null);
        }
        Runnable cancel = runCancels.get(runId);
        if (cancel != null) {
            cancel.run();
        }
    }

    private @Nullable String focusRunOfOwnRuns() {
        String focus = registry.focusRunId();
        if (focus == null) {
            return null;
        }
        RunDesign design = runDesigns.get(focus);
        if (design != null && !design.classic()) {
            return focus;
        }
        // The registry also tracks classic runs (for the status bar); prefer an own one.
        for (String runId : registry.runIds()) {
            RunDesign other = runDesigns.get(runId);
            if (other != null && !other.classic() && registry.state(runId) != AgentRunRegistry.State.DONE) {
                return runId;
            }
        }
        return null;
    }

    private boolean hasActiveOwnRuns() {
        for (String runId : registry.runIds()) {
            RunDesign design = runDesigns.get(runId);
            if (design != null && !design.classic() && registry.state(runId) != AgentRunRegistry.State.DONE) {
                return true;
            }
        }
        return false;
    }

    private int[] ownRunCounts() {
        int[] counts = new int[4];
        for (String runId : registry.runIds()) {
            RunDesign design = runDesigns.get(runId);
            if (design == null || design.classic()) {
                continue;
            }
            switch (registry.state(runId)) {
                case AWAITING_INPUT -> counts[0]++;
                case WORKING -> counts[1]++;
                case PAUSED -> counts[2]++;
                case DONE -> counts[3]++;
            }
        }
        return counts;
    }

    private void refreshStatus() {
        refreshStatus(false);
    }

    /**
     * Shows the run the status display follows: one waiting for input, else the newest working one,
     * else the newest. Each run keeps the display of its combination; the others are hidden.
     */
    private void refreshStatus(boolean force) {
        String runId = null;
        String focus = registry.focusRunId();
        if (focus != null && traces.containsKey(focus)) {
            runId = focus;
        } else {
            for (String candidate : registry.runIds()) {
                if (traces.containsKey(candidate)) {
                    runId = candidate;
                }
            }
        }
        RunDesign design = runId != null ? runDesigns.get(runId) : null;
        AgentStatusPresenter presenter = design != null && design.hasStatus() ? statusPresenters.get(design.statusId()) : null;
        for (AgentStatusPresenter other : statusPresenters.values()) {
            if (other != presenter && other.isShowing()) {
                other.hideNow();
            }
        }
        if (presenter == null) {
            activeStatusId = null;
            return;
        }
        activeStatusId = design.statusId();
        if (statusBarDismissed && !force) {
            return;
        }
        AgentRunRegistry.State state = registry.state(runId);
        if (!presenter.isShowing() && state == AgentRunRegistry.State.DONE && !force) {
            // A finished run does not bring a faded-out display back.
            return;
        }
        long now = System.currentTimeMillis();
        String meta = AgentInlineBlockLayout.formatElapsed(registry.elapsedMillis(runId, now));
        Long tokens = runTokens.get(runId);
        if (tokens != null && tokens > 0) {
            meta = I18n.get("ai.agent.activity.tokens.short", AiTokenUsageManager.formatCompact(tokens)) + " · " + meta;
        }
        int active = 0;
        for (String candidate : traces.keySet()) {
            RunDesign other = runDesigns.get(candidate);
            if (other != null && design.statusId().equals(other.statusId())
                && registry.state(candidate) != AgentRunRegistry.State.DONE) {
                active++;
            }
        }
        presenter.show(new AgentStatusPresenter.View(runId, registry.prompt(runId), metroModels.get(runId),
            traces.get(runId), meta, state != AgentRunRegistry.State.DONE, state == AgentRunRegistry.State.PAUSED,
            state == AgentRunRegistry.State.AWAITING_INPUT, active));
        if (force && !registry.hasActiveRuns()) {
            presenter.scheduleAutoHide(0);
        }
    }

    private @Nullable AgentStatusPresenter activeStatus() {
        return activeStatusId != null ? statusPresenters.get(activeStatusId) : null;
    }

    /** The status display of {@code moduleId}, created and put into its slot on first use. */
    private AgentStatusPresenter ensureStatusPresenter(String moduleId) {
        AgentStatusPresenter existing = statusPresenters.get(moduleId);
        if (existing != null) {
            return existing;
        }
        AgentStatusPresenter created = switch (moduleId) {
            case "d01" -> new AgentDesignPowerlineBar(statusActions);
            case "d11" -> new AgentDesignMinimapRail();
            case "d12" -> new AgentDesignTopBar(statusActions);
            case "d18" -> new AgentDesignNeonTicker(statusActions);
            default -> new AgentDesignMetroBar(statusActions);
        };
        created.applyPalette(AgentDesignPalette.of(theme));
        GlobalSettings settings = globalSettings();
        created.setReducedAnimations(settings != null && settings.isAiAgentDesignReducedAnimations());
        created.setOverlay(() -> surface != null ? surface.overlayLayer() : null);
        Region node = created.node();
        switch (created.slot()) {
            case TOP -> {
                topSlot.getChildren().add(node);
                node.managedProperty().addListener((obs, oldValue, newValue) -> refreshSlot(topSlot));
            }
            case RIGHT -> {
                rightSlot.getChildren().add(node);
                node.managedProperty().addListener((obs, oldValue, newValue) -> refreshSlot(rightSlot));
            }
            case BOTTOM -> {
                // Below the classic panel, above the approval row.
                getChildren().add(getChildren().indexOf(approvalStrip), node);
                node.managedProperty().addListener((obs, oldValue, newValue) -> refreshOwnVisibility());
            }
        }
        statusPresenters.put(moduleId, created);
        return created;
    }

    /** A slot takes room only while one of its displays is shown. */
    private static void refreshSlot(Pane slot) {
        boolean any = slot.getChildren().stream().anyMatch(Node::isManaged);
        slot.setVisible(any);
        slot.setManaged(any);
    }

    private static boolean isFinal(TerminalAgentModels.Phase phase) {
        return phase == TerminalAgentModels.Phase.DONE
            || phase == TerminalAgentModels.Phase.BLOCKED
            || phase == TerminalAgentModels.Phase.CANCELLED
            || phase == TerminalAgentModels.Phase.FAILED;
    }

    @Override
    public @Nullable Region topSlot() {
        return topSlot;
    }

    @Override
    public @Nullable Region rightSlot() {
        return rightSlot;
    }

    private void startMetaRefresh() {
        if (metaRefresh.getStatus() != javafx.animation.Animation.Status.RUNNING) {
            metaRefresh.play();
        }
    }

    private void refreshOwnVisibility() {
        boolean any = sideDocked;
        for (Node child : getChildren()) {
            if (child.isManaged()) {
                any = true;
                break;
            }
        }
        setVisible(any);
        setManaged(any);
    }

    private static GlobalSettings globalSettings() {
        KorTTYApplication application = KorTTYApplication.getInstance();
        return application != null && application.getGlobalSettingsManager() != null
            ? application.getGlobalSettingsManager().getSettings()
            : null;
    }

    private static void runOnFx(Runnable task) {
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }
}
