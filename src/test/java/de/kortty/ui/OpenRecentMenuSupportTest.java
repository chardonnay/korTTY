package de.kortty.ui;

import de.kortty.core.RecentProjects;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import de.kortty.ui.actions.ActionIds;
import de.kortty.ui.actions.AppAction;
import de.kortty.ui.actions.MenuActionHarvester;
import de.kortty.ui.actions.MenuItemActivation;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * <i>File → Open Recent</i>: the connections used last under a greyed-out heading, then the recent
 * projects, then Clear List; each entry runs its command, labels are shown as they are and without
 * control characters, and an empty list says so. Menu items need no JavaFX toolkit, so plain items
 * stand in for the separators. How MainWindow fills and wires the menu is pinned against its source.
 */
class OpenRecentMenuSupportTest {

    private static final Path HOME = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    private static final Path MAIN_WINDOW = Path.of("src/main/java/de/kortty/ui/MainWindow.java");

    // ---- the items ----------------------------------------------------------------------------

    @Test
    void connectionsComeFirstThenProjectsThenClearList() {
        Recorder commands = new Recorder();
        ServerConnection prod = connection("prod", "db1.example", "admin");
        Path ops = HOME.resolve("ops.kortty");

        List<MenuItem> items = OpenRecentMenuSupport.items(List.of(prod), List.of(ops), HOME, commands,
            OpenRecentMenuSupportTest::separator);

        assertThat(texts(items)).containsExactly(
            I18n.get(OpenRecentMenuSupport.CONNECTIONS_KEY),
            "prod — admin@db1.example",
            "---",
            I18n.get(OpenRecentMenuSupport.PROJECTS_KEY),
            RecentProjects.label(ops, HOME),
            "---",
            I18n.get(OpenRecentMenuSupport.CLEAR_KEY)).inOrder();
        assertWithMessage("the headings are greyed out").that(items.get(0).isDisable()).isTrue();
        assertThat(items.get(3).isDisable()).isTrue();
        assertThat(items.get(1).isDisable()).isFalse();
        assertThat(items.get(4).isDisable()).isFalse();
    }

    @Test
    void eachEntryRunsItsCommand() {
        Recorder commands = new Recorder();
        ServerConnection prod = connection("prod", "db1.example", "admin");
        Path ops = HOME.resolve("ops.kortty");
        List<MenuItem> items = OpenRecentMenuSupport.items(List.of(prod), List.of(ops), HOME, commands,
            OpenRecentMenuSupportTest::separator);

        MenuItemActivation.activate(items.get(1));
        MenuItemActivation.activate(items.get(4));
        MenuItemActivation.activate(items.get(6));

        assertThat(commands.log).containsExactly("connect prod", "open " + ops, "clear").inOrder();
    }

    @Test
    void aGroupWithoutEntriesIsLeftOutAndAnEmptyListSaysSo() {
        Recorder commands = new Recorder();

        List<MenuItem> onlyProjects = OpenRecentMenuSupport.items(List.of(), List.of(HOME.resolve("a.kortty")), HOME,
            commands, OpenRecentMenuSupportTest::separator);
        List<MenuItem> onlyConnections = OpenRecentMenuSupport.items(List.of(connection("c", "h", "u")), List.of(),
            HOME, commands, OpenRecentMenuSupportTest::separator);
        List<MenuItem> empty = OpenRecentMenuSupport.items(List.of(), List.of(), HOME, commands,
            OpenRecentMenuSupportTest::separator);

        assertThat(texts(onlyProjects).get(0)).isEqualTo(I18n.get(OpenRecentMenuSupport.PROJECTS_KEY));
        assertThat(texts(onlyProjects)).doesNotContain(I18n.get(OpenRecentMenuSupport.CONNECTIONS_KEY));
        assertThat(texts(onlyConnections)).doesNotContain(I18n.get(OpenRecentMenuSupport.PROJECTS_KEY));
        assertThat(texts(onlyConnections).get(texts(onlyConnections).size() - 1))
            .isEqualTo(I18n.get(OpenRecentMenuSupport.CLEAR_KEY));
        assertWithMessage("nothing to clear: one greyed-out line, no Clear List")
            .that(texts(empty)).containsExactly(I18n.get(OpenRecentMenuSupport.EMPTY_KEY));
        assertThat(empty.get(0).isDisable()).isTrue();
        MenuItemActivation.activate(empty.get(0));
        assertThat(commands.log).isEmpty();
    }

