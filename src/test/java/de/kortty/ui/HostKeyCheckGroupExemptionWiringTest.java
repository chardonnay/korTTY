package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Where the Connection Manager keeps the folders without host-key verification in step with its
 * folders, which no headless test can build: renaming a folder moves the exemptions with the
 * connections, deleting it drops them, deleting, moving or regrouping a folder's last connection
 * drops those of folders that are gone, and so does every start and backup import; the result is
 * saved. The rules themselves are checked in {@code HostKeyCheckGroupExemptionsTest} and {@code
 * HostKeyCheckExemptionsAfterLoadTest}.
 */
class HostKeyCheckGroupExemptionWiringTest {

    private static final Path UI = Path.of("src/main/java/de/kortty/ui");
    private static final Path APP = Path.of("src/main/java/de/kortty/KorTTYApplication.java");

    @Test
    void renamingAFolderMovesItsExemptions() throws IOException {
        String rename = region(source("ConnectionManagerDialog.java"),
            "private void renameGroup(GroupPath oldPath) {", "\n    }\n");

        int computed = rename.indexOf("HostKeyCheckGroupExemptions.renamed(");
        int moved = rename.indexOf("conn.setGroup(");
        int stored = rename.indexOf("storeHostKeyCheckExemptions(exemptionsAfter);");
        assertThat(computed).isAtLeast(0);
        assertWithMessage("the folders that exist are read before the connections move")
            .that(moved).isGreaterThan(computed);
        assertThat(stored).isGreaterThan(moved);
        assertThat(rename).contains("oldPath.getPath(), newPath.getPath(), "
            + "connections.stream().map(ServerConnection::getGroup).toList()");
    }

    @Test
    void deletingAFolderDropsItsExemptions() throws IOException {
        String delete = region(source("ConnectionManagerDialog.java"),
            "private void deleteGroup(GroupPath groupPath) {", "\n    }\n");

        int removed = delete.indexOf("configManager.removeConnection(conn);");
        int stored = delete.indexOf("storeHostKeyCheckExemptions(hostKeyCheckExemptionsAfter(");
        assertThat(removed).isAtLeast(0);
        assertThat(stored).isGreaterThan(removed);
        assertThat(delete).contains("HostKeyCheckGroupExemptions.deleted(exempt, groupPath.getPath())");
    }

    @Test
    void theNewExemptionsAreSaved() throws IOException {
        String store = region(source("ConnectionManagerDialog.java"),
            "private void storeHostKeyCheckExemptions(List<String> exempt) {", "\n    }\n");

        int set = store.indexOf("settings.setHostKeyCheckDisabledGroups(new ArrayList<>(exempt));");
        int write = store.indexOf("gsm.save();");
        assertThat(set).isAtLeast(0);
        assertThat(write).isGreaterThan(set);
        assertWithMessage("a failed save is reported, not swallowed")
            .that(store).contains("alert.showAndWait();");
    }

    @Test
    void deletingConnectionsDropsTheExemptionsOfEmptiedFolders() throws IOException {
        String delete = region(source("ConnectionManagerDialog.java"),
            "private void deleteConnections(List<ServerConnection> connectionsToDelete) {", "\n    }\n");

        int teamworkReturn = delete.indexOf("return;\n        }\n        \n        Alert confirmLocal");
        int removed = delete.indexOf("configManager.removeConnection(conn);");
        int pruned = delete.indexOf("pruneHostKeyCheckExemptions();");
        assertWithMessage("only after the local connections were removed, never for the teamwork tab")
            .that(teamworkReturn).isAtLeast(0);
        assertThat(removed).isGreaterThan(teamworkReturn);
        assertThat(pruned).isGreaterThan(removed);
    }

    @Test
    void editingAConnectionDropsTheExemptionOfTheFolderItLeft() throws IOException {
        String edit = region(source("ConnectionManagerDialog.java"),
            "private void editConnection(ServerConnection connection) {", "\n    }\n");

        int updated = edit.indexOf("configManager.updateConnection(editedConnection);");
        int pruned = edit.indexOf("pruneHostKeyCheckExemptions();");
        assertThat(updated).isAtLeast(0);
        assertThat(pruned).isGreaterThan(updated);
    }

