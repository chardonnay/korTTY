package de.kortty.ui;

import de.kortty.core.swarm.SwarmModels;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

class SwarmStatusStripSupportTest {

    // ---- Adaptive slow rule ---------------------------------------------------

    @Test
    void fallbackThresholdAppliesWhileFewerThanTwoAgentsAreDone() {
        assertThat(SwarmStatusStripSupport.slowThresholdSeconds(List.of())).isEqualTo(180L);
        assertThat(SwarmStatusStripSupport.slowThresholdSeconds(List.of(10L))).isEqualTo(180L);
        assertThat(SwarmStatusStripSupport.isSlow(SwarmModels.SwarmAgentState.RUNNING, 179, List.of())).isFalse();
        assertThat(SwarmStatusStripSupport.isSlow(SwarmModels.SwarmAgentState.RUNNING, 180, List.of())).isTrue();
    }

    @Test
    void sixtySecondFloorGovernsFastSwarms() {
        List<Long> fastDone = List.of(0L, 10L);
        assertThat(SwarmStatusStripSupport.slowThresholdSeconds(fastDone)).isEqualTo(60L);
        assertThat(SwarmStatusStripSupport.isSlow(SwarmModels.SwarmAgentState.RUNNING, 59, fastDone)).isFalse();
        assertThat(SwarmStatusStripSupport.isSlow(SwarmModels.SwarmAgentState.RUNNING, 60, fastDone)).isTrue();
        assertThat(SwarmStatusStripSupport.isSlow(SwarmModels.SwarmAgentState.RUNNING, 61, fastDone)).isTrue();
    }

    @Test
    void adaptiveThresholdIsTwiceTheMedian() {
        List<Long> done = List.of(90L, 90L);
        assertThat(SwarmStatusStripSupport.slowThresholdSeconds(done)).isEqualTo(180L);
        assertThat(SwarmStatusStripSupport.isSlow(SwarmModels.SwarmAgentState.RUNNING, 179, done)).isFalse();
        assertThat(SwarmStatusStripSupport.isSlow(SwarmModels.SwarmAgentState.RUNNING, 180, done)).isTrue();
    }

    @Test
    void medianHandlesEmptySingleOddEvenAndUnsortedInput() {
        assertThat(SwarmStatusStripSupport.medianOf(List.of())).isEqualTo(0L);
        assertThat(SwarmStatusStripSupport.medianOf(List.of(42L))).isEqualTo(42L);
        assertThat(SwarmStatusStripSupport.medianOf(List.of(30L, 10L, 20L))).isEqualTo(20L);
        assertThat(SwarmStatusStripSupport.medianOf(List.of(10L, 21L))).isEqualTo(16L);
        assertThat(SwarmStatusStripSupport.medianOf(List.of(40L, 10L, 30L, 20L))).isEqualTo(25L);
    }

    @Test
    void onlyDoneAgentsFeedTheMedian() {
        List<SwarmStatusStripSupport.AgentViz> agents = new ArrayList<>();
        agents.add(agent("a", SwarmModels.SwarmAgentState.DONE, 100));
        agents.add(agent("b", SwarmModels.SwarmAgentState.DONE, 100));
        agents.add(agent("c", SwarmModels.SwarmAgentState.FAILED, 1));
        agents.add(agent("d", SwarmModels.SwarmAgentState.CANCELLED, 1));
        agents.add(agent("e", SwarmModels.SwarmAgentState.SKIPPED, 1));
        assertThat(SwarmStatusStripSupport.doneElapsedSeconds(agents)).containsExactly(100L, 100L);
        assertThat(SwarmStatusStripSupport.slowThresholdSeconds(
            SwarmStatusStripSupport.doneElapsedSeconds(agents))).isEqualTo(200L);
    }

