package de.kortty.ui;

import de.kortty.ui.ClosedWindowMenuRouter.WindowNeed;
import de.kortty.ui.PaneNavigator.PaneDirection;
import de.kortty.ui.actions.ActionPaletteSource;
import de.kortty.ui.actions.ActionRegistry;
import de.kortty.ui.actions.AppAction;
import de.kortty.ui.actions.MenuActionHarvester;
import de.kortty.ui.actions.MenuStateRefresh;
import de.kortty.ui.actions.PaletteEntry;
import de.kortty.ui.actions.TerminalPaletteActions;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * View → Panes and View → Multi-exec as command palette rows. The palette harvests the window's
 * in-window menu bar, so these submenus' items arrive as commands with their i18n keys as stable
 * ids, the menu path View › Panes or View › Multi-exec, the shortcuts MainWindow gives them, check
 * marks for the toggles (Zoom Pane, the broadcast item and the two include items), and the enabled
 * state their sync gives them once the palette has refreshed the menus. A row runs through its menu
 * item, so the item's validation, its sync and the closed-window router's marks stay in force. The
 * palette's own terminal commands add no second split or broadcast row.
 *
 * <p>No JavaFX toolkit is started: menu items are no nodes, and the separators are plain marker
 * items, since a SeparatorMenuItem holds a control whose static initializer needs the toolkit.
 */
class PanesPaletteHarvestTest {

    private static final Path SOURCE = Path.of("src/main/java/de/kortty/ui/MainWindow.java");

    private static final Supplier<MenuItem> SEPARATORS = MenuItem::new;

