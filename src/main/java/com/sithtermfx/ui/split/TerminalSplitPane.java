package com.sithtermfx.ui.split;

import com.sithtermfx.core.Terminal;
import com.sithtermfx.core.TerminalOutputStream;
import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.TerminalAction;
import com.sithtermfx.ui.TerminalActionProvider;
import com.sithtermfx.ui.TerminalPanel;
import com.sithtermfx.ui.TerminalWidgetListener;
import com.sithtermfx.ui.settings.SettingsProvider;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import de.kortty.ui.I18n;
import de.kortty.ui.MirroredInputWriter;
import de.kortty.ui.TerminalNavigationKeys;
import de.kortty.ui.TerminalPaneActions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javafx.scene.layout.HBox;

/**
 * Pane that supports splitting terminal widgets horizontally or vertically.
 * Supports nested splits (e.g. 2x2 grid). Each split has its own session (TtyConnector).
 * Extends StackPane so children automatically fill the available space when the window is resized.
 */
public class TerminalSplitPane extends StackPane {

    private static final Logger logger = LoggerFactory.getLogger(TerminalSplitPane.class);
    private static final String TRANSPARENT_BACKGROUND_STYLE = "-fx-background-color: transparent;";

    private static final DataFormat DRAG_TERMINAL_FORMAT = new DataFormat("application/x-sithtermfx-terminal-widget");

    /** Drop placement when moving a split terminal. */
    public enum Placement {
        ABOVE, BELOW, LEFT_OF, RIGHT_OF
    }

    private static final class ExtractResult {
        final SplitCell extracted;
        final SplitCell replacement;

        ExtractResult(SplitCell extracted, SplitCell replacement) {
            this.extracted = extracted;
            this.replacement = replacement;
        }
    }

    // Produces a fresh SettingsProvider per widget so each pane can carry its own appearance override.
    private final Supplier<SettingsProvider> settingsProviderFactory;
    private final SplitConnectorFactory connectorFactory;
    private final Consumer<SithTermFxWidget> widgetConfigurator;
    private final Function<SithTermFxWidget, Region> leftPanelFactory;
    private final Function<SithTermFxWidget, Region> bottomPanelFactory;
    private final BiFunction<SithTermFxWidget, TtyConnector, TtyConnector> connectorDecorator;

    private SplitCell rootCell;
    private SithTermFxWidget focusedWidget;
    private boolean broadcastMode = false;
    // JavaFX creates a new themed SplitPane for every split level. Remember see-through mode so both
    // existing and future nested controls stay transparent instead of restoring the opaque theme.
    private boolean backgroundTransparent = false;

    // Optional left-side panels (e.g. timestamp gutters) per widget
    private final Map<SithTermFxWidget, Region> widgetLeftPanels = new HashMap<>();
    private final Map<SithTermFxWidget, Region> widgetBottomPanels = new HashMap<>();
    // Per-widget VBox that hosts the bottom panel; kept so the panel can be detached/re-attached at
    // runtime when the AI-agent panel is docked to the side instead of the bottom.
    private final Map<SithTermFxWidget, VBox> widgetBottomHosts = new HashMap<>();
    private boolean bottomPanelsDetached = false;
    private final Map<SithTermFxWidget, Button> widgetCloseButtons = new HashMap<>();
    // Per-widget StackPane wrapper (the leaf cell's root node) used as the mount point for a per-pane
    // effect overlay. Reused by reference across split/close/move, so an overlay follows its pane.
    private final Map<SithTermFxWidget, StackPane> widgetOverlayHosts = new HashMap<>();

    // Track the currently showing context menu so we can hide it properly
    private ContextMenu activeContextMenu;

    // Optional supplier of extra menu items to add to the context menu (e.g. timestamp toggle)
    private Function<SithTermFxWidget, List<MenuItem>> extraMenuItemsFactory;

    // Optional hook invoked when a widget is closed (split close or close-all) so owners can release
    // per-widget resources such as terminal-agent runs/panels.
    private Consumer<SithTermFxWidget> onWidgetClosed;

    // Optional hook invoked after a new widget is created by splitting an existing pane, carrying the
    // originating SplitRequest (whose parentWidget is the source pane) so owners can inherit state.
    private BiConsumer<SithTermFxWidget, SplitRequest> onWidgetSplitCreated;

    // Optional hook invoked when the LAST remaining pane's session ends (e.g. Ctrl+D / exit), so the
    // owner can close the whole tab. Splits with more than one pane just auto-close the single pane.
    private Runnable onLastWidgetSessionEnded;

    // Optional hook invoked when broadcast mode actually changes state (host-app telemetry).
    private Consumer<Boolean> onBroadcastModeChanged;

    // Strips the host app's connector decorators so key routing sees the real connector type.
    private UnaryOperator<TtyConnector> connectorUnwrapper = UnaryOperator.identity();

    // Mirror guard: decides whether a pane may receive broadcast input; the host app skips panes
    // that must not get keys from other panes, such as one that is sending a paced paste.
    private Predicate<SithTermFxWidget> mirrorTargetGuard = widget -> true;

    /** If set, called when user chooses "Reset" font size in context menu (e.g. to reset to connection/global default). */
    private Runnable resetZoomCallback;

    public TerminalSplitPane(@NotNull Supplier<SettingsProvider> settingsProviderFactory,
                             @NotNull SplitConnectorFactory connectorFactory) {
        this(settingsProviderFactory, connectorFactory, w -> {}, null);
    }

    public TerminalSplitPane(@NotNull Supplier<SettingsProvider> settingsProviderFactory,
                             @NotNull SplitConnectorFactory connectorFactory,
                             @NotNull Consumer<SithTermFxWidget> widgetConfigurator) {
        this(settingsProviderFactory, connectorFactory, widgetConfigurator, null);
    }

    public TerminalSplitPane(@NotNull Supplier<SettingsProvider> settingsProviderFactory,
                             @NotNull SplitConnectorFactory connectorFactory,
                             @NotNull Consumer<SithTermFxWidget> widgetConfigurator,
                             @Nullable Function<SithTermFxWidget, Region> leftPanelFactory) {
        this(settingsProviderFactory, connectorFactory, widgetConfigurator, leftPanelFactory, (widget, connector) -> connector);
    }

    public TerminalSplitPane(@NotNull Supplier<SettingsProvider> settingsProviderFactory,
                             @NotNull SplitConnectorFactory connectorFactory,
                             @NotNull Consumer<SithTermFxWidget> widgetConfigurator,
                             @Nullable Function<SithTermFxWidget, Region> leftPanelFactory,
                             @NotNull BiFunction<SithTermFxWidget, TtyConnector, TtyConnector> connectorDecorator) {
        this(settingsProviderFactory, connectorFactory, widgetConfigurator, leftPanelFactory, null, connectorDecorator);
    }

    public TerminalSplitPane(@NotNull Supplier<SettingsProvider> settingsProviderFactory,
                             @NotNull SplitConnectorFactory connectorFactory,
                             @NotNull Consumer<SithTermFxWidget> widgetConfigurator,
                             @Nullable Function<SithTermFxWidget, Region> leftPanelFactory,
                             @Nullable Function<SithTermFxWidget, Region> bottomPanelFactory,
                             @NotNull BiFunction<SithTermFxWidget, TtyConnector, TtyConnector> connectorDecorator) {
        this.settingsProviderFactory = settingsProviderFactory;
        this.connectorFactory = connectorFactory;
        this.widgetConfigurator = widgetConfigurator;
        this.leftPanelFactory = leftPanelFactory;
        this.bottomPanelFactory = bottomPanelFactory;
        this.connectorDecorator = connectorDecorator;
        this.rootCell = createInitialCell();
        getChildren().add(rootCell.getNode());
        VBox.setVgrow(this, Priority.ALWAYS);
        // Only allow pane-move drag with Shift+Alt/Option.
        addEventFilter(MouseEvent.DRAG_DETECTED, event -> {
            if (rootCell == null || rootCell.countWidgets() <= 1) return;
            if (!(event.isShiftDown() && event.isAltDown())) {
                event.consume();
            }
        });
        refreshDragAndDrop();
    }
    
