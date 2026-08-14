package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static de.kortty.ui.TerminalDisconnectSupport.Reaction;
import static de.kortty.ui.TerminalDisconnectSupport.reactionFor;

class TerminalDisconnectSupportTest {

    @Test
    void cleanSshEndWithoutJournalClosesTheTab() {
        assertThat(reactionFor(false, false, false, false, false, false))
            .isEqualTo(Reaction.CLOSE_TAB);
    }

    @Test
    void cleanSshEndWithARunningJournalKeepsTheTabForTheDecision() {
        // The reboot case: the server ends the connection cleanly, but the work continues later.
        assertThat(reactionFor(false, false, false, false, false, true))
            .isEqualTo(Reaction.KEEP_OPEN_JOURNAL_DECISION);
    }

    @Test
    void errorDisconnectKeepsTheTabEitherWay() {
        assertThat(reactionFor(true, false, false, false, false, false))
            .isEqualTo(Reaction.KEEP_OPEN_DISCONNECTED);
        assertThat(reactionFor(true, false, false, false, false, true))
            .isEqualTo(Reaction.KEEP_OPEN_JOURNAL_DECISION);
        // An error during a user-requested reconnect still shows the disconnect UI.
        assertThat(reactionFor(true, true, false, false, false, false))
            .isEqualTo(Reaction.KEEP_OPEN_DISCONNECTED);
    }

    @Test
    void reconnectInProgressSwallowsTheExpectedCleanDisconnect() {
        assertThat(reactionFor(false, true, false, false, false, true))
            .isEqualTo(Reaction.IGNORE_RECONNECT_IN_PROGRESS);
    }

    @Test
    void splitPaneExitNeverClosesTheTab() {
        assertThat(reactionFor(false, false, false, false, true, false))
            .isEqualTo(Reaction.PANE_CLOSED_ONLY);
        assertThat(reactionFor(false, false, false, false, true, true))
            .isEqualTo(Reaction.PANE_CLOSED_ONLY);
    }

    @Test
    void transientMoshDropKeepsTheTabOpen() {
        assertThat(reactionFor(false, false, true, false, false, false))
            .isEqualTo(Reaction.KEEP_OPEN_DISCONNECTED);
        assertThat(reactionFor(false, false, true, false, false, true))
            .isEqualTo(Reaction.KEEP_OPEN_JOURNAL_DECISION);
    }

    @Test
    void moshRemoteLogoutBehavesLikeACleanSshEnd() {
        assertThat(reactionFor(false, false, true, true, false, false))
            .isEqualTo(Reaction.CLOSE_TAB);
        assertThat(reactionFor(false, false, true, true, false, true))
            .isEqualTo(Reaction.KEEP_OPEN_JOURNAL_DECISION);
    }
}
