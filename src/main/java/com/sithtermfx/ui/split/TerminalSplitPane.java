package com.sithtermfx.ui.split;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.ui.SithTermFxWidget;
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
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.DragEvent;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.scene.layout.HBox;

/**
 * Pane that supports splitting terminal widgets horizontally or vertically.
 * Supports nested splits (e.g. 2x2 grid). Each split has its own session (TtyConnector).
 * Extends StackPane so children automatically fill the available space when the window is resized.
 */
public class TerminalSplitPane extends StackPane {

    private static final Logger logger = LoggerFactory.getLogger(TerminalSplitPane.class);

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

    private final SettingsProvider settingsProvider;
    private final SplitConnectorFactory connectorFactory;
    private final Consumer<SithTermFxWidget> widgetConfigurator;
    private final Function<SithTermFxWidget, Region> leftPanelFactory;
    private final Function<SithTermFxWidget, Region> bottomPanelFactory;
    private final BiFunction<SithTermFxWidget, TtyConnector, TtyConnector> connectorDecorator;

    private SplitCell rootCell;
    private SithTermFxWidget focusedWidget;
    private boolean broadcastMode = false;

    // Optional left-side panels (e.g. timestamp gutters) per widget
    private final Map<SithTermFxWidget, Region> widgetLeftPanels = new HashMap<>();
    private final Map<SithTermFxWidget, Region> widgetBottomPanels = new HashMap<>();
    // Per-widget VBox that hosts the bottom panel; kept so the panel can be detached/re-attached at
    // runtime when the AI-agent panel is docked to the side instead of the bottom.
    private final Map<SithTermFxWidget, VBox> widgetBottomHosts = new HashMap<>();
    private boolean bottomPanelsDetached = false;
    private final Map<SithTermFxWidget, Button> widgetCloseButtons = new HashMap<>();

    // Track the currently showing context menu so we can hide it properly
    private ContextMenu activeContextMenu;

    // Optional supplier of extra menu items to add to the context menu (e.g. timestamp toggle)
    private Function<SithTermFxWidget, List<MenuItem>> extraMenuItemsFactory;

    // Optional hook invoked when a widget is closed (split close or close-all) so owners can release
    // per-widget resources such as terminal-agent runs/panels.
    private Consumer<SithTermFxWidget> onWidgetClosed;

    // Optional hook invoked when broadcast mode actually changes state (host-app telemetry).
    private Consumer<Boolean> onBroadcastModeChanged;

    /** If set, called when user chooses "Reset" font size in context menu (e.g. to reset to connection/global default). */
    private Runnable resetZoomCallback;

    public TerminalSplitPane(@NotNull SettingsProvider settingsProvider,
                             @NotNull SplitConnectorFactory connectorFactory) {
        this(settingsProvider, connectorFactory, w -> {}, null);
    }

    public TerminalSplitPane(@NotNull SettingsProvider settingsProvider,
                             @NotNull SplitConnectorFactory connectorFactory,
                             @NotNull Consumer<SithTermFxWidget> widgetConfigurator) {
        this(settingsProvider, connectorFactory, widgetConfigurator, null);
    }

    public TerminalSplitPane(@NotNull SettingsProvider settingsProvider,
                             @NotNull SplitConnectorFactory connectorFactory,
                             @NotNull Consumer<SithTermFxWidget> widgetConfigurator,
                             @Nullable Function<SithTermFxWidget, Region> leftPanelFactory) {
        this(settingsProvider, connectorFactory, widgetConfigurator, leftPanelFactory, (widget, connector) -> connector);
    }

    public TerminalSplitPane(@NotNull SettingsProvider settingsProvider,
                             @NotNull SplitConnectorFactory connectorFactory,
                             @NotNull Consumer<SithTermFxWidget> widgetConfigurator,
                             @Nullable Function<SithTermFxWidget, Region> leftPanelFactory,
                             @NotNull BiFunction<SithTermFxWidget, TtyConnector, TtyConnector> connectorDecorator) {
        this(settingsProvider, connectorFactory, widgetConfigurator, leftPanelFactory, null, connectorDecorator);
    }