    /**
     * Broadcasts input to all OTHER widgets (not the source widget) that the mirror guard accepts
     * ({@link #setMirrorTargetGuard}). The writes are queued on
     * {@link MirroredInputWriter}, so a pane whose connection stalls never blocks the FX thread.
     */
    private void broadcastToOthers(@NotNull SithTermFxWidget sourceWidget, @NotNull String data) {
        if (!broadcastMode) return;
        
        for (SithTermFxWidget widget : mirrorTargets(getAllWidgets(), sourceWidget, this::acceptsMirroredInput)) {
            TtyConnector connector = widget.getTtyConnector();
            if (connector != null && connector.isConnected()) {
                MirroredInputWriter.shared().write(connector, data);
            }
        }
    }
    
    /**
     * Broadcasts input that each pane encodes for itself, e.g. an arrow key that one pane's
     * application wants as {@code ESC O A} and another's as {@code ESC [ A}. A pane for which
     * {@code bytesFor} returns {@code null} gets nothing. The bytes are encoded here on the FX
     * thread, from each pane's state at the moment of the key press, and then queued on
     * {@link MirroredInputWriter} in the same per-pane queue as the typed characters of
     * {@link #broadcastToOthers(SithTermFxWidget, String)}, so every pane receives keys and
     * characters in the order they were pressed.
     */
    private void broadcastToOthers(@NotNull SithTermFxWidget sourceWidget,
                                   @NotNull Function<SithTermFxWidget, byte[]> bytesFor) {
        if (!broadcastMode) return;

        for (SithTermFxWidget widget : mirrorTargets(getAllWidgets(), sourceWidget, this::acceptsMirroredInput)) {
            TtyConnector connector = widget.getTtyConnector();
            if (connector == null || !connector.isConnected()) {
                continue;
            }
            byte[] bytes = bytesFor.apply(widget);
            if (bytes == null) {
                continue;
            }
            MirroredInputWriter.shared().write(connector, bytes);
        }
    }

    /**
     * The panes that get the input broadcast from {@code source}: every other pane the guard accepts,
     * in the order given.
     */
    static <W> @NotNull List<W> mirrorTargets(@NotNull List<W> panes, @Nullable W source,
                                              @NotNull Predicate<? super W> guard) {
        List<W> targets = new ArrayList<>();
        for (W pane : panes) {
            if (pane != source && guard.test(pane)) {
                targets.add(pane);
            }
        }
        return targets;
    }

    /** Whether the mirror guard lets {@code widget} receive broadcast input; a failing guard says no. */
    private boolean acceptsMirroredInput(@NotNull SithTermFxWidget widget) {
        try {
            return mirrorTargetGuard.test(widget);
        } catch (RuntimeException e) {
            logger.debug("Mirror guard failed, the pane gets no broadcast input: {}", e.getMessage());
            return false;
        }
    }

    /** Broadcast bytes of the keys that are neither typed characters nor navigation keys. */
    private @Nullable String getControlSequence(KeyEvent event) {
        switch (event.getCode()) {
            case ENTER: return "\r";
            case BACK_SPACE: return "\u007F";
            case ESCAPE: return "\u001B";
            default: return null;
        }
    }

    /**
     * Routes a navigation key (see {@link TerminalNavigationKeys#isNavigationKey}) pressed in a pane.
     *
     * <p>korTTY sends these keys itself instead of leaving them to the terminal canvas, so they also
     * reach the shell while the pane or its scroll bar has the focus, and broadcast mode can mirror
     * them. Each pane gets them encoded for its own state: with their modifiers, and in application
     * cursor mode ({@code ESC[?1h}, used by mc and vim) as {@code ESC O A} instead of {@code ESC [ A}.
     *
     * <ul>
     *   <li>Keys aimed at the find bar's text field, or any other control inside the pane, are left
     *       alone and never broadcast.</li>
     *   <li>SithTermFX's own scroll keys (Shift+Page Up/Down, Ctrl+Up/Down, Cmd+Up/Down on macOS)
     *       scroll the pane's scrollback while it shows the normal screen and are never broadcast.
     *       The alternate screen of vim, less or mc has no scrollback, so there they go to the
     *       application.</li>
     *   <li>Ctrl+Tab and Meta (Cmd) chords are shortcuts and are not sent.</li>
     * </ul>
     */
    private void routeKeyPressed(@NotNull SithTermFxWidget widget, @NotNull KeyEvent event) {
        if (event.isConsumed()) {
            return;
        }
        if (!isTerminalKeyTarget(widget, event.getTarget())) {
            // The find bar handles its own keys; Enter there must not run a command in the other panes.
            return;
        }
        if (!TerminalNavigationKeys.isNavigationKey(event.getCode())) {
            if (broadcastMode) {
                String sequence = getControlSequence(event);
                if (sequence != null) {
                    broadcastToOthers(widget, sequence);
                }
            }
            return;
        }
        if (TerminalNavigationKeys.isKorttyEncoded(widget.getEmulationType())
            && performsLocalScrollAction(widget, event)) {
            return;
        }
        byte[] bytes = encodeNavigationKey(widget, event);
        if (bytes == null || !sendToPane(widget, bytes)) {
            return;
        }
        broadcastToOthers(widget, target -> encodeNavigationKey(target, event));
        event.consume();
    }

    /**
     * True when the key event is aimed at the terminal itself: its canvas, the panes around it, or its
     * scroll bar. The find bar's text field, buttons and check box are not, so editing the search
     * text never types into the shell.
     */
    private static boolean isTerminalKeyTarget(@NotNull SithTermFxWidget widget, @Nullable Object target) {
        if (target == null) {
            return false;
        }
        if (target == widget.getPane() || target instanceof ScrollBar) {
            return true;
        }
        TerminalPanel panel = widget.getTerminalPanel();
        return panel != null && (target == panel.getCanvas() || target == panel.getPane());
    }

    /**
     * Mirrors SithTermFX's key-action lookup ({@code TerminalAction.processEvent}): the first action
     * whose key combination matches decides. An enabled one (scrolling the scrollback) runs locally
     * and the key is not sent. On the canvas SithTermFX's own key filter runs it; for a key aimed at
     * the pane or the scroll bar that filter never runs, so it is performed here.
     *
     * @return true when the key was used for a local action and must not reach the application
     */
    private static boolean performsLocalScrollAction(@NotNull SithTermFxWidget widget, @NotNull KeyEvent event) {
        TerminalPanel panel = widget.getTerminalPanel();
        if (panel == null) {
            return false;
        }
        TerminalTextBuffer buffer = widget.getTerminalTextBuffer();
        if (buffer != null && buffer.isUsingAlternateBuffer()) {
            return false;
        }
        TerminalAction action = firstMatchingAction(panel, event);
        if (action == null || !action.isEnabled(event)) {
            return false;
        }
        if (event.getTarget() == panel.getCanvas()) {
            return true;
        }
        if (action.actionPerformed(event)) {
            event.consume();
            return true;
        }
        return false;
    }

    private static @Nullable TerminalAction firstMatchingAction(@NotNull TerminalActionProvider first,
                                                                @NotNull KeyEvent event) {
        int depth = 0;
        for (TerminalActionProvider provider = first; provider != null && depth < 32;
             provider = provider.getNextProvider(), depth++) {
            List<TerminalAction> actions = provider.getActions();
            if (actions == null) {
                continue;
            }
            for (TerminalAction action : actions) {
                if (action.matches(event)) {
                    return action;
                }
            }
        }
        return null;
    }

