package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.model.ServerConnection;
import de.kortty.teamwork.TeamworkRecycleBinService;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Dialog to restore a previously deleted teamwork connection from the recycle bin.
 */
public class RestoreTeamworkDialog extends Dialog<ServerConnection> {

    private static final Logger logger = LoggerFactory.getLogger(RestoreTeamworkDialog.class);

    private final Stage owner;
    private final KorTTYApplication app;
    private final ListView<ServerConnection> listView;
    private final ObservableList<ServerConnection> deletedList;

    public RestoreTeamworkDialog(Stage owner, KorTTYApplication app) {
        this.owner = owner;
        this.app = app;
        TeamworkRecycleBinService recycleBin = app.getTeamworkRecycleBinService();
        deletedList = FXCollections.observableArrayList(recycleBin != null ? recycleBin.getDeleted() : List.of());

        setTitle(I18n.get("connectionManager.teamwork.restore"));
        setHeaderText(I18n.get("teamwork.restore.header"));
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);

        listView = new ListView<>(deletedList);
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ServerConnection item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getDisplayName() + " (" + item.getHost() + ")");
            }
        });
        listView.setPrefSize(400, 300);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        if (deletedList.isEmpty()) {
            content.getChildren().add(new Label(I18n.get("teamwork.restore.empty")));
        } else {
            content.getChildren().add(new Label(I18n.get("teamwork.restore.hint")));
            content.getChildren().add(listView);
        }

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.disableProperty().bind(Bindings.isNull(listView.getSelectionModel().selectedItemProperty()));
        okButton.setText(I18n.get("teamwork.restore.restoreButton"));

        setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                ServerConnection selected = listView.getSelectionModel().getSelectedItem();
                if (selected != null && app.getTeamworkRecycleBinService() != null) {
                    try {
                        app.getTeamworkRecycleBinService().restore(selected.getId());
                        return selected;
                    } catch (Exception ex) {
                        logger.error("Failed to restore teamwork connection: {}", selected.getId(), ex);
                        Alert errorAlert = new Alert(Alert.AlertType.ERROR);
                        errorAlert.setTitle(I18n.get("error.title"));
                        errorAlert.setHeaderText(null);
                        errorAlert.setContentText(I18n.get("teamwork.restore.failed", ex.getMessage()));
                        errorAlert.showAndWait();
                        return null;
                    }
                }
            }
            return null;
        });
    }
}
