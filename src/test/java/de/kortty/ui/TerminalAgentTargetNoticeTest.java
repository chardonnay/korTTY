package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class TerminalAgentTargetNoticeTest {

    @Test
    void sshTargetPrefersTheSessionsExpectedIdentity() {
        assertThat(TerminalAgentTargetNotice.describeTarget("alice", "web1", "bob", "web2", false))
            .isEqualTo("alice@web1");
    }

    @Test
    void sshTargetFallsBackToTheConnectionUserAndHost() {
        assertThat(TerminalAgentTargetNotice.describeTarget(null, " ", "bob", "web2", false))
            .isEqualTo("bob@web2");
        // Each half falls back on its own.
        assertThat(TerminalAgentTargetNotice.describeTarget("alice", null, null, "web2", false))
            .isEqualTo("alice@web2");
        assertThat(TerminalAgentTargetNotice.describeTarget(null, null, " bob ", null, false))
            .isEqualTo("bob");
        assertThat(TerminalAgentTargetNotice.describeTarget(null, "web1", null, null, false))
            .isEqualTo("web1");
    }

    @Test
    void localShellTargetSaysThisComputerInsteadOfUserAtHost() {
        String target = TerminalAgentTargetNotice.describeTarget("daniel", "macbook.local", null, null, true);

        assertThat(target).isEqualTo(I18n.get("ai.agent.foreignSession.localTarget", "daniel"));
        assertThat(target).contains("daniel");
        assertThat(target).doesNotContain("@");
        assertThat(target).doesNotContain("macbook.local");
    }

    @Test
    void unknownIdentityGetsAGenericWordingInsteadOfNullOrAnAt() {
        String unknown = I18n.get("ai.agent.foreignSession.unknownTarget");
        assertThat(unknown).isNotEqualTo("ai.agent.foreignSession.unknownTarget");

        assertThat(TerminalAgentTargetNotice.describeTarget(null, null, null, null, false)).isEqualTo(unknown);
        assertThat(TerminalAgentTargetNotice.describeTarget(" ", "", null, null, false)).isEqualTo(unknown);
        assertThat(TerminalAgentTargetNotice.describeTarget(null, "macbook.local", null, null, true)).isEqualTo(unknown);
    }

    @Test
    void identityJoinsTheKnownHalves() {
        assertThat(TerminalAgentTargetNotice.identity("root", "db1")).isEqualTo("root@db1");
        assertThat(TerminalAgentTargetNotice.identity("root", null)).isEqualTo("root");
        assertThat(TerminalAgentTargetNotice.identity(null, "db1")).isEqualTo("db1");
        assertThat(TerminalAgentTargetNotice.identity(" ", null)).isNull();
    }

    @Test
    void warningMessageNamesTheTarget() {
        String message = I18n.get("ai.agent.foreignSession.message", "alice@web1");

        assertThat(message).contains("alice@web1");
        assertThat(message).doesNotContain("{0}");
    }

    @Test
    void aNativeSessionNeverAsks() {
        Object connector = new Object();

        assertThat(TerminalAgentTargetNotice.requiresConfirmation(false, connector, null)).isFalse();
        assertThat(TerminalAgentTargetNotice.requiresConfirmation(false, null, null)).isFalse();
    }

    @Test
    void aForeignSessionAsksUnlessTheSameConnectorWasAlreadyAcknowledged() {
        Object connector = new Object();
        Object otherConnector = new Object();

        assertThat(TerminalAgentTargetNotice.requiresConfirmation(true, connector, null)).isTrue();
        // Planning accepted the warning: executing the plan, or a Retry, on the same pane does not ask again.
        assertThat(TerminalAgentTargetNotice.requiresConfirmation(true, connector, connector)).isFalse();
        // An acknowledgement for another pane's (or a reconnected) connector does not carry over.
        assertThat(TerminalAgentTargetNotice.requiresConfirmation(true, connector, otherConnector)).isTrue();
        assertThat(TerminalAgentTargetNotice.requiresConfirmation(true, null, null)).isTrue();
    }
}
