package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AtomicFileWriter;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.RemotePathSupport;
import de.kortty.core.SFTPSession;
import de.kortty.core.SftpFileTransferService;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.core.SnippetManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionState;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import de.kortty.model.SnippetDiagram;
import de.kortty.model.TemporarySSHKey;
import de.kortty.ui.sftp.SftpDragOutPolicy;
import de.kortty.ui.sftp.SftpDragPayload;
import de.kortty.ui.sftp.SftpFileItem;
import de.kortty.ui.sftp.SftpFileItemComparators;
import de.kortty.ui.sftp.FxConflictResolver;
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
import javafx.collections.transformation.FilteredList;
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

    private enum RemoteState { CONNECTING, CONNECTED, DISCONNECTED }

    /** A remote folder listed off the FX thread: its absolute path and its entries. */
    private record RemoteListing(String path, List<SftpFileItem> items) { }

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
        this.app = app;
        this.ownerWindow = ownerWindow;
        this.connection = connection;
        this.password = password;
        this.temporarySSHKey = temporarySSHKey;
        String listThreadName = "SFTP-List-" + connection.getHost();
        this.remoteListExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, listThreadName);
            thread.setDaemon(true);
            return thread;
        });

        setText("SFTP: " + connection.getDisplayName());
        setClosable(true);
        
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
        
        statusBox.getChildren().addAll(statusLabel, reconnectButton, statusProgressBar, spacer, timeoutLabel);
        
        // Split pane for local and remote
        SplitPane splitPane = new SplitPane();
        splitPane.setDividerPositions(0.5);
        
        // Local panel
        VBox localPanel = createLocalPanel();
        
        // Remote panel
        VBox remotePanel = createRemotePanel();
        
        splitPane.getItems().addAll(localPanel, remotePanel);
        
        // Transfer buttons
        HBox buttonBox = createButtonBox();
        
        // Transfer list: appears with the first upload or download, collapsible to its header.
        createTransferQueue();

        mainBox.getChildren().addAll(splitPane, buttonBox, transferQueuePane, statusBox);
        VBox.setVgrow(splitPane, Priority.ALWAYS);
        
        return mainBox;
    }
    
    /** Icon tint shared with the local file browser (matches its resolveIconColor). */
    private static String fileBrowserIconColor() {
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
    private static void installTypeIconCell(TableColumn<SftpFileItem, String> column) {
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

    private HBox createButtonBox() {
        HBox buttonBox = new HBox(0);
        buttonBox.setAlignment(Pos.CENTER);
        buttonBox.setPadding(new Insets(10, 5, 5, 5));
        
        // === LOCAL (left) buttons ===
        HBox localButtons = new HBox(5);
        localButtons.setAlignment(Pos.CENTER_LEFT);
        localButtons.setPadding(new Insets(0, 10, 0, 5));
        
        Label localLabel = new Label(I18n.get("sftp.localSystem"));
        localLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #666;");
        
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

        MenuButton editLocalButton = new MenuButton(I18n.get("sftp.edit"));
        editLocalButton.setTooltip(new Tooltip(I18n.get("sftp.edit.snippetEditor")));
        editLocalButton.setDisable(true);
        MenuItem editLocalSnippetItem = new MenuItem(I18n.get("sftp.edit.snippetEditor"));
        editLocalSnippetItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openSelectedLocalFileInSnippetEditor();
        });
        editLocalButton.getItems().add(editLocalSnippetItem);
        
        localButtons.getChildren().addAll(localLabel, refreshLocalButton, newFolderLocalButton, deleteLocalButton,
                ownerLocalButton, editLocalButton);
        
        // === Vertical separator ===
        Separator verticalSeparator = new Separator();
        verticalSeparator.setOrientation(javafx.geometry.Orientation.VERTICAL);
        verticalSeparator.setPadding(new Insets(0, 15, 0, 15));
        
        // === REMOTE (right) buttons ===
        HBox remoteButtons = new HBox(5);
        remoteButtons.setAlignment(Pos.CENTER_LEFT);
        remoteButtons.setPadding(new Insets(0, 5, 0, 10));
        
        Label remoteLabel = new Label(I18n.get("sftp.remoteLabel"));
        remoteLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #666;");
        
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
        
        Separator remoteSep1 = new Separator();
        remoteSep1.setOrientation(javafx.geometry.Orientation.VERTICAL);
        remoteSep1.setPadding(new Insets(0, 5, 0, 5));
        
        Button uploadButton = new Button(I18n.get("sftp.upload"));
        styleToolbarButton(uploadButton, FileBrowserIcons.UPLOAD);
        uploadButton.setTooltip(new Tooltip(I18n.get("sftp.uploading", "...")));
        uploadButton.setDisable(true);
        uploadButton.setOnAction(e -> {
            resetAutoCloseTimer();
            uploadSelected();
        });

        Button downloadButton = new Button(I18n.get("sftp.download"));
        styleToolbarButton(downloadButton, FileBrowserIcons.DOWNLOAD);
        downloadButton.setTooltip(new Tooltip(I18n.get("sftp.downloading", "...")));
        downloadButton.setDisable(true);
        downloadButton.setOnAction(e -> {
            resetAutoCloseTimer();
            downloadSelected();
        });
        
        Separator remoteSep2 = new Separator();
        remoteSep2.setOrientation(javafx.geometry.Orientation.VERTICAL);
        remoteSep2.setPadding(new Insets(0, 5, 0, 5));
        
        Button archiveButton = new Button(I18n.get("sftp.archive"));
        styleToolbarButton(archiveButton, FileBrowserIcons.ARCHIVE_BOX);
        archiveButton.setTooltip(new Tooltip(I18n.get("sftp.contextMenu.archive")));
        archiveButton.setDisable(true);
        archiveButton.setOnAction(e -> {
            resetAutoCloseTimer();
            createRemoteArchive();
        });

        MenuButton editRemoteButton = new MenuButton(I18n.get("sftp.edit"));
        editRemoteButton.setTooltip(new Tooltip(I18n.get("sftp.edit.snippetEditor")));
        editRemoteButton.setDisable(true);
        MenuItem editRemoteSnippetItem = new MenuItem(I18n.get("sftp.edit.snippetEditor"));
        editRemoteSnippetItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openSelectedRemoteFileInSnippetEditor();
        });
        editRemoteButton.getItems().add(editRemoteSnippetItem);
        
        remoteButtons.getChildren().addAll(remoteLabel, refreshRemoteButton, newFolderRemoteButton, deleteRemoteButton,
                ownerRemoteButton,
                remoteSep1, uploadButton, downloadButton, remoteSep2, archiveButton, editRemoteButton);
        
        // Assemble main button box: local | separator | remote
        buttonBox.getChildren().addAll(localButtons, verticalSeparator, remoteButtons);
        HBox.setHgrow(localButtons, Priority.ALWAYS);
        HBox.setHgrow(remoteButtons, Priority.ALWAYS);
        
        localSelectionActions = () -> {
            SftpFileItem selected = localTable.getSelectionModel().getSelectedItem();
            boolean hasSelection = selected != null && !selected.getName().equals("..");
            // Upload needs a live session and an absolute target: SFTP itself does not expand '~'.
            uploadButton.setDisable(!hasSelection || !isRemoteConnected() || !remotePathResolved);
            deleteLocalButton.setDisable(!hasSelection);
            ownerLocalButton.setDisable(!hasSelection);
            editLocalButton.setDisable(!isSingleEditableFileSelection(localTable));
        };

        remoteSelectionActions = () -> {
            SftpFileItem selected = remoteTable.getSelectionModel().getSelectedItem();
            boolean hasSelection = selected != null && !selected.getName().equals("..");
            boolean usable = hasSelection && isRemoteConnected();
            downloadButton.setDisable(!usable);
            archiveButton.setDisable(!usable);
            deleteRemoteButton.setDisable(!usable);
            ownerRemoteButton.setDisable(!usable);
            editRemoteButton.setDisable(!isRemoteConnected() || !isSingleEditableFileSelection(remoteTable));
            // A new folder goes into the absolute folder a listing returned, not the unexpanded '~'.
            newFolderRemoteButton.setDisable(!isRemoteConnected() || !remotePathResolved);
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
        
        return buttonBox;
    }
    
    private VBox createLocalPanel() {
        VBox panel = new VBox(5);
        panel.setPadding(new Insets(5));
        
        Label titleLabel = new Label(I18n.get("sftp.localSystem"));
        titleLabel.setStyle("-fx-font-weight: bold;");
        
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
        
        pathBox.getChildren().addAll(new Label(I18n.get("sftp.path")), localPathField, upButton, homeButton);
        HBox.setHgrow(localPathField, Priority.ALWAYS);
        
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
        localTable.getColumns().addAll(java.util.List.of(nameColumn, typeColumn, sizeColumn, dateColumn, userColumn, groupColumn, permColumn));
        
        // Context menu for local table
        ContextMenu localContextMenu = createLocalContextMenu();
        localTable.setContextMenu(localContextMenu);
        installFileKeys(localTable, this::renameLocalSelected, this::deleteLocalSelected);
        
        // Double-click to navigate
        localTable.setRowFactory(tv -> {
            TableRow<SftpFileItem> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    SftpFileItem item = row.getItem();
                    if (!item.isFile()) {
                        navigateLocal(item.getPath());
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
        filteredLocalItems = new FilteredList<>(localItems, p -> true);
        sortedLocalItems = new javafx.collections.transformation.SortedList<>(filteredLocalItems);
        
        installSortPolicy(localTable, sortedLocalItems, typeColumn, sizeColumn);
        localTable.setItems(sortedLocalItems);
        
        // Set default sort by type (directories first, alphabetically)
        typeColumn.setSortType(TableColumn.SortType.ASCENDING);
        localTable.getSortOrder().add(typeColumn);
        // Apply initial sort
        localTable.sort();
        
        // Search filter: a glob such as *.log, or a plain substring
        localSearchField.textProperty().addListener((obs, oldVal, newVal) ->
            filteredLocalItems.setPredicate(searchFilter(newVal)));
        
        panel.getChildren().addAll(titleLabel, pathBox, searchBox, localTable);
        VBox.setVgrow(localTable, Priority.ALWAYS);
        
        return panel;
    }
    
    private VBox createRemotePanel() {
        VBox panel = new VBox(5);
        panel.setPadding(new Insets(5));
        
        Label titleLabel = new Label(I18n.get("sftp.remoteServer", connection.getHost()));
        titleLabel.setStyle("-fx-font-weight: bold;");
        
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
        
        pathBox.getChildren().addAll(new Label(I18n.get("sftp.path")), remotePathField, upButton, homeButton);
        HBox.setHgrow(remotePathField, Priority.ALWAYS);
        
        // Search field
        HBox searchBox = new HBox(5);
        remoteSearchField = new TextField();
        remoteSearchField.setPromptText(I18n.get("sftp.searchPrompt"));
        searchBox.getChildren().addAll(new Label(I18n.get("sftp.search")), remoteSearchField);
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
        remoteTable.getColumns().addAll(java.util.List.of(nameColumn, typeColumn, sizeColumn, dateColumn, userColumn, groupColumn, permColumn));
        
        // Double-click to navigate, right-click for context menu
        remoteTable.setRowFactory(tv -> {
            TableRow<SftpFileItem> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    SftpFileItem item = row.getItem();
                    if (!item.isFile()) {
                        navigateRemote(item.getPath());
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
        installFileKeys(remoteTable, this::renameRemoteSelected, this::deleteRemoteSelected);
        
        // Setup data binding with filter and sort
        remoteItems = FXCollections.observableArrayList();
        filteredRemoteItems = new FilteredList<>(remoteItems, p -> true);
        sortedRemoteItems = new javafx.collections.transformation.SortedList<>(filteredRemoteItems);
        
        installSortPolicy(remoteTable, sortedRemoteItems, typeColumn, sizeColumn);
        remoteTable.setItems(sortedRemoteItems);
        
        // Set default sort by type (directories first, alphabetically)
        typeColumn.setSortType(TableColumn.SortType.ASCENDING);
        remoteTable.getSortOrder().add(typeColumn);
        // Apply initial sort
        remoteTable.sort();
        
        // Search filter: a glob such as *.log, or a plain substring
        remoteSearchField.textProperty().addListener((obs, oldVal, newVal) ->
            filteredRemoteItems.setPredicate(searchFilter(newVal)));
        
        // Remote folders are listed in the background; the overlay shows while one loads.
        remoteLoadingOverlay = FileBrowserLoadingOverlay.create();
        StackPane remoteStack = new StackPane(remoteTable, remoteLoadingOverlay);
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

    private void onConnected(SFTPSession session) {
        if (closing || session != sftpSession) {
            return;
        }
        remoteState = RemoteState.CONNECTED;
        setReconnectVisible(false);
        statusLabel.setText(I18n.get("sftp.connectedTo", connection.getHost()));
        transferQueueHost.onSessionReady(session);
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
        // Cancels the transfers and closes their channels before the session goes.
        transferQueueHost.close();
        transferQueuePane.dispose();
        SFTPSession session = sftpSession;
        if (session != null) {
            closeQuietly(session);
        }
    }
    
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
                    localItems.add(SftpFileItem.fromDetails(file.getName(),
                        file.getAbsolutePath(), file.isFile(), size, date, permissions, owner, group, sizeBytes));
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

                // Get owner - try name first, fall back to UID
                String owner = attrs.getOwner();
                if (owner == null || owner.isEmpty()) {
                    Integer uid = attrs.getUserId();
                    owner = uid != null ? String.valueOf(uid) : "";
                }

                // Get group - try name first, fall back to GID
                String group = attrs.getGroup();
                if (group == null || group.isEmpty()) {
                    Integer gid = attrs.getGroupId();
                    group = gid != null ? String.valueOf(gid) : "";
                }

                items.add(SftpFileItem.fromDetails(name, RemotePathSupport.appendRemotePath(path, name),
                    !isDir, size, date, permissions, owner, group, sizeBytes));
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
        remoteItems.setAll(listing.items());
        remotePathField.setText(currentRemotePath);
        // Re-apply sort after refresh
        remoteTable.sort();
        refreshActionStates();
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
        loadRemote(path);
    }

    private void navigateRemoteUp() {
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

    /** The transfer list and its queue; the queue itself is made once the first session is ready. */
    private void createTransferQueue() {
        transferQueuePane = new SftpTransferQueuePane();
        String resumeScope = connection.getId() != null && !connection.getId().isBlank()
            ? connection.getId()
            : connection.getUsername() + "@" + connection.getHost() + ":" + connection.getPort();
        transferQueueHost = new SftpTransferQueueHost(
            () -> SftpTransferQueueHost.defaultSettings(resumeScope),
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
        List<File> prepared = prepareDragOut(items);
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
        return (isRemoteConnected() && ownRemoteEntries(dragboard).isPresent()) || dragboard.hasFiles();
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
        return !startedIn(event.getGestureSource(), remoteTable)
            && event.getDragboard().hasFiles()
            && isRemoteConnected()
            && remotePathResolved;
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
            ownerItem,
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
        
        MenuItem archiveItem = new MenuItem(I18n.get("sftp.contextMenu.archive"));
        archiveItem.setOnAction(e -> {
            resetAutoCloseTimer();
            createRemoteArchive();
        });
        
        MenuItem editWithSnippetEditorItem = new MenuItem(I18n.get("sftp.contextMenu.editWithSnippetEditor"));
        editWithSnippetEditorItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openSelectedRemoteFileInSnippetEditor();
        });
        
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
            ownerItem,
            new SeparatorMenuItem(), 
            archiveItem,
            new SeparatorMenuItem(),
            editWithSnippetEditorItem, openImageItem
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
            archiveItem.setDisable(!hasSelection);
            editWithSnippetEditorItem.setDisable(!isSingleFile);
            openImageItem.setDisable(!isImageFile);
        });

        addRemovePartsItem(menu, true);
        return menu;
    }

    /**
     * F2 renames the selected entry, Delete (or Cmd/Ctrl+Backspace) deletes the selection after the
     * usual confirmation, as in the file browser sidebar.
     */
    private void installFileKeys(TableView<SftpFileItem> table, Runnable rename, Runnable delete) {
        table.setOnKeyPressed(event -> {
            KeyCode code = event.getCode();
            if (code == KeyCode.F2 && !event.isShortcutDown() && !event.isAltDown()) {
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
        String currentOwner = firstItem.getOwner();
        String currentGroup = firstItem.getGroup();
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
        
        // Permissions field with current value
        grid.add(new Label(I18n.get("sftp.setOwner.permissions")), 0, row);
        TextField permField = new TextField(currentPerms);
        permField.setPromptText("755");
        grid.add(permField, 1, row++);
        
        // Recursive checkbox
        CheckBox recursiveCheck = new CheckBox(I18n.get("sftp.setOwner.recursive"));
        grid.add(recursiveCheck, 0, row++, 2, 1);
        
        // Info
        Label info = new Label(I18n.get("sftp.setOwner.infoSeparate"));
        info.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        info.setWrapText(true);
        info.setMaxWidth(300);
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
    private enum ArchiveFormat {
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
    
    private void createRemoteArchive() {
        var selected = remoteTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) {
            showError(I18n.get("error.title"), I18n.get("sftp.error.selectFilesToArchive"));
            return;
        }
        
        // Filter out ".." entry and collect file paths
        List<String> filesToArchive = new java.util.ArrayList<>();
        long estimatedSize = 0;
        for (var item : selected) {
            if (item.getName().equals("..")) continue;
            filesToArchive.add(item.getPath());
            // Estimate size (rough, since directories can't be easily calculated)
            if (item.isFile() && item.getSizeBytes() > 0) {
                estimatedSize += item.getSizeBytes();
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
        
        // Show archive creation dialog
        showArchiveDialog(filesToArchive, defaultFilename, defaultCompression, estimatedSize);
    }
    
    // Check which archive tools are available on the remote server
    private java.util.Map<ArchiveFormat, Boolean> checkAvailableArchiveTools() {
        java.util.Map<ArchiveFormat, Boolean> available = new java.util.EnumMap<>(ArchiveFormat.class);
        
        // Default all to false
        for (ArchiveFormat fmt : ArchiveFormat.values()) {
            available.put(fmt, false);
        }
        
        SFTPSession session = sftpSession;
        if (session == null || !session.isConnected()) {
            return available;
        }

        try {
            // Check zip
            String zipCheck = session.executeCommand("which zip 2>/dev/null || command -v zip 2>/dev/null || echo ''");
            available.put(ArchiveFormat.ZIP, !zipCheck.trim().isEmpty());

            // Check tar (always available on Unix)
            String tarCheck = session.executeCommand("which tar 2>/dev/null || command -v tar 2>/dev/null || echo ''");
            available.put(ArchiveFormat.TAR_BZ2, !tarCheck.trim().isEmpty());

            // Check 7z or 7za
            String sevenZCheck = session.executeCommand("which 7z 2>/dev/null || which 7za 2>/dev/null || command -v 7z 2>/dev/null || command -v 7za 2>/dev/null || echo ''");
            available.put(ArchiveFormat.SEVEN_ZIP, !sevenZCheck.trim().isEmpty());
            
        } catch (Exception e) {
            logger.warn("Could not check archive tool availability: {}", e.getMessage());
            // Assume zip and tar are available as they usually are
            available.put(ArchiveFormat.ZIP, true);
            available.put(ArchiveFormat.TAR_BZ2, true);
        }
        
        logger.debug("Archive tool availability: {}", available);
        return available;
    }
    
    private void showArchiveDialog(List<String> filesToArchive, String defaultFilename, int defaultCompression, long estimatedSize) {
        // Check available tools first
        java.util.Map<ArchiveFormat, Boolean> availableTools = checkAvailableArchiveTools();
        
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("sftp.archive"));
        dialog.setHeaderText(I18n.get("sftp.archive.dialogHeader", filesToArchive.size()));
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
        
        // Password (optional) - only for ZIP and 7z
        grid.add(new Label(I18n.get("sftp.archive.password")), 0, row);
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(I18n.get("sftp.archive.passwordPrompt"));
        grid.add(passwordField, 1, row++);
        
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
        
        dialog.getDialogPane().setContent(grid);
        
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
                String password = passwordField.getText();
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
                passwordField.setDisable(true);
                excludeField.setDisable(true);
                
                // Show progress
                progressLabel.setVisible(true);
                progressBar.setVisible(true);
                timeLabel.setVisible(true);
                progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                
                // Create archive in background
                executeRemoteArchiveCreation(dialog, filesToArchive, archivePath, format, compression, 
                        owner, permissions, password, excludePatterns, progressLabel, progressBar, timeLabel, sizeLabel);
            }
            return null;
        });
        
        dialog.show();
    }
    
    private void executeRemoteArchiveCreation(Dialog<Void> dialog, List<String> filesToArchive, String archivePath,
                                              ArchiveFormat format, int compression, String owner, String permissions, 
                                              String password, List<String> excludePatterns,
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
                // Build the archive command based on format
                String archiveCommand = buildArchiveCommand(filesToArchive, archivePath, format, compression, password, excludePatterns);

                // Log archive metadata instead of the command line: any buildArchiveCommand result
                // can embed the archive password, and the old regex masks were bypassable via
                // quote-escaping ('\'') — CodeQL java/sensitive-log.
                logger.info("Executing remote archive command: format={} target={} files={} passwordProtected={}",
                    format, archivePath, filesToArchive.size(),
                    password != null && !password.isEmpty());
                
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
                    String chownCmd = "chown '" + owner.replace("'", "'\\''") + "' '" + archivePath.replace("'", "'\\''") + "'";
                    try {
                        session.executeCommand(chownCmd);
                    } catch (Exception e) {
                        logger.warn("Could not set owner: {}", e.getMessage());
                    }
                }

                // Set permissions if specified (validated in the dialog; re-checked before the shell sees it)
                if (permissions != null && !permissions.isEmpty()
                        && LocalFileBrowser.isValidOctalPermissions(permissions)) {
                    String chmodCmd = "chmod " + permissions + " '" + archivePath.replace("'", "'\\''") + "'";
                    try {
                        session.executeCommand(chmodCmd);
                    } catch (Exception e) {
                        logger.warn("Could not set permissions: {}", e.getMessage());
                    }
                }
                
                // Get actual file size and duration
                String sizeCmd = "stat -c%s '" + archivePath.replace("'", "'\\''") + "' 2>/dev/null || stat -f%z '" + archivePath.replace("'", "'\\''") + "'";
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
    
    private String buildArchiveCommand(List<String> files, String archivePath, ArchiveFormat format, int compression, String password, List<String> excludePatterns) {
        StringBuilder cmd = new StringBuilder();
        String escapedPath = archivePath.replace("'", "'\\''");
        List<String> exclude = excludePatterns != null ? excludePatterns : List.of();
        
        switch (format) {
            case ZIP:
                if (password != null && !password.isEmpty()) {
                    cmd.append("zip -r -").append(compression)
                       .append(" -P '").append(password.replace("'", "'\\''")).append("' ");
                } else {
                    cmd.append("zip -r -").append(compression).append(" ");
                }
                cmd.append("'").append(escapedPath).append("' ");
                for (String pattern : exclude) {
                    cmd.append("-x '").append(pattern.replace("'", "'\\''")).append("' ");
                }
                for (String file : files) {
                    cmd.append("'").append(file.replace("'", "'\\''")).append("' ");
                }
                break;
                
            case TAR_BZ2:
                // tar with bzip2 compression
                // -j = bzip2, compression level via BZIP2 env var
                cmd.append("BZIP2=-").append(compression).append(" tar -cjf '")
                   .append(escapedPath).append("' ");
                for (String pattern : exclude) {
                    cmd.append("--exclude='").append(pattern.replace("'", "'\\''")).append("' ");
                }
                for (String file : files) {
                    cmd.append("'").append(file.replace("'", "'\\''")).append("' ");
                }
                break;
                
            case SEVEN_ZIP:
                // 7z archive - try 7z first, then 7za (p7zip uses 7za on some systems)
                // -mx=compression level, -p for password, -x! for exclude
                cmd.append("$(command -v 7z || command -v 7za) a -mx=").append(compression);
                if (password != null && !password.isEmpty()) {
                    cmd.append(" -p'").append(password.replace("'", "'\\''")).append("' -mhe=on");
                }
                for (String pattern : exclude) {
                    cmd.append(" -x!'").append(pattern.replace("'", "'\\''")).append("'");
                }
                cmd.append(" '").append(escapedPath).append("' ");
                for (String file : files) {
                    cmd.append("'").append(file.replace("'", "'\\''")).append("' ");
                }
                break;
        }
        
        return cmd.toString().trim();
    }
    
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    /**
     * Sorts the Type column in its documented group order and the Size column by bytes rather
     * than by the formatted label; every other column uses its cell values. The parent entry
     * {@code ..} stays on top in each case. Columns are matched by reference, not by header text.
     */
    private static void installSortPolicy(
            TableView<SftpFileItem> table,
            javafx.collections.transformation.SortedList<SftpFileItem> sortedItems,
            TableColumn<SftpFileItem, ?> typeColumn,
            TableColumn<SftpFileItem, ?> sizeColumn) {
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
                sortedItems.setComparator(SftpFileItemComparators.size(ascending));
            } else {
                sortedItems.setComparator(SftpFileItemComparators.parentFirst(view.getComparator()));
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
        
        // Permissions field with current value
        grid.add(new Label(I18n.get("sftp.setOwner.permissions")), 0, row);
        TextField permissionsField = new TextField(currentPerms);
        permissionsField.setPromptText("755");
        grid.add(permissionsField, 1, row++);
        
        // Recursive checkbox
        CheckBox recursiveCheck = new CheckBox(I18n.get("sftp.setOwner.recursive"));
        grid.add(recursiveCheck, 0, row++, 2, 1);
        
        // Info label
        Label infoLabel = new Label(I18n.get("sftp.setOwner.infoSeparate"));
        infoLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
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
            
            for (int i = 0; i < items.size(); i++) {
                var item = items.get(i);
                int currentIndex = i;
                Platform.runLater(() -> {
                    statusLbl.setText(item.getName());
                    progressBar.setProgress((double) currentIndex / items.size());
                });
                
                try {
                    Path path = Paths.get(item.getPath());
                    
                    // Set permissions (works on Unix-like systems)
                    if (!permissions.isEmpty()) {
                        try {
                            // Rejects anything but three octal digits instead of guessing a mode.
                            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions
                                .fromString(LocalFileBrowser.octalToPosix(permissions)));
                        } catch (UnsupportedOperationException e) {
                            // On Windows, POSIX permissions are not supported
                            logger.debug("POSIX permissions not supported on this system");
                        }
                    }
                    
                    // Note: Java doesn't provide direct API for chown
                    // This would require native calls or ProcessBuilder
                    
                    success++;
                } catch (Exception e) {
                    logger.error("Failed to set owner/permissions for: {}", item.getPath(), e);
                    failed++;
                }
            }
            
            int finalSuccess = success;
            int finalFailed = failed;
            Platform.runLater(() -> {
                progressDialog.close();
                refreshLocal();
                
                if (finalFailed == 0) {
                    statusLabel.setText(I18n.get("sftp.setOwner.success", finalSuccess));
                } else {
                    showError(I18n.get("sftp.setOwner.error"),
                        I18n.get("sftp.setOwner.errorCount", finalFailed, items.size()));
                }
            });
        }, "Local-SetOwner").start();
    }
    
    private void createLocalArchive() {
        var selected = localTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;
        
        // Filter out ".." and collect items
        List<Path> filesToArchive = new java.util.ArrayList<>();
        for (var item : selected) {
            if (!item.getName().equals("..")) {
                filesToArchive.add(Paths.get(item.getPath()));
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
        
        showLocalArchiveDialog(filesToArchive, defaultFilename, defaultCompression, estimatedSize);
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
    
    private void showLocalArchiveDialog(List<Path> filesToArchive, String defaultFilename, 
                                        int defaultCompression, long estimatedSize) {
        // Check which archive tools are available locally
        java.util.Map<ArchiveFormat, Boolean> availableTools = checkLocalArchiveTools();
        
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("sftp.archive"));
        dialog.setHeaderText(I18n.get("sftp.archive.dialogHeader", filesToArchive.size()));
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
        
        dialog.getDialogPane().setContent(grid);
        
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
        if (selected == null || !requireConnected()) {
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

    private void openRemoteSnippetFileDialog(SftpFileItem selected, String content) {
        Snippet snippet = createFileSnippetDraft(selected.getName(), content);
        SnippetEditDialog.ExternalFileActionConfig config = new SnippetEditDialog.ExternalFileActionConfig(
            selected.getPath(),
            I18n.get("sftp.snippetEditor.overwriteRemote"),
            I18n.get("sftp.snippetEditor.saveAs"),
            I18n.get("sftp.snippetEditor.saveSnippet"),
            I18n.get("sftp.snippetEditor.savedFile"),
            I18n.get("sftp.snippetEditor.savedFile"),
            I18n.get("sftp.snippetEditor.savedSnippet"),
            draft -> overwriteRemoteSnippetFile(selected.getPath(), draft),
            draft -> saveRemoteSnippetFileAs(selected.getPath(), selected.getName(), draft),
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
        connectedSession().uploadFileBytes(draft.getContent().getBytes(StandardCharsets.UTF_8), remotePath);
        Platform.runLater(this::refreshRemote);
        return true;
    }

    private boolean saveRemoteSnippetFileAs(String originalRemotePath, String originalFileName, Snippet draft) throws Exception {
        Optional<String> response = callOnFxThread(() -> {
            TextInputDialog dialog = new TextInputDialog(originalFileName);
            dialog.setTitle(I18n.get("sftp.snippetEditor.remoteFileName.title"));
            dialog.setHeaderText(I18n.get("sftp.snippetEditor.remoteFileName.header"));
            dialog.setContentText(I18n.get("sftp.snippetEditor.remoteFileName.content"));
            applyDarkTheme(dialog);
            if (getTabPane() != null && getTabPane().getScene() != null) {
                dialog.initOwner(getTabPane().getScene().getWindow());
            }
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