    public TerminalSplitPane(@NotNull SettingsProvider settingsProvider,
                             @NotNull SplitConnectorFactory connectorFactory,
                             @NotNull Consumer<SithTermFxWidget> widgetConfigurator,
                             @Nullable Function<SithTermFxWidget, Region> leftPanelFactory,
                             @Nullable Function<SithTermFxWidget, Region> bottomPanelFactory,
                             @NotNull BiFunction<SithTermFxWidget, TtyConnector, TtyConnector> connectorDecorator) {
        this.settingsProvider = settingsProvider;
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
     * Broadcasts input to all OTHER widgets (not the source widget).
     */
    private void broadcastToOthers(@NotNull SithTermFxWidget sourceWidget, @NotNull String data) {
        if (!broadcastMode) return;
        
        List<SithTermFxWidget> allWidgets = getAllWidgets();
        for (SithTermFxWidget widget : allWidgets) {
            if (widget != sourceWidget) {
                TtyConnector connector = widget.getTtyConnector();
                if (connector != null && connector.isConnected()) {
                    try {
                        connector.write(data);
                    } catch (IOException e) {
                        logger.debug("Failed to broadcast to widget: {}", e.getMessage());
                    }
                }
            }
        }
    }
    
    private @Nullable String getControlSequence(KeyEvent event) {
        switch (event.getCode()) {
            case ENTER: return "\r";
            case BACK_SPACE: return "\u007F";
            case TAB: return "\t";
            case ESCAPE: return "\u001B";
            case UP: return "\u001B[A";
            case DOWN: return "\u001B[B";
            case RIGHT: return "\u001B[C";
            case LEFT: return "\u001B[D";
            case HOME: return "\u001B[H";
            case END: return "\u001B[F";
            case PAGE_UP: return "\u001B[5~";
            case PAGE_DOWN: return "\u001B[6~";
            case INSERT: return "\u001B[2~";
            case DELETE: return "\u001B[3~";
            case F1: return "\u001BOP";
            case F2: return "\u001BOQ";
            case F3: return "\u001BOR";
            case F4: return "\u001BOS";
            case F5: return "\u001B[15~";
            case F6: return "\u001B[17~";
            case F7: return "\u001B[18~";
            case F8: return "\u001B[19~";
            case F9: return "\u001B[20~";
            case F10: return "\u001B[21~";
            case F11: return "\u001B[23~";
            case F12: return "\u001B[24~";
            default: return null;
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
        SithTermFxWidget widget = new SithTermFxWidget(80, 24, settingsProvider);
        widgetConfigurator.accept(widget);
        TtyConnector connector = connectorFactory.createConnectorForSplit(request);
        if (connector != null) {
            TtyConnector decoratedConnector = connectorDecorator.apply(widget, connector);
            widget.setTtyConnector(decoratedConnector != null ? decoratedConnector : connector);
            widget.start();
        }
        return widget;
    }

    private void setupWidget(@NotNull SithTermFxWidget widget) {
        focusedWidget = widget;
        widget.getPane().setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                focusedWidget = widget;
                requestWidgetFocus(widget);
            }
        });
        widget.getPreferredFocusableNode().focusedProperty().addListener((obs, oldV, newV) -> {
            if (Boolean.TRUE.equals(newV)) {
                focusedWidget = widget;
            }
        });
        // Auto-close split when session ends (e.g. Ctrl+D / exit)
        widget.addListener(new TerminalWidgetListener() {
            @Override
            public void allSessionsClosed(com.sithtermfx.ui.TerminalWidget w) {
                Platform.runLater(() -> {
                    if (rootCell != null && rootCell.countWidgets() > 1) {
                        logger.info("Session closed in split widget, auto-closing split pane");
                        closeSplit(widget);
                    }
                });
            }
        });
        
