package de.kortty.ui;

import de.kortty.ui.ClosedWindowMenuRouter.WindowNeed;
import de.kortty.ui.actions.MenuItemActivation;
import javafx.event.Event;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Where the menu bar of a closed window acts. On macOS korTTY keeps running after the last window
 * closed and that window's menu bar stays the application's menu bar, so its items must act in an
 * open window or a new one, never in the closed window. The decisions and the routing are checked
 * on {@link ClosedWindowMenuRouter} with plain menus (no Control, so no JavaFX toolkit); the wiring
 * into {@link MainWindow}, which cannot be built without a stage, is pinned against the source.
 */
class ClosedWindowMenuRouterTest {

    private static final Path UI_ROOT = Path.of("src/main/java/de/kortty/ui");

    // ---- decisions ---------------------------------------------------------------------------

    @Test
    void anOpenWindowActsInItself() {
        List<String> opened = new ArrayList<>();

        String window = ClosedWindowMenuRouter.targetWindow("owner", WindowNeed.ANY_WINDOW, "owner"::equals,
            () -> "other", () -> {
                opened.add("new");
                return "new";
            });

        assertThat(window).isEqualTo("owner");
        assertThat(opened).isEmpty();
    }

    @Test
    void aClosedWindowHandsTheActionToAnOpenWindowWithoutOpeningAnother() {
        List<String> opened = new ArrayList<>();

        String window = ClosedWindowMenuRouter.targetWindow("closed", WindowNeed.ANY_WINDOW, w -> false,
            () -> "open", () -> {
                opened.add("new");
                return "new";
            });

        assertThat(window).isEqualTo("open");
        assertThat(opened).isEmpty();
    }

    @Test
    void aClosedWindowWithNoWindowLeftOpensANewOne() {
        assertThat(ClosedWindowMenuRouter.targetWindow("closed", WindowNeed.ANY_WINDOW, w -> false,
            () -> null, () -> "new")).isEqualTo("new");
        assertWithMessage("a window that could not be opened leaves nothing to act in")
            .that(ClosedWindowMenuRouter.<String>targetWindow("closed", WindowNeed.ANY_WINDOW, w -> false,
                () -> null, () -> null)).isNull();
    }

    @Test
    void anItemOnItsOwnWindowsContentDoesNothingOnceThatWindowClosed() {
        List<String> asked = new ArrayList<>();

        String window = ClosedWindowMenuRouter.targetWindow("closed", WindowNeed.OWN_WINDOW, w -> false,
            () -> {
                asked.add("open");
                return "open";
            }, () -> {
                asked.add("new");
                return "new";
            });

        assertWithMessage("Close Tab must not close a tab of another window, nor open one to close it")
            .that(window).isNull();
        assertThat(asked).isEmpty();
        assertThat(ClosedWindowMenuRouter.targetWindow("owner", WindowNeed.OWN_WINDOW, w -> true,
            () -> "other", () -> "new")).isEqualTo("owner");
    }

    @Test
    void anItemThatNeedsNoWindowRunsWhereItIs() {
        List<String> asked = new ArrayList<>();

        String window = ClosedWindowMenuRouter.targetWindow("closed", WindowNeed.NO_WINDOW, w -> false,
            () -> {
                asked.add("open");
                return "open";
            }, () -> {
                asked.add("new");
                return "new";
            });

        assertWithMessage("New Window and Quit must not open a window first").that(window).isEqualTo("closed");
        assertThat(asked).isEmpty();
    }

    @Test
    void unmarkedItemsNeedAWindow() {
        MenuItem plain = new MenuItem("New Tab");
        MenuItem close = ClosedWindowMenuRouter.ownWindowOnly(new MenuItem("Close Tab"));
        MenuItem quit = ClosedWindowMenuRouter.noWindowNeeded(new MenuItem("Quit"));

        assertThat(ClosedWindowMenuRouter.needOf(plain)).isEqualTo(WindowNeed.ANY_WINDOW);
        assertThat(ClosedWindowMenuRouter.needOf(close)).isEqualTo(WindowNeed.OWN_WINDOW);
        assertThat(ClosedWindowMenuRouter.needOf(quit)).isEqualTo(WindowNeed.NO_WINDOW);
    }

