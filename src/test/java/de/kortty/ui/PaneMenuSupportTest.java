package de.kortty.ui;

import de.kortty.ui.ClosedWindowMenuRouter.WindowNeed;
import de.kortty.ui.PaneNavigator.PaneDirection;
import de.kortty.ui.actions.ActionIds;
import de.kortty.ui.actions.MenuItemActivation;
import javafx.scene.control.MenuItem;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * View → Panes: four focus items, Next and Previous Pane, and the tab's broadcast mode as a check
 * item. The focus items need two or more panes; the broadcast item shows the tab's real mode and its
 * action decides from that mode, never from the item JavaFX has already flipped. Menu items are not
 * nodes, so no JavaFX toolkit is started; separators are plain marker items here, since a
 * SeparatorMenuItem holds a control whose static initializer needs the toolkit.
 */
class PaneMenuSupportTest {

    private static final String SEPARATOR_MARK = "test.separator";
    private static final Path SOURCE = Path.of("src/main/java/de/kortty/ui/MainWindow.java");

    private static final Supplier<MenuItem> SEPARATORS = () -> {
        MenuItem separator = new MenuItem();
        separator.getProperties().put(SEPARATOR_MARK, Boolean.TRUE);
        return separator;
    };

    /** A tab with panes and a broadcast mode, as the menu's commands see it. */
    private static final class FakeTab implements PaneMenuSupport.Commands {
        final List<String> log = new ArrayList<>();
        int paneCount = 2;
        boolean broadcast;

        @Override
        public void focus(PaneDirection direction) {
            log.add("focus " + direction);
        }

        @Override
        public void cycle(boolean forward) {
            log.add(forward ? "next" : "previous");
        }

        @Override
        public void toggleBroadcast() {
            broadcast = !broadcast;
            log.add("broadcast " + (broadcast ? "on" : "off"));
        }

        PaneMenuSupport.State state() {
            return new PaneMenuSupport.State(true, paneCount, broadcast);
        }
    }

    @Test
    void theMenuListsTheFocusItemsThenNextAndPreviousThenBroadcast() {
        PaneMenuSupport.PaneMenu menu = PaneMenuSupport.create(new FakeTab(), SEPARATORS);

        List<String> labels = new ArrayList<>();
        for (MenuItem item : menu.menu().getItems()) {
            labels.add(Boolean.TRUE.equals(item.getProperties().get(SEPARATOR_MARK)) ? "---" : ActionIds.idOf(item));
        }
        assertThat(labels).containsExactly(PaneMenuSupport.FOCUS_LEFT_KEY, PaneMenuSupport.FOCUS_RIGHT_KEY,
            PaneMenuSupport.FOCUS_UP_KEY, PaneMenuSupport.FOCUS_DOWN_KEY, "---", PaneMenuSupport.NEXT_KEY,
            PaneMenuSupport.PREVIOUS_KEY, "---", PaneMenuSupport.BROADCAST_KEY).inOrder();
        assertThat(menu.menu().getText()).isEqualTo(I18n.get(PaneMenuSupport.MENU_KEY));
        for (PaneDirection direction : PaneDirection.values()) {
            assertThat(menu.focusItem(direction).getText()).isEqualTo(I18n.get(PaneMenuSupport.focusKey(direction)));
        }
    }

    @Test
    void everyItemRunsItsCommand() {
        FakeTab tab = new FakeTab();
        PaneMenuSupport.PaneMenu menu = PaneMenuSupport.create(tab, SEPARATORS);
        PaneMenuSupport.sync(menu, tab.state());

        for (PaneDirection direction : PaneDirection.values()) {
            assertThat(MenuItemActivation.activate(menu.focusItem(direction))).isTrue();
        }
        assertThat(MenuItemActivation.activate(menu.next())).isTrue();
        assertThat(MenuItemActivation.activate(menu.previous())).isTrue();

        assertThat(tab.log).containsExactly("focus LEFT", "focus RIGHT", "focus UP", "focus DOWN", "next", "previous")
            .inOrder();
    }

    @Test
    void itemsStayDisabledUntilATerminalTabWithSeveralPanesIsActive() {
        PaneMenuSupport.PaneMenu menu = PaneMenuSupport.create(new FakeTab(), SEPARATORS);
        assertWithMessage("built disabled, before the first sync").that(allDisabled(menu)).isTrue();

        PaneMenuSupport.sync(menu, PaneMenuSupport.State.NO_TERMINAL);
        assertThat(allDisabled(menu)).isTrue();
        assertThat(menu.broadcast().isSelected()).isFalse();

        PaneMenuSupport.sync(menu, new PaneMenuSupport.State(true, 1, false));
        assertWithMessage("one pane: nothing to move to and nothing to broadcast to").that(allDisabled(menu)).isTrue();

        PaneMenuSupport.sync(menu, new PaneMenuSupport.State(true, 3, false));
        for (MenuItem item : menu.focusItems().values()) {
            assertThat(item.isDisable()).isFalse();
        }
        assertThat(menu.next().isDisable()).isFalse();
        assertThat(menu.previous().isDisable()).isFalse();
        assertThat(menu.broadcast().isDisable()).isFalse();
    }

    @Test
    void broadcastLeftOnInAOnePaneTabCanStillBeSwitchedOff() {
        PaneMenuSupport.PaneMenu menu = PaneMenuSupport.create(new FakeTab(), SEPARATORS);

        PaneMenuSupport.sync(menu, new PaneMenuSupport.State(true, 1, true));

        assertThat(menu.broadcast().isDisable()).isFalse();
        assertThat(menu.broadcast().isSelected()).isTrue();
        assertThat(menu.next().isDisable()).isTrue();
    }

