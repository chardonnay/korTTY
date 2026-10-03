package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

/**
 * The set of panes multi-exec mirrors between, on plain stand-ins for panes: toggling a pane,
 * including and removing a tab's or a window's panes, a pane leaving when it closes, stopping, the
 * counts the status bar shows, the order mirrored keys follow, and the listeners being told exactly
 * once per change.
 */
class MultiExecMembershipTest {

    /** A pane stand-in that is equal to every other one, so only identity can tell them apart. */
    private static final class Pane {
        private final String name;

        Pane(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Pane;
        }

        @Override
        public int hashCode() {
            return 1;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final Pane a1 = new Pane("a1");
    private final Pane a2 = new Pane("a2");
    private final Pane b1 = new Pane("b1");
    private final Pane c1 = new Pane("c1");

    @Test
    void togglingAPaneLetsItJoinAndLeave() {
        MultiExecMembership<Pane> members = new MultiExecMembership<>();
        AtomicInteger changes = count(members);

        assertThat(members.toggle(a1)).isTrue();
        assertThat(members.contains(a1)).isTrue();
        assertThat(members.toggle(a1)).isFalse();
        assertThat(members.contains(a1)).isFalse();
        assertThat(members.isEmpty()).isTrue();
        assertThat(changes.get()).isEqualTo(2);
        assertWithMessage("no pane, no change").that(members.toggle(null)).isFalse();
        assertThat(changes.get()).isEqualTo(2);
    }

    @Test
    void panesAreToldApartByReferenceNeverByEquals() {
        MultiExecMembership<Pane> members = new MultiExecMembership<>();
        members.toggle(a1);

        assertWithMessage("an equal but different pane is not a member").that(members.contains(a2)).isFalse();
        members.toggle(a2);
        assertThat(members.size()).isEqualTo(2);
        assertThat(members.members()).containsExactly(a1, a2).inOrder();
    }

    @Test
    void includingATabOrAWindowTellsTheListenersOnceAndKeepsTheJoinOrder() {
        MultiExecMembership<Pane> members = new MultiExecMembership<>();
        AtomicInteger changes = count(members);
        members.toggle(b1);

        assertThat(members.setAll(List.of(a1, a2, b1), true)).isTrue();
        assertWithMessage("one call for the toggle, one for the tab").that(changes.get()).isEqualTo(2);
        assertWithMessage("a pane that was in keeps its place").that(members.members())
            .containsExactly(b1, a1, a2).inOrder();
        assertThat(members.othersBesides(a1)).containsExactly(b1, a2).inOrder();
        assertThat(members.othersBesides(c1)).containsExactly(b1, a1, a2).inOrder();

        assertWithMessage("nothing changed, nobody is told").that(members.setAll(List.of(a1, a2), true)).isFalse();
        assertThat(changes.get()).isEqualTo(2);
    }

    @Test
    void removingATabTakesOnlyItsPanesOut() {
        MultiExecMembership<Pane> members = new MultiExecMembership<>();
        members.setAll(List.of(a1, a2, b1), true);
        AtomicInteger changes = count(members);

        assertThat(members.setAll(List.of(a1, a2, c1), false)).isTrue();
        assertThat(members.members()).containsExactly(b1);
        assertThat(changes.get()).isEqualTo(1);
    }

    @Test
    void aClosedPaneLeavesAndANonMemberClosingChangesNothing() {
        MultiExecMembership<Pane> members = new MultiExecMembership<>();
        members.setAll(List.of(a1, b1), true);
        AtomicInteger changes = count(members);

        assertThat(members.remove(c1)).isFalse();
        assertThat(changes.get()).isEqualTo(0);
        assertThat(members.remove(a1)).isTrue();
        assertThat(members.members()).containsExactly(b1);
        assertThat(changes.get()).isEqualTo(1);
        assertThat(members.remove(null)).isFalse();
    }

    @Test
    void stopTakesEveryPaneOutOnceAndAgainChangesNothing() {
        MultiExecMembership<Pane> members = new MultiExecMembership<>();
        members.setAll(List.of(a1, a2, b1), true);
        AtomicInteger changes = count(members);

        assertThat(members.clear()).isTrue();
        assertThat(members.isEmpty()).isTrue();
        assertThat(members.clear()).isFalse();
        assertThat(changes.get()).isEqualTo(1);
    }

    @Test
    void aTabIsIncludedOnlyWhenEveryOneOfItsPanesIs() {
        MultiExecMembership<Pane> members = new MultiExecMembership<>();
        members.toggle(a1);

        assertThat(members.includesAll(List.of(a1, a2))).isFalse();
        assertThat(members.countIn(List.of(a1, a2))).isEqualTo(1);
        members.toggle(a2);
        assertThat(members.includesAll(List.of(a1, a2))).isTrue();
        assertWithMessage("a pane listed twice counts once").that(members.countIn(List.of(a1, a1, a2)))
            .isEqualTo(2);
        assertWithMessage("a tab without panes is not included").that(members.includesAll(List.of())).isFalse();
    }

    @Test
    void theCountsNameDifferentTabsAndWindowsAndSkipUnknownOnes() {
        MultiExecMembership<Pane> members = new MultiExecMembership<>();
        assertThat(members.counts(pane -> "tab", pane -> "window")).isEqualTo(MultiExecMembership.Counts.NONE);

        Object tabA = new Object();
        Object tabB = new Object();
        Object window1 = new Object();
        Object window2 = new Object();
        Map<Pane, Object> tabs = new java.util.IdentityHashMap<>();
        tabs.put(a1, tabA);
        tabs.put(a2, tabA);
        tabs.put(b1, tabB);
        Map<Object, Object> windows = new java.util.IdentityHashMap<>();
        windows.put(tabA, window1);
        windows.put(tabB, window2);
        members.setAll(List.of(a1, a2, b1, c1), true);

        MultiExecMembership.Counts counts = members.counts(tabs::get, pane -> {
            Object tab = tabs.get(pane);
            return tab != null ? windows.get(tab) : null;
        });

        assertThat(counts.panes()).isEqualTo(4);
        assertWithMessage("c1 has no known tab").that(counts.tabs()).isEqualTo(2);
        assertThat(counts.windows()).isEqualTo(2);
    }

    @Test
    void aFailingListenerDoesNotKeepTheOthersFromBeingToldAndAClosedHandleIsNotToldAgain() throws Exception {
        MultiExecMembership<Pane> members = new MultiExecMembership<>();
        List<String> told = new ArrayList<>();
        members.addListener(() -> {
            throw new IllegalStateException("broken window");
        });
        AutoCloseable handle = members.addListener(() -> told.add("second"));

        members.toggle(a1);
        assertThat(told).containsExactly("second");

        handle.close();
        members.toggle(a1);
        assertThat(told).containsExactly("second");
    }

    @Test
    void fireChangedTellsTheListenersWithoutAChange() {
        MultiExecMembership<Pane> members = new MultiExecMembership<>();
        AtomicInteger changes = count(members);

        members.fireChanged();

        assertThat(changes.get()).isEqualTo(1);
        assertThat(members.isEmpty()).isTrue();
    }

    @Test
    void theMembersAreACopy() {
        MultiExecMembership<Pane> members = new MultiExecMembership<>();
        members.toggle(a1);
        List<Pane> others = members.othersBesides(b1);

        others.clear();

        assertThat(members.members()).containsExactly(a1);
    }

    private static AtomicInteger count(MultiExecMembership<?> members) {
        AtomicInteger changes = new AtomicInteger();
        members.addListener(changes::incrementAndGet);
        return changes;
    }
}
