package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class CodingAgentNavigatorTest {

    private static final PaneRef W1_T1_P1 = new PaneRef("tab-a", "terminal-1");
    private static final PaneRef W1_T1_P2 = new PaneRef("tab-a", "terminal-2");
    private static final PaneRef W1_T2_P1 = new PaneRef("tab-b", "terminal-3");
    private static final PaneRef W2_T1_P1 = new PaneRef("tab-c", "terminal-4");
    private static final PaneRef UNPLACED = new PaneRef("tab-z", "terminal-9");

    /** Scripted focuser: records focus calls, optionally declines, reports a settable current pane. */
    private static final class FakeFocuser implements CodingAgentNavigator.PaneFocuser {
        final List<PaneRef> focused = new ArrayList<>();
        PaneRef current;
        boolean accept = true;

        @Override
        public boolean focus(PaneRef pane) {
            focused.add(pane);
            if (accept) {
                current = pane;
            }
            return accept;
        }

        @Override
        public Optional<PaneRef> currentPane() {
            return Optional.ofNullable(current);
        }
    }

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final Map<PaneRef, PaneLocation> locations = new HashMap<>();
    private final PaneLocator locator = pane -> Optional.ofNullable(locations.get(pane));
    private CodingAgentRegistry registry;
    private FakeFocuser focuser;
    private CodingAgentNavigator navigator;

    @BeforeMethod
    void setUp() {
        clock.set(1_000L);
        locations.clear();
        locations.put(W1_T1_P1, new PaneLocation(0, 2, "alpha", 0, 2, null));
        locations.put(W1_T1_P2, new PaneLocation(0, 2, "alpha", 1, 2, null));
        locations.put(W1_T2_P1, new PaneLocation(0, 2, "beta", 0, 1, null));
        locations.put(W2_T1_P1, new PaneLocation(1, 2, "Aardvark", 0, 1, null));
        registry = CodingAgentRegistry.forTests(new FakeFocusOracle(), clock::get);
        focuser = new FakeFocuser();
        navigator = new CodingAgentNavigator(registry, locator, focuser);
    }

    private CodingAgentEntry entry(PaneRef pane, CodingAgentState state, long since) {
        DetectionResult detection = DetectionResult.of(CodingAgentKind.CLAUDE_CODE, state, "rule", null);
        return new CodingAgentEntry(pane, CodingAgentKind.CLAUDE_CODE, state, detection, null, since, since, null, false);
    }

    private void detect(PaneRef pane, CodingAgentState state, long at) {
        clock.set(at);
        DetectionResult result = DetectionResult.of(CodingAgentKind.CLAUDE_CODE, state, "rule", null);
        registry.onEvent(new CodingAgentEvent(pane, DetectionResult.NONE, result, null,
            CodingAgentEvent.Reason.DETECTED, Instant.ofEpochMilli(0)));
    }

    private static List<PaneRef> panes(List<CodingAgentEntry> entries) {
        return entries.stream().map(CodingAgentEntry::pane).toList();
    }

    @Test
    void nextBlockedWrapsAround() {
        List<CodingAgentEntry> ordered = List.of(
            entry(W1_T1_P1, CodingAgentState.BLOCKED, 1),
            entry(W1_T2_P1, CodingAgentState.WORKING, 2),
            entry(W2_T1_P1, CodingAgentState.BLOCKED, 3));

        assertThat(CodingAgentNavigator.nextBlocked(ordered, W1_T1_P1)).hasValue(W2_T1_P1);
        assertThat(CodingAgentNavigator.nextBlocked(ordered, W2_T1_P1)).hasValue(W1_T1_P1);
        assertThat(CodingAgentNavigator.nextBlocked(ordered, null)).hasValue(W1_T1_P1);
        assertThat(CodingAgentNavigator.nextBlocked(ordered, UNPLACED)).hasValue(W1_T1_P1);
    }

    @Test
    void currentNotBlockedYieldsTheFirstBlockedAfterIt() {
        List<CodingAgentEntry> ordered = List.of(
            entry(W1_T1_P1, CodingAgentState.BLOCKED, 1),
            entry(W1_T1_P2, CodingAgentState.WORKING, 2),
            entry(W1_T2_P1, CodingAgentState.BLOCKED, 3),
            entry(W2_T1_P1, CodingAgentState.DONE, 4));

        assertThat(CodingAgentNavigator.nextBlocked(ordered, W1_T1_P2)).hasValue(W1_T2_P1);
        assertThat(CodingAgentNavigator.nextBlocked(ordered, W2_T1_P1)).hasValue(W1_T1_P1);
    }

    @Test
    void theOnlyBlockedEntryIsReturnedEvenWhenCurrent() {
        List<CodingAgentEntry> ordered = List.of(
            entry(W1_T1_P1, CodingAgentState.BLOCKED, 1),
            entry(W1_T1_P2, CodingAgentState.WORKING, 2));

        assertThat(CodingAgentNavigator.nextBlocked(ordered, W1_T1_P1)).hasValue(W1_T1_P1);
    }

    @Test
    void noBlockedEntryYieldsEmpty() {
        List<CodingAgentEntry> ordered = List.of(
            entry(W1_T1_P1, CodingAgentState.WORKING, 1),
            entry(W1_T2_P1, CodingAgentState.DONE, 2));

        assertThat(CodingAgentNavigator.nextBlocked(ordered, W1_T1_P1)).isEmpty();
        assertThat(CodingAgentNavigator.nextBlocked(List.of(), W1_T1_P1)).isEmpty();
        assertThat(CodingAgentNavigator.nextBlocked(null, null)).isEmpty();
        assertThat(navigator.focusNextBlocked()).isEmpty();
        assertThat(focuser.focused).isEmpty();
    }

    @Test
    void focusDelegatesAndReturnsTheFocuserResult() {
        assertThat(navigator.focus(W1_T1_P1)).isTrue();
        assertThat(focuser.focused).containsExactly(W1_T1_P1);
        assertThat(navigator.currentPane()).hasValue(W1_T1_P1);

        focuser.accept = false;
        assertThat(navigator.focus(W1_T2_P1)).isFalse();
        assertThat(navigator.focus(null)).isFalse();
        assertThat(focuser.focused).containsExactly(W1_T1_P1, W1_T2_P1).inOrder();
    }

    @Test
    void displayOrderSortsByWindowTabAndPane() {
        List<CodingAgentEntry> entries = new ArrayList<>(List.of(
            entry(UNPLACED, CodingAgentState.WORKING, 1),
            entry(W2_T1_P1, CodingAgentState.WORKING, 1),
            entry(W1_T2_P1, CodingAgentState.WORKING, 1),
            entry(W1_T1_P2, CodingAgentState.WORKING, 1),
            entry(W1_T1_P1, CodingAgentState.WORKING, 1)));

        entries.sort(CodingAgentNavigator.displayOrder(locator));

        assertThat(panes(entries)).containsExactly(W1_T1_P1, W1_T1_P2, W1_T2_P1, W2_T1_P1, UNPLACED).inOrder();
    }

    @Test
    void orderedEntriesPutBlockedFirstThenLongestWaitingThenDisplayOrder() {
        detect(W2_T1_P1, CodingAgentState.BLOCKED, 500);
        detect(W1_T1_P1, CodingAgentState.WORKING, 100);
        detect(W1_T2_P1, CodingAgentState.BLOCKED, 200);
        detect(W1_T1_P2, CodingAgentState.WORKING, 100);
        detect(UNPLACED, CodingAgentState.DONE, 50);

        List<CodingAgentEntry> ordered = navigator.orderedEntries();

        assertThat(panes(ordered)).containsExactly(W1_T2_P1, W2_T1_P1, UNPLACED, W1_T1_P1, W1_T1_P2).inOrder();
    }

    @Test
    void focusNextBlockedCallsTheFocuserExactlyOnce() {
        detect(W1_T1_P1, CodingAgentState.BLOCKED, 100);
        detect(W1_T2_P1, CodingAgentState.WORKING, 200);
        detect(W2_T1_P1, CodingAgentState.BLOCKED, 300);
        focuser.current = W1_T1_P1;

        assertThat(navigator.focusNextBlocked()).hasValue(W2_T1_P1);
        assertThat(focuser.focused).containsExactly(W2_T1_P1);

        assertThat(navigator.focusNextBlocked()).hasValue(W1_T1_P1);
        assertThat(focuser.focused).containsExactly(W2_T1_P1, W1_T1_P1).inOrder();

        focuser.accept = false;
        assertThat(navigator.focusNextBlocked()).isEmpty();
        assertThat(focuser.focused).hasSize(3);
    }
}
