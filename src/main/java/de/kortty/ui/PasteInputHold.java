package de.kortty.ui;

import de.kortty.paste.PastePacer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Holds a pane's keyboard input while {@link PastePacer} sends a paste into it line by line. A key
 * typed between two pasted lines would otherwise land in the middle of the paste: in a bracketed
 * paste it would become part of the pasted text, and on a device without bracketed paste it would
 * be added to whichever pasted line happens to be open. Esc stops the paste instead.
 *
 * <ul>
 *   <li>Key presses and typed characters aimed at a pane that is pacing a paste are consumed before
 *       the terminal, korTTY's own key handling and broadcast mode see them. Pressing Esc
 *       {@linkplain PastePacer#cancel cancels} the rest of the paste.</li>
 *   <li>Chords with Cmd (Meta) pass: they are shortcuts, which the terminal never sends as input.</li>
 *   <li>The character of a held key press is held as well, even when the paste ended in between, so
 *       a key pressed during the paste never types its character afterwards.</li>
 *   <li>Key releases pass.</li>
 * </ul>
 *
 * <p>JavaFX thread, like the pacer.
 */
final class PasteInputHold {

    /** What the hold does with a key press aimed at a pane that is pacing a paste. */
    enum Action {
        /** Let it through: a shortcut. */
        PASS,
        /** Consume it. */
        HOLD,
        /** Consume it and cancel the rest of the paste. */
        CANCEL
    }

    private final PastePacer pacer;

    /** The panes whose next typed character belongs to a held key press, compared by reference. */
    private final Set<Object> holdNextTyped = Collections.newSetFromMap(new IdentityHashMap<>());

    /** @param pacer tells which panes are pacing a paste, and cancels a paste on Esc */
    PasteInputHold(PastePacer pacer) {
        this.pacer = Objects.requireNonNull(pacer, "pacer");
    }

    /**
     * Holds {@code event} when it is input for a pane that is pacing a paste.
     *
     * @param paneKey the pane the event is aimed at, as its {@link de.kortty.paste.PasteTarget#key()};
     *     null when it is aimed at no pane
     * @param event a key event on its way to the pane
     * @return whether the event was consumed
     */
    boolean filter(Object paneKey, KeyEvent event) {
        if (paneKey == null || event == null || event.isConsumed()) {
            return false;
        }
        if (event.getEventType() == KeyEvent.KEY_PRESSED) {
            holdNextTyped.remove(paneKey);
            if (!pacer.isPacing(paneKey)) {
                return false;
            }
            Action action = action(event);
            if (action == Action.PASS) {
                return false;
            }
            holdNextTyped.add(paneKey);
            if (action == Action.CANCEL) {
                pacer.cancel(paneKey);
            }
            event.consume();
            return true;
        }
        if (event.getEventType() == KeyEvent.KEY_TYPED) {
            boolean residue = holdNextTyped.remove(paneKey);
            if (event.isMetaDown() || !(residue || pacer.isPacing(paneKey))) {
                return false;
            }
            event.consume();
            return true;
        }
        return false;
    }

    /**
     * Whether the hold has nothing to do: no pane is pacing a paste and no held key press waits for
     * its character. Lets the caller skip finding the pane of every key.
     */
    boolean isIdle() {
        return holdNextTyped.isEmpty() && !pacer.isPacingAny();
    }

    /** Forgets the pane, for example when it closes. */
    void release(Object paneKey) {
        if (paneKey != null) {
            holdNextTyped.remove(paneKey);
        }
    }

    /** What the hold does with {@code event}, a key press aimed at a pane that is pacing a paste. */
    static Action action(KeyEvent event) {
        if (event.isMetaDown()) {
            return Action.PASS;
        }
        return event.getCode() == KeyCode.ESCAPE ? Action.CANCEL : Action.HOLD;
    }
}
