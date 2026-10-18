package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AtomicFileWriter;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.RemotePathSupport;
import de.kortty.core.SFTPSession;
import de.kortty.core.SftpFileTransferService;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.core.SnippetManager;
import de.kortty.core.remote.RemoteArchiveCommands;
import de.kortty.core.remote.RemoteCommandRunner;
import de.kortty.core.sftp.FolderComparison;
import de.kortty.core.sftp.FolderSnapshots;
import de.kortty.core.sftp.RemoteAccountNames;
import de.kortty.core.sftp.RemoteFolderSizes;
import de.kortty.core.remote.RemoteCommandCancellation;
import de.kortty.core.remote.RemoteCommandCancelledException;
import de.kortty.core.remote.extract.ArchiveExtractException;
import de.kortty.core.remote.extract.RemoteArchiveExtractor;
import de.kortty.core.remote.search.RemoteSearchHit;
import de.kortty.core.remote.search.RemoteSearchOutcome;
import de.kortty.core.remote.search.RemoteSearchRequest;
import de.kortty.core.remote.search.RemoteSearchService;
import de.kortty.core.remote.search.RemoteTreeReader;
import de.kortty.core.remote.RemoteShell;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionState;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import de.kortty.model.SnippetDiagram;
import de.kortty.model.TemporarySSHKey;
import de.kortty.ui.sftp.RemoteExtractMessages;
import de.kortty.ui.sftp.RemoteSearchResultsPane;
import de.kortty.ui.sftp.SftpDragOutPolicy;
import de.kortty.ui.sftp.SftpEditGate;
import de.kortty.ui.sftp.SftpRemoteEdits;
import de.kortty.ui.sftp.SftpDragPayload;
import de.kortty.ui.sftp.SftpFileItem;
import de.kortty.ui.sftp.SftpFileItemComparators;
import de.kortty.ui.sftp.SftpTabKeys;
import de.kortty.ui.sftp.FxConflictResolver;
import de.kortty.ui.sftp.LocalHostName;
import de.kortty.ui.sftp.PermissionBitsEditor;
import de.kortty.ui.sftp.SftpBreadcrumbBar;
import de.kortty.ui.sftp.SftpComparePair;
import de.kortty.ui.sftp.SftpTextContent;
import de.kortty.ui.sftp.SftpNewFilePlacement;
import de.kortty.ui.sftp.SftpConflictDialog;
import de.kortty.ui.sftp.SftpTransferQueueHost;
import de.kortty.ui.sftp.SftpTransferQueuePane;
import de.kortty.ui.sftp.SftpTransferRowModel;
import de.kortty.core.sftp.transfer.PartFiles;
import de.kortty.core.sftp.transfer.RemoteEntryRef;
import de.kortty.core.sftp.transfer.TransferBatch;
import de.kortty.core.sftp.transfer.TransferCancellation;
import de.kortty.core.sftp.transfer.TransferDirection;
import de.kortty.core.sftp.transfer.TransferItem;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.transformation.FilteredList;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SFTP Manager as a Tab for file transfers between local and remote systems.
 * Can be embedded in the main window's TabPane instead of opening as a modal dialog.
 *
 * <p>While transfers run, every way of closing the tab asks first ({@link HostedCloseGuard}): its
 * close button, Close Tab, Close All Tabs, closing the window and quitting.
 */
public class SFTPManagerTab extends Tab implements HostedCloseGuard {
    
    private static final Logger logger = LoggerFactory.getLogger(SFTPManagerTab.class);
    
    private final KorTTYApplication app;
    private final MainWindow ownerWindow;
    private final ServerConnection connection;
    private final String password;
    private final TemporarySSHKey temporarySSHKey;
    /**
     * The current session; replaced on reconnect. Worker threads take a reference once and keep
     * using it, so a reconnect never swaps the session under a running transfer.
     */
    private volatile SFTPSession sftpSession;
    /** Where the remote side stands; read and written on the FX thread only. */
    private RemoteState remoteState = RemoteState.CONNECTING;
    /** Set by {@link #cleanup()}: a closing tab never shows "Disconnected" or reconnects. */
    private volatile boolean closing;
    /** Lists remote folders off the FX thread, so a folder with many entries never freezes the UI. */
    private final ExecutorService remoteListExecutor;
    /** Bumped for every remote listing request (FX thread); only the newest result is applied. */
    private long remoteListGeneration;
    /** Runs recursive remote searches (find or an SFTP walk) off the FX thread. */
    private final ExecutorService remoteSearchExecutor;
    /** Bumped for every recursive search and when one is left (FX thread); stale batches are dropped. */
    private long remoteSearchGeneration;
    /** The cancel switch of the running recursive search, or null when none runs (FX thread). */
    private RemoteCommandCancellation remoteSearchCancellation;
    /** Looks up owner and group names off the FX thread, one lookup at a time. */
    private final ExecutorService remoteInfoExecutor;
    /** The names behind the numeric owners and groups of this session; replaced on every connect. */
    private volatile RemoteAccountNames accountNames = new RemoteAccountNames();
    /** Sizes "Calculate size" measured for folders in the folder shown, by path; cleared by every listing (FX thread). */
    private final java.util.Map<String, Long> remoteFolderSizes = new java.util.HashMap<>();
    /** The running "Calculate size", or null; a second click stops it (FX thread). */
    private RemoteCommandCancellation folderSizeCancellation;
    /** The running "Extract Here" job, if any; FX thread. */
    private RemoteCommandCancellation remoteExtractCancellation;
    private RemoteSearchResultsPane remoteSearchResults;
    private CheckBox remoteRecursiveToggle;
    private CheckBox remoteSameFilesystemToggle;
    /** A search hit to select once its folder was listed (FX thread). */
    private String pendingRemoteSelection;
    /** Whether {@link #currentRemotePath} is an absolute path a listing returned; SFTP does not expand '~'. */
    private boolean remotePathResolved;
    /**
     * The remote folder a restored tab starts in, until its first listing was applied (FX thread);
     * if that listing fails, the tab falls back to the login directory once.
     */
    private String restoredRemotePath;
    private Node remoteLoadingOverlay;
    private Button reconnectButton;
    private Runnable localSelectionActions = () -> { };
    private Runnable remoteSelectionActions = () -> { };
    /** Names this tab in the drag payload of its remote rows; only this tab acts on those entries. */
    private final String dragSourceId = UUID.randomUUID().toString();
    /**
     * Temporary folders with remote files prepared for a drop outside the window (FX thread). Kept
     * until the next drag or until the tab closes, because the drop target may still be copying.
     */
    private final List<Path> dragOutDirectories = new ArrayList<>();

    /**
     * Borrows a terminal pane's SSH session again ({@link SFTPSession#attach}); called off the FX
     * thread. Throws when the pane's session is gone, no longer matches the pane, or refuses SFTP.
     */
    @FunctionalInterface
    public interface SessionFactory {
        SFTPSession open() throws Exception;
    }

    /** Non-null for a tab on a terminal pane's session: Connect and Reconnect go through it. */
    private final SessionFactory borrowedSessions;
    /** The session a borrowed tab was opened with, until its first connect took it (FX thread). */
    private SFTPSession pendingAttached;
    /** {@link SftpTabKeys}: which tab an "open" request reuses; null when never reused. */
    private final String dedupeKey;
    /** Shown once after the first connect: why the tab starts in the login folder; or null. */
    private String startHint;
    /** Opens a standalone tab with its own login when the pane's session is gone; or null. */
    private final Runnable ownLogin;
    private Button ownLoginButton;

    private enum RemoteState { CONNECTING, CONNECTED, DISCONNECTED }

    /** A remote folder listed off the FX thread: its absolute path and its entries. */
    private record RemoteListing(String path, List<SftpFileItem> items) { }

    /** Shows or hides the hidden entries of the focused panel, as in the macOS Finder. */
    static final KeyCombination TOGGLE_HIDDEN_KEYS =
        new KeyCodeCombination(KeyCode.PERIOD, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    /** Switches a panel's path to the text field, as in a web browser's address bar. */
    static final KeyCombination EDIT_PATH_KEYS = new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN);
    /** Marks the panel the user works in (last clicked or focused); see filebrowser.css. */
    private static final PseudoClass ACTIVE_PANE = PseudoClass.getPseudoClass("active-pane");

    /** Whether each panel lists hidden entries; its eye button and {@link #TOGGLE_HIDDEN_KEYS} switch it (FX thread). */
    private boolean showHiddenLocal = true;
    private boolean showHiddenRemote = true;
    private ToggleButton localHiddenToggle;
    private ToggleButton remoteHiddenToggle;
    /** The two panels, framed in the accent colour while active; the toolbar under each acts on it. */
    private VBox localPanel;
    private VBox remotePanel;
    /** The folder comparison both lists show, or null (FX thread). */
    private FolderComparison comparison;
    /** The two folders {@link #comparison} compared, and the patterns it left out. */
    private Path comparedLocalRoot;
    private String comparedRemoteRoot;
    private List<String> compareExcludes = List.of();
    /** Stops the comparison that is reading the folders; null while none runs (FX thread). */
    private java.util.concurrent.atomic.AtomicBoolean compareCancel;
    /** The bar above the lists: a running comparison, or its result with the Synchronize buttons. */
    private HBox compareBar;
    private Label compareTitle;
    private HBox compareChips;
    private CheckBox compareDifferencesOnly;
    private Button compareButton;
    private Button syncToServerButton;
    private Button syncToLocalButton;
    private Button recompareButton;
    private Button stopCompareButton;
    /** Transfer batches a synchronization started; the folders are compared again once all are done. */
    private final java.util.Set<Long> syncBatchIds = new java.util.HashSet<>();
    /** Deletions a synchronization runs; the comparison is repeated after them too. */
    private int syncDeletesRunning;
    private static final PseudoClass CMP_ONLY_HERE = PseudoClass.getPseudoClass("cmp-only-here");
    private static final PseudoClass CMP_NEWER_HERE = PseudoClass.getPseudoClass("cmp-newer-here");
    private static final PseudoClass CMP_OLDER_HERE = PseudoClass.getPseudoClass("cmp-older-here");
    private static final PseudoClass CMP_DIFFERENT = PseudoClass.getPseudoClass("cmp-different");
    private static final PseudoClass CMP_SAME = PseudoClass.getPseudoClass("cmp-same");
    /** Whether the server panel is the active one; decides which selection "Compare" uses (FX thread). */
    private boolean remotePaneActive;
    /** Each panel's path as clickable folders over its path field. */
    private SftpBreadcrumbBar localBreadcrumb;
    private SftpBreadcrumbBar remoteBreadcrumb;
    /** Each panel's line under its list: entries, selection, hidden entries (FX thread). */
    private Label localPaneStatus;
    private Label remotePaneStatus;

    private TableView<SftpFileItem> localTable;
    private TableView<SftpFileItem> remoteTable;
    private TextField localPathField;
    private TextField remotePathField;
    private TextField localSearchField;
    private TextField remoteSearchField;
    private Label statusLabel;
    
    private Path currentLocalPath;
    private String currentRemotePath;
    
    private ObservableList<SftpFileItem> localItems;
    private ObservableList<SftpFileItem> remoteItems;
    private FilteredList<SftpFileItem> filteredLocalItems;
    private FilteredList<SftpFileItem> filteredRemoteItems;
    private javafx.collections.transformation.SortedList<SftpFileItem> sortedLocalItems;
    private javafx.collections.transformation.SortedList<SftpFileItem> sortedRemoteItems;
    
    // Auto-close timeout
    private Timeline autoCloseTimer;
    private int remainingSeconds;
    private Label timeoutLabel;
    private ProgressBar statusProgressBar;
    private Runnable onCloseCallback;

    /** The transfer list at the bottom of the tab and the queue behind it (FX thread). */
    private SftpTransferQueuePane transferQueuePane;
    /** Files opened in an external editor, with their auto-upload ("Remote edits"); FX thread. */
    private SftpRemoteEdits remoteEdits;
    private SftpTransferQueueHost transferQueueHost;
    /** The drag-out download that may still run on its worker; FX thread. */
    private TransferCancellation dragOutCancel;
    /** Folders to list again shortly after transfers into them finished (FX thread). */
    private boolean transferRefreshLocal;
    private boolean transferRefreshRemote;
    private javafx.animation.PauseTransition transferRefreshDelay;
    
    public SFTPManagerTab(KorTTYApplication app, ServerConnection connection, String password) {
        this(app, connection, password, null, 0, null);
    }
    
    public SFTPManagerTab(KorTTYApplication app, ServerConnection connection, String password, TemporarySSHKey temporarySSHKey) {
        this(app, connection, password, temporarySSHKey, 0, null);
    }
    
    public SFTPManagerTab(KorTTYApplication app, ServerConnection connection, String password, TemporarySSHKey temporarySSHKey, int autoCloseTimeoutMinutes) {
        this(app, connection, password, temporarySSHKey, autoCloseTimeoutMinutes, null);
    }

    public SFTPManagerTab(
            KorTTYApplication app,
            ServerConnection connection,
            String password,
            TemporarySSHKey temporarySSHKey,
            int autoCloseTimeoutMinutes,
            MainWindow ownerWindow) {
        this(app, connection, password, temporarySSHKey, autoCloseTimeoutMinutes, ownerWindow, null, null);
    }

    /**
     * A tab that starts in the folders a saved project recorded. The local folder is used while it
     * still exists, otherwise the home folder. The remote folder is listed once connected; if it
     * no longer exists, the tab shows the login directory instead and says so in the status bar.
     *
     * @param initialLocalPath the saved local folder, or {@code null} for the home folder
     * @param initialRemotePath the saved remote folder, or {@code null} for the login directory
     */
    public SFTPManagerTab(
            KorTTYApplication app,
            ServerConnection connection,
            String password,
            TemporarySSHKey temporarySSHKey,
            int autoCloseTimeoutMinutes,
            MainWindow ownerWindow,
            String initialLocalPath,
            String initialRemotePath) {
        this(app, connection, password, temporarySSHKey, autoCloseTimeoutMinutes, ownerWindow,
            initialLocalPath, initialRemotePath, null, null, SftpTabKeys.standalone(connection), null, null);
    }

    /**
     * Opens SFTP on a terminal pane's SSH session instead of logging in again (D7). The tab starts
     * with {@code attached}, a session already {@linkplain SFTPSession#attach attached} off the FX
     * thread; Reconnect asks {@code reattach} for a new one, which borrows the pane's session again.
     * When that fails (the pane closed, or now runs as another user or host), the tab offers a
     * separate login through {@code ownLogin}. The tab never closes the terminal's session.
     *
     * @param paneConnection the connection the pane runs (its own, not necessarily the tab's)
     * @param attached       the session attached to the pane, owned by this tab from now on
     * @param reattach       borrows the pane's session again, for Reconnect
     * @param dedupeKey      {@link SftpTabKeys#borrowed}, one tab per pane (D9)
     * @param startPath      the folder to start in, or null for the login folder
     * @param startHint      a status hint on why the tab starts where it does, or null
     * @param ownLogin       opens a standalone SFTP tab for {@code paneConnection} with its own login
     */
    public SFTPManagerTab(
            KorTTYApplication app,
            ServerConnection paneConnection,
            SFTPSession attached,
            SessionFactory reattach,
            String dedupeKey,
            String startPath,
            String startHint,
            Runnable ownLogin,
            int autoCloseTimeoutMinutes,
            MainWindow ownerWindow) {
        this(app, paneConnection, null, null, autoCloseTimeoutMinutes, ownerWindow, null, startPath,
            java.util.Objects.requireNonNull(reattach, "reattach"),
            java.util.Objects.requireNonNull(attached, "attached"), dedupeKey, startHint, ownLogin);
    }

