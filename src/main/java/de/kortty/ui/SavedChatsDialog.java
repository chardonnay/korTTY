package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.model.SavedAiChat;
import de.kortty.model.SavedSwarmChat;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Standalone window for managing saved AI chats and saved swarm chats.
 *
 * <p>These two lists used to live as tabs inside {@link AiManagerDialog}. They now have their own
 * window (opened via the "AI" menu) so the AI manager stays focused on profiles, models and
 * knowledge stores. This dialog does not launch new swarm sessions — that stays on the separate
 * "AI Swarm" menu entry; here we only browse, open, rename and delete already-saved conversations.
 */
public class SavedChatsDialog extends ThemeAwareDialog<Void> {

    private static final DateTimeFormatter UPDATED_AT_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final MainWindow ownerWindow;
    private final KorTTYApplication app;
    private final ObservableList<SavedAiChat> chats;
    private final ObservableList<SavedSwarmChat> swarmChats;
    private final TableView<SavedAiChat> chatTable;
    private final TableView<SavedSwarmChat> swarmChatTable;

    public SavedChatsDialog(MainWindow ownerWindow) {
        this.ownerWindow = ownerWindow;
        this.app = KorTTYApplication.getInstance();
        initModality(Modality.NONE);
        setTitle(I18n.get("ai.chats.title"));
        setResizable(true);
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        chats = FXCollections.observableArrayList();
        swarmChats = FXCollections.observableArrayList();
        chatTable = buildChatTable();
        swarmChatTable = buildSwarmChatTable();

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getTabs().addAll(buildSavedChatsTab(), buildSwarmChatsTab());

        getDialogPane().setContent(tabPane);
        getDialogPane().setPrefSize(860, 560);
        getDialogPane().setMinSize(620, 420);
        restoreGeometry();
        setOnCloseRequest(event -> saveGeometry());
        setOnHidden(event -> saveGeometry());

        refresh();
    }

    private TableView<SavedAiChat> buildChatTable() {
        TableView<SavedAiChat> table = new TableView<>(chats);
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
        connectionColumn.setMinWidth(180);

        table.getColumns().addAll(List.of(titleColumn, profileColumn, updatedColumn, connectionColumn));
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
        return table;
    }

    private Tab buildSavedChatsTab() {
        Button openButton = new Button(I18n.get("ai.manager.open"));
        openButton.setOnAction(event -> openSelectedChat());
        ButtonIcons.apply(openButton, ButtonIcons.OPEN);
        Button renameButton = new Button(I18n.get("ai.manager.rename"));
        renameButton.setOnAction(event -> renameSelectedChat());
        ButtonIcons.apply(renameButton, ButtonIcons.RENAME);
        Button deleteButton = new Button(I18n.get("ai.manager.delete"));
        deleteButton.setOnAction(event -> deleteSelectedChat());
        ButtonIcons.apply(deleteButton, ButtonIcons.DELETE);
        Button refreshButton = new Button(I18n.get("ai.manager.refresh"));
        refreshButton.setOnAction(event -> refreshChats());
        ButtonIcons.apply(refreshButton, ButtonIcons.REFRESH);

        openButton.disableProperty().bind(chatTable.getSelectionModel().selectedItemProperty().isNull());
        renameButton.disableProperty().bind(chatTable.getSelectionModel().selectedItemProperty().isNull());
        deleteButton.disableProperty().bind(chatTable.getSelectionModel().selectedItemProperty().isNull());

        HBox buttonBar = new HBox(8, openButton, renameButton, deleteButton, refreshButton);
        VBox root = new VBox(10, chatTable, buttonBar);
        root.setPadding(new Insets(6));
        VBox.setVgrow(chatTable, Priority.ALWAYS);

        Tab tab = new Tab(I18n.get("ai.chats.tab.chats"));
        tab.setContent(root);
        return tab;
    }

