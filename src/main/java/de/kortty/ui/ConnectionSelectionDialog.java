package de.kortty.ui;

import de.kortty.model.ServerConnection;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.List;

/**
 * Simple dialog to select a connection from a list.
 */
public class ConnectionSelectionDialog extends Dialog<ServerConnection> {
    
    private final TableView<ServerConnection> table;
    private final ObservableList<ServerConnection> connections;
    
    public ConnectionSelectionDialog(Stage owner, List<ServerConnection> connections, String title) {
        setTitle(title);
        setHeaderText("Wählen Sie eine Verbindung aus:");
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        
        this.connections = FXCollections.observableArrayList(connections);
        
        table = new TableView<>(this.connections);
        table.setPrefSize(500, 300);
        
        TableColumn<ServerConnection, String> nameColumn = new TableColumn<>("Name");
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameColumn.setPrefWidth(150);
        
        TableColumn<ServerConnection, String> hostColumn = new TableColumn<>("Host");
        hostColumn.setCellValueFactory(new PropertyValueFactory<>("host"));
        hostColumn.setPrefWidth(150);
        
        TableColumn<ServerConnection, Integer> portColumn = new TableColumn<>("Port");
        portColumn.setCellValueFactory(new PropertyValueFactory<>("port"));
        portColumn.setPrefWidth(60);
        
        TableColumn<ServerConnection, String> userColumn = new TableColumn<>("Benutzer");
        userColumn.setCellValueFactory(new PropertyValueFactory<>("username"));
        userColumn.setPrefWidth(100);
        
        table.getColumns().addAll(nameColumn, hostColumn, portColumn, userColumn);
        table.setPlaceholder(new Label("Keine Verbindungen verfügbar"));
        
        // Double-click to select
        table.setRowFactory(tv -> {
            TableRow<ServerConnection> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    setResult(row.getItem());
                    close();
                }
            });
            return row;
        });
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.getChildren().add(table);
        
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            okButton.setDisable(selected == null);
        });
        
        setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                return table.getSelectionModel().getSelectedItem();
            }
            return null;
        });
    }
}
