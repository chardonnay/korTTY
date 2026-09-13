package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class CodingAgentRegistryTest {

    private static final PaneRef PANE_A = new PaneRef("tab-1", "terminal-a");
    private static final PaneRef PANE_B = new PaneRef("tab-1", "terminal-b");
    private static final PaneRef PANE_C = new PaneRef("tab-2", "terminal-c");
    private static final AgentProcess PROCESS = new AgentProcess(4242L, CodingAgentKind.CLAUDE_CODE, "claude", null);

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<RegistryChange> changes = new ArrayList<>();
    private FakeFocusOracle focus;
    private CodingAgentRegistry registry;

    @BeforeMethod
    void setUp() {
        clock.set(1_000_000L);
        changes.clear();
        focus = new FakeFocusOracle();
        registry = CodingAgentRegistry.forTests(focus, clock::get);
        registry.addListener(changes::add);
    }

    private static DetectionResult result(CodingAgentState state) {
        return result(CodingAgentKind.CLAUDE_CODE, state, "rule-" + state, "evidence " + state);
    }

    private static DetectionResult result(CodingAgentKind kind, CodingAgentState state, String rule, String evidence) {
        return DetectionResult.of(kind, state, rule, evidence);
    }

    private static CodingAgentEvent event(PaneRef pane, DetectionResult previous, DetectionResult current,
                                          CodingAgentEvent.Reason reason) {
        return new CodingAgentEvent(pane, previous, current, PROCESS, reason, Instant.ofEpochMilli(0));
    }

    private CodingAgentEntry detect(PaneRef pane, CodingAgentState state) {
        registry.onEvent(event(pane, DetectionResult.NONE, result(state), CodingAgentEvent.Reason.DETECTED));
        return registry.entry(pane).orElseThrow();
    }

    private CodingAgentEntry change(PaneRef pane, CodingAgentState to) {
        return change(pane, result(to));
    }

    private CodingAgentEntry change(PaneRef pane, DetectionResult to) {
        DetectionResult previous = registry.entry(pane).map(CodingAgentEntry::detection).orElse(DetectionResult.NONE);
        registry.onEvent(event(pane, previous, to, CodingAgentEvent.Reason.STATE_CHANGED));
        return registry.entry(pane).orElseThrow();
    }

    private RegistryChange lastChange() {
        assertThat(changes).isNotEmpty();
        return changes.get(changes.size() - 1);
    }

    @Test
    void detectionAddsAnEntryWithTheClockAndPublishesAdded() {
        clock.set(5_000L);
        CodingAgentEntry entry = detect(PANE_A, CodingAgentState.WORKING);

        assertThat(entry.pane()).isEqualTo(PANE_A);
        assertThat(entry.kind()).isEqualTo(CodingAgentKind.CLAUDE_CODE);
        assertThat(entry.state()).isEqualTo(CodingAgentState.WORKING);
        assertThat(entry.stateSinceMillis()).isEqualTo(5_000L);
        assertThat(entry.detectedAtMillis()).isEqualTo(5_000L);
        assertThat(entry.process()).isEqualTo(PROCESS);
        assertThat(entry.alias()).isNull();
        assertThat(entry.doneUntilSeen()).isFalse();
        assertThat(entry.lastLine()).isEqualTo("evidence WORKING");
        assertThat(changes).hasSize(1);
        assertThat(lastChange().kind()).isEqualTo(RegistryChange.Kind.ADDED);
        assertThat(lastChange().previous()).isNull();
        assertThat(lastChange().current()).isEqualTo(entry);
        assertThat(lastChange().atMillis()).isEqualTo(5_000L);
        assertThat(lastChange().entered(CodingAgentState.WORKING)).isTrue();
    }

    @Test
    void stateChangeResetsStateSinceOnlyWhenTheEffectiveStateDiffers() {
        detect(PANE_A, CodingAgentState.BLOCKED);
        clock.set(2_000_000L);
        CodingAgentEntry working = change(PANE_A, CodingAgentState.WORKING);

        assertThat(working.state()).isEqualTo(CodingAgentState.WORKING);
        assertThat(working.stateSinceMillis()).isEqualTo(2_000_000L);
        assertThat(lastChange().kind()).isEqualTo(RegistryChange.Kind.STATE_CHANGED);
        assertThat(lastChange().entered(CodingAgentState.WORKING)).isTrue();

        clock.set(3_000_000L);
        DetectionResult newEvidence = result(CodingAgentKind.CLAUDE_CODE, CodingAgentState.WORKING, "spinner",
            "still working");
        CodingAgentEntry sameState = change(PANE_A, newEvidence);

        assertThat(sameState.state()).isEqualTo(CodingAgentState.WORKING);
        assertThat(sameState.stateSinceMillis()).isEqualTo(2_000_000L);
        assertThat(sameState.detection()).isEqualTo(newEvidence);
        assertThat(lastChange().kind()).isEqualTo(RegistryChange.Kind.STATE_CHANGED);
        assertThat(lastChange().entered(CodingAgentState.WORKING)).isFalse();
    }

    @Test
    void workingToIdleWhileNotSeenBecomesDoneUntilSeen() {
        detect(PANE_A, CodingAgentState.WORKING);
        clock.set(2_000_000L);
        CodingAgentEntry done = change(PANE_A, CodingAgentState.IDLE);

        assertThat(done.state()).isEqualTo(CodingAgentState.DONE);
        assertThat(done.doneUntilSeen()).isTrue();
        assertThat(done.detection().state()).isEqualTo(CodingAgentState.IDLE);
        assertThat(done.stateSinceMillis()).isEqualTo(2_000_000L);
        assertThat(lastChange().kind()).isEqualTo(RegistryChange.Kind.STATE_CHANGED);
        assertThat(lastChange().entered(CodingAgentState.DONE)).isTrue();
    }

    @Test
    void workingToUnknownWhileNotSeenBecomesDoneUntilSeen() {
        detect(PANE_A, CodingAgentState.WORKING);
        CodingAgentEntry done = change(PANE_A, result(CodingAgentKind.CLAUDE_CODE, CodingAgentState.UNKNOWN, null, null));

        assertThat(done.state()).isEqualTo(CodingAgentState.DONE);
        assertThat(done.doneUntilSeen()).isTrue();
    }

    @Test
    void workingToIdleWhileSeenStaysIdle() {
        detect(PANE_A, CodingAgentState.WORKING);
        focus.setSeen(PANE_A, true);
        CodingAgentEntry idle = change(PANE_A, CodingAgentState.IDLE);

        assertThat(idle.state()).isEqualTo(CodingAgentState.IDLE);
        assertThat(idle.doneUntilSeen()).isFalse();
    }

    @Test
    void blockedToIdleNeverSynthesisesDone() {
        detect(PANE_A, CodingAgentState.BLOCKED);
        CodingAgentEntry idle = change(PANE_A, CodingAgentState.IDLE);

        assertThat(idle.state()).isEqualTo(CodingAgentState.IDLE);
        assertThat(idle.doneUntilSeen()).isFalse();
    }

    @Test
    void ruleDetectedDoneStaysDoneUntilSeen() {
        detect(PANE_A, CodingAgentState.WORKING);
        CodingAgentEntry done = change(PANE_A, CodingAgentState.DONE);
        assertThat(done.state()).isEqualTo(CodingAgentState.DONE);
        assertThat(done.doneUntilSeen()).isFalse();

        CodingAgentEntry stillDone = change(PANE_A, CodingAgentState.IDLE);
        assertThat(stillDone.state()).isEqualTo(CodingAgentState.DONE);
        assertThat(stillDone.doneUntilSeen()).isFalse();
        assertThat(stillDone.stateSinceMillis()).isEqualTo(done.stateSinceMillis());

        assertThat(registry.markSeen(PANE_A)).isTrue();
        assertThat(registry.entry(PANE_A).orElseThrow().state()).isEqualTo(CodingAgentState.IDLE);
    }

    @Test
    void markSeenTurnsDoneIntoIdleAndPublishesSeen() {
        detect(PANE_A, CodingAgentState.WORKING);
        change(PANE_A, CodingAgentState.IDLE);
        clock.set(9_000_000L);

        assertThat(registry.markSeen(PANE_A)).isTrue();

        CodingAgentEntry entry = registry.entry(PANE_A).orElseThrow();
        assertThat(entry.state()).isEqualTo(CodingAgentState.IDLE);
        assertThat(entry.doneUntilSeen()).isFalse();
        assertThat(entry.stateSinceMillis()).isEqualTo(9_000_000L);
        assertThat(lastChange().kind()).isEqualTo(RegistryChange.Kind.SEEN);
        assertThat(lastChange().previous().state()).isEqualTo(CodingAgentState.DONE);
        assertThat(lastChange().current()).isEqualTo(entry);
    }

    @Test
    void markSeenReturnsFalseWhenNothingToFlip() {
        assertThat(registry.markSeen(PANE_A)).isFalse();
        detect(PANE_A, CodingAgentState.WORKING);
        int before = changes.size();

        assertThat(registry.markSeen(PANE_A)).isFalse();
        assertThat(registry.markSeen(null)).isFalse();
        assertThat(changes).hasSize(before);
        assertThat(registry.entry(PANE_A).orElseThrow().state()).isEqualTo(CodingAgentState.WORKING);
    }

    @Test
    void reconcileSeenFlipsOnlySeenDoneEntries() {
        detect(PANE_A, CodingAgentState.WORKING);
        detect(PANE_B, CodingAgentState.WORKING);
        detect(PANE_C, CodingAgentState.WORKING);
        change(PANE_A, CodingAgentState.IDLE);
        change(PANE_B, CodingAgentState.IDLE);
        focus.setSeen(PANE_A, true);
        focus.setSeen(PANE_C, true);
        int before = changes.size();

        registry.reconcileSeen();

        assertThat(registry.entry(PANE_A).orElseThrow().state()).isEqualTo(CodingAgentState.IDLE);
        assertThat(registry.entry(PANE_B).orElseThrow().state()).isEqualTo(CodingAgentState.DONE);
        assertThat(registry.entry(PANE_C).orElseThrow().state()).isEqualTo(CodingAgentState.WORKING);
        assertThat(changes).hasSize(before + 1);
        assertThat(lastChange().kind()).isEqualTo(RegistryChange.Kind.SEEN);
        assertThat(lastChange().pane()).isEqualTo(PANE_A);
    }

    @Test
    void rawWorkingOrBlockedReplacesASyntheticDone() {
        detect(PANE_A, CodingAgentState.WORKING);
        change(PANE_A, CodingAgentState.IDLE);
        assertThat(registry.entry(PANE_A).orElseThrow().doneUntilSeen()).isTrue();

        CodingAgentEntry working = change(PANE_A, CodingAgentState.WORKING);
        assertThat(working.state()).isEqualTo(CodingAgentState.WORKING);
        assertThat(working.doneUntilSeen()).isFalse();

        change(PANE_A, CodingAgentState.IDLE);
        CodingAgentEntry blocked = change(PANE_A, CodingAgentState.BLOCKED);
        assertThat(blocked.state()).isEqualTo(CodingAgentState.BLOCKED);
        assertThat(blocked.doneUntilSeen()).isFalse();
    }

    @Test
    void syntheticDoneSurvivesFurtherIdleUnknownFlapping() {
        detect(PANE_A, CodingAgentState.WORKING);
        CodingAgentEntry done = change(PANE_A, CodingAgentState.IDLE);
        clock.addAndGet(5_000L);
        CodingAgentEntry stillDone = change(PANE_A, result(CodingAgentKind.CLAUDE_CODE, CodingAgentState.UNKNOWN, null, null));

        assertThat(stillDone.state()).isEqualTo(CodingAgentState.DONE);
        assertThat(stillDone.doneUntilSeen()).isTrue();
        assertThat(stillDone.stateSinceMillis()).isEqualTo(done.stateSinceMillis());
    }

    @Test
    void everyRemovalReasonRemovesTheEntryAndPublishesRemoved() {
        for (CodingAgentEvent.Reason reason : List.of(CodingAgentEvent.Reason.PROCESS_EXITED,
                CodingAgentEvent.Reason.DISCONNECTED, CodingAgentEvent.Reason.PANE_DETACHED,
                CodingAgentEvent.Reason.DETECTION_DISABLED, CodingAgentEvent.Reason.CONNECTOR_REBOUND)) {
            CodingAgentEntry entry = detect(PANE_A, CodingAgentState.BLOCKED);
            registry.onEvent(event(PANE_A, entry.detection(), DetectionResult.NONE, reason));

            assertThat(registry.entry(PANE_A)).isEmpty();
            assertThat(registry.entries()).isEmpty();
            assertThat(registry.rollupFor("tab-1")).isSameInstanceAs(TabRollup.EMPTY);
            assertThat(registry.summary()).isEqualTo(AgentSummary.EMPTY);
            assertThat(lastChange().kind()).isEqualTo(RegistryChange.Kind.REMOVED);
            assertThat(lastChange().previous()).isEqualTo(entry);
            assertThat(lastChange().current()).isNull();
        }
    }

    @Test
    void removalOfAnUnknownPaneIsIgnored() {
        registry.onEvent(event(PANE_A, result(CodingAgentState.WORKING), DetectionResult.NONE,
            CodingAgentEvent.Reason.PROCESS_EXITED));

        assertThat(changes).isEmpty();
    }

    @Test
    void aliasSurvivesConnectorReboundButNotPaneDetached() {
        CodingAgentEntry entry = detect(PANE_A, CodingAgentState.WORKING);
        registry.setAlias(PANE_A, " api ");
        assertThat(registry.entry(PANE_A).orElseThrow().alias()).isEqualTo("api");
        assertThat(lastChange().kind()).isEqualTo(RegistryChange.Kind.ALIAS_CHANGED);
        assertThat(lastChange().previous().alias()).isNull();
        assertThat(lastChange().current().alias()).isEqualTo("api");

        registry.onEvent(event(PANE_A, entry.detection(), DetectionResult.NONE, CodingAgentEvent.Reason.CONNECTOR_REBOUND));
        CodingAgentEntry rebound = detect(PANE_A, CodingAgentState.WORKING);
        assertThat(rebound.alias()).isEqualTo("api");
        assertThat(rebound.displayName()).isEqualTo("api");
        assertThat(rebound.shortName()).isEqualTo("api");

        registry.onEvent(event(PANE_A, rebound.detection(), DetectionResult.NONE, CodingAgentEvent.Reason.PANE_DETACHED));
        CodingAgentEntry fresh = detect(PANE_A, CodingAgentState.WORKING);
        assertThat(fresh.alias()).isNull();
        assertThat(fresh.displayName()).isEqualTo("Claude Code");
        assertThat(fresh.shortName()).isEqualTo("claude");
    }

    @Test
    void blankAliasClearsAndUnchangedAliasPublishesNothing() {
        detect(PANE_A, CodingAgentState.WORKING);
        registry.setAlias(PANE_A, "api");
        int before = changes.size();

        registry.setAlias(PANE_A, "api");
        assertThat(changes).hasSize(before);

        registry.setAlias(PANE_A, "   ");
        assertThat(registry.entry(PANE_A).orElseThrow().alias()).isNull();
        assertThat(lastChange().kind()).isEqualTo(RegistryChange.Kind.ALIAS_CHANGED);
        assertThat(lastChange().current().alias()).isNull();
    }

    @Test
    void rollupCountsAndMostUrgentAreCachedUntilTheNextEvent() {
        detect(PANE_A, CodingAgentState.WORKING);
        detect(PANE_B, CodingAgentState.IDLE);
        detect(PANE_C, CodingAgentState.BLOCKED);

        TabRollup first = registry.rollupFor("tab-1");
        assertThat(first.tabId()).isEqualTo("tab-1");
        assertThat(first.working()).isEqualTo(1);
        assertThat(first.idle()).isEqualTo(1);
        assertThat(first.blocked()).isEqualTo(0);
        assertThat(first.done()).isEqualTo(0);
        assertThat(first.agentCount()).isEqualTo(2);
        assertThat(first.hasAgents()).isTrue();
        assertThat(first.mostUrgent()).isEqualTo(CodingAgentState.WORKING);
        assertThat(registry.rollupFor("tab-1")).isSameInstanceAs(first);
        assertThat(registry.rollupFor("tab-2").mostUrgent()).isEqualTo(CodingAgentState.BLOCKED);
        assertThat(registry.rollupFor("tab-9")).isSameInstanceAs(TabRollup.EMPTY);
        assertThat(registry.rollupFor(null)).isSameInstanceAs(TabRollup.EMPTY);

        change(PANE_A, CodingAgentState.IDLE);
        TabRollup second = registry.rollupFor("tab-1");
        assertThat(second).isNotSameInstanceAs(first);
        assertThat(second.done()).isEqualTo(1);
        assertThat(second.mostUrgent()).isEqualTo(CodingAgentState.DONE);

        change(PANE_B, CodingAgentState.BLOCKED);
        assertThat(registry.rollupFor("tab-1").mostUrgent()).isEqualTo(CodingAgentState.BLOCKED);
        assertThat(registry.rollupFor("tab-1").blocked()).isEqualTo(1);
    }

    @Test
    void summaryTotalsFollowEveryEvent() {
        assertThat(registry.summary()).isSameInstanceAs(AgentSummary.EMPTY);
        detect(PANE_A, CodingAgentState.WORKING);
        detect(PANE_B, CodingAgentState.BLOCKED);
        detect(PANE_C, CodingAgentState.UNKNOWN);
        AgentSummary summary = registry.summary();

        assertThat(summary).isEqualTo(new AgentSummary(1, 1, 0, 1, 3));
        assertThat(registry.summary()).isSameInstanceAs(summary);

        change(PANE_A, CodingAgentState.IDLE);
        assertThat(registry.summary()).isEqualTo(new AgentSummary(1, 0, 1, 1, 3));
        registry.markSeen(PANE_A);
        assertThat(registry.summary()).isEqualTo(new AgentSummary(1, 0, 0, 2, 3));
    }

    @Test
    void entriesAreOrderedByUrgencyThenStateSinceThenIdsAndUnmodifiable() {
        clock.set(300L);
        detect(PANE_C, CodingAgentState.WORKING);
        clock.set(200L);
        detect(PANE_B, CodingAgentState.BLOCKED);
        clock.set(100L);
        detect(PANE_A, CodingAgentState.BLOCKED);
        PaneRef paneD = new PaneRef("tab-0", "terminal-d");
        clock.set(100L);
        detect(paneD, CodingAgentState.BLOCKED);
        PaneRef paneE = new PaneRef("tab-3", "terminal-e");
        clock.set(50L);
        detect(paneE, CodingAgentState.IDLE);

        List<CodingAgentEntry> entries = registry.entries();
        assertThat(entries.stream().map(CodingAgentEntry::pane).toList())
            .containsExactly(paneD, PANE_A, PANE_B, PANE_C, paneE).inOrder();
        assertThat(registry.entries()).isSameInstanceAs(entries);
        assertThrows(UnsupportedOperationException.class, () -> entries.remove(0));
        assertThat(registry.entriesForTab("tab-1").stream().map(CodingAgentEntry::pane).toList())
            .containsExactly(PANE_A, PANE_B).inOrder();
        assertThat(registry.entriesForTab("nope")).isEmpty();
        assertThat(registry.entriesForTab(null)).isEmpty();
    }

    @Test
    void blockedEntriesListTheLongestWaitingFirst() {
        clock.set(500L);
        detect(PANE_A, CodingAgentState.BLOCKED);
        clock.set(100L);
        detect(PANE_C, CodingAgentState.BLOCKED);
        clock.set(50L);
        detect(PANE_B, CodingAgentState.WORKING);

        assertThat(registry.blockedEntries().stream().map(CodingAgentEntry::pane).toList())
            .containsExactly(PANE_C, PANE_A).inOrder();
        assertThrows(UnsupportedOperationException.class, () -> registry.blockedEntries().clear());
    }

    @Test
    void closingTheListenerHandleStopsDelivery() throws Exception {
        List<RegistryChange> received = new ArrayList<>();
        AutoCloseable handle = registry.addListener(received::add);
        detect(PANE_A, CodingAgentState.WORKING);
        assertThat(received).hasSize(1);

        handle.close();
        change(PANE_A, CodingAgentState.BLOCKED);

        assertThat(received).hasSize(1);
        assertThat(changes).hasSize(2);
    }

    @Test
    void aThrowingListenerDoesNotBlockTheOthers() {
        List<RegistryChange> after = new ArrayList<>();
        registry.addListener(change -> {
            throw new IllegalStateException("boom");
        });
        registry.addListener(after::add);

        detect(PANE_A, CodingAgentState.WORKING);

        assertThat(after).hasSize(1);
        assertThat(changes).hasSize(1);
    }

    @Test
    void mutationsOffTheUiThreadAreDropped() {
        AtomicBoolean onUiThread = new AtomicBoolean(true);
        CodingAgentRegistry guarded = new CodingAgentRegistry(focus, clock::get, onUiThread::get);
        List<RegistryChange> received = new ArrayList<>();
        guarded.addListener(received::add);
        guarded.onEvent(event(PANE_A, DetectionResult.NONE, result(CodingAgentState.WORKING),
            CodingAgentEvent.Reason.DETECTED));
        assertThat(received).hasSize(1);

        onUiThread.set(false);
        guarded.onEvent(event(PANE_A, result(CodingAgentState.WORKING), result(CodingAgentState.BLOCKED),
            CodingAgentEvent.Reason.STATE_CHANGED));
        guarded.onEvent(event(PANE_B, DetectionResult.NONE, result(CodingAgentState.BLOCKED),
            CodingAgentEvent.Reason.DETECTED));
        guarded.setAlias(PANE_A, "api");
        guarded.reconcileSeen();
        guarded.seed(Map.of(PANE_C, result(CodingAgentState.WORKING)));
        guarded.clear();

        assertThat(guarded.markSeen(PANE_A)).isFalse();
        assertThat(received).hasSize(1);
        assertThat(guarded.entries()).hasSize(1);
        assertThat(guarded.entry(PANE_A).orElseThrow().state()).isEqualTo(CodingAgentState.WORKING);
        assertThat(guarded.entry(PANE_A).orElseThrow().alias()).isNull();
        assertThat(guarded.entry(PANE_B)).isEmpty();
        assertThat(guarded.entry(PANE_C)).isEmpty();
    }

    @Test
    void seedAddsEveryDetectedPaneOfASnapshotOnce() {
        clock.set(77_000L);
        detect(PANE_A, CodingAgentState.WORKING);
        int before = changes.size();

        registry.seed(Map.of(
            PANE_A, result(CodingAgentState.BLOCKED),
            PANE_B, result(CodingAgentState.BLOCKED),
            PANE_C, DetectionResult.NONE));

        assertThat(registry.entry(PANE_A).orElseThrow().state()).isEqualTo(CodingAgentState.WORKING);
        CodingAgentEntry seeded = registry.entry(PANE_B).orElseThrow();
        assertThat(seeded.state()).isEqualTo(CodingAgentState.BLOCKED);
        assertThat(seeded.process()).isNull();
        assertThat(seeded.stateSinceMillis()).isEqualTo(77_000L);
        assertThat(registry.entry(PANE_C)).isEmpty();
        assertThat(changes).hasSize(before + 1);
        assertThat(lastChange().kind()).isEqualTo(RegistryChange.Kind.ADDED);
        assertThat(lastChange().pane()).isEqualTo(PANE_B);
        assertThat(registry.summary().blocked()).isEqualTo(1);
    }

    @Test
    void focusOracleCanBeReplacedBeforeTheFirstEvent() {
        CodingAgentRegistry fresh = CodingAgentRegistry.forTests(null, clock::get);
        FakeFocusOracle seeing = new FakeFocusOracle();
        seeing.setSeen(PANE_A, true);
        fresh.setFocusOracle(seeing);

        fresh.onEvent(event(PANE_A, DetectionResult.NONE, result(CodingAgentState.WORKING),
            CodingAgentEvent.Reason.DETECTED));
        fresh.onEvent(event(PANE_A, result(CodingAgentState.WORKING), result(CodingAgentState.IDLE),
            CodingAgentEvent.Reason.STATE_CHANGED));

        assertThat(fresh.entry(PANE_A).orElseThrow().state()).isEqualTo(CodingAgentState.IDLE);

        fresh.setFocusOracle(null);
        fresh.onEvent(event(PANE_A, result(CodingAgentState.IDLE), result(CodingAgentState.WORKING),
            CodingAgentEvent.Reason.STATE_CHANGED));
        fresh.onEvent(event(PANE_A, result(CodingAgentState.WORKING), result(CodingAgentState.IDLE),
            CodingAgentEvent.Reason.STATE_CHANGED));
        assertThat(fresh.entry(PANE_A).orElseThrow().state()).isEqualTo(CodingAgentState.DONE);
    }

    @Test
    void clearRemovesEverythingAndPublishesRemovedForEachEntry() {
        detect(PANE_A, CodingAgentState.WORKING);
        detect(PANE_C, CodingAgentState.BLOCKED);
        registry.setAlias(PANE_A, "api");
        changes.clear();

        registry.clear();

        assertThat(registry.entries()).isEmpty();
        assertThat(registry.summary()).isEqualTo(AgentSummary.EMPTY);
        assertThat(registry.rollupFor("tab-1")).isSameInstanceAs(TabRollup.EMPTY);
        assertThat(changes.stream().map(RegistryChange::kind).toList())
            .containsExactly(RegistryChange.Kind.REMOVED, RegistryChange.Kind.REMOVED);
        assertThat(detect(PANE_A, CodingAgentState.WORKING).alias()).isNull();
    }

    @Test
    void stateChangeForAnUnknownPaneAddsTheEntry() {
        registry.onEvent(event(PANE_A, result(CodingAgentState.WORKING), result(CodingAgentState.BLOCKED),
            CodingAgentEvent.Reason.STATE_CHANGED));

        assertThat(registry.entry(PANE_A).orElseThrow().state()).isEqualTo(CodingAgentState.BLOCKED);
        assertThat(lastChange().kind()).isEqualTo(RegistryChange.Kind.ADDED);
    }

    @Test(timeOut = 30_000)
    void harnessDrivesRealServiceEventsIntoTheRegistry() {
        try (CodingAgentTestHarness harness = new CodingAgentTestHarness()) {
            PaneRef pane = harness.addAgent("tab-h", "terminal-h", CodingAgentKind.CODEX, CodingAgentState.WORKING,
                "Working on it");
            CodingAgentEntry entry = harness.registry().entry(pane).orElseThrow();
            assertThat(entry.kind()).isEqualTo(CodingAgentKind.CODEX);
            assertThat(entry.state()).isEqualTo(CodingAgentState.WORKING);
            assertThat(entry.detection().matchedRuleId()).isEqualTo("harness-working");
            assertThat(entry.lastLine()).isEqualTo("Working on it");
            assertThat(entry.process()).isNotNull();
            assertThat(entry.stateSinceMillis()).isEqualTo(harness.nowMillis());
            assertThat(harness.changes().get(0).kind()).isEqualTo(RegistryChange.Kind.ADDED);

            harness.advance(5_000L);
            harness.setState(pane, CodingAgentState.IDLE, null);
            CodingAgentEntry done = harness.registry().entry(pane).orElseThrow();
            assertThat(done.state()).isEqualTo(CodingAgentState.DONE);
            assertThat(done.doneUntilSeen()).isTrue();
            assertThat(done.stateSinceMillis()).isEqualTo(harness.nowMillis());

            harness.focus().setSeen(pane, true);
            harness.registry().reconcileSeen();
            assertThat(harness.registry().entry(pane).orElseThrow().state()).isEqualTo(CodingAgentState.IDLE);

            harness.setState(pane, CodingAgentState.BLOCKED, "Allow edit?");
            assertThat(harness.registry().blockedEntries()).hasSize(1);
            assertThat(harness.registry().summary()).isEqualTo(new AgentSummary(1, 0, 0, 0, 1));

            harness.remove(pane);
            assertThat(harness.registry().entry(pane)).isEmpty();
            RegistryChange last = harness.changes().get(harness.changes().size() - 1);
            assertThat(last.kind()).isEqualTo(RegistryChange.Kind.REMOVED);
        }
    }

    @Test
    void entryHelpersComputeSecondsInStateAndNames() {
        clock.set(10_000L);
        CodingAgentEntry entry = detect(PANE_A, CodingAgentState.WORKING);

        assertThat(entry.secondsInState(15_999L)).isEqualTo(5L);
        assertThat(entry.secondsInState(5_000L)).isEqualTo(0L);
        assertThat(entry.displayName()).isEqualTo("Claude Code");
        assertThat(entry.shortName()).isEqualTo("claude");
    }
}