    /**
     * The bytes a navigation key sends to one pane, from that pane's own emulation, cursor-key mode
     * and connector, or {@code null} when the key is not sent to it.
     */
    private byte @Nullable [] encodeNavigationKey(@NotNull SithTermFxWidget widget, @NotNull KeyEvent event) {
        KeyCode code = event.getCode();
        if (!TerminalNavigationKeys.isKorttyEncoded(widget.getEmulationType())) {
            return TerminalNavigationKeys.legacySequence(code);
        }
        Terminal terminal = widget.getTerminal();
        if (terminal == null) {
            return null;
        }
        IntFunction<byte[]> base = vk -> terminal.getCodeForKey(vk, 0);
        if (TerminalNavigationKeys.prefersSs3Arrows(connectorUnwrapper.apply(widget.getTtyConnector()))) {
            base = TerminalNavigationKeys.ss3ArrowBase(base);
        }
        return TerminalNavigationKeys.encode(code, event.isShiftDown(), event.isControlDown(), event.isAltDown(),
            event.isMetaDown(), com.sithtermfx.core.util.Platform.isMacOS(), base);
    }

    /**
     * Sends bytes to a pane through its terminal starter (the panel's output stream), the executor
     * that typed characters use as well, so a key never overtakes the characters typed before it.
     *
     * @return false when the pane has no live connection; the key is then left to SithTermFX
     */
    private static boolean sendToPane(@NotNull SithTermFxWidget widget, byte @NotNull [] bytes) {
        TtyConnector connector = widget.getTtyConnector();
        if (connector == null || !connector.isConnected()) {
            return false;
        }
        TerminalPanel panel = widget.getTerminalPanel();
        TerminalOutputStream starter = panel != null ? panel.getTerminalOutputStream() : null;
        if (starter != null) {
            starter.sendBytes(bytes, true);
            return true;
        }
        try {
            connector.write(bytes);
            return true;
        } catch (IOException e) {
            logger.debug("Failed to send key sequence: {}", e.getMessage());
            return false;
        }
    }

    private @NotNull SplitCell createInitialCell() {
        SithTermFxWidget widget = createWidget(null);
        setupWidget(widget);
        applyLeftPanel(widget);
        applyBottomPanel(widget);
        return new SplitCell(widget);
    }

    private @NotNull SithTermFxWidget createWidget(@Nullable SplitRequest request) {
        return createWidget(request, null);
    }

    /**
     * @param preparedConnector an already-connected connector to adopt; when it is non-null the
     *     {@link SplitConnectorFactory} is <strong>not consulted at all</strong>, which is what keeps
     *     a caller that prepared its own connector off the FX thread out of the factory's modal
     *     connect dialog
     */
    private @NotNull SithTermFxWidget createWidget(@Nullable SplitRequest request,
                                                   @Nullable TtyConnector preparedConnector) {
        // KorttyTermWidget routes terminal copy/paste through the policy-aware clipboard handler
        // (enterprise internal-clipboard mode).
        SithTermFxWidget widget = new de.kortty.ui.KorttyTermWidget(80, 24, settingsProviderFactory.get());
        widgetConfigurator.accept(widget);
        TtyConnector connector = preparedConnector != null
            ? preparedConnector
            : connectorFactory.createConnectorForSplit(request);
        if (connector != null) {
            TtyConnector decoratedConnector = connectorDecorator.apply(widget, connector);
            widget.setTtyConnector(decoratedConnector != null ? decoratedConnector : connector);
            widget.start();
        }
        return widget;
    }

