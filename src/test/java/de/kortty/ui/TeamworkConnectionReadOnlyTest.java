package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.ConnectionSource;
import de.kortty.model.ServerConnection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Teamwork connections are read-only in the Connection Manager, as korTTY never writes back to a shared
 * source. The editor writes the form into the object it is given, so it must never get a teamwork
 * connection: editing one used to change the live shared copy and then throw on {@code connections.set(-1, …)}
 * because only the local list was searched. The lookup is checked directly; the wiring that keeps Edit,
 * dragging and the folder and empty-space menus off the Teamwork tab is checked in the source, as no
 * headless test can build the dialog.
 */
class TeamworkConnectionReadOnlyTest {

    private static final Path UI = Path.of("src/main/java/de/kortty/ui");

    @Test
    void aLocalConnectionIsEditedAtItsOwnPosition() {
        ServerConnection web = new ServerConnection("web", "web.example.test", 22, "deploy");
        ServerConnection db = new ServerConnection("db", "db.example.test", 22, "deploy");
        List<ServerConnection> local = new ArrayList<>(List.of(web, db));

        assertThat(ConnectionManagerDialog.localEditIndex(local, web)).isEqualTo(0);
        assertThat(ConnectionManagerDialog.localEditIndex(local, db)).isEqualTo(1);
    }

    @Test
    void aTeamworkConnectionCannotBeEdited() {
        List<ServerConnection> local = new ArrayList<>(List.of(new ServerConnection("web", "web.example.test", 22, "deploy")));

        assertThat(ConnectionManagerDialog.localEditIndex(local, teamwork("shared", "shared.example.test")))
            .isEqualTo(-1);
    }

    @Test
    void aTeamworkConnectionWithTheIdOfALocalOneDoesNotReplaceIt() {
        ServerConnection web = new ServerConnection("web", "web.example.test", 22, "deploy");
        ServerConnection shared = teamwork("web (team)", "other.example.test");
        shared.setId(web.getId());
        List<ServerConnection> local = new ArrayList<>(List.of(web));

        assertWithMessage("connections match by id, so indexOf would hand back the local connection")
            .that(local.indexOf(shared)).isEqualTo(0);
        assertThat(ConnectionManagerDialog.localEditIndex(local, shared)).isEqualTo(-1);
    }

    @Test
    void nothingAndAFolderPlaceholderCannotBeEdited() {
        ServerConnection placeholder = new ServerConnection("(Ordner: prod)", "placeholder", 22, "");
        List<ServerConnection> local = new ArrayList<>(List.of(placeholder));

        assertThat(placeholder.isPlaceholder()).isTrue();
        assertThat(ConnectionManagerDialog.localEditIndex(local, placeholder)).isEqualTo(-1);
        assertThat(ConnectionManagerDialog.localEditIndex(local, null)).isEqualTo(-1);
    }

    @Test
    void theTeamworkTreeIsReadOnlyAndHasNoEditHandler() throws IOException {
        String manager = source("ConnectionManagerDialog.java");

        assertThat(manager).contains("teamworkTreeView.setReadOnlyConnections(true);");
        assertWithMessage("the Teamwork tab's context menu must not open the editor")
            .that(manager).doesNotContain("teamworkTreeView.setOnEditConnection(");
        assertThat(manager).contains("treeView.setOnEditConnection(this::editConnection);");
    }

    @Test
    void theEditButtonIsOffOnTheTeamworkTab() throws IOException {
        String update = region(source("ConnectionManagerDialog.java"), "private void updateButtonState() {", "\n    }\n");

        assertThat(update).contains("boolean local = isLocalTabActive();");
        assertThat(update).contains("editButton.setDisable(!local || !hasSingleConnection);");
    }

    @Test
    void theEditorOnlyOpensForAConnectionInTheLocalList() throws IOException {
        String edit = region(source("ConnectionManagerDialog.java"),
            "private void editConnection(ServerConnection connection) {", "\n    }\n");

        int guard = edit.indexOf("if (localEditIndex(connections, selected) < 0) {");
        int open = edit.indexOf("new ConnectionEditDialog(");
        assertThat(guard).isAtLeast(0);
        assertWithMessage("the editor writes into the connection, so the check comes before it opens")
            .that(open).isGreaterThan(guard);
        assertWithMessage("indexOf matches by id and would find a local connection for a teamwork one")
            .that(edit).doesNotContain("connections.indexOf(");
        int lookup = edit.indexOf("int index = localEditIndex(connections, selected);");
        int set = edit.indexOf("connections.set(index, editedConnection);");
        assertThat(lookup).isGreaterThan(open);
        assertThat(edit.indexOf("if (index < 0) {", lookup)).isIn(com.google.common.collect.Range.open(lookup, set));
    }

