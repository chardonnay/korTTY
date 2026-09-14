package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;
import static de.kortty.codingagent.CodingAgentState.BLOCKED;
import static de.kortty.codingagent.CodingAgentState.DONE;
import static de.kortty.codingagent.CodingAgentState.IDLE;
import static de.kortty.codingagent.CodingAgentState.UNKNOWN;
import static de.kortty.codingagent.CodingAgentState.WORKING;
import static de.kortty.codingagent.CodingAgentState.mostUrgent;

import org.testng.annotations.Test;

class CodingAgentStateTest {

    @Test
    void declarationOrderIsTheRollupOrder() {
        assertThat(CodingAgentState.values()).asList()
            .containsExactly(BLOCKED, DONE, WORKING, IDLE, UNKNOWN).inOrder();
    }

    @Test
    void mostUrgentPrefersBlockedOverEverything() {
        assertThat(mostUrgent(BLOCKED, DONE)).isEqualTo(BLOCKED);
        assertThat(mostUrgent(BLOCKED, WORKING)).isEqualTo(BLOCKED);
        assertThat(mostUrgent(BLOCKED, IDLE)).isEqualTo(BLOCKED);
        assertThat(mostUrgent(BLOCKED, UNKNOWN)).isEqualTo(BLOCKED);
    }

    @Test
    void mostUrgentFollowsDoneWorkingIdleUnknown() {
        assertThat(mostUrgent(DONE, WORKING)).isEqualTo(DONE);
        assertThat(mostUrgent(WORKING, IDLE)).isEqualTo(WORKING);
        assertThat(mostUrgent(IDLE, UNKNOWN)).isEqualTo(IDLE);
    }

    @Test
    void mostUrgentIsSymmetricAndIdempotent() {
        for (CodingAgentState a : CodingAgentState.values()) {
            assertThat(mostUrgent(a, a)).isEqualTo(a);
            for (CodingAgentState b : CodingAgentState.values()) {
                assertThat(mostUrgent(a, b)).isEqualTo(mostUrgent(b, a));
            }
        }
    }

    @Test
    void mostUrgentTreatsNullAsAbsent() {
        assertThat(mostUrgent(null, IDLE)).isEqualTo(IDLE);
        assertThat(mostUrgent(WORKING, null)).isEqualTo(WORKING);
        assertThat(mostUrgent(null, null)).isNull();
    }
}
