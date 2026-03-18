package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.model.SavedAiChat;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Dialog for managing saved AI chats.
 */
public class AiManagerDialog extends ThemeAwareDialog<Void> {

    private static final DateTimeFormatter UPDATED_AT_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final MainWindow ownerWindow;
    private final ObservableList<SavedAiChat> chats;
    private final TableView<SavedAiChat> table;

    public AiManagerDialog(MainWindow ownerWindow) {
        this.ownerWindow = ownerWindow;
        setTitle(I18n.get("ai.manager.title"));
        setHeaderText(I18n.get("ai.manager.header"));
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        chats = FXCollections.observableArrayList(loadChats());
        table = new TableView<>(chats);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        TableColumn<SavedAiChat, String> titleColumn = new TableColumn<>(I18n.get("ai.manager.column.title"));
        titleColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            cell.getValue().getTitle() != null && !cell.getValue().getTitle().isBlank()
                ? cell.getValue().getTitle()
                : I18n.get("ai.saved.defaultTitle")));
        titleColumn.setMinWidth(220);

        TableColumn<SavedAiChat, String> profileColumn = new TableColumn<>(I18n.get("ai.manager.column.profile"));
        profileColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            cell.getValue().getActiveAiProfileName() != null ? cell.getValue().getActiveAiProfileName() : ""));
        profileColumn.setMinWidth(150);

        TableColumn<SavedAiChat, String> updatedColumn = new TableColumn<>(I18n.get("ai.manager.column.updated"));
        updatedColumn.setCellValueFactory(cell -> new SimpleStringProperty(formatUpdatedAt(cell.getValue().getUpdatedAt())));
        updatedColumn.setMinWidth(160);

        TableColumn<SavedAiChat, String> connectionColumn = new TableColumn<>(I18n.get("ai.manager.column.connection"));
        connectionColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            cell.getValue().getConnectionDisplayName() != null ? cell.getValue().getConnectionDisplayName() : ""));
        connectionColumn.setMinWidth(160);

        table.getColumns().addAll(titleColumn, profileColumn, updatedColumn, connectionColumn);
        table.setPlaceholder(new Label(I18n.get("ai.manager.empty")));
        table.setRowFactory(view -> {
            TableRow<SavedAiChat> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    ownerWindow.openSavedAiChat(new SavedAiChat(row.getItem()));
                }
            });
            return row;
        });

        Button openButton = new Button(I18n.get("ai.manager.open"));
        openButton.setOnAction(event -> openSelectedChat());
        Button renameButton = new Button(I18n.get("ai.manager.rename"));
        renameButton.setOnAction(event -> renameSelectedChat());
        Button deleteButton = new Button(I18n.get("ai.manager.delete"));
        deleteButton.setOnAction(event -> deleteSelectedChat());
        Button refreshButton = new Button(I18n.get("ai.manager.refresh"));
        refreshButton.setOnAction(event -> refreshChats());

        openButton.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        renameButton.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        deleteButton.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());

        HBox buttonBar = new HBox(8, openButton, renameButton, deleteButton, refreshButton);
        VBox content = new VBox(10, table, buttonBar);
        VBox.setVgrow(table, Priority.ALWAYS);
        content.setPadding(new Insets(8, 0, 0, 0));
        getDialogPane().setContent(content);
        getDialogPane().setPrefSize(860, 500);
    }

    private List<SavedAiChat> loadChats() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        return app != null && app.getAiChatManager() != null
            ? app.getAiChatManager().getAllChats()
            : List.of();
    }

    private void refreshChats() {
        chats.setAll(loadChats());
    }

    private void openSelectedChat() {
        SavedAiChat selectedChat = table.getSelectionModel().getSelectedItem();
        if (selectedChat == null) {
            return;
        }
        ownerWindow.openSavedAiChat(new SavedAiChat(selectedChat));
    }

    private void renameSelectedChat() {
        SavedAiChat selectedChat = table.getSelectionModel().getSelectedItem();
        if (selectedChat == null) {
            return;
        }

        SaveAiChatDialog dialog = new SaveAiChatDialog(
            ownerWindow.getStage(),
            I18n.get("ai.result.rename.title"),
            I18n.get("ai.result.rename.header"),
            selectedChat.getTitle(),
            selectedChat.getTitle(),
            null);
        dialog.showAndWait().ifPresent(newTitle -> {
            if (ownerWindow.renameSavedAiChat(selectedChat, newTitle)) {
                refreshChats();
            }
        });
    }

    private void deleteSelectedChat() {
        SavedAiChat selectedChat = table.getSelectionModel().getSelectedItem();
        if (selectedChat == null) {
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(confirm);
        confirm.initOwner(ownerWindow.getStage());
        confirm.setTitle(I18n.get("ai.manager.delete.title"));
        confirm.setHeaderText(I18n.get("ai.manager.delete.header"));
        confirm.setContentText(I18n.get("ai.manager.delete.content",
            selectedChat.getTitle() != null ? selectedChat.getTitle() : I18n.get("ai.saved.defaultTitle")));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        if (ownerWindow.deleteSavedAiChat(selectedChat)) {
            refreshChats();
        }
    }

    private String formatUpdatedAt(long epochMillis) {
        if (epochMillis <= 0L) {
            return "";
        }
        return Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(UPDATED_AT_FORMAT);
    }
}
