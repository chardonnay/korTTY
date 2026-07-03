package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.model.GroupPath;
import de.kortty.model.ServerConnection;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets the user pick swarm targets from the connection-manager tree (selection only). Selected groups
 * are expanded to all member connections. Returns the resolved connection list plus the chosen group
 * paths (kept for persistence/resume) and whether local shells should be included.
 */
public final class SwarmTargetPickerDialog extends Dialog<SwarmTargetPickerDialog.Selection> {

    public record Selection(
        List<ServerConnection> connections,
        List<GroupPath> groups,
        boolean includeLocalShell) {
    }

    public SwarmTargetPickerDialog(boolean includeLocalDefault) {
        setTitle(I18n.get("ai.swarm.target.picker.title"));
        setResizable(true);

        List<ServerConnection> allConnections = KorTTYApplication.getInstance().getConfigManager().getConnections();
        ConnectionManagerTreeView tree = new ConnectionManagerTreeView(allConnections);
        tree.setSelectionOnly(true);
        tree.setPrefSize(440, 460);

        CheckBox includeLocal = new CheckBox(I18n.get("ai.swarm.target.includeLocal"));
        includeLocal.setSelected(includeLocalDefault);

        VBox box = new VBox(10, tree, includeLocal);
        VBox.setVgrow(tree, javafx.scene.layout.Priority.ALWAYS);
        getDialogPane().setContent(box);
        getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().setPrefSize(480, 540);

        setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            List<GroupPath> selectedGroups = tree.getSelectedGroups();
            Map<String, ServerConnection> byId = new LinkedHashMap<>();
            for (ServerConnection connection : tree.getSelectedConnections()) {
                if (connection != null && connection.getId() != null) {
                    byId.put(connection.getId(), connection);
                }
            }
            for (GroupPath group : selectedGroups) {
                for (ServerConnection connection : allConnections) {
                    if (connection == null || connection.isPlaceholder() || connection.getId() == null) {
                        continue;
                    }
                    GroupPath connectionGroup = new GroupPath(connection.getGroup() != null ? connection.getGroup() : "");
                    if (connectionGroup.equals(group) || connectionGroup.isChildOf(group)) {
                        byId.put(connection.getId(), connection);
                    }
                }
            }
            return new Selection(new ArrayList<>(byId.values()), selectedGroups, includeLocal.isSelected());
        });
    }
}
