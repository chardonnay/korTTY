package com.sithtermfx.ui.split;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The panes a key typed in a pane goes to, in broadcast mode (the tab's panes) and through an input
 * mirror (multi-exec: members in other tabs and windows), on plain pane objects. No JavaFX toolkit
 * is started.
 */
public class BroadcastTargetsTest {

    /** A pane; two panes with the same name are equal but never the same pane. */
    private static final class Pane {
        final String name;

        Pane(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Pane pane && pane.name.equals(name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final Predicate<Pane> EVERY_PANE = pane -> true;

    private final Pane left = new Pane("left");
    private final Pane right = new Pane("right");
    private final Pane bottom = new Pane("bottom");
    private final Pane otherTab = new Pane("other tab");
    private final Pane otherWindow = new Pane("other window");

    @Test
    public void broadcastAloneReachesTheTabsOtherPanesInOrder() {
        assertThat(BroadcastTargets.resolve(right, List.of(left, right, bottom), List.of(), EVERY_PANE, EVERY_PANE))
            .containsExactly(left, bottom).inOrder();
    }

    @Test
    public void aMirrorAloneReachesItsOtherMembersInItsOrder() {
        assertThat(BroadcastTargets.resolve(left, List.of(), List.of(otherWindow, otherTab, right),
            EVERY_PANE, EVERY_PANE))
            .containsExactly(otherWindow, otherTab, right).inOrder();
    }

    @Test
    public void broadcastAndMirrorTogetherReachEachPaneOnceTheTabsPanesFirst() {
        // "right" is in the tab and a member as well: it gets the key once, at its place in the tab.
        assertThat(BroadcastTargets.resolve(left, List.of(left, right, bottom), List.of(otherTab, right, otherWindow),
            EVERY_PANE, EVERY_PANE))
            .containsExactly(right, bottom, otherTab, otherWindow).inOrder();
    }

    @Test
    public void panesAreToldApartByReferenceNotByEquals() {
        Pane twin = new Pane("left");
        assertThat(twin).isEqualTo(left);

        List<Pane> targets = BroadcastTargets.resolve(left, List.of(left, right), List.of(twin, right),
            EVERY_PANE, EVERY_PANE);

        assertThat(targets).hasSize(2);
        assertWithMessage("an equal pane that is not the source still gets the key")
            .that(targets.get(1)).isSameInstanceAs(twin);
        assertThat(targets.get(0)).isSameInstanceAs(right);
    }

    @Test
    public void theSourceNeverGetsItsOwnKeyEvenWhenTheMirrorListsIt() {
        assertThat(BroadcastTargets.resolve(left, List.of(left, right), List.of(left, otherTab), EVERY_PANE, EVERY_PANE))
            .containsExactly(right, otherTab).inOrder();
    }

    @Test
    public void disconnectedAndGuardedPanesAreSkippedInEitherList() {
        Set<Pane> disconnected = Set.of(bottom, otherWindow);
        Set<Pane> held = Set.of(right, otherTab);
        Pane member = new Pane("member");

        List<Pane> targets = BroadcastTargets.resolve(left, List.of(left, right, bottom),
            List.of(otherTab, otherWindow, member),
            pane -> !disconnected.contains(pane), pane -> !held.contains(pane));

        assertThat(targets).containsExactly(member);
    }

    @Test
    public void aPaneSkippedInTheTabIsNotTakenFromTheMirrorList() {
        List<Pane> asked = new ArrayList<>();

        List<Pane> targets = BroadcastTargets.resolve(left, List.of(left, right), List.of(right),
            EVERY_PANE, pane -> {
                asked.add(pane);
                return pane != right;
            });

        assertThat(targets).isEmpty();
        assertWithMessage("each pane is judged once").that(asked).containsExactly(right);
    }

    @Test
    public void noBroadcastAndNoMirrorMeansNoTarget() {
        assertThat(BroadcastTargets.resolve(left, List.of(), List.of(), EVERY_PANE, EVERY_PANE)).isEmpty();
        assertThat(BroadcastTargets.resolve(left, List.of(left), List.of(), EVERY_PANE, EVERY_PANE)).isEmpty();
        assertThat(BroadcastTargets.<Pane>resolve(null, List.of(), List.of(), EVERY_PANE, EVERY_PANE)).isEmpty();
    }

    @Test
    public void theOrderIsStableFromKeyToKey() {
        List<Pane> tab = List.of(left, right, bottom);
        List<Pane> members = List.of(otherWindow, otherTab);

        List<Pane> first = BroadcastTargets.resolve(left, tab, members, EVERY_PANE, EVERY_PANE);
        for (int key = 0; key < 5; key++) {
            assertThat(BroadcastTargets.resolve(left, tab, members, EVERY_PANE, EVERY_PANE))
                .containsExactlyElementsIn(first).inOrder();
        }
    }

    @Test
    public void theKeyRuleIsAskedOncePerKeyAndKeepsTheOrder() {
        AtomicInteger asked = new AtomicInteger();
        List<Pane> targets = List.of(right, bottom, otherTab);

        List<Pane> receivers = BroadcastTargets.admit(targets, () -> {
            asked.incrementAndGet();
            return pane -> pane != bottom;
        });

        assertThat(receivers).containsExactly(right, otherTab).inOrder();
        assertThat(asked.get()).isEqualTo(1);
    }

    @Test
    public void theKeyRuleIsNotAskedWhenThereIsNoPaneToSendTo() {
        AtomicInteger asked = new AtomicInteger();

        List<Pane> receivers = BroadcastTargets.admit(List.<Pane>of(), () -> {
            asked.incrementAndGet();
            return EVERY_PANE;
        });

        assertThat(receivers).isEmpty();
        assertThat(asked.get()).isEqualTo(0);
    }

    @Test
    public void aKeyRuleThatRefusesEveryPaneLeavesNoReceiver() {
        assertThat(BroadcastTargets.admit(List.of(right, otherTab), () -> pane -> false)).isEmpty();
    }

    @Test
    public void onlyConnectedPanesTheGuardHoldsAreCountedAsLeftOutEachOnce() {
        Pane agent = new Pane("agent");
        Pane closed = new Pane("closed");
        Pane pacing = new Pane("pacing");
        List<Pane> panes = List.of(left, agent, closed, pacing, right, agent);
        Predicate<Pane> connected = pane -> pane != closed;
        Predicate<Pane> guard = pane -> pane != agent && pane != pacing && pane != closed;

        assertThat(BroadcastTargets.countHeld(panes, connected, guard)).isEqualTo(2);
        assertThat(BroadcastTargets.countHeld(panes, connected, EVERY_PANE)).isEqualTo(0);
        assertThat(BroadcastTargets.countHeld(panes, pane -> false, pane -> false)).isEqualTo(0);
        assertThat(BroadcastTargets.countHeld(List.<Pane>of(), connected, guard)).isEqualTo(0);
    }

    /**
     * A navigation key bound to an action of the pane, such as the scrollback keys and, later, the
     * keys that jump between shell prompts, stays in that pane while the action can run. While it
     * cannot (no prompt marks, nothing to scroll) or on the alternate screen of vim or less, the key
     * goes to the program and is mirrored to every target, so a prompt-navigation chord such as
     * Ctrl+Shift+Up then reaches the other panes as ESC[1;6A.
     */
    @Test
    public void aKeyMirrorsOnlyWhenItsPaneActionCannotRun() {
        assertThat(BroadcastTargets.routeOf(false, () -> true)).isEqualTo(BroadcastTargets.KeyRoute.LOCAL_ACTION);
        assertThat(BroadcastTargets.routeOf(false, () -> false)).isEqualTo(BroadcastTargets.KeyRoute.SEND_AND_MIRROR);
        assertThat(BroadcastTargets.routeOf(false, null)).isEqualTo(BroadcastTargets.KeyRoute.SEND_AND_MIRROR);
    }

    @Test
    public void onTheAlternateScreenEveryNavigationKeyGoesToTheProgramWithoutAskingTheAction() {
        AtomicInteger asked = new AtomicInteger();

        BroadcastTargets.KeyRoute route = BroadcastTargets.routeOf(true, () -> {
            asked.incrementAndGet();
            return true;
        });

        assertThat(route).isEqualTo(BroadcastTargets.KeyRoute.SEND_AND_MIRROR);
        assertThat(asked.get()).isEqualTo(0);
    }
}
