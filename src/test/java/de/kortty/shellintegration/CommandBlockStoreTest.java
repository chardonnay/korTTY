package de.kortty.shellintegration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.CommandBlockStore.CommandBlock;
import de.kortty.shellintegration.CommandBlockStore.Mark;
import java.time.Duration;
import java.util.List;
import java.util.NavigableMap;
import java.util.OptionalLong;
import org.testng.annotations.Test;

/**
 * The OSC 133 marks of one pane: the life of a command block, and how the marks follow the
 * scrollback with line ids that a full scrollback does not move.
 */
class CommandBlockStoreTest {

    @Test
    void aCommandGoesFromPromptToFinishedWithItsStatus() {
        CommandBlockStore store = new CommandBlockStore();
        assertThat(store.isEmpty()).isTrue();
        assertThat(store.hasPrompts()).isFalse();

        store.promptStart(4, 0);
        store.commandStart(4, 7);
        store.outputStart(5, 0, 1_000L);
        assertThat(store.blocks().getFirst().running()).isTrue();
        store.commandFinished(9, 0, 2, 5_000L);

        CommandBlock block = store.blocks().getFirst();
        assertThat(block.prompt()).isEqualTo(new Mark(4, 0));
        assertThat(block.command()).isEqualTo(new Mark(4, 7));
        assertThat(block.output()).isEqualTo(new Mark(5, 0));
        assertThat(block.end()).isEqualTo(new Mark(9, 0));
        assertThat(block.exitStatus()).isEqualTo(2);
        assertThat(block.outputStartNanos()).isEqualTo(1_000L);
        assertThat(block.endNanos()).isEqualTo(5_000L);
        assertThat(block.closed()).isTrue();
        assertThat(block.finished()).isTrue();
        assertThat(block.running()).isFalse();
        assertThat(store.isEmpty()).isFalse();
        assertThat(store.hasPrompts()).isTrue();
    }

    @Test
    void aFinishWithoutOutputStartBelongsToNoCommand() {
        CommandBlockStore store = new CommandBlockStore();
        // An empty command line: the shell reports D (with the last status), but no command ran.
        store.promptStart(0, 0);
        store.commandStart(0, 2);
        store.commandFinished(1, 0, 1, 10L);

        CommandBlock block = store.blocks().getFirst();
        assertThat(block.end()).isNull();
        assertThat(block.exitStatus()).isNull();
        assertThat(block.closed()).isFalse();
        assertWithMessage("the open block still takes its command when it comes")
            .that(store.currentPrompt()).isEqualTo(OptionalLong.of(0));
    }

    @Test
    void aFinishWithoutStatusClosesTheBlockWithoutOne() {
        CommandBlockStore store = new CommandBlockStore();
        store.promptStart(0, 0);
        store.outputStart(1, 0, 1L);
        store.commandFinished(3, 0, null, 2L);

        CommandBlock block = store.blocks().getFirst();
        assertThat(block.finished()).isTrue();
        assertThat(block.exitStatus()).isNull();
    }

    @Test
    void aPromptRedrawnOnTheSameLineReplacesTheBlock() {
        CommandBlockStore store = new CommandBlockStore();
        store.promptStart(3, 0);
        store.commandStart(3, 5);
        // A resize or Ctrl+L redraws the prompt on the same line.
        store.promptStart(3, 0);

        assertThat(store.blocks()).hasSize(1);
        assertThat(store.blocks().getFirst().command()).isNull();
        assertThat(store.blocks().getFirst().closed()).isFalse();
    }

