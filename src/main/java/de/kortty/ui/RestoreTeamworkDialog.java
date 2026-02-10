package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.model.ServerConnection;
import de.kortty.teamwork.TeamworkRecycleBinService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.List;
import java.util.Optional;

/**
 * Dialog to restore a previously deleted teamwork connection from the recycle bin.
 */
public class RestoreTeamworkDialog extends Dialog<ServerConnection> {

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
        okButton.setDisable(deletedList.isEmpty());
        okButton.setText(I18n.get("teamwork.restore.restoreButton"));

        listView.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            okButton.setDisable(sel == null);
        });

        setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                ServerConnection selected = listView.getSelectionModel().getSelectedItem();
                if (selected != null && app.getTeamworkRecycleBinService() != null) {
                    app.getTeamworkRecycleBinService().restore(selected.getId());
                    return selected;
                }
            }
            return null;
        });
    }
}
