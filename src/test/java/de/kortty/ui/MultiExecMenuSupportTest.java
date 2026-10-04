package de.kortty.ui;

import de.kortty.ui.ClosedWindowMenuRouter.WindowNeed;
import de.kortty.ui.actions.ActionIds;
import de.kortty.ui.actions.MenuItemActivation;
import javafx.scene.control.MenuItem;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * View → Multi-exec: Include This Pane and Include All Panes of This Tab as check items that show
 * the members, Include All Terminals of This Window, and Stop Multi-exec. The include items need a
 * terminal tab and stay in their own window; Stop needs a member and no window. The check items'
 * actions decide from the members, never from the item JavaFX has already flipped. Also the texts of
 * the tab marker and the status-bar chip. No JavaFX toolkit is started; separators are plain marker
 * items here, since a SeparatorMenuItem holds a control whose static initializer needs the toolkit.
 */
class MultiExecMenuSupportTest {

    private static final String SEPARATOR_MARK = "test.separator";

    private static final Supplier<MenuItem> SEPARATORS = () -> {
        MenuItem separator = new MenuItem();
        separator.getProperties().put(SEPARATOR_MARK, Boolean.TRUE);
        return separator;
    };

    /** The members as the menu's commands see them: one window with a two-pane tab. */
    private static final class FakeWindow implements MultiExecMenuSupport.Commands {
        final List<String> log = new ArrayList<>();
        boolean paneIncluded;
        boolean tabIncluded;
        int members;

        @Override
        public void togglePane() {
            paneIncluded = !paneIncluded;
            members += paneIncluded ? 1 : -1;
            log.add("pane " + (paneIncluded ? "in" : "out"));
        }

        @Override
        public void toggleTab() {
            tabIncluded = !tabIncluded;
            paneIncluded = tabIncluded;
            members = tabIncluded ? 2 : 0;
            log.add("tab " + (tabIncluded ? "in" : "out"));
        }

        @Override
        public void includeWindow() {
            tabIncluded = true;
            paneIncluded = true;
            members = 2;
            log.add("window in");
        }

        @Override
        public void stop() {
            tabIncluded = false;
            paneIncluded = false;
            members = 0;
            log.add("stop");
        }

        MultiExecMenuSupport.State state() {
            return new MultiExecMenuSupport.State(true, paneIncluded, tabIncluded, true, members);
        }
    }

    @Test
    void theMenuListsTheIncludeItemsThenStop() {
        MultiExecMenuSupport.MultiExecMenu menu = MultiExecMenuSupport.create(new FakeWindow(), SEPARATORS);

        List<String> ids = new ArrayList<>();
        for (MenuItem item : menu.menu().getItems()) {
            ids.add(Boolean.TRUE.equals(item.getProperties().get(SEPARATOR_MARK)) ? "---" : ActionIds.idOf(item));
        }
        assertThat(ids).containsExactly(MultiExecMenuSupport.INCLUDE_PANE_KEY, MultiExecMenuSupport.INCLUDE_TAB_KEY,
            MultiExecMenuSupport.INCLUDE_WINDOW_KEY, "---", MultiExecMenuSupport.STOP_KEY).inOrder();
        assertThat(menu.menu().getText()).isEqualTo(I18n.get(MultiExecMenuSupport.MENU_KEY));
        assertThat(menu.includePane().getText()).isEqualTo(I18n.get(MultiExecMenuSupport.INCLUDE_PANE_KEY));
        assertThat(menu.includeTab().getText()).isEqualTo(I18n.get(MultiExecMenuSupport.INCLUDE_TAB_KEY));
        assertThat(menu.includeWindow().getText()).isEqualTo(I18n.get(MultiExecMenuSupport.INCLUDE_WINDOW_KEY));
        assertThat(menu.stop().getText()).isEqualTo(I18n.get(MultiExecMenuSupport.STOP_KEY));
        assertWithMessage("the labels never change, so the action registry can harvest them")
            .that(ActionIds.isExcluded(menu.menu())).isFalse();
    }

    @Test
    void everythingIsDisabledUntilTheFirstSync() {
        MultiExecMenuSupport.MultiExecMenu menu = MultiExecMenuSupport.create(new FakeWindow(), SEPARATORS);

        for (MenuItem item : List.of(menu.includePane(), menu.includeTab(), menu.includeWindow(), menu.stop())) {
            assertThat(item.isDisable()).isTrue();
        }
        assertThat(menu.includePane().isSelected()).isFalse();
    }

    @Test
    void theIncludeItemsNeedATerminalTabAndStopNeedsAMember() {
        MultiExecMenuSupport.MultiExecMenu menu = MultiExecMenuSupport.create(new FakeWindow(), SEPARATORS);

        MultiExecMenuSupport.sync(menu, new MultiExecMenuSupport.State(false, false, false, true, 3));
        assertWithMessage("a pane of another window takes part: it can be stopped from here")
            .that(menu.stop().isDisable()).isFalse();
        assertThat(menu.includePane().isDisable()).isTrue();
        assertThat(menu.includeTab().isDisable()).isTrue();
        assertWithMessage("the window has a terminal tab, though not the active one")
            .that(menu.includeWindow().isDisable()).isFalse();

        MultiExecMenuSupport.sync(menu, new MultiExecMenuSupport.State(true, false, false, true, 0));
        assertThat(menu.includePane().isDisable()).isFalse();
        assertThat(menu.includeTab().isDisable()).isFalse();
        assertThat(menu.stop().isDisable()).isTrue();
    }

