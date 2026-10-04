package de.kortty.ui.sftp;

import de.kortty.core.remote.RemoteCommandRunner;
import de.kortty.core.remote.SudoAuthenticationException;
import de.kortty.core.remote.SudoPasswordRequiredException;
import de.kortty.core.remote.SudoRequiresTtyException;
import de.kortty.core.remote.edit.ExternalEditorLauncher;
import de.kortty.core.remote.edit.RemoteEdit;
import de.kortty.core.remote.edit.RemoteEditSession;
import de.kortty.core.remote.edit.RemoteEditTempDirs;
import de.kortty.core.remote.edit.RemoteEditWatcher;
import de.kortty.core.remote.edit.SudoEditService;
import de.kortty.telemetry.RemoteEditTelemetry;
import de.kortty.telemetry.Telemetry;
import de.kortty.telemetry.TelemetryEvents;
import de.kortty.ui.I18n;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * "Edit in External Editor" of the SFTP manager and its <b>Remote edits</b> list (D12, D13).
 *
 * <p>Opening downloads the file into a private folder ({@link RemoteEditSession}) and starts the
 * editor ({@link ExternalEditorLauncher}). A {@link RemoteEditWatcher} notices each save, which is
 * uploaded automatically with a note in the status bar; a change on the server meanwhile asks
 * <b>Overwrite server file</b>, <b>Save local copy as...</b> or <b>Stop watching</b>. Each row
 * shows the file, its state and the last upload, with <b>Upload now</b> (for editors whose saves
 * the polling misses) and <b>Stop</b>. Under a read-only policy the file opens without uploads.
 *
 * <p><b>Edit as root (sudo)...</b> ({@link #openAsRoot}) reads and writes the file through sudo
 * ({@link SudoEditService}): {@code sudo -n true} first, then a masked password prompt, or the
 * JobScheduler's saved sudo password when the user opted in for the server (D15). A symbolic link
 * is opened only after its target was shown and confirmed. Such rows carry a <b>sudo</b> badge;
 * their password lives in the edit session only and is wiped when it stops. The session journal
 * of a capturing terminal on the same connection gets one line, {@code sudo-edit <path>}.
 *
 * <p>Stop, closing the tab and losing the connection stop the watching and delete the local copy;
 * a lost connection with changes that were not uploaded first offers to keep a copy. All SFTP and
 * file work runs on one worker thread, one action at a time; the list itself is FX-thread only.
 */
public final class SftpRemoteEdits extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(SftpRemoteEdits.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** What the list needs from its SFTP tab. */
    public interface Host {
        /** The tab's current SFTP channel; throws when it is not connected. */
        RemoteEditSession.ClientSource clientSource();

        /** The window dialogs belong to, or {@code null}. */
        Window ownerWindow();

        /** Shows {@code text} in the tab's status bar. */
        void status(String text);

        /** The configured editor command, or blank for the system's text editor. */
        String editorCommand();

        /** Stores a new editor command the user entered. */
        void saveEditorCommand(String command);

        /** Styles a dialog like the rest of the tab. */
        void styleDialog(javafx.scene.control.Dialog<?> dialog);

        /** Runs commands on the tab's connection; throws when it is not connected. */
        default RemoteCommandRunner commandRunner() throws IOException {
            throw new IOException(I18n.get("sftp.notConnected"));
        }

        /** Whether the JobScheduler has a sudo password saved for this server or its group. */
        default boolean storedSudoPasswordAvailable() {
            return false;
        }

        /** Whether the user opted in to using the saved sudo password for this server. */
        default boolean storedSudoPasswordOptedIn() {
            return false;
        }

        /** Records the user's opt-in (or opt-out) for this server. */
        default void setStoredSudoPasswordOptedIn(boolean optedIn) {
        }

        /**
         * The saved sudo password as a fresh array the caller wipes, or empty when there is none
         * or the vault is locked. Runs on the worker thread (decrypting takes a moment).
         */
        default Optional<char[]> storedSudoPassword() {
            return Optional.empty();
        }

        /** Adds {@code text} to the session journal of a capturing terminal on this connection. */
        default void journalNote(String text) {
        }
    }

    /** Where an edit stands. */
    enum State { WATCHING, READ_ONLY, SAVED_LOCALLY, UPLOADING, UPLOADED, CONFLICT, FAILED }

    /** One row: an edit session and its state (FX thread). */
    static final class Entry {
        final RemoteEdit session;
        final boolean uploads;
        final String name;
        final RemoteEditTelemetry.Mode mode;
        final ObjectProperty<State> state = new SimpleObjectProperty<>();
        String detail = "";

        Entry(RemoteEdit session, boolean uploads, String name, RemoteEditTelemetry.Mode mode) {
            this.session = session;
            this.uploads = uploads;
            this.name = name;
            this.mode = mode;
            this.state.set(uploads ? State.WATCHING : State.READ_ONLY);
        }
    }

    private final Host host;
    private final ObservableList<Entry> entries = FXCollections.observableArrayList();
    private final RemoteEditWatcher watcher = new RemoteEditWatcher();
    private final ExecutorService worker;
    private boolean disposed;

    public SftpRemoteEdits(Host host, String threadName) {
        super(4);
        this.host = host;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
        getStyleClass().add("sftp-remote-edits");
        setPadding(new Insets(4, 0, 0, 0));

        Label title = new Label(I18n.get("sftp.remoteEdit.title"));
        title.setStyle("-fx-font-weight: bold;");
        ListView<Entry> list = new ListView<>(entries);
        list.setCellFactory(view -> new EntryCell());
        list.setFixedCellSize(30);
        list.prefHeightProperty().bind(javafx.beans.binding.Bindings.min(4, javafx.beans.binding.Bindings.size(entries))
            .multiply(30).add(4));
        getChildren().addAll(title, list);

        setVisible(false);
        setManaged(false);
        entries.addListener((ListChangeListener<Entry>) change -> {
            boolean any = !entries.isEmpty();
            setVisible(any);
            setManaged(any);
        });
    }

    /** Whether any file is being edited. */
    public boolean hasEdits() {
        return !entries.isEmpty();
    }

    /**
     * Downloads {@code remotePath} and opens it in the external editor.
     *
     * @param uploads whether saves go back to the server (the policy may say no)
     */
    public void open(String remotePath, String displayName, boolean uploads) {
        if (disposed) {
            return;
        }
        for (Entry entry : entries) {
            if (entry.mode == RemoteEditTelemetry.Mode.EXTERNAL && entry.session.remotePath().equals(remotePath)) {
                // Already open: start the editor on the same copy again.
                startEditor(entry.session, displayName, entry);
                return;
            }
        }
        host.status(I18n.get("sftp.remoteEdit.status.opening", displayName));
        RemoteEditSession.ClientSource source = host.clientSource();
        submit(() -> {
            RemoteEditSession session;
            try {
                session = RemoteEditSession.open(source, remotePath, RemoteEditTempDirs.defaultRoot());
            } catch (IOException | RuntimeException e) {
                logger.warn("Could not open a remote file for editing: {}", e.toString());
                Platform.runLater(() -> host.status(I18n.get("sftp.remoteEdit.error.open", displayName, message(e))));
                trackEnded(RemoteEditTelemetry.Mode.EXTERNAL, RemoteEditTelemetry.Outcome.FAILED, 0);
                return;
            }
            Platform.runLater(() -> {
                if (disposed) {
                    submit(session::close);
                    return;
                }
                Entry entry = new Entry(session, uploads, displayName, RemoteEditTelemetry.Mode.EXTERNAL);
                startEditor(session, displayName, entry);
            });
        });
    }

    /**
     * Reads {@code remotePath} as root and opens it in the external editor; saves go back through
     * sudo. The caller checked the policy ({@code sftp-sudo-edit}, file transfer, and
     * {@code load-into-snippet-editor = allow}).
     */
    public void openAsRoot(String remotePath, String displayName) {
        if (disposed) {
            return;
        }
        for (Entry entry : entries) {
            if (entry.mode == RemoteEditTelemetry.Mode.SUDO && entry.session.remotePath().equals(remotePath)) {
                startEditor(entry.session, displayName, entry);
                return;
            }
        }
        SudoEditService service;
        try {
            service = new SudoEditService(host.commandRunner());
        } catch (IOException e) {
            host.status(I18n.get("sftp.remoteEdit.error.open", displayName, message(e)));
            return;
        }
        host.status(I18n.get("sftp.sudoEdit.status.opening", displayName));
        submit(() -> {
            boolean needsPassword;
            try {
                needsPassword = service.needsPassword();
            } catch (IOException | RuntimeException e) {
                failAsRoot(displayName, e);
                return;
            }
            if (!needsPassword) {
                continueAsRoot(service, remotePath, displayName, null);
                return;
            }
            if (host.storedSudoPasswordOptedIn()) {
                Optional<char[]> stored = host.storedSudoPassword();
                if (stored.isPresent()) {
                    continueAsRoot(service, remotePath, displayName, stored.get());
                    return;
                }
            }
            Platform.runLater(() -> askPasswordAsRoot(service, remotePath, displayName, false));
        });
    }

    /** Asks for the sudo password (FX thread), then goes on on the worker. */
    private void askPasswordAsRoot(SudoEditService service, String remotePath, String displayName, boolean retry) {
        if (disposed) {
            return;
        }
        boolean storedAvailable = host.storedSudoPasswordAvailable();
        Optional<SftpSudoPasswordDialog.Answer> answer = SftpSudoPasswordDialog.show(host.ownerWindow(), displayName,
            retry, storedAvailable, host.storedSudoPasswordOptedIn(), host::styleDialog);
        if (answer.isEmpty()) {
            host.status(I18n.get("sftp.sudoEdit.status.cancelled", displayName));
            return;
        }
        boolean useStored = storedAvailable && answer.get().useStored();
        if (storedAvailable) {
            host.setStoredSudoPasswordOptedIn(useStored);
        }
        char[] typed = answer.get().password();
        submit(() -> {
            if (useStored) {
                wipe(typed);
                Optional<char[]> stored = host.storedSudoPassword();
                if (stored.isEmpty()) {
                    Platform.runLater(() -> host.status(I18n.get("sftp.sudoEdit.error.storedUnavailable")));
                    return;
                }
                continueAsRoot(service, remotePath, displayName, stored.get());
            } else {
                continueAsRoot(service, remotePath, displayName, typed);
            }
        });
    }

    /**
     * Looks at the path as root (worker thread). A link asks first; a wrong password asks again.
     * {@code secret} is wiped on every way out of the flow.
     */
    private void continueAsRoot(SudoEditService service, String remotePath, String displayName, char[] secret) {
        SudoEditService.Target target;
        try {
            target = service.inspect(remotePath, Optional.ofNullable(secret));
        } catch (SudoAuthenticationException | SudoPasswordRequiredException e) {
            wipe(secret);
            Platform.runLater(() -> askPasswordAsRoot(service, remotePath, displayName, true));
            return;
        } catch (IOException | RuntimeException e) {
            wipe(secret);
            failAsRoot(displayName, e);
            return;
        }
        switch (target.kind()) {
            case FILE -> openResolvedAsRoot(service, remotePath, displayName, secret);
            case LINK -> Platform.runLater(() -> {
                if (!disposed && confirmLink(target)) {
                    submit(() -> openResolvedAsRoot(service, target.resolvedPath(), displayName, secret));
                } else {
                    wipe(secret);
                }
            });
            default -> {
                wipe(secret);
                failAsRoot(displayName, new IOException(I18n.get("sftp.remoteEdit.error.notFile", remotePath)));
            }
        }
    }

    /** Opens the regular file {@code path} as root (worker thread) and wipes {@code secret}. */
    private void openResolvedAsRoot(SudoEditService service, String path, String displayName, char[] secret) {
        RemoteEdit session;
        try {
            service.refuseUserWritableFolder(path);
            session = service.open(path, Optional.ofNullable(secret), RemoteEditTempDirs.defaultRoot());
        } catch (IOException | RuntimeException e) {
            failAsRoot(displayName, e);
            return;
        } finally {
            wipe(secret);
        }
        Platform.runLater(() -> {
            if (disposed) {
                submit(session::close);
                return;
            }
            host.journalNote(SudoEditJournal.note(path));
            Entry entry = new Entry(session, true, displayName, RemoteEditTelemetry.Mode.SUDO);
            startEditor(session, displayName, entry);
        });
    }

    /** Shows where a link points and asks whether to edit that file instead (FX thread). */
    private boolean confirmLink(SudoEditService.Target target) {
        ButtonType edit = new ButtonType(I18n.get("sftp.sudoEdit.link.edit"), ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
            I18n.get("sftp.sudoEdit.link.content", target.path(), target.resolvedPath()), edit, ButtonType.CANCEL);
        Window owner = host.ownerWindow();
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle(I18n.get("sftp.sudoEdit.link.title"));
        alert.setHeaderText(I18n.get("sftp.sudoEdit.link.header"));
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        host.styleDialog(alert);
        ((Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        ((Button) alert.getDialogPane().lookupButton(edit)).setDefaultButton(false);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == edit;
    }

    private void failAsRoot(String displayName, Throwable e) {
        logger.warn("Could not open a remote file as root: {}", e.getClass().getSimpleName());
        String text = sudoMessage(e);
        Platform.runLater(() -> host.status(I18n.get("sftp.remoteEdit.error.open", displayName, text)));
        trackEnded(RemoteEditTelemetry.Mode.SUDO, RemoteEditTelemetry.Outcome.FAILED, 0);
    }

    /** A user-facing text for a failed sudo step; never the remote output or a password. */
    static String sudoMessage(Throwable e) {
        if (e instanceof SudoRequiresTtyException) {
            return I18n.get("sftp.sudoEdit.error.requiretty");
        }
        if (e instanceof SudoAuthenticationException) {
            return I18n.get("sftp.sudoEdit.error.password");
        }
        if (e instanceof SudoPasswordRequiredException) {
            return I18n.get("sftp.sudoEdit.error.passwordRequired");
        }
        return message(e);
    }

    private static void wipe(char[] secret) {
        if (secret != null) {
            Arrays.fill(secret, '\0');
        }
    }

    /** Plans and starts the editor (FX thread for any question); adds the row once it started. */
    private void startEditor(RemoteEdit session, String displayName, Entry entry) {
        ExternalEditorLauncher.Plan plan;
        try {
            plan = ExternalEditorLauncher.plan(host.editorCommand(), session.localFile());
            if (plan instanceof ExternalEditorLauncher.NeedsCommand) {
                Optional<String> command = askEditorCommand();
                if (command.isEmpty()) {
                    abandon(entry);
                    return;
                }
                host.saveEditorCommand(command.get());
                plan = ExternalEditorLauncher.plan(command.get(), session.localFile());
            }
        } catch (IllegalArgumentException e) {
            host.status(I18n.get("sftp.remoteEdit.editorCommand.invalid"));
            abandon(entry);
            return;
        }
        ExternalEditorLauncher.Plan chosen = plan;
        submit(() -> {
            try {
                ExternalEditorLauncher.start(chosen);
            } catch (IOException | RuntimeException e) {
                logger.warn("Could not start the external editor: {}", e.toString());
                Platform.runLater(() -> {
                    host.status(I18n.get("sftp.remoteEdit.error.editor", message(e)));
                    abandon(entry);
                });
                return;
            }
            Platform.runLater(() -> track(entry, displayName));
        });
    }

    private void track(Entry entry, String displayName) {
        if (disposed) {
            submit(entry.session::close);
            return;
        }
        if (!entries.contains(entry)) {
            entries.add(entry);
            Path local = entry.session.localFile();
            watcher.track(local, entry.session.baselineSha256(), (file, hash) -> onSaved(entry));
            watcher.start();
        }
        host.status(entry.uploads
            ? I18n.get("sftp.remoteEdit.status.opened", displayName)
            : I18n.get("sftp.remoteEdit.status.openedReadOnly", displayName));
    }

    /** A row that never made it into the list: delete its copy. */
    private void abandon(Entry entry) {
        if (!entries.contains(entry)) {
            submit(entry.session::close);
            trackEnded(entry.mode, RemoteEditTelemetry.Outcome.FAILED, 0);
        }
    }

    /** The watcher saw a save (watcher thread). */
    private void onSaved(Entry entry) {
        if (!entry.uploads) {
            Platform.runLater(() -> {
                setState(entry, State.SAVED_LOCALLY, "");
                host.status(I18n.get("sftp.remoteEdit.status.savedLocally", entry.name));
            });
            return;
        }
        upload(entry, false);
    }

    /** Uploads the local copy of {@code entry}; {@code force} overwrites a changed server file. */
    private void upload(Entry entry, boolean force) {
        Platform.runLater(() -> setState(entry, State.UPLOADING, ""));
        submit(() -> {
            if (entry.session.isClosed()) {
                return;
            }
            try {
                RemoteEditSession.UploadResult result = force ? entry.session.forceUpload() : entry.session.upload();
                Platform.runLater(() -> afterUpload(entry, result));
            } catch (IOException | RuntimeException e) {
                logger.warn("Uploading an edited file failed: {}", e.getClass().getSimpleName());
                String text = sudoMessage(e);
                Platform.runLater(() -> {
                    setState(entry, State.FAILED, text);
                    host.status(I18n.get("sftp.remoteEdit.state.failed", text));
                });
            }
        });
    }

    private void afterUpload(Entry entry, RemoteEditSession.UploadResult result) {
        if (!entries.contains(entry)) {
            return;
        }
        switch (result) {
            case UPLOADED -> {
                LocalTime at = LocalTime.ofInstant(entry.session.lastUpload(), ZoneId.systemDefault());
                setState(entry, State.UPLOADED, TIME.format(at));
                host.status(I18n.get("sftp.remoteEdit.status.uploaded", entry.name));
            }
            case UNCHANGED -> setState(entry, entry.session.uploads() > 0 ? State.UPLOADED : State.WATCHING,
                entry.detail);
            case CONFLICT -> {
                setState(entry, State.CONFLICT, "");
                askAboutConflict(entry);
            }
        }
    }

    private void askAboutConflict(Entry entry) {
        RemoteEditSession.Conflict conflict = entry.session.conflict();
        ButtonType overwrite = new ButtonType(I18n.get("sftp.remoteEdit.conflict.overwrite"), ButtonBar.ButtonData.OTHER);
        ButtonType saveCopy = new ButtonType(I18n.get("sftp.remoteEdit.conflict.saveCopy"), ButtonBar.ButtonData.OTHER);
        ButtonType stop = new ButtonType(I18n.get("sftp.remoteEdit.conflict.stop"), ButtonBar.ButtonData.OTHER);
        ButtonType later = new ButtonType(I18n.get("sftp.remoteEdit.conflict.later"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.WARNING, I18n.get("sftp.remoteEdit.conflict.content"),
            overwrite, saveCopy, stop, later);
        Window owner = host.ownerWindow();
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle(I18n.get("sftp.remoteEdit.conflict.title"));
        alert.setHeaderText(conflict != null && conflict.kind() == RemoteEditSession.ConflictKind.GONE
            ? I18n.get("sftp.remoteEdit.conflict.gone", entry.session.remotePath())
            : I18n.get("sftp.remoteEdit.conflict.header", entry.session.remotePath()));
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        host.styleDialog(alert);
        // Nothing destructive is the default: Enter decides later.
        ((Button) alert.getDialogPane().lookupButton(later)).setDefaultButton(true);
        ButtonType answer = alert.showAndWait().orElse(later);
        if (answer == overwrite) {
            upload(entry, true);
        } else if (answer == saveCopy) {
            if (saveCopyOf(entry)) {
                stop(entry, RemoteEditTelemetry.Outcome.CONFLICT);
            }
        } else if (answer == stop) {
            stop(entry, RemoteEditTelemetry.Outcome.CONFLICT);
        }
    }

    /** Asks where to save a copy and copies it there (on the worker); false when cancelled. */
    private boolean saveCopyOf(Entry entry) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("sftp.remoteEdit.saveCopy.title"));
        chooser.setInitialFileName(entry.session.localFile().getFileName().toString());
        File target = chooser.showSaveDialog(host.ownerWindow());
        if (target == null) {
            return false;
        }
        submit(() -> {
            try {
                entry.session.saveLocalCopy(target.toPath());
            } catch (IOException e) {
                Platform.runLater(() -> host.status(I18n.get("sftp.remoteEdit.error.copy", message(e))));
            }
        });
        return true;
    }

    /** Uploads {@code entry} now, whether or not the watcher saw a save. */
    void uploadNow(Entry entry) {
        if (entry.uploads && entries.contains(entry)) {
            upload(entry, false);
        }
    }

    /** Stops watching {@code entry} and deletes its local copy. */
    void stop(Entry entry, RemoteEditTelemetry.Outcome outcome) {
        if (!entries.remove(entry)) {
            return;
        }
        watcher.untrack(entry.session.localFile());
        int uploads = entry.session.uploads();
        submit(entry.session::close);
        trackEnded(entry.mode, outcome, uploads);
    }

    /**
     * The connection is gone: stops every edit. Edits with changes that were not uploaded first
     * offer to keep a copy of the local file.
     */
    public void onDisconnected() {
        if (entries.isEmpty()) {
            return;
        }
        List<Entry> stopped = new ArrayList<>(entries);
        for (Entry entry : stopped) {
            watcher.untrack(entry.session.localFile());
        }
        entries.clear();
        submit(() -> {
            List<Entry> unsynced = new ArrayList<>();
            for (Entry entry : stopped) {
                if (entry.uploads && entry.session.hasUnsyncedChanges()) {
                    unsynced.add(entry);
                }
            }
            Platform.runLater(() -> {
                for (Entry entry : unsynced) {
                    offerLocalCopy(entry);
                }
                for (Entry entry : stopped) {
                    int uploads = entry.session.uploads();
                    submit(entry.session::close);
                    trackEnded(entry.mode, RemoteEditTelemetry.Outcome.DISCONNECTED, uploads);
                }
            });
        });
    }

    private void offerLocalCopy(Entry entry) {
        ButtonType keep = new ButtonType(I18n.get("sftp.remoteEdit.disconnected.keep"), ButtonBar.ButtonData.OK_DONE);
        ButtonType discard = new ButtonType(I18n.get("sftp.remoteEdit.disconnected.discard"),
            ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.WARNING, I18n.get("sftp.remoteEdit.disconnected.content"), keep, discard);
        Window owner = host.ownerWindow();
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle(I18n.get("sftp.remoteEdit.disconnected.title"));
        alert.setHeaderText(I18n.get("sftp.remoteEdit.disconnected.header", entry.name));
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        host.styleDialog(alert);
        if (alert.showAndWait().orElse(discard) == keep) {
            saveCopyOf(entry);
        }
    }

    /** The tab closes: stops every edit and deletes the local copies. Idempotent; FX thread. */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        watcher.close();
        for (Entry entry : new ArrayList<>(entries)) {
            int uploads = entry.session.uploads();
            submit(entry.session::close);
            trackEnded(entry.mode, RemoteEditTelemetry.Outcome.CLOSED, uploads);
        }
        entries.clear();
        worker.shutdown();
    }

    private Optional<String> askEditorCommand() {
        TextInputDialog dialog = new TextInputDialog(host.editorCommand());
        Window owner = host.ownerWindow();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(I18n.get("sftp.remoteEdit.editorCommand.title"));
        dialog.setHeaderText(I18n.get("sftp.remoteEdit.editorCommand.header"));
        host.styleDialog(dialog);
        while (true) {
            Optional<String> answer = dialog.showAndWait().map(String::strip);
            if (answer.isEmpty() || answer.get().isEmpty()) {
                return Optional.empty();
            }
            if (ExternalEditorLauncher.isValidTemplate(answer.get())) {
                return answer;
            }
            dialog.setHeaderText(I18n.get("sftp.remoteEdit.editorCommand.invalid"));
        }
    }

    private void setState(Entry entry, State state, String detail) {
        entry.detail = detail == null ? "" : detail;
        // Re-set to refresh the cell even when the state itself repeats (a new upload time).
        entry.state.set(null);
        entry.state.set(state);
    }

    private void submit(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException e) {
            logger.debug("The remote-edit worker is shut down; running the cleanup inline");
            task.run();
        }
    }

    private static void trackEnded(RemoteEditTelemetry.Mode mode, RemoteEditTelemetry.Outcome outcome, int uploads) {
        Telemetry.track(TelemetryEvents.SFTP_REMOTE_EDIT, RemoteEditTelemetry.props(mode, outcome, uploads));
    }

    private static String message(Throwable e) {
        String text = e.getMessage();
        return text == null || text.isBlank() ? e.getClass().getSimpleName() : text;
    }

    /** The text of a state, with its detail (an upload time or an error). */
    static String stateText(State state, String detail) {
        if (state == null) {
            return "";
        }
        return switch (state) {
            case WATCHING -> I18n.get("sftp.remoteEdit.state.watching");
            case READ_ONLY -> I18n.get("sftp.remoteEdit.state.readOnly");
            case SAVED_LOCALLY -> I18n.get("sftp.remoteEdit.state.savedLocally");
            case UPLOADING -> I18n.get("sftp.remoteEdit.state.uploading");
            case UPLOADED -> I18n.get("sftp.remoteEdit.state.uploaded", detail);
            case CONFLICT -> I18n.get("sftp.remoteEdit.state.conflict");
            case FAILED -> I18n.get("sftp.remoteEdit.state.failed", detail);
        };
    }

    private final class EntryCell extends ListCell<Entry> {
        private final Label name = new Label();
        private final Label sudoBadge = new Label(I18n.get("sftp.sudoEdit.badge"));
        private final Label state = new Label();
        private final Button uploadNow = new Button(I18n.get("sftp.remoteEdit.uploadNow"));
        private final Button stop = new Button(I18n.get("sftp.remoteEdit.stop"));
        private final HBox box;
        private Entry bound;
        private final javafx.beans.value.ChangeListener<State> stateListener = (obs, old, value) -> refresh();

        EntryCell() {
            name.setStyle("-fx-font-weight: bold;");
            state.setStyle("-fx-text-fill: gray;");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            sudoBadge.getStyleClass().add("sftp-sudo-badge");
            sudoBadge.setStyle("-fx-background-color: #b45309; -fx-text-fill: white; -fx-padding: 0 5 0 5;"
                + " -fx-background-radius: 3; -fx-font-size: 0.85em; -fx-font-weight: bold;");
            sudoBadge.setTooltip(new Tooltip(I18n.get("sftp.sudoEdit.badge.tooltip")));
            box = new HBox(10, name, sudoBadge, state, spacer, uploadNow, stop);
            box.setAlignment(Pos.CENTER_LEFT);
            uploadNow.setOnAction(e -> {
                if (bound != null) {
                    uploadNow(bound);
                }
            });
            stop.setOnAction(e -> {
                if (bound != null) {
                    stop(bound, RemoteEditTelemetry.Outcome.STOPPED);
                }
            });
        }

        @Override
        protected void updateItem(Entry item, boolean empty) {
            super.updateItem(item, empty);
            if (bound != null) {
                bound.state.removeListener(stateListener);
            }
            bound = empty ? null : item;
            if (bound == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            bound.state.addListener(stateListener);
            name.setText(bound.name);
            boolean sudo = bound.mode == RemoteEditTelemetry.Mode.SUDO;
            sudoBadge.setVisible(sudo);
            sudoBadge.setManaged(sudo);
            Tooltip.install(name, new Tooltip(bound.session.remotePath()));
            uploadNow.setDisable(!bound.uploads);
            refresh();
            setText(null);
            setGraphic(box);
        }

        private void refresh() {
            if (bound != null && bound.state.get() != null) {
                state.setText(stateText(bound.state.get(), bound.detail));
            }
        }
    }
}
