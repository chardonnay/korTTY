package de.kortty.ui;

import de.kortty.model.GroupPath;
import de.kortty.model.ServerConnection;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Selection-only wrapper around the Connection Manager tree for JobScheduler targets.
 */
public class JobSchedulerTargetSelectionDialog extends ThemeAwareDialog<JobSchedulerTargetSelectionDialog.Selection> {

    private final ConnectionManagerTreeView treeView;
    private final Set<String> initialConnectionIds;
    private final Set<String> initialGroupNames;

    public JobSchedulerTargetSelectionDialog(
        Window owner,
        List<ServerConnection> connections,
        Collection<String> selectedConnectionIds,
        Collection<String> selectedGroupNames) {

        this.initialConnectionIds = normalize(selectedConnectionIds);
        this.initialGroupNames = normalize(selectedGroupNames);

        setTitle("JobScheduler targets");
        setHeaderText("Select servers or server groups from the Connection Manager.");
        if (owner != null) {
            initOwner(owner);
        }
        initModality(Modality.WINDOW_MODAL);
        setResizable(true);

        treeView = new ConnectionManagerTreeView(FXCollections.observableArrayList(connections));
        treeView.setSelectionOnly(true);
        treeView.setPrefSize(640, 460);

        TextField searchField = new TextField();
        searchField.setPromptText(I18n.get("connManager.searchPrompt"));
        searchField.textProperty().addListener((obs, oldValue, newValue) -> filterTree(newValue));

        HBox searchBox = new HBox(10, new Label(I18n.get("ssh.search")), searchField);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        VBox content = new VBox(10, searchBox, treeView);
        content.setPadding(new Insets(10));
        VBox.setVgrow(treeView, Priority.ALWAYS);
        getDialogPane().setContent(content);
        getDialogPane().setPrefSize(720, 560);

        ButtonType selectButtonType = new ButtonType("Select", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(selectButtonType, ButtonType.CANCEL);
        Button selectButton = (Button) getDialogPane().lookupButton(selectButtonType);
        selectButton.setDisable(true);

        treeView.getSelectionModel().getSelectedItems().addListener(
            (javafx.collections.ListChangeListener<TreeItem<ConnectionTreeItem.ItemData>>) change ->
                selectButton.setDisable(readSelection().isEmpty()));

        treeView.setOnDoubleClick(() -> {
            Selection selection = readSelection();
            if (!selection.isEmpty()) {
                setResult(selection);
                close();
            }
        });

        setOnShown(event -> {
            restoreSelection();
            selectButton.setDisable(readSelection().isEmpty());
        });
        setResultConverter(buttonType -> buttonType == selectButtonType ? readSelection() : null);
    }

    private void filterTree(String value) {
        String searchText = value != null ? value.trim().toLowerCase() : "";
        if (searchText.isEmpty()) {
            treeView.filterTree(null);
            restoreSelection();
            return;
        }
        treeView.filterTree(connection ->
            contains(connection.getName(), searchText)
                || contains(connection.getHost(), searchText)
                || contains(connection.getGroup(), searchText));
    }

    private void restoreSelection() {
        treeView.getSelectionModel().clearSelection();
        selectMatchingItems(treeView.getRoot());
    }

    private void selectMatchingItems(TreeItem<ConnectionTreeItem.ItemData> item) {
        if (item == null) {
            return;
        }
        ConnectionTreeItem.ItemData data = item.getValue();
        if (data != null && data.isGroup()) {
            GroupPath groupPath = data.getGroupPath();
            if (groupPath != null && initialGroupNames.contains(groupPath.getPath())) {
                treeView.getSelectionModel().select(item);
            }
        } else if (data != null && data.getConnection() != null) {
            String connectionId = data.getConnection().getId();
            if (connectionId != null && initialConnectionIds.contains(connectionId)) {
                treeView.getSelectionModel().select(item);
            }
        }
        for (TreeItem<ConnectionTreeItem.ItemData> child : item.getChildren()) {
            selectMatchingItems(child);
        }
    }

    private Selection readSelection() {
        List<String> connectionIds = treeView.getSelectedConnections().stream()
            .map(ServerConnection::getId)
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .toList();
        List<String> groupNames = treeView.getSelectedGroups().stream()
            .map(GroupPath::getPath)
            .filter(group -> group != null && !group.isBlank())
            .distinct()
            .toList();
        return new Selection(connectionIds, groupNames);
    }

    private boolean contains(String value, String searchText) {
        return value != null && value.toLowerCase().contains(searchText);
    }

    private Set<String> normalize(Collection<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return normalized;
    }

    public record Selection(List<String> connectionIds, List<String> groupNames) {
        public Selection {
            connectionIds = connectionIds != null ? List.copyOf(connectionIds) : List.of();
            groupNames = groupNames != null ? List.copyOf(groupNames) : List.of();
        }

        public boolean isEmpty() {
            return connectionIds.isEmpty() && groupNames.isEmpty();
        }
    }
}
