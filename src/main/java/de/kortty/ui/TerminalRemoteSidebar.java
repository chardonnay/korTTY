package de.kortty.ui;

import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.KorTTYApplication;
import de.kortty.core.RemoteDirectoryChange;
import de.kortty.core.SFTPSession;
import de.kortty.core.sftp.transfer.RemoteEntryRef;
import de.kortty.core.sftp.transfer.TransferCancellation;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.policy.FileTransferGate;
import de.kortty.policy.PolicyManager;
import de.kortty.ui.sftp.FxConflictResolver;
import de.kortty.ui.sftp.RemoteFollowController;
import de.kortty.ui.sftp.RemoteSidebarBrowser;
import de.kortty.ui.sftp.SftpConflictDialog;
import de.kortty.ui.sftp.SftpDragOutPolicy;
import de.kortty.ui.sftp.SftpFileItem;
import de.kortty.ui.sftp.SftpOpenTargetResolver;
import de.kortty.ui.sftp.SftpTransferQueueHost;
import de.kortty.ui.sftp.SftpTransferQueuePane;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The remote files sidebar of a terminal tab (View › Remote Files Sidebar): a browse-only listing
 * of the folder the shell of the focused SSH pane is in, on an SFTP channel of that pane's own
 * session (no second login). {@link RemoteFollowController} decides what to list (D23),
 * {@link RemoteSidebarBrowser} lists it off the FX thread.
 *
 * <p>Browse only (D11): nothing here types into the terminal. Copying files goes through the
 * transfer queue after a confirmation that names the target folder, because the sidebar may lag
 * behind the shell; upload, download and dragging rows out are greyed out up front when the
 * organization's policy denies file transfer (D6), and drops are rejected while dragging.
 *
 * <p>The SFTP session opens lazily, the first time the sidebar is shown in a visible tab, and
 * closes when the sidebar is hidden or the tab closes. FX thread throughout.
 */
