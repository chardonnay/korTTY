package de.kortty.shellintegration;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Previous Prompt and Next Prompt for one terminal pane: which prompt a jump goes to.
 *
 * <p>A jump starts from a reference line:
 * <ul>
 *   <li>the prompt the last jump went to, while the view is still where that jump put it (the scroll
 *       bar holds the value korTTY set and the cursor has not moved), so jumps chain even when the
 *       target was already on screen and the view could not move;</li>
 *   <li>otherwise, at the bottom of the scrollback, the cursor line, or the prompt the user is
 *       typing at when that is above it (a prompt of several lines), so Previous Prompt goes to the
 *       command before;</li>
 *   <li>otherwise, scrolled up by hand, the top visible line.</li>
 * </ul>
 * Previous Prompt goes to the closest prompt above the reference; Next Prompt to the closest one
 * below it, and after the last one back to the bottom.
 *
 * <p>Lines are {@link CommandBlockStore} line ids, which a full scrollback does not move. FX-free;
 * use it from one thread, the UI thread in the application.
 */
public final class PromptNavigator {

    /** Which way a jump goes. */
    public enum Direction {
        PREVIOUS,
        NEXT
    }

    /** Where the prompts are. {@link CommandBlockStore} is one. */
    public interface Prompts {

        /** The closest prompt strictly above {@code line}. */
        OptionalLong promptAbove(long line);

        /** The closest prompt strictly below {@code line}. */
        OptionalLong promptBelow(long line);
    }

    /**
     * The pane as the jump finds it.
     *
     * @param cursorLine    the line id of the cursor
     * @param currentPrompt the line id of the prompt the user is typing at, or {@code null}
     * @param topLine       the line id of the top visible line
     * @param atBottom      whether the view shows the bottom of the scrollback (scroll origin 0)
     * @param scrollValue   the scroll bar's value now
     */
    public record View(long cursorLine, Long currentPrompt, long topLine, boolean atBottom, double scrollValue) {
    }

    /** Where a jump goes. */
    public sealed interface Jump {

        /** Show the prompt on {@code line} at the top of the view, or as close to it as the scrollback allows. */
        record ToPrompt(long line) implements Jump {
        }

        /** Back to the bottom of the scrollback: Next Prompt after the last prompt. */
        record ToBottom() implements Jump {
        }

        /** Nowhere: no prompt above the reference. */
        record Stay() implements Jump {
        }
    }

    // The last jump and the view it left behind; null when the next jump starts afresh.
    private Long lastTarget;
    private double lastScrollValue;
    private long lastCursorLine;

    /**
     * Where a jump in {@code direction} goes from {@code view}. It only forgets a last jump the view
     * has moved away from; call {@link #jumped} or {@link #reset} once the jump is done.
     */
    public Jump jump(Direction direction, Prompts prompts, View view) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(prompts, "prompts");
        Objects.requireNonNull(view, "view");
        long reference = reference(view);
        if (direction == Direction.PREVIOUS) {
            OptionalLong above = prompts.promptAbove(reference);
            return above.isPresent() ? new Jump.ToPrompt(above.getAsLong()) : new Jump.Stay();
        }
        OptionalLong below = prompts.promptBelow(reference);
        return below.isPresent() ? new Jump.ToPrompt(below.getAsLong()) : new Jump.ToBottom();
    }

    /**
     * Records where a jump went and the view it left: the scroll bar's value after the jump and
     * the cursor line then. The next jump starts from {@code target} while both are unchanged.
     */
    public void jumped(long target, double scrollValue, long cursorLine) {
        lastTarget = target;
        lastScrollValue = scrollValue;
        lastCursorLine = cursorLine;
    }

    /** Forgets the last jump, after a jump back to the bottom. */
    public void reset() {
        lastTarget = null;
    }

    /** The line the next jump starts from; see the class comment. */
    long reference(View view) {
        if (lastTarget != null) {
            if (Double.compare(view.scrollValue(), lastScrollValue) == 0 && view.cursorLine() == lastCursorLine) {
                return lastTarget;
            }
            lastTarget = null;
        }
        if (view.atBottom()) {
            Long current = view.currentPrompt();
            return current != null ? Math.min(current, view.cursorLine()) : view.cursorLine();
        }
        return view.topLine();
    }
}
