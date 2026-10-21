package de.kortty.agentdesign;

import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * The run registry counts runs the way the classic panel does and picks the run the controls act on;
 * the metro-line model turns a run's steps into stations with an end station that fills in at the end.
 */
class AgentRunModelsTest {

    @Test
    void theRegistryCountsLikeTheClassicPanel() {
        AgentRunRegistry registry = new AgentRunRegistry();
        registry.begin("a", "first", 0L);
        registry.begin("b", "second", 10L);
        registry.begin("c", "third", 20L);
        registry.begin("d", "fourth", 30L);
        registry.setAwaitingInput("a", true);
        registry.setPaused("b", true);
        registry.finish("d", 40L);

        assertThat(registry.counts()).asList().containsExactly(1, 1, 1, 1).inOrder();
        assertThat(registry.focusRunId()).isEqualTo("a");
        registry.setAwaitingInput("a", false);
        assertThat(registry.focusRunId()).isEqualTo("c");
        assertThat(registry.elapsedMillis("d", 1_000L)).isEqualTo(10L);
        assertThat(registry.hasActiveRuns()).isTrue();
    }

    @Test
    void aFinishedRunCannotBePausedOrAwaitInput() {
        AgentRunRegistry registry = new AgentRunRegistry();
        registry.begin("a", "x", 0L);
        registry.finish("a", 5L);
        registry.setPaused("a", true);
        registry.setAwaitingInput("a", true);

        assertThat(registry.state("a")).isEqualTo(AgentRunRegistry.State.DONE);
        assertThat(registry.hasActiveRuns()).isFalse();
        assertThat(registry.focusRunId()).isEqualTo("a");
    }

    @Test
    void stationsFollowTheStepsAndEndWithTheAnswer() {
        MetroStationModel model = new MetroStationModel();
        model.accept(activity("r:probe", TerminalAgentModels.AgentActivityType.ACTION,
            TerminalAgentModels.AgentActivityStatus.COMPLETED, "Inspect SSH session"));
        model.accept(activity("r:thinking:1", TerminalAgentModels.AgentActivityType.THINKING,
            TerminalAgentModels.AgentActivityStatus.RUNNING, "Thinking"));
        model.accept(activity("r:message:start", TerminalAgentModels.AgentActivityType.MESSAGE,
            TerminalAgentModels.AgentActivityStatus.COMPLETED, "ignored"));

        List<MetroStationModel.Station> stations = model.stations();
        assertThat(stations).hasSize(3);
        assertThat(stations.get(0).state()).isEqualTo(MetroStationModel.StationState.DONE);
        assertThat(stations.get(1).state()).isEqualTo(MetroStationModel.StationState.ACTIVE);
        assertThat(stations.get(2).kind()).isEqualTo(MetroStationModel.StationKind.END);
        assertThat(stations.get(2).state()).isEqualTo(MetroStationModel.StationState.PENDING);
        assertThat(model.currentIndex()).isEqualTo(1);

        model.accept(activity("r:thinking:1", TerminalAgentModels.AgentActivityType.THINKING,
            TerminalAgentModels.AgentActivityStatus.COMPLETED, "Journal is largest"));
        model.accept(activity("r:question:approval-1", TerminalAgentModels.AgentActivityType.QUESTION,
            TerminalAgentModels.AgentActivityStatus.RUNNING, "Approve"));
        assertThat(model.stations().get(2).state()).isEqualTo(MetroStationModel.StationState.ATTENTION);
        model.questionAnswered();
        assertThat(model.stations().get(2).state()).isEqualTo(MetroStationModel.StationState.DONE);

        model.acceptPhase(TerminalAgentModels.Phase.DONE);
        model.finish();
        assertThat(model.stations().get(model.stations().size() - 1).state())
            .isEqualTo(MetroStationModel.StationState.DONE);
    }

    @Test
    void aCancelledRunEndsCancelled() {
        MetroStationModel model = new MetroStationModel();
        model.accept(activity("r:command:1", TerminalAgentModels.AgentActivityType.ACTION,
            TerminalAgentModels.AgentActivityStatus.RUNNING, "sleep 100"));
        model.acceptPhase(TerminalAgentModels.Phase.CANCELLED);
        model.finish();

        assertThat(model.stations().get(0).state()).isEqualTo(MetroStationModel.StationState.CANCELLED);
        assertThat(model.stations().get(1).state()).isEqualTo(MetroStationModel.StationState.CANCELLED);
    }

    @Test
    void stationLabelsAreShortCommandNames() {
        assertThat(MetroStationModel.shortLabel("du -xh /var --max-depth=2")).isEqualTo("du -xh");
        assertThat(MetroStationModel.shortLabel("sudo journalctl --vacuum-size=500M")).isEqualTo("journalctl");
        assertThat(MetroStationModel.shortLabel("systemctl-very-long-name status")).isEqualTo("systemctl-ver…");
        assertThat(MetroStationModel.shortLabel("  ")).isEmpty();
    }

    private static TerminalAgentModels.AgentActivity activity(String id, TerminalAgentModels.AgentActivityType type,
                                                              TerminalAgentModels.AgentActivityStatus status, String title) {
        return new TerminalAgentModels.AgentActivity(id, type, status, title, title, "",
            TerminalAgentModels.AgentActivityTokenUsage.unknown(), 0L, false, true);
    }
}