final class TerminalRemoteSidebar extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(TerminalRemoteSidebar.class);
    /** The foreign-session check reads the screen: at most once per this interval. */
    static final long CHECK_INTERVAL_MILLIS = 500;
    /** When a typed {@code cd} is checked for its new prompt. */
    private static final long[] PROMPT_PROBE_DELAYS = {500, 1200, 2500};
    /**
     * While paused for another user or host, how often the prompt is checked again: leaving
     * {@code su} with {@code exit} reports no new folder, so nothing else would resume following.
     */
    static final long FOREIGN_RECHECK_MILLIS = 1_500;

    private final TerminalView view;
    private final RemoteFollowController controller;
    private final RemoteSidebarBrowser browser;
    private final Map<String, TerminalView.SftpOpenRequest> requests = new HashMap<>();
    private final List<RemoteDirectoryChange.Subscription> subscriptions = new ArrayList<>();
    private RemoteDirectoryChange.Subscription directorySubscription = RemoteDirectoryChange.Subscription.NONE;
    private volatile @Nullable SithTermFxWidget activePane;
    private boolean started;
    private boolean disposed;
    private @Nullable Runnable onHideRequested;

    private final ObservableList<SftpFileItem> items = FXCollections.observableArrayList();
    private final FilteredList<SftpFileItem> filtered = new FilteredList<>(items, item -> true);
    private final Label banner = new Label();
    private final Label status = new Label();
    private final FlowPane breadcrumb = new FlowPane(2, 2);
    private final TableView<SftpFileItem> table = new TableView<>(filtered);
    private final TextField filterField = new TextField();
    private final ToggleButton pinButton = new ToggleButton("📌");
    private final Button refreshButton = new Button("⟳");
    private final Button uploadButton = new Button("⇧");
    private final Button downloadButton = new Button("⇩");
    private final Button openInManagerButton = new Button("↗");
    private final Button hideButton = new Button("✕");
    private @Nullable String currentPath;

    private final SftpTransferQueuePane queuePane = new SftpTransferQueuePane();
    private @Nullable SftpTransferQueueHost queueHost;
    private @Nullable String queuePaneKey;
    private @Nullable SFTPSession queueSession;

    private long lastCheckNanos;
    private @Nullable PauseTransition deferredCheck;
    private final List<Runnable> afterDeferredCheck = new ArrayList<>();
    private final List<PauseTransition> promptProbes = new ArrayList<>();
    private @Nullable javafx.animation.Timeline foreignRecheck;
    private final AtomicBoolean promptMarkQueued = new AtomicBoolean();
    private final List<Path> dragOutDirectories = new ArrayList<>();
    private @Nullable TransferCancellation dragOutCancel;

    private @Nullable Parent watchedParent;
    private final ChangeListener<Boolean> parentVisible = (obs, was, now) -> maybeStart();
    private final ChangeListener<Parent> viewParent = (obs, was, now) -> watchParent(now);
    private final ChangeListener<javafx.scene.Scene> viewScene = (obs, was, now) -> maybeStart();
    private boolean watching;

    TerminalRemoteSidebar(TerminalView view) {
        this.view = view;
        this.controller = new RemoteFollowController(TerminalRemoteSidebar::schedule, new RemoteFollowController.Listener() {
            @Override
            public void listRequested(String paneKey, @Nullable String path) {
                requestListing(paneKey, path);
            }

            @Override
            public void stateChanged(RemoteFollowController.State state, @Nullable String path) {
                updateBanner();
                updateForeignRecheck(state);
            }
        });
        this.browser = new RemoteSidebarBrowser(this::onResult, Platform::runLater, this::onSessionLost);
        getStyleClass().add("terminal-remote-sidebar");
        buildUi();
        updateBanner();
        updateButtons();
    }

    // ------------------------------------------------------------------ layout

    private void buildUi() {
        setSpacing(4);
        setPadding(new Insets(4));
        // The local file browser's look (filebrowser.css tokens): -fx-background is Modena's light
        // grey under the Normal design, which left the app's light label text unreadable.
        getStyleClass().add("file-browser-panel");
        URL stylesheet = TerminalRemoteSidebar.class.getResource("/styles/filebrowser.css");
        if (stylesheet != null) {
            getStylesheets().add(stylesheet.toExternalForm());
        }

        Label title = new Label(I18n.get("terminal.remoteSidebar.title"));
        title.setStyle("-fx-font-weight: bold; -fx-text-fill: -kortty-fb-fg;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        decorate(pinButton, "terminal.remoteSidebar.pin");
        decorate(refreshButton, "terminal.remoteSidebar.refresh");
        decorate(uploadButton, "terminal.remoteSidebar.upload");
        decorate(downloadButton, "terminal.remoteSidebar.download");
        decorate(openInManagerButton, "terminal.remoteSidebar.openInManager");
        decorate(hideButton, "terminal.remoteSidebar.hide");
        pinButton.setOnAction(e -> controller.setUserPaused(pinButton.isSelected()));
        refreshButton.setOnAction(e -> refresh());
        uploadButton.setOnAction(e -> chooseAndUpload());
        downloadButton.setOnAction(e -> chooseAndDownload());
        openInManagerButton.setOnAction(e -> openInManager());
        hideButton.setOnAction(e -> {
            Runnable hide = onHideRequested;
            if (hide != null) {
                hide.run();
            }
        });
        HBox header = new HBox(2, title, spacer, pinButton, refreshButton, uploadButton, downloadButton,
            openInManagerButton, hideButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("file-browser-toolbar");

        banner.setWrapText(true);
        banner.setStyle("-fx-font-size: 0.85em; -fx-text-fill: -kortty-fb-fg;");
        status.setWrapText(true);
        status.setStyle("-fx-font-size: 0.85em; -fx-text-fill: -kortty-fb-dim;");

        filterField.setPromptText(I18n.get("terminal.remoteSidebar.filter"));
        filterField.getStyleClass().add("file-browser-filter");
        filterField.textProperty().addListener((obs, was, now) -> applyFilter(now));

        TableColumn<SftpFileItem, String> type = new TableColumn<>("");
        type.setCellValueFactory(new PropertyValueFactory<>("type"));
        type.setPrefWidth(28);
        // The SFTP manager's glyphs: the emoji of SftpFileItem.getType() has no glyph in the table's monospace font.
        SFTPManagerTab.installTypeIconCell(type);
        TableColumn<SftpFileItem, String> name = new TableColumn<>(I18n.get("terminal.remoteSidebar.column.name"));
        name.setCellValueFactory(new PropertyValueFactory<>("name"));
        name.setPrefWidth(150);
        TableColumn<SftpFileItem, String> size = new TableColumn<>(I18n.get("terminal.remoteSidebar.column.size"));
        size.setCellValueFactory(new PropertyValueFactory<>("size"));
        size.setPrefWidth(70);
        TableColumn<SftpFileItem, String> modified = new TableColumn<>(I18n.get("terminal.remoteSidebar.column.modified"));
        modified.setCellValueFactory(new PropertyValueFactory<>("date"));
        modified.setPrefWidth(140);
        for (TableColumn<SftpFileItem, String> column : List.of(type, name, size, modified)) {
            // Listed in the SFTP manager's Type order (SftpFileItemComparators); not re-sorted here.
            column.setSortable(false);
        }
        table.getColumns().addAll(List.of(type, name, size, modified));
        table.getStyleClass().add("file-browser-table");
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.getSelectionModel().getSelectedItems().addListener(
            (javafx.collections.ListChangeListener<SftpFileItem>) change -> updateButtons());
        table.setRowFactory(tableView -> {
            TableRow<SftpFileItem> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !row.isEmpty()) {
                    open(row.getItem());
                }
            });
            row.setOnDragDetected(event -> startDragOut(row, event));
            return row;
        });
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                SftpFileItem selected = table.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    open(selected);
                    event.consume();
                }
            }
        });
        // On the whole sidebar, not only the list: a drop on its header, banner or transfer list
        // must not bubble up to the terminal view, which would copy into the shell's folder instead.
        setOnDragOver(event -> {
            if (acceptsDrop(event.getDragboard(), event.getGestureSource())) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            // Consumed either way: a denied drop must not fall through to the terminal below.
            event.consume();
        });
        setOnDragDropped(event -> {
            boolean accepted = acceptsDrop(event.getDragboard(), event.getGestureSource());
            if (accepted) {
                List<Path> paths = event.getDragboard().getFiles().stream().map(File::toPath).toList();
                Platform.runLater(() -> confirmAndUpload(paths));
            }
            event.setDropCompleted(accepted);
            event.consume();
        });
        VBox.setVgrow(table, Priority.ALWAYS);

        getChildren().addAll(header, banner, breadcrumb, filterField, table, status, queuePane);
        queuePane.setOnStatus(text -> {
            if (!disposed) {
                status.setText(text);
            }
        });
        queuePane.setOnBatchFinished(batch -> refresh());
    }

    private static void decorate(javafx.scene.control.ButtonBase button, String key) {
        String text = I18n.get(key);
        button.setTooltip(new Tooltip(text));
        button.setAccessibleText(text);
        button.setFocusTraversable(false);
        button.getStyleClass().add("file-browser-toolbar-button");
    }

    void setOnHideRequested(@Nullable Runnable onHideRequested) {
        this.onHideRequested = onHideRequested;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Docked into the tab: starts once the tab is visible (the session opens lazily). */
    void mounted() {
        if (disposed) {
            return;
        }
        if (!watching) {
            watching = true;
            view.parentProperty().addListener(viewParent);
            view.sceneProperty().addListener(viewScene);
            watchParent(view.getParent());
        }
        maybeStart();
    }

    private void watchParent(@Nullable Parent parent) {
        if (watchedParent != null) {
            watchedParent.visibleProperty().removeListener(parentVisible);
        }
        watchedParent = parent;
        if (parent != null) {
            parent.visibleProperty().addListener(parentVisible);
        }
        maybeStart();
    }

    /** Starts following when the sidebar is in a shown tab; a hidden tab's sidebar opens no channel. */
    private void maybeStart() {
        if (started || disposed || getParent() == null || view.getScene() == null) {
            return;
        }
        Parent parent = view.getParent();
        if (parent != null && !parent.isVisible()) {
            return;
        }
        started = true;
        subscriptions.add(view.focusedPaneChanges(this::onFocusedPane));
        subscriptions.add(view.promptMarks(widget -> {
            // Emulator thread: only the followed pane's marks, coalesced to one FX task.
            if (widget == activePane && promptMarkQueued.compareAndSet(false, true)) {
                Platform.runLater(() -> {
                    promptMarkQueued.set(false);
                    onPromptMark(widget);
                });
            }
        }));
        subscriptions.add(view.paneSessionChanges(this::onPaneSessionsChanged));
        SithTermFxWidget pane = pickPane(view.focusedPane());
        if (pane == null || !activate(pane, false)) {
            controller.reset();
            status.setText(I18n.get("terminal.remoteSidebar.noSshPane"));
        }
    }

    /** The number of transfers hiding the sidebar would cancel. */
    int activeTransferCount() {
        SftpTransferQueueHost host = queueHost;
        return host != null ? host.activeTransferCount() : 0;
    }

    /** Hidden for good: stops following, cancels transfers and closes the SFTP session off the FX thread. */
    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        for (RemoteDirectoryChange.Subscription subscription : subscriptions) {
            subscription.close();
        }
        subscriptions.clear();
        directorySubscription.close();
        directorySubscription = RemoteDirectoryChange.Subscription.NONE;
        if (watchedParent != null) {
            watchedParent.visibleProperty().removeListener(parentVisible);
            watchedParent = null;
        }
        if (watching) {
            watching = false;
            view.parentProperty().removeListener(viewParent);
            view.sceneProperty().removeListener(viewScene);
        }
        stopTimers();
        controller.reset();
        activePane = null;
        requests.clear();
        cancelDragOut();
        deleteDragOutDirectories();
        queuePane.dispose();
        SftpTransferQueueHost host = queueHost;
        queueHost = null;
        if (host != null) {
            host.close();
        }
        Thread closer = new Thread(() -> {
            if (host != null) {
                host.awaitStopped(5_000);
            }
            browser.close();
        }, "SFTP-Sidebar-Close");
        closer.setDaemon(true);
        closer.start();
    }

    private void stopTimers() {
        if (deferredCheck != null) {
            deferredCheck.stop();
            deferredCheck = null;
        }
        afterDeferredCheck.clear();
        stopPromptProbes();
        stopForeignRecheck();
    }

    /** Re-checks the prompt now and then while paused for another identity, so {@code exit} resumes. */
    private void updateForeignRecheck(RemoteFollowController.State state) {
        if (state != RemoteFollowController.State.PAUSED_FOREIGN || disposed) {
            stopForeignRecheck();
            return;
        }
        if (foreignRecheck != null) {
            return;
        }
        javafx.animation.Timeline timeline = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(Duration.millis(FOREIGN_RECHECK_MILLIS), event -> {
                if (!disposed && controller.state() == RemoteFollowController.State.PAUSED_FOREIGN) {
                    checkThen(null);
                }
            }));
        timeline.setCycleCount(javafx.animation.Animation.INDEFINITE);
        foreignRecheck = timeline;
        timeline.play();
    }

    private void stopForeignRecheck() {
        javafx.animation.Timeline timeline = foreignRecheck;
        foreignRecheck = null;
        if (timeline != null) {
            timeline.stop();
        }
    }

    private void stopPromptProbes() {
        for (PauseTransition probe : promptProbes) {
            probe.stop();
        }
        promptProbes.clear();
    }

    // ------------------------------------------------------------------ following

    /** The preferred pane when it can lend its session, else the first one that can; null when none. */
    private @Nullable SithTermFxWidget pickPane(@Nullable SithTermFxWidget preferred) {
        if (preferred != null && view.borrowedSessionSupplier(preferred) != null) {
            return preferred;
        }
        for (SithTermFxWidget pane : view.remoteSidebarPanes()) {
            if (view.borrowedSessionSupplier(pane) != null) {
                return pane;
            }
        }
        return null;
    }

    /** Follows {@code pane}; false when it cannot lend its session (local shell, Mosh, closed). */
    private boolean activate(SithTermFxWidget pane, boolean reconnect) {
        TerminalView.SftpOpenRequest request = view.captureSftpOpenRequest(pane);
        if (request == null) {
            return false;
        }
        String key = bind(pane, request);
        RemoteFollowController.Verdict verdict = verdictOf(request);
        if (reconnect) {
            controller.reconnected(key, request.target().startPath(), verdict);
        } else {
            controller.activate(key, request.target().startPath(), verdict);
        }
        return true;
    }

    private String bind(SithTermFxWidget pane, TerminalView.SftpOpenRequest request) {
        String key = TerminalView.remoteSidebarPaneKey(pane);
        requests.put(key, request);
        activePane = pane;
        stopPromptProbes();
        directorySubscription.close();
        directorySubscription = view.paneRemoteDirectoryChanges(pane,
            change -> Platform.runLater(() -> onDirectoryChange(pane, key, change)));
        return key;
    }

    private static RemoteFollowController.Verdict verdictOf(TerminalView.SftpOpenRequest request) {
        return request.target().reason() == SftpOpenTargetResolver.Reason.FOREIGN_SESSION
            ? RemoteFollowController.Verdict.FOREIGN
            : RemoteFollowController.Verdict.UNKNOWN;
    }

    private void onFocusedPane(SithTermFxWidget pane) {
        if (disposed || pane == null || pane == activePane
                && controller.state() != RemoteFollowController.State.UNAVAILABLE) {
            return;
        }
        TerminalView.SftpOpenRequest request = view.captureSftpOpenRequest(pane);
        if (request == null) {
            // A local or Mosh pane: the sidebar stays on the SSH pane it follows.
            return;
        }
        if (activeTransferCount() > 0) {
            status.setText(I18n.get("terminal.remoteSidebar.transfersRunning"));
            return;
        }
        String key = bind(pane, request);
        controller.focusChanged(key, true, request.target().startPath(), verdictOf(request));
    }

    private void onDirectoryChange(SithTermFxWidget pane, String key, RemoteDirectoryChange change) {
        if (disposed || pane != activePane || !key.equals(controller.activePane())) {
            return;
        }
        if (change.source() == RemoteDirectoryChange.Source.TYPED_CD) {
            // The screen still shows the line the cd was typed on; a new last line is the next prompt.
            String baseline = view.remoteSidebarLastLine(pane);
            controller.directoryChanged(key, change.path(), change.source());
            schedulePromptProbes(pane, key, baseline);
            return;
        }
        // The identity is checked first: a report from a shell of another user must pause the
        // sidebar before its folder can be listed, not after the debounce already listed it.
        checkThen(() -> {
            if (!disposed && pane == activePane && key.equals(controller.activePane())) {
                controller.directoryChanged(key, change.path(), change.source());
            }
        });
    }

    private void schedulePromptProbes(SithTermFxWidget pane, String key, String baseline) {
        stopPromptProbes();
        for (long delay : PROMPT_PROBE_DELAYS) {
            PauseTransition probe = new PauseTransition(Duration.millis(delay));
            probe.setOnFinished(event -> {
                promptProbes.remove(probe);
                if (disposed || pane != activePane || !controller.hasPending()) {
                    return;
                }
                TerminalView.RemoteSidebarProbe seen = view.probeRemoteSidebarPrompt(pane);
                lastCheckNanos = System.nanoTime();
                if (seen.lastLine().equals(baseline)) {
                    return; // no new prompt yet
                }
                controller.promptSeen(key, seen.verdict());
            });
            promptProbes.add(probe);
            probe.play();
        }
    }

    private void onPromptMark(SithTermFxWidget pane) {
        if (disposed || pane != activePane) {
            return;
        }
        String key = controller.activePane();
        checkThen(() -> {
            if (key != null) {
                controller.promptMark(key);
            }
        });
    }

    /**
     * Runs the foreign-session check of the followed pane (it reads the screen), at most once per
     * {@link #CHECK_INTERVAL_MILLIS}, then {@code after}; inside the interval both wait for its end.
     */
    private void checkThen(@Nullable Runnable after) {
        if (after != null) {
            afterDeferredCheck.add(after);
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastCheckNanos);
        if (lastCheckNanos == 0 || elapsedMillis >= CHECK_INTERVAL_MILLIS) {
            runCheck();
            return;
        }
        if (deferredCheck == null) {
            PauseTransition wait = new PauseTransition(Duration.millis(CHECK_INTERVAL_MILLIS - elapsedMillis));
            wait.setOnFinished(event -> {
                deferredCheck = null;
                runCheck();
            });
            deferredCheck = wait;
            wait.play();
        }
    }

    private void runCheck() {
        List<Runnable> after = List.copyOf(afterDeferredCheck);
        afterDeferredCheck.clear();
        SithTermFxWidget pane = activePane;
        String key = controller.activePane();
        if (disposed || pane == null || key == null) {
            return;
        }
        lastCheckNanos = System.nanoTime();
        controller.verdict(key, view.probeRemoteSidebarPrompt(pane).verdict());
        for (Runnable task : after) {
            task.run();
        }
    }

    private void onPaneSessionsChanged() {
        if (disposed || !started) {
            return;
        }
        SithTermFxWidget pane = activePane;
        if (pane == null || !view.remoteSidebarPanes().contains(pane)) {
            SithTermFxWidget next = pickPane(view.focusedPane());
            if (next == null || !activate(next, false)) {
                directorySubscription.close();
                directorySubscription = RemoteDirectoryChange.Subscription.NONE;
                activePane = null;
                controller.reset();
                browser.release();
                status.setText(I18n.get("terminal.remoteSidebar.noSshPane"));
            }
            return;
        }
        String key = TerminalView.remoteSidebarPaneKey(pane);
        boolean lends = view.borrowedSessionSupplier(pane) != null;
        if (!lends) {
            if (controller.state() != RemoteFollowController.State.UNAVAILABLE) {
                controller.disconnected(key);
                browser.release();
                SftpTransferQueueHost host = queueHost;
                if (host != null) {
                    host.onSessionLost();
                }
            }
        } else if (controller.state() == RemoteFollowController.State.UNAVAILABLE) {
            // The pane connected again: a new connector, so subscribe and list again.
            activate(pane, true);
        }
    }

    /** The sidebar's SFTP channel closed under it (the session ended or the server dropped it). */
    private void onSessionLost() {
        if (disposed) {
            return;
        }
        String key = controller.activePane();
        if (key != null) {
            controller.disconnected(key);
        }
        SftpTransferQueueHost host = queueHost;
        if (host != null) {
            host.onSessionLost();
        }
        updateButtons();
    }

    // ------------------------------------------------------------------ listing

    private void requestListing(String paneKey, @Nullable String path) {
        TerminalView.SftpOpenRequest request = requests.get(paneKey);
        if (request == null || disposed) {
            return;
        }
        status.setText(I18n.get("terminal.remoteSidebar.loading"));
        browser.list(paneKey, () -> SFTPSession.attach(request.supplier(), request.paneConnection(), request.label()), path);
    }

    private void refresh() {
        String key = controller.activePane();
        SithTermFxWidget pane = activePane;
        if (key == null || pane == null) {
            return;
        }
        if (controller.state() == RemoteFollowController.State.UNAVAILABLE) {
            activate(pane, true);
            return;
        }
        requestListing(key, currentPath);
    }

    private void open(SftpFileItem item) {
        if (item == null || item.isFile()) {
            return;
        }
        String key = controller.activePane();
        if (key == null || controller.state() == RemoteFollowController.State.UNAVAILABLE) {
            return;
        }
        controller.browsed(item.getPath());
        requestListing(key, item.getPath());
    }

    private void browseTo(String path) {
        String key = controller.activePane();
        if (key == null || controller.state() == RemoteFollowController.State.UNAVAILABLE) {
            return;
        }
        controller.browsed(path);
        requestListing(key, path);
    }

    private void onResult(RemoteSidebarBrowser.Result result) {
        if (disposed || !result.paneKey().equals(controller.activePane())) {
            return;
        }
        if (result instanceof RemoteSidebarBrowser.Listing listing) {
            currentPath = listing.path();
            items.setAll(listing.items());
            applyFilter(filterField.getText());
            rebuildBreadcrumb(listing.path());
            status.setText("");
            readyQueue(listing.paneKey());
        } else if (result instanceof RemoteSidebarBrowser.Failure failure) {
            String path = failure.path() != null ? failure.path() : "~";
            switch (failure.kind()) {
                // The last listing stays; no dialog.
                case NOT_FOUND -> status.setText(I18n.get("terminal.remoteSidebar.pathNotFound", path));
                case UNAVAILABLE -> {
                    status.setText(I18n.get("terminal.remoteSidebar.sessionUnavailable", failure.message()));
                    controller.disconnected(failure.paneKey());
                }
                default -> status.setText(I18n.get("terminal.remoteSidebar.listFailed", path, failure.message()));
            }
        }
        updateBanner();
        updateButtons();
    }

    private void applyFilter(@Nullable String text) {
        String query = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        filtered.setPredicate(item -> query.isEmpty() || item.isParentEntry()
            || item.getName().toLowerCase(Locale.ROOT).contains(query));
    }

    private void rebuildBreadcrumb(String path) {
        breadcrumb.getChildren().clear();
        Hyperlink root = crumb("/", "/");
        breadcrumb.getChildren().add(root);
        StringBuilder sofar = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            sofar.append('/').append(part);
            breadcrumb.getChildren().add(crumb(part, sofar.toString()));
        }
    }

    private Hyperlink crumb(String label, String target) {
        Hyperlink link = new Hyperlink(label);
        link.setPadding(new Insets(0, 2, 0, 2));
        link.setOnAction(e -> browseTo(target));
        return link;
    }

    private void updateBanner() {
        RemoteFollowController.State state = controller.state();
        String shown = currentPath != null ? currentPath : controller.followedPath();
        String text = switch (state) {
            case FOLLOWING -> I18n.get("terminal.remoteSidebar.following", shown != null ? shown : "~");
            case PAUSED_FOREIGN -> I18n.get("terminal.remoteSidebar.pausedForeign");
            case PAUSED_USER -> I18n.get("terminal.remoteSidebar.pausedUser");
            case UNAVAILABLE -> I18n.get("terminal.remoteSidebar.unavailable");
        };
        banner.setText(text);
        pinButton.setSelected(controller.isUserPaused());
    }

    // ------------------------------------------------------------------ transfers

    private boolean connected() {
        return !disposed && currentPath != null && browser.session() != null
            && controller.state() != RemoteFollowController.State.UNAVAILABLE;
    }

    private static boolean allowed(FileTransferGate.Route route) {
        return FileTransferGate.current(route).allowed();
    }

    /** Greys out what the policy denies, up front, with its reason as the tooltip (D6). */
    private void updateButtons() {
        boolean connected = connected();
        boolean uploadAllowed = allowed(FileTransferGate.Route.SFTP_UPLOAD);
        boolean downloadAllowed = allowed(FileTransferGate.Route.SFTP_DOWNLOAD);
        uploadButton.setDisable(!uploadAllowed || !connected);
        downloadButton.setDisable(!downloadAllowed || !connected || selectedEntries().isEmpty());
        uploadButton.setTooltip(new Tooltip(uploadAllowed ? I18n.get("terminal.remoteSidebar.upload")
            : FileTransferGate.current(FileTransferGate.Route.SFTP_UPLOAD).reason()));
        downloadButton.setTooltip(new Tooltip(downloadAllowed ? I18n.get("terminal.remoteSidebar.download")
            : FileTransferGate.current(FileTransferGate.Route.SFTP_DOWNLOAD).reason()));
        refreshButton.setDisable(disposed || controller.activePane() == null);
        openInManagerButton.setDisable(!connected || view.sftpOpenAtHandler() == null);
        pinButton.setDisable(controller.activePane() == null);
    }

    private List<SftpFileItem> selectedEntries() {
        return table.getSelectionModel().getSelectedItems().stream()
            .filter(item -> item != null && !item.isParentEntry())
            .toList();
    }

    private boolean acceptsDrop(Dragboard dragboard, Object gestureSource) {
        if (gestureSource instanceof Node node && isInside(node)) {
            return false;
        }
        return dragboard.hasFiles() && connected() && allowed(FileTransferGate.Route.SFTP_UPLOAD);
    }

    private boolean isInside(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == this) {
                return true;
            }
        }
        return false;
    }

    private void chooseAndUpload() {
        if (!connected() || !allowed(FileTransferGate.Route.SFTP_UPLOAD)) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("terminal.remoteSidebar.upload"));
        List<File> files = chooser.showOpenMultipleDialog(ownerWindow());
        if (files == null || files.isEmpty()) {
            return;
        }
        confirmAndUpload(files.stream().map(File::toPath).toList());
    }

    /** Uploads into the folder shown, after a confirmation that names it (the shell may be elsewhere). */
    private void confirmAndUpload(List<Path> paths) {
        Path[] usable = paths.stream().filter(path -> path.getFileName() != null).toArray(Path[]::new);
        String folder = currentPath;
        if (usable.length == 0 || folder == null || !connected()) {
            return;
        }
        Optional<String> refusal = Optional.ofNullable(FileTransferGate.current(FileTransferGate.Route.SFTP_UPLOAD).reason());
        if (refusal.isPresent()) {
            status.setText(refusal.get());
            return;
        }
        String message = I18n.get("terminal.remoteSidebar.confirmUpload",
            String.valueOf(usable.length), folder, hostLabel());
        if (!confirm(message)) {
            return;
        }
        SftpTransferQueueHost host = queueHost;
        if (host != null && host.enqueueUpload(List.of(usable), folder).isPresent()) {
            queuePane.reveal();
        }
    }

    private void chooseAndDownload() {
        List<SftpFileItem> selected = selectedEntries();
        String folder = currentPath;
        if (selected.isEmpty() || folder == null || !connected()) {
            return;
        }
        Optional<String> refusal = Optional.ofNullable(FileTransferGate.current(FileTransferGate.Route.SFTP_DOWNLOAD).reason());
        if (refusal.isPresent()) {
            status.setText(refusal.get());
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("sftp.selectTargetFolder"));
        File target = chooser.showDialog(ownerWindow());
        if (target == null) {
            return;
        }
        String message = I18n.get("terminal.remoteSidebar.confirmDownload",
            String.valueOf(selected.size()), folder, hostLabel(), target.getAbsolutePath());
        if (!confirm(message)) {
            return;
        }
        List<RemoteEntryRef> entries = selected.stream().map(SFTPManagerTab::remoteEntryRef).toList();
        SftpTransferQueueHost host = queueHost;
        if (host != null && host.enqueueDownload(entries, target.toPath()).isPresent()) {
            queuePane.reveal();
        }
    }

    private boolean confirm(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
            message + "\n\n" + I18n.get("terminal.remoteSidebar.confirm.lagHint"), ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle(I18n.get("terminal.remoteSidebar.confirm.title"));
        alert.setHeaderText(null);
        Window owner = ownerWindow();
        if (owner != null) {
            alert.initOwner(owner);
        }
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private String hostLabel() {
        TerminalView.SftpOpenRequest request = activeRequest();
        ServerConnection connection = request != null ? request.paneConnection() : null;
        if (connection == null) {
            return "?";
        }
        String user = connection.getUsername();
        return (user != null && !user.isBlank() ? user + "@" : "") + connection.getHost();
    }

    private @Nullable TerminalView.SftpOpenRequest activeRequest() {
        String key = controller.activePane();
        return key != null ? requests.get(key) : null;
    }

    /**
     * Gives the transfer queue the session just listed with. One queue per followed pane: a
     * reconnect of the same pane moves the queue to the new session (failed rows can be retried),
     * another pane gets a fresh queue, so a retry can never land on another server.
     */
    private void readyQueue(String paneKey) {
        SFTPSession session = browser.session();
        if (session == null || session == queueSession && paneKey.equals(queuePaneKey)) {
            return;
        }
        SftpTransferQueueHost host = queueHost;
        if (host != null && !paneKey.equals(queuePaneKey)) {
            host.close();
            host = null;
        }
        if (host == null) {
            host = createQueueHost();
            queueHost = host;
        }
        queuePaneKey = paneKey;
        queueSession = session;
        host.onSessionReady(session);
    }

    private SftpTransferQueueHost createQueueHost() {
        TerminalView.SftpOpenRequest request = activeRequest();
        ServerConnection connection = request != null ? request.paneConnection() : null;
        String resumeScope = connection == null ? "terminal"
            : connection.getId() != null && !connection.getId().isBlank() ? connection.getId()
            : connection.getUsername() + "@" + connection.getHost() + ":" + connection.getPort();
        SftpTransferQueueHost[] created = new SftpTransferQueueHost[1];
        created[0] = new SftpTransferQueueHost(
            () -> SftpTransferQueueHost.settingsFrom(globalSettings(), PolicyManager.effective(), resumeScope),
            () -> new FxConflictResolver(SftpConflictDialog.presenter(this::ownerWindow)),
            queuePane.listener(),
            () -> created[0].queue().ifPresent(queuePane::setQueue));
        return created[0];
    }

    private static @Nullable GlobalSettings globalSettings() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        return app != null && app.getGlobalSettingsManager() != null ? app.getGlobalSettingsManager().getSettings() : null;
    }

    private void openInManager() {
        java.util.function.BiConsumer<SithTermFxWidget, String> handler = view.sftpOpenAtHandler();
        SithTermFxWidget pane = activePane;
        if (handler != null && pane != null && currentPath != null) {
            handler.accept(pane, currentPath);
        }
    }

    private @Nullable Window ownerWindow() {
        return getScene() != null ? getScene().getWindow() : null;
    }

    // ------------------------------------------------------------------ drag out

    /**
     * Starts a drag of the selected files out of the window: they are downloaded into a new
     * owner-only temporary folder first (pipelined, see {@code SftpStreamCopier}), with the SFTP
     * manager's caps and wait. Folders, too much data or a policy denial offer no drag at all.
     */
    private void startDragOut(TableRow<SftpFileItem> row, javafx.scene.input.MouseEvent event) {
        if (row.isEmpty() || !connected() || !allowed(FileTransferGate.Route.SFTP_DRAG_OUT)) {
            return;
        }
        List<SftpFileItem> selected = selectedEntries();
        if (selected.isEmpty()) {
            return;
        }
        List<File> prepared = prepareDragOut(selected);
        if (prepared.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putFiles(prepared);
        Dragboard dragboard = row.startDragAndDrop(TransferMode.COPY);
        dragboard.setContent(content);
        event.consume();
    }

    private List<File> prepareDragOut(List<SftpFileItem> selected) {
        cancelDragOut();
        deleteDragOutDirectories();
        if (SftpDragOutPolicy.check(selected) != SftpDragOutPolicy.Verdict.ALLOWED) {
            status.setText(I18n.get("sftp.dragOut.tooLarge", String.valueOf(SftpDragOutPolicy.MAX_FILES),
                String.valueOf(SftpDragOutPolicy.MAX_TOTAL_BYTES / (1024 * 1024)) + " MB"));
            return List.of();
        }
        SFTPSession session = browser.session();
        if (session == null) {
            return List.of();
        }
        Path directory;
        try {
            directory = Files.createTempDirectory("kortty-sftp-drag-");
        } catch (IOException e) {
            status.setText(I18n.get("sftp.dragOut.failed", String.valueOf(e.getMessage())));
            return List.of();
        }
        dragOutDirectories.add(directory);
        TransferCancellation cancel = TransferCancellation.create();
        dragOutCancel = cancel;
        FutureTask<List<File>> download = new FutureTask<>(
            () -> SFTPManagerTab.downloadForDragOut(session, selected, directory, cancel));
        Thread worker = new Thread(download, "SFTP-Sidebar-DragOut");
        worker.setDaemon(true);
        worker.start();
        try {
            return download.get(SftpDragOutPolicy.MAX_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancel.cancel();
            download.cancel(true);
            status.setText(I18n.get("sftp.dragOut.timeout", String.valueOf(SftpDragOutPolicy.MAX_WAIT.toSeconds())));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.info("Remote files could not be prepared for a drag out of the sidebar: {}", cause.getMessage());
            status.setText(I18n.get("sftp.dragOut.failed", String.valueOf(cause.getMessage())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel.cancel();
            download.cancel(true);
        }
        return List.of();
    }

    private void cancelDragOut() {
        TransferCancellation cancel = dragOutCancel;
        dragOutCancel = null;
        if (cancel != null) {
            cancel.cancel();
        }
    }

    private void deleteDragOutDirectories() {
        dragOutDirectories.removeIf(TerminalRemoteSidebar::deleteTreeQuietly);
    }

    private static boolean deleteTreeQuietly(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        // Files.walk does not follow links by default, so a link inside is deleted, not its target.
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException | java.io.UncheckedIOException e) {
            logger.debug("Could not delete the sidebar's drag-out folder {} yet", root, e);
            return false;
        }
    }

    // ------------------------------------------------------------------ scheduling

    /** {@link RemoteFollowController}'s timers on the FX thread. */
    private static RemoteFollowController.Cancellable schedule(Runnable task, long delayMillis) {
        PauseTransition timer = new PauseTransition(Duration.millis(delayMillis));
        timer.setOnFinished(event -> task.run());
        timer.play();
        return timer::stop;
    }
}