        setupContextMenu(widget);
        
        var widgetPane = widget.getPane();
        
        widgetPane.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (!broadcastMode) return;
            String character = event.getCharacter();
            if (character != null && !character.isEmpty()) {
                char c = character.charAt(0);
                if (c == '\r' || c == '\t' || c == '\u001B' || c == '\u007F') {
                    return;
                }
                broadcastToOthers(widget, character);
            }
        });
        
        widgetPane.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!broadcastMode) return;
            String sequence = getControlSequence(event);
            if (sequence != null) {
                broadcastToOthers(widget, sequence);
            }
        });
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

    public void setOnBroadcastModeChanged(@Nullable Consumer<Boolean> onBroadcastModeChanged) {
        this.onBroadcastModeChanged = onBroadcastModeChanged;
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
                focusedWidget = widget;
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
    
    private @NotNull ContextMenu createFullContextMenu(@NotNull SithTermFxWidget widget) {
        ContextMenu menu = new ContextMenu();
        var terminalPanel = widget.getTerminalPanel();
        
        MenuItem copy = new MenuItem(I18n.get("terminal.contextMenu.copy"));
        copy.setOnAction(e -> invokeTerminalPanelMethod(terminalPanel, "handleCopy", null));
        MenuItem paste = new MenuItem(I18n.get("terminal.contextMenu.paste"));
        paste.setOnAction(e -> invokeTerminalPanelMethod(terminalPanel, "pasteFromClipboard", false));
        MenuItem clearBuffer = new MenuItem(I18n.get("terminal.contextMenu.clearBuffer"));
        clearBuffer.setOnAction(e -> invokeTerminalPanelMethod(terminalPanel, "clearBuffer", null));
        MenuItem find = new MenuItem(I18n.get("terminal.contextMenu.find"));
        find.setOnAction(e -> invokeWidgetMethod(widget, "showFindComponent"));
        menu.getItems().addAll(copy, paste, clearBuffer, find);
        
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
        MenuItem increaseFont = new MenuItem(I18n.get("terminal.contextMenu.increase"));
        increaseFont.setOnAction(e -> invokeWidgetFontMethod(widget, "increaseFontSize", 2));
        MenuItem decreaseFont = new MenuItem(I18n.get("terminal.contextMenu.decrease"));
        decreaseFont.setOnAction(e -> invokeWidgetFontMethod(widget, "decreaseFontSize", 2));
        MenuItem resetFont = new MenuItem(I18n.get("terminal.contextMenu.reset"));
        resetFont.setOnAction(e -> {
            if (resetZoomCallback != null) {
                resetZoomCallback.run();
            } else {
                invokeWidgetFontMethod(widget, "resetFontSize", null);
            }
        });
        fontMenu.getItems().addAll(increaseFont, decreaseFont, resetFont);
        
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
    
    private void invokeTerminalPanelMethod(@NotNull Object terminalPanel, @NotNull String methodName, @Nullable Object arg) {
        try {
            if (arg == null) {
                try {
                    var method = terminalPanel.getClass().getDeclaredMethod(methodName);
                    method.setAccessible(true);
                    method.invoke(terminalPanel);
                    return;
                } catch (NoSuchMethodException ignored) {}
                try {
                    var method = terminalPanel.getClass().getDeclaredMethod(methodName, KeyEvent.class);
                    method.setAccessible(true);
                    method.invoke(terminalPanel, (KeyEvent) null);
                    return;
                } catch (NoSuchMethodException ignored) {}
            } else if (arg instanceof Boolean) {
                var method = terminalPanel.getClass().getDeclaredMethod(methodName, boolean.class);
                method.setAccessible(true);
                method.invoke(terminalPanel, arg);
                return;
            }
            logger.warn("Could not find method {} on TerminalPanel", methodName);
        } catch (Exception e) {
            logger.warn("Failed to invoke {} on TerminalPanel: {}", methodName, e.getMessage());
        }
    }
    
    private void invokeWidgetMethod(@NotNull SithTermFxWidget widget, @NotNull String methodName) {
        try {
            var method = widget.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(widget);
        } catch (Exception e) {
            logger.warn("Failed to invoke {} on SithTermFxWidget: {}", methodName, e.getMessage());
        }
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
        SplitRequest request = new SplitRequest(mode, widget);
        SithTermFxWidget newWidget = createWidget(request);
        TtyConnector connector = newWidget.getTtyConnector();
        if (connector == null || !connector.isConnected()) {
            try {
                newWidget.close();
            } catch (Exception ignored) {
            }
            return;
        }
        setupWidget(newWidget);
        applyLeftPanel(newWidget);
        applyBottomPanel(newWidget);
        SplitCell newCell = new SplitCell(newWidget);
        SplitCell replacement = rootCell.replaceWidget(widget, newCell, orientation);
        if (replacement != null) {
            getChildren().clear();
            rootCell = replacement;
            getChildren().add(rootCell.getNode());
            VBox.setVgrow(rootCell.getNode(), Priority.ALWAYS);
            refreshDragAndDrop();
            refreshSplitCloseButtons();
        }
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
        SplitCell replacement = rootCell.removeWidget(widget);
        if (replacement != rootCell) {
            getChildren().clear();
            rootCell = replacement;
            if (rootCell != null) {
                getChildren().add(rootCell.getNode());
                VBox.setVgrow(rootCell.getNode(), Priority.ALWAYS);
            }
            focusedWidget = rootCell != null ? findFirstWidget(rootCell) : null;
            refreshDragAndDrop();
            refreshSplitCloseButtons();
        }
    }

    public @Nullable SithTermFxWidget getFocusedWidget() {
        return focusedWidget;
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
    }

    private void refreshSplitCloseButtons() {
        boolean showButtons = rootCell != null && rootCell.countWidgets() > 1;
        List<SithTermFxWidget> activeWidgets = getAllWidgets();
        widgetCloseButtons.entrySet().removeIf(entry -> !activeWidgets.contains(entry.getKey()));
        for (Button button : widgetCloseButtons.values()) {
            button.setVisible(showButtons);
            button.setManaged(showButtons);
        }
    }

    private void invokeWidgetFontMethod(@NotNull SithTermFxWidget widget, @NotNull String methodName, @Nullable Integer delta) {
        try {
            if (delta == null) {
                var method = widget.getClass().getMethod(methodName);
                method.invoke(widget);
            } else {
                var method = widget.getClass().getMethod(methodName, int.class);
                method.invoke(widget, delta);
            }
        } catch (NoSuchMethodException e) {
            logger.debug("Widget method {} not available (upstream SithTermFX)", methodName);
        } catch (Exception e) {
            logger.debug("Failed to invoke widget method {}: {}", methodName, e.getMessage());
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
            Button closeButton = new Button("x");
            closeButton.getStyleClass().add("split-close-button");
            closeButton.setFocusTraversable(false);
            closeButton.setMinSize(18, 18);
            closeButton.setPrefSize(18, 18);
            closeButton.setMaxSize(18, 18);
            closeButton.setStyle("-fx-font-size: 10px; -fx-padding: 0; -fx-background-radius: 9;");
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

    private @Nullable SithTermFxWidget findFirstWidget(@NotNull SplitCell cell) {
        if (cell.widget != null) {
            return cell.widget;
        }
        if (cell.leftCell != null) {
            SithTermFxWidget w = findFirstWidget(cell.leftCell);
            if (w != null) return w;
        }
        if (cell.rightCell != null) {
            return findFirstWidget(cell.rightCell);
        }
        return null;
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