    @Test
    void aReadOnlyTreeOffersNoEditEntryAndNoDragging() throws IOException {
        String tree = source("ConnectionManagerTreeView.java");

        String menu = region(tree, "private ContextMenu createConnectionContextMenu() {", "\n        return menu;\n");
        assertThat(menu).contains("if (onEditConnection != null && !readOnlyConnections) {\n"
            + "            menu.getItems().add(editItem);");
        assertThat(menu).doesNotContain("addAll(editItem");

        String cells = region(tree, "private void setCellFactory() {", "\n    /**");
        for (String handler : List.of("cell.setOnDragDetected(event -> {", "cell.setOnDragOver(event -> {",
                "cell.setOnDragDropped(event -> {")) {
            assertWithMessage("a teamwork connection dragged into another folder would change its shared group")
                .that(region(cells, handler, "\n            });\n"))
                .contains("if (selectionOnly || readOnlyConnections) {\n                    return;");
        }
    }

    @Test
    void theTeamworkTreeRegistersNoFolderOrNewConnectionHandler() throws IOException {
        String manager = source("ConnectionManagerDialog.java");

        for (String setter : List.of("setOnCreateGroup(", "setOnRenameGroup(", "setOnDeleteGroup(", "setOnExportGroup(",
                "setOnAddConnection(", "setOnToggleGroupHostKeyCheck(", "setGroupHostKeyCheckDisabledProbe(",
                "setOnAssignTagToGroup(", "setOnRemoveTagFromGroup(", "setOnEditGroupColor(")) {
            assertWithMessage("the local tree keeps its folder and empty-space menus")
                .that(manager).contains("        treeView." + setter);
            assertWithMessage("a teamwork folder handler would act on the local list or relax host-key checks")
                .that(manager).doesNotContain("teamworkTreeView." + setter);
        }
    }

    @Test
    void aReadOnlyTreeHasNoMenuForEmptySpace() throws IOException {
        String tree = source("ConnectionManagerTreeView.java");

        String menu = region(tree, "private ContextMenu createEmptyAreaContextMenu() {", "\n    }\n");
        assertThat(menu).contains("if (onCreateGroup != null && !readOnlyConnections) {\n"
            + "            menu.getItems().add(createFolderItem);");
        assertThat(menu).contains("if (onAddConnection != null && !readOnlyConnections) {\n"
            + "            menu.getItems().add(createConnectionItem);");
        assertWithMessage("an empty menu must give way to none").that(menu)
            .contains("return menu.getItems().isEmpty() ? null : menu;");
        assertThat(menu).doesNotContain("addAll(");

        assertWithMessage("the menu is only ever set through updateEmptyAreaContextMenu()")
            .that(occurrences(tree, "createEmptyAreaContextMenu()")).isEqualTo(2);
        assertThat(region(tree, "private void updateEmptyAreaContextMenu() {", "\n    }\n"))
            .contains("setContextMenu(selectionOnly ? null : createEmptyAreaContextMenu());");
        // It is built in the constructor, before any handler or the read-only flag is set.
        for (String setter : List.of("public ConnectionManagerTreeView(List<ServerConnection> connections) {",
                "public void setSelectionOnly(boolean selectionOnly) {",
                "public void setReadOnlyConnections(boolean readOnlyConnections) {",
                "public void setOnCreateGroup(Consumer<GroupPath> callback) {",
                "public void setOnAddConnection(Runnable callback) {")) {
            assertWithMessage(setter).that(region(tree, setter, "\n    }\n")).contains("updateEmptyAreaContextMenu();");
        }
    }

    @Test
    void aReadOnlyTreeHasNoFolderMenu() throws IOException {
        String tree = source("ConnectionManagerTreeView.java");

        String menu = region(tree, "private ContextMenu createGroupContextMenu(GroupPath groupPath) {",
            "\n        return menu.getItems().isEmpty() ? null : menu;\n");
        assertThat(menu).contains("boolean changeable = !readOnlyConnections;");
        for (String entry : List.of(
                "if (changeable && onRenameGroup != null) {\n            structure.add(renameItem);",
                "if (changeable && onCreateGroup != null) {\n            structure.add(createSubGroupItem);",
                "if (changeable && onToggleGroupHostKeyCheck != null) {\n            settings.add(disableHostKeyItem);",
                "if (changeable && onEditGroupColor != null) {",
                "if (changeable && (onAssignTagToGroup != null || onRemoveTagFromGroup != null)) {\n"
                    + "            tags.addAll(List.of(assignTagItem, removeTagItem));",
                "if (onExportGroup != null) {\n            folder.add(exportGroupItem);",
                "if (onDeleteGroup != null) {\n            folder.add(deleteGroupItem);")) {
            assertThat(menu).contains(entry);
        }
        assertWithMessage("every entry is added once, under its own condition")
            .that(occurrences(menu, "menu.getItems().add")).isEqualTo(2);
        assertThat(menu).contains("menu.getItems().add(new SeparatorMenuItem());");
        assertThat(menu).contains("menu.getItems().addAll(section);");
        assertWithMessage("a cell without a menu leaves the right-click to the tree, which has none either")
            .that(region(tree, "private void setCellFactory() {", "\n    /**"))
            .contains("setContextMenu(createGroupContextMenu(item.getGroupPath()));");
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static ServerConnection teamwork(String name, String host) {
        ServerConnection connection = new ServerConnection(name, host, 22, "deploy");
        connection.setConnectionSource(ConnectionSource.TEAMWORK);
        return connection;
    }

    private static String source(String file) throws IOException {
        // A Windows checkout has CRLF line endings; the markers above are written with \n.
        return Files.readString(UI.resolve(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
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