    private TableView<SavedSwarmChat> buildSwarmChatTable() {
        TableView<SavedSwarmChat> table = new TableView<>(swarmChats);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        TableColumn<SavedSwarmChat, String> titleColumn = new TableColumn<>(I18n.get("ai.swarm.manager.title"));
        titleColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            cell.getValue().getTitle() != null && !cell.getValue().getTitle().isBlank()
                ? cell.getValue().getTitle()
                : I18n.get("ai.saved.defaultTitle")));
        titleColumn.setMinWidth(240);

        TableColumn<SavedSwarmChat, String> profileColumn = new TableColumn<>(I18n.get("ai.manager.column.profile"));
        profileColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            cell.getValue().getActiveAiProfileName() != null ? cell.getValue().getActiveAiProfileName() : ""));
        profileColumn.setMinWidth(150);

        TableColumn<SavedSwarmChat, String> targetsColumn = new TableColumn<>(I18n.get("ai.swarm.manager.targets"));
        targetsColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            String.valueOf(cell.getValue().getTargetConnectionIds() != null
                ? cell.getValue().getTargetConnectionIds().size() : 0)));
        targetsColumn.setMinWidth(100);

        TableColumn<SavedSwarmChat, String> updatedColumn = new TableColumn<>(I18n.get("ai.swarm.manager.updated"));
        updatedColumn.setCellValueFactory(cell -> new SimpleStringProperty(formatUpdatedAt(cell.getValue().getUpdatedAt())));
        updatedColumn.setMinWidth(160);

        table.getColumns().addAll(List.of(titleColumn, profileColumn, targetsColumn, updatedColumn));
        table.setPlaceholder(new Label(I18n.get("ai.swarm.manager.empty")));
        table.setRowFactory(view -> {
            TableRow<SavedSwarmChat> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    ownerWindow.openSavedSwarmChat(new SavedSwarmChat(row.getItem()));
                }
            });
            return row;
        });
        return table;
    }

    private Tab buildSwarmChatsTab() {
        Button openButton = new Button(I18n.get("ai.manager.open"));
        openButton.setOnAction(event -> openSelectedSwarmChat());
        ButtonIcons.apply(openButton, ButtonIcons.OPEN);
        Button deleteButton = new Button(I18n.get("ai.manager.delete"));
        deleteButton.setOnAction(event -> deleteSelectedSwarmChat());
        ButtonIcons.apply(deleteButton, ButtonIcons.DELETE);
        Button refreshButton = new Button(I18n.get("ai.manager.refresh"));
        refreshButton.setOnAction(event -> refreshSwarmChats());
        ButtonIcons.apply(refreshButton, ButtonIcons.REFRESH);

        openButton.disableProperty().bind(swarmChatTable.getSelectionModel().selectedItemProperty().isNull());
        deleteButton.disableProperty().bind(swarmChatTable.getSelectionModel().selectedItemProperty().isNull());

        HBox buttonBar = new HBox(8, openButton, deleteButton, refreshButton);
        VBox root = new VBox(10, swarmChatTable, buttonBar);
        root.setPadding(new Insets(6));
        VBox.setVgrow(swarmChatTable, Priority.ALWAYS);

        Tab tab = new Tab(I18n.get("ai.swarm.manager.section"));
        tab.setContent(root);
        return tab;
    }

    private void refresh() {
        refreshChats();
        refreshSwarmChats();
    }

    private void refreshChats() {
        chats.setAll(loadChats());
    }

    private List<SavedAiChat> loadChats() {
        return app != null && app.getAiChatManager() != null
            ? app.getAiChatManager().getAllChats()
            : List.of();
    }

    private void refreshSwarmChats() {
        swarmChats.setAll(loadSwarmChats());
    }

    private List<SavedSwarmChat> loadSwarmChats() {
        return app != null && app.getSwarmChatManager() != null
            ? app.getSwarmChatManager().getAllChats()
            : List.of();
    }

    private void openSelectedChat() {
        SavedAiChat selectedChat = chatTable.getSelectionModel().getSelectedItem();
        if (selectedChat == null) {
            return;
        }
        ownerWindow.openSavedAiChat(new SavedAiChat(selectedChat));
    }

    private void renameSelectedChat() {
        SavedAiChat selectedChat = chatTable.getSelectionModel().getSelectedItem();
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
        SavedAiChat selectedChat = chatTable.getSelectionModel().getSelectedItem();
        if (selectedChat == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(confirm);
        confirm.initOwner(ownerWindow.getStage());
        confirm.setTitle(I18n.get("ai.manager.delete.title"));
        confirm.setHeaderText(I18n.get("ai.manager.delete.header"));
        confirm.setContentText(I18n.get(
            "ai.manager.delete.content",
            selectedChat.getTitle() != null ? selectedChat.getTitle() : I18n.get("ai.saved.defaultTitle")));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        if (ownerWindow.deleteSavedAiChat(selectedChat)) {
            refreshChats();
        }
    }

    private void openSelectedSwarmChat() {
        SavedSwarmChat selected = swarmChatTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        ownerWindow.openSavedSwarmChat(new SavedSwarmChat(selected));
    }

    private void deleteSelectedSwarmChat() {
        SavedSwarmChat selected = swarmChatTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(confirm);
        confirm.initOwner(ownerWindow.getStage());
        confirm.setTitle(I18n.get("ai.manager.delete.title"));
        confirm.setHeaderText(I18n.get("ai.manager.delete.header"));
        confirm.setContentText(I18n.get("ai.manager.delete.content",
            selected.getTitle() != null ? selected.getTitle() : I18n.get("ai.saved.defaultTitle")));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        if (ownerWindow.deleteSavedSwarmChat(selected)) {
            refreshSwarmChats();
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

    /** Restore the user's last size/position for this dialog (mirrors other korTTY dialogs). */
    private void restoreGeometry() {
        DialogGeometrySupport.restore(this, settings -> settings.getSavedChatsDialogGeometry());
    }

    /** Persist the current size/position so it is restored next time the dialog opens. */
    private void saveGeometry() {
        if (isHostedInTab()) {
            return; // the pane's window is the main window's stage, not this dialog's geometry
        }
        DialogGeometrySupport.persist(this, (settings, geometry) -> settings.setSavedChatsDialogGeometry(geometry));
    }
}
