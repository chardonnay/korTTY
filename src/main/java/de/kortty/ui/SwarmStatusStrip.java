package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.swarm.SwarmModels;
import de.kortty.model.SavedSwarmServerSummary;
import javafx.animation.AnimationTimer;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Animated "swarm" visualization shown at the top of the AI swarm window: one glowing orb per
 * agent. Running agents breathe, agents awaiting approval blink with a sonar ping, finished agents
 * settle green with a short pop, failures flash red, and agents that run unusually long (adaptive
 * median rule, see {@link SwarmStatusStripSupport}) carry a rotating amber warning arc. A legend
 * with per-state counts is drawn on the right; hovering an orb shows a details popup and clicking
 * it notifies the host (which highlights the matching dashboard row).
 *
 * <p>All methods must be called on the FX thread. The render loop is an {@link AnimationTimer}
 * capped at ~30fps that only runs while something actually animates, the tab is selected and the
 * window is showing; it stops itself 1.5s after the last agent reached a terminal state.
 */
class SwarmStatusStrip extends Pane {

    private static final double FRAME_INTERVAL_NANOS = 33_000_000L;
    private static final double PADDING_X = 12;
    private static final double PADDING_Y = 4;
    private static final double COMPACT_LEGEND_MIN_WIDTH = 480;

    private final Canvas canvas = new Canvas();
    private final List<SwarmStatusStripSupport.AgentViz> agents = new ArrayList<>();
    private final Map<String, SwarmStatusStripSupport.AgentViz> agentsById = new LinkedHashMap<>();
    private final Map<String, RadialGradient> glowGradients = new HashMap<>();
    private final Map<String, RadialGradient> coreGradients = new HashMap<>();

    private final long originNanos = System.nanoTime();
    private long lastFrameNanos;
    private boolean timerRunning;
    private boolean disposed;
    private boolean staticMode;
    private boolean runFinished;
    private boolean tabSelected = true;
    private Consumer<String> onOrbClicked;

    private SwarmStatusStripSupport.StripLayout lastLayout = SwarmStatusStripSupport.StripLayout.empty();
    private double lastFieldX;
    private double lastFieldY;
    private int hoveredOrbIndex = -1;

    // Scene/window listeners are kept in fields and detached symmetrically: they hang off the
    // long-lived main window, so leaving them registered would leak one strip per closed tab.
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
    private Scene attachedScene;
    private Window attachedWindow;

    private Popup hoverPopup;
    private Label popupNameLabel;
    private Label popupStateLabel;
    private Label popupElapsedLabel;
    private Label popupTokensLabel;
    private Label popupSlowLabel;
    private Label popupHintLabel;