    private void setupWidget(@NotNull SithTermFxWidget widget) {
        setFocusedWidgetInternal(widget);
        widget.getPane().setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                setFocusedWidgetInternal(widget);
                requestWidgetFocus(widget);
            }
        });
        widget.getPreferredFocusableNode().focusedProperty().addListener((obs, oldV, newV) -> {
            if (Boolean.TRUE.equals(newV)) {
                setFocusedWidgetInternal(widget);
            }
        });
        // The keystrokes go to the canvas inside that node, and the node's own focused property
        // stays false while the canvas holds the focus. Follow the canvas as well, or focus that
        // reaches a pane without a primary click on it (a middle click, the window regaining focus
        // after a dialog, a focus request from code) leaves getFocusedWidget() on the old pane.
        TerminalPanel panel = widget.getTerminalPanel();
        if (panel != null && panel.getCanvas() != null) {
            panel.getCanvas().focusedProperty().addListener((obs, oldV, newV) -> {
                if (Boolean.TRUE.equals(newV)) {
                    setFocusedWidgetInternal(widget);
                }
            });
        }
        // Auto-close split when session ends (e.g. Ctrl+D / exit)
        widget.addListener(new TerminalWidgetListener() {
            @Override
            public void allSessionsClosed(com.sithtermfx.ui.TerminalWidget w) {
                Platform.runLater(() -> {
                    if (rootCell != null && rootCell.countWidgets() > 1) {
                        logger.info("Session closed in split widget, auto-closing split pane");
                        closeSplit(widget);
                    } else if (onLastWidgetSessionEnded != null) {
                        logger.info("Session closed in the last pane, requesting tab close");
                        onLastWidgetSessionEnded.run();
                    }
                });
            }
        });
        
        setupContextMenu(widget);
        
        var widgetPane = widget.getPane();
        
        widgetPane.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (!broadcastMode) return;
            // Meta/Cmd chords are shortcuts, not text (menu accelerators such as Cmd+Shift+D only
            // consume KEY_PRESSED; macOS still delivers the paired KEY_TYPED character here).
            if (event.isMetaDown()) return;
            // Search text typed into the find bar is not shell input.
            if (!isTerminalKeyTarget(widget, event.getTarget())) return;
            String character = event.getCharacter();
            if (character != null && !character.isEmpty()) {
                char c = character.charAt(0);
                if (c == '\r' || c == '\t' || c == '\u001B' || c == '\u007F') {
                    return;
                }
                broadcastToOthers(widget, character);
            }
        });
        
        widgetPane.addEventFilter(KeyEvent.KEY_PRESSED, event -> routeKeyPressed(widget, event));
    }

    private void requestWidgetFocus(@NotNull SithTermFxWidget widget) {
        if (widget.getTerminalPanel() != null && widget.getTerminalPanel().getCanvas() != null) {
            widget.getTerminalPanel().getCanvas().requestFocus();
            return;
        }
        Node focusTarget = widget.getPreferredFocusableNode();
        if (focusTarget != null) {
            focusTarget.requestFocus();
        } else {
            widget.getPane().requestFocus();
        }
    }

    public void setExtraMenuItemsFactory(@Nullable Function<SithTermFxWidget, List<MenuItem>> factory) {
        this.extraMenuItemsFactory = factory;
    }

    /** Sets a hook invoked for each widget being closed (split close or close-all). */
    public void setOnWidgetClosed(@Nullable Consumer<SithTermFxWidget> onWidgetClosed) {
        this.onWidgetClosed = onWidgetClosed;
    }

    /** Sets a hook invoked after a split creates a new widget; receives (newWidget, originating request). */
    public void setOnWidgetSplitCreated(@Nullable BiConsumer<SithTermFxWidget, SplitRequest> onWidgetSplitCreated) {
        this.onWidgetSplitCreated = onWidgetSplitCreated;
    }

    /** Sets a hook invoked when the last remaining pane's session ends, so the owner can close the tab. */
    public void setOnLastWidgetSessionEnded(@Nullable Runnable onLastWidgetSessionEnded) {
        this.onLastWidgetSessionEnded = onLastWidgetSessionEnded;
    }

    /** Returns the per-pane StackPane wrapper used as the mount point for a per-pane effect overlay. */
    public @Nullable StackPane getWidgetOverlayHost(@Nullable SithTermFxWidget widget) {
        return widget != null ? widgetOverlayHosts.get(widget) : null;
    }

    private void notifyWidgetSplitCreated(@Nullable SithTermFxWidget widget, @NotNull SplitRequest request) {
        if (onWidgetSplitCreated != null && widget != null) {
            try {
                onWidgetSplitCreated.accept(widget, request);
            } catch (RuntimeException e) {
                logger.debug("onWidgetSplitCreated hook failed: {}", e.getMessage());
            }
        }
    }

    public void setOnBroadcastModeChanged(@Nullable Consumer<Boolean> onBroadcastModeChanged) {
        this.onBroadcastModeChanged = onBroadcastModeChanged;
    }

    /**
     * Sets how to strip the decorators the host app wraps around a pane's connector (the
     * constructor's {@code connectorDecorator}), so navigation keys are encoded for the real
     * connector type. Defaults to no unwrapping.
     */
    public void setConnectorUnwrapper(@Nullable UnaryOperator<TtyConnector> connectorUnwrapper) {
        this.connectorUnwrapper = connectorUnwrapper != null ? connectorUnwrapper : UnaryOperator.identity();
    }

    /**
     * Sets the mirror guard: broadcast mode sends a pane's keys only to the other panes for which
     * {@code guard} returns true, read at every key. Null lets every pane receive them, the default.
     */
    public void setMirrorTargetGuard(@Nullable Predicate<SithTermFxWidget> guard) {
        this.mirrorTargetGuard = guard != null ? guard : widget -> true;
    }

    /**
     * Makes this container and every nested JavaFX split control transparent. The state is retained so
     * split controls created later inherit it as well; clearing it restores the active theme background.
     */
    public void setBackgroundTransparent(boolean transparent) {
        backgroundTransparent = transparent;
        applyBackgroundStyle(this);
        if (rootCell != null) {
            rootCell.refreshBackgroundStyle();
        }
    }

    private void applyBackgroundStyle(@NotNull Region region) {
        region.setStyle(backgroundTransparent ? TRANSPARENT_BACKGROUND_STYLE : null);
    }

    private void notifyWidgetClosed(@Nullable SithTermFxWidget widget) {
        if (onWidgetClosed != null && widget != null) {
            try {
                onWidgetClosed.accept(widget);
            } catch (Exception e) {
                logger.debug("onWidgetClosed hook failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Sets a callback to run when the user chooses "Reset" font size in the context menu.
     * If set, this is used instead of the widget's default reset (so e.g. zoom can reset to connection/global font size).
     */
    public void setResetZoomCallback(@Nullable Runnable resetZoomCallback) {
        this.resetZoomCallback = resetZoomCallback;
    }

    private void setupContextMenu(@NotNull SithTermFxWidget widget) {
        var terminalPanel = widget.getTerminalPanel();
        var canvas = terminalPanel.getCanvas();
        
        canvas.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            if (activeContextMenu != null && activeContextMenu.isShowing()) {
                activeContextMenu.hide();
                activeContextMenu = null;
                if (event.getButton() != MouseButton.SECONDARY) {
                    return;
                }
            }
        });
        
        canvas.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                setFocusedWidgetInternal(widget);
                ContextMenu menu = createFullContextMenu(widget);
                activeContextMenu = menu;
                menu.setOnHidden(e -> {
                    if (activeContextMenu == menu) {
                        activeContextMenu = null;
                    }
                });
                menu.show(canvas, event.getScreenX(), event.getScreenY());
                event.consume();
            }
        });
    }
    
    /** One entry of the terminal context menu: the i18n key of its label and the command it runs. */
    record TerminalMenuAction(@NotNull String i18nKey, @NotNull Runnable action) {
    }

    /**
     * The edit entries at the top of the terminal context menu, in menu order. Free of JavaFX so a
     * unit test can fire every entry and check that it reaches the pane.
     */
    static @NotNull List<TerminalMenuAction> editActions(@NotNull TerminalPaneActions actions) {
        return List.of(
            new TerminalMenuAction("terminal.contextMenu.copy", actions::copySelection),
            new TerminalMenuAction("terminal.contextMenu.paste", actions::paste),
            new TerminalMenuAction("terminal.contextMenu.clearBuffer", actions::clearBuffer),
            new TerminalMenuAction("terminal.contextMenu.find", actions::showFind));
    }

    /**
     * The entries of <b>Extras &gt; Font Size</b>, in menu order.
     *
     * @param reset what <b>Reset</b> runs: the tab's reset-zoom callback or the widget's own reset
     */
    static @NotNull List<TerminalMenuAction> fontSizeActions(@NotNull TerminalPaneActions actions,
                                                             @NotNull Runnable reset) {
        return List.of(
            new TerminalMenuAction("terminal.contextMenu.increase", actions::increaseFontSize),
            new TerminalMenuAction("terminal.contextMenu.decrease", actions::decreaseFontSize),
            new TerminalMenuAction("terminal.contextMenu.reset", reset));
    }

    private static @NotNull List<MenuItem> toMenuItems(@NotNull List<TerminalMenuAction> actions) {
        List<MenuItem> items = new ArrayList<>(actions.size());
        for (TerminalMenuAction action : actions) {
            MenuItem item = new MenuItem(I18n.get(action.i18nKey()));
            item.setOnAction(e -> action.action().run());
            items.add(item);
        }
        return items;
    }

    /** Every widget this pane creates is a {@code KorttyTermWidget}; anything else gets no pane commands. */
    private static @Nullable TerminalPaneActions paneActionsOf(@NotNull SithTermFxWidget widget) {
        return widget instanceof TerminalPaneActions actions ? actions : null;
    }

    private @NotNull ContextMenu createFullContextMenu(@NotNull SithTermFxWidget widget) {
        ContextMenu menu = new ContextMenu();
        TerminalPaneActions actions = paneActionsOf(widget);
        if (actions != null) {
            List<MenuItem> editItems = toMenuItems(editActions(actions));
            // Copy without a selection would do nothing, so it is greyed out.
            editItems.get(0).setDisable(widget.getTerminalPanel().getSelection() == null);
            menu.getItems().addAll(editItems);
        }

        if (extraMenuItemsFactory != null) {
            List<MenuItem> extraItems = extraMenuItemsFactory.apply(widget);
            if (extraItems != null && !extraItems.isEmpty()) {
                menu.getItems().add(new SeparatorMenuItem());
                menu.getItems().addAll(extraItems);
            }
        }

        menu.getItems().add(new SeparatorMenuItem());
        Menu extrasMenu = createExtrasSubmenu(widget);
        menu.getItems().add(extrasMenu);
        return menu;
    }
    
    private @NotNull Menu createExtrasSubmenu(@NotNull SithTermFxWidget widget) {
        Menu extrasMenu = new Menu(I18n.get("terminal.contextMenu.extras"));
        Menu fontMenu = new Menu(I18n.get("terminal.contextMenu.fontSize"));
        Runnable resetFont = () -> {
            if (resetZoomCallback != null) {
                resetZoomCallback.run();
            } else {
                widget.resetFontSize();
            }
        };
        TerminalPaneActions actions = paneActionsOf(widget);
        fontMenu.getItems().addAll(toMenuItems(actions != null
            ? fontSizeActions(actions, resetFont)
            : List.of(new TerminalMenuAction("terminal.contextMenu.reset", resetFont))));
        
        Menu splitMenu = new Menu(I18n.get("terminal.contextMenu.splitTerminal"));
        MenuItem splitRightSame = new MenuItem(I18n.get("terminal.contextMenu.splitRightSame"));
        splitRightSame.setOnAction(e -> split(SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.HORIZONTAL));
        MenuItem splitRightNew = new MenuItem(I18n.get("terminal.contextMenu.splitRightNew"));
        splitRightNew.setOnAction(e -> split(SplitRequest.SplitMode.NEW_CONNECTION, Orientation.HORIZONTAL));
        MenuItem splitDownSame = new MenuItem(I18n.get("terminal.contextMenu.splitDownSame"));
        splitDownSame.setOnAction(e -> split(SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.VERTICAL));
        MenuItem splitDownNew = new MenuItem(I18n.get("terminal.contextMenu.splitDownNew"));
        splitDownNew.setOnAction(e -> split(SplitRequest.SplitMode.NEW_CONNECTION, Orientation.VERTICAL));
        MenuItem closeSplit = new MenuItem(I18n.get("terminal.contextMenu.closeSplit"));
        closeSplit.setOnAction(e -> closeSplit(widget));
        closeSplit.setDisable(rootCell.countWidgets() <= 1);
        splitMenu.getItems().addAll(splitRightSame, splitRightNew, new SeparatorMenuItem(),
                                    splitDownSame, splitDownNew, new SeparatorMenuItem(), closeSplit);
        
        CheckMenuItem broadcastToggle = new CheckMenuItem(I18n.get("terminal.contextMenu.broadcastMode"));
        broadcastToggle.setSelected(broadcastMode);
        broadcastToggle.setOnAction(e -> setBroadcastMode(broadcastToggle.isSelected()));
        broadcastToggle.setDisable(rootCell.countWidgets() <= 1);
        extrasMenu.getItems().addAll(splitMenu, fontMenu, new SeparatorMenuItem(), broadcastToggle);
        return extrasMenu;
    }
    
    public void split(@NotNull SplitRequest.SplitMode mode, @NotNull Orientation orientation) {
        SithTermFxWidget parent = getFocusedWidget();
        if (parent == null) {
            logger.warn("No focused widget to split");
            return;
        }
        splitWidget(parent, mode, orientation);
    }

    public void splitHorizontally(@Nullable SplitRequest.SplitMode mode) {
        split(mode != null ? mode : SplitRequest.SplitMode.NEW_CONNECTION, Orientation.HORIZONTAL);
    }

    public void splitVertically(@Nullable SplitRequest.SplitMode mode) {
        split(mode != null ? mode : SplitRequest.SplitMode.NEW_CONNECTION, Orientation.VERTICAL);
    }

    private void splitWidget(@NotNull SithTermFxWidget widget, @NotNull SplitRequest.SplitMode mode,
                            @NotNull Orientation orientation) {
        splitWidget(widget, mode, orientation, null);
    }

    /**
     * Splits {@code widget}, optionally adopting a connector the caller has already built and
     * connected.
     *
     * <p>A non-null {@code preparedConnector} bypasses the {@link SplitConnectorFactory} entirely.
     * That is the whole point of the overload: korTTY answers a same-server split by showing an
     * {@code APPLICATION_MODAL} progress stage and running a nested JavaFX event loop with a
     * two-minute await, which a programmatic caller on the FX thread would sit inside. Preparing the
     * connector off the FX thread and handing it in here keeps the layout mutation to a single
     * non-blocking hop.
     *
     * <p>Unlike the menu path this reports the outcome instead of aborting silently: a connector that
     * is null or not connected leaves the tree untouched and answers null, so a programmatic caller
     * can surface the failure rather than watch nothing happen.
     *
     * @param widget the pane to split; it stays open beside the new one
     * @param mode always pass a mode explicitly — {@code splitHorizontally(null)} and
     *     {@code splitVertically(null)} fall back to {@link SplitRequest.SplitMode#NEW_CONNECTION},
     *     which asks the user for a new connection
     * @param orientation where the new pane goes
     * @param preparedConnector an already-connected connector, or null to use the factory
     * @return the new pane, or null when the split did not happen
     */
    public @Nullable SithTermFxWidget splitWidget(@NotNull SithTermFxWidget widget,
                                                  @NotNull SplitRequest.SplitMode mode,
                                                  @NotNull Orientation orientation,
                                                  @Nullable TtyConnector preparedConnector) {
        SplitRequest request = new SplitRequest(mode, widget);
        SithTermFxWidget newWidget = createWidget(request, preparedConnector);
        TtyConnector connector = newWidget.getTtyConnector();
        if (connector == null || !connector.isConnected()) {
            // The widget configurator (and, with a connector, the decorator) already ran for this
            // widget, so per-widget registrations exist although it never joins the tree: fire the
            // close hook exactly as closeSplit does, or those registrations leak for the tab's life.
            notifyWidgetClosed(newWidget);
            try {
                newWidget.close();
            } catch (Exception ignored) {
            }
            return null;
        }
        setupWidget(newWidget);
        applyLeftPanel(newWidget);
        applyBottomPanel(newWidget);
        SplitCell newCell = new SplitCell(newWidget);
        SplitCell replacement = rootCell.replaceWidget(widget, newCell, orientation);
        if (replacement == null) {
            return null;
        }
        getChildren().clear();
        rootCell = replacement;
        getChildren().add(rootCell.getNode());
        VBox.setVgrow(rootCell.getNode(), Priority.ALWAYS);
        refreshDragAndDrop();
        refreshSplitCloseButtons();
        notifyWidgetSplitCreated(newWidget, request);
        return newWidget;
    }

    /**
     * Closes one split pane, refusing the tab's last one.
     *
     * <p>The refusal is the contract, not a convenience: {@code SplitCell.removeWidget} answers null
     * for a root leaf, so closing the only pane clears the children and leaves an empty terminal area
     * inside a still-open tab. The user-facing "Close split" menu item is disabled in exactly this
     * case; a programmatic caller gets the same protection here.
     *
     * @param widget a pane of this split pane
     * @return true when the pane was closed, false when it was the last one or does not belong here
     */
    public boolean closeSplitPane(@NotNull SithTermFxWidget widget) {
        if (getWidgetCount() <= 1 || !getAllWidgets().contains(widget)) {
            return false;
        }
        closeSplit(widget);
        return true;
    }

    private void closeSplit(@NotNull SithTermFxWidget widget) {
        notifyWidgetClosed(widget);
        try {
            widget.close();
        } catch (Exception e) {
            logger.debug("Error closing widget: {}", e.getMessage());
        }
        widgetLeftPanels.remove(widget);
        widgetBottomPanels.remove(widget);
        widgetBottomHosts.remove(widget);
        widgetCloseButtons.remove(widget);
        widgetOverlayHosts.remove(widget);
        SplitCell replacement = rootCell.removeWidget(widget);
        if (replacement != rootCell) {
            getChildren().clear();
            rootCell = replacement;
            if (rootCell != null) {
                getChildren().add(rootCell.getNode());
                VBox.setVgrow(rootCell.getNode(), Priority.ALWAYS);
            }
            setFocusedWidgetInternal(paneFocusedAfterClose(focusedWidget, widget, getAllWidgets()));
            refreshDragAndDrop();
            refreshSplitCloseButtons();
        }
    }

    /**
     * The focused pane once {@code closed} has left the tree. Closing another pane (its session
     * ended, its close button, the Control API) does not move the keyboard focus, so the focused pane
     * stays; only when the focused pane itself is gone does the first remaining pane take over.
     *
     * @param remaining the panes still in the tree, in {@link #getAllWidgets()} order
     */
    static <W> @Nullable W paneFocusedAfterClose(@Nullable W focused, @NotNull W closed,
                                                 @NotNull List<W> remaining) {
        if (focused != null && focused != closed) {
            for (W pane : remaining) {
                if (pane == focused) {
                    return focused;
                }
            }
        }
        return remaining.isEmpty() ? null : remaining.get(0);
    }

    public @Nullable SithTermFxWidget getFocusedWidget() {
        return focusedWidget;
    }

    /**
     * The only writer of {@link #focusedWidget}: a new pane, primary and secondary clicks, the
     * canvas and preferred-node focus listeners, {@link #focusWidget} and closing a pane all change
     * the focused pane through here, so Edit &gt; Find, Copy/Paste and the AI actions, which read
     * {@link #getFocusedWidget()}, see one notion of it. TerminalSplitPaneFocusTrackingTest pins that.
     */
    private void setFocusedWidgetInternal(@Nullable SithTermFxWidget widget) {
        focusedWidget = widget;
    }

    /**
     * Focuses {@code widget} programmatically exactly as a primary click on its pane would: the
     * split pane's own notion of the focused widget is updated first, then keyboard focus is
     * requested on the node that receives the keystrokes.
     *
     * <p>Requesting focus on the pane's canvas alone is not enough while the window itself is not
     * focused: the split pane follows the canvas's {@code focused} property, which stays false until
     * the window gets the focus, so {@link #getFocusedWidget()} would stay on the previous pane.
     *
     * @param widget a widget of this split pane; widgets that do not belong to it are ignored
     */
    public void focusWidget(@NotNull SithTermFxWidget widget) {
        if (!getAllWidgets().contains(widget)) {
            logger.debug("focusWidget ignored a widget that does not belong to this split pane");
            return;
        }
        setFocusedWidgetInternal(widget);
        requestWidgetFocus(widget);
    }

    public @NotNull List<SithTermFxWidget> getAllWidgets() {
        List<SithTermFxWidget> widgets = new ArrayList<>();
        collectWidgets(rootCell, widgets);
        return widgets;
    }
    
    private void collectWidgets(@Nullable SplitCell cell, @NotNull List<SithTermFxWidget> widgets) {
        if (cell == null) return;
        if (cell.widget != null) {
            widgets.add(cell.widget);
        }
        collectWidgets(cell.leftCell, widgets);
        collectWidgets(cell.rightCell, widgets);
    }
    
    public int getWidgetCount() {
        return rootCell != null ? rootCell.countWidgets() : 0;
    }
    
    public boolean isBroadcastMode() {
        return broadcastMode;
    }
    
    public void setBroadcastMode(boolean enabled) {
        boolean changed = this.broadcastMode != enabled;
        this.broadcastMode = enabled;
        logger.info("Broadcast mode {}", enabled ? "enabled" : "disabled");
        if (changed && onBroadcastModeChanged != null) {
            try {
                onBroadcastModeChanged.accept(enabled);
            } catch (RuntimeException e) {
                logger.debug("onBroadcastModeChanged hook failed: {}", e.getMessage());
            }
        }
    }
    
    public void toggleBroadcastMode() {
        setBroadcastMode(!broadcastMode);
    }

    public void moveWidget(@NotNull SithTermFxWidget source, @NotNull SithTermFxWidget target,
                           @NotNull Placement placement) {
        if (rootCell == null || getWidgetCount() <= 1 || source == target) {
            return;
        }
        ExtractResult er = rootCell.extractWidget(source);
        if (er == null || er.replacement == null) {
            return;
        }
        Orientation orientation;
        boolean newCellFirst;
        switch (placement) {
            case ABOVE:   orientation = Orientation.VERTICAL;   newCellFirst = true;  break;
            case BELOW:   orientation = Orientation.VERTICAL;   newCellFirst = false; break;
            case LEFT_OF: orientation = Orientation.HORIZONTAL; newCellFirst = true;  break;
            case RIGHT_OF: orientation = Orientation.HORIZONTAL; newCellFirst = false; break;
            default: return;
        }
        SplitCell newRoot = er.replacement.replaceWidget(target, er.extracted, orientation, newCellFirst);
        if (newRoot == null) {
            return;
        }
        getChildren().clear();
        rootCell = newRoot;
        getChildren().add(rootCell.getNode());
        VBox.setVgrow(rootCell.getNode(), Priority.ALWAYS);
        refreshDragAndDrop();
    }

    private void forEachLeafCell(@Nullable SplitCell cell, @NotNull java.util.function.Consumer<SplitCell> action) {
        if (cell == null) return;
        if (cell.widget != null) {
            action.accept(cell);
            return;
        }
        forEachLeafCell(cell.leftCell, action);
        forEachLeafCell(cell.rightCell, action);
    }

    private void refreshDragAndDrop() {
        forEachLeafCell(rootCell, this::attachDragAndDropToCell);
    }

    private @Nullable SithTermFxWidget findWidgetByIdentity(@NotNull String idString) {
        int id;
        try {
            id = Integer.parseInt(idString);
        } catch (NumberFormatException e) {
            return null;
        }
        for (SithTermFxWidget w : getAllWidgets()) {
            if (System.identityHashCode(w) == id) return w;
        }
        return null;
    }

    private void attachDragAndDropToCell(@NotNull SplitCell cell) {
        Region node = cell.getNode();
        SithTermFxWidget widget = cell.widget;
        if (widget == null) return;

        node.setOnDragDetected(event -> {
            if (getWidgetCount() <= 1) return;
            if (event.isConsumed()) return;
            if (!(event.isShiftDown() && event.isAltDown())) return;
            Dragboard db = node.startDragAndDrop(TransferMode.ANY);
            db.setContent(Map.of(DRAG_TERMINAL_FORMAT, String.valueOf(System.identityHashCode(widget))));
            event.consume();
        });

        node.addEventFilter(DragEvent.DRAG_ENTERED, event -> {
            if (!event.getDragboard().hasContent(DRAG_TERMINAL_FORMAT)) return;
            String sourceId = (String) event.getDragboard().getContent(DRAG_TERMINAL_FORMAT);
            if (sourceId.equals(String.valueOf(System.identityHashCode(widget)))) return;
            SithTermFxWidget source = findWidgetByIdentity(sourceId);
            if (source == null) return;
            DropZoneOverlay.show(node, widget, sourceId, this);
            event.consume();
        });

        node.addEventFilter(DragEvent.DRAG_EXITED, event -> {
            if (event.getDragboard().hasContent(DRAG_TERMINAL_FORMAT)) {
                DropZoneOverlay.hide(node);
                event.consume();
            }
        });

        node.addEventFilter(DragEvent.DRAG_OVER, event -> {
            if (event.getDragboard().hasContent(DRAG_TERMINAL_FORMAT)) {
                event.acceptTransferModes(TransferMode.ANY);
                DropZoneOverlay.updatePlacement(node, event.getX(), event.getY());
                event.consume();
            }
        });

        node.addEventFilter(DragEvent.DRAG_DROPPED, event -> {
            if (!event.getDragboard().hasContent(DRAG_TERMINAL_FORMAT)) return;
            boolean done = DropZoneOverlay.tryDrop(node, this);
            event.setDropCompleted(done);
            event.consume();
        });
    }

    private void applyLeftPanel(@NotNull SithTermFxWidget widget) {
        if (leftPanelFactory != null) {
            Region leftPanel = leftPanelFactory.apply(widget);
            if (leftPanel != null) {
                widgetLeftPanels.put(widget, leftPanel);
            }
        }
    }

    public void setWidgetLeftPanel(@NotNull SithTermFxWidget widget, @NotNull Region panel) {
        widgetLeftPanels.put(widget, panel);
    }

    public void removeWidgetLeftPanel(@NotNull SithTermFxWidget widget) {
        widgetLeftPanels.remove(widget);
    }

    private void applyBottomPanel(@NotNull SithTermFxWidget widget) {
        if (bottomPanelFactory != null) {
            Region bottomPanel = bottomPanelFactory.apply(widget);
            if (bottomPanel != null) {
                widgetBottomPanels.put(widget, bottomPanel);
            }
        }
    }

    public void setWidgetBottomPanel(@NotNull SithTermFxWidget widget, @NotNull Region panel) {
        widgetBottomPanels.put(widget, panel);
    }

    public void removeWidgetBottomPanel(@NotNull SithTermFxWidget widget) {
        widgetBottomPanels.remove(widget);
    }

    /**
     * Detaches (true) or re-attaches (false) every widget's bottom panel. While detached, new split
     * cells also skip embedding the bottom panel, so the AI-agent panel can be hosted in a side dock.
     */
    public void setBottomPanelsDetached(boolean detached) {
        if (this.bottomPanelsDetached == detached) {
            return;
        }
        this.bottomPanelsDetached = detached;
        for (SithTermFxWidget widget : getAllWidgets()) {
            if (detached) {
                detachBottomPanel(widget);
            } else {
                reattachBottomPanel(widget, widgetBottomPanels.get(widget));
            }
        }
    }

    public boolean isBottomPanelsDetached() {
        return bottomPanelsDetached;
    }

    /** Removes a widget's bottom panel from its split cell so it can be hosted elsewhere (side dock). */
    public void detachBottomPanel(@NotNull SithTermFxWidget widget) {
        Region panel = widgetBottomPanels.get(widget);
        VBox host = widgetBottomHosts.get(widget);
        if (panel != null && host != null) {
            host.getChildren().remove(panel);
        }
    }

    /** Re-inserts a widget's bottom panel into its split cell (no-op if already there or unknown). */
    public void reattachBottomPanel(@NotNull SithTermFxWidget widget, @Nullable Region panel) {
        Region target = panel != null ? panel : widgetBottomPanels.get(widget);
        VBox host = widgetBottomHosts.get(widget);
        if (target != null && host != null && !host.getChildren().contains(target)) {
            host.getChildren().add(target);
        }
    }

    public void closeAll() {
        for (SithTermFxWidget widget : getAllWidgets()) {
            notifyWidgetClosed(widget);
        }
        if (rootCell != null) {
            rootCell.closeAll();
        }
        widgetLeftPanels.clear();
        widgetBottomPanels.clear();
        widgetBottomHosts.clear();
        widgetCloseButtons.clear();
        widgetOverlayHosts.clear();
    }

    private void refreshSplitCloseButtons() {
        boolean showButtons = rootCell != null && rootCell.countWidgets() > 1;
        List<SithTermFxWidget> activeWidgets = getAllWidgets();
        widgetCloseButtons.entrySet().removeIf(entry -> !activeWidgets.contains(entry.getKey()));
        widgetOverlayHosts.keySet().removeIf(w -> !activeWidgets.contains(w));
        for (Button button : widgetCloseButtons.values()) {
            button.setVisible(showButtons);
            button.setManaged(showButtons);
        }
    }

    private class SplitCell {
        private final @Nullable SithTermFxWidget widget;
        private final @Nullable SplitPane splitPane;
        private final @Nullable SplitCell leftCell;
        private final @Nullable SplitCell rightCell;
        private final Region node;

        SplitCell(@NotNull SithTermFxWidget widget) {
            this.widget = widget;
            this.splitPane = null;
            this.leftCell = null;
            this.rightCell = null;
            Region leftPanel = widgetLeftPanels.get(widget);
            Region bottomPanel = widgetBottomPanels.get(widget);
            Region terminalContent;
            if (leftPanel != null) {
                HBox hbox = new HBox(leftPanel, widget.getPane());
                HBox.setHgrow(widget.getPane(), Priority.ALWAYS);
                hbox.setMinSize(0, 0);
                hbox.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                terminalContent = hbox;
            } else {
                terminalContent = widget.getPane();
            }
            // Always wrap in a VBox host so the bottom panel can be detached/re-attached at runtime
            // (the AI-agent panel can be docked to the side instead of the bottom). The bottom panel is
            // only embedded here when not currently detached.
            VBox bottomHost = new VBox(terminalContent);
            VBox.setVgrow(terminalContent, Priority.ALWAYS);
            bottomHost.setMinSize(0, 0);
            bottomHost.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            if (bottomPanel != null && !bottomPanelsDetached) {
                bottomHost.getChildren().add(bottomPanel);
            }
            widgetBottomHosts.put(widget, bottomHost);
            Region wrapperContent = bottomHost;
            StackPane wrapper = new StackPane(wrapperContent);
            wrapper.setMinSize(0, 0);
            wrapper.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            VBox.setVgrow(wrapper, Priority.ALWAYS);
            wrapper.setUserData(widget);
            widgetOverlayHosts.put(widget, wrapper);
            Button closeButton = new Button("x");
            closeButton.getStyleClass().add("split-close-button");
            closeButton.setFocusTraversable(false);
            closeButton.setMinSize(18, 18);
            closeButton.setPrefSize(18, 18);
            closeButton.setMaxSize(18, 18);
            closeButton.setStyle("-fx-font-size: 0.7692em; -fx-padding: 0; -fx-background-radius: 9;");
            closeButton.setOnAction(e -> {
                e.consume();
                closeSplit(widget);
            });
            StackPane.setAlignment(closeButton, Pos.TOP_RIGHT);
            StackPane.setMargin(closeButton, new javafx.geometry.Insets(4, 4, 0, 0));
            wrapper.getChildren().add(closeButton);
            widgetCloseButtons.put(widget, closeButton);
            this.node = wrapper;
            refreshSplitCloseButtons();
        }

        SplitCell(@NotNull SplitCell left, @NotNull SplitCell right, @NotNull Orientation orientation) {
            this.widget = null;
            this.leftCell = left;
            this.rightCell = right;
            Region leftNode = left.getNode();
            Region rightNode = right.getNode();
            leftNode.setMinWidth(0);
            leftNode.setMinHeight(0);
            leftNode.setMaxWidth(Double.MAX_VALUE);
            leftNode.setMaxHeight(Double.MAX_VALUE);
            rightNode.setMinWidth(0);
            rightNode.setMinHeight(0);
            rightNode.setMaxWidth(Double.MAX_VALUE);
            rightNode.setMaxHeight(Double.MAX_VALUE);
            this.splitPane = new SplitPane(leftNode, rightNode);
            splitPane.setOrientation(orientation);
            splitPane.setDividerPositions(0.5);
            splitPane.setMinSize(0, 0);
            splitPane.setMaxWidth(Double.MAX_VALUE);
            splitPane.setMaxHeight(Double.MAX_VALUE);
            applyBackgroundStyle(splitPane);
            this.node = splitPane;
            Platform.runLater(() -> splitPane.setDividerPositions(0.5));
        }

        Region getNode() {
            return node;
        }

        @Nullable
        SplitCell replaceWidget(@NotNull SithTermFxWidget target, @NotNull SplitCell newCell,
                               @NotNull Orientation orientation) {
            return replaceWidget(target, newCell, orientation, false);
        }

        @Nullable
        SplitCell replaceWidget(@NotNull SithTermFxWidget target, @NotNull SplitCell newCell,
                               @NotNull Orientation orientation, boolean newCellFirst) {
            if (widget == target) {
                return newCellFirst
                        ? new SplitCell(newCell, this, orientation)
                        : new SplitCell(this, newCell, orientation);
            }
            if (leftCell != null && rightCell != null) {
                SplitCell newLeft = leftCell.replaceWidget(target, newCell, orientation, newCellFirst);
                if (newLeft != null) {
                    return new SplitCell(newLeft, rightCell, splitPane.getOrientation());
                }
                SplitCell newRight = rightCell.replaceWidget(target, newCell, orientation, newCellFirst);
                if (newRight != null) {
                    return new SplitCell(leftCell, newRight, splitPane.getOrientation());
                }
            }
            return null;
        }

        @Nullable
        ExtractResult extractWidget(@NotNull SithTermFxWidget target) {
            if (widget == target) {
                return new ExtractResult(this, null);
            }
            if (leftCell != null && rightCell != null) {
                ExtractResult leftResult = leftCell.extractWidget(target);
                if (leftResult != null) {
                    SplitCell newLeft = leftResult.replacement;
                    if (newLeft == null) {
                        return new ExtractResult(leftResult.extracted, rightCell);
                    }
                    return new ExtractResult(leftResult.extracted, new SplitCell(newLeft, rightCell, splitPane.getOrientation()));
                }
                ExtractResult rightResult = rightCell.extractWidget(target);
                if (rightResult != null) {
                    SplitCell newRight = rightResult.replacement;
                    if (newRight == null) {
                        return new ExtractResult(rightResult.extracted, leftCell);
                    }
                    return new ExtractResult(rightResult.extracted, new SplitCell(leftCell, newRight, splitPane.getOrientation()));
                }
            }
            return null;
        }

        @Nullable
        SplitCell removeWidget(@NotNull SithTermFxWidget target) {
            if (widget == target) {
                return null;
            }
            if (leftCell != null && rightCell != null) {
                SplitCell newLeft = leftCell.removeWidget(target);
                if (newLeft != leftCell) {
                    if (newLeft == null) {
                        return rightCell;
                    }
                    return new SplitCell(newLeft, rightCell, splitPane.getOrientation());
                }
                SplitCell newRight = rightCell.removeWidget(target);
                if (newRight != rightCell) {
                    if (newRight == null) {
                        return leftCell;
                    }
                    return new SplitCell(leftCell, newRight, splitPane.getOrientation());
                }
            }
            return this;
        }

        int countWidgets() {
            if (widget != null) return 1;
            return (leftCell != null ? leftCell.countWidgets() : 0)
                    + (rightCell != null ? rightCell.countWidgets() : 0);
        }

        void refreshBackgroundStyle() {
            if (splitPane != null) {
                applyBackgroundStyle(splitPane);
            }
            if (leftCell != null) leftCell.refreshBackgroundStyle();
            if (rightCell != null) rightCell.refreshBackgroundStyle();
        }

        void closeAll() {
            if (widget != null) {
                try {
                    widget.close();
                } catch (Exception e) {
                    logger.debug("Error closing widget: {}", e.getMessage());
                }
            }
            if (leftCell != null) leftCell.closeAll();
            if (rightCell != null) rightCell.closeAll();
        }
    }

    private static final String DROP_ZONE_OVERLAY_KEY = "sithtermfx.dropZoneOverlay";

    private static final class DropZoneOverlay {
        private final Pane pane;
        private final Region wrapper;
        private final SithTermFxWidget targetWidget;
        private final String sourceId;
        private final TerminalSplitPane splitPane;
        private Placement currentPlacement;
        private final Region zoneAbove;
        private final Region zoneBelow;
        private final Region zoneLeft;
        private final Region zoneRight;

        private static final String STYLE_ZONE = "-fx-background-color: rgba(64,128,255,0.25);";
        private static final String STYLE_ZONE_HIGHLIGHT = "-fx-background-color: rgba(64,128,255,0.5);";

        DropZoneOverlay(Region wrapper, SithTermFxWidget targetWidget, String sourceId, TerminalSplitPane splitPane) {
            this.wrapper = wrapper;
            this.targetWidget = targetWidget;
            this.sourceId = sourceId;
            this.splitPane = splitPane;
            this.pane = new Pane();
            pane.setMinSize(0, 0);
            pane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            pane.setPickOnBounds(true);
            zoneAbove = new Region();
            zoneBelow = new Region();
            zoneLeft = new Region();
            zoneRight = new Region();
            zoneAbove.setStyle(STYLE_ZONE);
            zoneBelow.setStyle(STYLE_ZONE);
            zoneLeft.setStyle(STYLE_ZONE);
            zoneRight.setStyle(STYLE_ZONE);
            pane.getChildren().addAll(zoneAbove, zoneBelow, zoneLeft, zoneRight);
            currentPlacement = null;
            pane.widthProperty().addListener((o, a, b) -> layoutZones());
            pane.heightProperty().addListener((o, a, b) -> layoutZones());
        }

        private void layoutZones() {
            double w = pane.getWidth();
            double h = pane.getHeight();
            if (w <= 0 || h <= 0) return;
            double topH = h * 0.25;
            double bottomH = h * 0.25;
            double leftW = w * 0.25;
            double rightW = w * 0.25;
            double midH = h - topH - bottomH;
            zoneAbove.resizeRelocate(0, 0, w, topH);
            zoneBelow.resizeRelocate(0, h - bottomH, w, bottomH);
            zoneLeft.resizeRelocate(0, topH, leftW, midH);
            zoneRight.resizeRelocate(w - rightW, topH, rightW, midH);
        }

        void updatePlacement(double x, double y) {
            double w = wrapper.getWidth();
            double h = wrapper.getHeight();
            if (w <= 0 || h <= 0) return;
            Placement next = null;
            if (y < h * 0.25) next = Placement.ABOVE;
            else if (y > h * 0.75) next = Placement.BELOW;
            else if (x < w * 0.25) next = Placement.LEFT_OF;
            else if (x > w * 0.75) next = Placement.RIGHT_OF;
            if (next != currentPlacement) {
                currentPlacement = next;
                zoneAbove.setStyle(next == Placement.ABOVE ? STYLE_ZONE_HIGHLIGHT : STYLE_ZONE);
                zoneBelow.setStyle(next == Placement.BELOW ? STYLE_ZONE_HIGHLIGHT : STYLE_ZONE);
                zoneLeft.setStyle(next == Placement.LEFT_OF ? STYLE_ZONE_HIGHLIGHT : STYLE_ZONE);
                zoneRight.setStyle(next == Placement.RIGHT_OF ? STYLE_ZONE_HIGHLIGHT : STYLE_ZONE);
            }
        }

        boolean tryDrop() {
            if (currentPlacement == null) return false;
            SithTermFxWidget source = splitPane.findWidgetByIdentity(sourceId);
            if (source == null) return false;
            splitPane.moveWidget(source, targetWidget, currentPlacement);
            return true;
        }

        static void show(Region wrapper, SithTermFxWidget targetWidget, String sourceId, TerminalSplitPane splitPane) {
            hide(wrapper);
            DropZoneOverlay overlay = new DropZoneOverlay(wrapper, targetWidget, sourceId, splitPane);
            wrapper.getProperties().put(DROP_ZONE_OVERLAY_KEY, overlay);
            if (wrapper instanceof StackPane) {
                overlay.pane.setOnDragOver(e -> {
                    if (e.getDragboard().hasContent(DRAG_TERMINAL_FORMAT)) {
                        e.acceptTransferModes(TransferMode.ANY);
                        updatePlacement(wrapper, e.getX(), e.getY());
                    }
                    e.consume();
                });
                overlay.pane.setOnDragDropped(e -> {
                    boolean done = tryDrop(wrapper, splitPane);
                    e.setDropCompleted(done);
                    e.consume();
                });
                ((StackPane) wrapper).getChildren().add(overlay.pane);
                Platform.runLater(overlay::layoutZones);
            }
        }

        static void hide(Region wrapper) {
            Object old = wrapper.getProperties().remove(DROP_ZONE_OVERLAY_KEY);
            if (old instanceof DropZoneOverlay && wrapper instanceof StackPane) {
                ((StackPane) wrapper).getChildren().remove(((DropZoneOverlay) old).pane);
            }
        }

        static void updatePlacement(Region wrapper, double x, double y) {
            Object o = wrapper.getProperties().get(DROP_ZONE_OVERLAY_KEY);
            if (o instanceof DropZoneOverlay) {
                ((DropZoneOverlay) o).updatePlacement(x, y);
            }
        }

        static boolean tryDrop(Region wrapper, TerminalSplitPane splitPane) {
            Object o = wrapper.getProperties().get(DROP_ZONE_OVERLAY_KEY);
            if (o instanceof DropZoneOverlay) {
                DropZoneOverlay overlay = (DropZoneOverlay) o;
                boolean ok = overlay.tryDrop();
                hide(wrapper);
                return ok;
            }
            return false;
        }
    }
}
