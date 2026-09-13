package de.kortty.ui;

import de.kortty.codingagent.AgentSummary;
import de.kortty.codingagent.CodingAgentActionException;
import de.kortty.codingagent.CodingAgentActions;
import de.kortty.codingagent.CodingAgentEntry;
import de.kortty.codingagent.CodingAgentGlyphs;
import de.kortty.codingagent.CodingAgentNavigator;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.DurationText;
import de.kortty.codingagent.KeyChord;
import de.kortty.codingagent.PaneLocation;
import de.kortty.codingagent.PaneLocator;
import de.kortty.codingagent.PaneRef;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The dockable "Coding Agents" panel: one per window, listing every agent of every window in
 * {@link CodingAgentNavigator#orderedEntries} order (BLOCKED first, longest waiting first) with a
 * state chip, the pane location, the evidence line and the quick actions Focus / y / n / Enter /
 * Esc / ↑ / ↓ / Ctrl+C / Explain / Rename…, plus a prompt box for the selected agent.
 *
 * <p>The panel never touches MainWindow: locations come from the {@link PaneLocator} port, focus
 * from the {@link CodingAgentNavigator}, writes from {@link CodingAgentActions}. It subscribes to
 * the registry only between {@link #bind()} and {@link #unbind()} (an {@link AutoCloseable} handle
 * kept in a field), runs one 1-second {@link Timeline} for the duration labels while bound (a cell
 * re-renders only when the cached duration String identity changes) and one pulse
 * {@link AnimationTimer} (33 ms cap) gated by {@link CodingAgentStripSupport#shouldPulse}. Errors of
 * the verbs are mapped from {@link CodingAgentActionException#code()} to a status label shown for
 * five seconds — never a modal dialog.
 *
 * <p>Style class {@code coding-agent-panel} carries no CSS rules by convention; colours are applied
 * inline through {@link #applyTheme}. All methods must be called on the FX application thread.
 */
public class CodingAgentPanel extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(CodingAgentPanel.class);

    private static final long FRAME_INTERVAL_NANOS = 33_000_000L;
    private static final int EVIDENCE_MAX_CHARS = 80;
    private static final int EXPLAIN_ROWS = 6;
    private static final int PROMPT_ROWS = 2;
    private static final int PROMPT_COLUMNS = 16;
    private static final double STATE_DOT_RADIUS = 5;
    private static final String DEFAULT_BG = "#1e1e1e";
    private static final String DEFAULT_FG = "#d4d4d4";
    private static final String ROW_CURRENT_CLASS = "coding-agent-row-current";
    private static final KeyCombination NEXT_BLOCKED_ACCELERATOR =
        new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN);

    /** A verb of {@link CodingAgentActions} run from a button. */
    @FunctionalInterface
    private interface ActionCall {
        void run() throws CodingAgentActionException;
    }

    private final CodingAgentRegistry registry;
    private final CodingAgentActions actions;
    private final CodingAgentNavigator navigator;
    private final PaneLocator locator;
    private final LongSupplier clockMillis;

    // header
    private final Label titleLabel = new Label(I18n.get("codingAgent.panel.title"));
    private final Label summaryLabel = new Label();
    private final Button nextBlockedButton = new Button(I18n.get("codingAgent.panel.nextBlocked"));
    private final MenuButton overflowMenu = new MenuButton("⋯");

    // center
    private final ListView<CodingAgentEntry> list = new ListView<>();
    private final Label emptyPlaceholder = new Label(I18n.get("codingAgent.panel.empty"));

    // bottom
    private final Label statusLabel = new Label();
    private final PauseTransition statusClear = new PauseTransition(Duration.seconds(5));
    private final ComboBox<CodingAgentEntry> targetCombo = new ComboBox<>();
    private final TextArea promptArea = new TextArea();
    private final Button sendButton = new Button(I18n.get("codingAgent.panel.prompt.send"));
    private final Tooltip sendBlockedTooltip = new Tooltip(I18n.get("codingAgent.panel.prompt.blocked"));

    private final Set<AgentCell> cells = new HashSet<>();
    private final Set<PaneRef> expandedExplain = new HashSet<>();
    private final Map<PaneRef, String> explainTexts = new HashMap<>();

    private Consumer<CodingAgentPanelDockManager.Placement> onDockRequest;
    private Runnable onNextBlocked;

    private AutoCloseable registryHandle;
    private Timeline durationTimer;
    private boolean bound;
    private boolean disposed;
    private boolean windowActive = true;
    private boolean syncingSelection;
    private PaneRef selectedPane;
    private PaneRef currentPane;
    private AgentSummary lastSummary = AgentSummary.EMPTY;

    // theme
    private String background = DEFAULT_BG;
    private String foreground = DEFAULT_FG;
    private boolean lightBackground;
    private String dimStyle = "";

    // pulse
    private final long originNanos = System.nanoTime();
    private long lastFrameNanos;
    private boolean timerRunning;
    private final AnimationTimer pulseTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (now - lastFrameNanos < FRAME_INTERVAL_NANOS) {
                return;
            }
            lastFrameNanos = now;
            renderPulse(nowSeconds());
        }
    };
    private final ChangeListener<Boolean> showingListener = (obs, was, showing) -> updateTimerState();
    private final ChangeListener<Window> windowListener = (obs, oldWindow, newWindow) -> {
        if (oldWindow != null) {
            oldWindow.showingProperty().removeListener(showingListener);
        }
        attachedWindow = newWindow;
        if (newWindow != null) {
            newWindow.showingProperty().addListener(showingListener);
        }
        updateTimerState();
    };
    private final ChangeListener<Scene> sceneListener = (obs, oldScene, newScene) -> {
        detachWindowListeners();
        attachWindowListeners(newScene);
        updateTimerState();
    };
    private Scene attachedScene;
    private Window attachedWindow;

    /**
     * @param registry the application-scoped registry (all windows' agents)
     * @param actions the verbs run by the buttons and the prompt box
     * @param navigator focus and "next blocked" navigation across windows
     * @param locator resolves the location line of a pane
     * @param clockMillis the clock the durations are computed against (the registry clock)
     */
    public CodingAgentPanel(CodingAgentRegistry registry, CodingAgentActions actions, CodingAgentNavigator navigator,
                            PaneLocator locator, LongSupplier clockMillis) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.locator = Objects.requireNonNull(locator, "locator");
        this.clockMillis = clockMillis != null ? clockMillis : System::currentTimeMillis;
        getStyleClass().add("coding-agent-panel");
        setTop(buildHeader());
        setCenter(buildList());
        setBottom(buildPromptBox());
        statusClear.setOnFinished(event -> clearStatus());
        applyTheme(null, null);
    }

    // ---- construction -------------------------------------------------------------------------

    private VBox buildHeader() {
        titleLabel.setStyle("-fx-font-weight: bold;");
        summaryLabel.setStyle(dimStyle);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        nextBlockedButton.setTooltip(new Tooltip(I18n.get("menu.codingAgent.nextBlocked") + " ("
            + NEXT_BLOCKED_ACCELERATOR.getDisplayText() + ")"));
        nextBlockedButton.setOnAction(event -> focusNextBlocked());

        MenuItem dockLeft = new MenuItem(I18n.get("codingAgent.panel.dockLeft"));
        dockLeft.setOnAction(event -> requestDock(CodingAgentPanelDockManager.Placement.LEFT));
        MenuItem dockRight = new MenuItem(I18n.get("codingAgent.panel.dockRight"));
        dockRight.setOnAction(event -> requestDock(CodingAgentPanelDockManager.Placement.RIGHT));
        MenuItem hide = new MenuItem(I18n.get("codingAgent.panel.hide"));
        hide.setOnAction(event -> requestDock(CodingAgentPanelDockManager.Placement.HIDDEN));
        overflowMenu.getItems().addAll(dockLeft, dockRight, hide);

        HBox titleRow = new HBox(8, titleLabel, summaryLabel, spacer, nextBlockedButton, overflowMenu);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        VBox header = new VBox(titleRow);
        header.setPadding(new Insets(8, 8, 6, 8));
        return header;
    }

    private ListView<CodingAgentEntry> buildList() {
        list.getStyleClass().add("coding-agent-list");
        emptyPlaceholder.setWrapText(true);
        emptyPlaceholder.setPadding(new Insets(12));
        list.setPlaceholder(emptyPlaceholder);
        list.setCellFactory(view -> {
            AgentCell cell = new AgentCell();
            cells.add(cell);
            return cell;
        });
        list.getSelectionModel().selectedItemProperty().addListener((obs, oldEntry, newEntry) -> {
            if (syncingSelection) {
                return;
            }
            if (newEntry != null) {
                selectedPane = newEntry.pane();
                syncingSelection = true;
                try {
                    targetCombo.getSelectionModel().select(newEntry);
                } finally {
                    syncingSelection = false;
                }
                updateSendState();
            }
        });
        return list;
    }

    private VBox buildPromptBox() {
        statusLabel.setWrapText(true);
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);

        targetCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(CodingAgentEntry entry) {
                if (entry == null) {
                    return "";
                }
                String location = locator.locate(entry.pane()).map(PaneLocation::shortText).orElse("");
                return location.isBlank() ? entry.displayName()
                    : entry.displayName() + CodingAgentGlyphs.SEPARATOR + location;
            }

            @Override
            public CodingAgentEntry fromString(String text) {
                return null;
            }
        });
        targetCombo.setMinWidth(110);
        targetCombo.setMaxWidth(220);
        targetCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldEntry, newEntry) -> {
            if (newEntry != null) {
                selectedPane = newEntry.pane();
            }
            updateSendState();
        });

        promptArea.setPrefRowCount(PROMPT_ROWS);
        // The TextArea default (40 columns) is wider than the panel and would squeeze the target
        // combo and the Send button to nothing; it grows with the row instead.
        promptArea.setPrefColumnCount(PROMPT_COLUMNS);
        promptArea.setWrapText(true);
        promptArea.setPromptText(I18n.get("codingAgent.panel.prompt.placeholder"));
        promptArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() != KeyCode.ENTER) {
                return;
            }
            if (event.isShiftDown()) {
                promptArea.replaceSelection("\n");
            } else {
                sendPrompt();
            }
            event.consume();
        });
        HBox.setHgrow(promptArea, Priority.ALWAYS);

        sendButton.setOnAction(event -> sendPrompt());
        sendButton.setDisable(true);
        sendButton.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(6, targetCombo, promptArea, sendButton);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox bottom = new VBox(4, statusLabel, row);
        bottom.setPadding(new Insets(6, 8, 8, 8));
        return bottom;
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    /** Subscribes to the registry, starts the 1-second duration tick and renders; a no-op while bound. */
    public void bind() {
        if (bound || disposed) {
            return;
        }
        bound = true;
        registryHandle = registry.addListener(change -> refresh());
        durationTimer = new Timeline(new KeyFrame(Duration.seconds(1), event -> tick()));
        durationTimer.setCycleCount(Timeline.INDEFINITE);
        durationTimer.play();
        sceneProperty().addListener(sceneListener);
        attachWindowListeners(getScene());
        refresh();
    }

    /** Unsubscribes and stops every timer; a no-op while not bound. */
    public void unbind() {
        if (!bound) {
            return;
        }
        bound = false;
        closeRegistryHandle();
        if (durationTimer != null) {
            durationTimer.stop();
            durationTimer = null;
        }
        sceneProperty().removeListener(sceneListener);
        detachWindowListeners();
        stopTimer();
    }

    /** True between {@link #bind()} and {@link #unbind()}. */
    public boolean isBound() {
        return bound;
    }

    /** Rebuilds the rows from the registry (selection preserved by pane), the summary and the prompt target. */
    public void refresh() {
        if (disposed) {
            return;
        }
        List<CodingAgentEntry> entries = navigator.orderedEntries();
        currentPane = navigator.currentPane().orElse(null);
        AgentSummary summary = registry.summary();
        lastSummary = summary != null ? summary : AgentSummary.EMPTY;
        summaryLabel.setText(CodingAgentGlyphs.summaryText(lastSummary));
        nextBlockedButton.setDisable(lastSummary.blocked() == 0);

        Set<PaneRef> present = new HashSet<>();
        for (CodingAgentEntry entry : entries) {
            present.add(entry.pane());
        }
        expandedExplain.retainAll(present);
        explainTexts.keySet().retainAll(present);

        syncingSelection = true;
        try {
            list.getItems().setAll(entries);
            targetCombo.getItems().setAll(entries);
            targetCombo.setDisable(entries.isEmpty());
            CodingAgentEntry selected = findEntry(entries, selectedPane);
            if (selected == null && !entries.isEmpty()) {
                selected = entries.get(0);
                selectedPane = selected.pane();
            }
            if (selected != null) {
                list.getSelectionModel().select(selected);
                targetCombo.getSelectionModel().select(selected);
            } else {
                selectedPane = null;
                list.getSelectionModel().clearSelection();
                targetCombo.getSelectionModel().clearSelection();
            }
        } finally {
            syncingSelection = false;
        }
        updateSendState();
        updateTimerState();
    }

    /** The 1-second tick: updates the duration of each visible row whose text changed. */
    public void tick() {
        if (disposed) {
            return;
        }
        long now = clockMillis.getAsLong();
        for (AgentCell cell : cells) {
            cell.tick(now);
        }
    }

    /** Selects and scrolls to the row of {@code pane} (MainWindow: "Show in Coding Agents Panel"). */
    public void selectPane(PaneRef pane) {
        if (pane == null || disposed) {
            return;
        }
        selectedPane = pane;
        List<CodingAgentEntry> items = list.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (pane.equals(items.get(i).pane())) {
                list.getSelectionModel().select(i);
                list.scrollTo(i);
                targetCombo.getSelectionModel().select(items.get(i));
                updateSendState();
                return;
            }
        }
    }

    /** Receives the "⋯" menu's Dock Left / Dock Right / Hide choices; MainWindow wires it to the dock manager. */
    public void setOnDockRequest(Consumer<CodingAgentPanelDockManager.Placement> handler) {
        this.onDockRequest = handler;
    }

    /** Replaces the "Next blocked" button's default ({@link CodingAgentNavigator#focusNextBlocked}). */
    public void setOnNextBlocked(Runnable handler) {
        this.onNextBlocked = handler;
    }

    /** Applies the window's chrome colours inline; state colours switch to the light palette on a bright background. */
    public void applyTheme(String bgColor, String fgColor) {
        background = bgColor != null && !bgColor.isBlank() ? bgColor : DEFAULT_BG;
        foreground = fgColor != null && !fgColor.isBlank() ? fgColor : DEFAULT_FG;
        lightBackground = CodingAgentStripSupport.isLight(background);
        dimStyle = "-fx-text-fill: derive(" + foreground + ", " + (lightBackground ? "45%" : "-45%") + ");";
        String textStyle = "-fx-text-fill: " + foreground + ";";
        setStyle("-fx-background-color: " + background + ";");
        titleLabel.setStyle("-fx-font-weight: bold; " + textStyle);
        summaryLabel.setStyle(dimStyle);
        emptyPlaceholder.setStyle(textStyle);
        statusLabel.setStyle(textStyle);
        list.setStyle("-fx-background-color: " + background + "; -fx-control-inner-background: " + background + ";");
        for (AgentCell cell : cells) {
            cell.applyPalette();
        }
        list.refresh();
    }

    /** Whether the hosting window is the foreground one; BLOCKED dots pulse only then. */
    public void setWindowActive(boolean active) {
        if (windowActive == active) {
            return;
        }
        windowActive = active;
        updateTimerState();
    }

    /** True while the pulse timer runs; the smoke asserts false after dispose. */
    public boolean isPulseTimerRunning() {
        return timerRunning;
    }

    /** Renders the pulse frame at {@code t} seconds without a running timer (smoke seam). */
    public void renderFrameForTest(double t) {
        renderPulse(t);
    }

    /** Unbinds, stops every timer and ignores later calls. */
    public void dispose() {
        if (disposed) {
            return;
        }
        unbind();
        disposed = true;
        statusClear.stop();
    }

    /**
     * "✋ Waiting for you · 2:14": glyph, localised state label ({@code codingAgent.state.<state>})
     * and time in state.
     *
     * @param messages (key, args) -&gt; text, typically {@code I18n::get}
     */
    static String rowStateText(CodingAgentEntry entry, long seconds, BiFunction<String, Object[], String> messages) {
        return CodingAgentStripSupport.rowStateText(entry != null ? entry.state() : null, seconds, messages);
    }

    // ---- actions ------------------------------------------------------------------------------

    private void focusNextBlocked() {
        if (onNextBlocked != null) {
            try {
                onNextBlocked.run();
            } catch (RuntimeException e) {
                logger.debug("Next-blocked handler failed: {}", e.toString());
            }
            return;
        }
        navigator.focusNextBlocked();
    }

    private void requestDock(CodingAgentPanelDockManager.Placement placement) {
        if (onDockRequest == null) {
            return;
        }
        try {
            onDockRequest.accept(placement);
        } catch (RuntimeException e) {
            logger.debug("Dock request handler failed: {}", e.toString());
        }
    }

    private void sendPrompt() {
        CodingAgentEntry target = targetCombo.getValue();
        String text = promptArea.getText();
        if (target == null || text == null || text.isBlank()) {
            return;
        }
        if (target.state() == CodingAgentState.BLOCKED) {
            showStatus(I18n.get("codingAgent.panel.prompt.blocked"));
            return;
        }
        boolean sent = runAction(target, () -> actions.prompt(target.pane(), text), text, true);
        if (sent) {
            promptArea.clear();
        }
    }

    private void focusPane(CodingAgentEntry entry) {
        if (!navigator.focus(entry.pane())) {
            showStatus(I18n.get("codingAgent.panel.error.paneNotFound"));
        }
    }

    private void sendKey(CodingAgentEntry entry, KeyChord chord) {
        runAction(entry, () -> actions.sendKey(entry.pane(), chord), null, true);
    }

    private void toggleExplain(CodingAgentEntry entry) {
        PaneRef pane = entry.pane();
        if (expandedExplain.remove(pane)) {
            explainTexts.remove(pane);
        } else {
            try {
                explainTexts.put(pane, actions.explain(pane));
                expandedExplain.add(pane);
            } catch (CodingAgentActionException e) {
                showError(e, null);
                return;
            }
        }
        list.refresh();
    }

    private void rename(CodingAgentEntry entry) {
        TextInputDialog dialog = new TextInputDialog(entry.alias() != null ? entry.alias() : "");
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(I18n.get("codingAgent.panel.rename.title"));
        dialog.setHeaderText(entry.kind().displayName());
        dialog.setContentText(I18n.get("codingAgent.panel.rename.prompt"));
        Scene scene = getScene();
        if (scene != null && scene.getWindow() != null) {
            dialog.initOwner(scene.getWindow());
        }
        Optional<String> alias = dialog.showAndWait();
        alias.ifPresent(value -> runAction(entry, () -> actions.rename(entry.pane(), value), null, false));
    }

    /**
     * Runs a verb on the FX thread; a refused or failed verb shows its mapped message in the status
     * label for five seconds (EMPTY_INPUT is ignored), a successful send shows "Sent to {name}".
     *
     * @param promptText the text being sent for the host-shortcut message, or null for key verbs
     * @param announce whether success shows "Sent to {name}" (false for rename)
     * @return true when the verb succeeded
     */
    private boolean runAction(CodingAgentEntry entry, ActionCall call, String promptText, boolean announce) {
        try {
            call.run();
            if (announce) {
                showStatus(I18n.get("codingAgent.panel.sent", entry.displayName()));
            }
            return true;
        } catch (CodingAgentActionException e) {
            showError(e, promptText);
            return false;
        } catch (RuntimeException e) {
            logger.warn("Coding agent action failed unexpectedly: {}", e.toString());
            showStatus(I18n.get("codingAgent.panel.error.writeFailed", e.toString()));
            return false;
        }
    }

    private void showError(CodingAgentActionException e, String promptText) {
        String message = switch (e.code()) {
            case PANE_NOT_FOUND -> I18n.get("codingAgent.panel.error.paneNotFound");
            case NOT_CONNECTED -> I18n.get("codingAgent.panel.error.notConnected");
            case WRITE_FAILED -> I18n.get("codingAgent.panel.error.writeFailed", String.valueOf(e.getMessage()));
            case AGENT_BLOCKED -> I18n.get("codingAgent.panel.prompt.blocked");
            case HOST_SHORTCUT_CONFLICT -> I18n.get("codingAgent.panel.prompt.hostShortcut", firstToken(promptText));
            case EMPTY_INPUT -> null;
        };
        if (message != null) {
            showStatus(message);
        }
    }

    private static String firstToken(String text) {
        if (text == null) {
            return "";
        }
        String firstLine = text.strip();
        int newline = firstLine.indexOf('\n');
        if (newline >= 0) {
            firstLine = firstLine.substring(0, newline);
        }
        int space = firstLine.indexOf(' ');
        return space > 0 ? firstLine.substring(0, space) : firstLine;
    }

    private void showStatus(String message) {
        statusLabel.setText(message);
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
        statusClear.playFromStart();
    }

    private void clearStatus() {
        statusLabel.setText("");
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
    }

    private void updateSendState() {
        CodingAgentEntry target = targetCombo.getValue();
        if (target == null) {
            sendButton.setDisable(true);
        sendButton.setMinWidth(Region.USE_PREF_SIZE);
            sendButton.setTooltip(null);
            promptArea.setDisable(targetCombo.getItems().isEmpty());
            return;
        }
        promptArea.setDisable(false);
        boolean blocked = target.state() == CodingAgentState.BLOCKED;
        sendButton.setDisable(blocked);
        sendButton.setTooltip(blocked ? sendBlockedTooltip : null);
    }

    private static CodingAgentEntry findEntry(List<CodingAgentEntry> entries, PaneRef pane) {
        if (pane == null) {
            return null;
        }
        for (CodingAgentEntry entry : entries) {
            if (pane.equals(entry.pane())) {
                return entry;
            }
        }
        return null;
    }

    // ---- pulse --------------------------------------------------------------------------------

    private void updateTimerState() {
        boolean shouldRun = !disposed
            && bound
            && CodingAgentStripSupport.shouldPulse(lastSummary, windowActive,
                AppDesignStyleSupport.appDesignAnimationsEnabled(), attachedToShowingWindow());
        if (shouldRun && !timerRunning) {
            timerRunning = true;
            pulseTimer.start();
        } else if (!shouldRun && timerRunning) {
            stopTimer();
        }
    }

    private void stopTimer() {
        if (timerRunning) {
            pulseTimer.stop();
            timerRunning = false;
        }
        for (AgentCell cell : cells) {
            cell.resetPulse();
        }
    }

    private void renderPulse(double t) {
        double scale = SwarmStatusStripSupport.pulseScale(t, 0);
        double alpha = SwarmStatusStripSupport.pulseGlowAlpha(t, 0);
        for (AgentCell cell : cells) {
            cell.renderPulse(scale, alpha);
        }
    }

    private double nowSeconds() {
        return (System.nanoTime() - originNanos) / 1_000_000_000.0;
    }

    private boolean attachedToShowingWindow() {
        Scene scene = getScene();
        if (scene == null) {
            return false;
        }
        Window window = scene.getWindow();
        return window != null && window.isShowing();
    }

    private void attachWindowListeners(Scene scene) {
        attachedScene = scene;
        if (scene == null) {
            return;
        }
        scene.windowProperty().addListener(windowListener);
        attachedWindow = scene.getWindow();
        if (attachedWindow != null) {
            attachedWindow.showingProperty().addListener(showingListener);
        }
    }

    private void detachWindowListeners() {
        if (attachedScene != null) {
            attachedScene.windowProperty().removeListener(windowListener);
        }
        if (attachedWindow != null) {
            attachedWindow.showingProperty().removeListener(showingListener);
        }
        attachedScene = null;
        attachedWindow = null;
    }

    private void closeRegistryHandle() {
        AutoCloseable handle = registryHandle;
        registryHandle = null;
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (Exception e) {
            logger.debug("Could not unsubscribe the coding agent panel: {}", e.toString());
        }
    }

    // ---- row cell -----------------------------------------------------------------------------

    /** One agent row; keeps the duration String by identity so the tick re-renders only on change. */
    private final class AgentCell extends ListCell<CodingAgentEntry> {

        private final VBox root = new VBox(2);
        private final Circle stateDot = new Circle(STATE_DOT_RADIUS);
        private final Label nameLabel = new Label();
        private final Label stateChip = new Label();
        private final Label kindTag = new Label();
        private final Label locationLabel = new Label();
        private final Label evidenceLabel = new Label();
        private final TextArea explainArea = new TextArea();

        private final Button focusButton = new Button(I18n.get("codingAgent.panel.focus"));
        private final Button explainButton = new Button(I18n.get("codingAgent.panel.explain"));
        private final Button renameButton = new Button(I18n.get("codingAgent.panel.rename"));
        private final List<Button> keyButtons = new ArrayList<>();
        private final List<Button> answerButtons = new ArrayList<>();
        private final Map<Button, Tooltip> keyTooltips = new HashMap<>();
        private final Tooltip notConnectedTooltip = new Tooltip(I18n.get("codingAgent.panel.notConnected"));

        private CodingAgentEntry entry;
        private String cachedDuration;

        AgentCell() {
            root.setPadding(new Insets(6, 8, 6, 8));
            // The stylesheets' selected-cell colour equals their button colour, which would make the
            // quick keys of the selected row vanish; the selection tint is derived from the panel
            // background instead.
            selectedProperty().addListener((obs, was, selected) -> applySelectionStyle());
            nameLabel.setStyle("-fx-font-weight: bold;");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox line1 = new HBox(6, stateDot, nameLabel, stateChip, spacer, kindTag);
            line1.setAlignment(Pos.CENTER_LEFT);

            locationLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            locationLabel.setMaxWidth(Double.MAX_VALUE);
            evidenceLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            evidenceLabel.setMaxWidth(Double.MAX_VALUE);

            focusButton.setOnAction(event -> withEntry(CodingAgentPanel.this::focusPane));
            Button yButton = keyButton("y", KeyChord.Y, true);
            Button nButton = keyButton("n", KeyChord.N, true);
            Button enterButton = keyButton("Enter", KeyChord.ENTER, true);
            Button escButton = keyButton("Esc", KeyChord.ESC, true);
            Button upButton = keyButton("↑", KeyChord.UP, false);
            Button downButton = keyButton("↓", KeyChord.DOWN, false);
            Button ctrlCButton = keyButton("Ctrl+C", KeyChord.CTRL_C, false);
            explainButton.setOnAction(event -> withEntry(CodingAgentPanel.this::toggleExplain));
            renameButton.setOnAction(event -> withEntry(CodingAgentPanel.this::rename));
            // A FlowPane, not an HBox: ten buttons are wider than the panel at its default width
            // (380 px), and a row that wraps beats one whose last buttons are clipped away.
            FlowPane line4 = new FlowPane(4, 4, focusButton, yButton, nButton, enterButton, escButton, upButton,
                downButton, ctrlCButton, explainButton, renameButton);
            line4.setAlignment(Pos.CENTER_LEFT);

            explainArea.setEditable(false);
            explainArea.setWrapText(true);
            explainArea.setPrefRowCount(EXPLAIN_ROWS);
            explainArea.setStyle("-fx-font-family: monospace;");
            explainArea.setVisible(false);
            explainArea.setManaged(false);

            root.getChildren().addAll(line1, locationLabel, evidenceLabel, line4, explainArea);
            applyPalette();
        }

        private Button keyButton(String text, KeyChord chord, boolean answer) {
            Button button = new Button(text);
            Tooltip tooltip = new Tooltip(I18n.get("codingAgent.panel.key.tooltip", text));
            button.setTooltip(tooltip);
            button.setOnAction(event -> withEntry(current -> sendKey(current, chord)));
            keyButtons.add(button);
            keyTooltips.put(button, tooltip);
            if (answer) {
                answerButtons.add(button);
            }
            return button;
        }

        private void withEntry(Consumer<CodingAgentEntry> action) {
            CodingAgentEntry current = entry;
            if (current != null) {
                action.accept(current);
            }
        }

        @Override
        protected void updateItem(CodingAgentEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                entry = null;
                cachedDuration = null;
                setGraphic(null);
                setText(null);
                getStyleClass().remove(ROW_CURRENT_CLASS);
                resetPulse();
                return;
            }
            boolean changedRow = entry == null || !entry.pane().equals(item.pane()) || entry.state() != item.state();
            entry = item;
            if (changedRow) {
                cachedDuration = null;
                resetPulse();
            }
            nameLabel.setText(item.displayName());
            boolean aliased = item.alias() != null && !item.alias().isBlank();
            kindTag.setText(aliased ? item.kind().displayName() : "");
            kindTag.setVisible(aliased);
            kindTag.setManaged(aliased);
            locationLabel.setText(locator.locate(item.pane()).map(location -> location.text(I18n::get)).orElse(""));
            String evidence = CodingAgentGlyphs.evidenceLine(item.detection(), EVIDENCE_MAX_CHARS);
            evidenceLabel.setText(evidence);
            evidenceLabel.setVisible(!evidence.isEmpty());
            evidenceLabel.setManaged(!evidence.isEmpty());

            boolean connected = actions.isConnected(item.pane());
            boolean blocked = item.state() == CodingAgentState.BLOCKED;
            String stateColor = CodingAgentStripSupport.colorHex(item.state(), lightBackground);
            for (Button button : keyButtons) {
                button.setDisable(!connected);
                button.setTooltip(connected ? keyTooltips.get(button) : notConnectedTooltip);
            }
            // Explicit colours, not -fx-base: the design stylesheets paint .button with a literal
            // background, which would swallow a derived accent.
            for (Button button : answerButtons) {
                button.setStyle(blocked
                    ? "-fx-background-color: " + stateColor + "; -fx-text-fill: "
                        + CodingAgentStripSupport.chipTextHex(lightBackground) + ";"
                    : null);
            }
            boolean expanded = expandedExplain.contains(item.pane());
            explainArea.setText(expanded ? explainTexts.getOrDefault(item.pane(), "") : "");
            explainArea.setVisible(expanded);
            explainArea.setManaged(expanded);

            boolean current = currentPane != null && currentPane.equals(item.pane());
            if (current && !getStyleClass().contains(ROW_CURRENT_CLASS)) {
                getStyleClass().add(ROW_CURRENT_CLASS);
            } else if (!current) {
                getStyleClass().remove(ROW_CURRENT_CLASS);
            }
            root.setStyle(current
                ? "-fx-border-color: transparent transparent transparent " + stateColor + "; -fx-border-width: 0 0 0 3;"
                : null);
            applyPalette();
            tick(clockMillis.getAsLong());
            setText(null);
            setGraphic(root);
        }

        /** Re-renders the state chip only when the cached duration String identity changed. */
        void tick(long nowMillis) {
            CodingAgentEntry current = entry;
            if (current == null) {
                return;
            }
            long seconds = current.secondsInState(nowMillis);
            String duration = DurationText.mmss(seconds);
            if (duration != cachedDuration) { // identity: DurationText caches values below one hour
                cachedDuration = duration;
                stateChip.setText(rowStateText(current, seconds, I18n::get));
            }
        }

        void applySelectionStyle() {
            setStyle(isSelected()
                ? "-fx-background-color: derive(" + background + ", " + (lightBackground ? "-8%" : "10%") + ");"
                : null);
        }

        void applyPalette() {
            applySelectionStyle();
            CodingAgentEntry current = entry;
            CodingAgentState state = current != null ? current.state() : null;
            String stateColor = CodingAgentStripSupport.colorHex(state, lightBackground);
            stateDot.setFill(Color.web(stateColor));
            nameLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + foreground + ";");
            stateChip.setStyle("-fx-background-color: " + stateColor + "; -fx-text-fill: "
                + CodingAgentStripSupport.chipTextHex(lightBackground)
                + "; -fx-background-radius: 8; -fx-padding: 0 6 0 6; -fx-font-size: 0.9231em;");
            kindTag.setStyle(dimStyle);
            locationLabel.setStyle(dimStyle);
            evidenceLabel.setStyle(dimStyle + " -fx-font-family: monospace;");
        }

        void renderPulse(double scale, double alpha) {
            CodingAgentEntry current = entry;
            if (current == null || current.state() != CodingAgentState.BLOCKED) {
                resetPulse();
                return;
            }
            stateDot.setScaleX(scale);
            stateDot.setScaleY(scale);
            stateDot.setOpacity(alpha);
        }

        void resetPulse() {
            stateDot.setScaleX(1.0);
            stateDot.setScaleY(1.0);
            stateDot.setOpacity(1.0);
        }
    }
}