    @Test
    void waitingQueuedAndTerminalStatesAreNeverSlow() {
        List<Long> done = List.of(1L, 1L);
        for (SwarmModels.SwarmAgentState state : SwarmModels.SwarmAgentState.values()) {
            boolean canBeSlow = state == SwarmModels.SwarmAgentState.CONNECTING
                || state == SwarmModels.SwarmAgentState.PROBING
                || state == SwarmModels.SwarmAgentState.RUNNING;
            assertWithMessage("state %s at huge elapsed", state)
                .that(SwarmStatusStripSupport.isSlow(state, 10_000, done))
                .isEqualTo(canBeSlow);
        }
    }

    @Test
    void refreshSlowFlagsMarksOnlySlowWorkers() {
        List<SwarmStatusStripSupport.AgentViz> agents = new ArrayList<>();
        agents.add(agent("done1", SwarmModels.SwarmAgentState.DONE, 30));
        agents.add(agent("done2", SwarmModels.SwarmAgentState.DONE, 30));
        agents.add(agent("fast", SwarmModels.SwarmAgentState.RUNNING, 59));
        agents.add(agent("slow", SwarmModels.SwarmAgentState.RUNNING, 61));
        agents.add(agent("waiting", SwarmModels.SwarmAgentState.AWAITING_APPROVAL, 500));
        SwarmStatusStripSupport.refreshSlowFlags(agents);
        assertThat(agents.get(2).slow).isFalse();
        assertThat(agents.get(3).slow).isTrue();
        assertThat(agents.get(4).slow).isFalse();
    }

    // ---- Layout ----------------------------------------------------------------

    @DataProvider(name = "layoutInputs")
    public Object[][] layoutInputs() {
        int[] counts = {0, 1, 2, 4, 10, 12, 30, 50, 120};
        double[] widths = {0, 50, 380, 800, 1400, 4000};
        List<Object[]> cases = new ArrayList<>();
        for (int count : counts) {
            for (double width : widths) {
                cases.add(new Object[]{count, width, 60.0});
            }
        }
        return cases.toArray(new Object[0][]);
    }

    @Test(dataProvider = "layoutInputs")
    void layoutIsTotalAndKeepsOrbsInsideTheField(int count, double width, double height) {
        SwarmStatusStripSupport.StripLayout layout = SwarmStatusStripSupport.layout(count, width, height);
        if (count == 0 || width <= 0) {
            assertThat(layout.orbs()).isEmpty();
            return;
        }
        assertThat(layout.orbs().size() + layout.overflowCount()).isEqualTo(count);
        for (SwarmStatusStripSupport.OrbGeometry orb : layout.orbs()) {
            assertWithMessage("cx for count=%s width=%s", count, width).that(orb.cx()).isNotNaN();
            assertWithMessage("cy for count=%s width=%s", count, width).that(orb.cy()).isNotNaN();
            assertThat(orb.radius()).isAtLeast(2.0);
            assertThat(orb.radius()).isAtMost(16.0);
            assertWithMessage("disc left for count=%s width=%s", count, width)
                .that(orb.cx() - orb.radius()).isAtLeast(0.0);
            assertWithMessage("disc right for count=%s width=%s", count, width)
                .that(orb.cx() + orb.radius()).isAtMost(width);
            assertThat(orb.cy() - orb.radius()).isAtLeast(0.0);
            assertThat(orb.cy() + orb.radius()).isAtMost(height);
        }
    }

    @Test
    void layoutWrapsToTwoRowsWhenOrbsWouldOverlap() {
        SwarmStatusStripSupport.StripLayout few = SwarmStatusStripSupport.layout(8, 800, 60);
        assertThat(few.rows()).isEqualTo(1);
        SwarmStatusStripSupport.StripLayout many = SwarmStatusStripSupport.layout(80, 800, 60);
        assertThat(many.rows()).isEqualTo(2);
        assertThat(many.labelMode()).isEqualTo(SwarmStatusStripSupport.LabelMode.HIDDEN);
    }

