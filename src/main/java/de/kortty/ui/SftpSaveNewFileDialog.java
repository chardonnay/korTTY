package de.kortty.ui;

import de.kortty.ui.sftp.SftpComparePair;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Asks where a new file the AI wrote from two compared files goes: on the server or on this
 * computer, at which path, and whether an existing file there is kept as a {@code .bak} first.
 * The SFTP manager proposes the right file's place, as the file it is meant to replace; whether to
 * overwrite is asked once the path is known to exist. "Browse..." picks the path with a click: the
 * system's save dialog on this computer, {@link SftpRemoteSaveChooser} on the server.
 */
final class SftpSaveNewFileDialog extends ThemeAwareDialog<SftpSaveNewFileDialog.Choice> {

    /** Where the user chose to save. */
    record Choice(SftpComparePair.Side side, String path, boolean backup) {
    }

    /**
     * One side the file can go to: its caption ("Server demo (admin@host)"), the proposed path,
     * and why it cannot be used, or {@code null} when it can.
     */
    record Place(String caption, String proposedPath, String unavailableReason) {
        Place {
            Objects.requireNonNull(caption, "caption");
            proposedPath = Objects.requireNonNullElse(proposedPath, "");
        }

        boolean available() {
            return unavailableReason == null;
        }
    }

    static final ButtonType SAVE = new ButtonType(I18n.get("sftp.saveNewFile.save"), ButtonBar.ButtonData.OK_DONE);

    private final ToggleGroup sides = new ToggleGroup();
    private final RadioButton server;
    private final RadioButton local;
    private final TextField path = new TextField();
    private final Button browse = new Button(I18n.get("sftp.saveNewFile.browse"));
    private final CheckBox backup = new CheckBox(I18n.get("sftp.saveNewFile.backup"));
    /** The path typed for each side, so switching sides and back keeps it. */
    private final Map<SftpComparePair.Side, String> paths = new EnumMap<>(SftpComparePair.Side.class);
    private final SftpRemoteSaveChooser.Lister serverLister;
    private SftpComparePair.Side shown;

    /** @param serverLister lists server folders for browsing there; {@code null} offers typing only */
    SftpSaveNewFileDialog(Window owner, String fileSummary, Place serverPlace, Place localPlace,
                          SftpComparePair.Side proposed, SftpRemoteSaveChooser.Lister serverLister) {
        this.serverLister = serverLister;
        setTitle(I18n.get("sftp.saveNewFile.title"));
        setHeaderText(I18n.get("sftp.saveNewFile.header"));
        initModality(Modality.WINDOW_MODAL);
        if (owner != null) {
            initOwner(owner);
        }
        paths.put(SftpComparePair.Side.REMOTE, serverPlace.proposedPath());
        paths.put(SftpComparePair.Side.LOCAL, localPlace.proposedPath());

        server = new RadioButton(serverPlace.caption());
        server.setToggleGroup(sides);
        server.setUserData(SftpComparePair.Side.REMOTE);
        local = new RadioButton(localPlace.caption());
        local.setToggleGroup(sides);
        local.setUserData(SftpComparePair.Side.LOCAL);
        VBox where = new VBox(6, server);
        if (!serverPlace.available()) {
            server.setDisable(true);
            Label reason = new Label(serverPlace.unavailableReason());
            reason.setWrapText(true);
            reason.setStyle("-fx-font-size: 0.8462em; -fx-opacity: 0.75;");
            reason.setPadding(new Insets(0, 0, 0, 24));
            where.getChildren().add(reason);
        }
        where.getChildren().add(local);

        path.setPrefColumnCount(42);
        path.textProperty().addListener((obs, old, text) -> {
            if (shown != null) {
                paths.put(shown, text);
            }
            updateSaveButton();
        });
        browse.setTooltip(new Tooltip(I18n.get("sftp.saveNewFile.browseTooltip")));
        browse.setOnAction(event -> {
            if (shown == SftpComparePair.Side.REMOTE) {
                browseServer();
            } else {
                browseLocal();
            }
        });
        HBox pathRow = new HBox(8, path, browse);
        HBox.setHgrow(path, Priority.ALWAYS);

        backup.setSelected(true);
        backup.setTooltip(new Tooltip(I18n.get("sftp.saveNewFile.backupTooltip")));

        Label summary = new Label(fileSummary);
        summary.setWrapText(true);
        summary.setStyle("-fx-opacity: 0.8;");
        Label hint = new Label(I18n.get("sftp.saveNewFile.hint"));
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 0.8462em; -fx-opacity: 0.75;");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.addRow(0, new Label(I18n.get("sftp.saveNewFile.place")), where);
        grid.addRow(1, new Label(I18n.get("sftp.saveNewFile.path")), pathRow);
        grid.add(backup, 1, 2);
        GridPane.setHgrow(pathRow, Priority.ALWAYS);

        VBox root = new VBox(12, summary, grid, hint);
        root.setPadding(new Insets(10, 14, 6, 14));
        root.setPrefWidth(620);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().addAll(SAVE, ButtonType.CANCEL);

        sides.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle != null) {
                show((SftpComparePair.Side) toggle.getUserData());
            }
        });
        boolean serverFirst = proposed == SftpComparePair.Side.REMOTE && serverPlace.available();
        sides.selectToggle(serverFirst ? server : local);
        setResultConverter(button -> button == SAVE
            ? new Choice(shown, path.getText().trim(), backup.isSelected())
            : null);
        setOnShown(event -> Platform.runLater(() -> {
            path.requestFocus();
            path.end();
        }));
    }

    private void show(SftpComparePair.Side side) {
        shown = null;
        path.setText(paths.getOrDefault(side, ""));
        shown = side;
        boolean browsable = side == SftpComparePair.Side.LOCAL || serverLister != null;
        browse.setVisible(browsable);
        browse.setManaged(browsable);
        updateSaveButton();
    }

    private void updateSaveButton() {
        javafx.scene.Node save = getDialogPane().lookupButton(SAVE);
        if (save != null) {
            save.setDisable(path.getText() == null || path.getText().isBlank());
        }
    }

    private void browseLocal() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("sftp.saveNewFile.title"));
        String current = path.getText() == null ? "" : path.getText().trim();
        if (!current.isEmpty()) {
            Path proposed = Path.of(current);
            Path folder = proposed.getParent();
            if (folder != null && folder.toFile().isDirectory()) {
                chooser.setInitialDirectory(folder.toFile());
            }
            if (proposed.getFileName() != null) {
                chooser.setInitialFileName(proposed.getFileName().toString());
            }
        }
        // The overwrite question comes afterwards from the SFTP manager, with the backup option.
        File chosen = chooser.showSaveDialog(getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
        if (chosen != null) {
            path.setText(chosen.getAbsolutePath());
        }
    }

    private void browseServer() {
        SftpRemoteSaveChooser chooser = new SftpRemoteSaveChooser(
            getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
            path.getText(), serverLister);
        chooser.showAndWait().ifPresent(path::setText);
    }

    // ---- for SftpManagerScreenshotGenerator ------------------------------------------------------

    Button browseButton() {
        return browse;
    }

    TextField path() {
        return path;
    }

    RadioButton serverChoice() {
        return server;
    }

    RadioButton localChoice() {
        return local;
    }

    CheckBox backup() {
        return backup;
    }
}
