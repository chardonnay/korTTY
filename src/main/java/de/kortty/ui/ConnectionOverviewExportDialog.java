package de.kortty.ui;

import de.kortty.core.ConnectionOverviewExport;
import de.kortty.core.ConnectionOverviewExport.Format;
import de.kortty.core.ConnectionOverviewExport.Part;
import de.kortty.model.ServerConnection;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exports an overview of the servers as JSON, YAML, TOML or XML: which connections (the selected
 * ones, those the Connection Manager shows, or all), the format, and which parts of each entry,
 * with a live preview of the first entry. The overview is an inventory for documentation and
 * scripts, not an import file; passwords and keys never appear in it ({@link ConnectionOverviewExport}).
 */
final class ConnectionOverviewExportDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionOverviewExportDialog.class);

    private final ConnectionOverviewExport.Context context;
    private final Map<Part, CheckBox> partBoxes = new EnumMap<>(Part.class);
    private final ToggleGroup formats = new ToggleGroup();
    private final ToggleGroup scopes = new ToggleGroup();
    private final TextArea preview = new TextArea();

    /**
     * @param selected the connections selected in the manager
     * @param shown    the connections the manager shows right now
     * @param all      every local and teamwork connection
     */
    ConnectionOverviewExportDialog(Window owner, List<ServerConnection> selected, List<ServerConnection> shown,
                                   List<ServerConnection> all, ConnectionOverviewExport.Context context) {
        this.context = context;
        if (owner != null) {
            initOwner(owner);
        }
        setTitle(I18n.get("connectionManager.overview.title"));
        setHeaderText(I18n.get("connectionManager.overview.header"));

        VBox scopeBox = new VBox(6,
            scope("connectionManager.overview.scope.selected", selected),
            scope("connectionManager.overview.scope.shown", shown),
            scope("connectionManager.overview.scope.all", all));
        scopes.getToggles().stream().filter(toggle -> !((RadioButton) toggle).isDisabled())
            .findFirst().ifPresent(toggle -> toggle.setSelected(true));

        HBox formatBox = new HBox();
        for (Format format : Format.values()) {
            ToggleButton button = new ToggleButton(format.name());
            button.setUserData(format);
            button.setToggleGroup(formats);
            button.getStyleClass().add(format.ordinal() == 0 ? "left-pill"
                : format.ordinal() == Format.values().length - 1 ? "right-pill" : "center-pill");
            formatBox.getChildren().add(button);
        }
        formats.getToggles().get(0).setSelected(true);
        // A format stays chosen: clicking the chosen one again does not leave none.
        formats.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null && old != null) {
                old.setSelected(true);
            }
        });

        CheckBox basics = new CheckBox(I18n.get("connectionManager.overview.part.BASICS"));
        basics.setSelected(true);
        basics.setDisable(true);
        FlowPane parts = new FlowPane(16, 8, basics);
        for (Part part : Part.values()) {
            if (part == Part.BASICS) {
                continue;
            }
            CheckBox box = new CheckBox(I18n.get("connectionManager.overview.part." + part.name()));
            box.setSelected(part != Part.USAGE && part != Part.TERMINAL);
            box.selectedProperty().addListener((obs, was, is) -> updatePreview());
            partBoxes.put(part, box);
            parts.getChildren().add(box);
        }
        parts.setPrefWrapLength(520);
        Button allParts = new Button(I18n.get("connectionManager.overview.parts.all"));
        allParts.setOnAction(e -> partBoxes.values().forEach(box -> box.setSelected(true)));
        Button noParts = new Button(I18n.get("connectionManager.overview.parts.none"));
        noParts.setOnAction(e -> partBoxes.values().forEach(box -> box.setSelected(false)));
        Label secrets = new Label(I18n.get("connectionManager.overview.noSecrets"));
        secrets.setWrapText(true);
        secrets.setStyle("-fx-opacity: 0.7;");

        preview.setEditable(false);
        preview.setPrefRowCount(12);
        preview.setPrefColumnCount(64);
        preview.setStyle("-fx-font-family: 'Monospaced';");
        preview.setFocusTraversable(false);

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(12);
        grid.add(caption("connectionManager.overview.connections"), 0, 0);
        grid.add(scopeBox, 1, 0);
        grid.add(caption("connectionManager.overview.format"), 0, 1);
        grid.add(formatBox, 1, 1);
        grid.add(caption("connectionManager.overview.parts"), 0, 2);
        HBox partButtons = new HBox(8, allParts, noParts);
        grid.add(new VBox(8, parts, partButtons, secrets), 1, 2);
        GridPane.setHgrow(parts, Priority.ALWAYS);
        Label previewLabel = caption("connectionManager.overview.preview");
        VBox content = new VBox(12, grid, previewLabel, preview);
        VBox.setVgrow(preview, Priority.ALWAYS);
        content.setPadding(new Insets(16));
        getDialogPane().setContent(content);
        getDialogPane().setPrefWidth(760);

        ButtonType export = new ButtonType(I18n.get("connectionManager.overview.export"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(export, ButtonType.CANCEL);
        Button exportButton = (Button) getDialogPane().lookupButton(export);
        exportButton.disableProperty().bind(scopes.selectedToggleProperty().isNull());
        exportButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (!exportToFile()) {
                event.consume(); // stay open after Cancel in the file chooser or a failed write
            }
        });
        formats.selectedToggleProperty().addListener((obs, old, now) -> updatePreview());
        scopes.selectedToggleProperty().addListener((obs, old, now) -> updatePreview());
        updatePreview();
    }

    private RadioButton scope(String key, List<ServerConnection> connections) {
        RadioButton button = new RadioButton(I18n.get(key, connections.size()));
        button.setUserData(connections);
        button.setToggleGroup(scopes);
        button.setDisable(connections.isEmpty());
        return button;
    }

    private static Label caption(String key) {
        Label caption = new Label(I18n.get(key));
        caption.setStyle("-fx-font-weight: bold;");
        caption.setMinWidth(Label.USE_PREF_SIZE);
        GridPane.setValignment(caption, javafx.geometry.VPos.TOP);
        return caption;
    }

    @SuppressWarnings("unchecked")
    private List<ServerConnection> chosenConnections() {
        return scopes.getSelectedToggle() != null ? (List<ServerConnection>) scopes.getSelectedToggle().getUserData() : List.of();
    }

    private Format chosenFormat() {
        return formats.getSelectedToggle() != null ? (Format) formats.getSelectedToggle().getUserData() : Format.JSON;
    }

    private Set<Part> chosenParts() {
        Set<Part> parts = EnumSet.of(Part.BASICS);
        partBoxes.forEach((part, box) -> {
            if (box.isSelected()) {
                parts.add(part);
            }
        });
        return parts;
    }

    /** The chosen format and parts for the first chosen connection, as the file will have them. */
    private void updatePreview() {
        List<ServerConnection> chosen = chosenConnections();
        preview.setText(ConnectionOverviewExport.write(chosen.isEmpty() ? List.of() : chosen.subList(0, 1),
            chosenParts(), chosenFormat(), context));
        preview.positionCaret(0);
    }

    /** Asks where to save and writes the overview; false when nothing was written. */
    private boolean exportToFile() {
        Format format = chosenFormat();
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("connectionManager.overview.title"));
        chooser.setInitialFileName("korTTY-servers." + format.extension());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(format.name(), "*." + format.extension()));
        File file = chooser.showSaveDialog(getDialogPane().getScene().getWindow());
        if (file == null) {
            return false;
        }
        try {
            List<ServerConnection> chosen = chosenConnections();
            Files.writeString(file.toPath(), ConnectionOverviewExport.write(chosen, chosenParts(), format, context),
                StandardCharsets.UTF_8);
            Alert done = new Alert(Alert.AlertType.INFORMATION,
                I18n.get("connectionManager.overview.done", chosen.size(), file.getAbsolutePath()), ButtonType.OK);
            DialogThemeHelper.applyTheme(done);
            done.initOwner(getDialogPane().getScene().getWindow());
            done.setHeaderText(null);
            done.showAndWait();
            return true;
        } catch (Exception e) {
            logger.warn("Could not write the connection overview to {}", file, e);
            Alert failed = new Alert(Alert.AlertType.ERROR,
                I18n.get("connectionManager.overview.failed", e.getMessage()), ButtonType.OK);
            DialogThemeHelper.applyTheme(failed);
            failed.initOwner(getDialogPane().getScene().getWindow());
            failed.setHeaderText(null);
            failed.showAndWait();
            return false;
        }
    }
}