    @Test
    void layoutFoldsExtremeCountsIntoAnOverflow() {
        SwarmStatusStripSupport.StripLayout layout = SwarmStatusStripSupport.layout(400, 800, 60);
        assertThat(layout.overflowCount()).isGreaterThan(0);
        assertThat(layout.orbs().size() + layout.overflowCount()).isEqualTo(400);
    }

    @Test
    void labelModeFollowsSlotWidthAndCount() {
        assertThat(SwarmStatusStripSupport.layout(4, 800, 60).labelMode())
            .isEqualTo(SwarmStatusStripSupport.LabelMode.FULL);
        assertThat(SwarmStatusStripSupport.layout(16, 800, 60).labelMode())
            .isEqualTo(SwarmStatusStripSupport.LabelMode.ABBREVIATED);
        assertThat(SwarmStatusStripSupport.layout(4, 150, 60).labelMode())
            .isEqualTo(SwarmStatusStripSupport.LabelMode.HIDDEN);
    }

    @Test
    void hitTestRoundTripsOrbCentersAndMissesGaps() {
        SwarmStatusStripSupport.StripLayout layout = SwarmStatusStripSupport.layout(5, 800, 60);
        for (int i = 0; i < layout.orbs().size(); i++) {
            SwarmStatusStripSupport.OrbGeometry orb = layout.orbs().get(i);
            assertThat(SwarmStatusStripSupport.orbIndexAt(layout, orb.cx(), orb.cy())).isEqualTo(i);
        }
        SwarmStatusStripSupport.OrbGeometry first = layout.orbs().get(0);
        SwarmStatusStripSupport.OrbGeometry second = layout.orbs().get(1);
        double midX = (first.cx() + second.cx()) / 2;
        assertThat(SwarmStatusStripSupport.orbIndexAt(layout, midX, 0)).isEqualTo(-1);
        assertThat(SwarmStatusStripSupport.orbIndexAt(layout, -50, -50)).isEqualTo(-1);
    }

    @Test
    void abbreviateStripsDomainAndEllipsizes() {
        assertThat(SwarmStatusStripSupport.abbreviate("web-01.example.com", 12)).isEqualTo("web-01");
        assertThat(SwarmStatusStripSupport.abbreviate("very-long-hostname", 8)).isEqualTo("very-lo…");
        assertThat(SwarmStatusStripSupport.abbreviate(null, 8)).isEmpty();
        assertThat(SwarmStatusStripSupport.abbreviate("ok", 8)).isEqualTo("ok");
    }

    // ---- Curves ----------------------------------------------------------------

    @Test
    void pulseScaleIsBoundedAndPeriodic() {
        for (double t = 0; t < 5; t += 0.05) {
            double scale = SwarmStatusStripSupport.pulseScale(t, 1.0);
            assertThat(scale).isAtLeast(0.92);
            assertThat(scale).isAtMost(1.08);
        }
        assertThat(SwarmStatusStripSupport.pulseScale(0, 0.5))
            .isWithin(1e-9).of(SwarmStatusStripSupport.pulseScale(2.4, 0.5));
    }

    @Test
    void glowAndBlinkAlphasStayInRenderableRange() {
        for (double t = 0; t < 5; t += 0.05) {
            double glow = SwarmStatusStripSupport.pulseGlowAlpha(t, 2.0);
            assertThat(glow).isAtLeast(0.25);
            assertThat(glow).isAtMost(0.85);
            double blink = SwarmStatusStripSupport.blinkAlpha(t);
            assertThat(blink).isAtLeast(0.35);
            assertThat(blink).isAtMost(1.0);
        }
        assertThat(SwarmStatusStripSupport.blinkAlpha(0)).isWithin(1e-9).of(0.35);
        assertThat(SwarmStatusStripSupport.blinkAlpha(0.4)).isWithin(1e-9).of(1.0);
    }

