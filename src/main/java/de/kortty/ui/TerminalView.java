package de.kortty.ui;

import com.sithtermfx.core.CursorShape;
import com.sithtermfx.core.Terminal;
import com.sithtermfx.core.TerminalColor;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.TerminalModelListener;
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
import de.kortty.core.ConnectionSettingsSupport;
import de.kortty.core.Mosh4jTtyConnector;
import de.kortty.core.SshTtyConnector;
import de.kortty.core.ObservableTtyConnector;
import de.kortty.core.LocalShellTtyConnector;
import de.kortty.core.agent.AgentCommandRunner;
import de.kortty.core.agent.AgentCommandRunners;
import de.kortty.core.NativeMoshTtyConnector;
import de.kortty.core.DisconnectListener;
import de.kortty.core.TerminalEmulationSupport;
import de.kortty.core.TerminalAgentCommandSupport;
import de.kortty.core.TerminalAgentCompletionSupport;
import de.kortty.core.TerminalRecordingScreenSnapshot;
import de.kortty.core.TerminalRecordingSession;
import de.kortty.core.TerminalRecordingStyleRun;
import de.kortty.core.TerminalColorControlSequenceFilter;
import de.kortty.model.AiProfile;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.TerminalRecordingScope;
import de.kortty.model.Theme;
import de.kortty.plugin.terminaleffects.TerminalEffectAnimationSpeed;
import de.kortty.plugin.terminaleffects.TerminalEffectAppearance;
import de.kortty.plugin.terminaleffects.TerminalEffectConnectorWrapper;
import de.kortty.plugin.terminaleffects.TerminalEffectContext;
import de.kortty.plugin.terminaleffects.TerminalEffectPlugin;
import de.kortty.plugin.terminaleffects.TerminalEffectSession;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javafx.scene.control.ProgressIndicator;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.scene.layout.VBox;
import javafx.scene.Scene;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

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
    public interface TerminalTextFileLoadHandler {
        void handle(@Nullable TerminalAgentRunContext runContext, String selectedText);
    }

    @FunctionalInterface
    public interface TerminalAgentShortcutHandler {
        void handle(String rawCommand, @Nullable TerminalAgentRunContext runContext);
    }

    @FunctionalInterface
    public interface TerminalAgentContextHandler {
        void handle(@Nullable TerminalAgentRunContext runContext);
    }

    /**
     * Handler for the AI-agent "Ask" context-menu entry. Receives the terminal text that was
     * selected when the menu was opened, so the question can be answered about that selection.
     */
    @FunctionalInterface
    public interface TerminalAgentAskHandler {
        void handle(@Nullable TerminalAgentRunContext runContext, String selectedText);
    }

    public record TerminalAgentRunContext(
        @Nullable SithTermFxWidget widget,
        ObservableTtyConnector connector,
        @Nullable String workingDirectory) {

        public TerminalAgentRunContext(@Nullable SithTermFxWidget widget, ObservableTtyConnector connector) {
            this(widget, connector, null);
        }
    }

    private static final class TerminalAgentRunState {
        private final String runId;
        private final TerminalAgentRunContext runContext;
        private final Runnable cancelHandler;
        private final Runnable toggleDetailsHandler;
        private Timeline shellKeepAliveTimeline;

        private TerminalAgentRunState(
            String runId,
            TerminalAgentRunContext runContext,
            @Nullable Runnable cancelHandler,
            @Nullable Runnable toggleDetailsHandler) {
            this.runId = runId;
            this.runContext = runContext;
            this.cancelHandler = cancelHandler;
            this.toggleDetailsHandler = toggleDetailsHandler;
        }
    }

    private record TerminalAgentShortcutInputFilterRegistration(
        @Nullable SithTermFxWidget widget,
        TerminalAgentShortcutInputFilter filter) {
    }

    /**
     * Immutable per-pane appearance override contributed by an active terminal effect.
     * Any {@code null} field means "inherit the tab baseline" (the connection settings).
     */
    private record PaneAppearanceOverride(
            String foregroundColor,
            String backgroundColor,
            String cursorColor,
            String cursorStyle,
            String fontFamily,
            Float fontSize) {

        /** Builds an override from an effect appearance, blank-to-null normalized, preserving cursor-blink preference. */
        static PaneAppearanceOverride from(TerminalEffectAppearance a, ConnectionSettings baseline) {
            if (a == null) {
                return null;
            }
            String cursorStyle = blankToNull(a.cursorStyle());
            if (cursorStyle != null && baseline != null) {
                boolean blinking = TerminalCursorStyleSupport.isBlinkingStyle(baseline.getCursorStyle());
                cursorStyle = TerminalCursorStyleSupport.withBlinkingPreference(cursorStyle, blinking);
            }
            Float fontSize = (a.fontSize() != null && a.fontSize() > 0) ? a.fontSize().floatValue() : null;
            return new PaneAppearanceOverride(
                    blankToNull(a.foregroundColor()),
                    blankToNull(a.backgroundColor()),
                    blankToNull(a.cursorColor()),
                    cursorStyle,
                    blankToNull(a.fontFamily()),
                    fontSize);
        }

        private static String blankToNull(String s) {
            return (s == null || s.isBlank()) ? null : s;
        }
    }

    /**
     * Runtime state of a terminal effect active on a single pane (widget). Replaces the former
     * tab-wide single-effect holder: effects are now owned per pane via {@link #paneEffects}.
     */
    private static final class PaneEffect {
        private final String pluginId;
        private TerminalEffectSession session;
        private ConnectionSettings baselineSettings;
        private double animationSpeed;
        private PaneAppearanceOverride override;

        private PaneEffect(String pluginId, double animationSpeed, ConnectionSettings baselineSettings) {
            this.pluginId = pluginId;
            this.animationSpeed = animationSpeed;
            this.baselineSettings = baselineSettings;
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
    // Single tab-wide font-size source. Every per-pane provider delegates its font-size reads/writes
    // here, so Cmd/Ctrl +/- zoom and reset stay global across all splits.
    private DynamicFontSizeSettingsProvider sharedFontSource;
    // One settings provider per pane (widget). Each carries a nullable per-pane appearance override so
    // an effect can recolor / re-font a single pane without touching its siblings.
    private final Map<SithTermFxWidget, KorTTYSettingsProvider> paneProviders = new ConcurrentHashMap<>();
    // Terminal background transparency as a percentage: 0 = fully opaque (default), 100 = fully
    // transparent. Applied to the window/default background alpha only; glyph colours stay opaque.
    // Read live by each pane's settings provider and by applyStyleStateColors.
    private volatile int backgroundTransparencyPercent = 0;
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
    private java.util.function.BooleanSupplier menuBarHiddenSupplier;
    private Runnable menuBarRestoreHandler;
    private TerminalTextFileLoadHandler terminalTextFileLoadHandler;
    private TerminalAgentContextHandler aiAgentHandler;
    private TerminalAgentAskHandler aiAgentAskHandler;
    private TerminalAgentContextHandler aiPlanningHandler;
    private TerminalAgentShortcutHandler terminalAgentShortcutHandler;
    private final Map<SithTermFxWidget, AiAgentActivityTabsPanel> terminalAgentActivityPanels = new ConcurrentHashMap<>();
    // Fired when the set of terminal widgets changes (split opened/closed) so a side dock can rebuild.
    private Runnable onWidgetSetChanged;
    // Fired when the user requests closing the tab from within the terminal (e.g. Ctrl+D on a local cmd/PowerShell).
    private Runnable onCloseTabRequest;
    // One terminal/split widget can host several concurrent agent runs, keyed by runId.
    private final Map<SithTermFxWidget, Map<String, TerminalAgentRunState>> terminalAgentRunStates = new ConcurrentHashMap<>();
    private ObservableTtyConnector.DataListener terminalLoggerDataListener;
    // Session journal: the live capture session survives reconnects (one journal per tab
    // lifetime); only the data listener hops to the new connector.
    private volatile de.kortty.core.SessionJournalSession journalSession;
    private ObservableTtyConnector.DataListener journalDataListener;
    private ObservableTtyConnector journalAttachedConnector;
    private String journalTabSessionId;
    private java.util.function.Consumer<SithTermFxWidget> journalScreenshotHandler;
    private Runnable journalNoteHandler;
    private final Map<SshTtyConnector, ObservableTtyConnector.DataListener> terminalAgentPromptDataListeners = new ConcurrentHashMap<>();
    private final Map<ObservableTtyConnector, TerminalAgentShortcutInputFilterRegistration>
        terminalAgentShortcutInputFilters = new ConcurrentHashMap<>();
    private final Map<SithTermFxWidget, TerminalModelListener> terminalRecordingModelListeners = new ConcurrentHashMap<>();
    private final Map<ObservableTtyConnector, ObservableTtyConnector.InputActivityListener> terminalRecordingInputListeners = new ConcurrentHashMap<>();
    private final Map<SithTermFxWidget, StringBuilder> agentShortcutBuffers = new ConcurrentHashMap<>();
    private final StringBuilder agentShortcutPromptTail = new StringBuilder();
    private final Map<SshTtyConnector, StringBuilder> terminalAgentOscBuffers = new ConcurrentHashMap<>();
    private volatile boolean agentShortcutPromptReady;
    private TerminalAgentCompletionPopup agentCompletionPopup;
    private volatile boolean timestampGuttersVisibleState;
    private volatile boolean terminalScrollbarsVisible = true;
    // Seed speed for panes that don't yet have an effect (used when a pane inherits/gains one).
    private double defaultEffectAnimationSpeed = TerminalEffectAnimationSpeed.DEFAULT;
    // One active effect per pane (widget). Absent key = no effect on that pane.
    private final Map<SithTermFxWidget, PaneEffect> paneEffects = new ConcurrentHashMap<>();
    private volatile TerminalRecordingSession terminalRecordingSession;
    private volatile TerminalRecordingScope terminalRecordingScope = TerminalRecordingScope.ACTIVE_SPLIT;
    private volatile List<SithTermFxWidget> terminalRecordingTargetWidgets = List.of();
    
    public TerminalView(ServerConnection connection, String password) {
        this(connection, password, null);
    }
    
    public TerminalView(ServerConnection connection, String password, de.kortty.model.TemporarySSHKey temporarySSHKey) {
        if (connection == null) {
            throw new IllegalArgumentException("connection must not be null");
        }
        this.connection = connection;
        this.password = password;
        this.temporarySSHKey = temporarySSHKey;
        // Capture connection's font size and family at open (before theme/default resolution) for zoom reset.
        ConnectionSettings connSettingsForReset = connection.getSettings();
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
        
        ConnectionSettings connSettings = resolveInitialSettings(connection);
        if (connection.getSettings() == null) {
            connection.setSettings(new ConnectionSettings());
            logger.warn("Connection '{}' had no settings, using effective terminal defaults", connection.getName());
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

    private static ConnectionSettings resolveInitialSettings(ServerConnection connection) {
        return resolveEffectiveSettings(connection != null ? connection.getSettings() : null);
    }

    private static ConnectionSettings resolveEffectiveSettings(ConnectionSettings connectionSettings) {
        try {
            var app = KorTTYApplication.getInstance();
            var globalSettings = app != null && app.getGlobalSettingsManager() != null
                    ? app.getGlobalSettingsManager().getSettings()
                    : null;
            var globalDefaults = globalSettings != null ? globalSettings.getDefaultTerminalSettings() : null;
            return ConnectionSettingsSupport.effectiveTerminalSettings(connectionSettings, globalDefaults);
        } catch (Exception e) {
            return ConnectionSettingsSupport.effectiveTerminalSettings(
                    connectionSettings,
                    null);
        }
    }
    
    private void initializeTerminal() {
        // Single tab-wide font-size source (enables Cmd/Ctrl+Plus/Minus zoom). Every per-pane provider
        // delegates its font size here, so zoom/reset stay global across all splits.
        sharedFontSource = new DynamicFontSizeSettingsProvider(defaultFontSize);

        // Re-render every terminal when the shared font size changes.
        sharedFontSource.addFontSizeListener(this::updateAllTerminalFonts);

        // Create SplitConnectorFactory that creates new SSH connections for splits
        SplitConnectorFactory connectorFactory = this::createSplitConnector;

        // Each pane gets its OWN settings provider (carrying a nullable per-pane appearance override),
        // all sharing the single font-size source above.
        java.util.function.Supplier<com.sithtermfx.ui.settings.SettingsProvider> providerFactory =
                () -> new KorTTYSettingsProvider(settings, sharedFontSource, () -> backgroundTransparencyPercent);

        // Create TerminalSplitPane with split support
        // Right-click context menu will show: Font size options + Split right/down + Close split
        splitPane = new TerminalSplitPane(providerFactory, connectorFactory, widget -> {
            registerPaneProvider(widget);
            setupWidgetEventHandlers(widget);
            applyCursorShape(widget);
            setupTimestampGutter(widget);
            applyTerminalScrollbarVisibility(widget);
        }, widget -> gutterMap.get(widget), this::createTerminalAgentActivityPanel, this::decorateTerminalConnector); // Left panel factory: returns the gutter created in setupTimestampGutter
        splitPane.setOnWidgetClosed(this::onPaneClosed); // Stop the pane's effect + release its provider/agent runs when its split closes
        splitPane.setOnWidgetSplitCreated(this::inheritEffectOnSplit); // New split panes inherit the source pane's effect
        splitPane.setOnLastWidgetSessionEnded(() -> { // Only the LAST pane's exit closes the tab (splits close just their pane)
            if (onCloseTabRequest != null) {
                onCloseTabRequest.run();
            }
        });
        // Telemetry: count broadcast toggles (never keystrokes — the data path stays uninstrumented).
        splitPane.setOnBroadcastModeChanged(enabled ->
            de.kortty.telemetry.Telemetry.track(de.kortty.telemetry.TelemetryEvents.BROADCAST_TOGGLED, Map.of(
                "enabled", enabled,
                "split_count", splitPane != null ? splitPane.getWidgetCount() : 0)));
        splitPane.setResetZoomCallback(this::resetZoom); // Reset zoom to connection or global default (not hardcoded 14)
        
        // Register extra context menu items: Theme, Reconnect, Timestamp toggle
        splitPane.setExtraMenuItemsFactory(widget -> {
            java.util.List<javafx.scene.control.MenuItem> items = new java.util.ArrayList<>();
            if (menuBarRestoreHandler != null && menuBarHiddenSupplier != null && menuBarHiddenSupplier.getAsBoolean()) {
                javafx.scene.control.MenuItem showMenuBarItem =
                    new javafx.scene.control.MenuItem(I18n.get("menu.view.menuBar"));
                showMenuBarItem.setOnAction(e -> Platform.runLater(menuBarRestoreHandler));
                items.add(showMenuBarItem);
                items.add(new javafx.scene.control.SeparatorMenuItem());
            }
            String selectedText = getSelectedText(widget);
            List<AiProfile> aiProfiles = getConfiguredAiProfiles();
            boolean hasSelectedText = selectedText != null && !selectedText.isBlank();
            boolean hasExecutableAgentAction = aiAgentHandler != null && isTerminalAgentExecutionEnabled();
            boolean hasAgentActions = hasExecutableAgentAction || aiAgentAskHandler != null || aiPlanningHandler != null;
            if (shouldShowLoadAsTextFileContextItem(selectedText, terminalTextFileLoadHandler != null)) {
                javafx.scene.control.MenuItem loadTextFileItem =
                    new javafx.scene.control.MenuItem(I18n.get("terminal.contextMenu.loadAsTextFile"));
                loadTextFileItem.setOnAction(e -> terminalTextFileLoadHandler.handle(
                    createTerminalAgentRunContext(widget),
                    selectedText));
                items.add(loadTextFileItem);
                items.add(new javafx.scene.control.SeparatorMenuItem());
            }
            if (shouldShowAiContextMenu(aiProfiles, hasSelectedText, hasAgentActions)) {
                javafx.scene.control.Menu aiMenu = new javafx.scene.control.Menu(I18n.get("terminal.contextMenu.ai"));
                if (hasExecutableAgentAction) {
                    javafx.scene.control.MenuItem agentItem = new javafx.scene.control.MenuItem(I18n.get("terminal.contextMenu.ai.agent"));
                    agentItem.setOnAction(e -> aiAgentHandler.handle(createTerminalAgentRunContext(widget)));
                    aiMenu.getItems().add(agentItem);
                }
                if (aiAgentAskHandler != null) {
                    javafx.scene.control.MenuItem agentAskItem = new javafx.scene.control.MenuItem(I18n.get("terminal.contextMenu.ai.agentAsk"));
                    // Capture the selection at menu time so the question is answered about it.
                    agentAskItem.setOnAction(e -> aiAgentAskHandler.handle(createTerminalAgentRunContext(widget), selectedText));
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
            if (isSessionJournalActive()) {
                if (journalScreenshotHandler != null) {
                    javafx.scene.control.MenuItem journalShotItem =
                        new javafx.scene.control.MenuItem(I18n.get("terminal.contextMenu.journalScreenshot"));
                    journalShotItem.setOnAction(e -> journalScreenshotHandler.accept(widget));
                    items.add(journalShotItem);
                }
                if (journalNoteHandler != null) {
                    javafx.scene.control.MenuItem journalNoteItem =
                        new javafx.scene.control.MenuItem(I18n.get("terminal.contextMenu.journalNote"));
                    journalNoteItem.setOnAction(e -> journalNoteHandler.run());
                    items.add(journalNoteItem);
                }
                if (journalScreenshotHandler != null || journalNoteHandler != null) {
                    items.add(new javafx.scene.control.SeparatorMenuItem());
                }
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
            if (TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
                items.add(buildPaneEffectMenu(widget));
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
            if (isEventTargetWithinAgentActivityPanel(event)) {
                // Keys typed into the agent's own UI (e.g. the sudo password prompt) must reach that
                // control's handlers instead of being swallowed by the terminal input lock below, so
                // ENTER can confirm the password just like the OK button does.
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
            // Ctrl+D closes the tab for local cmd.exe/PowerShell shells, which (unlike bash and SSH)
            // do not exit on EOF. Elsewhere Ctrl+D stays EOF so it reaches the shell/program normally.
            if (event.getCode() == KeyCode.D && event.isControlDown()
                && !event.isAltDown() && !event.isShiftDown() && !event.isMetaDown()) {
                SithTermFxWidget focused = eventWidget != null ? eventWidget : splitPane.getFocusedWidget();
                TtyConnector focusedConnector = focused != null ? focused.getTtyConnector() : null;
                if (ctrlDShouldCloseTab(focusedConnector)) {
                    event.consume();
                    if (onCloseTabRequest != null) {
                        onCloseTabRequest.run();
                    }
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

        // Ctrl + mouse wheel (Cmd on macOS) zooms the terminal font instead of scrolling the buffer.
        // Filter runs before the terminal panel's own scroll handling, so we consume to suppress scrollback.
        splitPane.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (!(e.isControlDown() || e.isShortcutDown())) {
                return;
            }
            double dy = e.getDeltaY();
            if (dy == 0) {
                dy = e.getDeltaX();
            }
            if (dy > 0) {
                zoom(1);
                e.consume();
            } else if (dy < 0) {
                zoom(-1);
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

    /** Adds a "show menu bar" entry to the terminal context menu while the menu bar is hidden. */
    public void setMenuBarRestoreHandler(java.util.function.BooleanSupplier menuBarHidden, Runnable restoreHandler) {
        this.menuBarHiddenSupplier = menuBarHidden;
        this.menuBarRestoreHandler = restoreHandler;
    }

    public void setTerminalTextFileLoadHandler(@Nullable TerminalTextFileLoadHandler terminalTextFileLoadHandler) {
        this.terminalTextFileLoadHandler = terminalTextFileLoadHandler;
    }

    public void setAiAgentHandler(TerminalAgentContextHandler aiAgentHandler) {
        this.aiAgentHandler = aiAgentHandler;
    }

    public void setAiAgentAskHandler(TerminalAgentAskHandler aiAgentAskHandler) {
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
        @Nullable TerminalAgentRunContext runContext,
        String runId,
        boolean locked,
        @Nullable Runnable cancelHandler,
        @Nullable Runnable toggleDetailsHandler) {
        TerminalAgentRunContext resolvedContext = runContext != null ? runContext : captureTerminalAgentRunContext();
        SithTermFxWidget widget = resolvedContext != null ? resolvedContext.widget() : null;
        if (runId == null || runId.isBlank()) {
            return;
        }
        if (locked && resolvedContext != null && widget != null) {
            Map<String, TerminalAgentRunState> runs =
                terminalAgentRunStates.computeIfAbsent(widget, ignored -> new ConcurrentHashMap<>());
            TerminalAgentRunState previousState = runs.get(runId);
            stopTerminalAgentShellKeepAlive(previousState);
            TerminalAgentRunState state = new TerminalAgentRunState(runId, resolvedContext, cancelHandler, toggleDetailsHandler);
            runs.put(runId, state);
            startTerminalAgentShellKeepAlive(state);
        } else if (!locked && widget != null) {
            Map<String, TerminalAgentRunState> runs = terminalAgentRunStates.get(widget);
            if (runs != null) {
                TerminalAgentRunState previousState = runs.remove(runId);
                stopTerminalAgentShellKeepAlive(previousState);
                if (runs.isEmpty()) {
                    terminalAgentRunStates.remove(widget);
                }
            }
        }
        // Keep the cursor visible even while locked: the user may keep typing the next command
        // during an active run, so the terminal must not look frozen.
        Platform.runLater(() -> setCursorVisible(widget, true));
    }

    public @Nullable TerminalAgentRunContext captureTerminalAgentRunContext() {
        SithTermFxWidget focused = splitPane != null ? splitPane.getFocusedWidget() : terminalWidget;
        return createTerminalAgentRunContext(focused);
    }

    private @Nullable TerminalAgentRunContext createTerminalAgentRunContext(@Nullable SithTermFxWidget widget) {
        if (widget == null) {
            return null;
        }
        TtyConnector connector = unwrapTerminalEffectConnector(widget.getTtyConnector());
        if (connector instanceof ObservableTtyConnector agentConnector && agentConnector.isConnected()) {
            return createTerminalAgentRunContext(widget, agentConnector, null);
        }
        return null;
    }

    private @Nullable TerminalAgentRunContext createTerminalAgentRunContext(ObservableTtyConnector connector) {
        return createTerminalAgentRunContext(connector, null);
    }

    private @Nullable TerminalAgentRunContext createTerminalAgentRunContext(ObservableTtyConnector connector, @Nullable String workingDirectory) {
        if (connector == null || !connector.isConnected()) {
            return null;
        }
        return createTerminalAgentRunContext(findWidgetForConnector(connector), connector, workingDirectory);
    }

    private TerminalAgentRunContext createTerminalAgentRunContext(
        @Nullable SithTermFxWidget widget,
        ObservableTtyConnector connector,
        @Nullable String workingDirectory) {

        String promptDirectory = resolveWorkingDirectoryFromPrompt(widget, connector);
        String cachedDirectory = connector.getCurrentWorkingDirectory();
        if (connector instanceof LocalShellTtyConnector localConnector
            && localConnector.hasUnresolvedWorkingDirectoryChange()
            && workingDirectory == null
            && promptDirectory == null) {
            // After a submitted cd/pushd/popd there is no safe JAT-side fallback until either the
            // OS or a new absolute prompt confirms the current directory.
            cachedDirectory = null;
        }
        String directory = firstAbsoluteWorkingDirectory(
            workingDirectory,
            promptDirectory,
            cachedDirectory);
        return new TerminalAgentRunContext(widget, connector, directory);
    }

    private @Nullable String resolveWorkingDirectoryFromPrompt(@Nullable SithTermFxWidget widget, ObservableTtyConnector connector) {
        try {
            String screenLines = widget != null && widget.getTerminalTextBuffer() != null
                ? widget.getTerminalTextBuffer().getScreenLines()
                : "";
            return extractWorkingDirectoryFromVisibleScreen(
                screenLines,
                connector != null ? connector.getHomeRemoteDirectory() : null);
        } catch (Exception e) {
            logger.debug("Failed to resolve working directory from terminal prompt: {}", e.getMessage());
            return null;
        }
    }

    private static String firstAbsoluteWorkingDirectory(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (isAbsoluteWorkingDirectorySyntax(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static boolean isAbsoluteWorkingDirectorySyntax(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String value = candidate.strip();
        return value.startsWith("/")
            || value.matches("^[A-Za-z]:[\\\\/].*")
            || value.startsWith("\\\\");
    }

    private @Nullable SithTermFxWidget findWidgetForConnector(TtyConnector connector) {
        if (connector == null || splitPane == null) {
            return null;
        }
        for (SithTermFxWidget widget : splitPane.getAllWidgets()) {
            if (widget != null && unwrapTerminalEffectConnector(widget.getTtyConnector()) == connector) {
                return widget;
            }
        }
        return null;
    }

    public @Nullable AiAgentActivityTabsPanel getTerminalAgentActivityPanel(@Nullable TerminalAgentRunContext runContext) {
        TerminalAgentRunContext resolvedContext = runContext != null ? runContext : captureTerminalAgentRunContext();
        SithTermFxWidget widget = resolvedContext != null ? resolvedContext.widget() : null;
        return widget != null ? terminalAgentActivityPanels.get(widget) : null;
    }

    public boolean isTerminalAgentRunActive(@Nullable TerminalAgentRunContext runContext) {
        TerminalAgentRunContext resolvedContext = runContext != null ? runContext : captureTerminalAgentRunContext();
        SithTermFxWidget widget = resolvedContext != null ? resolvedContext.widget() : null;
        return widget != null && hasTerminalAgentRuns(widget);
    }

    /** All terminal widgets of this view in split order (base terminal + any splits). */
    public java.util.List<SithTermFxWidget> getOrderedWidgets() {
        if (splitPane != null) {
            return splitPane.getAllWidgets();
        }
        return terminalWidget != null ? java.util.List.of(terminalWidget) : java.util.List.of();
    }

    /**
     * All agent-capable connectors across this view's split widgets (terminal-effect wrappers
     * unwrapped). Used by the AI swarm to enumerate every open server in this tab.
     */
    public java.util.List<ObservableTtyConnector> getAllAgentConnectors() {
        java.util.List<ObservableTtyConnector> result = new java.util.ArrayList<>();
        for (SithTermFxWidget widget : getOrderedWidgets()) {
            if (widget == null) {
                continue;
            }
            TtyConnector base = unwrapTerminalEffectConnector(widget.getTtyConnector());
            if (base instanceof ObservableTtyConnector agentConnector) {
                result.add(agentConnector);
            }
        }
        if (result.isEmpty() && ttyConnector instanceof ObservableTtyConnector agentConnector) {
            result.add(agentConnector);
        }
        return result;
    }

    /** The agent activity panel hosted by the given widget, or null. */
    public @Nullable AiAgentActivityTabsPanel getAgentPanelForWidget(@Nullable SithTermFxWidget widget) {
        return widget != null ? terminalAgentActivityPanels.get(widget) : null;
    }

    /** Detaches (true) / re-attaches (false) all bottom agent panels so they can be docked elsewhere. */
    public void setBottomPanelsDetached(boolean detached) {
        if (splitPane != null) {
            splitPane.setBottomPanelsDetached(detached);
        }
    }

    /** Re-inserts a widget's agent panel into its split's bottom slot (used when leaving side-dock). */
    public void reattachAgentBottomPanel(@Nullable SithTermFxWidget widget) {
        if (splitPane != null && widget != null) {
            splitPane.reattachBottomPanel(widget, terminalAgentActivityPanels.get(widget));
        }
    }

    /** Aggregated run-state counts [awaitingInput, working, paused, done] across all widgets' panels. */
    public int[] aggregateTerminalAgentRunCounts() {
        int[] total = new int[4];
        for (AiAgentActivityTabsPanel panel : terminalAgentActivityPanels.values()) {
            if (panel == null) {
                continue;
            }
            int[] counts = panel.runCounts();
            for (int i = 0; i < total.length && i < counts.length; i++) {
                total[i] += counts[i];
            }
        }
        return total;
    }

    /** Registers a callback fired (on the FX thread) when the set of terminal widgets changes. */
    public void setOnWidgetSetChanged(@Nullable Runnable onWidgetSetChanged) {
        this.onWidgetSetChanged = onWidgetSetChanged;
    }

    private void fireWidgetSetChanged() {
        Runnable callback = onWidgetSetChanged;
        if (callback != null) {
            Platform.runLater(callback);
        }
    }

    /** Number of active (locked) agent runs hosted by the widget for the given run context. */
    public int terminalAgentRunCount(@Nullable SithTermFxWidget widget) {
        Map<String, TerminalAgentRunState> runs = widget != null ? terminalAgentRunStates.get(widget) : null;
        return runs != null ? runs.size() : 0;
    }

    private boolean hasTerminalAgentRuns(@Nullable SithTermFxWidget widget) {
        Map<String, TerminalAgentRunState> runs = widget != null ? terminalAgentRunStates.get(widget) : null;
        return runs != null && !runs.isEmpty();
    }

    /** Whether another concurrent terminal-agent run may start given the active count and cap. */
    static boolean canStartTerminalAgentRun(int active, int max) {
        return max > 0 && active < max;
    }

    public String buildTerminalAgentScopedSessionId(String sessionId, @Nullable TerminalAgentRunContext runContext) {
        String baseSessionId = sessionId != null && !sessionId.isBlank() ? sessionId : "terminal-agent";
        ObservableTtyConnector connector = runContext != null ? runContext.connector() : null;
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
        for (AiAgentActivityTabsPanel panel : terminalAgentActivityPanels.values()) {
            if (panel != null) {
                panel.applyTheme(theme);
            }
        }
    }

    /** The pane the tab-level (no-arg) effect API acts on: the focused split, else the first, else the primary. */
    private @Nullable SithTermFxWidget getPrimaryPane() {
        if (splitPane != null) {
            SithTermFxWidget focused = splitPane.getFocusedWidget();
            if (focused != null) {
                return focused;
            }
            List<SithTermFxWidget> all = splitPane.getAllWidgets();
            if (!all.isEmpty()) {
                return all.get(0);
            }
        }
        return terminalWidget;
    }

    /** Registers a pane's settings provider so its per-pane appearance override can be resolved later. */
    private void registerPaneProvider(SithTermFxWidget widget) {
        if (widget != null && widget.getSettingsProvider() instanceof KorTTYSettingsProvider provider) {
            paneProviders.put(widget, provider);
        }
    }

    /** Called when a split pane is closed: stop its effect and release its per-pane state. */
    private void onPaneClosed(SithTermFxWidget widget) {
        stopPaneEffect(widget);
        paneProviders.remove(widget);
        discardTerminalAgentRunsForWidget(widget);
        releasePaneState(widget);
    }

    /**
     * Drops every per-pane map entry that would otherwise keep the closed widget — and through
     * it its canvas and scrollback buffer — strongly reachable for the life of the tab. The
     * consumers of these maps tolerate missing entries, so removal degrades to no-ops for any
     * late callbacks.
     */
    private void releasePaneState(SithTermFxWidget widget) {
        if (widget == null) {
            return;
        }
        gutterMap.remove(widget);
        lastTimestampLineByWidget.remove(widget);
        timestampHistoryByWidget.remove(widget);
        awaitingCommandCompletionByWidget.remove(widget);
        PauseTransition completionTimer = commandCompletionTimerByWidget.remove(widget);
        if (completionTimer != null) {
            // A running transition is pinned by the FX master timer and its onFinished holds the widget.
            completionTimer.stop();
        }
        commandStartLineByWidget.remove(widget);
        agentShortcutBuffers.remove(widget);
        TerminalModelListener recordingListener = terminalRecordingModelListeners.remove(widget);
        if (recordingListener != null && widget.getTerminalTextBuffer() != null) {
            widget.getTerminalTextBuffer().removeModelListener(recordingListener);
        }
        if (terminalRecordingTargetWidgets.contains(widget)) {
            terminalRecordingTargetWidgets = terminalRecordingTargetWidgets.stream()
                .filter(target -> target != widget)
                .toList();
        }
        // Connector-keyed state: splits normally own their connector, but only release it when
        // no surviving pane still runs on the same one.
        TtyConnector baseConnector = unwrapTerminalEffectConnector(widget.getTtyConnector());
        if (baseConnector == null || connectorInUseByOtherPane(widget, baseConnector)) {
            return;
        }
        reportTerminalDisconnected(baseConnector);
        if (baseConnector instanceof ObservableTtyConnector observableConnector) {
            releaseAgentShortcutInputInterceptor(observableConnector);
            ObservableTtyConnector.InputActivityListener inputListener =
                terminalRecordingInputListeners.remove(observableConnector);
            if (inputListener != null) {
                observableConnector.removeInputActivityListener(inputListener);
            }
        }
    }

    private boolean connectorInUseByOtherPane(SithTermFxWidget closingWidget, TtyConnector baseConnector) {
        if (splitPane == null) {
            return false;
        }
        for (SithTermFxWidget widget : splitPane.getAllWidgets()) {
            if (widget != null && widget != closingWidget
                && unwrapTerminalEffectConnector(widget.getTtyConnector()) == baseConnector) {
                return true;
            }
        }
        return false;
    }

    /** New split panes inherit the source pane's effect (id + speed), per the feature design. */
    private void inheritEffectOnSplit(SithTermFxWidget newWidget, SplitRequest request) {
        if (newWidget == null || request == null) {
            return;
        }
        SithTermFxWidget source = request.getParentWidget();
        if (source == null) {
            return;
        }
        String effectId = getTerminalEffectPluginId(source);
        if (effectId == null) {
            return;
        }
        setTerminalEffectAnimationSpeed(newWidget, getTerminalEffectAnimationSpeed(source));
        setTerminalEffectPluginId(newWidget, effectId);
    }

    // ---- Tab-level (no-arg) API: forwards to the primary/focused pane so MainWindow and the
    // connection-default tab-open path keep working unchanged. ----

    public @Nullable String getTerminalEffectPluginId() {
        return getTerminalEffectPluginId(getPrimaryPane());
    }

    public double getTerminalEffectAnimationSpeed() {
        return getTerminalEffectAnimationSpeed(getPrimaryPane());
    }

    public void setTerminalEffectAnimationSpeed(double speed) {
        setTerminalEffectAnimationSpeed(getPrimaryPane(), speed);
    }

    public void setTerminalEffectPluginId(@Nullable String pluginId) {
        setTerminalEffectPluginId(getPrimaryPane(), pluginId);
    }

    // ---- Per-pane API. ----

    public @Nullable String getTerminalEffectPluginId(@Nullable SithTermFxWidget pane) {
        PaneEffect effect = pane != null ? paneEffects.get(pane) : null;
        return effect != null ? effect.pluginId : null;
    }

    public double getTerminalEffectAnimationSpeed(@Nullable SithTermFxWidget pane) {
        PaneEffect effect = pane != null ? paneEffects.get(pane) : null;
        return effect != null ? effect.animationSpeed : defaultEffectAnimationSpeed;
    }

    public void setTerminalEffectAnimationSpeed(@Nullable SithTermFxWidget pane, double speed) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> setTerminalEffectAnimationSpeed(pane, speed));
            return;
        }
        double normalized = TerminalEffectAnimationSpeed.normalize(speed);
        defaultEffectAnimationSpeed = normalized; // seed panes that gain an effect later (incl. inherit-on-split)
        PaneEffect effect = pane != null ? paneEffects.get(pane) : null;
        if (effect != null) {
            effect.animationSpeed = normalized;
        }
    }

    public void setTerminalEffectPluginId(@Nullable SithTermFxWidget pane, @Nullable String pluginId) {
        if (pane == null) {
            return;
        }
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> setTerminalEffectPluginId(pane, pluginId));
            return;
        }
        if (!TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
            stopPaneEffect(pane);
            return;
        }
        String normalizedPluginId = normalizeTerminalEffectPluginId(pluginId);
        if (Objects.equals(getTerminalEffectPluginId(pane), normalizedPluginId)) {
            return;
        }

        stopPaneEffect(pane);
        if (normalizedPluginId == null) {
            return;
        }

        KorTTYApplication app = KorTTYApplication.getInstance();
        if (app == null || app.getTerminalEffectPluginManager() == null) {
            logger.warn("Terminal effect plugin manager is not available");
            return;
        }
        TerminalEffectPlugin plugin = app.getTerminalEffectPluginManager()
                .findPlugin(normalizedPluginId)
                .orElse(null);
        if (plugin == null) {
            logger.warn("Terminal effect plugin '{}' is not available", normalizedPluginId);
            return;
        }

        ConnectionSettings baselineSettings = new ConnectionSettings(settings);
        // Build the holder first so the context callbacks can capture it; the session is set once created.
        PaneEffect effect = new PaneEffect(
                normalizedPluginId,
                getTerminalEffectAnimationSpeed(pane),
                baselineSettings);
        paneEffects.put(pane, effect);

        StackPane overlayHost = resolveOverlayHost(pane);
        TerminalEffectContext context = new TerminalEffectContext(
                normalizedPluginId,
                this,
                overlayHost,
                () -> List.of(pane),
                () -> effect.animationSpeed,
                appearance -> applyPaneEffectAppearance(pane, normalizedPluginId, appearance),
                () -> restorePaneAppearance(pane, normalizedPluginId));

        try {
            TerminalEffectSession session = plugin.createSession(context);
            if (session == null) {
                throw new IllegalStateException("Plugin returned no terminal effect session");
            }
            effect.session = session;
            // Plugin id only — resolved successfully, so this counts real on-screen usage.
            de.kortty.telemetry.Telemetry.track(de.kortty.telemetry.TelemetryEvents.TERMINAL_EFFECT_APPLIED,
                Map.of("effect", normalizedPluginId));
            session.start();
            logger.info("Activated terminal effect plugin '{}' on a pane", normalizedPluginId);
        } catch (Exception e) {
            logger.warn("Could not activate terminal effect plugin '{}': {}", normalizedPluginId, e.getMessage(), e);
            paneEffects.remove(pane);
            clearPaneOverride(pane);
        }
    }

    /** Stops and removes the effect on a single pane, restoring that pane's baseline appearance. */
    private void stopPaneEffect(@Nullable SithTermFxWidget pane) {
        if (pane == null) {
            return;
        }
        PaneEffect effect = paneEffects.remove(pane);
        if (effect == null) {
            return;
        }
        try {
            if (effect.session != null) {
                effect.session.stop();
            }
        } catch (Exception e) {
            logger.warn("Terminal effect plugin '{}' failed to stop: {}", effect.pluginId, e.getMessage(), e);
        } finally {
            clearPaneOverride(pane);
            logger.info("Deactivated terminal effect plugin '{}'", effect.pluginId);
        }
    }

    /** Stops every pane's effect (used on tab dispose). */
    private void stopAllEffects() {
        for (SithTermFxWidget pane : List.copyOf(paneEffects.keySet())) {
            stopPaneEffect(pane);
        }
    }

    /**
     * Where a pane's effect overlay is mounted: that pane's own StackPane wrapper, so the overlay is
     * confined to the pane. Prefers the split pane's map; if that misses, walks up from the pane's node
     * to its wrapper (the StackPane whose userData is the widget). Only a widget-less view uses the tab
     * container as a last resort — never a real split pane, which would bleed across siblings.
     */
    private StackPane resolveOverlayHost(SithTermFxWidget pane) {
        StackPane host = splitPane != null ? splitPane.getWidgetOverlayHost(pane) : null;
        if (host == null && pane != null && pane.getPane() != null) {
            javafx.scene.Node node = pane.getPane().getParent();
            while (node != null) {
                if (node instanceof StackPane sp && sp.getUserData() == pane) {
                    host = sp;
                    break;
                }
                node = node.getParent();
            }
        }
        if (host == null) {
            logger.warn("No per-pane overlay host found for a terminal pane; "
                    + "the effect overlay will fall back to the whole tab container");
        }
        return host != null ? host : terminalContainer;
    }

    /** Applies an effect's appearance to a SINGLE pane via its provider override — never touches siblings. */
    private void applyPaneEffectAppearance(SithTermFxWidget pane, String pluginId, TerminalEffectAppearance appearance) {
        PaneEffect effect = paneEffects.get(pane);
        if (effect == null || !effect.pluginId.equals(pluginId) || appearance == null) {
            return;
        }
        PaneAppearanceOverride override = PaneAppearanceOverride.from(appearance, effect.baselineSettings);
        effect.override = override;
        KorTTYSettingsProvider provider = paneProviders.get(pane);
        if (provider != null) {
            provider.setOverride(override);
        }
        refreshPaneAppearance(pane);
    }

    /** Restore callback a plugin may invoke; clears the pane's override if this plugin still owns the pane. */
    private void restorePaneAppearance(SithTermFxWidget pane, String pluginId) {
        PaneEffect effect = paneEffects.get(pane);
        if (effect != null && !effect.pluginId.equals(pluginId)) {
            return; // a different effect owns this pane now
        }
        clearPaneOverride(pane);
    }

    /** Drops a pane's appearance override and re-renders that pane at the baseline settings. */
    private void clearPaneOverride(SithTermFxWidget pane) {
        KorTTYSettingsProvider provider = paneProviders.get(pane);
        if (provider != null) {
            provider.setOverride(null);
        }
        refreshPaneAppearance(pane);
    }

    /** Re-applies colors, cursor shape and font to a single pane from its current (override-or-baseline) source. */
    private void refreshPaneAppearance(SithTermFxWidget pane) {
        if (pane == null) {
            return;
        }
        applyStyleStateColors(pane);
        applyCursorShape(pane);
        setCursorVisible(pane, true);
        reinitPaneFont(pane);
    }

    /**
     * Builds the per-pane "Terminal Effect" submenu for the given pane's right-click menu. Selecting an
     * effect here is runtime-only (never persisted to the connection), by design.
     */
    private javafx.scene.control.Menu buildPaneEffectMenu(SithTermFxWidget widget) {
        javafx.scene.control.Menu menu = new javafx.scene.control.Menu(I18n.get("plugin.terminalEffect"));
        if (!TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
            menu.setDisable(true);
            return menu;
        }
        javafx.scene.control.ToggleGroup group = new javafx.scene.control.ToggleGroup();
        String activePluginId = getTerminalEffectPluginId(widget);

        javafx.scene.control.RadioMenuItem noneItem =
                new javafx.scene.control.RadioMenuItem(I18n.get("plugin.none"));
        noneItem.setToggleGroup(group);
        noneItem.setSelected(activePluginId == null);
        noneItem.setOnAction(e -> setTerminalEffectPluginId(widget, null));
        menu.getItems().add(noneItem);

        KorTTYApplication app = KorTTYApplication.getInstance();
        var manager = app != null ? app.getTerminalEffectPluginManager() : null;
        if (manager == null || manager.getPlugins().isEmpty()) {
            return menu;
        }
        menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        for (var plugin : manager.getPlugins()) {
            javafx.scene.control.RadioMenuItem pluginItem =
                    new javafx.scene.control.RadioMenuItem(plugin.displayName());
            pluginItem.setToggleGroup(group);
            pluginItem.setSelected(plugin.id().equals(activePluginId));
            String id = plugin.id();
            pluginItem.setOnAction(e -> setTerminalEffectPluginId(widget, id));
            menu.getItems().add(pluginItem);
        }
        menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        menu.getItems().add(buildPaneEffectSpeedItem(widget, activePluginId != null));
        return menu;
    }

    private javafx.scene.control.CustomMenuItem buildPaneEffectSpeedItem(SithTermFxWidget widget, boolean enabled) {
        TerminalEffectUiSupport.AnimationSpeedControls speedControls =
                TerminalEffectUiSupport.createAnimationSpeedControls(getTerminalEffectAnimationSpeed(widget));
        speedControls.valueProperty().addListener((obs, oldValue, newValue) ->
                setTerminalEffectAnimationSpeed(widget, newValue.doubleValue()));
        javafx.scene.layout.VBox content = speedControls.root();
        content.setPadding(new javafx.geometry.Insets(4, 8, 6, 8));
        javafx.scene.control.CustomMenuItem item = new javafx.scene.control.CustomMenuItem(content);
        item.setHideOnClick(false);
        item.setDisable(!enabled);
        return item;
    }

    private static @Nullable String normalizeTerminalEffectPluginId(@Nullable String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return null;
        }
        return pluginId.trim();
    }

    private Region createTerminalAgentActivityPanel(SithTermFxWidget widget) {
        AiAgentActivityTabsPanel panel = new AiAgentActivityTabsPanel();
        terminalAgentActivityPanels.put(widget, panel);
        fireWidgetSetChanged();
        return panel;
    }

    /** Cancels and discards all agent runs (and the activity wrapper) hosted by the given widget. */
    private void discardTerminalAgentRunsForWidget(@Nullable SithTermFxWidget widget) {
        if (widget == null) {
            return;
        }
        Map<String, TerminalAgentRunState> runs = terminalAgentRunStates.remove(widget);
        if (runs != null) {
            for (TerminalAgentRunState state : runs.values()) {
                stopTerminalAgentShellKeepAlive(state);
                Runnable handler = state != null ? state.cancelHandler : null;
                if (handler != null) {
                    Platform.runLater(handler);
                }
            }
        }
        AiAgentActivityTabsPanel panel = terminalAgentActivityPanels.remove(widget);
        if (panel != null) {
            panel.cancelAllRuns();
        }
        fireWidgetSetChanged();
    }

    /** Cancels every active agent run across all widgets (used during full cleanup). */
    private void cancelAllTerminalAgentRuns() {
        for (Map<String, TerminalAgentRunState> runs : terminalAgentRunStates.values()) {
            if (runs == null) {
                continue;
            }
            for (TerminalAgentRunState state : runs.values()) {
                Runnable handler = state != null ? state.cancelHandler : null;
                if (handler != null) {
                    try {
                        handler.run();
                    } catch (Exception e) {
                        logger.debug("Failed to cancel terminal agent run during cleanup: {}", e.getMessage());
                    }
                }
            }
        }
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
        for (Map<String, TerminalAgentRunState> runs : terminalAgentRunStates.values()) {
            if (runs == null) {
                continue;
            }
            for (TerminalAgentRunState state : runs.values()) {
                stopTerminalAgentShellKeepAlive(state);
            }
        }
        terminalAgentRunStates.clear();
    }

    private void sendTerminalAgentShellKeepAlive(TerminalAgentRunState state) {
        ObservableTtyConnector agentConnector = state != null && state.runContext != null ? state.runContext.connector() : null;
        if (agentConnector == null) {
            stopTerminalAgentShellKeepAlive(state);
            return;
        }
        // Shell keep-alive only applies to SSH channels; local shells need none.
        if (!(agentConnector instanceof SshTtyConnector sshConnector)) {
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

    static boolean shouldShowLoadAsTextFileContextItem(@Nullable String selectedText, boolean hasHandler) {
        return hasHandler && selectedText != null && !selectedText.isBlank();
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
                logger.debug("splitPane DRAG_OVER filter: hasFiles={}", event.getDragboard().hasFiles());
                if (handleFileDragOver(event)) {
                    logger.debug("splitPane DRAG_OVER filter: handling, consuming event");
                    event.consume();
                }
            });
            splitPane.addEventFilter(DragEvent.DRAG_DROPPED, event -> {
                logger.debug("splitPane DRAG_DROPPED filter: hasFiles={}", event.getDragboard().hasFiles());
                if (handleFileDragDropped(event)) {
                    logger.debug("splitPane DRAG_DROPPED filter: handling, consuming event");
                    event.consume();
                }
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
        return focused != null ? unwrapTerminalEffectConnector(focused.getTtyConnector()) : null;
    }

    public SshTtyConnector getActiveSshConnector() {
        TtyConnector focusedConnector = getFocusedConnector();
        if (focusedConnector instanceof SshTtyConnector sshConnector) {
            return sshConnector;
        }
        return ttyConnector instanceof SshTtyConnector sshConnector ? sshConnector : null;
    }

    /**
     * The active connector usable by the AI agent (SSH or local shell). Unlike
     * {@link #getActiveSshConnector()} this also returns local-shell connectors.
     */
    public ObservableTtyConnector getActiveAgentConnector() {
        TtyConnector focusedConnector = getFocusedConnector();
        if (focusedConnector instanceof ObservableTtyConnector agentConnector) {
            return agentConnector;
        }
        return ttyConnector instanceof ObservableTtyConnector agentConnector ? agentConnector : null;
    }

    /** Builds an {@link AgentCommandRunner} for the active connector (SSH or local), or null. */
    public AgentCommandRunner createActiveAgentRunner() {
        TerminalAgentRunContext runContext = captureTerminalAgentRunContext();
        if (runContext != null) {
            return AgentCommandRunners.forConnector(
                runContext.connector(),
                runContext.workingDirectory());
        }
        return AgentCommandRunners.forConnector(getActiveAgentConnector());
    }

    /** Sets the handler invoked when the terminal requests its tab be closed (e.g. Ctrl+D on cmd/PowerShell). */
    public void setOnCloseTabRequest(Runnable onCloseTabRequest) {
        this.onCloseTabRequest = onCloseTabRequest;
    }

    /** Number of terminal panes (splits) in this tab; 1 when there is no split. */
    public int getTerminalPaneCount() {
        return splitPane != null ? splitPane.getWidgetCount() : 1;
    }

    /**
     * Whether closing this tab warrants a confirmation prompt. It does when there is more than one
     * split pane (closing them all at once), or when the single active pane looks busy — a foreground
     * command is running. An idle single-pane terminal sitting at its shell prompt closes without a
     * prompt.
     *
     * <p>Busy detection is per connector: a local shell checks the OS process tree; SSH uses the shell
     * prompt marker (there is no remote-process access). Mosh and anything unknown are treated as not
     * busy — a Mosh session survives a client disconnect anyway.
     */
    public boolean shouldConfirmClose() {
        if (getTerminalPaneCount() > 1) {
            return true;
        }
        SithTermFxWidget widget = splitPane != null ? splitPane.getFocusedWidget() : null;
        if (widget == null) {
            widget = terminalWidget;
        }
        if (widget == null) {
            return false;
        }
        TtyConnector base = unwrapTerminalEffectConnector(widget.getTtyConnector());
        if (base instanceof LocalShellTtyConnector local) {
            return local.hasRunningChildProcess();
        }
        if (base instanceof SshTtyConnector) {
            // No remote-process visibility over SSH: "busy" means not currently at a shell prompt.
            return !agentShortcutPromptReady;
        }
        return false;
    }

    /**
     * True when Ctrl+D should close the tab instead of sending EOF: only for local cmd.exe/PowerShell
     * shells, which do not exit on EOF. Bash-family local shells (Git Bash/Cygwin/WSL), $SHELL, custom
     * commands, and SSH/Mosh keep Ctrl+D as the normal EOF control character.
     */
    private boolean ctrlDShouldCloseTab(TtyConnector connector) {
        TtyConnector base = unwrapTerminalEffectConnector(connector);
        if (!(base instanceof LocalShellTtyConnector local) || !local.isConnected()) {
            return false;
        }
        String command = local.getConnection() != null ? local.getConnection().getLocalShellCommand() : null;
        String exe;
        if (command == null || command.isBlank()) {
            // Default local shell: PowerShell on Windows, $SHELL (EOF-honoring) elsewhere.
            exe = LocalShellTtyConnector.isWindows() ? "powershell.exe" : "";
        } else {
            java.util.List<String> tokens = de.kortty.model.ServerConnection.tokenizeLocalShellCommand(command);
            exe = tokens.isEmpty() ? "" : tokens.get(0)
                .replace('\\', '/').replaceAll(".*/", "").toLowerCase(java.util.Locale.ROOT);
        }
        return exe.contains("cmd") || exe.contains("powershell") || exe.contains("pwsh");
    }

    public int getRecordingWidgetCount() {
        if (splitPane != null) {
            return splitPane.getWidgetCount();
        }
        return terminalWidget != null ? 1 : 0;
    }

    public synchronized void attachTerminalRecordingSession(
        TerminalRecordingSession session,
        TerminalRecordingScope scope) {
        detachTerminalRecordingSession();
        if (session == null) {
            return;
        }
        terminalRecordingSession = session;
        terminalRecordingScope = scope != null ? scope : TerminalRecordingScope.ACTIVE_SPLIT;
        List<SithTermFxWidget> targetWidgets = resolveRecordingWidgets(terminalRecordingScope);
        terminalRecordingTargetWidgets = List.copyOf(targetWidgets);
        for (SithTermFxWidget widget : targetWidgets) {
            installTerminalRecordingModelListener(widget);
            installTerminalRecordingInputListener(widget != null ? widget.getTtyConnector() : null);
            recordTerminalRecordingSnapshot(widget);
        }
    }

    public synchronized void detachTerminalRecordingSession() {
        for (Map.Entry<SithTermFxWidget, TerminalModelListener> entry : terminalRecordingModelListeners.entrySet()) {
            SithTermFxWidget widget = entry.getKey();
            if (widget != null && widget.getTerminalTextBuffer() != null) {
                widget.getTerminalTextBuffer().removeModelListener(entry.getValue());
            }
        }
        terminalRecordingModelListeners.clear();
        for (Map.Entry<ObservableTtyConnector, ObservableTtyConnector.InputActivityListener> entry : terminalRecordingInputListeners.entrySet()) {
            entry.getKey().removeInputActivityListener(entry.getValue());
        }
        terminalRecordingInputListeners.clear();
        terminalRecordingSession = null;
        terminalRecordingTargetWidgets = List.of();
    }

    private List<SithTermFxWidget> resolveRecordingWidgets(TerminalRecordingScope scope) {
        if (scope == TerminalRecordingScope.WHOLE_TAB && splitPane != null) {
            return splitPane.getAllWidgets();
        }
        SithTermFxWidget focused = splitPane != null ? splitPane.getFocusedWidget() : terminalWidget;
        return focused != null ? List.of(focused) : List.of();
    }

    private void installTerminalRecordingModelListener(SithTermFxWidget widget) {
        TerminalRecordingSession session = terminalRecordingSession;
        if (session == null || widget == null || widget.getTerminalTextBuffer() == null) {
            return;
        }
        if (!isTerminalRecordingWidgetInScope(widget)) {
            return;
        }
        terminalRecordingModelListeners.computeIfAbsent(widget, key -> {
            TerminalModelListener listener = () -> recordTerminalRecordingSnapshot(key);
            key.getTerminalTextBuffer().addModelListener(listener);
            return listener;
        });
    }

    private void installTerminalRecordingInputListener(TtyConnector connector) {
        TerminalRecordingSession session = terminalRecordingSession;
        TtyConnector baseConnector = unwrapTerminalEffectConnector(connector);
        if (session == null || !(baseConnector instanceof ObservableTtyConnector observableConnector)) {
            return;
        }
        if (!isTerminalRecordingConnectorInScope(observableConnector)) {
            return;
        }
        terminalRecordingInputListeners.computeIfAbsent(observableConnector, key -> {
            ObservableTtyConnector.InputActivityListener listener = byteCount -> {
                if (terminalRecordingSession == session && isTerminalRecordingConnectorInScope(key)) {
                    session.recordUserInputActivity();
                }
            };
            key.addInputActivityListener(listener);
            return listener;
        });
    }

    private boolean isTerminalRecordingWidgetInScope(SithTermFxWidget widget) {
        if (widget == null || terminalRecordingSession == null) {
            return false;
        }
        return terminalRecordingScope == TerminalRecordingScope.WHOLE_TAB
            || terminalRecordingTargetWidgets.contains(widget);
    }

    private boolean isTerminalRecordingConnectorInScope(ObservableTtyConnector connector) {
        if (connector == null || terminalRecordingSession == null) {
            return false;
        }
        if (terminalRecordingScope == TerminalRecordingScope.WHOLE_TAB) {
            return true;
        }
        for (SithTermFxWidget widget : terminalRecordingTargetWidgets) {
            if (widget != null && unwrapTerminalEffectConnector(widget.getTtyConnector()) == connector) {
                return true;
            }
        }
        return false;
    }

    private void recordTerminalRecordingSnapshot(SithTermFxWidget widget) {
        TerminalRecordingSession session = terminalRecordingSession;
        if (session == null || widget == null || widget.getTerminalTextBuffer() == null) {
            return;
        }
        try {
            session.recordScreenSnapshot(
                "terminal-" + Integer.toHexString(System.identityHashCode(widget)),
                captureTerminalRecordingSnapshot(widget));
        } catch (IllegalStateException e) {
            logger.warn("Could not record terminal screen snapshot: {}", e.getMessage());
        }
    }

    private TerminalRecordingScreenSnapshot captureTerminalRecordingSnapshot(SithTermFxWidget widget) {
        var textBuffer = widget.getTerminalTextBuffer();
        int pixelWidth = widget.getTerminalPanel() != null ? Math.max(0, widget.getTerminalPanel().getPixelWidth()) : 0;
        int pixelHeight = widget.getTerminalPanel() != null ? Math.max(0, widget.getTerminalPanel().getPixelHeight()) : 0;
        boolean captureColors = isTerminalRecordingColorCaptureEnabled();

        textBuffer.lock();
        try {
            List<TerminalRecordingStyleRun> styleRuns = captureColors
                ? captureTerminalStyleRuns(textBuffer, settings)
                : List.of();
            return new TerminalRecordingScreenSnapshot(
                textBuffer.getScreenLines(),
                textBuffer.getWidth(),
                textBuffer.getHeight(),
                pixelWidth,
                pixelHeight,
                styleRuns);
        } finally {
            textBuffer.unlock();
        }
    }

    private boolean isTerminalRecordingColorCaptureEnabled() {
        try {
            GlobalSettings globalSettings = KorTTYApplication.getInstance()
                .getGlobalSettingsManager()
                .getSettings();
            return globalSettings != null && globalSettings.isTerminalRecordingCaptureColorsEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<TerminalRecordingStyleRun> captureTerminalStyleRuns(
        com.sithtermfx.core.model.TerminalTextBuffer textBuffer,
        ConnectionSettings settings) {
        List<TerminalRecordingStyleRun> runs = new ArrayList<>();
        for (int row = 0; row < textBuffer.getHeight(); row++) {
            var line = textBuffer.getLine(row);
            if (line == null || line.isNulOrEmpty()) {
                continue;
            }
            int column = 0;
            for (var entry : line.getEntries()) {
                String text = entry.getText() != null ? entry.getText().toString() : "";
                if (!text.isEmpty()) {
                    TextStyle style = entry.getStyle();
                    runs.add(new TerminalRecordingStyleRun(
                        row,
                        column,
                        text,
                        terminalColorToHex(style != null ? style.getForeground() : null, style, settings, true),
                        terminalColorToHex(style != null ? style.getBackground() : null, style, settings, false),
                        terminalStyleOptions(style)));
                }
                column += Math.max(0, entry.getLength());
            }
        }
        return runs;
    }

    private static List<String> terminalStyleOptions(TextStyle style) {
        if (style == null) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        for (TextStyle.Option option : TextStyle.Option.values()) {
            if (style.hasOption(option)) {
                options.add(option.name());
            }
        }
        return options;
    }

    private static String terminalColorToHex(
        TerminalColor color,
        TextStyle style,
        ConnectionSettings settings,
        boolean foreground) {
        if (color == null) {
            return null;
        }
        if (color.isIndexed()) {
            return indexedTerminalColorToHex(color.getColorIndex(), style, settings, foreground);
        }
        com.sithtermfx.core.Color resolved;
        try {
            resolved = color.toColor();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (resolved == null) {
            return null;
        }
        return String.format(
            java.util.Locale.ROOT,
            "#%02X%02X%02X",
            resolved.getRed(),
            resolved.getGreen(),
            resolved.getBlue());
    }

    private static String indexedTerminalColorToHex(
        int colorIndex,
        TextStyle style,
        ConnectionSettings settings,
        boolean foreground) {
        if (colorIndex < 0) {
            return null;
        }
        if (colorIndex < 16) {
            int ansiIndex = colorIndex % 8;
            boolean bright = colorIndex >= 8
                || (foreground
                    && settings != null
                    && settings.isBoldAsBright()
                    && style != null
                    && style.hasOption(TextStyle.Option.BOLD));
            return settings != null
                ? settings.getAnsiColor(ansiIndex, bright)
                : defaultAnsiColor(ansiIndex, bright);
        }
        if (colorIndex < 232) {
            int value = colorIndex - 16;
            int red = xtermColorCubeValue((value / 36) % 6);
            int green = xtermColorCubeValue((value / 6) % 6);
            int blue = xtermColorCubeValue(value % 6);
            return rgbToHex(red, green, blue);
        }
        if (colorIndex < 256) {
            int level = 8 + ((colorIndex - 232) * 10);
            return rgbToHex(level, level, level);
        }
        return null;
    }

    private static int xtermColorCubeValue(int component) {
        return component == 0 ? 0 : 55 + (component * 40);
    }

    private static String defaultAnsiColor(int index, boolean bright) {
        return switch (index) {
            case 0 -> bright ? "#7F7F7F" : "#000000";
            case 1 -> bright ? "#FF0000" : "#CD0000";
            case 2 -> bright ? "#00FF00" : "#00CD00";
            case 3 -> bright ? "#FFFF00" : "#CDCD00";
            case 4 -> bright ? "#5C5CFF" : "#0000EE";
            case 5 -> bright ? "#FF00FF" : "#CD00CD";
            case 6 -> bright ? "#00FFFF" : "#00CDCD";
            default -> bright ? "#FFFFFF" : "#E5E5E5";
        };
    }

    private static String rgbToHex(int red, int green, int blue) {
        return String.format(
            java.util.Locale.ROOT,
            "#%02X%02X%02X",
            Math.max(0, Math.min(255, red)),
            Math.max(0, Math.min(255, green)),
            Math.max(0, Math.min(255, blue)));
    }

    private TtyConnector decorateTerminalConnector(SithTermFxWidget widget, TtyConnector connector) {
        if (connector == null) {
            return null;
        }
        TtyConnector baseConnector = unwrapTerminalEffectConnector(connector);
        applyTerminalEmulation(widget, baseConnector);
        installAgentShortcutInputInterceptor(widget, baseConnector);
        installTerminalRecordingInputListener(baseConnector);
        PaneEffect effect = paneEffects.get(widget);
        TtyConnector decorated = baseConnector;
        if (effect == null || effect.session == null) {
            return new TerminalColorFilteringTtyConnector(
                decorated,
                () -> settings == null || settings.isTerminalColorsEnabled(),
                this::reportTerminalActivity);
        }
        try {
            decorated = effect.session.wrapConnector(widget, baseConnector);
        } catch (Exception e) {
            logger.warn("Terminal effect '{}' failed to wrap connector: {}", effect.pluginId, e.getMessage());
            decorated = baseConnector;
        }
        return new TerminalColorFilteringTtyConnector(
            decorated,
            () -> settings == null || settings.isTerminalColorsEnabled(),
            this::reportTerminalActivity);
    }

    private TtyConnector unwrapTerminalEffectConnector(TtyConnector connector) {
        TtyConnector current = connector;
        while (true) {
            if (current instanceof TerminalColorFilteringTtyConnector wrapper) {
                current = wrapper.delegate();
                continue;
            }
            if (current instanceof TerminalEffectConnectorWrapper wrapper) {
                current = wrapper.delegate();
                continue;
            }
            return current;
        }
    }

    private void applyTerminalEmulation(SithTermFxWidget widget, TtyConnector connector) {
        if (widget == null || connector == null) {
            return;
        }
        ServerConnection targetConnection = connection;
        if (connector instanceof SshTtyConnector sshConnector) {
            targetConnection = sshConnector.getConnection();
        } else if (connector instanceof Mosh4jTtyConnector moshConnector) {
            targetConnection = moshConnector.getConnection();
        } else if (connector instanceof NativeMoshTtyConnector nativeMoshConnector) {
            targetConnection = nativeMoshConnector.getConnection();
        }
        widget.setEmulationType(TerminalEmulationSupport.fromConnection(targetConnection));
    }

    private void installAgentShortcutInputInterceptor(SithTermFxWidget widget, TtyConnector connector) {
        if (!(connector instanceof ObservableTtyConnector observableConnector)) {
            return;
        }
        boolean preferRemoteShortcut = false;
        if (observableConnector instanceof SshTtyConnector sshConnector) {
            sshConnector.addDataListener(getTerminalAgentPromptDataListener(sshConnector));
            preferRemoteShortcut = sshConnector.hasShellStartupCommandConfigured();
        }

        TerminalAgentShortcutInputFilterRegistration existing =
            terminalAgentShortcutInputFilters.get(observableConnector);
        if (existing != null && existing.widget() == widget) {
            observableConnector.setInputInterceptor(existing.filter()::filter);
            return;
        }

        boolean shellHandlesRecognizedShortcut = preferRemoteShortcut;
        TerminalAgentShortcutInputFilter inputFilter = new TerminalAgentShortcutInputFilter(
            bufferedCommand -> bufferedCommand != null ? bufferedCommand.trim() : "",
            rawCommand -> shouldLetRemoteShellHandleAgentShortcut(
                shellHandlesRecognizedShortcut,
                rawCommand,
                getTerminalAgentCommandName(),
                isTerminalAgentCommandNameCaseInsensitive()),
            rawCommand -> shouldInterceptFilteredAgentShortcut(widget, rawCommand),
            rawCommand -> dispatchFilteredTerminalAgentShortcut(widget, rawCommand),
            this::forwardJournalInputLine);
        terminalAgentShortcutInputFilters.put(
            observableConnector,
            new TerminalAgentShortcutInputFilterRegistration(widget, inputFilter));
        observableConnector.setInputInterceptor(inputFilter::filter);
        logger.debug(
            "Installed terminal AI input interceptor for {} (widgetBound={}, preferRemote={})",
            observableConnector.getClass().getSimpleName(),
            widget != null,
            preferRemoteShortcut);
    }

    private void releaseAgentShortcutInputInterceptor(TtyConnector connector) {
        TtyConnector baseConnector = unwrapTerminalEffectConnector(connector);
        if (!(baseConnector instanceof ObservableTtyConnector observableConnector)) {
            return;
        }
        TerminalAgentShortcutInputFilterRegistration registration =
            terminalAgentShortcutInputFilters.remove(observableConnector);
        if (registration != null) {
            observableConnector.setInputInterceptor(null);
            if (registration.widget() != null) {
                agentShortcutBuffers.remove(registration.widget());
            }
        }
        if (observableConnector instanceof SshTtyConnector sshConnector) {
            ObservableTtyConnector.DataListener promptListener =
                terminalAgentPromptDataListeners.remove(sshConnector);
            if (promptListener != null) {
                sshConnector.removeDataListener(promptListener);
            }
            terminalAgentOscBuffers.remove(sshConnector);
        }
    }

    private void releaseAllAgentShortcutInputInterceptors() {
        for (ObservableTtyConnector connector : List.copyOf(terminalAgentShortcutInputFilters.keySet())) {
            releaseAgentShortcutInputInterceptor(connector);
        }
    }

    private ObservableTtyConnector.DataListener getTerminalAgentPromptDataListener(SshTtyConnector connector) {
        return terminalAgentPromptDataListeners.computeIfAbsent(
            connector,
            sourceConnector -> data -> recordAgentShortcutPromptSignal(sourceConnector, data));
    }

    private boolean usesTerminalConnector(TtyConnector candidate, TtyConnector expected) {
        return expected != null && unwrapTerminalEffectConnector(candidate) == expected;
    }

    private boolean shouldPreferRemoteAgentShortcut(TtyConnector connector) {
        TtyConnector baseConnector = unwrapTerminalEffectConnector(connector);
        return baseConnector instanceof SshTtyConnector sshConnector
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
        AtomicLong startTime = new AtomicLong(System.currentTimeMillis());
        javafx.scene.control.ProgressBar progressBar = new javafx.scene.control.ProgressBar(0);
        progressBar.setPrefWidth(300);
        progressBar.setProgress(0);
        javafx.scene.control.Label targetLabel = new javafx.scene.control.Label("");
        targetLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888;");
        javafx.scene.control.Label timeLabel = new javafx.scene.control.Label("0s");
        timeLabel.setStyle("-fx-font-size: 11px;");
        javafx.scene.control.Label statusLabel = new javafx.scene.control.Label(
            I18n.get("terminal.dragDrop.count", 0, total));
        javafx.scene.control.Label currentFileLabel = new javafx.scene.control.Label("");
        currentFileLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #aaaaaa;");
        javafx.scene.control.Button abortButton = new javafx.scene.control.Button(I18n.get("terminal.dragDrop.abort"));
        javafx.scene.layout.VBox vbox = new javafx.scene.layout.VBox(8,
            targetLabel, timeLabel, statusLabel, currentFileLabel, progressBar, abortButton);
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
                String trackedDir = sshConnector.getCurrentRemoteDirectory();
                String sftpStartDir = needsSftpStartDirectory(trackedDir) ? resolveSftpStartDirectory(sftp) : null;
                String remoteTargetDir = resolveDragDropRemoteDirectory(trackedDir, sftpStartDir);
                logger.debug(
                    "Using drag-drop remote directory: {} (tracked={}, sftpStart={})",
                    remoteTargetDir,
                    trackedDir,
                    sftpStartDir);
                final String remoteHome = remoteTargetDir;
                logger.debug("Drag-drop will upload to remote directory: {}", remoteHome);
                // Update target label with destination directory
                Platform.runLater(() -> {
                    targetLabel.setText(I18n.get("terminal.dragDrop.target", remoteHome));
                });
                int copied = 0;
                for (int i = 0; i < toUpload.size() && !aborted.get(); i++) {
                    PathPair p = toUpload.get(i);
                    String fullRemote = appendRemotePath(remoteHome, p.remote);
                    final String fileName = p.remote;
                    Platform.runLater(() -> {
                        long elapsed = (System.currentTimeMillis() - startTime.get()) / 1000;
                        timeLabel.setText(elapsed + "s");
                    });
                    uploadOne(sftp, p, fullRemote);
                    if (aborted.get()) break;
                    copied++;
                    final int done = copied;
                    Platform.runLater(() -> {
                        statusLabel.setText(I18n.get("terminal.dragDrop.count", done, total));
                        currentFileLabel.setText(fileName);
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
            mkdirsRemote(sftp, fullRemotePath);
            return;
        }
        mkdirsRemote(sftp, parentRemotePath(fullRemotePath));
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

    static String appendRemotePath(String basePath, String relativePath) {
        String base = basePath == null || basePath.isBlank() ? "." : basePath.trim();
        String relative = relativePath == null ? "" : relativePath.trim();
        if (relative.isEmpty()) {
            return base;
        }
        if (relative.startsWith("/")) {
            return relative;
        }
        if ("/".equals(base)) {
            return "/" + relative;
        }
        return base.endsWith("/") ? base + relative : base + "/" + relative;
    }

    private String resolveSftpStartDirectory(SftpClient sftp) {
        try {
            String directory = sftp.canonicalPath(".");
            if (directory != null && !directory.isBlank()) {
                return directory.trim();
            }
        } catch (IOException e) {
            logger.debug("Could not resolve SFTP start directory via canonicalPath('.'): {}", e.getMessage());
        }
        return ".";
    }

    private static boolean needsSftpStartDirectory(String trackedDirectory) {
        if (trackedDirectory == null || trackedDirectory.isBlank()) {
            return true;
        }
        String tracked = trackedDirectory.trim();
        return "~".equals(tracked) || tracked.startsWith("~/");
    }

    static String resolveDragDropRemoteDirectory(String trackedDirectory, String sftpStartDirectory) {
        String fallback = normalizedRemoteDirectoryOrCurrent(sftpStartDirectory);
        if (trackedDirectory == null || trackedDirectory.isBlank()) {
            return fallback;
        }
        String tracked = trackedDirectory.trim();
        if (tracked.startsWith("/")) {
            return tracked;
        }
        if ("~".equals(tracked)) {
            return fallback;
        }
        if (tracked.startsWith("~/")) {
            String relativeToHome = tracked.substring(2);
            if (relativeToHome.isBlank()) {
                return fallback;
            }
            return fallback.startsWith("/")
                ? appendRemotePath(fallback, relativeToHome)
                : relativeToHome;
        }
        return tracked;
    }

    private static String normalizedRemoteDirectoryOrCurrent(String remoteDirectory) {
        if (remoteDirectory == null || remoteDirectory.isBlank()) {
            return ".";
        }
        return remoteDirectory.trim();
    }

    static String parentRemotePath(String remotePath) {
        if (remotePath == null || remotePath.isBlank()) {
            return ".";
        }
        String normalized = remotePath.trim();
        int index = normalized.lastIndexOf('/');
        if (index < 0) {
            return ".";
        }
        if (index == 0) {
            return "/";
        }
        return normalized.substring(0, index);
    }

    private void mkdirsRemote(SftpClient sftp, String remoteDirectory) throws IOException {
        if (remoteDirectory == null || remoteDirectory.isBlank()
                || ".".equals(remoteDirectory) || "/".equals(remoteDirectory)) {
            return;
        }
        boolean absolute = remoteDirectory.startsWith("/");
        String current = absolute ? "/" : null;
        for (String part : remoteDirectory.split("/")) {
            if (part == null || part.isBlank() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                current = current == null ? part : appendRemotePath(current, part);
                continue;
            }
            current = current == null ? part : appendRemotePath(current, part);
            ensureRemoteDirectory(sftp, current);
        }
    }

    private void ensureRemoteDirectory(SftpClient sftp, String remoteDirectory) throws IOException {
        try {
            SftpClient.Attributes attrs = sftp.stat(remoteDirectory);
            if (!attrs.isDirectory()) {
                throw new IOException("Remote path exists but is not a directory: " + remoteDirectory);
            }
        } catch (SftpException e) {
            if (e.getStatus() != SftpConstants.SSH_FX_NO_SUCH_FILE) {
                throw e;
            }
            try {
                sftp.mkdir(remoteDirectory);
            } catch (SftpException mkdirException) {
                if (mkdirException.getStatus() != SftpConstants.SSH_FX_FILE_ALREADY_EXISTS) {
                    throw mkdirException;
                }
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
                reinitPaneFont(widget);
            }
        });
    }

    /**
     * Re-reads the font from a single pane's settings provider and re-renders it. Must run on the FX
     * thread. Used both by {@link #updateAllTerminalFonts()} and the per-pane effect appearance path.
     */
    private void reinitPaneFont(SithTermFxWidget widget) {
        if (widget == null) return;
        try {
            var terminalPanel = widget.getTerminalPanel();
            var method = terminalPanel.getClass().getDeclaredMethod("reinitFontAndResize");
            method.setAccessible(true);
            method.invoke(terminalPanel);
        } catch (Exception e) {
            logger.warn("Failed to update font for terminal widget: {}", e.getMessage());
        }
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
        
        // NEW_CONNECTION asks the user for a new connection; SAME_SERVER_NEW_SHELL
        // opens a new session to the same server.
        TtyConnector connector = request.getSplitMode() == SplitRequest.SplitMode.NEW_CONNECTION
            ? createNewConnectionForSplit()
            : createSameServerConnection();
        if (connector != null) {
            de.kortty.telemetry.Telemetry.track(de.kortty.telemetry.TelemetryEvents.TERMINAL_SPLIT_CREATED, Map.of(
                "mode", request.getSplitMode().name().toLowerCase(Locale.ROOT),
                "split_count", (splitPane != null ? splitPane.getWidgetCount() : 0) + 1));
        }
        return connector;
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
                    I18n.get("mosh.mosh4j.releaseUnavailable", de.kortty.core.Mosh4jTtyConnector.getMosh4jVersion()));
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
        } else if (targetConnection.getProtocol() == ConnectionProtocol.LOCAL_SHELL) {
            connector = new LocalShellTtyConnector(targetConnection);
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
            connector = sshConnector;
        }
        return connector;
    }

    private boolean connectConnector(TtyConnector connector) throws Exception {
        boolean connected;
        if (connector instanceof Mosh4jTtyConnector mosh4jConnector) {
            connected = mosh4jConnector.connect();
        } else if (connector instanceof NativeMoshTtyConnector nativeMoshConnector) {
            connected = nativeMoshConnector.connect();
        } else if (connector instanceof LocalShellTtyConnector localShellConnector) {
            connected = localShellConnector.connect();
        } else if (connector instanceof SshTtyConnector sshConnector) {
            connected = sshConnector.connect();
        } else {
            throw new IllegalStateException("Unsupported connector type: " + connector.getClass().getName());
        }
        if (connected) {
            reportTerminalConnected(connector);
        }
        return connected;
    }

    private void setConnectorDisconnectListener(TtyConnector connector, DisconnectListener listener) {
        DisconnectListener powerAwareListener = (reason, wasError) -> {
            releaseAgentShortcutInputInterceptor(connector);
            reportTerminalDisconnected(connector);
            listener.onDisconnect(reason, wasError);
        };
        if (connector instanceof Mosh4jTtyConnector mosh4jConnector) {
            mosh4jConnector.setDisconnectListener(powerAwareListener);
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
            nativeMoshConnector.setDisconnectListener(powerAwareListener);
            return;
        }
        if (connector instanceof LocalShellTtyConnector localShellConnector) {
            localShellConnector.setDisconnectListener(powerAwareListener);
            return;
        }
        if (connector instanceof SshTtyConnector sshConnector) {
            sshConnector.setDisconnectListener(powerAwareListener);
        }
    }

    private void reportTerminalConnected(TtyConnector connector) {
        var app = KorTTYApplication.getInstance();
        if (app != null && app.getPowerManagementCoordinator() != null) {
            app.getPowerManagementCoordinator().terminalConnected(connector);
        }
    }

    private void reportTerminalDisconnected(TtyConnector connector) {
        var app = KorTTYApplication.getInstance();
        if (app != null && app.getPowerManagementCoordinator() != null) {
            app.getPowerManagementCoordinator().terminalDisconnected(connector);
        }
    }

    private void reportTerminalActivity() {
        var app = KorTTYApplication.getInstance();
        if (app != null && app.getPowerManagementCoordinator() != null) {
            app.getPowerManagementCoordinator().recordTerminalActivity();
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
            Scene scene = new Scene(root);
            AppDesignStyleSupport.applyToScene(scene);
            connectingStage.setScene(scene);
            connectingStage.setResizable(false);
            connectingStage.setOnCloseRequest(event -> {
                if (done.getCount() > 0) {
                    event.consume();
                }
            });
            
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

            // Host-key confirmation and keyboard-interactive authentication both need a responsive
            // FX thread. Run the network handshake in a worker and keep processing FX events via the
            // nested progress-dialog event loop, just like the same-server split path above.
            AtomicReference<Boolean> connectSuccess = new AtomicReference<>(false);
            AtomicReference<Throwable> connectFailure = new AtomicReference<>();
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            Stage connectingStage = new Stage(StageStyle.UTILITY);
            connectingStage.initModality(Modality.APPLICATION_MODAL);
            connectingStage.setTitle(I18n.get("split.connecting"));
            javafx.scene.control.Label label = new javafx.scene.control.Label(I18n.get("split.connecting"));
            ProgressIndicator progress = new ProgressIndicator(-1);
            VBox root = new VBox(15, progress, label);
            root.setStyle("-fx-padding: 20; -fx-alignment: center;");
            Scene scene = new Scene(root);
            AppDesignStyleSupport.applyToScene(scene);
            connectingStage.setScene(scene);
            connectingStage.setResizable(false);
            connectingStage.setOnCloseRequest(event -> {
                if (done.getCount() > 0) {
                    event.consume();
                }
            });

            TtyConnector connectorToConnect = newConnector;
            Thread connectThread = new Thread(() -> {
                try {
                    connectSuccess.set(connectConnector(connectorToConnect));
                } catch (Throwable failure) {
                    connectFailure.set(failure);
                    logger.error("Split new-connection error: {}", failure.getMessage(), failure);
                } finally {
                    done.countDown();
                    Platform.runLater(connectingStage::close);
                }
            }, "SSH-Split-New-Connection");
            connectThread.setDaemon(true);
            connectThread.start();

            connectingStage.showAndWait();
            Throwable failure = connectFailure.get();
            if (failure != null) {
                if (failure instanceof Exception exception) {
                    throw exception;
                }
                throw new RuntimeException(failure);
            }

            boolean connected = Boolean.TRUE.equals(connectSuccess.get());
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
        // Effect panes keep their own appearance override automatically (applyStyleStateColors /
        // getTerminalFont resolve per pane), so a theme change never clobbers a pane running an effect.
    }

    /** The appearance override active on a pane (from its effect), or null when the pane has none. */
    private PaneAppearanceOverride paneOverride(SithTermFxWidget widget) {
        KorTTYSettingsProvider provider = widget != null ? paneProviders.get(widget) : null;
        return provider != null ? provider.getOverride() : null;
    }

    /** Effective foreground for a pane: its effect override if set, else the shared connection baseline. */
    private String effectiveForeground(SithTermFxWidget widget) {
        PaneAppearanceOverride o = paneOverride(widget);
        return (o != null && o.foregroundColor() != null) ? o.foregroundColor() : settings.getForegroundColor();
    }

    private String effectiveBackground(SithTermFxWidget widget) {
        PaneAppearanceOverride o = paneOverride(widget);
        return (o != null && o.backgroundColor() != null) ? o.backgroundColor() : settings.getBackgroundColor();
    }

    private String effectiveCursorStyle(SithTermFxWidget widget) {
        PaneAppearanceOverride o = paneOverride(widget);
        return (o != null && o.cursorStyle() != null) ? o.cursorStyle() : settings.getCursorStyle();
    }

    private void applyStyleStateColors(SithTermFxWidget widget) {
        if (widget == null || settings == null) return;
        var terminal = widget.getTerminal();
        if (!(terminal instanceof SithTerminal sithTerminal)) return;
        var styleState = sithTerminal.getStyleState();
        String fgWeb = effectiveForeground(widget);
        String bgWeb = effectiveBackground(widget);
        Color fg;
        Color bg;
        try {
            fg = Color.web(fgWeb);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid foreground color '{}', using white", fgWeb, e);
            fg = Color.WHITE;
        }
        try {
            bg = Color.web(bgWeb);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid background color '{}', using black", bgWeb, e);
            bg = Color.BLACK;
        }
        TerminalColor fgTc = TerminalColor.rgb(
                (int) (fg.getRed() * 255),
                (int) (fg.getGreen() * 255),
                (int) (fg.getBlue() * 255));
        // The default background carries the transparency alpha so plain cells match the translucent
        // window background painted by doRepaint (which keeps the terminal see-through uniformly).
        int bgAlpha = alphaForTransparencyPercent(backgroundTransparencyPercent);
        int bgR = (int) (bg.getRed() * 255);
        int bgG = (int) (bg.getGreen() * 255);
        int bgB = (int) (bg.getBlue() * 255);
        TerminalColor bgTc = bgAlpha >= 255
                ? TerminalColor.rgb(bgR, bgG, bgB)
                : TerminalColor.rgba(bgR, bgG, bgB, bgAlpha);
        TextStyle newStyle = new TextStyle(fgTc, bgTc);
        styleState.setDefaultStyle(newStyle);
        styleState.reset();
        Platform.runLater(() -> widget.getTerminalPanel().repaint());
    }

    /**
     * Maps a background-transparency percentage (0 = opaque, 100 = fully transparent) to an alpha
     * value in 0..255. Shared by the per-pane settings provider and applyStyleStateColors so the
     * window background and the default cell background use exactly the same alpha.
     */
    static int alphaForTransparencyPercent(int percent) {
        if (percent <= 0) return 255;
        if (percent >= 100) return 0;
        return (int) Math.round(255.0 * (100 - percent) / 100.0);
    }

    /**
     * Makes this terminal view's own container chain (the view itself, the terminal content stack and
     * the split pane) transparent so the desktop shows through the translucent terminal canvas. Needed
     * because the split pane otherwise carries an opaque {@code .split-pane} background from the CSS.
     * Only used in the borderless transparent-window mode.
     */
    public void setBackgroundTransparent(boolean transparent) {
        String style = transparent ? "-fx-background-color: transparent;" : null;
        setStyle(style);
        if (terminalContainer != null) {
            terminalContainer.setStyle(style);
        }
        if (splitPane != null) {
            // TerminalSplitPane also propagates this state to every current and future nested
            // JavaFX SplitPane. Otherwise the first split reinstates the theme's opaque background.
            splitPane.setBackgroundTransparent(transparent);
        }
    }

    /** Current terminal background transparency (0 = opaque, 100 = fully transparent). */
    public int getBackgroundTransparency() {
        return backgroundTransparencyPercent;
    }

    /**
     * Sets the terminal background transparency (0 = opaque, 100 = fully transparent) and repaints
     * every pane live. Only the background alpha changes; glyphs stay fully opaque.
     */
    public void setBackgroundTransparency(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        if (clamped == backgroundTransparencyPercent) {
            return;
        }
        backgroundTransparencyPercent = clamped;
        // Re-apply colours (updates each pane's default-style alpha) and force a repaint.
        for (SithTermFxWidget widget : paneProviders.keySet()) {
            applyStyleStateColors(widget);
        }
    }

    private void applyCursorShape(SithTermFxWidget widget) {
        if (widget == null || settings == null) return;
        String style = effectiveCursorStyle(widget);
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

        // While a terminal agent run is active the user may keep typing the next command; KEY_TYPED
        // events are no longer swallowed here (only stray run-control characters are dropped in the
        // canvas dispatcher), so the full command reaches the shell and the shortcut buffer.

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
        installTerminalRecordingModelListener(widget);
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
                    if (isTerminalAgentInputLockedFor(widget)
                        && isAgentRunControlCharacter(keyEvent.getCharacter())) {
                        // Swallow stray run-control characters (Ctrl+C / Ctrl+R / Esc) so they do not
                        // reach the shell; ordinary typed text is buffered and forwarded as usual.
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

    private boolean isEventTargetWithinAgentActivityPanel(@Nullable KeyEvent event) {
        if (event == null || !(event.getTarget() instanceof Node targetNode)) {
            return false;
        }
        for (AiAgentActivityTabsPanel panel : terminalAgentActivityPanels.values()) {
            if (isNodeWithin(targetNode, panel)) {
                return true;
            }
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
        if (event.getEventType() != KeyEvent.KEY_PRESSED) {
            return false;
        }
        if (!isAgentRunControlKey(event.getCode(), event.isControlDown(), event.isAltDown(), event.isMetaDown())) {
            // A run is active, but the user may keep composing the next command. Only the run-control
            // shortcuts are intercepted here; every other keystroke flows on to the shell and the
            // shortcut buffer so nothing the user types is silently dropped.
            return false;
        }
        TerminalAgentRunState state = resolveSelectedTerminalAgentRunState(widget);
        if (state == null) {
            // No run selected in the wrapper: nothing to control, let the key flow through.
            return false;
        }
        if (isAgentInputCancelShortcut(event.getCode(), event.isControlDown(), event.isAltDown(), event.isMetaDown())) {
            Runnable handler = state.cancelHandler;
            if (handler != null) {
                Platform.runLater(handler);
            }
        } else {
            Runnable handler = state.toggleDetailsHandler;
            if (handler != null) {
                Platform.runLater(handler);
            }
        }
        event.consume();
        return true;
    }

    /** Resolves the run state for the run currently selected in the widget's activity wrapper. */
    private @Nullable TerminalAgentRunState resolveSelectedTerminalAgentRunState(@Nullable SithTermFxWidget widget) {
        Map<String, TerminalAgentRunState> runs = widget != null ? terminalAgentRunStates.get(widget) : null;
        if (runs == null || runs.isEmpty()) {
            return null;
        }
        AiAgentActivityTabsPanel panel = terminalAgentActivityPanels.get(widget);
        String selectedRunId = panel != null ? panel.selectedRunId() : null;
        if (selectedRunId != null) {
            TerminalAgentRunState selected = runs.get(selectedRunId);
            if (selected != null) {
                return selected;
            }
        }
        return null;
    }

    private boolean isTerminalAgentInputLockedFor(@Nullable SithTermFxWidget widget) {
        return widget != null && hasTerminalAgentRuns(widget);
    }

    static boolean isAgentInputCancelShortcut(KeyCode code, boolean controlDown, boolean altDown, boolean metaDown) {
        return code == KeyCode.ESCAPE || (code == KeyCode.C && controlDown && !altDown && !metaDown);
    }

    /**
     * Run-control keys recognised while a terminal agent run is active: cancel (Esc / Ctrl+C) and
     * toggle-details (Ctrl+R). These are intercepted; every other key is passed through so the user
     * can keep typing the next command during a run without losing keystrokes.
     */
    static boolean isAgentRunControlKey(KeyCode code, boolean controlDown, boolean altDown, boolean metaDown) {
        return isAgentInputCancelShortcut(code, controlDown, altDown, metaDown)
            || (code == KeyCode.R && controlDown && !altDown && !metaDown);
    }

    /** Control characters used for run control that must not leak to the shell while a run is active. */
    static boolean isAgentRunControlCharacter(String character) {
        if (character == null || character.isEmpty()) {
            return false;
        }
        char c = character.charAt(0);
        return c == 3 || c == 18 || c == 27; // Ctrl+C, Ctrl+R, Esc
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
        if (event.getCode() == KeyCode.TAB
            && !event.isControlDown() && !event.isAltDown() && !event.isMetaDown() && !event.isShiftDown()) {
            if (agentShortcutPromptReady && showAgentCompletion(widget, buffer)) {
                event.consume();
            }
            return;
        }
        if (event.getCode() != KeyCode.ENTER) {
            return;
        }
        if (hasAgentShortcutInputFilter(widget)) {
            // The connector byte stream contains keyboard input and clipboard paste. Let its filter
            // be the sole Enter/dispatch authority; this key path only resets the TAB/history buffer.
            buffer.setLength(0);
            agentShortcutPromptReady = false;
            return;
        }
        // Concurrent runs are supported: a new `agent ...` command must still be intercepted and
        // launched as an additional run (a new tab) even while another run is active. The per-widget
        // concurrency cap is enforced when the run is dispatched.

        String typedCommand = buffer.toString();
        String rawCommand = resolveAgentShortcutCommand(widget, typedCommand);
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
        recordAgentInputHistory(typedCommand, commandName, caseInsensitiveCommandName);
        TerminalAgentRunContext runContext = createTerminalAgentRunContext(widget);
        Platform.runLater(() -> {
            TerminalAgentShortcutHandler handler = terminalAgentShortcutHandler;
            if (handler != null) {
                handler.handle(rawCommand, runContext);
            }
        });
    }

    private boolean hasAgentShortcutInputFilter(@Nullable SithTermFxWidget widget) {
        if (widget == null) {
            return false;
        }
        TtyConnector connector = unwrapTerminalEffectConnector(widget.getTtyConnector());
        return connector instanceof ObservableTtyConnector observableConnector
            && terminalAgentShortcutInputFilters.containsKey(observableConnector);
    }

    private boolean shouldInterceptFilteredAgentShortcut(
        @Nullable SithTermFxWidget widget,
        String rawCommand) {

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

    private void dispatchFilteredTerminalAgentShortcut(
        @Nullable SithTermFxWidget widget,
        String rawCommand) {

        String command = rawCommand != null ? rawCommand.trim() : "";
        Platform.runLater(() -> {
            TerminalAgentRunContext runContext = createTerminalAgentRunContext(widget);
            recordAgentInputHistory(
                command,
                getTerminalAgentCommandName(),
                isTerminalAgentCommandNameCaseInsensitive());
            TerminalAgentShortcutHandler handler = terminalAgentShortcutHandler;
            if (handler != null) {
                handler.handle(command, runContext);
            }
        });
    }

    /** Records the prompt part of an intercepted agent command into the persistent input history. */
    private void recordAgentInputHistory(String typedCommand, String commandName, boolean caseInsensitive) {
        try {
            // Record from the connector-filtered command. Unlike visible-screen reconstruction it has
            // no soft-wrap artifacts, and unlike KEY_TYPED it includes clipboard-pasted text.
            String prompt = TerminalAgentCompletionSupport.promptFromRaw(typedCommand, commandName, caseInsensitive);
            prompt = TerminalAgentCompletionSupport.sanitizeHistoryPrompt(prompt);
            if (prompt == null || prompt.isBlank()) {
                return;
            }
            var gsm = KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm != null ? gsm.getSettings() : null;
            if (gs == null) {
                return;
            }
            gs.addTerminalAgentInput(prompt);
            gsm.save();
        } catch (Exception e) {
            logger.debug("Could not record terminal agent input history: {}", e.getMessage());
        }
    }

    /** Shows the TAB-completion popup (command variants or prompt history). Returns true if handled. */
    private boolean showAgentCompletion(SithTermFxWidget widget, StringBuilder buffer) {
        if (agentCompletionPopup != null && agentCompletionPopup.isShowing()) {
            return true;
        }
        String commandName = getTerminalAgentCommandName();
        boolean caseInsensitive = isTerminalAgentCommandNameCaseInsensitive();
        String raw = buffer.toString();
        TerminalAgentCompletionSupport.TabContext context =
            TerminalAgentCompletionSupport.classify(raw, commandName, caseInsensitive);
        if (context == TerminalAgentCompletionSupport.TabContext.NONE) {
            return false;
        }
        TtyConnector connector = widget.getTtyConnector();
        if (connector == null || !connector.isConnected()) {
            return false;
        }
        Node canvas = widget.getTerminalPanel() != null ? widget.getTerminalPanel().getCanvas() : null;
        if (canvas == null) {
            return false;
        }
        boolean commandMode = context == TerminalAgentCompletionSupport.TabContext.COMMAND;
        java.util.List<TerminalAgentCompletionPopup.CompletionEntry> items = commandMode
            ? commandCompletionEntries(commandName)
            : terminalAgentInputHistoryEntries();
        if (items.isEmpty()) {
            return true; // consume TAB even when there is nothing to offer yet
        }
        if (agentCompletionPopup == null) {
            agentCompletionPopup = new TerminalAgentCompletionPopup();
        }
        if (!commandMode) {
            // History mode is user-resizable; restore the persisted size and persist changes.
            var gs = resolveGlobalSettings();
            int width = gs != null ? gs.getTerminalAgentHistoryPopupWidth() : 460;
            int height = gs != null ? gs.getTerminalAgentHistoryPopupHeight() : 260;
            agentCompletionPopup.setHistoryGeometry(width, height, this::saveAgentHistoryPopupGeometry);
        }
        agentCompletionPopup.show(
            canvas,
            items,
            selected -> applyAgentCompletion(widget, buffer, commandMode, raw, selected),
            () -> Platform.runLater(canvas::requestFocus),
            commandMode ? null : this::removeAgentInputHistoryEntry,
            commandMode ? null : this::clearAgentInputHistory);
        return true;
    }

    private static de.kortty.model.GlobalSettings resolveGlobalSettings() {
        try {
            var gsm = KorTTYApplication.getInstance().getGlobalSettingsManager();
            return gsm != null ? gsm.getSettings() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Persists a user-chosen TAB history popup size so it survives restarts. */
    private void saveAgentHistoryPopupGeometry(double width, double height) {
        try {
            var gsm = KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm != null ? gsm.getSettings() : null;
            if (gs == null) {
                return;
            }
            gs.setTerminalAgentHistoryPopupWidth((int) Math.round(width));
            gs.setTerminalAgentHistoryPopupHeight((int) Math.round(height));
            gsm.save();
        } catch (Exception e) {
            logger.debug("Could not persist terminal agent history popup geometry: {}", e.getMessage());
        }
    }

    /** Removes a single prompt from the persistent terminal agent input history. */
    private void removeAgentInputHistoryEntry(String prompt) {
        try {
            var gsm = KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm != null ? gsm.getSettings() : null;
            if (gs == null) {
                return;
            }
            if (gs.removeTerminalAgentInput(prompt)) {
                gsm.save();
            }
        } catch (Exception e) {
            logger.debug("Could not remove terminal agent input history entry: {}", e.getMessage());
        }
    }

    /** Clears the entire persistent terminal agent input history. */
    private void clearAgentInputHistory() {
        try {
            var gsm = KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm != null ? gsm.getSettings() : null;
            if (gs == null) {
                return;
            }
            gs.clearTerminalAgentInputHistory();
            gsm.save();
        } catch (Exception e) {
            logger.debug("Could not clear terminal agent input history: {}", e.getMessage());
        }
    }

    private void applyAgentCompletion(SithTermFxWidget widget, StringBuilder buffer,
                                      boolean commandMode, String raw, String selected) {
        if (selected == null) {
            return;
        }
        TtyConnector connector = widget.getTtyConnector();
        if (connector == null || !connector.isConnected()) {
            return;
        }
        String toSend;
        if (commandMode) {
            String suffix = TerminalAgentCompletionSupport.completionSuffix(raw.strip(), selected);
            toSend = suffix.isEmpty() ? " " : suffix;
        } else {
            toSend = selected;
        }
        try {
            connector.write(toSend);
            buffer.append(toSend);
        } catch (Exception e) {
            logger.debug("Failed to send AI completion to terminal: {}", e.getMessage());
        }
    }

    private java.util.List<TerminalAgentCompletionPopup.CompletionEntry> commandCompletionEntries(String commandName) {
        java.util.List<TerminalAgentCompletionPopup.CompletionEntry> entries = new java.util.ArrayList<>();
        for (String option : TerminalAgentCompletionSupport.commandOptions(commandName)) {
            entries.add(TerminalAgentCompletionPopup.CompletionEntry.of(option));
        }
        return entries;
    }

    private java.util.List<TerminalAgentCompletionPopup.CompletionEntry> terminalAgentInputHistoryEntries() {
        try {
            var gsm = KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm != null ? gsm.getSettings() : null;
            if (gs == null) {
                return java.util.List.of();
            }
            // Migrate the stored history in place: sanitize each prompt (dropping shell noise) and
            // collapse whitespace-only duplicates into the cleanest variant. This removes terminal
            // line-wrap corruption ("nur" stored as "nu r") whenever a clean copy exists, and is
            // self-healing once the command is re-run cleanly. Persist the cleaned list once so the
            // stored value matches what is displayed/inserted and junk is gone for good.
            java.util.List<de.kortty.model.TerminalAgentInputHistoryEntry> stored =
                gs.getTerminalAgentInputHistoryEntries();
            java.util.List<de.kortty.model.TerminalAgentInputHistoryEntry> cleaned =
                TerminalAgentCompletionSupport.dedupHistoryEntries(stored);
            if (!TerminalAgentCompletionSupport.sameHistoryEntries(stored, cleaned)) {
                gs.setTerminalAgentInputHistory(cleaned);
                try {
                    gsm.save();
                } catch (Exception ignored) {
                    // best-effort migration; the cleaned list is still used for this popup
                }
            }
            java.util.List<TerminalAgentCompletionPopup.CompletionEntry> entries = new java.util.ArrayList<>();
            for (de.kortty.model.TerminalAgentInputHistoryEntry entry : cleaned) {
                entries.add(new TerminalAgentCompletionPopup.CompletionEntry(
                    entry.getPrompt(),                                  // value: full prompt inserted/run on selection
                    shortenAgentHistoryDisplay(entry.getPrompt()),      // primary: shortened for the row
                    formatAgentHistoryTimestamp(entry.getLastUsedEpochMillis())));
            }
            return entries;
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    /** Max characters of a history prompt shown in the TAB popup before it is shortened with an ellipsis. */
    static final int AGENT_HISTORY_DISPLAY_MAX_CHARS = 60;

    /**
     * Shortens a history prompt for display in the TAB popup: prompts longer than
     * {@link #AGENT_HISTORY_DISPLAY_MAX_CHARS} characters are cut and an ellipsis is appended, so a row
     * never runs off the popup. The full prompt is still stored and inserted on selection.
     */
    static String shortenAgentHistoryDisplay(String prompt) {
        if (prompt == null) {
            return "";
        }
        String text = prompt.strip();
        if (text.length() <= AGENT_HISTORY_DISPLAY_MAX_CHARS) {
            return text;
        }
        return text.substring(0, AGENT_HISTORY_DISPLAY_MAX_CHARS).stripTrailing() + "…";
    }

    private static final java.time.format.DateTimeFormatter AGENT_HISTORY_TIMESTAMP_FORMAT =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Formats a last-used epoch-millis timestamp for display next to a history entry, or "" if unset. */
    static String formatAgentHistoryTimestamp(long epochMillis) {
        if (epochMillis <= 0) {
            return "";
        }
        return java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(AGENT_HISTORY_TIMESTAMP_FORMAT);
    }

    private String resolveAgentShortcutCommand(SithTermFxWidget widget, String bufferedCommand) {
        String trimmed = bufferedCommand != null ? bufferedCommand.trim() : "";
        String commandName = getTerminalAgentCommandName();
        boolean caseInsensitiveCommandName = isTerminalAgentCommandNameCaseInsensitive();
        TerminalAgentCommandSupport.Invocation bufferedInvocation =
            TerminalAgentCommandSupport.parseShortcut(trimmed, commandName, caseInsensitiveCommandName);
        try {
            String screenLines = widget != null && widget.getTerminalTextBuffer() != null
                ? widget.getTerminalTextBuffer().getScreenLines()
                : "";
            String visibleCommand = extractAgentShortcutFromVisibleScreen(
                screenLines,
                commandName,
                caseInsensitiveCommandName);
            if (visibleCommand != null && shouldUseVisibleAgentShortcut(
                visibleCommand,
                trimmed,
                bufferedInvocation,
                commandName,
                caseInsensitiveCommandName)) {
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
        if (!shouldBufferAgentShortcutKeyTyped(
            event.getCharacter(),
            event.isControlDown(),
            event.isAltDown(),
            event.isMetaDown())) {
            return;
        }
        agentShortcutBuffers.computeIfAbsent(widget, ignored -> new StringBuilder()).append(event.getCharacter());
    }

    static boolean shouldBufferAgentShortcutKeyTyped(
        String character,
        boolean controlDown,
        boolean altDown,
        boolean metaDown) {

        if (controlDown || altDown || metaDown || character == null || character.isEmpty()) {
            return false;
        }
        char first = character.charAt(0);
        return first != '\r'
            && first != '\n'
            && first != '\b'
            && first != 127
            && first >= 32;
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

    private boolean isTerminalAgentExecutionEnabled() {
        try {
            var gsm = KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm != null ? gsm.getSettings() : null;
            return gs == null || gs.isTerminalAgentExecutionEnabled();
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

    /**
     * Clears the local agent-shortcut input buffer for the widget owning {@code sourceConnector} once
     * a bare shell prompt (no typed input) is on screen, so a stale "agent ..." entry from a finished
     * command cannot make a later TAB falsely offer the prompt history.
     */
    private void clearAgentShortcutBufferForFreshPrompt(SshTtyConnector sourceConnector) {
        SithTermFxWidget widget = findWidgetForConnector(sourceConnector);
        if (widget == null) {
            return;
        }
        Platform.runLater(() -> {
            StringBuilder buffer = agentShortcutBuffers.get(widget);
            if (buffer != null && !buffer.isEmpty()) {
                buffer.setLength(0);
            }
        });
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
                    // A bare shell prompt with no typed input is showing: drop any stale local input
                    // buffer so a TAB here cannot resurrect a previous "agent ..." line and wrongly
                    // offer the history. The buffer refills as the user types the next command.
                    clearAgentShortcutBufferForFreshPrompt(sourceConnector);
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
        String workingDirectory = SshTtyConnector.extractWorkingDirectoryFromAgentOscPayload(payload);
        sourceConnector.updateCurrentRemoteDirectoryHint(workingDirectory);
        logger.debug("Received terminal AI OSC shortcut kind='{}'", kind);
        TerminalAgentRunContext runContext = createTerminalAgentRunContext(sourceConnector, workingDirectory);
        Platform.runLater(() -> {
            recordAgentInputHistory(
                rawCommand,
                getTerminalAgentCommandName(),
                isTerminalAgentCommandNameCaseInsensitive());
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

    static String extractAgentShortcutFromVisibleScreen(
        String screenLines,
        String commandName,
        boolean caseInsensitiveCommandName) {

        String normalized = screenLines != null
            ? stripTerminalControlSequences(screenLines).replace("\r\n", "\n").replace('\r', '\n')
            : "";
        String[] lines = normalized.split("\n", -1);
        for (int start = lines.length - 1; start >= 0; start--) {
            StringBuilder candidate = new StringBuilder();
            for (int index = start; index < lines.length; index++) {
                String line = lines[index] != null ? lines[index].strip() : "";
                if (line.isBlank()) {
                    continue;
                }
                if (!candidate.isEmpty()) {
                    candidate.append(' ');
                }
                candidate.append(line);
            }
            String command = extractAgentShortcutFromVisibleLine(
                candidate.toString(),
                commandName,
                caseInsensitiveCommandName);
            if (command != null) {
                return command;
            }
        }
        return null;
    }

    private static boolean shouldUseVisibleAgentShortcut(
        String visibleCommand,
        String bufferedCommand,
        TerminalAgentCommandSupport.Invocation bufferedInvocation,
        String commandName,
        boolean caseInsensitiveCommandName) {

        TerminalAgentCommandSupport.Invocation visibleInvocation =
            TerminalAgentCommandSupport.parseShortcut(visibleCommand, commandName, caseInsensitiveCommandName);
        if (visibleInvocation == null) {
            return false;
        }
        if (bufferedInvocation == null) {
            return true;
        }
        if (visibleCommand.trim().length() <= (bufferedCommand != null ? bufferedCommand.trim().length() : 0)) {
            return false;
        }
        return visibleInvocation.kind() == bufferedInvocation.kind()
            && Objects.equals(visibleInvocation.profileName(), bufferedInvocation.profileName())
            && visibleInvocation.askConfirmationBeforeEveryCommand() == bufferedInvocation.askConfirmationBeforeEveryCommand()
            && visibleInvocation.autoApproveRootCommands() == bufferedInvocation.autoApproveRootCommands();
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
        String normalized = stripTerminalControlSequences(value)
            .replace("\r\n", "\n")
            .replace('\r', '\n');
        int index = normalized.lastIndexOf('\n');
        return index >= 0 ? normalized.substring(index + 1).trim() : normalized.trim();
    }

    private static String stripTerminalControlSequences(String value) {
        return (value != null ? value : "")
            .replaceAll("\\u001B\\[[;?0-9]*[ -/]*[@-~]", "")
            .replaceAll("\\u001B\\].*?(\\u0007|\\u001B\\\\)", "");
    }

    static @Nullable String extractWorkingDirectoryFromPromptLine(String line, String homeDirectory) {
        String normalized = line != null ? line.stripTrailing() : "";
        String prompt = extractPromptPrefixFromVisibleLine(normalized);
        if (prompt.isBlank()) {
            return null;
        }
        String beforePrompt = prompt.substring(0, prompt.length() - 1).stripTrailing();
        String windowsDirectory = extractNativeWindowsWorkingDirectory(beforePrompt);
        if (windowsDirectory != null) {
            return windowsDirectory;
        }
        String candidate = extractWorkingDirectoryCandidate(beforePrompt);
        if (candidate.isBlank()) {
            return null;
        }
        if (isAbsoluteWorkingDirectorySyntax(candidate)) {
            return candidate;
        }
        if ("~".equals(candidate)) {
            return homeDirectory != null && homeDirectory.startsWith("/") ? homeDirectory : null;
        }
        if (candidate.startsWith("~/")) {
            return homeDirectory != null && homeDirectory.startsWith("/")
                ? homeDirectory + candidate.substring(1)
                : null;
        }
        return null;
    }

    private static @Nullable String extractNativeWindowsWorkingDirectory(String beforePrompt) {
        String candidate = beforePrompt != null ? beforePrompt.strip() : "";
        if (candidate.regionMatches(true, 0, "PS ", 0, 3)) {
            candidate = candidate.substring(3).stripLeading();
        }
        return isAbsoluteWorkingDirectorySyntax(candidate) && !candidate.startsWith("/")
            ? candidate
            : null;
    }

    private static String extractWorkingDirectoryCandidate(String beforePrompt) {
        String normalized = beforePrompt != null ? beforePrompt.strip() : "";
        if (normalized.isBlank()) {
            return "";
        }
        int separator = normalized.lastIndexOf(':');
        if (separator >= 0 && separator + 1 < normalized.length()) {
            return stripPromptDirectoryDecorations(normalized.substring(separator + 1).strip());
        }
        if (normalized.endsWith("]") || normalized.endsWith(")")) {
            normalized = normalized.substring(0, normalized.length() - 1).stripTrailing();
        }
        int whitespace = lastWhitespaceIndex(normalized);
        if (whitespace < 0 || whitespace + 1 >= normalized.length()) {
            return "";
        }
        return stripPromptDirectoryDecorations(normalized.substring(whitespace + 1).strip());
    }

    private static int lastWhitespaceIndex(String value) {
        for (int i = value.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String stripPromptDirectoryDecorations(String candidate) {
        String stripped = candidate != null ? candidate.strip() : "";
        while (!stripped.isEmpty() && (stripped.endsWith("]") || stripped.endsWith(")"))) {
            stripped = stripped.substring(0, stripped.length() - 1).stripTrailing();
        }
        return stripped;
    }

    static @Nullable String extractWorkingDirectoryFromVisibleScreen(String screenLines, String homeDirectory) {
        String normalized = screenLines != null
            ? screenLines.replace("\r\n", "\n").replace('\r', '\n')
            : "";
        String[] lines = normalized.split("\n", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            String directory = extractWorkingDirectoryFromPromptLine(lines[i], homeDirectory);
            if (directory != null) {
                return directory;
            }
        }
        return null;
    }

    private static String extractPromptPrefixFromVisibleLine(String normalizedLine) {
        if (normalizedLine == null || normalizedLine.isBlank()) {
            return "";
        }
        if (looksLikeShellPrompt(normalizedLine)) {
            return normalizedLine;
        }
        for (int i = normalizedLine.length() - 2; i >= 0; i--) {
            char ch = normalizedLine.charAt(i);
            if ((ch == '$' || ch == '#' || ch == '%' || ch == '>') && Character.isWhitespace(normalizedLine.charAt(i + 1))) {
                String candidate = normalizedLine.substring(0, i + 1).stripTrailing();
                if (looksLikeShellPrompt(candidate)) {
                    return candidate;
                }
            }
        }
        return "";
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
            + "case ${PWD-} in /*) __kortty_cwd=$PWD;; *) __kortty_cwd=$(pwd -P 2>/dev/null || pwd 2>/dev/null || printf '');; esac; "
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
            + "printf '" + SshTtyConnector.SHELL_STARTUP_CLEANUP_MARKER_SHELL_LITERAL + "\\r\\033[K'; "
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
        return (int) sharedFontSource.getFontSize();
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
            boolean hostKeyVerificationFailed = false;
            boolean configurationRefused = false;

            // Clear terminal before first attempt
            clearTerminal();
            if (retryCount > 1) {
                showMessage(I18n.get("terminal.connectionAttempt", 1, retryCount));
            } else {
                showMessage(I18n.get("terminal.connecting"));
            }

            while (attempt < retryCount && !connected && !authenticationFailed && !configurationRefused) {
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
                        // The journal itself stays open across disconnect/reconnect; only the
                        // listener on the dying connector is released.
                        detachJournalDataListener();

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
                        // Start (or re-attach after reconnect) the session journal if enabled
                        startSessionJournal();
                        
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
                    
                } catch (SshTtyConnector.HostKeyVerificationException e) {
                    authenticationFailed = true; // Stops retries; this is not a user-authentication failure.
                    hostKeyVerificationFailed = true;
                    lastError = e.getMessage();
                    logger.error("Host-key verification failed for {} - NOT retrying: {}",
                        connection.getDisplayName(), e.getMessage());

                    clearTerminal();
                    showMessage(e.getMessage());
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

                } catch (IllegalStateException e) {
                    // Deterministic configuration/environment refusal (e.g. Mosh with a jump
                    // server, or a missing mosh runtime): retrying cannot change the outcome.
                    configurationRefused = true;
                    lastError = e.getMessage();
                    // Log raw host:port rather than connection.getDisplayName(): the latter can fall
                    // back to "username@host", and CodeQL's coarse sensitive-data heuristic treats any
                    // getter on ServerConnection as tainted once the class holds an encryptedPassword
                    // field. Host/port carry no credential and give the same diagnostic value.
                    logger.error("Connection refused for {}:{} - NOT retrying: {}",
                            connection.getHost(), connection.getPort(), e.getMessage());

                    clearTerminal();
                    showMessage(e.getMessage());
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
            
            // All retries failed (or auth failed / configuration refused)
            if (!authenticationFailed && !configurationRefused) {
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
            String errorMessage;
            if (hostKeyVerificationFailed || configurationRefused) {
                errorMessage = lastError != null && !lastError.isEmpty()
                    ? lastError : I18n.get("terminal.connectionFailed");
            } else if (authenticationFailed) {
                errorMessage = I18n.get("terminal.authFailed");
            } else {
                errorMessage = retryCount > 1
                    ? I18n.get("terminal.allAttemptsFailed", retryCount)
                    : I18n.get("terminal.connectionFailed");
            }
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
            if (logConfig.getFormat() != null) {
                // Measures actual logging sessions, not just configuration.
                de.kortty.telemetry.Telemetry.track(de.kortty.telemetry.TelemetryEvents.TERMINAL_LOG_STARTED,
                    Map.of("format", logConfig.getFormat().name().toLowerCase(Locale.ROOT)));
            }
            
            // Register data listener to capture terminal output (SSH or local shell)
            if (ttyConnector instanceof ObservableTtyConnector observableConnector) {
                terminalLoggerDataListener = data -> {
                    if (terminalLogger != null) {
                        terminalLogger.log(data);
                    }
                };
                observableConnector.addDataListener(terminalLoggerDataListener);
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
        if (ttyConnector instanceof ObservableTtyConnector observableConnector) {
            if (terminalLoggerDataListener != null) {
                observableConnector.removeDataListener(terminalLoggerDataListener);
                terminalLoggerDataListener = null;
            }
        }
    }

    // ==== Session journal ====

    /** The tab's session id, used for journal directory naming; set by TerminalTab. */
    public void setJournalTabSessionId(String tabSessionId) {
        this.journalTabSessionId = tabSessionId;
    }

    /** Handler the terminal-pane context menu "screenshot to journal" item calls; set by TerminalTab. */
    public void setJournalScreenshotHandler(java.util.function.Consumer<SithTermFxWidget> handler) {
        this.journalScreenshotHandler = handler;
    }

    /** Handler the terminal-pane context menu "note to journal" item calls; set by TerminalTab. */
    public void setJournalNoteHandler(Runnable handler) {
        this.journalNoteHandler = handler;
    }

    public boolean isSessionJournalActive() {
        de.kortty.core.SessionJournalSession session = journalSession;
        return session != null && session.isActive();
    }

    public de.kortty.core.SessionJournalSession getSessionJournalSession() {
        return journalSession;
    }

    /**
     * Called from the connect success path. First connect with journaling enabled creates the
     * journal; after a reconnect the existing journal continues and only the data listener is
     * re-attached to the new connector.
     */
    private void startSessionJournal() {
        try {
            de.kortty.policy.EffectivePolicy policy = de.kortty.policy.PolicyManager.effective();
            if (!policy.sessionJournalAllowed()) {
                return;
            }
            if (isSessionJournalActive()) {
                attachJournalDataListener();
                journalSession.noteReconnect();
                return;
            }
            de.kortty.model.SessionJournalConfig config = connection.getSessionJournalConfig();
            boolean enabled = config != null && config.isEnabled();
            if (!enabled && !policy.sessionJournalEnforced()) {
                return;
            }
            createAndStartSessionJournal(false, java.util.List.of());
        } catch (Exception e) {
            logger.error("Failed to start session journal for {}:{}: {}",
                connection.getHost(), connection.getPort(), e.getMessage(), e);
        }
    }

    /**
     * Enables the journal mid-session: the current scrollback is imported as seed entries, then
     * the live taps attach. Call on the FX thread; seeding runs on a background thread.
     *
     * @return true when the journal is (now) active
     */
    public boolean enableSessionJournalRetroactively() {
        if (isSessionJournalActive()) {
            return true;
        }
        if (ttyConnector == null || !de.kortty.policy.PolicyManager.effective().sessionJournalAllowed()) {
            return false;
        }
        try {
            final java.util.List<String> seedLines = readScrollbackForSeed();
            final de.kortty.core.SessionJournalSession session = createSessionJournal(true);
            session.start();
            journalSession = session;
            Thread seeder = new Thread(() -> {
                try {
                    session.appendSeedLines(seedLines);
                } catch (Exception e) {
                    logger.warn("Session journal seeding failed: {}", e.getMessage());
                } finally {
                    // Live output must come strictly after the seed block, so attach only now.
                    attachJournalDataListener();
                }
            }, "SessionJournal-Seed");
            seeder.setDaemon(true);
            seeder.start();
            return true;
        } catch (Exception e) {
            logger.error("Failed to enable session journal retroactively for {}:{}: {}",
                connection.getHost(), connection.getPort(), e.getMessage(), e);
            return false;
        }
    }

    /** Stops and closes the journal (explicit user stop or tab close). Safe to call repeatedly. */
    public void stopSessionJournal() {
        detachJournalDataListener();
        de.kortty.core.SessionJournalSession session = journalSession;
        journalSession = null;
        if (session != null) {
            try {
                de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
                if (app != null && app.getSessionJournalSummarizer() != null) {
                    app.getSessionJournalSummarizer().onSessionClosing(session);
                }
            } catch (Exception e) {
                logger.warn("Session journal close summarization failed: {}", e.getMessage());
            }
            try {
                session.close();
            } catch (Exception e) {
                logger.warn("Error closing session journal: {}", e.getMessage());
            }
        }
    }

    /**
     * Captures a PNG of the given split widget (or the whole terminal view) for the journal.
     * Must be called on the FX thread.
     */
    public byte[] captureJournalScreenshotPng(SithTermFxWidget widgetOrNull) throws java.io.IOException {
        javafx.scene.Node node = null;
        if (widgetOrNull != null && widgetOrNull.getTerminalPanel() != null) {
            node = widgetOrNull.getTerminalPanel().getPane();
        }
        if (node == null) {
            node = this;
        }
        javafx.scene.image.WritableImage image = node.snapshot(new javafx.scene.SnapshotParameters(), null);
        java.awt.image.BufferedImage buffered = javafx.embed.swing.SwingFXUtils.fromFXImage(image, null);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(buffered, "png", out);
        return out.toByteArray();
    }

    private void createAndStartSessionJournal(boolean seeded, java.util.List<String> seedLines)
            throws java.io.IOException {
        de.kortty.core.SessionJournalSession session = createSessionJournal(seeded);
        session.start();
        journalSession = session;
        if (seeded && !seedLines.isEmpty()) {
            session.appendSeedLines(seedLines);
        }
        attachJournalDataListener();
    }

    private de.kortty.core.SessionJournalSession createSessionJournal(boolean seeded) throws java.io.IOException {
        de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
        if (app == null || app.getSessionJournalService() == null) {
            throw new java.io.IOException("Session journal service not available");
        }
        de.kortty.model.GlobalSettings globalSettings = app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
        java.util.List<String> knownSecrets = new java.util.ArrayList<>();
        if (password != null && !password.isBlank()) {
            knownSecrets.add(password);
        }
        String tabSessionId = journalTabSessionId != null ? journalTabSessionId : connection.getId();
        de.kortty.core.SessionJournalSession session = app.getSessionJournalService()
            .createSession(connection, tabSessionId, globalSettings, knownSecrets, seeded);
        de.kortty.core.SessionJournalSummarizer summarizer = app.getSessionJournalSummarizer();
        if (summarizer != null) {
            summarizer.register(session);
        }
        return session;
    }

    private void attachJournalDataListener() {
        if (!(ttyConnector instanceof ObservableTtyConnector observableConnector)) {
            return;
        }
        detachJournalDataListener();
        journalDataListener = data -> {
            de.kortty.core.SessionJournalSession session = journalSession;
            if (session != null) {
                session.appendOutputChunk(data);
            }
        };
        observableConnector.addDataListener(journalDataListener);
        journalAttachedConnector = observableConnector;
    }

    private void detachJournalDataListener() {
        if (journalAttachedConnector != null && journalDataListener != null) {
            journalAttachedConnector.removeDataListener(journalDataListener);
        }
        journalAttachedConnector = null;
        journalDataListener = null;
    }

    private void forwardJournalInputLine(String line) {
        de.kortty.core.SessionJournalSession session = journalSession;
        if (session != null) {
            session.appendInputLine(line);
        }
    }

    /** Reads history + screen lines under the buffer lock for the retroactive seed. */
    private java.util.List<String> readScrollbackForSeed() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        SithTermFxWidget widget = splitPane != null ? splitPane.getFocusedWidget() : terminalWidget;
        if (widget == null) {
            widget = terminalWidget;
        }
        if (widget == null || widget.getTerminalTextBuffer() == null) {
            return lines;
        }
        var textBuffer = widget.getTerminalTextBuffer();
        textBuffer.lock();
        try {
            if (textBuffer.getHistoryBuffer() != null) {
                collectSeedLines(textBuffer.getHistoryBuffer().getLines(), lines);
            }
            collectSeedLines(textBuffer.getScreenLines(), lines);
        } finally {
            textBuffer.unlock();
        }
        // Trailing blank screen padding is noise in the journal.
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static void collectSeedLines(String block, java.util.List<String> target) {
        if (block == null || block.isEmpty()) {
            return;
        }
        for (String line : block.split("\n", -1)) {
            target.add(line.stripTrailing());
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
        detachJournalDataListener();
        if (ttyConnector != null) {
            reportTerminalDisconnected(ttyConnector);
            releaseAgentShortcutInputInterceptor(ttyConnector);
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
        cancelAllTerminalAgentRuns();
        stopAllTerminalAgentShellKeepAlives();
        detachTerminalRecordingSession();
        stopLogger();
        stopSessionJournal();
        stopAllEffects();
        if (ttyConnector != null) {
            reportTerminalDisconnected(ttyConnector);
            releaseAgentShortcutInputInterceptor(ttyConnector);
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
        releaseAllAgentShortcutInputInterceptors();
        terminalContainer = null;
        terminalAgentBusyStylesheetUrl = null;
        terminalAgentActivityPanels.clear();
        terminalAgentPromptDataListeners.clear();
        terminalAgentOscBuffers.clear();
        terminalAgentRunStates.clear();
        // Per-widget state: a still-running completion timer would pin the whole discarded
        // view graph via the FX master timer until it fires; the rest is reference hygiene so
        // a leaked TerminalView cannot keep every widget's scrollback buffer alive.
        for (PauseTransition completionTimer : commandCompletionTimerByWidget.values()) {
            completionTimer.stop();
        }
        commandCompletionTimerByWidget.clear();
        gutterMap.clear();
        lastTimestampLineByWidget.clear();
        timestampHistoryByWidget.clear();
        awaitingCommandCompletionByWidget.clear();
        commandStartLineByWidget.clear();
        agentShortcutBuffers.clear();
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

    public void setTerminalScrollbarsVisible(boolean visible) {
        terminalScrollbarsVisible = visible;
        Runnable task = () -> {
            if (splitPane != null) {
                for (SithTermFxWidget widget : splitPane.getAllWidgets()) {
                    applyTerminalScrollbarVisibility(widget);
                }
                return;
            }
            applyTerminalScrollbarVisibility(terminalWidget);
        };
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    private void applyTerminalScrollbarVisibility(@Nullable SithTermFxWidget widget) {
        if (widget == null || widget.getTerminalPanel() == null) {
            return;
        }
        ScrollBar scrollBar = widget.getTerminalPanel().getScrollBar();
        if (scrollBar == null) {
            return;
        }
        scrollBar.setVisible(terminalScrollbarsVisible);
        scrollBar.setManaged(terminalScrollbarsVisible);
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
            sharedFontSource.increaseFontSize(delta);
        } else if (delta < 0) {
            sharedFontSource.decreaseFontSize(-delta);
        }
        logger.debug("Zoom changed to font size: {}", sharedFontSource.getFontSize());
    }
    
    /**
     * Applies font family and size from the given connection settings to this terminal view.
     * Use this to refresh the display when connection settings were changed (e.g. in Connection Manager).
     */
    public void applyConnectionSettings(ConnectionSettings s) {
        if (s == null) return;
        ConnectionSettings resolved = resolveEffectiveSettings(s);
        ConnectionSettings effective = resolved;
        String themeId = resolved.getThemeId();
        if (themeId != null && !themeId.isEmpty()) {
            try {
                var tm = KorTTYApplication.getInstance().getThemeManager();
                if (tm != null) {
                    effective = tm.resolveSettings(resolved, themeId, isThemeFontApplyEnabled());
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
        settings.setTerminalColorsEnabled(effective.isTerminalColorsEnabled());
        sharedFontSource.setFontSize(size);

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
                    gutter.setTimestampFont(settings.getFontFamily(), sharedFontSource.getFontSize());
                }
            }
        } catch (Exception e) {
            logger.debug("Could not refresh timestamp gutter colors: {}", e.getMessage());
        }

        logger.debug("Applied connection settings: {} {}pt", family, size);
        // Effect panes keep their per-pane override (resolved in applyStyleStateColors / getTerminalFont),
        // so applying connection settings tab-wide never overwrites a pane that is running an effect.
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
            sharedFontSource.setFontSize(connectionSavedFontSize);
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
        sharedFontSource.setFontSize(defaultFontSize);
    }
    
    /**
     * Sets the terminal font size (e.g. when restoring project zoom level).
     */
    public void setFontSize(int fontSize) {
        sharedFontSource.setFontSize(fontSize);
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
    private static final class TerminalColorFilteringTtyConnector implements TtyConnector {

        private final TtyConnector delegate;
        private final BooleanSupplier terminalColorsEnabled;
        private final Runnable activityCallback;
        private final TerminalColorControlSequenceFilter filter = new TerminalColorControlSequenceFilter();
        private final StringBuilder pendingOutput = new StringBuilder();

        private TerminalColorFilteringTtyConnector(
                TtyConnector delegate,
                BooleanSupplier terminalColorsEnabled,
                Runnable activityCallback) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.terminalColorsEnabled = Objects.requireNonNull(terminalColorsEnabled, "terminalColorsEnabled");
            this.activityCallback = Objects.requireNonNull(activityCallback, "activityCallback");
        }

        TtyConnector delegate() {
            return delegate;
        }

        @Override
        public int read(char[] buf, int offset, int length) throws IOException {
            if (length <= 0) {
                return 0;
            }
            if (terminalColorsEnabled.getAsBoolean()) {
                filter.reset();
                pendingOutput.setLength(0);
                int count = delegate.read(buf, offset, length);
                if (count > 0) {
                    activityCallback.run();
                }
                return count;
            }
            while (pendingOutput.length() == 0) {
                char[] source = new char[Math.max(length, 256)];
                int count = delegate.read(source, 0, source.length);
                if (count <= 0) {
                    return count;
                }
                activityCallback.run();
                pendingOutput.append(filter.filter(source, 0, count));
            }

            int count = Math.min(length, pendingOutput.length());
            pendingOutput.getChars(0, count, buf, offset);
            pendingOutput.delete(0, count);
            return count;
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            delegate.write(bytes);
            if (bytes != null && bytes.length > 0) {
                activityCallback.run();
            }
        }

        @Override
        public void write(String string) throws IOException {
            delegate.write(string);
            if (string != null && !string.isEmpty()) {
                activityCallback.run();
            }
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public void resize(@NotNull com.sithtermfx.core.util.TermSize termSize) {
            delegate.resize(termSize);
        }

        @Override
        public int waitFor() throws InterruptedException {
            return delegate.waitFor();
        }

        @Override
        public boolean ready() throws IOException {
            return delegate.ready();
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @SuppressWarnings({"removal", "DeprecatedIsStillUsed"})
        @Override
        public boolean init(com.sithtermfx.core.Questioner questioner) {
            return delegate.init(questioner);
        }

        @SuppressWarnings("deprecation")
        @Override
        public void resize(@NotNull java.awt.Dimension termWinSize) {
            delegate.resize(termWinSize);
        }

        @SuppressWarnings({"deprecation", "removal"})
        @Override
        public void resize(java.awt.Dimension termWinSize, java.awt.Dimension pixelSize) {
            delegate.resize(termWinSize, pixelSize);
        }
    }

    private static class KorTTYSettingsProvider extends DynamicFontSizeSettingsProvider {
        
        private final ConnectionSettings settings;
        // Single tab-wide font size. May be null during super() construction (guarded below).
        private final DynamicFontSizeSettingsProvider sharedFontSource;
        // Live source (0..100) for the terminal background transparency; see TerminalView.backgroundTransparencyPercent.
        private final java.util.function.IntSupplier backgroundTransparencySupplier;
        // Per-pane appearance override contributed by an active effect; null = inherit the baseline settings.
        private volatile PaneAppearanceOverride override;

        public KorTTYSettingsProvider(ConnectionSettings settings, DynamicFontSizeSettingsProvider sharedFontSource,
                                      java.util.function.IntSupplier backgroundTransparencySupplier) {
            super(sharedFontSource.getFontSize());
            this.settings = settings;
            this.sharedFontSource = sharedFontSource;
            this.backgroundTransparencySupplier = backgroundTransparencySupplier;
        }

        void setOverride(PaneAppearanceOverride override) {
            this.override = override;
        }

        PaneAppearanceOverride getOverride() {
            return override;
        }

        // ---- Font size delegates to the single shared source so zoom/reset stay tab-wide. A pane may
        // pin its own size via the override (mother leaves it null, so it keeps tracking the shared size). ----
        @Override
        public float getFontSize() {
            if (sharedFontSource == null) {
                return super.getFontSize();
            }
            PaneAppearanceOverride o = override;
            if (o != null && o.fontSize() != null) {
                return o.fontSize();
            }
            return sharedFontSource.getFontSize();
        }

        @Override
        public float getTerminalFontSize() {
            return getFontSize();
        }

        @Override
        public void setFontSize(float size) {
            if (sharedFontSource == null) {
                super.setFontSize(size);
                return;
            }
            sharedFontSource.setFontSize(size);
        }

        @Override
        public void addFontSizeListener(Runnable listener) {
            if (sharedFontSource == null) {
                super.addFontSizeListener(listener);
                return;
            }
            sharedFontSource.addFontSizeListener(listener);
        }

        @Override
        public void removeFontSizeListener(Runnable listener) {
            if (sharedFontSource == null) {
                super.removeFontSizeListener(listener);
                return;
            }
            sharedFontSource.removeFontSizeListener(listener);
        }

        @Override
        public @NotNull Font getTerminalFont() {
            PaneAppearanceOverride o = override;
            String family = (o != null && o.fontFamily() != null) ? o.fontFamily() : settings.getFontFamily();
            if (family == null || family.isEmpty()) family = "Monospaced";
            if ("Monaco".equals(family) && !Font.getFamilies().contains("Monaco")) {
                family = "Monospaced";
            }
            return Font.font(family, getFontSize());
        }

        @Override
        public @NotNull TerminalColor getDefaultForeground() {
            PaneAppearanceOverride o = override;
            String color = (o != null && o.foregroundColor() != null) ? o.foregroundColor() : settings.getForegroundColor();
            return webToTerminalColor(color, Color.WHITE);
        }

        @Override
        public @NotNull TerminalColor getDefaultBackground() {
            PaneAppearanceOverride o = override;
            String color = (o != null && o.backgroundColor() != null) ? o.backgroundColor() : settings.getBackgroundColor();
            int alpha = backgroundTransparencySupplier != null
                    ? alphaForTransparencyPercent(backgroundTransparencySupplier.getAsInt())
                    : 255;
            return webToTerminalColor(color, Color.BLACK, alpha);
        }

        private static TerminalColor webToTerminalColor(String web, Color fallback) {
            return webToTerminalColor(web, fallback, 255);
        }

        private static TerminalColor webToTerminalColor(String web, Color fallback, int alpha) {
            Color c;
            try {
                c = Color.web(web);
            } catch (RuntimeException e) {
                c = fallback;
            }
            int r = (int) (c.getRed() * 255);
            int g = (int) (c.getGreen() * 255);
            int b = (int) (c.getBlue() * 255);
            if (alpha >= 255) {
                return TerminalColor.rgb(r, g, b);
            }
            return TerminalColor.rgba(r, g, b, Math.max(0, alpha));
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
        public int caretBlinkingMs() {
            PaneAppearanceOverride o = override;
            String cursorStyle = (o != null && o.cursorStyle() != null) ? o.cursorStyle() : settings.getCursorStyle();
            return TerminalCursorStyleSupport.caretBlinkingPeriodMs(
                    cursorStyle,
                    super.caretBlinkingMs());
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
            // Honors the connection's scrollback setting (Settings > Terminal); the widget reads
            // this once in its constructor, so it applies to newly opened tabs/split panes.
            int lines = settings != null ? settings.getScrollbackLines() : 10000;
            if (lines <= 0) {
                // Legacy/corrupt persisted XML: fall back to the model default.
                lines = 10000;
            }
            // Mirror the settings spinner's range so the buffer stays within sane bounds.
            return Math.max(100, Math.min(100_000, lines));
        }
    }
}
