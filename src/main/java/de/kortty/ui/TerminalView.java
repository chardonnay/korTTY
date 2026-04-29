package de.kortty.ui;

import com.sithtermfx.core.CursorShape;
import com.sithtermfx.core.Terminal;
import com.sithtermfx.core.TerminalColor;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import com.sithtermfx.ui.split.SplitConnectorFactory;
import com.sithtermfx.ui.split.SplitRequest;
import com.sithtermfx.ui.split.TerminalSplitPane;
import de.kortty.KorTTYApplication;
import de.kortty.core.AiAction;
import de.kortty.core.AiTokenUsageManager;
import de.kortty.core.AiTokenWarningLevel;
import de.kortty.core.Mosh4jTtyConnector;
import de.kortty.core.SshTtyConnector;
import de.kortty.core.NativeMoshTtyConnector;
import de.kortty.core.DisconnectListener;
import de.kortty.core.TerminalAgentCommandSupport;
import de.kortty.model.AiProfile;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.Theme;
import javafx.application.Platform;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.util.Duration;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.input.Dragboard;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.control.ProgressIndicator;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.scene.layout.VBox;
import javafx.scene.Scene;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

/**
 * Terminal view component using SithTermFX for professional terminal emulation.
 */
public class TerminalView extends BorderPane {
    
    private static final Logger logger = LoggerFactory.getLogger(TerminalView.class);
    private static final String AGENT_SHORTCUT_DISPATCHER_INSTALLED_KEY = "kortty.agentShortcutDispatcherInstalled";
    private static final Duration AGENT_SHELL_KEEPALIVE_INTERVAL = Duration.minutes(4);
    
    /**
     * Callback interface for creating new connections (e.g., via QuickConnect dialog).
     * Used when user requests a split with a new connection to a different server.
     */
    @FunctionalInterface
    public interface NewConnectionCallback {
        /**
         * Called when user requests a split with a new connection.
         * Implementation should show a connection dialog and return the result.
         * @return ConnectionResult with connection details, or null if cancelled
         */
        @Nullable ConnectionResult requestNewConnection();
    }
    
    /**
     * Result of a new connection request.
     */
    public static class ConnectionResult {
        public final ServerConnection connection;
        public final String password;
        
        public ConnectionResult(ServerConnection connection, String password) {
            this.connection = connection;
            this.password = password;
        }
    }

    @FunctionalInterface
    public interface AiSelectionHandler {
        void handle(AiAction action, @Nullable AiProfile profile, String selectedText);
    }

    @FunctionalInterface
    public interface TerminalAgentShortcutHandler {
        void handle(String rawCommand, @Nullable TerminalAgentRunContext runContext);
    }

    @FunctionalInterface
    public interface TerminalAgentContextHandler {
        void handle(@Nullable TerminalAgentRunContext runContext);
    }

    public record TerminalAgentRunContext(@Nullable SithTermFxWidget widget, SshTtyConnector connector) {
    }

    private static final class TerminalAgentRunState {
        private final TerminalAgentRunContext runContext;
        private final Runnable cancelHandler;
        private final Runnable toggleDetailsHandler;
        private Timeline shellKeepAliveTimeline;

        private TerminalAgentRunState(
            TerminalAgentRunContext runContext,
            @Nullable Runnable cancelHandler,
            @Nullable Runnable toggleDetailsHandler) {
            this.runContext = runContext;
            this.cancelHandler = cancelHandler;
            this.toggleDetailsHandler = toggleDetailsHandler;
        }
    }
    
    private final ServerConnection connection;
    private final ConnectionSettings settings;
    private final String password;
    private de.kortty.model.TemporarySSHKey temporarySSHKey;  // For split connections with temporary key
    
    private TerminalSplitPane splitPane;
    private StackPane terminalContainer;
    private String terminalAgentBusyStylesheetUrl;
    private SithTermFxWidget terminalWidget;  // Primary widget (first terminal in split)
    private TtyConnector ttyConnector;
    private KorTTYSettingsProvider settingsProvider;
    private final int defaultFontSize;
    /** Font size and family at tab open (from connection settings before theme/global). Reset uses these so zoom reset matches what user saw. */
    private final int connectionSavedFontSize;
    private final String connectionSavedFontFamily;
    
    private DisconnectListener externalDisconnectListener;
    private Runnable onConnectedCallback;
    private Runnable onMoshInterruptedCallback;
    private de.kortty.core.TerminalLogger terminalLogger;
    private NewConnectionCallback newConnectionCallback;
    
    // Timestamp gutter support: maps each widget to its gutter
    private final Map<SithTermFxWidget, TimestampGutter> gutterMap = new ConcurrentHashMap<>();
    // Last absolute line we added a timestamp for (per widget), to detect new prompt lines from server
    private final Map<SithTermFxWidget, Integer> lastTimestampLineByWidget = new ConcurrentHashMap<>();
    // Timestamp history per widget, independent from gutter visibility/UI state
    private final Map<SithTermFxWidget, TreeMap<Integer, LocalDateTime>> timestampHistoryByWidget = new ConcurrentHashMap<>();
    // Tracks whether we are waiting for "command finished" timestamp after user pressed Enter.
    private final Map<SithTermFxWidget, Boolean> awaitingCommandCompletionByWidget = new ConcurrentHashMap<>();
    // Debounce timer per widget: a short quiet period marks command completion.
    private final Map<SithTermFxWidget, PauseTransition> commandCompletionTimerByWidget = new ConcurrentHashMap<>();
    // Absolute line where the current command started (Enter pressed).
    private final Map<SithTermFxWidget, Integer> commandStartLineByWidget = new ConcurrentHashMap<>();

    // Optional listener called when timestamp gutter visibility is toggled (e.g. from context menu)
    private Runnable timestampToggleListener;
    private Runnable onReconnectRequested;
    private AiSelectionHandler aiSelectionHandler;
    private TerminalAgentContextHandler aiAgentHandler;
    private TerminalAgentContextHandler aiAgentAskHandler;
    private TerminalAgentContextHandler aiPlanningHandler;
    private TerminalAgentShortcutHandler terminalAgentShortcutHandler;
    private final Map<SithTermFxWidget, AiAgentActivityPanel> terminalAgentActivityPanels = new ConcurrentHashMap<>();
    private final Map<SithTermFxWidget, TerminalAgentRunState> terminalAgentRunStates = new ConcurrentHashMap<>();
    private SshTtyConnector.DataListener terminalLoggerDataListener;
    private final Map<SshTtyConnector, SshTtyConnector.DataListener> terminalAgentPromptDataListeners = new ConcurrentHashMap<>();
    private final Map<SithTermFxWidget, StringBuilder> agentShortcutBuffers = new ConcurrentHashMap<>();
    private final StringBuilder agentShortcutPromptTail = new StringBuilder();
    private final Map<SshTtyConnector, StringBuilder> terminalAgentOscBuffers = new ConcurrentHashMap<>();
    private volatile boolean agentShortcutPromptReady;
    private volatile boolean timestampGuttersVisibleState;
    
    public TerminalView(ServerConnection connection, String password) {
        this(connection, password, null);
    }
    
    public TerminalView(ServerConnection connection, String password, de.kortty.model.TemporarySSHKey temporarySSHKey) {
        this.connection = connection;
        this.password = password;
        this.temporarySSHKey = temporarySSHKey;
        // Capture connection's font size and family at open (before theme/default resolution) for zoom reset.
        ConnectionSettings connSettingsForReset = (connection != null) ? connection.getSettings() : null;
        int savedSize = 0;
        String savedFamily = null;
        if (connSettingsForReset != null) {
            if (connSettingsForReset.getFontSize() > 0) savedSize = connSettingsForReset.getFontSize();
            if (connSettingsForReset.getFontFamily() != null && !connSettingsForReset.getFontFamily().isEmpty()) {
                savedFamily = connSettingsForReset.getFontFamily();
            }
        }
        this.connectionSavedFontSize = savedSize;
        this.connectionSavedFontFamily = savedFamily;
        
        // Ensure settings is never null - use connection settings or create defaults
        ConnectionSettings connSettings = connection.getSettings();
        if (connSettings == null) {
            connSettings = new ConnectionSettings();
            try {
                var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
                if (gs != null && gs.getDefaultTerminalSettings() != null) {
                    connSettings = new ConnectionSettings(gs.getDefaultTerminalSettings());
                }
            } catch (Exception e) {
                // Use defaults
            }
            connection.setSettings(connSettings);
            logger.warn("Connection '{}' had no settings, using defaults", connection.getName());
        }
        // Resolve theme if themeId is set
        ConnectionSettings effective = connSettings;
        String themeId = connSettings.getThemeId();
        if (themeId != null && !themeId.isEmpty()) {
            try {
                var tm = KorTTYApplication.getInstance().getThemeManager();
                if (tm != null) {
                    effective = tm.resolveSettings(connSettings, themeId, isThemeFontApplyEnabled());
                }
            } catch (Exception e) {
                // Use connection settings
            }
        }
        this.settings = effective;
        this.defaultFontSize = settings.getFontSize();
        this.timestampGuttersVisibleState = isCommandTimestampsEnabled();
        
        initializeTerminal();
    }
    
