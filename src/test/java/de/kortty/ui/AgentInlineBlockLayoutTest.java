package de.kortty.ui;

import de.kortty.core.TerminalAgentService;
import de.kortty.model.AgentActionCategory;
import de.kortty.model.TerminalAgentModels;
import javafx.scene.input.KeyCode;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * The block of design 03 shows the question, one row per step and the open approval with its keys while
 * the run works, collapses to the answer afterwards, and never gets wider than the terminal allows.
 */
class AgentInlineBlockLayoutTest {

    private static final long NOW = 1_000_000L;

    @Test
    void aRunningBlockListsTheStepsUnderTheQuestion() {
        AgentInlineBlockLayout.BlockState state = new AgentInlineBlockLayout.BlockState("why is /var full?", NOW - 42_000);
        state.accept(message("r:message:start", "why is /var full?"), NOW);
        state.accept(action("r:command:1", "du -xh /var --max-depth=2", TerminalAgentModels.AgentActivityStatus.COMPLETED,
            "Exit 0 - 12 output lines"), NOW);
        state.accept(action("r:command:2", "journalctl --disk-usage", TerminalAgentModels.AgentActivityStatus.RUNNING, ""), NOW);

        List<String> lines = plain(AgentInlineBlockLayout.render(state, 80, "⠹", NOW));

        assertThat(lines.get(0)).startsWith("╭─ ◆ ");
        assertThat(lines.get(0)).contains("why is /var full?");
        assertThat(lines.get(0)).endsWith("0:42");
        assertThat(lines).hasSize(4);
        assertThat(lines.get(1)).startsWith("│ ✓ du -xh /var --max-depth=2");
        assertThat(lines.get(1)).endsWith("Exit 0 - 12 output lines");
        assertThat(lines.get(2)).startsWith("│ ⠹ journalctl --disk-usage");
        assertThat(lines.get(3)).startsWith("╰─ ");
    }

    @Test
    void anOpenApprovalShowsItsCommandsAndKeys() {
        AgentInlineBlockLayout.BlockState state = new AgentInlineBlockLayout.BlockState("clean up", NOW);
        state.pendingApproval = new TerminalAgentModels.Approval("r", "s", null, "Approve", "Shrink the journal?",
            List.of(new TerminalAgentModels.PlannedCommand("journalctl --vacuum-size=500M", "free space",
                TerminalAgentModels.Risk.REQUIRES_CONFIRMATION)), true);

        List<String> lines = plain(AgentInlineBlockLayout.render(state, 80, "⠹", NOW));

        assertThat(lines).contains("│ ? Shrink the journal?");
        assertThat(lines).contains("│   $ journalctl --vacuum-size=500M");
        String footer = lines.get(lines.size() - 1);
        assertThat(footer).startsWith("╰─ 1 ");
        assertThat(footer).contains("2 ");
        assertThat(footer).contains("3 ");
    }

    @Test
    void aFinishedBlockCollapsesToTheAnswer() {
        AgentInlineBlockLayout.BlockState state = new AgentInlineBlockLayout.BlockState("why is /var full?", NOW - 5_000);
        state.accept(action("r:command:1", "du -xh /var", TerminalAgentModels.AgentActivityStatus.COMPLETED, "Exit 0"), NOW);
        state.finalMessage = "/var/log/journal takes 21 GB.\nShrink it with journalctl --vacuum-size=500M.";
        state.finished = true;
        state.finishedAtMillis = NOW;
        state.finalPhase = TerminalAgentModels.Phase.DONE;

        List<String> lines = plain(AgentInlineBlockLayout.render(state, 80, "", NOW));

        assertThat(lines.get(0)).contains("✓");
        assertThat(lines.get(0)).endsWith("0:05");
        assertThat(lines.subList(1, lines.size() - 1)).containsExactly(
            "│ /var/log/journal takes 21 GB.",
            "│ Shrink it with journalctl --vacuum-size=500M.").inOrder();
        assertThat(lines.get(lines.size() - 1)).isEqualTo("╰─");
    }

    @Test
    void noLineIsWiderThanTheBlock() {
        AgentInlineBlockLayout.BlockState state = new AgentInlineBlockLayout.BlockState("x".repeat(300), NOW);
        state.accept(action("r:command:1", "y".repeat(400), TerminalAgentModels.AgentActivityStatus.COMPLETED,
            "z".repeat(90)), NOW);

        for (int columns : new int[]{20, 50, 80, 300}) {
            int width = AgentInlineBlockLayout.blockWidth(columns);
            for (AgentInlineBlockLayout.Line line : AgentInlineBlockLayout.render(state, columns, "⠹", NOW)) {
                assertThat(line.length()).isAtMost(width);
            }
        }
    }

    @Test
    void wrappingKeepsWordsAndLineBreaks() {
        assertThat(AgentInlineBlockLayout.wrap("one two three four", 9)).containsExactly("one two", "three", "four").inOrder();
        assertThat(AgentInlineBlockLayout.wrap("a\n\nb", 20)).containsExactly("a", "", "b").inOrder();
        assertThat(AgentInlineBlockLayout.wrap("abcdefghijkl", 8)).containsExactly("abcdefgh", "ijkl").inOrder();
        assertThat(AgentInlineBlockLayout.oneLine("a\tb\u0007c\nd")).isEqualTo("a b c d");
    }

    @Test
    void digitKeysAnswerAnApproval() {
        assertThat(AgentDesignHost.approvalDecisionForKey(KeyCode.DIGIT1, true))
            .isEqualTo(TerminalAgentService.ApprovalDecision.APPROVE_ONCE);
        assertThat(AgentDesignHost.approvalDecisionForKey(KeyCode.NUMPAD2, true))
            .isEqualTo(TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS);
        assertThat(AgentDesignHost.approvalDecisionForKey(KeyCode.DIGIT2, false))
            .isEqualTo(TerminalAgentService.ApprovalDecision.CANCEL);
        assertThat(AgentDesignHost.approvalDecisionForKey(KeyCode.DIGIT3, true))
            .isEqualTo(TerminalAgentService.ApprovalDecision.CANCEL);
        assertThat(AgentDesignHost.approvalDecisionForKey(KeyCode.DIGIT3, false)).isNull();
        assertThat(AgentDesignHost.approvalDecisionForKey(KeyCode.ENTER, true)).isNull();
    }

    private static TerminalAgentModels.AgentActivity action(String id, String command,
                                                            TerminalAgentModels.AgentActivityStatus status, String summary) {
        return new TerminalAgentModels.AgentActivity(id, TerminalAgentModels.AgentActivityType.ACTION, status, command,
            summary, "", TerminalAgentModels.AgentActivityTokenUsage.unknown(), 0L, false, true, AgentActionCategory.READ);
    }

    private static TerminalAgentModels.AgentActivity message(String id, String text) {
        return new TerminalAgentModels.AgentActivity(id, TerminalAgentModels.AgentActivityType.MESSAGE,
            TerminalAgentModels.AgentActivityStatus.COMPLETED, text, text, "",
            TerminalAgentModels.AgentActivityTokenUsage.unknown(), 0L, false, true);
    }

    private static List<String> plain(List<AgentInlineBlockLayout.Line> lines) {
        return lines.stream().map(line -> line.plain().stripTrailing()).toList();
    }
}
