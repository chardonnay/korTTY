package de.kortty.ui;

import de.kortty.core.RemotePathSupport;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Picks where a file goes on the server, like a save dialog of the operating system does for this
 * computer: the folders and files of a server folder, a double-click opens a folder, a click on a
 * file takes its name, and the name can be typed. Returns the full path. The listing runs off the
 * FX thread through the SFTP manager's session ({@link Lister}).
 */
final class SftpRemoteSaveChooser extends ThemeAwareDialog<String> {

    /** One entry of a server folder. */
    record Entry(String name, boolean directory) {
        Entry {
            Objects.requireNonNull(name, "name");
        }

        boolean parent() {
            return "..".equals(name);
        }
    }

    /** A listed server folder: its absolute path as the server resolved it, and what is in it. */
    record Folder(String path, List<Entry> entries) {
    }

    /**
     * Lists a server folder; called off the FX thread. {@code folder} may be relative or empty
     * (the folder the server panel shows).
     */
    interface Lister {
        Folder list(String folder) throws IOException;
    }

    static final ButtonType CHOOSE = new ButtonType(I18n.get("sftp.saveNewFile.remote.choose"), ButtonBar.ButtonData.OK_DONE);

    /** Folders first, then files, each by name as the lists sort them. */
    static final Comparator<Entry> ORDER = Comparator.comparing((Entry entry) -> !entry.parent())
        .thenComparing(entry -> !entry.directory())
        .thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT))
        .thenComparing(Entry::name);

    private final Lister lister;
    private final ObservableList<Entry> entries = FXCollections.observableArrayList();
    private final ListView<Entry> list = new ListView<>(entries);
    private final TextField folderField = new TextField();
    private final TextField nameField = new TextField();
    private final Label status = new Label();
    private final Button upButton = new Button(I18n.get("dialog.remoteDirectory.button.up"));
    private String folder;
    private long request;
    private boolean loading;

    /**
     * @param proposedPath the full path proposed so far; its folder is shown and its name filled in
     */
    SftpRemoteSaveChooser(Window owner, String proposedPath, Lister lister) {
        this.lister = Objects.requireNonNull(lister, "lister");
        setTitle(I18n.get("sftp.saveNewFile.remote.title"));
        setHeaderText(I18n.get("sftp.saveNewFile.remote.header"));
        initModality(Modality.WINDOW_MODAL);
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }
        String proposed = proposedPath == null ? "" : proposedPath.trim();
        String startFolder = proposed.contains("/") ? RemotePathSupport.parentRemotePath(proposed) : "";
        nameField.setText(proposed.contains("/") ? proposed.substring(proposed.lastIndexOf('/') + 1) : proposed);

        upButton.setOnAction(event -> {
            if (folder != null && !"/".equals(folder)) {
                open(RemotePathSupport.parentRemotePath(folder));
            }
        });
        folderField.setOnAction(event -> open(folderField.getText()));
        folderField.setTooltip(new Tooltip(I18n.get("sftp.saveNewFile.remote.folderTooltip")));
        HBox folderRow = new HBox(8, upButton, folderField);
        folderRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(folderField, Priority.ALWAYS);

        list.setCellFactory(view -> new EntryCell());
        list.setPrefHeight(320);
        list.getStyleClass().add("sftp-remote-save-list");
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, entry) -> {
            if (entry != null && !entry.directory()) {
                nameField.setText(entry.name());
            }
        });
        list.setOnMouseClicked(event -> {
            Entry entry = list.getSelectionModel().getSelectedItem();
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && entry != null) {
                activate(entry);
            }
        });
        list.setOnKeyPressed(event -> {
            Entry entry = list.getSelectionModel().getSelectedItem();
            if (event.getCode() == KeyCode.ENTER && entry != null && entry.directory()) {
                event.consume();
                activate(entry);
            }
        });

        nameField.textProperty().addListener((obs, old, text) -> updateChooseButton());
        GridPane nameRow = new GridPane();
        nameRow.setHgap(10);
        nameRow.addRow(0, new Label(I18n.get("sftp.saveNewFile.remote.name")), nameField);
        GridPane.setHgrow(nameField, Priority.ALWAYS);

        status.setWrapText(true);
        status.setStyle("-fx-font-size: 0.8462em; -fx-opacity: 0.8;");

        VBox root = new VBox(10, folderRow, list, nameRow, status);
        root.setPadding(new Insets(10, 14, 6, 14));
        root.setPrefWidth(620);
        VBox.setVgrow(list, Priority.ALWAYS);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().addAll(CHOOSE, ButtonType.CANCEL);
        setResultConverter(button -> button == CHOOSE ? chosenPath() : null);
        updateChooseButton();
        setOnShown(event -> {
            open(startFolder);
            Platform.runLater(() -> {
                nameField.requestFocus();
                nameField.end();
            });
        });
    }

    /** The folder shown and the name typed, as one path; null while either is missing. */
    String chosenPath() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (folder == null || name.isEmpty()) {
            return null;
        }
        return RemotePathSupport.appendRemotePath(folder, name);
    }

    private void activate(Entry entry) {
        if (entry.parent()) {
            open(RemotePathSupport.parentRemotePath(folder));
        } else if (entry.directory()) {
            open(RemotePathSupport.appendRemotePath(folder, entry.name()));
        } else {
            nameField.setText(entry.name());
            javafx.scene.Node choose = getDialogPane().lookupButton(CHOOSE);
            if (choose instanceof Button button && !button.isDisabled()) {
                button.fire();
            }
        }
    }

    /** Lists {@code path} off the FX thread; on failure the folder shown so far stays. */
    private void open(String path) {
        String target = path == null ? "" : path.trim();
        long current = ++request;
        loading = true;
        updateChooseButton();
        status.setText(I18n.get("dialog.remoteDirectory.status.loading"));
        CompletableFuture.supplyAsync(() -> {
            try {
                return lister.list(target);
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((listing, error) -> Platform.runLater(() -> {
            if (current != request) {
                return;
            }
            loading = false;
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                status.setText(I18n.get("sftp.saveNewFile.remote.failed", target,
                    cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()));
                folderField.setText(folder == null ? "" : folder);
                updateChooseButton();
                return;
            }
            show(listing);
        }));
    }

    private void show(Folder listing) {
        folder = listing.path();
        folderField.setText(folder);
        upButton.setDisable("/".equals(folder));
        List<Entry> shown = new java.util.ArrayList<>();
        if (!"/".equals(folder)) {
            shown.add(new Entry("..", true));
        }
        for (Entry entry : listing.entries()) {
            if (!".".equals(entry.name()) && !"..".equals(entry.name())) {
                shown.add(entry);
            }
        }
        shown.sort(ORDER);
        entries.setAll(shown);
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        entries.stream().filter(entry -> !entry.directory() && entry.name().equals(name)).findFirst()
            .ifPresent(entry -> {
                list.getSelectionModel().select(entry);
                list.scrollTo(entry);
            });
        status.setText(shown.size() <= ("/".equals(folder) ? 0 : 1)
            ? I18n.get("sftp.saveNewFile.remote.empty")
            : "");
        updateChooseButton();
    }

    private void updateChooseButton() {
        javafx.scene.Node choose = getDialogPane().lookupButton(CHOOSE);
        if (choose != null) {
            choose.setDisable(loading || chosenPath() == null);
        }
    }

    /** A folder or file with the file lists' icon and row height; dot entries dimmed as there. */
    private static final class EntryCell extends ListCell<Entry> {
        EntryCell() {
            // As the SFTP lists: an AtlantaFX theme alone makes a row 3em tall.
            setStyle("-fx-cell-size: 2em; -fx-padding: 0 0.6em;");
        }

        @Override
        protected void updateItem(Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            FileBrowserIcons.IconKind kind = FileBrowserIcons.kindFor(entry.name(), entry.directory(), false, false);
            setGraphic(FileBrowserIcons.treeIcon(kind, SFTPManagerTab.fileBrowserIconColor(),
                entry.name().startsWith(".") && !entry.parent(), false, "#21252b"));
            setText(entry.name());
            setGraphicTextGap(8);
        }
    }

    // ---- for SftpManagerScreenshotGenerator ------------------------------------------------------

    ListView<Entry> list() {
        return list;
    }

    TextField nameField() {
        return nameField;
    }

    String folder() {
        return folder;
    }
}