    @Test
    void movingAConnectionAndUndoingTheMoveDropTheExemptionsOfEmptiedFolders() throws IOException {
        String dialog = source("ConnectionManagerDialog.java");
        assertThat(dialog).contains("treeView.setOnConnectionsMoved(this::pruneHostKeyCheckExemptions);");
        assertWithMessage("the teamwork tree's folders have no exemptions")
            .that(dialog).doesNotContain("teamworkTreeView.setOnConnectionsMoved(");

        String tree = source("ConnectionManagerTreeView.java");
        String move = region(tree, "private boolean moveConnection(String connectionId, GroupPath target) {",
            "\n    }\n");
        assertThat(move.indexOf("notifyConnectionsMoved();")).isGreaterThan(move.indexOf("conn.setGroup(newGroup);"));
        String undo = region(tree, "public void undoLastMove() {", "\n    }\n");
        assertThat(undo.indexOf("notifyConnectionsMoved();"))
            .isGreaterThan(undo.indexOf("op.connection.setGroup(op.oldGroup);"));
        assertWithMessage("both drop handlers move through moveConnection")
            .that(occurrences(tree, "success = moveConnection(db.getString(), cell.getItem().getGroupPath());"))
            .isEqualTo(2);
        assertThat(occurrences(tree, ".setGroup(")).isEqualTo(2);
    }

    @Test
    void pruningInTheDialogOnlyUsesLoadedConnections() throws IOException {
        String prune = region(source("ConnectionManagerDialog.java"),
            "private void pruneHostKeyCheckExemptions() {", "\n    }\n");

        assertThat(prune).contains("HostKeyCheckGroupExemptions.prunedAgainstLoaded(exempt, configManager)");
        assertThat(prune).contains("storeHostKeyCheckExemptions(");
    }

    @Test
    void everyStartDropsTheExemptionsOfFoldersThatAreGone() throws IOException {
        String app = read(APP);
        String start = region(app, "public void start(Stage primaryStage) {", "\n    }\n");

        int connectionsLoaded = start.indexOf("configManager.load(masterPasswordManager.getDerivedKey());");
        int settingsReloaded = start.indexOf("globalSettingsManager.load();", connectionsLoaded);
        int pruned = start.indexOf("pruneHostKeyCheckExemptions();");
        assertThat(connectionsLoaded).isAtLeast(0);
        assertThat(settingsReloaded).isGreaterThan(connectionsLoaded);
        assertWithMessage("after both the connections and the settings are loaded")
            .that(pruned).isGreaterThan(settingsReloaded);

        String prune = region(app, "public void pruneHostKeyCheckExemptions() {", "\n    }\n");
        assertThat(prune).contains("HostKeyCheckGroupExemptions.prunedAgainstLoaded(stored, configManager)");
        assertThat(prune.indexOf("globalSettingsManager.save();"))
            .isGreaterThan(prune.indexOf("settings.setHostKeyCheckDisabledGroups(kept);"));
    }

    @Test
    void aBackupImportDropsTheExemptionsOfFoldersThatAreGone() throws IOException {
        String reload = region(source("MainWindow.java"),
            "private void reloadStoresAfterBackupImport() {", "\n    }\n");

        int connections = reload.indexOf("app.getConfigManager().load(");
        int settings = reload.indexOf("app.getGlobalSettingsManager().load()");
        int pruned = reload.indexOf("app.pruneHostKeyCheckExemptions();");
        assertThat(connections).isAtLeast(0);
        assertThat(settings).isGreaterThan(connections);
        assertThat(pruned).isGreaterThan(settings);
    }

    @Test
    void theFolderToggleIsOnlyOfferedWhereAHandlerStoresIt() throws IOException {
        String menu = region(source("ConnectionManagerTreeView.java"),
            "private ContextMenu createGroupContextMenu(GroupPath groupPath) {", "\n    }\n");

        int guard = menu.indexOf("if (onToggleGroupHostKeyCheck != null) {");
        int added = menu.indexOf("menu.getItems().addAll(disableHostKeyItem, new SeparatorMenuItem());");
        assertThat(guard).isAtLeast(0);
        assertThat(added).isGreaterThan(guard);
        assertThat(occurrences(menu, "disableHostKeyItem, new SeparatorMenuItem()")).isEqualTo(1);
    }

    private static String source(String file) throws IOException {
        return read(UI.resolve(file));
    }

    private static String read(Path file) throws IOException {
        // A Windows checkout has CRLF line endings; the markers above are written with \n.
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    /** The text from {@code startMarker} up to and including the next {@code endMarker}. */
    private static String region(String source, String startMarker, String endMarker) {
        int from = source.indexOf(startMarker);
        assertWithMessage("marker not found: " + startMarker).that(from).isAtLeast(0);
        int to = source.indexOf(endMarker, from + startMarker.length());
        assertWithMessage("end marker not found after " + startMarker).that(to).isAtLeast(0);
        return source.substring(from, to + endMarker.length());
    }
}
