package de.kortty.ui;

import de.kortty.codingagent.CodingAgentEntry;
import de.kortty.codingagent.CodingAgentKind;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.DetectionResult;
import de.kortty.codingagent.PaneRef;
import de.kortty.core.AgentDashboardStatus;
import org.testng.annotations.Test;

import java.text.MessageFormat;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.BiFunction;

import static com.google.common.truth.Truth.assertThat;

/** Pure tests of the dashboard's coding-agent mark mapping; no JavaFX toolkit involved. */
class DashboardAgentMarksTest {

    private static final PaneRef PANE = new PaneRef("tab-1", "terminal-1a2b");
    private static final Map<String, String> MESSAGES = Map.of(
        "dashboard.agent.tooltip", "{0} · {1} since {2}",
        "codingAgent.state.blocked", "Waiting for you",
        "codingAgent.state.working", "Working",
        "codingAgent.state.done", "Done",
        "codingAgent.state.idle", "Idle",
        "codingAgent.state.unknown", "Unknown");
    private static final BiFunction<String, Object[], String> ENGLISH = (key, args) -> {
        String pattern = MESSAGES.get(key);
        return pattern == null ? key : new MessageFormat(pattern).format(args);
    };

    private static CodingAgentEntry entry(CodingAgentKind kind, CodingAgentState state, String alias, long since) {
        DetectionResult detection = DetectionResult.of(kind, state, kind.id() + ".rule", "Do you want to proceed?");
        return new CodingAgentEntry(PANE, kind, state, detection, null, since, since, alias, false);
    }

    @Test
    void blockedChipCarriesGlyphShortNameBlockedClassAndPulse() {
        DashboardAgentMarks.ChipText chip = DashboardAgentMarks.chipFor(
            entry(CodingAgentKind.CLAUDE_CODE, CodingAgentState.BLOCKED, null, 1_000L));

        assertThat(chip.label()).isEqualTo("✋ claude");
        assertThat(chip.styleClass()).isEqualTo("dashboard-agent-chip-blocked");
        assertThat(chip.pulse()).isTrue();
    }

    @Test
    void aliasReplacesTheShortName() {
        DashboardAgentMarks.ChipText chip = DashboardAgentMarks.chipFor(
            entry(CodingAgentKind.CODEX, CodingAgentState.WORKING, "api-fix", 1_000L));

        assertThat(chip.label()).isEqualTo("⚡ api-fix");
        assertThat(chip.styleClass()).isEqualTo("dashboard-agent-chip-working");
    }

    @Test
    void workingNeverPulses() {
        DashboardAgentMarks.ChipText chip = DashboardAgentMarks.chipFor(
            entry(CodingAgentKind.GEMINI_CLI, CodingAgentState.WORKING, null, 1_000L));

        assertThat(chip.pulse()).isFalse();
        assertThat(chip.label()).isEqualTo("⚡ gemini");
    }

    @Test
    void doneChipUsesTheCheckMark() {
        DashboardAgentMarks.ChipText chip = DashboardAgentMarks.chipFor(
            entry(CodingAgentKind.CLAUDE_CODE, CodingAgentState.DONE, null, 1_000L));

        assertThat(chip.label()).isEqualTo("✓ claude");
        assertThat(chip.styleClass()).isEqualTo("dashboard-agent-chip-done");
        assertThat(chip.pulse()).isFalse();
    }

    @Test
    void idleAndUnknownAgentsGetTheDimIdleChipWithoutAccentOrPulse() {
        for (CodingAgentState state : new CodingAgentState[] {CodingAgentState.IDLE, CodingAgentState.UNKNOWN}) {
            DashboardAgentMarks.ChipText chip = DashboardAgentMarks.chipFor(
                entry(CodingAgentKind.CLAUDE_CODE, state, null, 1_000L));

            assertThat(chip.label()).isEqualTo("· claude");
            assertThat(chip.styleClass()).isEqualTo("dashboard-agent-chip-idle");
            assertThat(chip.pulse()).isFalse();
            assertThat(DashboardAgentMarks.accentClassFor(state)).isNull();
        }
    }

    @Test
    void nullEntryHasNoChip() {
        assertThat(DashboardAgentMarks.chipFor(null)).isNull();
        assertThat(DashboardAgentMarks.chipFor(null, AgentDashboardStatus.State.AWAITING)).isNull();
    }

    @Test
    void legacyStateMergesIntoTheChipGlyphAndColour() {
        CodingAgentEntry working = entry(CodingAgentKind.CLAUDE_CODE, CodingAgentState.WORKING, null, 1_000L);

        DashboardAgentMarks.ChipText merged =
            DashboardAgentMarks.chipFor(working, AgentDashboardStatus.State.AWAITING);
        assertThat(merged.label()).isEqualTo("✋ claude");
        assertThat(merged.styleClass()).isEqualTo("dashboard-agent-chip-blocked");
        assertThat(merged.pulse()).isTrue();

        DashboardAgentMarks.ChipText plain = DashboardAgentMarks.chipFor(working, AgentDashboardStatus.State.NONE);
        assertThat(plain).isEqualTo(DashboardAgentMarks.chipFor(working));
        assertThat(DashboardAgentMarks.chipFor(working, null)).isEqualTo(plain);
    }