    private void initializeTerminal() {
        // Create settings provider with dynamic font size support (enables Cmd+Plus/Minus zoom)
        settingsProvider = new KorTTYSettingsProvider(settings, defaultFontSize);
        
        // Add listener to re-render terminals when font size changes
        settingsProvider.addFontSizeListener(this::updateAllTerminalFonts);
        
        // Create SplitConnectorFactory that creates new SSH connections for splits
        SplitConnectorFactory connectorFactory = this::createSplitConnector;
        
        // Create TerminalSplitPane with split support
        // Right-click context menu will show: Font size options + Split right/down + Close split
        splitPane = new TerminalSplitPane(settingsProvider, connectorFactory, widget -> {
            setupWidgetEventHandlers(widget);
            applyCursorShape(widget);
            setupTimestampGutter(widget);
        }, widget -> gutterMap.get(widget), this::createTerminalAgentActivityPanel, this::decorateTerminalConnector); // Left panel factory: returns the gutter created in setupTimestampGutter
        splitPane.setResetZoomCallback(this::resetZoom); // Reset zoom to connection or global default (not hardcoded 14)
        
        // Register extra context menu items: Theme, Reconnect, Timestamp toggle
        splitPane.setExtraMenuItemsFactory(widget -> {
            java.util.List<javafx.scene.control.MenuItem> items = new java.util.ArrayList<>();
            String selectedText = getSelectedText(widget);
            List<AiProfile> aiProfiles = getConfiguredAiProfiles();
            boolean hasSelectedText = selectedText != null && !selectedText.isBlank();
            boolean hasAgentActions = aiAgentHandler != null || aiAgentAskHandler != null || aiPlanningHandler != null;
            if (shouldShowAiContextMenu(aiProfiles, hasSelectedText, hasAgentActions)) {
                javafx.scene.control.Menu aiMenu = new javafx.scene.control.Menu(I18n.get("terminal.contextMenu.ai"));
                if (aiAgentHandler != null) {
                    javafx.scene.control.MenuItem agentItem = new javafx.scene.control.MenuItem(I18n.get("terminal.contextMenu.ai.agent"));
                    agentItem.setOnAction(e -> aiAgentHandler.handle(createTerminalAgentRunContext(widget)));
                    aiMenu.getItems().add(agentItem);
                }
                if (aiAgentAskHandler != null) {
                    javafx.scene.control.MenuItem agentAskItem = new javafx.scene.control.MenuItem(I18n.get("terminal.contextMenu.ai.agentAsk"));
                    agentAskItem.setOnAction(e -> aiAgentAskHandler.handle(createTerminalAgentRunContext(widget)));
                    aiMenu.getItems().add(agentAskItem);
                }
                if (aiPlanningHandler != null) {
                    javafx.scene.control.MenuItem planningItem = new javafx.scene.control.MenuItem(I18n.get("terminal.contextMenu.ai.plan"));
                    planningItem.setOnAction(e -> aiPlanningHandler.handle(createTerminalAgentRunContext(widget)));
                    aiMenu.getItems().add(planningItem);
                }
                if (!aiMenu.getItems().isEmpty() && hasSelectedText) {
                    aiMenu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
                }
                if (hasSelectedText) {
                    aiMenu.getItems().addAll(
                        createAiProfileMenu(I18n.get("terminal.contextMenu.ai.summarize"), AiAction.SUMMARIZE, aiProfiles, selectedText),
                        createAiProfileMenu(I18n.get("terminal.contextMenu.ai.solve"), AiAction.SOLVE_PROBLEM, aiProfiles, selectedText),
                        createAiProfileMenu(I18n.get("terminal.contextMenu.ai.ask"), AiAction.ASK, aiProfiles, selectedText)
                    );
                }
                items.add(aiMenu);
                items.add(new javafx.scene.control.SeparatorMenuItem());
            }
            javafx.scene.control.Menu themeMenu = new javafx.scene.control.Menu(I18n.get("theme.menu"));
            try {
                var tm = KorTTYApplication.getInstance().getThemeManager();
                if (tm != null) {
                    for (Theme t : tm.getThemes()) {
                        javafx.scene.control.MenuItem mi = new javafx.scene.control.MenuItem(t.getName());
                        Theme theme = t;
                        mi.setOnAction(e -> applyThemeAtRuntime(theme));
                        themeMenu.getItems().add(mi);
                    }
                }
            } catch (Exception e) {
                // Theme manager not available
            }
            if (!themeMenu.getItems().isEmpty()) {
                items.add(themeMenu);
                items.add(new javafx.scene.control.SeparatorMenuItem());
            }
            javafx.scene.control.MenuItem reconnectItem = new javafx.scene.control.MenuItem(I18n.get("dashboard.reconnect"));
            reconnectItem.setOnAction(e -> {
                if (onReconnectRequested != null) Platform.runLater(onReconnectRequested);
            });
            items.add(reconnectItem);
            items.add(new javafx.scene.control.SeparatorMenuItem());
            javafx.scene.control.CheckMenuItem timestampToggle =
                new javafx.scene.control.CheckMenuItem(I18n.get("menu.view.timestamps"));
            timestampToggle.setSelected(isTimestampGuttersVisible());
            timestampToggle.setOnAction(e -> {
                toggleTimestampGutters();
                if (timestampToggleListener != null) timestampToggleListener.run();
            });
            items.add(timestampToggle);
            return items;
        });
        
        terminalWidget = splitPane.getFocusedWidget();
        if (terminalWidget != null) applyCursorShape(terminalWidget);
        
        // Handle arrow and navigation keys at split-pane level so we run before the terminal widget
        // (which may consume them for scrolling). Ensures mc and similar apps receive arrow keys.
        splitPane.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isConsumed()) {
                return;
            }
            SithTermFxWidget eventWidget = resolveWidgetForKeyEvent(event);
            if (handleTerminalAgentInputLock(eventWidget, event)) {
                return;
            }
            if (eventWidget != null) {
                handleAgentShortcutKeyPressed(eventWidget, event);
                if (event.isConsumed()) {
                    return;
                }
            }
            String sequence = keyCodeToControlSequence(event.getCode());
            if (sequence != null) {
                SithTermFxWidget focused = eventWidget != null ? eventWidget : splitPane.getFocusedWidget();
                if (focused != null) {
                    TtyConnector connector = focused.getTtyConnector();
                    if (connector != null && connector.isConnected()) {
                        try {
                            connector.write(sequence);
                            event.consume();
                        } catch (java.io.IOException e) {
                            logger.debug("Failed to send key sequence: {}", e.getMessage());
                        }
                    }
                }
            }
        });
        
        // Require Shift+Alt/Option for pane-move drag; otherwise consume so terminal gets text selection
        splitPane.addEventFilter(MouseEvent.DRAG_DETECTED, e -> {
            if (splitPane.getWidgetCount() > 1 && !(e.isShiftDown() && e.isAltDown())) {
                logger.debug("TerminalView DnD gate: consume DRAG_DETECTED (shift={}, alt={}, ctrl={}, meta={})",
                        e.isShiftDown(), e.isAltDown(), e.isControlDown(), e.isMetaDown());
                e.consume();
            }
        });
        
        terminalContainer = new StackPane(splitPane);
        setCenter(terminalContainer);

        setupDragDrop();

        // Request focus on the terminal
        Platform.runLater(() -> {
            SithTermFxWidget focused = splitPane.getFocusedWidget();
            if (focused != null) {
                getPrimaryKeyEventTarget(focused).requestFocus();
            }
        });
    }

    public void setAiSelectionHandler(@Nullable AiSelectionHandler aiSelectionHandler) {
        this.aiSelectionHandler = aiSelectionHandler;
    }

    public void setAiAgentHandler(TerminalAgentContextHandler aiAgentHandler) {
        this.aiAgentHandler = aiAgentHandler;
    }

    public void setAiAgentAskHandler(TerminalAgentContextHandler aiAgentAskHandler) {
        this.aiAgentAskHandler = aiAgentAskHandler;
    }

    public void setAiPlanningHandler(TerminalAgentContextHandler aiPlanningHandler) {
        this.aiPlanningHandler = aiPlanningHandler;
    }

    public void setTerminalAgentShortcutHandler(TerminalAgentShortcutHandler terminalAgentShortcutHandler) {
        this.terminalAgentShortcutHandler = terminalAgentShortcutHandler;
        logger.debug("Terminal AI shortcut handler {}", terminalAgentShortcutHandler != null ? "installed" : "cleared");
    }

    public void setTerminalAgentInputLocked(
        boolean locked,
        @Nullable Runnable cancelHandler,
        @Nullable Runnable toggleDetailsHandler) {
        setTerminalAgentInputLocked(captureTerminalAgentRunContext(), locked, cancelHandler, toggleDetailsHandler);
    }

    public void setTerminalAgentInputLocked(
        @Nullable TerminalAgentRunContext runContext,
        boolean locked,
        @Nullable Runnable cancelHandler,
        @Nullable Runnable toggleDetailsHandler) {
        TerminalAgentRunContext resolvedContext = runContext != null ? runContext : captureTerminalAgentRunContext();
        SithTermFxWidget widget = resolvedContext != null ? resolvedContext.widget() : null;
        TerminalAgentRunState previousState = widget != null ? terminalAgentRunStates.get(widget) : null;
        if (locked && resolvedContext != null && widget != null) {
            stopTerminalAgentShellKeepAlive(previousState);
            TerminalAgentRunState state = new TerminalAgentRunState(resolvedContext, cancelHandler, toggleDetailsHandler);
            terminalAgentRunStates.put(widget, state);
            startTerminalAgentShellKeepAlive(state);
        } else if (!locked && widget != null) {
            previousState = terminalAgentRunStates.remove(widget);
            stopTerminalAgentShellKeepAlive(previousState);
        }
        Platform.runLater(() -> {
            if (locked) {
                setCursorVisible(widget, false);
            } else {
                setCursorVisible(widget, true);
            }
        });
    }

    public @Nullable TerminalAgentRunContext captureTerminalAgentRunContext() {
        SithTermFxWidget focused = splitPane != null ? splitPane.getFocusedWidget() : terminalWidget;
        return createTerminalAgentRunContext(focused);
    }

    private @Nullable TerminalAgentRunContext createTerminalAgentRunContext(@Nullable SithTermFxWidget widget) {
        if (widget == null) {
            return null;
        }
        TtyConnector connector = widget.getTtyConnector();
        if (connector instanceof SshTtyConnector sshConnector && sshConnector.isConnected()) {
            return new TerminalAgentRunContext(widget, sshConnector);
        }
        return null;
    }

    private @Nullable TerminalAgentRunContext createTerminalAgentRunContext(SshTtyConnector connector) {
        if (connector == null || !connector.isConnected()) {
            return null;
        }
        return new TerminalAgentRunContext(findWidgetForConnector(connector), connector);
    }

    private @Nullable SithTermFxWidget findWidgetForConnector(SshTtyConnector connector) {
        if (connector == null || splitPane == null) {
            return null;
        }
        for (SithTermFxWidget widget : splitPane.getAllWidgets()) {
            if (widget != null && widget.getTtyConnector() == connector) {
                return widget;
            }
        }
        return null;
    }

    public @Nullable AiAgentActivityPanel getTerminalAgentActivityPanel(@Nullable TerminalAgentRunContext runContext) {
        TerminalAgentRunContext resolvedContext = runContext != null ? runContext : captureTerminalAgentRunContext();
        SithTermFxWidget widget = resolvedContext != null ? resolvedContext.widget() : null;
        return widget != null ? terminalAgentActivityPanels.get(widget) : null;
    }

    public boolean isTerminalAgentRunActive(@Nullable TerminalAgentRunContext runContext) {
        TerminalAgentRunContext resolvedContext = runContext != null ? runContext : captureTerminalAgentRunContext();
        SithTermFxWidget widget = resolvedContext != null ? resolvedContext.widget() : null;
        return widget != null && terminalAgentRunStates.containsKey(widget);
    }

    public String buildTerminalAgentScopedSessionId(String sessionId, @Nullable TerminalAgentRunContext runContext) {
        String baseSessionId = sessionId != null && !sessionId.isBlank() ? sessionId : "terminal-agent";
        SshTtyConnector connector = runContext != null ? runContext.connector() : null;
        return connector != null
            ? baseSessionId + ":" + Integer.toHexString(System.identityHashCode(connector))
            : baseSessionId;
    }

    public void applyTerminalAgentActivityTheme(@Nullable Theme theme) {
        if (terminalContainer == null) {
            return;
        }
        if (terminalAgentBusyStylesheetUrl != null) {
            terminalContainer.getStylesheets().remove(terminalAgentBusyStylesheetUrl);
            terminalAgentBusyStylesheetUrl = null;
        }
        String stylesheetUrl = ThemeCssSupport.getAgentActivityStylesheetUrl(theme);
        if (stylesheetUrl != null) {
            terminalContainer.getStylesheets().add(stylesheetUrl);
            terminalAgentBusyStylesheetUrl = stylesheetUrl;
        }
        for (AiAgentActivityPanel panel : terminalAgentActivityPanels.values()) {
            if (panel != null) {
                panel.applyTheme(theme);
            }
        }
    }

    private Region createTerminalAgentActivityPanel(SithTermFxWidget widget) {
        AiAgentActivityPanel panel = new AiAgentActivityPanel();
        terminalAgentActivityPanels.put(widget, panel);
        return panel;
    }

    private void startTerminalAgentShellKeepAlive(TerminalAgentRunState state) {
        if (state == null) {
            return;
        }
        if (state.shellKeepAliveTimeline == null) {
            state.shellKeepAliveTimeline = new Timeline(new KeyFrame(
                AGENT_SHELL_KEEPALIVE_INTERVAL,
                event -> sendTerminalAgentShellKeepAlive(state)));
            state.shellKeepAliveTimeline.setCycleCount(Timeline.INDEFINITE);
        }
        state.shellKeepAliveTimeline.playFromStart();
    }

    private void stopTerminalAgentShellKeepAlive(@Nullable TerminalAgentRunState state) {
        if (state != null && state.shellKeepAliveTimeline != null) {
            state.shellKeepAliveTimeline.stop();
            state.shellKeepAliveTimeline = null;
        }
    }

    private void stopAllTerminalAgentShellKeepAlives() {
        for (TerminalAgentRunState state : terminalAgentRunStates.values()) {
            stopTerminalAgentShellKeepAlive(state);
        }
        terminalAgentRunStates.clear();
    }

    private void sendTerminalAgentShellKeepAlive(TerminalAgentRunState state) {
        SshTtyConnector sshConnector = state != null && state.runContext != null ? state.runContext.connector() : null;
        if (sshConnector == null) {
            stopTerminalAgentShellKeepAlive(state);
            return;
        }
        if (!sshConnector.isConnected()) {
            return;
        }
        Thread keepAliveThread = new Thread(() -> {
            try {
                sshConnector.sendShellKeepAliveBlankLine();
                logger.debug("Sent terminal-agent shell keepalive blank line");
            } catch (IOException e) {
                logger.debug("Failed to send terminal-agent shell keepalive: {}", e.getMessage());
            }
        }, "terminal-agent-shell-keepalive");
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }

    private javafx.scene.control.Menu createAiProfileMenu(
        String title,
        AiAction action,
        List<AiProfile> profiles,
        String selectedText) {
        javafx.scene.control.Menu actionMenu = new javafx.scene.control.Menu(title);
        for (AiProfile profile : profiles) {
            javafx.scene.control.MenuItem profileItem = new javafx.scene.control.MenuItem();
            javafx.scene.control.Label profileLabel = new javafx.scene.control.Label(buildAiProfileMenuLabel(profile));
            AiTokenWarningLevel warningLevel = AiTokenUsageManager.refreshUsage(profile).warningLevel();
            if (warningLevel == AiTokenWarningLevel.YELLOW) {
                profileLabel.setTextFill(Color.web("#b7791f"));
            } else if (warningLevel == AiTokenWarningLevel.RED) {
                profileLabel.setTextFill(Color.web("#c53030"));
            }
            profileItem.setGraphic(profileLabel);
            profileItem.setOnAction(e -> {
                if (aiSelectionHandler != null) {
                    aiSelectionHandler.handle(action, profile, selectedText);
                }
            });
            actionMenu.getItems().add(profileItem);
        }
        return actionMenu;
    }

    private List<AiProfile> getConfiguredAiProfiles() {
        try {
            GlobalSettings globalSettings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            if (globalSettings == null) {
                return Collections.emptyList();
            }
            List<AiProfile> profiles = new ArrayList<>();
            for (AiProfile profile : globalSettings.getAiProfiles()) {
                if (profile != null) {
                    profiles.add(new AiProfile(profile));
                }
            }
            return profiles;
        } catch (Exception e) {
            logger.warn("Could not load AI profiles for context menu", e);
            return Collections.emptyList();
        }
    }

    static boolean shouldShowAiContextMenu(List<AiProfile> profiles, boolean hasSelectedText, boolean hasAgentActions) {
        try {
            var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            if (gs != null && !gs.isAiFeaturesEnabled()) {
                return false;
            }
        } catch (Exception ignored) {
            // Fall through: if settings unavailable, keep previous visibility rules
        }
        if (profiles == null || profiles.isEmpty()) {
            return false;
        }
        return hasSelectedText || hasAgentActions;
    }

    private String getAiProfileDisplayName(@Nullable AiProfile profile) {
        if (profile == null || profile.getName() == null || profile.getName().isBlank()) {
            return I18n.get("settings.ai.profile.unnamed");
        }
        return profile.getName().trim();
    }

    private String buildAiProfileMenuLabel(@Nullable AiProfile profile) {
        if (profile == null) {
            return "";
        }
        var snapshot = AiTokenUsageManager.refreshUsage(profile);
        if (snapshot.unlimited()) {
            return getAiProfileDisplayName(profile);
        }
        return getAiProfileDisplayName(profile)
            + " ("
            + AiTokenUsageManager.formatCompact(snapshot.remainingTokens())
            + " "
            + I18n.get("settings.ai.token.remaining.short")
            + ")";
    }

    private @Nullable String getSelectedText(@NotNull SithTermFxWidget widget) {
        try {
            String selectedText = readSelectedTextDirectly(widget.getTerminalPanel());
            if (selectedText == null || selectedText.isBlank()) {
                selectedText = widget.getTerminalPanel().selectedTextProperty().get();
            }
            if (selectedText == null) {
                return null;
            }
            String trimmed = selectedText.trim();
            return trimmed.isEmpty() ? null : selectedText;
        } catch (Exception e) {
            logger.debug("Could not read selected terminal text: {}", e.getMessage());
            return null;
        }
    }

    private @Nullable String readSelectedTextDirectly(@NotNull com.sithtermfx.ui.TerminalPanel terminalPanel) {
        try {
            var method = terminalPanel.getClass().getDeclaredMethod("getSelectedText");
            method.setAccessible(true);
            Object value = method.invoke(terminalPanel);
            return value instanceof String str ? str : null;
        } catch (Exception e) {
            logger.debug("Could not read selected text directly from TerminalPanel: {}", e.getMessage());
            return null;
        }
    }

    private boolean isTerminalDragDropEnabled() {
        try {
            var gsm = KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm != null ? gsm.getSettings() : null;
            return gs != null && gs.isTerminalDragDropEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    /** MIME type used by TerminalSplitPane for internal pane-move DnD. */
    private static final String SPLIT_DRAG_MIME = "application/x-sithtermfx-terminal-widget";

    /** Check whether a dragboard carries an internal split-pane move payload. */
    private static boolean isSplitPaneDrag(javafx.scene.input.Dragboard db) {
        javafx.scene.input.DataFormat fmt = javafx.scene.input.DataFormat.lookupMimeType(SPLIT_DRAG_MIME);
        return fmt != null && db.hasContent(fmt);
    }

    private void setupDragDrop() {
        // Handlers on this BorderPane (used when drag is over empty area; rarely with terminal in center)
        setOnDragOver(event -> handleFileDragOver(event));
        setOnDragDropped(event -> handleFileDragDropped(event));
        // Capture-phase filters on split pane so we get file drops before cell filters; terminal content is in center so drag target is usually a cell node
        if (splitPane != null) {
            splitPane.addEventFilter(DragEvent.DRAG_OVER, event -> {
                if (handleFileDragOver(event)) event.consume();
            });
            splitPane.addEventFilter(DragEvent.DRAG_DROPPED, event -> {
                if (handleFileDragDropped(event)) event.consume();
            });
        }
    }

    /** Returns true if the event was handled (caller should consume). */
    private boolean handleFileDragOver(DragEvent event) {
        if (isSplitPaneDrag(event.getDragboard())) return false;
        if (!isTerminalDragDropEnabled()) return false;
        Dragboard db = event.getDragboard();
        if (!db.hasFiles()) return false;
        TtyConnector conn = getFocusedConnector();
        if (conn instanceof SshTtyConnector ssh && ssh.isConnected() && ssh.getSession() != null) {
            event.acceptTransferModes(TransferMode.COPY);
            return true;
        }
        return false;
    }

    /** Returns true if the event was handled (caller should consume). */
    private boolean handleFileDragDropped(DragEvent event) {
        if (isSplitPaneDrag(event.getDragboard())) return false;
        if (!isTerminalDragDropEnabled()) return false;
        Dragboard db = event.getDragboard();
        if (!db.hasFiles()) return false;
        TtyConnector conn = getFocusedConnector();
        if (!(conn instanceof SshTtyConnector ssh) || !ssh.isConnected() || ssh.getSession() == null) {
            event.setDropCompleted(false);
            return false;
        }
        List<java.io.File> dropped = db.getFiles();
        if (dropped.isEmpty()) {
            event.setDropCompleted(false);
            return false;
        }
        event.setDropCompleted(true);
        copyDroppedFilesToServer(ssh, dropped);
        return true;
    }

    private TtyConnector getFocusedConnector() {
        SithTermFxWidget focused = splitPane != null ? splitPane.getFocusedWidget() : null;
        return focused != null ? focused.getTtyConnector() : null;
    }

    public SshTtyConnector getActiveSshConnector() {
        TtyConnector focusedConnector = getFocusedConnector();
        if (focusedConnector instanceof SshTtyConnector sshConnector) {
            return sshConnector;
        }
        return ttyConnector instanceof SshTtyConnector sshConnector ? sshConnector : null;
    }

    private TtyConnector decorateTerminalConnector(SithTermFxWidget widget, TtyConnector connector) {
        if (connector == null) {
            return null;
        }
        installAgentShortcutInputInterceptor(widget, connector);
        return connector;
    }

    private void installAgentShortcutInputInterceptor(SithTermFxWidget widget, TtyConnector connector) {
        if (!(connector instanceof SshTtyConnector sshConnector)) {
            return;
        }
        sshConnector.addDataListener(getTerminalAgentPromptDataListener(sshConnector));
        AgentShortcutInputFilter inputFilter = new AgentShortcutInputFilter(
            widget,
            sshConnector.hasShellStartupCommandConfigured());
        sshConnector.setInputInterceptor(inputFilter::filter);
        logger.debug("Installed terminal AI SSH input interceptor (widgetBound={})", widget != null);
    }

    private SshTtyConnector.DataListener getTerminalAgentPromptDataListener(SshTtyConnector connector) {
        return terminalAgentPromptDataListeners.computeIfAbsent(
            connector,
            sourceConnector -> data -> recordAgentShortcutPromptSignal(sourceConnector, data));
    }

    private boolean usesTerminalConnector(TtyConnector candidate, TtyConnector expected) {
        return expected != null && candidate == expected;
    }

    private boolean shouldPreferRemoteAgentShortcut(TtyConnector connector) {
        return connector instanceof SshTtyConnector sshConnector
            && sshConnector.hasShellStartupCommandConfigured();
    }

    private void copyDroppedFilesToServer(SshTtyConnector sshConnector, List<java.io.File> dropped) {
        List<PathPair> toUpload = new ArrayList<>();
        for (java.io.File f : dropped) {
            collectFiles(f.toPath(), "", toUpload);
        }
        if (toUpload.isEmpty()) return;
        int total = toUpload.size();
        AtomicBoolean aborted = new AtomicBoolean(false);
        javafx.scene.control.ProgressBar progressBar = new javafx.scene.control.ProgressBar(0);
        progressBar.setPrefWidth(300);
        progressBar.setProgress(0);
        javafx.scene.control.Label statusLabel = new javafx.scene.control.Label(
            I18n.get("terminal.dragDrop.count", 0, total));
        javafx.scene.control.Button abortButton = new javafx.scene.control.Button(I18n.get("terminal.dragDrop.abort"));
        javafx.scene.layout.VBox vbox = new javafx.scene.layout.VBox(10,
            statusLabel, progressBar, abortButton);
        vbox.setPadding(new javafx.geometry.Insets(15));
        javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle(I18n.get("terminal.dragDrop.title"));
        dialog.getDialogPane().setContent(vbox);
        dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        dialog.setOnShown(e -> abortButton.requestFocus());
        abortButton.setOnAction(e -> {
            aborted.set(true);
            dialog.close();
        });
        Thread worker = new Thread(() -> {
            try {
                SftpClient sftp = SftpClientFactory.instance().createSftpClient(sshConnector.getSession());
                // Use the connector's tracked working directory and only fall back to the SFTP start directory.
                String remoteTargetDir = sshConnector.getCurrentRemoteDirectory();
                if (remoteTargetDir == null || remoteTargetDir.isEmpty()) {
                    try {
                        remoteTargetDir = sftp.canonicalPath(".");
                    } catch (Exception e) {
                        logger.debug("Could not resolve remote cwd, using '.'");
                        remoteTargetDir = ".";
                    }
                }
                if (remoteTargetDir == null || remoteTargetDir.isEmpty()) remoteTargetDir = ".";
                final String remoteHome = remoteTargetDir;
                int copied = 0;
                for (int i = 0; i < toUpload.size() && !aborted.get(); i++) {
                    PathPair p = toUpload.get(i);
                    String fullRemote = (remoteHome.endsWith("/") ? remoteHome : remoteHome + "/") + p.remote;
                    uploadOne(sftp, p, fullRemote);
                    if (aborted.get()) break;
                    copied++;
                    final int done = copied;
                    Platform.runLater(() -> {
                        statusLabel.setText(I18n.get("terminal.dragDrop.count", done, total));
                        progressBar.setProgress((double) done / total);
                    });
                }
                if (!aborted.get()) {
                    Platform.runLater(() -> {
                        statusLabel.setText(I18n.get("terminal.dragDrop.done"));
                        progressBar.setProgress(1.0);
                        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.2));
                        pause.setOnFinished(e -> dialog.close());
                        pause.play();
                    });
                }
            } catch (Exception ex) {
                logger.warn("Drag-drop copy failed", ex);
                String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                Platform.runLater(() -> {
                    statusLabel.setText(I18n.get("terminal.dragDrop.error", msg));
                });
            }
        }, "TerminalDragDrop");
        worker.setDaemon(true);
        worker.start();
        dialog.show();
    }

    private static class PathPair {
        final Path local;
        final String remote;
        final boolean isDir;

        PathPair(Path local, String remote, boolean isDir) {
            this.local = local;
            this.remote = remote;
            this.isDir = isDir;
        }
    }

    private void collectFiles(Path local, String remoteDir, List<PathPair> out) {
        if (Files.isRegularFile(local)) {
            String name = local.getFileName().toString();
            String remote = remoteDir.isEmpty() ? name : remoteDir + "/" + name;
            out.add(new PathPair(local, remote, false));
        } else if (Files.isDirectory(local)) {
            String dirName = local.getFileName().toString();
            String subRemote = remoteDir.isEmpty() ? dirName : remoteDir + "/" + dirName;
            out.add(new PathPair(local, subRemote, true));
            try {
                try (var stream = Files.list(local)) {
                    for (Path child : stream.toList()) {
                        collectFiles(child, subRemote, out);
                    }
                }
            } catch (Exception e) {
                logger.debug("List dir failed: {}", e.getMessage());
            }
        }
    }

    private void uploadOne(SftpClient sftp, PathPair p, String fullRemotePath) throws java.io.IOException {
        if (p.isDir) {
            try {
                sftp.mkdir(fullRemotePath);
            } catch (Exception e) {
                // may already exist
            }
            return;
        }
        try (InputStream in = Files.newInputStream(p.local);
             OutputStream out = sftp.write(fullRemotePath, java.util.EnumSet.of(
                 SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }
    
    /**
     * Updates the font rendering for all terminal widgets when font size changes.
     * Calls reinitFontAndResize() on each TerminalPanel via reflection since it's protected.
     */
    private void updateAllTerminalFonts() {
        if (splitPane == null) return;
        
        Platform.runLater(() -> {
            for (SithTermFxWidget widget : splitPane.getAllWidgets()) {
                try {
                    var terminalPanel = widget.getTerminalPanel();
                    var method = terminalPanel.getClass().getDeclaredMethod("reinitFontAndResize");
                    method.setAccessible(true);
                    method.invoke(terminalPanel);
                } catch (Exception e) {
                    logger.warn("Failed to update font for terminal widget: {}", e.getMessage());
                }
            }
        });
    }
    
    /**
     * Sets the callback for requesting new connections (for "Split with new connection" feature).
     */
    public void setNewConnectionCallback(NewConnectionCallback callback) {
        this.newConnectionCallback = callback;
    }
    
    /**
     * Creates a new SSH TtyConnector for a split terminal.
     * Each split gets its own independent SSH session to the same server.
     * For the initial terminal (request == null), returns null - connection is made later via connect().
     */
    private @Nullable TtyConnector createSplitConnector(@Nullable SplitRequest request) {
        // Initial terminal: return null, will be connected later via connect()
        if (request == null) {
            return null;
        }
        
        // Handle NEW_CONNECTION mode - ask user for a new connection
        if (request.getSplitMode() == SplitRequest.SplitMode.NEW_CONNECTION) {
            return createNewConnectionForSplit();
        }
        
        // SAME_SERVER_NEW_SHELL mode - create new SSH session to same server
        return createSameServerConnection();
    }

    private TtyConnector createConnectorForConnection(ServerConnection targetConnection, String targetPassword) {
        TtyConnector connector;
        if (targetConnection.getProtocol() == ConnectionProtocol.MOSH) {
            if (Mosh4jTtyConnector.isReleaseSupported()) {
                Mosh4jTtyConnector mosh4jConnector = new Mosh4jTtyConnector(targetConnection, targetPassword);
                de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
                if (app != null && app.getSSHKeyManager() != null) {
                    mosh4jConnector.setSSHKeyManager(
                            app.getSSHKeyManager(),
                            app.getMasterPasswordManager().getMasterPassword()
                    );
                }
                connector = mosh4jConnector;
                return connector;
            }
            throw new IllegalStateException(
                    I18n.get("mosh.mosh4j.releaseUnavailable", de.kortty.core.Mosh4jTtyConnector.getMosh4jReleaseTag()));
        } else if (targetConnection.getProtocol() == ConnectionProtocol.MOSH_CLIENT) {
            if (!NativeMoshTtyConnector.isNativeMoshAvailable()) {
                throw new IllegalStateException(I18n.get("mosh.native.binaryMissingInstallHint"));
            }
            NativeMoshTtyConnector nativeMosh = new NativeMoshTtyConnector(targetConnection, targetPassword);
            de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
            if (app != null && app.getSSHKeyManager() != null) {
                nativeMosh.setSSHKeyManager(
                        app.getSSHKeyManager(),
                        app.getMasterPasswordManager().getMasterPassword()
                );
            }
            connector = nativeMosh;
        } else {
            SshTtyConnector sshConnector = new SshTtyConnector(targetConnection, targetPassword);
            if (targetConnection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY) {
                de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
                if (app != null && app.getSSHKeyManager() != null) {
                    sshConnector.setSSHKeyManager(
                            app.getSSHKeyManager(),
                            app.getMasterPasswordManager().getMasterPassword()
                    );
                }
            }
            if (isTerminalAgentShortcutEnabled()) {
                sshConnector.setShellStartupCommand(buildTerminalAgentShellStartupCommand(getTerminalAgentCommandName()));
            }
            installAgentShortcutInputInterceptor(null, sshConnector);
            connector = sshConnector;
        }
        return connector;
    }

    private boolean connectConnector(TtyConnector connector) throws Exception {
        if (connector instanceof Mosh4jTtyConnector mosh4jConnector) {
            return mosh4jConnector.connect();
        }
        if (connector instanceof NativeMoshTtyConnector nativeMoshConnector) {
            return nativeMoshConnector.connect();
        }
        if (connector instanceof SshTtyConnector sshConnector) {
            return sshConnector.connect();
        }
        throw new IllegalStateException("Unsupported connector type: " + connector.getClass().getName());
    }

    private void setConnectorDisconnectListener(TtyConnector connector, DisconnectListener listener) {
        if (connector instanceof Mosh4jTtyConnector mosh4jConnector) {
            mosh4jConnector.setDisconnectListener(listener);
            mosh4jConnector.setOnRecoveredCallback(() ->
                    Platform.runLater(this::requestTerminalFocusAfterRecovery));
            mosh4jConnector.setOnInterruptedCallback(() ->
                    Platform.runLater(() -> {
                        if (onMoshInterruptedCallback != null) {
                            onMoshInterruptedCallback.run();
                        }
                    }));
            return;
        }
        if (connector instanceof NativeMoshTtyConnector nativeMoshConnector) {
            nativeMoshConnector.setDisconnectListener(listener);
            return;
        }
        if (connector instanceof SshTtyConnector sshConnector) {
            sshConnector.setDisconnectListener(listener);
        }
    }

    /**
     * Called after mosh recovers from a network interruption. Re-applies the connector to the
     * terminal widget(s) that use it so the write path is re-bound (Ctrl+C and other keys work again).
     */
    private void requestTerminalFocusAfterRecovery() {
        if (ttyConnector == null) {
            return;
        }
        // Re-set connector on the focused widget if it uses this connector (e.g. single tab or focused split).
        SithTermFxWidget focused = splitPane != null ? splitPane.getFocusedWidget() : terminalWidget;
        if (focused != null && usesTerminalConnector(focused.getTtyConnector(), ttyConnector)) {
            focused.setTtyConnector(decorateTerminalConnector(focused, ttyConnector));
            getPrimaryKeyEventTarget(focused).requestFocus();
            return;
        }
        // If there are multiple widgets (splits), re-apply to any widget that uses this connector.
        if (splitPane != null) {
            for (SithTermFxWidget w : splitPane.getAllWidgets()) {
                if (usesTerminalConnector(w.getTtyConnector(), ttyConnector)) {
                    w.setTtyConnector(decorateTerminalConnector(w, ttyConnector));
                }
            }
            if (focused != null) {
                getPrimaryKeyEventTarget(focused).requestFocus();
            }
        }
    }
    
    /**
     * Creates a new SSH connection to the same server (for same-server splits).
     * Runs connect() in a background thread so the JavaFX thread stays responsive for
     * keyboard-interactive auth dialogs (e.g. CyberArk "reason for operation").
     * Ensures Stage/Label/ProgressIndicator and showAndWait() run on the FX Application Thread.
     */
    private @Nullable TtyConnector createSameServerConnection() {
        if (Platform.isFxApplicationThread()) {
            return doCreateSameServerConnection();
        }
        final TtyConnector[] result = new TtyConnector[1];
        try {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            Platform.runLater(() -> {
                result[0] = doCreateSameServerConnection();
                latch.countDown();
            });
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result[0];
    }
    
    /**
     * Must be called on the JavaFX Application Thread. Creates UI (Stage, progress dialog),
     * starts connect() in a background thread, shows the dialog and waits for completion.
     */
    private @Nullable TtyConnector doCreateSameServerConnection() {
        try {
            logger.info("Creating new SSH connection for split to {}@{}:{}",
                    connection.getUsername(), connection.getHost(), connection.getPort());
            
            // Check if using temporary SSH key and if it's still valid
            if (temporarySSHKey != null) {
                if (!temporarySSHKey.isValid()) {
                    logger.error("Temporary SSH key has expired - cannot create split connection");
                    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.ERROR);
                    alert.setTitle(I18n.get("error.title"));
                    alert.setHeaderText(I18n.get("sftp.tempKeyExpired"));
                    alert.setContentText(I18n.get("split.tempKeyExpiredMessage"));
                    alert.showAndWait();
                    return null;
                }
                logger.debug("Using temporary SSH key for split (valid for {} more seconds)", 
                    temporarySSHKey.getRemainingSeconds());
            }
            
            TtyConnector newConnector = createConnectorForConnection(connection, password);
            
            // Run connect() in background thread so JavaFX can show keyboard-interactive dialogs
            AtomicReference<Boolean> connectSuccess = new AtomicReference<>(false);
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            Stage connectingStage = new Stage(StageStyle.UTILITY);
            connectingStage.initModality(Modality.APPLICATION_MODAL);
            connectingStage.setTitle(I18n.get("split.connecting"));
            javafx.scene.control.Label label = new javafx.scene.control.Label(I18n.get("split.connecting"));
            ProgressIndicator progress = new ProgressIndicator(-1);
            VBox root = new VBox(15, progress, label);
            root.setStyle("-fx-padding: 20; -fx-alignment: center;");
            connectingStage.setScene(new Scene(root));
            connectingStage.setResizable(false);
            
            Thread connectThread = new Thread(() -> {
                try {
                    boolean ok = connectConnector(newConnector);
                    connectSuccess.set(ok);
                } catch (Throwable t) {
                    logger.error("Split connect error: {}", t.getMessage(), t);
                    connectSuccess.set(false);
                } finally {
                    done.countDown();
                    Platform.runLater(() -> connectingStage.close());
                }
            }, "SSH-Split-Connect");
            connectThread.setDaemon(true);
            connectThread.start();
            
            connectingStage.showAndWait();
            try {
                done.await(2, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            
            if (!Boolean.TRUE.equals(connectSuccess.get())) {
                logger.error("Failed to connect split SSH session");
                return null;
            }
            return newConnector;
        } catch (Exception e) {
            logger.error("Failed to create split SSH connection: {}", e.getMessage(), e);
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.ERROR);
            alert.setTitle(I18n.get("error.title"));
            alert.setHeaderText(I18n.get("split.connectionFailed"));
            alert.setContentText(e.getMessage());
            alert.showAndWait();
            return null;
        }
    }
    
    /**
     * Sets the temporary SSH key for this terminal view.
     * Used for split connections that need to reuse the same temporary key.
     */
    public void setTemporarySSHKey(de.kortty.model.TemporarySSHKey temporarySSHKey) {
        this.temporarySSHKey = temporarySSHKey;
    }
    
    /**
     * Gets the temporary SSH key if this terminal was connected with one.
     */
    public de.kortty.model.TemporarySSHKey getTemporarySSHKey() {
        return temporarySSHKey;
    }
    
    /**
     * Creates a new SSH connection to a different server (via QuickConnect dialog).
     */
    private @Nullable TtyConnector createNewConnectionForSplit() {
        if (newConnectionCallback == null) {
            logger.warn("No new connection callback set - cannot create new connection for split");
            return null;
        }
        
        // Call the callback on JavaFX thread and wait for result
        final TtyConnector[] result = new TtyConnector[1];
        if (Platform.isFxApplicationThread()) {
            result[0] = doCreateNewConnectionForSplit();
        } else {
            try {
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                Platform.runLater(() -> {
                    result[0] = doCreateNewConnectionForSplit();
                    latch.countDown();
                });
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return result[0];
    }
    
    private @Nullable TtyConnector doCreateNewConnectionForSplit() {
        ConnectionResult connResult = newConnectionCallback.requestNewConnection();
        if (connResult == null) {
            logger.info("User cancelled new connection for split");
            return null;
        }

        TtyConnector newConnector = null;
        try {
            logger.info("Creating new SSH connection for split to {}@{}:{}",
                    connResult.connection.getUsername(),
                    connResult.connection.getHost(),
                    connResult.connection.getPort());

            newConnector = createConnectorForConnection(connResult.connection, connResult.password);

            // Connect the new session
            boolean connected = connectConnector(newConnector);
            if (!connected) {
                logger.warn("Split new-connection failed for {}@{}:{}",
                        connResult.connection.getUsername(),
                        connResult.connection.getHost(),
                        connResult.connection.getPort());
                showSplitConnectionErrorDialog(
                        connResult,
                        I18n.get("split.connectionNoDetails"));
                try {
                    newConnector.close();
                } catch (Exception ignored) {
                }
                return null;
            }
            return newConnector;
        } catch (Exception e) {
            logger.error("Failed to create new connection for split: {}", e.getMessage(), e);
            if (newConnector != null) {
                try {
                    newConnector.close();
                } catch (Exception closeError) {
                    logger.debug("Failed to close split new-connection connector after error: {}", closeError.getMessage());
                }
            }
            String errorDetail = e.getMessage();
            if (errorDetail == null || errorDetail.isBlank()) {
                errorDetail = e.getClass().getSimpleName();
            }
            showSplitConnectionErrorDialog(connResult, errorDetail);
            return null;
        }
    }

    private void showSplitConnectionErrorDialog(ConnectionResult connResult, String detail) {
        String identity = connResult.connection.getUsername() + "@"
                + connResult.connection.getHost() + ":"
                + connResult.connection.getPort();
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.ERROR);
        alert.setTitle(I18n.get("error.title"));
        alert.setHeaderText(I18n.get("split.connectionFailed"));
        alert.setContentText(I18n.get("split.connectionFailedWithDetails", identity, detail));
        alert.showAndWait();
    }
    
    /**
     * Sets up event handlers for a terminal widget (used for each widget in split).
     */
    private void applyThemeAtRuntime(Theme theme) {
        if (theme == null || settings == null) return;
        boolean includeFont = isThemeFontApplyEnabled();
        theme.applyTo(settings, includeFont);
        ConnectionSettings connSettings = connection.getSettings();
        if (connSettings != null) {
            theme.applyTo(connSettings, includeFont);
            connSettings.setThemeId(theme.getId());
        }
        if (splitPane != null) {
            for (SithTermFxWidget w : splitPane.getAllWidgets()) {
                applyStyleStateColors(w);
                applyCursorShape(w);
                setCursorVisible(w, true);
            }
        }
        applyTerminalAgentActivityTheme(theme);
        updateAllTerminalFonts();
    }

    private void applyStyleStateColors(SithTermFxWidget widget) {
        if (widget == null || settings == null) return;
        var terminal = widget.getTerminal();
        if (!(terminal instanceof SithTerminal sithTerminal)) return;
        var styleState = sithTerminal.getStyleState();
        Color fg;
        Color bg;
        try {
            fg = Color.web(settings.getForegroundColor());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid foreground color '{}', using white", settings.getForegroundColor(), e);
            fg = Color.WHITE;
        }
        try {
            bg = Color.web(settings.getBackgroundColor());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid background color '{}', using black", settings.getBackgroundColor(), e);
            bg = Color.BLACK;
        }
        TerminalColor fgTc = TerminalColor.rgb(
                (int) (fg.getRed() * 255),
                (int) (fg.getGreen() * 255),
                (int) (fg.getBlue() * 255));
        TerminalColor bgTc = TerminalColor.rgb(
                (int) (bg.getRed() * 255),
                (int) (bg.getGreen() * 255),
                (int) (bg.getBlue() * 255));
        TextStyle newStyle = new TextStyle(fgTc, bgTc);
        styleState.setDefaultStyle(newStyle);
        styleState.reset();
        Platform.runLater(() -> widget.getTerminalPanel().repaint());
    }

    private void applyCursorShape(SithTermFxWidget widget) {
        if (widget == null || settings == null) return;
        String style = settings.getCursorStyle();
        if (style == null || style.isEmpty()) return;
        String styleUpper = style.toUpperCase();
        try {
            CursorShape shape = CursorShape.valueOf(styleUpper);
            widget.getTerminalPanel().setCursorShape(shape);
        } catch (IllegalArgumentException e) {
            logger.debug("Cursor shape not supported: {}", styleUpper);
        }
    }

    private void setCursorVisible(SithTermFxWidget widget, boolean visible) {
        if (widget == null) return;
        var terminal = widget.getTerminal();
        if (terminal != null) {
            terminal.setCursorVisible(visible);
        }
    }

    /**
     * Shows or hides the cursor across all terminal widgets (primary + splits).
     * Call from JavaFX Application Thread.
     */
    public void setAllCursorsVisible(boolean visible) {
        if (splitPane != null) {
            for (SithTermFxWidget w : splitPane.getAllWidgets()) {
                setCursorVisible(w, visible);
            }
        } else {
            setCursorVisible(terminalWidget, visible);
        }
    }

    private void setupWidgetEventHandlers(SithTermFxWidget widget) {
        installAgentShortcutEventDispatcher(widget);
        Node keyEventTarget = getPrimaryKeyEventTarget(widget);
        javafx.event.EventHandler<KeyEvent> keyPressedHandler = event -> {
            if (event.isConsumed()) {
                return;
            }
            if (handleTerminalAgentInputLock(widget, event)) {
                return;
            }
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                TtyConnector connector = widget.getTtyConnector();
                if (connector != null && connector.isConnected()) {
                    try {
                        connector.write("\u001B");
                    } catch (java.io.IOException e) {
                        logger.error("Failed to send ESCAPE character", e);
                    }
                } else if (widget.getTerminal() != null) {
                    widget.getTerminal().writeCharacters("\u001B");
                }
                event.consume();
                return;
            }
        };
        keyEventTarget.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, keyPressedHandler);

        javafx.event.EventHandler<KeyEvent> keyTypedHandler = event -> {
            if (event.isConsumed()) {
                return;
            }
            if (isTerminalAgentInputLockedFor(widget)) {
                event.consume();
            }
        };
        keyEventTarget.addEventFilter(javafx.scene.input.KeyEvent.KEY_TYPED, keyTypedHandler);

        // Navigation keys (arrow, Tab, etc.) are handled at split-pane level so we run before the
        // terminal widget consumes them; see splitPane.addEventFilter(KeyEvent.KEY_PRESSED, ...) above.

        // Copy-on-select: when user finishes selecting text, copy to clipboard if enabled
        var panel = widget.getTerminalPanel();
        if (panel != null) {
            panel.selectedTextProperty().addListener((obs, oldVal, newVal) -> {
                if (isTerminalCopyOnSelectEnabled() && newVal != null && !newVal.isEmpty()) {
                    panel.handleCopy(false, false);
                }
            });
        }
    }

    private void installAgentShortcutEventDispatcher(SithTermFxWidget widget) {
        if (widget == null || widget.getTerminalPanel() == null || widget.getTerminalPanel().getCanvas() == null) {
            return;
        }
        Node canvas = widget.getTerminalPanel().getCanvas();
        if (Boolean.TRUE.equals(canvas.getProperties().get(AGENT_SHORTCUT_DISPATCHER_INSTALLED_KEY))) {
            return;
        }
        javafx.event.EventDispatcher originalDispatcher = canvas.getEventDispatcher();
        canvas.setEventDispatcher((event, tail) -> {
            if (event instanceof KeyEvent keyEvent) {
                if (keyEvent.isConsumed()) {
                    return keyEvent;
                }
                if (keyEvent.getEventType() == KeyEvent.KEY_PRESSED) {
                    if (handleTerminalAgentInputLock(widget, keyEvent)) {
                        return keyEvent;
                    }
                } else if (keyEvent.getEventType() == KeyEvent.KEY_TYPED) {
                    if (isTerminalAgentInputLockedFor(widget)) {
                        keyEvent.consume();
                        return keyEvent;
                    }
                    handleAgentShortcutKeyTyped(widget, keyEvent);
                }
            }
            return originalDispatcher.dispatchEvent(event, tail);
        });
        canvas.getProperties().put(AGENT_SHORTCUT_DISPATCHER_INSTALLED_KEY, Boolean.TRUE);
        logger.debug("Installed terminal AI canvas event dispatcher");
    }

    private @Nullable SithTermFxWidget resolveWidgetForKeyEvent(@Nullable KeyEvent event) {
        if (event != null && event.getTarget() instanceof Node targetNode) {
            SithTermFxWidget widget = findWidgetContainingNode(targetNode);
            if (widget != null) {
                return widget;
            }
        }
        return splitPane != null ? splitPane.getFocusedWidget() : terminalWidget;
    }

    private @Nullable SithTermFxWidget findWidgetContainingNode(@Nullable Node targetNode) {
        if (targetNode == null || splitPane == null) {
            return null;
        }
        for (SithTermFxWidget widget : splitPane.getAllWidgets()) {
            if (isNodeWithin(targetNode, widget.getPane())) {
                return widget;
            }
            Node focusTarget = getPrimaryKeyEventTarget(widget);
            if (focusTarget != widget.getPane() && isNodeWithin(targetNode, focusTarget)) {
                return widget;
            }
            if (widget.getTerminalPanel() != null
                && isNodeWithin(targetNode, widget.getTerminalPanel().getCanvas())) {
                return widget;
            }
        }
        return null;
    }

    private boolean isNodeWithin(@Nullable Node targetNode, @Nullable Node ancestorNode) {
        Node current = targetNode;
        while (current != null && ancestorNode != null) {
            if (current == ancestorNode) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean handleTerminalAgentInputLock(@Nullable SithTermFxWidget widget, KeyEvent event) {
        if (event == null || event.isConsumed()) {
            return event != null && event.isConsumed();
        }
        if (!isTerminalAgentInputLockedFor(widget)) {
            return false;
        }
        TerminalAgentRunState state = widget != null ? terminalAgentRunStates.get(widget) : null;
        if (event.getEventType() == KeyEvent.KEY_PRESSED) {
            if (isAgentInputCancelShortcut(event.getCode(), event.isControlDown(), event.isAltDown(), event.isMetaDown())) {
                Runnable handler = state != null ? state.cancelHandler : null;
                if (handler != null) {
                    Platform.runLater(handler);
                }
            } else if (event.getCode() == KeyCode.R && event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
                Runnable handler = state != null ? state.toggleDetailsHandler : null;
                if (handler != null) {
                    Platform.runLater(handler);
                }
            }
        }
        event.consume();
        return true;
    }

    private boolean isTerminalAgentInputLockedFor(@Nullable SithTermFxWidget widget) {
        return widget != null && terminalAgentRunStates.containsKey(widget);
    }

    static boolean isAgentInputCancelShortcut(KeyCode code, boolean controlDown, boolean altDown, boolean metaDown) {
        return code == KeyCode.ESCAPE || (code == KeyCode.C && controlDown && !altDown && !metaDown);
    }

    private void handleAgentShortcutKeyPressed(SithTermFxWidget widget, KeyEvent event) {
        if (event == null || event.isConsumed()) {
            return;
        }
        if (!isTerminalAgentShortcutEnabled() || terminalAgentShortcutHandler == null) {
            return;
        }

        StringBuilder buffer = agentShortcutBuffers.computeIfAbsent(widget, ignored -> new StringBuilder());
        if (event.getCode() == KeyCode.BACK_SPACE) {
            if (!buffer.isEmpty()) {
                buffer.setLength(buffer.length() - 1);
            }
            return;
        }
        if (event.getCode() == KeyCode.DELETE) {
            return;
        }
        if (event.getCode() == KeyCode.U && event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
            buffer.setLength(0);
            return;
        }
        if (event.getCode() != KeyCode.ENTER) {
            return;
        }

        String rawCommand = resolveAgentShortcutCommand(widget, buffer.toString());
        buffer.setLength(0);
        agentShortcutPromptReady = false;
        String commandName = getTerminalAgentCommandName();
        boolean caseInsensitiveCommandName = isTerminalAgentCommandNameCaseInsensitive();
        if (!canInterceptBufferedAgentShortcut(rawCommand, commandName, caseInsensitiveCommandName)) {
            return;
        }

        TtyConnector connector = widget.getTtyConnector();
        if (connector == null || !connector.isConnected()) {
            return;
        }
        if (shouldLetRemoteShellHandleAgentShortcut(
            shouldPreferRemoteAgentShortcut(connector),
            rawCommand,
            commandName,
            caseInsensitiveCommandName)) {
            return;
        }
        logger.debug("Intercepting terminal AI shortcut from key event before shell execution");

        try {
            connector.write("\u0015");
        } catch (Exception e) {
            logger.debug("Failed to clear terminal line before AI shortcut interception: {}", e.getMessage());
        }
        event.consume();
        TerminalAgentRunContext runContext = createTerminalAgentRunContext(widget);
        Platform.runLater(() -> {
            TerminalAgentShortcutHandler handler = terminalAgentShortcutHandler;
            if (handler != null) {
                handler.handle(rawCommand, runContext);
            }
        });
    }

    private String resolveAgentShortcutCommand(SithTermFxWidget widget, String bufferedCommand) {
        String trimmed = bufferedCommand != null ? bufferedCommand.trim() : "";
        String commandName = getTerminalAgentCommandName();
        boolean caseInsensitiveCommandName = isTerminalAgentCommandNameCaseInsensitive();
        if (TerminalAgentCommandSupport.parseShortcut(trimmed, commandName, caseInsensitiveCommandName) != null) {
            return trimmed;
        }
        try {
            String screenLines = widget != null && widget.getTerminalTextBuffer() != null
                ? widget.getTerminalTextBuffer().getScreenLines()
                : "";
            String lastVisibleLine = extractLastVisibleLine(screenLines);
            String visibleCommand = extractAgentShortcutFromVisibleLine(
                lastVisibleLine,
                commandName,
                caseInsensitiveCommandName);
            if (visibleCommand != null) {
                return visibleCommand;
            }
        } catch (Exception e) {
            logger.debug("Failed to resolve visible AI shortcut command: {}", e.getMessage());
        }
        return trimmed;
    }

    private void handleAgentShortcutKeyTyped(SithTermFxWidget widget, KeyEvent event) {
        if (event == null || event.isConsumed()) {
            return;
        }
        if (!isTerminalAgentShortcutEnabled() || terminalAgentShortcutHandler == null) {
            return;
        }
        String character = event.getCharacter();
        if (character == null || character.isEmpty()) {
            return;
        }
        char first = character.charAt(0);
        if (first == '\r' || first == '\n' || first == '\b' || first == 127) {
            return;
        }
        if (first < 32) {
            return;
        }
        agentShortcutBuffers.computeIfAbsent(widget, ignored -> new StringBuilder()).append(character);
    }

    private boolean canInterceptAgentShortcut(String rawCommand) {
        return canInterceptAgentShortcut(rawCommand, agentShortcutPromptReady);
    }

    private boolean canInterceptAgentShortcut(String rawCommand, boolean promptReady) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return false;
        }
        String commandName = getTerminalAgentCommandName();
        return canInterceptAgentShortcut(
            rawCommand,
            promptReady,
            commandName,
            isTerminalAgentCommandNameCaseInsensitive());
    }

    static boolean canInterceptAgentShortcut(String rawCommand, boolean promptReady, String commandName) {
        return canInterceptAgentShortcut(rawCommand, promptReady, commandName, false);
    }

    static boolean canInterceptAgentShortcut(
        String rawCommand,
        boolean promptReady,
        String commandName,
        boolean caseInsensitiveCommandName) {
        if (rawCommand == null || rawCommand.isBlank() || !promptReady) {
            return false;
        }
        return canInterceptBufferedAgentShortcut(rawCommand, commandName, caseInsensitiveCommandName);
    }

    static boolean canInterceptBufferedAgentShortcut(String rawCommand, String commandName) {
        return canInterceptBufferedAgentShortcut(rawCommand, commandName, false);
    }

    static boolean canInterceptBufferedAgentShortcut(
        String rawCommand,
        String commandName,
        boolean caseInsensitiveCommandName) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return false;
        }
        return TerminalAgentCommandSupport.parseShortcut(rawCommand, commandName, caseInsensitiveCommandName) != null;
    }

    static boolean shouldLetRemoteShellHandleAgentShortcut(
        boolean remoteShortcutConfigured,
        String rawCommand,
        String commandName) {
        return shouldLetRemoteShellHandleAgentShortcut(remoteShortcutConfigured, rawCommand, commandName, false);
    }

    static boolean shouldLetRemoteShellHandleAgentShortcut(
        boolean remoteShortcutConfigured,
        String rawCommand,
        String commandName,
        boolean caseInsensitiveCommandName) {
        if (!remoteShortcutConfigured || !canInterceptBufferedAgentShortcut(rawCommand, commandName, caseInsensitiveCommandName)) {
            return false;
        }
        return !caseInsensitiveCommandName || canInterceptBufferedAgentShortcut(rawCommand, commandName, false);
    }

    private boolean isTerminalAgentPromptHookEnabled() {
        try {
            var gsm = KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm != null ? gsm.getSettings() : null;
            return gs == null || gs.isDefaultPromptHookEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    private boolean isTerminalAgentShortcutEnabled() {
        try {
            var gsm = KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm != null ? gsm.getSettings() : null;
            return gs == null || gs.isAiFeaturesEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    private String getTerminalAgentCommandName() {
        try {
            var gsm = KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm != null ? gsm.getSettings() : null;
            return TerminalAgentCommandSupport.normalizeCommandName(
                gs != null ? gs.getTerminalAgentCommandName() : null);
        } catch (Exception e) {
            return TerminalAgentCommandSupport.DEFAULT_COMMAND_NAME;
        }
    }

    private boolean isTerminalAgentCommandNameCaseInsensitive() {
        try {
            var gsm = KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm != null ? gsm.getSettings() : null;
            return gs != null && gs.isTerminalAgentCommandNameCaseInsensitive();
        } catch (Exception e) {
            return false;
        }
    }

    private void recordAgentShortcutPromptSignal(SshTtyConnector sourceConnector, String data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        processTerminalAgentOscSignal(sourceConnector, data);
        if (data.contains("\u001B]133;A") || data.contains("\u001B]133;B")) {
            agentShortcutPromptReady = true;
        }
        if (data.contains("\u001B]133;C")) {
            agentShortcutPromptReady = false;
        }

        synchronized (agentShortcutPromptTail) {
            agentShortcutPromptTail.append(data);
            int overflow = agentShortcutPromptTail.length() - 2048;
            if (overflow > 0) {
                agentShortcutPromptTail.delete(0, overflow);
            }
            String lastLine = extractLastVisibleLine(agentShortcutPromptTail.toString());
            if (!lastLine.isBlank()) {
                if (looksLikeShellPrompt(lastLine)) {
                    agentShortcutPromptReady = true;
                } else if (shouldResetPromptReady(lastLine, hasPendingAgentShortcutInput())) {
                    agentShortcutPromptReady = false;
                }
            }
        }
    }

    private void processTerminalAgentOscSignal(SshTtyConnector sourceConnector, String data) {
        StringBuilder terminalAgentOscBuffer = terminalAgentOscBuffers.computeIfAbsent(
            sourceConnector,
            ignored -> new StringBuilder());
        synchronized (terminalAgentOscBuffer) {
            terminalAgentOscBuffer.append(data);
            while (true) {
                String prefix = "\u001B]777;korTTY-agent;";
                int start = terminalAgentOscBuffer.indexOf(prefix);
                if (start < 0) {
                    trimTerminalAgentOscBuffer(terminalAgentOscBuffer);
                    return;
                }
                int bellEnd = terminalAgentOscBuffer.indexOf("\u0007", start);
                int stEnd = terminalAgentOscBuffer.indexOf("\u001B\\", start);
                int end = -1;
                int terminatorLength = 0;
                if (bellEnd >= 0 && (stEnd < 0 || bellEnd < stEnd)) {
                    end = bellEnd;
                    terminatorLength = 1;
                } else if (stEnd >= 0) {
                    end = stEnd;
                    terminatorLength = 2;
                }
                if (end < 0) {
                    if (start > 0) {
                        terminalAgentOscBuffer.delete(0, start);
                    }
                    trimTerminalAgentOscBuffer(terminalAgentOscBuffer);
                    return;
                }
                String payload = terminalAgentOscBuffer.substring(start + prefix.length(), end);
                terminalAgentOscBuffer.delete(0, end + terminatorLength);
                dispatchTerminalAgentOscPayload(sourceConnector, payload);
            }
        }
    }

    private void trimTerminalAgentOscBuffer(StringBuilder terminalAgentOscBuffer) {
        int maxLength = 4096;
        if (terminalAgentOscBuffer.length() > maxLength) {
            terminalAgentOscBuffer.delete(0, terminalAgentOscBuffer.length() - maxLength);
        }
    }

    private void dispatchTerminalAgentOscPayload(SshTtyConnector sourceConnector, String payload) {
        int separator = payload.indexOf(';');
        if (separator <= 0 || separator >= payload.length() - 1) {
            logger.debug("Ignoring malformed terminal AI OSC payload");
            return;
        }
        String kind = payload.substring(0, separator);
        int cwdSeparator = payload.indexOf(';', separator + 1);
        String encodedPrompt = cwdSeparator > separator
            ? payload.substring(cwdSeparator + 1)
            : payload.substring(separator + 1);
        String rawCommand = buildTerminalAgentRawCommandFromOscPayload(kind, encodedPrompt, getTerminalAgentCommandName());
        if (rawCommand == null) {
            logger.debug("Ignoring unsupported terminal AI OSC payload kind='{}'", kind);
            return;
        }
        logger.debug("Received terminal AI OSC shortcut kind='{}'", kind);
        TerminalAgentRunContext runContext = createTerminalAgentRunContext(sourceConnector);
        Platform.runLater(() -> {
            TerminalAgentShortcutHandler handler = terminalAgentShortcutHandler;
            if (handler != null) {
                handler.handle(rawCommand, runContext);
            }
        });
    }

    private boolean hasPendingAgentShortcutInput() {
        return agentShortcutBuffers.values().stream()
            .anyMatch(buffer -> buffer != null && !buffer.isEmpty());
    }

    private Node getPrimaryKeyEventTarget(SithTermFxWidget widget) {
        if (widget.getTerminalPanel() != null && widget.getTerminalPanel().getCanvas() != null) {
            return widget.getTerminalPanel().getCanvas();
        }
        Node preferred = widget.getPreferredFocusableNode();
        return preferred != null ? preferred : widget.getPane();
    }

    private boolean hasVisiblePromptForCommand(SithTermFxWidget widget, String rawCommand) {
        if (widget == null || rawCommand == null || rawCommand.isBlank()) {
            return false;
        }
        try {
            String screenLines = widget.getTerminalTextBuffer() != null
                ? widget.getTerminalTextBuffer().getScreenLines()
                : "";
            String lastVisibleLine = extractLastVisibleLine(screenLines);
            return hasVisiblePromptForCommand(lastVisibleLine, rawCommand);
        } catch (Exception e) {
            logger.debug("Failed to inspect visible terminal prompt for AI shortcut interception: {}", e.getMessage());
            return false;
        }
    }

    static boolean hasVisiblePromptForCommand(String lastVisibleLine, String rawCommand) {
        if (lastVisibleLine == null || lastVisibleLine.isBlank()) {
            return false;
        }
        if (looksLikeShellPrompt(lastVisibleLine)) {
            return true;
        }
        if (rawCommand == null || rawCommand.isBlank()) {
            return false;
        }

        String normalizedLine = lastVisibleLine.stripTrailing();
        String normalizedCommand = rawCommand.trim();
        if (!normalizedLine.endsWith(normalizedCommand)) {
            return false;
        }

        String promptPrefix = normalizedLine.substring(0, normalizedLine.length() - normalizedCommand.length())
            .stripTrailing();
        return looksLikeShellPrompt(promptPrefix);
    }

    static String extractAgentShortcutFromVisibleLine(String lastVisibleLine, String commandName) {
        return extractAgentShortcutFromVisibleLine(lastVisibleLine, commandName, false);
    }

    static String extractAgentShortcutFromVisibleLine(
        String lastVisibleLine,
        String commandName,
        boolean caseInsensitiveCommandName) {
        if (lastVisibleLine == null || lastVisibleLine.isBlank()) {
            return null;
        }
        String normalizedLine = lastVisibleLine.stripTrailing();
        String normalizedCommandName = TerminalAgentCommandSupport.normalizeCommandName(commandName);
        for (int index = 0; index < normalizedLine.length(); index++) {
            if (index > 0 && !Character.isWhitespace(normalizedLine.charAt(index - 1))) {
                continue;
            }
            String candidate = normalizedLine.substring(index).trim();
            if (!startsWithAgentShortcut(candidate, normalizedCommandName, caseInsensitiveCommandName)) {
                continue;
            }
            if (TerminalAgentCommandSupport.parseShortcut(candidate, normalizedCommandName, caseInsensitiveCommandName) == null) {
                continue;
            }
            if (index == 0 || looksLikeShellPrompt(normalizedLine.substring(0, index).stripTrailing())) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean startsWithAgentShortcut(
        String candidate,
        String commandName,
        boolean caseInsensitiveCommandName) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String normalizedCandidate = caseInsensitiveCommandName
            ? candidate.toLowerCase(java.util.Locale.ROOT)
            : candidate;
        String normalizedCommandName = TerminalAgentCommandSupport.normalizeCommandName(commandName);
        if (caseInsensitiveCommandName) {
            normalizedCommandName = normalizedCommandName.toLowerCase(java.util.Locale.ROOT);
        }
        return normalizedCandidate.equals(normalizedCommandName)
            || normalizedCandidate.startsWith(normalizedCommandName + " ")
            || normalizedCandidate.startsWith(normalizedCommandName + ":")
            || normalizedCandidate.startsWith(normalizedCommandName + "(")
            || normalizedCandidate.startsWith(normalizedCommandName + "-ask ")
            || normalizedCandidate.startsWith(normalizedCommandName + "-ask:")
            || normalizedCandidate.startsWith(normalizedCommandName + "-plan ")
            || normalizedCandidate.startsWith(normalizedCommandName + "-plan:")
            || normalizedCandidate.startsWith(normalizedCommandName + "-plan(");
    }

    static String extractLastVisibleLine(String value) {
        String normalized = value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replaceAll("\\u001B\\[[;?0-9]*[ -/]*[@-~]", "")
            .replaceAll("\\u001B\\].*?(\\u0007|\\u001B\\\\)", "");
        int index = normalized.lastIndexOf('\n');
        return index >= 0 ? normalized.substring(index + 1).trim() : normalized.trim();
    }

    private static boolean looksLikeShellPrompt(String line) {
        String normalized = line == null ? "" : line.stripTrailing();
        return normalized.endsWith("$")
            || normalized.endsWith("#")
            || normalized.endsWith("%")
            || normalized.endsWith(">")
            || normalized.matches(".*\\[[^\\]]+\\]\\$");
    }

    static boolean shouldResetPromptReady(String lastVisibleLine, boolean localCommandEntryInProgress) {
        if (lastVisibleLine == null || lastVisibleLine.isBlank()) {
            return false;
        }
        if (looksLikeShellPrompt(lastVisibleLine)) {
            return false;
        }
        if (localCommandEntryInProgress) {
            return false;
        }
        return !lastVisibleLine.endsWith(" ");
    }

    static String buildTerminalAgentShellStartupCommand(String commandName) {
        String normalizedCommand = TerminalAgentCommandSupport.normalizeCommandName(commandName);
        if (TerminalAgentCommandSupport.validateCommandName(normalizedCommand) != null) {
            return "";
        }
        String askCommand = TerminalAgentCommandSupport.getAskCommandName(normalizedCommand);
        String planCommand = TerminalAgentCommandSupport.getPlanCommandName(normalizedCommand);
        return "__kortty_agent_b64(){ if command -v base64 >/dev/null 2>&1; then "
            + "printf '%s' \"$*\" | base64 | tr -d '\\r\\n'; "
            + "elif command -v python3 >/dev/null 2>&1; then "
            + "python3 -c 'import base64,sys;print(base64.b64encode(\" \".join(sys.argv[1:]).encode()).decode(), end=\"\")' \"$@\"; "
            + "else printf ''; fi; }; "
            + "__kortty_agent_emit(){ __kortty_kind=$1; shift; "
            + "__kortty_cwd=$(pwd -P 2>/dev/null || pwd 2>/dev/null || printf ''); "
            + "__kortty_cwd_payload=$(__kortty_agent_b64 \"$__kortty_cwd\"); "
            + "__kortty_payload=$(__kortty_agent_b64 \"$@\"); "
            + "printf '\\033]777;korTTY-agent;%s;%s;%s\\007' \"$__kortty_kind\" \"$__kortty_cwd_payload\" \"$__kortty_payload\"; }; "
            + "alias " + normalizedCommand + "='__kortty_agent_emit execute'; "
            + "alias " + askCommand + "='__kortty_agent_emit ask'; "
            + "alias " + planCommand + "='__kortty_agent_emit plan'; "
            + "__kortty_agent_clean_history(){ if [ -n \"${BASH_VERSION-}\" ]; then "
            + "if command -v awk >/dev/null 2>&1 && command -v sort >/dev/null 2>&1; then "
            + "for __kortty_h in $(history | awk '/__kortty_agent_b64\\(\\)/ {print $1}' | sort -rn); do "
            + "history -d \"$__kortty_h\" 2>/dev/null || true; done; "
            + "else history -d $((HISTCMD-1)) 2>/dev/null || true; fi; "
            + "if [ -n \"${HISTFILE-}\" ] && [ -f \"$HISTFILE\" ] && [ -w \"$HISTFILE\" ] && command -v awk >/dev/null 2>&1; then "
            + "__kortty_hist_tmp=\"${HISTFILE}.kortty.$$\"; "
            + "awk 'index($0,\"__kortty_agent_b64(){\")==0' \"$HISTFILE\" > \"$__kortty_hist_tmp\" "
            + "&& cat \"$__kortty_hist_tmp\" > \"$HISTFILE\"; rm -f \"$__kortty_hist_tmp\"; fi; "
            + "fi; }; "
            + "__kortty_agent_clean_history; unset -f __kortty_agent_clean_history; "
            + "printf '\\r\\033[K'; "
            + "stty echo\n";
    }

    static @Nullable String buildTerminalAgentRawCommandFromOscPayload(
        String kind,
        String encodedPrompt,
        String commandName) {
        if (kind == null || encodedPrompt == null) {
            return null;
        }
        String prompt;
        try {
            prompt = new String(Base64.getDecoder().decode(encodedPrompt), StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (prompt.isBlank()) {
            return null;
        }
        String normalizedCommand = TerminalAgentCommandSupport.normalizeCommandName(commandName);
        return switch (kind) {
            case "execute" -> normalizedCommand + " " + prompt;
            case "ask" -> TerminalAgentCommandSupport.getAskCommandName(normalizedCommand) + " " + prompt;
            case "plan" -> TerminalAgentCommandSupport.getPlanCommandName(normalizedCommand) + " " + prompt;
            default -> null;
        };
    }

    private final class AgentShortcutInputFilter {
        private static final char ESCAPE = '\u001B';
        private static final char CTRL_C = '\u0003';
        private static final char CTRL_R = '\u0012';
        private static final char CTRL_U = '\u0015';
        private static final char DELETE = '\u007F';

        private final SithTermFxWidget widget;
        private final boolean preferRemoteShortcut;
        private final StringBuilder inputLine = new StringBuilder();
        private final ByteArrayOutputStream partialUtf8 = new ByteArrayOutputStream(4);
        private boolean swallowNextLineFeed;
        private boolean escapePending;
        private boolean escapeSequence;

        private AgentShortcutInputFilter(SithTermFxWidget widget, boolean preferRemoteShortcut) {
            this.widget = widget;
            this.preferRemoteShortcut = preferRemoteShortcut;
        }

        private byte[] filter(byte[] bytes) {
            if (bytes == null || bytes.length == 0) {
                return new byte[0];
            }

            ByteArrayOutputStream outgoing = new ByteArrayOutputStream(bytes.length);
            int[] currentLineStart = {0};
            for (byte value : bytes) {
                processByte(value & 0xFF, outgoing, currentLineStart);
            }
            return outgoing.toByteArray();
        }

        private void processByte(int value, ByteArrayOutputStream outgoing, int[] currentLineStart) {
            if (isTerminalAgentInputLockedFor(widget)) {
                partialUtf8.reset();
                if (value < 0x80) {
                    handleLockedInput((char) value);
                }
                return;
            }

            while (true) {
                if (partialUtf8.size() > 0) {
                    if (isUtf8Continuation(value)) {
                        partialUtf8.write(value);
                        if (partialUtf8.size() == expectedUtf8Length(partialUtf8.toByteArray()[0] & 0xFF)) {
                            byte[] characterBytes = partialUtf8.toByteArray();
                            partialUtf8.reset();
                            emitUtf8Character(characterBytes, outgoing, currentLineStart);
                        }
                        return;
                    }
                    flushPartialUtf8(outgoing);
                    continue;
                }

                if (value < 0x80) {
                    byte[] originalBytes = {(byte) value};
                    processCharacter(String.valueOf((char) value), originalBytes, outgoing, currentLineStart);
                    return;
                }

                if (expectedUtf8Length(value) > 1) {
                    partialUtf8.write(value);
                    return;
                }

                outgoing.write(value);
                return;
            }
        }

        private void emitUtf8Character(byte[] characterBytes, ByteArrayOutputStream outgoing, int[] currentLineStart) {
            if (!isWellFormedUtf8(characterBytes)) {
                outgoing.writeBytes(characterBytes);
                return;
            }
            processCharacter(new String(characterBytes, StandardCharsets.UTF_8), characterBytes, outgoing, currentLineStart);
        }

        private void processCharacter(
            String text,
            byte[] originalBytes,
            ByteArrayOutputStream outgoing,
            int[] currentLineStart) {
            if (text == null || text.isEmpty()) {
                return;
            }
            if (text.length() == 1 && swallowNextLineFeed && text.charAt(0) == '\n') {
                swallowNextLineFeed = false;
                return;
            }
            swallowNextLineFeed = false;
            if (text.length() == 1 && isLineBreak(text.charAt(0))) {
                handleLineBreak(text.charAt(0), originalBytes, outgoing, currentLineStart);
                return;
            }
            if (text.length() == 1 && consumeTerminalControlForInputLine(text.charAt(0))) {
                outgoing.writeBytes(originalBytes);
                return;
            }
            updateInputLine(text);
            outgoing.writeBytes(originalBytes);
        }

        private void handleLineBreak(
            char ch,
            byte[] originalBytes,
            ByteArrayOutputStream outgoing,
            int[] currentLineStart) {
            String rawCommand = resolveAgentShortcutCommand(widget, inputLine.toString());
            String commandName = getTerminalAgentCommandName();
            boolean caseInsensitiveCommandName = isTerminalAgentCommandNameCaseInsensitive();
            boolean recognized = canInterceptBufferedAgentShortcut(
                rawCommand,
                commandName,
                caseInsensitiveCommandName);
            logger.debug(
                "Terminal AI input filter saw line break (bufferLength={}, recognized={})",
                inputLine.length(),
                recognized);
            inputLine.setLength(0);
            escapePending = false;
            escapeSequence = false;
            if (shouldLetRemoteShellHandleAgentShortcut(
                preferRemoteShortcut,
                rawCommand,
                commandName,
                caseInsensitiveCommandName)) {
                outgoing.writeBytes(originalBytes);
                currentLineStart[0] = outgoing.size();
                return;
            }
            if (recognized && shouldInterceptShortcut(rawCommand)) {
                truncate(outgoing, currentLineStart[0]);
                outgoing.write((byte) CTRL_U);
                dispatchTerminalAgentShortcut(rawCommand);
                if (ch == '\r') {
                    swallowNextLineFeed = true;
                }
                currentLineStart[0] = outgoing.size();
                return;
            }
            outgoing.writeBytes(originalBytes);
            currentLineStart[0] = outgoing.size();
        }

        private void truncate(ByteArrayOutputStream outgoing, int length) {
            byte[] current = outgoing.toByteArray();
            outgoing.reset();
            outgoing.write(current, 0, Math.min(length, current.length));
        }

        private void flushPartialUtf8(ByteArrayOutputStream outgoing) {
            if (partialUtf8.size() == 0) {
                return;
            }
            outgoing.writeBytes(partialUtf8.toByteArray());
            partialUtf8.reset();
        }

        private boolean isUtf8Continuation(int value) {
            return (value & 0xC0) == 0x80;
        }

        private int expectedUtf8Length(int value) {
            if (value >= 0xC2 && value <= 0xDF) {
                return 2;
            }
            if (value >= 0xE0 && value <= 0xEF) {
                return 3;
            }
            if (value >= 0xF0 && value <= 0xF4) {
                return 4;
            }
            return value < 0x80 ? 1 : -1;
        }

        private boolean isWellFormedUtf8(byte[] value) {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                decoder.decode(ByteBuffer.wrap(value));
                return true;
            } catch (CharacterCodingException e) {
                return false;
            }
        }

        private boolean shouldInterceptShortcut(String rawCommand) {
            if (!isTerminalAgentShortcutEnabled() || terminalAgentShortcutHandler == null) {
                return false;
            }
            String commandName = getTerminalAgentCommandName();
            if (!canInterceptBufferedAgentShortcut(
                rawCommand,
                commandName,
                isTerminalAgentCommandNameCaseInsensitive())) {
                return false;
            }
            logger.debug("Intercepting terminal AI shortcut before shell execution");
            agentShortcutPromptReady = false;
            if (widget != null) {
                StringBuilder keyEventBuffer = agentShortcutBuffers.get(widget);
                if (keyEventBuffer != null) {
                    keyEventBuffer.setLength(0);
                }
            }
            return true;
        }

        private void dispatchTerminalAgentShortcut(String rawCommand) {
            String command = rawCommand.trim();
            TerminalAgentRunContext runContext = createTerminalAgentRunContext(widget);
            Platform.runLater(() -> {
                TerminalAgentShortcutHandler handler = terminalAgentShortcutHandler;
                if (handler != null) {
                    handler.handle(command, runContext);
                }
            });
        }

        private void handleLockedInput(char ch) {
            TerminalAgentRunState state = terminalAgentRunStates.get(widget);
            if (ch == ESCAPE || ch == CTRL_C) {
                Runnable handler = state != null ? state.cancelHandler : null;
                if (handler != null) {
                    Platform.runLater(handler);
                }
            } else if (ch == CTRL_R) {
                Runnable handler = state != null ? state.toggleDetailsHandler : null;
                if (handler != null) {
                    Platform.runLater(handler);
                }
            }
        }

        private boolean isLineBreak(char ch) {
            return ch == '\r' || ch == '\n';
        }

        private boolean consumeTerminalControlForInputLine(char ch) {
            if (ch == ESCAPE) {
                escapePending = true;
                escapeSequence = false;
                return true;
            }
            if (escapePending) {
                escapePending = false;
                if (ch == '[' || ch == 'O' || ch == ']') {
                    escapeSequence = true;
                }
                return true;
            }
            if (escapeSequence) {
                if (ch >= '@' && ch <= '~') {
                    escapeSequence = false;
                }
                return true;
            }
            return false;
        }

        private void updateInputLine(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            char first = text.charAt(0);
            if (text.length() == 1 && (first == '\b' || first == DELETE)) {
                if (!inputLine.isEmpty()) {
                    int lastCodePoint = inputLine.codePointBefore(inputLine.length());
                    inputLine.setLength(inputLine.length() - Character.charCount(lastCodePoint));
                }
                return;
            }
            if (text.length() == 1 && (first == CTRL_U || first == CTRL_C)) {
                inputLine.setLength(0);
                escapePending = false;
                escapeSequence = false;
                return;
            }
            if (first == '\t' || first >= 32) {
                inputLine.append(text);
            }
        }
    }

    /**
     * Returns the terminal escape sequence for navigation/special keys (arrow, Tab, Home, End, F-keys, etc.).
     * Used so that e.g. Midnight Commander receives these keys (pane switch with Tab, selection with arrows).
     * Returns null for keys that should be handled by the default path (Enter, Backspace, printable).
     */
    private static String keyCodeToControlSequence(KeyCode code) {
        return switch (code) {
            case TAB -> "\t";
            // Use application keypad mode (SS3) for arrow keys so ncurses apps like mc receive them.
            case UP -> "\u001BOA";
            case DOWN -> "\u001BOB";
            case RIGHT -> "\u001BOC";
            case LEFT -> "\u001BOD";
            case HOME -> "\u001B[H";
            case END -> "\u001B[F";
            case PAGE_UP -> "\u001B[5~";
            case PAGE_DOWN -> "\u001B[6~";
            case INSERT -> "\u001B[2~";
            case DELETE -> "\u001B[3~";
            case F1 -> "\u001BOP";
            case F2 -> "\u001BOQ";
            case F3 -> "\u001BOR";
            case F4 -> "\u001BOS";
            case F5 -> "\u001B[15~";
            case F6 -> "\u001B[17~";
            case F7 -> "\u001B[18~";
            case F8 -> "\u001B[19~";
            case F9 -> "\u001B[20~";
            case F10 -> "\u001B[21~";
            case F11 -> "\u001B[23~";
            case F12 -> "\u001B[24~";
            default -> null;
        };
    }

    private boolean isTerminalCopyOnSelectEnabled() {
        try {
            var gsm = KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm != null ? gsm.getSettings() : null;
            return gs != null && gs.isTerminalCopyOnSelectEnabled();
        } catch (Exception e) {
            return true;
        }
    }
    
    /**
     * Sets up a timestamp gutter for the given widget.
     * The gutter is always created and Enter key timestamps are always recorded,
     * but the gutter is only visible if command timestamps are enabled.
     * This allows instant toggling without losing recorded timestamps.
     */
    private void setupTimestampGutter(SithTermFxWidget widget) {
        TimestampGutter gutter = new TimestampGutter();
        gutterMap.put(widget, gutter);
        timestampHistoryByWidget.computeIfAbsent(widget, w -> new TreeMap<>());
        
        // Configure gutter colors and font to match terminal
        gutter.setGutterBackgroundColor(Color.web(settings.getBackgroundColor()));
        gutter.setGutterTextColor(Color.web(settings.getForegroundColor()));
        gutter.setTimestampFont(settings.getFontFamily(), settings.getFontSize());
        
        // Set initial visibility based on current runtime state (important for new split widgets
        // created after user toggled timestamps in the active tab).
        boolean visible = timestampGuttersVisibleState;
        gutter.setVisible(visible);
        gutter.setManaged(visible);
        
        // Note: gutter is registered with the split pane via the leftPanelFactory
        // (passed in the TerminalSplitPane constructor), not here - because this method
        // runs during the TerminalSplitPane constructor when splitPane is not yet assigned.
        syncGutterFromHistory(widget, gutter);
        
        // Always listen for Enter key to record timestamps (even when gutter is hidden).
        // Register on the primary focusable target only; attaching to parent + child duplicates the event.
        javafx.event.EventHandler<KeyEvent> enterHandler = event -> {
            if (event.getCode() == KeyCode.ENTER) {
                int startAbsoluteLine = getCurrentAbsoluteCursorLine(widget);
                if (startAbsoluteLine >= 0) {
                    recordTimestampForLine(widget, startAbsoluteLine, LocalDateTime.now());
                    commandStartLineByWidget.put(widget, startAbsoluteLine);
                }
                awaitingCommandCompletionByWidget.put(widget, true);
            }
        };
        getPrimaryKeyEventTarget(widget).addEventFilter(KeyEvent.KEY_PRESSED, enterHandler);
        
        // Synchronize gutter with terminal scrollbar
        var terminalPanel = widget.getTerminalPanel();
        ScrollBar scrollBar = terminalPanel.getScrollBar();
        var textBuffer = terminalPanel.getTerminalTextBuffer();
        
        // Update gutter on scroll changes
        scrollBar.valueProperty().addListener((obs, oldV, newV) -> {
            updateGutterScrollState(gutter, scrollBar, textBuffer, terminalPanel);
        });

        // Keep timestamp line mapping aligned when terminal geometry changes
        // (window resize, split resize, divider moves).
        terminalPanel.getPane().widthProperty().addListener((obs, oldV, newV) -> {
            handleTerminalGeometryChanged(widget, gutter, scrollBar, textBuffer, terminalPanel);
        });
        terminalPanel.getPane().heightProperty().addListener((obs, oldV, newV) -> {
            handleTerminalGeometryChanged(widget, gutter, scrollBar, textBuffer, terminalPanel);
        });
        
        // Update gutter when terminal content changes (new output, resize)
        textBuffer.addModelListener(() -> {
            Platform.runLater(() -> {
                updateGutterScrollState(gutter, scrollBar, textBuffer, terminalPanel);
                // Add timestamp when prompt appears (cursor at start of new line from server output).
                // For command end tracking, use a short quiet-time debounce after Enter:
                // this avoids creating timestamps for every output line.
                try {
                    var terminal = widget.getTerminal();
                    if (terminal == null) return;
                    if (Boolean.TRUE.equals(awaitingCommandCompletionByWidget.get(widget))) {
                        scheduleCommandCompletionDetection(widget);
                    }
                } catch (Exception e) {
                    logger.trace("Timestamp on prompt: {}", e.getMessage());
                }
            });
        });
        
        // Initial update
        Platform.runLater(() -> updateGutterScrollState(gutter, scrollBar, textBuffer, terminalPanel));
    }
    
    /**
     * Records a timestamp in persistent per-widget history and mirrors it to the gutter.
     * This keeps data even when gutters are hidden or recreated later.
     */
    private void recordTimestampForLine(SithTermFxWidget widget, int absoluteLine, LocalDateTime timestamp) {
        if (absoluteLine < 0 || timestamp == null) return;
        TreeMap<Integer, LocalDateTime> history =
                timestampHistoryByWidget.computeIfAbsent(widget, w -> new TreeMap<>());
        if (!history.containsKey(absoluteLine)) {
            history.put(absoluteLine, timestamp);
        }
        TimestampGutter widgetGutter = gutterMap.get(widget);
        if (widgetGutter != null && !widgetGutter.hasTimestampForLine(absoluteLine)) {
            widgetGutter.addTimestamp(absoluteLine, history.get(absoluteLine));
        }
        int last = lastTimestampLineByWidget.getOrDefault(widget, -1);
        if (absoluteLine > last) {
            lastTimestampLineByWidget.put(widget, absoluteLine);
        }
    }

    private void syncGutterFromHistory(SithTermFxWidget widget, TimestampGutter gutter) {
        TreeMap<Integer, LocalDateTime> history = timestampHistoryByWidget.get(widget);
        if (history == null || history.isEmpty()) {
            return;
        }
        gutter.setAllTimestamps(new TreeMap<>(history));
    }

    private void handleTerminalGeometryChanged(SithTermFxWidget widget, TimestampGutter gutter, ScrollBar scrollBar,
                                               com.sithtermfx.core.model.TerminalTextBuffer textBuffer,
                                               com.sithtermfx.ui.TerminalPanel terminalPanel) {
        Platform.runLater(() -> {
            syncGutterFromHistory(widget, gutter);
            updateGutterScrollState(gutter, scrollBar, textBuffer, terminalPanel);
        });
    }

    private void scheduleCommandCompletionDetection(SithTermFxWidget widget) {
        PauseTransition timer = commandCompletionTimerByWidget.computeIfAbsent(widget, w -> {
            PauseTransition pt = new PauseTransition(Duration.millis(500));
            pt.setOnFinished(e -> recordCommandCompletionTimestamp(w));
            return pt;
        });
        timer.playFromStart();
    }

    private void recordCommandCompletionTimestamp(SithTermFxWidget widget) {
        if (!Boolean.TRUE.equals(awaitingCommandCompletionByWidget.get(widget))) {
            return;
        }
        try {
            int absoluteLine = getCurrentAbsoluteCursorLine(widget);
            if (absoluteLine < 0) {
                return;
            }
            int startLine = commandStartLineByWidget.getOrDefault(widget, -1);
            // Ensure completion marker is never above the command start line.
            if (startLine >= 0 && absoluteLine < startLine) {
                absoluteLine = startLine;
            }
            recordTimestampForLine(widget, absoluteLine, LocalDateTime.now());
            awaitingCommandCompletionByWidget.put(widget, false);
        } catch (Exception e) {
            logger.debug("Failed to record command completion timestamp: {}", e.getMessage());
        }
    }

    /**
     * Returns the current absolute cursor line (0-based over full history+screen buffer),
     * with bounds clamped to valid terminal rows to avoid transient out-of-bounds states.
     */
    private int getCurrentAbsoluteCursorLine(SithTermFxWidget widget) {
        try {
            var terminal = widget.getTerminal();
            var textBuffer = widget.getTerminalTextBuffer();
            if (terminal == null || textBuffer == null) return -1;
            int screenHeight = Math.max(1, textBuffer.getHeight());
            int cursorY = terminal.getCursorY() - 1; // cursor is 1-based
            int clampedCursorY = Math.min(Math.max(cursorY, 0), screenHeight - 1);
            return textBuffer.getHistoryLinesCount() + clampedCursorY;
        } catch (Exception e) {
            logger.trace("Could not resolve current cursor line: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Returns a snapshot of recorded timestamps for the primary terminal widget.
     * Used by project save/export.
     */
    public java.util.List<de.kortty.model.TerminalTimestampEntry> getPrimaryTimestampEntries() {
        SithTermFxWidget primary = terminalWidget;
        if (primary == null) {
            return java.util.Collections.emptyList();
        }
        TreeMap<Integer, LocalDateTime> history = timestampHistoryByWidget.get(primary);
        if (history == null || history.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        java.util.List<de.kortty.model.TerminalTimestampEntry> entries = new java.util.ArrayList<>(history.size());
        for (Map.Entry<Integer, LocalDateTime> entry : history.entrySet()) {
            if (entry.getValue() != null) {
                entries.add(new de.kortty.model.TerminalTimestampEntry(entry.getKey(), entry.getValue()));
            }
        }
        return entries;
    }

    /**
     * Restores recorded timestamps for the primary terminal widget from project import.
     */
    public void restorePrimaryTimestampEntries(java.util.List<de.kortty.model.TerminalTimestampEntry> entries) {
        SithTermFxWidget primary = terminalWidget;
        if (primary == null) {
            return;
        }
        TreeMap<Integer, LocalDateTime> restored = new TreeMap<>();
        if (entries != null) {
            for (de.kortty.model.TerminalTimestampEntry entry : entries) {
                if (entry == null || entry.getTimestamp() == null) continue;
                int absoluteLine = entry.getAbsoluteLine();
                if (absoluteLine < 0) continue;
                restored.putIfAbsent(absoluteLine, entry.getTimestamp());
            }
        }
        timestampHistoryByWidget.put(primary, restored);
        TimestampGutter gutter = gutterMap.get(primary);
        if (gutter != null) {
            gutter.setAllTimestamps(new TreeMap<>(restored));
        }
        int lastLine = restored.isEmpty() ? -1 : restored.lastKey();
        lastTimestampLineByWidget.put(primary, lastLine);
    }
    
    /**
     * Updates the gutter's scroll state to match the terminal panel's current state.
     * Computes the scroll origin from the scrollbar value using the same formula
     * as TerminalPanel.resolveSwingScrollBarValue().
     */
    private void updateGutterScrollState(TimestampGutter gutter, ScrollBar scrollBar,
                                          com.sithtermfx.core.model.TerminalTextBuffer textBuffer,
                                          com.sithtermfx.ui.TerminalPanel terminalPanel) {
        try {
            int historyLines = textBuffer.getHistoryLinesCount();
            int visibleRows = textBuffer.getHeight();
            // During resize/reflow the buffer can transiently report invalid geometry.
            // Ignore those intermediate states to avoid "empty" gutter redraws.
            if (visibleRows <= 0) {
                return;
            }
            double charHeight = resolveCellHeightPixels(terminalPanel, visibleRows);
            if (charHeight <= 0) {
                return;
            }
            int scrollOrigin = resolveScrollOrigin(terminalPanel, scrollBar, historyLines);
            int minOrigin = -Math.max(0, historyLines);
            if (scrollOrigin < minOrigin) scrollOrigin = minOrigin;
            if (scrollOrigin > 0) scrollOrigin = 0;
            double baselineOffset = resolveCellBaselineOffsetPixels(terminalPanel, charHeight);
            if (baselineOffset <= 0 || baselineOffset > charHeight * 2.0) {
                baselineOffset = charHeight * 0.78;
            }
            gutter.updateScrollState(scrollOrigin, historyLines, charHeight, visibleRows, baselineOffset);
        } catch (Exception e) {
            logger.debug("Failed to update gutter scroll state: {}", e.getMessage());
        }
    }

    private double resolveCellHeightPixels(com.sithtermfx.ui.TerminalPanel terminalPanel, int visibleRows) {
        Double fromMethod = invokeTerminalPanelDoubleMethod(terminalPanel, "getCellHeightPixels");
        if (fromMethod != null && fromMethod > 0) {
            return fromMethod;
        }
        double pixelHeight = terminalPanel.getPixelHeight();
        if (pixelHeight > 0 && visibleRows > 0) {
            return pixelHeight / visibleRows;
        }
        return 16.0;
    }

    private int resolveScrollOrigin(com.sithtermfx.ui.TerminalPanel terminalPanel, ScrollBar scrollBar, int historyLines) {
        Integer fromMethod = invokeTerminalPanelIntMethod(terminalPanel, "getScrollOrigin");
        if (fromMethod != null) {
            return fromMethod;
        }
        // Fallback for upstream builds that don't expose getScrollOrigin().
        // ScrollBar value maps to "lines scrolled into history".
        if (scrollBar != null) {
            int fromScroll = -(int) Math.round(scrollBar.getValue());
            int min = -Math.max(0, historyLines);
            if (fromScroll < min) return min;
            if (fromScroll > 0) return 0;
            return fromScroll;
        }
        return 0;
    }

    private double resolveCellBaselineOffsetPixels(com.sithtermfx.ui.TerminalPanel terminalPanel, double charHeight) {
        Double fromMethod = invokeTerminalPanelDoubleMethod(terminalPanel, "getCellBaselineOffsetPixels");
        if (fromMethod != null && fromMethod > 0 && fromMethod <= charHeight * 2.0) {
            return fromMethod;
        }
        return charHeight * 0.78;
    }

    private Double invokeTerminalPanelDoubleMethod(com.sithtermfx.ui.TerminalPanel terminalPanel, String methodName) {
        try {
            var method = terminalPanel.getClass().getMethod(methodName);
            Object result = method.invoke(terminalPanel);
            if (result instanceof Number number) {
                return number.doubleValue();
            }
        } catch (NoSuchMethodException ignored) {
            // Method not available in this SithTermFX build.
        } catch (Exception e) {
            logger.debug("Failed to call {} on TerminalPanel: {}", methodName, e.getMessage());
        }
        return null;
    }

    private Integer invokeTerminalPanelIntMethod(com.sithtermfx.ui.TerminalPanel terminalPanel, String methodName) {
        try {
            var method = terminalPanel.getClass().getMethod(methodName);
            Object result = method.invoke(terminalPanel);
            if (result instanceof Number number) {
                return number.intValue();
            }
        } catch (NoSuchMethodException ignored) {
            // Method not available in this SithTermFX build.
        } catch (Exception e) {
            logger.debug("Failed to call {} on TerminalPanel: {}", methodName, e.getMessage());
        }
        return null;
    }
    
    /**
     * Checks whether command timestamps are enabled, considering both connection-specific
     * and global settings. If the connection uses global settings, the global setting is checked.
     */
    private boolean isCommandTimestampsEnabled() {
        // Connection-specific setting takes priority
        if (!settings.isUseGlobalSettings()) {
            return settings.isCommandTimestampsEnabled();
        }
        // Check global setting
        try {
            var gsm = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm.getSettings();
            return gs != null && gs.isCommandTimestampsEnabled();
        } catch (Exception e) {
            return settings.isCommandTimestampsEnabled();
        }
    }
    
    /**
     * Gets the current font size from the settings provider (includes zoom level).
     */
    public int getCurrentFontSize() {
        return (int) settingsProvider.getFontSize();
    }
    
    /**
     * Focuses the terminal input, making it ready for keyboard input.
     * Should be called when switching to this terminal tab.
     */
    public void focusTerminal() {
        Runnable focusTask = () -> {
            SithTermFxWidget focused = splitPane != null ? splitPane.getFocusedWidget() : terminalWidget;
            if (focused != null) {
                getPrimaryKeyEventTarget(focused).requestFocus();
                return;
            }
            requestFocus();
        };
        if (Platform.isFxApplicationThread()) {
            focusTask.run();
        } else {
            Platform.runLater(focusTask);
        }
    }
    
    /**
     * Sets a listener to be notified when the SSH connection is disconnected.
     */
    public void setDisconnectListener(DisconnectListener listener) {
        this.externalDisconnectListener = listener;
    }
    
    /**
     * Sets a callback to be notified when the SSH connection is successfully established.
     */
    public void setOnConnectedCallback(Runnable callback) {
        this.onConnectedCallback = callback;
    }

    /**
     * Sets a callback run when a mosh4j session enters a transient network interruption,
     * so the tab can show the status bar immediately.
     */
    public void setOnMoshInterruptedCallback(Runnable callback) {
        this.onMoshInterruptedCallback = callback;
    }
    
    public void setOnReconnectRequested(Runnable r) {
        this.onReconnectRequested = r;
    }
    
    /**
     * Connects to the SSH server and starts the terminal session.
     * Implements retry logic with configurable timeout and retry count.
     * Runs asynchronously to prevent UI blocking.
     */
    public void connect() {
        // Run connection in background thread to prevent UI blocking
        Thread connectThread = new Thread(() -> {
            // Check if retries are enabled globally
            boolean retriesEnabled = true;
            try {
                de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
                if (app != null && app.getGlobalSettingsManager() != null) {
                    de.kortty.model.GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
                    if (globalSettings != null) {
                        retriesEnabled = globalSettings.isConnectionRetriesEnabled();
                    }
                }
            } catch (Exception e) {
                logger.warn("Could not check global retry setting: {}", e.getMessage());
            }
            
            int retryCount = retriesEnabled ? connection.getRetryCount() : 1;
            if (retryCount <= 0) {
                retryCount = retriesEnabled ? 4 : 1; // Default fallback
            }
            
            int attempt = 0;
            boolean connected = false;
            String lastError = null;
            boolean authenticationFailed = false;
            
            // Clear terminal before first attempt
            clearTerminal();
            if (retryCount > 1) {
                showMessage(I18n.get("terminal.connectionAttempt", 1, retryCount));
            } else {
                showMessage(I18n.get("terminal.connecting"));
            }
            
            while (attempt < retryCount && !connected && !authenticationFailed) {
                attempt++;
                
                try {
                    // Clean up previous attempt if any
                    if (ttyConnector != null) {
                        try {
                            ttyConnector.close();
                        } catch (Exception e) {
                            // Ignore cleanup errors
                        }
                    }
                    
                    // Clear terminal before each retry attempt
                    if (attempt > 1) {
                        clearTerminal();
                        showMessage(I18n.get("terminal.connectionAttempt", attempt, retryCount));
                    }
                    
                    // Create TtyConnector
                    ttyConnector = createConnectorForConnection(connection, password);
                    
                    // Register disconnect listener
                    setConnectorDisconnectListener(ttyConnector, (reason, wasError) -> {
                        logger.info("Disconnect event: {} (wasError={})", reason, wasError);
                        
                        // Stop logger if running
                        stopLogger();
                        
                        if (externalDisconnectListener != null) {
                            externalDisconnectListener.onDisconnect(reason, wasError);
                        }
                    });
                    
                    // Connect based on selected protocol
                    connected = connectConnector(ttyConnector);
                    
                    if (connected) {
                        if (ttyConnector instanceof SshTtyConnector sshConnector) {
                            sshConnector.addDataListener(getTerminalAgentPromptDataListener(sshConnector));
                        }
                        // Start terminal logger if enabled
                        startLogger();
                        
                        // Set the connector and start the terminal on JavaFX thread
                        Platform.runLater(() -> {
                            try {
                                if (terminalWidget == null) return; // Tab was closed during connect
                                terminalWidget.setTtyConnector(decorateTerminalConnector(terminalWidget, ttyConnector));
                                terminalWidget.start();
                                applyCursorShape(terminalWidget);
                                if (splitPane != null) {
                                    for (SithTermFxWidget w : splitPane.getAllWidgets()) {
                                        applyCursorShape(w);
                                        setCursorVisible(w, true);
                                    }
                                }
                                setCursorVisible(terminalWidget, true);
                                if (onConnectedCallback != null) {
                                    onConnectedCallback.run();
                                }
                            } catch (RejectedExecutionException e) {
                                // Widget was already closed (e.g. tab closed) and its executor terminated
                                logger.debug("Terminal widget already closed, skipping start: {}", e.getMessage());
                            }
                        });
                        
                        logger.info("Terminal session started for {} (attempt {}/{})", 
                                   connection.getDisplayName(), attempt, retryCount);
                        return; // Success!
                    } else {
                        lastError = I18n.get("terminal.sshConnectionFailed");
                        logger.warn("Connection attempt {}/{} failed for {}", 
                                   attempt, retryCount, connection.getDisplayName());
                        
                        // Show failure message
                        showMessage(I18n.get("terminal.attemptFailed", attempt));
                        
                        // Wait a bit before retry (except on last attempt)
                        if (attempt < retryCount) {
                            try {
                                Thread.sleep(1000); // 1 second delay between retries
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                    
                } catch (SshTtyConnector.AuthenticationException e) {
                    // Authentication failed - do NOT retry
                    authenticationFailed = true;
                    lastError = e.getMessage();
                    logger.error("Authentication failed for {} - NOT retrying: {}", 
                                connection.getDisplayName(), e.getMessage());
                    
                    // Show clear error message
                    clearTerminal();
                    showMessage(I18n.get("terminal.authFailed"));
                    if (connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY) {
                        showMessage("");
                        showMessage(I18n.get("terminal.possibleCauses"));
                        showMessage("  - " + I18n.get("terminal.noSSHKeySelected"));
                        showMessage("  - " + I18n.get("terminal.sshKeyNotAuthorized"));
                        showMessage("  - " + I18n.get("terminal.wrongUsername"));
                    }
                    showMessage("");
                    showMessage(I18n.get("error.title") + ": " + e.getMessage());
                    
                } catch (Exception e) {
                    lastError = I18n.get("terminal.connectionFailed") + ": " + e.getMessage();
                    logger.error("Failed to start terminal session (attempt {}/{}): {}", 
                                attempt, retryCount, e.getMessage(), e);
                    
                    // Show failure message
                    showMessage(I18n.get("terminal.attemptFailed", attempt) + ": " + e.getMessage());
                    
                    // Wait before retry (except on last attempt)
                    if (attempt < retryCount && !authenticationFailed) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            
            // All retries failed (or auth failed)
            if (!authenticationFailed) {
                clearTerminal();
                if (retryCount > 1) {
                    showMessage(I18n.get("terminal.allAttemptsFailed", retryCount));
                } else {
                    showMessage(I18n.get("terminal.connectionFailed"));
                }
                showMessage(I18n.get("terminal.timeout", connection.getConnectionTimeoutSeconds()));
                String finalError = lastError != null && !lastError.isEmpty() ? lastError : I18n.get("terminal.unknownError");
                showMessage(finalError);
            }
            logger.error("All connection attempts failed for {}", connection.getDisplayName());
            
            // Notify disconnect listener about failure
            String errorMessage = authenticationFailed 
                ? I18n.get("terminal.authFailed") 
                : (retryCount > 1 
                    ? I18n.get("terminal.allAttemptsFailed", retryCount) 
                    : I18n.get("terminal.connectionFailed"));
            if (externalDisconnectListener != null) {
                Platform.runLater(() -> {
                    externalDisconnectListener.onDisconnect(errorMessage, true);
                });
            }
        }, "SSH-Connect-" + connection.getDisplayName());
        connectThread.setDaemon(true);
        connectThread.start();
    }
    
    /**
     * Starts the terminal logger if logging is enabled for this connection.
     */
    private void startLogger() {
        de.kortty.model.TerminalLogConfig logConfig = connection.getLogConfig();
        if (logConfig == null || !logConfig.isEnabled()) {
            return;
        }
        
        try {
            terminalLogger = new de.kortty.core.TerminalLogger(logConfig, connection.getDisplayName());
            terminalLogger.start();
            
            // Register data listener to capture terminal output
            if (ttyConnector instanceof SshTtyConnector sshConnector) {
                terminalLoggerDataListener = data -> {
                    if (terminalLogger != null) {
                        terminalLogger.log(data);
                    }
                };
                sshConnector.addDataListener(terminalLoggerDataListener);
            }
            
            logger.info("Terminal logging started for {}", connection.getDisplayName());
        } catch (Exception e) {
            logger.error("Failed to start terminal logger: {}", e.getMessage(), e);
            showError("Logging konnte nicht gestartet werden: " + e.getMessage());
        }
    }
    
    /**
     * Stops the terminal logger.
     */
    private void stopLogger() {
        if (terminalLogger != null) {
            terminalLogger.stop();
            terminalLogger = null;
            logger.info("Terminal logging stopped for {}", connection.getDisplayName());
        }
        if (ttyConnector instanceof SshTtyConnector sshConnector) {
            if (terminalLoggerDataListener != null) {
                sshConnector.removeDataListener(terminalLoggerDataListener);
                terminalLoggerDataListener = null;
            }
        }
    }
    
    /**
     * Clears the terminal screen.
     */
    public void clearTerminal() {
        Platform.runLater(() -> {
            if (terminalWidget != null && terminalWidget.getTerminal() != null) {
                // Clear screen using ANSI escape sequence
                terminalWidget.getTerminal().writeCharacters("\033[2J\033[H");
            }
        });
    }
    
    /**
     * Shows an error message in the terminal.
     */
    public void showError(String message) {
        Platform.runLater(() -> {
            // Display error in terminal if possible
            SithTermFxWidget targetWidget = splitPane != null ? splitPane.getFocusedWidget() : terminalWidget;
            if (targetWidget != null && targetWidget.getTerminal() != null) {
                targetWidget.getTerminal().writeCharacters("\r\n*** " + message + " ***\r\n");
            }
        });
    }
    
    /**
     * Shows a message in the terminal (on new line).
     */
    public void showMessage(String message) {
        Platform.runLater(() -> {
            SithTermFxWidget targetWidget = splitPane != null ? splitPane.getFocusedWidget() : terminalWidget;
            if (targetWidget != null && targetWidget.getTerminal() != null) {
                writeLocalMessageToTerminal(targetWidget.getTerminal(), message);
            }
        });
    }

    public void showAgentMessage(String message) {
        showAgentMessage(null, message);
    }

    public void showAgentMessage(@Nullable TerminalAgentRunContext runContext, String message) {
        Platform.runLater(() -> {
            SithTermFxWidget targetWidget = runContext != null && runContext.widget() != null
                ? runContext.widget()
                : (splitPane != null ? splitPane.getFocusedWidget() : terminalWidget);
            if (targetWidget != null && targetWidget.getTerminal() != null) {
                String prompt = resolvePromptForLocalRedisplay(targetWidget);
                writeLocalMessageToTerminal(targetWidget.getTerminal(), message);
                writePromptForLocalRedisplay(targetWidget.getTerminal(), prompt);
            }
        });
    }

    private void writeLocalMessageToTerminal(Terminal terminal, String message) {
        if (terminal == null || message == null || message.isBlank()) {
            return;
        }
        writeTerminalNewLine(terminal);
        for (String line : normalizeTerminalMessageLines(message)) {
            terminal.writeUnwrappedString(line);
            writeTerminalNewLine(terminal);
        }
    }

    private void writeTerminalNewLine(Terminal terminal) {
        terminal.carriageReturn();
        terminal.newLine();
    }

    static List<String> normalizeTerminalMessageLines(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        return message
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .map(String::stripTrailing)
            .toList();
    }

    private String resolvePromptForLocalRedisplay(SithTermFxWidget widget) {
        try {
            String screenLines = widget != null && widget.getTerminalTextBuffer() != null
                ? widget.getTerminalTextBuffer().getScreenLines()
                : "";
            return extractPromptForLocalRedisplay(
                screenLines,
                getTerminalAgentCommandName(),
                isTerminalAgentCommandNameCaseInsensitive());
        } catch (Exception e) {
            logger.debug("Failed to resolve terminal prompt for AI response redisplay: {}", e.getMessage());
            return "";
        }
    }

    static String extractPromptForLocalRedisplay(String screenLines, String commandName) {
        return extractPromptForLocalRedisplay(screenLines, commandName, false);
    }

    static String extractPromptForLocalRedisplay(
        String screenLines,
        String commandName,
        boolean caseInsensitiveCommandName) {
        if (screenLines == null || screenLines.isBlank()) {
            return "";
        }
        String normalized = screenLines
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replaceAll("\\u001B\\[[;?0-9]*[ -/]*[@-~]", "")
            .replaceAll("\\u001B\\].*?(\\u0007|\\u001B\\\\)", "");
        String[] lines = normalized.split("\n", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].stripTrailing();
            if (line.isBlank()) {
                continue;
            }
            String prompt = extractPromptFromVisibleLine(line, commandName, caseInsensitiveCommandName);
            if (!prompt.isBlank()) {
                return prompt;
            }
        }
        return "";
    }

    private static String extractPromptFromVisibleLine(String line, String commandName) {
        return extractPromptFromVisibleLine(line, commandName, false);
    }

    private static String extractPromptFromVisibleLine(
        String line,
        String commandName,
        boolean caseInsensitiveCommandName) {
        if (looksLikeShellPrompt(line)) {
            return ensurePromptInputSpacing(line);
        }
        String command = extractAgentShortcutFromVisibleLine(line, commandName, caseInsensitiveCommandName);
        if (command == null || !line.stripTrailing().endsWith(command)) {
            return "";
        }
        String prompt = line.substring(0, line.length() - command.length()).stripTrailing();
        return looksLikeShellPrompt(prompt) ? ensurePromptInputSpacing(prompt) : "";
    }

    private static String ensurePromptInputSpacing(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        return Character.isWhitespace(prompt.charAt(prompt.length() - 1)) ? prompt : prompt + " ";
    }

    private void writePromptForLocalRedisplay(Terminal terminal, String prompt) {
        if (terminal == null || prompt == null || prompt.isBlank()) {
            return;
        }
        terminal.writeUnwrappedString(prompt);
    }
    
    /**
     * Disconnects without destroying the UI. Use for reconnect - keeps terminal widget and split pane.
     */
    public void disconnectOnly() {
        stopAllTerminalAgentShellKeepAlives();
        stopLogger();
        if (ttyConnector != null) {
            try {
                ttyConnector.close();
            } catch (Exception e) {
                logger.warn("Error closing TtyConnector: {}", e.getMessage());
            }
            ttyConnector = null;
        }
    }
    
    /**
     * Cleans up resources (closes connection and destroys UI). Use when closing the tab.
     */
    public void cleanup() {
        stopAllTerminalAgentShellKeepAlives();
        stopLogger();
        if (ttyConnector != null) {
            try {
                ttyConnector.close();
            } catch (Exception e) {
                logger.warn("Error closing TtyConnector: {}", e.getMessage());
            }
            ttyConnector = null;
        }
        if (splitPane != null) {
            try {
                splitPane.closeAll();
            } catch (Exception e) {
                logger.debug("Ignoring split pane cleanup exception: {}", e.getMessage());
            }
            splitPane = null;
        }
        terminalContainer = null;
        terminalAgentBusyStylesheetUrl = null;
        terminalAgentActivityPanels.clear();
        terminalAgentPromptDataListeners.clear();
        terminalAgentOscBuffers.clear();
        terminalWidget = null;
    }
    
    /**
     * Checks if connected.
     */
    public boolean isConnected() {
        return ttyConnector != null && ttyConnector.isConnected();
    }

    /**
     * Returns true when a connected mosh4j session currently has no host traffic
     * for a while and is in transient network interruption mode.
     */
    public boolean isMoshNetworkInterrupted() {
        if (ttyConnector instanceof Mosh4jTtyConnector mosh4j) {
            return mosh4j.isNetworkInterrupted();
        }
        return false;
    }

    /**
     * Returns interruption start timestamp (epoch millis) for mosh4j sessions,
     * or -1 when no interruption is active.
     */
    public long getMoshInterruptionStartedAtMs() {
        if (ttyConnector instanceof Mosh4jTtyConnector mosh4j) {
            return mosh4j.getInterruptionStartedAtMs();
        }
        return -1L;
    }
    
    /**
     * Sends input to the terminal (for broadcast mode).
     */
    public void sendInput(String text) {
        if (ttyConnector != null && ttyConnector.isConnected()) {
            try {
                ttyConnector.write(text);
            } catch (java.io.IOException e) {
                logger.error("Failed to send input to terminal", e);
            }
        }
    }

    /**
     * Sends {@code text} followed by a Unix newline ({@code \n}) so an interactive POSIX shell
     * accepts the buffer as a complete line and executes it (same effect as pressing Enter).
     */
    public void sendInputLine(String text) {
        if (text == null) {
            return;
        }
        sendInput(text + "\n");
    }

    /**
     * Sends a generated command without letting the remote PTY echo the command text back to the terminal.
     * Falls back to normal sending for non-SSH connectors.
     */
    public void sendGeneratedInputLineHidden(String text) {
        if (text == null) {
            return;
        }
        TtyConnector connector = ttyConnector;
        if (!(connector instanceof SshTtyConnector) || !connector.isConnected()) {
            sendInputLine(text);
            return;
        }
        Thread sender = new Thread(() -> sendGeneratedInputLineHidden(connector, text), "terminal-hidden-input-sender");
        sender.setDaemon(true);
        sender.start();
    }

    private void sendGeneratedInputLineHidden(TtyConnector connector, String text) {
        try {
            connector.write("stty -echo\n");
            TimeUnit.MILLISECONDS.sleep(80);
            connector.write(buildEchoSuppressedGeneratedInput(text));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            restoreTerminalEcho(connector);
        } catch (IOException e) {
            logger.error("Failed to send generated input to terminal without echo", e);
            restoreTerminalEcho(connector);
        }
    }

    private void restoreTerminalEcho(TtyConnector connector) {
        if (connector == null || !connector.isConnected()) {
            return;
        }
        try {
            connector.write("stty echo\n");
        } catch (IOException e) {
            logger.debug("Failed to restore terminal echo after hidden generated input: {}", e.getMessage());
        }
    }

    static String buildEchoSuppressedGeneratedInput(String text) {
        String command = text != null ? text.stripTrailing() : "";
        if (command.isBlank()) {
            return "stty echo\n";
        }
        return "printf '\\033[1A\\r\\033[K\\033[1B\\r\\033[K\\033[1A\\r'; "
            + appendEchoRestore(command)
            + "\n";
    }

    private static String appendEchoRestore(String command) {
        int firstNewline = command.indexOf('\n');
        if (firstNewline >= 0) {
            return command.substring(0, firstNewline)
                + "; stty echo"
                + command.substring(firstNewline);
        }
        return command + "; stty echo";
    }
    
    /**
     * Copies selected text to clipboard.
     */
    public void copyToClipboard() {
        SithTermFxWidget focused = splitPane != null ? splitPane.getFocusedWidget() : terminalWidget;
        if (focused != null && focused.getTerminalPanel() != null) {
            // handleCopy(withCustomSelectors, byKeyStroke)
            focused.getTerminalPanel().handleCopy(false, false);
        }
    }
    
    /**
     * Toggles the visibility of all timestamp gutters in this terminal view.
     * When shown, previously recorded timestamps become visible immediately.
     *
     * @return true if gutters are now visible, false if hidden
     */
    public boolean toggleTimestampGutters() {
        boolean newVisible = !timestampGuttersVisibleState;
        setTimestampGuttersVisible(newVisible);
        return newVisible;
    }
    
    /**
     * Sets the visibility of all timestamp gutters in this terminal view.
     */
    public void setTimestampGuttersVisible(boolean visible) {
        boolean oldVisible = timestampGuttersVisibleState;
        timestampGuttersVisibleState = visible;
        for (Map.Entry<SithTermFxWidget, TimestampGutter> entry : gutterMap.entrySet()) {
            syncGutterFromHistory(entry.getKey(), entry.getValue());
            TimestampGutter gutter = entry.getValue();
            gutter.setVisible(visible);
            gutter.setManaged(visible);
        }
        if (oldVisible != visible) {
            adjustWindowWidthForTimestampToggle(oldVisible, visible);
        }
    }

    private void adjustWindowWidthForTimestampToggle(boolean oldVisible, boolean newVisible) {
        if (oldVisible == newVisible) return;
        Platform.runLater(() -> {
            Window window = getScene() != null ? getScene().getWindow() : null;
            if (!(window instanceof Stage stage)) return;
            if (stage.isMaximized() || stage.isFullScreen()) return;
            double delta = TimestampGutter.GUTTER_WIDTH;
            double targetWidth = stage.getWidth() + (newVisible ? delta : -delta);
            stage.setWidth(Math.max(640, targetWidth));
        });
        }
    
    /**
     * Sets a listener that is called whenever timestamp gutter visibility is toggled
     * from the context menu. Used by MainWindow to update the CheckMenuItem state.
     */
    public void setTimestampToggleListener(Runnable listener) {
        this.timestampToggleListener = listener;
    }
    
    /**
     * Returns whether timestamp gutters are currently visible.
     */
    public boolean isTimestampGuttersVisible() {
        return timestampGuttersVisibleState;
    }
    
    /**
     * Shows the find bar in the terminal.
     */
    public void showFind() {
        SithTermFxWidget focused = splitPane != null ? splitPane.getFocusedWidget() : terminalWidget;
        if (focused != null) {
            try {
                java.lang.reflect.Method m = focused.getClass().getDeclaredMethod("showFindComponent");
                m.setAccessible(true);
                m.invoke(focused);
            } catch (Exception e) {
                logger.warn("Could not invoke showFindComponent", e);
            }
        }
    }
    
    /**
     * Pastes from clipboard.
     */
    public void pasteFromClipboard() {
        SithTermFxWidget focused = splitPane != null ? splitPane.getFocusedWidget() : terminalWidget;
        if (focused != null && focused.getTerminalPanel() != null) {
            focused.getTerminalPanel().handlePaste();
        }
    }
    
    /**
     * Zooms the terminal font.
     * Uses SithTermFX 1.2.0's native dynamic font size support.
     */
    public void zoom(int delta) {
        if (delta > 0) {
            settingsProvider.increaseFontSize(delta);
        } else if (delta < 0) {
            settingsProvider.decreaseFontSize(-delta);
        }
        logger.debug("Zoom changed to font size: {}", settingsProvider.getFontSize());
    }
    
    /**
     * Applies font family and size from the given connection settings to this terminal view.
     * Use this to refresh the display when connection settings were changed (e.g. in Connection Manager).
     */
    public void applyConnectionSettings(ConnectionSettings s) {
        if (s == null) return;
        ConnectionSettings effective = s;
        String themeId = s.getThemeId();
        if (themeId != null && !themeId.isEmpty()) {
            try {
                var tm = KorTTYApplication.getInstance().getThemeManager();
                if (tm != null) {
                    effective = tm.resolveSettings(s, themeId, isThemeFontApplyEnabled());
                }
            } catch (Exception e) {
                logger.debug("Could not resolve theme '{}' while applying settings: {}", themeId, e.getMessage());
            }
        }

        String family = effective.getFontFamily();
        if (family == null || family.isEmpty()) family = "Monospaced";
        int size = effective.getFontSize();
        if (size <= 0) size = defaultFontSize;
        settings.setFontFamily(family);
        settings.setFontSize(size);
        settings.setForegroundColor(effective.getForegroundColor());
        settings.setBackgroundColor(effective.getBackgroundColor());
        settings.setCursorColor(effective.getCursorColor());
        settings.setCursorStyle(effective.getCursorStyle());
        settingsProvider.setFontSize(size);

        if (splitPane != null) {
            for (SithTermFxWidget w : splitPane.getAllWidgets()) {
                applyStyleStateColors(w);
                applyCursorShape(w);
                setCursorVisible(w, true);
            }
        } else if (terminalWidget != null) {
            applyStyleStateColors(terminalWidget);
            applyCursorShape(terminalWidget);
            setCursorVisible(terminalWidget, true);
        }

        // Keep timestamp gutter colors in sync with applied theme/colors.
        try {
            for (var entry : gutterMap.entrySet()) {
                var gutter = entry.getValue();
                if (gutter != null) {
                    gutter.setGutterBackgroundColor(Color.web(settings.getBackgroundColor()));
                    gutter.setGutterTextColor(Color.web(settings.getForegroundColor()));
                    gutter.setTimestampFont(settings.getFontFamily(), settingsProvider.getFontSize());
                }
            }
        } catch (Exception e) {
            logger.debug("Could not refresh timestamp gutter colors: {}", e.getMessage());
        }

        logger.debug("Applied connection settings: {} {}pt", family, size);
    }

    private boolean isThemeFontApplyEnabled() {
        try {
            var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            return gs != null && gs.isApplyThemeFonts();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Resets the terminal font size and family to the baseline: what this tab had at open (connectionSaved*),
     * then config manager, then global. Using the tab-open baseline ensures reset matches what the user saw when connecting.
     */
    public void resetZoom() {
        // 1) Prefer the font we had at tab open (user connected with this size/family)
        if (connectionSavedFontSize > 0) {
            String family = (connectionSavedFontFamily != null && !connectionSavedFontFamily.isEmpty())
                    ? connectionSavedFontFamily : "Monospaced";
            settings.setFontFamily(family);
            settings.setFontSize(connectionSavedFontSize);
            settingsProvider.setFontSize(connectionSavedFontSize);
            logger.debug("Zoom reset to tab-open baseline: {} {}pt", family, connectionSavedFontSize);
            return;
        }
        // 2) Fallback: config manager or tab connection or global
        ConnectionSettings toApply = null;
        try {
            if (connection != null && connection.getId() != null) {
                ServerConnection stored = KorTTYApplication.getInstance().getConfigManager().getConnectionById(connection.getId());
                if (stored != null && stored.getSettings() != null) toApply = stored.getSettings();
            }
            if (toApply == null && connection != null && connection.getSettings() != null && connection.getSettings().getFontSize() > 0) {
                toApply = connection.getSettings();
            }
            if (toApply == null || toApply.getFontSize() <= 0) {
                var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
                if (gs != null && gs.getDefaultTerminalSettings() != null) toApply = gs.getDefaultTerminalSettings();
            }
            if (toApply != null && toApply.getFontSize() > 0) {
                applyConnectionSettings(toApply);
                return;
            }
        } catch (Exception e) {
            logger.debug("Could not get stored font for reset: {}", e.getMessage());
        }
        settingsProvider.setFontSize(defaultFontSize);
    }
    
    /**
     * Sets the terminal font size (e.g. when restoring project zoom level).
     */
    public void setFontSize(int fontSize) {
        settingsProvider.setFontSize(fontSize);
        logger.debug("Font size set to: {}", fontSize);
    }
    
    /**
     * Gets the split pane structure for saving to project.
     * Returns null if there are no splits (single terminal).
     */
    public de.kortty.model.SplitPaneState getSplitState() {
        if (splitPane == null) {
            return null;
        }
        
        int widgetCount = splitPane.getWidgetCount();
        if (widgetCount <= 1) {
            return null; // No splits
        }
        
        // Build state from rootCell
        return buildSplitState(getRootCell(), splitPane.getAllWidgets());
    }
    
    /**
     * Recursively builds SplitPaneState from the cell tree structure.
     */
    private de.kortty.model.SplitPaneState buildSplitState(Object cell, List<SithTermFxWidget> allWidgets) {
        // Use reflection to access private SplitCell fields
        try {
            Class<?> cellClass = cell.getClass();
            
            // Check if it's a leaf (has widget)
            var widgetField = cellClass.getDeclaredField("widget");
            widgetField.setAccessible(true);
            SithTermFxWidget widget = (SithTermFxWidget) widgetField.get(cell);
            
            if (widget != null) {
                // Leaf node - find widget index
                int index = allWidgets.indexOf(widget);
                return de.kortty.model.SplitPaneState.createLeaf(index);
            }
            
            // Split node - get orientation, divider, and children
            var splitPaneField = cellClass.getDeclaredField("splitPane");
            splitPaneField.setAccessible(true);
            javafx.scene.control.SplitPane splitPaneObj = (javafx.scene.control.SplitPane) splitPaneField.get(cell);
            
            var leftCellField = cellClass.getDeclaredField("leftCell");
            leftCellField.setAccessible(true);
            Object leftCell = leftCellField.get(cell);
            
            var rightCellField = cellClass.getDeclaredField("rightCell");
            rightCellField.setAccessible(true);
            Object rightCell = rightCellField.get(cell);
            
            if (splitPaneObj != null && leftCell != null && rightCell != null) {
                Orientation ori = splitPaneObj.getOrientation();
                double[] positions = splitPaneObj.getDividerPositions();
                double dividerPos = positions.length > 0 ? positions[0] : 0.5;
                
                de.kortty.model.SplitPaneState leftState = buildSplitState(leftCell, allWidgets);
                de.kortty.model.SplitPaneState rightState = buildSplitState(rightCell, allWidgets);
                
                return de.kortty.model.SplitPaneState.createSplit(ori, dividerPos, leftState, rightState);
            }
        } catch (Exception e) {
            logger.error("Failed to build split state: {}", e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * Gets the rootCell from TerminalSplitPane using reflection.
     */
    private Object getRootCell() {
        try {
            var rootCellField = splitPane.getClass().getDeclaredField("rootCell");
            rootCellField.setAccessible(true);
            return rootCellField.get(splitPane);
        } catch (Exception e) {
            logger.error("Failed to get rootCell: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Gets the terminal history/buffer.
     */
    public String getTerminalHistory() {
        if (terminalWidget != null && terminalWidget.getTerminalTextBuffer() != null) {
            return terminalWidget.getTerminalTextBuffer().getScreenLines();
        }
        return "";
    }
    
    /**
     * Restores terminal history by writing to temp file first, then displaying it.
     * This approach shows only one short command line instead of multi-line here-doc.
     */
    public void restoreHistory(String history) {
        if (history == null || history.isEmpty()) {
            return;
        }
        
        Platform.runLater(() -> {
            if (ttyConnector != null && ttyConnector.isConnected()) {
                try {
                    // Clean up the history - remove excessive empty lines
                    String cleanHistory = history
                        .replaceAll("\\n{3,}", "\n\n") // Max 2 consecutive newlines
                        .trim();
                    
                    // Escape for shell (for writing to file via here-doc)
                    String escapedForFile = cleanHistory;
                    
                    // Use a temp file approach - much cleaner!
                    // 1. Write history to temp file (using very short here-doc 'H')
                    // 2. Clear screen and cat the file  
                    // 3. Delete the temp file
                    // This way only "clear;cat ..." is visible, not the multi-line content
                    
                    StringBuilder command = new StringBuilder();
                    String tmpFile = "/tmp/.kortty_hist_$$"; // $$ = current shell PID (unique)
                    
                    // Write to temp file (this command is short)
                    command.append("cat>").append(tmpFile).append("<<H\n");
                    command.append(escapedForFile);
                    if (!escapedForFile.endsWith("\n")) {
                        command.append("\n");
                    }
                    command.append("H\n");
                    
                    // Clear screen, show file content, delete file (all in one short line)
                    command.append("clear;cat ").append(tmpFile).append(";rm ").append(tmpFile).append("\n");
                    
                    ttyConnector.write(command.toString());
                    
                    logger.info("Restored history ({} bytes)", cleanHistory.length());
                } catch (Exception e) {
                    logger.error("Failed to restore terminal history", e);
                }
            } else {
                logger.warn("Cannot restore history: terminal not connected");
            }
        });
    }
    
    public ServerConnection getConnection() {
        return connection;
    }
    
    public SshTtyConnector getTtyConnector() {
        return ttyConnector instanceof SshTtyConnector ssh ? ssh : null;
    }

    /**
     * Informs the connector whether this terminal tab is the active (focused) one.
     * Used by Mosh4j to avoid showing a false "connection interrupted" when the user switched to another tab.
     */
    public void setTerminalActive(boolean active) {
        if (ttyConnector instanceof Mosh4jTtyConnector mosh) {
            mosh.setTerminalActive(active);
        }
    }

    /**
     * Restores split pane structure from saved state.
     * Recreates the split tree with new SSH connections for each widget.
     */
    public void restoreSplitState(de.kortty.model.SplitPaneState splitState) {
        if (splitState == null || !splitState.isSplit()) {
            logger.debug("No split structure to restore");
            return;
        }
        
        logger.info("Restoring split structure: {}", splitState);
        
        // We need to restore splits after the initial connection is established
        // Schedule split restoration for after the current terminal is connected
        Platform.runLater(() -> {
            try {
                // Start with the currently focused widget (the initial one)
                SithTermFxWidget initialWidget = splitPane.getFocusedWidget();
                if (initialWidget == null) {
                    logger.warn("No initial widget to start split restoration");
                    return;
                }
                
                // Rebuild the split structure directly from the saved state
                restoreSplitRecursive(splitState, initialWidget);
                
            } catch (Exception e) {
                logger.error("Failed to restore split structure: {}", e.getMessage(), e);
            }
        });
    }
    
    /**
     * Sets the focusedWidget field in TerminalSplitPane using reflection.
     * This is necessary because split() always uses getFocusedWidget().
     */
    private void setFocusedWidget(SithTermFxWidget widget) {
        try {
            var focusedWidgetField = splitPane.getClass().getDeclaredField("focusedWidget");
            focusedWidgetField.setAccessible(true);
            focusedWidgetField.set(splitPane, widget);
            logger.debug("Set focusedWidget to: {}", widget.hashCode());
        } catch (Exception e) {
            logger.error("Failed to set focusedWidget: {}", e.getMessage());
        }
    }
    
    /**
     * Recursively restores the split structure by creating splits as needed.
     * This method traverses the split tree and creates splits in the correct order and orientation.
     * 
     * @param state The split state to restore
     * @param widgetToSplit The specific widget that should be split
     */
    private void restoreSplitRecursive(de.kortty.model.SplitPaneState state, SithTermFxWidget widgetToSplit) {
        if (state == null || state.isLeaf()) {
            return; // Leaf node - nothing to do
        }
        
        if (widgetToSplit == null) {
            logger.warn("Widget to split is null");
            return;
        }
        
        // This is a split node - we need to create the split
        Orientation orientation = state.getOrientationEnum();
        if (orientation == null) {
            logger.warn("Invalid orientation in split state: {}", state.getOrientation());
            return;
        }
        
        logger.info("Creating split: orientation={}, dividerPos={} on widget {}", 
                     orientation, state.getDividerPosition(), widgetToSplit.hashCode());
        
        // CRITICAL: Set the focusedWidget directly using reflection
        // because split() uses getFocusedWidget() internally
        setFocusedWidget(widgetToSplit);
        
        // Remember the number of widgets before split
        int widgetCountBefore = splitPane.getAllWidgets().size();
        
        // Perform the split with the CORRECT orientation from saved state
        splitPane.split(SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, orientation);
        
        // Wait for split to complete and connection to establish
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Get all widgets after the split
        List<SithTermFxWidget> allWidgets = splitPane.getAllWidgets();
        int widgetCountAfter = allWidgets.size();
        
        if (widgetCountAfter <= widgetCountBefore) {
            logger.warn("Split did not create new widget (before: {}, after: {})", widgetCountBefore, widgetCountAfter);
            return;
        }
        
        // The new widget is the last one in the list
        SithTermFxWidget newWidget = allWidgets.get(allWidgets.size() - 1);
        
        logger.info("Split created: leftWidget={}, rightWidget={}, totalWidgets={}", 
                     widgetToSplit.hashCode(), newWidget.hashCode(), widgetCountAfter);
        
        // Set divider position for this split
        setDividerPositionForLastSplit(state.getDividerPosition());
        
        // Process left child (the original widget that was split)
        if (state.getLeftChild() != null && state.getLeftChild().isSplit()) {
            logger.debug("Processing left child");
            restoreSplitRecursive(state.getLeftChild(), widgetToSplit);
        }
        
        // Process right child (the newly created widget)
        if (state.getRightChild() != null && state.getRightChild().isSplit()) {
            logger.debug("Processing right child");
            restoreSplitRecursive(state.getRightChild(), newWidget);
        }
    }
    
    
    /**
     * Sets the divider position for the most recently created split.
     */
    private void setDividerPositionForLastSplit(double position) {
        Platform.runLater(() -> {
            try {
                // Get rootCell from TerminalSplitPane
                var rootCellField = splitPane.getClass().getDeclaredField("rootCell");
                rootCellField.setAccessible(true);
                Object rootCell = rootCellField.get(splitPane);
                
                if (rootCell != null) {
                    // Find all SplitPanes in the tree and set the last one
                    List<javafx.scene.control.SplitPane> splitPanes = new java.util.ArrayList<>();
                    collectSplitPanes(rootCell, splitPanes);
                    
                    if (!splitPanes.isEmpty()) {
                        javafx.scene.control.SplitPane lastSplit = splitPanes.get(splitPanes.size() - 1);
                        lastSplit.setDividerPositions(position);
                        logger.debug("Set divider position to: {}", position);
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not set divider position: {}", e.getMessage());
            }
        });
    }
    
    /**
     * Collects all SplitPane instances from the cell tree.
     */
    private void collectSplitPanes(Object cell, List<javafx.scene.control.SplitPane> splitPanes) {
        try {
            Class<?> cellClass = cell.getClass();
            
            // Check if this cell has a splitPane
            var splitPaneField = cellClass.getDeclaredField("splitPane");
            splitPaneField.setAccessible(true);
            javafx.scene.control.SplitPane sp = (javafx.scene.control.SplitPane) splitPaneField.get(cell);
            
            if (sp != null) {
                splitPanes.add(sp);
                
                // Recurse into children
                var leftCellField = cellClass.getDeclaredField("leftCell");
                leftCellField.setAccessible(true);
                Object leftCell = leftCellField.get(cell);
                
                var rightCellField = cellClass.getDeclaredField("rightCell");
                rightCellField.setAccessible(true);
                Object rightCell = rightCellField.get(cell);
                
                if (leftCell != null) {
                    collectSplitPanes(leftCell, splitPanes);
                }
                if (rightCell != null) {
                    collectSplitPanes(rightCell, splitPanes);
                }
            }
        } catch (Exception e) {
            // Ignore - might be a leaf cell
        }
    }
    
    /**
     * Custom settings provider for KorTTY with dynamic font size support.
     * Extends DynamicFontSizeSettingsProvider to enable:
     * - Font zoom via Cmd+Plus/Minus (or Ctrl+Plus/Minus)
     * - Font zoom via right-click context menu
     */
    private static class KorTTYSettingsProvider extends DynamicFontSizeSettingsProvider {
        
        private final ConnectionSettings settings;
        
        public KorTTYSettingsProvider(ConnectionSettings settings, int initialFontSize) {
            super(initialFontSize);
            this.settings = settings;
        }
        
        @Override
        public @NotNull Font getTerminalFont() {
            String family = settings.getFontFamily();
            if (family == null || family.isEmpty()) family = "Monospaced";
            if ("Monaco".equals(family) && !Font.getFamilies().contains("Monaco")) {
                family = "Monospaced";
            }
            return Font.font(family, getFontSize());
        }
        
        @Override
        public @NotNull TerminalColor getDefaultForeground() {
            Color fgColor = Color.web(settings.getForegroundColor());
            return TerminalColor.rgb(
                    (int) (fgColor.getRed() * 255),
                    (int) (fgColor.getGreen() * 255),
                    (int) (fgColor.getBlue() * 255)
            );
        }
        
        @Override
        public @NotNull TerminalColor getDefaultBackground() {
            Color bgColor = Color.web(settings.getBackgroundColor());
            return TerminalColor.rgb(
                    (int) (bgColor.getRed() * 255),
                    (int) (bgColor.getGreen() * 255),
                    (int) (bgColor.getBlue() * 255)
            );
        }
        
        @Override
        public boolean altSendsEscape() {
            // On macOS, Option+key produces special characters (e.g. Option+7 = |, Option+5 = [,
            // Option+8 = {, Option+L = @, Option+N = ~). Returning false lets the OS handle these
            // combinations natively instead of sending ESC+key sequences.
            String os = System.getProperty("os.name", "").toLowerCase();
            return !os.contains("mac");
        }
        
        @Override
        public boolean audibleBell() {
            return false; // Disable bell sound!
        }
        
        @Override
        public boolean enableMouseReporting() {
            return true;
        }
        
        @Override
        public boolean copyOnSelect() {
            return false;
        }
        
        @Override
        public boolean pasteOnMiddleMouseClick() {
            return true;
        }
        
        @Override
        public boolean emulateX11CopyPaste() {
            return false;
        }
        
        @Override
        public boolean useInverseSelectionColor() {
            return true;
        }
        
        @Override
        public int getBufferMaxLinesCount() {
            return 10000;
        }
    }
}