    @Test
    void phaseOffsetIsStableAndDistinguishesAgents() {
        double first = SwarmStatusStripSupport.phaseOffset("agent-1");
        assertThat(SwarmStatusStripSupport.phaseOffset("agent-1")).isEqualTo(first);
        assertThat(first).isAtLeast(0.0);
        assertThat(first).isLessThan(2 * Math.PI);
        assertThat(SwarmStatusStripSupport.phaseOffset("agent-2")).isNotEqualTo(first);
        assertThat(SwarmStatusStripSupport.phaseOffset(null)).isEqualTo(0.0);
    }

    @Test
    void settlePopEndsAfterItsWindow() {
        assertThat(SwarmStatusStripSupport.settlePopScale(-1)).isEqualTo(1.0);
        assertThat(SwarmStatusStripSupport.settlePopScale(0.2)).isGreaterThan(1.0);
        assertThat(SwarmStatusStripSupport.settlePopScale(0.46)).isEqualTo(1.0);
        assertThat(SwarmStatusStripSupport.isSettling(0.5)).isTrue();
        assertThat(SwarmStatusStripSupport.isSettling(1.5)).isFalse();
        assertThat(SwarmStatusStripSupport.isSettling(-0.1)).isFalse();
    }

    @Test
    void pingAndSlowRingCurvesAreWellBehaved() {
        for (double t = 0; t < 5; t += 0.05) {
            double ping = SwarmStatusStripSupport.pingProgress(t);
            assertThat(ping).isAtLeast(0.0);
            assertThat(ping).isLessThan(1.0);
            double angle = SwarmStatusStripSupport.slowRingStartAngle(t);
            assertThat(angle).isAtMost(0.0);
            assertThat(angle).isGreaterThan(-360.0);
            assertThat(SwarmStatusStripSupport.driftX(t, 1.0)).isAtMost(1.5);
            assertThat(SwarmStatusStripSupport.driftX(t, 1.0)).isAtLeast(-1.5);
            assertThat(SwarmStatusStripSupport.driftY(t, 1.0)).isAtMost(1.2);
            assertThat(SwarmStatusStripSupport.driftY(t, 1.0)).isAtLeast(-1.2);
        }
    }

    // ---- Colors & classification -------------------------------------------------

    @Test
    void colorMappingCoversEveryState() {
        for (SwarmModels.SwarmAgentState state : SwarmModels.SwarmAgentState.values()) {
            assertThat(SwarmStatusStripSupport.coreColorHex(state, null)).isNotNull();
            assertThat(SwarmStatusStripSupport.coreColorHex(state, "#bd93f9")).isNotNull();
            assertThat(SwarmStatusStripSupport.glowColorHex(state)).isNotNull();
        }
        assertThat(SwarmStatusStripSupport.glowColorHex(SwarmModels.SwarmAgentState.DONE)).isEqualTo("#2e7d32");
        assertThat(SwarmStatusStripSupport.glowColorHex(SwarmModels.SwarmAgentState.FAILED)).isEqualTo("#c62828");
        assertThat(SwarmStatusStripSupport.glowColorHex(SwarmModels.SwarmAgentState.AWAITING_APPROVAL)).isEqualTo("#e65100");
        assertThat(SwarmStatusStripSupport.coreColorHex(SwarmModels.SwarmAgentState.CANCELLED, null)).isEqualTo("#757575");
        assertThat(SwarmStatusStripSupport.coreColorHex(SwarmModels.SwarmAgentState.SKIPPED, null)).isEqualTo("#757575");
        assertThat(SwarmStatusStripSupport.coreColorHex(SwarmModels.SwarmAgentState.RUNNING, "#bd93f9")).isEqualTo("#bd93f9");
        assertThat(SwarmStatusStripSupport.coreColorHex(SwarmModels.SwarmAgentState.PAUSED, null)).isEqualTo("#b39ddb");
        assertThat(SwarmStatusStripSupport.glowColorHex(SwarmModels.SwarmAgentState.PAUSED)).isEqualTo("#512da8");
    }

