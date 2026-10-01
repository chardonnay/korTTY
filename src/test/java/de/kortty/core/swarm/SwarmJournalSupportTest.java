package de.kortty.core.swarm;

import de.kortty.core.AutomationJournalRun;
import de.kortty.model.AutomationRunStatus;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SwarmJournalSupportTest {

    @Test
    void mapsAgentStatesToJournalOutcomes() {
        assertThat(SwarmJournalSupport.map(SwarmModels.SwarmAgentState.DONE, false)).isEqualTo(AutomationRunStatus.SUCCESS);
        assertThat(SwarmJournalSupport.map(SwarmModels.SwarmAgentState.FAILED, false)).isEqualTo(AutomationRunStatus.FAILED);
        assertThat(SwarmJournalSupport.map(SwarmModels.SwarmAgentState.CANCELLED, false)).isEqualTo(AutomationRunStatus.CANCELLED);
        assertThat(SwarmJournalSupport.map(SwarmModels.SwarmAgentState.SKIPPED, false)).isEqualTo(AutomationRunStatus.CANCELLED);
        // an approval denial surfaces as FAILED but is a policy block
        assertThat(SwarmJournalSupport.map(SwarmModels.SwarmAgentState.FAILED, true)).isEqualTo(AutomationRunStatus.BLOCKED);
        assertThat(SwarmJournalSupport.map(null, false)).isEqualTo(AutomationRunStatus.CANCELLED);
    }

    @Test
    void targetsStayUntouchedWithoutJournals() {
        List<SwarmTarget> targets = List.of(new SwarmTarget(
            "a1", new ServerConnection("S", "host", 22, "root"), null, null, "s1", "host"));

        assertThat(SwarmJournalSupport.wrapTargets(targets, AutomationJournalRun.NONE)).isSameInstanceAs(targets);
        assertThat(SwarmJournalSupport.wrapTargets(targets, null)).isSameInstanceAs(targets);
    }
}
