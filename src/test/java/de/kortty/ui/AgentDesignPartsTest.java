package de.kortty.ui;

import de.kortty.model.AgentActionCategory;
import de.kortty.model.TerminalAgentModels;
import javafx.scene.input.KeyCode;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * The toolkit-free parts of the KI-Agent designs of phases 3 to 5: how a step's command, exit code and
 * output are read, the thinking dock's choice by terminal size, the focus mode's lines and the keys of
 * the approval at the cursor line.
 */
class AgentDesignPartsTest {

    private static final long NOW = 2_000_000L;
    private static final String DETAIL = "Find the largest directories\nCommand: du -xh /var | tail -3\nExit code: 0\n"
        + "stdout:\n1.4G\t/var/lib/docker\n21G\t/var/log/journal\n23G\t/var";

    @Test
    void aFinishedCommandStepGivesItsCommandExitCodeAndOutput() {
        TerminalAgentModels.AgentActivity step = action("r:command:1", "Read(du -xh /var | tail -3)",
            TerminalAgentModels.AgentActivityStatus.COMPLETED, "Exit 0 - 3 output lines", DETAIL);

        assertThat(AgentStepDetails.isCommand(step)).isTrue();
        assertThat(AgentStepDetails.command(step)).isEqualTo("du -xh /var | tail -3");
        assertThat(AgentStepDetails.exitCode(step)).isEqualTo(0);
        assertThat(AgentStepDetails.output(step)).containsExactly("1.4G\t/var/lib/docker", "21G\t/var/log/journal", "23G\t/var")
            .inOrder();
        assertThat(AgentStepDetails.kind(step)).isEqualTo("read");
        assertThat(AgentStepDetails.result(step)).isEqualTo("Exit 0 · 3 lines");
    }

    @Test
    void aRunningCommandIsReadFromItsTitleAndAnInspectionIsNoCommand() {
        TerminalAgentModels.AgentActivity running = action("r:command:2", "Run(sudo journalctl --vacuum-size=500M)",
            TerminalAgentModels.AgentActivityStatus.RUNNING, "", "Shrink the journal");
        TerminalAgentModels.AgentActivity inspect = new TerminalAgentModels.AgentActivity("r:probe",
            TerminalAgentModels.AgentActivityType.ACTION, TerminalAgentModels.AgentActivityStatus.COMPLETED,
            "Inspect SSH session", "Ubuntu 24.04", "", TerminalAgentModels.AgentActivityTokenUsage.unknown(), 0L,
            false, true, AgentActionCategory.INSPECT);

        assertThat(AgentStepDetails.command(running)).isEqualTo("sudo journalctl --vacuum-size=500M");
        assertThat(AgentStepDetails.exitCode(running)).isNull();
        assertThat(AgentStepDetails.kind(running)).isEqualTo("run");
        assertThat(AgentStepDetails.isCommand(inspect)).isFalse();
        assertThat(AgentStepDetails.kind(inspect)).isEqualTo("ssh");
        assertThat(AgentStepDetails.result(inspect)).isEqualTo("Ubuntu 24.04");
    }

    @Test
    void aFailedCommandKeepsItsExitCodeFromTheSummaryAlone() {
        TerminalAgentModels.AgentActivity step = action("r:command:3", "Run(false)",
            TerminalAgentModels.AgentActivityStatus.COMPLETED, "Exit 1 - 0 output lines", "");

        assertThat(AgentStepDetails.exitCode(step)).isEqualTo(1);
        assertThat(AgentStepDetails.result(step)).isEqualTo("Exit 1");
    }

    @Test
    void aThoughtShowsItsTokensAsItsResultNotItsTextAgain() {
        TerminalAgentModels.AgentActivity thought = new TerminalAgentModels.AgentActivity("r:thinking:1",
            TerminalAgentModels.AgentActivityType.THINKING, TerminalAgentModels.AgentActivityStatus.COMPLETED,
            "Thinking", "The journal is the biggest item", "",
            new TerminalAgentModels.AgentActivityTokenUsage(true, 1_000L, 284L, 1_284L), 0L, false, true);

        assertThat(AgentStepDetails.result(thought)).doesNotContain("journal");
        assertThat(AgentStepDetails.result(thought)).contains("1.3k");
        assertThat(AgentStepDetails.kind(thought)).isEqualTo("think");
    }

