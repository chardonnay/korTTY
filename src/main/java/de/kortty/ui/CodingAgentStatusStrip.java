package de.kortty.ui;

import de.kortty.codingagent.AgentSummary;
import de.kortty.codingagent.CodingAgentGlyphs;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.CodingAgentState;
import javafx.animation.AnimationTimer;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * The status-bar strip "✋ 1 · ⚡ 2 · ✓ 1": up to three chips (BLOCKED, WORKING, DONE — zero
 * counts omitted) over the global {@link AgentSummary} of the {@link CodingAgentRegistry}. The
 * whole strip is invisible and unmanaged while no agent is registered, so the status bar looks
 * exactly as before when nothing runs. A primary click runs the activate handler (MainWindow jumps
 * to the next blocked agent), a right-click offers Show/Hide panel and Next Blocked Agent.
 *
 * <p>The strip subscribes to the registry only between {@link #attach()} and {@link #detach()}
 * and keeps the handle in a field so hiding or closing the window always unsubscribes. The
 * BLOCKED dot pulses through one {@link AnimationTimer} (33 ms frame cap) that runs only while
 * {@link CodingAgentStripSupport#shouldPulse} allows it; a never-shown smoke Stage therefore keeps
 * the timer off and frames come from {@link #renderFrameForTest}.
 *
 * <p>All methods must be called on the FX application thread.
 */
public class CodingAgentStatusStrip extends HBox {

    private static final Logger logger = LoggerFactory.getLogger(CodingAgentStatusStrip.class);

    private static final long FRAME_INTERVAL_NANOS = 33_000_000L;
    private static final double DOT_RADIUS = 4;
    private static final String DEFAULT_BG = "#2d2d2d";
    private static final String DEFAULT_FG = "#cccccc";

    private final CodingAgentRegistry registry;

    private final Chip blockedChip = new Chip(CodingAgentState.BLOCKED);
    private final Chip workingChip = new Chip(CodingAgentState.WORKING);
    private final Chip doneChip = new Chip(CodingAgentState.DONE);
    private final Label firstSeparator = new Label(CodingAgentGlyphs.SEPARATOR.strip());
    private final Label secondSeparator = new Label(CodingAgentGlyphs.SEPARATOR.strip());
    private final Tooltip tooltip = new Tooltip();
    private final ContextMenu contextMenu = new ContextMenu();

    private Runnable onActivate;
    private Runnable onShowPanel;
    private Runnable onNextBlocked;

    private AutoCloseable registryHandle;
    private boolean attached;
    private boolean disposed;
    private boolean windowActive = true;
    private boolean lightBackground;
    private String foreground = DEFAULT_FG;
    private AgentSummary lastSummary = AgentSummary.EMPTY;

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

    // Scene/window listeners are kept in fields and detached symmetrically: they hang off the
    // long-lived main window, so leaving them registered would leak the strip.
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

    /** One state chip: a coloured dot and its glyph + count label. */
    private final class Chip extends HBox {
        private final CodingAgentState state;
        private final Circle dot = new Circle(DOT_RADIUS);
        private final Label label = new Label();
        private String countText;

        Chip(CodingAgentState state) {
            super(4);
            this.state = state;
            setAlignment(Pos.CENTER_LEFT);
            getChildren().addAll(dot, label);
            setVisible(false);
            setManaged(false);
        }

        void update(int count) {
            boolean show = count > 0;
            if (show) {
                String text = CodingAgentStripSupport.countText(count);
                if (text != countText) { // identity: countText is cached for 0..99
                    countText = text;
                    label.setText(CodingAgentGlyphs.glyph(state) + " " + text);
                }
            }
            setVisible(show);
            setManaged(show);
        }

        void applyPalette() {
            String color = CodingAgentStripSupport.colorHex(state, lightBackground);
            dot.setFill(Color.web(color));
            label.setStyle("-fx-text-fill: " + color + ";");
        }

        void resetPulse() {
            dot.setScaleX(1.0);
            dot.setScaleY(1.0);
            dot.setOpacity(1.0);
        }
    }

    /**
     * @param registry the application-scoped registry whose summary the strip renders
     */
    public CodingAgentStatusStrip(CodingAgentRegistry registry) {
        super(6);
        this.registry = Objects.requireNonNull(registry, "registry");
        getStyleClass().add("coding-agent-status-strip");
        setAlignment(Pos.CENTER_LEFT);
        setCursor(Cursor.HAND);
        getChildren().addAll(blockedChip, firstSeparator, workingChip, secondSeparator, doneChip);
        firstSeparator.setVisible(false);
        firstSeparator.setManaged(false);
        secondSeparator.setVisible(false);
        secondSeparator.setManaged(false);

        MenuItem showPanelItem = new MenuItem(I18n.get("menu.codingAgent.panel.toggle"));
        showPanelItem.setOnAction(event -> run(onShowPanel));
        MenuItem nextBlockedItem = new MenuItem(I18n.get("menu.codingAgent.nextBlocked"));
        nextBlockedItem.setOnAction(event -> run(onNextBlocked));
        contextMenu.getItems().addAll(showPanelItem, nextBlockedItem);

        setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                run(onActivate);
                event.consume();
            }
        });
        setOnContextMenuRequested(event -> {
            contextMenu.show(this, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        Tooltip.install(this, tooltip);

        applyPalette();
        setVisible(false);
        setManaged(false);
    }

    /** Subscribes to the registry and renders the current summary; a no-op while already attached. */
    public void attach() {
        if (attached || disposed) {
            return;
        }
        attached = true;
        registryHandle = registry.addListener(change -> refresh());
        sceneProperty().addListener(sceneListener);
        attachWindowListeners(getScene());
        refresh();
    }

    /** Unsubscribes from the registry and stops the pulse timer; a no-op while not attached. */
    public void detach() {
        if (!attached) {
            return;
        }
        attached = false;
        closeRegistryHandle();
        sceneProperty().removeListener(sceneListener);
        detachWindowListeners();
        stopTimer();
    }

    /** Primary-click handler (MainWindow: focus the next blocked agent, or the first agent). */
    public void setOnActivate(Runnable onActivate) {
        this.onActivate = onActivate;
    }

    /** Right-click "Show/Hide" handler (MainWindow: toggle the Coding Agents panel). */
    public void setOnShowPanel(Runnable onShowPanel) {
        this.onShowPanel = onShowPanel;
    }

    /** Right-click "Next Blocked Agent" handler. */
    public void setOnNextBlocked(Runnable onNextBlocked) {
        this.onNextBlocked = onNextBlocked;
    }

    /** Whether the hosting window is the foreground one; the BLOCKED dot pulses only then. */
    public void setWindowActive(boolean active) {
        if (windowActive == active) {
            return;
        }
        windowActive = active;
        updateTimerState();
    }

    /**
     * Applies the status-bar colours inline (the strip inherits the bar's background; text colours
     * must be set explicitly because the theme code overwrites the bar's style).
     */
    public void applyTheme(String bgColor, String fgColor) {
        String bg = bgColor != null && !bgColor.isBlank() ? bgColor : DEFAULT_BG;
        foreground = fgColor != null && !fgColor.isBlank() ? fgColor : DEFAULT_FG;
        lightBackground = CodingAgentStripSupport.isLight(bg);
        applyPalette();
    }

    /** Re-reads the registry summary and updates chips, visibility, tooltip and the pulse gate. */
    public void refresh() {
        if (disposed) {
            return;
        }
        AgentSummary summary = registry.summary();
        if (summary == null) {
            summary = AgentSummary.EMPTY;
        }
        lastSummary = summary;
        blockedChip.update(summary.blocked());
        workingChip.update(summary.working());
        doneChip.update(summary.done());
        boolean showFirst = summary.blocked() > 0 && (summary.working() > 0 || summary.done() > 0);
        boolean showSecond = summary.working() > 0 && summary.done() > 0;
        firstSeparator.setVisible(showFirst);
        firstSeparator.setManaged(showFirst);
        secondSeparator.setVisible(showSecond);
        secondSeparator.setManaged(showSecond);
        boolean show = !summary.isEmpty();
        setVisible(show);
        setManaged(show);
        if (show) {
            tooltip.setText(I18n.get("codingAgent.strip.tooltip", summary.total(),
                CodingAgentStripSupport.stripText(summary)));
        }
        updateTimerState();
    }

    /** True while the BLOCKED pulse timer runs; the smoke asserts false after dispose. */
    public boolean isPulseTimerRunning() {
        return timerRunning;
    }

    /** Renders the pulse frame at {@code t} seconds without a running timer (smoke seam). */
    public void renderFrameForTest(double t) {
        renderPulse(t);
    }

    /** Detaches, stops the timer and ignores every later call. */
    public void dispose() {
        if (disposed) {
            return;
        }
        detach();
        disposed = true;
        contextMenu.hide();
        Tooltip.uninstall(this, tooltip);
    }

    // ---- internals ----------------------------------------------------------------------------

    private void applyPalette() {
        blockedChip.applyPalette();
        workingChip.applyPalette();
        doneChip.applyPalette();
        String dimStyle = "-fx-text-fill: derive(" + foreground + ", " + (lightBackground ? "45%" : "-45%") + ");";
        firstSeparator.setStyle(dimStyle);
        secondSeparator.setStyle(dimStyle);
    }

    private void updateTimerState() {
        boolean shouldRun = !disposed
            && attached
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
        blockedChip.resetPulse();
    }

    private void renderPulse(double t) {
        if (lastSummary.blocked() <= 0) {
            blockedChip.resetPulse();
            return;
        }
        double scale = SwarmStatusStripSupport.pulseScale(t, 0);
        blockedChip.dot.setScaleX(scale);
        blockedChip.dot.setScaleY(scale);
        blockedChip.dot.setOpacity(SwarmStatusStripSupport.pulseGlowAlpha(t, 0));
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
            logger.debug("Could not unsubscribe the coding agent status strip: {}", e.toString());
        }
    }

    private static void run(Runnable handler) {
        if (handler == null) {
            return;
        }
        try {
            handler.run();
        } catch (RuntimeException e) {
            logger.debug("Coding agent status strip handler failed: {}", e.toString());
        }
    }
}
