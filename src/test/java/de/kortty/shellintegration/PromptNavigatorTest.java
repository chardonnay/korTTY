package de.kortty.shellintegration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.PromptNavigator.Direction;
import de.kortty.shellintegration.PromptNavigator.Jump;
import de.kortty.shellintegration.PromptNavigator.View;
import java.util.List;
import java.util.OptionalLong;
import java.util.TreeSet;
import org.testng.annotations.Test;

/**
 * Previous Prompt and Next Prompt: where a jump starts and where it goes, in line ids.
 */
class PromptNavigatorTest {

    /** The view at the bottom, which cannot scroll (no scrollback): its value never changes. */
    private static final double BOTTOM = 24.0;

    @Test
    void walkingUpFromTheBottomDoesNotGetStuckWhenTheTargetsAreOnTheLastScreen() {
        Prompts prompts = new Prompts(2, 5, 8);
        PromptNavigator navigator = new PromptNavigator();
        View atPrompt = new View(9, 8L, 0, true, BOTTOM);

        assertWithMessage("the prompt of the command before the one being typed")
            .that(navigator.jump(Direction.PREVIOUS, prompts, atPrompt)).isEqualTo(new Jump.ToPrompt(5));
        // The view cannot move: line 5 is on the last screen. The next press goes on from there.
        navigator.jumped(5, BOTTOM, 9);
        assertThat(navigator.jump(Direction.PREVIOUS, prompts, atPrompt)).isEqualTo(new Jump.ToPrompt(2));
        navigator.jumped(2, BOTTOM, 9);
        assertWithMessage("nothing above the first prompt")
            .that(navigator.jump(Direction.PREVIOUS, prompts, atPrompt)).isEqualTo(new Jump.Stay());
        assertWithMessage("and back down again")
            .that(navigator.jump(Direction.NEXT, prompts, atPrompt)).isEqualTo(new Jump.ToPrompt(5));
    }

    @Test
    void aPromptOfSeveralLinesIsNotTheFirstTarget() {
        // The prompt starts on line 8 and the user types on line 9.
        Prompts prompts = new Prompts(3, 8);
        assertThat(new PromptNavigator().jump(Direction.PREVIOUS, prompts, new View(9, 8L, 0, true, BOTTOM)))
            .isEqualTo(new Jump.ToPrompt(3));
    }

    @Test
    void whileACommandRunsPreviousGoesToItsPrompt() {
        Prompts prompts = new Prompts(10, 40);
        assertThat(new PromptNavigator().jump(Direction.PREVIOUS, prompts, new View(57, null, 34, true, BOTTOM)))
            .isEqualTo(new Jump.ToPrompt(40));
    }

    @Test
    void promptsVisibleBelowTheTopLineAreNotSkipped() {
        Prompts prompts = new Prompts(90, 105, 130);
        View scrolledByHand = new View(500, null, 100, false, 0.25);
        PromptNavigator navigator = new PromptNavigator();

        assertWithMessage("the first prompt below the top line, even though it is visible")
            .that(navigator.jump(Direction.NEXT, prompts, scrolledByHand)).isEqualTo(new Jump.ToPrompt(105));
        assertThat(navigator.jump(Direction.PREVIOUS, prompts, scrolledByHand)).isEqualTo(new Jump.ToPrompt(90));
        assertWithMessage("a prompt on the top line itself is where the view is, not a target")
            .that(navigator.jump(Direction.NEXT, prompts, new View(500, null, 105, false, 0.3)))
            .isEqualTo(new Jump.ToPrompt(130));
    }

    @Test
    void scrollingByHandStartsTheNextJumpFromTheView() {
        Prompts prompts = new Prompts(90, 105, 130, 160);
        PromptNavigator navigator = new PromptNavigator();
        navigator.jumped(105, 0.30, 500);

        assertWithMessage("the view is still where the jump left it")
            .that(navigator.jump(Direction.NEXT, prompts, new View(500, null, 105, false, 0.30)))
            .isEqualTo(new Jump.ToPrompt(130));
        assertWithMessage("the user scrolled down to line 140 since")
            .that(navigator.jump(Direction.PREVIOUS, prompts, new View(500, null, 140, false, 0.45)))
            .isEqualTo(new Jump.ToPrompt(130));
        assertWithMessage("the old jump is forgotten for good, even if the value comes back")
            .that(navigator.jump(Direction.PREVIOUS, prompts, new View(500, null, 140, false, 0.30)))
            .isEqualTo(new Jump.ToPrompt(130));
    }

    @Test
    void newOutputStartsTheNextJumpFromTheCursor() {
        Prompts prompts = new Prompts(2, 5, 12);
        PromptNavigator navigator = new PromptNavigator();
        navigator.jumped(2, BOTTOM, 9);

        // Enter was pressed since: the cursor moved, so the old jump no longer counts.
        assertThat(navigator.jump(Direction.PREVIOUS, prompts, new View(13, 12L, 0, true, BOTTOM)))
            .isEqualTo(new Jump.ToPrompt(5));
    }

    @Test
    void nextPastTheLastPromptGoesToTheBottom() {
        Prompts prompts = new Prompts(10, 20);
        PromptNavigator navigator = new PromptNavigator();
        navigator.jumped(20, 0.5, 80);

        assertThat(navigator.jump(Direction.NEXT, prompts, new View(80, null, 20, false, 0.5)))
            .isEqualTo(new Jump.ToBottom());
        navigator.reset();
        assertWithMessage("after the reset the jump starts from the view again")
            .that(navigator.jump(Direction.PREVIOUS, prompts, new View(80, null, 15, false, 0.5)))
            .isEqualTo(new Jump.ToPrompt(10));
        assertWithMessage("at the bottom with nothing below, Next stays at the bottom")
            .that(new PromptNavigator().jump(Direction.NEXT, prompts, new View(30, 20L, 0, true, BOTTOM)))
            .isEqualTo(new Jump.ToBottom());
    }

    @Test
    void theStoreAnswersTheNavigatorInLineIdsAcrossTrims() {
        CommandBlockStore store = new CommandBlockStore();
        for (int line : List.of(0, 30, 60, 90)) {
            store.promptStart(line, 0);
        }
        store.shift(40);
        // The prompt that was on line 60 is on line 20 now; its id is still 60.
        PromptNavigator navigator = new PromptNavigator();
        View bottom = new View(store.lineId(51), store.currentPrompt().getAsLong(), store.lineId(30), true, BOTTOM);
        assertThat(navigator.jump(Direction.PREVIOUS, store, bottom)).isEqualTo(new Jump.ToPrompt(60));
        navigator.jumped(60, BOTTOM, store.lineId(51));
        assertWithMessage("the prompt on id 30 left the scrollback")
            .that(navigator.jump(Direction.PREVIOUS, store, bottom)).isEqualTo(new Jump.Stay());
    }

    /** Prompt lines in a set. */
    private static final class Prompts implements PromptNavigator.Prompts {
        private final TreeSet<Long> lines = new TreeSet<>();

        Prompts(long... lines) {
            for (long line : lines) {
                this.lines.add(line);
            }
        }

        @Override
        public OptionalLong promptAbove(long line) {
            Long found = lines.lower(line);
            return found != null ? OptionalLong.of(found) : OptionalLong.empty();
        }

        @Override
        public OptionalLong promptBelow(long line) {
            Long found = lines.higher(line);
            return found != null ? OptionalLong.of(found) : OptionalLong.empty();
        }
    }
}