    @Test
    void theBroadcastItemFollowsTheTabNotItsOwnClick() {
        FakeTab tab = new FakeTab();
        PaneMenuSupport.PaneMenu menu = PaneMenuSupport.create(tab, SEPARATORS);
        PaneMenuSupport.sync(menu, tab.state());

        // A click flips the item, then fires it: the tab switches on, and the re-sync shows it.
        MenuItemActivation.activate(menu.broadcast());
        PaneMenuSupport.sync(menu, tab.state());
        assertThat(tab.log).containsExactly("broadcast on");
        assertThat(menu.broadcast().isSelected()).isTrue();

        // Another window's tab, or the context menu, switched broadcast off meanwhile: the stale check
        // mark is corrected while the menu opens, and the next click switches it on again, not off.
        tab.broadcast = false;
        PaneMenuSupport.sync(menu, tab.state());
        assertThat(menu.broadcast().isSelected()).isFalse();
        MenuItemActivation.activate(menu.broadcast());
        assertThat(tab.log).containsExactly("broadcast on", "broadcast on").inOrder();
    }

    @Test
    void aDisabledItemIsRefusedWhenItsMenuValidatesFirst() {
        FakeTab tab = new FakeTab();
        tab.paneCount = 1;
        PaneMenuSupport.PaneMenu menu = PaneMenuSupport.create(tab, SEPARATORS);
        // As MainWindow does: an accelerator, or the menu bar of a closed window, syncs before running.
        menu.menu().setOnMenuValidation(event -> PaneMenuSupport.sync(menu, tab.state()));
        PaneMenuSupport.sync(menu, new PaneMenuSupport.State(true, 4, false));

        assertThat(MenuItemActivation.activate(menu.focusItem(PaneDirection.LEFT))).isFalse();
        assertThat(tab.log).isEmpty();

        tab.paneCount = 2;
        assertThat(MenuItemActivation.activate(menu.focusItem(PaneDirection.LEFT))).isTrue();
        assertThat(tab.log).containsExactly("focus LEFT");
    }

    @Test
    void everyItemHasItsStableActionId() {
        PaneMenuSupport.PaneMenu menu = PaneMenuSupport.create(new FakeTab(), SEPARATORS);

        for (PaneDirection direction : PaneDirection.values()) {
            assertThat(ActionIds.idOf(menu.focusItem(direction))).isEqualTo(PaneMenuSupport.focusKey(direction));
        }
        assertThat(ActionIds.idOf(menu.next())).isEqualTo(PaneMenuSupport.NEXT_KEY);
        assertThat(ActionIds.idOf(menu.previous())).isEqualTo(PaneMenuSupport.PREVIOUS_KEY);
        assertThat(ActionIds.idOf(menu.broadcast())).isEqualTo(PaneMenuSupport.BROADCAST_KEY);
    }

    @Test
    void broadcastActsOnItsOwnWindowOnlyAndTheFocusItemsAreRouted() {
        PaneMenuSupport.PaneMenu menu = PaneMenuSupport.create(new FakeTab(), SEPARATORS);

        assertWithMessage("the menu bar of a closed window must not switch where another window's keys go")
            .that(ClosedWindowMenuRouter.needOf(menu.broadcast())).isEqualTo(WindowNeed.OWN_WINDOW);
        for (MenuItem item : menu.focusItems().values()) {
            assertThat(ClosedWindowMenuRouter.needOf(item)).isEqualTo(WindowNeed.ANY_WINDOW);
        }
        assertThat(ClosedWindowMenuRouter.needOf(menu.next())).isEqualTo(WindowNeed.ANY_WINDOW);
        assertThat(ClosedWindowMenuRouter.needOf(menu.previous())).isEqualTo(WindowNeed.ANY_WINDOW);
    }

    @Test
    void mainWindowBuildsViewPanesForBothMenuBarsAndSyncsItFromTheActiveTab() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");

        String viewMenu = methodBody(source, "private Menu createViewMenu(MenuBarTarget target) {");
        assertThat(viewMenu).contains("Menu panesMenu = createPanesMenu(target);");
        assertThat(viewMenu).contains("panesMenu, highlightingMenu, terminalEffectMenu");

        String panes = methodBody(source, "private Menu createPanesMenu(MenuBarTarget target) {");
        assertThat(panes).contains("panes.menu().setOnShowing(event -> syncPaneMenuItems());");
        assertThat(panes).contains("panes.menu().setOnMenuValidation(event -> syncPaneMenuItems());");
        assertThat(panes).contains("paneMenu = panes;");
        assertThat(panes).contains("systemPaneMenu = panes;");

        String sync = methodBody(source, "private void syncPaneMenuItems() {");
        assertThat(sync).contains("PaneMenuSupport.sync(paneMenu, state);");
        assertThat(sync).contains("PaneMenuSupport.sync(systemPaneMenu, state);");
        assertWithMessage("a pane split or closed changes what View → Panes can do")
            .that(methodBody(source, "private void onTerminalWidgetSetChanged(TerminalTab terminalTab) {"))
            .contains("syncPaneMenuItems();");

        String toggle = methodBody(source, "private void toggleBroadcastInActiveTerminal() {");
        assertThat(toggle).contains("boolean on = view.isBroadcastMode();");
        assertThat(toggle).doesNotContain("isSelected()");
    }

    private static boolean allDisabled(PaneMenuSupport.PaneMenu menu) {
        for (MenuItem item : menu.focusItems().values()) {
            if (!item.isDisable()) {
                return false;
            }
        }
        return menu.next().isDisable() && menu.previous().isDisable() && menu.broadcast().isDisable();
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
