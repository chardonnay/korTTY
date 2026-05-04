package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.jobscheduler.JobSchedulerConnectionResolver;
import de.kortty.jobscheduler.JobSchedulerRemoteSession;
import de.kortty.jobscheduler.JobSchedulerService;
import de.kortty.jobscheduler.PinnedHostKey;
import de.kortty.model.ServerConnection;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/**
 * Remote directory picker for JobScheduler fields.
 */
public class JobSchedulerRemoteDirectoryDialog extends ThemeAwareDialog<String> {

    private final KorTTYApplication app;
    private final JobSchedulerService schedulerService;
    private final String connectionId;
    private final String initialPath;
    private final boolean hostKeyVerificationDisabled;
    private final ObservableList<JobSchedulerRemoteSession.RemoteDirectoryEntry> directories = FXCollections.observableArrayList();
    private final ListView<JobSchedulerRemoteSession.RemoteDirectoryEntry> directoryList = new ListView<>(directories);
    private final TextField pathField = new TextField();
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Object remoteSessionLock = new Object();
    private volatile long directoryRequestId;
    private JobSchedulerRemoteSession remoteSession;
    private Task<DirectorySnapshot> currentDirectoryTask;
    private Button selectButton;
    private Button openButton;
    private Button upButton;
    private Button refreshButton;

    public JobSchedulerRemoteDirectoryDialog(
        KorTTYApplication app,
        JobSchedulerService schedulerService,
        Window owner,
        String connectionId,
        String initialPath,
        boolean hostKeyVerificationDisabled) {

        this.app = app;
        this.schedulerService = schedulerService;
        this.connectionId = connectionId;
        this.initialPath = initialPath != null && !initialPath.isBlank() ? initialPath.trim() : ".";
        this.hostKeyVerificationDisabled = hostKeyVerificationDisabled;

        setTitle(I18n.get("dialog.remoteDirectory.title"));
        setHeaderText(I18n.get("dialog.remoteDirectory.header"));
        if (owner != null) {
            initOwner(owner);
        }
        initModality(Modality.WINDOW_MODAL);
        setResizable(true);

        buildUi();
        setOnShown(event -> loadDirectory(this.initialPath));
        setOnHidden(event -> {
            cancelCurrentDirectoryTask();
            closeRemoteSession();
        });
        setResultConverter(buttonType -> buttonType != null
            && buttonType.getButtonData() == ButtonBar.ButtonData.OK_DONE
                ? pathField.getText()
                : null);
    }

