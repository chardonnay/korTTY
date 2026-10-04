package de.kortty.ui;

import de.kortty.ui.actions.ActionIds;
import de.kortty.ui.actions.AppAction;
import de.kortty.ui.actions.MenuActionHarvester;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Every item of the main window's menu bars carries a stable action id, its i18n key, so the command
 * palette, its recently-used list and later key bindings can find it whatever the UI language. The
 * factories are tried on plain menu items (no toolkit needed); how the menu builders use them is
 * pinned against the source, since the window cannot be built without a stage. Both macOS menu bars
 * come from the same builders, so a pin here covers both.
 */
class MainWindowActionIdsTest {

    private static final Path SOURCE = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final Path BASE_BUNDLE = Path.of("src/main/resources/i18n/messages.properties");

    /** The builders of the menu-bar menus whose items the command palette harvests. */
    private static final List<String> BUILDERS = List.of(
        "private Menu createFileMenu() {",
        "private Menu createEditMenu(MenuBarTarget target) {",
        "private Menu createConnectionsMenu() {",
        "private Menu createConfigurationMenu() {",
        "private Menu createToolsMenu() {",
        "private Menu createAiMenu() {",
        "private Menu createTeamworkMenu() {",
        "private Menu createPluginsMenu() {",
        "private Menu createViewMenu(MenuBarTarget target) {",
        "private Menu createHighlightingMenu(MenuBarTarget target) {",
        "private Menu createPanesMenu(MenuBarTarget target) {",
        "private Menu createMultiExecMenu(MenuBarTarget target) {",
        "private Menu createHelpMenu() {");

    private static final Pattern NEW_ITEM = Pattern.compile("new (?:Check|Radio)?MenuItem\\(");
    private static final Pattern ITEM_DECLARATION =
        Pattern.compile("\\b(?:Check|Radio)?MenuItem (\\w+)\\s*=\\s*([^;]+);");
    private static final Pattern FACTORY_KEY = Pattern.compile("\\b(?:menuItem|checkMenuItem)\\(\"([^\"]+)\"\\)");
    private static final Pattern EXPLICIT_KEY = Pattern.compile("ActionIds\\.tag\\([^;]*?,\\s*\"([^\"]+)\"\\);");

    // ---- the factories ------------------------------------------------------------------------

    @Test
    void theFactoriesLabelAnItemWithItsKeyAndTagItWithTheKey() {
        MenuItem newTab = MainWindow.menuItem("menu.file.newTab");
        CheckMenuItem dashboard = MainWindow.checkMenuItem("menu.view.dashboard");

        assertThat(newTab.getText()).isEqualTo(I18n.get("menu.file.newTab"));
        assertThat(ActionIds.idOf(newTab)).isEqualTo("menu.file.newTab");
        assertThat(newTab).isNotInstanceOf(CheckMenuItem.class);
        assertThat(dashboard.getText()).isEqualTo(I18n.get("menu.view.dashboard"));
        assertThat(ActionIds.idOf(dashboard)).isEqualTo("menu.view.dashboard");
        assertThat(dashboard.isSelected()).isFalse();
    }

    @Test
    void harvestedItemsKeepTheKeyAsAStableIdAndPolicyLockedOnesSaySo() {
        MenuItem sessionJournals = MainWindow.menuItem("menu.tools.sessionJournals");
        MainWindow.lockByPolicy(sessionJournals);
        Menu tools = new Menu(I18n.get("menu.tools"));
        tools.getItems().addAll(MainWindow.menuItem("menu.tools.snippets"), sessionJournals);

        List<AppAction> actions = MenuActionHarvester.harvest(List.of(tools));

        assertThat(actions.stream().map(AppAction::id).toList())
            .containsExactly("menu.tools.snippets", "menu.tools.sessionJournals").inOrder();
        assertThat(actions.stream().allMatch(AppAction::stableId)).isTrue();
        AppAction snippets = actions.get(0);
        assertThat(snippets.isEnabled()).isTrue();
        assertThat(snippets.policyLocked()).isFalse();
        AppAction locked = actions.get(1);
        assertThat(sessionJournals.isDisable()).isTrue();
        assertThat(ActionIds.isPolicyLocked(sessionJournals)).isTrue();
        assertThat(locked.isEnabled()).isFalse();
        assertThat(locked.policyLocked()).isTrue();
    }

