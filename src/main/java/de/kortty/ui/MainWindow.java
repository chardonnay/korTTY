package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.ui.I18n;
import de.kortty.core.AiAction;
import de.kortty.core.AiCliArgumentTemplate;
import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiInternetAccessConfiguration;
import de.kortty.core.AiProfileSelectionSupport;
import de.kortty.core.AiPromptService;
import de.kortty.core.AiRequest;
import de.kortty.core.AiService;
import de.kortty.core.AiServiceFactory;
import de.kortty.core.AiSkillPromptSupport;
import de.kortty.core.AiTokenCounter;
import de.kortty.core.AiTokenUsage;
import de.kortty.core.AiTokenUsageManager;
import de.kortty.core.AiTokenUsageSnapshot;
import de.kortty.core.AiTokenWarningLevel;
import de.kortty.core.FailingAiService;
import de.kortty.core.LanguageManager;
import de.kortty.core.AiReasoningSupport;
import de.kortty.core.ProjectManager;
import de.kortty.core.RemoteTextFileSelectionSupport;
import de.kortty.core.SftpFileTransferService;
import de.kortty.core.SSHSession;
import de.kortty.core.SessionManager;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.core.SnippetManager;
import de.kortty.core.SshTtyConnector;
import de.kortty.core.TerminalAgentCommandSupport;
import de.kortty.core.TerminalAgentService;
import de.kortty.jobscheduler.ActiveJobSummary;
import de.kortty.jobscheduler.JobSchedulerService;
import de.kortty.jobscheduler.ScheduledJob;
import de.kortty.model.*;
import de.kortty.persistence.importer.ConnectionImporter;
import de.kortty.persistence.importer.MTPuTTYImporter;
import de.kortty.persistence.importer.MobaXTermImporter;
import de.kortty.persistence.importer.PuTTYCMImporter;
import de.kortty.persistence.exporter.ConnectionExporter;
import de.kortty.persistence.exporter.KorTTYExporter;
import de.kortty.persistence.exporter.MTPuTTYExporter;
import de.kortty.persistence.exporter.MobaXTermExporter;
import de.kortty.plugin.terminaleffects.TerminalEffectAnimationSpeed;
import de.kortty.security.PasswordVault;
import de.kortty.update.AvailableUpdate;
import de.kortty.update.DownloadDirectoryResolver;
import de.kortty.update.DownloadException;
import de.kortty.update.UpdateAssetDownloader;
import de.kortty.update.UpdateCheckResult;
import de.kortty.update.UpdateCheckService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.concurrent.Task;
import java.util.function.Consumer;

import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.control.*;
import javafx.scene.control.skin.MenuBarSkin;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.DragEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.TransferMode;
import javafx.geometry.Point2D;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.event.Event;
import javafx.geometry.Side;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Main application window with TabPane for SSH terminals.
 */
public class MainWindow {
    
    private static MainWindow instance;  // Singleton instance for global access
    
    public static MainWindow getInstance() {
        return instance;
    }
    private static final Logger logger = LoggerFactory.getLogger(MainWindow.class);
    private static final int DEFAULT_MAX_AI_SELECTION_CHARS = 1_000_000;
    private static final KeyCombination PASTE_ACCELERATOR =
        new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination MENU_BAR_TOGGLE_ACCELERATOR =
        new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final KeyCombination RECORDING_TOGGLE_ACCELERATOR =
        new KeyCodeCombination(KeyCode.E, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final KeyCombination JOB_SCHEDULER_ACCELERATOR =
        new KeyCodeCombination(KeyCode.J, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final KeyCombination VIDEO_MANAGER_ACCELERATOR =
        new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final KeyCombination AI_AGENT_ACCELERATOR =
        new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN);
    private static final KeyCombination AI_PLANNING_ACCELERATOR =
        new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN);
    private static final KeyCombination TERMINAL_ONLY_FULLSCREEN_ACCELERATOR =
        new KeyCodeCombination(KeyCode.F12);
    private static final String MENU_BAR_TOGGLE_SHORTCUT_LABEL = "Cmd/Ctrl+Shift+L";
    private static final int JOB_SCHEDULER_QUEUE_LIMIT = 5;
    private static final int JOB_SCHEDULER_STATUS_LEFT_PADDING = 14;
    private static final String PROJECT_URL = "https://github.com/chardonnay/korTTY";
    private static final String JOB_SCHEDULER_STATUS_SPACED_TEXT_PROPERTY = "kortty.jobscheduler.status.spacedText";
    private static final String JOB_SCHEDULER_QUEUE_JOB_NAME_PROPERTY = "kortty.jobscheduler.queue.jobName";
    private static final String JOB_SCHEDULER_QUEUE_NEXT_RUN_PROPERTY = "kortty.jobscheduler.queue.nextRun";
    private static final DateTimeFormatter JOB_SCHEDULER_MENU_TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    
    private final Stage stage;
    private final BorderPane root;
    private final TabPane tabPane;
    private final Label statusLabel;
    private VBox statusBar;
    private final HBox mainContentBox;
    private final boolean unifiedTitleBarEnabled;
    private MenuBar menuBar;
    private MenuBar systemMenuBar;
    private String dynamicThemeStylesheetUrl;
    private DashboardView dashboardView;
    private boolean dashboardVisible = false;
    private LocalFileBrowser localFileBrowser;
    private LocalFileBrowserManager fileBrowserManager;
    private CheckMenuItem showDashboardMenuItem;
    private CheckMenuItem showFileBrowserLeftMenuItem;
    private CheckMenuItem showFileBrowserRightMenuItem;
    private CheckMenuItem systemShowFileBrowserLeftMenuItem;
    private CheckMenuItem systemShowFileBrowserRightMenuItem;
    private ResizableDivider fileBrowserDivider;
    private CheckMenuItem systemShowDashboardMenuItem;
    private MenuItem cutMenuItem;
    private MenuItem systemCutMenuItem;
    private CheckMenuItem showMenuBarMenuItem;
    private CheckMenuItem systemShowMenuBarMenuItem;
    private CheckMenuItem terminalOnlyFullscreenMenuItem;
    private CheckMenuItem systemTerminalOnlyFullscreenMenuItem;
    private CheckMenuItem hideFullscreenScrollbarsMenuItem;
    private CheckMenuItem systemHideFullscreenScrollbarsMenuItem;
    private CheckMenuItem showTimestampsMenuItem;
    private CheckMenuItem systemShowTimestampsMenuItem;
    private Menu jobSchedulerStatusMenu;
    private Menu systemJobSchedulerStatusMenu;
    private Timeline jobSchedulerStatusTimeline;
    private Runnable jobSchedulerStatusListener;
    private long lastTerminalPasteShortcutAtNanos = -1L;

    /** Window + system menu bar: AI Manager / Agent / Planning items (disable when AI is turned off). */
    private final List<MenuItem> toolsAiMenuItems = new ArrayList<>(6);
    private final List<MenuItem> toolsAiAgentExecutionMenuItems = new ArrayList<>(2);
    
    private final KorTTYApplication app;
    private final SessionManager sessionManager;
    private final ProjectManager projectManager;
    private final TerminalAgentService terminalAgentService = new TerminalAgentService();
    private final List<ConnectionImporter> importers;
    
    private static final List<MainWindow> openWindows = new ArrayList<>();
    private static final Set<MainWindow> applicationQuitApprovedWindows =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private static volatile boolean applicationQuitRequested = false;
    private static volatile boolean schedulerDrainApproved = false;
    private static volatile boolean schedulerDrainInProgress = false;

    /** DataFormat for drag-and-drop of tabs between KorTTY windows (value: transfer ID). */
    private static final DataFormat KORTTY_TAB_TRANSFER_FORMAT = new DataFormat("application/x-kortty-tab-transfer");
    /** Pending tab transfer: transferId -> (source window, tab). Cleared after drop or drag done. */
    private static final Map<String, TabTransfer> pendingTabTransfers = new HashMap<>();

    private record TabTransfer(MainWindow sourceWindow, Tab tab) {}
    private enum MenuBarTarget { WINDOW, SYSTEM }

    private final Map<String, AiResultTab> openSavedAiChatTabs = new HashMap<>();

    private volatile boolean quickConnectDialogOpen = false;
    private volatile boolean startupComplete = false; // Prevent QuickConnect during startup
    private boolean terminalOnlyFullscreenActive = false;
    private boolean terminalOnlyPreviousFullScreen = false;
    private boolean terminalOnlyPreviousMenuBarVisible = true;
    private boolean terminalOnlyPreviousStatusBarVisible = true;
    private boolean terminalOnlyPreviousDashboardVisible = false;
    private LocalFileBrowserManager.Position terminalOnlyPreviousFileBrowserPosition =
        LocalFileBrowserManager.Position.HIDDEN;
    /** Consumer reference for file browser position listener, stored so it can be removed on close. */
    private Consumer<LocalFileBrowserManager.Position> fileBrowserPositionListener;
    
    public MainWindow(Stage stage) {
        instance = this;  // Set singleton instance
        this.stage = stage;
        this.app = KorTTYApplication.getInstance();
        this.sessionManager = app.getSessionManager();
        this.projectManager = new ProjectManager(KorTTYApplication.getConfigDirectory());
        this.unifiedTitleBarEnabled = configureWindowChrome(stage);
        
        // Initialize importers
        this.importers = List.of(new MTPuTTYImporter(), new MobaXTermImporter(), new PuTTYCMImporter());
        
        // Create UI components
        this.root = new BorderPane();
        this.tabPane = new TabPane();
        this.statusLabel = new Label(I18n.get("app.ready"));
        this.mainContentBox = new HBox();
        
        setupUI();
        setupMenuBar();
        setupKeyBindings();
        WindowCloseShortcutSupport.installForMainWindow(stage, openWindows.isEmpty(), this::fireCloseRequest);
        
        openWindows.add(this);
    }

    private boolean configureWindowChrome(Stage stage) {
        try {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (!osName.contains("mac") || !Platform.isSupported(ConditionalFeature.UNIFIED_WINDOW)) {
                return false;
            }

            stage.initStyle(StageStyle.UNIFIED);
            return true;
        } catch (IllegalStateException e) {
            logger.debug("Could not enable unified window decorations: {}", e.getMessage());
            return false;
        }
    }
    