    @Test
    void theFrontmostWindowIsTheFocusedOneElseTheOneFocusedLastElseTheOneOpenedLast() {
        List<String> open = List.of("first", "second", "third");
        Predicate<String> noneFocused = w -> false;

        assertThat(ClosedWindowMenuRouter.frontmost(open, "second"::equals, "first")).isEqualTo("second");
        assertThat(ClosedWindowMenuRouter.frontmost(open, noneFocused, "first")).isEqualTo("first");
        assertWithMessage("a window focused last that has closed since does not count")
            .that(ClosedWindowMenuRouter.frontmost(open, noneFocused, "closed")).isEqualTo("third");
        assertThat(ClosedWindowMenuRouter.frontmost(open, noneFocused, null)).isEqualTo("third");
        assertThat(ClosedWindowMenuRouter.frontmost(List.<String>of(), noneFocused, "closed")).isNull();
    }

    // ---- routing ------------------------------------------------------------------------------

    @Test
    void anItemOfAnOpenWindowRunsAsBuilt() {
        Desktop desktop = new Desktop();
        Win owner = desktop.open("A");

        click(owner.item("File", "New Tab"));

        assertThat(desktop.log).containsExactly("New Tab in A");
    }

    @Test
    void anItemOfAClosedWindowRunsInTheFrontmostOpenWindowWhichComesToTheFront() {
        Desktop desktop = new Desktop();
        Win closed = desktop.open("A");
        desktop.open("B");
        desktop.open("C");
        closed.open = false;
        desktop.frontmost = "B";

        click(closed.item("File", "New Tab"));

        assertThat(desktop.log).containsExactly("front B", "New Tab in B").inOrder();
        assertThat(desktop.opened).isEmpty();
    }

    @Test
    void anItemOfAClosedWindowOpensANewWindowWhenNoneIsOpen() {
        Desktop desktop = new Desktop();
        Win closed = desktop.open("A");
        closed.open = false;

        click(closed.item("Connections", "Manage Connections"));

        assertThat(desktop.opened).containsExactly("new-1");
        assertThat(desktop.log).containsExactly("front new-1", "Manage Connections in new-1").inOrder();
    }

    @Test
    void itemsInSubmenusAreFoundAtTheSamePlace() {
        Desktop desktop = new Desktop();
        Win closed = desktop.open("A");
        desktop.open("B");
        closed.open = false;

        click(closed.item("Configuration", "Security", "SSH Keys"));

        assertThat(desktop.log).containsExactly("front B", "SSH Keys in B").inOrder();
    }

    @Test
    void aToggleOfAClosedWindowFollowsTheStateOfTheWindowItActsIn() {
        Desktop desktop = new Desktop();
        Win closed = desktop.open("A");
        Win open = desktop.open("B");
        CheckMenuItem closedDashboard = (CheckMenuItem) closed.item("View", "Dashboard");
        // A showed its dashboard when it closed; B shows none.
        closedDashboard.setSelected(true);
        closed.open = false;

        click(closedDashboard);

        assertWithMessage("B shows its dashboard, it does not hide one it never showed")
            .that(desktop.log).containsExactly("front B", "Dashboard on in B").inOrder();
        assertThat(((CheckMenuItem) open.item("View", "Dashboard")).isSelected()).isTrue();
        assertWithMessage("the closed menu bar shows what B has now")
            .that(closedDashboard.isSelected()).isTrue();
    }

    @Test
    void closingAndClipboardItemsOfAClosedWindowDoNothing() {
        Desktop desktop = new Desktop();
        Win closed = desktop.open("A");
        desktop.open("B");
        closed.open = false;

        click(closed.item("File", "Close Tab"));
        click(closed.item("Edit", "Paste"));
        desktop.windows.get(1).open = false;
        click(closed.item("File", "Close Window"));

        assertThat(desktop.log).isEmpty();
        assertThat(desktop.opened).isEmpty();
    }

    @Test
    void itemsThatNeedNoWindowRunInTheClosedWindowWithoutOpeningOne() {
        Desktop desktop = new Desktop();
        Win closed = desktop.open("A");
        closed.open = false;

        click(closed.item("File", "New Window"));
        click(closed.item("File", "Quit"));

        assertThat(desktop.log).containsExactly("New Window in A", "Quit in A").inOrder();
        assertThat(desktop.opened).isEmpty();
    }

    @Test
    void aMenuThatFillsItselfWhileOpeningIsFilledInTheOtherWindowFirst() {
        Desktop desktop = new Desktop();
        Win closed = desktop.open("A");
        Win open = desktop.open("B");
        closed.open = false;
        Menu closedJobs = closed.menu("Jobs");
        showing(closedJobs);
        assertThat(open.menu("Jobs").getItems()).isEmpty();

        click(closed.item("Jobs", "Open Scheduler"));

        assertThat(desktop.log).containsExactly("front B", "Open Scheduler in B").inOrder();
    }