    // ---- the builders -------------------------------------------------------------------------

    @Test
    void theBuildersCreateNoItemWithoutAnId() throws IOException {
        String source = source();
        List<String> untagged = new ArrayList<>();
        for (String builder : BUILDERS) {
            String body = methodBody(source, builder);
            Matcher created = NEW_ITEM.matcher(body);
            while (created.find()) {
                String before = body.substring(0, created.start()).stripTrailing();
                if (!before.endsWith("ActionIds.tag(")) {
                    untagged.add(builder + " … " + lineAt(body, created.start()));
                }
            }
            Matcher declared = ITEM_DECLARATION.matcher(body);
            while (declared.find()) {
                String name = declared.group(1);
                String value = declared.group(2).strip();
                boolean tagged = value.startsWith("menuItem(") || value.startsWith("checkMenuItem(")
                    || value.startsWith("ActionIds.tag(") || body.contains("ActionIds.tag(" + name + ", ");
                if (!tagged) {
                    untagged.add(builder + " … " + name);
                }
            }
        }
        assertWithMessage("menu-bar items must come from menuItem(key)/checkMenuItem(key) or carry ActionIds.tag")
            .that(untagged).isEmpty();
    }

    @Test
    void everyIdIsAnExistingKeyAndNoTwoItemsShareOne() throws IOException {
        String source = source();
        Properties base = new Properties();
        try (Reader reader = Files.newBufferedReader(BASE_BUNDLE, StandardCharsets.UTF_8)) {
            base.load(reader);
        }

        Map<String, Integer> ids = new LinkedHashMap<>();
        for (String builder : BUILDERS) {
            String body = methodBody(source, builder);
            for (Pattern pattern : List.of(FACTORY_KEY, EXPLICIT_KEY)) {
                Matcher key = pattern.matcher(body);
                while (key.find()) {
                    ids.merge(key.group(1), 1, Integer::sum);
                }
            }
        }
        // The highlighting items come from HighlightMenuSupport and are tagged with its key constants.
        String highlighting = methodBody(source, "private Menu createHighlightingMenu(MenuBarTarget target) {");
        assertThat(highlighting).contains("ActionIds.tag(toggle, HighlightMenuSupport.TOGGLE_KEY);");
        assertThat(highlighting).contains("ActionIds.tag(manage, HighlightMenuSupport.MANAGE_KEY);");
        ids.merge(HighlightMenuSupport.TOGGLE_KEY, 1, Integer::sum);
        ids.merge(HighlightMenuSupport.MANAGE_KEY, 1, Integer::sum);
        // View > Panes and View > Multi-exec come from PaneMenuSupport and MultiExecMenuSupport, which tag
        // every item with its key (PaneMenuSupportTest, MultiExecMenuSupportTest, PanesPaletteHarvestTest).
        String view = methodBody(source, "private Menu createViewMenu(MenuBarTarget target) {");
        assertThat(view).contains("Menu panesMenu = createPanesMenu(target);");
        assertThat(view).contains("Menu multiExecMenu = createMultiExecMenu(target);");
        assertThat(methodBody(source, "private Menu createPanesMenu(MenuBarTarget target) {"))
            .contains("PaneMenuSupport.create(");
        assertThat(methodBody(source, "private Menu createMultiExecMenu(MenuBarTarget target) {"))
            .contains("MultiExecMenuSupport.create(");
        for (List<String> submenuKeys : List.of(PaneMenuSupport.KEYS, MultiExecMenuSupport.KEYS)) {
            for (String key : submenuKeys.subList(1, submenuKeys.size())) {
                ids.merge(key, 1, Integer::sum);
            }
        }

        assertThat(ids.size()).isAtLeast(80);
        assertThat(ids).containsKey("menu.view.panes.broadcast");
        assertThat(ids).containsKey("menu.help.about");
        assertThat(ids).containsKey("menu.configuration.preventSleep");
        List<String> missing = ids.keySet().stream().filter(id -> !base.containsKey(id)).toList();
        assertWithMessage("action ids that are no key of messages.properties").that(missing).isEmpty();
        List<String> shared = ids.entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .map(Map.Entry::getKey)
            .toList();
        assertWithMessage("a shared id would leave the later item without a stable id").that(shared).isEmpty();
    }