    private void setupUI() {
        // Tab pane configuration
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabPane.setTabDragPolicy(TabPane.TabDragPolicy.REORDER);
        root.getStyleClass().add("kortty-main-root");
        mainContentBox.getStyleClass().add("main-content");
        tabPane.getStyleClass().add("main-tab-pane");
        
        // Auto-focus terminal when tab is selected; tell Mosh connector when this tab is active so it does not show false "interrupted"
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (oldTab instanceof TerminalTab oldTerminalTab) {
                oldTerminalTab.getTerminalView().setTerminalActive(false);
            }
            if (newTab instanceof TerminalTab terminalTab) {
                terminalTab.getTerminalView().setTerminalActive(true);
                Platform.runLater(() -> terminalTab.getTerminalView().focusTerminal());
            }
            updateEditMenuItemsForSelection();
        });
        
        // Listen for tab removals to update dashboard and clear per-terminal AI state.
        tabPane.getTabs().addListener((javafx.collections.ListChangeListener.Change<? extends Tab> change) -> {
            while (change.next()) {
                if (change.wasAdded() && terminalOnlyFullscreenActive) {
                    for (Tab addedTab : change.getAddedSubList()) {
                        if (addedTab instanceof TerminalTab terminalTab) {
                            terminalTab.setTerminalChromeVisible(false);
                            applyTerminalScrollbarVisibility(terminalTab);
                        }
                    }
                } else if (change.wasAdded()) {
                    for (Tab addedTab : change.getAddedSubList()) {
                        if (addedTab instanceof TerminalTab terminalTab) {
                            applyTerminalScrollbarVisibility(terminalTab);
                        }
                    }
                }
                if (change.wasRemoved()) {
                    for (Tab removedTab : change.getRemoved()) {
                        if (removedTab instanceof TerminalTab terminalTab) {
                            terminalAgentService.clearCachedSudoPassword(terminalTab.getAiSessionId());
                        }
                    }
                    Platform.runLater(() -> {
                        updateDashboard();
                    });
                }
            }
        });
        
        // Handle double-click on tab for retry
        tabPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getClickCount() == 2) {
                Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
                if (selectedTab instanceof TerminalTab terminalTab) {
                    // Check if tab is in failed state (dark red)
                    String style = selectedTab.getStyle();
                    if (style != null && style.contains("#8B0000")) {
                        // Retry connection
                        terminalTab.retryConnection();
                        event.consume();
                    }
                }
            }
        });

        // Tab drag-and-drop: only when drag starts in the tab bar (not in tab content) so text selection works
        final double tabBarHeightPx = 36;
        tabPane.addEventFilter(MouseEvent.DRAG_DETECTED, event -> {
            Point2D inTabPane = tabPane.sceneToLocal(event.getSceneX(), event.getSceneY());
            if (inTabPane.getY() < 0 || inTabPane.getY() >= tabBarHeightPx) {
                return; // drag started in content area: allow text selection / split-pane drag
            }
            Tab selected = tabPane.getSelectionModel().getSelectedItem();
            if (selected == null || !selected.isClosable()) {
                return;
            }
            String transferId = UUID.randomUUID().toString();
            pendingTabTransfers.put(transferId, new TabTransfer(this, selected));
            Dragboard db = tabPane.startDragAndDrop(TransferMode.MOVE);
            db.setContent(Map.of(KORTTY_TAB_TRANSFER_FORMAT, transferId));
            event.consume();
        });
        tabPane.setOnDragOver(event -> {
            if (!event.getDragboard().hasContent(KORTTY_TAB_TRANSFER_FORMAT)) {
                return;
            }
            String transferId = (String) event.getDragboard().getContent(KORTTY_TAB_TRANSFER_FORMAT);
            TabTransfer xfer = pendingTabTransfers.get(transferId);
            if (xfer == null) {
                return;
            }
            // Allow drop on any tab pane (same or other window); same-window drop reorders
            event.acceptTransferModes(TransferMode.MOVE);
            event.consume();
        });
        tabPane.setOnDragDropped(event -> {
            if (!event.getDragboard().hasContent(KORTTY_TAB_TRANSFER_FORMAT)) {
                event.setDropCompleted(false);
                return;
            }
            String transferId = (String) event.getDragboard().getContent(KORTTY_TAB_TRANSFER_FORMAT);
            TabTransfer xfer = pendingTabTransfers.remove(transferId);
            if (xfer == null) {
                event.setDropCompleted(false);
                return;
            }
            Tab tab = xfer.tab();
            MainWindow sourceWindow = xfer.sourceWindow();
            javafx.scene.control.TabPane sourcePane = sourceWindow.tabPane;
            if (!sourcePane.getTabs().contains(tab)) {
                event.setDropCompleted(false);
                return;
            }
            sourcePane.getTabs().remove(tab);
            // Insert index: approximate position from drop X for reorder.
            int insertIndex = (int) ((event.getX() / Math.max(1, tabPane.getWidth())) * (tabPane.getTabs().size()));
            insertIndex = Math.max(0, Math.min(insertIndex, tabPane.getTabs().size()));
            tabPane.getTabs().add(insertIndex, tab);
            tabPane.getSelectionModel().select(tab);
            if (tab instanceof TerminalTab tt) {
                installAiSelectionHandler(tt);
                Platform.runLater(() -> tt.getTerminalView().requestFocus());
            }
            event.setDropCompleted(true);
            event.consume();
            if (sourceWindow != this) {
                sourceWindow.updateDashboard();
                updateDashboard();
                updateAllTabContextMenus();
                sourceWindow.updateAllTabContextMenus();
            } else {
                updateDashboard();
                updateAllTabContextMenus();
            }
        });
        tabPane.setOnDragDone(event -> {
            if (!event.isDropCompleted()) {
                pendingTabTransfers.entrySet().removeIf(entry -> entry.getValue().sourceWindow() == this);
            }
        });

        // Accept tab drops anywhere in this window (bubbling handlers so split-terminal DnD is not affected)
        root.setOnDragOver(event -> {
            if (!event.getDragboard().hasContent(KORTTY_TAB_TRANSFER_FORMAT)) return;
            String transferId = (String) event.getDragboard().getContent(KORTTY_TAB_TRANSFER_FORMAT);
            if (pendingTabTransfers.get(transferId) == null) return;
            event.acceptTransferModes(TransferMode.MOVE);
            event.consume();
        });
        root.setOnDragDropped(event -> {
            if (!event.getDragboard().hasContent(KORTTY_TAB_TRANSFER_FORMAT)) return;
            String transferId = (String) event.getDragboard().getContent(KORTTY_TAB_TRANSFER_FORMAT);
            TabTransfer xfer = pendingTabTransfers.remove(transferId);
            if (xfer == null) return;
            Tab tab = xfer.tab();
            MainWindow sourceWindow = xfer.sourceWindow();
            javafx.scene.control.TabPane sourcePane = sourceWindow.tabPane;
            if (!sourcePane.getTabs().contains(tab)) return;
            sourcePane.getTabs().remove(tab);
            int insertIndex = tabPane.getTabs().size();
            tabPane.getTabs().add(insertIndex, tab);
            tabPane.getSelectionModel().select(tab);
            if (tab instanceof TerminalTab tt) {
                installAiSelectionHandler(tt);
                Platform.runLater(() -> tt.getTerminalView().requestFocus());
            }
            event.setDropCompleted(true);
            event.consume();
            if (sourceWindow != this) {
                sourceWindow.updateDashboard();
                updateDashboard();
                updateAllTabContextMenus();
                sourceWindow.updateAllTabContextMenus();
            } else {
                updateDashboard();
                updateAllTabContextMenus();
            }
        });
        
        // Status bar
        statusBar = new VBox(statusLabel);
        statusBar.getStyleClass().add("status-bar");
        statusLabel.getStyleClass().add("status-label");
        statusBar.setStyle("-fx-padding: 5; -fx-background-color: #2d2d2d;");
        statusLabel.setStyle("-fx-text-fill: #cccccc;");
        
        // HBox for dashboard (fixed width) + tab pane (grows with window)
        mainContentBox.getChildren().add(tabPane);
        HBox.setHgrow(tabPane, Priority.ALWAYS);
        
        root.setCenter(mainContentBox);
        root.setBottom(statusBar);
        applyMainWindowThemeFromGlobalSettings();
        
        // Scene setup
        Scene scene = new Scene(root, 1000, 700);
        if (unifiedTitleBarEnabled) {
            // Let the themed root background flow into the macOS title bar area.
            scene.setFill(Color.TRANSPARENT);
        }
        
        // Load CSS stylesheet (safely)
        var cssResource = getClass().getResource("/styles/terminal.css");
        if (cssResource != null) {
            scene.getStylesheets().add(cssResource.toExternalForm());
        } else {
            logger.warn("Could not load terminal.css stylesheet");
        }
        
        // Global keyboard shortcuts for zoom and fullscreen (works on all keyboard layouts)
        // Track if zoom was triggered to also consume KEY_TYPED event
        final boolean[] zoomTriggered = {false};
        
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (MENU_BAR_TOGGLE_ACCELERATOR.match(event)) {
                toggleMenuBarVisibility(menuBar == null || !menuBar.isVisible());
                event.consume();
                return;
            }
            if (TERMINAL_ONLY_FULLSCREEN_ACCELERATOR.match(event)) {
                toggleTerminalOnlyFullscreen();
                event.consume();
                return;
            }
            if (PASTE_ACCELERATOR.match(event)
                && tabPane.getSelectionModel().getSelectedItem() instanceof TerminalTab) {
                lastTerminalPasteShortcutAtNanos = System.nanoTime();
            }

            boolean ctrl = event.isControlDown();
            boolean alt = event.isAltDown();
            KeyCode code = event.getCode();
            String text = event.getText();
            String character = event.getCharacter();
            zoomTriggered[0] = false;
            
            // Fullscreen toggle: F11
            if (code == KeyCode.F11) {
                boolean goFullscreen = !stage.isFullScreen();
                stage.setFullScreen(goFullscreen);
                // Force terminal resize after fullscreen change
                Platform.runLater(() -> {
                    Platform.runLater(() -> {
                        // Double runLater to ensure layout is complete
                        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
                        if (selectedTab instanceof TerminalTab terminalTab) {
                            terminalTab.getTerminalView().requestFocus();
                        }
                    });
                });
                event.consume();
                return;
            }
            
            // On macOS, use Cmd (Meta) for zoom; on other OS use Ctrl or Alt.
            // Option (Alt) on macOS must NOT be intercepted — it produces special characters
            // like |, [, ], {, }, @, ~, \.
            boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
            boolean zoomModifier = isMac ? event.isMetaDown() : (ctrl || alt);
            
            if (zoomModifier) {
                // Zoom in: modifier + Plus (various key codes for different keyboards)
                if (code == KeyCode.PLUS || code == KeyCode.ADD || 
                    code == KeyCode.EQUALS || "+".equals(text) || "+".equals(character)) {
                    zoomTerminal(1);
                    zoomTriggered[0] = true;
                    event.consume();
                }
                // Zoom out: modifier + Minus
                else if (code == KeyCode.MINUS || code == KeyCode.SUBTRACT || 
                         "-".equals(text) || "-".equals(character)) {
                    zoomTerminal(-1);
                    zoomTriggered[0] = true;
                    event.consume();
                }
                // Reset zoom: modifier + 0
                else if (code == KeyCode.DIGIT0 || code == KeyCode.NUMPAD0) {
                    resetTerminalZoom();
                    zoomTriggered[0] = true;
                    event.consume();
                }
            }
        });
        
        // Also consume KEY_TYPED events for zoom to prevent +/- appearing in terminal
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_TYPED, event -> {
            if (zoomTriggered[0]) {
                // Consume early to prevent SithTermFX from processing empty character
                event.consume();
                zoomTriggered[0] = false; // Reset for next event
            }
        });
        
        stage.setScene(scene);
        AppDesignStyleSupport.installGlobalWindowStyler();
        // Apply theme again now that scene exists (first call in setupUI had scene == null)
        applyMainWindowThemeFromGlobalSettings();
        stage.setTitle(KorTTYApplication.getAppName());
        
        // Set window icon (e.g. Windows taskbar and title bar)
        try {
            var iconUrl = getClass().getResource("/icon/kortty_icon.png");
            if (iconUrl != null) {
                stage.getIcons().add(new Image(iconUrl.toExternalForm()));
            }
        } catch (Exception e) {
            logger.warn("Could not set window icon", e);
        }
        
        // Handle fullscreen changes - resize terminal properly
        stage.fullScreenProperty().addListener((obs, wasFullscreen, isFullscreen) -> {
            if (!isFullscreen && terminalOnlyFullscreenActive) {
                setTerminalOnlyFullscreen(false);
            }
            applyTerminalScrollbarVisibilityForOpenTabs();
            Platform.runLater(() -> {
                // Give layout time to update, then resize terminal
                Platform.runLater(() -> {
                    Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
                    if (selectedTab instanceof TerminalTab terminalTab) {
                        // Request focus to trigger proper resize
                        terminalTab.getTerminalView().requestFocus();
                    }
                });
            });
        });
        
        // Window close handling
        stage.setOnCloseRequest(e -> {
            // Save window geometry on close if enabled (not when using fixed geometry)
            GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
            
            // Always save last geometry (for next session) unless fixed geometry is used
            if (!globalSettings.isUseFixedWindowGeometry()) {
                WindowGeometry geo = new WindowGeometry(
                    stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()
                );
                geo.setMaximized(stage.isMaximized());
                globalSettings.setLastWindowGeometry(geo);
                logger.info("Saving window geometry: x={}, y={}, w={}, h={}, maximized={}", 
                    geo.getX(), geo.getY(), geo.getWidth(), geo.getHeight(), geo.isMaximized());
            }
            
            // Save dashboard state on close if enabled
            if (globalSettings.isRememberDashboardState()) {
                globalSettings.setDashboardVisible(dashboardVisible);
            }
            
            // Save settings BEFORE confirmClose (which might exit the app)
            try {
                app.getGlobalSettingsManager().save();
                logger.info("Window settings saved successfully");
            } catch (Exception ex) {
                logger.error("Failed to save window settings", ex);
            }
            
            boolean closeConfirmed = applicationQuitApprovedWindows.remove(this) || confirmClose();
            if (!closeConfirmed) {
                clearApplicationQuitState();
                e.consume();
            } else {
                stopJobSchedulerStatusUpdates();
                closeAllTabs();
                // Deregister file browser manager listener to prevent memory leaks and stale callbacks
                if (fileBrowserManager != null && fileBrowserPositionListener != null) {
                    fileBrowserManager.removePositionListener(fileBrowserPositionListener);
                    fileBrowserPositionListener = null;
                }
                openWindows.remove(this);

                // On macOS the application stays alive after the last window closes so the
                // dock icon can reopen a new window without restarting the process.
                if (openWindows.isEmpty()) {
                    if (applicationQuitRequested) {
                        logger.info("Application quit requested, exiting");
                        clearApplicationQuitState();
                        Platform.exit();
                        return;
                    }

                    if (app.shouldKeepRunningAfterLastWindowClosed()) {
                        logger.info("Last macOS window closed, keeping application alive");
                        return;
                    }

                    logger.info("Last window closed, exiting application");
                    Platform.exit();
                }
            }
        });
    }
    
    private void setupMenuBar() {
        menuBar = createApplicationMenuBar(MenuBarTarget.WINDOW);
        if (isMacOs()) {
            systemMenuBar = createApplicationMenuBar(MenuBarTarget.SYSTEM);
            systemMenuBar.setUseSystemMenuBar(true);
            systemMenuBar.setManaged(false);
            systemMenuBar.setVisible(false);
            // Keep a hidden companion menu bar attached to the scene so macOS can
            // continue to show the application menu even when the in-window bar is hidden.
            VBox menuContainer = new VBox(systemMenuBar, menuBar);
            root.setTop(menuContainer);
            MenuBarSkin.setDefaultSystemMenuBar(systemMenuBar);
        } else {
            root.setTop(menuBar);
        }
        applyMenuBarVisibility(isMenuBarVisiblePreference());
        syncDashboardMenuItems(shouldRestoreDashboardOnStartup());
        syncTimestampMenuItems(false);
        applyMainWindowThemeFromGlobalSettings();
        syncAiFeaturesMenuItemsEnabled();
        startJobSchedulerStatusUpdates();
    }

    private void syncAiFeaturesMenuItemsEnabled() {
        boolean enabled = false;
        try {
            var gs = app.getGlobalSettingsManager().getSettings();
            if (gs != null) {
                enabled = gs.isAiFeaturesEnabled();
            }
        } catch (Exception e) {
            logger.debug("syncAiFeaturesMenuItemsEnabled: {}", e.getMessage());
        }
        boolean agentExecutionEnabled = enabled && isTerminalAgentExecutionEnabled();
        for (MenuItem menuItem : toolsAiMenuItems) {
            menuItem.setDisable(!enabled);
        }
        for (MenuItem menuItem : toolsAiAgentExecutionMenuItems) {
            menuItem.setDisable(!agentExecutionEnabled);
        }
    }

    private boolean isAiFeaturesEnabled() {
        try {
            var gs = app.getGlobalSettingsManager().getSettings();
            return gs != null && gs.isAiFeaturesEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTerminalAgentExecutionEnabled() {
        if (!isAiFeaturesEnabled()) {
            return false;
        }
        try {
            var gs = app.getGlobalSettingsManager().getSettings();
            return gs == null || gs.isTerminalAgentExecutionEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean shouldConfirmTerminalAgentMutatingCommandSets() {
        try {
            var gs = app.getGlobalSettingsManager().getSettings();
            return gs != null && gs.isTerminalAgentConfirmMutatingCommandSets();
        } catch (Exception e) {
            return false;
        }
    }

    private void startJobSchedulerStatusUpdates() {
        if (jobSchedulerStatusTimeline != null) {
            return;
        }
        JobSchedulerService schedulerService = app.getJobSchedulerService();
        if (schedulerService != null) {
            jobSchedulerStatusListener = () -> Platform.runLater(this::updateJobSchedulerStatusMenus);
            schedulerService.addListener(jobSchedulerStatusListener);
        }
        jobSchedulerStatusTimeline = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), event -> updateJobSchedulerStatusMenus()));
        jobSchedulerStatusTimeline.setCycleCount(Timeline.INDEFINITE);
        jobSchedulerStatusTimeline.play();
        updateJobSchedulerStatusMenus();
    }

    private void stopJobSchedulerStatusUpdates() {
        if (jobSchedulerStatusTimeline != null) {
            jobSchedulerStatusTimeline.stop();
            jobSchedulerStatusTimeline = null;
        }
        JobSchedulerService schedulerService = app.getJobSchedulerService();
        if (schedulerService != null && jobSchedulerStatusListener != null) {
            schedulerService.removeListener(jobSchedulerStatusListener);
        }
        jobSchedulerStatusListener = null;
    }

    private void updateJobSchedulerStatusMenus() {
        JobSchedulerService schedulerService = app.getJobSchedulerService();
        String text = buildJobSchedulerMenuTitle();
        updateJobSchedulerStatusMenu(jobSchedulerStatusMenu, schedulerService, text);
        updateJobSchedulerStatusMenu(systemJobSchedulerStatusMenu, schedulerService, text);
    }

    private void updateJobSchedulerStatusMenu(Menu menu, JobSchedulerService schedulerService, String text) {
        if (menu == null) {
            return;
        }
        boolean visible = shouldShowJobSchedulerStatusMenu(schedulerService);
        menu.setVisible(visible);
        if (!visible) {
            menu.getItems().clear();
            return;
        }
        if (menu.getGraphic() instanceof Label label) {
            label.setText(text);
        }
        if (menu.getGraphic() == null) {
            boolean spacedText = Boolean.TRUE.equals(menu.getProperties().get(JOB_SCHEDULER_STATUS_SPACED_TEXT_PROPERTY));
            menu.setText(spacedText ? "  " + text : text);
        } else {
            menu.setText("");
        }
        updateVisibleJobSchedulerQueueItemTimers(menu);
    }

    private boolean shouldShowJobSchedulerStatusMenu(JobSchedulerService schedulerService) {
        if (!isJobSchedulerMenuStatusEnabled() || schedulerService == null) {
            return false;
        }
        if (!schedulerService.getActiveJobSummaries().isEmpty()) {
            return true;
        }
        return schedulerService.getJobs().stream().anyMatch(this::isActiveSchedulerEntry);
    }

    private boolean isJobSchedulerMenuStatusEnabled() {
        try {
            GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
            return settings == null || settings.isJobSchedulerMenuStatusEnabled();
        } catch (Exception e) {
            logger.debug("Could not read JobScheduler menu status setting", e);
            return true;
        }
    }

    private boolean isActiveSchedulerEntry(ScheduledJob job) {
        return job != null && job.isEnabled() && job.getSchedule().isEnabled();
    }

    private String buildJobSchedulerMenuTitle() {
        JobSchedulerService schedulerService = app.getJobSchedulerService();
        if (schedulerService == null) {
            return I18n.get("jobscheduler.menu.noJobs");
        }
        List<ActiveJobSummary> activeJobs = schedulerService.getActiveJobSummaries();
        if (!activeJobs.isEmpty()) {
            long cancelling = activeJobs.stream().filter(ActiveJobSummary::cancellationRequested).count();
            if (activeJobs.size() == 1) {
                ActiveJobSummary active = activeJobs.get(0);
                return I18n.get(
                    active.cancellationRequested() ? "jobscheduler.menu.cancellingOne" : "jobscheduler.menu.runningOne",
                    nonBlank(active.jobName(), active.jobId()));
            }
            return cancelling > 0
                ? I18n.get("jobscheduler.menu.cancellingMany", cancelling, activeJobs.size())
                : I18n.get("jobscheduler.menu.runningMany", activeJobs.size());
        }

        List<NextScheduledJob> queuedJobs = nextScheduledJobs(schedulerService);
        if (!queuedJobs.isEmpty()) {
            NextScheduledJob scheduled = queuedJobs.get(0);
            return I18n.get(
                "jobscheduler.menu.next",
                displayJobName(scheduled.job()),
                formatRemainingTime(scheduled.nextRun()));
        }

        return schedulerService.getJobs().isEmpty()
            ? I18n.get("jobscheduler.menu.noJobs")
            : I18n.get("jobscheduler.menu.noNext");
    }

    private void rebuildJobSchedulerStatusMenuItems(Menu menu) {
        if (menu == null) {
            return;
        }
        JobSchedulerService schedulerService = app.getJobSchedulerService();
        menu.getItems().clear();

        if (!shouldShowJobSchedulerStatusMenu(schedulerService)) {
            return;
        }

        MenuItem openScheduler = new MenuItem(I18n.get("jobscheduler.menu.open"));
        openScheduler.setOnAction(event -> showJobScheduler());
        menu.getItems().add(openScheduler);

        if (schedulerService == null) {
            return;
        }

        List<ActiveJobSummary> activeJobs = schedulerService.getActiveJobSummaries();
        if (!activeJobs.isEmpty()) {
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem runningHeader = new MenuItem(I18n.get("jobscheduler.menu.runningHeader"));
            runningHeader.setDisable(true);
            menu.getItems().add(runningHeader);
            for (ActiveJobSummary active : activeJobs) {
                String jobName = nonBlank(active.jobName(), active.jobId());
                MenuItem status = new MenuItem(active.cancellationRequested()
                    ? I18n.get("jobscheduler.menu.cancellingItem", jobName)
                    : I18n.get("jobscheduler.menu.runningItem", jobName));
                status.setDisable(true);
                menu.getItems().add(status);
                MenuItem cancel = new MenuItem(I18n.get("jobscheduler.menu.cancel", jobName));
                cancel.setDisable(active.cancellationRequested());
                cancel.setOnAction(event -> schedulerService.cancelJob(active.jobId()));
                menu.getItems().add(cancel);
            }
        }

        List<NextScheduledJob> queuedJobs = nextScheduledJobs(schedulerService);
        if (!queuedJobs.isEmpty()) {
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem queueHeader = new MenuItem(I18n.get("jobscheduler.menu.queueHeader"));
            queueHeader.setDisable(true);
            menu.getItems().add(queueHeader);
            for (NextScheduledJob scheduled : queuedJobs) {
                menu.getItems().add(createQueuedJobMenuItem(scheduled));
            }
        }
    }

    private MenuItem createQueuedJobMenuItem(NextScheduledJob scheduled) {
        MenuItem item = new MenuItem();
        item.setDisable(true);
        item.getProperties().put(JOB_SCHEDULER_QUEUE_JOB_NAME_PROPERTY, displayJobName(scheduled.job()));
        item.getProperties().put(JOB_SCHEDULER_QUEUE_NEXT_RUN_PROPERTY, scheduled.nextRun());
        updateQueuedJobMenuItem(item);
        return item;
    }

    private void updateVisibleJobSchedulerQueueItemTimers(Menu menu) {
        if (menu == null || !menu.isShowing()) {
            return;
        }
        for (MenuItem item : menu.getItems()) {
            updateQueuedJobMenuItem(item);
        }
    }

    private void updateQueuedJobMenuItem(MenuItem item) {
        Object jobName = item.getProperties().get(JOB_SCHEDULER_QUEUE_JOB_NAME_PROPERTY);
        Object nextRun = item.getProperties().get(JOB_SCHEDULER_QUEUE_NEXT_RUN_PROPERTY);
        if (jobName instanceof String name && nextRun instanceof ZonedDateTime runAt) {
            item.setText(I18n.get(
                "jobscheduler.menu.queueItem",
                name,
                formatMenuStartTime(runAt),
                formatRemainingTime(runAt)));
        }
    }

    private void showJobSchedulerStatusContextMenu(Label label) {
        ContextMenu contextMenu = new ContextMenu();
        JobSchedulerService schedulerService = app.getJobSchedulerService();
        if (schedulerService != null) {
            for (ActiveJobSummary active : schedulerService.getActiveJobSummaries()) {
                String jobName = nonBlank(active.jobName(), active.jobId());
                MenuItem cancel = new MenuItem(I18n.get("jobscheduler.menu.cancel", jobName));
                cancel.setDisable(active.cancellationRequested());
                cancel.setOnAction(event -> schedulerService.cancelJob(active.jobId()));
                contextMenu.getItems().add(cancel);
            }
        }
        if (!contextMenu.getItems().isEmpty()) {
            contextMenu.getItems().add(new SeparatorMenuItem());
        }
        MenuItem openScheduler = new MenuItem(I18n.get("jobscheduler.menu.open"));
        openScheduler.setOnAction(event -> showJobScheduler());
        contextMenu.getItems().add(openScheduler);
        contextMenu.show(label, Side.BOTTOM, 0, 0);
    }

    private List<NextScheduledJob> nextScheduledJobs(JobSchedulerService schedulerService) {
        ZonedDateTime now = ZonedDateTime.now();
        return schedulerService.getJobs().stream()
            .filter(job -> job.isEnabled() && job.getNextRunAt() != null && !job.getNextRunAt().isBlank())
            .map(job -> parseJobNextRun(job)
                .filter(nextRun -> nextRun.isAfter(now))
                .map(nextRun -> new NextScheduledJob(job, nextRun)))
            .flatMap(Optional::stream)
            .sorted((left, right) -> left.nextRun().compareTo(right.nextRun()))
            .limit(JOB_SCHEDULER_QUEUE_LIMIT)
            .toList();
    }

    private Optional<ZonedDateTime> parseJobNextRun(ScheduledJob job) {
        try {
            return Optional.of(ZonedDateTime.parse(job.getNextRunAt()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String formatRemainingTime(ZonedDateTime nextRun) {
        long seconds = Math.max(0, java.time.Duration.between(ZonedDateTime.now(), nextRun).getSeconds());
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long secs = seconds % 60;
        if (days > 0) {
            return String.format(Locale.ROOT, "%dd %02d:%02d:%02d", days, hours, minutes, secs);
        }
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, secs);
    }

    private String formatMenuStartTime(ZonedDateTime nextRun) {
        return nextRun.format(JOB_SCHEDULER_MENU_TIME_FORMAT);
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String displayJobName(ScheduledJob job) {
        return job != null ? nonBlank(job.getName(), job.getId()) : "";
    }

    private record NextScheduledJob(ScheduledJob job, ZonedDateTime nextRun) {
    }

    private MenuBar createApplicationMenuBar(MenuBarTarget target) {
        MenuBar createdMenuBar = new MenuBar();
        createdMenuBar.getMenus().addAll(
            createFileMenu(),
            createEditMenu(target),
            createConnectionsMenu(),
            createManagementMenu(),
            createToolsMenu(),
            createPluginsMenu(),
            createViewMenu(target),
            createHelpMenu(),
            createJobSchedulerStatusMenu(target)
        );
        return createdMenuBar;
    }

    private Menu createFileMenu() {
        Menu fileMenu = new Menu(I18n.get("menu.file"));

        MenuItem newTab = new MenuItem(I18n.get("menu.file.newTab"));
        newTab.setAccelerator(new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN));
        newTab.setOnAction(e -> showQuickConnect());

        MenuItem closeTab = new MenuItem(I18n.get("menu.file.closeTab"));
        closeTab.setAccelerator(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN));
        closeTab.setOnAction(e -> closeCurrentTab());

        MenuItem closeAllTabs = new MenuItem(I18n.get("menu.file.closeAllTabs"));
        closeAllTabs.setOnAction(e -> confirmAndCloseAllTabs());

        MenuItem newWindow = new MenuItem(I18n.get("menu.file.newWindow"));
        newWindow.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        newWindow.setOnAction(e -> openNewWindow());

        MenuItem closeWindow = new MenuItem(I18n.get("menu.file.closeWindow"));
        closeWindow.setAccelerator(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        closeWindow.setOnAction(e -> fireCloseRequest());

        MenuItem openProject = new MenuItem(I18n.get("menu.file.openProject"));
        openProject.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN));
        openProject.setOnAction(e -> openProject());

        MenuItem saveProject = new MenuItem(I18n.get("menu.file.saveProject"));
        saveProject.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
        saveProject.setOnAction(e -> saveProject());

        MenuItem createBackup = new MenuItem(I18n.get("menu.edit.createBackup"));
        createBackup.setAccelerator(new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        createBackup.setOnAction(e -> createBackup());

        MenuItem importBackup = new MenuItem(I18n.get("menu.edit.importBackup"));
        importBackup.setOnAction(e -> importBackup());

        MenuItem quit = new MenuItem(I18n.get("menu.file.quit"));
        quit.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN));
        quit.setOnAction(e -> requestApplicationQuit());

        fileMenu.getItems().addAll(
            newTab, closeTab, closeAllTabs, new SeparatorMenuItem(),
            newWindow, closeWindow, new SeparatorMenuItem(),
            openProject, saveProject, new SeparatorMenuItem(),
            createBackup, importBackup, new SeparatorMenuItem(), quit);
        return fileMenu;
    }

    private Menu createEditMenu(MenuBarTarget target) {
        Menu editMenu = new Menu(I18n.get("menu.edit"));

        MenuItem cut = new MenuItem(I18n.get("menu.edit.cut"));
        cut.setAccelerator(new KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN));
        cut.setOnAction(e -> cutFromCurrentContext());
        if (target == MenuBarTarget.WINDOW) {
            cutMenuItem = cut;
        } else {
            systemCutMenuItem = cut;
        }
        updateEditMenuItemsForSelection();

        MenuItem copy = new MenuItem(I18n.get("menu.edit.copy"));
        copy.setAccelerator(new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN));
        copy.setOnAction(e -> copyFromTerminal());

        MenuItem paste = new MenuItem(I18n.get("menu.edit.paste"));
        paste.setAccelerator(PASTE_ACCELERATOR);
        paste.setOnAction(e -> pasteToTerminal());

        MenuItem find = new MenuItem(I18n.get("menu.edit.find"));
        find.setAccelerator(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN));
        find.setOnAction(e -> findInCurrentTab());

        MenuItem settings = new MenuItem(I18n.get("menu.settings.global"));
        settings.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN));
        settings.setOnAction(e -> showSettings());

        editMenu.getItems().addAll(cut, copy, paste, new SeparatorMenuItem(), find, new SeparatorMenuItem(), settings);
        return editMenu;
    }

    private Menu createConnectionsMenu() {
        Menu connectionsMenu = new Menu(I18n.get("menu.connections"));

        MenuItem quickConnect = new MenuItem(I18n.get("menu.connections.quickConnect"));
        quickConnect.setAccelerator(new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN));
        quickConnect.setOnAction(e -> showQuickConnect());

        MenuItem manageConnections = new MenuItem(I18n.get("menu.connections.manage"));
        manageConnections.setAccelerator(new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN));
        manageConnections.setOnAction(e -> showConnectionManager());

        MenuItem importConnections = new MenuItem(I18n.get("menu.connections.import"));
        importConnections.setOnAction(e -> importConnections());

        MenuItem exportConnections = new MenuItem(I18n.get("menu.connections.export"));
        exportConnections.setOnAction(e -> exportConnections());

        connectionsMenu.getItems().addAll(quickConnect, manageConnections,
            new SeparatorMenuItem(), importConnections, exportConnections);
        return connectionsMenu;
    }

    private Menu createManagementMenu() {
        Menu managementMenu = new Menu(I18n.get("menu.management"));

        MenuItem manageCredentials = new MenuItem(I18n.get("menu.management.credentials"));
        manageCredentials.setAccelerator(new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        manageCredentials.setOnAction(e -> showCredentialManagement());

        MenuItem manageGPGKeys = new MenuItem(I18n.get("menu.management.gpgKeys"));
        manageGPGKeys.setAccelerator(new KeyCodeCombination(KeyCode.G, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        manageGPGKeys.setOnAction(e -> showGPGKeyManagement());

        MenuItem manageSSHKeys = new MenuItem(I18n.get("menu.management.sshKeys"));
        manageSSHKeys.setAccelerator(new KeyCodeCombination(KeyCode.I, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        manageSSHKeys.setOnAction(e -> showSSHKeyManagement());

        MenuItem teamworkSettings = new MenuItem(I18n.get("menu.management.teamwork"));
        teamworkSettings.setOnAction(e -> showTeamworkSettings());

        managementMenu.getItems().addAll(manageCredentials, manageGPGKeys, manageSSHKeys, new SeparatorMenuItem(), teamworkSettings);
        return managementMenu;
    }

    private Menu createToolsMenu() {
        Menu toolsMenu = new Menu(I18n.get("menu.tools"));

        MenuItem openSFTPManager = new MenuItem(I18n.get("menu.tools.sftpManager"));
        openSFTPManager.setAccelerator(new KeyCodeCombination(KeyCode.U, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        openSFTPManager.setOnAction(e -> showSFTPManager());

        MenuItem asciiArtBanner = new MenuItem(I18n.get("menu.tools.asciiArtBanner"));
        asciiArtBanner.setAccelerator(new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        asciiArtBanner.setOnAction(e -> showAsciiArtBanner());

        MenuItem snippetManager = new MenuItem(I18n.get("menu.tools.snippets"));
        snippetManager.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        snippetManager.setOnAction(e -> showSnippetManager());

        MenuItem jobScheduler = new MenuItem(I18n.get("menu.tools.jobScheduler"));
        jobScheduler.setAccelerator(JOB_SCHEDULER_ACCELERATOR);
        jobScheduler.setOnAction(e -> showJobScheduler());

        MenuItem videoManager = new MenuItem(I18n.get("menu.tools.videoManager"));
        videoManager.setAccelerator(VIDEO_MANAGER_ACCELERATOR);
        videoManager.setOnAction(e -> showTerminalRecordingManager());

        MenuItem toggleRecording = new MenuItem(I18n.get("menu.tools.toggleRecording"));
        toggleRecording.setAccelerator(RECORDING_TOGGLE_ACCELERATOR);
        toggleRecording.setOnAction(e -> toggleTerminalRecording());

        MenuItem aiManager = new MenuItem(I18n.get("menu.tools.aiManager"));
        aiManager.setAccelerator(new KeyCodeCombination(KeyCode.Y, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        aiManager.setOnAction(e -> showAiManager());

        MenuItem aiAgent = new MenuItem(I18n.get("menu.tools.aiAgent"));
        aiAgent.setAccelerator(AI_AGENT_ACCELERATOR);
        aiAgent.setOnAction(e -> showAiAgent());

        MenuItem aiPlanning = new MenuItem(I18n.get("menu.tools.aiPlanning"));
        aiPlanning.setAccelerator(AI_PLANNING_ACCELERATOR);
        aiPlanning.setOnAction(e -> showAiPlanning());

        toolsAiMenuItems.add(aiManager);
        toolsAiMenuItems.add(aiAgent);
        toolsAiMenuItems.add(aiPlanning);
        toolsAiAgentExecutionMenuItems.add(aiAgent);

        toolsMenu.getItems().addAll(
            openSFTPManager,
            new SeparatorMenuItem(),
            asciiArtBanner,
            new SeparatorMenuItem(),
            snippetManager,
            new SeparatorMenuItem(),
            jobScheduler,
            new SeparatorMenuItem(),
            videoManager,
            toggleRecording,
            new SeparatorMenuItem(),
            aiManager,
            aiAgent,
            aiPlanning);
        return toolsMenu;
    }

    private Menu createPluginsMenu() {
        Menu pluginsMenu = new Menu(I18n.get("menu.plugins"));
        MenuItem terminalEffects = new MenuItem(I18n.get("menu.plugins.terminalEffects"));
        terminalEffects.setOnAction(event -> showTerminalEffectPluginManager());
        pluginsMenu.getItems().add(terminalEffects);
        return pluginsMenu;
    }

    private Menu createJobSchedulerStatusMenu(MenuBarTarget target) {
        Menu jobsMenu = new Menu(I18n.get("jobscheduler.menu.noJobs"));
        if (target == MenuBarTarget.WINDOW) {
            jobSchedulerStatusMenu = jobsMenu;
            Label label = new Label(I18n.get("jobscheduler.menu.noJobs"));
            label.setPadding(new Insets(0, 0, 0, JOB_SCHEDULER_STATUS_LEFT_PADDING));
            label.setOnContextMenuRequested(event -> {
                showJobSchedulerStatusContextMenu(label);
                event.consume();
            });
            label.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.SECONDARY) {
                    showJobSchedulerStatusContextMenu(label);
                    event.consume();
                }
            });
            jobsMenu.setGraphic(label);
            jobsMenu.setText("");
        } else {
            systemJobSchedulerStatusMenu = jobsMenu;
            jobsMenu.getProperties().put(JOB_SCHEDULER_STATUS_SPACED_TEXT_PROPERTY, Boolean.TRUE);
        }
        jobsMenu.setOnShowing(event -> {
            updateJobSchedulerStatusMenus();
            rebuildJobSchedulerStatusMenuItems(jobsMenu);
        });
        return jobsMenu;
    }

    private Menu createViewMenu(MenuBarTarget target) {
        Menu viewMenu = new Menu(I18n.get("menu.view"));
        boolean restoreDashboard = shouldRestoreDashboardOnStartup();

        CheckMenuItem dashboardItem = new CheckMenuItem(I18n.get("menu.view.dashboard"));
        dashboardItem.setAccelerator(new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        dashboardItem.setSelected(restoreDashboard);
        dashboardItem.setOnAction(e -> toggleDashboard(dashboardItem.isSelected()));
        if (target == MenuBarTarget.WINDOW) {
            showDashboardMenuItem = dashboardItem;
        } else {
            systemShowDashboardMenuItem = dashboardItem;
        }

        CheckMenuItem timestampsItem = new CheckMenuItem(I18n.get("menu.view.timestamps"));
        timestampsItem.setAccelerator(new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        timestampsItem.setOnAction(e -> toggleTimestampsInCurrentTab(timestampsItem));
        if (target == MenuBarTarget.WINDOW) {
            showTimestampsMenuItem = timestampsItem;
        } else {
            systemShowTimestampsMenuItem = timestampsItem;
        }

        CheckMenuItem menuBarItem = new CheckMenuItem(I18n.get("menu.view.menuBar"));
        menuBarItem.setAccelerator(MENU_BAR_TOGGLE_ACCELERATOR);
        menuBarItem.setSelected(isMenuBarVisiblePreference());
        menuBarItem.setOnAction(e -> toggleMenuBarVisibility(menuBarItem.isSelected()));
        if (target == MenuBarTarget.WINDOW) {
            showMenuBarMenuItem = menuBarItem;
        } else {
            systemShowMenuBarMenuItem = menuBarItem;
        }

        // File Browser submenu
        Menu fileBrowserMenu = new Menu(I18n.get("menu.view.fileBrowser"));
        CheckMenuItem fileBrowserLeftItem = new CheckMenuItem(I18n.get("menu.view.fileBrowser.left"));
        fileBrowserLeftItem.setAccelerator(new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        fileBrowserLeftItem.setOnAction(e -> {
            if (fileBrowserLeftItem.isSelected()) {
                toggleFileBrowser(LocalFileBrowserManager.Position.LEFT);
            } else {
                fileBrowserManager.hide();
            }
        });
        CheckMenuItem fileBrowserRightItem = new CheckMenuItem(I18n.get("menu.view.fileBrowser.right"));
        fileBrowserRightItem.setAccelerator(new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        fileBrowserRightItem.setOnAction(e -> {
            if (fileBrowserRightItem.isSelected()) {
                toggleFileBrowser(LocalFileBrowserManager.Position.RIGHT);
            } else {
                fileBrowserManager.hide();
            }
        });
        // Store references for both window and system menus so syncFileBrowserMenuItems can update both
        if (target == MenuBarTarget.WINDOW) {
            showFileBrowserLeftMenuItem = fileBrowserLeftItem;
            showFileBrowserRightMenuItem = fileBrowserRightItem;
        } else {
            systemShowFileBrowserLeftMenuItem = fileBrowserLeftItem;
            systemShowFileBrowserRightMenuItem = fileBrowserRightItem;
        }
        fileBrowserMenu.getItems().addAll(fileBrowserLeftItem, fileBrowserRightItem);

        MenuItem zoomIn = new MenuItem(I18n.get("menu.view.zoomIn"));
        zoomIn.setAccelerator(new KeyCodeCombination(KeyCode.PLUS, KeyCombination.ALT_DOWN));
        zoomIn.setOnAction(e -> zoomTerminal(1));

        MenuItem zoomOut = new MenuItem(I18n.get("menu.view.zoomOut"));
        zoomOut.setAccelerator(new KeyCodeCombination(KeyCode.MINUS, KeyCombination.ALT_DOWN));
        zoomOut.setOnAction(e -> zoomTerminal(-1));

        MenuItem resetZoom = new MenuItem(I18n.get("menu.view.resetZoom"));
        resetZoom.setAccelerator(new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.ALT_DOWN));
        resetZoom.setOnAction(e -> resetTerminalZoom());

        Menu terminalEffectMenu = createTerminalEffectMenu(null);
        terminalEffectMenu.setOnShowing(event ->
                rebuildTerminalEffectMenu(terminalEffectMenu, getActiveTerminalTab()));

        MenuItem fullscreen = new MenuItem(I18n.get("menu.view.fullscreen"));
        fullscreen.setAccelerator(new KeyCodeCombination(KeyCode.F11));
        fullscreen.setOnAction(e -> stage.setFullScreen(!stage.isFullScreen()));

        CheckMenuItem terminalOnlyFullscreen = new CheckMenuItem(I18n.get("menu.view.terminalOnlyFullscreen"));
        terminalOnlyFullscreen.setAccelerator(TERMINAL_ONLY_FULLSCREEN_ACCELERATOR);
        terminalOnlyFullscreen.setSelected(terminalOnlyFullscreenActive);
        terminalOnlyFullscreen.setOnAction(e -> setTerminalOnlyFullscreen(terminalOnlyFullscreen.isSelected()));
        if (target == MenuBarTarget.WINDOW) {
            terminalOnlyFullscreenMenuItem = terminalOnlyFullscreen;
        } else {
            systemTerminalOnlyFullscreenMenuItem = terminalOnlyFullscreen;
        }

        CheckMenuItem hideFullscreenScrollbars =
            new CheckMenuItem(I18n.get("menu.view.hideTerminalScrollbarsFullscreen"));
        hideFullscreenScrollbars.setSelected(isHideTerminalScrollbarsInFullscreenPreference());
        hideFullscreenScrollbars.setOnAction(e ->
            setHideTerminalScrollbarsInFullscreen(hideFullscreenScrollbars.isSelected()));
        if (target == MenuBarTarget.WINDOW) {
            hideFullscreenScrollbarsMenuItem = hideFullscreenScrollbars;
        } else {
            systemHideFullscreenScrollbarsMenuItem = hideFullscreenScrollbars;
        }

        viewMenu.getItems().addAll(dashboardItem, timestampsItem, menuBarItem, fileBrowserMenu, new SeparatorMenuItem(),
            zoomIn, zoomOut, resetZoom, new SeparatorMenuItem(), terminalEffectMenu, new SeparatorMenuItem(),
            fullscreen, terminalOnlyFullscreen, hideFullscreenScrollbars);
        return viewMenu;
    }

    private Menu createTerminalEffectMenu(TerminalTab terminalTab) {
        Menu menu = new Menu(I18n.get("plugin.terminalEffect"));
        rebuildTerminalEffectMenu(menu, terminalTab);
        return menu;
    }

    private void rebuildTerminalEffectMenu(Menu menu, TerminalTab terminalTab) {
        menu.getItems().clear();
        if (!TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
            menu.setDisable(true);
            return;
        }
        menu.setDisable(false);
        ToggleGroup group = new ToggleGroup();
        String activePluginId = terminalTab != null ? terminalTab.getTerminalView().getTerminalEffectPluginId() : null;

        RadioMenuItem noneItem = new RadioMenuItem(I18n.get("plugin.none"));
        noneItem.setToggleGroup(group);
        noneItem.setSelected(activePluginId == null);
        noneItem.setDisable(terminalTab == null);
        noneItem.setOnAction(event -> {
            if (terminalTab != null) {
                terminalTab.getTerminalView().setTerminalEffectPluginId(null);
                rememberTerminalEffectPluginId(terminalTab, null);
                updateAllTabContextMenus();
            }
        });
        menu.getItems().add(noneItem);

        var manager = app.getTerminalEffectPluginManager();
        if (manager == null || manager.getPlugins().isEmpty()) {
            menu.setDisable(terminalTab == null);
            return;
        }

        menu.getItems().add(new SeparatorMenuItem());
        for (var plugin : manager.getPlugins()) {
            RadioMenuItem pluginItem = new RadioMenuItem(plugin.displayName());
            pluginItem.setToggleGroup(group);
            pluginItem.setSelected(plugin.id().equals(activePluginId));
            pluginItem.setDisable(terminalTab == null);
            pluginItem.setOnAction(event -> {
                if (terminalTab != null) {
                    terminalTab.getTerminalView().setTerminalEffectPluginId(plugin.id());
                    rememberTerminalEffectPluginId(terminalTab, plugin.id());
                    updateAllTabContextMenus();
                }
            });
            menu.getItems().add(pluginItem);
        }
        menu.getItems().add(new SeparatorMenuItem());
        menu.getItems().add(createTerminalEffectAnimationSpeedMenuItem(terminalTab, activePluginId != null));
        menu.setDisable(false);
    }

    private CustomMenuItem createTerminalEffectAnimationSpeedMenuItem(TerminalTab terminalTab, boolean enabled) {
        double currentSpeed = terminalTab != null
                ? terminalTab.getTerminalView().getTerminalEffectAnimationSpeed()
                : TerminalEffectAnimationSpeed.DEFAULT;
        TerminalEffectUiSupport.AnimationSpeedControls speedControls =
                TerminalEffectUiSupport.createAnimationSpeedControls(currentSpeed);
        speedControls.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (terminalTab != null) {
                terminalTab.getTerminalView().setTerminalEffectAnimationSpeed(newValue.doubleValue());
                rememberTerminalEffectAnimationSpeed(terminalTab, newValue.doubleValue());
            }
        });

        VBox content = speedControls.root();
        content.setPadding(new Insets(4, 8, 6, 8));
        CustomMenuItem item = new CustomMenuItem(content);
        item.setHideOnClick(false);
        item.setDisable(terminalTab == null || !enabled);
        return item;
    }

    private void rememberTerminalEffectAnimationSpeed(TerminalTab terminalTab, double speed) {
        if (terminalTab == null || terminalTab.getTerminalView() == null) {
            return;
        }
        String pluginId = terminalTab.getTerminalView().getTerminalEffectPluginId();
        Double speedForStorage = TerminalEffectUiSupport.animationSpeedForStorage(pluginId, speed);
        ServerConnection connection = terminalTab.getConnection();
        if (connection != null) {
            connection.setTerminalEffectAnimationSpeed(speedForStorage);
        }

        if (rememberSavedConnectionTerminalEffectAnimationSpeed(connection, speedForStorage)) {
            return;
        }
        rememberQuickConnectTerminalEffectAnimationSpeed(speedForStorage);
    }

    private boolean rememberSavedConnectionTerminalEffectAnimationSpeed(
            ServerConnection connection,
            Double speedForStorage) {
        if (connection == null || connection.getId() == null || app == null || app.getConfigManager() == null) {
            return false;
        }
        ServerConnection stored = app.getConfigManager().getConnectionById(connection.getId());
        if (stored == null) {
            return false;
        }
        stored.setTerminalEffectAnimationSpeed(speedForStorage);
        try {
            app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
        } catch (Exception e) {
            logger.warn("Could not persist terminal effect animation speed for '{}': {}",
                    stored.getDisplayName(), e.getMessage());
        }
        return true;
    }

    private void rememberQuickConnectTerminalEffectAnimationSpeed(Double speedForStorage) {
        if (app == null || app.getGlobalSettingsManager() == null) {
            return;
        }
        try {
            GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
            if (settings != null) {
                settings.setLastQuickConnectTerminalEffectAnimationSpeed(speedForStorage);
                app.getGlobalSettingsManager().save();
            }
        } catch (Exception e) {
            logger.warn("Could not persist QuickConnect terminal effect animation speed: {}", e.getMessage());
        }
    }

    private void rememberTerminalEffectPluginId(TerminalTab terminalTab, String pluginId) {
        if (terminalTab == null) {
            return;
        }
        ServerConnection connection = terminalTab.getConnection();
        if (connection != null) {
            connection.setTerminalEffectPluginId(pluginId);
        }
        rememberSavedConnectionTerminalEffectPluginId(connection, pluginId);
    }

    private void rememberSavedConnectionTerminalEffectPluginId(ServerConnection connection, String pluginId) {
        if (connection == null || connection.getId() == null || app == null || app.getConfigManager() == null) {
            return;
        }
        ServerConnection stored = app.getConfigManager().getConnectionById(connection.getId());
        if (stored == null) {
            return;
        }
        stored.setTerminalEffectPluginId(pluginId);
        try {
            app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
        } catch (Exception e) {
            logger.warn("Could not persist terminal effect plugin for '{}': {}",
                    stored.getDisplayName(), e.getMessage());
        }
    }

    private Menu createHelpMenu() {
        Menu helpMenu = new Menu(I18n.get("menu.help"));
        MenuItem about = new MenuItem(I18n.get("menu.help.about") + " " + KorTTYApplication.getAppName());
        about.setOnAction(e -> showAbout());
        helpMenu.getItems().add(about);
        return helpMenu;
    }

    private boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private boolean shouldRestoreDashboardOnStartup() {
        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        return globalSettings != null && globalSettings.isRememberDashboardState() && globalSettings.isDashboardVisible();
    }
    
    private void setupKeyBindings() {
        stage.getScene().setOnKeyPressed(e -> {
            // Tab switching with Ctrl+Tab / Ctrl+Shift+Tab
            if (e.isControlDown() && e.getCode() == KeyCode.TAB) {
                if (e.isShiftDown()) {
                    selectPreviousTab();
                } else {
                    selectNextTab();
                }
                e.consume();
            }
        });
    }
    
    public void show() {
        // Restore window geometry if enabled
        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        
        // Determine which geometry to use
        WindowGeometry geoToUse = null;
        
        if (globalSettings.isUseFixedWindowGeometry() && globalSettings.getFixedWindowGeometry() != null) {
            // Use fixed geometry
            geoToUse = globalSettings.getFixedWindowGeometry();
        } else if (globalSettings.isRememberWindowGeometry() && globalSettings.getLastWindowGeometry() != null) {
            // Use last geometry
            geoToUse = globalSettings.getLastWindowGeometry();
        }
        
        if (geoToUse != null) {
            stage.setX(geoToUse.getX());
            stage.setY(geoToUse.getY());
            stage.setWidth(geoToUse.getWidth());
            stage.setHeight(geoToUse.getHeight());
            if (geoToUse.isMaximized()) {
                stage.setMaximized(true);
            }
        }
        
        stage.show();
        
        // Mark startup as complete after a short delay to allow UI to settle
        Platform.runLater(() -> {
            Platform.runLater(() -> {
                startupComplete = true;
            });
        });
        
        // Restore dashboard state if enabled
        if (shouldRestoreDashboardOnStartup()) {
            Platform.runLater(() -> toggleDashboard(true));
        }
    }
    
    /**
     * Kept for tab classes that notify the main window before closing.
     */
    public static void suppressNextQuickConnect() {
    }
    
    /**
     * Opens a new SSH connection in a new tab.
     */
    public void openConnection(ServerConnection connection, String password) {
        openConnection(connection, password, null, null);
    }
    
    /**
     * Opens a new SSH connection in a new tab with optional history restore.
     */
    public void openConnection(ServerConnection connection, String password, String historyToRestore) {
        openConnection(connection, password, historyToRestore, null);
    }
    
    /**
     * Opens a new SSH connection in a new tab with optional history restore and temporary SSH key.
     */
    public void openConnection(ServerConnection connection, String password, String historyToRestore, de.kortty.model.TemporarySSHKey temporarySSHKey) {
        openConnectionAndReturnTab(
                connection,
                password,
                historyToRestore,
                temporarySSHKey,
                connection != null ? connection.getTerminalEffectPluginId() : null,
                connection != null ? connection.getTerminalEffectAnimationSpeed() : null);
    }
    
    /**
     * Opens a new SSH connection in a new tab with optional history restore and returns the tab.
     * The tab starts with NO group (independent from connection group).
     */
    private TerminalTab openConnectionAndReturnTab(ServerConnection connection, String password, String historyToRestore, de.kortty.model.TemporarySSHKey temporarySSHKey) {
        return openConnectionAndReturnTab(
                connection,
                password,
                historyToRestore,
                temporarySSHKey,
                connection != null ? connection.getTerminalEffectPluginId() : null,
                connection != null ? connection.getTerminalEffectAnimationSpeed() : null);
    }

    private TerminalTab openConnectionAndReturnTab(
            ServerConnection connection,
            String password,
            String historyToRestore,
            de.kortty.model.TemporarySSHKey temporarySSHKey,
            String terminalEffectPluginId,
            Double terminalEffectAnimationSpeed) {
        try {
            if (!TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
                terminalEffectPluginId = null;
                terminalEffectAnimationSpeed = null;
            }
            // Resolve temporary SSH key when connection was configured with one
            de.kortty.model.TemporarySSHKey keyToUse = temporarySSHKey;
            if (keyToUse == null && connection.getTemporaryKeyContent() != null && !connection.getTemporaryKeyContent().trim().isEmpty()) {
                de.kortty.core.TemporarySSHKeyManager keyManager = de.kortty.core.TemporarySSHKeyManager.getInstance();
                Long expirationMinutes = connection.getTemporaryKeyExpirationMinutes();
                if (expirationMinutes == null || expirationMinutes <= 0) {
                    expirationMinutes = 15L;
                }
                de.kortty.model.TemporarySSHKey existingKey = keyManager.getTemporaryKey(connection.getTemporaryKeyContent());
                if (existingKey != null && existingKey.isValid()) {
                    keyToUse = existingKey;
                } else {
                    keyToUse = keyManager.storeTemporaryKey(connection.getTemporaryKeyContent(), expirationMinutes);
                }
                connection.setPrivateKeyPath("TEMPORARY:" + connection.getTemporaryKeyContent());
                connection.setAuthMethod(de.kortty.model.AuthMethod.PUBLIC_KEY);
            } else if (keyToUse == null && connection.isTemporaryKeyPermanent() && 
                connection.getTemporaryKeyContent() != null && !connection.getTemporaryKeyContent().trim().isEmpty()) {
                // Legacy path: permanent temporary key
                Long expirationMinutes = connection.getTemporaryKeyExpirationMinutes();
                if (expirationMinutes != null && expirationMinutes > 0) {
                    de.kortty.core.TemporarySSHKeyManager keyManager = de.kortty.core.TemporarySSHKeyManager.getInstance();
                    de.kortty.model.TemporarySSHKey existingKey = keyManager.getTemporaryKey(connection.getTemporaryKeyContent());
                    if (existingKey != null && existingKey.isValid()) {
                        keyToUse = existingKey;
                    } else {
                        keyToUse = keyManager.storeTemporaryKey(connection.getTemporaryKeyContent(), expirationMinutes);
                    }
                    connection.setPrivateKeyPath("TEMPORARY:" + connection.getTemporaryKeyContent());
                    connection.setAuthMethod(de.kortty.model.AuthMethod.PUBLIC_KEY);
                }
            }
            
            // Create terminal tab with SithTermFX
            // Note: Tab starts with NO group (tabGroup = null), even if connection has a group
            TerminalTab terminalTab = new TerminalTab(connection, password, keyToUse);
            if (terminalEffectAnimationSpeed != null) {
                terminalTab.getTerminalView().setTerminalEffectAnimationSpeed(terminalEffectAnimationSpeed);
            }
            terminalTab.getTerminalView().setTerminalEffectPluginId(terminalEffectPluginId);
            
            // Set callback for "Split with new connection" feature
            terminalTab.getTerminalView().setNewConnectionCallback(this::requestNewConnectionForSplit);
            installAiSelectionHandler(terminalTab);
            
            // Register timestamp toggle listener so context menu toggle updates the View menu
            terminalTab.setTimestampToggleListener(() -> {
                Platform.runLater(() -> {
                    Tab activeTab = tabPane.getSelectionModel().getSelectedItem();
                    if (activeTab instanceof TerminalTab active) {
                        syncTimestampMenuItems(active.isTimestampGuttersVisible());
                    }
                });
            });
            
            terminalTab.setOnClosed(e -> {
                updateDashboard();
                organizeTabsByGroup();
                updateAllTabContextMenus(); // Update context menus when tab closes
            });
            
            // Setup context menu for group assignment (before group assignment)
            setupTabContextMenu(terminalTab);
            
            // Assign group from connection if present (for initial assignment)
            // This allows connection groups to be used as default for new tabs
            // Groups are automatically created if they don't exist yet
            if (connection.getGroup() != null && !connection.getGroup().trim().isEmpty()) {
                terminalTab.setGroup(connection.getGroup().trim());
            }
            
            // Insert new terminal tabs in group order.
            insertTabInGroupOrder(terminalTab);
            tabPane.getSelectionModel().select(terminalTab);
            
            // Update dashboard and context menus after group assignment
            updateDashboard();
            updateAllTabContextMenus();
            
            // Connect in background
            Thread connectThread = new Thread(() -> {
                try {
                    terminalTab.connect();
                    
                    // Set callback AFTER connect() to update dashboard when connection succeeds
                    // Note: TerminalTab.connect() sets a callback for tab title/color update.
                    // Since we're overwriting it, we need to also do what TerminalTab's callback does:
                    // - Update tab title
                    // - Reset tab color (setStyle(""))
                    terminalTab.getTerminalView().setOnConnectedCallback(() -> {
                        Platform.runLater(() -> {
                            // Update tab title (what TerminalTab's callback does)
                            terminalTab.updateTabTitle();
                            // Reset tab color (TerminalTab's resetTabColor() does setStyle(""))
                            terminalTab.setStyle("");
                            // Update status and dashboard
                            updateStatus(I18n.get("status.connectedToWithHostAndProtocol",
                                    connection.getDisplayName(), connection.getHost(), getProtocolLabel(connection.getProtocol())));
                            updateDashboard(); // Update dashboard when connection succeeds
                        });
                    });
                    
                    // Restore history after connection is established
                    if (historyToRestore != null && !historyToRestore.isEmpty()) {
                        // Wait a bit for terminal to be fully initialized
                        new Thread(() -> {
                            try {
                                Thread.sleep(500); // Give terminal time to settle
                                Platform.runLater(() -> {
                                    terminalTab.getTerminalView().restoreHistory(historyToRestore);
                                    logger.info("Terminal history restored for {}", connection.getDisplayName());
                                });
                            } catch (InterruptedException e) {
                                logger.error("History restore interrupted", e);
                            }
                        }).start();
                    }
                } catch (Exception ex) {
                    logger.error("Connection failed", ex);
                    Platform.runLater(() -> {
                        terminalTab.onConnectionFailed(ex.getMessage());
                        updateStatus(I18n.get("status.connectionFailed", ex.getMessage()));
                        updateDashboard(); // Update dashboard on failure too
                    });
                }
            });
            connectThread.setDaemon(true);
            connectThread.start();
            
            // Don't update dashboard immediately - wait for connection to establish
            // Dashboard will be updated after connection succeeds/fails
            return terminalTab;
        } catch (Exception e) {
            logger.error("Failed to create session", e);
            showError(I18n.get("error.connectionError"), I18n.get("status.sessionCreationFailed", e.getMessage()));
            return null;
        }
    }
    
    private void showQuickConnect() {
        // Prevent double-opening
        if (quickConnectDialogOpen) {
            return;
        }
        
        quickConnectDialogOpen = true;
        
        try {
            // Create password vault for retrieving stored passwords
            PasswordVault vault = new PasswordVault(
                    app.getMasterPasswordManager().getEncryptionService(),
                    app.getMasterPasswordManager().getMasterPassword()
            );
            
            // Pass saved connections and vault to the dialog
            QuickConnectDialog dialog = new QuickConnectDialog(stage, app.getConfigManager().getConnections(), vault, 
                    app.getCredentialManager(), app.getSSHKeyManager(), 
                    app.getMasterPasswordManager().getMasterPassword(), 10);
            dialog.showAndWait().ifPresent(result -> {
            // Handle load project request
            if (result.isLoadProject()) {
                openProject();
                return;
            }
            
            // Handle group connection
            if (result.isGroupConnection()) {
                openGroupConnections(result.groupName());
                return;
            }
            
            if (result.connection() == null) {
                return;
            }
            
            String password = result.password();
            String finalPassword = ensurePasswordForConnection(result.connection(), password);
            if (result.connection().getAuthMethod() != de.kortty.model.AuthMethod.PUBLIC_KEY
                    && (finalPassword == null || finalPassword.isBlank())) {
                return; // User cancelled password prompt or no valid password available
            }
            
            // Increment usage count for existing saved connection (update stored connection so "last used" is correct)
            if (result.existingSaved()) {
                ServerConnection stored = app.getConfigManager().getConnectionById(result.connection().getId());
                if (stored != null) {
                    stored.incrementUsageCount();
                    if (result.connection().getSettings() != null) {
                        stored.setSettings(new ConnectionSettings(result.connection().getSettings()));
                    }
                    stored.setTerminalEffectPluginId(result.connection().getTerminalEffectPluginId());
                    stored.setTerminalEffectAnimationSpeed(result.connection().getTerminalEffectAnimationSpeed());
                    stored.setTerminalEmulationType(result.connection().getTerminalEmulationType());
                    app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
                }
            }
            
            // Save connection if requested (for new connections)
            if (result.save() && !result.existingSaved()) {
                // Store password encrypted
                if (finalPassword != null && !finalPassword.isEmpty()) {
                    vault.storePassword(result.connection(), finalPassword);
                }
                app.getConfigManager().addConnection(result.connection());
                try {
                    app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
                    logger.info("Connection saved: {}", result.connection().getDisplayName());
                } catch (Exception e) {
                    logger.error("Failed to save connection", e);
                }
            }
            // Pass temporary SSH key if available
            openConnection(result.connection(), finalPassword, null, result.temporarySSHKey());
            });
        } finally {
            quickConnectDialogOpen = false;
        }
    }
    
    /**
     * Opens QuickConnect dialog for split terminal and returns the connection result.
     * Used by TerminalView when user requests "Split with new connection".
     */
    private TerminalView.ConnectionResult requestNewConnectionForSplit() {
        logger.info("requestNewConnectionForSplit() called - Opening QuickConnect for split");
        
        try {
            // Create password vault for retrieving stored passwords
            PasswordVault vault = new PasswordVault(
                    app.getMasterPasswordManager().getEncryptionService(),
                    app.getMasterPasswordManager().getMasterPassword()
            );
            
            // Open QuickConnect dialog
            QuickConnectDialog dialog = new QuickConnectDialog(stage, app.getConfigManager().getConnections(), vault,
                    app.getCredentialManager(), app.getSSHKeyManager(),
                    app.getMasterPasswordManager().getMasterPassword(), 10);
            
            var optResult = dialog.showAndWait();
            if (optResult.isEmpty()) {
                return null; // User cancelled
            }
            
            var result = optResult.get();
            
            // Don't handle load project or group connection for splits
            if (result.isLoadProject() || result.isGroupConnection()) {
                return null;
            }
            
            if (result.connection() == null) {
                return null;
            }
            
            String password = result.password();
            String finalPassword = ensurePasswordForConnection(result.connection(), password);
            if (result.connection().getAuthMethod() != de.kortty.model.AuthMethod.PUBLIC_KEY
                    && (finalPassword == null || finalPassword.isBlank())) {
                return null;
            }
            
            // Increment usage count for existing saved connection (update stored connection so "last used" is correct)
            if (result.existingSaved()) {
                ServerConnection stored = app.getConfigManager().getConnectionById(result.connection().getId());
                if (stored != null) {
                    stored.incrementUsageCount();
                    if (result.connection().getSettings() != null) {
                        stored.setSettings(new ConnectionSettings(result.connection().getSettings()));
                    }
                    stored.setTerminalEffectPluginId(result.connection().getTerminalEffectPluginId());
                    stored.setTerminalEffectAnimationSpeed(result.connection().getTerminalEffectAnimationSpeed());
                    stored.setTerminalEmulationType(result.connection().getTerminalEmulationType());
                    app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
                }
            }
            
            // Save connection if requested (for new connections)
            if (result.save() && !result.existingSaved()) {
                if (finalPassword != null && !finalPassword.isEmpty()) {
                    vault.storePassword(result.connection(), finalPassword);
                }
                app.getConfigManager().addConnection(result.connection());
                try {
                    app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
                    logger.info("Connection saved: {}", result.connection().getDisplayName());
                } catch (Exception e) {
                    logger.error("Failed to save connection", e);
                }
            }
            
            return new TerminalView.ConnectionResult(result.connection(), finalPassword);
        } catch (Exception e) {
            logger.error("Failed to request new connection for split", e);
            return null;
        }
    }

    private String ensurePasswordForConnection(ServerConnection connection, String candidatePassword) {
        if (connection == null || connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY) {
            return candidatePassword;
        }

        if (candidatePassword != null && !candidatePassword.isBlank()) {
            return candidatePassword;
        }

        String stored = getConnectionPassword(connection);
        if (stored != null && !stored.isBlank()) {
            return stored;
        }

        Dialog<String> pwDialog = new Dialog<>();
        DialogThemeHelper.applyTheme(pwDialog);
        pwDialog.initOwner(stage);
        pwDialog.setTitle(I18n.get("dialog.passwordRequired"));
        pwDialog.setHeaderText(I18n.get("dialog.passwordFor", connection.getDisplayName()));
        pwDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        PasswordField pwField = new PasswordField();
        pwField.setPromptText(I18n.get("dialog.enterPassword"));
        VBox content = new VBox(10);
        content.getChildren().addAll(new Label(I18n.get("dialog.pleaseEnterPassword")), pwField);
        content.setPadding(new javafx.geometry.Insets(20));
        pwDialog.getDialogPane().setContent(content);
        Button okButton = (Button) pwDialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.disableProperty().bind(pwField.textProperty().isEmpty());
        pwField.requestFocus();
        pwDialog.setResultConverter(bt -> bt == ButtonType.OK ? pwField.getText() : null);
        return pwDialog.showAndWait().orElse(null);
    }
    
    private void showConnectionManager() {
        logger.info("showConnectionManager() called - Opening Connection Manager");
        ConnectionManagerDialog dialog = new ConnectionManagerDialog(stage, app);
        dialog.setOnConnectionsSavedCallback(this::refreshAllTerminalTabsConnectionSettings);
        dialog.showAndWait().ifPresent(connection -> {
            // For teamwork connections without auth, apply default credential/SSH key from GlobalSettings
            final ServerConnection conn = resolveTeamworkConnectionAuth(connection);
            GlobalSettings gs = app.getGlobalSettingsManager().getSettings();
            // Teamwork default "temporary SSH key": ask for temp key and connect (no stored credential/key)
            if (conn.isTeamworkConnection() && conn.getCredentialId() == null && conn.getSshKeyId() == null
                    && gs.getTeamworkUseTemporaryKey()) {
                de.kortty.model.TemporarySSHKey tempKey = requestNewTemporarySSHKey(conn);
                if (tempKey != null) {
                    openConnection(conn, null, null, tempKey);
                }
                return;
            }
            // Check if connection uses a temporary SSH key
            de.kortty.model.TemporarySSHKey tempKey = null;
            if (conn.getTemporaryKeyContent() != null && !conn.getTemporaryKeyContent().trim().isEmpty()) {
                de.kortty.core.TemporarySSHKeyManager keyManager = de.kortty.core.TemporarySSHKeyManager.getInstance();
                tempKey = keyManager.getTemporaryKey(conn.getTemporaryKeyContent());
                if (tempKey != null && tempKey.isValid()) {
                    // Valid temp key found - connect directly without password dialog
                    logger.info("Using existing temporary SSH key for saved connection (valid for {} more seconds)",
                            tempKey.getRemainingSeconds());
                    openConnection(conn, null, null, tempKey);
                    return;
                }
                // Key expired or not found - ask user for a new temporary key
                tempKey = requestNewTemporarySSHKey(conn);
                if (tempKey != null) {
                    openConnection(conn, null, null, tempKey);
                    return;
                }
                // User cancelled - do not connect
                return;
            }
            
            // SSH key auth does not require a password - connect directly
            if (conn.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY) {
                openConnection(conn, null);
                return;
            }
            
            // Non-key connection: ask for password if needed
            String password = getConnectionPassword(conn);
            if (password == null) {
                Dialog<String> pwDialog = new Dialog<>();
                DialogThemeHelper.applyTheme(pwDialog);
                pwDialog.setTitle(I18n.get("dialog.passwordRequired"));
                pwDialog.setHeaderText(I18n.get("dialog.passwordFor", conn.getDisplayName()));
                pwDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
                PasswordField pwField = new PasswordField();
                pwField.setPromptText(I18n.get("dialog.enterPassword"));
                VBox content = new VBox(10);
                content.getChildren().addAll(new Label(I18n.get("dialog.pleaseEnterPassword")), pwField);
                content.setPadding(new javafx.geometry.Insets(20));
                pwDialog.getDialogPane().setContent(content);
                pwDialog.setResultConverter(bt -> bt == ButtonType.OK ? pwField.getText() : null);
                pwDialog.showAndWait().ifPresent(pw -> {
                    if (pw != null && !pw.isEmpty()) {
                        openConnection(conn, pw);
                    }
                });
            } else {
                openConnection(conn, password);
            }
        });
    }
    
    /**
     * Applies the current saved connection settings (font, etc.) to all open terminal tabs.
     * Called when connections are saved in Connection Manager so changes take effect immediately.
     */
    private void refreshAllTerminalTabsConnectionSettings() {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                ServerConnection conn = terminalTab.getConnection();
                if (conn != null && conn.getId() != null) {
                    ServerConnection stored = app.getConfigManager().getConnectionById(conn.getId());
                    if (stored != null) {
                        if (stored.getSettings() != null) {
                            terminalTab.applyConnectionSettings(stored.getSettings());
                        }
                        if (TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
                            terminalTab.getTerminalView().setTerminalEffectAnimationSpeed(
                                    stored.getTerminalEffectAnimationSpeed() != null
                                            ? stored.getTerminalEffectAnimationSpeed()
                                            : TerminalEffectAnimationSpeed.DEFAULT);
                            terminalTab.getTerminalView().setTerminalEffectPluginId(stored.getTerminalEffectPluginId());
                        } else {
                            terminalTab.getTerminalView().setTerminalEffectPluginId(null);
                        }
                    }
                }
            }
        }
    }
    
    private void showSettings() {
        SettingsDialog dialog = new SettingsDialog(stage, app, app.getConfigManager(), 
                app.getGlobalSettingsManager().getSettings(),
                app.getCredentialManager(), app.getGpgKeyManager());
        
        // Add listener to apply settings changes immediately to all open terminals
        dialog.addChangeListener(() -> {
            logger.info("Settings changed, updating all terminal views");
            Platform.runLater(() -> {
                refreshAppDesignForOpenWindows();
                applyMenuBarVisibility(isMenuBarVisiblePreference());
                syncHideFullscreenScrollbarsMenuItems();
                applyTerminalScrollbarVisibilityForOpenTabs();
                syncAiFeaturesMenuItemsEnabled();
                refreshTerminalTabsUsingGlobalDefaults();
                refreshTerminalRecordingControlsVisibility();
                if (menuBar != null && !menuBar.isVisible()) {
                    updateStatus(I18n.get("menu.view.menuBar.hiddenHint", MENU_BAR_TOGGLE_SHORTCUT_LABEL));
                } else {
                    updateStatus(I18n.get("status.globalSettingsSaved"));
                }
            });
        });
        
        dialog.showAndWait();
    }

    private void refreshTerminalTabsUsingGlobalDefaults() {
        ConnectionSettings globalDefaults = null;
        try {
            var gs = app.getGlobalSettingsManager().getSettings();
            if (gs != null && gs.getDefaultTerminalSettings() != null) {
                globalDefaults = new ConnectionSettings(gs.getDefaultTerminalSettings());
            }
        } catch (Exception e) {
            logger.debug("Could not load global defaults for live refresh: {}", e.getMessage());
        }
        if (globalDefaults == null) {
            return;
        }

        for (Tab tab : tabPane.getTabs()) {
            if (!(tab instanceof TerminalTab terminalTab)) {
                continue;
            }
            ServerConnection conn = terminalTab.getConnection();
            ConnectionSettings connSettings = conn != null ? conn.getSettings() : null;
            if (connSettings == null || connSettings.isUseGlobalSettings()) {
                terminalTab.applyConnectionSettings(globalDefaults);
            } else {
                terminalTab.applyConnectionSettings(connSettings);
            }
        }
    }

    private void applyMainWindowThemeFromGlobalSettings() {
        try {
            boolean customAppDesign = AppDesignStyleSupport.isCustomAppDesignActive();
            ThemeCssSupport.ThemeColors colors = ThemeCssSupport.resolveThemeColors(app);
            String bg = colors != null ? colors.backgroundColor() : null;
            String fg = colors != null ? colors.foregroundColor() : null;

            if (customAppDesign) {
                clearMainWindowInlineThemeStyles();
            } else if (bg != null && !bg.isEmpty()) {
                String bgStyle = "-fx-background-color: " + bg + ";";
                root.setStyle(bgStyle);
                mainContentBox.setStyle(bgStyle);
                tabPane.setStyle(bgStyle + " -fx-control-inner-background: " + bg + ";");
                statusBar.setStyle("-fx-padding: 5; " + bgStyle);
                if (stage.getScene() != null) {
                    stage.getScene().setFill(unifiedTitleBarEnabled ? Color.TRANSPARENT : Color.web(bg));
                }
            }
            if (!customAppDesign && fg != null && !fg.isEmpty()) {
                statusLabel.setStyle("-fx-text-fill: " + fg + ";");
            }
            if (dashboardView != null) {
                dashboardView.applyTheme(bg, fg);
            }
            if (localFileBrowser != null) {
                localFileBrowser.applyTheme(bg, fg);
            }
            if (bg != null && !bg.isEmpty()) {
                updateDynamicThemeStylesheet(bg, fg);
            }
            if (stage.getScene() != null) {
                AppDesignStyleSupport.applyToScene(stage.getScene());
                if (customAppDesign) {
                    stage.getScene().setFill(unifiedTitleBarEnabled
                        ? Color.TRANSPARENT
                        : Color.web(AppDesignStyleSupport.activeBackgroundColor()));
                }
            }
        } catch (Exception e) {
            logger.debug("Could not apply main window theme from global settings: {}", e.getMessage());
        }
    }

    private void clearMainWindowInlineThemeStyles() {
        root.setStyle(null);
        mainContentBox.setStyle(null);
        tabPane.setStyle(null);
        if (statusBar != null) {
            statusBar.setStyle("-fx-padding: 5;");
        }
        statusLabel.setStyle(null);
    }

    private void updateDynamicThemeStylesheet(String bg, String fg) {
        if (stage.getScene() == null || bg == null || bg.isEmpty()) {
            return;
        }
        try {
            String stylesheetUrl = ThemeCssSupport.getDynamicStylesheetUrl(bg, fg);
            if (stylesheetUrl == null) {
                return;
            }
            var sheets = stage.getScene().getStylesheets();
            if (dynamicThemeStylesheetUrl != null && !dynamicThemeStylesheetUrl.equals(stylesheetUrl)) {
                sheets.remove(dynamicThemeStylesheetUrl);
            }
            dynamicThemeStylesheetUrl = stylesheetUrl;
            if (!sheets.contains(dynamicThemeStylesheetUrl)) {
                sheets.add(dynamicThemeStylesheetUrl);
            }
        } catch (Exception e) {
            logger.debug("Could not update dynamic theme stylesheet: {}", e.getMessage());
        }
    }

    private static void refreshAppDesignForOpenWindows() {
        for (MainWindow window : new ArrayList<>(openWindows)) {
            window.applyMainWindowThemeFromGlobalSettings();
        }
        AppDesignStyleSupport.applyToOpenWindows();
    }
    
    private void openNewWindow() {
        Stage newStage = new Stage();
        MainWindow newWindow = new MainWindow(newStage);
        newWindow.show();
    }

    public static void reopenOrCreateWindow() {
        MainWindow targetWindow = getFocusedOrLastOpenWindow();
        if (targetWindow != null) {
            targetWindow.stage.show();
            targetWindow.stage.toFront();
            targetWindow.stage.requestFocus();
            return;
        }

        Stage newStage = new Stage();
        MainWindow newWindow = new MainWindow(newStage);
        newWindow.show();
    }

    public static boolean hasOpenWindows() {
        return !openWindows.isEmpty();
    }

    public static void requestApplicationQuit() {
        MainWindow promptWindow = getFocusedOrLastOpenWindow();
        if (maybeHandleSchedulerDrainBeforeExit(promptWindow, MainWindow::requestApplicationQuit)) {
            return;
        }

        List<MainWindow> windowsToClose = new ArrayList<>(openWindows);
        if (windowsToClose.isEmpty()) {
            applicationQuitRequested = true;
            Platform.exit();
            return;
        }

        applicationQuitApprovedWindows.clear();
        for (MainWindow window : windowsToClose) {
            if (!window.confirmClose()) {
                clearApplicationQuitState();
                return;
            }
            applicationQuitApprovedWindows.add(window);
        }

        applicationQuitRequested = true;
        for (MainWindow window : windowsToClose) {
            if (openWindows.contains(window)) {
                window.fireCloseRequest();
            }
        }
    }

    /**
     * Fires the window close request so the same confirmation and cleanup logic runs as when closing via the window button.
     */
    private void fireCloseRequest() {
        Event.fireEvent(stage, new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
    }

    private static MainWindow getFocusedOrLastOpenWindow() {
        for (MainWindow window : openWindows) {
            if (window.stage.isFocused()) {
                return window;
            }
        }

        if (openWindows.isEmpty()) {
            return null;
        }

        return openWindows.get(openWindows.size() - 1);
    }

    private static void clearApplicationQuitState() {
        applicationQuitRequested = false;
        applicationQuitApprovedWindows.clear();
        if (!schedulerDrainInProgress) {
            schedulerDrainApproved = false;
        }
    }

    private void closeCurrentTab() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab != null && currentTab.isClosable()) {
            tabPane.getTabs().remove(currentTab);
        }
    }
    
    /**
     * Asks the user for confirmation and then closes all tabs (without further prompts).
     */
    private void confirmAndCloseAllTabs() {
        long closableCount = tabPane.getTabs().stream().filter(Tab::isClosable).count();
        if (closableCount == 0) return;
        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        boolean skipConfirmation = globalSettings != null
            && globalSettings.isCloseActiveTerminalWindowsWithoutConfirmation();
        if (skipConfirmation) {
            closeAllTabs();
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("dialog.closeAllTabs.title"));
        alert.setHeaderText(I18n.get("dialog.closeAllTabs.header"));
        alert.setContentText(I18n.get("dialog.closeAllTabs.content"));
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        closeAllTabs();
    }

    private void closeAllTabs() {
        List<Tab> tabsToClose = new ArrayList<>(tabPane.getTabs());
        for (Tab tab : tabsToClose) {
            if (!tab.isClosable()) continue;
            if (tab instanceof TerminalTab terminalTab) {
                terminalTab.setOnCloseRequest(null);
                terminalTab.closeRecordingResources();
                terminalTab.getTerminalView().cleanup();
            }
            tabPane.getTabs().remove(tab);
        }
    }
    
    private boolean confirmClose() {
        if (willCloseApplication() && maybeHandleSchedulerDrainBeforeExit(this, this::fireCloseRequest)) {
            return false;
        }

        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        if (globalSettings != null && globalSettings.isCloseActiveTerminalWindowsWithoutConfirmation()) {
            return true;
        }

        // Count only active (connected) sessions
        long activeConnections = tabPane.getTabs().stream()
                .filter(t -> t instanceof TerminalTab)
                .map(t -> (TerminalTab) t)
                .filter(TerminalTab::isConnected)
                .count();
        
        // No confirmation needed if no active connections
        if (activeConnections == 0) {
            return true;
        }
        
        // Show confirmation for active connections
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("dialog.closeWindow"));
        alert.setHeaderText(I18n.get("dialog.activeConnections"));
        alert.setContentText(I18n.get("dialog.activeConnectionsMessage", activeConnections));
        
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private boolean willCloseApplication() {
        return applicationQuitRequested || (openWindows.size() <= 1 && !app.shouldKeepRunningAfterLastWindowClosed());
    }

    private static boolean maybeHandleSchedulerDrainBeforeExit(MainWindow promptWindow, Runnable afterDrain) {
        if (schedulerDrainApproved || schedulerDrainInProgress) {
            return schedulerDrainInProgress;
        }
        KorTTYApplication application = promptWindow != null ? promptWindow.app : KorTTYApplication.getInstance();
        if (application == null || application.getJobSchedulerService() == null) {
            return false;
        }
        List<ActiveJobSummary> activeJobs = application.getJobSchedulerService().getActiveJobSummaries();
        if (activeJobs.isEmpty()) {
            return false;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(alert);
        if (promptWindow != null) {
            alert.initOwner(promptWindow.stage);
        }
        alert.setTitle(I18n.get("jobscheduler.quit.title"));
        alert.setHeaderText(I18n.get("jobscheduler.quit.header", activeJobs.size()));
        alert.setContentText(I18n.get("jobscheduler.quit.content", formatActiveJobNames(activeJobs)));
        ButtonType waitAndQuit = new ButtonType(I18n.get("jobscheduler.quit.wait"), ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(waitAndQuit, ButtonType.CANCEL);
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != waitAndQuit) {
            schedulerDrainApproved = false;
            schedulerDrainInProgress = false;
            return true;
        }

        schedulerDrainInProgress = true;
        application.getJobSchedulerService().beginDrainForShutdown();
        showSchedulerDrainDialog(promptWindow, application, afterDrain);
        return true;
    }

    private static void showSchedulerDrainDialog(
        MainWindow promptWindow,
        KorTTYApplication application,
        Runnable afterDrain) {

        Dialog<Void> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        if (promptWindow != null) {
            dialog.initOwner(promptWindow.stage);
        }
        dialog.setTitle(I18n.get("jobscheduler.quit.waiting.title"));
        dialog.setHeaderText(I18n.get("jobscheduler.quit.waiting.header"));
        VBox content = new VBox(10,
            new ProgressIndicator(),
            new Label(I18n.get("jobscheduler.quit.waiting.content")));
        content.setPadding(new Insets(20));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll();
        dialog.show();

        Thread waiter = new Thread(() -> {
            boolean drainCompleted = false;
            try {
                application.getJobSchedulerService().awaitDrain();
                drainCompleted = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            boolean completed = drainCompleted;
            Platform.runLater(() -> {
                schedulerDrainApproved = completed;
                schedulerDrainInProgress = false;
                dialog.close();
                if (completed) {
                    afterDrain.run();
                }
            });
        }, "JobScheduler-Shutdown-Drain-Waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    private static String formatActiveJobNames(List<ActiveJobSummary> activeJobs) {
        return activeJobs.stream()
            .map(ActiveJobSummary::jobName)
            .filter(name -> name != null && !name.isBlank())
            .limit(8)
            .reduce((left, right) -> left + "\n- " + right)
            .map(text -> "- " + text)
            .orElse("");
    }
    
    private void selectNextTab() {
        if (tabPane.getTabs().isEmpty()) {
            return;
        }
        int current = tabPane.getSelectionModel().getSelectedIndex();
        int next = (current + 1) % tabPane.getTabs().size();
        tabPane.getSelectionModel().select(next);
    }
    
    private void selectPreviousTab() {
        if (tabPane.getTabs().isEmpty()) {
            return;
        }
        int current = tabPane.getSelectionModel().getSelectedIndex();
        int prev = current - 1;
        if (prev < 0) prev = tabPane.getTabs().size() - 1;
        tabPane.getSelectionModel().select(prev);
    }
    
    private void copyFromTerminal() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab instanceof TerminalTab terminalTab) {
            terminalTab.copySelection();
        }
    }

    private void cutFromCurrentContext() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab instanceof TerminalTab) {
            return;
        } else if (currentTab instanceof FileEditorTab editorTab) {
            editorTab.cut();
            return;
        }

        Scene scene = stage.getScene();
        if (scene == null) {
            return;
        }

        Node focusOwner = scene.getFocusOwner();
        if (focusOwner instanceof TextInputControl textInputControl) {
            textInputControl.cut();
            return;
        }
        if (focusOwner != null) {
            if (invokeCutMethodIfPresent(focusOwner)) {
                return;
            }
            focusOwner.fireEvent(new KeyEvent(
                KeyEvent.KEY_PRESSED,
                "",
                "",
                KeyCode.X,
                false,
                !isMacOs(),
                false,
                isMacOs()
            ));
        }
    }

    private void updateEditMenuItemsForSelection() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        boolean disableCut = currentTab instanceof TerminalTab;
        if (cutMenuItem != null) {
            cutMenuItem.setDisable(disableCut);
        }
        if (systemCutMenuItem != null) {
            systemCutMenuItem.setDisable(disableCut);
        }
    }

    private boolean invokeCutMethodIfPresent(Node focusOwner) {
        try {
            var cutMethod = focusOwner.getClass().getMethod("cut");
            if (cutMethod.getParameterCount() == 0) {
                cutMethod.invoke(focusOwner);
                return true;
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall back to firing the standard shortcut event below.
        }
        return false;
    }
    
    private void pasteToTerminal() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (!(currentTab instanceof TerminalTab terminalTab)) {
            return;
        }
        Scene mainScene = tabPane.getScene();
        if (mainScene == null) {
            return;
        }
        Window hostWindow = mainScene.getWindow();
        if (hostWindow == null) {
            return;
        }
        if (!hostWindow.isFocused()) {
            return;
        }
        Node focusOwner = mainScene.getFocusOwner();
        Node terminalRoot = terminalTab.getContent();
        if (terminalRoot != null && focusOwner != null
                && !isNodeUnderRoot(focusOwner, terminalRoot)) {
            return;
        }
        if (wasTriggeredByTerminalPasteShortcut()) {
            return;
        }
        terminalTab.paste();
    }

    private static boolean isNodeUnderRoot(Node node, Node root) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == root) {
                return true;
            }
        }
        return false;
    }

    private boolean wasTriggeredByTerminalPasteShortcut() {
        long shortcutAt = lastTerminalPasteShortcutAtNanos;
        lastTerminalPasteShortcutAtNanos = -1L;
        return shortcutAt > 0 && (System.nanoTime() - shortcutAt) < 1_000_000_000L;
    }
    
    private void findInCurrentTab() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab instanceof TerminalTab terminalTab) {
            terminalTab.showFind();
        } else if (currentTab instanceof FileEditorTab editorTab) {
            editorTab.showFind();
        }
    }
    
    /**
     * Toggles the timestamp gutter in the currently active terminal tab.
     * Updates the CheckMenuItem state to reflect the current visibility.
     */
    private void toggleTimestampsInCurrentTab(CheckMenuItem menuItem) {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab instanceof TerminalTab terminalTab) {
            boolean nowVisible = terminalTab.toggleTimestampGutters();
            syncTimestampMenuItems(nowVisible);
        } else if (menuItem != null) {
            syncTimestampMenuItems(menuItem.isSelected());
        }
    }

    private boolean isMenuBarVisiblePreference() {
        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        return globalSettings == null || globalSettings.isShowMenuBar();
    }

    private void applyMenuBarVisibility(boolean visible) {
        if (menuBar == null) {
            return;
        }
        menuBar.setVisible(visible);
        menuBar.setManaged(visible);
        syncMenuBarToggleMenuItems(visible);
    }

    private void toggleMenuBarVisibility(boolean visible) {
        applyMenuBarVisibility(visible);

        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        if (globalSettings != null) {
            globalSettings.setShowMenuBar(visible);
            try {
                app.getGlobalSettingsManager().save();
            } catch (Exception e) {
                logger.warn("Could not persist menu bar visibility", e);
            }
        }

        if (visible) {
            updateStatus(I18n.get("menu.view.menuBar.shown"));
        } else {
            updateStatus(I18n.get("menu.view.menuBar.hiddenHint", MENU_BAR_TOGGLE_SHORTCUT_LABEL));
        }
    }
    
    private void zoomTerminal(int delta) {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab instanceof TerminalTab terminalTab) {
            terminalTab.zoom(delta);
        }
    }
    
    private void resetTerminalZoom() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab instanceof TerminalTab terminalTab) {
            terminalTab.resetZoom();
        }
    }
    
    private static final int DASHBOARD_FIXED_WIDTH = 300;
    private static final int FILE_BROWSER_DEFAULT_WIDTH = 220;
    private static final int FILE_BROWSER_MIN_WIDTH = 160;
    private static final int FILE_BROWSER_MAX_WIDTH = 420;

    private void syncDashboardMenuItems(boolean visible) {
        if (showDashboardMenuItem != null && showDashboardMenuItem.isSelected() != visible) {
            showDashboardMenuItem.setSelected(visible);
        }
        if (systemShowDashboardMenuItem != null && systemShowDashboardMenuItem.isSelected() != visible) {
            systemShowDashboardMenuItem.setSelected(visible);
        }
    }

    private void toggleFileBrowser(LocalFileBrowserManager.Position position) {
        ensureFileBrowserManager();
        fileBrowserManager.toggle(position);
    }

    private void onFileBrowserPositionChanged(LocalFileBrowserManager.Position position) {
        // Lazy-create the file browser and divider when first shown
        if (position != LocalFileBrowserManager.Position.HIDDEN && localFileBrowser == null) {
            localFileBrowser = new LocalFileBrowser();
            localFileBrowser.setMinWidth(FILE_BROWSER_MIN_WIDTH);
            localFileBrowser.setPrefWidth(FILE_BROWSER_DEFAULT_WIDTH);
            localFileBrowser.setMaxWidth(FILE_BROWSER_MAX_WIDTH);

            fileBrowserDivider = new ResizableDivider(Orientation.VERTICAL);
            fileBrowserDivider.setResizeListener(delta -> {
                double currentWidth = localFileBrowser.getPrefWidth();
                double directionalDelta =
                    fileBrowserManager.getPosition() == LocalFileBrowserManager.Position.RIGHT ? -delta : delta;
                double newWidth = currentWidth + directionalDelta;
                newWidth = Math.max(FILE_BROWSER_MIN_WIDTH, Math.min(FILE_BROWSER_MAX_WIDTH, newWidth));
                localFileBrowser.setPrefWidth(newWidth);
                fileBrowserManager.setPreferredWidth(newWidth);
                return newWidth;
            });
        }

        // Remove from current position if visible
        if (localFileBrowser != null && mainContentBox.getChildren().contains(localFileBrowser)) {
            mainContentBox.getChildren().remove(localFileBrowser);
        }
        if (fileBrowserDivider != null && mainContentBox.getChildren().contains(fileBrowserDivider)) {
            mainContentBox.getChildren().remove(fileBrowserDivider);
        }

        if (position == LocalFileBrowserManager.Position.HIDDEN) {
            // Hidden
            syncFileBrowserMenuItems(LocalFileBrowserManager.Position.HIDDEN);
        } else if (position == LocalFileBrowserManager.Position.LEFT) {
            // Restore saved width
            restoreFileBrowserWidth();
            // Insert browser first, then divider so the sidebar is flush with the window edge.
            int insertIndex = 0;
            mainContentBox.getChildren().add(insertIndex, localFileBrowser);
            mainContentBox.getChildren().add(insertIndex + 1, fileBrowserDivider);
            syncFileBrowserMenuItems(LocalFileBrowserManager.Position.LEFT);
            applyMainWindowThemeFromGlobalSettings();
        } else if (position == LocalFileBrowserManager.Position.RIGHT) {
            // Restore saved width
            restoreFileBrowserWidth();
            // Insert divider first, then browser so the sidebar is flush with the window edge.
            mainContentBox.getChildren().add(fileBrowserDivider);
            mainContentBox.getChildren().add(localFileBrowser);
            syncFileBrowserMenuItems(LocalFileBrowserManager.Position.RIGHT);
            applyMainWindowThemeFromGlobalSettings();
        }
    }

    private void restoreFileBrowserWidth() {
        double clampedWidth = Math.max(
            FILE_BROWSER_MIN_WIDTH,
            Math.min(FILE_BROWSER_MAX_WIDTH, fileBrowserManager.getPreferredWidth()));
        localFileBrowser.setPrefWidth(clampedWidth);
        fileBrowserManager.setPreferredWidth(clampedWidth);
    }

    private void syncFileBrowserMenuItems(LocalFileBrowserManager.Position position) {
        if (showFileBrowserLeftMenuItem != null) {
            showFileBrowserLeftMenuItem.setSelected(position == LocalFileBrowserManager.Position.LEFT);
        }
        if (showFileBrowserRightMenuItem != null) {
            showFileBrowserRightMenuItem.setSelected(position == LocalFileBrowserManager.Position.RIGHT);
        }
        if (systemShowFileBrowserLeftMenuItem != null) {
            systemShowFileBrowserLeftMenuItem.setSelected(position == LocalFileBrowserManager.Position.LEFT);
        }
        if (systemShowFileBrowserRightMenuItem != null) {
            systemShowFileBrowserRightMenuItem.setSelected(position == LocalFileBrowserManager.Position.RIGHT);
        }
    }

    private void syncTimestampMenuItems(boolean visible) {
        if (showTimestampsMenuItem != null && showTimestampsMenuItem.isSelected() != visible) {
            showTimestampsMenuItem.setSelected(visible);
        }
        if (systemShowTimestampsMenuItem != null && systemShowTimestampsMenuItem.isSelected() != visible) {
            systemShowTimestampsMenuItem.setSelected(visible);
        }
    }

    private void syncMenuBarToggleMenuItems(boolean visible) {
        if (showMenuBarMenuItem != null && showMenuBarMenuItem.isSelected() != visible) {
            showMenuBarMenuItem.setSelected(visible);
        }
        if (systemShowMenuBarMenuItem != null && systemShowMenuBarMenuItem.isSelected() != visible) {
            systemShowMenuBarMenuItem.setSelected(visible);
        }
    }

    private void syncTerminalOnlyFullscreenMenuItems() {
        if (terminalOnlyFullscreenMenuItem != null
            && terminalOnlyFullscreenMenuItem.isSelected() != terminalOnlyFullscreenActive) {
            terminalOnlyFullscreenMenuItem.setSelected(terminalOnlyFullscreenActive);
        }
        if (systemTerminalOnlyFullscreenMenuItem != null
            && systemTerminalOnlyFullscreenMenuItem.isSelected() != terminalOnlyFullscreenActive) {
            systemTerminalOnlyFullscreenMenuItem.setSelected(terminalOnlyFullscreenActive);
        }
    }

    private void syncHideFullscreenScrollbarsMenuItems() {
        boolean hide = isHideTerminalScrollbarsInFullscreenPreference();
        if (hideFullscreenScrollbarsMenuItem != null
            && hideFullscreenScrollbarsMenuItem.isSelected() != hide) {
            hideFullscreenScrollbarsMenuItem.setSelected(hide);
        }
        if (systemHideFullscreenScrollbarsMenuItem != null
            && systemHideFullscreenScrollbarsMenuItem.isSelected() != hide) {
            systemHideFullscreenScrollbarsMenuItem.setSelected(hide);
        }
    }

    private boolean isHideTerminalScrollbarsInFullscreenPreference() {
        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        return globalSettings != null && globalSettings.isHideTerminalScrollbarsInFullscreen();
    }

    private void setHideTerminalScrollbarsInFullscreen(boolean hide) {
        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        if (globalSettings != null) {
            globalSettings.setHideTerminalScrollbarsInFullscreen(hide);
            try {
                app.getGlobalSettingsManager().save();
            } catch (Exception e) {
                logger.warn("Could not persist fullscreen scrollbar visibility preference", e);
            }
        }
        syncHideFullscreenScrollbarsMenuItems();
        applyTerminalScrollbarVisibilityForOpenTabs();
        updateStatus(I18n.get("status.globalSettingsSaved"));
    }

    private void toggleTerminalOnlyFullscreen() {
        setTerminalOnlyFullscreen(!terminalOnlyFullscreenActive);
    }

    private void setTerminalOnlyFullscreen(boolean active) {
        if (active == terminalOnlyFullscreenActive) {
            syncTerminalOnlyFullscreenMenuItems();
            return;
        }

        if (active) {
            terminalOnlyPreviousFullScreen = stage.isFullScreen();
            terminalOnlyPreviousMenuBarVisible = menuBar == null || menuBar.isVisible();
            terminalOnlyPreviousStatusBarVisible = statusBar == null || statusBar.isVisible();
            terminalOnlyPreviousDashboardVisible = dashboardVisible;
            terminalOnlyPreviousFileBrowserPosition = fileBrowserManager != null
                ? fileBrowserManager.getPosition()
                : LocalFileBrowserManager.Position.HIDDEN;

            terminalOnlyFullscreenActive = true;
            if (!stage.isFullScreen()) {
                stage.setFullScreen(true);
            }
            applyMenuBarVisibility(false);
            applyStatusBarVisibility(false);
            if (dashboardVisible) {
                toggleDashboard(false);
            }
            if (fileBrowserManager != null) {
                fileBrowserManager.hide();
            }
            applyTerminalTabsChromeVisibility(false);
            applyTerminalOnlyTabHeaderVisibility(false);
        } else {
            terminalOnlyFullscreenActive = false;
            applyTerminalOnlyTabHeaderVisibility(true);
            applyTerminalTabsChromeVisibility(true);
            applyMenuBarVisibility(terminalOnlyPreviousMenuBarVisible);
            applyStatusBarVisibility(terminalOnlyPreviousStatusBarVisible);
            if (terminalOnlyPreviousDashboardVisible) {
                toggleDashboard(true);
            }
            restoreTerminalOnlyFileBrowserPosition();
            stage.setFullScreen(terminalOnlyPreviousFullScreen);
        }

        syncTerminalOnlyFullscreenMenuItems();
        applyTerminalScrollbarVisibilityForOpenTabs();
        Platform.runLater(() -> {
            Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
            if (selectedTab instanceof TerminalTab terminalTab) {
                terminalTab.getTerminalView().focusTerminal();
            }
        });
    }

    private void applyTerminalScrollbarVisibilityForOpenTabs() {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                applyTerminalScrollbarVisibility(terminalTab);
            }
        }
    }

    private void applyTerminalScrollbarVisibility(TerminalTab terminalTab) {
        if (terminalTab == null) {
            return;
        }
        terminalTab.getTerminalView().setTerminalScrollbarsVisible(shouldShowTerminalScrollbars());
    }

    private boolean shouldShowTerminalScrollbars() {
        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        boolean showTerminalScrollbar = globalSettings == null || globalSettings.isShowTerminalScrollbar();
        boolean hideForFullscreen = globalSettings != null
            && globalSettings.isHideTerminalScrollbarsInFullscreen()
            && stage.isFullScreen();
        return showTerminalScrollbar && !hideForFullscreen;
    }

    private void applyStatusBarVisibility(boolean visible) {
        if (statusBar == null) {
            return;
        }
        statusBar.setVisible(visible);
        statusBar.setManaged(visible);
    }

    private void applyTerminalOnlyTabHeaderVisibility(boolean visible) {
        String styleClass = "terminal-only-fullscreen";
        if (visible) {
            root.getStyleClass().remove(styleClass);
        } else if (!root.getStyleClass().contains(styleClass)) {
            root.getStyleClass().add(styleClass);
        }
    }

    private void applyTerminalTabsChromeVisibility(boolean visible) {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                terminalTab.setTerminalChromeVisible(visible);
            }
        }
    }

    private void restoreTerminalOnlyFileBrowserPosition() {
        if (terminalOnlyPreviousFileBrowserPosition == null
            || terminalOnlyPreviousFileBrowserPosition == LocalFileBrowserManager.Position.HIDDEN) {
            return;
        }
        ensureFileBrowserManager();
        fileBrowserManager.show(terminalOnlyPreviousFileBrowserPosition);
    }

    private void ensureFileBrowserManager() {
        if (fileBrowserManager == null) {
            fileBrowserManager = LocalFileBrowserManager.getInstance();
            fileBrowserPositionListener = pos -> onFileBrowserPositionChanged(pos);
            fileBrowserManager.addPositionListener(fileBrowserPositionListener);
        }
    }
    
    private void toggleDashboard(boolean show) {
        if (show && !dashboardVisible) {
            if (dashboardView == null) {
                dashboardView = new DashboardView(tabPane, this::handleDashboardAction);
                // Fixed width - dashboard does not resize when main window resizes
                dashboardView.setMinWidth(DASHBOARD_FIXED_WIDTH);
                dashboardView.setMaxWidth(DASHBOARD_FIXED_WIDTH);
                dashboardView.setPrefWidth(DASHBOARD_FIXED_WIDTH);
            }
            mainContentBox.getChildren().add(0, dashboardView);
            dashboardVisible = true;
            applyMainWindowThemeFromGlobalSettings();
        } else if (!show && dashboardVisible) {
            mainContentBox.getChildren().remove(dashboardView);
            dashboardVisible = false;
        }
        syncDashboardMenuItems(dashboardVisible);
    }
    
    private void updateDashboard() {
        if (dashboardView != null && dashboardVisible) {
            dashboardView.refresh();
        }
    }
    
    private void handleDashboardAction(TerminalTab terminalTab, DashboardView.DashboardAction action) {
        switch (action) {
            case FOCUS:
                // Focus the tab
                tabPane.getSelectionModel().select(terminalTab);
                break;
                
            case CLOSE:
                // Close the tab
                tabPane.getTabs().remove(terminalTab);
                updateDashboard();
                updateStatus(I18n.get("status.tabClosed", terminalTab.getConnection().getDisplayName()));
                break;
                
            case RECONNECT:
                Platform.runLater(() -> {
                    try {
                        terminalTab.triggerReconnect();
                    } catch (Exception e) {
                        logger.error("Reconnect failed", e);
                        updateStatus(I18n.get("status.reconnectionFailed", e.getMessage()));
                    }
                });
                break;
                
            case SFTP_MANAGER:
                // Open SFTP Manager for this connection
                if (terminalTab.isConnected()) {
                    openSFTPManagerForConnection(terminalTab.getConnection(), terminalTab.getTemporarySSHKey());
                } else {
                    showError(I18n.get("error.notConnected"), I18n.get("error.notConnectedMessage"));
                }
                break;
                
            case DUPLICATE:
                // Duplicate the tab
                duplicateTab(terminalTab);
                break;
        }
    }
    
    private void openProject() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("menu.file.openProject"));
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("KorTTY Projekte", "*" + ProjectManager.getProjectExtension())
        );
        
        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            try {
                Project project = projectManager.loadProject(file.toPath());
                loadProject(project);
                updateStatus(I18n.get("status.projectLoaded", project.getName()));
            } catch (Exception e) {
                logger.error("Failed to load project", e);
                showError(I18n.get("error.title"), I18n.get("error.projectLoadFailed", e.getMessage()));
            }
        }
    }
    
    private void saveProject() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("menu.file.saveProject"));
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("KorTTY Projekte", "*" + ProjectManager.getProjectExtension())
        );
        
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            String path = file.getPath();
            if (!path.endsWith(ProjectManager.getProjectExtension())) {
                path += ProjectManager.getProjectExtension();
                file = new File(path);
            }
            
            try {
                Project project = createProjectFromCurrentState();
                
                // Show project settings dialog
                ProjectSettingsDialog dialog = new ProjectSettingsDialog(stage, project);
                Optional<Project> result = dialog.showAndWait();
                
                if (result.isPresent()) {
                    projectManager.saveProject(result.get(), file.toPath());
                    updateStatus(I18n.get("status.projectSaved", file.getName()));
                }
            } catch (Exception e) {
                logger.error("Failed to save project", e);
                showError(I18n.get("error.title"), I18n.get("error.projectSaveFailed", e.getMessage()));
            }
        }
    }
    
    private Project createProjectFromCurrentState() {
        Project project = new Project("New Project");
        
        WindowState windowState = new WindowState(UUID.randomUUID().toString());
        windowState.setGeometry(new WindowGeometry(
                stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()
        ));
        windowState.getGeometry().setMaximized(stage.isMaximized());
        
        // Save dashboard state (visibility)
        windowState.setDashboardVisible(dashboardVisible);
        
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                ServerConnection connection = terminalTab.getConnection();
                SessionState sessionState = new SessionState(
                        java.util.UUID.randomUUID().toString(),
                        connection.getId()
                );
                sessionState.setTabType(SessionState.TabType.TERMINAL);
                sessionState.setSettings(connection.getSettings());
                sessionState.setTerminalHistory(terminalTab.getTerminalView().getTerminalHistory());
                sessionState.setTerminalTimestamps(terminalTab.getTerminalView().getPrimaryTimestampEntries());
                sessionState.setTerminalEffectPluginId(terminalTab.getTerminalView().getTerminalEffectPluginId());
                double terminalEffectAnimationSpeed = terminalTab.getTerminalView().getTerminalEffectAnimationSpeed();
                if (Double.compare(terminalEffectAnimationSpeed, TerminalEffectAnimationSpeed.DEFAULT) != 0) {
                    sessionState.setTerminalEffectAnimationSpeed(terminalEffectAnimationSpeed);
                }
                sessionState.setGroup(terminalTab.getGroup()); // Save tab group (not connection group)
                // Save current font size (zoom level) - may differ from settings when user zoomed
                int currentFontSize = terminalTab.getTerminalView().getCurrentFontSize();
                if (currentFontSize != connection.getSettings().getFontSize()) {
                    sessionState.setFontSizeOverride(currentFontSize);
                }
                // Save split pane structure (if terminal has splits)
                de.kortty.model.SplitPaneState splitState = terminalTab.getTerminalView().getSplitState();
                if (splitState != null) {
                    sessionState.setSplitPaneState(splitState);
                    logger.info("Saving split structure for tab: {}", connection.getDisplayName());
                }
                windowState.addTab(sessionState);
            } else if (tab instanceof SFTPManagerTab sftpTab) {
                SessionState sessionState = sftpTab.createSessionState();
                windowState.addTab(sessionState);
                logger.info("Saving SFTP Manager tab: {}", sftpTab.getText());
            } else if (tab instanceof FileEditorTab editorTab) {
                SessionState sessionState = editorTab.createSessionState();
                // Find connection ID if this is a remote file
                if (editorTab.isRemote() && editorTab.getSftpSession() != null) {
                    // Try to find matching SFTP tab to get connection
                    for (Tab t : tabPane.getTabs()) {
                        if (t instanceof SFTPManagerTab sftpTab && 
                            sftpTab.getConnection() != null) {
                            sessionState.setConnectionId(sftpTab.getConnection().getId());
                            break;
                        }
                    }
                }
                windowState.addTab(sessionState);
                logger.info("Saving File Editor tab: {}", editorTab.getText());
            } else if (tab instanceof ImageViewerTab viewerTab) {
                SessionState sessionState = viewerTab.createSessionState();
                // Find connection ID if this is a remote image
                if (viewerTab.isRemote() && viewerTab.getSftpSession() != null) {
                    // Try to find matching SFTP tab to get connection
                    for (Tab t : tabPane.getTabs()) {
                        if (t instanceof SFTPManagerTab sftpTab && 
                            sftpTab.getConnection() != null) {
                            sessionState.setConnectionId(sftpTab.getConnection().getId());
                            break;
                        }
                    }
                }
                windowState.addTab(sessionState);
                logger.info("Saving Image Viewer tab: {}", viewerTab.getText());
            }
        }
        
        windowState.setActiveTabIndex(tabPane.getSelectionModel().getSelectedIndex());
        project.addWindow(windowState);
        
        return project;
    }
    
    private void loadProject(Project project) {
        // Close existing tabs
        closeAllTabs();
        
        for (WindowState windowState : project.getWindows()) {
            // Apply window geometry
            WindowGeometry geo = windowState.getGeometry();
            if (geo != null) {
                stage.setX(geo.getX());
                stage.setY(geo.getY());
                stage.setWidth(geo.getWidth());
                stage.setHeight(geo.getHeight());
                stage.setMaximized(geo.isMaximized());
            }
            
            // Restore tabs
            for (SessionState sessionState : windowState.getTabs()) {
                SessionState.TabType tabType = sessionState.getTabType();
                if (tabType == null) {
                    tabType = SessionState.TabType.TERMINAL; // Backward compatibility
                }
                
                switch (tabType) {
                    case TERMINAL -> {
                        ServerConnection connection = app.getConfigManager().getConnectionById(sessionState.getConnectionId());
                        if (connection != null) {
                            if (project.isAutoReconnect()) {
                                // Get password and reconnect with history restore
                                // SSH key auth does not require a password
                                boolean isKeyAuth = connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY;
                                String password = isKeyAuth ? null : getConnectionPassword(connection);
                                if (password != null || isKeyAuth) {
                                    String history = sessionState.getTerminalHistory();
                                    TerminalTab restoredTab = openConnectionAndReturnTab(
                                            connection,
                                            password,
                                            history,
                                            null,
                                            sessionState.getTerminalEffectPluginId(),
                                            sessionState.getTerminalEffectAnimationSpeed());
                                    restoredTab.getTerminalView().restorePrimaryTimestampEntries(
                                            sessionState.getTerminalTimestamps());
                                    // Restore tab group (not connection group)
                                    if (sessionState.getGroup() != null && !sessionState.getGroup().trim().isEmpty()) {
                                        restoredTab.setGroup(sessionState.getGroup());
                                        organizeTabsByGroup();
                                    }
                                    // Restore font size (zoom level) if saved
                                    Integer fontSizeOverride = sessionState.getFontSizeOverride();
                                    if (fontSizeOverride != null && fontSizeOverride > 0) {
                                        restoredTab.getTerminalView().setFontSize(fontSizeOverride);
                                    }
                                    // Restore split pane structure if saved (delayed until connection is ready)
                                    de.kortty.model.SplitPaneState splitState = sessionState.getSplitPaneState();
                                    if (splitState != null) {
                                        logger.info("Scheduling split structure restoration for tab: {}", connection.getDisplayName());
                                        // Wait for initial connection to be fully established before restoring splits
                                        Platform.runLater(() -> {
                                            try {
                                                Thread.sleep(1000); // Give time for initial connection
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            restoredTab.getTerminalView().restoreSplitState(splitState);
                                        });
                                    }
                                    logger.info("Restoring tab for {} with {} chars of history", 
                                            connection.getDisplayName(), 
                                            history != null ? history.length() : 0);
                                }
                            } else {
                                // TODO: Create read-only tab with history display only (no connection)
                                logger.info("Auto-reconnect disabled, skipping connection for {}", 
                                        connection.getDisplayName());
                            }
                        }
                    }
                    case SFTP_MANAGER -> {
                        ServerConnection connection = app.getConfigManager().getConnectionById(sessionState.getConnectionId());
                        if (connection != null && project.isAutoReconnect()) {
                            boolean isKeyAuth = connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY;
                            String password = isKeyAuth ? null : getConnectionPassword(connection);
                            if (password != null || isKeyAuth) {
                                Integer timeout = sessionState.getSftpAutoCloseTimeout();
                                int timeoutMinutes = (timeout != null && timeout > 0) ? timeout : 0;
                                
                                SFTPManagerTab sftpTab = new SFTPManagerTab(app, connection, password, null, timeoutMinutes, this);
                                tabPane.getTabs().add(sftpTab);
                                
                                // Restore paths if saved
                                if (sessionState.getSftpLocalPath() != null) {
                                    // Will be restored after connection
                                }
                                if (sessionState.getSftpRemotePath() != null) {
                                    // Will be restored after connection
                                }
                                
                                logger.info("Restored SFTP Manager tab for {}", connection.getDisplayName());
                            }
                        }
                    }
                    case FILE_EDITOR -> {
                        Boolean isRemote = sessionState.getEditorIsRemote();
                        String filePath = sessionState.getEditorFilePath();
                        
                        if (filePath != null) {
                            if (Boolean.TRUE.equals(isRemote)) {
                                // Remote file - need connection
                                ServerConnection connection = app.getConfigManager().getConnectionById(sessionState.getConnectionId());
                                if (connection != null && project.isAutoReconnect()) {
                                    boolean isKeyAuth = connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY;
                                    String password = isKeyAuth ? null : getConnectionPassword(connection);
                                    if (password != null || isKeyAuth) {
                                        // Open SFTP session and download file
                                        new Thread(() -> {
                                            try {
                                                de.kortty.core.SFTPSession sftpSession = new de.kortty.core.SFTPSession(connection, password);
                                                sftpSession.connect();
                                                
                                                byte[] content = sftpSession.downloadFileBytes(filePath);
                                                String filename = java.nio.file.Paths.get(filePath).getFileName().toString();
                                                
                                                Platform.runLater(() -> {
                                                    FileEditorTab editorTab = new FileEditorTab(filename, filePath, sftpSession, content);
                                                    tabPane.getTabs().add(editorTab);
                                                    logger.info("Restored remote file editor: {}", filePath);
                                                });
                                            } catch (Exception e) {
                                                logger.error("Failed to restore remote file editor", e);
                                            }
                                        }).start();
                                    }
                                }
                            } else {
                                // Local file
                                try {
                                    java.nio.file.Path path = java.nio.file.Paths.get(filePath);
                                    if (java.nio.file.Files.exists(path)) {
                                        FileEditorTab editorTab = new FileEditorTab(path);
                                        tabPane.getTabs().add(editorTab);
                                        logger.info("Restored local file editor: {}", filePath);
                                    }
                                } catch (Exception e) {
                                    logger.error("Failed to restore local file editor", e);
                                }
                            }
                        }
                    }
                    case IMAGE_VIEWER -> {
                        Boolean isRemote = sessionState.getImageIsRemote();
                        String filePath = sessionState.getImageFilePath();
                        Double zoomLevel = sessionState.getImageZoomLevel();
                        
                        if (filePath != null) {
                            if (Boolean.TRUE.equals(isRemote)) {
                                // Remote image - need connection
                                ServerConnection connection = app.getConfigManager().getConnectionById(sessionState.getConnectionId());
                                if (connection != null && project.isAutoReconnect()) {
                                    boolean isKeyAuth = connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY;
                                    String password = isKeyAuth ? null : getConnectionPassword(connection);
                                    if (password != null || isKeyAuth) {
                                        // Open SFTP session and download image
                                        new Thread(() -> {
                                            try {
                                                de.kortty.core.SFTPSession sftpSession = new de.kortty.core.SFTPSession(connection, password);
                                                sftpSession.connect();
                                                
                                                byte[] imageData = sftpSession.downloadFileBytes(filePath);
                                                String filename = java.nio.file.Paths.get(filePath).getFileName().toString();
                                                
                                                Platform.runLater(() -> {
                                                    ImageViewerTab viewerTab = new ImageViewerTab(filename, filePath, sftpSession, imageData);
                                                    tabPane.getTabs().add(viewerTab);
                                                    logger.info("Restored remote image viewer: {}", filePath);
                                                });
                                            } catch (Exception e) {
                                                logger.error("Failed to restore remote image viewer", e);
                                            }
                                        }).start();
                                    }
                                }
                            } else {
                                // Local image
                                try {
                                    java.nio.file.Path path = java.nio.file.Paths.get(filePath);
                                    if (java.nio.file.Files.exists(path)) {
                                        ImageViewerTab viewerTab = new ImageViewerTab(path);
                                        tabPane.getTabs().add(viewerTab);
                                        logger.info("Restored local image viewer: {}", filePath);
                                    }
                                } catch (Exception e) {
                                    logger.error("Failed to restore local image viewer", e);
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Restore dashboard state from project (visibility)
        WindowState firstWindow = project.getWindows().isEmpty() ? null : project.getWindows().get(0);
        if (firstWindow != null && Boolean.TRUE.equals(firstWindow.getDashboardVisible())) {
            toggleDashboard(true);
        }
    }
    
    private void importConnections() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("menu.connections.import"));
        
        // Add filters for each importer
        for (ConnectionImporter importer : importers) {
            List<String> extensions = new ArrayList<>();
            for (String ext : importer.getSupportedExtensions()) {
                extensions.add("*." + ext);
            }
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(importer.getFileDescription(), extensions)
            );
        }
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Alle Dateien", "*.*")
        );
        
        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            for (ConnectionImporter importer : importers) {
                if (importer.canImport(file.toPath())) {
                    try {
                        List<ServerConnection> imported = importer.importConnections(file.toPath());
                        for (ServerConnection conn : imported) {
                            app.getConfigManager().addConnection(conn);
                        }
                        app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
                        
                        showInfo(I18n.get("info.importSuccessful", imported.size(), importer.getName()), "");
                        return;
                    } catch (Exception e) {
                        logger.error("Import failed", e);
                        showError(I18n.get("error.importFailed"), e.getMessage());
                        return;
                    }
                }
            }
            showError(I18n.get("error.importFailed"), I18n.get("error.importUnsupportedFormat"));
        }
    }
    
    private void exportConnections() {
        List<ServerConnection> allConnections = app.getConfigManager().getConnections();
        
        if (allConnections.isEmpty()) {
            showInfo(I18n.get("error.noConnectionsToExport"), "");
            return;
        }
        
        // Show export dialog
        ExportDialog dialog = new ExportDialog(stage, allConnections, app.getGpgKeyManager());
        Optional<ExportDialog.ExportResult> result = dialog.showAndWait();
        
        if (result.isEmpty() || result.get() == null) {
            return;
        }
        
        ExportDialog.ExportResult exportResult = result.get();
        
        // File chooser
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("menu.connections.export") + " " + I18n.get("dialog.as") + " " + exportResult.exporter.getName());
        
        if (exportResult.encryptionType == ExportDialog.EncryptionType.PASSWORD) {
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(I18n.get("backup.encryption.password") + " (*.zip)", "*.zip")
            );
            fileChooser.setInitialFileName("connections.zip");
        } else if (exportResult.encryptionType == ExportDialog.EncryptionType.GPG) {
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(I18n.get("backup.encryption.gpg") + " (*.gpg)", "*.gpg")
            );
            fileChooser.setInitialFileName("connections." + exportResult.exporter.getFileExtension() + ".gpg");
        } else {
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(exportResult.exporter.getFileDescription(), 
                            "*." + exportResult.exporter.getFileExtension())
            );
            fileChooser.setInitialFileName("connections." + exportResult.exporter.getFileExtension());
        }
        
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            try {
                if (exportResult.encrypted()) {
                    exportAsEncryptedZip(exportResult, file.toPath());
                } else {
                    exportResult.exporter.exportConnections(exportResult.connections, file.toPath());
                }
                
                showInfo(I18n.get("info.exportSuccessful", 
                        exportResult.connections.size(), file.getName(),
                        "\n\nFormat: " + exportResult.exporter.getName() +
                        (exportResult.encrypted() ? "\n" + I18n.get("info.encrypted") : "")), "");
            } catch (Exception e) {
                logger.error("Export failed", e);
                showError(I18n.get("error.exportFailed"), e.getMessage());
            }
        }
    }
    
    private void exportAsEncryptedZip(ExportDialog.ExportResult exportResult, Path zipFile) throws Exception {
        // Create temporary file for export
        Path tempFile = Files.createTempFile("kortty-export", "." + exportResult.exporter.getFileExtension());
        
        try {
            // Export to temp file
            exportResult.exporter.exportConnections(exportResult.connections, tempFile);
            
            // Create encrypted ZIP
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
                // Set password if provided (Note: Standard Java ZIP doesn't support encryption)
                // We'll use a workaround with encrypted content
                
                ZipEntry entry = new ZipEntry("connections." + exportResult.exporter.getFileExtension());
                zos.putNextEntry(entry);
                
                // Read and encrypt content
                byte[] content = Files.readAllBytes(tempFile);
                byte[] encrypted = encryptContent(content, exportResult.password);
                zos.write(encrypted);
                
                zos.closeEntry();
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
    
    
    private void exportAsGPGEncrypted(ExportDialog.ExportResult exportResult, Path gpgFile) throws Exception {
        // Create temporary file for export
        Path tempFile = Files.createTempFile("kortty-export", "." + exportResult.exporter.getFileExtension());
        
        try {
            // Export to temp file
            exportResult.exporter.exportConnections(exportResult.connections, tempFile);
            
            // Encrypt with GPG
            String keyId = exportResult.gpgKey.getKeyId();
            
            ProcessBuilder pb = new ProcessBuilder(
                "gpg",
                "--encrypt",
                "--recipient", keyId,
                "--trust-model", "always",  // Trust the key automatically
                "--output", gpgFile.toString(),
                tempFile.toString()
            );
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Read output
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            int exitCode = process.waitFor();
            
            if (exitCode != 0) {
                throw new Exception(I18n.get("error.gpgEncryptionFailed", exitCode, output.toString(), keyId));
            }
            
            logger.info("File encrypted with GPG using key {}: {}", keyId, gpgFile);
            
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
    
    private byte[] encryptContent(byte[] data, String password) throws Exception {
        // Use AES encryption
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        
        // Derive key from password
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] key = digest.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(key, "AES");
        
        // Generate IV
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.spec.GCMParameterSpec gcmSpec = new javax.crypto.spec.GCMParameterSpec(128, iv);
        
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, gcmSpec);
        byte[] encrypted = cipher.doFinal(data);
        
        // Prepend IV to encrypted data
        byte[] result = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
        
        return result;
    }
    
    private void showAbout() {
        Dialog<Void> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(stage);
        dialog.setTitle(I18n.get("dialog.about") + " " + KorTTYApplication.getAppName());
        dialog.setHeaderText(KorTTYApplication.getAppName() + " v" + KorTTYApplication.getAppVersion());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        Label aboutText = new Label(I18n.get("dialog.aboutText"));
        aboutText.setWrapText(true);
        Label developedBy = new Label(I18n.get("dialog.aboutDeveloped"));
        Label jmxLabel = new Label(I18n.get("dialog.aboutJMX") + "\n" + "de.kortty:type=SSHClient");
        Hyperlink projectLink = new Hyperlink(PROJECT_URL);
        projectLink.setOnAction(event -> openProjectPage());

        Button manualUpdateCheckButton = new Button(I18n.get("updates.checkNow"));
        ProgressIndicator updateProgress = new ProgressIndicator();
        updateProgress.setPrefSize(18, 18);
        updateProgress.setVisible(false);
        updateProgress.setManaged(false);
        Label updateStatusLabel = new Label();
        updateStatusLabel.setWrapText(true);
        updateStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        manualUpdateCheckButton.setOnAction(event ->
            runManualUpdateCheck(manualUpdateCheckButton, updateProgress, updateStatusLabel));

        HBox updateCheckBox = new HBox(10, manualUpdateCheckButton, updateProgress);
        updateCheckBox.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(10);
        content.setPadding(new Insets(4, 0, 0, 0));
        content.setPrefWidth(640);

        var iconUrl = getClass().getResource("/icon/kortty_logo.png");
        if (iconUrl == null) {
            iconUrl = getClass().getResource("/icon/kortty_icon.png");
        }
        if (iconUrl != null) {
            ImageView iconView = new ImageView(new Image(iconUrl.toExternalForm()));
            iconView.setFitWidth(560);
            iconView.setPreserveRatio(true);
            iconView.setSmooth(true);
            HBox logoBox = new HBox(iconView);
            logoBox.setAlignment(Pos.CENTER);
            content.getChildren().addAll(logoBox, new Separator());
        }
        content.getChildren().addAll(
            aboutText,
            developedBy,
            new Label(I18n.get("dialog.aboutProject")),
            projectLink,
            jmxLabel,
            new Separator(),
            updateCheckBox,
            updateStatusLabel);
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private void openProjectPage() {
        try {
            app.getHostServices().showDocument(PROJECT_URL);
        } catch (Exception e) {
            logger.warn("Could not open project page", e);
            showError(I18n.get("error.title"), I18n.get("updates.project.openFailed"));
        }
    }

    private void runManualUpdateCheck(
        Button manualUpdateCheckButton,
        ProgressIndicator updateProgress,
        Label updateStatusLabel
    ) {
        UpdateCheckService service = ensureUpdateCheckService();
        if (service == null) {
            updateStatusLabel.setText(I18n.get("updates.checkFailed"));
            return;
        }
        manualUpdateCheckButton.setDisable(true);
        updateProgress.setVisible(true);
        updateProgress.setManaged(true);
        updateStatusLabel.setText(I18n.get("updates.checking"));

        Task<UpdateCheckResult> task = new Task<>() {
            @Override
            protected UpdateCheckResult call() {
                return service.checkManually();
            }
        };
        task.setOnSucceeded(event -> {
            manualUpdateCheckButton.setDisable(false);
            updateProgress.setVisible(false);
            updateProgress.setManaged(false);
            handleManualUpdateCheckResult(task.getValue(), updateStatusLabel);
        });
        task.setOnFailed(event -> {
            manualUpdateCheckButton.setDisable(false);
            updateProgress.setVisible(false);
            updateProgress.setManaged(false);
            Throwable error = task.getException();
            String message = error != null && error.getMessage() != null
                ? error.getMessage()
                : I18n.get("updates.checkFailed");
            updateStatusLabel.setText(message);
        });
        Thread thread = new Thread(task, "kortty-manual-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private void handleManualUpdateCheckResult(UpdateCheckResult result, Label updateStatusLabel) {
        if (result == null) {
            updateStatusLabel.setText(I18n.get("updates.checkFailed"));
            return;
        }
        switch (result.status()) {
            case UPDATE_AVAILABLE -> {
                updateStatusLabel.setText(I18n.get("updates.available.short", result.update().versionLabel()));
                showUpdateAvailableDialog(result.update(), true);
            }
            case NO_UPDATE -> updateStatusLabel.setText(I18n.get("updates.manual.current"));
            case NO_COMPATIBLE_ASSET -> updateStatusLabel.setText(I18n.get("updates.noCompatibleAsset"));
            case FAILED -> updateStatusLabel.setText(I18n.get("updates.checkFailed.detail", result.message()));
        }
    }

    private void showUpdateAvailableDialog(AvailableUpdate update, boolean manual) {
        if (update == null) {
            return;
        }
        Dialog<ButtonType> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(stage);
        dialog.setTitle(I18n.get("updates.dialog.title"));
        dialog.setHeaderText(I18n.get("updates.dialog.header", update.versionLabel()));

        ButtonType downloadButton = new ButtonType(I18n.get("updates.download"), ButtonBar.ButtonData.OK_DONE);
        ButtonType remindButton = new ButtonType(I18n.get("updates.remindTomorrow"), ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType skipButton = new ButtonType(I18n.get("updates.skipVersion"), ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(downloadButton, remindButton, skipButton);

        Label content = new Label(I18n.get(
            "updates.dialog.content",
            KorTTYApplication.getAppVersion(),
            update.versionLabel(),
            update.asset().name(),
            DownloadDirectoryResolver.resolveDefaultDownloadsDirectory()));
        content.setWrapText(true);
        content.setPrefWidth(460);
        dialog.getDialogPane().setContent(content);

        Optional<ButtonType> result = dialog.showAndWait();
        UpdateCheckService service = ensureUpdateCheckService();
        if (result.isPresent() && result.get() == downloadButton) {
            downloadUpdate(update);
        } else if (service != null && result.isPresent() && result.get() == skipButton) {
            service.ignoreVersion(update.versionLabel());
        } else if (service != null && (!manual || (result.isPresent() && result.get() == remindButton))) {
            service.snoozeUntilTomorrow(update.versionLabel());
        }
    }

    private void downloadUpdate(AvailableUpdate update) {
        Dialog<Void> progressDialog = new Dialog<>();
        DialogThemeHelper.applyTheme(progressDialog);
        progressDialog.initOwner(stage);
        progressDialog.setTitle(I18n.get("updates.download.title"));
        progressDialog.setHeaderText(I18n.get("updates.download.header", update.versionLabel()));
        ButtonType cancelButtonType = ButtonType.CANCEL;
        progressDialog.getDialogPane().getButtonTypes().add(cancelButtonType);

        ProgressIndicator progressIndicator = new ProgressIndicator();
        Label status = new Label(I18n.get("updates.download.running", update.asset().name()));
        status.setWrapText(true);
        VBox content = new VBox(12, progressIndicator, status);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPrefWidth(420);
        progressDialog.getDialogPane().setContent(content);

        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                return new UpdateAssetDownloader().download(
                    update.asset(),
                    DownloadDirectoryResolver.resolveDefaultDownloadsDirectory());
            }
        };
        Button cancelButton = (Button) progressDialog.getDialogPane().lookupButton(cancelButtonType);
        cancelButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            task.cancel(true);
            progressDialog.close();
        });
        task.setOnSucceeded(event -> {
            progressDialog.close();
            UpdateCheckService service = ensureUpdateCheckService();
            if (service != null) {
                service.recordDownloadedVersion(update.versionLabel());
            }
            showInfo(
                I18n.get("updates.download.complete.title"),
                I18n.get("updates.download.complete", task.getValue()));
        });
        task.setOnFailed(event -> {
            progressDialog.close();
            Throwable error = task.getException();
            String message = error != null && error.getMessage() != null
                ? error.getMessage()
                : I18n.get("updates.download.failed");
            if (error instanceof DownloadException && error.getCause() != null && error.getCause().getMessage() != null) {
                message = error.getCause().getMessage();
            }
            showError(I18n.get("updates.download.failed.title"), message);
        });
        Thread thread = new Thread(task, "kortty-update-download");
        thread.setDaemon(true);
        thread.start();
        progressDialog.showAndWait();
    }

    private UpdateCheckService ensureUpdateCheckService() {
        UpdateCheckService service = app.getUpdateCheckService();
        if (service == null) {
            app.restartUpdateCheckService();
            service = app.getUpdateCheckService();
        }
        return service;
    }
    
    private void updateStatus(String message) {
        statusLabel.setText(message);
    }

    private void handleAiSelectionAction(TerminalTab terminalTab, AiAction action, AiProfile profile, String selectedText) {
        if (!isAiFeaturesEnabled()) {
            return;
        }
        if (selectedText == null || selectedText.trim().isEmpty()) {
            return;
        }
        AiProfile effectiveProfile = profile != null ? profile : getDefaultAiProfile();
        int maxSelectionChars = getMaxAiSelectionChars(effectiveProfile);
        if (selectedText.length() > maxSelectionChars) {
            showError(I18n.get("ai.error.title"), I18n.get("ai.error.selectionTooLarge", maxSelectionChars));
            return;
        }
        String effectiveModel = effectiveProfile != null ? effectiveProfile.getModel() : null;
        if (effectiveProfile != null
            && requiresModelForAiProfile(effectiveProfile)
            && (effectiveModel == null || effectiveModel.isBlank())) {
            showError(I18n.get("ai.error.title"), I18n.get("settings.ai.error.noModel"));
            return;
        }

        AiService aiService = createAiService(effectiveProfile);
        if (aiService == null) {
            showAiConfigurationDialog();
            return;
        }
        if (aiService instanceof FailingAiService failingService) {
            showError(I18n.get("ai.error.title"), failingService.message());
            return;
        }
        String connectionName = terminalTab.getConnection() != null ? terminalTab.getConnection().getDisplayName() : null;
        String languageCode = LanguageManager.getInstance().getCurrentLanguageCode();
        Optional<AiRequestDraft> confirmedDraft = maybeConfirmAiRequest(action, effectiveProfile, selectedText, connectionName, languageCode);
        if (confirmedDraft.isEmpty()) {
            return;
        }
        AiRequestDraft draft = confirmedDraft.get();
        String requestText = draft.selectedText();
        if (requestText.trim().isEmpty()) {
            return;
        }
        if (requestText.length() > maxSelectionChars) {
            showError(I18n.get("ai.error.title"), I18n.get("ai.error.selectionTooLarge", maxSelectionChars));
            return;
        }

        AiRequest request = new AiRequest(action, requestText, connectionName, languageCode, draft.userPrompt());
        String tabTitle = I18n.get("ai.tab.title", getAiActionLabel(action));
        AiResultTab resultTab = new AiResultTab(
            this,
            tabTitle,
            effectiveProfile,
            requestText,
            connectionName,
            languageCode,
            null,
            false);
        if (action == AiAction.ASK && draft.userPrompt() != null && !draft.userPrompt().isBlank()) {
            resultTab.appendUserMessage(draft.userPrompt());
        }
        insertTemporaryTab(resultTab);
        updateStatus(I18n.get("ai.status.running", getAiActionLabel(action)));

        Task<AiExecutionResult> task = new Task<>() {
            @Override
            protected AiExecutionResult call() throws Exception {
                return aiService.execute(request);
            }
        };
        Thread thread = new Thread(task, "ai-selection-" + action.name().toLowerCase(Locale.ROOT));
        thread.setDaemon(true);
        resultTab.attachRunningTask(task, thread, I18n.get("ai.result.loading"));
        task.setOnSucceeded(e -> {
            AiExecutionResult result = task.getValue();
            resultTab.showResult(result != null ? result.content() : "");
            recordAiUsage(effectiveProfile, request, result);
            updateStatus(I18n.get("ai.status.finished", getAiActionLabel(action)));
        });
        task.setOnCancelled(e -> {
            resultTab.showCancelled();
            updateStatus(I18n.get("ai.status.cancelled", getAiActionLabel(action)));
        });
        task.setOnFailed(e -> {
            if (task.isCancelled()) {
                resultTab.showCancelled();
                updateStatus(I18n.get("ai.status.cancelled", getAiActionLabel(action)));
                return;
            }
            Throwable error = task.getException();
            String message = error != null && error.getMessage() != null
                ? error.getMessage()
                : I18n.get("ai.result.error");
            resultTab.showError(I18n.get("ai.result.errorMessage", message));
            updateStatus(I18n.get("ai.status.failed", getAiActionLabel(action)));
        });

        thread.start();
    }

    private boolean requiresModelForAiProfile(AiProfile profile) {
        if (profile == null) {
            return false;
        }
        if (profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI) {
            return AiCliArgumentTemplate.requiresModel(profile.getCliArgumentsTemplate());
        }
        return profile.getModelSelectionMode() == AiModelSelectionMode.MANUAL;
    }

    private int getMaxAiSelectionChars(AiProfile profile) {
        if (profile != null && profile.getMaxSelectionChars() != null && profile.getMaxSelectionChars() > 0) {
            return profile.getMaxSelectionChars();
        }
        return DEFAULT_MAX_AI_SELECTION_CHARS;
    }

    private AiService createAiService(AiProfile profile) {
        if (profile == null) {
            return null;
        }
        String apiKey = getAiApiKeyPlain(profile);
        if (profile.getConnectionMode() != AiConnectionMode.LOCAL_CLI
            && (profile.getApiUrl() == null || profile.getApiUrl().isBlank())) {
            return null;
        }
        try {
            return AiServiceFactory.create(
                profile,
                apiKey,
                buildInternetAccessConfiguration(profile),
                AiSkillPromptSupport.fromSettings(app.getGlobalSettingsManager().getSettings()));
        } catch (IllegalStateException e) {
            return new FailingAiService(e.getMessage());
        }
    }

    private String getAiApiKeyPlain(AiProfile profile) {
        if (profile == null || profile.getEncryptedApiKey() == null || profile.getEncryptedApiKey().isBlank()) {
            return null;
        }
        try {
            char[] masterPassword = app.getMasterPasswordManager() != null ? app.getMasterPasswordManager().getMasterPassword() : null;
            if (masterPassword == null) {
                return null;
            }
            de.kortty.security.EncryptionService encryptionService = new de.kortty.security.EncryptionService();
            String decrypted = encryptionService.decryptPassword(profile.getEncryptedApiKey(), masterPassword);
            return decrypted != null && !decrypted.isBlank() ? decrypted : null;
        } catch (Exception e) {
            logger.warn("Could not decrypt AI API key", e);
            return null;
        }
    }

    private AiInternetAccessConfiguration buildInternetAccessConfiguration(AiProfile profile) {
        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
        AiInternetAccessMode mode = profile != null ? profile.getInternetAccessMode() : null;
        if (settings == null || mode == null || !mode.isEnabled()) {
            return AiInternetAccessConfiguration.disabled();
        }
        String tavilyApiKey = null;
        String brightDataApiToken = null;
        String braveSearchApiKey = null;
        String searxngUrl = null;
        String tavilyMcpServerLabel = null;
        String brightDataMcpServerLabel = null;
        String braveSearchMcpPluginId = null;
        String searxngMcpPluginId = null;
        String lmStudioToolpackMcpPluginId = null;
        switch (mode) {
            case KORTTY_TAVILY_TOOL -> tavilyApiKey =
                decryptGlobalSecret(settings.getEncryptedAiTavilyApiKey(), "Tavily API key");
            case LM_STUDIO_TAVILY_MCP -> {
                tavilyApiKey = decryptGlobalSecret(settings.getEncryptedAiTavilyApiKey(), "Tavily API key");
                tavilyMcpServerLabel = settings.getAiTavilyMcpServerLabel();
            }
            case BRIGHT_DATA_WEB_MCP -> {
                brightDataApiToken =
                    decryptGlobalSecret(settings.getEncryptedAiBrightDataApiToken(), "Bright Data API token");
                brightDataMcpServerLabel = settings.getAiBrightDataMcpServerLabel();
            }
            case BRAVE_SEARCH_MCP -> {
                braveSearchApiKey =
                    decryptGlobalSecret(settings.getEncryptedAiBraveSearchApiKey(), "Brave Search API key");
                braveSearchMcpPluginId = settings.getAiBraveSearchMcpPluginId();
            }
            case SEARXNG_MCP -> {
                searxngUrl = settings.getAiSearxngUrl();
                searxngMcpPluginId = settings.getAiSearxngMcpPluginId();
            }
            case LM_STUDIO_TOOLPACK -> lmStudioToolpackMcpPluginId = settings.getAiLmStudioToolpackMcpPluginId();
            case DISABLED -> {
            }
        }
        return new AiInternetAccessConfiguration(
            mode,
            tavilyApiKey,
            brightDataApiToken,
            braveSearchApiKey,
            searxngUrl,
            tavilyMcpServerLabel,
            brightDataMcpServerLabel,
            braveSearchMcpPluginId,
            searxngMcpPluginId,
            lmStudioToolpackMcpPluginId);
    }

    private String decryptGlobalSecret(String encryptedValue, String label) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            return null;
        }
        try {
            char[] masterPassword = app.getMasterPasswordManager() != null ? app.getMasterPasswordManager().getMasterPassword() : null;
            if (masterPassword == null) {
                throw new IllegalStateException(label + " cannot be decrypted because the password vault is locked.");
            }
            de.kortty.security.EncryptionService encryptionService = new de.kortty.security.EncryptionService();
            String decrypted = encryptionService.decryptPassword(encryptedValue, masterPassword);
            return decrypted != null && !decrypted.isBlank() ? decrypted : null;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("Could not decrypt {}", label, e);
            throw new IllegalStateException(label + " could not be decrypted.");
        }
    }

    private void showAiConfigurationDialog() {
        ButtonType openSettings = new ButtonType(I18n.get("ai.settings.open"), ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.WARNING, I18n.get("ai.error.notConfigured"), openSettings, ButtonType.CANCEL);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("ai.error.title"));
        alert.setHeaderText(null);
        if (alert.showAndWait().orElse(ButtonType.CANCEL) == openSettings) {
            showSettings();
        }
    }

    private Optional<AiRequestDraft> maybeConfirmAiRequest(
        AiAction action,
        AiProfile profile,
        String selectedText,
        String connectionName,
        String languageCode) {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        if (action != AiAction.ASK && settings != null && !settings.isAiConfirmBeforeSend()) {
            return Optional.of(new AiRequestDraft(selectedText, null));
        }
        return confirmAiRequest(action, profile, selectedText, connectionName, languageCode);
    }

    private Optional<AiRequestDraft> confirmAiRequest(
        AiAction action,
        AiProfile profile,
        String selectedText,
        String connectionName,
        String languageCode) {
        String model = aiModelDisplayText(profile);
        String apiUrl = profile != null && profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI
            ? de.kortty.core.AiCliProviderRegistry.find(profile.getCliProviderId())
                .map(de.kortty.core.AiCliProviderDescriptor::displayName)
                .orElse(I18n.get("settings.ai.connectionMode.local_cli"))
            : profile != null && profile.getApiUrl() != null ? profile.getApiUrl() : "";
        Dialog<AiRequestDraft> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(I18n.get("ai.confirm.title"));
        dialog.setHeaderText(I18n.get("ai.confirm.header", getAiActionLabel(action)));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Label summaryLabel = new Label(buildAiConfirmSummary(action, profile, model, apiUrl, selectedText, connectionName, languageCode, promptAreaInitialValue(action)));
        summaryLabel.setWrapText(true);
        AiQuotaBar quotaBar = new AiQuotaBar();
        quotaBar.setPrefWidth(420);

        TextField searchField = new TextField();
        searchField.setPromptText(I18n.get("editor.search.findPrompt"));
        TextField replaceField = new TextField();
        replaceField.setPromptText(I18n.get("editor.search.replacePrompt"));

        ComboBox<String> promptHistoryCombo = new ComboBox<>();
        promptHistoryCombo.setPrefWidth(420);
        Button clearPromptHistoryButton = new Button(I18n.get("ai.confirm.prompt.history.clear"));
        TextArea promptArea = new TextArea();
        promptArea.setPrefColumnCount(80);
        promptArea.setPrefRowCount(5);
        promptArea.setWrapText(true);
        promptArea.setPromptText(I18n.get("ai.confirm.prompt.input"));
        VBox promptBox = new VBox(8);
        promptBox.setVisible(action == AiAction.ASK);
        promptBox.setManaged(action == AiAction.ASK);
        if (action == AiAction.ASK) {
            GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
            List<String> promptHistory = settings != null ? new ArrayList<>(settings.getAiPromptHistory()) : List.of();
            promptHistoryCombo.getItems().setAll(promptHistory);
            promptHistoryCombo.setPromptText(I18n.get("ai.confirm.prompt.history"));
            promptHistoryCombo.setOnAction(e -> {
                String selectedPrompt = promptHistoryCombo.getValue();
                if (selectedPrompt != null) {
                    promptArea.setText(selectedPrompt);
                }
            });
            clearPromptHistoryButton.setOnAction(e -> {
                GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
                if (globalSettings != null) {
                    globalSettings.clearAiPromptHistory();
                    try {
                        app.getGlobalSettingsManager().save();
                    } catch (Exception ex) {
                        logger.warn("Could not clear AI prompt history", ex);
                    }
                }
                promptHistoryCombo.getItems().clear();
                promptHistoryCombo.setValue(null);
            });
            promptBox.getChildren().addAll(
                new Label(I18n.get("ai.confirm.prompt.label")),
                new HBox(8, promptHistoryCombo, clearPromptHistoryButton),
                promptArea
            );
        }

        TextArea preview = new TextArea(selectedText);
        preview.setEditable(true);
        preview.setWrapText(true);
        preview.setPrefColumnCount(80);
        preview.setPrefRowCount(18);

        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        updateAiConfirmQuotaBar(quotaBar, profile, buildAiConfirmTokenEstimate(action, profile, selectedText, connectionName, languageCode, promptArea.getText()));

        if (action == AiAction.ASK) {
            promptArea.textProperty().addListener((obs, oldValue, newValue) -> {
                long requestTokens = buildAiConfirmTokenEstimate(action, profile, preview.getText(), connectionName, languageCode, newValue);
                summaryLabel.setText(buildAiConfirmSummary(action, profile, model, apiUrl, preview.getText(), connectionName, languageCode, newValue));
                updateAiConfirmQuotaBar(quotaBar, profile, requestTokens);
            });
        }

        preview.textProperty().addListener((obs, oldValue, newValue) -> {
            long requestTokens = buildAiConfirmTokenEstimate(action, profile, newValue, connectionName, languageCode, promptArea.getText());
            summaryLabel.setText(buildAiConfirmSummary(action, profile, model, apiUrl, newValue, connectionName, languageCode, promptArea.getText()));
            updateAiConfirmQuotaBar(quotaBar, profile, requestTokens);
        });

        Button findNextButton = new Button(I18n.get("editor.search.next"));
        findNextButton.setOnAction(e -> findNextInTextArea(preview, searchField.getText(), statusLabel));

        Button replaceButton = new Button(I18n.get("editor.search.replaceOne"));
        replaceButton.setOnAction(e -> replaceSelectionInTextArea(preview, searchField.getText(), replaceField.getText(), statusLabel));

        Button replaceAllButton = new Button(I18n.get("editor.search.replaceAll"));
        replaceAllButton.setOnAction(e -> replaceAllInTextArea(preview, searchField.getText(), replaceField.getText(), statusLabel));

        searchField.setOnAction(e -> findNextInTextArea(preview, searchField.getText(), statusLabel));
        replaceField.setOnAction(e -> replaceSelectionInTextArea(preview, searchField.getText(), replaceField.getText(), statusLabel));

        GridPane replaceGrid = new GridPane();
        replaceGrid.setHgap(8);
        replaceGrid.setVgap(8);
        replaceGrid.add(new Label(I18n.get("editor.search.find")), 0, 0);
        replaceGrid.add(searchField, 1, 0);
        replaceGrid.add(findNextButton, 2, 0);
        replaceGrid.add(new Label(I18n.get("editor.search.replace")), 0, 1);
        replaceGrid.add(replaceField, 1, 1);
        replaceGrid.add(new HBox(8, replaceButton, replaceAllButton), 2, 1);

        VBox content = new VBox(10, summaryLabel, quotaBar, replaceGrid, preview, promptBox, statusLabel);
        content.setPadding(new Insets(5, 0, 0, 0));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(900);
        dialog.getDialogPane().setPrefHeight(650);
        Platform.runLater(() -> {
            if (action == AiAction.ASK) {
                promptArea.requestFocus();
                promptArea.positionCaret(promptArea.getLength());
            } else {
                searchField.requestFocus();
            }
        });

        final Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        if (action == AiAction.ASK) {
            okButton.setDisable(promptArea.getText() == null || promptArea.getText().trim().isEmpty());
            promptArea.textProperty().addListener((obs, oldValue, newValue) ->
                okButton.setDisable(newValue == null || newValue.trim().isEmpty()));
        }
        dialog.setResultConverter(buttonType -> {
            if (buttonType != ButtonType.OK) {
                return null;
            }
            String promptText = promptArea.getText() != null && !promptArea.getText().isBlank() ? promptArea.getText().trim() : null;
            if (action == AiAction.ASK) {
                GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
                if (settings != null && promptText != null) {
                    settings.addAiPromptHistoryEntry(promptText);
                    try {
                        app.getGlobalSettingsManager().save();
                    } catch (Exception e) {
                        logger.warn("Could not persist AI prompt history", e);
                    }
                }
            }
            return new AiRequestDraft(preview.getText(), promptText);
        });
        return dialog.showAndWait();
    }

    private String promptAreaInitialValue(AiAction action) {
        return action == AiAction.ASK ? "" : null;
    }

    private String aiModelDisplayText(AiProfile profile) {
        if (profile == null) {
            return "";
        }
        String model = profile.getModel() != null ? profile.getModel() : "";
        if (profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI) {
            String provider = de.kortty.core.AiCliProviderRegistry.find(profile.getCliProviderId())
                .map(de.kortty.core.AiCliProviderDescriptor::displayName)
                .orElse(I18n.get("settings.ai.connectionMode.local_cli"));
            return model.isBlank() ? provider : provider + " / " + model;
        }
        if (profile.getModelSelectionMode() == AiModelSelectionMode.DEFAULT) {
            return I18n.get("ai.model.default");
        }
        if (profile.getModelSelectionMode() == AiModelSelectionMode.AUTO) {
            return model.isBlank()
                ? I18n.get("ai.model.auto")
                : I18n.get("ai.model.autoWithName", model);
        }
        return model;
    }

    private String buildAiConfirmSummary(
        AiAction action,
        AiProfile profile,
        String model,
        String apiUrl,
        String text,
        String connectionName,
        String languageCode,
        String userPrompt) {
        String safeText = text != null ? text : "";
        long requestTokens = buildAiConfirmTokenEstimate(action, profile, safeText, connectionName, languageCode, userPrompt);
        long remainingTokens = AiTokenUsageManager.remainingAfter(profile, requestTokens);
        AiTokenWarningLevel warningLevel = AiTokenUsageManager.determineProjectedWarningLevel(profile, requestTokens);
        return I18n.get(
            "ai.confirm.summary",
            getAiProfileDisplayName(profile),
            model,
            apiUrl,
            safeText.length(),
            requestTokens,
            formatRemainingTokens(remainingTokens),
            I18n.get("settings.ai.token.warning." + warningLevel.name().toLowerCase(Locale.ROOT)));
    }

    private long buildAiConfirmTokenEstimate(
        AiAction action,
        AiProfile profile,
        String text,
        String connectionName,
        String languageCode,
        String userPrompt) {
        AiRequest request = new AiRequest(action, text != null ? text : "", connectionName, languageCode, userPrompt);
        return countAiRequestTokens(profile, request);
    }

    private long countAiRequestTokens(AiProfile profile, AiRequest request) {
        AiTokenizerType tokenizerType = profile != null && profile.getTokenizerType() != null
            ? profile.getTokenizerType()
            : AiTokenizerType.ESTIMATE;
        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
        return AiTokenCounter.countRequestTokens(request, tokenizerType, AiSkillPromptSupport.fromSettings(settings));
    }

    private String formatRemainingTokens(long remainingTokens) {
        if (remainingTokens == Long.MAX_VALUE) {
            return I18n.get("settings.ai.token.unlimited");
        }
        return AiTokenUsageManager.formatCompact(remainingTokens);
    }

    private void updateAiConfirmQuotaBar(AiQuotaBar quotaBar, AiProfile profile, long requestTokens) {
        if (quotaBar == null) {
            return;
        }
        AiTokenUsageSnapshot snapshot = AiTokenUsageManager.refreshUsage(profile);
        long projectedUsed = snapshot.usedTotalTokens() + Math.max(0L, requestTokens);
        double usedFraction = snapshot.unlimited() || snapshot.maxTokens() <= 0
            ? 0.0
            : Math.min(1.0, projectedUsed / (double) snapshot.maxTokens());
        quotaBar.update(
            usedFraction,
            profile != null && profile.getTokenWarningYellowPercent() != null ? profile.getTokenWarningYellowPercent() : 75,
            profile != null && profile.getTokenWarningRedPercent() != null ? profile.getTokenWarningRedPercent() : 90,
            AiTokenUsageManager.determineProjectedWarningLevel(profile, requestTokens),
            snapshot.unlimited());
    }

    private void recordAiUsage(AiProfile profile, AiRequest request, AiExecutionResult result) {
        if (profile == null || profile.getId() == null) {
            return;
        }
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        if (settings == null) {
            return;
        }
        AiProfile mutableProfile = settings.getAiProfiles().stream()
            .filter(candidate -> candidate != null && profile.getId().equals(candidate.getId()))
            .findFirst()
            .orElse(null);
        if (mutableProfile == null) {
            return;
        }
        AiTokenUsage usage = result != null ? result.usage() : null;
        if (usage == null) {
            long promptTokens = countAiRequestTokens(mutableProfile, request);
            long completionTokens = AiTokenCounter.countTextTokens(
                result != null ? result.content() : "",
                mutableProfile.getTokenizerType() != null ? mutableProfile.getTokenizerType() : AiTokenizerType.ESTIMATE);
            usage = new AiTokenUsage(promptTokens, completionTokens, promptTokens + completionTokens);
        }
        AiTokenUsageSnapshot snapshot = AiTokenUsageManager.recordUsage(mutableProfile, usage);
        logger.debug("Updated AI token usage for profile {} to {}", getAiProfileDisplayName(mutableProfile), snapshot.usedTotalTokens());
        try {
            app.getGlobalSettingsManager().save();
        } catch (Exception e) {
            logger.warn("Could not persist AI token usage", e);
        }
    }

    private void recordAiUsage(AiProfile profile, AiTokenUsage usage) {
        if (profile == null || profile.getId() == null || usage == null) {
            return;
        }
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        if (settings == null) {
            return;
        }
        AiProfile mutableProfile = settings.getAiProfiles().stream()
            .filter(candidate -> candidate != null && profile.getId().equals(candidate.getId()))
            .findFirst()
            .orElse(null);
        if (mutableProfile == null) {
            return;
        }
        AiTokenUsageSnapshot snapshot = AiTokenUsageManager.recordUsage(mutableProfile, usage);
        logger.debug("Updated AI token usage for profile {} to {}", getAiProfileDisplayName(mutableProfile), snapshot.usedTotalTokens());
        try {
            app.getGlobalSettingsManager().save();
        } catch (Exception e) {
            logger.warn("Could not persist AI token usage", e);
        }
    }

    private void findNextInTextArea(TextArea textArea, String search, Label statusLabel) {
        if (search == null || search.isEmpty()) {
            statusLabel.setText(I18n.get("editor.search.noMatches"));
            return;
        }
        String text = textArea.getText();
        int searchStart = Math.max(textArea.getSelection().getEnd(), textArea.getCaretPosition());
        int index = text.indexOf(search, searchStart);
        if (index < 0 && searchStart > 0) {
            index = text.indexOf(search);
        }
        if (index < 0) {
            statusLabel.setText(I18n.get("editor.search.noMatches"));
            return;
        }
        textArea.requestFocus();
        textArea.selectRange(index, index + search.length());
        textArea.positionCaret(index + search.length());
        statusLabel.setText(I18n.get("ai.confirm.foundAt", index + 1));
    }

    private void replaceSelectionInTextArea(TextArea textArea, String search, String replacement, Label statusLabel) {
        if (search == null || search.isEmpty()) {
            statusLabel.setText(I18n.get("editor.search.noMatches"));
            return;
        }
        String selectedText = textArea.getSelectedText();
        if (!search.equals(selectedText)) {
            findNextInTextArea(textArea, search, statusLabel);
            selectedText = textArea.getSelectedText();
            if (!search.equals(selectedText)) {
                return;
            }
        }
        textArea.replaceSelection(replacement != null ? replacement : "");
        statusLabel.setText(I18n.get("editor.status.replaced", 1));
    }

    private void replaceAllInTextArea(TextArea textArea, String search, String replacement, Label statusLabel) {
        if (search == null || search.isEmpty()) {
            statusLabel.setText(I18n.get("editor.search.noMatches"));
            return;
        }
        String text = textArea.getText();
        int replacements = countOccurrences(text, search);
        if (replacements == 0) {
            statusLabel.setText(I18n.get("editor.search.noMatches"));
            return;
        }
        textArea.setText(text.replace(search, replacement != null ? replacement : ""));
        statusLabel.setText(I18n.get("editor.status.replaced", replacements));
    }

    private int countOccurrences(String text, String search) {
        if (text == null || text.isEmpty() || search == null || search.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(search, index)) >= 0) {
            count++;
            index += search.length();
        }
        return count;
    }

    private void insertTemporaryTab(Tab tab) {
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
    }

    private void installAiSelectionHandler(TerminalTab terminalTab) {
        terminalTab.getTerminalView().setAiSelectionHandler((action, profile, selectedText) ->
            handleAiSelectionAction(terminalTab, action, profile, selectedText));
        terminalTab.getTerminalView().setTerminalTextFileLoadHandler((runContext, selectedText) ->
            loadTerminalSelectionAsTextFile(terminalTab, runContext, selectedText));
        terminalTab.getTerminalView().setAiAgentHandler(runContext ->
            requestAiAgentForTab(terminalTab, false, null, null, false, false, runContext));
        terminalTab.getTerminalView().setAiAgentAskHandler(runContext ->
            requestAiAgentForTab(terminalTab, true, null, null, false, false, runContext));
        terminalTab.getTerminalView().setAiPlanningHandler(runContext ->
            requestAiPlanningForTab(terminalTab, null, null, runContext));
        terminalTab.getTerminalView().setTerminalAgentShortcutHandler((rawCommand, runContext) ->
            handleTerminalAgentShortcut(terminalTab, rawCommand, runContext));
    }

    private String getAiActionLabel(AiAction action) {
        return switch (action) {
            case SUMMARIZE -> I18n.get("terminal.contextMenu.ai.summarize");
            case SOLVE_PROBLEM -> I18n.get("terminal.contextMenu.ai.solve");
            case ASK -> I18n.get("terminal.contextMenu.ai.ask");
            case GENERATE_CHAT_TITLE -> I18n.get("ai.action.generateTitle");
            case GENERATE_SNIPPET_METADATA -> I18n.get("ai.result.saveSnippet");
            case CORRECT_SNIPPET_DESCRIPTION -> I18n.get("snippets.description.correct");
            case CORRECT_SNIPPET_SELECTION_TEXT -> I18n.get("snippets.ai.menu.correct");
            case TRANSLATE_SNIPPET_SELECTION_TEXT -> I18n.get("snippets.ai.menu.translate");
            case DESCRIBE_SNIPPET_SELECTION, DESCRIBE_SNIPPET_FULL -> I18n.get("snippets.ai.menu.describe");
            case GENERATE_SNIPPET_ALTERNATIVES -> I18n.get("snippets.ai.alternatives.context");
            case COMPLETE_SNIPPET_CODE -> I18n.get("snippets.ai.code.complete");
            case REVIEW_SNIPPET_CODE -> I18n.get("snippets.ai.code.review");
            case IMPROVE_SNIPPET_CODE -> I18n.get("snippets.ai.code.improve.custom");
            case ASSIST_SNIPPET_CODE -> I18n.get("snippets.ai.assistant.context");
            case SECURITY_REVIEW_SNIPPET_CODE, APPLY_SNIPPET_SECURITY_FIXES -> I18n.get("snippets.ai.security.title");
            case GENERATE_SNIPPET_ONE_LINER -> I18n.get("snippets.oneliner.compact");
            case GENERATE_SNIPPET_PLANTUML -> I18n.get("snippets.ai.diagram.menu");
        };
    }

    private void loadTerminalSelectionAsTextFile(
        TerminalTab terminalTab,
        TerminalView.TerminalAgentRunContext runContext,
        String selectedText
    ) {
        String selectedFileName;
        try {
            selectedFileName = RemoteTextFileSelectionSupport.normalizeSelectedFileName(selectedText);
        } catch (IllegalArgumentException e) {
            showError(I18n.get("error.title"), I18n.get("terminal.loadTextFile.invalidSelection"));
            return;
        }

        TerminalView.TerminalAgentRunContext resolvedContext = runContext != null
            ? runContext
            : terminalTab.getTerminalView().captureTerminalAgentRunContext();
        if (resolvedContext == null
            || resolvedContext.connector() == null
            || !resolvedContext.connector().isConnected()
            || resolvedContext.connector().getSession() == null) {
            showError(I18n.get("error.title"), I18n.get("terminal.loadTextFile.notConnected"));
            return;
        }

        SshTtyConnector connector = resolvedContext.connector();
        String workingDirectory = resolvedContext.workingDirectory() != null && !resolvedContext.workingDirectory().isBlank()
            ? resolvedContext.workingDirectory()
            : connector.getCurrentRemoteDirectory();
        updateStatus(I18n.get("terminal.loadTextFile.loading", selectedFileName));

        Task<TerminalRemoteTextFile> task = new Task<>() {
            @Override
            protected TerminalRemoteTextFile call() throws Exception {
                return readTerminalRemoteTextFile(connector, workingDirectory, selectedFileName);
            }
        };
        task.setOnSucceeded(event ->
            openTerminalRemoteTextFileInSnippetEditor(terminalTab, connector, task.getValue()));
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            logger.error("Failed to load selected terminal text as remote text file '{}'", selectedFileName, failure);
            showTerminalTextFileLoadFailure(selectedFileName, failure);
        });
        Thread thread = new Thread(task, "terminal-text-file-loader");
        thread.setDaemon(true);
        thread.start();
    }

    private TerminalRemoteTextFile readTerminalRemoteTextFile(
        SshTtyConnector connector,
        String workingDirectory,
        String selectedFileName
    ) throws Exception {
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(connector.getSession())) {
            String sftpStartDirectory = resolveSftpStartDirectory(sftp);
            String remotePath = RemoteTextFileSelectionSupport.resolveRemoteFilePath(
                workingDirectory,
                selectedFileName,
                sftpStartDirectory);
            SftpClient.Attributes attributes = statTerminalRemotePath(sftp, remotePath);
            if (!attributes.isRegularFile()) {
                throw new TerminalTextFileLoadException(TerminalTextFileLoadFailure.NOT_REGULAR_FILE, remotePath);
            }
            byte[] bytes = readTerminalRemoteFileBytes(sftp, remotePath);
            String content;
            try {
                content = RemoteTextFileSelectionSupport.decodeUtf8TextFile(bytes);
            } catch (RemoteTextFileSelectionSupport.BinaryOrNonTextFileException e) {
                throw new TerminalTextFileLoadException(TerminalTextFileLoadFailure.BINARY_OR_NON_TEXT, remotePath, e);
            }
            return new TerminalRemoteTextFile(selectedFileName, remotePath, content);
        }
    }

    private SftpClient.Attributes statTerminalRemotePath(SftpClient sftp, String remotePath) throws Exception {
        try {
            return sftp.stat(remotePath);
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) {
                throw new TerminalTextFileLoadException(TerminalTextFileLoadFailure.NOT_FOUND, remotePath, e);
            }
            throw e;
        }
    }

    private byte[] readTerminalRemoteFileBytes(SftpClient sftp, String remotePath) throws IOException {
        try (InputStream input = sftp.read(remotePath);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        }
    }

    private String resolveSftpStartDirectory(SftpClient sftp) {
        try {
            String directory = sftp.canonicalPath(".");
            if (directory != null && !directory.isBlank()) {
                return directory.trim();
            }
        } catch (IOException e) {
            logger.debug("Could not resolve SFTP start directory for terminal text-file load: {}", e.getMessage());
        }
        return ".";
    }

    private void openTerminalRemoteTextFileInSnippetEditor(
        TerminalTab terminalTab,
        SshTtyConnector connector,
        TerminalRemoteTextFile remoteFile
    ) {
        SnippetManager snippetManager = app.getSnippetManager();
        if (snippetManager == null) {
            showError(I18n.get("error.title"), I18n.get("terminal.loadTextFile.snippetManagerMissing"));
            return;
        }
        Snippet snippet = createTerminalFileSnippetDraft(remoteFile.fileName(), remoteFile.content());
        SnippetEditDialog.ExternalFileActionConfig config = new SnippetEditDialog.ExternalFileActionConfig(
            remoteFile.remotePath(),
            I18n.get("sftp.snippetEditor.overwriteRemote"),
            I18n.get("sftp.snippetEditor.saveAs"),
            I18n.get("sftp.snippetEditor.saveSnippet"),
            I18n.get("sftp.snippetEditor.savedFile"),
            I18n.get("sftp.snippetEditor.savedFile"),
            I18n.get("sftp.snippetEditor.savedSnippet"),
            draft -> overwriteTerminalRemoteTextFile(connector, remoteFile.remotePath(), draft),
            draft -> saveTerminalRemoteTextFileAs(connector, remoteFile.remotePath(), remoteFile.fileName(), draft),
            this::saveTerminalDraftAsSnippet
        );
        List<String> categoryNames = snippetManager.getAllCategories().stream()
            .map(SnippetCategory::getName)
            .toList();
        SnippetEditDialog.AiAssist aiAssist = SnippetAiAssistFactory.create(this, getConnectionDisplayName(terminalTab));
        SnippetEditDialog dialog = new SnippetEditDialog(snippet, categoryNames, aiAssist, config);
        dialog.initOwner(stage);
        dialog.showNonBlocking(null);
        updateStatus(I18n.get("terminal.loadTextFile.loaded", remoteFile.remotePath()));
    }

    private Snippet createTerminalFileSnippetDraft(String fileName, String content) {
        Snippet snippet = new Snippet();
        snippet.setName(fileName);
        snippet.setContent(content);
        snippet.setLanguage(SnippetLanguageSupport.detectFileLanguage(fileName, content));
        snippet.setCategory("");
        snippet.setDescription("");
        snippet.setTagsFromString("");
        return snippet;
    }

    private boolean overwriteTerminalRemoteTextFile(SshTtyConnector connector, String remotePath, Snippet draft) throws Exception {
        uploadTerminalRemoteTextFile(connector, remotePath, draft.getContent());
        return true;
    }

    private boolean saveTerminalRemoteTextFileAs(
        SshTtyConnector connector,
        String originalRemotePath,
        String originalFileName,
        Snippet draft
    ) throws Exception {
        Optional<String> response = callOnFxThread(() -> {
            TextInputDialog dialog = new TextInputDialog(originalFileName);
            DialogThemeHelper.applyTheme(dialog);
            dialog.setTitle(I18n.get("sftp.snippetEditor.remoteFileName.title"));
            dialog.setHeaderText(I18n.get("sftp.snippetEditor.remoteFileName.header"));
            dialog.setContentText(I18n.get("sftp.snippetEditor.remoteFileName.content"));
            dialog.initOwner(stage);
            return dialog.showAndWait();
        });
        if (response.isEmpty()) {
            return false;
        }

        String targetPath;
        try {
            targetPath = SftpFileTransferService.resolveSiblingRemoteFilePath(originalRemotePath, response.get());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(I18n.get("sftp.snippetEditor.invalidFileName", response.get()), e);
        }
        if (terminalRemoteFileExists(connector, targetPath) && !confirmTerminalRemoteOverwrite(targetPath)) {
            return false;
        }
        uploadTerminalRemoteTextFile(connector, targetPath, draft.getContent());
        return true;
    }

    private void uploadTerminalRemoteTextFile(SshTtyConnector connector, String remotePath, String content) throws IOException {
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(connector.getSession());
             OutputStream output = sftp.write(remotePath, EnumSet.of(
                 SftpClient.OpenMode.Write,
                 SftpClient.OpenMode.Create,
                 SftpClient.OpenMode.Truncate))) {
            output.write((content != null ? content : "").getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean terminalRemoteFileExists(SshTtyConnector connector, String remotePath) {
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(connector.getSession())) {
            sftp.stat(remotePath);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean confirmTerminalRemoteOverwrite(String targetLabel) throws Exception {
        return callOnFxThread(() -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            DialogThemeHelper.applyTheme(confirm);
            confirm.setTitle(I18n.get("sftp.snippetEditor.confirmOverwrite.title"));
            confirm.setHeaderText(I18n.get("sftp.snippetEditor.confirmOverwrite.header"));
            confirm.setContentText(I18n.get("sftp.snippetEditor.confirmOverwrite.content", targetLabel));
            confirm.initOwner(stage);
            return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
        });
    }

    private boolean saveTerminalDraftAsSnippet(Snippet draft) throws Exception {
        SnippetManager snippetManager = app.getSnippetManager();
        Snippet snippet = copyTerminalSnippetForManager(draft);
        ensureTerminalSnippetCategoryExists(snippetManager, snippet.getCategory());
        snippetManager.addSnippet(snippet);
        snippetManager.save();
        return true;
    }

    private Snippet copyTerminalSnippetForManager(Snippet draft) {
        Snippet snippet = new Snippet();
        snippet.setName(draft.getName());
        snippet.setContent(draft.getContent());
        snippet.setLanguage(draft.getLanguage());
        snippet.setCategory(draft.getCategory());
        snippet.setDescription(draft.getDescription());
        snippet.setTags(new ArrayList<>(draft.getTags()));
        List<SnippetDiagram> diagrams = new ArrayList<>();
        for (SnippetDiagram diagram : draft.getDiagrams()) {
            if (diagram != null) {
                diagrams.add(new SnippetDiagram(diagram));
            }
        }
        snippet.setDiagrams(diagrams);
        return snippet;
    }

    private void ensureTerminalSnippetCategoryExists(SnippetManager snippetManager, String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return;
        }
        String normalized = categoryName.trim();
        if (snippetManager.findCategoryByName(normalized).isEmpty()) {
            snippetManager.addCategory(new SnippetCategory(normalized));
        }
    }

    private <T> T callOnFxThread(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return action.call();
        }
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for UI action", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        }
    }

    private void showTerminalTextFileLoadFailure(String selectedFileName, Throwable failure) {
        if (failure instanceof TerminalTextFileLoadException loadFailure) {
            String remotePath = loadFailure.remotePath();
            String message = switch (loadFailure.reason()) {
                case NOT_FOUND -> I18n.get("terminal.loadTextFile.notFound", remotePath);
                case NOT_REGULAR_FILE -> I18n.get("terminal.loadTextFile.notRegularFile", remotePath);
                case BINARY_OR_NON_TEXT -> I18n.get("terminal.loadTextFile.binary", remotePath);
            };
            showError(I18n.get("error.title"), message);
            updateStatus(message);
            return;
        }
        String detail = failure != null && failure.getMessage() != null
            ? failure.getMessage()
            : I18n.get("terminal.loadTextFile.unknownError");
        String message = I18n.get("terminal.loadTextFile.failed", selectedFileName, detail);
        showError(I18n.get("error.title"), message);
        updateStatus(message);
    }

    private record TerminalRemoteTextFile(String fileName, String remotePath, String content) {
    }

    private enum TerminalTextFileLoadFailure {
        NOT_FOUND,
        NOT_REGULAR_FILE,
        BINARY_OR_NON_TEXT
    }

    private static final class TerminalTextFileLoadException extends Exception {
        private final TerminalTextFileLoadFailure reason;
        private final String remotePath;

        private TerminalTextFileLoadException(TerminalTextFileLoadFailure reason, String remotePath) {
            this(reason, remotePath, null);
        }

        private TerminalTextFileLoadException(TerminalTextFileLoadFailure reason, String remotePath, Throwable cause) {
            super(remotePath, cause);
            this.reason = reason;
            this.remotePath = remotePath;
        }

        private TerminalTextFileLoadFailure reason() {
            return reason;
        }

        private String remotePath() {
            return remotePath;
        }
    }

    private TerminalTab getActiveTerminalTab() {
        Tab activeTab = getActiveTab();
        return activeTab instanceof TerminalTab terminalTab ? terminalTab : null;
    }

    private String getTerminalAgentCommandName() {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return TerminalAgentCommandSupport.normalizeCommandName(
            settings != null ? settings.getTerminalAgentCommandName() : null);
    }

    private boolean isTerminalAgentCommandNameCaseInsensitive() {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return settings != null && settings.isTerminalAgentCommandNameCaseInsensitive();
    }

    private TerminalAgentExecutionTarget getTerminalAgentExecutionTarget() {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return settings != null ? settings.getTerminalAgentExecutionTarget() : TerminalAgentExecutionTarget.TERMINAL_WINDOW;
    }

    private boolean shouldShowTerminalAgentRunDialog() {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return settings == null || settings.isTerminalAgentShowRunDialog();
    }

    private boolean shouldShowTerminalAgentDebugMessages() {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return settings != null && settings.isTerminalAgentShowDebugMessages();
    }

    private boolean shouldShowTerminalAgentRuntimeMessages() {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return settings != null && settings.isTerminalAgentShowRuntimeMessages();
    }

    private String getConnectionDisplayName(TerminalTab terminalTab) {
        return terminalTab.getConnection() != null
            ? terminalTab.getConnection().getDisplayName()
            : I18n.get("ai.agent.connection.unknown");
    }

    private boolean isTerminalPromptHookEnabled() {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return settings == null || settings.isDefaultPromptHookEnabled();
    }

    private void showAiAgent() {
        if (!isAiFeaturesEnabled()) {
            return;
        }
        if (!isTerminalAgentExecutionEnabled()) {
            showError(I18n.get("ai.agent.title"), I18n.get("ai.agent.error.executionDisabled"));
            return;
        }
        TerminalTab terminalTab = getActiveTerminalTab();
        if (terminalTab == null || !terminalTab.isConnected()) {
            showError(I18n.get("ai.agent.title"), I18n.get("ai.agent.error.noTerminal"));
            return;
        }
        requestAiAgentForTab(terminalTab, false, null, null, false, false);
    }

    private void showAiPlanning() {
        if (!isAiFeaturesEnabled()) {
            return;
        }
        TerminalTab terminalTab = getActiveTerminalTab();
        if (terminalTab == null || !terminalTab.isConnected()) {
            showError(I18n.get("ai.plan.title"), I18n.get("ai.agent.error.noTerminal"));
            return;
        }
        requestAiPlanningForTab(terminalTab, null, null);
    }

    private void requestAiAgentForTab(
        TerminalTab terminalTab,
        boolean queryOnly,
        String initialPrompt,
        String requestedProfileName,
        boolean askConfirmationBeforeEveryCommand,
        boolean autoApproveRootCommands) {
        requestAiAgentForTab(
            terminalTab,
            queryOnly,
            initialPrompt,
            requestedProfileName,
            askConfirmationBeforeEveryCommand,
            autoApproveRootCommands,
            null);
    }

    private void requestAiAgentForTab(
        TerminalTab terminalTab,
        boolean queryOnly,
        String initialPrompt,
        String requestedProfileName,
        boolean askConfirmationBeforeEveryCommand,
        boolean autoApproveRootCommands,
        TerminalView.TerminalAgentRunContext runContext) {
        if (!isAiFeaturesEnabled()) {
            return;
        }
        if (!queryOnly && !isTerminalAgentExecutionEnabled()) {
            showError(I18n.get("ai.agent.title"), I18n.get("ai.agent.error.executionDisabled"));
            return;
        }
        List<AiProfile> profiles = getAvailableAiProfiles();
        if (profiles.isEmpty()) {
            showAiManager();
            showError(I18n.get(queryOnly ? "ai.agent.ask.title" : "ai.agent.title"), I18n.get("settings.ai.error.noProfilesConfigured"));
            return;
        }
        if (!shouldShowTerminalAgentRunDialog() && initialPrompt != null && !initialPrompt.isBlank()) {
            AiProfile resolvedProfile = requestedProfileName != null && !requestedProfileName.isBlank()
                ? findAiProfileByLookup(requestedProfileName)
                : null;
            if (resolvedProfile == null) {
                resolvedProfile = getDefaultAiProfile();
            }
            if (resolvedProfile == null) {
                showAiManager();
                showError(I18n.get(queryOnly ? "ai.agent.ask.title" : "ai.agent.title"), I18n.get("settings.ai.error.noProfilesConfigured"));
                return;
            }
            TerminalAgentModels.Request directRequest = new TerminalAgentModels.Request(
                terminalTab.getAiSessionId(),
                resolvedProfile.getId(),
                initialPrompt.trim(),
                getConnectionDisplayName(terminalTab),
                "",
                getTerminalAgentExecutionTarget(),
                shouldShowTerminalAgentDebugMessages(),
                shouldShowTerminalAgentRuntimeMessages(),
                askConfirmationBeforeEveryCommand,
                autoApproveRootCommands,
                !queryOnly && shouldConfirmTerminalAgentMutatingCommandSets(),
                queryOnly);
            launchTerminalAgent(terminalTab, directRequest, runContext);
            return;
        }

        List<AiProfile> orderedProfiles = reorderProfilesForLookup(profiles, requestedProfileName);
        AiAgentDialog dialog = new AiAgentDialog(
            stage,
            orderedProfiles,
            getConnectionDisplayName(terminalTab),
            getTerminalAgentExecutionTarget(),
            shouldShowTerminalAgentDebugMessages(),
            shouldShowTerminalAgentRuntimeMessages(),
            queryOnly,
            initialPrompt);
        dialog.showAndWait().ifPresent(request -> {
            TerminalAgentModels.Request enrichedRequest = new TerminalAgentModels.Request(
                terminalTab.getAiSessionId(),
                request.profileId(),
                request.userPrompt(),
                request.connectionDisplayName(),
                request.acceptedPlanContext(),
                request.executionTarget(),
                request.showDebugMessages(),
                request.showRuntimeMessages(),
                askConfirmationBeforeEveryCommand || request.askConfirmationBeforeEveryCommand(),
                autoApproveRootCommands || request.autoApproveRootCommands(),
                !request.queryOnly() && shouldConfirmTerminalAgentMutatingCommandSets(),
                request.queryOnly());
            launchTerminalAgent(terminalTab, enrichedRequest, runContext);
        });
    }

    private void requestAiPlanningForTab(TerminalTab terminalTab, String initialPrompt, String requestedProfileName) {
        requestAiPlanningForTab(terminalTab, initialPrompt, requestedProfileName, null);
    }

    private void requestAiPlanningForTab(
        TerminalTab terminalTab,
        String initialPrompt,
        String requestedProfileName,
        TerminalView.TerminalAgentRunContext runContext) {
        if (!isAiFeaturesEnabled()) {
            return;
        }
        List<AiProfile> profiles = getAvailableAiProfiles();
        if (profiles.isEmpty()) {
            showAiManager();
            showError(I18n.get("ai.plan.title"), I18n.get("settings.ai.error.noProfilesConfigured"));
            return;
        }
        List<AiProfile> orderedProfiles = reorderProfilesForLookup(profiles, requestedProfileName);
        AiAgentPlanDialog dialog = new AiAgentPlanDialog(
            stage,
            orderedProfiles,
            terminalTab.getConnection() != null ? terminalTab.getConnection().getDisplayName() : null,
            initialPrompt);
        dialog.showAndWait().ifPresent(request -> {
            TerminalAgentModels.PlanRequest enrichedRequest = new TerminalAgentModels.PlanRequest(
                terminalTab.getAiSessionId(),
                request.profileId(),
                request.userPrompt(),
                request.connectionDisplayName());
            launchTerminalAgentPlan(terminalTab, enrichedRequest, runContext);
        });
    }

    private List<AiProfile> reorderProfilesForLookup(List<AiProfile> profiles, String requestedProfileName) {
        return AiProfileSelectionSupport.reorderByRequestedOrDefault(
            profiles,
            requestedProfileName,
            getDefaultAiProfileId());
    }

    private AiProfile findAiProfileByLookup(String lookup) {
        return AiProfileSelectionSupport.findByLookup(getAvailableAiProfiles(), lookup);
    }

    private void launchTerminalAgent(TerminalTab terminalTab, TerminalAgentModels.Request request) {
        launchTerminalAgent(terminalTab, request, null);
    }

    private void launchTerminalAgent(
        TerminalTab terminalTab,
        TerminalAgentModels.Request request,
        TerminalView.TerminalAgentRunContext runContext) {
        AiProfile profile = findAiProfileById(request.profileId());
        if (profile == null) {
            showError(I18n.get("ai.agent.title"), I18n.get("ai.agent.error.profileMissing"));
            return;
        }
        logger.info("Launching terminal AI agent with profile '{}' ({})", getAiProfileDisplayName(profile), profile.getId());
        AiService service = createAiServiceForProfile(profile);
        if (!(service instanceof AiPromptService aiService)) {
            showError(I18n.get("ai.agent.title"), I18n.get("ai.error.notConfigured"));
            return;
        }

        if (request.queryOnly()) {
            openDirectAiAskTab(profile, request.userPrompt(), request.connectionDisplayName());
            return;
        }

        TerminalView.TerminalAgentRunContext resolvedRunContext = resolveTerminalAgentRunContext(terminalTab, runContext);
        applyTerminalAgentWorkingDirectoryHint(resolvedRunContext);

        if (request.executionTarget() == TerminalAgentExecutionTarget.CHAT_WINDOW) {
            AiAgentRunTab runTab = new AiAgentRunTab(this, I18n.get("ai.agent.run.tabTitle"));
            insertTemporaryTab(runTab);
            runTab.startRun(
                terminalAgentService,
                terminalTab,
                profile,
                aiService,
                resolvedRunContext != null ? resolvedRunContext.connector() : null,
                request);
            return;
        }

        runTerminalAgentInTerminalWindow(terminalTab, profile, aiService, request, resolvedRunContext);
    }

    private void openDirectAiAskTab(AiProfile profile, String prompt, String connectionDisplayName) {
        if (prompt == null || prompt.isBlank()) {
            return;
        }
        String languageCode = LanguageManager.getInstance().getCurrentLanguageCode();
        AiRequest request = new AiRequest(AiAction.ASK, prompt, connectionDisplayName, languageCode, prompt);
        AiResultTab resultTab = new AiResultTab(
            this,
            I18n.get("ai.agent.ask.tabTitle"),
            profile,
            prompt,
            connectionDisplayName,
            languageCode,
            null,
            false);
        resultTab.appendUserMessage(prompt);
        insertTemporaryTab(resultTab);

        AiService aiService = createAiServiceForProfile(profile);
        Task<AiExecutionResult> task = new Task<>() {
            @Override
            protected AiExecutionResult call() throws Exception {
                return aiService.execute(request);
            }
        };
        Thread thread = new Thread(task, "ai-agent-ask");
        thread.setDaemon(true);
        resultTab.attachRunningTask(task, thread, I18n.get("ai.result.loading"));
        task.setOnSucceeded(event -> {
            AiExecutionResult result = task.getValue();
            resultTab.showResult(result != null ? result.content() : "");
            recordAiUsageForProfile(profile, request, result);
        });
        task.setOnCancelled(event -> resultTab.showCancelled());
        task.setOnFailed(event -> {
            Throwable error = task.getException();
            resultTab.showError(error != null && error.getMessage() != null
                ? error.getMessage()
                : I18n.get("ai.result.error"));
        });
        thread.start();
    }

    private void runTerminalAgentInTerminalWindow(
        TerminalTab terminalTab,
        AiProfile profile,
        AiPromptService aiService,
        TerminalAgentModels.Request request,
        TerminalView.TerminalAgentRunContext runContext) {
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<Thread> workerRef = new java.util.concurrent.atomic.AtomicReference<>();
        TerminalView.TerminalAgentRunContext resolvedRunContext = resolveTerminalAgentRunContext(terminalTab, runContext);
        applyTerminalAgentWorkingDirectoryHint(resolvedRunContext);
        if (resolvedRunContext == null || resolvedRunContext.connector() == null) {
            showError(I18n.get("ai.agent.title"), I18n.get("ai.agent.error.noTerminal"));
            return;
        }
        AiAgentActivityPanel activityPanel = terminalTab.getTerminalView().getTerminalAgentActivityPanel(resolvedRunContext);
        if (activityPanel == null) {
            showError(I18n.get("ai.agent.title"), I18n.get("ai.agent.error.noTerminal"));
            return;
        }
        if (terminalTab.getTerminalView().isTerminalAgentRunActive(resolvedRunContext) || activityPanel.isRunning()) {
            showError(I18n.get("ai.agent.title"), I18n.get("ai.agent.terminalBusy"));
            return;
        }
        TerminalAgentModels.Request scopedRequest = withTerminalAgentSessionId(
            request,
            terminalTab.getTerminalView().buildTerminalAgentScopedSessionId(request.sessionId(), resolvedRunContext));
        Runnable cancelRun = () -> {
            cancelled.set(true);
            Thread workerThread = workerRef.get();
            if (workerThread != null) {
                workerThread.interrupt();
            }
        };
        Runnable reloadRun = () -> launchTerminalAgent(terminalTab, request, resolvedRunContext);
        terminalTab.getTerminalView().setTerminalAgentInputLocked(
            resolvedRunContext,
            true,
            cancelRun,
            activityPanel::toggleThinkingDetails);
        AiAgentActivityPanel.RunMetadata runMetadata = new AiAgentActivityPanel.RunMetadata(
            profile.getId(),
            profile.getName(),
            aiModelDisplayText(profile),
            AiReasoningSupport.exportStatus(profile));
        Platform.runLater(() -> {
            activityPanel.beginRun(scopedRequest.userPrompt(), cancelRun, reloadRun, runMetadata);
        });
        Thread worker = new Thread(() -> {
            try {
                terminalAgentService.runAgent(terminalTab, resolvedRunContext.connector(), profile, aiService, scopedRequest, new TerminalAgentService.RunUi() {
                    @Override
                    public void updateState(TerminalAgentModels.RunState state) {
                        if (state != null && isTerminalAgentFinalPhase(state.phase())) {
                            String message = formatTerminalAgentFinalMessage(state);
                            if (message != null && !message.isBlank()) {
                                terminalTab.getTerminalView().showAgentMessage(resolvedRunContext, message);
                            }
                        }
                    }

                    @Override
                    public void appendTranscript(String text) {
                        if (scopedRequest.showDebugMessages()) {
                            activityPanel.publishActivity(new TerminalAgentModels.AgentActivity(
                                "debug-" + System.nanoTime(),
                                TerminalAgentModels.AgentActivityType.MESSAGE,
                                TerminalAgentModels.AgentActivityStatus.COMPLETED,
                                I18n.get("ai.agent.activity.debug"),
                                I18n.get("ai.agent.activity.debug"),
                                text != null ? text.trim() : "",
                                TerminalAgentModels.AgentActivityTokenUsage.unknown(),
                                0L,
                                text != null && !text.isBlank(),
                                true));
                        }
                    }

                    @Override
                    public void publishActivity(TerminalAgentModels.AgentActivity activity) {
                        activityPanel.publishActivity(activity);
                    }

                    @Override
                    public void recordTokenUsage(AiTokenUsage usage) {
                        if (usage == null) {
                            return;
                        }
                        recordAiUsageForProfile(profile, usage);
                    }

                    @Override
                    public TerminalAgentService.ApprovalDecision requestApproval(TerminalAgentModels.Approval approval) {
                        return activityPanel.requestApproval(approval);
                    }

                    @Override
                    public TerminalAgentModels.PasswordResponse requestPassword(TerminalAgentModels.PasswordRequest passwordRequest) {
                        return activityPanel.requestPassword(passwordRequest);
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancelled.get();
                    }
                });
            } catch (Exception e) {
                if (TerminalAgentService.isCancellation(e) || cancelled.get()) {
                    activityPanel.publishActivity(new TerminalAgentModels.AgentActivity(
                        "cancelled-" + System.nanoTime(),
                        TerminalAgentModels.AgentActivityType.MESSAGE,
                        TerminalAgentModels.AgentActivityStatus.CANCELLED,
                        I18n.get("ai.agent.activity.cancelled"),
                        I18n.get("ai.agent.activity.cancelled"),
                        "",
                        TerminalAgentModels.AgentActivityTokenUsage.unknown(),
                        0L,
                        false,
                        true));
                    terminalTab.getTerminalView().showAgentMessage(resolvedRunContext, I18n.get("ai.agent.activity.cancelled"));
                } else {
                    activityPanel.publishActivity(new TerminalAgentModels.AgentActivity(
                        "failed-" + System.nanoTime(),
                        TerminalAgentModels.AgentActivityType.ERROR,
                        TerminalAgentModels.AgentActivityStatus.FAILED,
                        I18n.get("ai.agent.run.phase.failed"),
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                        "",
                        TerminalAgentModels.AgentActivityTokenUsage.unknown(),
                        0L,
                        false,
                        true));
                    Platform.runLater(() -> showError(I18n.get("ai.agent.title"),
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                }
            } finally {
                Platform.runLater(() -> {
                    activityPanel.finishRun();
                    terminalTab.getTerminalView().setTerminalAgentInputLocked(resolvedRunContext, false, null, null);
                });
            }
        }, "ai-agent-terminal");
        workerRef.set(worker);
        worker.setDaemon(true);
        worker.start();
    }

    private TerminalView.TerminalAgentRunContext resolveTerminalAgentRunContext(
        TerminalTab terminalTab,
        TerminalView.TerminalAgentRunContext runContext) {

        if (runContext != null) {
            return runContext;
        }
        return terminalTab.getTerminalView() != null
            ? terminalTab.getTerminalView().captureTerminalAgentRunContext()
            : null;
    }

    private void applyTerminalAgentWorkingDirectoryHint(TerminalView.TerminalAgentRunContext runContext) {
        if (runContext == null || runContext.connector() == null) {
            return;
        }
        runContext.connector().updateCurrentRemoteDirectoryHint(runContext.workingDirectory());
    }

    private TerminalAgentModels.Request withTerminalAgentSessionId(
        TerminalAgentModels.Request request,
        String sessionId) {
        return new TerminalAgentModels.Request(
            sessionId,
            request.profileId(),
            request.userPrompt(),
            request.connectionDisplayName(),
            request.acceptedPlanContext(),
            request.executionTarget(),
            request.showDebugMessages(),
            request.showRuntimeMessages(),
            request.askConfirmationBeforeEveryCommand(),
            request.autoApproveRootCommands(),
            request.confirmMutatingCommandSets(),
            request.queryOnly());
    }

    private boolean isTerminalAgentFinalPhase(TerminalAgentModels.Phase phase) {
        return phase == TerminalAgentModels.Phase.DONE
            || phase == TerminalAgentModels.Phase.BLOCKED
            || phase == TerminalAgentModels.Phase.CANCELLED
            || phase == TerminalAgentModels.Phase.FAILED;
    }

    private String formatTerminalAgentFinalMessage(TerminalAgentModels.RunState state) {
        if (state == null) {
            return "";
        }
        String userMessage = state.userMessage() != null ? state.userMessage().trim() : "";
        String summary = state.summary() != null ? state.summary().trim() : "";
        if (userMessage.isBlank()) {
            return summary;
        }
        if (summary.isBlank() || userMessage.equals(summary) || userMessage.contains(summary)) {
            return userMessage;
        }
        if (summary.contains(userMessage)) {
            return summary;
        }
        return userMessage + "\n" + summary;
    }

    private void launchTerminalAgentPlan(TerminalTab terminalTab, TerminalAgentModels.PlanRequest request) {
        launchTerminalAgentPlan(terminalTab, request, null);
    }

    private void launchTerminalAgentPlan(
        TerminalTab terminalTab,
        TerminalAgentModels.PlanRequest request,
        TerminalView.TerminalAgentRunContext runContext) {
        AiProfile profile = findAiProfileById(request.profileId());
        if (profile == null) {
            showError(I18n.get("ai.plan.title"), I18n.get("ai.agent.error.profileMissing"));
            return;
        }
        logger.info("Launching terminal AI planning with profile '{}' ({})", getAiProfileDisplayName(profile), profile.getId());
        AiService service = createAiServiceForProfile(profile);
        if (!(service instanceof AiPromptService aiService)) {
            showError(I18n.get("ai.plan.title"), I18n.get("ai.error.notConfigured"));
            return;
        }

        TerminalAgentExecutionTarget executionTarget = getTerminalAgentExecutionTarget();
        TerminalView.TerminalAgentRunContext resolvedRunContext = runContext;
        if (executionTarget == TerminalAgentExecutionTarget.TERMINAL_WINDOW
            && resolvedRunContext == null
            && terminalTab.getTerminalView() != null) {
            resolvedRunContext = terminalTab.getTerminalView().captureTerminalAgentRunContext();
        }
        applyTerminalAgentWorkingDirectoryHint(resolvedRunContext);
        TerminalView.TerminalAgentRunContext planRunContext = resolvedRunContext;
        AiAgentPlanTab planTab = new AiAgentPlanTab(
            this,
            terminalAgentService,
            terminalTab,
            profile,
            aiService,
            request,
            planRunContext != null ? planRunContext.connector() : null,
            () -> resolveTerminalAgentPreflightSessionId(terminalTab, request.sessionId(), planRunContext),
            (planRequest, report) -> startAcceptedPlanExecution(terminalTab, profile, planRequest, report, planRunContext));
        insertTemporaryTab(planTab);
        planTab.start();
    }

    private String resolveTerminalAgentPreflightSessionId(
        TerminalTab terminalTab,
        String sessionId,
        TerminalView.TerminalAgentRunContext runContext) {
        if (getTerminalAgentExecutionTarget() == TerminalAgentExecutionTarget.TERMINAL_WINDOW
            && runContext != null
            && terminalTab.getTerminalView() != null) {
            return terminalTab.getTerminalView().buildTerminalAgentScopedSessionId(sessionId, runContext);
        }
        return sessionId;
    }

    private void startAcceptedPlanExecution(
        TerminalTab terminalTab,
        AiProfile profile,
        TerminalAgentModels.PlanRequest planRequest,
        TerminalAgentModels.PlanReport report,
        TerminalView.TerminalAgentRunContext runContext) {
        if (!isTerminalAgentExecutionEnabled()) {
            showError(I18n.get("ai.agent.title"), I18n.get("ai.agent.error.executionDisabled"));
            return;
        }
        TerminalAgentModels.Request request = new TerminalAgentModels.Request(
            planRequest.sessionId(),
            profile.getId(),
            planRequest.userPrompt(),
            planRequest.connectionDisplayName(),
            terminalAgentService.buildAcceptedPlanContext(report),
            getTerminalAgentExecutionTarget(),
            app.getGlobalSettingsManager().getSettings() != null && app.getGlobalSettingsManager().getSettings().isTerminalAgentShowDebugMessages(),
            app.getGlobalSettingsManager().getSettings() != null && app.getGlobalSettingsManager().getSettings().isTerminalAgentShowRuntimeMessages(),
            false,
            false,
            shouldConfirmTerminalAgentMutatingCommandSets(),
            false);
        launchTerminalAgent(terminalTab, request, runContext);
    }

    private void handleTerminalAgentShortcut(
        TerminalTab terminalTab,
        String rawCommand,
        TerminalView.TerminalAgentRunContext runContext) {
        String commandName = getTerminalAgentCommandName();
        TerminalAgentCommandSupport.Invocation invocation =
            TerminalAgentCommandSupport.parseShortcut(
                rawCommand,
                commandName,
                isTerminalAgentCommandNameCaseInsensitive());
        if (invocation == null) {
            terminalTab.getTerminalView().showError(TerminalAgentCommandSupport.buildUsageText(commandName));
            return;
        }
        switch (invocation.kind()) {
            case ASK -> requestAiAgentForTab(terminalTab, true, invocation.userPrompt(), invocation.profileName(), false, false, runContext);
            case PLAN -> requestAiPlanningForTab(terminalTab, invocation.userPrompt(), invocation.profileName(), runContext);
            case EXECUTE -> requestAiAgentForTab(
                terminalTab,
                false,
                invocation.userPrompt(),
                invocation.profileName(),
                invocation.askConfirmationBeforeEveryCommand(),
                invocation.autoApproveRootCommands(),
                runContext);
        }
    }

    private record AiRequestDraft(String selectedText, String userPrompt) {
    }

    private String getAiProfileDisplayName(AiProfile profile) {
        if (profile == null || profile.getName() == null || profile.getName().isBlank()) {
            return I18n.get("settings.ai.profile.unnamed");
        }
        return profile.getName().trim();
    }

    void updateStatusMessage(String message) {
        updateStatus(message);
    }

    List<AiProfile> getAvailableAiProfiles() {
        refreshGlobalSettingsIfChangedBeforeAiProfileResolution();
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        if (settings == null || settings.getAiProfiles() == null) {
            return List.of();
        }
        return settings.getAiProfiles().stream()
            .filter(profile -> profile != null)
            .sorted((left, right) -> getAiProfileDisplayName(left).compareToIgnoreCase(getAiProfileDisplayName(right)))
            .toList();
    }

    String getDefaultAiProfileId() {
        refreshGlobalSettingsIfChangedBeforeAiProfileResolution();
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return settings != null ? settings.getDefaultAiProfileId() : null;
    }

    private void refreshGlobalSettingsIfChangedBeforeAiProfileResolution() {
        try {
            var manager = app.getGlobalSettingsManager();
            if (manager != null && manager.reloadIfChanged()) {
                logger.info("Reloaded global settings before resolving AI profile selection");
            }
        } catch (Exception e) {
            logger.warn("Could not refresh global settings before resolving AI profile selection: {}", e.getMessage());
        }
    }

    AiProfile getDefaultAiProfile() {
        return AiProfileSelectionSupport.defaultProfile(getAvailableAiProfiles(), getDefaultAiProfileId());
    }

    AiProfile findAiProfileById(String profileId) {
        return AiProfileSelectionSupport.findById(getAvailableAiProfiles(), profileId);
    }

    AiService createAiServiceForProfile(AiProfile profile) {
        return createAiService(profile);
    }

    void recordAiUsageForProfile(AiProfile profile, AiRequest request, AiExecutionResult result) {
        recordAiUsage(profile, request, result);
    }

    void recordAiUsageForProfile(AiProfile profile, AiTokenUsage usage) {
        recordAiUsage(profile, usage);
    }

    void registerSavedChatTab(AiResultTab tab) {
        if (tab == null || tab.getSavedChatId() == null || tab.getSavedChatId().isBlank()) {
            return;
        }
        openSavedAiChatTabs.put(tab.getSavedChatId(), tab);
    }

    void unregisterSavedChatTab(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        openSavedAiChatTabs.remove(chatId);
    }

    AiResultTab findOpenSavedChatTab(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return null;
        }
        AiResultTab tab = openSavedAiChatTabs.get(chatId);
        if (tab == null) {
            return null;
        }
        if (!tabPane.getTabs().contains(tab)) {
            openSavedAiChatTabs.remove(chatId);
            return null;
        }
        return tab;
    }

    AiResultTab openSavedAiChat(SavedAiChat chat) {
        if (chat == null) {
            return null;
        }

        AiResultTab existingTab = findOpenSavedChatTab(chat.getId());
        if (existingTab != null) {
            tabPane.getSelectionModel().select(existingTab);
            return existingTab;
        }

        SavedAiChat workingCopy = new SavedAiChat(chat);
        List<AiProfile> availableProfiles = getAvailableAiProfiles();
        AiProfile activeProfile = findAiProfileById(workingCopy.getActiveAiProfileId());
        boolean readOnly = false;

        if (activeProfile == null && workingCopy.getActiveAiProfileId() != null && !workingCopy.getActiveAiProfileId().isBlank()) {
            if (availableProfiles.isEmpty()) {
                readOnly = true;
            } else {
                Optional<AiProfile> replacementProfile = promptForSavedChatProfile(workingCopy, availableProfiles);
                if (replacementProfile.isEmpty()) {
                    return null;
                }
                activeProfile = replacementProfile.get();
                workingCopy.setActiveAiProfileId(activeProfile.getId());
                workingCopy.setActiveAiProfileName(getAiProfileDisplayName(activeProfile));
                try {
                    workingCopy = app.getAiChatManager().saveChat(workingCopy);
                } catch (Exception e) {
                    logger.error("Failed to persist replacement AI profile for saved chat {}", workingCopy.getId(), e);
                    showError(I18n.get("error.title"), e.getMessage());
                    return null;
                }
            }
        } else if (activeProfile == null && !availableProfiles.isEmpty()) {
            activeProfile = getDefaultAiProfile();
            workingCopy.setActiveAiProfileId(activeProfile.getId());
            workingCopy.setActiveAiProfileName(getAiProfileDisplayName(activeProfile));
            try {
                workingCopy = app.getAiChatManager().saveChat(workingCopy);
            } catch (Exception e) {
                logger.error("Failed to persist default AI profile for saved chat {}", workingCopy.getId(), e);
                showError(I18n.get("error.title"), e.getMessage());
                return null;
            }
        } else if (activeProfile == null) {
            readOnly = true;
        }

        String title = workingCopy.getTitle() != null && !workingCopy.getTitle().isBlank()
            ? workingCopy.getTitle().trim()
            : I18n.get("ai.saved.defaultTitle");
        AiResultTab resultTab = new AiResultTab(
            this,
            title,
            activeProfile,
            workingCopy.getSelectedText(),
            workingCopy.getConnectionDisplayName(),
            workingCopy.getResponseLanguageCode(),
            workingCopy,
            readOnly);
        insertTemporaryTab(resultTab);
        registerSavedChatTab(resultTab);
        updateStatus(I18n.get("ai.manager.opened", title));
        return resultTab;
    }

    boolean renameSavedAiChat(SavedAiChat chat, String newTitle) {
        if (chat == null || newTitle == null || newTitle.isBlank()) {
            return false;
        }
        SavedAiChat updatedChat = new SavedAiChat(chat);
        updatedChat.setTitle(newTitle.trim());
        try {
            SavedAiChat saved = app.getAiChatManager().saveChat(updatedChat);
            AiResultTab openTab = findOpenSavedChatTab(saved.getId());
            if (openTab != null) {
                openTab.applySavedChatTitle(saved.getTitle());
            }
            updateStatus(I18n.get("ai.manager.renamed", saved.getTitle()));
            return true;
        } catch (Exception e) {
            logger.error("Failed to rename saved AI chat {}", chat.getId(), e);
            showError(I18n.get("error.title"), e.getMessage());
            return false;
        }
    }

    boolean deleteSavedAiChat(SavedAiChat chat) {
        if (chat == null || chat.getId() == null || chat.getId().isBlank()) {
            return false;
        }
        try {
            boolean deleted = app.getAiChatManager().deleteChat(chat.getId());
            if (!deleted) {
                return false;
            }
            AiResultTab openTab = findOpenSavedChatTab(chat.getId());
            if (openTab != null) {
                openTab.closeTab();
            }
            unregisterSavedChatTab(chat.getId());
            updateStatus(I18n.get("ai.manager.deleted", chat.getTitle() != null ? chat.getTitle() : ""));
            return true;
        } catch (Exception e) {
            logger.error("Failed to delete saved AI chat {}", chat.getId(), e);
            showError(I18n.get("error.title"), e.getMessage());
            return false;
        }
    }

    private Optional<AiProfile> promptForSavedChatProfile(SavedAiChat chat, List<AiProfile> availableProfiles) {
        Dialog<AiProfile> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(I18n.get("ai.profile.missing.title"));
        dialog.setHeaderText(I18n.get("ai.profile.missing.header",
            chat.getTitle() != null && !chat.getTitle().isBlank() ? chat.getTitle().trim() : I18n.get("ai.saved.defaultTitle")));
        dialog.initOwner(stage);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ComboBox<AiProfile> profileBox = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(availableProfiles));
        profileBox.setPrefWidth(360);
        profileBox.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : getAiProfileDisplayName(item));
            }
        });
        profileBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : getAiProfileDisplayName(item));
            }
        });
        AiProfile defaultProfile = getDefaultAiProfile();
        if (defaultProfile != null) {
            profileBox.getSelectionModel().select(
                availableProfiles.stream()
                    .filter(profile -> defaultProfile.getId() != null && defaultProfile.getId().equals(profile.getId()))
                    .findFirst()
                    .orElse(availableProfiles.getFirst()));
        } else {
            profileBox.getSelectionModel().selectFirst();
        }

        VBox content = new VBox(10, new Label(I18n.get("ai.profile.missing.content")), profileBox);
        content.setPadding(new Insets(8, 0, 0, 0));
        dialog.getDialogPane().setContent(content);

        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(profileBox.getSelectionModel().getSelectedItem() == null);
        profileBox.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) ->
            okButton.setDisable(newValue == null));

        dialog.setResultConverter(buttonType -> buttonType == ButtonType.OK ? profileBox.getSelectionModel().getSelectedItem() : null);
        return dialog.showAndWait();
    }

    private static String getProtocolLabel(ConnectionProtocol protocol) {
        if (protocol == null) return "SSH";
        return switch (protocol) {
            case MOSH -> I18n.get("protocol.mosh");
            case MOSH_CLIENT -> I18n.get("protocol.moshClient");
            case SSH_TCP -> I18n.get("protocol.sshTcp");
        };
    }
    
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    public static List<MainWindow> getOpenWindows() {
        return openWindows;
    }

    public static void showAutomaticUpdateAvailable(AvailableUpdate update) {
        MainWindow window = getFocusedOrLastOpenWindow();
        if (window != null) {
            window.showUpdateAvailableDialog(update, false);
        }
    }
    
    public Stage getStage() {
        return stage;
    }
    
    /**
     * Returns the currently selected tab in the main tab pane.
     */
    public Tab getActiveTab() {
        return tabPane.getSelectionModel().getSelectedItem();
    }
    
    /**
     * Opens all connections in a group as tabs.
     */
    private void openGroupConnections(String groupName) {
        List<ServerConnection> groupConnections = app.getConfigManager().getConnections().stream()
                .filter(c -> groupName.equals(c.getGroup()))
                .collect(java.util.stream.Collectors.toList());
        
        if (groupConnections.isEmpty()) {
            logger.warn("No connections found in group: {}", groupName);
            return;
        }
        
        logger.info("Opening {} connections from group: {}", groupConnections.size(), groupName);
        
        // Create password vault for retrieving stored passwords
        PasswordVault vault = new PasswordVault(
                app.getMasterPasswordManager().getEncryptionService(),
                app.getMasterPasswordManager().getMasterPassword()
        );
        
        for (ServerConnection conn : groupConnections) {
            // Retrieve password from vault
            String password = vault != null ? vault.retrievePassword(conn) : "";
            
            if (password == null || password.isEmpty()) {
                logger.warn("No password found for connection: {}", conn.getDisplayName());
                // Skip this connection or show password dialog
                continue;
            }
            
            // Increment usage count
            conn.incrementUsageCount();
            
            // Open tab
            TerminalTab tab = new TerminalTab(conn, password);
            if (TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
                tab.getTerminalView().setTerminalEffectAnimationSpeed(
                        conn.getTerminalEffectAnimationSpeed() != null
                                ? conn.getTerminalEffectAnimationSpeed()
                                : TerminalEffectAnimationSpeed.DEFAULT);
                tab.getTerminalView().setTerminalEffectPluginId(conn.getTerminalEffectPluginId());
            }
            installAiSelectionHandler(tab);
            tab.setTimestampToggleListener(() -> Platform.runLater(() -> {
                Tab activeTab = tabPane.getSelectionModel().getSelectedItem();
                if (activeTab instanceof TerminalTab active) {
                    syncTimestampMenuItems(active.isTimestampGuttersVisible());
                }
            }));
            tabPane.getTabs().add(tab);
            tab.connect();
            
            // Select the first tab
            if (tabPane.getTabs().size() == 1) {
                tabPane.getSelectionModel().select(tab);
            }
        }
        
        // Save updated usage counts
        try {
            app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
        } catch (Exception e) {
            logger.error("Failed to save usage counts", e);
        }
        
        updateStatus(I18n.get("status.groupOpened", groupName, groupConnections.size()));
        updateDashboard();
    }
    
    
    private void showCredentialManagement() {
        try {
            CredentialManagementDialog dialog = new CredentialManagementDialog(
                app.getCredentialManager(),
                app.getEnvironmentManager(),
                app.getMasterPasswordManager().getMasterPassword()
            );
            dialog.initOwner(stage);
            dialog.showAndWait();
        } catch (Exception e) {
            logger.error("Failed to show credential management", e);
            showError(I18n.get("error.title"), I18n.get("error.credentialManagementFailed", e.getMessage()));
        }
    }
    
    private void showGPGKeyManagement() {
        try {
            GPGKeyManagementDialog dialog = new GPGKeyManagementDialog(app.getGpgKeyManager());
            dialog.initOwner(stage);
            dialog.showAndWait();
        } catch (Exception e) {
            logger.error("Failed to show GPG key management", e);
            showError(I18n.get("error.title"), I18n.get("error.gpgKeyManagementFailed", e.getMessage()));
        }
    }
    
    private void showTeamworkSettings() {
        try {
            TeamworkSettingsDialog dialog = new TeamworkSettingsDialog(stage, app);
            dialog.showAndWait();
        } catch (Exception e) {
            logger.error("Failed to show teamwork settings", e);
            showError(I18n.get("error.title"), e.getMessage());
        }
    }

    private void showTerminalEffectPluginManager() {
        try {
            var manager = app.getTerminalEffectPluginManager();
            if (manager == null) {
                showError(I18n.get("error.title"), I18n.get("plugin.initError"));
                return;
            }
            TerminalEffectPluginManagerDialog dialog =
                    new TerminalEffectPluginManagerDialog(stage, manager);
            dialog.showAndWait();
            deactivateTerminalEffectsIfDisabled();
            deactivateUnavailableTerminalEffects();
            updateAllTabContextMenus();
        } catch (Exception e) {
            logger.error("Failed to show terminal effect plugin manager", e);
            showError(I18n.get("error.title"), e.getMessage());
        }
    }

    private void deactivateUnavailableTerminalEffects() {
        var manager = app.getTerminalEffectPluginManager();
        if (manager == null) {
            return;
        }
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                String pluginId = terminalTab.getTerminalView().getTerminalEffectPluginId();
                if (pluginId != null && manager.findPlugin(pluginId).isEmpty()) {
                    terminalTab.getTerminalView().setTerminalEffectPluginId(null);
                }
            }
        }
    }

    private void deactivateTerminalEffectsIfDisabled() {
        if (TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
            return;
        }
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                terminalTab.getTerminalView().setTerminalEffectPluginId(null);
            }
        }
    }
    
    private void showSSHKeyManagement() {
        try {
            SSHKeyManagementDialog dialog = new SSHKeyManagementDialog(
                app.getSSHKeyManager(),
                app.getMasterPasswordManager().getMasterPassword()
            );
            dialog.initOwner(stage);
            dialog.showAndWait();
        } catch (Exception e) {
            logger.error("Failed to show SSH key management", e);
            showError(I18n.get("error.title"), I18n.get("error.sshKeyManagementFailed", e.getMessage()));
        }
    }
    
    private void showAsciiArtBanner() {
        try {
            AsciiArtBannerDialog dialog = new AsciiArtBannerDialog();
            dialog.initOwner(stage);
            dialog.showAndWait();
        } catch (Exception e) {
            logger.error("Failed to open ASCII Art Banner dialog", e);
            showError(I18n.get("error.title"), e.getMessage());
        }
    }

    private void showTerminalRecordingManager() {
        try {
            TerminalRecordingManagerDialog dialog = new TerminalRecordingManagerDialog(
                app.getGlobalSettingsManager(),
                new de.kortty.core.TerminalRecordingService());
            dialog.initOwner(stage);
            dialog.setOnHidden(event -> refreshTerminalRecordingControlsVisibility());
            dialog.show();
        } catch (Exception e) {
            logger.error("Failed to open terminal recording manager", e);
            showError(I18n.get("error.title"), e.getMessage());
        }
    }

    private void refreshTerminalRecordingControlsVisibility() {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                terminalTab.refreshRecordingControlsVisibility();
            }
        }
    }

    private void toggleTerminalRecording() {
        Tab activeTab = getActiveTab();
        if (activeTab instanceof TerminalTab terminalTab) {
            terminalTab.toggleRecordingFromMenuOrShortcut();
            return;
        }
        updateStatus(I18n.get("terminal.recording.error.noTerminal"));
    }
    
    /**
     * Shows SFTP Manager dialog. If a connection is selected, opens it directly.
     * Otherwise, shows a dialog to select a connection.
     */
    private void showSnippetManager() {
        logger.info("showSnippetManager() called - Opening Snippet Manager");
        try {
            de.kortty.core.SnippetManager mgr = app.getSnippetManager();
            if (mgr == null) {
                showError(I18n.get("error.title"), "Snippet Manager not initialized");
                return;
            }
            SnippetManagementDialog dialog = new SnippetManagementDialog(mgr, this);
            dialog.initOwner(stage);
            dialog.show();
        } catch (Exception e) {
            logger.error("Failed to open Snippet Manager", e);
            showError(I18n.get("error.title"), e.getMessage());
        }
    }

    private void showJobScheduler() {
        logger.info("showJobScheduler() called - Opening JobScheduler");
        try {
            if (app.getJobSchedulerService() == null) {
                showError(I18n.get("error.title"), "JobScheduler is not initialized.");
                return;
            }
            JobSchedulerDialog dialog = new JobSchedulerDialog(app, stage);
            dialog.show();
        } catch (Exception e) {
            logger.error("Failed to open JobScheduler", e);
            showError(I18n.get("error.title"), "JobScheduler could not be opened: " + e.getMessage());
        }
    }

    private void showAiManager() {
        if (!isAiFeaturesEnabled()) {
            return;
        }
        try {
            AiManagerDialog dialog = new AiManagerDialog(this);
            dialog.initOwner(stage);
            dialog.showAndWait();
        } catch (Exception e) {
            logger.error("Failed to open AI Manager", e);
            showError(I18n.get("error.title"), e.getMessage());
        }
    }
    
    private void showSFTPManager() {
        logger.info("showSFTPManager() called - Opening SFTP Manager");
        
        // Check if there's an active connection in the current tab
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        if (selectedTab instanceof TerminalTab terminalTab && terminalTab.isConnected()) {
            // Use current connection - pass temporary key if tab was connected with one
            logger.info("Using active connection: {}", terminalTab.getConnection().getDisplayName());
            openSFTPManagerForConnection(terminalTab.getConnection(), terminalTab.getTemporarySSHKey());
            return;
        }
        
        logger.info("No active connection - showing connection selection dialog");
        
        // Show connection selection dialog
        ConnectionSelectionDialog dialog = new ConnectionSelectionDialog(
            stage, 
            app.getConfigManager().getConnections(),
            I18n.get("sftp.selectConnection")
        );
        
        dialog.showAndWait().ifPresent(connection -> {
            logger.info("Connection selected: {}", connection.getDisplayName());
            // Connection selection - do NOT use temp key (only when opened from tab with temp key)
            openSFTPManagerForConnection(connection, null);
        });
    }
    
    /**
     * Opens SFTP Manager for a specific connection.
     * @param connection The connection to use
     * @param temporarySSHKey Optional temporary SSH key (only when opened from tab that used temp key)
     */
    private void openSFTPManagerForConnection(ServerConnection connection, de.kortty.model.TemporarySSHKey temporarySSHKey) {
        try {
            de.kortty.model.TemporarySSHKey keyToUse = temporarySSHKey;
            
            // Check if this connection was originally connected with a temporary SSH key
            boolean wasConnectedWithTempKey = (temporarySSHKey != null) || 
                (connection.getTemporaryKeyContent() != null && !connection.getTemporaryKeyContent().trim().isEmpty());
            
            // When tab was connected with temp key: check if still valid, otherwise ask for new key
            if (wasConnectedWithTempKey) {
                if (keyToUse == null || !keyToUse.isValid()) {
                    // Ask for new temporary SSH key
                    keyToUse = requestNewTemporarySSHKey(connection);
                    if (keyToUse == null) {
                        return; // User cancelled
                    }
                }
            }
            
            // When using valid temporary SSH key or regular SSH key auth, no password needed
            String password = null;
            boolean isKeyAuth = connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY;
            if (keyToUse == null && !isKeyAuth) {
                password = getConnectionPassword(connection);
            }
            if (password == null && keyToUse == null && !isKeyAuth) {
                // Ask for password using a simple dialog (only when NOT using key auth)
                Dialog<String> pwdDialog = new Dialog<>();
                DialogThemeHelper.applyTheme(pwdDialog);
                pwdDialog.setTitle(I18n.get("dialog.passwordRequired"));
                pwdDialog.setHeaderText(I18n.get("dialog.passwordFor", connection.getDisplayName()));
                pwdDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
                
                PasswordField passwordField = new PasswordField();
                passwordField.setPromptText(I18n.get("common.password"));
                VBox content = new VBox(10);
                content.setPadding(new javafx.geometry.Insets(20));
                content.getChildren().addAll(new Label(I18n.get("dialog.pleaseEnterPassword")), passwordField);
                pwdDialog.getDialogPane().setContent(content);
                
                pwdDialog.setResultConverter(buttonType -> {
                    if (buttonType == ButtonType.OK) {
                        return passwordField.getText();
                    }
                    return null;
                });
                
                Optional<String> result = pwdDialog.showAndWait();
                if (result.isPresent() && !result.get().isEmpty()) {
                    password = result.get();
                } else {
                    return; // User cancelled
                }
            }
            
            // Use empty password when temp key auth - SFTPSession uses key, not password
            String passwordToUse = (keyToUse != null && keyToUse.isValid()) ? "" : (password != null ? password : "");
            
            // Open SFTP Manager as a TAB instead of a dialog
            openSFTPManagerTab(connection, passwordToUse, keyToUse);
        } catch (Exception e) {
            logger.error("Failed to open SFTP manager", e);
            showError(I18n.get("error.title"), I18n.get("error.sftpManagerFailed", e.getMessage()));
        }
    }
    
    /**
     * Requests a new temporary SSH key from the user.
     * Shows a dialog where the user can enter a new valid temporary SSH key.
     * @param connection The connection that requires a temporary SSH key
     * @return The new temporary SSH key, or null if cancelled
     */
    private de.kortty.model.TemporarySSHKey requestNewTemporarySSHKey(ServerConnection connection) {
        Dialog<de.kortty.model.TemporarySSHKey> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(I18n.get("sftp.tempKeyRequired"));
        dialog.setHeaderText(I18n.get("sftp.tempKeyRequiredMessage"));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResizable(true);
        
        // Create content
        VBox content = new VBox(10);
        content.setPadding(new javafx.geometry.Insets(20));
        content.setPrefWidth(500);
        
        Label infoLabel = new Label(I18n.get("sftp.enterNewTempKey"));
        
        TextArea keyArea = new TextArea();
        keyArea.setPromptText("-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----");
        keyArea.setPrefRowCount(8);
        keyArea.setWrapText(true);
        
        // Expiration spinner
        HBox expirationBox = new HBox(10);
        expirationBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label expirationLabel = new Label(I18n.get("quickConnect.expirationMinutes"));
        Spinner<Integer> expirationSpinner = new Spinner<>(1, 1440, 60);
        expirationSpinner.setEditable(true);
        expirationSpinner.setPrefWidth(100);
        expirationBox.getChildren().addAll(expirationLabel, expirationSpinner);
        
        content.getChildren().addAll(infoLabel, keyArea, expirationBox);
        dialog.getDialogPane().setContent(content);
        
        // Disable OK button until key is entered
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        keyArea.textProperty().addListener((obs, old, newVal) -> {
            okButton.setDisable(newVal == null || newVal.trim().isEmpty());
        });
        
        dialog.setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                String keyContent = keyArea.getText().trim();
                if (!keyContent.isEmpty()) {
                    long expirationMinutes = expirationSpinner.getValue();
                    de.kortty.model.TemporarySSHKey newKey = de.kortty.core.TemporarySSHKeyManager.getInstance()
                        .storeTemporaryKey(keyContent, expirationMinutes);
                    
                    // Update connection with new temporary key
                    connection.setTemporaryKeyContent(keyContent);
                    connection.setTemporaryKeyExpirationMinutes(expirationMinutes);
                    connection.setPrivateKeyPath("TEMPORARY:" + keyContent);
                    
                    return newKey;
                }
            }
            return null;
        });
        
        return dialog.showAndWait().orElse(null);
    }
    
    /**
     * Opens the SFTP Manager in a new tab.
     * @param connection The connection to use
     * @param password The password (or empty for temp key auth)
     * @param temporarySSHKey Optional temporary SSH key
     */
    private void openSFTPManagerTab(ServerConnection connection, String password, de.kortty.model.TemporarySSHKey temporarySSHKey) {
        // Check if SFTP tab for this connection already exists
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof SFTPManagerTab sftpTab) {
                if (sftpTab.getConnection().getId() != null && 
                    sftpTab.getConnection().getId().equals(connection.getId())) {
                    // Tab already exists - select it
                    tabPane.getSelectionModel().select(tab);
                    return;
                }
            }
        }
        
        // Get auto-close timeout from global settings (0 = disabled)
        int autoCloseMinutes = 0;
        try {
            var globalSettings = app.getGlobalSettingsManager().getSettings();
            if (globalSettings != null && globalSettings.getSftpAutoCloseMinutes() != null) {
                autoCloseMinutes = globalSettings.getSftpAutoCloseMinutes();
            }
        } catch (Exception e) {
            logger.debug("Could not get SFTP timeout setting: {}", e.getMessage());
        }
        
        // Create new SFTP tab
        SFTPManagerTab sftpTab = new SFTPManagerTab(app, connection, password, temporarySSHKey, autoCloseMinutes, this);
        
        tabPane.getTabs().add(sftpTab);
        tabPane.getSelectionModel().select(sftpTab);
        
        logger.info("Opened SFTP Manager tab for: {}", connection.getDisplayName());
    }

    
    /**
     * For teamwork connections that have no credential or SSH key set, applies the default
     * from GlobalSettings (teamwork default credential or SSH key). Returns a copy with auth
     * filled in so the original connection in the list is never modified.
     */
    private ServerConnection resolveTeamworkConnectionAuth(ServerConnection connection) {
        if (!connection.isTeamworkConnection()) {
            return connection;
        }
        if (connection.getCredentialId() != null || connection.getSshKeyId() != null) {
            return connection;
        }
        GlobalSettings gs = app.getGlobalSettingsManager().getSettings();
        String credId = gs.getTeamworkDefaultCredentialId();
        String keyId = gs.getTeamworkDefaultSshKeyId();
        if (credId != null && app.getCredentialManager() != null) {
            Optional<StoredCredential> cred = app.getCredentialManager().findCredentialById(credId);
            if (cred.isPresent()) {
                ServerConnection copy = ServerConnection.copyForAuth(connection);
                copy.setCredentialId(cred.get().getId());
                String credUsername = cred.get().getUsername();
                if (credUsername != null && !credUsername.isBlank()) {
                    copy.setUsername(credUsername.trim());
                }
                copy.setAuthMethod(AuthMethod.PASSWORD);
                copy.setSshKeyId(null);
                copy.setPrivateKeyPath(null);
                return copy;
            }
        }
        if (keyId != null && app.getSSHKeyManager() != null) {
            Optional<SSHKey> key = app.getSSHKeyManager().findKeyById(keyId);
            if (key.isPresent()) {
                ServerConnection copy = ServerConnection.copyForAuth(connection);
                copy.setSshKeyId(key.get().getId());
                copy.setAuthMethod(AuthMethod.PUBLIC_KEY);
                copy.setPrivateKeyPath(app.getSSHKeyManager().getEffectiveKeyPath(key.get()));
                copy.setCredentialId(null);
                // Optional username: if set use for all, else keep username from teamwork file
                String username = gs.getTeamworkDefaultUsername();
                if (username != null && !username.isBlank()) {
                    copy.setUsername(username.trim());
                }
                return copy;
            }
        }
        // Temporary SSH key: always return a defensive copy so the shared instance is never mutated
        if (gs.getTeamworkUseTemporaryKey()) {
            ServerConnection copy = ServerConnection.copyForAuth(connection);
            String username = gs.getTeamworkDefaultUsername();
            if (username != null && !username.isBlank()) {
                copy.setUsername(username.trim());
            }
            return copy;
        }
        return connection;
    }

    /**
     * Retrieves password for a connection, either from credential store or from encrypted password.
     * This ensures password changes in credential management are immediately reflected.
     */
    private String getConnectionPassword(ServerConnection connection) {
        // Try credential store first (if credentialId is set)
        if (connection.getCredentialId() != null) {
            try {
                java.util.Optional<de.kortty.model.StoredCredential> credential = 
                    app.getCredentialManager().findCredentialById(connection.getCredentialId());
                
                if (credential.isPresent()) {
                    String password = app.getCredentialManager().getPassword(
                        credential.get(), 
                        app.getMasterPasswordManager().getMasterPassword()
                    );
                    if (password != null) {
                        logger.debug("Using password from credential store for: {}", connection.getDisplayName());
                        return password;
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to retrieve password from credential store: {}", e.getMessage());
                // Fall back to stored password
            }
        }
        
        // Fall back to stored encrypted password in connection
        PasswordVault vault = new PasswordVault(
            app.getMasterPasswordManager().getEncryptionService(),
            app.getMasterPasswordManager().getMasterPassword()
        );
        return vault.retrievePassword(connection);
    }
    
    /**
     * Duplicates a tab with the same connection details.
     * The new tab is inserted directly to the right of the source tab.
     */
    private void duplicateTab(TerminalTab sourceTab) {
        ServerConnection connection = sourceTab.getConnection();
        
        // SSH key auth does not require a password - duplicate directly
        if (connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY) {
            createDuplicateTab(sourceTab, connection, null);
            return;
        }
        
        String password = getConnectionPassword(connection);
        
        if (password == null) {
            // Password not available, show dialog with masked input
            Dialog<String> pwDialog = new Dialog<>();
            DialogThemeHelper.applyTheme(pwDialog);
            pwDialog.setTitle(I18n.get("dialog.passwordRequired"));
            pwDialog.setHeaderText(I18n.get("dialog.passwordFor", connection.getDisplayName()));
            pwDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            PasswordField pwField = new PasswordField();
            pwField.setPromptText(I18n.get("dialog.enterPassword"));
            VBox content = new VBox(10);
            content.getChildren().addAll(new Label(I18n.get("dialog.pleaseEnterPassword")), pwField);
            content.setPadding(new javafx.geometry.Insets(20));
            pwDialog.getDialogPane().setContent(content);
            pwDialog.setResultConverter(bt -> bt == ButtonType.OK ? pwField.getText() : null);
            pwDialog.showAndWait().ifPresent(pw -> {
                if (pw != null && !pw.trim().isEmpty()) {
                    createDuplicateTab(sourceTab, connection, pw.trim());
                }
            });
        } else {
            createDuplicateTab(sourceTab, connection, password);
        }
    }
    
    /**
     * Creates a duplicated tab directly to the right of the source tab.
     */
    private void createDuplicateTab(TerminalTab sourceTab, ServerConnection connection, String password) {
        try {
            // Find the position of the source tab
            int sourceIndex = tabPane.getTabs().indexOf(sourceTab);
            if (sourceIndex == -1) {
                logger.warn("Source tab not found in tab pane");
                return;
            }
            
            // Create new tab with the same connection
            TerminalTab newTab = new TerminalTab(connection, password);
            installAiSelectionHandler(newTab);
            newTab.setTimestampToggleListener(() -> Platform.runLater(() -> {
                Tab activeTab = tabPane.getSelectionModel().getSelectedItem();
                if (activeTab instanceof TerminalTab active) {
                    syncTimestampMenuItems(active.isTimestampGuttersVisible());
                }
            }));
            newTab.setOnClosed(e -> {
                updateDashboard();
                organizeTabsByGroup();
                updateAllTabContextMenus();
            });
            
            // Setup context menu
            setupTabContextMenu(newTab);
            
            // Take over the tab group from the source tab (if present)
            String sourceGroup = sourceTab.getGroup();
            if (sourceGroup != null && !sourceGroup.trim().isEmpty()) {
                newTab.setGroup(sourceGroup);
            }
            
            // Insert the new tab directly to the right of the source tab.
            int insertIndex = sourceIndex + 1;
            
            tabPane.getTabs().add(insertIndex, newTab);
            tabPane.getSelectionModel().select(newTab);
            
            // Update dashboard und context menus
            updateDashboard();
            updateAllTabContextMenus();
            
            newTab.setOnConnectedCallback(() -> {
                newTab.updateTabTitle();
                newTab.setStyle("");
                updateStatus(I18n.get("status.connectedToWithHostAndProtocol",
                        connection.getDisplayName(), connection.getHost(), getProtocolLabel(connection.getProtocol())));
                updateDashboard();
            });

            // Verbinde den neuen Tab
            new Thread(() -> {
                try {
                    newTab.connect();
                } catch (Exception e) {
                    logger.error("Failed to connect duplicated tab", e);
                }
            }, "Duplicate-Tab-Connect").start();
            
            logger.info("Tab duplicated: {} -> {}", sourceTab.getConnection().getDisplayName(), 
                        newTab.getConnection().getDisplayName());
        } catch (Exception e) {
            logger.error("Failed to duplicate tab", e);
            showError(I18n.get("error.title"), I18n.get("error.tabDuplicateFailed", e.getMessage()));
        }
    }

    
    /**
     * Creates an encrypted backup of all settings.
     */
    private void createBackup() {
        // Show directory chooser
        javafx.stage.DirectoryChooser dirChooser = new javafx.stage.DirectoryChooser();
        dirChooser.setTitle(I18n.get("backup.selectDestination"));
        
        // Use last backup path as initial directory if available
        String lastPath = app.getGlobalSettingsManager().getSettings().getLastBackupPath();
        if (lastPath != null) {
            java.io.File lastDir = new java.io.File(lastPath);
            if (lastDir.exists() && lastDir.isDirectory()) {
                dirChooser.setInitialDirectory(lastDir);
            }
        } else {
            // Default to user home
            dirChooser.setInitialDirectory(new java.io.File(System.getProperty("user.home")));
        }
        
        java.io.File selectedDir = dirChooser.showDialog(stage);
        if (selectedDir == null) {
            return; // User cancelled
        }
        
        // Create backup in background
        javafx.concurrent.Task<java.nio.file.Path> backupTask = new javafx.concurrent.Task<>() {
            @Override
            protected java.nio.file.Path call() throws Exception {
                updateMessage(I18n.get("backup.creating"));
                return app.getBackupManager().createBackup(
                    selectedDir.toPath(),
                    app.getCredentialManager(),
                    app.getGpgKeyManager(),
                    app.getMasterPasswordManager().getMasterPassword()
                );
            }
        };
        
        backupTask.setOnSucceeded(e -> {
            java.nio.file.Path backupFile = backupTask.getValue();
            try {
                long fileSize = java.nio.file.Files.size(backupFile) / 1024; // KB
                
                // Determine encryption description
                String encryptionDesc = I18n.get("backup.encryption.unknown");
                if (app.getGlobalSettingsManager().getSettings().getBackupEncryptionType() 
                    == de.kortty.model.GlobalSettings.BackupEncryptionType.PASSWORD) {
                    encryptionDesc = I18n.get("backup.encryption.password");
                } else if (app.getGlobalSettingsManager().getSettings().getBackupEncryptionType() 
                           == de.kortty.model.GlobalSettings.BackupEncryptionType.GPG) {
                    encryptionDesc = I18n.get("backup.encryption.gpg");
                }
                
                Alert success = new Alert(Alert.AlertType.INFORMATION);
                DialogThemeHelper.applyTheme(success);
                success.setTitle(I18n.get("backup.created"));
                success.setHeaderText(I18n.get("backup.createdSuccess"));
                success.setContentText(String.format(
                    I18n.get("backup.createdMessage"),
                    backupFile.getFileName(),
                    fileSize,
                    encryptionDesc
                ));
                success.showAndWait();
                
                // Save global settings (updated by BackupManager)
                app.getGlobalSettingsManager().save();
                
                updateStatus(I18n.get("backup.createdSuccess") + ": " + backupFile.getFileName());
                
            } catch (Exception ex) {
                logger.error("Failed to get backup file size", ex);
            }
        });
        
        backupTask.setOnFailed(e -> {
            Throwable ex = backupTask.getException();
            logger.error("Backup creation failed", ex);
            
            Alert error = new Alert(Alert.AlertType.ERROR);
            DialogThemeHelper.applyTheme(error);
            error.setTitle(I18n.get("error.title"));
            error.setHeaderText(I18n.get("backup.failed"));
            error.setContentText(I18n.get("backup.failedMessage") + "\n" + ex.getMessage());
            error.showAndWait();
        });
        
        // Update status and run task
        updateStatus(I18n.get("backup.creating"));
        
        Thread thread = new Thread(backupTask);
        thread.setDaemon(true);
        thread.start();
    }
    
    /**
     * Imports a backup from an encrypted backup file.
     */
    private void importBackup() {
        // Show file chooser
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle(I18n.get("backup.import.selectFile"));
        
        // Add filters for backup files
        fileChooser.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter("Backup Files", "*.zip", "*.gpg")
        );
        fileChooser.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter("All Files", "*.*")
        );
        
        // Use last backup path as initial directory if available
        String lastPath = app.getGlobalSettingsManager().getSettings().getLastBackupPath();
        if (lastPath != null) {
            java.io.File lastDir = new java.io.File(lastPath);
            if (lastDir.exists() && lastDir.isDirectory()) {
                fileChooser.setInitialDirectory(lastDir);
            }
        } else {
            // Default to user home
            fileChooser.setInitialDirectory(new java.io.File(System.getProperty("user.home")));
        }
        
        java.io.File backupFile = fileChooser.showOpenDialog(stage);
        if (backupFile == null) {
            return; // User cancelled
        }
        
        // Check if file is GPG-encrypted or password-encrypted
        String fileName = backupFile.getName().toLowerCase();
        boolean isGPGEncrypted = fileName.endsWith(".gpg");
        final String[] password = {null}; // Use array to allow modification in lambda
        
        if (!isGPGEncrypted) {
            // Ask for password (masked input)
            Dialog<String> passwordDialog = new Dialog<>();
            DialogThemeHelper.applyTheme(passwordDialog);
            passwordDialog.setTitle(I18n.get("backup.import.password.title"));
            passwordDialog.setHeaderText(I18n.get("backup.import.password.header"));
            passwordDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            PasswordField pwField = new PasswordField();
            pwField.setPromptText(I18n.get("dialog.enterPassword"));
            VBox content = new VBox(10);
            content.getChildren().addAll(new Label(I18n.get("backup.import.password.content")), pwField);
            content.setPadding(new javafx.geometry.Insets(20));
            passwordDialog.getDialogPane().setContent(content);
            passwordDialog.setResultConverter(bt -> bt == ButtonType.OK ? pwField.getText() : null);
            java.util.Optional<String> passwordResult = passwordDialog.showAndWait();
            if (!passwordResult.isPresent() || passwordResult.get() == null || passwordResult.get().isEmpty()) {
                return; // User cancelled or entered empty password
            }
            password[0] = passwordResult.get();
        }
        
        // Ask if existing files should be overwritten
        javafx.scene.control.Alert overwriteDialog = new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.CONFIRMATION
        );
        DialogThemeHelper.applyTheme(overwriteDialog);
        overwriteDialog.setTitle(I18n.get("backup.import.overwrite.title"));
        overwriteDialog.setHeaderText(I18n.get("backup.import.overwrite.header"));
        overwriteDialog.setContentText(I18n.get("backup.import.overwrite.content"));
        
        java.util.Optional<javafx.scene.control.ButtonType> overwriteResult = overwriteDialog.showAndWait();
        final boolean overwriteExisting = overwriteResult.isPresent() && 
            overwriteResult.get() == javafx.scene.control.ButtonType.OK;
        
        // Import backup in background
        final java.nio.file.Path backupFilePath = backupFile.toPath();
        javafx.concurrent.Task<Integer> importTask = new javafx.concurrent.Task<>() {
            @Override
            protected Integer call() throws Exception {
                updateMessage(I18n.get("backup.import.importing"));
                return app.getBackupManager().importBackup(
                    backupFilePath,
                    password[0],
                    overwriteExisting
                );
            }
        };
        
        importTask.setOnSucceeded(e -> {
            int filesImported = importTask.getValue();
            
            Alert success = new Alert(Alert.AlertType.INFORMATION);
            DialogThemeHelper.applyTheme(success);
            success.setTitle(I18n.get("backup.import.success"));
            success.setHeaderText(I18n.get("backup.import.successHeader"));
            success.setContentText(String.format(
                I18n.get("backup.import.successMessage"),
                filesImported
            ));
            success.showAndWait();
            
            // Reload all managers to reflect imported data
            try {
                app.getConfigManager().load(app.getMasterPasswordManager().getDerivedKey());
                app.getCredentialManager().load();
                app.getGpgKeyManager().load();
                app.getGlobalSettingsManager().load();
            } catch (Exception ex) {
                logger.error("Failed to reload managers after backup import", ex);
            }
            
            updateStatus(I18n.get("backup.import.successHeader") + ": " + filesImported + " " + I18n.get("backup.import.files"));
        });
        
        importTask.setOnFailed(e -> {
            Throwable ex = importTask.getException();
            logger.error("Backup import failed", ex);
            
            Alert error = new Alert(Alert.AlertType.ERROR);
            DialogThemeHelper.applyTheme(error);
            error.setTitle(I18n.get("error.title"));
            error.setHeaderText(I18n.get("backup.import.failed"));
            error.setContentText(I18n.get("backup.import.failedMessage") + "\n" + ex.getMessage());
            error.showAndWait();
        });
        
        // Update status and run task
        updateStatus(I18n.get("backup.import.importing"));
        
        Thread thread = new Thread(importTask);
        thread.setDaemon(true);
        thread.start();
    }
    
    /**
     * Sets up context menu for a terminal tab to assign/change groups.
     */
    private void setupTabContextMenu(TerminalTab terminalTab) {
        ContextMenu contextMenu = new ContextMenu();
        
        terminalTab.setOnReconnectRequested(() -> {
            updateDashboard();
            updateStatus(I18n.get("status.reconnecting", terminalTab.getConnection().getDisplayName()));
        });
        
        MenuItem duplicateItem = new MenuItem("Duplizieren");
        duplicateItem.setOnAction(e -> duplicateTab(terminalTab));
        contextMenu.getItems().add(duplicateItem);
        
        MenuItem reconnectItem = new MenuItem(I18n.get("dashboard.reconnect"));
        reconnectItem.setOnAction(e -> {
            terminalTab.triggerReconnect();
        });
        contextMenu.getItems().add(reconnectItem);
        
        if (TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
            contextMenu.getItems().add(new SeparatorMenuItem());
            Menu terminalEffectMenu = createTerminalEffectMenu(terminalTab);
            terminalEffectMenu.setOnShowing(event -> rebuildTerminalEffectMenu(terminalEffectMenu, terminalTab));
            contextMenu.getItems().add(terminalEffectMenu);
        }

        contextMenu.getItems().add(new SeparatorMenuItem());
        
        // Get all available groups
        List<String> groups = getAllGroups();
        String currentGroup = terminalTab.getGroup();
        
        // Menu item to remove from group
        MenuItem removeGroupItem = new MenuItem("Keine Gruppe");
        removeGroupItem.setOnAction(e -> {
            terminalTab.setGroup(null);
            organizeTabsByGroup();
            updateAllTabContextMenus(); // Update all context menus to reflect changes
            updateDashboard(); // Refresh dashboard to show group changes
        });
        if (currentGroup == null || currentGroup.trim().isEmpty()) {
            removeGroupItem.setDisable(true);
        }
        contextMenu.getItems().add(removeGroupItem);
        
        // Separator
        contextMenu.getItems().add(new SeparatorMenuItem());
        
        // Menu items for each group
        if (!groups.isEmpty()) {
            for (String group : groups) {
                MenuItem groupItem = new MenuItem(group);
                groupItem.setOnAction(e -> {
                    terminalTab.setGroup(group);
                    organizeTabsByGroup();
                    updateAllTabContextMenus(); // Update all context menus to reflect changes
                    updateDashboard(); // Refresh dashboard to show group changes
                });
                if (group.equals(currentGroup)) {
                    groupItem.setDisable(true);
                }
                contextMenu.getItems().add(groupItem);
            }
            contextMenu.getItems().add(new SeparatorMenuItem());
        }
        
        // Menu item to create new group
        MenuItem newGroupItem = new MenuItem(I18n.get("group.new"));
        newGroupItem.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog();
            DialogThemeHelper.applyTheme(dialog);
            dialog.setTitle(I18n.get("group.new"));
            dialog.setHeaderText(I18n.get("group.enterName"));
            dialog.setContentText(I18n.get("group.name") + ":");
            dialog.getEditor().setPromptText(I18n.get("group.nameExample"));
            
            dialog.showAndWait().ifPresent(groupName -> {
                if (groupName != null && !groupName.trim().isEmpty()) {
                    String trimmedName = groupName.trim();
                    terminalTab.setGroup(trimmedName);
                    organizeTabsByGroup();
                    updateAllTabContextMenus(); // Update all context menus to reflect new group
                    updateDashboard(); // Refresh dashboard to show new group
                }
            });
        });
        contextMenu.getItems().add(newGroupItem);
        
        // Menu item to rename current group (if tab has a group)
        if (currentGroup != null && !currentGroup.trim().isEmpty()) {
            contextMenu.getItems().add(new SeparatorMenuItem());
            MenuItem renameGroupItem = new MenuItem(I18n.get("group.rename"));
            renameGroupItem.setOnAction(e -> {
                TextInputDialog dialog = new TextInputDialog(currentGroup);
                DialogThemeHelper.applyTheme(dialog);
                dialog.setTitle(I18n.get("group.rename"));
                dialog.setHeaderText(I18n.get("group.enterNewName"));
                dialog.setContentText(I18n.get("group.newName") + ":");
                
                dialog.showAndWait().ifPresent(newGroupName -> {
                    if (newGroupName != null && !newGroupName.trim().isEmpty()) {
                        String trimmedName = newGroupName.trim();
                        if (!trimmedName.equals(currentGroup)) {
                            renameGroupForAllTabs(currentGroup, trimmedName);
                            organizeTabsByGroup();
                            updateAllTabContextMenus(); // Update all context menus
                            updateDashboard(); // Refresh dashboard to show renamed group
                        }
                    }
                });
            });
            contextMenu.getItems().add(renameGroupItem);
        }
        
        terminalTab.setContextMenu(contextMenu);
    }
    
    /**
     * Gets all unique group names from open tabs (not from connections).
     */
    private List<String> getAllGroups() {
        List<String> groups = new ArrayList<>();
        // Get groups from open tabs only (tab groups, not connection groups)
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                String group = terminalTab.getGroup();
                if (group != null && !group.trim().isEmpty() && !groups.contains(group)) {
                    groups.add(group);
                }
            }
        }
        groups.sort(String::compareToIgnoreCase);
        return groups;
    }
    
    /**
     * Renames a group for all tabs that have this group.
     */
    private void renameGroupForAllTabs(String oldGroupName, String newGroupName) {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                String group = terminalTab.getGroup();
                if (oldGroupName.equals(group)) {
                    terminalTab.setGroup(newGroupName);
                }
            }
        }
    }
    
    /**
     * Updates context menus for all tabs to reflect current group state.
     */
    private void updateAllTabContextMenus() {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                setupTabContextMenu(terminalTab);
            }
        }
    }
    
    /**
     * Inserts a tab in the correct position based on group ordering.
     * Tabs are organized: no group first, then grouped tabs alphabetically by group name.
     */
    private void insertTabInGroupOrder(TerminalTab newTab) {
        String newTabGroup = newTab.getGroup();
        if (newTabGroup == null || newTabGroup.trim().isEmpty()) {
            newTabGroup = null;
        }
        
        // Find insertion point
        int insertIndex = tabPane.getTabs().size();
        for (int i = 0; i < tabPane.getTabs().size(); i++) {
            Tab tab = tabPane.getTabs().get(i);
            if (tab instanceof TerminalTab terminalTab) {
                String tabGroup = terminalTab.getGroup();
                if (tabGroup == null || tabGroup.trim().isEmpty()) {
                    tabGroup = null;
                }
                
                // Compare groups
                if (newTabGroup == null) {
                    // New tab has no group - insert before first grouped tab
                    if (tabGroup != null) {
                        insertIndex = i;
                        break;
                    }
                } else {
                    // New tab has group - insert after last tab with same group or before first tab with larger group
                    if (tabGroup != null && tabGroup.compareToIgnoreCase(newTabGroup) > 0) {
                        insertIndex = i;
                        break;
                    } else if (tabGroup != null && tabGroup.equals(newTabGroup)) {
                        // Continue until we find the end of this group
                        insertIndex = i + 1;
                    }
                }
            }
        }
        
        tabPane.getTabs().add(insertIndex, newTab);
    }
    
    /**
     * Reorganizes all tabs by group order.
     * Tabs without group come first, then grouped tabs sorted alphabetically by group name.
     */
    private void organizeTabsByGroup() {
        // Get all terminal tabs.
        List<TerminalTab> terminalTabs = new ArrayList<>();
        List<Tab> preservedTabs = new ArrayList<>();
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                terminalTabs.add(terminalTab);
            } else {
                preservedTabs.add(tab);
            }
        }
        
        // Sort tabs: no group first, then by group name alphabetically
        terminalTabs.sort((t1, t2) -> {
            String g1 = t1.getGroup();
            String g2 = t2.getGroup();
            
            if (g1 == null || g1.trim().isEmpty()) {
                g1 = null;
            }
            if (g2 == null || g2.trim().isEmpty()) {
                g2 = null;
            }
            
            if (g1 == null && g2 == null) {
                return 0; // Keep original order for tabs without group
            }
            if (g1 == null) {
                return -1; // No group comes first
            }
            if (g2 == null) {
                return 1; // No group comes first
            }
            
            return g1.compareToIgnoreCase(g2);
        });
        
        // Clear all tabs
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        tabPane.getTabs().clear();
        
        // Re-add sorted tabs
        for (TerminalTab tab : terminalTabs) {
            tabPane.getTabs().add(tab);
            setupTabContextMenu(tab); // Re-setup context menu
        }

        tabPane.getTabs().addAll(preservedTabs);
        
        // Restore selection
        if (selectedTab != null) {
            tabPane.getSelectionModel().select(selectedTab);
        }
    }

}