    @Test
    void namesAndPathsAreShownAsTheyAreWithoutControlCharacters() {
        ServerConnection tricky = connection("my_db‮gnp.exe\u001B[31m", "host", "root");
        Path project = HOME.resolve("my_project.kortty");

        List<MenuItem> items = OpenRecentMenuSupport.items(List.of(tricky), List.of(project), HOME, new Recorder(),
            OpenRecentMenuSupportTest::separator);

        MenuItem connectionItem = items.get(1);
        assertThat(connectionItem.getText()).isEqualTo("my_dbgnp.exe[31m — root@host");
        assertWithMessage("an underscore is no mnemonic").that(connectionItem.isMnemonicParsing()).isFalse();
        assertThat(items.get(4).getText()).startsWith("my_project — ");
        assertThat(items.get(4).isMnemonicParsing()).isFalse();
    }

    @Test
    void aConnectionLabelNamesTheTargetOnceAndALocalShellOnlyItsName() {
        assertThat(OpenRecentMenuSupport.connectionLabel(connection("prod", "db1", "admin")))
            .isEqualTo("prod — admin@db1");
        assertThat(OpenRecentMenuSupport.connectionLabel(connection(null, "db1", "admin"))).isEqualTo("admin@db1");
        assertThat(OpenRecentMenuSupport.connectionLabel(connection("prod", "db1", ""))).isEqualTo("prod — db1");
        ServerConnection shell = connection("zsh", null, null);
        shell.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        assertThat(OpenRecentMenuSupport.connectionLabel(shell)).isEqualTo("zsh");
    }

    @Test
    void clearListNeedsNoWindowAndTheEntriesActInTheFrontmostOne() {
        List<MenuItem> items = OpenRecentMenuSupport.items(List.of(connection("c", "h", "u")),
            List.of(HOME.resolve("a.kortty")), HOME, new Recorder(), OpenRecentMenuSupportTest::separator);

        assertThat(ClosedWindowMenuRouter.needOf(items.get(items.size() - 1)))
            .isEqualTo(ClosedWindowMenuRouter.WindowNeed.NO_WINDOW);
        assertThat(ClosedWindowMenuRouter.needOf(items.get(1))).isEqualTo(ClosedWindowMenuRouter.WindowNeed.ANY_WINDOW);
        assertThat(ClosedWindowMenuRouter.needOf(items.get(4))).isEqualTo(ClosedWindowMenuRouter.WindowNeed.ANY_WINDOW);
    }

    @Test
    void theCommandPaletteHarvestSkipsTheFilledSubmenu() {
        Menu file = new Menu("File");
        MenuItem openProject = ActionIds.tag(new MenuItem(I18n.get("menu.file.openProject")), "menu.file.openProject");
        Menu openRecent = ActionIds.exclude(new Menu(I18n.get(OpenRecentMenuSupport.MENU_KEY)));
        openRecent.getItems().setAll(OpenRecentMenuSupport.items(List.of(connection("prod", "db1", "admin")),
            List.of(HOME.resolve("a.kortty")), HOME, new Recorder(), OpenRecentMenuSupportTest::separator));
        file.getItems().addAll(openProject, openRecent);

        List<AppAction> actions = MenuActionHarvester.harvest(List.of(file));

        assertWithMessage("entries rebuilt on every opening would go stale in the palette")
            .that(actions.stream().map(AppAction::id).toList()).containsExactly("menu.file.openProject");
    }

    // ---- the wiring in MainWindow -------------------------------------------------------------