    private SFTPManagerTab(
            KorTTYApplication app,
            ServerConnection connection,
            String password,
            TemporarySSHKey temporarySSHKey,
            int autoCloseTimeoutMinutes,
            MainWindow ownerWindow,
            String initialLocalPath,
            String initialRemotePath,
            SessionFactory borrowedSessions,
            SFTPSession attached,
            String dedupeKey,
            String startHint,
            Runnable ownLogin) {
        this.app = app;
        this.ownerWindow = ownerWindow;
        this.connection = connection;
        this.password = password;
        this.temporarySSHKey = temporarySSHKey;
        this.borrowedSessions = borrowedSessions;
        this.pendingAttached = attached;
        this.dedupeKey = dedupeKey;
        this.startHint = startHint;
        this.ownLogin = ownLogin;
        String listThreadName = "SFTP-List-" + connection.getHost();
        this.remoteListExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, listThreadName);
            thread.setDaemon(true);
            return thread;
        });
        String infoThreadName = "SFTP-Info-" + connection.getHost();
        this.remoteInfoExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, infoThreadName);
            thread.setDaemon(true);
            return thread;
        });
        String searchThreadName = "SFTP-Search-" + connection.getHost();
        this.remoteSearchExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, searchThreadName);
            thread.setDaemon(true);
            return thread;
        });

        setText("SFTP: " + connection.getDisplayName());
        setClosable(true);
        if (isBorrowed()) {
            setTooltip(new Tooltip(I18n.get("sftp.borrowed.tooltip")));
        }
        
        // Each panel remembers whether it lists hidden entries (default: it does).
        GlobalSettings settings = globalSettingsOrNull();
        if (settings != null) {
            showHiddenLocal = settings.isSftpShowHiddenLocal();
            showHiddenRemote = settings.isSftpShowHiddenRemote();
        }
        // The archive dialog names this computer; the lookup can be slow, so it starts now.
        LocalHostName.prefetch();

        // Initialize paths: the saved folders of a restored tab, otherwise the home folders.
        currentLocalPath = SftpSessionRestoreSupport.initialLocalPath(
            initialLocalPath, Paths.get(System.getProperty("user.home")));
        currentRemotePath = SftpSessionRestoreSupport.initialRemotePath(initialRemotePath);
        restoredRemotePath = SftpSessionRestoreSupport.REMOTE_HOME.equals(currentRemotePath)
            ? null
            : currentRemotePath;

        // Create UI
        VBox content = createContent();
        setContent(content);
        
        // Handle tab close: running transfers ask first; a veto keeps the tab open.
        setOnCloseRequest(event -> {
            if (!confirmHostedClose()) {
                event.consume();
                return;
            }
            cleanup();
            if (onCloseCallback != null) {
                onCloseCallback.run();
            }
        });
        
        // Setup auto-close timeout if enabled
        if (autoCloseTimeoutMinutes > 0) {
            setupAutoCloseTimeout(autoCloseTimeoutMinutes);
        }
        
        // Connect to SFTP
        connectToSFTP();
    }
    
    /**
     * Sets a callback to be called when the tab is closed.
     */
    public void setOnCloseCallback(Runnable callback) {
        this.onCloseCallback = callback;
    }
    
    /**
     * Sets up the auto-close timeout feature.
     */
    private void setupAutoCloseTimeout(int minutes) {
        remainingSeconds = minutes * 60;
        
        autoCloseTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (transferQueueHost != null && transferQueueHost.needsCloseConfirmation()) {
                // Running transfers count as activity: the tab never closes itself under them.
                resetAutoCloseTimer();
                return;
            }
            remainingSeconds--;
            updateTimeoutLabel();
            
            if (remainingSeconds <= 0) {
                autoCloseTimer.stop();
                Platform.runLater(() -> {
                    cleanup();
                    if (getTabPane() != null) {
                        removeTabSafely();
                    }
                    if (onCloseCallback != null) {
                        onCloseCallback.run();
                    }
                });
            }
        }));
        autoCloseTimer.setCycleCount(Timeline.INDEFINITE);
        autoCloseTimer.play();
    }
    
    private void updateTimeoutLabel() {
        if (timeoutLabel != null) {
            int mins = remainingSeconds / 60;
            int secs = remainingSeconds % 60;
            Platform.runLater(() -> {
                timeoutLabel.setText(I18n.get("sftp.autoClose.countdown", String.format("%d:%02d", mins, secs)));
                if (remainingSeconds <= 60) {
                    timeoutLabel.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
                }
            });
        }
    }
    
    /**
     * Resets the auto-close timer (e.g., when user interacts with the tab).
     */
    public void resetAutoCloseTimer() {
        if (autoCloseTimer != null) {
            // Get original timeout from settings
            int timeoutMinutes = 0;
            try {
                var globalSettings = app.getGlobalSettingsManager().getSettings();
                if (globalSettings != null && globalSettings.getSftpAutoCloseMinutes() != null) {
                    timeoutMinutes = globalSettings.getSftpAutoCloseMinutes();
                }
            } catch (Exception e) {
                logger.debug("Could not get SFTP timeout setting: {}", e.getMessage());
            }
            
            if (timeoutMinutes > 0) {
                remainingSeconds = timeoutMinutes * 60;
                updateTimeoutLabel();
            }
        }
    }
    
    private VBox createContent() {
        VBox mainBox = new VBox(10);
        mainBox.setPadding(new Insets(10));
        mainBox.getStyleClass().add("file-browser-panel");
        java.net.URL fileBrowserStyles = SFTPManagerTab.class.getResource("/styles/filebrowser.css");
        if (fileBrowserStyles != null) {
            mainBox.getStylesheets().add(fileBrowserStyles.toExternalForm());
        }

        // Status bar with timeout indicator and progress bar
        HBox statusBox = new HBox(10);
        statusBox.setAlignment(Pos.CENTER_LEFT);
        
        statusLabel = new Label(I18n.get("sftp.connecting"));
        statusLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");

        // Shown only while the connection is down ("Disconnected from host").
        reconnectButton = new Button(I18n.get("sftp.reconnect"));
        styleToolbarButton(reconnectButton, FileBrowserIcons.REFRESH);
        reconnectButton.setVisible(false);
        reconnectButton.setManaged(false);
        reconnectButton.setOnAction(e -> {
            resetAutoCloseTimer();
            reconnect();
        });

        // Progress bar for archive/copy operations (initially hidden)
        statusProgressBar = new ProgressBar(0);
        statusProgressBar.setPrefWidth(150);
        statusProgressBar.setVisible(false);
        statusProgressBar.setMaxHeight(15);
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        timeoutLabel = new Label("");
        timeoutLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        
        // A borrowed tab whose terminal session is gone can log in on its own instead.
        ownLoginButton = new Button(I18n.get("sftp.borrowed.ownLogin"));
        styleToolbarButton(ownLoginButton, FileBrowserIcons.REFRESH);
        ownLoginButton.setVisible(false);
        ownLoginButton.setManaged(false);
        ownLoginButton.setOnAction(e -> switchToOwnLogin());

        statusBox.getChildren().addAll(statusLabel, reconnectButton, ownLoginButton, statusProgressBar, spacer, timeoutLabel);
        
        // Split pane for local and remote
        SplitPane splitPane = new SplitPane();
        splitPane.setDividerPositions(0.5);
        
        localPanel = createLocalPanel();
        remotePanel = createRemotePanel();
        splitPane.getItems().addAll(localPanel, remotePanel);

        // Each panel's buttons sit right under its own list, so it is plain which side they act on.
        createPaneToolbars();
        installActivePaneTracking();
        setActivePane(false);
        localBreadcrumb.setAfterEditing(localTable::requestFocus);
        remoteBreadcrumb.setAfterEditing(remoteTable::requestFocus);
        
        // Transfer list: appears with the first upload or download, collapsible to its header.
        createTransferQueue();
        createRemoteEdits();

        compareBar = createCompareBar();
        mainBox.getChildren().addAll(compareBar, splitPane, transferQueuePane, remoteEdits, statusBox);
        VBox.setVgrow(splitPane, Priority.ALWAYS);
        
        return mainBox;
    }
    
    /** Icon tint shared with the local file browser (matches its resolveIconColor). */
    static String fileBrowserIconColor() {
        return AppDesignStyleSupport.isCustomAppDesignActive()
            ? AppDesignStyleSupport.activeTextColor()
            : "#abb2bf";
    }

    /** Applies a shared flat toolbar glyph + style class to an SFTP toolbar button. */
    private static void styleToolbarButton(javafx.scene.control.ButtonBase button, String glyph) {
        FileBrowserIcons.applyToolbarIcon(button, glyph, fileBrowserIconColor());
        button.getStyleClass().add("file-browser-toolbar-button");
        button.setGraphicTextGap(6);
    }

    /**
     * Renders the Type column as a shared file-browser type glyph instead of the emoji from
     * {@link SftpFileItem#getType()}. Sorting is unaffected (it keys off isFile()+name, not this cell).
     */
    static void installTypeIconCell(TableColumn<SftpFileItem, String> column) {
        column.setCellFactory(col -> new javafx.scene.control.TableCell<SftpFileItem, String>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                SftpFileItem item = getTableRow() == null ? null : getTableRow().getItem();
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                String name = item.getName() != null ? item.getName() : "";
                FileBrowserIcons.IconKind kind = FileBrowserIcons.kindFor(name, !item.isFile(), false, false);
                setGraphic(FileBrowserIcons.treeIcon(kind, fileBrowserIconColor(), name.startsWith("."), false, "#21252b"));
                setText(null);
                setAlignment(Pos.CENTER);
            }
        });
    }

    /**
     * Builds the button row under each list and appends it to its panel. Every button acts on the
     * panel it sits in; the two transfer buttons face each other across the divider: Upload at the
     * right end of the local row, Download at the left end of the server row.
     */
    private void createPaneToolbars() {
        // === LOCAL buttons ===
        Button refreshLocalButton = new Button(I18n.get("sftp.refresh"));
        styleToolbarButton(refreshLocalButton, FileBrowserIcons.REFRESH);
        refreshLocalButton.setTooltip(new Tooltip(I18n.get("sftp.refreshLocal")));
        refreshLocalButton.setOnAction(e -> {
            resetAutoCloseTimer();
            refreshLocal();
        });

        Button newFolderLocalButton = new Button();
        styleToolbarButton(newFolderLocalButton, FileBrowserIcons.NEW_FOLDER);
        newFolderLocalButton.setTooltip(new Tooltip(I18n.get("filebrowser.tooltip.newFolder")));
        newFolderLocalButton.setOnAction(e -> {
            resetAutoCloseTimer();
            createLocalFolder();
        });

        Button deleteLocalButton = new Button(I18n.get("sftp.delete"));
        styleToolbarButton(deleteLocalButton, FileBrowserIcons.DELETE);
        deleteLocalButton.setTooltip(new Tooltip(I18n.get("sftp.contextMenu.delete")));
        deleteLocalButton.setDisable(true);
        deleteLocalButton.setOnAction(e -> {
            resetAutoCloseTimer();
            deleteLocalSelected();
        });

        Button ownerLocalButton = new Button(I18n.get("sftp.permissionsShort"));
        styleToolbarButton(ownerLocalButton, FileBrowserIcons.LOCK);
        ownerLocalButton.setTooltip(new Tooltip(I18n.get("sftp.contextMenu.setOwner")));
        ownerLocalButton.setDisable(true);
        ownerLocalButton.setOnAction(e -> {
            resetAutoCloseTimer();
            setLocalOwnerPermissionsDialog();
        });

        Button archiveLocalButton = new Button(I18n.get("sftp.archive"));
        styleToolbarButton(archiveLocalButton, FileBrowserIcons.ARCHIVE_BOX);
        archiveLocalButton.setTooltip(new Tooltip(I18n.get("sftp.contextMenu.archive")));
        archiveLocalButton.setDisable(true);
        archiveLocalButton.setOnAction(e -> {
            resetAutoCloseTimer();
            createLocalArchive();
        });

        MenuButton editLocalButton = new MenuButton(I18n.get("sftp.edit"));
        editLocalButton.getStyleClass().add("file-browser-toolbar-button");
        editLocalButton.setTooltip(new Tooltip(I18n.get("sftp.edit.snippetEditor")));
        editLocalButton.setDisable(true);
        MenuItem editLocalSnippetItem = new MenuItem(I18n.get("sftp.edit.snippetEditor"));
        editLocalSnippetItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openSelectedLocalFileInSnippetEditor();
        });
        editLocalButton.getItems().add(editLocalSnippetItem);

        Button uploadButton = new Button(I18n.get("sftp.upload"));
        styleToolbarButton(uploadButton, FileBrowserIcons.UPLOAD);
        uploadButton.getStyleClass().add("sftp-transfer-button");
        uploadButton.setTooltip(new Tooltip(transferRefusal(de.kortty.policy.FileTransferGate.Route.SFTP_UPLOAD)
            .orElse(I18n.get("sftp.uploading", "..."))));
        uploadButton.setDisable(true);
        uploadButton.setOnAction(e -> {
            resetAutoCloseTimer();
            uploadSelected();
        });

        // Compare sits with the transfer buttons where the panels meet: it concerns both of them.
        compareButton = new Button(I18n.get("sftp.compare"));
        styleToolbarButton(compareButton, FileBrowserIcons.COMPARE);
        compareButton.setTooltip(new Tooltip(I18n.get("sftp.compare.tooltip")));
        compareButton.setOnAction(e -> {
            resetAutoCloseTimer();
            compareSelection();
        });

        Region localSpacer = new Region();
        HBox.setHgrow(localSpacer, Priority.ALWAYS);
        HBox localToolbar = paneToolbar(refreshLocalButton, newFolderLocalButton, deleteLocalButton,
            ownerLocalButton, archiveLocalButton, editLocalButton, localSpacer, compareButton, uploadButton);

        // === REMOTE buttons ===
        Button downloadButton = new Button(I18n.get("sftp.download"));
        styleToolbarButton(downloadButton, FileBrowserIcons.DOWNLOAD);
        downloadButton.getStyleClass().add("sftp-transfer-button");
        downloadButton.setTooltip(new Tooltip(transferRefusal(de.kortty.policy.FileTransferGate.Route.SFTP_DOWNLOAD)
            .orElse(I18n.get("sftp.downloading", "..."))));
        downloadButton.setDisable(true);
        downloadButton.setOnAction(e -> {
            resetAutoCloseTimer();
            downloadSelected();
        });

        Separator transferSeparator = new Separator(javafx.geometry.Orientation.VERTICAL);
        transferSeparator.setPadding(new Insets(0, 4, 0, 4));

        Button refreshRemoteButton = new Button(I18n.get("sftp.refresh"));
        styleToolbarButton(refreshRemoteButton, FileBrowserIcons.REFRESH);
        refreshRemoteButton.setTooltip(new Tooltip(I18n.get("sftp.refreshRemote")));
        refreshRemoteButton.setOnAction(e -> {
            resetAutoCloseTimer();
            refreshRemote();
        });

        Button newFolderRemoteButton = new Button();
        styleToolbarButton(newFolderRemoteButton, FileBrowserIcons.NEW_FOLDER);
        newFolderRemoteButton.setTooltip(new Tooltip(I18n.get("filebrowser.tooltip.newFolder")));
        newFolderRemoteButton.setDisable(true);
        newFolderRemoteButton.setOnAction(e -> {
            resetAutoCloseTimer();
            createRemoteFolder();
        });

        Button deleteRemoteButton = new Button(I18n.get("sftp.delete"));
        styleToolbarButton(deleteRemoteButton, FileBrowserIcons.DELETE);
        deleteRemoteButton.setTooltip(new Tooltip(I18n.get("sftp.contextMenu.delete")));
        deleteRemoteButton.setDisable(true);
        deleteRemoteButton.setOnAction(e -> {
            resetAutoCloseTimer();
            deleteRemoteSelected();
        });

        Button ownerRemoteButton = new Button(I18n.get("sftp.permissionsShort"));
        styleToolbarButton(ownerRemoteButton, FileBrowserIcons.LOCK);
        ownerRemoteButton.setTooltip(new Tooltip(I18n.get("sftp.contextMenu.setOwner")));
        ownerRemoteButton.setDisable(true);
        ownerRemoteButton.setOnAction(e -> {
            resetAutoCloseTimer();
            setOwnerPermissionsDialog();
        });

        Button archiveButton = new Button(I18n.get("sftp.archive"));
        styleToolbarButton(archiveButton, FileBrowserIcons.ARCHIVE_BOX);
        archiveButton.setTooltip(new Tooltip(I18n.get("sftp.contextMenu.archive")));
        archiveButton.setDisable(true);
        archiveButton.setOnAction(e -> {
            resetAutoCloseTimer();
            createRemoteArchive();
        });

        MenuButton editRemoteButton = new MenuButton(I18n.get("sftp.edit"));
        editRemoteButton.getStyleClass().add("file-browser-toolbar-button");
        editRemoteButton.setTooltip(new Tooltip(I18n.get("sftp.edit.snippetEditor")));
        editRemoteButton.setDisable(true);
        MenuItem editRemoteSnippetItem = new MenuItem(I18n.get("sftp.edit.snippetEditor"));
        editRemoteSnippetItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openSelectedRemoteFileInSnippetEditor();
        });
        MenuItem editRemoteExternalItem = new MenuItem(I18n.get("sftp.edit.externalEditor"));
        editRemoteExternalItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openSelectedRemoteFileInExternalEditor();
        });
        MenuItem editRemoteSudoItem = new MenuItem(I18n.get("sftp.edit.asRoot"));
        editRemoteSudoItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openSelectedRemoteFileAsRoot();
        });
        editRemoteButton.getItems().addAll(editRemoteSnippetItem, editRemoteExternalItem, editRemoteSudoItem);

        HBox remoteToolbar = paneToolbar(downloadButton, transferSeparator, refreshRemoteButton,
            newFolderRemoteButton, deleteRemoteButton, ownerRemoteButton, archiveButton, editRemoteButton);

        localPaneStatus = paneStatusLabel();
        remotePaneStatus = paneStatusLabel();
        localPanel.getChildren().addAll(localPaneStatus, localToolbar);
        remotePanel.getChildren().addAll(remotePaneStatus, remoteToolbar);

        localSelectionActions = () -> {
            SftpFileItem selected = localTable.getSelectionModel().getSelectedItem();
            boolean hasSelection = selected != null && !selected.getName().equals("..");
            // Upload needs a live session and an absolute target: SFTP itself does not expand '~'.
            // Greyed out up front while the policy denies file transfer; uploadPaths refuses as well.
            uploadButton.setDisable(!uploadEnabled(hasSelection, isRemoteConnected(), remotePathResolved,
                fileTransferAllowed()));
            deleteLocalButton.setDisable(!hasSelection);
            ownerLocalButton.setDisable(!hasSelection);
            archiveLocalButton.setDisable(!hasSelection);
            editLocalButton.setDisable(!isSingleEditableFileSelection(localTable));
        };

        remoteSelectionActions = () -> {
            SftpFileItem selected = remoteTable.getSelectionModel().getSelectedItem();
            boolean hasSelection = selected != null && !selected.getName().equals("..");
            boolean usable = hasSelection && isRemoteConnected();
            downloadButton.setDisable(!downloadEnabled(usable, fileTransferAllowed()));
            archiveButton.setDisable(!usable);
            deleteRemoteButton.setDisable(!usable);
            ownerRemoteButton.setDisable(!usable);
            // The organization's load-into-snippet-editor policy can deny editing server files, and
            // the file-transfer policy the local copy an external editor needs: both grey out up front.
            SftpEditGate editGate = SftpEditGate.current();
            boolean editableFile = isRemoteConnected() && isSingleEditableFileSelection(remoteTable);
            editRemoteSnippetItem.setDisable(!editableFile || !editGate.remoteEditorAvailable());
            editRemoteExternalItem.setDisable(!editableFile || !externalEditAvailable(editGate));
            editRemoteSudoItem.setDisable(!editableFile || !sudoEditAvailable());
            editRemoteButton.setDisable(editRemoteSnippetItem.isDisable() && editRemoteExternalItem.isDisable()
                && editRemoteSudoItem.isDisable());
            // A new folder goes into the absolute folder a listing returned, not the unexpanded '~'.
            newFolderRemoteButton.setDisable(!isRemoteConnected() || !remotePathResolved);
            // Two local files or folders compare without a server; everything else checks it when run.
            compareButton.setDisable(compareCancel != null);
        };

        // Enable/disable buttons based on selection
        localTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) ->
            localSelectionActions.run());
        remoteTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) ->
            remoteSelectionActions.run());

        // Enable multiple selection
        localTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        remoteTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        localTable.getSelectionModel().getSelectedItems().addListener(
            (ListChangeListener<SftpFileItem>) change -> localSelectionActions.run());
        remoteTable.getSelectionModel().getSelectedItems().addListener(
            (ListChangeListener<SftpFileItem>) change -> remoteSelectionActions.run());

        // The status line follows the list (listing, search, hidden entries) and the selection.
        sortedLocalItems.addListener((ListChangeListener<SftpFileItem>) change -> updatePaneStatus(false));
        sortedRemoteItems.addListener((ListChangeListener<SftpFileItem>) change -> updatePaneStatus(true));
        localTable.getSelectionModel().getSelectedItems().addListener(
            (ListChangeListener<SftpFileItem>) change -> updatePaneStatus(false));
        remoteTable.getSelectionModel().getSelectedItems().addListener(
            (ListChangeListener<SftpFileItem>) change -> updatePaneStatus(true));
        updatePaneStatus(false);
        updatePaneStatus(true);
    }

    private static Label paneStatusLabel() {
        Label status = new Label();
        status.getStyleClass().addAll("file-browser-footer", "sftp-pane-status");
        status.setMaxWidth(Double.MAX_VALUE);
        status.setMinWidth(0);
        return status;
    }

    /**
     * A panel's status line: how many entries it lists ("12 of 40" while the search narrows the
     * list), how many are selected and their size, and how many hidden entries it hides.
     */
    private void updatePaneStatus(boolean remote) {
        Label status = remote ? remotePaneStatus : localPaneStatus;
        if (status == null) {
            return;
        }
        ObservableList<SftpFileItem> all = remote ? remoteItems : localItems;
        List<SftpFileItem> shown = remote ? sortedRemoteItems : sortedLocalItems;
        boolean showHidden = remote ? showHiddenRemote : showHiddenLocal;
        long hidden = showHidden ? 0 : all.stream().filter(SftpFileItem::isHidden).count();
        long listed = all.stream().filter(item -> !item.isParentEntry()).count() - hidden;
        long visible = shown.stream().filter(item -> !item.isParentEntry()).count();
        List<String> parts = new ArrayList<>();
        parts.add(visible == listed
            ? I18n.get("sftp.paneStatus.entries", visible)
            : I18n.get("sftp.paneStatus.filtered", visible, listed));
        List<SftpFileItem> selected = (remote ? remoteTable : localTable).getSelectionModel().getSelectedItems()
            .stream().filter(item -> item != null && !item.isParentEntry()).toList();
        if (!selected.isEmpty()) {
            long bytes = 0;
            for (SftpFileItem item : selected) {
                long size = remote ? remoteSizeBytes(item) : item.getSizeBytes();
                bytes += Math.max(0, size);
            }
            parts.add(bytes > 0
                ? I18n.get("sftp.paneStatus.selectedSize", selected.size(), formatSize(bytes))
                : I18n.get("sftp.paneStatus.selected", selected.size()));
        }
        if (hidden > 0) {
            parts.add(I18n.get("sftp.paneStatus.hidden", hidden));
        }
        status.setText(String.join("  \u00B7  ", parts));
    }

    // ------------------------------------------------------------------ compare and synchronize

    /** The bar above the lists for a running comparison and its result; hidden until one runs. */
    private HBox createCompareBar() {
        compareTitle = new Label();
        compareTitle.getStyleClass().add("sftp-compare-title");
        compareTitle.setMinWidth(0);
        compareChips = new HBox(6);
        compareChips.setAlignment(Pos.CENTER_LEFT);
        compareChips.setMinWidth(0);

        compareDifferencesOnly = new CheckBox(I18n.get("sftp.compare.differencesOnly"));
        compareDifferencesOnly.setOnAction(e -> {
            applyRowFilter(false);
            applyRowFilter(true);
        });

        syncToLocalButton = new Button(I18n.get("sftp.compare.syncToLocal"));
        styleToolbarButton(syncToLocalButton, FileBrowserIcons.DOWNLOAD);
        syncToLocalButton.setTooltip(new Tooltip(I18n.get("sftp.compare.syncToLocalTooltip")));
        syncToLocalButton.setOnAction(e -> {
            resetAutoCloseTimer();
            synchronize(false);
        });
        syncToServerButton = new Button(I18n.get("sftp.compare.syncToServer"));
        styleToolbarButton(syncToServerButton, FileBrowserIcons.UPLOAD);
        syncToServerButton.setTooltip(new Tooltip(I18n.get("sftp.compare.syncToServerTooltip")));
        syncToServerButton.setOnAction(e -> {
            resetAutoCloseTimer();
            synchronize(true);
        });
        recompareButton = new Button();
        styleToolbarButton(recompareButton, FileBrowserIcons.REFRESH);
        recompareButton.setTooltip(new Tooltip(I18n.get("sftp.compare.recompare")));
        recompareButton.setOnAction(e -> {
            resetAutoCloseTimer();
            if (comparedLocalRoot != null && comparedRemoteRoot != null) {
                runCompare(comparedLocalRoot, comparedRemoteRoot, compareExcludes);
            }
        });
        stopCompareButton = new Button(I18n.get("sftp.compare.stop"));
        styleToolbarButton(stopCompareButton, FileBrowserIcons.CLOSE);
        stopCompareButton.setOnAction(e -> {
            if (compareCancel != null) {
                compareCancel.set(true);
            }
        });
        Button closeButton = new Button();
        styleToolbarButton(closeButton, FileBrowserIcons.CLOSE);
        closeButton.setTooltip(new Tooltip(I18n.get("sftp.compare.close")));
        closeButton.setOnAction(e -> closeComparison());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, compareTitle, compareChips, spacer, compareDifferencesOnly, syncToLocalButton,
            syncToServerButton, recompareButton, stopCompareButton, closeButton);
        bar.getStyleClass().add("sftp-compare-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setVisible(false);
        bar.setManaged(false);
        return bar;
    }

    /**
     * "Compare": what it compares depends on the selection (see {@link SftpComparePair}). Two files
     * open side by side, two folders are compared with everything inside them, and without such a
     * pair the local and the server folder shown are compared.
     */
    private void compareSelection() {
        SftpComparePair pair = SftpComparePair.choose(selectedInOrder(localTable), selectedInOrder(remoteTable),
            remotePaneActive);
        switch (pair.kind()) {
            case FILES -> compareFiles(fileRef(pair.left()), fileRef(pair.right()));
            case FOLDERS -> compareFolders(pair.left(), pair.right());
            case MIXED -> statusLabel.setText(I18n.get("sftp.compare.mixed"));
            case NONE -> startCompare();
        }
    }

    /** The two folders shown, local against the server, in the bar above the lists. */
    private void startCompare() {
        if (!requireConnected() || !remotePathResolved || compareCancel != null) {
            return;
        }
        startCompare(currentLocalPath, currentRemotePath, false);
    }

    /**
     * A local folder against a server folder, in the bar above the lists; with {@code navigate} both
     * panels go into the two folders, so their rows take the comparison's colours.
     */
    private void startCompare(Path localRoot, String remoteRoot, boolean navigate) {
        if (compareCancel != null) {
            return;
        }
        askCompareExcludes(I18n.get("sftp.compare.local"), localRoot.toString(),
            I18n.get("sftp.compare.remote"), serverDisplayName() + ":  " + remoteRoot)
            .ifPresent(patterns -> {
                if (navigate) {
                    navigateLocal(localRoot.toString());
                    navigateRemote(remoteRoot);
                }
                runCompare(localRoot, remoteRoot, patterns);
            });
    }

    /** Asks for the patterns a folder comparison leaves out; empty when the user cancels. */
    private Optional<List<String>> askCompareExcludes(String leftCaption, String leftPath, String rightCaption,
                                                      String rightPath) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("sftp.compare.title"));
        dialog.setHeaderText(I18n.get("sftp.compare.header"));
        applyDarkTheme(dialog);
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(20));
        Label leftLabel = new Label(leftPath);
        Label rightLabel = new Label(rightPath);
        for (Label path : List.of(leftLabel, rightLabel)) {
            path.setStyle("-fx-font-family: \"Menlo\", \"Consolas\", monospace;");
            path.setWrapText(true);
            path.setMaxWidth(460);
        }
        grid.addRow(0, new Label(leftCaption), leftLabel);
        grid.addRow(1, new Label(rightCaption), rightLabel);
        TextArea excludes = new TextArea(String.join("\n", compareExcludes));
        excludes.setPromptText(I18n.get("sftp.archive.excludePrompt"));
        excludes.setPrefRowCount(3);
        excludes.setPrefWidth(460);
        grid.addRow(2, new Label(I18n.get("sftp.archive.exclude")), excludes);
        Label info = new Label(I18n.get("sftp.compare.info"));
        info.setWrapText(true);
        info.setMaxWidth(560);
        info.setStyle("-fx-font-size: 0.8462em; -fx-opacity: 0.75;");
        grid.add(info, 0, 3, 2, 1);
        dialog.getDialogPane().setContent(grid);
        ButtonType compare = new ButtonType(I18n.get("sftp.compare"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(compare, ButtonType.CANCEL);
        return dialog.showAndWait().filter(compare::equals).map(answer -> {
            List<String> patterns = java.util.Arrays.stream(excludes.getText().split("\n"))
                .map(String::trim)
                .filter(pattern -> !pattern.isEmpty())
                .toList();
            compareExcludes = List.copyOf(patterns);
            return patterns;
        });
    }

    /**
     * Two folders: a local and a server folder in the bar above the lists (with Synchronize), two
     * on the same side in {@link SftpFolderCompareDialog}, which the panels cannot colour.
     */
    private void compareFolders(SftpComparePair.Entry left, SftpComparePair.Entry right) {
        if ((left.isRemote() || right.isRemote()) && !requireConnected()) {
            return;
        }
        if (left.side() != right.side()) {
            SftpComparePair.Entry local = left.isRemote() ? right : left;
            SftpComparePair.Entry remote = left.isRemote() ? left : right;
            startCompare(Paths.get(local.item().getPath()), remote.item().getPath(), true);
            return;
        }
        if (compareCancel != null) {
            return;
        }
        String leftLabel = placeOf(left.side()) + ": " + left.item().getPath();
        String rightLabel = placeOf(right.side()) + ": " + right.item().getPath();
        askCompareExcludes(I18n.get("sftp.diff.left"), leftLabel, I18n.get("sftp.diff.right"), rightLabel)
            .ifPresent(patterns -> runFolderCompareDialog(left, right, leftLabel, rightLabel, patterns));
    }

    /** Reads two folders of one side off the FX thread, with the bar's progress and Stop, then shows the result. */
    private void runFolderCompareDialog(SftpComparePair.Entry left, SftpComparePair.Entry right, String leftLabel,
                                        String rightLabel, List<String> excludes) {
        SFTPSession session = sftpSession;
        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
        compareCancel = cancel;
        showCompareRunning(0);
        refreshActionStates();
        java.util.function.Predicate<String> excluded = relative -> matchesExclude(relative, excludes);
        Thread worker = new Thread(() -> {
            FolderComparison result = null;
            Throwable failure = null;
            try {
                int[] leftCount = {0};
                List<FolderComparison.Entry> leftEntries = snapshot(left.side(), left.item().getPath(), session,
                    excluded, cancel::get, count -> Platform.runLater(() -> showCompareProgress(cancel, count)));
                leftCount[0] = leftEntries.size();
                List<FolderComparison.Entry> rightEntries = snapshot(right.side(), right.item().getPath(), session,
                    excluded, cancel::get,
                    count -> Platform.runLater(() -> showCompareProgress(cancel, leftCount[0] + count)));
                result = FolderComparison.compare(leftEntries, rightEntries);
            } catch (Exception e) {
                failure = e;
            }
            FolderComparison compared = result;
            Throwable error = failure;
            Platform.runLater(() -> {
                if (closing || compareCancel != cancel) {
                    return;
                }
                compareCancel = null;
                refreshActionStates();
                stopCompareButton.setVisible(false);
                stopCompareButton.setManaged(false);
                // The bar goes back to the comparison it showed before, or away.
                if (comparison != null) {
                    showComparisonResult();
                } else {
                    compareBar.setVisible(false);
                    compareBar.setManaged(false);
                }
                if (error != null) {
                    statusLabel.setText(compareFailureMessage(error));
                    return;
                }
                new SftpFolderCompareDialog(ownerWindowOrNull(), leftLabel, rightLabel, compared,
                    relative -> {
                        FolderComparison.Entry leftEntry = compared.local(relative).orElse(null);
                        FolderComparison.Entry rightEntry = compared.remote(relative).orElse(null);
                        if (leftEntry != null && rightEntry != null) {
                            compareFiles(
                                new FileRef(left.side(), childPath(left.side(), left.item().getPath(), relative),
                                    leftEntry.size()),
                                new FileRef(right.side(), childPath(right.side(), right.item().getPath(), relative),
                                    rightEntry.size()));
                        }
                    },
                    this::formatSize).show();
            });
        }, "SFTP-CompareFolders");
        worker.setDaemon(true);
        worker.start();
    }

    /** Everything below a local or a server folder, for a comparison. */
    private static List<FolderComparison.Entry> snapshot(SftpComparePair.Side side, String root, SFTPSession session,
                                                         java.util.function.Predicate<String> excluded,
                                                         java.util.function.BooleanSupplier cancelled,
                                                         FolderSnapshots.Progress progress) throws IOException {
        return side == SftpComparePair.Side.LOCAL
            ? FolderSnapshots.local(Paths.get(root), excluded, cancelled, progress)
            : FolderSnapshots.remote(remoteReader(session), root, excluded, cancelled, progress);
    }

    private static RemoteTreeReader remoteReader(SFTPSession session) {
        return new RemoteTreeReader() {
            @Override
            public List<SftpClient.DirEntry> list(String folder) throws IOException {
                return session.listFiles(folder);
            }

            @Override
            public SftpClient.Attributes lstat(String path) throws IOException {
                return session.getLinkAttributes(path);
            }
        };
    }

    private static String childPath(SftpComparePair.Side side, String root, String relative) {
        return side == SftpComparePair.Side.LOCAL
            ? Paths.get(root).resolve(relative).toString()
            : RemotePathSupport.appendRemotePath(root, relative);
    }

    private String compareFailureMessage(Throwable error) {
        if (error instanceof FolderSnapshots.StoppedException stopped) {
            return stopped.tooMany()
                ? I18n.get("sftp.compare.tooMany", FolderSnapshots.MAX_ENTRIES)
                : I18n.get("sftp.compare.stopped");
        }
        return I18n.get("sftp.compare.failed", failureMessage(error));
    }

    /** A file to compare: its panel, its path and the size the listing showed (-1 when unknown). */
    private record FileRef(SftpComparePair.Side side, String path, long size) {
        String name() {
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }

    private static FileRef fileRef(SftpComparePair.Entry entry) {
        return new FileRef(entry.side(), entry.item().getPath(), entry.item().getSizeBytes());
    }

    /** "This computer (name)" or "Server name (user@host)", as the archive dialog says it. */
    private String placeOf(SftpComparePair.Side side) {
        return side == SftpComparePair.Side.LOCAL
            ? LocalHostName.current()
                .map(name -> I18n.get("sftp.archive.computer.localNamed", name))
                .orElse(I18n.get("sftp.archive.computer.local"))
            : I18n.get("sftp.archive.computer.remote", serverDisplayName());
    }

    /**
     * Opens two text files side by side ({@link SftpFileDiffDialog}): reads them off the FX thread,
     * refuses binary files and files over {@link SftpTextContent#MAX_BYTES}. A server file needs a
     * connection and the policy that lets server files be loaded into an editor.
     */
    private void compareFiles(FileRef left, FileRef right) {
        for (FileRef ref : List.of(left, right)) {
            if (ref.size() > SftpTextContent.MAX_BYTES) {
                statusLabel.setText(I18n.get("sftp.diff.tooLarge", ref.name(), formatSize(SftpTextContent.MAX_BYTES)));
                return;
            }
        }
        boolean remote = left.side() == SftpComparePair.Side.REMOTE || right.side() == SftpComparePair.Side.REMOTE;
        if (remote && (!requireConnected()
                || !remoteEditAllowedByPolicy(SftpEditGate.current().remoteEditorAvailable()))) {
            return;
        }
        SFTPSession session = sftpSession;
        statusLabel.setText(I18n.get("sftp.diff.loading", left.name(), right.name()));
        Thread worker = new Thread(() -> {
            try {
                String leftText = readText(session, left);
                String rightText = readText(session, right);
                Platform.runLater(() -> {
                    if (closing) {
                        return;
                    }
                    statusLabel.setText(I18n.get("sftp.diff.title", left.name(), right.name()));
                    new SftpFileDiffDialog(ownerWindowOrNull(),
                        new SftpFileDiffDialog.Side(placeOf(left.side()), left.path(), leftText),
                        new SftpFileDiffDialog.Side(placeOf(right.side()), right.path(), rightText),
                        aiAccess(), newFileTarget(left, right)).show();
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText(I18n.get("sftp.diff.loadFailed", failureMessage(e))));
            }
        }, "SFTP-Diff-Load");
        worker.setDaemon(true);
        worker.start();
    }

    private String readText(SFTPSession session, FileRef ref) throws IOException {
        byte[] bytes;
        if (ref.side() == SftpComparePair.Side.LOCAL) {
            Path path = Paths.get(ref.path());
            if (Files.size(path) > SftpTextContent.MAX_BYTES) {
                throw new IOException(I18n.get("sftp.diff.tooLarge", ref.name(), formatSize(SftpTextContent.MAX_BYTES)));
            }
            bytes = Files.readAllBytes(path);
        } else {
            bytes = session.downloadFileBytes(ref.path());
        }
        if (bytes.length > SftpTextContent.MAX_BYTES) {
            throw new IOException(I18n.get("sftp.diff.tooLarge", ref.name(), formatSize(SftpTextContent.MAX_BYTES)));
        }
        return SftpTextContent.decode(bytes)
            .orElseThrow(() -> new IOException(I18n.get("sftp.diff.binary", ref.name())));
    }

    /** A double-click on a compared file that exists on both sides shows the two side by side. */
    private void openComparedFile(SftpFileItem item, boolean remote) {
        FolderComparison current = comparison;
        String relative = remote ? remoteRelative(item.getPath()) : localRelative(item.getPath());
        if (current == null || relative == null) {
            return;
        }
        FolderComparison.Entry local = current.local(relative).orElse(null);
        FolderComparison.Entry server = current.remote(relative).orElse(null);
        if (local == null || server == null || local.directory() || server.directory()) {
            return;
        }
        compareFiles(
            new FileRef(SftpComparePair.Side.LOCAL, comparedLocalRoot.resolve(relative).toString(), local.size()),
            new FileRef(SftpComparePair.Side.REMOTE, RemotePathSupport.appendRemotePath(comparedRemoteRoot, relative),
                server.size()));
    }

    /** The selection of a list in list order, without {@code ..}. */
    private static List<SftpFileItem> selectedInOrder(TableView<SftpFileItem> table) {
        List<SftpFileItem> items = table.getItems();
        return table.getSelectionModel().getSelectedIndices().stream()
            .filter(index -> index != null && index >= 0 && index < items.size())
            .sorted()
            .map(items::get)
            .filter(item -> item != null && !item.isParentEntry())
            .toList();
    }

    /**
     * What {@link SftpFileDiffDialog} needs to ask the AI: the AI features must be on, the policy
     * must allow AI requests, and a profile must exist. Requests go through the main window's
     * profile routing, service and usage accounting, like every other AI request.
     */
    private SftpFileDiffDialog.AiAccess aiAccess() {
        MainWindow window = ownerWindow;
        return new SftpFileDiffDialog.AiAccess() {
            @Override
            public Optional<String> unavailableReason() {
                GlobalSettings settings = globalSettingsOrNull();
                if (window == null || settings == null || !settings.isAiFeaturesEnabled()) {
                    return Optional.of(I18n.get("sftp.diff.ai.disabled"));
                }
                if (!de.kortty.policy.PolicyManager.effective().aiChatAllowed()) {
                    return Optional.of(I18n.get("sftp.diff.ai.policy"));
                }
                if (window.getAvailableAiProfiles().isEmpty()) {
                    return Optional.of(I18n.get("sftp.diff.ai.noProfile"));
                }
                return Optional.empty();
            }

            @Override
            public List<de.kortty.model.AiProfile> profiles() {
                return window.getAvailableAiProfiles();
            }

            @Override
            public de.kortty.model.AiProfile defaultProfile() {
                return window.resolveAiProfileForAction(connection, de.kortty.core.AiAction.COMPARE_FILES, null);
            }

            @Override
            public de.kortty.core.AiService service(de.kortty.model.AiProfile profile) {
                return window.createAiServiceForProfile(profile, connection);
            }

            @Override
            public void recordUsage(de.kortty.model.AiProfile profile, de.kortty.core.AiRequest request,
                                    de.kortty.core.AiExecutionResult result) {
                window.recordAiUsageForProfile(profile, request, result);
            }

            @Override
            public String connectionName() {
                return connection.getDisplayName();
            }
        };
    }

    /**
     * Where {@link SftpFileDiffDialog} puts a new file the AI wrote from {@code left} and
     * {@code right}: on the server or this computer, proposing the right file's place (the file it
     * is meant to replace), or through the snippet editor into the Snippet Manager.
     */
    private SftpFileDiffDialog.NewFileTarget newFileTarget(FileRef left, FileRef right) {
        SftpNewFilePlacement.Ref leftRef = new SftpNewFilePlacement.Ref(left.side(), left.path());
        SftpNewFilePlacement.Ref rightRef = new SftpNewFilePlacement.Ref(right.side(), right.path());
        return new SftpFileDiffDialog.NewFileTarget() {
            @Override
            public void save(javafx.stage.Window owner, String content,
                             java.util.function.Consumer<SftpFileDiffDialog.Saved> done) {
                Optional<SftpSaveNewFileDialog.Choice> choice = askNewFilePlace(owner, content, leftRef, rightRef);
                if (choice.isEmpty() || closing) {
                    done.accept(SftpFileDiffDialog.Saved.cancelled());
                    return;
                }
                statusLabel.setText(I18n.get("sftp.diff.newFile.saving"));
                Thread worker = new Thread(() -> {
                    SftpFileDiffDialog.Saved outcome;
                    try {
                        outcome = writeNewFile(owner, choice.get(), content);
                    } catch (Exception e) {
                        logger.warn("Could not save the new file at {}", choice.get().path(), e);
                        outcome = SftpFileDiffDialog.Saved.failed(failureMessage(e));
                    }
                    SftpFileDiffDialog.Saved saved = outcome;
                    Platform.runLater(() -> {
                        noteNewFileSaved(saved);
                        done.accept(saved);
                    });
                }, "SFTP-NewFile-Save");
                worker.setDaemon(true);
                worker.start();
            }

            @Override
            public void edit(javafx.stage.Window owner, String name, String content, String description,
                             java.util.function.Consumer<SftpFileDiffDialog.Saved> saved) {
                editNewFile(owner, name, content, description, leftRef, rightRef, saved);
            }
        };
    }

    /** Asks on the FX thread where a new file goes; empty when the user cancelled. */
    private Optional<SftpSaveNewFileDialog.Choice> askNewFilePlace(javafx.stage.Window owner, String content,
                                                                   SftpNewFilePlacement.Ref left,
                                                                   SftpNewFilePlacement.Ref right) {
        Optional<String> serverRefusal = serverWriteRefusal();
        SftpSaveNewFileDialog.Place server = new SftpSaveNewFileDialog.Place(
            placeOf(SftpComparePair.Side.REMOTE),
            SftpNewFilePlacement.proposedPath(SftpComparePair.Side.REMOTE, left, right, currentRemotePath),
            serverRefusal.orElse(null));
        SftpSaveNewFileDialog.Place local = new SftpSaveNewFileDialog.Place(
            placeOf(SftpComparePair.Side.LOCAL),
            SftpNewFilePlacement.proposedPath(SftpComparePair.Side.LOCAL, left, right,
                currentLocalPath != null ? currentLocalPath.toString() : null),
            null);
        return new SftpSaveNewFileDialog(owner,
            I18n.get("sftp.saveNewFile.summary", de.kortty.core.TextDiff.lineCount(content)),
            server, local, SftpNewFilePlacement.proposedSide(left, right, serverRefusal.isEmpty()),
            serverRefusal.isEmpty() ? remoteSaveLister() : null).showAndWait();
    }

    /**
     * Lists server folders for {@link SftpRemoteSaveChooser}, off the FX thread: a relative folder
     * is taken from the folder the server panel shows, a link to a folder counts as a folder.
     */
    private SftpRemoteSaveChooser.Lister remoteSaveLister() {
        String base = currentRemotePath;
        return folder -> {
            SFTPSession session = connectedSession();
            String path = resolveRemoteListingPath(folder == null || folder.isBlank() ? base : folder, base,
                session.getCurrentDirectory());
            List<SftpRemoteSaveChooser.Entry> entries = new ArrayList<>();
            for (SftpClient.DirEntry entry : session.listFiles(path)) {
                SftpClient.Attributes attrs = entry.getAttributes();
                boolean directory = attrs.isDirectory();
                if (!directory && attrs.isSymbolicLink()) {
                    try {
                        directory = session.getAttributes(
                            RemotePathSupport.appendRemotePath(path, entry.getFilename())).isDirectory();
                    } catch (IOException brokenLink) {
                        directory = false;
                    }
                }
                entries.add(new SftpRemoteSaveChooser.Entry(entry.getFilename(), directory));
            }
            return new SftpRemoteSaveChooser.Folder(path, entries);
        };
    }

    /** Why a new file cannot go to the server now, or empty when it can. */
    private Optional<String> serverWriteRefusal() {
        SFTPSession session = sftpSession;
        if (!isRemoteConnected() || session == null || !session.isConnected()) {
            return Optional.of(I18n.get("sftp.notConnected"));
        }
        if (!SftpEditGate.current().remoteWriteBackAllowed()) {
            return Optional.of(I18n.get("policy.terminal.loadReadOnly"));
        }
        if (!fileTransferAllowed()) {
            return Optional.of(transferRefusal(de.kortty.policy.FileTransferGate.Route.SFTP_UPLOAD)
                .orElse(I18n.get("policy.feature.disabled")));
        }
        return Optional.empty();
    }

    /**
     * Writes a new file where {@code choice} says; for a worker thread. A file already there is
     * overwritten only after the user confirms, and first kept as {@code .bak} when asked; it keeps
     * its permissions. Cancelled when the user declines overwriting.
     */
    private SftpFileDiffDialog.Saved writeNewFile(javafx.stage.Window owner, SftpSaveNewFileDialog.Choice choice,
                                                  String content) throws Exception {
        String place = placeOf(choice.side());
        String text = content == null ? "" : content;
        boolean written = choice.side() == SftpComparePair.Side.REMOTE
            ? writeNewRemoteFile(owner, place, choice.path(), text.getBytes(StandardCharsets.UTF_8), choice.backup())
            : writeNewLocalFile(owner, place, Paths.get(choice.path()), text, choice.backup());
        return written
            ? new SftpFileDiffDialog.Saved(place, choice.path(), text, null)
            : SftpFileDiffDialog.Saved.cancelled();
    }

    /** Says in the status line how saving a new file went and shows the file in its panel. */
    private void noteNewFileSaved(SftpFileDiffDialog.Saved saved) {
        if (saved.done()) {
            statusLabel.setText(I18n.get("sftp.diff.newFile.saved", saved.label()));
            if (saved.place().equals(placeOf(SftpComparePair.Side.REMOTE))) {
                refreshRemote();
            } else {
                refreshLocal();
            }
        } else if (saved.failure() != null) {
            statusLabel.setText(I18n.get("sftp.diff.newFile.saveFailed", saved.failure()));
        } else {
            statusLabel.setText("");
        }
    }

    /**
     * Opens a new file in the snippet editor above {@code owner} (the comparison window), as a file
     * of its own: "Save as snippet" keeps it in the Snippet Manager with a name and category the
     * user picks, "Replace the right file" writes it over the file it was meant to replace, and
     * "Save as..." asks where it goes. Every file written is reported to {@code saved}.
     */
    private void editNewFile(javafx.stage.Window owner, String name, String content, String description,
                             SftpNewFilePlacement.Ref left, SftpNewFilePlacement.Ref right,
                             java.util.function.Consumer<SftpFileDiffDialog.Saved> saved) {
        Snippet snippet = createFileSnippetDraft(name, content);
        snippet.setDescription(description);
        SnippetEditDialog[] editor = new SnippetEditDialog[1];
        java.util.function.Supplier<javafx.stage.Window> editorWindow = () -> editor[0] != null
            && editor[0].getDialogPane().getScene() != null ? editor[0].getDialogPane().getScene().getWindow() : owner;
        Optional<String> serverRefusal = right.side() == SftpComparePair.Side.REMOTE
            ? serverWriteRefusal()
            : Optional.empty();
        SnippetEditDialog.ExternalFileAction replaceRight = serverRefusal.isPresent() ? null : draft ->
            reportNewFile(saved, writeNewFile(editorWindow.get(),
                new SftpSaveNewFileDialog.Choice(right.side(), right.path(), true), draft.getContent()));
        SnippetEditDialog.ExternalFileAction saveAs = draft -> {
            Optional<SftpSaveNewFileDialog.Choice> choice = callOnFxThread(() ->
                askNewFilePlace(editorWindow.get(), draft.getContent(), left, right));
            return choice.isPresent() && reportNewFile(saved, writeNewFile(editorWindow.get(), choice.get(), draft.getContent()));
        };
        SnippetEditDialog.ExternalFileActionConfig config = new SnippetEditDialog.ExternalFileActionConfig(
            I18n.get("sftp.diff.newFile.editorSource", right.name()),
            I18n.get("sftp.diff.newFile.replaceRight"),
            I18n.get("sftp.snippetEditor.saveAs"),
            I18n.get("sftp.snippetEditor.saveSnippet"),
            I18n.get("sftp.snippetEditor.savedFile"),
            I18n.get("sftp.snippetEditor.savedFile"),
            I18n.get("sftp.snippetEditor.savedSnippet"),
            replaceRight,
            saveAs,
            this::saveDraftAsSnippet,
            serverRefusal.orElse(null));
        List<String> categoryNames = app.getSnippetManager().getAllCategories().stream()
            .map(SnippetCategory::getName)
            .toList();
        editor[0] = new SnippetEditDialog(snippet, categoryNames, SnippetAiAssistFactory.create(ownerWindow, connection),
            config);
        if (owner != null) {
            editor[0].initOwner(owner);
        }
        editor[0].showNonBlocking(null);
    }

    /** Reports a file the snippet editor saved, on the FX thread; true when one was saved. */
    private boolean reportNewFile(java.util.function.Consumer<SftpFileDiffDialog.Saved> saved,
                                  SftpFileDiffDialog.Saved outcome) {
        if (!outcome.done()) {
            return false;
        }
        Platform.runLater(() -> {
            noteNewFileSaved(outcome);
            saved.accept(outcome);
        });
        return true;
    }

    private boolean writeNewRemoteFile(javafx.stage.Window owner, String place, String path, byte[] bytes,
                                       boolean backup) throws Exception {
        SftpEditGate.current().requireRemoteWriteBack(I18n.get("policy.terminal.loadReadOnly"));
        if (!fileTransferAllowed()) {
            throw new IOException(transferRefusal(de.kortty.policy.FileTransferGate.Route.SFTP_UPLOAD)
                .orElse(I18n.get("policy.feature.disabled")));
        }
        SFTPSession session = connectedSession();
        SftpClient.Attributes existing;
        try {
            existing = session.getAttributes(path);
        } catch (IOException missing) {
            existing = null;
        }
        if (existing != null) {
            if (existing.isDirectory()) {
                throw new IOException(I18n.get("sftp.saveNewFile.isFolder", path));
            }
            if (!confirmNewFileOverwrite(owner, place + ": " + path, backup)) {
                return false;
            }
            if (backup) {
                // The backup gets the file's permissions before it gets its content, so a
                // configuration only its owner may read is never readable by others as .bak; the
                // owner may always write it, or the copy could not fill it.
                String backupPath = SftpNewFilePlacement.backupPath(path);
                session.uploadFileBytes(new byte[0], backupPath);
                session.setPermissions(backupPath, String.format("%04o", (existing.getPermissions() & 07777) | 0600));
                session.copyFile(path, backupPath);
            }
        }
        session.uploadFileBytes(bytes, path);
        return true;
    }

    private boolean writeNewLocalFile(javafx.stage.Window owner, String place, Path path, String content,
                                      boolean backup) throws Exception {
        if (Files.isDirectory(path)) {
            throw new IOException(I18n.get("sftp.saveNewFile.isFolder", path));
        }
        if (Files.exists(path)) {
            if (!confirmNewFileOverwrite(owner, place + ": " + path, backup)) {
                return false;
            }
            if (backup) {
                Files.copy(path, Paths.get(SftpNewFilePlacement.backupPath(path.toString())),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        // Keeps the permissions of a file it replaces, such as a script's executable bit.
        AtomicFileWriter.writeStringAtomically(path, content);
        return true;
    }

    /** Asks on the FX thread, above {@code owner}, whether to overwrite {@code target}. */
    private boolean confirmNewFileOverwrite(javafx.stage.Window owner, String target, boolean backup) throws Exception {
        return callOnFxThread(() -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(I18n.get("sftp.snippetEditor.confirmOverwrite.title"));
            confirm.setHeaderText(I18n.get("sftp.snippetEditor.confirmOverwrite.header"));
            confirm.setContentText(I18n.get(backup ? "sftp.saveNewFile.confirmBackup" : "sftp.saveNewFile.confirm", target));
            applyDarkTheme(confirm);
            if (owner != null) {
                confirm.initOwner(owner);
            } else if (getTabPane() != null && getTabPane().getScene() != null) {
                confirm.initOwner(getTabPane().getScene().getWindow());
            }
            return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
        });
    }

    /**
     * Reads both folders with everything below them off the FX thread and shows the comparison:
     * the bar above the lists counts the differences, the rows take their colours. Stop ends it.
     */
    private void runCompare(Path localRoot, String remoteRoot, List<String> excludes) {
        if (compareCancel != null) {
            compareCancel.set(true);
        }
        SFTPSession session = sftpSession;
        if (session == null) {
            return;
        }
        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
        compareCancel = cancel;
        showCompareRunning(0);
        refreshActionStates();
        java.util.function.Predicate<String> excluded = relative -> matchesExclude(relative, excludes);
        RemoteTreeReader reader = remoteReader(session);
        Thread worker = new Thread(() -> {
            FolderComparison result = null;
            Throwable failure = null;
            try {
                int[] localCount = {0};
                List<FolderComparison.Entry> local = FolderSnapshots.local(localRoot, excluded, cancel::get,
                    count -> {
                        localCount[0] = count;
                        Platform.runLater(() -> showCompareProgress(cancel, count));
                    });
                localCount[0] = local.size();
                List<FolderComparison.Entry> remote = FolderSnapshots.remote(reader, remoteRoot, excluded,
                    cancel::get, count -> Platform.runLater(() -> showCompareProgress(cancel, localCount[0] + count)));
                result = FolderComparison.compare(local, remote);
            } catch (Exception e) {
                failure = e;
            }
            FolderComparison compared = result;
            Throwable error = failure;
            Platform.runLater(() -> applyComparison(cancel, localRoot, remoteRoot, excludes, compared, error));
        }, "SFTP-Compare");
        worker.setDaemon(true);
        worker.start();
    }

    private void showCompareRunning(int entries) {
        compareBar.setVisible(true);
        compareBar.setManaged(true);
        compareTitle.setText(I18n.get("sftp.compare.running", entries));
        compareChips.getChildren().clear();
        stopCompareButton.setVisible(true);
        stopCompareButton.setManaged(true);
        for (Node node : List.of(compareDifferencesOnly, syncToLocalButton, syncToServerButton, recompareButton)) {
            node.setVisible(false);
            node.setManaged(false);
        }
    }

    private void showCompareProgress(java.util.concurrent.atomic.AtomicBoolean cancel, int entries) {
        if (compareCancel == cancel) {
            compareTitle.setText(I18n.get("sftp.compare.running", entries));
        }
    }

    private void applyComparison(java.util.concurrent.atomic.AtomicBoolean cancel, Path localRoot, String remoteRoot,
                                 List<String> excludes, FolderComparison result, Throwable error) {
        if (closing || compareCancel != cancel) {
            return; // replaced by a newer comparison
        }
        compareCancel = null;
        refreshActionStates();
        stopCompareButton.setVisible(false);
        stopCompareButton.setManaged(false);
        if (error != null) {
            String message = compareFailureMessage(error);
            if (comparison == null) {
                compareTitle.setText(message);
            } else {
                statusLabel.setText(message);
                showComparisonResult();
            }
            return;
        }
        comparison = result;
        comparedLocalRoot = localRoot;
        comparedRemoteRoot = remoteRoot;
        showComparisonResult();
        localTable.refresh();
        remoteTable.refresh();
        applyRowFilter(false);
        applyRowFilter(true);
    }

    /** The bar with the counts of the comparison shown and the Synchronize buttons. */
    private void showComparisonResult() {
        FolderComparison current = comparison;
        if (current == null) {
            return;
        }
        compareBar.setVisible(true);
        compareBar.setManaged(true);
        String localName = comparedLocalRoot.getFileName() != null
            ? comparedLocalRoot.getFileName().toString()
            : comparedLocalRoot.toString();
        compareTitle.setText(localName + "  ⇄  " + comparedRemoteRoot);
        compareTitle.setTooltip(new Tooltip(comparedLocalRoot + "  ⇄  " + comparedRemoteRoot));
        FolderComparison.Counts counts = current.counts();
        List<Node> chips = new ArrayList<>();
        if (counts.differing() == 0) {
            chips.add(compareChip(I18n.get("sftp.compare.identical"), "cmp-chip-same"));
        } else {
            addChip(chips, counts.onlyLocal(), "sftp.compare.onlyLocal", "cmp-chip-only");
            addChip(chips, counts.newerLocal(), "sftp.compare.newerLocal", "cmp-chip-newer");
            addChip(chips, counts.onlyRemote(), "sftp.compare.onlyRemote", "cmp-chip-only");
            addChip(chips, counts.newerRemote(), "sftp.compare.newerRemote", "cmp-chip-newer");
            addChip(chips, counts.different(), "sftp.compare.different", "cmp-chip-different");
            addChip(chips, counts.same(), "sftp.compare.same", "cmp-chip-same");
        }
        compareChips.getChildren().setAll(chips);
        for (Node node : List.of(compareDifferencesOnly, syncToLocalButton, syncToServerButton, recompareButton)) {
            node.setVisible(true);
            node.setManaged(true);
        }
        syncToServerButton.setDisable(current.toUpload().isEmpty() && current.extraOnRemote().isEmpty());
        syncToLocalButton.setDisable(current.toDownload().isEmpty() && current.extraOnLocal().isEmpty());
    }

    private static void addChip(List<Node> chips, int count, String key, String styleClass) {
        if (count > 0) {
            chips.add(compareChip(I18n.get(key, count), styleClass));
        }
    }

    private static Label compareChip(String text, String styleClass) {
        Label chip = new Label(text);
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(4);
        dot.getStyleClass().add("sftp-compare-dot");
        chip.setGraphic(dot);
        chip.getStyleClass().addAll("sftp-compare-chip", styleClass);
        chip.setMinWidth(Label.USE_PREF_SIZE);
        return chip;
    }

    /** Ends the comparison: the bar goes and the rows lose their colours. */
    private void closeComparison() {
        if (compareCancel != null) {
            compareCancel.set(true);
            compareCancel = null;
        }
        comparison = null;
        compareBar.setVisible(false);
        compareBar.setManaged(false);
        compareDifferencesOnly.setSelected(false);
        localTable.refresh();
        remoteTable.refresh();
        applyRowFilter(false);
        applyRowFilter(true);
        refreshActionStates();
    }

    /** Colours a row by how its entry compares, as seen from its own side. */
    private void markComparison(TableRow<SftpFileItem> row, SftpFileItem item, boolean remote) {
        FolderComparison.State state = item == null || item.isParentEntry() ? null : comparisonState(item, remote);
        FolderComparison.State onlyHere = remote ? FolderComparison.State.ONLY_REMOTE : FolderComparison.State.ONLY_LOCAL;
        FolderComparison.State newerHere = remote ? FolderComparison.State.NEWER_REMOTE : FolderComparison.State.NEWER_LOCAL;
        FolderComparison.State newerThere = remote ? FolderComparison.State.NEWER_LOCAL : FolderComparison.State.NEWER_REMOTE;
        row.pseudoClassStateChanged(CMP_ONLY_HERE, state == onlyHere);
        row.pseudoClassStateChanged(CMP_NEWER_HERE, state == newerHere);
        row.pseudoClassStateChanged(CMP_OLDER_HERE, state == newerThere);
        row.pseudoClassStateChanged(CMP_DIFFERENT,
            state == FolderComparison.State.DIFFERENT || state == FolderComparison.State.CONTENT_DIFFERS);
        row.pseudoClassStateChanged(CMP_SAME, state == FolderComparison.State.SAME);
    }

    /** How a row's entry compares, or null when no comparison covers the folder it is in. */
    private FolderComparison.State comparisonState(SftpFileItem item, boolean remote) {
        FolderComparison current = comparison;
        if (current == null || item == null) {
            return null;
        }
        String relative = remote ? remoteRelative(item.getPath()) : localRelative(item.getPath());
        return relative == null ? null : current.state(relative).orElse(null);
    }

    private String localRelative(String path) {
        try {
            Path entry = Paths.get(path).toAbsolutePath().normalize();
            Path root = comparedLocalRoot.toAbsolutePath().normalize();
            if (!entry.startsWith(root) || entry.equals(root)) {
                return null;
            }
            return root.relativize(entry).toString().replace(File.separatorChar, '/');
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String remoteRelative(String path) {
        String root = comparedRemoteRoot;
        if (path == null || root == null) {
            return null;
        }
        String prefix = root.endsWith("/") ? root : root + "/";
        return path.startsWith(prefix) && path.length() > prefix.length() ? path.substring(prefix.length()) : null;
    }

    /**
     * "To the server" / "To this computer": shows what is copied, asks, then queues the copies in
     * the transfer list (replacing without a question per file and keeping modification times) and,
     * when asked for, deletes what exists only on the target. The folders are compared again after.
     */
    private void synchronize(boolean toServer) {
        FolderComparison current = comparison;
        if (current == null || !requireConnected()) {
            return;
        }
        if (refuseTransfer(toServer ? de.kortty.policy.FileTransferGate.Route.SFTP_UPLOAD
                : de.kortty.policy.FileTransferGate.Route.SFTP_DOWNLOAD)) {
            return;
        }
        List<FolderComparison.Entry> copy = toServer ? current.toUpload() : current.toDownload();
        List<FolderComparison.Entry> extra = toServer ? current.extraOnRemote() : current.extraOnLocal();
        if (copy.isEmpty() && extra.isEmpty()) {
            statusLabel.setText(I18n.get("sftp.sync.nothing"));
            return;
        }
        Path localRoot = comparedLocalRoot;
        String remoteRoot = comparedRemoteRoot;
        long bytes = current.bytes(copy, toServer);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("sftp.sync.title"));
        dialog.setHeaderText(I18n.get(toServer ? "sftp.sync.headerToServer" : "sftp.sync.headerToLocal",
            copy.size(), formatSize(bytes)));
        dialog.setResizable(true);
        applyDarkTheme(dialog);
        VBox content = new VBox(8);
        content.setPadding(new Insets(16, 20, 16, 20));
        GridPane paths = new GridPane();
        paths.setHgap(10);
        paths.setVgap(4);
        Label from = new Label(toServer ? localRoot.toString() : serverDisplayName() + ":  " + remoteRoot);
        Label to = new Label(toServer ? serverDisplayName() + ":  " + remoteRoot : localRoot.toString());
        for (Label path : List.of(from, to)) {
            path.setStyle("-fx-font-family: \"Menlo\", \"Consolas\", monospace;");
        }
        paths.addRow(0, new Label(I18n.get("sftp.sync.from")), from);
        paths.addRow(1, new Label(I18n.get("sftp.sync.to")), to);
        content.getChildren().add(paths);
        if (!copy.isEmpty()) {
            ListView<String> list = new ListView<>(FXCollections.observableArrayList(
                copy.stream().map(entry -> entry.relativePath() + (entry.directory() ? "/" : "")).toList()));
            list.setFocusTraversable(false);
            list.setFixedCellSize(24);
            list.setPrefHeight(Math.min(copy.size(), 8) * 24 + 4);
            list.setPrefWidth(520);
            content.getChildren().add(list);
        }
        List<String> notes = new ArrayList<>();
        notes.add(I18n.get("sftp.sync.replaceNote"));
        int keptNewer = toServer ? current.newerOnRemote() : current.newerOnLocal();
        if (keptNewer > 0) {
            notes.add(I18n.get("sftp.sync.keptNewer", keptNewer));
        }
        if (current.typeConflicts() > 0) {
            notes.add(I18n.get("sftp.sync.conflicts", current.typeConflicts()));
        }
        Label note = new Label(String.join("\n", notes));
        note.setWrapText(true);
        note.setMaxWidth(520);
        note.setStyle("-fx-font-size: 0.8462em; -fx-opacity: 0.8;");
        content.getChildren().add(note);
        CheckBox deleteExtra = new CheckBox(I18n.get(toServer ? "sftp.sync.deleteExtraServer"
            : "sftp.sync.deleteExtraLocal", extra.size()));
        deleteExtra.setDisable(extra.isEmpty());
        content.getChildren().add(deleteExtra);
        dialog.getDialogPane().setContent(content);
        ButtonType start = new ButtonType(I18n.get("sftp.sync.start"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(start, ButtonType.CANCEL);
        if (copy.isEmpty()) {
            // Only deletions are left: the button needs the box ticked.
            dialog.getDialogPane().lookupButton(start).disableProperty().bind(deleteExtra.selectedProperty().not());
        }
        if (dialog.showAndWait().filter(start::equals).isEmpty()) {
            return;
        }

        boolean delete = deleteExtra.isSelected() && !extra.isEmpty() && confirmSyncDelete(toServer, extra);
        int queued = 0;
        java.util.Map<String, List<FolderComparison.Entry>> byFolder = new java.util.TreeMap<>();
        for (FolderComparison.Entry entry : copy) {
            byFolder.computeIfAbsent(entry.parent(), folder -> new ArrayList<>()).add(entry);
        }
        for (java.util.Map.Entry<String, List<FolderComparison.Entry>> folder : byFolder.entrySet()) {
            String relativeFolder = folder.getKey();
            Optional<TransferBatch> batch;
            if (toServer) {
                String target = relativeFolder.isEmpty() ? remoteRoot
                    : RemotePathSupport.appendRemotePath(remoteRoot, relativeFolder);
                List<Path> sources = folder.getValue().stream()
                    .map(entry -> localRoot.resolve(entry.relativePath()))
                    .toList();
                batch = transferQueueHost.enqueueSyncUpload(sources, target);
            } else {
                Path target = relativeFolder.isEmpty() ? localRoot : localRoot.resolve(relativeFolder);
                List<RemoteEntryRef> sources = folder.getValue().stream()
                    .map(entry -> {
                        String path = RemotePathSupport.appendRemotePath(remoteRoot, entry.relativePath());
                        return entry.directory()
                            ? RemoteEntryRef.directory(path)
                            : new RemoteEntryRef(path, entry.name(), RemoteEntryRef.Type.FILE, entry.size(),
                                entry.modifiedMillis());
                    })
                    .toList();
                batch = transferQueueHost.enqueueSyncDownload(sources, target);
            }
            if (batch.isPresent()) {
                syncBatchIds.add(batch.get().id());
                queued += folder.getValue().size();
            }
        }
        if (queued > 0) {
            transferQueuePane.reveal();
            statusLabel.setText(I18n.get("sftp.sync.started", queued));
        }
        if (delete) {
            deleteSyncExtras(toServer, extra, localRoot, remoteRoot);
        }
    }

    /** The second question before a synchronization deletes: names what goes, for good. */
    private boolean confirmSyncDelete(boolean onServer, List<FolderComparison.Entry> extra) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(I18n.get("sftp.sync.confirmDeleteTitle"));
        alert.setHeaderText(I18n.get(onServer ? "sftp.sync.confirmDeleteServer" : "sftp.sync.confirmDeleteLocal",
            extra.size()));
        List<String> names = extra.stream().limit(12)
            .map(entry -> entry.relativePath() + (entry.directory() ? "/" : ""))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (extra.size() > names.size()) {
            names.add("…");
        }
        alert.setContentText(String.join("\n", names));
        alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        applyDarkTheme(alert);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    /** Deletes what exists only on the target side, off the FX thread; reports what could not go. */
    private void deleteSyncExtras(boolean onServer, List<FolderComparison.Entry> extra, Path localRoot,
                                  String remoteRoot) {
        SFTPSession session = sftpSession;
        syncDeletesRunning++;
        Thread worker = new Thread(() -> {
            List<String> failures = new ArrayList<>();
            for (FolderComparison.Entry entry : extra) {
                try {
                    if (onServer) {
                        deleteRemoteRecursive(session, RemotePathSupport.appendRemotePath(remoteRoot,
                            entry.relativePath()), entry.directory());
                    } else {
                        deleteLocalRecursive(localRoot.resolve(entry.relativePath()));
                    }
                } catch (Exception e) {
                    failures.add(entry.relativePath() + ": " + failureMessage(e));
                }
            }
            Platform.runLater(() -> {
                syncDeletesRunning--;
                if (closing) {
                    return;
                }
                if (onServer) {
                    refreshRemote();
                } else {
                    refreshLocal();
                }
                if (!failures.isEmpty()) {
                    showError(I18n.get("sftp.sync.title"), I18n.get("sftp.sync.deleteFailed", failures.size())
                        + "\n\n" + String.join("\n", failures.subList(0, Math.min(10, failures.size()))));
                }
                compareAgainAfterSync();
            });
        }, "SFTP-SyncDelete");
        worker.setDaemon(true);
        worker.start();
    }

    /** Once the last copy and deletion of a synchronization is done, compares the folders again. */
    private void compareAgainAfterSync() {
        if (!syncBatchIds.isEmpty() || syncDeletesRunning > 0 || comparison == null || closing) {
            return;
        }
        Path localRoot = comparedLocalRoot;
        String remoteRoot = comparedRemoteRoot;
        List<String> excludes = compareExcludes;
        // Lets the lists refresh first, as they do shortly after transfers.
        javafx.animation.PauseTransition settle = new javafx.animation.PauseTransition(Duration.millis(900));
        settle.setOnFinished(e -> {
            if (!closing && comparison != null && compareCancel == null && isRemoteConnected()) {
                runCompare(localRoot, remoteRoot, excludes);
            }
        });
        settle.play();
    }

    /**
     * A text cell aligned as given; with {@code tooltip} it shows its whole text on hover, for a
     * column too narrow for it (the date with its time).
     */
    static void installTextCell(TableColumn<SftpFileItem, String> column, Pos alignment, boolean tooltip) {
        column.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            private final Tooltip hover = new Tooltip();

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : value);
                setAlignment(alignment);
                if (tooltip && !empty && value != null && !value.isBlank()) {
                    hover.setText(value);
                    setTooltip(hover);
                } else {
                    setTooltip(null);
                }
            }
        });
    }

    /** One panel's button row, below its list. */
    private static HBox paneToolbar(Node... children) {
        HBox toolbar = new HBox(4, children);
        toolbar.getStyleClass().add("sftp-pane-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setMinWidth(0);
        return toolbar;
    }

    /**
     * Makes a panel the active one when it is clicked or something in it gets the keyboard focus.
     * The active panel is framed in the accent colour and draws its selection in it, so it is plain
     * which list the keyboard (Delete, F2, ...) and the last click work on.
     *
     * <p>The scene's focus owner decides, not {@code focusWithin}: a table marks the row its focus
     * model points at as focused even while another control has the keyboard, so re-sorting or
     * filtering one list would otherwise make its panel active without the user doing anything.
     */
    private void installActivePaneTracking() {
        localPanel.getStyleClass().add("sftp-pane");
        remotePanel.getStyleClass().add("sftp-pane");
        localPanel.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> setActivePane(false));
        remotePanel.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> setActivePane(true));
        localPanel.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (EDIT_PATH_KEYS.match(event)) {
                localBreadcrumb.edit();
                event.consume();
            }
        });
        remotePanel.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (EDIT_PATH_KEYS.match(event)) {
                remoteBreadcrumb.edit();
                event.consume();
            }
        });
        javafx.beans.value.ChangeListener<Node> focusOwner = (obs, old, owner) -> {
            if (isInside(owner, localPanel)) {
                setActivePane(false);
            } else if (isInside(owner, remotePanel)) {
                setActivePane(true);
            }
        };
        localPanel.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (oldScene != null) {
                oldScene.focusOwnerProperty().removeListener(focusOwner);
            }
            if (scene != null) {
                scene.focusOwnerProperty().addListener(focusOwner);
            }
        });
    }

    private static boolean isInside(Node node, Node ancestor) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    private void setActivePane(boolean remote) {
        remotePaneActive = remote;
        localPanel.pseudoClassStateChanged(ACTIVE_PANE, !remote);
        remotePanel.pseudoClassStateChanged(ACTIVE_PANE, remote);
    }
    
    private VBox createLocalPanel() {
        VBox panel = new VBox(5);
        panel.setPadding(new Insets(5));
        
        Label titleLabel = paneTitle(I18n.get("sftp.localSystem"));
        
        // Path field and navigation
        HBox pathBox = new HBox(5);
        localPathField = new TextField();
        localPathField.setEditable(true);
        localPathField.setOnAction(e -> navigateLocal(localPathField.getText()));
        
        Button upButton = new Button();
        styleToolbarButton(upButton, FileBrowserIcons.UP);
        upButton.setTooltip(new Tooltip(I18n.get("filebrowser.tooltip.up")));
        upButton.setOnAction(e -> navigateLocalUp());

        Button homeButton = new Button();
        styleToolbarButton(homeButton, FileBrowserIcons.HOME);
        homeButton.setTooltip(new Tooltip(I18n.get("filebrowser.tooltip.home")));
        homeButton.setOnAction(e -> navigateLocal(System.getProperty("user.home")));

        localHiddenToggle = createHiddenToggle(false);
        
        localBreadcrumb = new SftpBreadcrumbBar(localPathField, this::navigateLocal, File.separatorChar == '\\');
        pathBox.setAlignment(Pos.CENTER_LEFT);
        pathBox.getChildren().addAll(new Label(I18n.get("sftp.path")), localBreadcrumb, upButton, homeButton,
            localHiddenToggle);
        
        // Search field
        HBox searchBox = new HBox(5);
        localSearchField = new TextField();
        localSearchField.setPromptText(I18n.get("sftp.searchPrompt"));
        searchBox.getChildren().addAll(new Label(I18n.get("sftp.search")), localSearchField);
        HBox.setHgrow(localSearchField, Priority.ALWAYS);
        
        // File table
        localTable = new TableView<>();
        localTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        localTable.getStyleClass().add("file-browser-table");
        
        // Column order: Name, Type, Size, Date, User, Group, Permissions
        TableColumn<SftpFileItem, String> nameColumn = new TableColumn<>(I18n.get("sftp.column.name"));
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameColumn.setPrefWidth(180);
        nameColumn.setMinWidth(100);
        nameColumn.setSortable(true);
        nameColumn.setComparator(String.CASE_INSENSITIVE_ORDER);
        
        TableColumn<SftpFileItem, String> typeColumn = new TableColumn<>(I18n.get("sftp.column.type"));
        typeColumn.setCellValueFactory(new PropertyValueFactory<>("type"));
        typeColumn.setPrefWidth(50);
        typeColumn.setMinWidth(40);
        typeColumn.setMaxWidth(60);
        typeColumn.setSortable(true);
        
        TableColumn<SftpFileItem, String> sizeColumn = new TableColumn<>(I18n.get("sftp.column.size"));
        sizeColumn.setCellValueFactory(new PropertyValueFactory<>("size"));
        sizeColumn.setPrefWidth(80);
        sizeColumn.setMinWidth(60);
        sizeColumn.setMaxWidth(100);
        sizeColumn.setSortable(true);
        
        TableColumn<SftpFileItem, String> dateColumn = new TableColumn<>(I18n.get("sftp.column.date"));
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("date"));
        dateColumn.setPrefWidth(130);
        dateColumn.setMinWidth(110);
        dateColumn.setMaxWidth(150);
        dateColumn.setSortable(true);
        
        TableColumn<SftpFileItem, String> userColumn = new TableColumn<>(I18n.get("sftp.column.user"));
        userColumn.setCellValueFactory(new PropertyValueFactory<>("owner"));
        userColumn.setPrefWidth(70);
        userColumn.setMinWidth(50);
        userColumn.setMaxWidth(100);
        userColumn.setSortable(true);
        
        TableColumn<SftpFileItem, String> groupColumn = new TableColumn<>(I18n.get("sftp.column.group"));
        groupColumn.setCellValueFactory(new PropertyValueFactory<>("group"));
        groupColumn.setPrefWidth(70);
        groupColumn.setMinWidth(50);
        groupColumn.setMaxWidth(100);
        groupColumn.setSortable(true);
        
        TableColumn<SftpFileItem, String> permColumn = new TableColumn<>(I18n.get("sftp.column.permissions"));
        permColumn.setCellValueFactory(new PropertyValueFactory<>("permissions"));
        permColumn.setPrefWidth(80);
        permColumn.setMinWidth(60);
        permColumn.setMaxWidth(100);
        permColumn.setSortable(true);
        
        installTypeIconCell(typeColumn);
        installTextCell(sizeColumn, Pos.CENTER_RIGHT, false);
        installTextCell(dateColumn, Pos.CENTER_LEFT, true);
        localTable.getColumns().addAll(java.util.List.of(nameColumn, typeColumn, sizeColumn, dateColumn, userColumn, groupColumn, permColumn));
        
        // Context menu for local table
        ContextMenu localContextMenu = createLocalContextMenu();
        localTable.setContextMenu(localContextMenu);
        installFileKeys(localTable, this::renameLocalSelected, this::deleteLocalSelected,
            () -> setShowHidden(false, !showHiddenLocal));
        
        // Double-click to navigate
        localTable.setRowFactory(tv -> {
            TableRow<SftpFileItem> row = new TableRow<>() {
                @Override
                protected void updateItem(SftpFileItem item, boolean empty) {
                    super.updateItem(item, empty);
                    markComparison(this, empty ? null : item, false);
                }
            };
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    SftpFileItem item = row.getItem();
                    if (!item.isFile()) {
                        navigateLocal(item.getPath());
                    } else if (comparison != null) {
                        openComparedFile(item, false);
                    }
                }
            });
            installLocalRowDrag(row);
            return row;
        });
        localTable.setOnDragOver(event -> onLocalDragOver(event, null));
        localTable.setOnDragExited(event -> localTable.getStyleClass().remove(DROP_TARGET));
        localTable.setOnDragDropped(event -> onLocalDragDropped(event, null));
        
        // Setup data binding with filter and sort
        localItems = FXCollections.observableArrayList();
        filteredLocalItems = new FilteredList<>(localItems, rowFilter("", showHiddenLocal));
        sortedLocalItems = new javafx.collections.transformation.SortedList<>(filteredLocalItems);
        
        installSortPolicy(localTable, sortedLocalItems, typeColumn, sizeColumn, SftpFileItem::getSizeBytes);
        localTable.setItems(sortedLocalItems);
        
        // Set default sort by type (directories first, alphabetically)
        typeColumn.setSortType(TableColumn.SortType.ASCENDING);
        localTable.getSortOrder().add(typeColumn);
        // Apply initial sort
        localTable.sort();
        
        // Search filter: a glob such as *.log, or a plain substring; hidden entries only when shown
        localSearchField.textProperty().addListener((obs, oldVal, newVal) -> applyRowFilter(false));
        
        panel.getChildren().addAll(titleLabel, pathBox, searchBox, localTable);
        VBox.setVgrow(localTable, Priority.ALWAYS);
        
        return panel;
    }
    
    private VBox createRemotePanel() {
        VBox panel = new VBox(5);
        panel.setPadding(new Insets(5));
        
        Label titleLabel = paneTitle(I18n.get("sftp.remoteServer", connection.getHost()));
        
        // Path field and navigation
        HBox pathBox = new HBox(5);
        remotePathField = new TextField();
        remotePathField.setEditable(true);
        remotePathField.setOnAction(e -> navigateRemote(remotePathField.getText()));
        
        Button upButton = new Button();
        styleToolbarButton(upButton, FileBrowserIcons.UP);
        upButton.setTooltip(new Tooltip(I18n.get("filebrowser.tooltip.up")));
        upButton.setOnAction(e -> navigateRemoteUp());

        Button homeButton = new Button();
        styleToolbarButton(homeButton, FileBrowserIcons.HOME);
        homeButton.setTooltip(new Tooltip(I18n.get("filebrowser.tooltip.home")));
        homeButton.setOnAction(e -> navigateRemote("~"));

        remoteHiddenToggle = createHiddenToggle(true);
        
        remoteBreadcrumb = new SftpBreadcrumbBar(remotePathField, this::navigateRemote, false);
        pathBox.setAlignment(Pos.CENTER_LEFT);
        pathBox.getChildren().addAll(new Label(I18n.get("sftp.path")), remoteBreadcrumb, upButton, homeButton,
            remoteHiddenToggle);
        
        // Search field: filters the folder as you type; with "Include subfolders", Enter searches
        // the folder and everything below it on the server, Esc stops that search.
        HBox searchBox = new HBox(5);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        remoteSearchField = new TextField();
        remoteSearchField.setPromptText(I18n.get("sftp.searchPrompt"));
        remoteRecursiveToggle = new CheckBox(I18n.get("sftp.search.recursive"));
        remoteRecursiveToggle.setTooltip(new Tooltip(I18n.get("sftp.search.recursiveTooltip")));
        remoteSameFilesystemToggle = new CheckBox(I18n.get("sftp.search.sameFilesystem"));
        remoteSameFilesystemToggle.setTooltip(new Tooltip(I18n.get("sftp.search.sameFilesystemTooltip")));
        remoteSameFilesystemToggle.setSelected(true);
        remoteSameFilesystemToggle.disableProperty().bind(remoteRecursiveToggle.selectedProperty().not());
        remoteSearchField.setOnAction(e -> {
            if (remoteRecursiveToggle.isSelected()) {
                startRemoteSearch();
            }
        });
        remoteSearchField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && handleRemoteSearchEscape()) {
                e.consume();
            }
        });
        searchBox.getChildren().addAll(new Label(I18n.get("sftp.search")), remoteSearchField,
            remoteRecursiveToggle, remoteSameFilesystemToggle);
        HBox.setHgrow(remoteSearchField, Priority.ALWAYS);
        
        // File table
        remoteTable = new TableView<>();
        remoteTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        remoteTable.getStyleClass().add("file-browser-table");
        
        // Column order: Name, Type, Size, Date, User, Group, Permissions
        TableColumn<SftpFileItem, String> nameColumn = new TableColumn<>(I18n.get("sftp.column.name"));
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameColumn.setPrefWidth(180);
        nameColumn.setMinWidth(100);
        nameColumn.setSortable(true);
        nameColumn.setComparator(String.CASE_INSENSITIVE_ORDER);
        
        TableColumn<SftpFileItem, String> typeColumn = new TableColumn<>(I18n.get("sftp.column.type"));
        typeColumn.setCellValueFactory(new PropertyValueFactory<>("type"));
        typeColumn.setPrefWidth(50);
        typeColumn.setMinWidth(40);
        typeColumn.setMaxWidth(60);
        typeColumn.setSortable(true);
        
        TableColumn<SftpFileItem, String> sizeColumn = new TableColumn<>(I18n.get("sftp.column.size"));
        // A folder shows the size "Calculate size" measured for it; owners and groups the server
        // sent as numbers show their names once they are looked up.
        sizeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(remoteSizeLabel(cell.getValue())));
        sizeColumn.setPrefWidth(80);
        sizeColumn.setMinWidth(60);
        sizeColumn.setMaxWidth(100);
        sizeColumn.setSortable(true);
        
        TableColumn<SftpFileItem, String> dateColumn = new TableColumn<>(I18n.get("sftp.column.date"));
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("date"));
        dateColumn.setPrefWidth(130);
        dateColumn.setMinWidth(110);
        dateColumn.setMaxWidth(150);
        dateColumn.setSortable(true);
        
        TableColumn<SftpFileItem, String> userColumn = new TableColumn<>(I18n.get("sftp.column.user"));
        userColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(remoteOwner(cell.getValue())));
        userColumn.setPrefWidth(70);
        userColumn.setMinWidth(50);
        userColumn.setMaxWidth(100);
        userColumn.setSortable(true);
        
        TableColumn<SftpFileItem, String> groupColumn = new TableColumn<>(I18n.get("sftp.column.group"));
        groupColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(remoteGroup(cell.getValue())));
        groupColumn.setPrefWidth(70);
        groupColumn.setMinWidth(50);
        groupColumn.setMaxWidth(100);
        groupColumn.setSortable(true);
        
        TableColumn<SftpFileItem, String> permColumn = new TableColumn<>(I18n.get("sftp.column.permissions"));
        permColumn.setCellValueFactory(new PropertyValueFactory<>("permissions"));
        permColumn.setPrefWidth(80);
        permColumn.setMinWidth(60);
        permColumn.setMaxWidth(100);
        permColumn.setSortable(true);
        
        installTypeIconCell(typeColumn);
        installTextCell(sizeColumn, Pos.CENTER_RIGHT, false);
        installTextCell(dateColumn, Pos.CENTER_LEFT, true);
        remoteTable.getColumns().addAll(java.util.List.of(nameColumn, typeColumn, sizeColumn, dateColumn, userColumn, groupColumn, permColumn));
        
        // Double-click to navigate, right-click for context menu
        remoteTable.setRowFactory(tv -> {
            TableRow<SftpFileItem> row = new TableRow<>() {
                @Override
                protected void updateItem(SftpFileItem item, boolean empty) {
                    super.updateItem(item, empty);
                    markComparison(this, empty ? null : item, true);
                }
            };
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    SftpFileItem item = row.getItem();
                    if (!item.isFile()) {
                        navigateRemote(item.getPath());
                    } else if (comparison != null) {
                        openComparedFile(item, true);
                    }
                }
            });
            installRemoteRowDrag(row);
            return row;
        });
        remoteTable.setOnDragOver(event -> onRemoteDragOver(event, null));
        remoteTable.setOnDragExited(event -> remoteTable.getStyleClass().remove(DROP_TARGET));
        remoteTable.setOnDragDropped(event -> onRemoteDragDropped(event, null));
        
        // Context menu for remote table
        ContextMenu remoteContextMenu = createRemoteContextMenu();
        remoteTable.setContextMenu(remoteContextMenu);
        installFileKeys(remoteTable, this::renameRemoteSelected, this::deleteRemoteSelected,
            () -> setShowHidden(true, !showHiddenRemote));
        
        // Setup data binding with filter and sort
        remoteItems = FXCollections.observableArrayList();
        filteredRemoteItems = new FilteredList<>(remoteItems, rowFilter("", showHiddenRemote));
        sortedRemoteItems = new javafx.collections.transformation.SortedList<>(filteredRemoteItems);
        
        installSortPolicy(remoteTable, sortedRemoteItems, typeColumn, sizeColumn, this::remoteSizeBytes);
        remoteTable.setItems(sortedRemoteItems);
        
        // Set default sort by type (directories first, alphabetically)
        typeColumn.setSortType(TableColumn.SortType.ASCENDING);
        remoteTable.getSortOrder().add(typeColumn);
        // Apply initial sort
        remoteTable.sort();
        
        // Search filter: a glob such as *.log, or a plain substring; hidden entries only when shown
        remoteSearchField.textProperty().addListener((obs, oldVal, newVal) -> applyRowFilter(true));
        
        // Remote folders are listed in the background; the overlay shows while one loads.
        remoteLoadingOverlay = FileBrowserLoadingOverlay.create();
        remoteSearchResults = new RemoteSearchResultsPane(this::leaveRemoteSearchResults);
        remoteSearchResults.setOnOpen(this::openRemoteSearchHit);
        remoteSearchResults.setVisible(false);
        remoteSearchResults.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && handleRemoteSearchEscape()) {
                e.consume();
            }
        });
        StackPane remoteStack = new StackPane(remoteTable, remoteSearchResults, remoteLoadingOverlay);
        panel.getChildren().addAll(titleLabel, pathBox, searchBox, remoteStack);
        VBox.setVgrow(remoteStack, Priority.ALWAYS);
        
        return panel;
    }
    
    /**
     * The row filter of a search field: the text is compiled once per change (see
     * {@link FileBrowserPaths#compileNameFilter}), and the parent entry {@code ..} always stays,
     * so a filtered folder can still be left.
     */
    static java.util.function.Predicate<SftpFileItem> searchFilter(String text) {
        java.util.function.Predicate<String> names = FileBrowserPaths.compileNameFilter(text);
        return item -> item.isParentEntry() || names.test(item.getName());
    }

    /** {@link #searchFilter}, without the hidden entries unless {@code showHidden}. */
    static java.util.function.Predicate<SftpFileItem> rowFilter(String text, boolean showHidden) {
        java.util.function.Predicate<SftpFileItem> search = searchFilter(text);
        return showHidden ? search : item -> !item.isHidden() && search.test(item);
    }

    private void applyRowFilter(boolean remote) {
        java.util.function.Predicate<SftpFileItem> rows = remote
            ? rowFilter(remoteSearchField.getText(), showHiddenRemote)
            : rowFilter(localSearchField.getText(), showHiddenLocal);
        if (comparison != null && compareDifferencesOnly != null && compareDifferencesOnly.isSelected()) {
            // "Differences only": what compares as the same leaves the list; anything not compared stays.
            rows = rows.and(item -> item.isParentEntry()
                || comparisonState(item, remote) != FolderComparison.State.SAME);
        }
        if (remote) {
            filteredRemoteItems.setPredicate(rows);
        } else {
            filteredLocalItems.setPredicate(rows);
        }
    }

    /** A panel's title; its dot and text take the accent colour while the panel is active. */
    private static Label paneTitle(String text) {
        Label title = new Label(text);
        title.getStyleClass().add("sftp-pane-title");
        javafx.scene.shape.Circle marker = new javafx.scene.shape.Circle(4);
        marker.getStyleClass().add("sftp-pane-marker");
        title.setGraphic(marker);
        title.setGraphicTextGap(7);
        return title;
    }

    /** The eye button beside a panel's path: shows or hides its hidden entries. */
    private ToggleButton createHiddenToggle(boolean remote) {
        ToggleButton toggle = new ToggleButton();
        styleToolbarButton(toggle, FileBrowserIcons.EYE);
        toggle.setFocusTraversable(false);
        toggle.setSelected(remote ? showHiddenRemote : showHiddenLocal);
        toggle.setOnAction(e -> setShowHidden(remote, toggle.isSelected()));
        updateHiddenToggle(toggle);
        return toggle;
    }

    private static void updateHiddenToggle(ToggleButton toggle) {
        boolean shown = toggle.isSelected();
        FileBrowserIcons.applyToolbarIcon(toggle, shown ? FileBrowserIcons.EYE : FileBrowserIcons.EYE_OFF,
            fileBrowserIconColor());
        toggle.setTooltip(new Tooltip(I18n.get(shown ? "sftp.hidden.hide" : "sftp.hidden.show")
            + " (" + TOGGLE_HIDDEN_KEYS.getDisplayText() + ")"));
    }

    /**
     * Shows or hides one panel's hidden entries (names starting with a dot, and local entries the
     * file system marks hidden) and remembers the choice for new SFTP tabs.
     */
    private void setShowHidden(boolean remote, boolean show) {
        resetAutoCloseTimer();
        ToggleButton toggle = remote ? remoteHiddenToggle : localHiddenToggle;
        toggle.setSelected(show);
        updateHiddenToggle(toggle);
        if (remote) {
            showHiddenRemote = show;
        } else {
            showHiddenLocal = show;
        }
        applyRowFilter(remote);
        GlobalSettingsManager manager = globalSettingsManagerOrNull();
        if (manager == null || manager.getSettings() == null) {
            return;
        }
        if (remote) {
            manager.getSettings().setSftpShowHiddenRemote(show);
        } else {
            manager.getSettings().setSftpShowHiddenLocal(show);
        }
        manager.scheduleSave();
    }

    private GlobalSettingsManager globalSettingsManagerOrNull() {
        try {
            return app != null ? app.getGlobalSettingsManager() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private GlobalSettings globalSettingsOrNull() {
        GlobalSettingsManager manager = globalSettingsManagerOrNull();
        return manager != null ? manager.getSettings() : null;
    }

    private void connectToSFTP() {
        connectToSFTP(null);
    }

    /**
     * Opens a new SFTP session off the FX thread. On reconnect, {@code previous} is closed there
     * first; that close is deliberate, so it is never reported as a lost connection. Host keys go
     * through the shared trust store as on the first connect, so a changed key still blocks.
     */
    private void connectToSFTP(SFTPSession previous) {
        remoteState = RemoteState.CONNECTING;
        setReconnectVisible(false);
        statusLabel.setText(I18n.get("sftp.connecting"));
        refreshActionStates();
        if (borrowedSessions != null) {
            connectBorrowed(previous);
            return;
        }
        new Thread(() -> {
            if (previous != null) {
                closeQuietly(previous);
            }
            SFTPSession session = null;
            try {
                ServerConnection connToUse = SftpConnectionSupport.connectionForSftp(connection, temporarySSHKey);

                session = new SFTPSession(connToUse, password);
                // The SFTP manager is a tab the user is looking at, so a changed host key may be
                // reviewed and replaced here.
                session.setHostKeyReplacePolicy(de.kortty.core.SshHostKeyTrustManager.ReplacePolicy.INTERACTIVE);

                // The master password is needed whatever the target's authentication (it decrypts
                // the jump server password); the key manager only for a non-temporary key login.
                if (app != null) {
                    char[] master = app.getMasterPasswordManager() != null
                        ? app.getMasterPasswordManager().getMasterPassword()
                        : null;
                    SftpConnectionSupport.configureVault(
                        session, app.getSSHKeyManager(), master, temporarySSHKey);
                }

                SFTPSession connecting = session;
                // Called on an SSHD I/O thread when the server or the network ends the session.
                session.setDisconnectListener(() -> Platform.runLater(() -> onConnectionLost(connecting)));
                // Published before connect() so a tab closed meanwhile can close it.
                sftpSession = session;
                if (closing) {
                    closeQuietly(session);
                    return;
                }
                session.connect();
                if (closing) {
                    // The tab closed while connect() ran; its close may have come too early to stop it.
                    closeQuietly(session);
                    return;
                }

                Platform.runLater(() -> onConnected(connecting));
            } catch (Exception e) {
                logger.error("Failed to connect SFTP", e);
                if (session != null) {
                    // Releases the client of a half-opened session.
                    closeQuietly(session);
                }
                Platform.runLater(() -> onConnectFailed(e));
            }
        }, "SFTP-Connect").start();
    }

    /**
     * Connect and Reconnect of a tab on a terminal pane's session: the first time with the session
     * attached before the tab opened, later by borrowing the pane's session again, off the FX
     * thread. A previous session is closed there first; closing a borrowed session never closes
     * the terminal's.
     */
    private void connectBorrowed(SFTPSession previous) {
        SFTPSession initial = pendingAttached;
        pendingAttached = null;
        new Thread(() -> {
            if (previous != null) {
                closeQuietly(previous);
            }
            SFTPSession session = initial;
            try {
                if (session == null) {
                    session = borrowedSessions.open();
                }
                SFTPSession connecting = session;
                session.setDisconnectListener(() -> Platform.runLater(() -> onConnectionLost(connecting)));
                sftpSession = session;
                if (closing) {
                    closeQuietly(session);
                    return;
                }
                if (!session.isConnected()) {
                    // The terminal's session ended before the listener was registered.
                    Platform.runLater(() -> onConnectionLost(connecting));
                    return;
                }
                Platform.runLater(() -> onConnected(connecting));
            } catch (Exception e) {
                logger.info("Could not borrow the terminal session for SFTP: {}", e.getMessage());
                if (session != null) {
                    closeQuietly(session);
                }
                Platform.runLater(() -> onConnectFailed(e));
            }
        }, "SFTP-Attach").start();
    }

    /** Whether this tab works on a terminal pane's SSH session rather than a login of its own. */
    public boolean isBorrowed() {
        return borrowedSessions != null;
    }

    /** Which tab an "open" request for the same connection or pane reuses ({@link SftpTabKeys}); may be null. */
    public String getDedupeKey() {
        return dedupeKey;
    }

    /** Closes this borrowed tab (asking first while transfers run) and opens one with its own login. */
    private void switchToOwnLogin() {
        if (ownLogin == null || closing || !confirmHostedClose()) {
            return;
        }
        Runnable openStandalone = ownLogin;
        cleanup();
        if (getTabPane() != null) {
            removeTabSafely();
        }
        if (onCloseCallback != null) {
            onCloseCallback.run();
        }
        openStandalone.run();
    }

    private void onConnected(SFTPSession session) {
        if (closing || session != sftpSession) {
            return;
        }
        remoteState = RemoteState.CONNECTED;
        setReconnectVisible(false);
        String hint = startHint;
        startHint = null;
        statusLabel.setText(hint == null
            ? I18n.get("sftp.connectedTo", connection.getHost())
            : I18n.get("sftp.connectedTo", connection.getHost()) + " \u2014 " + hint);
        transferQueueHost.onSessionReady(session);
        accountNames = new RemoteAccountNames();
        refreshActionStates();
        refreshLocal();
        refreshRemote();
    }

    private void onConnectFailed(Exception failure) {
        if (closing) {
            return;
        }
        remoteState = RemoteState.DISCONNECTED;
        statusLabel.setText(I18n.get("sftp.connectionFailed", failureMessage(failure)));
        setReconnectVisible(true);
        refreshActionStates();
        showError(I18n.get("sftp.error.connection"), I18n.get("sftp.error.connectionFailed", failureMessage(failure)));
    }

    private void onConnectionLost(SFTPSession session) {
        // A replaced session (after Reconnect) is ignored.
        if (session == sftpSession) {
            transferQueueHost.onSessionLost();
            if (!closing) {
                remoteEdits.onDisconnected();
            }
            showDisconnectedState();
        }
    }

    /**
     * "Disconnected from host" with a Reconnect button; the remote actions are disabled until
     * the connection is back. A tab that is closing never shows it.
     */
    private void showDisconnectedState() {
        if (closing) {
            return;
        }
        remoteState = RemoteState.DISCONNECTED;
        // Drop a listing that is still on its way from the dead session.
        remoteListGeneration++;
        pendingRemoteSelection = null;
        cancelRemoteSearch();
        FileBrowserLoadingOverlay.show(remoteLoadingOverlay, false);
        statusLabel.setText(I18n.get("sftp.status.disconnected", connection.getHost()));
        setReconnectVisible(true);
        refreshActionStates();
    }

    /**
     * Whether the remote side can be used right now. If not, says why: still connecting, or the
     * Disconnected state with Reconnect, so an action never fails silently.
     */
    private boolean requireConnected() {
        SFTPSession session = sftpSession;
        if (remoteState == RemoteState.CONNECTED && session != null && session.isConnected()) {
            return true;
        }
        if (remoteState == RemoteState.CONNECTING) {
            statusLabel.setText(I18n.get("sftp.connecting"));
        } else {
            showDisconnectedState();
        }
        return false;
    }

    private boolean isRemoteConnected() {
        return remoteState == RemoteState.CONNECTED;
    }

    /**
     * The session for a worker thread, or a "not connected" failure; for code that may run after
     * the connection was replaced or lost.
     */
    private SFTPSession connectedSession() throws IOException {
        SFTPSession session = sftpSession;
        if (session == null || !session.isConnected()) {
            throw new IOException(I18n.get("sftp.notConnected"));
        }
        return session;
    }

    private void reconnect() {
        if (closing || remoteState == RemoteState.CONNECTING) {
            return;
        }
        SFTPSession previous = sftpSession;
        sftpSession = null;
        connectToSFTP(previous);
    }

    private void setReconnectVisible(boolean visible) {
        if (reconnectButton != null) {
            reconnectButton.setVisible(visible);
            reconnectButton.setManaged(visible);
        }
        if (ownLoginButton != null) {
            boolean offerOwnLogin = visible && isBorrowed() && ownLogin != null;
            ownLoginButton.setVisible(offerOwnLogin);
            ownLoginButton.setManaged(offerOwnLogin);
        }
    }

    private void refreshActionStates() {
        localSelectionActions.run();
        remoteSelectionActions.run();
    }

    private static void closeQuietly(SFTPSession session) {
        try {
            session.close();
        } catch (Exception e) {
            logger.warn("Error closing SFTP session", e);
        }
    }

    private static String failureMessage(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause()
            : failure;
        String message = cause.getMessage();
        return message != null && !message.isBlank() ? message : cause.getClass().getSimpleName();
    }

    /**
     * Ends the tab: stops the auto-close timer and the listing thread and closes the session. Runs
     * from the close button and, through {@code MainWindow.disposeTabContent}, on the programmatic
     * close paths (Cmd+W, close all, window close, opening a project), where JavaFX asks no close
     * request. Does nothing the second time. FX thread.
     */
    void cleanup() {
        if (closing) {
            return;
        }
        // First, so neither the disconnect listener nor a pending listing touches the closing tab.
        closing = true;
        if (autoCloseTimer != null) {
            autoCloseTimer.stop();
        }
        remoteListExecutor.shutdownNow();
        cancelDragOut();
        deleteDragOutDirectories();
        cancelRemoteSearch();
        remoteSearchExecutor.shutdownNow();
        remoteInfoExecutor.shutdownNow();
        if (compareCancel != null) {
            compareCancel.set(true);
        }
        if (folderSizeCancellation != null) {
            folderSizeCancellation.cancel();
        }
        if (remoteExtractCancellation != null) {
            remoteExtractCancellation.cancel();
        }
        // Stops watching externally edited files and deletes their local copies.
        remoteEdits.dispose();
        // Cancels the transfers and closes their channels before the session goes.
        boolean transfersRan = transferQueueHost.needsCloseConfirmation();
        transferQueueHost.close();
        transferQueuePane.dispose();
        SFTPSession session = sftpSession;
        if (session != null) {
            if (transfersRan) {
                // Cancelled uploads delete their partial files over this session: close it once they
                // stopped (bounded), off the FX thread.
                SftpTransferQueueHost host = transferQueueHost;
                Thread closer = new Thread(() -> {
                    host.awaitStopped(TRANSFER_STOP_WAIT_MILLIS);
                    closeQuietly(session);
                }, "SFTP-Close");
                closer.setDaemon(true);
                closer.start();
            } else {
                closeQuietly(session);
            }
        }
    }

    /** How long a closing tab lets cancelled transfers remove their partial files before the session goes. */
    private static final long TRANSFER_STOP_WAIT_MILLIS = 5_000;
    
    private void removeTabSafely() {
        TabPane tabPane = getTabPane();
        if (tabPane == null) return;
        
        int currentIndex = tabPane.getTabs().indexOf(this);
        
        // Suppress QuickConnect if + tab might be selected
        de.kortty.ui.MainWindow.suppressNextQuickConnect();
        
        // Select a different tab before removing this one to avoid selecting the "+" tab
        if (currentIndex > 0) {
            // Select previous tab
            tabPane.getSelectionModel().select(currentIndex - 1);
        } else if (tabPane.getTabs().size() > 1) {
            // We're at index 0, select next tab (if it's not the "+" tab)
            Tab nextTab = tabPane.getTabs().get(1);
            if (nextTab.getText() != null && !nextTab.getText().equals("+")) {
                tabPane.getSelectionModel().select(1);
            }
        }
        
        // Now remove this tab
        tabPane.getTabs().remove(this);
    }
    
    private void refreshLocal() {
        localItems.clear();
        try {
            File[] files = currentLocalPath.toFile().listFiles();
            if (files != null) {
                // Add parent directory entry
                if (currentLocalPath.getParent() != null) {
                    localItems.add(SftpFileItem.fromDetails("..",
                        currentLocalPath.getParent().toString(), false, "—", "", "", "", "", -1));
                }

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                for (File file : files) {
                    long sizeBytes = file.isDirectory() ? -1 : file.length();
                    String size = file.isDirectory() ? "—" : formatSize(sizeBytes);
                    String date = sdf.format(new Date(file.lastModified()));
                    String permissions = getLocalFilePermissions(file.toPath());
                    String owner = getLocalFileOwner(file.toPath());
                    String group = getLocalFileGroup(file.toPath());
                    // File.isHidden: a dot name, or the hidden attribute on Windows.
                    localItems.add(SftpFileItem.fromDetails(file.getName(), file.getAbsolutePath(),
                        file.isFile(), size, date, permissions, owner, group, sizeBytes, file.isHidden()));
                }
            }
            localPathField.setText(currentLocalPath.toString());
            // Re-apply sort after refresh
            localTable.sort();
        } catch (Exception e) {
            logger.error("Failed to list local files", e);
            showError(I18n.get("error.title"), I18n.get("sftp.error.listLocalFiles", e.getMessage()));
        }
    }
    
    /**
     * Gets the file permissions as a string (e.g., "rwxr-xr-x" on Unix or "rw-" on Windows).
     */
    private String getLocalFilePermissions(Path path) {
        try {
            // Try POSIX permissions first (Unix/macOS)
            java.nio.file.attribute.PosixFileAttributes attrs = 
                Files.readAttributes(path, java.nio.file.attribute.PosixFileAttributes.class);
            return java.nio.file.attribute.PosixFilePermissions.toString(attrs.permissions());
        } catch (UnsupportedOperationException e) {
            // Windows fallback
            StringBuilder perms = new StringBuilder();
            perms.append(Files.isReadable(path) ? "r" : "-");
            perms.append(Files.isWritable(path) ? "w" : "-");
            perms.append(Files.isExecutable(path) ? "x" : "-");
            return perms.toString();
        } catch (Exception e) {
            return "";
        }
    }
    
    private void refreshRemote() {
        loadRemote(currentRemotePath);
    }

    /**
     * Lists {@code requestedPath} in the background, with the loading overlay over the table. The
     * current path and the table change only once the listing succeeded; a failure keeps the
     * previous folder. Of several requests in flight only the newest is applied.
     */
    private void loadRemote(String requestedPath) {
        if (!requireConnected()) {
            return;
        }
        SFTPSession session = sftpSession;
        String basePath = currentRemotePath;
        long generation = ++remoteListGeneration;
        FileBrowserLoadingOverlay.show(remoteLoadingOverlay, true);
        try {
            CompletableFuture
                .supplyAsync(() -> listRemote(session, requestedPath, basePath), remoteListExecutor)
                .whenComplete((listing, error) -> Platform.runLater(
                    () -> applyRemoteListing(generation, requestedPath, listing, error)));
        } catch (RejectedExecutionException e) {
            // The tab is closing and its executor no longer takes work.
            FileBrowserLoadingOverlay.show(remoteLoadingOverlay, false);
        }
    }

    /** Runs on the listing thread: resolves the path, reads the folder and builds the rows. */
    private RemoteListing listRemote(SFTPSession session, String requestedPath, String basePath) {
        try {
            String path = resolveRemoteListingPath(requestedPath, basePath, session.getCurrentDirectory());
            List<SftpClient.DirEntry> entries = session.listFiles(path);

            List<SftpFileItem> items = new ArrayList<>(entries.size() + 1);
            if (!"/".equals(path)) {
                items.add(SftpFileItem.fromDetails("..",
                    RemotePathSupport.parentRemotePath(path), false, "—", "", "", "", "", -1));
            }
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            for (SftpClient.DirEntry entry : entries) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) continue;

                SftpClient.Attributes attrs = entry.getAttributes();
                boolean isDir = attrs.isDirectory();
                long sizeBytes = isDir ? -1 : attrs.getSize();
                String size = isDir ? "—" : formatSize(sizeBytes);

                FileTime modified = attrs.getModifyTime();
                String date = modified != null ? sdf.format(new Date(modified.toMillis())) : "";

                // Get file permissions, owner and group
                String permissions = formatRemotePermissions(attrs);

                // Owner and group: the names where the server sends them (SFTP 4 and later), else the
                // UID and GID (OpenSSH speaks SFTP 3), whose names lookUpAccountNames finds later.
                String owner = attrs.getOwner();
                int ownerId = -1;
                if (owner == null || owner.isEmpty()) {
                    Integer uid = attrs.getUserId();
                    owner = uid != null ? String.valueOf(uid) : "";
                    ownerId = uid != null ? uid : -1;
                }
                String group = attrs.getGroup();
                int groupId = -1;
                if (group == null || group.isEmpty()) {
                    Integer gid = attrs.getGroupId();
                    group = gid != null ? String.valueOf(gid) : "";
                    groupId = gid != null ? gid : -1;
                }

                SftpFileItem item = SftpFileItem.fromDetails(name, RemotePathSupport.appendRemotePath(path, name),
                    !isDir, size, date, permissions, owner, group, sizeBytes);
                items.add(ownerId >= 0 || groupId >= 0 ? item.withAccountIds(ownerId, groupId) : item);
            }
            return new RemoteListing(path, items);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private void applyRemoteListing(long generation, String requestedPath, RemoteListing listing, Throwable error) {
        if (closing || generation != remoteListGeneration) {
            // Superseded by a newer request, or by a disconnect.
            return;
        }
        FileBrowserLoadingOverlay.show(remoteLoadingOverlay, false);
        if (error != null) {
            pendingRemoteSelection = null;
            // Back to the folder that is still shown.
            remotePathField.setText(currentRemotePath);
            SFTPSession session = sftpSession;
            if (session == null || !session.isConnected()) {
                showDisconnectedState();
                return;
            }
            if (restoredRemotePath != null && restoredRemotePath.equals(requestedPath)) {
                fallBackFromRestoredRemotePath(error);
                return;
            }
            logger.error("Failed to list remote files", error);
            showError(I18n.get("error.title"), I18n.get("sftp.error.listRemoteFiles", failureMessage(error)));
            return;
        }
        // The first folder shown ends the restore, whichever folder it is.
        restoredRemotePath = null;
        currentRemotePath = listing.path();
        remotePathResolved = true;
        remoteFolderSizes.clear();
        remoteItems.setAll(listing.items());
        lookUpAccountNames(listing.items());
        remotePathField.setText(currentRemotePath);
        // Re-apply sort after refresh
        remoteTable.sort();
        selectPendingRemoteEntry();
        refreshActionStates();
    }

    /** How long the owner and group name lookup may take; a server that needs longer keeps numbers. */
    private static final java.time.Duration ACCOUNT_LOOKUP_TIMEOUT = java.time.Duration.ofSeconds(8);
    /** getent can fall back to the whole /etc/passwd; more than this is not read. */
    private static final long ACCOUNT_LOOKUP_OUTPUT_CAP = 1024 * 1024;
    /** How long "Calculate size" may run before it gives up; it can be stopped any time before. */
    private static final java.time.Duration FOLDER_SIZE_TIMEOUT = java.time.Duration.ofMinutes(10);

    /**
     * Looks up, off the FX thread, the names of the numeric owners and groups in {@code items} that
     * were never looked up in this session; the server's User and Group columns show them once they
     * are known. The listing never waits for it. A server that cannot run the lookup (an SFTP-only
     * account, a shell that is not POSIX) or does not answer within {@link #ACCOUNT_LOOKUP_TIMEOUT}
     * keeps showing numbers and is not asked again.
     */
    private void lookUpAccountNames(List<SftpFileItem> items) {
        RemoteAccountNames names = accountNames;
        List<Integer> uids = new ArrayList<>();
        List<Integer> gids = new ArrayList<>();
        for (SftpFileItem item : items) {
            if (item.getOwnerId() >= 0) {
                uids.add(item.getOwnerId());
            }
            if (item.getGroupId() >= 0) {
                gids.add(item.getGroupId());
            }
        }
        SFTPSession session = sftpSession;
        if (session == null) {
            return;
        }
        RemoteAccountNames.Pending pending = names.claim(uids, gids);
        if (pending.isEmpty()) {
            return;
        }
        try {
            remoteInfoExecutor.execute(() -> {
                try {
                    RemoteCommandRunner.Result result = session.commandRunner().run(RemoteCommandRunner.Request
                        .plain(RemoteAccountNames.lookupCommand(pending))
                        .timeout(ACCOUNT_LOOKUP_TIMEOUT)
                        .outputCap(ACCOUNT_LOOKUP_OUTPUT_CAP));
                    if (!result.isSuccess()) {
                        names.markUnavailable();
                        return;
                    }
                    names.accept(result.stdoutText());
                } catch (IOException | RuntimeException e) {
                    logger.debug("Owner and group names stay numbers: {}", e.getMessage());
                    names.markUnavailable();
                    return;
                }
                Platform.runLater(() -> {
                    if (!closing && names == accountNames) {
                        remoteTable.refresh();
                        remoteTable.sort();
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            // The tab is closing.
        }
    }

    /** The owner a server row shows: its name, also when the server sent only the UID and it was looked up. */
    private String remoteOwner(SftpFileItem item) {
        return item.getOwnerId() >= 0 ? accountNames.user(item.getOwnerId()).orElse(item.getOwner()) : item.getOwner();
    }

    /** The group a server row shows: its name, also when the server sent only the GID and it was looked up. */
    private String remoteGroup(SftpFileItem item) {
        return item.getGroupId() >= 0 ? accountNames.group(item.getGroupId()).orElse(item.getGroup()) : item.getGroup();
    }

    /** A server row's size label: a folder shows the size "Calculate size" measured for it. */
    private String remoteSizeLabel(SftpFileItem item) {
        Long measured = item.isFile() ? null : remoteFolderSizes.get(item.getPath());
        return measured != null ? formatSize(measured) : item.getSize();
    }

    /** A server row's byte count for sorting by size: a folder counts with its measured size. */
    private long remoteSizeBytes(SftpFileItem item) {
        Long measured = item.isFile() ? null : remoteFolderSizes.get(item.getPath());
        return measured != null ? measured : item.getSizeBytes();
    }

    /**
     * "Calculate size": measures the selected server folders with {@code du} off the FX thread and
     * shows the sizes in the Size column; while it runs, the same menu item stops it.
     */
    private void calculateSelectedFolderSizes() {
        if (folderSizeCancellation != null) {
            folderSizeCancellation.cancel();
            return;
        }
        if (!requireConnected()) {
            return;
        }
        List<String> folders = remoteTable.getSelectionModel().getSelectedItems().stream()
            .filter(item -> item != null && !item.isFile() && !item.isParentEntry())
            .map(SftpFileItem::getPath)
            .toList();
        if (folders.isEmpty()) {
            return;
        }
        statusLabel.setText(I18n.get("sftp.folderSize.running", folders.size()));
        RemoteCommandCancellation[] own = new RemoteCommandCancellation[1];
        own[0] = measureFolderSizes(folders, (sizes, error) -> {
            if (folderSizeCancellation == own[0]) {
                folderSizeCancellation = null;
            }
            if (error instanceof de.kortty.core.remote.RemoteCommandCancelledException) {
                statusLabel.setText(I18n.get("sftp.folderSize.cancelled"));
            } else if (error != null) {
                statusLabel.setText(I18n.get("sftp.folderSize.failed", failureMessage(error)));
            } else {
                long total = sizes.values().stream().mapToLong(Long::longValue).sum();
                statusLabel.setText(I18n.get("sftp.folderSize.done", sizes.size(), formatSize(total)));
            }
        });
        folderSizeCancellation = own[0];
    }

    /**
     * Measures {@code folders} with {@code du -sk} off the FX thread. The sizes go into the Size
     * column while the folder they are in is still shown; {@code done} then runs on the FX thread
     * with the sizes (possibly fewer than asked for), or with the failure and {@code null}.
     *
     * @return the switch that stops the measurement
     */
    private RemoteCommandCancellation measureFolderSizes(List<String> folders,
                                                         java.util.function.BiConsumer<java.util.Map<String, Long>, Throwable> done) {
        SFTPSession session = sftpSession;
        String folderShown = currentRemotePath;
        RemoteCommandCancellation cancellation = new RemoteCommandCancellation();
        Thread worker = new Thread(() -> {
            java.util.Map<String, Long> sizes = null;
            Throwable failure = null;
            try {
                RemoteCommandRunner.Result result = session.commandRunner().run(RemoteCommandRunner.Request
                    .plain(RemoteFolderSizes.command(folders))
                    .timeout(FOLDER_SIZE_TIMEOUT)
                    .outputCap(ACCOUNT_LOOKUP_OUTPUT_CAP)
                    .cancellation(cancellation));
                sizes = RemoteFolderSizes.parse(result.stdoutText(), folders);
                if (sizes.isEmpty()) {
                    // du prints its totals even when it could not read everything; none at all is an error.
                    String reason = result.stderr() == null || result.stderr().isBlank()
                        ? "du exited with " + result.exitCode()
                        : result.stderr().trim();
                    sizes = null;
                    failure = new IOException(reason);
                }
            } catch (Exception e) {
                failure = e;
            }
            java.util.Map<String, Long> measured = sizes;
            Throwable error = failure;
            Platform.runLater(() -> {
                if (closing) {
                    return;
                }
                if (measured != null && folderShown.equals(currentRemotePath)) {
                    remoteFolderSizes.putAll(measured);
                    remoteTable.refresh();
                    remoteTable.sort();
                    updatePaneStatus(true);
                }
                done.accept(measured, error);
            });
        }, "SFTP-FolderSize");
        worker.setDaemon(true);
        worker.start();
        return cancellation;
    }

    /** Selects the search hit that asked for this folder, once its listing is shown. */
    private void selectPendingRemoteEntry() {
        String wanted = pendingRemoteSelection;
        pendingRemoteSelection = null;
        if (wanted == null) {
            return;
        }
        for (SftpFileItem item : remoteTable.getItems()) {
            if (wanted.equals(item.getPath())) {
                remoteTable.getSelectionModel().clearSelection();
                remoteTable.getSelectionModel().select(item);
                remoteTable.scrollTo(item);
                remoteTable.requestFocus();
                return;
            }
        }
    }

    /**
     * Starts a recursive search below the folder shown: {@code find} over an exec channel when the
     * server has it, otherwise an SFTP walk (D17). Hits arrive in batches; a newer search, leaving
     * the results or a disconnect drops the older one's batches through the generation guard.
     */
    private void startRemoteSearch() {
        if (!requireConnected() || !remotePathResolved) {
            return;
        }
        String text = remoteSearchField.getText();
        if (text == null || text.isBlank()) {
            statusLabel.setText(I18n.get("sftp.search.emptyPattern"));
            return;
        }
        RemoteSearchRequest request;
        try {
            request = RemoteSearchRequest.of(currentRemotePath, text.trim(), FileBrowserPaths.isGlobFilter(text),
                    FileBrowserPaths.compileNameFilter(text))
                .withSameFilesystem(remoteSameFilesystemToggle.isSelected());
        } catch (IllegalArgumentException e) {
            statusLabel.setText(I18n.get("sftp.search.failed", e.getMessage()));
            return;
        }
        cancelRemoteSearch();
        long generation = ++remoteSearchGeneration;
        RemoteCommandCancellation cancellation = new RemoteCommandCancellation();
        remoteSearchCancellation = cancellation;
        SFTPSession session = sftpSession;
        remoteSearchResults.clear();
        remoteSearchResults.setStatus(RemoteSearchResultsPane.runningText(request.root(), 0));
        remoteSearchResults.setVisible(true);
        try {
            CompletableFuture
                .supplyAsync(() -> runRemoteSearch(session, request, generation, cancellation), remoteSearchExecutor)
                .whenComplete((outcome, error) -> Platform.runLater(
                    () -> applyRemoteSearchOutcome(generation, request.root(), outcome, error)));
        } catch (RejectedExecutionException e) {
            // The tab is closing.
            remoteSearchCancellation = null;
        }
    }

    /** Runs on the search thread. */
    private RemoteSearchOutcome runRemoteSearch(SFTPSession session, RemoteSearchRequest request, long generation,
                                                RemoteCommandCancellation cancellation) {
        RemoteTreeReader reader = new RemoteTreeReader() {
            @Override
            public List<SftpClient.DirEntry> list(String folder) throws IOException {
                return session.listFiles(folder);
            }

            @Override
            public SftpClient.Attributes lstat(String path) throws IOException {
                return session.getLinkAttributes(path);
            }
        };
        try {
            return new RemoteSearchService(session.commandRunner(), reader).search(request,
                batch -> Platform.runLater(() -> applyRemoteSearchBatch(generation, request.root(), batch)),
                cancellation);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private void applyRemoteSearchBatch(long generation, String root, List<RemoteSearchHit> batch) {
        if (closing || generation != remoteSearchGeneration) {
            return;
        }
        remoteSearchResults.addAll(batch);
        if (remoteSearchCancellation != null) {
            remoteSearchResults.setStatus(RemoteSearchResultsPane.runningText(root, remoteSearchResults.size()));
        }
    }

    private void applyRemoteSearchOutcome(long generation, String root, RemoteSearchOutcome outcome, Throwable error) {
        if (closing || generation != remoteSearchGeneration) {
            return;
        }
        remoteSearchCancellation = null;
        if (error != null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            logger.warn("Remote search below {} failed: {}", root, cause.getClass().getSimpleName());
            remoteSearchResults.setStatus(I18n.get("sftp.search.failed", failureMessage(cause)));
            return;
        }
        logger.info("Remote search below {} ended: {} results via {} ({})", root, outcome.results(),
            outcome.strategy(), outcome.stop());
        remoteSearchResults.setStatus(RemoteSearchResultsPane.outcomeText(outcome));
    }

    /** Stops the running recursive search, if any; its hits so far stay listed. */
    private void cancelRemoteSearch() {
        RemoteCommandCancellation running = remoteSearchCancellation;
        if (running != null) {
            running.cancel();
        }
    }

    /** Esc: stops a running search, or else leaves the results. Returns whether it did something. */
    private boolean handleRemoteSearchEscape() {
        if (remoteSearchCancellation != null) {
            cancelRemoteSearch();
            return true;
        }
        if (remoteSearchResults != null && remoteSearchResults.isVisible()) {
            leaveRemoteSearchResults();
            return true;
        }
        return false;
    }

    /** Back to the folder listing; a running search is stopped and its later batches dropped. */
    private void leaveRemoteSearchResults() {
        cancelRemoteSearch();
        remoteSearchCancellation = null;
        remoteSearchGeneration++;
        if (remoteSearchResults != null) {
            remoteSearchResults.setVisible(false);
            remoteSearchResults.clear();
        }
    }

    /** Opens the folder of a hit and selects the hit there. */
    private void openRemoteSearchHit(RemoteSearchHit hit) {
        leaveRemoteSearchResults();
        pendingRemoteSelection = hit.path();
        loadRemote(hit.parentPath());
    }

    /**
     * The saved folder of a restored tab could not be listed: the tab shows the login directory
     * instead, once. A folder that no longer exists is reported in the status bar; any other
     * failure, such as missing rights, gets the usual error dialog.
     */
    private void fallBackFromRestoredRemotePath(Throwable error) {
        String savedPath = restoredRemotePath;
        restoredRemotePath = null;
        if (SftpSessionRestoreSupport.isMissingFolder(error)) {
            logger.info("Restored remote folder {} no longer exists; showing the login directory", savedPath);
            statusLabel.setText(I18n.get("sftp.restore.remotePathMissing", savedPath));
        } else {
            logger.error("Failed to list the restored remote folder {}", savedPath, error);
            showError(I18n.get("error.title"), I18n.get("sftp.error.listRemoteFiles", failureMessage(error)));
        }
        currentRemotePath = SftpSessionRestoreSupport.REMOTE_HOME;
        remotePathResolved = false;
        remotePathField.setText(currentRemotePath);
        loadRemote(currentRemotePath);
    }

    /**
     * The absolute folder to list for what the user asked for. SFTP does not expand {@code ~}, so
     * {@code ~} and {@code ~/…} are resolved against the login directory; a relative path is taken
     * relative to the folder currently shown. {@code .} and {@code ..} are resolved, so Up and the
     * {@code ..} row lead to the real parent after typing {@code ..} or {@code ../logs}.
     *
     * @param requested the typed or clicked path
     * @param basePath the folder currently shown ({@code ~} before the first listing)
     * @param home the login directory of the SFTP session
     */
    static String resolveRemoteListingPath(String requested, String basePath, String home) {
        String path = requested == null ? "" : requested.trim();
        String resolved;
        if (path.isEmpty() || "~".equals(path) || path.startsWith("~/")) {
            resolved = RemotePathSupport.resolveTargetDirectory(path.isEmpty() ? "~" : path, home);
        } else if (path.startsWith("/")) {
            resolved = path;
        } else {
            String base = basePath != null && basePath.startsWith("/") ? basePath : home;
            resolved = RemotePathSupport.appendRemotePath(base, path);
        }
        if (resolved.startsWith("/")) {
            return RemotePathSupport.normalizeAbsolutePath(resolved);
        }
        while (resolved.length() > 1 && resolved.endsWith("/")) {
            resolved = resolved.substring(0, resolved.length() - 1);
        }
        return resolved;
    }
    
    /**
     * Formats remote file permissions from SFTP attributes to a string like "rwxr-xr-x".
     */
    private String formatRemotePermissions(SftpClient.Attributes attrs) {
        try {
            int perms = attrs.getPermissions();
            StringBuilder sb = new StringBuilder();
            
            // Owner permissions
            sb.append((perms & 0400) != 0 ? "r" : "-");
            sb.append((perms & 0200) != 0 ? "w" : "-");
            sb.append((perms & 0100) != 0 ? "x" : "-");
            
            // Group permissions
            sb.append((perms & 0040) != 0 ? "r" : "-");
            sb.append((perms & 0020) != 0 ? "w" : "-");
            sb.append((perms & 0010) != 0 ? "x" : "-");
            
            // Others permissions
            sb.append((perms & 0004) != 0 ? "r" : "-");
            sb.append((perms & 0002) != 0 ? "w" : "-");
            sb.append((perms & 0001) != 0 ? "x" : "-");
            
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
    
    private void navigateLocal(String path) {
        try {
            Path newPath = Paths.get(path);
            if (Files.exists(newPath) && Files.isDirectory(newPath)) {
                currentLocalPath = newPath;
                refreshLocal();
            }
        } catch (Exception e) {
            showError(I18n.get("error.title"), I18n.get("sftp.error.invalidPath", path));
        }
    }
    
    private void navigateLocalUp() {
        if (currentLocalPath.getParent() != null) {
            currentLocalPath = currentLocalPath.getParent();
            refreshLocal();
        }
    }
    
    private void navigateRemote(String path) {
        // The current path changes only once the new folder was listed.
        leaveRemoteSearchResults();
        loadRemote(path);
    }

    private void navigateRemoteUp() {
        leaveRemoteSearchResults();
        if (remotePathResolved && !"/".equals(currentRemotePath)) {
            loadRemote(RemotePathSupport.parentRemotePath(currentRemotePath));
        }
    }

    private void uploadSelected() {
        var selected = localTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;

        // Collect items to upload (filter out "..")
        List<Path> paths = new ArrayList<>();
        for (var item : selected) {
            if (!item.isParentEntry()) {
                paths.add(Paths.get(item.getPath()));
            }
        }
        // Taken now, on the FX thread: browsing on while the upload runs must not change its target.
        uploadPaths(paths, currentRemotePath);
    }

    /**
     * Uploads local files and folders into {@code targetDir} through the transfer queue: the Upload
     * button, local rows dragged onto the remote panel and files dropped there from the desktop.
     * {@code targetDir} is the folder shown or the folder row dropped on, taken on the FX thread.
     */
    private void uploadPaths(List<Path> paths, String targetDir) {
        if (paths == null || paths.isEmpty()) return;
        if (refuseTransfer(de.kortty.policy.FileTransferGate.Route.SFTP_UPLOAD)) return;
        if (!requireConnected()) return;
        // The target must be the absolute folder a listing returned, not the unexpanded '~'.
        if (!remotePathResolved) return;

        List<Path> toUpload = paths.stream().filter(path -> path.getFileName() != null).toList();
        if (toUpload.isEmpty()) return;
        // Whether a path is a folder is checked by a transfer worker, never here on the FX thread.
        if (transferQueueHost.enqueueUpload(toUpload, targetDir).isPresent()) {
            transferQueuePane.reveal();
        }
    }

    private void downloadSelected() {
        var selected = remoteTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;

        // Collect items to download (filter out "..")
        List<SftpFileItem> itemsToDownload = new ArrayList<>();
        for (var item : selected) {
            if (!item.isParentEntry()) {
                itemsToDownload.add(item);
            }
        }
        // Taken now, on the FX thread: browsing on while the download runs must not change its target.
        downloadItems(itemsToDownload, currentLocalPath);
    }

    /**
     * Downloads remote files and folders into {@code targetDir} through the transfer queue: the
     * Download button and remote rows dragged onto the local panel, into the folder shown or the
     * folder row dropped on.
     */
    private void downloadItems(List<SftpFileItem> itemsToDownload, Path targetDir) {
        if (itemsToDownload == null || itemsToDownload.isEmpty()) return;
        downloadEntries(itemsToDownload.stream()
            .filter(item -> !item.isParentEntry())
            .map(SFTPManagerTab::remoteEntryRef)
            .toList(), targetDir);
    }

    private void downloadEntries(List<RemoteEntryRef> entries, Path targetDir) {
        if (entries.isEmpty()) return;
        if (refuseTransfer(de.kortty.policy.FileTransferGate.Route.SFTP_DOWNLOAD)) return;
        if (!requireConnected()) return;
        if (transferQueueHost.enqueueDownload(entries, targetDir).isPresent()) {
            transferQueuePane.reveal();
        }
    }

    /** The queue's view of a listed remote row; the queue checks the entry again before it acts. */
    static RemoteEntryRef remoteEntryRef(SftpFileItem item) {
        RemoteEntryRef.Type type = item.isFile() ? RemoteEntryRef.Type.FILE : RemoteEntryRef.Type.DIRECTORY;
        return new RemoteEntryRef(item.getPath(), item.getName(), type, item.isFile() ? item.getSizeBytes() : -1, null);
    }

    /** The queue's view of a remote row dragged from this tab. */
    static RemoteEntryRef remoteEntryRef(SftpDragPayload.Entry entry) {
        return new RemoteEntryRef(entry.path(), entry.name(),
            entry.directory() ? RemoteEntryRef.Type.DIRECTORY : RemoteEntryRef.Type.FILE, -1, null);
    }

    // ---------- Transfer queue ----------

    /**
     * Whether the organization's policy lets this tab copy files to or from the server (D6). Cheap:
     * the policy is resolved once at startup and never changes while korTTY runs.
     */
    private boolean fileTransferAllowed() {
        return de.kortty.policy.FileTransferGate.current(de.kortty.policy.FileTransferGate.Route.SFTP_UPLOAD).allowed();
    }

    /** The policy's refusal for {@code route}, or empty when it may copy files. */
    private static Optional<String> transferRefusal(de.kortty.policy.FileTransferGate.Route route) {
        return Optional.ofNullable(de.kortty.policy.FileTransferGate.current(route).reason());
    }

    /** Says in the status bar why {@code route} is refused and returns true, or returns false. */
    private boolean refuseTransfer(de.kortty.policy.FileTransferGate.Route route) {
        Optional<String> refusal = transferRefusal(route);
        refusal.ifPresent(statusLabel::setText);
        return refusal.isPresent();
    }

    /** The transfer list and its queue; the queue itself is made once the first session is ready. */
    private void createTransferQueue() {
        transferQueuePane = new SftpTransferQueuePane();
        String resumeScope = connection.getId() != null && !connection.getId().isBlank()
            ? connection.getId()
            : connection.getUsername() + "@" + connection.getHost() + ":" + connection.getPort();
        transferQueueHost = new SftpTransferQueueHost(
            () -> SftpTransferQueueHost.settingsFrom(
                app != null && app.getGlobalSettingsManager() != null ? app.getGlobalSettingsManager().getSettings() : null,
                de.kortty.policy.PolicyManager.effective(), resumeScope),
            () -> new FxConflictResolver(SftpConflictDialog.presenter(this::ownerWindowOrNull)),
            transferQueuePane.listener(),
            () -> transferQueueHost.queue().ifPresent(transferQueuePane::setQueue));
        transferQueuePane.setOnStatus(text -> {
            if (!closing) {
                statusLabel.setText(text);
            }
        });
        transferQueuePane.setOnItemFinished(this::refreshAfterTransfer);
        transferQueuePane.setOnBatchFinished(this::onTransferBatchFinished);
        transferRefreshDelay = new javafx.animation.PauseTransition(Duration.millis(300));
        transferRefreshDelay.setOnFinished(event -> {
            boolean local = transferRefreshLocal;
            boolean remote = transferRefreshRemote;
            transferRefreshLocal = false;
            transferRefreshRemote = false;
            if (closing) {
                return;
            }
            if (local) {
                refreshLocal();
            }
            if (remote && isRemoteConnected()) {
                refreshRemote();
            }
        });
    }

    /** Whether closing would cancel running transfers; never prompts. */
    @Override
    public boolean needsCloseConfirmation() {
        return !closing && transferQueueHost != null && transferQueueHost.needsCloseConfirmation();
    }

    /**
     * Asks whether to cancel the running transfers and close; closes nothing itself. Nothing running
     * asks nothing. The caller disposes the tab ({@link #cleanup()} cancels the transfers).
     */
    @Override
    public boolean confirmHostedClose() {
        if (!needsCloseConfirmation()) {
            return true;
        }
        int running = transferQueueHost.activeTransferCount();
        ButtonType closeButton = new ButtonType(I18n.get("sftp.queue.close.confirm"), ButtonBar.ButtonData.OK_DONE);
        ButtonType keepButton = new ButtonType(I18n.get("sftp.queue.close.keep"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, I18n.get("sftp.queue.close.content"),
            closeButton, keepButton);
        javafx.stage.Window owner = ownerWindowOrNull();
        if (owner != null) {
            confirm.initOwner(owner);
        }
        confirm.setTitle(I18n.get("sftp.queue.close.title"));
        confirm.setHeaderText(I18n.get("sftp.queue.close.header", String.valueOf(running)));
        applyDarkTheme(confirm);
        // Keeping the tab is the default: Enter must not cancel transfers by accident.
        ((Button) confirm.getDialogPane().lookupButton(closeButton)).setDefaultButton(false);
        ((Button) confirm.getDialogPane().lookupButton(keepButton)).setDefaultButton(true);
        return confirm.showAndWait().orElse(keepButton) == closeButton;
    }

    private javafx.stage.Window ownerWindowOrNull() {
        TabPane pane = getTabPane();
        return pane != null && pane.getScene() != null ? pane.getScene().getWindow() : null;
    }

    /**
     * A transfer finished: lists the folder it wrote into again, but only while that folder is the
     * one shown, and at most once per burst of finished items.
     */
    private void refreshAfterTransfer(TransferItem item) {
        if (closing) {
            return;
        }
        if (item.direction() == TransferDirection.UPLOAD) {
            String folder = item.remoteFolder();
            if (remotePathResolved && folder != null
                    && java.util.Objects.equals(RemotePathSupport.normalizeAbsolutePath(folder),
                        RemotePathSupport.normalizeAbsolutePath(currentRemotePath))) {
                transferRefreshRemote = true;
            }
        } else {
            Path folder = item.localFolder();
            if (folder != null && currentLocalPath != null
                    && folder.toAbsolutePath().normalize().equals(currentLocalPath.toAbsolutePath().normalize())) {
                transferRefreshLocal = true;
            }
        }
        if (transferRefreshLocal || transferRefreshRemote) {
            transferRefreshDelay.playFromStart();
        }
    }

    /**
     * A batch finished: the status bar says how much arrived, and failed files or linked folders
     * that were not followed are summed up once, in a window that does not block the tab.
     */
    private void onTransferBatchFinished(TransferBatch batch) {
        if (closing) {
            return;
        }
        if (syncBatchIds.remove(batch.id())) {
            compareAgainAfterSync();
        }
        statusLabel.setText(SftpTransferRowModel.batchFinished(batch));
        if (batch.policy().isCancelled()) {
            return;
        }
        String summary = SftpTransferRowModel.batchSummary(batch);
        if (summary == null) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.initModality(javafx.stage.Modality.NONE);
        javafx.stage.Window owner = ownerWindowOrNull();
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle(I18n.get("sftp.queue.summary.title"));
        alert.setHeaderText(batch.direction() == TransferDirection.UPLOAD
            ? I18n.get("sftp.error.upload")
            : I18n.get("sftp.error.download"));
        alert.setContentText(summary);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        applyDarkTheme(alert);
        alert.show();
    }

    /**
     * "Remove leftover partial files" at the end of a panel's context menu: deletes the
     * {@code *.kortty-part} files in the folder shown, except those a running transfer writes.
     */
    private void addRemovePartsItem(ContextMenu menu, boolean remote) {
        MenuItem item = new MenuItem(I18n.get("sftp.contextMenu.removeParts"));
        item.setOnAction(e -> {
            resetAutoCloseTimer();
            removeLeftoverParts(remote);
        });
        menu.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWING,
            e -> item.setDisable(leftoverParts(remote).isEmpty()));
        menu.getItems().addAll(new SeparatorMenuItem(), item);
    }

    /** The part files listed in the folder shown that no running transfer writes (FX thread). */
    private List<SftpFileItem> leftoverParts(boolean remote) {
        if (remote && (!isRemoteConnected() || !remotePathResolved)) {
            return List.of();
        }
        List<SftpFileItem> listed = remote ? remoteItems : localItems;
        String folder = remote ? currentRemotePath : currentLocalPath.toAbsolutePath().normalize().toString();
        java.util.Set<String> active = transferQueueHost.activePartNames(
            remote ? TransferDirection.UPLOAD : TransferDirection.DOWNLOAD, folder);
        return listed.stream()
            .filter(entry -> entry.isFile() && !entry.isParentEntry() && PartFiles.isPartName(entry.getName()))
            .filter(entry -> !active.contains(entry.getName()))
            .toList();
    }

    private void removeLeftoverParts(boolean remote) {
        List<SftpFileItem> parts = leftoverParts(remote);
        if (parts.isEmpty()) {
            return;
        }
        String folder = remote ? currentRemotePath : currentLocalPath.toString();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("sftp.parts.confirm.title"));
        confirm.setHeaderText(I18n.get("sftp.parts.confirm.header", String.valueOf(parts.size()), folder));
        confirm.setContentText(I18n.get("sftp.parts.confirm.content"));
        applyDarkTheme(confirm);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        SFTPSession session = sftpSession;
        Path localFolder = currentLocalPath;
        new Thread(() -> {
            int removed = 0;
            List<String> failures = new ArrayList<>();
            for (SftpFileItem part : parts) {
                try {
                    if (remote) {
                        removeRemotePart(session, part.getPath());
                    } else {
                        removeLocalPart(localChild(localFolder, part.getName()));
                    }
                    removed++;
                } catch (Exception e) {
                    logger.warn("Could not remove the partial file {}", part.getName(), e);
                    failures.add(I18n.get("sftp.parts.removeFailed", part.getName(), failureMessage(e)));
                }
            }
            int count = removed;
            Platform.runLater(() -> {
                if (closing) {
                    return;
                }
                statusLabel.setText(I18n.get("sftp.parts.removed", String.valueOf(count)));
                if (remote) {
                    if (isRemoteConnected()) {
                        refreshRemote();
                    }
                } else {
                    refreshLocal();
                }
                if (!failures.isEmpty()) {
                    showError(I18n.get("sftp.parts.confirm.title"), String.join("\n", failures));
                }
            });
        }, "SFTP-RemoveParts").start();
    }

    /** Deletes a remote part file; a link at its name is removed itself, never followed. */
    private static void removeRemotePart(SFTPSession session, String path) throws IOException {
        if (session == null || !session.isConnected()) {
            throw new IOException(I18n.get("sftp.notConnected"));
        }
        SftpClient client = session.primaryClient();
        SftpClient.Attributes attributes = client.lstat(path);
        if (attributes.isDirectory()) {
            throw new IOException(I18n.get("sftp.error.targetIsDirectory", path));
        }
        client.remove(path);
    }

    /** Deletes a local part file; a link at its name is removed itself, never followed. */
    private static void removeLocalPart(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(I18n.get("sftp.error.targetIsDirectory", path.toString()));
        }
        Files.deleteIfExists(path);
    }

    /**
     * The entry {@code name} inside the local {@code folder}. Names come from the server, so one
     * that would end up elsewhere ({@code ..}, {@code a/b}, an absolute path, {@code a\b} on
     * Windows) is refused instead of being written outside the folder, and so is, on Windows, a
     * reserved device name or a name Windows would alter (see {@code LocalNames}).
     */
    static Path localChild(Path folder, String name) throws IOException {
        return de.kortty.core.sftp.transfer.LocalNames.localChild(folder, name);
    }

    private void copyLocalSelected() {
        var selected = localTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;
        List<SftpFileItem> items = selected.stream()
            .filter(item -> !"..".equals(item.getName()))
            .toList();
        if (items.isEmpty()) return;

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("sftp.selectTargetFolder"));
        chooser.setInitialDirectory(currentLocalPath.toFile());

        File targetDir = chooser.showDialog(null);
        if (targetDir == null) {
            return;
        }
        Path target = targetDir.toPath();

        // Copy off the FX thread: a large tree would otherwise freeze the window.
        statusProgressBar.setVisible(true);
        statusProgressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        CompletableFuture
            .supplyAsync(() -> {
                List<String> failures = new ArrayList<>();
                for (SftpFileItem item : items) {
                    Platform.runLater(() -> statusLabel.setText(I18n.get("sftp.copying", item.getName())));
                    try {
                        copyLocalItem(item, target);
                    } catch (Exception e) {
                        logger.error("Local copy failed: {}", item.getPath(), e);
                        failures.add(item.getName() + ": " + failureMessage(e));
                    }
                }
                return failures;
            })
            .whenComplete((failures, error) -> Platform.runLater(() -> finishLocalCopy(
                items.stream().map(SftpFileItem::getName).toList(), failures, error)));
    }

    /** Ends a background local copy: hides the progress, lists the folder again and reports. */
    private void finishLocalCopy(List<String> copiedNames, List<String> failures, Throwable error) {
        statusProgressBar.setVisible(false);
        statusProgressBar.setProgress(0);
        refreshLocal();
        if (error != null) {
            showError(I18n.get("sftp.error.copy"), failureMessage(error));
        } else if (!failures.isEmpty()) {
            statusLabel.setText(I18n.get("sftp.error.copy"));
            showError(I18n.get("sftp.error.copy"), String.join("\n", failures));
        } else if (!copiedNames.isEmpty()) {
            statusLabel.setText(I18n.get("sftp.copied", String.join(", ", copiedNames)));
        }
    }

    /** What a copy of dropped files did: the names copied and the failures, one line each. */
    private record LocalCopyResult(List<String> copied, List<String> failures) { }

    /**
     * Copies files dropped from outside the panel (Finder, Explorer, the file browser sidebar, or
     * the prepared files of another SFTP tab) into a local folder, in the background. An existing
     * name gets a " (2)" suffix instead of being replaced; a file dropped onto the folder it is in
     * is left alone.
     */
    private void copyIntoLocal(List<File> files, Path targetDir) {
        List<Path> sources = files.stream().map(File::toPath).toList();
        Path target = targetDir.toAbsolutePath().normalize();
        statusProgressBar.setVisible(true);
        statusProgressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        CompletableFuture
            .supplyAsync(() -> {
                List<String> copied = new ArrayList<>();
                List<String> failures = new ArrayList<>();
                for (Path source : sources) {
                    Path fileName = source.getFileName();
                    Path parent = source.toAbsolutePath().normalize().getParent();
                    if (fileName == null || target.equals(parent)) {
                        continue;
                    }
                    String name = fileName.toString();
                    Platform.runLater(() -> statusLabel.setText(I18n.get("sftp.copying", name)));
                    try {
                        Path destination = FileBrowserPaths.uniqueDestination(target, name);
                        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                            if (isLocalCopyIntoItself(source, destination)) {
                                throw new IOException(I18n.get("sftp.error.copyIntoItself", source, destination));
                            }
                            copyDirectory(source, destination);
                        } else {
                            Files.copy(source, destination);
                        }
                        copied.add(name);
                    } catch (IOException | RuntimeException e) {
                        logger.error("Copying the dropped {} into {} failed", source, target, e);
                        failures.add(name + ": " + failureMessage(e));
                    }
                }
                return new LocalCopyResult(copied, failures);
            })
            .whenComplete((result, error) -> Platform.runLater(() -> finishLocalCopy(
                result == null ? List.of() : result.copied(),
                result == null ? List.of() : result.failures(),
                error)));
    }

    private void copyLocalItem(SftpFileItem item, Path targetDir) throws IOException {
        Path source = Paths.get(item.getPath());
        Path target = targetDir.resolve(item.getName());
        if (isLocalCopyIntoItself(source, target)) {
            throw new IOException(I18n.get("sftp.error.copyIntoItself", source, target));
        }
        if (item.isFile()) {
            Files.copy(source, target);
        } else {
            copyDirectory(source, target);
        }
    }

    /**
     * Whether {@code target} is {@code source} or lies inside it. Onto itself a file copy silently
     * does nothing; a folder copied into itself would keep walking the copies it just made.
     */
    static boolean isLocalCopyIntoItself(Path source, Path target) {
        return target.toAbsolutePath().normalize().startsWith(source.toAbsolutePath().normalize());
    }

    /** Copies a tree; entries that fail are skipped, and the first failure is thrown at the end. */
    private void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        IOException firstFailure = null;
        try (var stream = Files.walk(source)) {
            for (Path sourcePath : (Iterable<Path>) stream::iterator) {
                try {
                    Path targetPath = target.resolve(source.relativize(sourcePath));
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.copy(sourcePath, targetPath);
                    }
                } catch (IOException e) {
                    logger.error("Error copying {}", sourcePath, e);
                    if (firstFailure == null) {
                        firstFailure = e;
                    }
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }
    
    private void copyRemoteSelected() {
        var selected = remoteTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;
        if (!requireConnected()) return;
        List<SftpFileItem> items = selected.stream()
            .filter(item -> !"..".equals(item.getName()))
            .toList();

        TextInputDialog dialog = new TextInputDialog(currentRemotePath);
        dialog.setTitle(I18n.get("sftp.remoteCopy"));
        dialog.setHeaderText(I18n.get("sftp.remoteCopy.header"));
        dialog.setContentText(I18n.get("sftp.path"));
        applyDarkTheme(dialog);

        dialog.showAndWait().ifPresent(targetPath -> {
            for (var item : items) {
                copyRemoteFile(item, targetPath);
            }
        });
    }

    private void copyRemoteFile(SftpFileItem item, String targetPath) {
        if (!requireConnected()) return;
        SFTPSession session = sftpSession;

        new Thread(() -> {
            try {
                String target = RemotePathSupport.appendRemotePath(targetPath.trim(), item.getName());

                session.copyFile(item.getPath(), target);

                Platform.runLater(() -> {
                    statusLabel.setText(I18n.get("sftp.remoteCopied", item.getName()));
                    refreshRemote();
                });
            } catch (Exception e) {
                logger.error("Remote copy failed", e);
                Platform.runLater(() -> {
                    if (requireConnected()) {
                        showError(I18n.get("sftp.error.remoteCopy"), failureMessage(e));
                    }
                });
            }
        }, "SFTP-RemoteCopy").start();
    }
    
    // ---------- Drag and drop ----------

    /** The style class of the panel or folder row a drag would drop into. */
    private static final String DROP_TARGET = "drop-target";

    /**
     * The drag format of remote rows (see {@link SftpDragPayload}). Created on first use, through a
     * lookup first: a {@link DataFormat} exists once per JVM, and a second {@code new} would throw.
     */
    private static final class DragFormats {
        static final DataFormat REMOTE_ITEMS = lookupOrCreate("application/x-kortty-sftp-remote-items");

        private static DataFormat lookupOrCreate(String mimeType) {
            DataFormat existing = DataFormat.lookupMimeType(mimeType);
            return existing != null ? existing : new DataFormat(mimeType);
        }
    }

    /** A local row starts a drag of the selection as files and takes drops when it is a folder. */
    private void installLocalRowDrag(TableRow<SftpFileItem> row) {
        SftpTransferQueuePane.markPartRows(row);
        row.setOnDragDetected(event -> startLocalDrag(row, event));
        row.setOnDragOver(event -> onLocalDragOver(event, row));
        row.setOnDragExited(event -> row.getStyleClass().remove(DROP_TARGET));
        row.setOnDragDropped(event -> onLocalDragDropped(event, row));
        // Ours: MainWindow treats a DRAG_DONE that reaches the tab pane as the end of a tab drag.
        row.setOnDragDone(DragEvent::consume);
    }

    /** A remote row starts a drag of the selection and takes drops when it is a folder. */
    private void installRemoteRowDrag(TableRow<SftpFileItem> row) {
        SftpTransferQueuePane.markPartRows(row);
        row.setOnDragDetected(event -> startRemoteDrag(row, event));
        row.setOnDragOver(event -> onRemoteDragOver(event, row));
        row.setOnDragExited(event -> row.getStyleClass().remove(DROP_TARGET));
        row.setOnDragDropped(event -> onRemoteDragDropped(event, row));
        row.setOnDragDone(DragEvent::consume);
    }

    /**
     * The rows a drag from {@code row} carries: the selection, or the row alone when it is not part
     * of the selection. Never the parent entry {@code ..}.
     */
    private static List<SftpFileItem> dragSelection(TableView<SftpFileItem> table, TableRow<SftpFileItem> row) {
        SftpFileItem item = row.getItem();
        if (row.isEmpty() || item == null || item.isParentEntry()) {
            return List.of();
        }
        if (!table.getSelectionModel().isSelected(row.getIndex())) {
            table.getSelectionModel().clearAndSelect(row.getIndex());
        }
        return table.getSelectionModel().getSelectedItems().stream()
            .filter(selected -> selected != null && !selected.isParentEntry())
            .toList();
    }

    /** The folder entry of {@code row}, or {@code null} for a file row, the parent entry or no row. */
    private static SftpFileItem folderRow(TableRow<SftpFileItem> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        SftpFileItem item = row.getItem();
        return item != null && !item.isFile() && !item.isParentEntry() ? item : null;
    }

    /** Whether the drag started on a row of {@code table}. */
    private static boolean startedIn(Object gestureSource, TableView<SftpFileItem> table) {
        return gestureSource instanceof TableRow<?> row && row.getTableView() == table;
    }

    /** Marks the folder row a drag is over, or the whole panel when {@code row} is {@code null}. */
    private static void showDropTarget(TableView<SftpFileItem> table, TableRow<SftpFileItem> row) {
        if (row == null) {
            if (!table.getStyleClass().contains(DROP_TARGET)) {
                table.getStyleClass().add(DROP_TARGET);
            }
        } else {
            table.getStyleClass().remove(DROP_TARGET);
            if (!row.getStyleClass().contains(DROP_TARGET)) {
                row.getStyleClass().add(DROP_TARGET);
            }
        }
    }

    private static void clearDropTarget(TableView<SftpFileItem> table, TableRow<SftpFileItem> row) {
        table.getStyleClass().remove(DROP_TARGET);
        if (row != null) {
            row.getStyleClass().remove(DROP_TARGET);
        }
    }

    /** The remote entries of a drag that started in this tab; empty for any other drag. */
    private Optional<List<SftpDragPayload.Entry>> ownRemoteEntries(Dragboard dragboard) {
        if (!dragboard.hasContent(DragFormats.REMOTE_ITEMS)) {
            return Optional.empty();
        }
        return dragboard.getContent(DragFormats.REMOTE_ITEMS) instanceof String text
            ? SftpDragPayload.decode(text, dragSourceId)
            : Optional.empty();
    }

    private void startLocalDrag(TableRow<SftpFileItem> row, javafx.scene.input.MouseEvent event) {
        List<SftpFileItem> items = dragSelection(localTable, row);
        if (items.isEmpty()) {
            return;
        }
        resetAutoCloseTimer();
        // As files: the remote panel uploads them, the desktop and other programs copy them.
        ClipboardContent content = new ClipboardContent();
        content.putFiles(items.stream().map(item -> new File(item.getPath())).toList());
        Dragboard dragboard = row.startDragAndDrop(TransferMode.COPY);
        dragboard.setContent(content);
        event.consume();
    }

    /**
     * Starts a drag of remote rows. It always carries the entries, so the local panel of this tab
     * downloads any selection, folders included. A small selection of files is also downloaded
     * into a temporary folder first, so the desktop and other programs can take it as files.
     */
    private void startRemoteDrag(TableRow<SftpFileItem> row, javafx.scene.input.MouseEvent event) {
        if (!isRemoteConnected()) {
            return;
        }
        List<SftpFileItem> items = dragSelection(remoteTable, row);
        if (items.isEmpty()) {
            return;
        }
        resetAutoCloseTimer();
        ClipboardContent content = new ClipboardContent();
        content.put(DragFormats.REMOTE_ITEMS, SftpDragPayload.encode(dragSourceId, items.stream()
            .map(item -> new SftpDragPayload.Entry(item.getPath(), !item.isFile()))
            .toList()));
        // Under a policy that forbids file transfer the drag stays inside the window, where drops refuse too.
        List<File> prepared = refuseTransfer(de.kortty.policy.FileTransferGate.Route.SFTP_DRAG_OUT)
            ? List.of() : prepareDragOut(items);
        if (!prepared.isEmpty()) {
            content.putFiles(prepared);
        }
        Dragboard dragboard = row.startDragAndDrop(TransferMode.COPY);
        dragboard.setContent(content);
        event.consume();
    }

    /**
     * Downloads a small selection of remote files into a new temporary folder so the drag can offer
     * them outside the window. JavaFX cannot offer files that are fetched only on drop, so this
     * waits on the FX thread, for at most {@link SftpDragOutPolicy#MAX_WAIT}. For folders, a large
     * selection, a timeout or a failure it returns no files and says why in the status bar; the drag
     * then works inside the window only.
     */
    private List<File> prepareDragOut(List<SftpFileItem> items) {
        // The copies of the previous drag were dropped by now; a download still running for it stops.
        cancelDragOut();
        deleteDragOutDirectories();
        if (SftpDragOutPolicy.check(items) != SftpDragOutPolicy.Verdict.ALLOWED) {
            statusLabel.setText(I18n.get("sftp.dragOut.tooLarge",
                String.valueOf(SftpDragOutPolicy.MAX_FILES), formatSize(SftpDragOutPolicy.MAX_TOTAL_BYTES)));
            return List.of();
        }
        SFTPSession session = sftpSession;
        if (session == null || !session.isConnected()) {
            return List.of();
        }
        Path directory;
        try {
            directory = Files.createTempDirectory("kortty-sftp-drag-");
        } catch (IOException e) {
            logger.warn("Could not create a folder for dragging remote files out", e);
            statusLabel.setText(I18n.get("sftp.dragOut.failed", failureMessage(e)));
            return List.of();
        }
        dragOutDirectories.add(directory);
        TransferCancellation cancel = TransferCancellation.create();
        dragOutCancel = cancel;
        FutureTask<List<File>> download = new FutureTask<>(() -> downloadForDragOut(session, items, directory, cancel));
        Thread worker = new Thread(download, "SFTP-DragOut");
        worker.setDaemon(true);
        worker.start();
        try {
            return download.get(SftpDragOutPolicy.MAX_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Stops the download in the middle of its file; it deletes what it wrote.
            cancel.cancel();
            download.cancel(true);
            logger.info("Remote files for a drag out of the window took longer than {}", SftpDragOutPolicy.MAX_WAIT);
            statusLabel.setText(I18n.get("sftp.dragOut.timeout",
                String.valueOf(SftpDragOutPolicy.MAX_WAIT.toSeconds())));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof DragOutTooLarge) {
                logger.info("Remote files for a drag out of the window resolve to more than the caps allow");
                statusLabel.setText(I18n.get("sftp.dragOut.tooLarge",
                    String.valueOf(SftpDragOutPolicy.MAX_FILES), formatSize(SftpDragOutPolicy.MAX_TOTAL_BYTES)));
            } else {
                logger.warn("Could not download remote files for a drag out of the window", e.getCause());
                statusLabel.setText(I18n.get("sftp.dragOut.failed", failureMessage(e.getCause())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel.cancel();
            download.cancel(true);
        }
        return List.of();
    }

    /** Stops a drag-out download that is still running (the next drag, the tab closes). */
    private void cancelDragOut() {
        TransferCancellation cancel = dragOutCancel;
        dragOutCancel = null;
        if (cancel != null) {
            cancel.cancel();
        }
    }

    /** The dragged names resolve to a folder, a device or more bytes than a drag out of the window may carry. */
    static final class DragOutTooLarge extends IOException {
        DragOutTooLarge(SftpDragOutPolicy.Verdict verdict) {
            super("Not offered outside the window: " + verdict);
        }
    }

    /**
     * Runs on the drag-out worker: one download after another into {@code directory}. First every
     * name is resolved on the server and checked against the caps once more
     * ({@link SftpDragOutPolicy#checkResolved}): the listing shows a symbolic link with the size of
     * the link, so a link to a large file or to {@code /dev/zero} would otherwise be copied. The
     * downloads go through {@link SFTPSession#downloadNewFile}, so {@code cancel} (the wait ran out,
     * the tab closed) stops one in the middle of a file and deletes what it wrote.
     */
    static List<File> downloadForDragOut(SFTPSession session, List<SftpFileItem> items, Path directory,
            TransferCancellation cancel) throws IOException {
        List<SftpDragOutPolicy.Resolved> resolved = new ArrayList<>(items.size());
        for (SftpFileItem item : items) {
            cancel.throwIfCancelled();
            SftpClient.Attributes attributes = session.getAttributes(item.getPath());
            var flags = attributes.getFlags();
            resolved.add(new SftpDragOutPolicy.Resolved(
                flags.contains(SftpClient.Attribute.Perms) ? attributes.getPermissions() : 0,
                flags.contains(SftpClient.Attribute.Size) ? attributes.getSize() : -1));
        }
        SftpDragOutPolicy.Verdict verdict = SftpDragOutPolicy.checkResolved(resolved);
        if (verdict != SftpDragOutPolicy.Verdict.ALLOWED) {
            throw new DragOutTooLarge(verdict);
        }
        List<File> files = new ArrayList<>(items.size());
        for (SftpFileItem item : items) {
            cancel.throwIfCancelled();
            Path target = localChild(directory, item.getName());
            session.downloadNewFile(item.getPath(), target, cancel);
            files.add(target.toFile());
        }
        return files;
    }

    /** Deletes the temporary folders of earlier drags out of the window; a busy one is retried later. */
    private void deleteDragOutDirectories() {
        dragOutDirectories.removeIf(SFTPManagerTab::deleteTreeQuietly);
    }

    /** Deletes a temporary tree; {@code true} once nothing of it is left. */
    private static boolean deleteTreeQuietly(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException | java.io.UncheckedIOException e) {
            logger.debug("Could not delete the drag-out folder {} yet", root, e);
            return false;
        }
    }

    /**
     * The local panel takes this tab's remote rows (downloaded into the folder) and files from
     * anywhere else (copied); never its own rows.
     */
    private boolean acceptsLocalDrop(DragEvent event) {
        if (startedIn(event.getGestureSource(), localTable)) {
            return false;
        }
        Dragboard dragboard = event.getDragboard();
        return localDropAccepted(false, isRemoteConnected(), ownRemoteEntries(dragboard).isPresent(),
            dragboard.hasFiles(), fileTransferAllowed());
    }

    /**
     * The local panel's answer for a drag, as a pure decision. Its own rows never; this tab's remote
     * rows only while connected and while the policy allows file transfer (they are downloaded), so
     * under a denial the drag is rejected over the panel instead of refused after the drop. Files
     * from the desktop or another program are a local copy and stay accepted (D6).
     */
    static boolean localDropAccepted(boolean startedHere, boolean remoteConnected, boolean ownRemoteEntries,
            boolean hasFiles, boolean transferAllowed) {
        if (startedHere) {
            return false;
        }
        if (ownRemoteEntries && remoteConnected) {
            return transferAllowed;
        }
        return hasFiles;
    }

    private void onLocalDragOver(DragEvent event, TableRow<SftpFileItem> row) {
        if (row != null && folderRow(row) == null) {
            // A file row: the table below takes the drag, for the folder shown.
            return;
        }
        if (acceptsLocalDrop(event)) {
            event.acceptTransferModes(TransferMode.COPY);
            showDropTarget(localTable, row);
            event.consume();
        }
    }

    private void onLocalDragDropped(DragEvent event, TableRow<SftpFileItem> row) {
        SftpFileItem folder = folderRow(row);
        if (row != null && folder == null) {
            return;
        }
        clearDropTarget(localTable, row);
        boolean accepted = false;
        if (acceptsLocalDrop(event)) {
            resetAutoCloseTimer();
            Path target = folder != null ? Paths.get(folder.getPath()) : currentLocalPath;
            Dragboard dragboard = event.getDragboard();
            Optional<List<SftpDragPayload.Entry>> remote = ownRemoteEntries(dragboard);
            if (remote.isPresent() && isRemoteConnected()) {
                // Downloaded again in the background, folders included; the prepared copies are for the desktop.
                downloadEntries(remote.get().stream().map(SFTPManagerTab::remoteEntryRef).toList(), target);
            } else {
                copyIntoLocal(dragboard.getFiles(), target);
            }
            accepted = true;
        }
        event.setDropCompleted(accepted);
        event.consume();
    }

    /**
     * The remote panel takes files (uploaded into the folder shown or the folder row): local rows,
     * the desktop, other programs, and the prepared files of another SFTP tab; never its own rows.
     */
    private boolean acceptsRemoteDrop(DragEvent event) {
        return remoteDropAccepted(startedIn(event.getGestureSource(), remoteTable), event.getDragboard().hasFiles(),
            isRemoteConnected(), remotePathResolved, fileTransferAllowed());
    }

    /**
     * The remote panel's answer for a drag, as a pure decision: every drop onto it is an upload, so
     * while the policy denies file transfer it rejects the drag over the panel (no copy cursor, no
     * drop highlight) instead of refusing after the drop.
     */
    static boolean remoteDropAccepted(boolean startedHere, boolean hasFiles, boolean remoteConnected,
            boolean remotePathResolved, boolean transferAllowed) {
        return !startedHere && hasFiles && remoteConnected && remotePathResolved && transferAllowed;
    }

    /** Whether the Upload button is usable: a selection, a resolved remote folder and the policy's consent. */
    static boolean uploadEnabled(boolean hasSelection, boolean remoteConnected, boolean remotePathResolved,
            boolean transferAllowed) {
        return hasSelection && remoteConnected && remotePathResolved && transferAllowed;
    }

    /** Whether the Download button is usable: a usable remote selection and the policy's consent. */
    static boolean downloadEnabled(boolean usableRemoteSelection, boolean transferAllowed) {
        return usableRemoteSelection && transferAllowed;
    }

    private void onRemoteDragOver(DragEvent event, TableRow<SftpFileItem> row) {
        if (row != null && folderRow(row) == null) {
            return;
        }
        if (acceptsRemoteDrop(event)) {
            event.acceptTransferModes(TransferMode.COPY);
            showDropTarget(remoteTable, row);
            event.consume();
        }
    }

    private void onRemoteDragDropped(DragEvent event, TableRow<SftpFileItem> row) {
        SftpFileItem folder = folderRow(row);
        if (row != null && folder == null) {
            return;
        }
        clearDropTarget(remoteTable, row);
        boolean accepted = false;
        if (acceptsRemoteDrop(event)) {
            resetAutoCloseTimer();
            String target = folder != null ? folder.getPath() : currentRemotePath;
            uploadPaths(event.getDragboard().getFiles().stream().map(File::toPath).toList(), target);
            accepted = true;
        }
        event.setDropCompleted(accepted);
        event.consume();
    }

    private ContextMenu createLocalContextMenu() {
        ContextMenu menu = new ContextMenu();
        
        MenuItem copyItem = new MenuItem(I18n.get("sftp.contextMenu.copy"));
        copyItem.setOnAction(e -> {
            resetAutoCloseTimer();
            copyLocalSelected();
        });
        
        MenuItem deleteItem = new MenuItem(I18n.get("sftp.contextMenu.delete"));
        deleteItem.setOnAction(e -> {
            resetAutoCloseTimer();
            deleteLocalSelected();
        });

        MenuItem renameItem = new MenuItem(I18n.get("sftp.rename"));
        renameItem.setOnAction(e -> {
            resetAutoCloseTimer();
            renameLocalSelected();
        });

        MenuItem newFolderItem = new MenuItem(I18n.get("filebrowser.context.newFolder"));
        newFolderItem.setOnAction(e -> {
            resetAutoCloseTimer();
            createLocalFolder();
        });
        
        MenuItem ownerItem = new MenuItem(I18n.get("sftp.contextMenu.setOwner"));
        ownerItem.setOnAction(e -> {
            resetAutoCloseTimer();
            setLocalOwnerPermissionsDialog();
        });
        
        MenuItem compareItem = new MenuItem(I18n.get("sftp.contextMenu.compare"));
        compareItem.setOnAction(e -> {
            resetAutoCloseTimer();
            setActivePane(false);
            compareSelection();
        });

        MenuItem archiveItem = new MenuItem(I18n.get("sftp.contextMenu.archive"));
        archiveItem.setOnAction(e -> {
            resetAutoCloseTimer();
            createLocalArchive();
        });
        
        MenuItem editWithSnippetEditorItem = new MenuItem(I18n.get("sftp.contextMenu.editWithSnippetEditor"));
        editWithSnippetEditorItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openSelectedLocalFileInSnippetEditor();
        });
        
        MenuItem openImageItem = new MenuItem(I18n.get("sftp.contextMenu.openImage"));
        openImageItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openLocalImage();
        });
        
        menu.getItems().addAll(
            copyItem, renameItem, deleteItem,
            new SeparatorMenuItem(),
            newFolderItem,
            new SeparatorMenuItem(),
            ownerItem, compareItem,
            new SeparatorMenuItem(), 
            archiveItem,
            new SeparatorMenuItem(),
            editWithSnippetEditorItem, openImageItem
        );
        
        // Disable items when nothing is selected
        menu.setOnShowing(e -> {
            var selected = localTable.getSelectionModel().getSelectedItems();
            boolean hasSelection = selected != null && !selected.isEmpty() && 
                !(selected.size() == 1 && selected.get(0).getName().equals(".."));
            boolean isSingleFile = hasSelection && selected.size() == 1 && selected.get(0).isFile();
            boolean isImageFile = isSingleFile && isImageFileType(selected.get(0).getName());
            
            copyItem.setDisable(!hasSelection);
            renameItem.setDisable(singleNamedSelection(localTable) == null);
            deleteItem.setDisable(!hasSelection);
            ownerItem.setDisable(!hasSelection);
            archiveItem.setDisable(!hasSelection);
            compareItem.setDisable(compareCancel != null);
            editWithSnippetEditorItem.setDisable(!isSingleFile);
            openImageItem.setDisable(!isImageFile);
        });

        addRemovePartsItem(menu, false);
        return menu;
    }

    private ContextMenu createRemoteContextMenu() {
        ContextMenu menu = new ContextMenu();
        
        MenuItem copyItem = new MenuItem(I18n.get("sftp.contextMenu.copy"));
        copyItem.setOnAction(e -> {
            resetAutoCloseTimer();
            copyRemoteSelected();
        });
        
        MenuItem deleteItem = new MenuItem(I18n.get("sftp.contextMenu.delete"));
        deleteItem.setOnAction(e -> {
            resetAutoCloseTimer();
            deleteRemoteSelected();
        });

        MenuItem renameItem = new MenuItem(I18n.get("sftp.rename"));
        renameItem.setOnAction(e -> {
            resetAutoCloseTimer();
            renameRemoteSelected();
        });

        MenuItem newFolderItem = new MenuItem(I18n.get("filebrowser.context.newFolder"));
        newFolderItem.setOnAction(e -> {
            resetAutoCloseTimer();
            createRemoteFolder();
        });
        
        MenuItem ownerItem = new MenuItem(I18n.get("sftp.contextMenu.setOwner"));
        ownerItem.setOnAction(e -> {
            resetAutoCloseTimer();
            setOwnerPermissionsDialog();
        });
        
        MenuItem calculateSizeItem = new MenuItem(I18n.get("sftp.contextMenu.calculateSize"));
        calculateSizeItem.setOnAction(e -> {
            resetAutoCloseTimer();
            calculateSelectedFolderSizes();
        });

        MenuItem compareItem = new MenuItem(I18n.get("sftp.contextMenu.compare"));
        compareItem.setOnAction(e -> {
            resetAutoCloseTimer();
            setActivePane(true);
            compareSelection();
        });

        MenuItem archiveItem = new MenuItem(I18n.get("sftp.contextMenu.archive"));
        archiveItem.setOnAction(e -> {
            resetAutoCloseTimer();
            createRemoteArchive();
        });

        MenuItem extractItem = new MenuItem(I18n.get("sftp.contextMenu.extractHere"));
        extractItem.setOnAction(e -> {
            resetAutoCloseTimer();
            extractSelectedRemoteArchive();
        });
        
        MenuItem editWithSnippetEditorItem = new MenuItem(I18n.get("sftp.contextMenu.editWithSnippetEditor"));
        editWithSnippetEditorItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openSelectedRemoteFileInSnippetEditor();
        });
        
        MenuItem editExternalItem = new MenuItem(I18n.get("sftp.contextMenu.editExternal"));
        editExternalItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openSelectedRemoteFileInExternalEditor();
        });

        // "Open with" holds the ways of editing that need more than the login user's rights.
        MenuItem sudoEditItem = new MenuItem(I18n.get("sftp.contextMenu.editAsRoot"));
        sudoEditItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openSelectedRemoteFileAsRoot();
        });
        Menu openWithMenu = new Menu(I18n.get("sftp.contextMenu.openWith"));
        openWithMenu.getItems().add(sudoEditItem);

        MenuItem openImageItem = new MenuItem(I18n.get("sftp.contextMenu.openImage"));
        openImageItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openRemoteImage();
        });
        
        menu.getItems().addAll(
            copyItem, renameItem, deleteItem,
            new SeparatorMenuItem(),
            newFolderItem,
            new SeparatorMenuItem(),
            ownerItem, calculateSizeItem, compareItem,
            new SeparatorMenuItem(), 
            archiveItem, extractItem,
            new SeparatorMenuItem(),
            editWithSnippetEditorItem, editExternalItem, openWithMenu, openImageItem
        );
        
        // Disable items when nothing is selected
        menu.setOnShowing(e -> {
            var selected = remoteTable.getSelectionModel().getSelectedItems();
            boolean hasSelection = isRemoteConnected() && selected != null && !selected.isEmpty() &&
                !(selected.size() == 1 && selected.get(0).getName().equals(".."));
            boolean isSingleFile = hasSelection && selected.size() == 1 && selected.get(0).isFile();
            boolean isImageFile = isSingleFile && isImageFileType(selected.get(0).getName());
            
            copyItem.setDisable(!hasSelection);
            renameItem.setDisable(!isRemoteConnected() || singleNamedSelection(remoteTable) == null);
            deleteItem.setDisable(!hasSelection);
            newFolderItem.setDisable(!isRemoteConnected() || !remotePathResolved);
            ownerItem.setDisable(!hasSelection);
            // While a measurement runs, the item stops it.
            boolean measuring = folderSizeCancellation != null;
            calculateSizeItem.setText(I18n.get(measuring
                ? "sftp.contextMenu.stopCalculatingSize" : "sftp.contextMenu.calculateSize"));
            calculateSizeItem.setDisable(!measuring && (!isRemoteConnected() || selected == null
                || selected.stream().noneMatch(item -> item != null && !item.isFile() && !item.isParentEntry())));
            archiveItem.setDisable(!hasSelection);
            compareItem.setDisable(compareCancel != null);
            extractItem.setDisable(!isSingleFile || remoteExtractCancellation != null
                || RemoteArchiveExtractor.detect(selected.get(0).getName()).isEmpty());
            SftpEditGate editGate = SftpEditGate.current();
            editWithSnippetEditorItem.setDisable(!isSingleFile || !editGate.remoteEditorAvailable());
            editExternalItem.setDisable(!isSingleFile || !externalEditAvailable(editGate));
            sudoEditItem.setDisable(!isSingleFile || !sudoEditAvailable());
            openWithMenu.setDisable(sudoEditItem.isDisable());
            openImageItem.setDisable(!isImageFile || !editGate.remoteImageAvailable());
        });

        addRemovePartsItem(menu, true);
        return menu;
    }

    /**
     * F2 renames the selected entry, Delete (or Cmd/Ctrl+Backspace) deletes the selection after the
     * usual confirmation, as in the file browser sidebar; Cmd/Ctrl+Shift+. shows or hides the
     * hidden entries, as in the macOS Finder.
     */
    private void installFileKeys(TableView<SftpFileItem> table, Runnable rename, Runnable delete,
                                 Runnable toggleHidden) {
        table.setOnKeyPressed(event -> {
            KeyCode code = event.getCode();
            if (TOGGLE_HIDDEN_KEYS.match(event)) {
                toggleHidden.run();
                event.consume();
            } else if (code == KeyCode.F2 && !event.isShortcutDown() && !event.isAltDown()) {
                resetAutoCloseTimer();
                rename.run();
                event.consume();
            } else if (code == KeyCode.DELETE || (code == KeyCode.BACK_SPACE && event.isShortcutDown())) {
                resetAutoCloseTimer();
                delete.run();
                event.consume();
            }
        });
    }

    /** The one selected entry other than {@code ..}, or {@code null}. */
    private static SftpFileItem singleNamedSelection(TableView<SftpFileItem> table) {
        var selected = table.getSelectionModel().getSelectedItems();
        if (selected == null || selected.size() != 1) {
            return null;
        }
        SftpFileItem item = selected.get(0);
        return item == null || item.isParentEntry() ? null : item;
    }

    /**
     * Asks for the name of a new or renamed entry. While the name is not usable (see
     * {@link SftpFileTransferService#validateEntryName}) the dialog stays open with an error.
     *
     * @return the trimmed name, or empty when the dialog was cancelled
     */
    private Optional<String> promptEntryName(String title, String header, String initialName) {
        TextInputDialog dialog = new TextInputDialog(initialName);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(I18n.get("sftp.nameLabel"));
        applyDarkTheme(dialog);
        if (getTabPane() != null && getTabPane().getScene() != null) {
            dialog.initOwner(getTabPane().getScene().getWindow());
        }
        Node okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
        if (okButton != null) {
            okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                String typed = dialog.getEditor().getText();
                if (!isValidEntryName(typed)) {
                    event.consume();
                    showError(title, I18n.get("sftp.error.invalidName", typed == null ? "" : typed.trim()));
                }
            });
        }
        return dialog.showAndWait().map(String::trim);
    }

    static boolean isValidEntryName(String name) {
        try {
            SftpFileTransferService.validateEntryName(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The message for a failed rename or new folder: "already exists" in words, otherwise the cause. */
    private static String entryFailureMessage(Throwable failure, String name) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause()
            : failure;
        return cause instanceof FileAlreadyExistsException
            ? I18n.get("sftp.error.nameExists", name)
            : failureMessage(cause);
    }

    private void renameLocalSelected() {
        SftpFileItem item = singleNamedSelection(localTable);
        if (item == null) {
            return;
        }
        promptEntryName(I18n.get("sftp.rename"), I18n.get("sftp.renamePrompt", item.getName()), item.getName())
            .filter(name -> !name.equals(item.getName()))
            .ifPresent(name -> {
                try {
                    // Never over an existing entry of that name.
                    SftpFileTransferService.renameLocalEntry(Paths.get(item.getPath()), name);
                    statusLabel.setText(I18n.get("sftp.renamed", item.getName(), name));
                } catch (IOException | RuntimeException e) {
                    logger.error("Local rename failed: {} -> {}", item.getPath(), name, e);
                    showError(I18n.get("sftp.error.renameFailed"), item.getName() + ": " + entryFailureMessage(e, name));
                }
                refreshLocal();
            });
    }

    private void renameRemoteSelected() {
        SftpFileItem item = singleNamedSelection(remoteTable);
        if (item == null || !requireConnected()) {
            return;
        }
        promptEntryName(I18n.get("sftp.rename"), I18n.get("sftp.renamePrompt", item.getName()), item.getName())
            .filter(name -> !name.equals(item.getName()))
            .ifPresent(name -> {
                if (!requireConnected()) {
                    return;
                }
                SFTPSession session = sftpSession;
                String target = SftpFileTransferService.resolveSiblingRemoteFilePath(item.getPath(), name);
                statusLabel.setText(I18n.get("sftp.renaming", item.getName(), name));
                runRemoteOperation("SFTP-Rename", session, () -> {
                    // Not every server refuses to replace an existing target on rename: check first.
                    if (remoteRenameTargetTaken(session, item.getName(), target, name)) {
                        throw new FileAlreadyExistsException(target);
                    }
                    session.renameFile(item.getPath(), target);
                }, () -> statusLabel.setText(I18n.get("sftp.renamed", item.getName(), name)), failure -> {
                    logger.error("Remote rename failed: {} -> {}", item.getPath(), target, failure);
                    showError(I18n.get("sftp.error.renameFailed"),
                        item.getName() + ": " + entryFailureMessage(failure, name));
                });
            });
    }

    private void createLocalFolder() {
        Path targetDir = currentLocalPath;
        promptEntryName(I18n.get("filebrowser.newFolder.title"), I18n.get("filebrowser.newFolder.header"), "")
            .ifPresent(name -> {
                try {
                    Files.createDirectory(targetDir.resolve(name));
                    statusLabel.setText(I18n.get("filebrowser.folder.created") + ": " + name);
                } catch (IOException | RuntimeException e) {
                    logger.error("Creating local folder {} in {} failed", name, targetDir, e);
                    showError(I18n.get("filebrowser.error.createFolder"), entryFailureMessage(e, name));
                }
                refreshLocal();
            });
    }

    private void createRemoteFolder() {
        if (!requireConnected() || !remotePathResolved) {
            return;
        }
        // Taken now: browsing on while the dialog is open must not change where the folder goes.
        String targetDir = currentRemotePath;
        promptEntryName(I18n.get("filebrowser.newFolder.title"), I18n.get("filebrowser.newFolder.header"), "")
            .ifPresent(name -> {
                if (!requireConnected()) {
                    return;
                }
                SFTPSession session = sftpSession;
                String folder = RemotePathSupport.appendRemotePath(targetDir, name);
                runRemoteOperation("SFTP-NewFolder", session, () -> {
                    if (remoteFileExists(session, folder)) {
                        throw new FileAlreadyExistsException(folder);
                    }
                    // Strict: unlike an upload, New Folder never merges into an existing folder.
                    session.createDirectory(folder);
                }, () -> statusLabel.setText(I18n.get("filebrowser.folder.created") + ": " + name), failure -> {
                    logger.error("Creating remote folder {} failed", folder, failure);
                    showError(I18n.get("filebrowser.error.createFolder"), entryFailureMessage(failure, name));
                });
            });
    }

    /** A remote action for {@link #runRemoteOperation}. */
    @FunctionalInterface
    private interface RemoteOperation {
        void run() throws Exception;
    }

    /**
     * Runs {@code operation} on its own thread and then, on the FX thread, reports it and lists the
     * remote folder again. A failure caused by a lost connection shows the Disconnected state
     * instead of {@code onFailure}.
     */
    private void runRemoteOperation(String threadName, SFTPSession session, RemoteOperation operation,
            Runnable onSuccess, java.util.function.Consumer<Exception> onFailure) {
        new Thread(() -> {
            Exception failure = null;
            try {
                operation.run();
            } catch (Exception e) {
                failure = e;
            }
            Exception result = failure;
            Platform.runLater(() -> {
                if (closing) {
                    return;
                }
                if (result == null) {
                    onSuccess.run();
                } else if (!session.isConnected()) {
                    requireConnected();
                    return;
                } else {
                    onFailure.accept(result);
                }
                refreshRemote();
            });
        }, threadName).start();
    }

    private void deleteRemoteSelected() {
        var selected = remoteTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;
        
        // Filter out ".." and collect items to delete
        List<SftpFileItem> toDelete = new java.util.ArrayList<>();
        for (var item : selected) {
            if (!item.getName().equals("..")) {
                toDelete.add(item);
            }
        }
        
        if (toDelete.isEmpty()) return;
        if (!requireConnected()) return;

        // Confirm deletion
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("sftp.delete.confirm.title"));
        confirm.setHeaderText(I18n.get("sftp.delete.confirm.header", toDelete.size()));
        confirm.setContentText(I18n.get("sftp.delete.confirm.content"));
        applyDarkTheme(confirm);

        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        if (!requireConnected()) return;
        SFTPSession session = sftpSession;

        // Show progress dialog
        Dialog<Void> progressDialog = new Dialog<>();
        progressDialog.setTitle(I18n.get("sftp.delete.progress.title"));
        progressDialog.setHeaderText(I18n.get("sftp.delete.progress.header"));
        applyDarkTheme(progressDialog);

        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        Label statusLbl = new Label(I18n.get("sftp.delete.progress.preparing"));
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        content.getChildren().addAll(statusLbl, progressBar);
        progressDialog.getDialogPane().setContent(content);
        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        progressDialog.show();

        new Thread(() -> {
            int total = toDelete.size();
            int[] current = {0};
            int[] errors = {0};

            for (var item : toDelete) {
                try {
                    final int idx = current[0] + 1;
                    Platform.runLater(() -> {
                        statusLbl.setText(I18n.get("sftp.delete.progress.deleting", item.getName()));
                        progressBar.setProgress((double) idx / total);
                    });

                    deleteRemoteRecursive(session, item.getPath(), !item.isFile());
                    current[0]++;
                    
                } catch (Exception e) {
                    logger.error("Failed to delete {}: {}", item.getPath(), e.getMessage());
                    errors[0]++;
                }
            }
            
            Platform.runLater(() -> {
                progressDialog.close();
                refreshRemote();
                if (errors[0] > 0) {
                    showError(I18n.get("sftp.delete.error"), 
                        I18n.get("sftp.delete.errorCount", errors[0], total));
                } else {
                    statusLabel.setText(I18n.get("sftp.delete.success", total));
                }
            });
        }, "SFTP-Delete").start();
    }
    
    private void deleteRemoteRecursive(SFTPSession session, String path, boolean isDir) throws Exception {
        if (isDir) {
            // List directory contents and delete recursively
            List<SftpClient.DirEntry> entries = session.listFiles(path);
            for (var entry : entries) {
                String name = entry.getFilename();
                if (name.equals(".") || name.equals("..")) continue;
                String childPath = RemotePathSupport.appendRemotePath(path, name);
                deleteRemoteRecursive(session, childPath, entry.getAttributes().isDirectory());
            }
        }
        session.deleteFile(path);
    }

    private void setOwnerPermissionsDialog() {
        var selected = remoteTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;

        // Check connection
        if (!requireConnected()) return;
        
        // Filter out ".."
        List<SftpFileItem> items = new java.util.ArrayList<>();
        for (var item : selected) {
            if (!item.getName().equals("..")) {
                items.add(item);
            }
        }
        if (items.isEmpty()) return;
        
        // Get current values from first selected item for pre-filling
        SftpFileItem firstItem = items.get(0);
        String currentOwner = remoteOwner(firstItem);
        String currentGroup = remoteGroup(firstItem);
        String currentPerms = getOctalPermissions(firstItem.getPermissions());
        
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("sftp.setOwner.title"));
        dialog.setHeaderText(I18n.get("sftp.setOwner.header", items.size()));
        applyDarkTheme(dialog);
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        int row = 0;
        
        // Owner field (separate from group)
        grid.add(new Label(I18n.get("sftp.setOwner.ownerUser")), 0, row);
        TextField ownerField = new TextField(currentOwner);
        ownerField.setPromptText("user");
        grid.add(ownerField, 1, row++);
        
        // Group field
        grid.add(new Label(I18n.get("sftp.setOwner.ownerGroup")), 0, row);
        TextField groupField = new TextField(currentGroup);
        groupField.setPromptText("group");
        grid.add(groupField, 1, row++);
        
        // Permissions: the octal field and, below it, a checkbox per bit; each follows the other.
        grid.add(new Label(I18n.get("sftp.setOwner.permissions")), 0, row);
        TextField permField = new TextField(currentPerms);
        permField.setPromptText("755");
        row = addPermissionBits(grid, row, permField);
        
        // Recursive checkbox
        CheckBox recursiveCheck = new CheckBox(I18n.get("sftp.setOwner.recursive"));
        grid.add(recursiveCheck, 0, row++, 2, 1);
        
        // Info
        Label info = new Label(I18n.get("sftp.setOwner.infoSeparate"));
        info.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        info.setWrapText(true);
        info.setMaxWidth(360);
        grid.add(info, 0, row++, 2, 1);
        
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        installPermissionsValidation(dialog, ButtonType.OK, permField, currentPerms);

        dialog.showAndWait().ifPresent(result -> {
            if (result != ButtonType.OK) return;

            String newOwner = ownerField.getText().trim();
            String newGroup = groupField.getText().trim();
            String newPerms = permField.getText().trim();
            boolean recursive = recursiveCheck.isSelected();

            // Check if anything actually changed
            boolean ownerChanged = !newOwner.equals(currentOwner);
            boolean groupChanged = !newGroup.equals(currentGroup);
            boolean permsChanged = !newPerms.equals(currentPerms);

            if (!ownerChanged && !groupChanged && !permsChanged) {
                // Nothing changed, no need to apply
                return;
            }
            // The mode ends up in a remote shell command: only three octal digits get there.
            if (!isAcceptedPermissionsInput(newPerms, currentPerms)) {
                return;
            }

            // Build owner:group string only if owner or group changed
            String ownerGroup = "";
            if (ownerChanged || groupChanged) {
                if (!newOwner.isEmpty() && !newGroup.isEmpty()) {
                    ownerGroup = newOwner + ":" + newGroup;
                } else if (!newOwner.isEmpty()) {
                    ownerGroup = newOwner;
                } else if (!newGroup.isEmpty()) {
                    ownerGroup = ":" + newGroup;
                }
            }
            
            // Only pass permissions if changed
            String permsToApply = permsChanged ? newPerms : "";
            
            // Apply changes with progress dialog
            applyOwnerPermissions(items, ownerGroup, permsToApply, recursive);
        });
    }
    
    /**
     * Whether a permissions field may be applied: left empty or unchanged (nothing to set), or
     * exactly three octal digits as {@link LocalFileBrowser#isValidOctalPermissions} accepts. The
     * value is used in a {@code chmod} command line, so nothing else may pass.
     */
    static boolean isAcceptedPermissionsInput(String input, String unchangedValue) {
        String value = input == null ? "" : input.trim();
        return value.isEmpty() || value.equals(unchangedValue) || LocalFileBrowser.isValidOctalPermissions(value);
    }

    /**
     * Puts {@code permissionsField} with its {@code rwxr-xr-x} reading into {@code row} of the
     * owner/permissions grid and the checkbox grid below it, both kept in step with the field.
     *
     * @return the next free row
     */
    private static int addPermissionBits(GridPane grid, int row, TextField permissionsField) {
        PermissionBitsEditor bits = new PermissionBitsEditor(permissionsField);
        permissionsField.setPrefColumnCount(5);
        HBox fieldRow = new HBox(10, permissionsField, bits.symbolicLabel());
        fieldRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(fieldRow, 1, row++);
        grid.add(bits.grid(), 0, row++, 2, 1);
        return row;
    }

    /**
     * Keeps {@code dialog} open with an error when {@code okButton} is pressed while the
     * permissions field holds something that is not three octal digits.
     */
    private void installPermissionsValidation(
            Dialog<?> dialog, ButtonType okButton, TextField permissionsField, String unchangedValue) {
        Node button = dialog.getDialogPane().lookupButton(okButton);
        if (button == null) {
            return;
        }
        button.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            String value = permissionsField.getText() == null ? "" : permissionsField.getText().trim();
            if (!isAcceptedPermissionsInput(value, unchangedValue)) {
                event.consume();
                showError(I18n.get("error.title"), I18n.get("sftp.error.invalidPermissions", value));
            }
        });
    }

    /**
     * Converts symbolic permissions (e.g., "rwxr-xr-x") to octal (e.g., "755").
     */
    private String getOctalPermissions(String symbolic) {
        if (symbolic == null || symbolic.isEmpty()) return "";
        
        // If already looks like octal, return as-is
        if (symbolic.matches("\\d{3,4}")) return symbolic;
        
        // Handle symbolic format like "rwxr-xr-x" or "-rwxr-xr-x" (with leading type char)
        String perms = symbolic;
        if (perms.length() == 10) {
            perms = perms.substring(1); // Remove leading type character
        }
        if (perms.length() != 9) return "";
        
        try {
            int owner = 0, group = 0, other = 0;
            
            // Owner permissions (chars 0-2)
            if (perms.charAt(0) == 'r') owner += 4;
            if (perms.charAt(1) == 'w') owner += 2;
            if (perms.charAt(2) == 'x' || perms.charAt(2) == 's') owner += 1;
            
            // Group permissions (chars 3-5)
            if (perms.charAt(3) == 'r') group += 4;
            if (perms.charAt(4) == 'w') group += 2;
            if (perms.charAt(5) == 'x' || perms.charAt(5) == 's') group += 1;
            
            // Other permissions (chars 6-8)
            if (perms.charAt(6) == 'r') other += 4;
            if (perms.charAt(7) == 'w') other += 2;
            if (perms.charAt(8) == 'x' || perms.charAt(8) == 't') other += 1;
            
            return "" + owner + group + other;
        } catch (Exception e) {
            return "";
        }
    }
    
    private void applyOwnerPermissions(List<SftpFileItem> items, String owner, String perms, boolean recursive) {
        if (!requireConnected()) return;
        SFTPSession session = sftpSession;
        // Show progress dialog
        Dialog<Void> progressDialog = new Dialog<>();
        progressDialog.setTitle(I18n.get("sftp.setOwner.title"));
        progressDialog.setHeaderText(I18n.get("sftp.setOwner.applying"));
        applyDarkTheme(progressDialog);
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        Label statusLbl = new Label(I18n.get("sftp.setOwner.applying"));
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        content.getChildren().addAll(statusLbl, progressBar);
        progressDialog.getDialogPane().setContent(content);
        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        progressDialog.show();
        
        new Thread(() -> {
            int[] success = {0};
            int[] errors = {0};
            StringBuilder errorMessages = new StringBuilder();
            int total = items.size();
            
            for (int i = 0; i < items.size(); i++) {
                var item = items.get(i);
                int currentIndex = i;
                Platform.runLater(() -> {
                    statusLbl.setText(item.getName());
                    progressBar.setProgress((double) currentIndex / total);
                });
                
                try {
                    String recursiveFlag = recursive ? "-R " : "";
                    
                    if (!owner.isEmpty()) {
                        String cmd = "chown " + recursiveFlag + "'" + owner.replace("'", "'\\''") + "' '" +
                            item.getPath().replace("'", "'\\''") + "'";
                        session.executeCommand(cmd);
                    }

                    if (!perms.isEmpty()) {
                        if (!LocalFileBrowser.isValidOctalPermissions(perms)) {
                            throw new IllegalArgumentException(I18n.get("sftp.error.invalidPermissions", perms));
                        }
                        String cmd = "chmod " + recursiveFlag + perms + " '" +
                            item.getPath().replace("'", "'\\''") + "'";
                        session.executeCommand(cmd);
                    }
                    
                    success[0]++;
                } catch (Exception e) {
                    logger.error("Failed to set owner/permissions for {}: {}", item.getPath(), e.getMessage());
                    errors[0]++;
                    // Collect error messages (limit to first 3 to avoid huge dialogs)
                    if (errorMessages.length() < 500) {
                        if (errorMessages.length() > 0) errorMessages.append("\n");
                        errorMessages.append(item.getName()).append(": ").append(e.getMessage());
                    }
                }
            }
            
            final String errorMsg = errorMessages.toString();
            Platform.runLater(() -> {
                progressDialog.close();
                refreshRemote();
                if (errors[0] > 0) {
                    String details = I18n.get("sftp.setOwner.errorCount", errors[0], total);
                    if (!errorMsg.isEmpty()) {
                        details += "\n\n" + errorMsg;
                    }
                    showError(I18n.get("sftp.setOwner.error"), details);
                } else {
                    statusLabel.setText(I18n.get("sftp.setOwner.success", success[0]));
                }
            });
        }, "SFTP-SetOwner").start();
    }
    
    // Archive format enum
    enum ArchiveFormat {
        ZIP("zip", ".zip"),
        TAR_BZ2("tar.bz2", ".tar.bz2"),
        SEVEN_ZIP("7z", ".7z");
        
        private final String displayName;
        private final String extension;
        
        ArchiveFormat(String displayName, String extension) {
            this.displayName = displayName;
            this.extension = extension;
        }
        
        public String getDisplayName() { return displayName; }
        public String getExtension() { return extension; }
        
        @Override
        public String toString() { return displayName; }
    }
    
    /**
     * "Extract Here...": unpacks the selected archive on the server into a new folder next to it
     * (see {@link RemoteArchiveExtractor}). The data never leaves the server, so this is not a file
     * transfer. FX thread.
     */
    private void extractSelectedRemoteArchive() {
        SftpFileItem item = singleNamedSelection(remoteTable);
        if (item == null || !item.isFile() || !requireConnected() || remoteExtractCancellation != null) {
            return;
        }
        String archivePath = item.getPath();
        if (RemoteArchiveExtractor.detect(item.getName()).isEmpty() || archivePath == null
                || !archivePath.startsWith("/")) {
            showError(I18n.get("sftp.extract.failedTitle"), I18n.get("sftp.extract.error.unsupported", item.getName()));
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("sftp.extract.title"));
        confirm.setHeaderText(I18n.get("sftp.extract.header", item.getName()));
        confirm.setContentText(I18n.get("sftp.extract.content"));
        applyDarkTheme(confirm);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        SFTPSession session = sftpSession;
        RemoteCommandCancellation cancellation = new RemoteCommandCancellation();
        remoteExtractCancellation = cancellation;

        Dialog<Void> progress = new Dialog<>();
        progress.setTitle(I18n.get("sftp.extract.title"));
        progress.setHeaderText(item.getName());
        Label phaseLabel = new Label(RemoteExtractMessages.phaseText(RemoteArchiveExtractor.Phase.CHECKING));
        ProgressBar bar = new ProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS);
        bar.setMaxWidth(Double.MAX_VALUE);
        VBox content = new VBox(10, phaseLabel, bar);
        content.setPadding(new Insets(10));
        content.setPrefWidth(380);
        progress.getDialogPane().setContent(content);
        progress.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        progress.setOnCloseRequest(event -> {
            if (remoteExtractCancellation == cancellation) {
                // the job closes the dialog once it has cleaned up
                cancellation.cancel();
                phaseLabel.setText(I18n.get("sftp.extract.cancelling"));
                event.consume();
            }
        });
        applyDarkTheme(progress);
        progress.show();
        statusLabel.setText(I18n.get("sftp.extract.running", item.getName()));

        Thread.ofPlatform().daemon().name("SFTP-Extract").start(() -> {
            RemoteArchiveExtractor.Result result = null;
            Throwable failure = null;
            try {
                if (session == null) {
                    throw new IOException(I18n.get("sftp.notConnected"));
                }
                result = new RemoteArchiveExtractor(session.commandRunner()).extract(archivePath, cancellation,
                    phase -> Platform.runLater(() -> phaseLabel.setText(RemoteExtractMessages.phaseText(phase))));
            } catch (IOException | RuntimeException e) {
                failure = e;
            }
            RemoteArchiveExtractor.Result done = result;
            Throwable error = failure;
            Platform.runLater(() -> finishRemoteExtract(cancellation, progress, item.getName(), done, error));
        });
    }

    private void finishRemoteExtract(RemoteCommandCancellation cancellation, Dialog<Void> progress, String name,
                                     RemoteArchiveExtractor.Result result, Throwable failure) {
        if (remoteExtractCancellation == cancellation) {
            remoteExtractCancellation = null;
        }
        progress.close();
        if (closing) {
            return;
        }
        if (result != null) {
            statusLabel.setText(I18n.get("sftp.extract.done", name, result.folder()));
            String parent = RemotePathSupport.parentRemotePath(result.folder());
            if (parent != null && parent.equals(currentRemotePath)) {
                pendingRemoteSelection = result.folder();
                refreshRemote();
            }
            return;
        }
        if (failure instanceof RemoteCommandCancelledException) {
            statusLabel.setText(I18n.get("sftp.extract.cancelled"));
            return;
        }
        String message = failure instanceof ArchiveExtractException refused
            ? RemoteExtractMessages.errorText(refused)
            : I18n.get("sftp.extract.error.failed", failureMessage(failure));
        logger.warn("Extracting {} failed: {}", name, failure instanceof ArchiveExtractException refused
            ? refused.reason() : failure.getClass().getSimpleName());
        statusLabel.setText(message);
        showError(I18n.get("sftp.extract.failedTitle"), message);
    }

    private void createRemoteArchive() {
        var selected = remoteTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) {
            showError(I18n.get("error.title"), I18n.get("sftp.error.selectFilesToArchive"));
            return;
        }
        
        // Filter out ".." entry and collect file paths
        List<String> filesToArchive = new java.util.ArrayList<>();
        List<SftpFileItem> selection = new java.util.ArrayList<>();
        long estimatedSize = 0;
        for (var item : selected) {
            if (item.getName().equals("..")) continue;
            filesToArchive.add(item.getPath());
            selection.add(item);
            // Files by their size, folders by what "Calculate size" measured; the archive dialog
            // measures the others itself.
            long bytes = remoteSizeBytes(item);
            if (bytes > 0) {
                estimatedSize += bytes;
            }
        }

        if (filesToArchive.isEmpty()) {
            showError(I18n.get("error.title"), I18n.get("sftp.error.selectFilesToArchive"));
            return;
        }
        if (!requireConnected()) return;
        
        // Get default settings from GlobalSettings
        String defaultArchivePath = "/tmp";
        int defaultCompression = 6;
        try {
            de.kortty.core.GlobalSettingsManager gsm = app.getGlobalSettingsManager();
            if (gsm != null && gsm.getSettings() != null) {
                defaultArchivePath = gsm.getSettings().getSftpDefaultZipPath();
                defaultCompression = gsm.getSettings().getSftpDefaultZipCompression();
            }
        } catch (Exception e) {
            logger.debug("Could not get global settings: {}", e.getMessage());
        }
        
        // Generate default filename with timestamp
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String defaultFilename = defaultArchivePath + "/archive_" + timestamp;
        
        // The tools are looked up on the server off the FX thread: a server that runs no commands
        // (an SFTP-only account) would otherwise freeze the window until the channel gives up.
        SFTPSession session = sftpSession;
        String folderShown = currentRemotePath;
        int compression = defaultCompression;
        long size = estimatedSize;
        String statusBefore = statusLabel.getText();
        String checking = I18n.get("sftp.archive.checkingTools");
        statusLabel.setText(checking);
        CompletableFuture<java.util.Map<ArchiveFormat, Boolean>> tools = new CompletableFuture<>();
        Thread probe = new Thread(() -> tools.complete(checkAvailableArchiveTools(session)), "SFTP-ArchiveTools");
        probe.setDaemon(true);
        probe.start();
        tools.completeOnTimeout(assumedArchiveTools(), ARCHIVE_TOOLS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .thenAccept(available -> Platform.runLater(() -> {
                if (closing) {
                    return;
                }
                if (checking.equals(statusLabel.getText())) {
                    statusLabel.setText(statusBefore);
                }
                showArchiveDialog(filesToArchive, selection, folderShown, defaultFilename, compression, size,
                    available);
            }));
    }

    /** How long "Archive" waits for the server's tool list before assuming zip and tar. */
    private static final long ARCHIVE_TOOLS_TIMEOUT_SECONDS = 8;

    /** One round trip instead of one per tool; prints the name of each tool the server has. */
    static final String ARCHIVE_TOOLS_PROBE =
        "for t in zip tar 7z 7za; do command -v \"$t\" >/dev/null 2>&1 && echo \"$t\"; done; true";

    /** What the server is assumed to have when it cannot be asked: zip and tar, as most have. */
    private static java.util.Map<ArchiveFormat, Boolean> assumedArchiveTools() {
        java.util.Map<ArchiveFormat, Boolean> available = new java.util.EnumMap<>(ArchiveFormat.class);
        available.put(ArchiveFormat.ZIP, true);
        available.put(ArchiveFormat.TAR_BZ2, true);
        available.put(ArchiveFormat.SEVEN_ZIP, false);
        return available;
    }
    
    /** The archive dialog's content: what is archived on top, the archive settings below. */
    private static Node archiveDialogContent(Node summary, GridPane settings) {
        settings.setPadding(new Insets(12, 20, 20, 20));
        Separator separator = new Separator();
        separator.setPadding(new Insets(0, 20, 0, 20));
        return new VBox(4, summary, separator, settings);
    }

    /**
     * The top of an archive dialog: the computer the selection is on (the archive is written there
     * as well), the folder it is in, how many folders and files are selected, and their names.
     */
    private Node archiveSelectionSummary(boolean remote, String folder, List<SftpFileItem> selection) {
        long folders = selection.stream().filter(item -> !item.isFile()).count();
        long files = selection.size() - folders;

        GridPane summary = new GridPane();
        summary.getStyleClass().add("sftp-archive-summary");
        summary.setHgap(10);
        summary.setVgap(6);
        summary.setPadding(new Insets(16, 20, 8, 20));

        Label computer = new Label(remote
            ? I18n.get("sftp.archive.computer.remote", serverDisplayName())
            : LocalHostName.current()
                .map(name -> I18n.get("sftp.archive.computer.localNamed", name))
                .orElse(I18n.get("sftp.archive.computer.local")));
        computer.setStyle("-fx-font-weight: bold;");
        Label folderPath = new Label(folder);
        folderPath.setStyle("-fx-font-family: \"Menlo\", \"Consolas\", monospace;");
        folderPath.setWrapText(true);
        folderPath.setMaxWidth(460);
        Label counts = new Label(I18n.get("sftp.archive.selectionCounts", folders, files));

        summary.addRow(0, new Label(I18n.get("sftp.archive.computer")), computer);
        summary.addRow(1, new Label(I18n.get("sftp.archive.folder")), folderPath);
        summary.addRow(2, new Label(I18n.get("sftp.archive.selection")), counts);

        ListView<SftpFileItem> names = new ListView<>(FXCollections.observableArrayList(selection));
        names.getStyleClass().add("sftp-archive-names");
        names.setFocusTraversable(false);
        names.setFixedCellSize(24);
        names.setPrefHeight(Math.min(selection.size(), 6) * 24 + 4);
        names.setMinHeight(names.getPrefHeight());
        names.setPrefWidth(460);
        names.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(SftpFileItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                String name = item.getName() != null ? item.getName() : "";
                FileBrowserIcons.IconKind kind = FileBrowserIcons.kindFor(name, !item.isFile(), false, false);
                setGraphic(FileBrowserIcons.treeIcon(kind, fileBrowserIconColor(), item.isHidden(), false, "#21252b"));
                setText(name);
            }
        });
        summary.add(names, 1, 3);
        GridPane.setHgrow(names, Priority.ALWAYS);
        return summary;
    }

    /** The server as the archive dialog names it: the connection's name with user@host, or user@host. */
    private String serverDisplayName() {
        String user = connection.getUsername();
        String userAtHost = user == null || user.isBlank() ? connection.getHost() : user + "@" + connection.getHost();
        String name = connection.getDisplayName();
        return name == null || name.isBlank() || name.equals(userAtHost) ? userAtHost : name + " (" + userAtHost + ")";
    }

    /** Which archive tools the server has, from one {@link #ARCHIVE_TOOLS_PROBE} run; off the FX thread. */
    private java.util.Map<ArchiveFormat, Boolean> checkAvailableArchiveTools(SFTPSession session) {
        if (session == null || !session.isConnected()) {
            java.util.Map<ArchiveFormat, Boolean> none = new java.util.EnumMap<>(ArchiveFormat.class);
            for (ArchiveFormat fmt : ArchiveFormat.values()) {
                none.put(fmt, false);
            }
            return none;
        }
        try {
            // The runner closes the channel at the timeout: on an SFTP-only account the command
            // never ends, and a plain executeCommand would keep this thread for half an hour.
            RemoteCommandRunner.Result result = session.commandRunner().run(RemoteCommandRunner.Request
                .plain(ARCHIVE_TOOLS_PROBE)
                .timeout(java.time.Duration.ofSeconds(ARCHIVE_TOOLS_TIMEOUT_SECONDS))
                .outputCap(64 * 1024));
            if (!result.isSuccess()) {
                return assumedArchiveTools();
            }
            java.util.Map<ArchiveFormat, Boolean> available = parseArchiveTools(result.stdoutText());
            logger.debug("Archive tool availability: {}", available);
            return available;
        } catch (Exception e) {
            logger.warn("Could not check archive tool availability: {}", e.getMessage());
            return assumedArchiveTools();
        }
    }

    /** The formats {@link #ARCHIVE_TOOLS_PROBE}'s output (one tool name per line) allows. */
    static java.util.Map<ArchiveFormat, Boolean> parseArchiveTools(String probeOutput) {
        java.util.Set<String> tools = new java.util.HashSet<>();
        for (String line : (probeOutput == null ? "" : probeOutput).split("\\R")) {
            tools.add(line.trim());
        }
        java.util.Map<ArchiveFormat, Boolean> available = new java.util.EnumMap<>(ArchiveFormat.class);
        available.put(ArchiveFormat.ZIP, tools.contains("zip"));
        available.put(ArchiveFormat.TAR_BZ2, tools.contains("tar"));
        available.put(ArchiveFormat.SEVEN_ZIP, tools.contains("7z") || tools.contains("7za"));
        return available;
    }
    
    private void showArchiveDialog(List<String> filesToArchive, List<SftpFileItem> selection, String folder,
                                   String defaultFilename, int defaultCompression, long estimatedSize,
                                   java.util.Map<ArchiveFormat, Boolean> availableTools) {
        
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("sftp.archive"));
        dialog.setHeaderText(I18n.get("sftp.archive.dialogHeader.remote", selection.size(), serverDisplayName()));
        dialog.setResizable(true);
        DialogGeometrySupport.installAutomatic(dialog, "sftp.remoteArchive");
        applyDarkTheme(dialog);
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        int row = 0;
        
        // Archive format with availability info
        grid.add(new Label(I18n.get("sftp.archive.format")), 0, row);
        ComboBox<String> formatCombo = new ComboBox<>();
        
        // Add formats with availability indicator
        for (ArchiveFormat fmt : ArchiveFormat.values()) {
            boolean isAvailable = availableTools.getOrDefault(fmt, false);
            String displayName = fmt.getDisplayName();
            if (!isAvailable) {
                displayName += " " + I18n.get("sftp.archive.notInstalled");
            }
            formatCombo.getItems().add(displayName);
        }
        
        // Select first available format (prefer ZIP)
        int selectedIndex = 0;
        for (int i = 0; i < ArchiveFormat.values().length; i++) {
            if (availableTools.getOrDefault(ArchiveFormat.values()[i], false)) {
                selectedIndex = i;
                break;
            }
        }
        formatCombo.getSelectionModel().select(selectedIndex);
        grid.add(formatCombo, 1, row++);
        
        // Helper to get selected format
        java.util.function.Supplier<ArchiveFormat> getSelectedFormat = () -> {
            int idx = formatCombo.getSelectionModel().getSelectedIndex();
            return idx >= 0 && idx < ArchiveFormat.values().length ? ArchiveFormat.values()[idx] : ArchiveFormat.ZIP;
        };
        
        // Set initial extension based on first available format
        String initialExtension = ArchiveFormat.values()[selectedIndex].getExtension();
        
        // Archive file path
        grid.add(new Label(I18n.get("sftp.archive.path")), 0, row);
        TextField pathField = new TextField(defaultFilename + initialExtension);
        pathField.setPrefWidth(350);
        grid.add(pathField, 1, row++);
        
        // Compression level
        grid.add(new Label(I18n.get("sftp.archive.compression")), 0, row);
        ComboBox<String> compressionCombo = new ComboBox<>();
        compressionCombo.getItems().addAll(
            "0 - " + I18n.get("sftp.archive.noCompression"),
            "1 - " + I18n.get("sftp.archive.fastest"),
            "3 - " + I18n.get("sftp.archive.fast"),
            "6 - " + I18n.get("sftp.archive.normal"),
            "9 - " + I18n.get("sftp.archive.best")
        );
        compressionCombo.getSelectionModel().select(
            defaultCompression == 0 ? 0 :
            defaultCompression <= 1 ? 1 :
            defaultCompression <= 3 ? 2 :
            defaultCompression <= 6 ? 3 : 4
        );
        grid.add(compressionCombo, 1, row++);
        
        // Owner (optional)
        grid.add(new Label(I18n.get("sftp.archive.owner")), 0, row);
        TextField ownerField = new TextField();
        ownerField.setPromptText(I18n.get("sftp.archive.ownerPrompt"));
        grid.add(ownerField, 1, row++);
        
        // Permissions (optional)
        grid.add(new Label(I18n.get("sftp.archive.permissions")), 0, row);
        TextField permissionsField = new TextField("644");
        permissionsField.setPromptText("644");
        grid.add(permissionsField, 1, row++);
        
        // No password: zip/7z would need it on the command line (visible in ps) or on a terminal.
        grid.add(new Label(I18n.get("sftp.archive.password")), 0, row);
        Label passwordUnsupported = new Label(I18n.get("sftp.archive.passwordUnsupportedRemote"));
        passwordUnsupported.setWrapText(true);
        passwordUnsupported.setMaxWidth(350);
        grid.add(passwordUnsupported, 1, row++);
        
        // Exclude (optional) - patterns to exclude from archive
        grid.add(new Label(I18n.get("sftp.archive.exclude")), 0, row);
        TextArea excludeField = new TextArea();
        excludeField.setPromptText(I18n.get("sftp.archive.excludePrompt"));
        excludeField.setPrefRowCount(3);
        excludeField.setWrapText(true);
        excludeField.setMaxWidth(Double.MAX_VALUE);
        GridPane.setFillWidth(excludeField, true);
        grid.add(excludeField, 1, row++);
        
        // Update extension and password field when format changes
        formatCombo.setOnAction(e -> {
            String currentPath = pathField.getText();
            // Remove old extension and add new one
            for (ArchiveFormat fmt : ArchiveFormat.values()) {
                if (currentPath.endsWith(fmt.getExtension())) {
                    currentPath = currentPath.substring(0, currentPath.length() - fmt.getExtension().length());
                    break;
                }
            }
            pathField.setText(currentPath + getSelectedFormat.get().getExtension());
        });
        
        // Separator
        grid.add(new Separator(), 0, row++, 2, 1);
        
        // Progress area (initially hidden)
        Label progressLabel = new Label(I18n.get("sftp.archive.preparing"));
        progressLabel.setVisible(false);
        grid.add(progressLabel, 0, row++, 2, 1);
        
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);
        progressBar.setVisible(false);
        grid.add(progressBar, 0, row++, 2, 1);
        
        Label timeLabel = new Label();
        timeLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        timeLabel.setVisible(false);
        grid.add(timeLabel, 0, row++, 2, 1);
        
        // Folders not measured yet are measured now, in the background; the estimate grows once
        // du answers, and says so if it cannot.
        List<String> unmeasured = selection.stream()
            .filter(item -> !item.isFile() && !remoteFolderSizes.containsKey(item.getPath()))
            .map(SftpFileItem::getPath)
            .toList();
        Label sizeLabel = new Label(unmeasured.isEmpty()
            ? I18n.get("sftp.archive.estimatedSize", formatSize(estimatedSize))
            : I18n.get("sftp.archive.estimatedSizeCalculating", formatSize(estimatedSize), unmeasured.size()));
        sizeLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        sizeLabel.setWrapText(true);
        sizeLabel.setMaxWidth(460);
        grid.add(sizeLabel, 0, row++, 2, 1);
        if (!unmeasured.isEmpty()) {
            String measuringText = sizeLabel.getText();
            RemoteCommandCancellation measuring = measureFolderSizes(unmeasured, (sizes, error) -> {
                if (!measuringText.equals(sizeLabel.getText())) {
                    return; // the archive is being written; its own figures are shown now
                }
                long measuredBytes = sizes == null ? 0 : sizes.values().stream().mapToLong(Long::longValue).sum();
                int missing = unmeasured.size() - (sizes == null ? 0 : sizes.size());
                sizeLabel.setText(missing == 0
                    ? I18n.get("sftp.archive.estimatedSize", formatSize(estimatedSize + measuredBytes))
                    : I18n.get("sftp.archive.estimatedSizeFilesOnly", formatSize(estimatedSize + measuredBytes), missing));
            });
            dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> measuring.cancel());
        }
        
        dialog.getDialogPane().setContent(archiveDialogContent(
            archiveSelectionSummary(true, folder, selection), grid));
        
        // Buttons
        ButtonType createButton = new ButtonType(I18n.get("sftp.archive.create"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createButton, ButtonType.CANCEL);
        
        // Get the create button for enabling/disabling
        Button createBtn = (Button) dialog.getDialogPane().lookupButton(createButton);
        // The mode is passed to chmod on the server: only three octal digits (or nothing) are accepted.
        installPermissionsValidation(dialog, createButton, permissionsField, "");

        dialog.setResultConverter(buttonType -> {
            if (buttonType == createButton) {
                String archivePath = pathField.getText().trim();
                if (archivePath.isEmpty()) {
                    showError(I18n.get("error.title"), I18n.get("sftp.archive.pathRequired"));
                    return null;
                }
                
                ArchiveFormat format = getSelectedFormat.get();
                
                // Check if the tool is available
                if (!availableTools.getOrDefault(format, false)) {
                    showError(I18n.get("error.title"), I18n.get("sftp.archive.toolNotInstalled", format.getDisplayName()));
                    return null;
                }
                
                // Get compression level from selection
                int compression = switch (compressionCombo.getSelectionModel().getSelectedIndex()) {
                    case 0 -> 0;
                    case 1 -> 1;
                    case 2 -> 3;
                    case 3 -> 6;
                    case 4 -> 9;
                    default -> 6;
                };
                
                String owner = ownerField.getText().trim();
                String permissions = permissionsField.getText().trim();
                List<String> excludePatterns = java.util.Arrays.stream(excludeField.getText().split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
                
                // Disable controls during creation
                createBtn.setDisable(true);
                pathField.setDisable(true);
                formatCombo.setDisable(true);
                compressionCombo.setDisable(true);
                ownerField.setDisable(true);
                permissionsField.setDisable(true);
                excludeField.setDisable(true);
                
                // Show progress
                progressLabel.setVisible(true);
                progressBar.setVisible(true);
                timeLabel.setVisible(true);
                progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                
                // Create archive in background
                executeRemoteArchiveCreation(dialog, filesToArchive, archivePath, format, compression, 
                        owner, permissions, excludePatterns, progressLabel, progressBar, timeLabel, sizeLabel);
            }
            return null;
        });
        
        dialog.show();
    }
    
    private void executeRemoteArchiveCreation(Dialog<Void> dialog, List<String> filesToArchive, String archivePath,
                                              ArchiveFormat format, int compression, String owner, String permissions, 
                                              List<String> excludePatterns,
                                              Label progressLabel, ProgressBar progressBar, 
                                              Label timeLabel, Label sizeLabel) {
        long startTime = System.currentTimeMillis();
        
        // Show progress in status bar
        Platform.runLater(() -> {
            statusProgressBar.setVisible(true);
            statusProgressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
            statusLabel.setText(I18n.get("sftp.archive.creating"));
        });
        
        // Timer for elapsed time
        Timeline timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            long elapsed = System.currentTimeMillis() - startTime;
            long seconds = (elapsed / 1000) % 60;
            long minutes = (elapsed / 1000) / 60;
            timeLabel.setText(I18n.get("sftp.archive.elapsed", String.format("%02d:%02d", minutes, seconds)));
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        SFTPSession session = sftpSession;

        new Thread(() -> {
            try {
                if (session == null) {
                    throw new IOException(I18n.get("sftp.notConnected"));
                }
                // Build the archive command based on format (never with a password: see RemoteArchiveCommands)
                String archiveCommand = RemoteArchiveCommands.build(toRemoteArchiveFormat(format), filesToArchive,
                    archivePath, compression, excludePatterns);

                logger.info("Executing remote archive command: format={} target={} files={}",
                    format, archivePath, filesToArchive.size());
                
                Platform.runLater(() -> progressLabel.setText(I18n.get("sftp.archive.creating")));
                
                // Execute the archive command with progress
                de.kortty.core.SFTPSession.CommandResult result = session.executeCommandWithProgress(archiveCommand, line -> {
                    Platform.runLater(() -> {
                        progressLabel.setText(line);
                    });
                });
                
                // Handle exit codes
                boolean hasWarning = false;
                
                // ZIP exit code 18 means some files were skipped
                if (format == ArchiveFormat.ZIP && result.getExitCode() == 18) {
                    hasWarning = true;
                    logger.warn("Archive created with warnings (exit code 18): {}", result.getStderr());
                } else if (result.getExitCode() != 0) {
                    String errorMsg = result.getStderr();
                    if (errorMsg == null || errorMsg.trim().isEmpty()) {
                        errorMsg = "Exit code: " + result.getExitCode();
                    }
                    logger.error("Archive command failed with exit code {}: {}", result.getExitCode(), errorMsg);
                    throw new Exception(errorMsg);
                }
                
                final boolean finalHasWarning = hasWarning;
                
                // Set owner if specified
                if (owner != null && !owner.isEmpty()) {
                    String chownCmd = "chown " + RemoteShell.quote(owner) + " " + RemoteArchiveCommands.pathArgument(archivePath);
                    try {
                        session.executeCommand(chownCmd);
                    } catch (Exception e) {
                        logger.warn("Could not set owner: {}", e.getMessage());
                    }
                }

                // Set permissions if specified (validated in the dialog; re-checked before the shell sees it)
                if (permissions != null && !permissions.isEmpty()
                        && LocalFileBrowser.isValidOctalPermissions(permissions)) {
                    String chmodCmd = "chmod " + permissions + " " + RemoteArchiveCommands.pathArgument(archivePath);
                    try {
                        session.executeCommand(chmodCmd);
                    } catch (Exception e) {
                        logger.warn("Could not set permissions: {}", e.getMessage());
                    }
                }
                
                // Get actual file size and duration
                String quotedArchive = RemoteArchiveCommands.pathArgument(archivePath);
                String sizeCmd = "stat -c%s " + quotedArchive + " 2>/dev/null || stat -f%z " + quotedArchive;
                String actualSize = "";
                long actualSizeBytes = 0;
                try {
                    actualSize = session.executeCommand(sizeCmd).trim();
                    actualSizeBytes = Long.parseLong(actualSize);
                } catch (Exception e) {
                    logger.debug("Could not get file size: {}", e.getMessage());
                }
                
                final long finalSizeBytes = actualSizeBytes;
                final long durationSeconds = (System.currentTimeMillis() - startTime) / 1000;
                
                Platform.runLater(() -> {
                    timer.stop();
                    progressBar.setProgress(1.0);
                    
                    if (finalHasWarning) {
                        progressLabel.setText(I18n.get("sftp.archive.successWithWarning"));
                        progressLabel.setStyle("-fx-text-fill: orange;");
                    } else {
                        progressLabel.setText(I18n.get("sftp.archive.success"));
                    }
                    
                    if (finalSizeBytes > 0) {
                        sizeLabel.setText(I18n.get("sftp.archive.actualSize", formatSize(finalSizeBytes)));
                    }
                    
                    // Update status bar with size and duration
                    statusProgressBar.setProgress(1.0);
                    statusLabel.setText(String.format("%s (%s, %s)", 
                        I18n.get("sftp.archiveCreated", archivePath),
                        formatSize(finalSizeBytes),
                        formatDuration(durationSeconds)));
                    
                    refreshRemote();
                    
                    // Hide progress bar after delay
                    Timeline hideProgressDelay = new Timeline(new KeyFrame(Duration.seconds(3), e -> {
                        statusProgressBar.setVisible(false);
                        statusProgressBar.setProgress(0);
                    }));
                    hideProgressDelay.play();
                    
                    int delaySeconds = finalHasWarning ? 4 : 2;
                    Timeline closeDelay = new Timeline(new KeyFrame(Duration.seconds(delaySeconds), e -> dialog.close()));
                    closeDelay.play();
                });
                
            } catch (Exception e) {
                logger.error("Remote archive creation failed", e);
                Platform.runLater(() -> {
                    timer.stop();
                    progressBar.setProgress(0);
                    progressLabel.setText(I18n.get("sftp.archive.error", e.getMessage()));
                    statusProgressBar.setVisible(false);
                    statusProgressBar.setProgress(0);
                    statusLabel.setText(I18n.get("sftp.error.archive") + ": " + e.getMessage());
                    showError(I18n.get("sftp.error.archive"), e.getMessage());
                });
            }
        }, "Remote-Archive-Creator").start();
    }
    
    private static RemoteArchiveCommands.Format toRemoteArchiveFormat(ArchiveFormat format) {
        return switch (format) {
            case ZIP -> RemoteArchiveCommands.Format.ZIP;
            case TAR_BZ2 -> RemoteArchiveCommands.Format.TAR_BZ2;
            case SEVEN_ZIP -> RemoteArchiveCommands.Format.SEVEN_ZIP;
        };
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    /**
     * Keeps the documented groups (hidden folders, hidden files, folders, files) whatever column
     * is sorted; the column orders the entries within each group. The Type column sorts by name,
     * the Size column by bytes rather than by the formatted label, every other column by its cell
     * values. The parent entry {@code ..} stays on top in each case. Columns are matched by
     * reference, not by header text.
     */
    private static void installSortPolicy(
            TableView<SftpFileItem> table,
            javafx.collections.transformation.SortedList<SftpFileItem> sortedItems,
            TableColumn<SftpFileItem, ?> typeColumn,
            TableColumn<SftpFileItem, ?> sizeColumn,
            java.util.function.ToLongFunction<SftpFileItem> sizeBytes) {
        table.setSortPolicy(view -> {
            if (view.getSortOrder().isEmpty()) {
                sortedItems.setComparator(null);
                return true;
            }
            TableColumn<SftpFileItem, ?> primaryColumn = view.getSortOrder().get(0);
            boolean ascending = primaryColumn.getSortType() == TableColumn.SortType.ASCENDING;
            if (primaryColumn == typeColumn) {
                sortedItems.setComparator(SftpFileItemComparators.type(ascending));
            } else if (primaryColumn == sizeColumn) {
                sortedItems.setComparator(SftpFileItemComparators.size(ascending, sizeBytes));
            } else {
                sortedItems.setComparator(SftpFileItemComparators.grouped(view.getComparator()));
            }
            return true;
        });
    }
    
    /**
     * Applies the active KorTTY theme to a dialog.
     */
    private void applyDarkTheme(Dialog<?> dialog) {
        DialogThemeHelper.applyTheme(dialog);
    }
    
    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        long secs = seconds % 60;
        if (minutes < 60) return String.format("%dm %ds", minutes, secs);
        long hours = minutes / 60;
        minutes = minutes % 60;
        return String.format("%dh %dm %ds", hours, minutes, secs);
    }
    
    
    private void showError(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            applyDarkTheme(alert);
            alert.showAndWait();
        });
    }
    
    private void deleteLocalSelected() {
        var selected = localTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;
        
        // Filter out ".." and collect items to delete
        List<SftpFileItem> toDelete = new java.util.ArrayList<>();
        for (var item : selected) {
            if (!item.getName().equals("..")) {
                toDelete.add(item);
            }
        }
        
        if (toDelete.isEmpty()) return;
        
        // Confirm deletion
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("sftp.delete.confirm.title"));
        confirm.setHeaderText(I18n.get("sftp.delete.confirm.header", toDelete.size()));
        confirm.setContentText(I18n.get("sftp.delete.confirm.content"));
        applyDarkTheme(confirm);
        
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        
        // Show progress dialog
        Dialog<Void> progressDialog = new Dialog<>();
        progressDialog.setTitle(I18n.get("sftp.delete.progress.title"));
        progressDialog.setHeaderText(I18n.get("sftp.delete.local.progress.header"));
        applyDarkTheme(progressDialog);

        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        Label statusLbl = new Label(I18n.get("sftp.delete.progress.preparing"));
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        content.getChildren().addAll(statusLbl, progressBar);
        progressDialog.getDialogPane().setContent(content);
        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        progressDialog.show();

        // Delete in background
        new Thread(() -> {
            int total = toDelete.size();
            int success = 0;
            int failed = 0;
            
            for (int i = 0; i < toDelete.size(); i++) {
                var item = toDelete.get(i);
                int currentIndex = i;
                Platform.runLater(() -> {
                    statusLbl.setText(I18n.get("sftp.delete.progress.deleting", item.getName()));
                    progressBar.setProgress((double) currentIndex / total);
                });
                
                try {
                    deleteLocalRecursive(Paths.get(item.getPath()));
                    success++;
                } catch (Exception e) {
                    logger.error("Failed to delete local file: {}", item.getPath(), e);
                    failed++;
                }
            }
            
            int finalSuccess = success;
            int finalFailed = failed;
            Platform.runLater(() -> {
                progressDialog.close();
                refreshLocal();
                
                if (finalFailed == 0) {
                    statusLabel.setText(I18n.get("sftp.delete.success", finalSuccess));
                } else {
                    showError(I18n.get("sftp.delete.error"),
                        I18n.get("sftp.delete.errorCount", finalFailed, total));
                }
            });
        }, "Local-Delete").start();
    }
    
    private void deleteLocalRecursive(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (var stream = Files.walk(path)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                      .forEach(p -> {
                          try {
                              Files.delete(p);
                          } catch (Exception e) {
                              throw new RuntimeException(e);
                          }
                      });
            }
        } else {
            Files.delete(path);
        }
    }
    
    private void setLocalOwnerPermissionsDialog() {
        var selected = localTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;
        
        // Filter out ".."
        List<SftpFileItem> items = new java.util.ArrayList<>();
        for (var item : selected) {
            if (!item.getName().equals("..")) {
                items.add(item);
            }
        }
        
        if (items.isEmpty()) return;
        
        // Get current values from first selected item
        SftpFileItem firstItem = items.get(0);
        String currentOwner = getLocalFileOwner(Paths.get(firstItem.getPath()));
        String currentGroup = getLocalFileGroup(Paths.get(firstItem.getPath()));
        String currentPerms = getOctalPermissions(firstItem.getPermissions());
        
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("sftp.setOwner.title"));
        dialog.setHeaderText(I18n.get("sftp.setOwner.header", items.size()));
        applyDarkTheme(dialog);
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        int row = 0;
        
        // Owner field (separate from group)
        grid.add(new Label(I18n.get("sftp.setOwner.ownerUser")), 0, row);
        TextField ownerField = new TextField(currentOwner);
        ownerField.setPromptText("user");
        if (isWindows) {
            ownerField.setDisable(true);
            ownerField.setPromptText(I18n.get("sftp.setOwner.notAvailableWindows"));
        }
        grid.add(ownerField, 1, row++);
        
        // Group field
        grid.add(new Label(I18n.get("sftp.setOwner.ownerGroup")), 0, row);
        TextField groupField = new TextField(currentGroup);
        groupField.setPromptText("group");
        if (isWindows) {
            groupField.setDisable(true);
            groupField.setPromptText(I18n.get("sftp.setOwner.notAvailableWindows"));
        }
        grid.add(groupField, 1, row++);
        
        // Permissions: the octal field and, below it, a checkbox per bit; each follows the other.
        grid.add(new Label(I18n.get("sftp.setOwner.permissions")), 0, row);
        TextField permissionsField = new TextField(currentPerms);
        permissionsField.setPromptText("755");
        row = addPermissionBits(grid, row, permissionsField);
        
        // Recursive checkbox
        CheckBox recursiveCheck = new CheckBox(I18n.get("sftp.setOwner.recursive"));
        grid.add(recursiveCheck, 0, row++, 2, 1);
        
        // Info label
        Label infoLabel = new Label(I18n.get("sftp.setOwner.infoSeparate"));
        infoLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        infoLabel.setWrapText(true);
        infoLabel.setMaxWidth(360);
        grid.add(infoLabel, 0, row++, 2, 1);
        
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        installPermissionsValidation(dialog, ButtonType.OK, permissionsField, currentPerms);

        dialog.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                String newOwner = ownerField.getText().trim();
                String newGroup = groupField.getText().trim();
                String newPerms = permissionsField.getText().trim();
                boolean recursive = recursiveCheck.isSelected();

                // Check if anything actually changed
                boolean ownerChanged = !newOwner.equals(currentOwner);
                boolean groupChanged = !newGroup.equals(currentGroup);
                boolean permsChanged = !newPerms.equals(currentPerms);

                if (!ownerChanged && !groupChanged && !permsChanged) {
                    // Nothing changed
                    return;
                }
                if (!isAcceptedPermissionsInput(newPerms, currentPerms)) {
                    return;
                }

                // Build owner:group string only if owner or group changed
                String ownerGroup = "";
                if (ownerChanged || groupChanged) {
                    if (!newOwner.isEmpty() && !newGroup.isEmpty()) {
                        ownerGroup = newOwner + ":" + newGroup;
                    } else if (!newOwner.isEmpty()) {
                        ownerGroup = newOwner;
                    } else if (!newGroup.isEmpty()) {
                        ownerGroup = ":" + newGroup;
                    }
                }
                
                // Only pass permissions if changed
                String permsToApply = permsChanged ? newPerms : "";
                
                applyLocalOwnerPermissions(items, ownerGroup, permsToApply, recursive);
            }
        });
    }
    
    /**
     * Gets the owner name of a local file.
     */
    private String getLocalFileOwner(Path path) {
        try {
            java.nio.file.attribute.UserPrincipal owner = Files.getOwner(path);
            return owner != null ? owner.getName() : "";
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Gets the group name of a local file.
     */
    private String getLocalFileGroup(Path path) {
        try {
            java.nio.file.attribute.PosixFileAttributes attrs = 
                Files.readAttributes(path, java.nio.file.attribute.PosixFileAttributes.class);
            return attrs.group().getName();
        } catch (Exception e) {
            return "";
        }
    }
    
    private void applyLocalOwnerPermissions(List<SftpFileItem> items, String owner, 
                                            String permissions, boolean recursive) {
        Dialog<Void> progressDialog = new Dialog<>();
        progressDialog.setTitle(I18n.get("sftp.setOwner.title"));
        progressDialog.setHeaderText(I18n.get("sftp.setOwner.applying"));
        applyDarkTheme(progressDialog);
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        Label statusLbl = new Label();
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        content.getChildren().addAll(statusLbl, progressBar);
        progressDialog.getDialogPane().setContent(content);
        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        progressDialog.show();
        
        new Thread(() -> {
            int success = 0;
            int failed = 0;
            StringBuilder errorMessages = new StringBuilder();
            
            for (int i = 0; i < items.size(); i++) {
                var item = items.get(i);
                int currentIndex = i;
                Platform.runLater(() -> {
                    statusLbl.setText(item.getName());
                    progressBar.setProgress((double) currentIndex / items.size());
                });
                
                try {
                    applyLocalOwnerAndMode(Paths.get(item.getPath()), owner, permissions, recursive);
                    success++;
                } catch (Exception e) {
                    logger.error("Failed to set owner/permissions for: {}", item.getPath(), e);
                    failed++;
                    if (errorMessages.length() < 500) {
                        if (errorMessages.length() > 0) errorMessages.append("\n");
                        errorMessages.append(item.getName()).append(": ").append(localFailureMessage(e));
                    }
                }
            }
            
            int finalSuccess = success;
            int finalFailed = failed;
            String errorMsg = errorMessages.toString();
            Platform.runLater(() -> {
                progressDialog.close();
                refreshLocal();
                
                if (finalFailed == 0) {
                    statusLabel.setText(I18n.get("sftp.setOwner.success", finalSuccess));
                } else {
                    String details = I18n.get("sftp.setOwner.errorCount", finalFailed, items.size());
                    if (!errorMsg.isEmpty()) {
                        details += "\n\n" + errorMsg;
                    }
                    showError(I18n.get("sftp.setOwner.error"), details);
                }
            });
        }, "Local-SetOwner").start();
    }

    /** A local file failure with its kind: an AccessDeniedException's message is only the path. */
    private static String localFailureMessage(Exception failure) {
        if (failure instanceof java.nio.file.attribute.UserPrincipalNotFoundException notFound) {
            return notFound.getClass().getSimpleName() + ": " + notFound.getName();
        }
        String message = failureMessage(failure);
        String kind = failure.getClass().getSimpleName();
        return failure instanceof IOException && !message.equals(kind) ? kind + ": " + message : message;
    }

    /**
     * Sets the owner and group ({@code user:group}, {@code user} or {@code :group}; empty keeps
     * them) and the mode (three octal digits; empty keeps it) of a local entry, and with
     * {@code recursive} of everything inside a folder too. Like {@code chown -R} and {@code chmod -R},
     * a symbolic link inside the folder is skipped, so nothing outside the folder changes; the
     * selected entry itself is changed through a link. Entries are changed deepest first, so a mode
     * that locks a folder cannot hide what is still to be changed inside it. Off the FX thread.
     *
     * @throws IOException when a name is unknown, the change is not permitted, or the walk fails
     */
    static void applyLocalOwnerAndMode(Path root, String ownerGroup, String octal, boolean recursive)
            throws IOException {
        String owner = ownerGroup == null ? "" : ownerGroup.trim();
        int colon = owner.indexOf(':');
        String userName = colon < 0 ? owner : owner.substring(0, colon).trim();
        String groupName = colon < 0 ? "" : owner.substring(colon + 1).trim();
        java.nio.file.attribute.UserPrincipalLookupService lookup =
            root.getFileSystem().getUserPrincipalLookupService();
        java.nio.file.attribute.UserPrincipal user = userName.isEmpty() ? null : lookup.lookupPrincipalByName(userName);
        java.nio.file.attribute.GroupPrincipal group =
            groupName.isEmpty() ? null : lookup.lookupPrincipalByGroupName(groupName);
        String mode = octal == null ? "" : octal.trim();
        java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions = mode.isEmpty()
            ? null
            // Rejects anything but three octal digits instead of guessing a mode.
            : java.nio.file.attribute.PosixFilePermissions.fromString(LocalFileBrowser.octalToPosix(mode));
        if (user == null && group == null && permissions == null) {
            return;
        }

        List<Path> targets = new ArrayList<>();
        if (recursive && Files.isDirectory(root)) {
            try (var stream = Files.walk(root)) {
                stream.filter(path -> path.equals(root) || !Files.isSymbolicLink(path)).forEach(targets::add);
            }
            java.util.Collections.reverse(targets);
        } else {
            targets.add(root);
        }
        for (Path target : targets) {
            LinkOption[] follow = target.equals(root) ? new LinkOption[0] : new LinkOption[] {LinkOption.NOFOLLOW_LINKS};
            java.nio.file.attribute.PosixFileAttributeView posix =
                Files.getFileAttributeView(target, java.nio.file.attribute.PosixFileAttributeView.class, follow);
            if (posix == null) {
                // Windows: an owner can be set, a group and a POSIX mode cannot.
                if (user != null) {
                    Files.setOwner(target, user);
                }
                continue;
            }
            if (user != null) {
                posix.setOwner(user);
            }
            if (group != null) {
                posix.setGroup(group);
            }
            if (permissions != null) {
                posix.setPermissions(permissions);
            }
        }
    }
    
    private void createLocalArchive() {
        var selected = localTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;
        
        // Filter out ".." and collect items
        List<Path> filesToArchive = new java.util.ArrayList<>();
        List<SftpFileItem> selection = new java.util.ArrayList<>();
        for (var item : selected) {
            if (!item.getName().equals("..")) {
                filesToArchive.add(Paths.get(item.getPath()));
                selection.add(item);
            }
        }
        
        if (filesToArchive.isEmpty()) {
            showError(I18n.get("error.title"), I18n.get("sftp.error.selectFilesToArchive"));
            return;
        }
        
        // Estimate size
        long estimatedSize = 0;
        for (Path path : filesToArchive) {
            estimatedSize += estimateLocalSize(path);
        }
        
        // Generate default filename
        String timestamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String defaultFilename = currentLocalPath.resolve("archive_" + timestamp).toString();
        
        // Get default settings
        int defaultCompression = 6; // Default compression level
        if (app != null && app.getGlobalSettingsManager() != null) {
            GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
            if (globalSettings.getSftpDefaultZipCompression() != null) {
                defaultCompression = globalSettings.getSftpDefaultZipCompression();
            }
        }
        
        showLocalArchiveDialog(filesToArchive, selection, defaultFilename, defaultCompression, estimatedSize);
    }
    
    private long estimateLocalSize(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.walk(path)) {
                    return stream.filter(Files::isRegularFile)
                                 .mapToLong(p -> {
                                     try { return Files.size(p); }
                                     catch (Exception e) { return 0; }
                                 })
                                 .sum();
                }
            } else {
                return Files.size(path);
            }
        } catch (Exception e) {
            return 0;
        }
    }
    
    // Check which archive tools are available on the local system
    private java.util.Map<ArchiveFormat, Boolean> checkLocalArchiveTools() {
        java.util.Map<ArchiveFormat, Boolean> available = new java.util.EnumMap<>(ArchiveFormat.class);
        
        // ZIP is always available via zip4j library
        available.put(ArchiveFormat.ZIP, true);
        
        // TAR is always available via Apache Commons Compress
        available.put(ArchiveFormat.TAR_BZ2, true);
        
        // Check if 7z command is available locally
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", "command -v 7z || command -v 7za"});
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            String result = reader.readLine();
            process.waitFor();
            available.put(ArchiveFormat.SEVEN_ZIP, result != null && !result.isEmpty());
        } catch (Exception e) {
            logger.debug("Could not check for local 7z: {}", e.getMessage());
            available.put(ArchiveFormat.SEVEN_ZIP, false);
        }
        
        logger.debug("Local archive tool availability: {}", available);
        return available;
    }
    
    private void showLocalArchiveDialog(List<Path> filesToArchive, List<SftpFileItem> selection, String defaultFilename,
                                        int defaultCompression, long estimatedSize) {
        // Check which archive tools are available locally
        java.util.Map<ArchiveFormat, Boolean> availableTools = checkLocalArchiveTools();
        
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("sftp.archive"));
        dialog.setHeaderText(I18n.get("sftp.archive.dialogHeader.local", selection.size()));
        dialog.setResizable(true);
        DialogGeometrySupport.installAutomatic(dialog, "sftp.localArchive");
        applyDarkTheme(dialog);
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        int row = 0;
        
        // Archive format with availability info
        grid.add(new Label(I18n.get("sftp.archive.format")), 0, row);
        ComboBox<String> formatCombo = new ComboBox<>();
        
        // Add formats with availability indicator
        for (ArchiveFormat fmt : ArchiveFormat.values()) {
            boolean isAvailable = availableTools.getOrDefault(fmt, false);
            String displayName = fmt.getDisplayName();
            if (!isAvailable) {
                displayName += " " + I18n.get("sftp.archive.notInstalled");
            }
            formatCombo.getItems().add(displayName);
        }
        
        // Select first available format (prefer ZIP)
        int selectedIndex = 0;
        for (int i = 0; i < ArchiveFormat.values().length; i++) {
            if (availableTools.getOrDefault(ArchiveFormat.values()[i], false)) {
                selectedIndex = i;
                break;
            }
        }
        formatCombo.getSelectionModel().select(selectedIndex);
        grid.add(formatCombo, 1, row++);
        
        // Helper to get selected format
        java.util.function.Supplier<ArchiveFormat> getSelectedFormat = () -> {
            int idx = formatCombo.getSelectionModel().getSelectedIndex();
            return idx >= 0 && idx < ArchiveFormat.values().length ? ArchiveFormat.values()[idx] : ArchiveFormat.ZIP;
        };
        
        // Set initial extension based on first available format
        String initialExtension = ArchiveFormat.values()[selectedIndex].getExtension();
        
        // Archive file path
        grid.add(new Label(I18n.get("sftp.archive.path")), 0, row);
        TextField pathField = new TextField(defaultFilename + initialExtension);
        pathField.setPrefWidth(350);
        Button browseButton = new Button("...");
        browseButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.get("sftp.archive.selectPath"));
            chooser.setInitialDirectory(currentLocalPath.toFile());
            
            ArchiveFormat selectedFormat = getSelectedFormat.get();
            String extension = "*" + selectedFormat.getExtension();
            chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(selectedFormat.getDisplayName() + " Archives", extension));
            
            File file = chooser.showSaveDialog(null);
            if (file != null) {
                pathField.setText(file.getAbsolutePath());
            }
        });
        HBox pathBox = new HBox(5, pathField, browseButton);
        HBox.setHgrow(pathField, Priority.ALWAYS);
        grid.add(pathBox, 1, row++);
        
        // Compression level
        grid.add(new Label(I18n.get("sftp.archive.compression")), 0, row);
        ComboBox<String> compressionCombo = new ComboBox<>();
        compressionCombo.getItems().addAll(
            "0 - " + I18n.get("sftp.archive.noCompression"),
            "1 - " + I18n.get("sftp.archive.fastest"),
            "3 - " + I18n.get("sftp.archive.fast"),
            "6 - " + I18n.get("sftp.archive.normal"),
            "9 - " + I18n.get("sftp.archive.best")
        );
        compressionCombo.getSelectionModel().select(
            defaultCompression == 0 ? 0 :
            defaultCompression <= 1 ? 1 :
            defaultCompression <= 3 ? 2 :
            defaultCompression <= 6 ? 3 : 4
        );
        grid.add(compressionCombo, 1, row++);
        
        // Password (optional)
        grid.add(new Label(I18n.get("sftp.archive.password")), 0, row);
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(I18n.get("sftp.archive.passwordPrompt"));
        grid.add(passwordField, 1, row++);
        
        // Exclude (optional)
        grid.add(new Label(I18n.get("sftp.archive.exclude")), 0, row);
        TextArea excludeField = new TextArea();
        excludeField.setPromptText(I18n.get("sftp.archive.excludePrompt"));
        excludeField.setPrefRowCount(3);
        excludeField.setWrapText(true);
        excludeField.setMaxWidth(Double.MAX_VALUE);
        GridPane.setFillWidth(excludeField, true);
        grid.add(excludeField, 1, row++);
        
        // Update extension and password field when format changes
        formatCombo.setOnAction(e -> {
            String currentPath = pathField.getText();
            // Remove old extension and add new one
            for (ArchiveFormat fmt : ArchiveFormat.values()) {
                if (currentPath.endsWith(fmt.getExtension())) {
                    currentPath = currentPath.substring(0, currentPath.length() - fmt.getExtension().length());
                    break;
                }
            }
            pathField.setText(currentPath + getSelectedFormat.get().getExtension());
            
            // Disable password for tar.bz2
            ArchiveFormat selectedFmt = getSelectedFormat.get();
            passwordField.setDisable(selectedFmt == ArchiveFormat.TAR_BZ2);
            if (selectedFmt == ArchiveFormat.TAR_BZ2) {
                passwordField.clear();
            }
        });
        
        // Separator
        grid.add(new Separator(), 0, row++, 2, 1);
        
        // Progress area (initially hidden)
        Label progressLabel = new Label(I18n.get("sftp.archive.preparing"));
        progressLabel.setVisible(false);
        grid.add(progressLabel, 0, row++, 2, 1);
        
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);
        progressBar.setVisible(false);
        grid.add(progressBar, 0, row++, 2, 1);
        
        Label timeLabel = new Label();
        timeLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        timeLabel.setVisible(false);
        grid.add(timeLabel, 0, row++, 2, 1);
        
        Label sizeLabel = new Label(I18n.get("sftp.archive.estimatedSize", formatSize(estimatedSize)));
        sizeLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        grid.add(sizeLabel, 0, row++, 2, 1);
        
        dialog.getDialogPane().setContent(archiveDialogContent(
            archiveSelectionSummary(false, currentLocalPath.toString(), selection), grid));
        
        // Buttons
        ButtonType createButton = new ButtonType(I18n.get("sftp.archive.create"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createButton, ButtonType.CANCEL);
        
        Button createBtn = (Button) dialog.getDialogPane().lookupButton(createButton);
        
        dialog.setResultConverter(buttonType -> {
            if (buttonType == createButton) {
                String archivePath = pathField.getText().trim();
                if (archivePath.isEmpty()) {
                    showError(I18n.get("error.title"), I18n.get("sftp.archive.pathRequired"));
                    return null;
                }
                
                // Get compression level
                int compression = switch (compressionCombo.getSelectionModel().getSelectedIndex()) {
                    case 0 -> 0;
                    case 1 -> 1;
                    case 2 -> 3;
                    case 3 -> 6;
                    case 4 -> 9;
                    default -> 6;
                };
                
                String password = passwordField.getText();
                ArchiveFormat format = getSelectedFormat.get();
                List<String> excludePatterns = java.util.Arrays.stream(excludeField.getText().split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
                
                // Check if the tool is available
                if (!availableTools.getOrDefault(format, false)) {
                    showError(I18n.get("error.title"), I18n.get("sftp.archive.toolNotInstalled", format.getDisplayName()));
                    return null;
                }
                
                // Disable controls
                createBtn.setDisable(true);
                pathField.setDisable(true);
                formatCombo.setDisable(true);
                compressionCombo.setDisable(true);
                passwordField.setDisable(true);
                browseButton.setDisable(true);
                excludeField.setDisable(true);
                
                // Show progress
                progressLabel.setVisible(true);
                progressBar.setVisible(true);
                timeLabel.setVisible(true);
                progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                
                // Create archive
                executeLocalArchiveCreation(dialog, filesToArchive, archivePath, format, compression, 
                        password, excludePatterns, progressLabel, progressBar, timeLabel, sizeLabel);
            }
            return null;
        });
        
        dialog.show();
    }
    
    private void executeLocalArchiveCreation(Dialog<Void> dialog, List<Path> files, String archivePath,
                                             ArchiveFormat format, int compression, String password,
                                             List<String> excludePatterns,
                                             Label progressLabel, ProgressBar progressBar, 
                                             Label timeLabel, Label sizeLabel) {
        List<String> exclude = excludePatterns != null ? excludePatterns : List.of();
        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            
            // Show progress in status bar
            Platform.runLater(() -> {
                statusProgressBar.setVisible(true);
                statusProgressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                statusLabel.setText(I18n.get("sftp.archive.creating"));
            });
            
            Timeline timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                timeLabel.setText(I18n.get("sftp.archive.elapsed", elapsed));
            }));
            timer.setCycleCount(Timeline.INDEFINITE);
            timer.play();
            
            try {
                Platform.runLater(() -> progressLabel.setText(I18n.get("sftp.archive.creating")));
                
                switch (format) {
                    case ZIP -> createLocalZip(files, archivePath, compression, password, exclude);
                    case TAR_BZ2 -> createLocalTarBz2(files, archivePath, compression, exclude);
                    case SEVEN_ZIP -> createLocal7z(files, archivePath, compression, password, exclude);
                }
                
                // Get actual size and duration
                long actualSize = Files.size(Paths.get(archivePath));
                long durationSeconds = (System.currentTimeMillis() - startTime) / 1000;
                
                Platform.runLater(() -> {
                    timer.stop();
                    progressBar.setProgress(1.0);
                    progressLabel.setText(I18n.get("sftp.archive.success"));
                    progressLabel.setStyle("-fx-text-fill: green;");
                    sizeLabel.setText(I18n.get("sftp.archive.actualSize", formatSize(actualSize)));
                    
                    // Update status bar with size and duration
                    statusProgressBar.setProgress(1.0);
                    statusLabel.setText(String.format("%s (%s, %s)", 
                        I18n.get("sftp.archiveCreated", archivePath),
                        formatSize(actualSize),
                        formatDuration(durationSeconds)));
                    
                    refreshLocal();
                    
                    // Hide progress bar after delay
                    Timeline hideProgressDelay = new Timeline(new KeyFrame(Duration.seconds(3), e -> {
                        statusProgressBar.setVisible(false);
                        statusProgressBar.setProgress(0);
                    }));
                    hideProgressDelay.play();
                    
                    Timeline closeDelay = new Timeline(new KeyFrame(Duration.seconds(2), e -> dialog.close()));
                    closeDelay.play();
                });
                
            } catch (Exception e) {
                logger.error("Local archive creation failed", e);
                Platform.runLater(() -> {
                    timer.stop();
                    progressBar.setProgress(0);
                    progressLabel.setText(I18n.get("sftp.archive.error", e.getMessage()));
                    statusProgressBar.setVisible(false);
                    statusProgressBar.setProgress(0);
                    statusLabel.setText(I18n.get("sftp.error.archive") + ": " + e.getMessage());
                    showError(I18n.get("sftp.error.archive"), e.getMessage());
                });
            }
        }, "Local-Archive-Creator").start();
    }
    
    /**
     * Returns true if the relative path (using /) matches any exclude glob.
     * Each exclude line is applied so that e.g. "*.log" excludes any .log file
     * and "node_modules" excludes that directory and its contents.
     */
    private static boolean matchesExclude(String relativePath, List<String> excludePatterns) {
        if (relativePath == null || excludePatterns == null || excludePatterns.isEmpty()) return false;
        String normalized = relativePath.replace(File.separatorChar, '/');
        Path path = Paths.get(normalized);
        for (String p : excludePatterns) {
            if (p == null || p.trim().isEmpty()) continue;
            String glob = "**/" + p.trim();
            if (FileSystems.getDefault().getPathMatcher("glob:" + glob).matches(path)) return true;
            if (FileSystems.getDefault().getPathMatcher("glob:" + glob + "/**").matches(path)) return true;
        }
        return false;
    }
    
    private void createLocalZip(List<Path> files, String archivePath, int compression, String password, List<String> excludePatterns) throws Exception {
        List<String> exclude = excludePatterns != null ? excludePatterns : List.of();
        try (net.lingala.zip4j.ZipFile zipFile = new net.lingala.zip4j.ZipFile(archivePath)) {
            if (password != null && !password.isEmpty()) {
                zipFile.setPassword(password.toCharArray());
            }
            
            net.lingala.zip4j.model.ZipParameters zipParams = new net.lingala.zip4j.model.ZipParameters();
            zipParams.setCompressionLevel(
                compression == 0 ? net.lingala.zip4j.model.enums.CompressionLevel.NO_COMPRESSION :
                compression <= 1 ? net.lingala.zip4j.model.enums.CompressionLevel.FASTEST :
                compression <= 3 ? net.lingala.zip4j.model.enums.CompressionLevel.FAST :
                compression <= 6 ? net.lingala.zip4j.model.enums.CompressionLevel.NORMAL :
                net.lingala.zip4j.model.enums.CompressionLevel.MAXIMUM
            );
            
            if (password != null && !password.isEmpty()) {
                zipParams.setEncryptFiles(true);
                zipParams.setEncryptionMethod(net.lingala.zip4j.model.enums.EncryptionMethod.AES);
            }
            
            for (Path root : files) {
                if (Files.isDirectory(root)) {
                    try (var stream = Files.walk(root)) {
                        stream.filter(Files::isRegularFile).forEach(file -> {
                            try {
                                Path rel = root.relativize(file);
                                String relStr = rel.toString().replace(File.separatorChar, '/');
                                if (matchesExclude(relStr, exclude)) return;
                                zipParams.setFileNameInZip(relStr);
                                zipFile.addFile(file.toFile(), zipParams);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
                    }
                } else {
                    String name = root.getFileName().toString();
                    if (!matchesExclude(name, exclude)) {
                        zipFile.addFile(root.toFile(), zipParams);
                    }
                }
            }
        }
    }
    
    private void createLocalTarBz2(List<Path> files, String archivePath, int compression, List<String> excludePatterns) throws Exception {
        List<String> exclude = excludePatterns != null ? excludePatterns : List.of();
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(archivePath);
             java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(fos);
             org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream bzos = 
                 new org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(bos);
             org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tarOutput = 
                 new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(bzos)) {
            
            tarOutput.setLongFileMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX);
            
            for (Path file : files) {
                addToTar(tarOutput, file, "", exclude);
            }
        }
    }
    
    private void addToTar(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tarOutput, 
                          Path path, String base, List<String> excludePatterns) throws Exception {
        String entryName = base + path.getFileName().toString();
        String entryPath = entryName.replace(File.separatorChar, '/');
        if (matchesExclude(entryPath, excludePatterns)) return;
        
        org.apache.commons.compress.archivers.tar.TarArchiveEntry entry = 
            new org.apache.commons.compress.archivers.tar.TarArchiveEntry(path.toFile(), entryName);
        tarOutput.putArchiveEntry(entry);
        
        if (Files.isRegularFile(path)) {
            Files.copy(path, tarOutput);
        }
        
        tarOutput.closeArchiveEntry();
        
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.forEach(child -> {
                    try {
                        addToTar(tarOutput, child, entryName + "/", excludePatterns);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }
    
    private void createLocal7z(List<Path> files, String archivePath, int compression, String password, List<String> excludePatterns) throws Exception {
        List<String> exclude = excludePatterns != null ? excludePatterns : List.of();

        // Head and tail are shared verbatim between the executed and the logged command; only the
        // password fragment differs (real secret vs. "***"), so the secret never flows into the
        // logged string at all (CodeQL java/sensitive-log — the command previously went to the
        // log unmasked).
        String head = "$(command -v 7z || command -v 7za) a -mx=" + compression;
        boolean passwordProtected = password != null && !password.isEmpty();

        StringBuilder tail = new StringBuilder();
        for (String pattern : exclude) {
            tail.append(" -x!'").append(pattern.replace("'", "'\\''")).append("'");
        }

        tail.append(" '").append(archivePath.replace("'", "'\\''")).append("' ");

        for (Path file : files) {
            tail.append("'").append(file.toAbsolutePath().toString().replace("'", "'\\''")).append("' ");
        }

        StringBuilder cmd = new StringBuilder(head);
        if (passwordProtected) {
            cmd.append(" -p'").append(password.replace("'", "'\\''")).append("' -mhe=on");
        }
        cmd.append(tail);

        logger.info("Executing local 7z command: {}",
            head + (passwordProtected ? " -p'***' -mhe=on" : "") + tail);
        
        // Execute command
        Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd.toString()});
        
        // Capture output
        java.io.BufferedReader stdInput = new java.io.BufferedReader(
            new java.io.InputStreamReader(process.getInputStream()));
        java.io.BufferedReader stdError = new java.io.BufferedReader(
            new java.io.InputStreamReader(process.getErrorStream()));
        
        // Read output
        StringBuilder output = new StringBuilder();
        StringBuilder errorOutput = new StringBuilder();
        String line;
        while ((line = stdInput.readLine()) != null) {
            output.append(line).append("\n");
        }
        while ((line = stdError.readLine()) != null) {
            errorOutput.append(line).append("\n");
        }
        
        int exitCode = process.waitFor();
        
        if (exitCode != 0) {
            throw new Exception("7z command failed with exit code " + exitCode + 
                              (errorOutput.length() > 0 ? ": " + errorOutput.toString() : ""));
        }
    }

    private boolean isSingleEditableFileSelection(TableView<SftpFileItem> table) {
        var selectedItems = table.getSelectionModel().getSelectedItems();
        if (selectedItems == null || selectedItems.size() != 1) {
            return false;
        }
        SftpFileItem selected = selectedItems.get(0);
        return selected != null && selected.isFile() && !"..".equals(selected.getName());
    }

    private SftpFileItem getSingleEditableFileSelection(TableView<SftpFileItem> table) {
        return isSingleEditableFileSelection(table)
            ? table.getSelectionModel().getSelectedItems().get(0)
            : null;
    }

    private void openSelectedRemoteFileInSnippetEditor() {
        SftpFileItem selected = getSingleEditableFileSelection(remoteTable);
        if (selected == null || !remoteEditAllowedByPolicy(SftpEditGate.current().remoteEditorAvailable())
            || !requireConnected()) {
            return;
        }
        SFTPSession session = sftpSession;

        new Thread(() -> {
            try {
                byte[] bytes = session.downloadFileBytes(selected.getPath());
                String content = new String(bytes, StandardCharsets.UTF_8);
                Platform.runLater(() -> openRemoteSnippetFileDialog(selected, content));
            } catch (Exception e) {
                logger.error("Failed to load remote file into snippet editor: {}", selected.getPath(), e);
                Platform.runLater(() -> {
                    statusLabel.setText(I18n.get("sftp.snippetEditor.loadFailed", e.getMessage()));
                    showError(I18n.get("error.title"), I18n.get("sftp.snippetEditor.loadFailed", e.getMessage()));
                });
            }
        }, "sftp-remote-snippet-loader").start();
    }

    /**
     * Whether "Edit in External Editor" is offered: the load-into-snippet-editor policy must not
     * deny editing server files, and the file-transfer policy must allow the download of the local
     * copy. Read-only policies still open the file, without uploads.
     */
    private boolean externalEditAvailable(SftpEditGate editGate) {
        return editGate.externalEditAvailable(
            de.kortty.policy.FileTransferGate.current(de.kortty.policy.FileTransferGate.Route.SFTP_DOWNLOAD).allowed());
    }

    /** Downloads the selected server file and opens it in the external editor (see {@link SftpRemoteEdits}). */
    private void openSelectedRemoteFileInExternalEditor() {
        SftpFileItem selected = getSingleEditableFileSelection(remoteTable);
        if (selected == null) {
            return;
        }
        SftpEditGate editGate = SftpEditGate.current();
        if (!remoteEditAllowedByPolicy(editGate.remoteEditorAvailable())
                || refuseTransfer(de.kortty.policy.FileTransferGate.Route.SFTP_DOWNLOAD)
                || !requireConnected()) {
            return;
        }
        boolean uploads = editGate.externalEditUploads(
            de.kortty.policy.FileTransferGate.current(de.kortty.policy.FileTransferGate.Route.SFTP_UPLOAD).allowed());
        remoteEdits.open(selected.getPath(), selected.getName(), uploads);
    }

    /**
     * Whether "Edit as root (sudo)..." is offered (D16): the policy must not deny
     * {@code sftp-sudo-edit}, must allow file transfer, and {@code load-into-snippet-editor} must be
     * {@code allow}, because an edit as root always writes back.
     */
    private boolean sudoEditAvailable() {
        return de.kortty.policy.PolicyManager.effective().sudoEditAllowed();
    }

    /** Reads the selected server file as root and opens it in the external editor (see {@link SftpRemoteEdits}). */
    private void openSelectedRemoteFileAsRoot() {
        SftpFileItem selected = getSingleEditableFileSelection(remoteTable);
        if (selected == null) {
            return;
        }
        if (!remoteEditAllowedByPolicy(sudoEditAvailable()) || !requireConnected()) {
            return;
        }
        String path = selected.getPath();
        if (path == null || !path.startsWith("/")) {
            statusLabel.setText(I18n.get("sftp.sudoEdit.error.relative"));
            return;
        }
        remoteEdits.openAsRoot(path, selected.getName());
    }

    /** The JobScheduler's sudo credential store, or null when the scheduler is not running. */
    private de.kortty.jobscheduler.JobSchedulerRepository sudoCredentialRepository() {
        return app != null && app.getJobSchedulerService() != null ? app.getJobSchedulerService().getRepository() : null;
    }

    /** The "Remote edits" list below the transfer list; hidden while nothing is edited. */
    private void createRemoteEdits() {
        remoteEdits = new SftpRemoteEdits(new SftpRemoteEdits.Host() {
            @Override
            public de.kortty.core.remote.edit.RemoteEditSession.ClientSource clientSource() {
                return () -> connectedSession().primaryClient();
            }

            @Override
            public javafx.stage.Window ownerWindow() {
                return ownerWindowOrNull();
            }

            @Override
            public void status(String text) {
                if (!closing) {
                    statusLabel.setText(text);
                }
            }

            @Override
            public String editorCommand() {
                return app != null && app.getGlobalSettingsManager() != null
                    ? app.getGlobalSettingsManager().getSettings().getSftpExternalEditorCommand()
                    : "";
            }

            @Override
            public void saveEditorCommand(String command) {
                if (app != null && app.getGlobalSettingsManager() != null) {
                    app.getGlobalSettingsManager().getSettings().setSftpExternalEditorCommand(command);
                    app.getGlobalSettingsManager().scheduleSave();
                }
            }

            @Override
            public void styleDialog(Dialog<?> dialog) {
                applyDarkTheme(dialog);
            }

            @Override
            public de.kortty.core.remote.RemoteCommandRunner commandRunner() throws IOException {
                return connectedSession().commandRunner();
            }

            @Override
            public boolean storedSudoPasswordAvailable() {
                de.kortty.jobscheduler.JobSchedulerRepository repository = sudoCredentialRepository();
                return repository != null && (repository.findServerSudoCredential(connection.getId()).isPresent()
                    || repository.findGroupSudoCredential(connection.getGroup()).isPresent());
            }

            @Override
            public boolean storedSudoPasswordOptedIn() {
                return app != null && app.getGlobalSettingsManager() != null
                    && app.getGlobalSettingsManager().getSettings().isSftpSudoEditStoredPasswordAllowed(connection.getId());
            }

            @Override
            public void setStoredSudoPasswordOptedIn(boolean optedIn) {
                if (app != null && app.getGlobalSettingsManager() != null) {
                    app.getGlobalSettingsManager().getSettings()
                        .setSftpSudoEditStoredPasswordAllowed(connection.getId(), optedIn);
                    app.getGlobalSettingsManager().scheduleSave();
                }
            }

            @Override
            public Optional<char[]> storedSudoPassword() {
                de.kortty.jobscheduler.JobSchedulerRepository repository = sudoCredentialRepository();
                char[] master = app != null && app.getMasterPasswordManager() != null
                    ? app.getMasterPasswordManager().getMasterPassword() : null;
                if (repository == null || master == null) {
                    return Optional.empty();
                }
                try {
                    // The scheduler hands out a String (D15 accepts that for the opted-in case);
                    // korTTY's own copy is a char[] that the edit session wipes.
                    return new de.kortty.jobscheduler.JobSchedulerSudoService(repository)
                        .resolveSudoPassword(connection, master)
                        .filter(value -> !value.isEmpty())
                        .map(String::toCharArray);
                } catch (Exception e) {
                    logger.warn("The saved sudo password could not be read: {}", e.getClass().getSimpleName());
                    return Optional.empty();
                }
            }

            @Override
            public void journalNote(String text) {
                MainWindow.noteInConnectionJournals(connection.getId(), text);
            }
        }, "SFTP-RemoteEdit-" + connection.getHost());
    }

    private void openSelectedLocalFileInSnippetEditor() {
        SftpFileItem selected = getSingleEditableFileSelection(localTable);
        if (selected == null) {
            return;
        }

        Path filePath = Path.of(selected.getPath());
        new Thread(() -> {
            try {
                String content = Files.readString(filePath, StandardCharsets.UTF_8);
                Platform.runLater(() -> openLocalSnippetFileDialog(selected, filePath, content));
            } catch (Exception e) {
                logger.error("Failed to load local file into snippet editor: {}", filePath, e);
                Platform.runLater(() -> {
                    statusLabel.setText(I18n.get("sftp.snippetEditor.loadFailed", e.getMessage()));
                    showError(I18n.get("error.title"), I18n.get("sftp.snippetEditor.loadFailed", e.getMessage()));
                });
            }
        }, "sftp-local-snippet-loader").start();
    }

    /**
     * The organization's policy denies {@code allowed == false}: says so in the status line and
     * returns {@code false}. Guards the actions whose menu items the policy already disables.
     */
    private boolean remoteEditAllowedByPolicy(boolean allowed) {
        if (!allowed) {
            statusLabel.setText(I18n.get("policy.feature.disabled"));
        }
        return allowed;
    }

    private void openRemoteSnippetFileDialog(SftpFileItem selected, String content) {
        // Read-only policy: the file opens and can be saved as a snippet, but the overwrite and
        // save-as actions that write to the server are locked in the dialog.
        SftpEditGate editGate = SftpEditGate.current();
        if (!remoteEditAllowedByPolicy(editGate.remoteEditorAvailable())) {
            return;
        }
        Snippet snippet = createFileSnippetDraft(selected.getName(), content);
        SnippetEditDialog.ExternalFileAction overwrite =
            draft -> overwriteRemoteSnippetFile(selected.getPath(), draft);
        SnippetEditDialog.ExternalFileAction saveAs =
            draft -> saveRemoteSnippetFileAs(selected.getPath(), draft);
        SnippetEditDialog.ExternalFileActionConfig config = new SnippetEditDialog.ExternalFileActionConfig(
            selected.getPath(),
            I18n.get("sftp.snippetEditor.overwriteRemote"),
            I18n.get("sftp.snippetEditor.saveAs"),
            I18n.get("sftp.snippetEditor.saveSnippet"),
            I18n.get("sftp.snippetEditor.savedFile"),
            I18n.get("sftp.snippetEditor.savedFile"),
            I18n.get("sftp.snippetEditor.savedSnippet"),
            editGate.remoteWriteAction(overwrite),
            editGate.remoteWriteAction(saveAs),
            this::saveDraftAsSnippet
        );
        showSnippetFileDialog(snippet, config);
    }

    private void openLocalSnippetFileDialog(SftpFileItem selected, Path filePath, String content) {
        Snippet snippet = createFileSnippetDraft(selected.getName(), content);
        SnippetEditDialog.ExternalFileActionConfig config = new SnippetEditDialog.ExternalFileActionConfig(
            filePath.toString(),
            I18n.get("sftp.snippetEditor.overwriteLocal"),
            I18n.get("sftp.snippetEditor.saveAs"),
            I18n.get("sftp.snippetEditor.saveSnippet"),
            I18n.get("sftp.snippetEditor.savedFile"),
            I18n.get("sftp.snippetEditor.savedFile"),
            I18n.get("sftp.snippetEditor.savedSnippet"),
            draft -> overwriteLocalSnippetFile(filePath, draft),
            draft -> saveLocalSnippetFileAs(filePath, draft),
            this::saveDraftAsSnippet
        );
        showSnippetFileDialog(snippet, config);
    }

    private Snippet createFileSnippetDraft(String fileName, String content) {
        Snippet snippet = new Snippet();
        snippet.setName(fileName);
        snippet.setContent(content);
        snippet.setLanguage(SnippetLanguageSupport.detectFileLanguage(fileName, content));
        snippet.setCategory("");
        snippet.setDescription("");
        snippet.setTagsFromString("");
        return snippet;
    }

    private void showSnippetFileDialog(Snippet snippet, SnippetEditDialog.ExternalFileActionConfig config) {
        List<String> categoryNames = app.getSnippetManager().getAllCategories().stream()
            .map(SnippetCategory::getName)
            .toList();
        SnippetEditDialog.AiAssist aiAssist = SnippetAiAssistFactory.create(ownerWindow, connection);
        SnippetEditDialog dialog = new SnippetEditDialog(snippet, categoryNames, aiAssist, config);
        if (getTabPane() != null && getTabPane().getScene() != null) {
            dialog.initOwner(getTabPane().getScene().getWindow());
        }
        dialog.showNonBlocking(null);
    }

    private boolean overwriteRemoteSnippetFile(String remotePath, Snippet draft) throws Exception {
        SftpEditGate.current().requireRemoteWriteBack(I18n.get("policy.terminal.loadReadOnly"));
        connectedSession().uploadFileBytes(draft.getContent().getBytes(StandardCharsets.UTF_8), remotePath);
        Platform.runLater(this::refreshRemote);
        return true;
    }

    /**
     * "Save as..." for a server file in the snippet editor: the place is picked with a click in the
     * server's folders ({@link SftpRemoteSaveChooser}), the file's own place proposed.
     */
    private boolean saveRemoteSnippetFileAs(String originalRemotePath, Snippet draft) throws Exception {
        SftpEditGate.current().requireRemoteWriteBack(I18n.get("policy.terminal.loadReadOnly"));
        Optional<String> chosen = callOnFxThread(() -> new SftpRemoteSaveChooser(
            getTabPane() != null && getTabPane().getScene() != null ? getTabPane().getScene().getWindow() : null,
            originalRemotePath, remoteSaveLister()).showAndWait());
        if (chosen.isEmpty()) {
            return false;
        }

        String targetPath = chosen.get();
        SFTPSession session = connectedSession();
        if (remoteFileExists(session, targetPath) && !confirmOverwrite(targetPath)) {
            return false;
        }

        session.uploadFileBytes(draft.getContent().getBytes(StandardCharsets.UTF_8), targetPath);
        Platform.runLater(this::refreshRemote);
        return true;
    }

    private boolean overwriteLocalSnippetFile(Path filePath, Snippet draft) throws IOException {
        AtomicFileWriter.writeStringAtomically(filePath, draft.getContent() != null ? draft.getContent() : "");
        Platform.runLater(this::refreshLocal);
        return true;
    }

    private boolean saveLocalSnippetFileAs(Path sourcePath, Snippet draft) throws Exception {
        File targetFile = callOnFxThread(() -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.get("sftp.snippetEditor.saveAs"));
            if (sourcePath.getParent() != null && Files.isDirectory(sourcePath.getParent())) {
                chooser.setInitialDirectory(sourcePath.getParent().toFile());
            }
            if (sourcePath.getFileName() != null) {
                chooser.setInitialFileName(sourcePath.getFileName().toString());
            }
            return chooser.showSaveDialog(
                getTabPane() != null && getTabPane().getScene() != null ? getTabPane().getScene().getWindow() : null);
        });
        if (targetFile == null) {
            return false;
        }

        Path targetPath = targetFile.toPath();
        if (Files.exists(targetPath) && !confirmOverwrite(targetPath.toString())) {
            return false;
        }

        Files.writeString(
            targetPath,
            draft.getContent(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING);
        Platform.runLater(this::refreshLocal);
        return true;
    }

    private boolean saveDraftAsSnippet(Snippet draft) throws Exception {
        SnippetManager snippetManager = app.getSnippetManager();
        Snippet snippet = copySnippetForManager(draft);
        // The editor runs this on a worker thread; the SnippetManager is FX-thread state (its
        // lists are read by open dialogs and its change listeners expect FX), so mutate and save
        // there and let any failure propagate unchanged.
        return callOnFxThread(() -> {
            snippetManager.ensureCategory(snippet.getCategory());
            snippetManager.addSnippet(snippet);
            snippetManager.save();
            return true;
        });
    }

    private Snippet copySnippetForManager(Snippet draft) {
        Snippet snippet = new Snippet();
        snippet.setName(draft.getName());
        snippet.setContent(draft.getContent());
        snippet.setLanguage(draft.getLanguage());
        snippet.setCategory(draft.getCategory());
        snippet.setDescription(draft.getDescription());
        snippet.setTags(new ArrayList<>(draft.getTags()));
        List<SnippetDiagram> diagramCopies = new ArrayList<>();
        for (SnippetDiagram diagram : draft.getDiagrams()) {
            if (diagram != null) {
                diagramCopies.add(new SnippetDiagram(diagram));
            }
        }
        snippet.setDiagrams(diagramCopies);
        return snippet;
    }

    /**
     * Whether renaming the entry {@code currentName} to {@code target} (named {@code newName}) would
     * meet another entry. A change of case only is looked up in the folder's listing, spelled
     * exactly: on a case-insensitive server the new name "exists" as the entry itself, while on a
     * case-sensitive one it can be a second file that a rename might replace.
     */
    static boolean remoteRenameTargetTaken(SFTPSession session, String currentName, String target, String newName)
            throws IOException {
        if (!newName.equalsIgnoreCase(currentName)) {
            return remoteFileExists(session, target);
        }
        for (SftpClient.DirEntry entry : session.listFiles(RemotePathSupport.parentRemotePath(target))) {
            if (newName.equals(entry.getFilename())) {
                return true;
            }
        }
        return false;
    }

    private static boolean remoteFileExists(SFTPSession session, String remotePath) {
        try {
            session.getAttributes(remotePath);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean confirmOverwrite(String targetLabel) {
        try {
            return callOnFxThread(() -> {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle(I18n.get("sftp.snippetEditor.confirmOverwrite.title"));
                confirm.setHeaderText(I18n.get("sftp.snippetEditor.confirmOverwrite.header"));
                confirm.setContentText(I18n.get("sftp.snippetEditor.confirmOverwrite.content", targetLabel));
                applyDarkTheme(confirm);
                if (getTabPane() != null && getTabPane().getScene() != null) {
                    confirm.initOwner(getTabPane().getScene().getWindow());
                }
                return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
            });
        } catch (Exception e) {
            throw new IllegalStateException("Could not confirm overwrite", e);
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
    
    private void openRemoteImage() {
        var selected = remoteTable.getSelectionModel().getSelectedItem();
        if (selected == null || !selected.isFile()) return;
        if (!remoteEditAllowedByPolicy(SftpEditGate.current().remoteImageAvailable())) return;
        if (!requireConnected()) return;
        SFTPSession session = sftpSession;

        new Thread(() -> {
            try {
                // Download image
                byte[] imageData = session.downloadFileBytes(selected.getPath());

                // Open in image viewer tab
                Platform.runLater(() -> {
                    try {
                        ImageViewerTab viewerTab = new ImageViewerTab(
                            selected.getName(),
                            selected.getPath(),
                            session,
                            imageData
                        );

                        // Add tab to the main window's tab pane
                        TabPane tabPane = (TabPane) getTabPane();
                        tabPane.getTabs().add(viewerTab);
                        tabPane.getSelectionModel().select(viewerTab);

                        logger.info("Opened remote image: {}", selected.getPath());
                    } catch (Exception e) {
                        logger.error("Failed to open image", e);
                        showError(I18n.get("error.title"), I18n.get("sftp.error.openImage", failureMessage(e)));
                    }
                });
            } catch (Exception e) {
                logger.error("Failed to download image", e);
                Platform.runLater(() ->
                    showError(I18n.get("error.title"), I18n.get("sftp.error.downloadImage", failureMessage(e)))
                );
            }
        }, "SFTP-ImageDownload").start();
    }
    
    private boolean isImageFileType(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
               lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }
    
    private void openLocalImage() {
        var selected = localTable.getSelectionModel().getSelectedItem();
        if (selected == null || !selected.isFile()) return;
        
        Platform.runLater(() -> {
            try {
                Path filePath = Path.of(selected.getPath());
                ImageViewerTab viewerTab = new ImageViewerTab(filePath);
                
                // Add tab to the main window's tab pane
                TabPane tabPane = (TabPane) getTabPane();
                tabPane.getTabs().add(viewerTab);
                tabPane.getSelectionModel().select(viewerTab);
                
                logger.info("Opened local image: {}", filePath);
            } catch (Exception e) {
                logger.error("Failed to open image", e);
                showError(I18n.get("error.title"), I18n.get("sftp.error.openImage", failureMessage(e)));
            }
        });
    }
    
    /**
     * Returns the connection associated with this SFTP tab.
     */
    public ServerConnection getConnection() {
        return connection;
    }
    
    /**
     * Creates a SessionState for saving this SFTP Manager tab.
     */
    public SessionState createSessionState() {
        SessionState state = new SessionState();
        state.setTabType(SessionState.TabType.SFTP_MANAGER);
        // The id, as for terminal tabs: the restore looks connections up by id.
        state.setConnectionId(connection.getId());
        state.setTabTitle(getText());
        state.setSftpLocalPath(currentLocalPath != null ? currentLocalPath.toString() : null);
        state.setSftpRemotePath(currentRemotePath);
        
        // Save auto-close timeout if configured
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        if (settings.getSftpAutoCloseMinutes() != null && settings.getSftpAutoCloseMinutes() > 0) {
            state.setSftpAutoCloseTimeout(settings.getSftpAutoCloseMinutes());
        }
        
        return state;
    }
}