    @Test
    void itemsWithAComputedLabelAreTaggedExplicitly() throws IOException {
        String source = source();

        assertThat(compact(methodBody(source, "private Menu createHelpMenu() {"))).contains(
            "MenuItemabout=ActionIds.tag(newMenuItem(I18n.get(\"menu.help.about\")+\"\"+KorTTYApplication.getAppName()),"
                + "\"menu.help.about\");");
        assertThat(compact(methodBody(source, "private Menu createConfigurationMenu() {"))).contains(
            "CheckMenuItempreventSleep=ActionIds.tag(newCheckMenuItem(),\"menu.configuration.preventSleep\");");
    }

    /**
     * Recently Closed, Open Recent, the job status menu and the terminal effects are filled while they
     * open, so a harvested copy of their items would go stale; the highlighting set list is excluded
     * item by item (HighlightMenuSupportTest).
     */
    @Test
    void menusFilledWhileTheyOpenStayOutOfTheHarvest() throws IOException {
        String source = source();

        assertThat(methodBody(source, "private Menu createFileMenu() {"))
            .contains("Menu recentlyClosed = ActionIds.exclude(new Menu(I18n.get(\"menu.file.recentlyClosed\")));");
        assertThat(methodBody(source, "private Menu createFileMenu() {"))
            .contains("Menu openRecent = ActionIds.exclude(new Menu(I18n.get(\"menu.file.openRecent\")));");
        assertThat(methodBody(source, "private Menu createJobSchedulerStatusMenu(MenuBarTarget target) {"))
            .contains("Menu jobsMenu = ActionIds.exclude(new Menu(I18n.get(\"jobscheduler.menu.noJobs\")));");
        assertThat(methodBody(source, "private Menu createViewMenu(MenuBarTarget target) {")).contains(
            "Menu terminalEffectMenu = ActionIds.exclude(createTerminalEffectMenu(null, includeEffectSpeedControl));");
    }

    /** View &gt; Command Palette… opens the palette, so the palette does not list it as a command. */
    @Test
    void theCommandPaletteItemIsNoCommandOfThePalette() throws IOException {
        String view = methodBody(source(), "private Menu createViewMenu(MenuBarTarget target) {");

        assertThat(view).contains("MenuItem commandPalette = menuItem(\"menu.view.commandPalette\");");
        assertThat(view).contains("ActionIds.exclude(commandPalette);");
    }

    @Test
    void itemsThePolicyDeniesAreLockedNotJustDisabled() throws IOException {
        String source = source();

        for (String builder : BUILDERS) {
            assertWithMessage("%s disables an item for good; use lockByPolicy, or a sync method for a passing state",
                    builder)
                .that(methodBody(source, builder)).doesNotContain(".setDisable(true);");
        }
        Map<String, List<String>> locked = Map.of(
            "private Menu createToolsMenu() {", List.of("sessionJournals", "toggleSessionJournal", "journalScreenshot"),
            "private Menu createAiMenu() {", List.of("savedChats", "aiAgent", "aiPlanning", "aiSwarm"),
            "private Menu createTeamworkMenu() {", List.of("teamworkSettings"),
            "private Menu createPluginsMenu() {", List.of("terminalEffects"));
        for (Map.Entry<String, List<String>> entry : locked.entrySet()) {
            String body = methodBody(source, entry.getKey());
            assertThat(body).contains("PolicyManager.effective()");
            for (String item : entry.getValue()) {
                assertThat(body).contains("lockByPolicy(" + item + ");");
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static String source() throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text without any whitespace, so a pin does not depend on line breaks or indentation. */
    private static String compact(String text) {
        return text.replaceAll("\\s+", "");
    }

    private static String lineAt(String text, int index) {
        int start = text.lastIndexOf('\n', index) + 1;
        int end = text.indexOf('\n', index);
        return text.substring(start, end < 0 ? text.length() : end).strip();
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