    @Test
    void theDockPicksTheDisplayByTheTerminalsSize() {
        assertThat(AgentAdaptiveDock.fresh(200, 50)).isEqualTo(AgentAdaptiveDock.SIDE);
        assertThat(AgentAdaptiveDock.fresh(120, 40)).isEqualTo(AgentAdaptiveDock.BAR);
        assertThat(AgentAdaptiveDock.fresh(200, 20)).isEqualTo(AgentAdaptiveDock.BAR);
        assertThat(AgentAdaptiveDock.fresh(60, 40)).isEqualTo(AgentAdaptiveDock.PILL);
        assertThat(AgentAdaptiveDock.fresh(120, 12)).isEqualTo(AgentAdaptiveDock.PILL);
    }

    @Test
    void theDockDoesNotFlickerAroundAThreshold() {
        AgentAdaptiveDock dock = new AgentAdaptiveDock();
        assertThat(dock.current()).isEqualTo(AgentAdaptiveDock.BAR);
        assertThat(dock.choose(AgentAdaptiveDock.SIDE_COLUMNS, 40)).isEqualTo(AgentAdaptiveDock.SIDE);
        // Just below the threshold the side timeline stays...
        assertThat(dock.choose(AgentAdaptiveDock.SIDE_COLUMNS - 3, 40)).isEqualTo(AgentAdaptiveDock.SIDE);
        // ...until the terminal is clearly narrower.
        assertThat(dock.choose(AgentAdaptiveDock.SIDE_COLUMNS - AgentAdaptiveDock.COLUMN_HYSTERESIS - 1, 40))
            .isEqualTo(AgentAdaptiveDock.BAR);

        assertThat(dock.choose(AgentAdaptiveDock.PILL_COLUMNS - 1, 40)).isEqualTo(AgentAdaptiveDock.PILL);
        assertThat(dock.choose(AgentAdaptiveDock.PILL_COLUMNS + 2, 40)).isEqualTo(AgentAdaptiveDock.PILL);
        assertThat(dock.choose(AgentAdaptiveDock.PILL_COLUMNS + AgentAdaptiveDock.COLUMN_HYSTERESIS, 40))
            .isEqualTo(AgentAdaptiveDock.BAR);
        // An unmeasured terminal keeps the choice.
        assertThat(dock.choose(0, 0)).isEqualTo(AgentAdaptiveDock.BAR);
    }

    @Test
    void theFocusModeWritesTheCommandsAsIfTheAgentHadTypedThem() {
        AgentInlineBlockLayout.BlockState state = new AgentInlineBlockLayout.BlockState("why is /var full?", NOW - 42_000);
        state.accept(action("r:command:1", "Read(du -xh /var | tail -3)", TerminalAgentModels.AgentActivityStatus.COMPLETED,
            "Exit 0 - 3 output lines", DETAIL), NOW);
        state.accept(action("r:command:2", "Read(journalctl --disk-usage)", TerminalAgentModels.AgentActivityStatus.RUNNING,
            "", "Measure the journal"), NOW);

        List<String> lines = plain(AgentFocusLayout.render(state, 80, "⠹", NOW));

        for (String line : lines) {
            assertThat(line).startsWith(AgentFocusLayout.INDENT);
            assertThat(line.length()).isAtMost(78);
        }
        assertThat(lines.get(0)).startsWith("  ◆ ");
        assertThat(lines.get(0)).contains("Exit 0");
        assertThat(lines.get(1)).isEqualTo("  › why is /var full?");
        assertThat(lines.get(2)).isEqualTo("  $ du -xh /var | tail -3");
        assertThat(lines.subList(3, 6)).containsExactly("    1.4G\t/var/lib/docker", "    21G\t/var/log/journal", "    23G\t/var")
            .inOrder();
        assertThat(lines.get(6)).startsWith("  $ journalctl --disk-usage  ⠹");
    }

