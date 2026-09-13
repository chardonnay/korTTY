package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;
import static de.kortty.codingagent.CodingAgentNotificationCoordinator.MIN_INTERVAL_PER_PANE_MILLIS;
import static de.kortty.codingagent.CodingAgentNotificationCoordinator.NEVER_DELIVERED;
import static de.kortty.codingagent.CodingAgentNotificationCoordinator.SETTLE_MILLIS;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class CodingAgentNotificationCoordinatorTest {

    private static final PaneRef PANE = new PaneRef("tab-1", "terminal-a");
    private static final PaneRef OTHER = new PaneRef("tab-2", "terminal-b");

    private record Delivery(PaneRef pane, CodingAgentState state, long stateSince, boolean anyWindowFocused) {}

    private final AtomicLong clock = new AtomicLong(100_000L);
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final List<Delivery> deliveries = new ArrayList<>();
    private FakeFocusOracle focus;
    private CodingAgentRegistry registry;
    private CodingAgentNotificationCoordinator coordinator;

    @BeforeMethod
    void setUp() {
        clock.set(100_000L);
        enabled.set(true);
        deliveries.clear();
        focus = new FakeFocusOracle();
        focus.setAnyWindowFocused(false);
        registry = CodingAgentRegistry.forTests(focus, clock::get);
        coordinator = new CodingAgentNotificationCoordinator(registry, focus, enabled::get,
            (entry, state, anyWindowFocused) ->
                deliveries.add(new Delivery(entry.pane(), state, entry.stateSinceMillis(), anyWindowFocused)),
            clock::get);
        registry.addListener(coordinator);
    }

    private static DetectionResult result(CodingAgentState state) {
        return DetectionResult.of(CodingAgentKind.CLAUDE_CODE, state, "rule-" + state, "evidence");
    }

    private void detect(PaneRef pane, CodingAgentState state) {
        registry.onEvent(new CodingAgentEvent(pane, DetectionResult.NONE, result(state), null,
            CodingAgentEvent.Reason.DETECTED, Instant.ofEpochMilli(0)));
    }

    private void change(PaneRef pane, CodingAgentState to) {
        DetectionResult previous = registry.entry(pane).map(CodingAgentEntry::detection).orElse(DetectionResult.NONE);
        registry.onEvent(new CodingAgentEvent(pane, previous, result(to), null,
            CodingAgentEvent.Reason.STATE_CHANGED, Instant.ofEpochMilli(0)));
    }

    private void remove(PaneRef pane) {
        DetectionResult previous = registry.entry(pane).map(CodingAgentEntry::detection).orElse(DetectionResult.NONE);
        registry.onEvent(new CodingAgentEvent(pane, previous, DetectionResult.NONE, null,
            CodingAgentEvent.Reason.PROCESS_EXITED, Instant.ofEpochMilli(0)));
    }

    private void advanceAndTick(long millis) {
        clock.addAndGet(millis);
        coordinator.tick();
    }

    @Test
    void blockedIsDeliveredOnlyAfterTheSettleWindow() {
        detect(PANE, CodingAgentState.WORKING);
        change(PANE, CodingAgentState.BLOCKED);
        assertThat(coordinator.pendingCount()).isEqualTo(1);

        coordinator.tick();
        assertThat(deliveries).isEmpty();
        advanceAndTick(SETTLE_MILLIS - 1);
        assertThat(deliveries).isEmpty();
        assertThat(coordinator.pendingCount()).isEqualTo(1);

        advanceAndTick(1);
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).pane()).isEqualTo(PANE);
        assertThat(deliveries.get(0).state()).isEqualTo(CodingAgentState.BLOCKED);
        assertThat(deliveries.get(0).anyWindowFocused()).isFalse();
        assertThat(coordinator.pendingCount()).isEqualTo(0);

        advanceAndTick(60_000L);
        assertThat(deliveries).hasSize(1);
    }

    @Test
    void detectionDirectlyAsBlockedCountsAsEntering() {
        detect(PANE, CodingAgentState.BLOCKED);
        advanceAndTick(SETTLE_MILLIS);
        assertThat(deliveries).hasSize(1);
    }

    @Test
    void blockedThatUnblocksBeforeTheSettleIsNotDelivered() {
        detect(PANE, CodingAgentState.WORKING);
        change(PANE, CodingAgentState.BLOCKED);
        clock.addAndGet(300L);
        change(PANE, CodingAgentState.WORKING);
        assertThat(coordinator.pendingCount()).isEqualTo(0);

        advanceAndTick(SETTLE_MILLIS);
        assertThat(deliveries).isEmpty();
    }

    @Test
    void blockedWorkingBlockedWithinTheSettleYieldsAtMostOne() {
        detect(PANE, CodingAgentState.WORKING);
        change(PANE, CodingAgentState.BLOCKED);
        clock.addAndGet(200L);
        change(PANE, CodingAgentState.WORKING);
        clock.addAndGet(200L);
        change(PANE, CodingAgentState.BLOCKED);
        assertThat(coordinator.pendingCount()).isEqualTo(1);

        advanceAndTick(SETTLE_MILLIS - 200L);
        assertThat(deliveries).isEmpty();
        advanceAndTick(200L);
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).stateSince()).isEqualTo(registry.entry(PANE).orElseThrow().stateSinceMillis());
        advanceAndTick(SETTLE_MILLIS);
        assertThat(deliveries).hasSize(1);
    }

    @Test
    void doneUntilSeenIsDeliveredOnTheNextTickWithoutSettle() {
        detect(PANE, CodingAgentState.WORKING);
        change(PANE, CodingAgentState.IDLE);
        assertThat(registry.entry(PANE).orElseThrow().doneUntilSeen()).isTrue();
        assertThat(coordinator.pendingCount()).isEqualTo(1);

        coordinator.tick();

        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).state()).isEqualTo(CodingAgentState.DONE);
        assertThat(coordinator.pendingCount()).isEqualTo(0);
    }

    @Test
    void ruleDetectedDoneIsNotANotification() {
        detect(PANE, CodingAgentState.WORKING);
        change(PANE, CodingAgentState.DONE);
        assertThat(coordinator.pendingCount()).isEqualTo(0);

        advanceAndTick(SETTLE_MILLIS);
        assertThat(deliveries).isEmpty();
    }

    @Test
    void seenPanesAreSuppressed() {
        detect(PANE, CodingAgentState.WORKING);
        change(PANE, CodingAgentState.BLOCKED);
        focus.setSeen(PANE, true);

        advanceAndTick(SETTLE_MILLIS);

        assertThat(deliveries).isEmpty();
        assertThat(coordinator.pendingCount()).isEqualTo(0);
        focus.setSeen(PANE, false);
        advanceAndTick(SETTLE_MILLIS);
        assertThat(deliveries).isEmpty();
    }

    @Test
    void disabledSuppresses() {
        enabled.set(false);
        detect(PANE, CodingAgentState.BLOCKED);

        advanceAndTick(SETTLE_MILLIS);

        assertThat(deliveries).isEmpty();
        assertThat(coordinator.pendingCount()).isEqualTo(0);
    }

    @Test
    void minimumIntervalPerPaneDefersTheNextDelivery() {
        detect(PANE, CodingAgentState.BLOCKED);
        advanceAndTick(SETTLE_MILLIS);
        assertThat(deliveries).hasSize(1);
        long firstDelivery = clock.get();

        clock.addAndGet(2_000L);
        change(PANE, CodingAgentState.WORKING);
        clock.addAndGet(100L);
        change(PANE, CodingAgentState.BLOCKED);
        advanceAndTick(SETTLE_MILLIS);
        assertThat(deliveries).hasSize(1);
        assertThat(coordinator.pendingCount()).isEqualTo(1);

        clock.set(firstDelivery + MIN_INTERVAL_PER_PANE_MILLIS - 1);
        coordinator.tick();
        assertThat(deliveries).hasSize(1);

        clock.set(firstDelivery + MIN_INTERVAL_PER_PANE_MILLIS);
        coordinator.tick();
        assertThat(deliveries).hasSize(2);
        assertThat(coordinator.pendingCount()).isEqualTo(0);
    }

    @Test
    void intervalIsPerPane() {
        detect(PANE, CodingAgentState.BLOCKED);
        detect(OTHER, CodingAgentState.BLOCKED);

        advanceAndTick(SETTLE_MILLIS);

        assertThat(deliveries).hasSize(2);
        assertThat(deliveries.stream().map(Delivery::pane).toList()).containsExactly(PANE, OTHER);
    }

    @Test
    void removedDropsPending() {
        detect(PANE, CodingAgentState.BLOCKED);
        assertThat(coordinator.pendingCount()).isEqualTo(1);

        remove(PANE);

        assertThat(coordinator.pendingCount()).isEqualTo(0);
        advanceAndTick(SETTLE_MILLIS);
        assertThat(deliveries).isEmpty();
    }

    @Test
    void seenBeforeTickDropsAQueuedDone() {
        detect(PANE, CodingAgentState.WORKING);
        change(PANE, CodingAgentState.IDLE);
        registry.markSeen(PANE);

        coordinator.tick();

        assertThat(deliveries).isEmpty();
        assertThat(coordinator.pendingCount()).isEqualTo(0);
    }

    @Test
    void theSameTransitionIsNeverDeliveredTwice() {
        detect(PANE, CodingAgentState.BLOCKED);
        advanceAndTick(SETTLE_MILLIS);
        assertThat(deliveries).hasSize(1);

        CodingAgentEntry current = registry.entry(PANE).orElseThrow();
        CodingAgentEntry pretendPrevious = new CodingAgentEntry(PANE, current.kind(), CodingAgentState.WORKING,
            current.detection(), null, current.stateSinceMillis(), current.detectedAtMillis(), null, false);
        coordinator.onRegistryChanged(new RegistryChange(RegistryChange.Kind.STATE_CHANGED, PANE, pretendPrevious,
            current, clock.get()));
        assertThat(coordinator.pendingCount()).isEqualTo(1);

        advanceAndTick(SETTLE_MILLIS + MIN_INTERVAL_PER_PANE_MILLIS);

        assertThat(deliveries).hasSize(1);
        assertThat(coordinator.pendingCount()).isEqualTo(0);
    }

    @Test
    void anyWindowFocusedIsPassedThrough() {
        focus.setAnyWindowFocused(true);
        detect(PANE, CodingAgentState.BLOCKED);
        advanceAndTick(SETTLE_MILLIS);
        assertThat(deliveries.get(0).anyWindowFocused()).isTrue();
    }

    @Test
    void aThrowingSinkDoesNotBreakTheCoordinator() {
        CodingAgentNotificationCoordinator noisy = new CodingAgentNotificationCoordinator(registry, focus,
            enabled::get, (entry, state, anyWindowFocused) -> {
                throw new IllegalStateException("sink down");
            }, clock::get);
        registry.addListener(noisy);
        detect(PANE, CodingAgentState.BLOCKED);
        clock.addAndGet(SETTLE_MILLIS);

        noisy.tick();

        assertThat(noisy.pendingCount()).isEqualTo(0);
    }

    @Test
    void nullAndIrrelevantChangesAreIgnored() {
        coordinator.onRegistryChanged(null);
        detect(PANE, CodingAgentState.WORKING);
        registry.setAlias(PANE, "api");
        assertThat(coordinator.pendingCount()).isEqualTo(0);
        coordinator.tick();
        assertThat(deliveries).isEmpty();
    }

    @Test
    void shouldDeliverTable() {
        CodingAgentEntry blocked = new CodingAgentEntry(PANE, CodingAgentKind.CODEX, CodingAgentState.BLOCKED,
            result(CodingAgentState.BLOCKED), null, 1_000L, 1_000L, null, false);
        long now = 50_000L;

        assertThat(CodingAgentNotificationCoordinator.shouldDeliver(blocked, CodingAgentState.BLOCKED, 1_000L,
            false, true, NEVER_DELIVERED, now)).isTrue();
        assertThat(CodingAgentNotificationCoordinator.shouldDeliver(blocked, CodingAgentState.BLOCKED, 1_000L,
            true, true, NEVER_DELIVERED, now)).isFalse();
        assertThat(CodingAgentNotificationCoordinator.shouldDeliver(blocked, CodingAgentState.BLOCKED, 1_000L,
            false, false, NEVER_DELIVERED, now)).isFalse();
        assertThat(CodingAgentNotificationCoordinator.shouldDeliver(null, CodingAgentState.BLOCKED, 1_000L,
            false, true, NEVER_DELIVERED, now)).isFalse();
        assertThat(CodingAgentNotificationCoordinator.shouldDeliver(blocked, CodingAgentState.DONE, 1_000L,
            false, true, NEVER_DELIVERED, now)).isFalse();
        assertThat(CodingAgentNotificationCoordinator.shouldDeliver(blocked, null, 1_000L,
            false, true, NEVER_DELIVERED, now)).isFalse();
        assertThat(CodingAgentNotificationCoordinator.shouldDeliver(blocked, CodingAgentState.BLOCKED, 999L,
            false, true, NEVER_DELIVERED, now)).isFalse();
        assertThat(CodingAgentNotificationCoordinator.shouldDeliver(blocked, CodingAgentState.BLOCKED, 1_000L,
            false, true, now - MIN_INTERVAL_PER_PANE_MILLIS, now)).isTrue();
        assertThat(CodingAgentNotificationCoordinator.shouldDeliver(blocked, CodingAgentState.BLOCKED, 1_000L,
            false, true, now - MIN_INTERVAL_PER_PANE_MILLIS + 1, now)).isFalse();
        assertThat(CodingAgentNotificationCoordinator.shouldDeliver(blocked, CodingAgentState.BLOCKED, 1_000L,
            false, true, now, now)).isFalse();
    }
}