    @Test
    void aPromptWithoutFinishClosesThePreviousBlockWithoutStatus() {
        CommandBlockStore store = new CommandBlockStore();
        store.promptStart(0, 0);
        store.outputStart(1, 0, 1L);
        // Ctrl+C, or a shell that never sends D.
        store.promptStart(4, 0);

        List<CommandBlock> blocks = store.blocks();
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).closed()).isTrue();
        assertThat(blocks.get(0).finished()).isFalse();
        assertThat(blocks.get(0).exitStatus()).isNull();
        assertThat(blocks.get(0).running()).isFalse();
        assertThat(blocks.get(1).closed()).isFalse();
    }

    @Test
    void aPromptAboveExistingBlocksDropsThemBecauseTheirLinesWereOverwritten() {
        CommandBlockStore store = new CommandBlockStore();
        store.promptStart(10, 0);
        store.outputStart(11, 0, 1L);
        store.commandFinished(15, 0, 0, 2L);
        store.promptStart(15, 0);
        // The screen was cleared without touching the scrollback: the next prompt is on the top row.
        store.promptStart(8, 0);

        assertThat(store.blocks()).hasSize(1);
        assertThat(store.blocks().getFirst().prompt()).isEqualTo(new Mark(8, 0));
    }

    @Test
    void marksOfTheCommandGoOnlyToAnOpenBlock() {
        CommandBlockStore store = new CommandBlockStore();
        store.commandStart(0, 0);
        store.outputStart(0, 0, 1L);
        store.commandFinished(0, 0, 0, 1L);
        assertWithMessage("B, C and D before any A are dropped").that(store.isEmpty()).isTrue();

        store.promptStart(0, 0);
        store.outputStart(1, 0, 1L);
        store.commandFinished(2, 0, 0, 2L);
        store.outputStart(3, 0, 3L);
        store.commandFinished(4, 0, 9, 4L);
        CommandBlock block = store.blocks().getFirst();
        assertWithMessage("a closed block keeps its marks").that(block.end()).isEqualTo(new Mark(2, 0));
        assertThat(block.exitStatus()).isEqualTo(0);
    }

    @Test
    void aShiftKeepsTheLineIdsAndDropsWhatLeftTheScrollback() {
        CommandBlockStore store = new CommandBlockStore();
        store.promptStart(2, 0);
        store.outputStart(3, 0, 1L);
        store.commandFinished(5, 0, 0, 2L);
        store.promptStart(5, 0);

        store.shift(3);
        assertThat(store.trimmed()).isEqualTo(3);
        assertWithMessage("ids do not move").that(store.blocks().get(1).prompt().line()).isEqualTo(5);
        assertWithMessage("the prompt that was on line 5 is now on line 2")
            .that(store.absoluteLine(5)).isEqualTo(2);
        assertThat(store.lineId(2)).isEqualTo(5);
        assertWithMessage("the first block still ends on a kept line").that(store.blocks()).hasSize(2);
        assertWithMessage("its prompt is gone, so it is no jump target")
            .that(store.promptAbove(5)).isEqualTo(OptionalLong.empty());

        store.shift(3);
        assertWithMessage("all of the first block left the scrollback").that(store.blocks()).hasSize(1);
        assertThat(store.hasPrompts()).isFalse();
        assertWithMessage("the open block stays while its command may still print")
            .that(store.isEmpty()).isFalse();
    }

    @Test
    void anOpenBlockSurvivesItsPromptLeavingTheScrollback() {
        CommandBlockStore store = new CommandBlockStore();
        store.promptStart(0, 0);
        store.outputStart(1, 0, 1L);
        store.shift(500);

        assertThat(store.blocks()).hasSize(1);
        assertThat(store.blocks().getFirst().running()).isTrue();
        assertThat(store.hasPrompts()).isFalse();
        // When the long command finishes, its block finishes with it.
        store.commandFinished(10, 0, 0, 2L);
        assertThat(store.blocks().getFirst().end()).isEqualTo(new Mark(510, 0));
    }

    @Test
    void aClearDropsEveryMark() {
        CommandBlockStore store = new CommandBlockStore();
        store.promptStart(0, 0);
        store.outputStart(1, 0, 1L);
        store.clear();

        assertThat(store.isEmpty()).isTrue();
        assertThat(store.hasPrompts()).isFalse();
        store.commandFinished(0, 0, 0, 2L);
        assertWithMessage("a D after the clear finds no block").that(store.isEmpty()).isTrue();
    }

    @Test
    void promptLookupsSkipPromptsThatLeftTheScrollback() {
        CommandBlockStore store = new CommandBlockStore();
        for (int line = 0; line < 50; line += 10) {
            store.promptStart(line, 0);
        }
        assertThat(store.promptAbove(25)).isEqualTo(OptionalLong.of(20));
        assertThat(store.promptAbove(20)).isEqualTo(OptionalLong.of(10));
        assertThat(store.promptAbove(0)).isEqualTo(OptionalLong.empty());
        assertThat(store.promptBelow(20)).isEqualTo(OptionalLong.of(30));
        assertThat(store.promptBelow(40)).isEqualTo(OptionalLong.empty());

        store.shift(15);
        assertWithMessage("ids 0 and 10 are above the scrollback now").that(store.promptAbove(20)).isEqualTo(OptionalLong.empty());
        assertWithMessage("from above the scrollback, the first prompt still in it")
            .that(store.promptBelow(-3)).isEqualTo(OptionalLong.of(20));
        assertThat(store.promptBelow(5)).isEqualTo(OptionalLong.of(20));
    }

    @Test
    void theCurrentPromptIsTheNewestBlockBeforeItsCommandRuns() {
        CommandBlockStore store = new CommandBlockStore();
        assertThat(store.currentPrompt()).isEqualTo(OptionalLong.empty());
        store.promptStart(7, 0);
        assertThat(store.currentPrompt()).isEqualTo(OptionalLong.of(7));
        store.outputStart(8, 0, 1L);
        assertWithMessage("a running command is no prompt the user types at")
            .that(store.currentPrompt()).isEqualTo(OptionalLong.empty());
    }

    @Test
    void theLastFinishedCommandSkipsTheOneRunningAndPromptsWithoutACommand() {
        CommandBlockStore store = new CommandBlockStore();
        assertThat(store.lastFinished()).isEmpty();
        store.promptStart(0, 0);
        store.outputStart(1, 0, 1L);
        assertWithMessage("a running command has not finished").that(store.lastFinished()).isEmpty();
        store.commandFinished(3, 0, 0, 2L);
        assertThat(store.lastFinished().orElseThrow().end()).isEqualTo(new Mark(3, 0));

        // Enter on an empty line (D without C), then a command that runs now.
        store.promptStart(3, 0);
        store.commandFinished(4, 0, 0, 3L);
        store.promptStart(4, 0);
        store.outputStart(5, 0, 4L);
        assertThat(store.lastFinished().orElseThrow().output()).isEqualTo(new Mark(1, 0));

        // A command that the next prompt closed without D did not finish either.
        store.promptStart(9, 0);
        assertThat(store.lastFinished().orElseThrow().output()).isEqualTo(new Mark(1, 0));
        store.outputStart(10, 0, 5L);
        store.commandFinished(12, 4, 1, 6L);
        CommandBlock newest = store.lastFinished().orElseThrow();
        assertThat(newest.output()).isEqualTo(new Mark(10, 0));
        assertThat(newest.exitStatus()).isEqualTo(1);
    }

    @Test
    void theLastFinishedCommandIsGoneWithTheScrollback() {
        CommandBlockStore store = new CommandBlockStore();
        store.promptStart(0, 0);
        store.outputStart(1, 0, 1L);
        store.commandFinished(3, 0, 0, 2L);
        store.promptStart(3, 0);
        store.shift(3);
        assertWithMessage("the end of the output is still there").that(store.lastFinished()).isPresent();
        store.shift(1);
        assertThat(store.lastFinished()).isEmpty();
    }

    @Test
    void aFinishIsTakenOnlyWhenItEndsACommand() {
        CommandBlockStore store = new CommandBlockStore();
        assertWithMessage("no prompt yet").that(store.commandFinished(0, 0, 0, 1L)).isFalse();
        store.promptStart(0, 0);
        assertWithMessage("an empty command line").that(store.commandFinished(1, 0, 0, 2L)).isFalse();
        store.outputStart(1, 0, 3L);
        assertThat(store.commandFinished(2, 0, 0, 4L)).isTrue();
        assertWithMessage("the block is closed").that(store.commandFinished(3, 0, 0, 5L)).isFalse();
    }

    @Test
    void theGutterShowsAFinishedCommandOnTheLineOfItsFinishMark() {
        CommandBlockStore store = new CommandBlockStore();
        store.promptStart(0, 0);
        store.commandStart(0, 2);
        store.outputStart(1, 0, 1_000_000_000L);
        store.commandFinished(4, 0, 0, 13_500_000_000L);
        store.promptStart(4, 0);
        store.commandStart(4, 2);
        store.outputStart(5, 0, 20_000_000_000L);
        store.commandFinished(6, 0, 127, 20_200_000_000L);
        store.promptStart(6, 0);

        NavigableMap<Integer, CommandStatus> statuses = store.commandStatuses();
        assertWithMessage("one status per finished command, and none for the prompt being typed at")
            .that(statuses.keySet()).containsExactly(4, 6).inOrder();
        assertThat(statuses.get(4).kind()).isEqualTo(CommandStatus.Kind.SUCCEEDED);
        assertThat(statuses.get(4).runtime()).isEqualTo(Duration.ofMillis(12_500));
        assertThat(statuses.get(6).kind()).isEqualTo(CommandStatus.Kind.FAILED);
        assertThat(statuses.get(6).exitStatus()).isEqualTo(127);
        assertThat(statuses.get(6).runtime()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void theGutterShowsARunningCommandOnItsFirstOutputLine() {
        CommandBlockStore store = new CommandBlockStore();
        store.promptStart(0, 0);
        store.outputStart(1, 0, 5L);
        assertThat(store.commandStatuses()).containsExactly(1, new CommandStatus(CommandStatus.Kind.RUNNING, null, 5L, 0L));

        // A shell that marks C before it moves to the next line: the output starts on the line below.
        CommandBlockStore early = new CommandBlockStore();
        early.promptStart(3, 0);
        early.commandStart(3, 2);
        early.outputStart(3, 9, 5L);
        assertThat(early.commandStatuses().keySet()).containsExactly(4);
    }

    @Test
    void promptsWithoutACommandAndCommandsWithoutAFinishShowNothing() {
        CommandBlockStore store = new CommandBlockStore();
        store.promptStart(0, 0);
        store.commandStart(0, 2);
        // Ctrl+C at the prompt, then a command whose shell sends no D before the next prompt.
        store.promptStart(1, 0);
        store.outputStart(2, 0, 1L);
        store.promptStart(4, 0);
        assertThat(store.commandStatuses()).isEmpty();
    }

    @Test
    void theStatusesFollowTheScrollbackAndLeaveWithIt() {
        CommandBlockStore store = new CommandBlockStore();
        store.promptStart(0, 0);
        store.outputStart(1, 0, 1L);
        store.commandFinished(3, 0, 0, 2L);
        store.promptStart(3, 0);
        store.outputStart(4, 0, 3L);
        store.commandFinished(9, 0, 1, 4L);
        store.promptStart(9, 0);

        store.shift(2);
        assertWithMessage("both moved up by the two dropped lines")
            .that(store.commandStatuses().keySet()).containsExactly(1, 7).inOrder();
        store.shift(3);
        assertWithMessage("the first finish line left the scrollback")
            .that(store.commandStatuses().keySet()).containsExactly(4);
    }

    @Test
    void theNewerCommandWinsALineTwoCommandsShare() {
        CommandBlockStore store = new CommandBlockStore();
        // A program moved the cursor up, so the next prompt starts above the line where the first
        // command finished, and both commands finish on line 5.
        store.promptStart(3, 0);
        store.outputStart(4, 0, 1L);
        store.commandFinished(5, 0, 1, 2L);
        store.promptStart(4, 0);
        assertWithMessage("the first block is still there").that(store.blocks()).hasSize(2);
        store.outputStart(4, 5, 3L);
        store.commandFinished(5, 0, 0, 4L);
        assertThat(store.commandStatuses().keySet()).containsExactly(5);
        assertThat(store.commandStatuses().get(5).kind()).isEqualTo(CommandStatus.Kind.SUCCEEDED);
    }

    @Test
    void aCommandRunsSinceTheEnterThatSubmittedIt() {
        CommandBlockStore store = new CommandBlockStore();
        store.promptStart(0, 0);
        assertWithMessage("nothing runs at a prompt").that(store.commandRunningSince(100L)).isFalse();
        store.outputStart(1, 0, 150L);
        assertThat(store.commandRunningSince(100L)).isTrue();
        assertThat(store.commandRunningSince(150L)).isTrue();
        assertWithMessage("an Enter typed into the running command, e.g. in a second ssh session")
            .that(store.commandRunningSince(200L)).isFalse();
        store.commandFinished(3, 0, 0, 300L);
        assertWithMessage("finished").that(store.commandRunningSince(100L)).isFalse();

        CommandBlockStore wrapped = new CommandBlockStore();
        wrapped.promptStart(0, 0);
        wrapped.outputStart(1, 0, Long.MIN_VALUE + 5);
        assertWithMessage("nanoTime is compared by difference, so it may run through its overflow")
            .that(wrapped.commandRunningSince(Long.MAX_VALUE - 10)).isTrue();
    }

    @Test
    void theOldestBlocksGoBeyondTheCap() {
        CommandBlockStore store = new CommandBlockStore();
        for (int line = 0; line < CommandBlockStore.MAX_BLOCKS + 5; line++) {
            store.promptStart(line, 0);
        }
        List<CommandBlock> blocks = store.blocks();
        assertThat(blocks).hasSize(CommandBlockStore.MAX_BLOCKS);
        assertThat(blocks.getFirst().prompt().line()).isEqualTo(5);
    }

    @Test
    void tenThousandMarksAndAHundredThousandShiftsStayFast() {
        CommandBlockStore store = new CommandBlockStore();
        long start = System.nanoTime();
        int line = 0;
        for (int command = 0; command < 10_000; command++) {
            store.promptStart(line, 0);
            store.commandStart(line, 2);
            store.outputStart(line + 1, 0, command);
            store.commandFinished(line + 3, 0, command % 3, command + 1L);
            line += 3;
        }
        for (int step = 0; step < 100_000; step++) {
            store.shift(1);
            store.promptAbove(line + step);
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertWithMessage("10k commands and 100k one-line trims took " + millis + " ms").that(millis).isLessThan(2_000L);
        assertWithMessage("every finished block left the scrollback").that(store.isEmpty()).isTrue();
    }
}
