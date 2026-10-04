package de.kortty.ui;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

/**
 * Where a snippet from the command palette goes: the pane of the tab that has (or last had) the
 * keyboard focus, the first pane when that one is gone, and only that pane; a pane that closed, is
 * busy with a full-screen program or a paste, or has no session gets nothing. Toolkit-free: the panes
 * are plain strings behind {@link SnippetTerminalSend.PaneInput}.
 */
class SnippetTerminalSendPaneTargetTest {

    /** A split tab's panes: which are open, which are busy, which are connected, and what each received. */
    private static final class FakePanes implements SnippetTerminalSend.PaneInput<String> {
        final List<String> open = new ArrayList<>();
        final Set<String> busy = new HashSet<>();
        final Set<String> disconnected = new HashSet<>();
        final List<String> received = new ArrayList<>();

        FakePanes(String... panes) {
            open.addAll(List.of(panes));
        }

        @Override
        public boolean hasPane(String pane) {
            return open.contains(pane);
        }

        @Override
        public boolean isBusy(String pane) {
            return busy.contains(pane);
        }

        @Override
        public boolean sendLine(String pane, String line, boolean generatedOneLiner) {
            if (disconnected.contains(pane)) {
                return false;
            }
            received.add(pane + " <- " + line + (generatedOneLiner ? " (hidden)" : ""));
            return true;
        }
    }

    @Test
    void theFocusedSplitPaneIsTheTargetAndTheFirstPaneOnlyAFallback() {
        List<String> panes = List.of("pane-1", "pane-2", "pane-3");

        assertThat(SnippetTerminalSend.targetPane(panes, "pane-2")).isEqualTo("pane-2");
        assertThat(SnippetTerminalSend.targetPane(panes, "pane-1")).isEqualTo("pane-1");
        // The focused pane closed, or the view never had one focused: the first pane.
        assertThat(SnippetTerminalSend.targetPane(panes, "closed")).isEqualTo("pane-1");
        assertThat(SnippetTerminalSend.targetPane(panes, null)).isEqualTo("pane-1");
        assertThat(SnippetTerminalSend.<String>targetPane(List.of(), "pane-1")).isNull();
        assertThat(SnippetTerminalSend.<String>targetPane(null, "pane-1")).isNull();
    }

    @Test
    void theFocusedSplitPaneReceivesTheTextAndNoOtherPane() {
        FakePanes tab = new FakePanes("pane-1", "pane-2");
        String target = SnippetTerminalSend.targetPane(tab.open, "pane-2");

        SnippetTerminalSend.PaneSendOutcome outcome =
            SnippetTerminalSend.deliverToPane(tab, target, "df -h", false);

        assertThat(outcome).isEqualTo(SnippetTerminalSend.PaneSendOutcome.SENT);
        assertThat(tab.received).containsExactly("pane-2 <- df -h");
    }

    @Test
    void aGeneratedOneLinerKeepsItsHiddenSend() {
        FakePanes tab = new FakePanes("pane-1", "pane-2");

        SnippetTerminalSend.deliverToPane(tab, "pane-2", "echo a | base64 -d | bash", true);

        assertThat(tab.received).containsExactly("pane-2 <- echo a | base64 -d | bash (hidden)");
    }

    @Test
    void aBusyPaneIsRefusedAndNothingIsTyped() {
        FakePanes tab = new FakePanes("pane-1", "pane-2");
        tab.busy.add("pane-2");

        SnippetTerminalSend.PaneSendOutcome outcome =
            SnippetTerminalSend.deliverToPane(tab, "pane-2", "sudo systemctl restart nginx", false);

        assertThat(outcome).isEqualTo(SnippetTerminalSend.PaneSendOutcome.PANE_BUSY);
        assertThat(tab.received).isEmpty();
    }

    @Test
    void aClosedPaneIsNeverSwappedForAnotherOne() {
        FakePanes tab = new FakePanes("pane-1", "pane-2");
        tab.open.remove("pane-2");

        assertThat(SnippetTerminalSend.deliverToPane(tab, "pane-2", "df -h", false))
            .isEqualTo(SnippetTerminalSend.PaneSendOutcome.PANE_GONE);
        assertThat(SnippetTerminalSend.deliverToPane(tab, null, "df -h", false))
            .isEqualTo(SnippetTerminalSend.PaneSendOutcome.PANE_GONE);
        assertThat(tab.received).isEmpty();
    }

    @Test
    void aPaneWithoutASessionReportsThatNothingWasSent() {
        FakePanes tab = new FakePanes("pane-1", "pane-2");
        tab.disconnected.add("pane-2");

        assertThat(SnippetTerminalSend.deliverToPane(tab, "pane-2", "df -h", false))
            .isEqualTo(SnippetTerminalSend.PaneSendOutcome.NOT_SENT);
        assertThat(tab.received).isEmpty();
    }
}
