package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Where folder (group) tab colors are hooked in, which no headless test can build: the folder menu of
 * the Connection Manager's own tree opens the color dialog, the stored colors reach every open tab in
 * every window, renaming and deleting a folder move or drop its colors together with its connections,
 * dragging a connection into another folder recolors its tabs, and the window resolves a tab's color
 * with the group colors between the connection's own and the credential environment's. The rules
 * themselves are checked in {@code ConnectionGroupColorsTest} and {@code ConnectionColorSupportTest}.
 */
class ConnectionGroupColorWiringTest {

    private static final Path UI = Path.of("src/main/java/de/kortty/ui");

    @Test
    void onlyTheLocalTreeOffersFolderColors() throws IOException {
        String manager = source("ConnectionManagerDialog.java");

        assertThat(manager).contains("treeView.setOnEditGroupColor(this::editGroupColor);");
        assertThat(manager).contains("treeView.setGroupColorProbe(this::groupColorOf);");
        assertWithMessage("teamwork connections never take a folder color, so their tree has no color entry")
            .that(manager).doesNotContain("teamworkTreeView.setOnEditGroupColor(");
        assertThat(manager).doesNotContain("teamworkTreeView.setGroupColorProbe(");
    }

    @Test
    void theFolderMenuHasTheColorEntryOnlyWithAHandler() throws IOException {
        String menu = region(source("ConnectionManagerTreeView.java"),
            "private ContextMenu createGroupContextMenu(GroupPath groupPath) {", "\n        return menu;\n");

        assertThat(menu).contains("if (onEditGroupColor != null) {");
        assertThat(menu).contains("new MenuItem(I18n.get(\"connManager.group.tabColor\"))");
        assertThat(menu).contains("onEditGroupColor.accept(groupPath)");
    }

    @Test
    void aFolderWithAColorShowsANamedDotAfterItsName() throws IOException {
        String tree = source("ConnectionManagerTreeView.java");

        assertThat(region(tree, "private void setCellFactory() {", "showGroupColor(this, item.getGroupPath());"))
            .contains("if (item.isGroup()) {");
        String show = region(tree, "private void showGroupColor(", "\n    }\n");
        assertThat(show).contains("TabColorPresentation.swatch(color, text)");
        assertThat(show).contains("I18n.get(\"connManager.group.tabColor.swatch\"");
        assertWithMessage("the color is never the only cue").that(show).contains("cell.setTooltip(new Tooltip(text));");
        assertThat(show).contains("cell.setAccessibleText(cell.getText() + \", \" + text);");
        assertWithMessage("a reused cell must not keep another folder's color text")
            .that(region(tree, "private void setCellFactory() {", "if (empty || item == null) {"))
            .contains("setAccessibleText(null);");
        assertThat(show).contains("ConnectionColorSupport.normalizeHex(");
    }

    @Test
    void aNewColorIsSavedThenShownInEveryWindow() throws IOException {
        String manager = source("ConnectionManagerDialog.java");
        String edit = region(manager, "private void editGroupColor(GroupPath groupPath) {", "\n    }\n");

        assertThat(edit).contains("new ConnectionGroupColorDialog(groupPath, colors)");
        int store = edit.indexOf("if (storeGroupColors(colors)) {");
        int refresh = edit.indexOf("MainWindow.refreshConnectionColorsInAllWindows();");
        assertThat(store).isAtLeast(0);
        assertWithMessage("open tabs are recolored after the colors were saved").that(refresh).isGreaterThan(store);
        assertThat(edit).contains("treeView.refreshPreservingFilter();");

        String save = region(manager, "private boolean storeGroupColors(Map<String, String> colors) {", "\n    }\n");
        int set = save.indexOf("settings.setConnectionGroupColors(colors);");
        int write = save.indexOf("gsm.save();");
        int restore = save.indexOf("settings.setConnectionGroupColors(previous);");
        assertThat(set).isAtLeast(0);
        assertThat(write).isGreaterThan(set);
        assertWithMessage("a failed save puts the previous colors back").that(restore).isGreaterThan(write);
    }