    private static final KeyCombination SPLIT_CHORD =
        new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final KeyCombination ZOOM_CHORD =
        new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final Map<PaneDirection, KeyCombination> FOCUS_CHORDS = Map.of(
        PaneDirection.LEFT, new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN),
        PaneDirection.RIGHT, new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN),
        PaneDirection.UP, new KeyCodeCombination(KeyCode.UP, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN),
        PaneDirection.DOWN, new KeyCodeCombination(KeyCode.DOWN, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN));

    private static final List<String> TOGGLES = List.of(PaneMenuSupport.ZOOM_KEY, PaneMenuSupport.BROADCAST_KEY,
        MultiExecMenuSupport.INCLUDE_PANE_KEY, MultiExecMenuSupport.INCLUDE_TAB_KEY);

    /**
     * One window's View menu with Panes and Multi-exec as MainWindow builds them: the shortcuts on
     * Split Pane, Zoom Pane and the focus items, and both submenus synced while they open, when they
     * validate and after every command.
     */
    private static final class Window implements PaneMenuSupport.Commands, MultiExecMenuSupport.Commands {
        final List<String> log = new ArrayList<>();
        final Menu view = new Menu(I18n.get("menu.view"));
        final PaneMenuSupport.PaneMenu panes;
        final MultiExecMenuSupport.MultiExecMenu multiExec;
        boolean terminal = true;
        int paneCount = 1;
        boolean broadcast;
        boolean zoomed;
        boolean paneIncluded;
        int members;

        Window() {
            panes = PaneMenuSupport.create(this, SEPARATORS);
            panes.split().setAccelerator(SPLIT_CHORD);
            panes.zoom().setAccelerator(ZOOM_CHORD);
            FOCUS_CHORDS.forEach((direction, chord) -> panes.focusItem(direction).setAccelerator(chord));
            panes.menu().setOnShowing(event -> sync());
            panes.menu().setOnMenuValidation(event -> sync());
            multiExec = MultiExecMenuSupport.create(this, SEPARATORS);
            multiExec.menu().setOnShowing(event -> sync());
            multiExec.menu().setOnMenuValidation(event -> sync());
            view.getItems().addAll(panes.menu(), multiExec.menu());
        }

        List<Menu> menus() {
            return List.of(view);
        }

        void sync() {
            PaneMenuSupport.sync(panes, terminal
                ? new PaneMenuSupport.State(true, paneCount, broadcast, zoomed)
                : PaneMenuSupport.State.NO_TERMINAL);
            MultiExecMenuSupport.sync(multiExec, new MultiExecMenuSupport.State(terminal,
                terminal && paneIncluded, terminal && paneIncluded && paneCount == 1, terminal, members));
        }

        @Override
        public void split(SplitOrientationChooser.SplitSide side) {
            log.add("split " + (side != null ? side : "auto"));
            paneCount++;
            sync();
        }

        @Override
        public void closePane() {
            log.add("close");
            paneCount--;
            sync();
        }

        @Override
        public void focus(PaneDirection direction) {
            log.add("focus " + direction);
        }

        @Override
        public void cycle(boolean forward) {
            log.add(forward ? "next" : "previous");
        }

        @Override
        public void toggleZoom() {
            zoomed = !zoomed;
            log.add("zoom " + (zoomed ? "on" : "off"));
            sync();
        }

        @Override
        public void toggleBroadcast() {
            // As MainWindow: the tab's mode decides, and switching it on needs a second pane.
            if (broadcast || paneCount >= 2) {
                broadcast = !broadcast;
                log.add("broadcast " + (broadcast ? "on" : "off"));
            }
            sync();
        }

        @Override
        public void togglePane() {
            paneIncluded = !paneIncluded;
            members += paneIncluded ? 1 : -1;
            log.add("pane " + (paneIncluded ? "in" : "out"));
            sync();
        }

        @Override
        public void toggleTab() {
            togglePane();
        }

        @Override
        public void includeWindow() {
            log.add("window in");
            sync();
        }

        @Override
        public void stop() {
            paneIncluded = false;
            members = 0;
            log.add("stop");
            sync();
        }
    }

    /** The palette's commands as MainWindow registers them: the harvested menus first, then its own. */
    private static ActionRegistry registry(Window window) {
        ActionRegistry registry = new ActionRegistry();
        registry.addContributor(() -> MenuActionHarvester.harvest(window.menus()));
        List<AppAction> terminalActions = TerminalPaletteActions.actions(() -> null, I18n::get, null);
        registry.addContributor(() -> terminalActions);
        return registry;
    }

    private static List<String> itemKeys(List<String> keysWithMenu) {
        return keysWithMenu.subList(1, keysWithMenu.size());
    }

    private static AppAction action(ActionRegistry registry, String id) {
        return registry.find(id).orElseThrow(() -> new AssertionError("no palette row " + id));
    }

    @Test
    void everyPanesAndMultiExecItemIsACommandWithItsKeyAsStableIdAndItsMenuPath() {
        Window window = new Window();

        List<AppAction> actions = MenuActionHarvester.harvest(window.menus());

        List<String> expected = new ArrayList<>(itemKeys(PaneMenuSupport.KEYS));
        expected.addAll(itemKeys(MultiExecMenuSupport.KEYS));
        assertThat(actions.stream().map(AppAction::id).toList()).containsExactlyElementsIn(expected).inOrder();
        String panesPath = I18n.get("menu.view") + MenuActionHarvester.CATEGORY_SEPARATOR
            + I18n.get(PaneMenuSupport.MENU_KEY);
        String multiExecPath = I18n.get("menu.view") + MenuActionHarvester.CATEGORY_SEPARATOR
            + I18n.get(MultiExecMenuSupport.MENU_KEY);
        for (AppAction action : actions) {
            assertWithMessage(action.id()).that(action.stableId()).isTrue();
            assertWithMessage(action.id()).that(action.policyLocked()).isFalse();
            assertWithMessage(action.id()).that(action.label()).isEqualTo(I18n.get(action.id()));
            assertWithMessage(action.id()).that(action.category())
                .isEqualTo(action.id().startsWith(PaneMenuSupport.MENU_KEY + ".") ? panesPath : multiExecPath);
        }
    }

    @Test
    void theTogglesCarryCheckMarksAndTheShortcutsAreShown() {
        Window window = new Window();
        window.paneCount = 2;
        window.broadcast = true;
        window.paneIncluded = true;
        window.members = 1;
        window.sync();

        Map<String, AppAction> byId = new HashMap<>();
        for (AppAction action : MenuActionHarvester.harvest(window.menus())) {
            byId.put(action.id(), action);
        }

        for (AppAction action : byId.values()) {
            assertWithMessage(action.id() + " is a toggle").that(action.isCheckable())
                .isEqualTo(TOGGLES.contains(action.id()));
        }
        assertThat(byId.get(PaneMenuSupport.BROADCAST_KEY).isChecked()).isTrue();
        assertThat(byId.get(PaneMenuSupport.ZOOM_KEY).isChecked()).isFalse();
        assertThat(byId.get(MultiExecMenuSupport.INCLUDE_PANE_KEY).isChecked()).isTrue();

        assertThat(byId.get(PaneMenuSupport.SPLIT_AUTO_KEY).accelerator()).isEqualTo(SPLIT_CHORD);
        assertThat(byId.get(PaneMenuSupport.ZOOM_KEY).accelerator()).isEqualTo(ZOOM_CHORD);
        for (PaneDirection direction : PaneDirection.values()) {
            assertThat(byId.get(PaneMenuSupport.focusKey(direction)).accelerator())
                .isEqualTo(FOCUS_CHORDS.get(direction));
        }
        for (String withoutChord : List.of(PaneMenuSupport.SPLIT_RIGHT_KEY, PaneMenuSupport.SPLIT_DOWN_KEY,
                PaneMenuSupport.CLOSE_KEY, PaneMenuSupport.NEXT_KEY, PaneMenuSupport.PREVIOUS_KEY,
                PaneMenuSupport.BROADCAST_KEY, MultiExecMenuSupport.STOP_KEY)) {
            assertThat(byId.get(withoutChord).accelerator()).isNull();
        }

        ActionPaletteSource source = new ActionPaletteSource(registry(window), KeyCombination::getName,
            () -> "managed", () -> "unavailable");
        PaletteEntry broadcast = source.entries().stream()
            .filter(entry -> entry.key().equals("action:" + PaneMenuSupport.BROADCAST_KEY))
            .findFirst().orElseThrow();
        assertThat(broadcast.checked()).isTrue();
        assertThat(broadcast.detail()).isEqualTo(byId.get(PaneMenuSupport.BROADCAST_KEY).category());
    }

    /** The palette runs every menu's opening handler first (MenuStateRefresh), nested submenus too. */
    @Test
    void theEnabledStateFollowsTheActiveTabOnceThePaletteRefreshedTheMenus() {
        Window window = new Window();
        ActionRegistry registry = registry(window);

        for (AppAction action : registry.snapshot()) {
            assertWithMessage(action.id() + " before the first sync").that(action.isEnabled()).isFalse();
        }

        window.paneCount = 2;
        MenuStateRefresh.refresh(window.menus());
        for (String id : itemKeys(PaneMenuSupport.KEYS)) {
            assertWithMessage(id + " with two panes").that(action(registry, id).isEnabled()).isTrue();
        }
        assertThat(action(registry, MultiExecMenuSupport.INCLUDE_PANE_KEY).isEnabled()).isTrue();
        assertWithMessage("no pane takes part yet").that(action(registry, MultiExecMenuSupport.STOP_KEY).isEnabled())
            .isFalse();

        window.paneCount = 1;
        MenuStateRefresh.refresh(window.menus());
        assertThat(action(registry, PaneMenuSupport.SPLIT_RIGHT_KEY).isEnabled()).isTrue();
        assertWithMessage("the tab's last pane is never closed")
            .that(action(registry, PaneMenuSupport.CLOSE_KEY).isEnabled()).isFalse();
        assertWithMessage("broadcast needs a second pane to switch on")
            .that(action(registry, PaneMenuSupport.BROADCAST_KEY).isEnabled()).isFalse();
        assertThat(action(registry, PaneMenuSupport.ZOOM_KEY).isEnabled()).isFalse();

        window.terminal = false;
        MenuStateRefresh.refresh(window.menus());
        assertWithMessage("no terminal tab, nothing to split")
            .that(action(registry, PaneMenuSupport.SPLIT_AUTO_KEY).isEnabled()).isFalse();
    }

    @Test
    void aRowRunsItsMenuItemAndARefusedOneDoesNothing() {
        Window window = new Window();
        ActionRegistry registry = registry(window);
        MenuStateRefresh.refresh(window.menus());

        assertWithMessage("one pane: broadcast cannot switch on")
            .that(registry.run(PaneMenuSupport.BROADCAST_KEY)).isFalse();
        assertThat(registry.run(PaneMenuSupport.SPLIT_RIGHT_KEY)).isTrue();
        assertThat(registry.run(PaneMenuSupport.SPLIT_DOWN_KEY)).isTrue();
        assertThat(registry.run(PaneMenuSupport.BROADCAST_KEY)).isTrue();
        assertThat(action(registry, PaneMenuSupport.BROADCAST_KEY).isChecked()).isTrue();
        assertThat(registry.run(PaneMenuSupport.ZOOM_KEY)).isTrue();
        assertThat(action(registry, PaneMenuSupport.ZOOM_KEY).isChecked()).isTrue();
        assertThat(registry.run(MultiExecMenuSupport.INCLUDE_PANE_KEY)).isTrue();
        assertThat(registry.run(MultiExecMenuSupport.STOP_KEY)).isTrue();
        assertThat(action(registry, MultiExecMenuSupport.STOP_KEY).isEnabled()).isFalse();

        assertThat(window.log).containsExactly("split RIGHT", "split DOWN", "broadcast on", "zoom on", "pane in",
            "stop").inOrder();
    }

    /**
     * View → Panes → Split Right / Split Down / Broadcast to All Panes of This Tab do what the
     * right-click menu's same-server splits and Broadcast Mode do, so the palette lists them once,
     * from the menu bar, and its own terminal commands are only those without a menu item.
     */
    @Test
    void thePaletteHasOneRowPerPaneCommand() {
        Window window = new Window();

        List<String> ids = registry(window).snapshot().stream().map(AppAction::id).toList();

        assertThat(ids).containsAtLeast(PaneMenuSupport.SPLIT_RIGHT_KEY, PaneMenuSupport.SPLIT_DOWN_KEY,
            PaneMenuSupport.BROADCAST_KEY, TerminalPaletteActions.CLEAR_BUFFER, TerminalPaletteActions.DUPLICATE,
            TerminalPaletteActions.RECONNECT);
        assertThat(ids).containsNoneOf("terminal.contextMenu.splitRightSame", "terminal.contextMenu.splitDownSame",
            "terminal.contextMenu.broadcastMode");
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            assertWithMessage("a second row for " + id).that(seen.add(id)).isTrue();
        }
        List<String> harvested = MenuActionHarvester.harvest(window.menus()).stream().map(AppAction::id).toList();
        for (AppAction own : TerminalPaletteActions.actions(() -> null, I18n::get, null)) {
            assertWithMessage("the palette's own " + own.id() + " shadows no menu item")
                .that(harvested).doesNotContain(own.id());
        }
    }

    /**
     * A palette row fires its menu item, so the wrapper ClosedWindowMenuRouter put on the item decides
     * where it acts: in its own open window as built; once that window closed, the broadcast item and
     * Close Pane do nothing, a split acts in the window that is open, and Stop Multi-exec needs none.
     */
    @Test
    void theClosedWindowRouterStillDecidesWhereAHarvestedRowActs() {
        Window first = new Window();
        Window second = new Window();
        Set<Window> open = new HashSet<>(List.of(first, second));
        ClosedWindowMenuRouter.Windows<Window> windows = new ClosedWindowMenuRouter.Windows<>() {
            @Override
            public boolean isOpen(Window window) {
                return open.contains(window);
            }

            @Override
            public Window frontmostOpen() {
                return open.contains(second) ? second : null;
            }

            @Override
            public Window openNew() {
                return null;
            }

            @Override
            public void bringToFront(Window window) {
            }
        };
        ClosedWindowMenuRouter.install(first, Window::menus, windows);
        ClosedWindowMenuRouter.install(second, Window::menus, windows);
        for (Window window : List.of(first, second)) {
            window.paneCount = 2;
            window.members = 1;
            window.paneIncluded = true;
            window.sync();
        }
        List<AppAction> firstRows = MenuActionHarvester.harvest(first.menus());
        Map<String, AppAction> byId = new HashMap<>();
        firstRows.forEach(action -> byId.put(action.id(), action));

        assertThat(ActionRegistry.runIfEnabled(byId.get(PaneMenuSupport.BROADCAST_KEY))).isTrue();
        assertThat(first.log).containsExactly("broadcast on");

        open.remove(first);
        first.log.clear();
        ActionRegistry.runIfEnabled(byId.get(PaneMenuSupport.BROADCAST_KEY));
        ActionRegistry.runIfEnabled(byId.get(PaneMenuSupport.CLOSE_KEY));
        assertWithMessage("decides where typed keys go, or ends a session: never in another window")
            .that(first.log).isEmpty();
        assertThat(second.log).isEmpty();

        ActionRegistry.runIfEnabled(byId.get(PaneMenuSupport.SPLIT_RIGHT_KEY));
        assertThat(second.log).containsExactly("split RIGHT");
        assertThat(first.log).isEmpty();

        ActionRegistry.runIfEnabled(byId.get(MultiExecMenuSupport.STOP_KEY));
        assertWithMessage("Stop needs no window and runs as built").that(first.log).containsExactly("stop");

        assertThat(ClosedWindowMenuRouter.needOf(first.panes.broadcast())).isEqualTo(WindowNeed.OWN_WINDOW);
        assertThat(ClosedWindowMenuRouter.needOf(first.panes.close())).isEqualTo(WindowNeed.OWN_WINDOW);
        assertThat(ClosedWindowMenuRouter.needOf(first.multiExec.stop())).isEqualTo(WindowNeed.NO_WINDOW);
    }

    /**
     * MainWindow hands the palette the in-window menu bar, whose View menu holds Panes and Multi-exec
     * unexcluded, and refreshes every menu (nested ones too) before the palette reads them.
     */
    @Test
    void mainWindowHarvestsViewPanesAndMultiExecAndRefreshesThemFirst() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(source).contains("MenuActionHarvester.harvest(menuBar.getMenus())");
        assertThat(source).contains("MenuStateRefresh.refresh(menuBar.getMenus());");
        assertThat(source).contains("Menu panesMenu = createPanesMenu(target);");
        assertThat(source).contains("Menu multiExecMenu = createMultiExecMenu(target);");
        assertThat(source).doesNotContain("ActionIds.exclude(createPanesMenu");
        assertThat(source).doesNotContain("ActionIds.exclude(createMultiExecMenu");
        assertThat(source).doesNotContain("ActionIds.exclude(panesMenu");
        assertThat(source).doesNotContain("ActionIds.exclude(multiExecMenu");
        assertThat(source).contains("menu.menu().setOnShowing(event -> syncMultiExecMenuItems());");
        assertThat(source).contains("panes.menu().setOnShowing(event -> syncPaneMenuItems());");
    }
}
