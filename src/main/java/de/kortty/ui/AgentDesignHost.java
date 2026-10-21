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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Shows the KI-Agent runs of one terminal pane in the design combination each run was started with
 * (Plugins → KI-Agent Design). It sits in the pane's bottom slot and holds the parts the combinations
 * need: the classic activity panel, the history panels (02, 06, 07, 09, 13, 16) and status displays
 * (01, 04, 05, 08, 10, 11, 12, 15, 18) in the slots around the terminal or floating over it, the
 * approval row, the approval at the cursor line (14), the picture-in-picture window (19), and the
 * lines drawn into the terminal itself (03, 17). Runs started with the classic history are handed to
 * the classic panel unchanged; for the others the host keeps the run state and answers approvals
 * itself.
 *
 * <p>Methods that block for an answer ({@link #requestApproval}, {@link #requestPassword}) run on the
 * agent's worker thread; everything else may be called from any thread and runs on the JavaFX thread.
 */
public class AgentDesignHost extends VBox implements com.sithtermfx.ui.split.TerminalSplitPane.PaneSideSlots {

    /** Style class of the host, so the classic panel inside can look past it to the pane's column. */
    static final String STYLE_CLASS = "ai-agent-design-host";
    private static final Logger logger = LoggerFactory.getLogger(AgentDesignHost.class);
    private static final int KEPT_FINISHED_RUNS = 5;
    /** The history modules shown as a panel beside the terminal (not written into it). */
    private static final Set<String> HISTORY_PANELS = Set.of("d02", "d06", "d07", "d09", "d13", "d16");
    private static final String PICTURE_IN_PICTURE_KEY = "pip";

    /**
     * How one run is shown.
     *
     * @param classic        the classic activity panel shows it
     * @param inline         its blocks are written into the terminal (03)
     * @param focus          its lines are written into the terminal and marked, the rest dims (17)
     * @param historyPanelId the history panel beside the terminal, or {@code null}
     * @param statusId       the chosen status display, or {@code null} for none
     * @param cursorApproval its approvals are offered at the cursor line (14)
     * @param arrangementId  where its panel goes: fixed, picture-in-picture (19) or by size (20)
     */
    private record RunDesign(
        boolean classic,
        boolean inline,
        boolean focus,
        @Nullable String historyPanelId,
        @Nullable String statusId,
        boolean cursorApproval,
        String arrangementId) {

        boolean pictureInPicture() {
            return AgentModuleCatalog.PICTURE_IN_PICTURE.equals(arrangementId);
        }

        boolean adaptive() {
            return AgentModuleCatalog.ADAPTIVE_DOCK.equals(arrangementId);
        }

        boolean hasStatus() {
            return statusId != null || adaptive();
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
    // Commands the user took over with Tab at the cursor line; typed at the prompt once their run ended.
    private final Map<String, String> pendingInserts = new HashMap<>();
    // What the run menu needs of each run: the profile, users and connection, and how to start it again.
    private final Map<String, AiAgentActivityPanel.RunMetadata> runMetadata = new ConcurrentHashMap<>();
    private final Map<String, Runnable> runReloads = new ConcurrentHashMap<>();
    private final AgentRunRegistry registry = new AgentRunRegistry();
    private final AgentDesignApprovalStrip approvalStrip = new AgentDesignApprovalStrip();
    // Designs beside or over the terminal by module id, created on first use, and the run state they show.
    private final Map<String, AgentRunPresenter> presenters = new LinkedHashMap<>();
    private final Map<String, AgentInlineBlockLayout.BlockState> traces = new HashMap<>();
    private final VBox topSlot = new VBox();
    private final HBox rightSlot = new HBox();
    private final AgentAdaptiveDock adaptiveDock = new AgentAdaptiveDock();
    private @Nullable String activeStatusId;
    private @Nullable String pinnedRunId;
    private @Nullable AiAgentActivityTabsPanel classicPanel;
    private @Nullable AgentDesignInlineBlocks inlineBlocks;
    private @Nullable AgentDesignFocusMode focusMode;
    private @Nullable AgentDesignCursorApproval cursorApproval;
    private @Nullable AgentDesignSpotlight spotlight;
    private @Nullable AgentDesignCountdownRing countdownRing;
    private @Nullable Theme theme;
    private @Nullable String activeStylesheetUrl;
    private boolean sideDocked;
    private boolean statusBarDismissed;
    private boolean panelDismissed;
    private @Nullable String swallowTypedCharacters;
    private @Nullable javafx.scene.control.ContextMenu openMenu;
    private javafx.animation.Timeline metaRefresh;

    public AgentDesignHost(@Nullable AgentTerminalSurface surface) {
        this.surface = surface;
        getStyleClass().add(STYLE_CLASS);
        setFillWidth(true);
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
        RunDesign runDesign = designFor(combination != null ? combination : AgentDesignPresets.STANDARD);
        runDesigns.put(runId, runDesign);
        if (cancel != null) {
            runCancels.put(runId, cancel);
        }
        if (pause != null) {
            runPauses.put(runId, pause);
        }
        if (metadata != null) {
            runMetadata.put(runId, metadata);
        }
        if (reload != null) {
            runReloads.put(runId, reload);
        }
        if (runDesign.classic()) {
            ensureClassicPanel().beginRun(runId, prompt, cancel, pause, reload, metadata);
        }
        runOnFx(() -> {
            applyDesignSettings();
            registry.begin(runId, prompt, System.currentTimeMillis());
            if (!runDesign.classic() || runDesign.hasStatus() || runDesign.pictureInPicture()) {
                metroModels.put(runId, new MetroStationModel());
                traces.put(runId, new AgentInlineBlockLayout.BlockState(prompt, System.currentTimeMillis()));
                if (runDesign.statusId() != null) {
                    ensurePresenter(runDesign.statusId());
                }
                if (runDesign.historyPanelId() != null) {
                    ensurePresenter(runDesign.historyPanelId());
                }
                if (runDesign.pictureInPicture()) {
                    ensurePresenter(PICTURE_IN_PICTURE_KEY);
                }
                statusBarDismissed = false;
                panelDismissed = false;
                pinnedRunId = null;
                if (spotlight != null) {
                    spotlight.runStarted(prompt, "d05".equals(effectiveStatusId(runDesign)));
                }
            }
            // The displays first: they change the terminal's size, which the block must not see.
            refreshStatus();
            AgentDesignInlineBlocks inTerminal = inTerminalFor(runDesign, true);
            if (inTerminal != null) {
                inTerminal.beginRun(runId, prompt);
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
            AgentDesignInlineBlocks inTerminal = inTerminalFor(design, false);
            if (inTerminal != null) {
                inTerminal.publishActivity(runId, activity);
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
            for (AgentDesignInlineBlocks inTerminal : inTerminalDesigns()) {
                inTerminal.updatePhase(runId, state.phase());
            }
        });
    }

    /**
     * Shows the run's final answer in the design itself. Returns {@code false} when the caller should
     * also write the answer into the terminal as before (every design but the ones drawing into it).
     */
    public boolean showFinalMessage(String runId, String message, @Nullable TerminalAgentModels.Phase phase) {
        RunDesign design = runDesigns.get(runId);
        if (design == null) {
            return false;
        }
        runOnFx(() -> {
            AgentInlineBlockLayout.BlockState trace = traces.get(runId);
            if (trace != null && message != null && !message.isBlank()) {
                trace.finalMessage = message;
            }
            AgentDesignInlineBlocks inTerminal = inTerminalFor(design, false);
            if (inTerminal != null) {
                inTerminal.setFinalMessage(runId, message, phase);
            }
            refreshStatus();
        });
        return design.inline() || design.focus();
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
            AgentDesignInlineBlocks inTerminal = inTerminalFor(design, false);
            if (inTerminal != null) {
                inTerminal.finishRun(runId);
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
            typeTakenOverCommand(runId);
            pruneFinishedRuns();
            refreshStatus();
            if (!registry.hasActiveRuns()) {
                metaRefresh.stop();
                int seconds = globalSettings() != null
                    ? globalSettings().getAiAgentDesignAutoHideSeconds()
                    : GlobalSettings.AI_AGENT_DESIGN_AUTO_HIDE_DEFAULT_SECONDS;
                AgentRunPresenter active = activeStatus();
                if (active != null && !active.staysAfterRun()) {
                    active.scheduleAutoHide(seconds);
                    if (design.adaptive() && seconds > 0) {
                        ensureCountdownRing().start(seconds);
                    }
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
        runOnFx(() -> {
            AgentDesignInlineBlocks inTerminal = inTerminalFor(design, false);
            if (inTerminal != null) {
                inTerminal.toggleDetails(runId);
            }
        });
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
        runOnFx(() -> {
            for (AgentRunPresenter presenter : presenters.values()) {
                if (presenter instanceof AgentDesignPictureInPicture pip) {
                    pip.dispose();
                }
            }
            if (focusMode != null) {
                focusMode.dispose();
            }
            if (spotlight != null) {
                spotlight.close();
            }
        });
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
            for (AgentRunPresenter presenter : presenters.values()) {
                presenter.applyPalette(palette);
            }
            topSlot.getStylesheets().setAll(activeStylesheetUrl != null ? List.of(activeStylesheetUrl) : List.of());
            rightSlot.getStylesheets().setAll(topSlot.getStylesheets());
            if (classicPanel != null) {
                classicPanel.applyTheme(theme);
            }
            for (AgentDesignInlineBlocks inTerminal : inTerminalDesigns()) {
                inTerminal.applyTheme(theme);
            }
            if (focusMode != null) {
                focusMode.applyPalette(palette);
            }
            if (cursorApproval != null) {
                cursorApproval.applyPalette(palette);
            }
            if (spotlight != null) {
                spotlight.applyPalette(palette);
            }
            if (countdownRing != null) {
                countdownRing.applyPalette(palette);
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
            AgentRunPresenter active = activeStatus();
            if (active != null && active.isShowing()) {
                statusBarDismissed = true;
                active.hideNow();
            } else {
                statusBarDismissed = false;
                panelDismissed = false;
                refreshStatus(true);
            }
        });
    }

    /** Whether any run of this pane uses a status display or panel, so a menu can offer to show or hide it. */
    public boolean hasStatusBarRuns() {
        for (RunDesign design : runDesigns.values()) {
            if (design.hasStatus() || design.historyPanelId() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Opens design 05's Spotlight: a field in the middle of the pane that starts a run with the chosen
     * profile, as {@code agent …} at the shell would. Returns whether it opened.
     */
    public boolean openSpotlight() {
        if (surface == null || !surface.canStartRuns()) {
            return false;
        }
        runOnFx(() -> ensureSpotlight().open());
        return true;
    }

    /** Shows {@code prompt} asked in the Spotlight, for the design preview, which starts its sample run itself. */
    void playSpotlight(String prompt) {
        runOnFx(() -> ensureSpotlight().openAsked(prompt));
    }

    /**
     * Answers an open approval of this pane with the keys 1, 2 and 3 typed into the terminal, and at the
     * cursor line (14) also with Enter, Tab and Esc. Returns whether the key was used; it then must not
     * reach the shell. FX thread.
     */
    public boolean handlePromptKey(KeyEvent event) {
        PendingPrompt prompt = firstApprovalPrompt();
        if (prompt == null || event == null || event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
            return false;
        }
        RunDesign design = runDesigns.get(prompt.runId());
        boolean atCursor = design != null && design.cursorApproval() && cursorApproval != null && cursorApproval.isShowing();
        AgentDesignCursorApproval.Key cursorKey = atCursor ? AgentDesignCursorApproval.keyFor(event.getCode()) : null;
        if (cursorKey != null) {
            swallowTypedCharacters = AgentDesignCursorApproval.typedCharacters(cursorKey);
            switch (cursorKey) {
                case RUN -> completePrompt(prompt, TerminalAgentService.ApprovalDecision.APPROVE_ONCE, null);
                case EDIT -> {
                    pendingInserts.put(prompt.runId(), AgentDesignCursorApproval.commandLine(prompt.approval()));
                    completePrompt(prompt, TerminalAgentService.ApprovalDecision.CANCEL, null);
                }
                case DISMISS -> completePrompt(prompt, TerminalAgentService.ApprovalDecision.CANCEL, null);
            }
            return true;
        }
        TerminalAgentService.ApprovalDecision decision =
            approvalDecisionForKey(event.getCode(), prompt.approval() != null && prompt.approval().allowAlways());
        if (decision == null) {
            return false;
        }
        // The key's typed character follows after the prompt is gone; it must not reach the shell either.
        swallowTypedCharacters = "123";
        completePrompt(prompt, decision, null);
        return true;
    }

    /** Whether {@code character} belongs to a key {@link #handlePromptKey} answered with (or would). */
    public boolean isPromptKeyCharacter(String character) {
        if (character == null || character.length() != 1) {
            return false;
        }
        if (swallowTypedCharacters != null && swallowTypedCharacters.contains(character)) {
            swallowTypedCharacters = null;
            return true;
        }
        PendingPrompt open = firstApprovalPrompt();
        if (open == null) {
            return false;
        }
        char c = character.charAt(0);
        RunDesign design = runDesigns.get(open.runId());
        if (design != null && design.cursorApproval() && cursorApproval != null && cursorApproval.isShowing()
            && AgentDesignCursorApproval.isKeyCharacter(c)) {
            return true;
        }
        return c == '1' || c == '2' || c == '3';
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

    /**
     * Whether {@code node} is part of this pane's designs: the host, its slots above and beside the
     * terminal, a design floating over it, or the Spotlight. Keys typed there belong to that control, not
     * to the terminal.
     */
    public boolean ownsNode(@Nullable Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == this || current == topSlot || current == rightSlot
                || (spotlight != null && spotlight.isCard(current))) {
                return true;
            }
            for (AgentRunPresenter presenter : presenters.values()) {
                if (current == presenter.node()) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ run menu and step menu

    /**
     * The terminal context menu's entry for the newest run of this pane that a design shows (not the
     * classic panel, which has its own buttons): the run menu as a submenu; {@code null} when there is none.
     */
    public @Nullable javafx.scene.control.Menu lastRunMenu(@Nullable Node owner) {
        String runId = null;
        for (String candidate : registry.runIds()) {
            if (traces.containsKey(candidate)) {
                runId = candidate;
            }
        }
        if (runId == null) {
            return null;
        }
        javafx.scene.control.Menu menu = new javafx.scene.control.Menu(I18n.get("aiAgentDesign.menu.lastRun"));
        menu.getItems().setAll(runMenuItems(runId, owner));
        return menu;
    }

    /** The run as the shared run actions (workflow, export) take it; {@code null} once it was forgotten. */
    @Nullable AgentRunActions.RunRecord recordOf(String runId) {
        AgentInlineBlockLayout.BlockState trace = runId != null ? traces.get(runId) : null;
        if (trace == null) {
            return null;
        }
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        Long tokens = runTokens.get(runId);
        long reported = trace.tokensKnown ? trace.tokens : tokens != null && tokens > 0 ? tokens : -1L;
        return new AgentRunActions.RunRecord(trace.prompt, trace.prompt, runMetadata.get(runId),
            java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(trace.startedAtMillis), zone),
            trace.finished && trace.finishedAtMillis >= 0
                ? java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(trace.finishedAtMillis), zone) : null,
            trace.elapsedMillis(System.currentTimeMillis()) / 1000L, reported, List.copyOf(trace.rows.values()));
    }

    /**
     * The run menu of {@code runId}: Workflow, export this run or all runs in a format, run again, copy the
     * answer, and the earlier runs of the pane. Dialogs open over {@code owner}'s window.
     */
    List<javafx.scene.control.MenuItem> runMenuItems(String runId, @Nullable Node owner) {
        AgentRunActions.RunRecord run = recordOf(runId);
        boolean running = run != null && registry.state(runId) != AgentRunRegistry.State.DONE;
        List<javafx.scene.control.MenuItem> items = new ArrayList<>();

        javafx.scene.control.MenuItem workflow = new javafx.scene.control.MenuItem(I18n.get("aiAgentDesign.menu.workflow"));
        workflow.setDisable(run == null || running || !AgentRunActions.hasReproducibleWork(run));
        workflow.setOnAction(event -> AgentRunActions.openWorkflow(windowOf(owner), run));
        items.add(workflow);

        javafx.scene.control.Menu exportRun = new javafx.scene.control.Menu(I18n.get("aiAgentDesign.menu.exportRun"));
        javafx.scene.control.Menu exportAll = new javafx.scene.control.Menu(I18n.get("aiAgentDesign.menu.exportAll"));
        List<AgentRunActions.RunRecord> finished = finishedRecords();
        for (de.kortty.core.TerminalAgentActivityExportService.Format format
            : de.kortty.core.TerminalAgentActivityExportService.Format.values()) {
            String label = I18n.get(AgentRunActions.exportFormatLabelKey(format));
            javafx.scene.control.MenuItem one = new javafx.scene.control.MenuItem(label);
            one.setOnAction(event -> AgentRunActions.export(windowOf(owner), List.of(run), format, "terminal-agent",
                text -> exportStatus(owner, text)));
            exportRun.getItems().add(one);
            javafx.scene.control.MenuItem all = new javafx.scene.control.MenuItem(label);
            all.setOnAction(event -> AgentRunActions.export(windowOf(owner), finished, format, "terminal-agent-all",
                text -> exportStatus(owner, text)));
            exportAll.getItems().add(all);
        }
        exportRun.setDisable(run == null || running);
        exportAll.setDisable(finished.isEmpty());
        items.add(exportRun);
        items.add(exportAll);

        javafx.scene.control.MenuItem rerun = new javafx.scene.control.MenuItem(I18n.get("aiAgentDesign.menu.rerun"));
        Runnable reload = runReloads.get(runId);
        rerun.setDisable(running || reload == null);
        rerun.setOnAction(event -> {
            if (reload != null) {
                reload.run();
            }
        });
        items.add(rerun);

        String answer = run != null ? AgentInlineBlockLayout.finalAnswer(traces.get(runId)) : "";
        javafx.scene.control.MenuItem copyAnswer = new javafx.scene.control.MenuItem(I18n.get("aiAgentDesign.menu.copyAnswer"));
        copyAnswer.setDisable(answer.isBlank());
        copyAnswer.setOnAction(event -> de.kortty.core.KorttyClipboard.setText(answer.strip()));
        items.add(copyAnswer);

        items.add(new javafx.scene.control.SeparatorMenuItem());
        javafx.scene.control.Menu earlier = new javafx.scene.control.Menu(I18n.get("aiAgentDesign.menu.earlierRuns"));
        for (String other : registry.runIds()) {
            if (!traces.containsKey(other)) {
                continue;
            }
            AgentRunRegistry.State state = registry.state(other);
            String marker = state == AgentRunRegistry.State.AWAITING_INPUT ? "? "
                : state == AgentRunRegistry.State.DONE ? "✓ " : "● ";
            javafx.scene.control.CheckMenuItem item = new javafx.scene.control.CheckMenuItem(
                marker + AiAgentActivityTabsPanel.truncateTabTitle(registry.prompt(other)));
            item.setSelected(other.equals(runId));
            item.setOnAction(event -> {
                pinnedRunId = other;
                statusBarDismissed = false;
                panelDismissed = false;
                refreshStatus(true);
            });
            earlier.getItems().add(0, item);
        }
        earlier.setDisable(earlier.getItems().size() < 2);
        items.add(earlier);
        return items;
    }

    /** The menu of one step: copy its command, its output or its text, open it in the Snippet Editor. */
    List<javafx.scene.control.MenuItem> stepMenuItems(TerminalAgentModels.AgentActivity step, @Nullable Node owner) {
        List<javafx.scene.control.MenuItem> items = new ArrayList<>();
        String command = AgentRunActions.commandText(step);
        String output = AgentRunActions.outputText(step);
        String text = AgentRunActions.copyText(step);
        if (!command.isBlank()) {
            javafx.scene.control.MenuItem copyCommand = new javafx.scene.control.MenuItem(I18n.get("aiAgentDesign.step.copyCommand"));
            copyCommand.setOnAction(event -> de.kortty.core.KorttyClipboard.setText(command));
            items.add(copyCommand);
        }
        if (!output.isBlank()) {
            javafx.scene.control.MenuItem copyOutput = new javafx.scene.control.MenuItem(I18n.get("aiAgentDesign.step.copyOutput"));
            copyOutput.setOnAction(event -> de.kortty.core.KorttyClipboard.setText(output));
            items.add(copyOutput);
        }
        javafx.scene.control.MenuItem copyEntry = new javafx.scene.control.MenuItem(I18n.get("ai.agent.activity.copyEntry"));
        copyEntry.setDisable(text.isBlank());
        copyEntry.setOnAction(event -> de.kortty.core.KorttyClipboard.setText(text));
        items.add(copyEntry);
        String snippet = command.isBlank() ? text : command;
        javafx.scene.control.MenuItem openSnippet = new javafx.scene.control.MenuItem(I18n.get("ai.agent.activity.openSnippetEditor"));
        openSnippet.setDisable(snippet.isBlank());
        openSnippet.setOnAction(event -> AgentRunActions.openSnippetEditor(windowOf(owner), snippet, step));
        items.add(openSnippet);
        return items;
    }

    private List<AgentRunActions.RunRecord> finishedRecords() {
        List<AgentRunActions.RunRecord> records = new ArrayList<>();
        for (String runId : registry.runIds()) {
            AgentRunActions.RunRecord record = registry.state(runId) == AgentRunRegistry.State.DONE ? recordOf(runId) : null;
            if (record != null) {
                records.add(record);
            }
        }
        return records;
    }

    private void showMenu(List<javafx.scene.control.MenuItem> items, Node anchor, double screenX, double screenY) {
        if (openMenu != null) {
            openMenu.hide();
        }
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        menu.getItems().setAll(items);
        openMenu = menu;
        menu.show(anchor, screenX, screenY);
    }

    /** Shows how an export goes for a few seconds next to where it was asked for. */
    private void exportStatus(@Nullable Node owner, String text) {
        if (owner == null || owner.getScene() == null || text == null || text.isBlank()) {
            return;
        }
        javafx.scene.control.Tooltip notice = new javafx.scene.control.Tooltip(text);
        javafx.geometry.Bounds bounds = owner.localToScreen(owner.getBoundsInLocal());
        if (bounds == null) {
            return;
        }
        notice.show(owner, bounds.getMinX(), bounds.getMaxY() + 4);
        javafx.animation.PauseTransition linger = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
        linger.setOnFinished(event -> notice.hide());
        linger.play();
    }

    private static @Nullable javafx.stage.Window windowOf(@Nullable Node node) {
        return node != null && node.getScene() != null ? node.getScene().getWindow() : null;
    }

    /** The classic panel, when one was created for this pane. */
    @Nullable AiAgentActivityTabsPanel classicPanel() {
        return classicPanel;
    }

    // ------------------------------------------------------------------ internals (FX thread)

    private RunDesign designFor(AgentDesignCombination design) {
        boolean writable = surface != null && surface.blockWriter() != null;
        String historyId = design.historyId() != null ? design.historyId().trim() : AgentModuleCatalog.CLASSIC;
        boolean inline = design.usesInlineBlocks() && writable;
        boolean focus = design.usesFocusMode() && writable;
        String panelId = HISTORY_PANELS.contains(historyId) ? historyId : null;
        boolean classic = !inline && !focus && panelId == null;
        String statusId = AgentModuleCatalog.isUsable(design.statusId(), AgentRole.STATUS)
            && !AgentModuleCatalog.NO_STATUS.equals(design.statusId()) ? design.statusId().trim() : null;
        boolean cursor = AgentModuleCatalog.CURSOR_APPROVAL.equals(design.approvalId()) && !classic;
        String arrangement = AgentModuleCatalog.isUsable(design.arrangementId(), AgentRole.ARRANGEMENT)
            ? design.arrangementId().trim() : AgentModuleCatalog.FIXED;
        return new RunDesign(classic, inline, focus, panelId, statusId, cursor, arrangement);
    }

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

    /** The design that writes {@code design}'s run into the terminal, created on first use, or {@code null}. */
    private @Nullable AgentDesignInlineBlocks inTerminalFor(RunDesign design, boolean create) {
        if (design.inline()) {
            return create ? ensureInlineBlocks() : inlineBlocks;
        }
        if (design.focus()) {
            return create ? ensureFocusMode() : focusMode;
        }
        return null;
    }

    private List<AgentDesignInlineBlocks> inTerminalDesigns() {
        List<AgentDesignInlineBlocks> designs = new ArrayList<>(2);
        if (inlineBlocks != null) {
            designs.add(inlineBlocks);
        }
        if (focusMode != null) {
            designs.add(focusMode);
        }
        return designs;
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

    private AgentDesignFocusMode ensureFocusMode() {
        if (focusMode == null) {
            focusMode = new AgentDesignFocusMode(surface);
            focusMode.applyTheme(theme);
            focusMode.applyPalette(AgentDesignPalette.of(theme));
            GlobalSettings settings = globalSettings();
            focusMode.setReducedAnimations(settings != null && settings.isAiAgentDesignReducedAnimations());
        }
        return focusMode;
    }

    private AgentDesignCursorApproval ensureCursorApproval() {
        if (cursorApproval == null) {
            cursorApproval = new AgentDesignCursorApproval(surface);
            cursorApproval.applyPalette(AgentDesignPalette.of(theme));
            GlobalSettings settings = globalSettings();
            cursorApproval.setReducedAnimations(settings != null && settings.isAiAgentDesignReducedAnimations());
        }
        return cursorApproval;
    }

    private AgentDesignSpotlight ensureSpotlight() {
        if (spotlight == null) {
            spotlight = new AgentDesignSpotlight(surface);
            spotlight.applyPalette(AgentDesignPalette.of(theme));
            GlobalSettings settings = globalSettings();
            spotlight.setReducedAnimations(settings != null && settings.isAiAgentDesignReducedAnimations());
        }
        return spotlight;
    }

    private AgentDesignCountdownRing ensureCountdownRing() {
        if (countdownRing == null) {
            countdownRing = new AgentDesignCountdownRing(() -> surface != null ? surface.controlLayer() : null,
                () -> surface != null ? surface.canvas() : null);
            countdownRing.applyPalette(AgentDesignPalette.of(theme));
        }
        return countdownRing;
    }

    private void applyDesignSettings() {
        GlobalSettings settings = globalSettings();
        boolean reduced = settings != null && settings.isAiAgentDesignReducedAnimations();
        for (AgentRunPresenter presenter : presenters.values()) {
            presenter.setReducedAnimations(reduced);
        }
        for (AgentDesignInlineBlocks inTerminal : inTerminalDesigns()) {
            inTerminal.setReducedAnimations(reduced);
        }
        if (cursorApproval != null) {
            cursorApproval.setReducedAnimations(reduced);
        }
        if (spotlight != null) {
            spotlight.setReducedAnimations(reduced);
        }
    }

    private void openApproval(PendingPrompt prompt) {
        String runId = prompt.runId();
        markAwaitingInput(runId, true);
        RunDesign design = runDesigns.get(runId);
        AgentDesignInlineBlocks inTerminal = design != null ? inTerminalFor(design, false) : null;
        if (inTerminal != null) {
            inTerminal.showApproval(runId, prompt.approval());
        }
        AgentInlineBlockLayout.BlockState trace = traces.get(runId);
        if (trace != null) {
            trace.pendingApproval = prompt.approval();
        }
        offerApproval(prompt, design, trace);
        statusBarDismissed = false;
        panelDismissed = false;
        pinnedRunId = null;
        refreshStatus();
    }

    /** Offers an approval at the cursor line (14), in a design's own buttons, or in the row below the terminal. */
    private void offerApproval(PendingPrompt prompt, @Nullable RunDesign design,
                               @Nullable AgentInlineBlockLayout.BlockState trace) {
        if (design != null && design.cursorApproval() && ensureCursorApproval().show(prompt.approval(), trace)) {
            approvalStrip.hideStrip();
        } else if (design != null && answeredByDesign(design)) {
            approvalStrip.hideStrip();
        } else {
            approvalStrip.showApproval(prompt.approval(), decision -> completePrompt(prompt, decision, null));
        }
    }

    private void openPassword(PendingPrompt prompt, TerminalAgentModels.PasswordRequest request) {
        String runId = prompt.runId();
        markAwaitingInput(runId, true);
        RunDesign design = runDesigns.get(runId);
        AgentDesignInlineBlocks inTerminal = design != null ? inTerminalFor(design, false) : null;
        if (inTerminal != null) {
            inTerminal.showPassword(runId, request);
        }
        AgentInlineBlockLayout.BlockState trace = traces.get(runId);
        if (trace != null) {
            trace.pendingPassword = request;
        }
        // A password is always typed into the row below the terminal.
        approvalStrip.showPassword(request, response -> completePrompt(prompt, null, response));
        statusBarDismissed = false;
        panelDismissed = false;
        pinnedRunId = null;
        refreshStatus();
    }

    private void closePrompt(String runId) {
        markAwaitingInput(runId, false);
        for (AgentDesignInlineBlocks inTerminal : inTerminalDesigns()) {
            inTerminal.clearPrompt(runId);
        }
        AgentInlineBlockLayout.BlockState trace = traces.get(runId);
        if (trace != null) {
            trace.pendingApproval = null;
            trace.pendingPassword = null;
            trace.completeOpenQuestions();
        }
        if (cursorApproval != null) {
            cursorApproval.hide();
        }
        questionAnswered(runId);
        PendingPrompt next = pendingPrompts.values().stream().findFirst().orElse(null);
        if (next == null || next.approval() == null) {
            approvalStrip.hideStrip();
        } else {
            offerApproval(next, runDesigns.get(next.runId()), traces.get(next.runId()));
        }
        refreshStatus();
    }

    /** Whether a design of the run has its own buttons for an approval, so the row below can stay hidden. */
    private boolean answeredByDesign(RunDesign design) {
        if (design.pictureInPicture()) {
            return true;
        }
        AgentRunPresenter panel = design.historyPanelId() != null ? presenters.get(design.historyPanelId()) : null;
        if (panel != null && panel.answersApprovals()) {
            return true;
        }
        String statusId = effectiveStatusId(design);
        AgentRunPresenter status = statusId != null ? presenters.get(statusId) : null;
        return status != null && status.answersApprovals();
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

    /** Types a command the user took over with Tab at the cursor line, once its run let go of the prompt. */
    private void typeTakenOverCommand(String runId) {
        String command = pendingInserts.remove(runId);
        if (command == null || command.isBlank() || surface == null) {
            return;
        }
        javafx.animation.PauseTransition settle = new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));
        settle.setOnFinished(event -> surface.insertAtPrompt(command));
        settle.play();
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
        List<String> finished = new ArrayList<>();
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
            pendingInserts.remove(runId);
            runMetadata.remove(runId);
            runReloads.remove(runId);
            if (runId.equals(pinnedRunId)) {
                pinnedRunId = null;
            }
            RunDesign design = runDesigns.get(runId);
            if (design != null && !design.classic()) {
                runDesigns.remove(runId);
            }
            for (AgentDesignInlineBlocks inTerminal : inTerminalDesigns()) {
                inTerminal.removeRun(runId);
            }
        }
    }

    private void togglePauseOf(@Nullable String runId) {
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
        for (AgentDesignInlineBlocks inTerminal : inTerminalDesigns()) {
            inTerminal.setPaused(runId, pause);
        }
        AgentInlineBlockLayout.BlockState trace = traces.get(runId);
        if (trace != null) {
            trace.paused = pause;
        }
        refreshStatus();
    }

    private void cancel(@Nullable String runId) {
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

    /** The run the designs follow: one the user picked, else one waiting for input, the newest working one, the newest. */
    private @Nullable String followedRunId() {
        if (pinnedRunId != null && traces.containsKey(pinnedRunId)) {
            return pinnedRunId;
        }
        String focus = registry.focusRunId();
        if (focus != null && traces.containsKey(focus)) {
            return focus;
        }
        String newest = null;
        for (String candidate : registry.runIds()) {
            if (traces.containsKey(candidate)) {
                newest = candidate;
            }
        }
        return newest;
    }

    private void refreshStatus() {
        refreshStatus(false);
    }

    /**
     * Shows the run the designs follow ({@link #followedRunId}) in its combination: its status display
     * (by the terminal's size for the adaptive dock) and its history panel, in its slot or in the
     * picture-in-picture window. The designs of other combinations are hidden.
     */
    private void refreshStatus(boolean force) {
        String runId = followedRunId();
        RunDesign design = runId != null ? runDesigns.get(runId) : null;
        String statusId = design != null ? effectiveStatusId(design) : null;
        String panelId = design != null ? design.historyPanelId() : null;
        boolean pip = design != null && design.pictureInPicture();
        AgentRunPresenter status = statusId != null ? ensurePresenter(statusId) : null;
        AgentRunPresenter panel = panelId != null ? ensurePresenter(panelId) : null;
        AgentDesignPictureInPicture window = pip ? (AgentDesignPictureInPicture) ensurePresenter(PICTURE_IN_PICTURE_KEY) : null;
        for (AgentRunPresenter other : presenters.values()) {
            if (other != status && other != panel && other != window && other.isShowing()) {
                other.hideNow();
            }
        }
        activeStatusId = status != null ? statusId : null;
        if (design == null) {
            return;
        }
        if (panel != null) {
            placePanel(panel, window);
        } else if (window != null) {
            window.adopt(null);
        }
        AgentRunPresenter.View view = viewOf(runId, design);
        AgentRunRegistry.State state = registry.state(runId);
        if (status != null && status != panel && !(statusBarDismissed && !force)
            && (status.isShowing() || state != AgentRunRegistry.State.DONE || force || status.staysAfterRun())) {
            // A finished run does not bring a faded-out display back.
            status.show(view);
            if (force && !registry.hasActiveRuns() && !status.staysAfterRun()) {
                status.scheduleAutoHide(0);
            }
        }
        if (panel != null && !panelDismissed) {
            panel.show(view);
        }
        if (window != null && !panelDismissed) {
            window.show(view);
        }
        if (cursorApproval != null && cursorApproval.isShowing()) {
            cursorApproval.refresh(view.meta());
        }
        if (focusMode != null && design.focus()) {
            focusMode.follow(runId, state != AgentRunRegistry.State.DONE);
        }
    }

    private AgentRunPresenter.View viewOf(String runId, RunDesign design) {
        long now = System.currentTimeMillis();
        String meta = AgentInlineBlockLayout.formatElapsed(registry.elapsedMillis(runId, now));
        Long tokens = runTokens.get(runId);
        if (tokens != null && tokens > 0) {
            meta = I18n.get("ai.agent.activity.tokens.short", AiTokenUsageManager.formatCompact(tokens)) + " · " + meta;
        }
        int active = 0;
        List<AgentRunPresenter.RunTab> runs = new ArrayList<>();
        String statusId = effectiveStatusId(design);
        for (String candidate : registry.runIds()) {
            RunDesign other = runDesigns.get(candidate);
            if (other == null || !traces.containsKey(candidate)) {
                continue;
            }
            AgentRunRegistry.State otherState = registry.state(candidate);
            runs.add(new AgentRunPresenter.RunTab(candidate, registry.prompt(candidate),
                otherState != AgentRunRegistry.State.DONE, otherState == AgentRunRegistry.State.AWAITING_INPUT));
            if (java.util.Objects.equals(effectiveStatusId(other), statusId)
                && java.util.Objects.equals(other.historyPanelId(), design.historyPanelId())
                && otherState != AgentRunRegistry.State.DONE) {
                active++;
            }
        }
        AgentRunRegistry.State state = registry.state(runId);
        return new AgentRunPresenter.View(runId, registry.prompt(runId), metroModels.get(runId), traces.get(runId),
            meta, state != AgentRunRegistry.State.DONE, state == AgentRunRegistry.State.PAUSED,
            state == AgentRunRegistry.State.AWAITING_INPUT, active, runs);
    }

    /** The status display a run shows: its choice, or for the adaptive dock the one the terminal's size asks for. */
    private @Nullable String effectiveStatusId(RunDesign design) {
        if (!design.adaptive()) {
            return design.statusId();
        }
        TerminalCellGeometry geometry = surface != null ? surface.cellGeometry() : null;
        if (geometry == null) {
            return adaptiveDock.current();
        }
        // The side timeline takes columns away from the terminal; judge the size it would have without it.
        int columns = geometry.columns();
        if (AgentAdaptiveDock.SIDE.equals(adaptiveDock.current()) && rightSlot.isManaged() && geometry.cellWidth() > 0) {
            columns += (int) Math.round(rightSlot.getWidth() / geometry.cellWidth());
        }
        return adaptiveDock.choose(columns, geometry.rows());
    }

    /** Puts a history panel into the picture-in-picture window or back into its slot. */
    private void placePanel(AgentRunPresenter panel, @Nullable AgentDesignPictureInPicture window) {
        Region node = panel.node();
        if (window != null) {
            window.adopt(node);
            return;
        }
        for (AgentRunPresenter presenter : presenters.values()) {
            if (presenter instanceof AgentDesignPictureInPicture pip && pip.holds(node)) {
                pip.adopt(null);
            }
        }
        Pane slot = switch (panel.slot()) {
            case TOP -> topSlot;
            case RIGHT -> rightSlot;
            case BOTTOM, OVERLAY -> this;
        };
        if (node.getParent() != slot) {
            attachToSlot(panel);
        }
    }

    private @Nullable AgentRunPresenter activeStatus() {
        return activeStatusId != null ? presenters.get(activeStatusId) : null;
    }

    /** The design of {@code moduleId}, created and put into its slot on first use. */
    private AgentRunPresenter ensurePresenter(String moduleId) {
        AgentRunPresenter existing = presenters.get(moduleId);
        if (existing != null) {
            return existing;
        }
        PresenterActions actions = new PresenterActions(moduleId);
        AgentRunPresenter created = switch (moduleId) {
            case "d01" -> new AgentDesignPowerlineBar(actions);
            case "d11" -> new AgentDesignMinimapRail(actions);
            case "d12" -> new AgentDesignTopBar(actions);
            case "d18" -> new AgentDesignNeonTicker(actions);
            case "d02" -> new AgentDesignTimeline(actions);
            case "d06" -> new AgentDesignPlanExecution(actions);
            case "d07" -> new AgentDesignCardStack(actions);
            case "d09" -> new AgentDesignTmuxPane(actions);
            case "d13" -> new AgentDesignChat(actions);
            case "d16" -> new AgentDesignLogTable(actions);
            case "d04" -> new AgentDesignHud(actions);
            case "d05" -> new AgentDesignStatusPill(actions);
            case "d10" -> new AgentDesignToasts(actions);
            case "d15" -> new AgentDesignOrbit(actions);
            case PICTURE_IN_PICTURE_KEY -> new AgentDesignPictureInPicture(actions);
            default -> new AgentDesignMetroBar(actions);
        };
        created.applyPalette(AgentDesignPalette.of(theme));
        GlobalSettings settings = globalSettings();
        created.setReducedAnimations(settings != null && settings.isAiAgentDesignReducedAnimations());
        created.setOverlay(() -> surface != null ? surface.overlayLayer() : null);
        created.setControlLayer(() -> surface != null ? surface.controlLayer() : null);
        created.setTerminalCanvas(() -> surface != null ? surface.canvas() : null);
        if (created instanceof AgentDesignHud hud && surface != null) {
            hud.watchTyping(surface.canvas());
        }
        presenters.put(moduleId, created);
        attachToSlot(created);
        created.node().managedProperty().addListener((obs, oldValue, newValue) -> {
            refreshSlot(topSlot);
            refreshSlot(rightSlot);
            refreshOwnVisibility();
        });
        return created;
    }

    private void attachToSlot(AgentRunPresenter presenter) {
        Region node = presenter.node();
        if (node.getParent() instanceof Pane parent && presenter.slot() != AgentRunPresenter.Slot.OVERLAY) {
            parent.getChildren().remove(node);
        }
        switch (presenter.slot()) {
            case TOP -> topSlot.getChildren().add(node);
            case RIGHT -> rightSlot.getChildren().add(node);
            // Below the classic panel, above the approval row.
            case BOTTOM -> getChildren().add(getChildren().indexOf(approvalStrip), node);
            case OVERLAY -> {
                // A floating design places itself in the control layer when it is shown.
            }
        }
        refreshSlot(topSlot);
        refreshSlot(rightSlot);
        refreshOwnVisibility();
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

    /** The buttons of one design; they act on the run the designs follow. */
    private final class PresenterActions implements AgentRunPresenter.Actions {
        private final String moduleId;

        private PresenterActions(String moduleId) {
            this.moduleId = moduleId;
        }

        @Override
        public void togglePause() {
            togglePauseOf(followedOwnRun());
        }

        @Override
        public void cancel() {
            AgentDesignHost.this.cancel(followedOwnRun());
        }

        @Override
        public void hide() {
            AgentRunPresenter presenter = presenters.get(moduleId);
            if (moduleId.equals(activeStatusId)) {
                statusBarDismissed = true;
            } else {
                panelDismissed = true;
            }
            if (presenter != null) {
                presenter.hideNow();
            }
        }

        @Override
        public void answerApproval(TerminalAgentService.ApprovalDecision decision) {
            String runId = followedRunId();
            PendingPrompt prompt = runId != null ? pendingPrompts.get(runId) : null;
            if (prompt == null) {
                prompt = firstApprovalPrompt();
            }
            if (prompt != null && prompt.approvalFuture() != null) {
                completePrompt(prompt, decision, null);
            }
        }

        @Override
        public void selectRun(String runId) {
            if (runId != null && traces.containsKey(runId)) {
                pinnedRunId = runId;
                refreshStatus(true);
            }
        }

        @Override
        public boolean startRun(String prompt) {
            return surface != null && surface.startRun(prompt, null);
        }

        @Override
        public boolean canStartRuns() {
            return surface != null && surface.canStartRuns();
        }

        @Override
        public void showRunMenu(Node anchor, double screenX, double screenY) {
            String runId = followedRunId();
            if (runId != null && anchor != null) {
                showMenu(runMenuItems(runId, anchor), anchor, screenX, screenY);
            }
        }

        @Override
        public void showStepMenu(Node anchor, TerminalAgentModels.AgentActivity step, double screenX, double screenY) {
            if (step != null && anchor != null) {
                showMenu(stepMenuItems(step, anchor), anchor, screenX, screenY);
            }
        }

        private @Nullable String followedOwnRun() {
            String followed = followedRunId();
            return followed != null ? followed : focusRunOfOwnRuns();
        }
    }
}
