package de.kortty.shellintegration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.CommandBlockStore.CommandBlock;
import de.kortty.shellintegration.CommandBlockStore.Mark;
import de.kortty.shellintegration.CommandStatus.Kind;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * What the timestamp gutter shows for a command the shell marked: a glyph per state, so the state
 * does not depend on telling green from red, and the real runtime from C to D.
 */
class CommandStatusTest {

    private static final Mark PROMPT = new Mark(0, 0);
    private static final Mark OUTPUT = new Mark(1, 0);
    private static final Mark END = new Mark(3, 0);

    @Test
    void theExitStatusDecidesTheState() {
        assertThat(CommandStatus.of(finished(0)).kind()).isEqualTo(Kind.SUCCEEDED);
        assertThat(CommandStatus.of(finished(1)).kind()).isEqualTo(Kind.FAILED);
        assertThat(CommandStatus.of(finished(130)).kind()).isEqualTo(Kind.FAILED);
        assertThat(CommandStatus.of(finished(-1)).kind()).isEqualTo(Kind.FAILED);
        assertThat(CommandStatus.of(finished(null)).kind()).isEqualTo(Kind.NO_STATUS);
        assertThat(CommandStatus.of(finished(1)).exitStatus()).isEqualTo(1);
    }

    @Test
    void aSubmittedCommandRunsUntilItFinishes() {
        CommandBlock running = new CommandBlock(PROMPT, null, OUTPUT, null, null, 2_000_000_000L, 0L, false);
        CommandStatus status = CommandStatus.of(running);
        assertThat(status.kind()).isEqualTo(Kind.RUNNING);
        assertThat(status.running()).isTrue();
        assertWithMessage("no runtime until it finished").that(status.runtime()).isNull();
        assertThat(status.runningFor(5_000_000_000L)).isEqualTo(Duration.ofSeconds(3));
        assertWithMessage("a clock read before the mark counts as no time")
            .that(status.runningFor(1L)).isEqualTo(Duration.ZERO);
    }

    @Test
    void promptsWithoutACommandAndCommandsClosedWithoutAFinishHaveNoStatus() {
        assertThat(CommandStatus.of(new CommandBlock(PROMPT, null, null, null, null, 0L, 0L, false))).isNull();
        assertThat(CommandStatus.of(new CommandBlock(PROMPT, null, null, null, null, 0L, 0L, true))).isNull();
        assertWithMessage("the next prompt closed it without a D mark")
            .that(CommandStatus.of(new CommandBlock(PROMPT, null, OUTPUT, null, null, 5L, 0L, true))).isNull();
    }

    @Test
    void theRuntimeRunsFromOutputStartToFinish() {
        CommandStatus status = CommandStatus.of(
            new CommandBlock(PROMPT, null, OUTPUT, END, 0, 1_000_000_000L, 13_250_000_000L, true));
        assertThat(status.runtime()).isEqualTo(Duration.ofMillis(12_250));
        assertThat(CommandStatus.of(new CommandBlock(PROMPT, null, OUTPUT, END, 0, 10L, 5L, true)).runtime())
            .isEqualTo(Duration.ZERO);
    }

    @Test
    void everyStateWithAnExitStatusHasAGlyphOfItsOwn() {
        Set<String> glyphs = new HashSet<>();
        for (Kind kind : new Kind[] {Kind.RUNNING, Kind.SUCCEEDED, Kind.FAILED}) {
            assertWithMessage(kind + " needs a glyph besides its colour").that(kind.glyph()).isNotEmpty();
            glyphs.add(kind.glyph());
        }
        assertThat(glyphs).hasSize(3);
        assertThat(Kind.SUCCEEDED.glyph()).isEqualTo("✓");
        assertThat(Kind.FAILED.glyph()).isEqualTo("✗");
        assertThat(Kind.RUNNING.glyph()).isEqualTo("…");
        assertWithMessage("no status reported, so nothing to claim").that(Kind.NO_STATUS.glyph()).isEmpty();
    }

    private static CommandBlock finished(Integer exitStatus) {
        return new CommandBlock(PROMPT, null, OUTPUT, END, exitStatus, 1L, 2L, true);
    }
}