    @Test
    void theFocusModeShowsAnOpenApprovalWithItsKeysAndTheAnswerAfterTheRun() {
        AgentInlineBlockLayout.BlockState state = new AgentInlineBlockLayout.BlockState("clean up", NOW);
        state.pendingApproval = approval(true, "journalctl --vacuum-size=500M");

        List<String> open = plain(AgentFocusLayout.render(state, 80, "⠹", NOW));
        assertThat(open).contains("  $ journalctl --vacuum-size=500M");
        assertThat(open.get(open.size() - 1)).startsWith("    1 ");
        assertThat(open.get(open.size() - 1)).contains(" · 3 ");

        state.pendingApproval = null;
        state.finished = true;
        state.finalMessage = "Freed 20.9 GB.";
        List<String> done = plain(AgentFocusLayout.render(state, 80, "", NOW));
        assertThat(done.get(done.size() - 1)).isEqualTo("  ◆ Freed 20.9 GB.");
    }

    @Test
    void theCursorLineAnswersWithEnterTabAndEscape() {
        assertThat(AgentDesignCursorApproval.keyFor(KeyCode.ENTER)).isEqualTo(AgentDesignCursorApproval.Key.RUN);
        assertThat(AgentDesignCursorApproval.keyFor(KeyCode.TAB)).isEqualTo(AgentDesignCursorApproval.Key.EDIT);
        assertThat(AgentDesignCursorApproval.keyFor(KeyCode.ESCAPE)).isEqualTo(AgentDesignCursorApproval.Key.DISMISS);
        assertThat(AgentDesignCursorApproval.keyFor(KeyCode.A)).isNull();
        // The character each key types next must not reach the shell.
        assertThat(AgentDesignCursorApproval.typedCharacters(AgentDesignCursorApproval.Key.RUN)).contains("\r");
        assertThat(AgentDesignCursorApproval.typedCharacters(AgentDesignCursorApproval.Key.EDIT)).isEqualTo("\t");
        assertThat(AgentDesignCursorApproval.typedCharacters(AgentDesignCursorApproval.Key.DISMISS)).isEqualTo("\u001b");
        assertThat(AgentDesignCursorApproval.isKeyCharacter('\t')).isTrue();
        assertThat(AgentDesignCursorApproval.isKeyCharacter('x')).isFalse();
    }

    @Test
    void aTakenOverApprovalIsTypedAsOneCommandLine() {
        assertThat(AgentDesignCursorApproval.commandLine(approval(false, "apt-get update", "apt-get upgrade -y")))
            .isEqualTo("apt-get update && apt-get upgrade -y");
        assertThat(AgentDesignCursorApproval.commandLine(null)).isEmpty();
    }

    private static TerminalAgentModels.Approval approval(boolean allowAlways, String... commands) {
        return new TerminalAgentModels.Approval("r", "s", null, "Approve", "Shrink the journal?",
            java.util.Arrays.stream(commands).map(command -> new TerminalAgentModels.PlannedCommand(command, "",
                TerminalAgentModels.Risk.REQUIRES_CONFIRMATION)).toList(), allowAlways);
    }

    private static TerminalAgentModels.AgentActivity action(String id, String title,
                                                            TerminalAgentModels.AgentActivityStatus status,
                                                            String summary, String detail) {
        return new TerminalAgentModels.AgentActivity(id, TerminalAgentModels.AgentActivityType.ACTION, status, title,
            summary, detail, TerminalAgentModels.AgentActivityTokenUsage.unknown(), 0L, !detail.isBlank(),
            status != TerminalAgentModels.AgentActivityStatus.RUNNING, AgentActionCategory.READ);
    }

    private static List<String> plain(List<AgentInlineBlockLayout.Line> lines) {
        return lines.stream().map(AgentInlineBlockLayout.Line::plain).toList();
    }
}
