package de.kortty.ui.actions;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * The most-recently-used order of a window's tabs, behind the command palette's tab rows and, with
 * the Window setting on, Ctrl+Tab's cycle through the tabs.
 */
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

    @Test
    void ctrlTabOnceGoesBackToThePreviouslyUsedTab() {
        TabMruTracker<String> tracker = used("a", "b", "c");

        String next = tracker.advance(List.of("a", "b", "c"), "c", false);
        tracker.commit(next);

        assertThat(next).isEqualTo("b");
        // Pressed again after releasing Ctrl, it toggles between the two most recent tabs.
        assertThat(tracker.advance(List.of("a", "b", "c"), "b", false)).isEqualTo("c");
    }

    @Test
    void holdingCtrlStepsFurtherBackAndWrapsAround() {
        TabMruTracker<String> tracker = used("a", "b", "c", "d");
        List<String> open = List.of("a", "b", "c", "d");

        assertThat(tracker.advance(open, "d", false)).isEqualTo("c");
        assertThat(tracker.advance(open, "c", false)).isEqualTo("b");
        assertThat(tracker.advance(open, "b", false)).isEqualTo("a");
        assertThat(tracker.advance(open, "a", false)).isEqualTo("d");
        assertThat(tracker.isCycling()).isTrue();
    }

    @Test
    void theTabsPassedOverDoNotCountOnlyTheOneStoppedAt() {
        TabMruTracker<String> tracker = used("a", "b", "c", "d");
        List<String> open = List.of("a", "b", "c", "d");

        tracker.advance(open, "d", false);
        String stoppedAt = tracker.advance(open, "c", false);
        // The order the cycle steps through stays as it was until the cycle ends.
        assertThat(tracker.recent()).containsExactly("d", "c", "b", "a").inOrder();

        tracker.commit(stoppedAt);

        assertThat(tracker.isCycling()).isFalse();
        assertThat(tracker.recent()).containsExactly("b", "d", "c", "a").inOrder();
    }

    @Test
    void ctrlShiftTabStartsAtTheTabUsedLongestAgoAndStepsTowardsTheRecentOnes() {
        TabMruTracker<String> tracker = used("a", "b", "c", "d");
        List<String> open = List.of("a", "b", "c", "d");

        assertThat(tracker.advance(open, "d", true)).isEqualTo("a");
        assertThat(tracker.advance(open, "a", true)).isEqualTo("b");
        // Shift released while Ctrl stays down: forward again from where the cycle is.
        assertThat(tracker.advance(open, "b", false)).isEqualTo("a");
    }

    @Test
    void theCycleStartsAtTheSelectedTabEvenIfItWasNotTheLastOneTouched() {
        TabMruTracker<String> tracker = used("a", "b", "c");

        assertThat(tracker.advance(List.of("a", "b", "c"), "a", false)).isEqualTo("c");
    }

    @Test
    void neverUsedTabsAreReachedAfterTheUsedOnesInTabBarOrder() {
        TabMruTracker<String> tracker = used("b", "d");
        List<String> open = List.of("a", "b", "c", "d");

        assertThat(tracker.advance(open, "d", false)).isEqualTo("b");
        assertThat(tracker.advance(open, "b", false)).isEqualTo("a");
        assertThat(tracker.advance(open, "a", false)).isEqualTo("c");
    }

    @Test
    void aTabThatClosedDuringTheCycleIsSkipped() {
        TabMruTracker<String> tracker = used("a", "b", "c", "d");

        assertThat(tracker.advance(List.of("a", "b", "c", "d"), "d", false)).isEqualTo("c");
        tracker.remove("b");

        assertThat(tracker.advance(List.of("a", "c", "d"), "c", false)).isEqualTo("a");
        assertThat(tracker.advance(List.of("a", "c", "d"), "a", true)).isEqualTo("c");
    }

    @Test
    void aTabOpenedDuringTheCycleIsNotReachedUntilTheNextCycle() {
        TabMruTracker<String> tracker = used("a", "b");

        assertThat(tracker.advance(List.of("a", "b"), "b", false)).isEqualTo("a");
        assertThat(tracker.advance(List.of("a", "b", "new"), "a", false)).isEqualTo("b");

        tracker.commit("b");
        assertThat(tracker.advance(List.of("a", "b", "new"), "b", true)).isEqualTo("new");
    }

    @Test
    void whenEveryTabOfTheCycleClosedANewCycleStarts() {
        TabMruTracker<String> tracker = used("a", "b");
        tracker.advance(List.of("a", "b"), "b", false);

        assertThat(tracker.advance(List.of("x", "y"), "x", false)).isEqualTo("y");
        assertThat(tracker.isCycling()).isTrue();
    }

    @Test
    void aSingleTabCyclesToItself() {
        TabMruTracker<String> tracker = used("only");

        assertThat(tracker.advance(List.of("only"), "only", false)).isEqualTo("only");
        assertThat(tracker.advance(List.of("only"), "only", true)).isEqualTo("only");
    }

    @Test
    void noOpenTabStartsNoCycle() {
        TabMruTracker<String> tracker = used("a");

        assertThat(tracker.advance(List.of(), null, false)).isNull();
        assertThat(tracker.isCycling()).isFalse();
    }

    @Test
    void commitWithoutACycleOnlyTouchesTheTab() {
        TabMruTracker<String> tracker = used("a", "b");

        tracker.commit("a");
        tracker.commit(null);

        assertThat(tracker.isCycling()).isFalse();
        assertThat(tracker.recent()).containsExactly("a", "b").inOrder();
    }

    @Test
    void aCommittedCycleStartsTheNextOneFromTheNewOrder() {
        TabMruTracker<String> tracker = used("a", "b", "c");
        List<String> open = List.of("a", "b", "c");

        tracker.advance(open, "c", false);
        tracker.commit(tracker.advance(open, "b", false));

        assertThat(tracker.advance(open, "a", false)).isEqualTo("c");
    }

    /** A tracker whose tabs were selected in this order, so the last one is the most recent. */
    private static TabMruTracker<String> used(String... tabs) {
        TabMruTracker<String> tracker = new TabMruTracker<>();
        for (String tab : tabs) {
            tracker.touch(tab);
        }
        return tracker;
    }
}
