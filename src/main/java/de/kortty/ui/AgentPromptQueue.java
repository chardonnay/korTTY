package de.kortty.ui;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The prompts of the agent runs of one pane, in the order they reach the one place that shows them: the row below
 * the terminal, or the approval buttons of a design. Exactly one prompt is shown at a time, and the prompt keys answer
 * only that one. An approval or a password request takes the place of what is shown; a solution card waits while any
 * prompt is registered and follows once the prompt on screen is closed. When the screen frees up, approvals and
 * passwords come before cards. FX thread only.
 *
 * @param <T> the prompt, compared by identity
 */
final class AgentPromptQueue<T> {

    /** How a prompt is shown. */
    enum Kind {
        APPROVAL, PASSWORD, CHOICE
    }

    private record Slot<T>(T prompt, Kind kind) {
    }

    private final Predicate<T> waiting;
    private final List<Slot<T>> slots = new ArrayList<>();
    private @Nullable Slot<T> shown;

    /** @param waiting whether a prompt still waits for its answer; an answered prompt is never shown again */
    AgentPromptQueue(Predicate<T> waiting) {
        this.waiting = waiting;
    }

    /**
     * Registers {@code prompt}. An approval or a password is shown at once, in place of what is shown; a solution card
     * only when nothing else is registered. Returns whether {@code prompt} is shown now.
     */
    boolean open(T prompt, Kind kind) {
        boolean free = slots.isEmpty();
        Slot<T> slot = new Slot<>(prompt, kind);
        slots.add(slot);
        if (kind == Kind.CHOICE && !free) {
            return false;
        }
        shown = slot;
        return true;
    }

    /** The prompt on screen, or {@code null} when none is. */
    @Nullable T shown() {
        return shown == null ? null : shown.prompt();
    }

    /**
     * Unregisters {@code prompt} once it is closed. Returns whether the prompt on screen changed; the caller then shows
     * {@link #shown()} again, or hides the row when that is {@code null}.
     */
    boolean close(T prompt) {
        Slot<T> closed = slotOf(prompt);
        if (closed == null) {
            return false;
        }
        slots.removeIf(slot -> slot == closed);
        if (shown != null && shown != closed && waiting.test(shown.prompt())) {
            return false;
        }
        Slot<T> next = nextWaiting();
        boolean changed = next != shown;
        shown = next;
        return changed;
    }

    private @Nullable Slot<T> slotOf(T prompt) {
        for (Slot<T> slot : slots) {
            if (slot.prompt() == prompt) {
                return slot;
            }
        }
        return null;
    }

    /** The first waiting approval or password, else the first waiting solution card. */
    private @Nullable Slot<T> nextWaiting() {
        Slot<T> card = null;
        for (Slot<T> slot : slots) {
            if (!waiting.test(slot.prompt())) {
                continue;
            }
            if (slot.kind() != Kind.CHOICE) {
                return slot;
            }
            if (card == null) {
                card = slot;
            }
        }
        return card;
    }
}