    private final AnimationTimer driver = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (now - lastFrameNanos < FRAME_INTERVAL_NANOS) {
                return;
            }
            lastFrameNanos = now;
            double t = nowSeconds();
            render(t);
            if (!anyAnimatedState(t)) {
                stopTimer();
                render(t);
            }
        }
    };

    SwarmStatusStrip() {
        setPrefHeight(96);
        setMinHeight(84);
        setMaxHeight(110);
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        canvas.widthProperty().addListener((obs, o, n) -> render(nowSeconds()));
        canvas.heightProperty().addListener((obs, o, n) -> render(nowSeconds()));
        setupHoverPopup();
        setupMouseHandlers();
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            hidePopup();
            detachWindowListeners();
            attachWindowListeners(newScene);
            updateTimerState();
        });
        setVisible(false);
        setManaged(false);
    }

    // ---- Host API (FX thread only) --------------------------------------------

    /** Empties the model and leaves static mode; the strip collapses until agents are added. */
    void clearAgents() {
        if (disposed) {
            return;
        }
        agents.clear();
        agentsById.clear();
        staticMode = false;
        runFinished = false;
        hidePopup();
        refreshVisibility();
        updateTimerState();
        renderIfStopped();
    }

    /** Registers one orb for the upcoming run. Leaves static mode if a saved chat was shown. */
    void addAgent(String agentId, String displayName) {
        if (disposed || agentId == null) {
            return;
        }
        if (staticMode) {
            agents.clear();
            agentsById.clear();
            staticMode = false;
        }
        runFinished = false;
        SwarmStatusStripSupport.AgentViz viz = new SwarmStatusStripSupport.AgentViz(agentId, displayName);
        SwarmStatusStripSupport.AgentViz previous = agentsById.put(agentId, viz);
        if (previous != null) {
            agents.remove(previous);
        }
        agents.add(viz);
        refreshVisibility();
        updateTimerState();
        renderIfStopped();
    }

    /** Mirrors {@code SwarmAgentRow.update}: applies a live status coming in over the callback. */
    void applyAgentStatus(SwarmModels.SwarmAgentStatus status) {
        if (disposed || status == null || staticMode) {
            return;
        }
        SwarmStatusStripSupport.AgentViz viz = agentsById.get(status.agentId());
        if (viz == null) {
            return;
        }
        if (viz.state != status.state()) {
            viz.lastStateChangeT = nowSeconds();
        }
        viz.state = status.state();
        viz.lastActivity = status.currentActivity() != null ? status.currentActivity() : "";
        viz.elapsedSeconds = status.elapsedSeconds();
        viz.totalTokens = status.tokens() != null ? status.tokens().total() : 0L;
        // Every status carries the agent's authoritative elapsed (pause-adjusted, restart-reset);
        // rebase unconditionally so the local 1s tick merely interpolates between statuses and the
        // slow rule never judges a new attempt by the old attempt's runtime.
        if (!SwarmStatusStripSupport.isTerminal(viz.state)) {
            viz.startedAtMillis = System.currentTimeMillis() - status.elapsedSeconds() * 1000L;
        }
        SwarmStatusStripSupport.refreshSlowFlags(agents);
        updateTimerState();
        renderIfStopped();
    }

    /** 1s cadence from the host timeline: advances elapsed times and the adaptive slow flags. */
    void tick() {
        if (disposed || staticMode || runFinished) {
            return;
        }
        long now = System.currentTimeMillis();
        for (SwarmStatusStripSupport.AgentViz viz : agents) {
            if (!SwarmStatusStripSupport.isTerminal(viz.state)
                && viz.state != SwarmModels.SwarmAgentState.PAUSED
                && viz.startedAtMillis > 0) {
                viz.elapsedSeconds = Math.max(0L, (now - viz.startedAtMillis) / 1000L);
            }
        }
        SwarmStatusStripSupport.refreshSlowFlags(agents);
        updateTimerState();
        renderIfStopped();
    }

    /**
     * Called when the whole run ends (also on cancellation, where queued agents never receive a
     * terminal status): dims every non-terminal orb and lets the animation wind down.
     */
    void markRunFinished() {
        if (disposed || staticMode) {
            return;
        }
        runFinished = true;
        for (SwarmStatusStripSupport.AgentViz viz : agents) {
            viz.slow = false;
        }
        updateTimerState();
        renderIfStopped();
    }

    /**
     * Static mode for rehydrated saved chats: renders the persisted final states without any
     * animation. An empty list collapses the strip.
     */
    void showFinalSummaries(List<SavedSwarmServerSummary> summaries) {
        if (disposed) {
            return;
        }
        agents.clear();
        agentsById.clear();
        staticMode = true;
        runFinished = false;
        if (summaries != null) {
            for (SavedSwarmServerSummary summary : summaries) {
                if (summary == null) {
                    continue;
                }
                String name = summary.getServerDisplayName() != null ? summary.getServerDisplayName() : "";
                SwarmStatusStripSupport.AgentViz viz = new SwarmStatusStripSupport.AgentViz(name, name);
                SwarmModels.SwarmAgentState state =
                    SwarmStatusStripSupport.parseFinalStateOrNull(summary.getFinalState());
                viz.state = state != null ? state : SwarmModels.SwarmAgentState.SKIPPED;
                viz.elapsedSeconds = summary.getElapsedSeconds();
                viz.totalTokens = summary.getTotalTokens();
                viz.lastStateChangeT = -10;
                agents.add(viz);
            }
        }
        hidePopup();
        refreshVisibility();
        updateTimerState();
        render(nowSeconds());
    }

    void setTabSelected(boolean selected) {
        if (disposed) {
            return;
        }
        this.tabSelected = selected;
        updateTimerState();
        if (selected) {
            renderIfStopped();
        }
    }

    void setOnOrbClicked(Consumer<String> handler) {
        this.onOrbClicked = handler;
    }

    boolean isAnimating() {
        return timerRunning;
    }

    /** True while the strip renders persisted final states of a rehydrated saved chat. */
    boolean isStaticMode() {
        return staticMode;
    }

    /** Stops the animation and detaches the strip; every mutator becomes a no-op afterwards. */
    void dispose() {
        disposed = true;
        stopTimer();
        hidePopup();
        detachWindowListeners();
        onOrbClicked = null;
    }

    /** Test seam: renders one frame at a fixed animation clock, bypassing the timer. */
    void renderFrameForTest(double t) {
        render(t);
    }

    // ---- Animation driver -------------------------------------------------------

    private double nowSeconds() {
        return (System.nanoTime() - originNanos) / 1_000_000_000.0;
    }

    private void updateTimerState() {
        boolean shouldRun = !disposed
            && !staticMode
            && tabSelected
            && attachedToShowingWindow()
            && AppDesignStyleSupport.appDesignAnimationsEnabled()
            && anyAnimatedState(nowSeconds());
        if (shouldRun && !timerRunning) {
            timerRunning = true;
            driver.start();
        } else if (!shouldRun && timerRunning) {
            stopTimer();
            render(nowSeconds());
        }
    }

    private void stopTimer() {
        driver.stop();
        timerRunning = false;
    }

    private boolean anyAnimatedState(double t) {
        for (SwarmStatusStripSupport.AgentViz viz : agents) {
            boolean terminal = SwarmStatusStripSupport.isTerminal(viz.state);
            if (terminal && viz.lastStateChangeT > 0
                && SwarmStatusStripSupport.isSettling(t - viz.lastStateChangeT)) {
                return true;
            }
            if (runFinished) {
                continue;
            }
            if (!terminal && (SwarmStatusStripSupport.isAnimated(viz.state) || viz.slow)) {
                return true;
            }
        }
        return false;
    }

    /** Keeps the strip correct (single event-driven frame) while the timer is off. */
    private void renderIfStopped() {
        if (!timerRunning) {
            render(nowSeconds());
        }
    }

    private void refreshVisibility() {
        boolean hasContent = !agents.isEmpty();
        setVisible(hasContent);
        setManaged(hasContent);
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

    // ---- Theme -------------------------------------------------------------------

    private record Palette(Color background, Color foreground, Color dim, String accentHex, boolean light) {
    }

    private Palette resolvePalette() {
        try {
            if (AppDesignStyleSupport.isCustomAppDesignActive()) {
                Color bg = Color.web(AppDesignStyleSupport.activeBackgroundColor());
                Color fg = Color.web(AppDesignStyleSupport.activeTextColor());
                Color dim = Color.web(AppDesignStyleSupport.activeDimColor());
                String accent = AppDesignStyleSupport.accentColor(AppDesignStyleSupport.activeDesign());
                return new Palette(bg, fg, dim, accent, bg.getBrightness() > 0.5);
            }
            ThemeCssSupport.ThemeColors colors =
                ThemeCssSupport.resolveThemeColors(KorTTYApplication.getInstance());
            if (colors != null) {
                Color bg = Color.web(colors.backgroundColor());
                Color fg = Color.web(colors.foregroundColor());
                return new Palette(bg, fg, fg.deriveColor(0, 1.0, 1.0, 0.6), null, bg.getBrightness() > 0.5);
            }
        } catch (Exception e) {
            // fall through to the default palette; a malformed color string must never break rendering
        }
        Color bg = Color.web("#1f2933");
        Color fg = Color.web("#d9e2ec");
        return new Palette(bg, fg, fg.deriveColor(0, 1.0, 1.0, 0.6), null, false);
    }

    private static Color deriveStripBackground(Color base) {
        return base.getBrightness() < 0.5
            ? base.deriveColor(0, 1.0, 1.12, 1.0)
            : base.deriveColor(0, 1.0, 0.94, 1.0);
    }

    // ---- Rendering -----------------------------------------------------------------

    private void render(double t) {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setGlobalAlpha(1.0);
        gc.clearRect(0, 0, width, height);

        Palette palette = resolvePalette();
        gc.setFill(deriveStripBackground(palette.background()));
        gc.fillRect(0, 0, width, height);
        gc.setStroke(palette.foreground().deriveColor(0, 1.0, 1.0, 0.12));
        gc.setLineWidth(1);
        gc.strokeLine(0, height - 0.5, width, height - 0.5);

        if (agents.isEmpty()) {
            return;
        }

        SwarmStatusStripSupport.VizSummary summary = SwarmStatusStripSupport.summarize(agents);
        boolean compactLegend = width < COMPACT_LEGEND_MIN_WIDTH;
        List<SwarmStatusStripSupport.LegendChip> chips = compactLegend
            ? List.of()
            : SwarmStatusStripSupport.legendChips(summary, palette.accentHex());
        double legendWidth = compactLegend ? 48 : SwarmStatusStripSupport.legendWidth(chips);

        double fieldX = PADDING_X;
        double fieldY = PADDING_Y;
        double fieldWidth = width - 2 * PADDING_X - legendWidth - (legendWidth > 0 ? 12 : 0);
        double fieldHeight = height - 2 * PADDING_Y;
        SwarmStatusStripSupport.StripLayout layout =
            SwarmStatusStripSupport.layout(agents.size(), fieldWidth, fieldHeight);
        lastLayout = layout;
        lastFieldX = fieldX;
        lastFieldY = fieldY;

        List<Integer> drawOrder = new ArrayList<>(layout.orbs().size());
        for (int i = 0; i < layout.orbs().size(); i++) {
            drawOrder.add(i);
        }
        drawOrder.sort(Comparator.comparingInt(i -> zPriority(agents.get(i))));

        for (int index : drawOrder) {
            drawOrb(gc, t, palette, agents.get(index), layout.orbs().get(index), fieldX, fieldY);
        }

        if (layout.labelMode() != SwarmStatusStripSupport.LabelMode.HIDDEN) {
            drawLabels(gc, palette, layout, fieldX, fieldY, fieldHeight);
        }
        if (layout.overflowCount() > 0) {
            gc.setGlobalAlpha(1.0);
            gc.setFill(palette.dim());
            gc.setFont(Font.font("System", FontWeight.NORMAL, 11));
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText("+" + layout.overflowCount(), fieldX + fieldWidth, fieldY + fieldHeight * 0.55);
        }

        if (compactLegend) {
            gc.setGlobalAlpha(1.0);
            gc.setFill(palette.dim());
            gc.setFont(Font.font("System", FontWeight.NORMAL, 12));
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText(summary.done() + "/" + summary.total(), width - PADDING_X, height * 0.5 + 4);
        } else {
            drawLegend(gc, t, palette, chips, width, height);
        }
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setGlobalAlpha(1.0);
    }

    private int zPriority(SwarmStatusStripSupport.AgentViz viz) {
        if (effectivelyDimmed(viz)) {
            return 0;
        }
        return switch (viz.state) {
            case DONE, FAILED -> 1;
            case QUEUED, PAUSED -> 2;
            case CONNECTING, PROBING, RUNNING -> 3;
            case AWAITING_APPROVAL -> 4;
            default -> 1;
        };
    }

    /** Non-terminal orbs render dim gray once the run finished (cancel with queued agents). */
    private boolean effectivelyDimmed(SwarmStatusStripSupport.AgentViz viz) {
        if (viz.state == SwarmModels.SwarmAgentState.CANCELLED
            || viz.state == SwarmModels.SwarmAgentState.SKIPPED) {
            return true;
        }
        return runFinished && !SwarmStatusStripSupport.isTerminal(viz.state);
    }

    private void drawOrb(GraphicsContext gc, double t, Palette palette,
                         SwarmStatusStripSupport.AgentViz viz,
                         SwarmStatusStripSupport.OrbGeometry geometry,
                         double fieldX, double fieldY) {
        double phase = SwarmStatusStripSupport.phaseOffset(viz.agentId);
        boolean terminal = SwarmStatusStripSupport.isTerminal(viz.state);
        double sinceChange = viz.lastStateChangeT > 0 ? t - viz.lastStateChangeT : Double.MAX_VALUE;

        double driftFactor;
        if (terminal) {
            driftFactor = Math.max(0, 1 - sinceChange / 0.8);
        } else if (staticMode || runFinished || viz.state == SwarmModels.SwarmAgentState.PAUSED) {
            driftFactor = 0;
        } else {
            driftFactor = 1;
        }
        double cx = fieldX + geometry.cx() + SwarmStatusStripSupport.driftX(t, phase) * driftFactor;
        double cy = fieldY + geometry.cy() + SwarmStatusStripSupport.driftY(t, phase) * driftFactor;
        double r = geometry.radius();

        if (effectivelyDimmed(viz)) {
            gc.setGlobalAlpha(0.55);
            gc.setFill(Color.web("#757575"));
            double dimRadius = r * 0.85;
            gc.fillOval(cx - dimRadius, cy - dimRadius, dimRadius * 2, dimRadius * 2);
            gc.setGlobalAlpha(1.0);
            return;
        }

        if (viz.state == SwarmModels.SwarmAgentState.QUEUED) {
            gc.setGlobalAlpha(1.0);
            gc.setStroke(palette.dim().deriveColor(0, 1.0, 1.0, 0.5));
            gc.setLineWidth(1.5);
            gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
            return;
        }

        String coreHex = palette.light()
            ? SwarmStatusStripSupport.glowColorHex(viz.state)
            : SwarmStatusStripSupport.coreColorHex(viz.state, palette.accentHex());
        String glowHex = SwarmStatusStripSupport.glowColorHex(viz.state);
        double glowAlphaScale = palette.light() ? 0.6 : 1.0;

        double scale = 1.0;
        double glowAlpha;
        double coreAlpha = 1.0;
        switch (viz.state) {
            case CONNECTING, PROBING -> {
                scale = 1 + 0.05 * Math.sin(2 * Math.PI * t / 1.2 + phase);
                glowAlpha = 0.45;
            }
            case RUNNING -> {
                scale = SwarmStatusStripSupport.pulseScale(t, phase);
                glowAlpha = SwarmStatusStripSupport.pulseGlowAlpha(t, phase);
            }
            case AWAITING_APPROVAL -> {
                glowAlpha = 0.5;
                coreAlpha = SwarmStatusStripSupport.blinkAlpha(t);
            }
            case PAUSED -> {
                glowAlpha = 0.25;
                coreAlpha = 0.9;
            }
            case DONE -> {
                glowAlpha = 0.30;
                scale = SwarmStatusStripSupport.settlePopScale(sinceChange);
            }
            case FAILED -> glowAlpha = sinceChange < 0.6 ? 1.0 - 0.55 * (sinceChange / 0.6) : 0.45;
            default -> glowAlpha = 0.3;
        }
        if (staticMode) {
            scale = 1.0;
            coreAlpha = 1.0;
        }
        double radius = r * scale;

        double glowRadius = radius * 2.2;
        gc.setGlobalAlpha(glowAlpha * glowAlphaScale);
        gc.setFill(glowGradient(glowHex));
        gc.fillOval(cx - glowRadius, cy - glowRadius, glowRadius * 2, glowRadius * 2);

        gc.setGlobalAlpha(coreAlpha);
        gc.setFill(coreGradient(coreHex));
        gc.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
        if (palette.light()) {
            gc.setStroke(palette.foreground().deriveColor(0, 1.0, 1.0, 0.15));
            gc.setLineWidth(1);
            gc.strokeOval(cx - radius, cy - radius, radius * 2, radius * 2);
        }
        gc.setFill(Color.color(1, 1, 1, 0.22));
        gc.fillOval(cx - 0.55 * radius - 0.3 * radius, cy - 0.7 * radius - 0.25 * radius,
            0.6 * radius, 0.5 * radius);
        gc.setGlobalAlpha(1.0);

        if (viz.state == SwarmModels.SwarmAgentState.PAUSED) {
            gc.setFill(deriveStripBackground(palette.background()));
            double barWidth = radius * 0.22;
            double barHeight = radius * 0.9;
            gc.fillRect(cx - radius * 0.32 - barWidth / 2, cy - barHeight / 2, barWidth, barHeight);
            gc.fillRect(cx + radius * 0.32 - barWidth / 2, cy - barHeight / 2, barWidth, barHeight);
        }

        if (!staticMode) {
            if (viz.state == SwarmModels.SwarmAgentState.CONNECTING
                || viz.state == SwarmModels.SwarmAgentState.PROBING) {
                gc.setStroke(Color.web("#7fb3ee"));
                gc.setLineWidth(1.5);
                double startAngle = -((t * 288) % 360);
                gc.strokeArc(cx - r - 3, cy - r - 3, (r + 3) * 2, (r + 3) * 2, startAngle, 100, ArcType.OPEN);
            }
            if (viz.state == SwarmModels.SwarmAgentState.AWAITING_APPROVAL) {
                double ping = SwarmStatusStripSupport.pingProgress(t);
                double pingRadius = r * (1 + 1.4 * ping);
                gc.setGlobalAlpha(0.7 * (1 - ping));
                gc.setStroke(Color.web("#ff9800"));
                gc.setLineWidth(2);
                gc.strokeOval(cx - pingRadius, cy - pingRadius, pingRadius * 2, pingRadius * 2);
                gc.setGlobalAlpha(1.0);
            }
            if (viz.state == SwarmModels.SwarmAgentState.DONE && sinceChange < 0.6) {
                double ping = sinceChange / 0.6;
                double pingRadius = r * (1 + 1.4 * ping);
                gc.setGlobalAlpha(0.7 * (1 - ping));
                gc.setStroke(Color.web("#4caf50"));
                gc.setLineWidth(2);
                gc.strokeOval(cx - pingRadius, cy - pingRadius, pingRadius * 2, pingRadius * 2);
                gc.setGlobalAlpha(1.0);
            }
            if (viz.slow) {
                gc.setGlobalAlpha(0.9);
                gc.setStroke(Color.web(SwarmStatusStripSupport.SLOW_RING_HEX));
                gc.setLineWidth(2);
                double startAngle = SwarmStatusStripSupport.slowRingStartAngle(t);
                gc.strokeArc(cx - r - 5, cy - r - 5, (r + 5) * 2, (r + 5) * 2, startAngle, 300, ArcType.OPEN);
                gc.setGlobalAlpha(1.0);
            }
        }
    }

    private void drawLabels(GraphicsContext gc, Palette palette,
                            SwarmStatusStripSupport.StripLayout layout,
                            double fieldX, double fieldY, double fieldHeight) {
        gc.setGlobalAlpha(1.0);
        gc.setFill(palette.dim());
        gc.setFont(Font.font("System", FontWeight.NORMAL, 10));
        gc.setTextAlign(TextAlignment.CENTER);
        int maxChars = Math.max(4, (int) (layout.slotWidth() / 6.5));
        for (int i = 0; i < layout.orbs().size(); i++) {
            SwarmStatusStripSupport.OrbGeometry orb = layout.orbs().get(i);
            String name = agents.get(i).displayName;
            String label = layout.labelMode() == SwarmStatusStripSupport.LabelMode.FULL
                ? name
                : SwarmStatusStripSupport.abbreviate(name, maxChars);
            gc.fillText(label, fieldX + orb.cx(), fieldY + fieldHeight - 3);
        }
    }

    private void drawLegend(GraphicsContext gc, double t, Palette palette,
                            List<SwarmStatusStripSupport.LegendChip> chips,
                            double width, double height) {
        if (chips.isEmpty()) {
            return;
        }
        double x = width - PADDING_X - SwarmStatusStripSupport.legendWidth(chips);
        double cy = height * 0.5;
        gc.setFont(Font.font("System", FontWeight.NORMAL, 11));
        gc.setTextAlign(TextAlignment.LEFT);
        for (SwarmStatusStripSupport.LegendChip chip : chips) {
            double alpha = chip.blinking() && timerRunning ? SwarmStatusStripSupport.blinkAlpha(t) : 1.0;
            gc.setGlobalAlpha(alpha);
            gc.setFill(Color.web(chip.colorHex()));
            gc.fillOval(x + 2, cy - 4, 8, 8);
            gc.setGlobalAlpha(1.0);
            gc.setFill(palette.foreground());
            gc.fillText(String.valueOf(chip.count()), x + 14, cy + 4);
            x += 18 + String.valueOf(chip.count()).length() * 7;
        }
    }

    private RadialGradient glowGradient(String hex) {
        return glowGradients.computeIfAbsent(hex, key -> {
            Color color = Color.web(key);
            return new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, color.deriveColor(0, 1.0, 1.0, 0.8)),
                new Stop(1, color.deriveColor(0, 1.0, 1.0, 0)));
        });
    }

    private RadialGradient coreGradient(String hex) {
        return coreGradients.computeIfAbsent(hex, key -> {
            Color color = Color.web(key);
            return new RadialGradient(0, 0, 0.4, 0.35, 0.75, true, CycleMethod.NO_CYCLE,
                new Stop(0, color.brighter()),
                new Stop(0.7, color),
                new Stop(1, color.darker()));
        });
    }

    // ---- Interaction ------------------------------------------------------------------

    private void setupMouseHandlers() {
        canvas.setOnMouseMoved(event -> {
            int index = SwarmStatusStripSupport.orbIndexAt(
                lastLayout, event.getX() - lastFieldX, event.getY() - lastFieldY);
            if (index < 0 || index >= agents.size()) {
                setCursor(Cursor.DEFAULT);
                hidePopup();
                return;
            }
            // Static (rehydrated) orbs have no matching dashboard row — no click affordance.
            setCursor(staticMode ? Cursor.DEFAULT : Cursor.HAND);
            if (index == hoveredOrbIndex && hoverPopup.isShowing()) {
                repositionPopup(event.getScreenX(), event.getScreenY());
                return;
            }
            hoveredOrbIndex = index;
            populatePopup(agents.get(index));
            showPopup(event.getScreenX(), event.getScreenY());
        });
        canvas.setOnMouseExited(event -> {
            setCursor(Cursor.DEFAULT);
            hidePopup();
        });
        canvas.setOnMouseClicked(event -> {
            if (staticMode) {
                return;
            }
            int index = SwarmStatusStripSupport.orbIndexAt(
                lastLayout, event.getX() - lastFieldX, event.getY() - lastFieldY);
            if (index >= 0 && index < agents.size() && onOrbClicked != null) {
                onOrbClicked.accept(agents.get(index).agentId);
            }
        });
    }

    private void setupHoverPopup() {
        hoverPopup = new Popup();
        hoverPopup.setAutoHide(true);

        popupNameLabel = new Label();
        popupNameLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 13px; -fx-font-weight: bold;");
        popupStateLabel = new Label();
        popupStateLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12px;");
        popupElapsedLabel = new Label();
        popupElapsedLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11px;");
        popupTokensLabel = new Label();
        popupTokensLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11px;");
        popupSlowLabel = new Label();
        popupSlowLabel.setStyle("-fx-text-fill: " + SwarmStatusStripSupport.SLOW_RING_HEX
            + "; -fx-font-size: 11px; -fx-font-weight: bold;");
        popupHintLabel = new Label();
        popupHintLabel.setStyle("-fx-text-fill: #777777; -fx-font-size: 11px;");

        VBox content = new VBox(3, popupNameLabel, popupStateLabel, popupElapsedLabel,
            popupTokensLabel, popupSlowLabel, popupHintLabel);
        content.setPadding(new Insets(8, 12, 8, 12));
        content.setStyle(
            "-fx-background-color: #2a2a2a;"
                + "-fx-background-radius: 6;"
                + "-fx-border-color: #555555;"
                + "-fx-border-radius: 6;"
                + "-fx-border-width: 1;"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 8, 0, 2, 2);");
        hoverPopup.getContent().add(content);
    }

    private void populatePopup(SwarmStatusStripSupport.AgentViz viz) {
        popupNameLabel.setText(viz.displayName);
        popupStateLabel.setText(statusLabel(viz.state));
        popupElapsedLabel.setText(I18n.get("ai.swarm.viz.tooltip.elapsed", formatElapsed(viz.elapsedSeconds)));
        boolean hasTokens = viz.totalTokens > 0;
        popupTokensLabel.setText(hasTokens ? I18n.get("ai.swarm.tokens", viz.totalTokens) : "");
        popupTokensLabel.setVisible(hasTokens);
        popupTokensLabel.setManaged(hasTokens);
        popupSlowLabel.setText(I18n.get("ai.swarm.viz.slow"));
        popupSlowLabel.setVisible(viz.slow);
        popupSlowLabel.setManaged(viz.slow);
        popupHintLabel.setText(I18n.get("ai.swarm.viz.tooltip.clickHint"));
        popupHintLabel.setVisible(!staticMode);
        popupHintLabel.setManaged(!staticMode);
    }

    private void showPopup(double screenX, double screenY) {
        if (getScene() == null || getScene().getWindow() == null) {
            return;
        }
        hoverPopup.show(getScene().getWindow(), screenX + 14, screenY + 14);
    }

    private void repositionPopup(double screenX, double screenY) {
        if (hoverPopup.isShowing()) {
            hoverPopup.setAnchorX(screenX + 14);
            hoverPopup.setAnchorY(screenY + 14);
        }
    }

    private void hidePopup() {
        hoveredOrbIndex = -1;
        if (hoverPopup != null && hoverPopup.isShowing()) {
            hoverPopup.hide();
        }
    }

    private static String statusLabel(SwarmModels.SwarmAgentState state) {
        return switch (state) {
            case QUEUED -> I18n.get("ai.swarm.status.queued");
            case CONNECTING -> I18n.get("ai.swarm.status.connecting");
            case PROBING -> I18n.get("ai.swarm.status.probing");
            case RUNNING -> I18n.get("ai.swarm.status.running");
            case AWAITING_APPROVAL -> I18n.get("ai.swarm.status.awaitingApproval");
            case PAUSED -> I18n.get("ai.swarm.status.paused");
            case DONE -> I18n.get("ai.swarm.status.done");
            case FAILED -> I18n.get("ai.swarm.status.failed");
            case CANCELLED -> I18n.get("ai.swarm.status.cancelled");
            case SKIPPED -> I18n.get("ai.swarm.status.skipped");
        };
    }

    private static String formatElapsed(long seconds) {
        long s = Math.max(0L, seconds);
        return String.format(Locale.ROOT, "%02d:%02d", s / 60L, s % 60L);
    }
}