    @Test
    void theFileMenuOffersOpenRecentAfterOpenProjectAndRebuildsItWhenItOpens() throws IOException {
        String file = methodBody(source(), "private Menu createFileMenu() {");

        assertThat(file).contains("Menu openRecent = ActionIds.exclude(new Menu(I18n.get(\"menu.file.openRecent\")));");
        assertThat(file).contains("openProject, openRecent, saveProject,");
        String showing = file.substring(file.indexOf("fileMenu.setOnShowing("));
        assertThat(showing).contains("syncOpenRecentMenus();");
        assertThat(showing).contains("refreshRecentProjects();");
        assertWithMessage("a new window lists what the others used")
            .that(methodBody(source(), "private void setupMenuBar() {")).contains("syncOpenRecentMenus();");
    }

    @Test
    void aRecentConnectionSignsInLikeConnectAndCountsAsAUse() throws IOException {
        String connect = methodBody(source(), "private void openRecentConnection(ServerConnection connection) {");

        assertThat(connect).contains("app.getConfigManager().getConnectionById(connection.getId())");
        assertThat(connect).contains("connectSavedConnection(current, true, tab -> { });");
        assertThat(methodBody(source(), "private void syncOpenRecentMenus() {"))
            .contains("RecentConnections.top(app.getConfigManager().getConnections(),");
    }

    @Test
    void aProjectIsRememberedOnceItOpenedOrWasSaved() throws IOException {
        String open = methodBody(source(), "private void openProjectFile(Path path) {");
        assertThat(open.indexOf("rememberRecentProject(path);")).isGreaterThan(open.indexOf("restoreProject(project, this);"));
        assertWithMessage("a cancelled open is not remembered")
            .that(open.indexOf("rememberRecentProject(path);"))
            .isGreaterThan(open.indexOf("if (!confirmHostedTabsClose()) {"));
        assertThat(methodBody(source(), "private void openProject() {")).contains("openProjectFile(file.toPath());");

        String save = methodBody(source(), "private void saveProject() {");
        assertThat(save.indexOf("rememberRecentProject(file.toPath());"))
            .isGreaterThan(save.indexOf("projectManager.saveProject(result.get(), file.toPath());"));
    }

    @Test
    void theProjectFilesAreLookedUpOffTheFxThreadAndAStaleLookupIsDropped() throws IOException {
        String refresh = methodBody(source(), "private void refreshRecentProjects() {");

        assertThat(refresh).contains("RECENT_PROJECTS_LOOKUP.execute(");
        assertThat(refresh).contains("RecentProjects.list(remembered, folder, clearedAt, RecentProjects.MAX_ENTRIES)");
        assertThat(refresh).contains("generation == recentProjectsGeneration");
        assertThat(methodBody(source(), "private void syncOpenRecentMenus() {")).doesNotContain("RecentProjects.list(");
        String clear = methodBody(source(), "private void clearOpenRecent() {");
        assertThat(clear).contains("settings.setOpenRecentClearedAt(System.currentTimeMillis());");
        assertThat(clear).contains("recentProjectsGeneration++;");
        assertThat(methodBody(source(), "private void rememberRecentProject(Path project) {"))
            .contains("recentProjectsGeneration++;");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static MenuItem separator() {
        return new MenuItem("---");
    }

    private static List<String> texts(List<MenuItem> items) {
        return items.stream().map(MenuItem::getText).toList();
    }

    private static ServerConnection connection(String name, String host, String user) {
        ServerConnection connection = new ServerConnection(name, host, 22, user);
        connection.setProtocol(ConnectionProtocol.SSH_TCP);
        return connection;
    }

    private static final class Recorder implements OpenRecentMenuSupport.Commands {
        final List<String> log = new ArrayList<>();

        @Override
        public void connect(ServerConnection connection) {
            log.add("connect " + connection.getName());
        }

        @Override
        public void openProject(Path project) {
            log.add("open " + project);
        }

        @Override
        public void clear() {
            log.add("clear");
        }
    }

    private static String source() throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(MAIN_WINDOW, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text of the method whose declaration contains {@code signature}, up to its closing brace. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("method not found: " + signature).that(start).isAtLeast(0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced method: " + signature);
    }
}
