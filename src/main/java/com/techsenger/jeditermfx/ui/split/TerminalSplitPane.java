package com.techsenger.jeditermfx.ui.split;

import com.techsenger.jeditermfx.core.TtyConnector;
import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import com.techsenger.jeditermfx.ui.TerminalWidgetListener;
import com.techsenger.jeditermfx.ui.settings.SettingsProvider;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final DataFormat DRAG_TERMINAL_FORMAT = new DataFormat("application/x-jeditermfx-terminal-widget");

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
    private final Consumer<JediTermFxWidget> widgetConfigurator;
    private final Function<JediTermFxWidget, Region> leftPanelFactory;

    private SplitCell rootCell;
    private JediTermFxWidget focusedWidget;
    private boolean broadcastMode = false;

    // Optional left-side panels (e.g. timestamp gutters) per widget
    private final Map<JediTermFxWidget, Region> widgetLeftPanels = new HashMap<>();

    // Track the currently showing context menu so we can hide it properly
    private ContextMenu activeContextMenu;

    // Optional supplier of extra menu items to add to the context menu (e.g. timestamp toggle)
    private Function<JediTermFxWidget, List<MenuItem>> extraMenuItemsFactory;

    public TerminalSplitPane(@NotNull SettingsProvider settingsProvider,
                             @NotNull SplitConnectorFactory connectorFactory) {
        this(settingsProvider, connectorFactory, w -> {}, null);
    }

    public TerminalSplitPane(@NotNull SettingsProvider settingsProvider,
                             @NotNull SplitConnectorFactory connectorFactory,
                             @NotNull Consumer<JediTermFxWidget> widgetConfigurator) {
        this(settingsProvider, connectorFactory, widgetConfigurator, null);
    }

    public TerminalSplitPane(@NotNull SettingsProvider settingsProvider,
                             @NotNull SplitConnectorFactory connectorFactory,
                             @NotNull Consumer<JediTermFxWidget> widgetConfigurator,
                             @Nullable Function<JediTermFxWidget, Region> leftPanelFactory) {
        this.settingsProvider = settingsProvider;
        this.connectorFactory = connectorFactory;
        this.widgetConfigurator = widgetConfigurator;
        this.leftPanelFactory = leftPanelFactory;
        this.rootCell = createInitialCell();
        getChildren().add(rootCell.getNode());
        VBox.setVgrow(this, Priority.ALWAYS);
        refreshDragAndDrop();
    }
    
    /**
     * Broadcasts input to all OTHER widgets (not the source widget).
     */
    private void broadcastToOthers(@NotNull JediTermFxWidget sourceWidget, @NotNull String data) {
        if (!broadcastMode) return;
        
        List<JediTermFxWidget> allWidgets = getAllWidgets();
        for (JediTermFxWidget widget : allWidgets) {
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
        JediTermFxWidget widget = createWidget(null);
        setupWidget(widget);
        applyLeftPanel(widget);
        return new SplitCell(widget);
    }

    private @NotNull JediTermFxWidget createWidget(@Nullable SplitRequest request) {
        JediTermFxWidget widget = new JediTermFxWidget(80, 24, settingsProvider);
        widgetConfigurator.accept(widget);
        TtyConnector connector = connectorFactory.createConnectorForSplit(request);
        if (connector != null) {
            widget.setTtyConnector(connector);
            widget.start();
        }
        return widget;
    }

    private void setupWidget(@NotNull JediTermFxWidget widget) {
        focusedWidget = widget;
        widget.getPane().setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                focusedWidget = widget;
            }
        });
        widget.getPreferredFocusableNode().focusedProperty().addListener((obs, oldV, newV) -> {
            if (Boolean.TRUE.equals(newV)) {
                focusedWidget = widget;
            }
        });
        // Auto-close split when session ends (e.g. Ctrl+D / exit)
        // Only closes the split pane if there is more than one widget;
        // if it's the last widget, the tab-level disconnect handler takes care of closing.
        widget.addListener(new TerminalWidgetListener() {
            @Override
            public void allSessionsClosed(com.techsenger.jeditermfx.ui.TerminalWidget w) {
                Platform.runLater(() -> {
                    if (rootCell != null && rootCell.countWidgets() > 1) {
                        logger.info("Session closed in split widget, auto-closing split pane");
                        closeSplit(widget);
                    }
                });
            }
        });
        
        // Add split menu via right-click on the widget pane
        setupContextMenu(widget);
        
        // Broadcast mode: register event filters on the WIDGET'S OUTER PANE
        // This is the outermost container (myInnerPanel), ensuring our filter runs 
        // BEFORE any inner filters in the capturing phase
        var widgetPane = widget.getPane();
        
        widgetPane.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (!broadcastMode) return;
            String character = event.getCharacter();
            if (character != null && !character.isEmpty()) {
                char c = character.charAt(0);
                // Only skip specific control characters that are handled in KEY_PRESSED:
                // \r (13) = ENTER, \t (9) = TAB, \u001B (27) = ESC, \u007F (127) = DEL
                // All other control characters (like Ctrl+L = \u000C) should pass through
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

    /**
     * Sets a factory that provides extra menu items to add to the context menu.
     * The factory receives the focused widget and returns a list of MenuItems.
     * Called each time the context menu is opened, so items can reflect current state.
     */
    public void setExtraMenuItemsFactory(@Nullable Function<JediTermFxWidget, List<MenuItem>> factory) {
        this.extraMenuItemsFactory = factory;
    }

    /**
     * Sets up the context menu for a widget with split options.
     * Creates a complete context menu that replaces the JediTermFX default,
     * including Copy, Paste, Clear Buffer, Find, and an "Extras" submenu.
     */
    private void setupContextMenu(@NotNull JediTermFxWidget widget) {
        // Get the canvas directly from TerminalPanel - this is where JediTermFX handles mouse events
        var terminalPanel = widget.getTerminalPanel();
        var canvas = terminalPanel.getCanvas();
        
        // Use event filter on MOUSE_PRESSED to close any existing context menu on any click
        canvas.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            if (activeContextMenu != null && activeContextMenu.isShowing()) {
                activeContextMenu.hide();
                activeContextMenu = null;
                if (event.getButton() != MouseButton.SECONDARY) {
                    // For non-right-clicks, just close the menu and let the event pass through
                    return;
                }
            }
        });
        
        // Use event filter on MOUSE_CLICKED with SECONDARY button (right-click)
        // This intercepts the event BEFORE JediTermFX's handler processes it
        canvas.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                focusedWidget = widget;
                
                // Create and show our complete context menu
                ContextMenu menu = createFullContextMenu(widget);
                activeContextMenu = menu;
                
                // Auto-clear reference when menu hides
                menu.setOnHidden(e -> {
                    if (activeContextMenu == menu) {
                        activeContextMenu = null;
                    }
                });
                
                menu.show(canvas, event.getScreenX(), event.getScreenY());
                
                // Consume the event to prevent JediTermFX from showing its own menu
                event.consume();
            }
        });
    }
    
    /**
     * Creates a full context menu with standard terminal actions plus Extras submenu.
     */
    private @NotNull ContextMenu createFullContextMenu(@NotNull JediTermFxWidget widget) {
        ContextMenu menu = new ContextMenu();
        var terminalPanel = widget.getTerminalPanel();
        
        // Copy
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> invokeTerminalPanelMethod(terminalPanel, "handleCopy", null));
        
        // Paste
        MenuItem paste = new MenuItem("Paste");
        paste.setOnAction(e -> invokeTerminalPanelMethod(terminalPanel, "pasteFromClipboard", false));
        
        // Clear Buffer
        MenuItem clearBuffer = new MenuItem("Clear Buffer");
        clearBuffer.setOnAction(e -> invokeTerminalPanelMethod(terminalPanel, "clearBuffer", null));
        
        // Find
        MenuItem find = new MenuItem("Find");
        find.setOnAction(e -> invokeWidgetMethod(widget, "showFindComponent"));
        
        menu.getItems().addAll(copy, paste, clearBuffer, find);
        
        // Add extra menu items from factory (e.g. timestamp toggle)
        if (extraMenuItemsFactory != null) {
            List<MenuItem> extraItems = extraMenuItemsFactory.apply(widget);
            if (extraItems != null && !extraItems.isEmpty()) {
                menu.getItems().add(new SeparatorMenuItem());
                menu.getItems().addAll(extraItems);
            }
        }
        
        // Separator before Extras
        menu.getItems().add(new SeparatorMenuItem());
        
        // Extras submenu
        Menu extrasMenu = createExtrasSubmenu(widget);
        menu.getItems().add(extrasMenu);
        
        return menu;
    }
    
    /**
     * Creates the "Extras" submenu with split and font options.
     */
    private @NotNull Menu createExtrasSubmenu(@NotNull JediTermFxWidget widget) {
        Menu extrasMenu = new Menu("Extras");
        
        // Font size submenu
        Menu fontMenu = new Menu("Font Size");
        MenuItem increaseFont = new MenuItem("Increase");
        increaseFont.setOnAction(e -> invokeWidgetFontMethod(widget, "increaseFontSize", 2));
        MenuItem decreaseFont = new MenuItem("Decrease");
        decreaseFont.setOnAction(e -> invokeWidgetFontMethod(widget, "decreaseFontSize", 2));
        MenuItem resetFont = new MenuItem("Reset");
        resetFont.setOnAction(e -> invokeWidgetFontMethod(widget, "resetFontSize", null));
        fontMenu.getItems().addAll(increaseFont, decreaseFont, resetFont);
        
        // Split submenu
        Menu splitMenu = new Menu("Split Terminal");
        MenuItem splitRightSame = new MenuItem("Split Right (same server)");
        splitRightSame.setOnAction(e -> split(SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.HORIZONTAL));
        MenuItem splitRightNew = new MenuItem("Split Right (new connection)");
        splitRightNew.setOnAction(e -> split(SplitRequest.SplitMode.NEW_CONNECTION, Orientation.HORIZONTAL));
        MenuItem splitDownSame = new MenuItem("Split Down (same server)");
        splitDownSame.setOnAction(e -> split(SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.VERTICAL));
        MenuItem splitDownNew = new MenuItem("Split Down (new connection)");
        splitDownNew.setOnAction(e -> split(SplitRequest.SplitMode.NEW_CONNECTION, Orientation.VERTICAL));
        MenuItem closeSplit = new MenuItem("Close Split");
        closeSplit.setOnAction(e -> closeSplit(widget));
        closeSplit.setDisable(rootCell.countWidgets() <= 1);
        splitMenu.getItems().addAll(splitRightSame, splitRightNew, new SeparatorMenuItem(),
                                    splitDownSame, splitDownNew, new SeparatorMenuItem(), closeSplit);
        
        // Broadcast mode
        CheckMenuItem broadcastToggle = new CheckMenuItem("Broadcast Mode");
        broadcastToggle.setSelected(broadcastMode);
        broadcastToggle.setOnAction(e -> setBroadcastMode(broadcastToggle.isSelected()));
        broadcastToggle.setDisable(rootCell.countWidgets() <= 1);
        
        // Add all to Extras menu
        extrasMenu.getItems().addAll(fontMenu, splitMenu, new SeparatorMenuItem(), broadcastToggle);
        
        return extrasMenu;
    }
    
    /**
     * Invokes a method on the TerminalPanel via reflection.
     */
    private void invokeTerminalPanelMethod(@NotNull Object terminalPanel, @NotNull String methodName, @Nullable Object arg) {
        try {
            if (arg == null) {
                // Try no-arg method first
                try {
                    var method = terminalPanel.getClass().getDeclaredMethod(methodName);
                    method.setAccessible(true);
                    method.invoke(terminalPanel);
                    return;
                } catch (NoSuchMethodException ignored) {}
                
                // Try KeyEvent arg (for handleCopy)
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
    
    /**
     * Invokes a no-arg method on the JediTermFxWidget via reflection.
     */
    private void invokeWidgetMethod(@NotNull JediTermFxWidget widget, @NotNull String methodName) {
        try {
            var method = widget.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(widget);
        } catch (Exception e) {
            logger.warn("Failed to invoke {} on JediTermFxWidget: {}", methodName, e.getMessage());
        }
    }

    /**
     * Splits the focused area in the given direction.
     */
    public void split(@NotNull SplitRequest.SplitMode mode, @NotNull Orientation orientation) {
        JediTermFxWidget parent = getFocusedWidget();
        if (parent == null) {
            logger.warn("No focused widget to split");
            return;
        }
        splitWidget(parent, mode, orientation);
    }

    /**
     * Splits horizontally (new pane to the right).
     */
    public void splitHorizontally(@Nullable SplitRequest.SplitMode mode) {
        split(mode != null ? mode : SplitRequest.SplitMode.NEW_CONNECTION, Orientation.HORIZONTAL);
    }

    /**
     * Splits vertically (new pane below).
     */
    public void splitVertically(@Nullable SplitRequest.SplitMode mode) {
        split(mode != null ? mode : SplitRequest.SplitMode.NEW_CONNECTION, Orientation.VERTICAL);
    }

    private void splitWidget(@NotNull JediTermFxWidget widget, @NotNull SplitRequest.SplitMode mode,
                            @NotNull Orientation orientation) {
        SplitRequest request = new SplitRequest(mode, widget);
        JediTermFxWidget newWidget = createWidget(request);
        if (newWidget.getTtyConnector() == null) {
            return;
        }
        setupWidget(newWidget);
        applyLeftPanel(newWidget);

        SplitCell newCell = new SplitCell(newWidget);
        SplitCell replacement = rootCell.replaceWidget(widget, newCell, orientation);
        if (replacement != null) {
            getChildren().clear();
            rootCell = replacement;
            getChildren().add(rootCell.getNode());
            VBox.setVgrow(rootCell.getNode(), Priority.ALWAYS);
            refreshDragAndDrop();
        }
    }

    private void closeSplit(@NotNull JediTermFxWidget widget) {
        try {
            widget.close();
        } catch (Exception e) {
            logger.debug("Error closing widget: {}", e.getMessage());
        }
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
        }
    }

    /**
     * Returns the currently focused terminal widget.
     */
    public @Nullable JediTermFxWidget getFocusedWidget() {
        return focusedWidget;
    }

    /**
     * Returns all terminal widgets in this split pane.
     */
    public @NotNull List<JediTermFxWidget> getAllWidgets() {
        List<JediTermFxWidget> widgets = new ArrayList<>();
        collectWidgets(rootCell, widgets);
        return widgets;
    }
    
    private void collectWidgets(@Nullable SplitCell cell, @NotNull List<JediTermFxWidget> widgets) {
        if (cell == null) return;
        if (cell.widget != null) {
            widgets.add(cell.widget);
        }
        collectWidgets(cell.leftCell, widgets);
        collectWidgets(cell.rightCell, widgets);
    }
    
    /**
     * Returns the number of terminal widgets in this split pane.
     */
    public int getWidgetCount() {
        return rootCell != null ? rootCell.countWidgets() : 0;
    }
    
    /**
     * Checks if broadcast mode is enabled.
     */
    public boolean isBroadcastMode() {
        return broadcastMode;
    }
    
    /**
     * Enables or disables broadcast mode.
     * When enabled, keyboard input is sent to all terminal widgets simultaneously.
     */
    public void setBroadcastMode(boolean enabled) {
        this.broadcastMode = enabled;
        logger.info("Broadcast mode {}", enabled ? "enabled" : "disabled");
    }
    
    /**
     * Toggles broadcast mode on/off.
     */
    public void toggleBroadcastMode() {
        setBroadcastMode(!broadcastMode);
    }

    /**
     * Moves a split terminal to a new position relative to another (e.g. above, below, left, right).
     */
    public void moveWidget(@NotNull JediTermFxWidget source, @NotNull JediTermFxWidget target,
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

    private @Nullable JediTermFxWidget findWidgetByIdentity(@NotNull String idString) {
        int id;
        try {
            id = Integer.parseInt(idString);
        } catch (NumberFormatException e) {
            return null;
        }
        for (JediTermFxWidget w : getAllWidgets()) {
            if (System.identityHashCode(w) == id) return w;
        }
        return null;
    }

    private void attachDragAndDropToCell(@NotNull SplitCell cell) {
        Region node = cell.getNode();
        JediTermFxWidget widget = cell.widget;
        if (widget == null) return;

        node.setOnDragDetected(event -> {
            if (getWidgetCount() <= 1) return;
            Dragboard db = node.startDragAndDrop(TransferMode.MOVE);
            db.setContent(Map.of(DRAG_TERMINAL_FORMAT, String.valueOf(System.identityHashCode(widget))));
            event.consume();
        });

        node.setOnDragEntered(event -> {
            if (!event.getDragboard().hasContent(DRAG_TERMINAL_FORMAT)) return;
            String sourceId = (String) event.getDragboard().getContent(DRAG_TERMINAL_FORMAT);
            if (sourceId.equals(String.valueOf(System.identityHashCode(widget)))) return;
            JediTermFxWidget source = findWidgetByIdentity(sourceId);
            if (source == null) return;
            DropZoneOverlay.show(node, widget, sourceId, this);
            event.consume();
        });

        node.setOnDragExited(event -> {
            DropZoneOverlay.hide(node);
            event.consume();
        });

        node.setOnDragOver(event -> {
            if (event.getDragboard().hasContent(DRAG_TERMINAL_FORMAT)) {
                event.acceptTransferModes(TransferMode.MOVE);
                DropZoneOverlay.updatePlacement(node, event.getX(), event.getY());
            }
            event.consume();
        });

        node.setOnDragDropped(event -> {
            boolean done = DropZoneOverlay.tryDrop(node, this);
            event.setDropCompleted(done);
            event.consume();
        });
    }

    /**
     * Applies the left panel factory for a widget, storing the result
     * in widgetLeftPanels so SplitCell can include it in the layout.
     * Called after widgetConfigurator but before SplitCell construction.
     */
    private void applyLeftPanel(@NotNull JediTermFxWidget widget) {
        if (leftPanelFactory != null) {
            Region leftPanel = leftPanelFactory.apply(widget);
            if (leftPanel != null) {
                widgetLeftPanels.put(widget, leftPanel);
            }
        }
    }

    /**
     * Registers a left-side panel (e.g. timestamp gutter) for a widget.
     * For dynamically adding panels after the widget is already created.
     */
    public void setWidgetLeftPanel(@NotNull JediTermFxWidget widget, @NotNull Region panel) {
        widgetLeftPanels.put(widget, panel);
    }

    /**
     * Removes the left-side panel reference for a widget.
     */
    public void removeWidgetLeftPanel(@NotNull JediTermFxWidget widget) {
        widgetLeftPanels.remove(widget);
    }

    /**
     * Closes all terminal sessions in this split pane.
     */
    public void closeAll() {
        rootCell.closeAll();
    }

    /**
     * Invokes optional font size methods on JediTermFxWidget via reflection.
     * This keeps compatibility with upstream JediTermFX builds where these methods do not exist.
     */
    private void invokeWidgetFontMethod(@NotNull JediTermFxWidget widget, @NotNull String methodName, @Nullable Integer delta) {
        try {
            if (delta == null) {
                var method = widget.getClass().getMethod(methodName);
                method.invoke(widget);
            } else {
                var method = widget.getClass().getMethod(methodName, int.class);
                method.invoke(widget, delta);
            }
        } catch (NoSuchMethodException e) {
            logger.debug("Widget method {} not available (upstream JediTermFX)", methodName);
        } catch (Exception e) {
            logger.debug("Failed to invoke widget method {}: {}", methodName, e.getMessage());
        }
    }

    /**
     * Recursive cell: either a single widget or a SplitPane with two cells.
     */
    private class SplitCell {
        private final @Nullable JediTermFxWidget widget;
        private final @Nullable SplitPane splitPane;
        private final @Nullable SplitCell leftCell;
        private final @Nullable SplitCell rightCell;
        private final Region node;

        SplitCell(@NotNull JediTermFxWidget widget) {
            this.widget = widget;
            this.splitPane = null;
            this.leftCell = null;
            this.rightCell = null;

            // Check if a left-side panel (e.g. timestamp gutter) is registered for this widget
            Region leftPanel = widgetLeftPanels.get(widget);
            Region wrapperContent;
            if (leftPanel != null) {
                HBox hbox = new HBox(leftPanel, widget.getPane());
                HBox.setHgrow(widget.getPane(), Priority.ALWAYS);
                hbox.setMinSize(0, 0);
                hbox.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                wrapperContent = hbox;
            } else {
                wrapperContent = widget.getPane();
            }

            StackPane wrapper = new StackPane(wrapperContent);
            wrapper.setMinSize(0, 0);
            wrapper.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            VBox.setVgrow(wrapper, Priority.ALWAYS);
            wrapper.setUserData(widget);
            this.node = wrapper;
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
            // Re-apply divider position after layout - prevents right pane from getting zero width
            Platform.runLater(() -> splitPane.setDividerPositions(0.5));
        }

        Region getNode() {
            return node;
        }

        @Nullable
        SplitCell replaceWidget(@NotNull JediTermFxWidget target, @NotNull SplitCell newCell,
                               @NotNull Orientation orientation) {
            return replaceWidget(target, newCell, orientation, false);
        }

        @Nullable
        SplitCell replaceWidget(@NotNull JediTermFxWidget target, @NotNull SplitCell newCell,
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
        ExtractResult extractWidget(@NotNull JediTermFxWidget target) {
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
        SplitCell removeWidget(@NotNull JediTermFxWidget target) {
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

    private @Nullable JediTermFxWidget findFirstWidget(@NotNull SplitCell cell) {
        if (cell.widget != null) {
            return cell.widget;
        }
        if (cell.leftCell != null) {
            JediTermFxWidget w = findFirstWidget(cell.leftCell);
            if (w != null) return w;
        }
        if (cell.rightCell != null) {
            return findFirstWidget(cell.rightCell);
        }
        return null;
    }

    private static final String DROP_ZONE_OVERLAY_KEY = "jeditermfx.dropZoneOverlay";

    private static final class DropZoneOverlay {
        private final Pane pane;
        private final Region wrapper;
        private final JediTermFxWidget targetWidget;
        private final String sourceId;
        private final TerminalSplitPane splitPane;
        private Placement currentPlacement;
        private final Region zoneAbove;
        private final Region zoneBelow;
        private final Region zoneLeft;
        private final Region zoneRight;

        private static final String STYLE_ZONE = "-fx-background-color: rgba(64,128,255,0.25);";
        private static final String STYLE_ZONE_HIGHLIGHT = "-fx-background-color: rgba(64,128,255,0.5);";

        DropZoneOverlay(Region wrapper, JediTermFxWidget targetWidget, String sourceId, TerminalSplitPane splitPane) {
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
            JediTermFxWidget source = splitPane.findWidgetByIdentity(sourceId);
            if (source == null) return false;
            splitPane.moveWidget(source, targetWidget, currentPlacement);
            return true;
        }

        static void show(Region wrapper, JediTermFxWidget targetWidget, String sourceId, TerminalSplitPane splitPane) {
            hide(wrapper);
            DropZoneOverlay overlay = new DropZoneOverlay(wrapper, targetWidget, sourceId, splitPane);
            wrapper.getProperties().put(DROP_ZONE_OVERLAY_KEY, overlay);
            if (wrapper instanceof StackPane) {
                overlay.pane.setOnDragOver(e -> {
                    if (e.getDragboard().hasContent(DRAG_TERMINAL_FORMAT)) {
                        e.acceptTransferModes(TransferMode.MOVE);
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