    @Test
    void renamingAndDeletingAFolderMoveOrDropItsColorsBeforeTheConnectionsAreSaved() throws IOException {
        String manager = source("ConnectionManagerDialog.java");

        String rename = region(manager, "private void renameGroup(GroupPath oldPath) {", "\n    }\n");
        int moved = rename.indexOf("ConnectionGroupColors.renamed(");
        assertThat(moved).isAtLeast(0);
        assertThat(rename).contains("oldPath.getPath(), newPath.getPath()");
        assertWithMessage("saving the connections recolors the open tabs, so the colors must be in place")
            .that(rename.indexOf("saveConnections();")).isGreaterThan(moved);

        String delete = region(manager, "private void deleteGroup(GroupPath groupPath) {", "\n    }\n");
        int dropped = delete.indexOf("ConnectionGroupColors.deleted(");
        assertThat(dropped).isAtLeast(0);
        assertThat(delete.indexOf("saveConnections();")).isGreaterThan(dropped);
        assertWithMessage("only a confirmed delete drops the colors")
            .that(dropped).isGreaterThan(delete.indexOf("if (result == ButtonType.OK) {"));
    }

    @Test
    void draggingAConnectionIntoAnotherFolderRecolorsItsTabs() throws IOException {
        String manager = source("ConnectionManagerDialog.java");
        String tree = source("ConnectionManagerTreeView.java");

        assertThat(manager).contains("treeView.setOnConnectionsMoved(MainWindow::refreshConnectionColorsInAllWindows);");
        String cells = region(tree, "private void setCellFactory() {", "\n    /**");
        assertThat(cells).contains("moveHistory.push(new MoveOperation(conn, oldGroup, newGroup));");
        assertThat(cells.indexOf("onConnectionsMoved.run();"))
            .isGreaterThan(cells.indexOf("moveHistory.push(new MoveOperation(conn, oldGroup, newGroup));"));
        assertThat(region(tree, "public void undoLastMove() {", "\n    }\n")).contains("onConnectionsMoved.run();");
    }

    @Test
    void theWindowResolvesGroupColorsBetweenTheConnectionAndTheEnvironment() throws IOException {
        String window = source("MainWindow.java");

        String resolve = region(window,
            "private ConnectionColorSupport.TabColor effectiveTabColor(ServerConnection connection) {", "\n    }\n");
        assertThat(resolve).contains("this::groupColor, this::credentialEnvironmentId, this::environmentColor");
        assertThat(region(window, "private String groupColor(String groupPath) {", "\n    }\n"))
            .contains("settings.getConnectionGroupColor(groupPath)");
        String source = region(window, "private String colorSourceName(ConnectionColorSupport.TabColor color) {", "\n    }\n");
        assertThat(source).contains("case GROUP -> TabColorPresentation.groupLabel(color.groupPath());");
        assertThat(source).contains("case CONNECTION -> null;");
    }

    @Test
    void theSettingsFileKnowsTheGroupColorEntries() throws IOException {
        String manager = Files.readString(Path.of("src/main/java/de/kortty/core/GlobalSettingsManager.java"),
            StandardCharsets.UTF_8);

        assertThat(manager).contains("de.kortty.model.ConnectionGroupColor.class,");
    }

    @Test
    void theDialogOffersThePresetsAndNamesTheColorFromAbove() throws IOException {
        String dialog = source("ConnectionGroupColorDialog.java");

        assertThat(dialog).contains("for (String preset : ConnectionColorSupport.PRESETS) {");
        assertThat(dialog).contains("ConnectionGroupColors.inheritedFromAbove(groupPath.getPath(), colors::get)");
        assertThat(dialog).contains("I18n.get(\"connManager.group.tabColor.inherited\"");
        assertThat(dialog).contains("picker.disableProperty().bind(enable.selectedProperty().not());");
        assertWithMessage("Cancel changes nothing").that(dialog).contains(": null);");
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