    @Test
    void anItemAddedWhileAMenuOpensIsRoutedToo() {
        Desktop desktop = new Desktop();
        Win closed = desktop.open("A");
        closed.open = false;
        showing(closed.menu("Jobs"));
        showing(closed.menu("Jobs"));

        click(closed.item("Jobs", "Cancel nightly"));
        click(closed.item("Jobs", "Open Scheduler"));

        assertWithMessage("Cancel needs no window; Open Scheduler opens one")
            .that(desktop.log).containsExactly("Cancel nightly in A", "front new-1", "Open Scheduler in new-1")
            .inOrder();
    }

    @Test
    void anItemLabelledDifferentlyInTheOtherWindowIsNotRun() {
        Desktop desktop = new Desktop();
        Win closed = desktop.open("A");
        Win open = desktop.open("B");
        closed.open = false;
        open.item("File", "New Tab").setText("Something else");

        click(closed.item("File", "New Tab"));

        assertThat(desktop.log).isEmpty();
    }

    @Test
    void installingTwiceStillRunsAnItemOnce() {
        Desktop desktop = new Desktop();
        Win owner = desktop.open("A");
        ClosedWindowMenuRouter.install(owner, w -> w.menus, desktop);
        showing(owner.menu("Jobs"));
        showing(owner.menu("Jobs"));

        click(owner.item("File", "New Tab"));
        click(owner.item("Jobs", "Open Scheduler"));

        assertThat(desktop.log).containsExactly("New Tab in A", "Open Scheduler in A").inOrder();
    }

    @Test
    void anItemsPlaceIsItsIndexOnEveryLevel() {
        Desktop desktop = new Desktop();
        Win window = desktop.open("A");

        assertThat(ClosedWindowMenuRouter.pathOf(window.item("Configuration", "Security", "SSH Keys"), window.menus))
            .containsExactly(3, 0, 1).inOrder();
        assertThat(ClosedWindowMenuRouter.pathOf(new MenuItem("Loose"), window.menus)).isEmpty();
    }

    // ---- wiring -------------------------------------------------------------------------------

    @Test
    void bothMenuBarsOfAMainWindowAreRouted() throws IOException {
        String window = source("MainWindow.java");

        String setup = methodBody(window, "private void setupMenuBar() {");
        assertThat(setup).contains("ClosedWindowMenuRouter.install(this, window -> window.menuBar.getMenus(), MENU_BAR_WINDOWS);");
        assertThat(setup).contains("ClosedWindowMenuRouter.install(this, MainWindow::systemMenus, MENU_BAR_WINDOWS);");
        assertWithMessage("routing comes after both bars are built")
            .that(setup.indexOf("ClosedWindowMenuRouter.install("))
            .isGreaterThan(setup.indexOf("systemMenuBar = createApplicationMenuBar(MenuBarTarget.SYSTEM);"));
        assertWithMessage("an item of a closed window acts in the frontmost open window, else a new one")
            .that(methodBody(window, "private static MainWindow getFrontmostOpenWindow() {"))
            .contains("ClosedWindowMenuRouter.frontmost(openWindows,");
    }