    private void buildUi() {
        pathField.setEditable(false);
        pathField.setMaxWidth(Double.MAX_VALUE);
        progress.setMaxSize(18, 18);
        progress.setVisible(false);
        progress.setManaged(false);

        upButton = new Button(I18n.get("dialog.remoteDirectory.button.up"));
        upButton.setOnAction(event -> loadDirectory(parentPath(pathField.getText())));
        openButton = new Button(I18n.get("dialog.remoteDirectory.button.open"));
        openButton.setOnAction(event -> openSelectedDirectory());
        refreshButton = new Button(I18n.get("dialog.remoteDirectory.button.refresh"));
        refreshButton.setOnAction(event -> loadDirectory(pathField.getText()));

        directoryList.setPrefSize(620, 420);
        directoryList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                openSelectedDirectory();
            }
        });

        HBox pathBox = new HBox(8, new Label(I18n.get("dialog.remoteDirectory.label.path")), pathField, progress);
        HBox.setHgrow(pathField, Priority.ALWAYS);
        HBox buttons = new HBox(8, upButton, openButton, refreshButton);
        VBox content = new VBox(10, pathBox, directoryList, buttons, statusLabel);
        content.setPadding(new Insets(10));
        VBox.setVgrow(directoryList, Priority.ALWAYS);
        getDialogPane().setContent(content);
        getDialogPane().setPrefSize(720, 580);

        ButtonType selectType = new ButtonType(I18n.get("dialog.remoteDirectory.button.select"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(I18n.get("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(selectType, cancelType);
        selectButton = (Button) getDialogPane().lookupButton(selectType);
        selectButton.setDisable(true);
    }

    private void openSelectedDirectory() {
        JobSchedulerRemoteSession.RemoteDirectoryEntry selected = directoryList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            loadDirectory(selected.path());
        }
    }

    private void loadDirectory(String requestedPath) {
        Task<DirectorySnapshot> previousTask = currentDirectoryTask;
        if (previousTask != null && previousTask.isRunning()) {
            previousTask.cancel();
        }
        long requestId = ++directoryRequestId;
        setBusy(true, I18n.get("dialog.remoteDirectory.status.loading"));
        Task<DirectorySnapshot> task = new Task<>() {
            @Override
            protected DirectorySnapshot call() throws Exception {
                ensureCurrentRequest(requestId);
                JobSchedulerRemoteSession session = ensureRemoteSession(requestId);
                ensureCurrentRequest(requestId);
                String canonicalPath = session.canonicalPath(requestedPath);
                ensureCurrentRequest(requestId);
                List<JobSchedulerRemoteSession.RemoteDirectoryEntry> entries = session.listDirectories(canonicalPath);
                ensureCurrentRequest(requestId);
                return new DirectorySnapshot(canonicalPath, entries);
            }
        };
        currentDirectoryTask = task;
        task.setOnSucceeded(event -> {
            if (!isCurrentDirectoryTask(task, requestId)) {
                return;
            }
            currentDirectoryTask = null;
            DirectorySnapshot snapshot = task.getValue();
            pathField.setText(snapshot.path());
            directories.setAll(snapshot.directories());
            selectButton.setDisable(false);
            setBusy(false, snapshot.directories().isEmpty() ? I18n.get("dialog.remoteDirectory.status.empty") : "");
        });
        task.setOnFailed(event -> {
            if (!isCurrentDirectoryTask(task, requestId)) {
                return;
            }
            currentDirectoryTask = null;
            Throwable error = task.getException();
            directories.clear();
            selectButton.setDisable(pathField.getText() == null || pathField.getText().isBlank());
            setBusy(false, error != null && error.getMessage() != null
                ? error.getMessage()
                : I18n.get("dialog.remoteDirectory.error.fetch"));
        });
        task.setOnCancelled(event -> {
            if (!isCurrentDirectoryTask(task, requestId)) {
                return;
            }
            currentDirectoryTask = null;
            setBusy(false, "");
        });
        Thread worker = new Thread(task, "JobScheduler-RemoteDirectory-Browse");
        worker.setDaemon(true);
        worker.start();
    }

    private JobSchedulerRemoteSession ensureRemoteSession(long requestId) throws Exception {
        synchronized (remoteSessionLock) {
            ensureCurrentRequest(requestId);
            if (remoteSession != null) {
                return remoteSession;
            }
        }
        ServerConnection connection = new JobSchedulerConnectionResolver(app).resolve(connectionId);
        Optional<PinnedHostKey> pinnedHostKey = schedulerService.findPinnedHostKey(connection.getId());
        if (!hostKeyVerificationDisabled && pinnedHostKey.isEmpty()) {
            throw new IllegalStateException(I18n.get("dialog.remoteDirectory.error.hostKeyNotPinned"));
        }
        char[] masterPassword = app.getMasterPasswordManager() != null
            ? app.getMasterPasswordManager().getMasterPassword()
            : null;
        JobSchedulerRemoteSession createdSession = new JobSchedulerRemoteSession(
            app,
            connection,
            pinnedHostKey.orElse(null),
            masterPassword,
            hostKeyVerificationDisabled);
        boolean storedSession = false;
        try {
            createdSession.connect();
            synchronized (remoteSessionLock) {
                ensureCurrentRequest(requestId);
                if (remoteSession == null) {
                    remoteSession = createdSession;
                    storedSession = true;
                    return remoteSession;
                }
                return remoteSession;
            }
        } finally {
            if (!storedSession) {
                createdSession.close();
            }
        }
    }

    private void ensureCurrentRequest(long requestId) {
        if (requestId != directoryRequestId) {
            throw new CancellationException();
        }
    }

    private boolean isCurrentDirectoryTask(Task<DirectorySnapshot> task, long requestId) {
        return requestId == directoryRequestId
            && currentDirectoryTask == task
            && !task.isCancelled();
    }

    private void cancelCurrentDirectoryTask() {
        directoryRequestId++;
        Task<DirectorySnapshot> task = currentDirectoryTask;
        currentDirectoryTask = null;
        if (task != null && task.isRunning()) {
            task.cancel();
        }
    }

    private void setBusy(boolean busy, String message) {
        progress.setVisible(busy);
        progress.setManaged(busy);
        directoryList.setDisable(busy);
        upButton.setDisable(busy);
        openButton.setDisable(busy);
        refreshButton.setDisable(busy);
        statusLabel.setText(message != null ? message : "");
    }

    private String parentPath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "/";
        }
        String normalized = path.endsWith("/") && path.length() > 1
            ? path.substring(0, path.length() - 1)
            : path;
        int slash = normalized.lastIndexOf('/');
        if (slash <= 0) {
            return "/";
        }
        return normalized.substring(0, slash);
    }

    private void closeRemoteSession() {
        JobSchedulerRemoteSession session;
        synchronized (remoteSessionLock) {
            session = remoteSession;
            remoteSession = null;
        }
        if (session != null) {
            Thread closer = new Thread(session::close, "JobScheduler-RemoteDirectory-Close");
            closer.setDaemon(true);
            closer.start();
        }
    }

    private record DirectorySnapshot(String path, List<JobSchedulerRemoteSession.RemoteDirectoryEntry> directories) {
    }
}