    @Test
    void theCheckItemsFollowTheMembersNotTheirOwnClick() {
        FakeWindow window = new FakeWindow();
        MultiExecMenuSupport.MultiExecMenu menu = MultiExecMenuSupport.create(window, SEPARATORS);
        MultiExecMenuSupport.sync(menu, window.state());

        assertThat(MenuItemActivation.activate(menu.includePane())).isTrue();
        MultiExecMenuSupport.sync(menu, window.state());
        assertThat(menu.includePane().isSelected()).isTrue();
        assertThat(menu.includeTab().isSelected()).isFalse();

        assertThat(MenuItemActivation.activate(menu.includeTab())).isTrue();
        MultiExecMenuSupport.sync(menu, window.state());
        assertThat(menu.includeTab().isSelected()).isTrue();

        assertThat(MenuItemActivation.activate(menu.stop())).isTrue();
        MultiExecMenuSupport.sync(menu, window.state());
        assertThat(menu.includePane().isSelected()).isFalse();
        assertThat(menu.includeTab().isSelected()).isFalse();
        assertThat(menu.stop().isDisable()).isTrue();

        assertThat(MenuItemActivation.activate(menu.includeWindow())).isTrue();
        assertThat(window.log).containsExactly("pane in", "tab in", "stop", "window in").inOrder();
    }

    @Test
    void theIncludeItemsStayInTheirWindowAndStopNeedsNone() {
        MultiExecMenuSupport.MultiExecMenu menu = MultiExecMenuSupport.create(new FakeWindow(), SEPARATORS);

        for (MenuItem item : List.of(menu.includePane(), menu.includeTab(), menu.includeWindow())) {
            assertWithMessage("%s decides where typed keys go", item.getText())
                .that(ClosedWindowMenuRouter.needOf(item)).isEqualTo(WindowNeed.OWN_WINDOW);
        }
        assertWithMessage("Stop takes every pane of every window out, from any menu bar")
            .that(ClosedWindowMenuRouter.needOf(menu.stop())).isEqualTo(WindowNeed.NO_WINDOW);
    }

    @Test
    void theTabMarkerNamesMultiExecAndBroadcastAndIsAbsentWithoutEither() {
        assertThat(MultiExecMarkers.tabMarkerText(0, 3, false)).isNull();
        assertWithMessage("broadcast mode in a one-pane tab reaches no other pane")
            .that(MultiExecMarkers.tabMarkerText(0, 1, true)).isNull();
        assertThat(MultiExecMarkers.tabMarkerText(2, 3, false))
            .isEqualTo(I18n.get(MultiExecMarkers.TAB_MULTI_EXEC_KEY, 2, 3));
        assertThat(MultiExecMarkers.tabMarkerText(0, 3, true))
            .isEqualTo(I18n.get(MultiExecMarkers.TAB_BROADCAST_KEY, 3));
        assertThat(MultiExecMarkers.tabMarkerText(1, 2, true)).isEqualTo(
            I18n.get(MultiExecMarkers.TAB_MULTI_EXEC_KEY, 1, 2) + "\n" + I18n.get(MultiExecMarkers.TAB_BROADCAST_KEY, 2));
    }

    @Test
    void theStatusTextCountsPanesTabsAndWindowsAndTheLeftOutOnesOnlyWhenThereAreAny() {
        MultiExecMembership.Counts counts = new MultiExecMembership.Counts(4, 3, 2);

        assertThat(MultiExecMarkers.statusText(counts, 0))
            .isEqualTo(I18n.get(MultiExecMarkers.STATUS_ACTIVE_KEY, 4, 3, 2));
        assertThat(MultiExecMarkers.statusText(counts, 1)).isEqualTo(I18n.get(MultiExecMarkers.STATUS_ACTIVE_KEY, 4, 3, 2)
            + MultiExecMarkers.SEPARATOR + I18n.get(MultiExecMarkers.STATUS_SKIPPED_KEY, 1));
    }

    @Test
    void aPolicyThatDeniesMultiExecLocksTheIncludeItemsForGoodButLeavesStop() {
        FakeWindow window = new FakeWindow();
        MultiExecMenuSupport.MultiExecMenu menu = MultiExecMenuSupport.create(window, SEPARATORS);

        MultiExecMenuSupport.lockByPolicy(menu);
        // A sync that would enable everything: a terminal tab, a window with terminals, a member.
        MultiExecMenuSupport.sync(menu, new MultiExecMenuSupport.State(true, false, false, true, 1));

        for (MenuItem item : List.of(menu.includePane(), menu.includeTab(), menu.includeWindow())) {
            assertWithMessage("%s stays disabled", ActionIds.idOf(item)).that(item.isDisable()).isTrue();
            assertWithMessage("%s says the policy locked it, for the palette", ActionIds.idOf(item))
                .that(ActionIds.isPolicyLocked(item)).isTrue();
            assertWithMessage("%s cannot run from a shortcut or the palette", ActionIds.idOf(item))
                .that(MenuItemActivation.activate(item)).isFalse();
        }
        assertThat(window.log).isEmpty();
        assertWithMessage("Stop takes out what took part before, policy or not")
            .that(menu.stop().isDisable()).isFalse();
        assertThat(ActionIds.isPolicyLocked(menu.stop())).isFalse();
        assertThat(MenuItemActivation.activate(menu.stop())).isTrue();
        assertThat(window.log).containsExactly("stop");

        MultiExecMenuSupport.lockByPolicy(null);
    }
}