    @Test
    void terminalAndAnimatedClassificationMatchesTheStateMachine() {
        for (SwarmModels.SwarmAgentState state : SwarmModels.SwarmAgentState.values()) {
            boolean terminal = state == SwarmModels.SwarmAgentState.DONE
                || state == SwarmModels.SwarmAgentState.FAILED
                || state == SwarmModels.SwarmAgentState.CANCELLED
                || state == SwarmModels.SwarmAgentState.SKIPPED;
            assertThat(SwarmStatusStripSupport.isTerminal(state)).isEqualTo(terminal);
            assertThat(SwarmStatusStripSupport.isAnimated(state))
                .isEqualTo(!terminal
                    && state != SwarmModels.SwarmAgentState.QUEUED
                    && state != SwarmModels.SwarmAgentState.PAUSED);
        }
    }

    // ---- Summary & legend ----------------------------------------------------------

    @Test
    void summaryCountsEveryCategoryConsistently() {
        List<SwarmStatusStripSupport.AgentViz> agents = new ArrayList<>();
        SwarmModels.SwarmAgentState[] states = SwarmModels.SwarmAgentState.values();
        for (int i = 0; i < states.length; i++) {
            agents.add(agent("a" + i, states[i], 10));
        }
        agents.get(3).slow = true;
        SwarmStatusStripSupport.VizSummary summary = SwarmStatusStripSupport.summarize(agents);
        assertThat(summary.total()).isEqualTo(states.length);
        assertThat(summary.active()).isEqualTo(3);
        assertThat(summary.waiting()).isEqualTo(1);
        assertThat(summary.paused()).isEqualTo(1);
        assertThat(summary.done()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.inactive()).isEqualTo(3);
        assertThat(summary.slow()).isEqualTo(1);
        assertThat(summary.active() + summary.waiting() + summary.paused() + summary.done()
            + summary.failed() + summary.inactive()).isEqualTo(summary.total());
    }

    @Test
    void legendDropsZeroCountChipsAndUsesTheAccent() {
        SwarmStatusStripSupport.VizSummary summary =
            new SwarmStatusStripSupport.VizSummary(5, 2, 0, 0, 3, 0, 0, 0);
        List<SwarmStatusStripSupport.LegendChip> chips =
            SwarmStatusStripSupport.legendChips(summary, "#ffcc44");
        assertThat(chips).hasSize(2);
        assertThat(chips.get(0).colorHex()).isEqualTo("#ffcc44");
        assertThat(chips.get(0).count()).isEqualTo(2);
        assertThat(chips.get(1).colorHex()).isEqualTo("#4caf50");
        assertThat(SwarmStatusStripSupport.legendWidth(chips)).isGreaterThan(0.0);
        assertThat(SwarmStatusStripSupport.legendWidth(List.of())).isEqualTo(0.0);
    }

    @Test
    void parseFinalStateNeverThrows() {
        assertThat(SwarmStatusStripSupport.parseFinalStateOrNull(null)).isNull();
        assertThat(SwarmStatusStripSupport.parseFinalStateOrNull("")).isNull();
        assertThat(SwarmStatusStripSupport.parseFinalStateOrNull("  ")).isNull();
        assertThat(SwarmStatusStripSupport.parseFinalStateOrNull("garbage")).isNull();
        assertThat(SwarmStatusStripSupport.parseFinalStateOrNull("done"))
            .isEqualTo(SwarmModels.SwarmAgentState.DONE);
        assertThat(SwarmStatusStripSupport.parseFinalStateOrNull(" FAILED "))
            .isEqualTo(SwarmModels.SwarmAgentState.FAILED);
    }

    private static SwarmStatusStripSupport.AgentViz agent(
        String id, SwarmModels.SwarmAgentState state, long elapsedSeconds) {
        SwarmStatusStripSupport.AgentViz viz = new SwarmStatusStripSupport.AgentViz(id, id);
        viz.state = state;
        viz.elapsedSeconds = elapsedSeconds;
        return viz;
    }
}