    @Test
    void legacyChipKeepsTheGlyphOnly() {
        DashboardAgentMarks.ChipText chip = DashboardAgentMarks.legacyChip("✋");

        assertThat(chip.label()).isEqualTo("✋");
        assertThat(chip.styleClass()).isEqualTo("dashboard-agent-chip-legacy");
        assertThat(chip.pulse()).isFalse();
        assertThat(DashboardAgentMarks.legacyChip("")).isNull();
        assertThat(DashboardAgentMarks.legacyChip(null)).isNull();
    }

    @Test
    void accentClassMapsTheThreeVisibleStatesOnly() {
        assertThat(DashboardAgentMarks.accentClassFor(CodingAgentState.BLOCKED)).isEqualTo("dashboard-row-agent-blocked");
        assertThat(DashboardAgentMarks.accentClassFor(CodingAgentState.WORKING)).isEqualTo("dashboard-row-agent-working");
        assertThat(DashboardAgentMarks.accentClassFor(CodingAgentState.DONE)).isEqualTo("dashboard-row-agent-done");
        assertThat(DashboardAgentMarks.accentClassFor(CodingAgentState.IDLE)).isNull();
        assertThat(DashboardAgentMarks.accentClassFor(CodingAgentState.UNKNOWN)).isNull();
        assertThat(DashboardAgentMarks.accentClassFor(null)).isNull();
    }

    @Test
    void connectionAndPaneRowsAreLeavesEverythingElseIsAHeader() {
        assertThat(DashboardAgentMarks.isHeader(DashboardView.NodeType.CONNECTION)).isFalse();
        assertThat(DashboardAgentMarks.isHeader(DashboardView.NodeType.PANE)).isFalse();
        assertThat(DashboardAgentMarks.isHeader(DashboardView.NodeType.MAIN_WINDOW)).isTrue();
        assertThat(DashboardAgentMarks.isHeader(DashboardView.NodeType.ENVIRONMENT)).isTrue();
        assertThat(DashboardAgentMarks.isHeader(DashboardView.NodeType.GROUP)).isTrue();
        assertThat(DashboardAgentMarks.isHeader(null)).isFalse();
    }

    @Test
    void tooltipLineShowsDisplayNameStateLabelAndClockTime() {
        long since = 12L * 3_600_000L + 34L * 60_000L + 56_000L; // 12:34:56 UTC on the epoch day
        CodingAgentEntry entry = entry(CodingAgentKind.CLAUDE_CODE, CodingAgentState.BLOCKED, null, since);

        String line = DashboardAgentMarks.tooltipLine(entry, since + 5_000L, ENGLISH, ZoneOffset.UTC);

        assertThat(line).isEqualTo("Claude Code · Waiting for you since 12:34");
    }

    @Test
    void tooltipLineUsesTheAliasAndTheUnknownLabel() {
        long since = 7L * 3_600_000L;
        CodingAgentEntry entry = entry(CodingAgentKind.CODEX, CodingAgentState.UNKNOWN, "refactor", since);

        String line = DashboardAgentMarks.tooltipLine(entry, since, ENGLISH, ZoneOffset.UTC);

        assertThat(line).isEqualTo("refactor · Unknown since 07:00");
        assertThat(DashboardAgentMarks.tooltipLine(null, since, ENGLISH)).isEmpty();
    }

    @Test
    void agentTokensCarryTheFiveTokensWithTheSpecifiedValues() {
        String light = DashboardAgentMarks.agentTokensFor(true);
        assertThat(light).contains("-kortty-dash-agent-blocked: #b45309;");
        assertThat(light).contains("-kortty-dash-agent-working: #1d4ed8;");
        assertThat(light).contains("-kortty-dash-agent-done: #15803d;");
        assertThat(light).contains("-kortty-dash-agent-blocked-tint: rgba(180,83,9,0.12);");
        assertThat(light).contains("-kortty-dash-agent-chip-fg: #ffffff;");

        String dark = DashboardAgentMarks.agentTokensFor(false);
        assertThat(dark).contains("-kortty-dash-agent-blocked: #f59e0b;");
        assertThat(dark).contains("-kortty-dash-agent-working: #60a5fa;");
        assertThat(dark).contains("-kortty-dash-agent-done: #34d399;");
        assertThat(dark).contains("-kortty-dash-agent-blocked-tint: rgba(245,158,11,0.14);");
        assertThat(dark).contains("-kortty-dash-agent-chip-fg: #0b1220;");

        for (String tokens : new String[] {light, dark}) {
            for (String name : new String[] {"blocked", "working", "done", "blocked-tint", "chip-fg"}) {
                assertThat(tokens).contains("-kortty-dash-agent-" + name + ":");
            }
        }
    }
}
