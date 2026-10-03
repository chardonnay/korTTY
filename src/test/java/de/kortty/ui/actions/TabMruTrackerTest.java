package de.kortty.ui.actions;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/** The most-recently-used order of a window's tabs, behind the command palette's tab rows. */
class TabMruTrackerTest {

    @Test
    void touchedTabsComeFirstTheNewestFirst() {
        TabMruTracker<String> tracker = new TabMruTracker<>();

        tracker.touch("a");
        tracker.touch("c");
        tracker.touch("b");

        assertThat(tracker.order(List.of("a", "b", "c"))).containsExactly("b", "c", "a").inOrder();
    }

    @Test
    void touchingATabAgainMovesItToTheFront() {
        TabMruTracker<String> tracker = new TabMruTracker<>();
        tracker.touch("a");
        tracker.touch("b");
        tracker.touch("c");

        tracker.touch("a");

        assertThat(tracker.recent()).containsExactly("a", "c", "b").inOrder();
    }

    @Test
    void tabsNeverUsedFollowInTheirTabBarOrder() {
        TabMruTracker<String> tracker = new TabMruTracker<>();
        tracker.touch("c");

        assertThat(tracker.order(List.of("a", "b", "c", "d"))).containsExactly("c", "a", "b", "d").inOrder();
    }

    @Test
    void aRemovedTabIsForgotten() {
        TabMruTracker<String> tracker = new TabMruTracker<>();
        tracker.touch("a");
        tracker.touch("b");

        tracker.remove("b");
        tracker.remove("missing");
        tracker.remove(null);

        assertThat(tracker.recent()).containsExactly("a");
        // Opened again later, it counts as never used until it is selected.
        assertThat(tracker.order(List.of("b", "a"))).containsExactly("a", "b").inOrder();
    }

    @Test
    void retainOnlyPrunesTheTabsThatAreGone() {
        TabMruTracker<String> tracker = new TabMruTracker<>();
        tracker.touch("a");
        tracker.touch("b");
        tracker.touch("c");

        tracker.retainOnly(List.of("a", "c"));

        assertThat(tracker.recent()).containsExactly("c", "a").inOrder();
    }

    @Test
    void theOrderListsOnlyOpenTabsAndEachOnce() {
        TabMruTracker<String> tracker = new TabMruTracker<>();
        tracker.touch("closed");
        tracker.touch("a");

        assertThat(tracker.order(List.of("b", "a", "b"))).containsExactly("a", "b").inOrder();
        assertThat(tracker.order(List.of())).isEmpty();
    }

    @Test
    void aBulkReorderKeepsTheOrderWhenTheSelectionChurnIsIgnored() {
        // What MainWindow.reorganizeTabs does: no touches while the tabs are removed and re-added,
        // then prune and touch the tab the window shows.
        TabMruTracker<String> tracker = new TabMruTracker<>();
        tracker.touch("a");
        tracker.touch("c");
        tracker.touch("b");

        tracker.retainOnly(List.of("c", "a", "b"));
        tracker.touch("b");

        assertThat(tracker.order(List.of("c", "a", "b"))).containsExactly("b", "c", "a").inOrder();
    }

    @Test
    void tabsBeyondTheCapacityCountAsNeverUsed() {
        TabMruTracker<Integer> tracker = new TabMruTracker<>();
        List<Integer> open = new java.util.ArrayList<>();
        for (int i = 0; i <= TabMruTracker.CAPACITY; i++) {
            open.add(i);
            tracker.touch(i);
        }

        List<Integer> order = tracker.order(open);

        assertThat(order).hasSize(open.size());
        assertThat(order.get(0)).isEqualTo(TabMruTracker.CAPACITY);
        // Tab 0 was used longest ago and fell out; it follows the remembered tabs.
        assertThat(order.get(order.size() - 1)).isEqualTo(0);
    }

    @Test
    void aTabCannotBeNull() {
        assertThrows(NullPointerException.class, () -> new TabMruTracker<String>().touch(null));
    }
}