    @Test
    void closingAndClipboardItemsStayInTheirWindowAndAppWideItemsNeedNone() throws IOException {
        String window = source("MainWindow.java");

        String file = methodBody(window, "private Menu createFileMenu() {");
        for (String item : List.of("closeTab", "closeOthers", "closeToRight", "closeAllTabs", "closeWindow")) {
            assertThat(file).contains("ClosedWindowMenuRouter.ownWindowOnly(" + item + ");");
        }
        for (String item : List.of("newWindow", "quit")) {
            assertThat(file).contains("ClosedWindowMenuRouter.noWindowNeeded(" + item + ");");
        }
        String edit = methodBody(window, "private Menu createEditMenu(MenuBarTarget target) {");
        for (String item : List.of("cut", "copy", "paste")) {
            assertThat(edit).contains("ClosedWindowMenuRouter.ownWindowOnly(" + item + ");");
        }
        assertThat(methodBody(window, "private Menu createConfigurationMenu() {"))
            .contains("ClosedWindowMenuRouter.noWindowNeeded(preventSleep);");
        // Like Find, the command palette opens in the frontmost open window (the default need).
        String view = methodBody(window, "private Menu createViewMenu(MenuBarTarget target) {");
        assertThat(view).contains("MenuItem commandPalette = menuItem(\"menu.view.commandPalette\");");
        assertThat(view).doesNotContain("ClosedWindowMenuRouter.ownWindowOnly(commandPalette)");
        assertThat(view).doesNotContain("ClosedWindowMenuRouter.noWindowNeeded(commandPalette)");
        assertThat(edit).doesNotContain("ClosedWindowMenuRouter.ownWindowOnly(find)");
        assertThat(methodBody(window, "private void rebuildJobSchedulerStatusMenuItems(Menu menu) {"))
            .contains("ClosedWindowMenuRouter.noWindowNeeded(cancel);");
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** A click on a menu item, as the macOS menu bar does it: a check item toggles, then it fires. */
    private static void click(MenuItem item) {
        assertThat(item).isNotNull();
        MenuItemActivation.activate(item);
    }

    /** A menu opening: it runs its onShowing, as the menu bar does. */
    private static void showing(Menu menu) {
        menu.getOnShowing().handle(new Event(Menu.ON_SHOWING));
    }

    /** The windows of a test, standing in for MainWindow's open-window list. */
    private static final class Desktop implements ClosedWindowMenuRouter.Windows<Win> {
        final List<Win> windows = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        final List<String> opened = new ArrayList<>();
        String frontmost;

        Win open(String name) {
            Win window = new Win(name, this);
            windows.add(window);
            ClosedWindowMenuRouter.install(window, w -> w.menus, this);
            return window;
        }

        @Override
        public boolean isOpen(Win window) {
            return window.open;
        }

        @Override
        public Win frontmostOpen() {
            List<Win> open = windows.stream().filter(w -> w.open).toList();
            Win last = windows.stream().filter(w -> w.name.equals(frontmost)).findFirst().orElse(null);
            return ClosedWindowMenuRouter.frontmost(open, w -> false, last);
        }

        @Override
        public Win openNew() {
            Win window = open("new-" + (opened.size() + 1));
            opened.add(window.name);
            return window;
        }

        @Override
        public void bringToFront(Win window) {
            log.add("front " + window.name);
        }
    }

    /** A window with the menus every window builds the same way. */
    private static final class Win {
        final String name;
        final List<Menu> menus = new ArrayList<>();
        boolean open = true;

        Win(String name, Desktop desktop) {
            this.name = name;
            List<String> log = desktop.log;
            Menu file = new Menu("File");
            file.getItems().addAll(
                action("New Tab", log),
                ClosedWindowMenuRouter.ownWindowOnly(action("Close Tab", log)),
                ClosedWindowMenuRouter.noWindowNeeded(action("New Window", log)),
                ClosedWindowMenuRouter.ownWindowOnly(action("Close Window", log)),
                ClosedWindowMenuRouter.noWindowNeeded(action("Quit", log)));
            Menu edit = new Menu("Edit");
            edit.getItems().addAll(ClosedWindowMenuRouter.ownWindowOnly(action("Paste", log)), action("Find", log));
            Menu connections = new Menu("Connections");
            connections.getItems().addAll(action("Quick Connect", log), action("Manage Connections", log));
            Menu security = new Menu("Security");
            security.getItems().addAll(action("Credentials", log), action("SSH Keys", log));
            Menu configuration = new Menu("Configuration");
            configuration.getItems().add(security);
            Menu view = new Menu("View");
            CheckMenuItem dashboard = new CheckMenuItem("Dashboard");
            dashboard.setOnAction(e -> log.add("Dashboard " + (dashboard.isSelected() ? "on" : "off") + " in " + name));
            view.getItems().add(dashboard);
            // Filled while it opens, like the job status menu.
            Menu jobs = new Menu("Jobs");
            jobs.setOnShowing(e -> {
                jobs.getItems().clear();
                jobs.getItems().add(action("Open Scheduler", log));
                jobs.getItems().add(ClosedWindowMenuRouter.noWindowNeeded(action("Cancel nightly", log)));
            });
            menus.addAll(List.of(file, edit, connections, configuration, view, jobs));
        }

        private MenuItem action(String label, List<String> log) {
            MenuItem item = new MenuItem(label);
            item.setOnAction(e -> log.add(label + " in " + name));
            return item;
        }

        Menu menu(String label) {
            return menus.stream().filter(m -> m.getText().equals(label)).findFirst().orElseThrow();
        }

        MenuItem item(String... labels) {
            MenuItem current = menu(labels[0]);
            for (int i = 1; i < labels.length; i++) {
                String label = labels[i];
                current = ((Menu) current).getItems().stream()
                    .filter(item -> label.equals(item.getText())).findFirst().orElse(null);
                if (current == null) {
                    return null;
                }
            }
            return current;
        }
    }

    private static String source(String fileName) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(UI_ROOT.resolve(fileName), StandardCharsets.UTF_8).replace("\r\n", "\n");
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
