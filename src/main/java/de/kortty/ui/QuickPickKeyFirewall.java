package de.kortty.ui;

import javafx.event.EventHandler;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The key firewall of a {@link QuickPickPopup}: a handler for {@link KeyEvent#ANY} on the popup's
 * scene that consumes every key event the search field and the result list left unconsumed.
 *
 * <p>A JavaFX popup does not own the keyboard. Its owner window hands each key event to the popup
 * first and, when the popup leaves it unconsumed, dispatches it on to its own focus owner. Behind a
 * quick pick that is often a terminal, so without this firewall Ctrl+D, Ctrl+L, Page Up or F5
 * pressed in the search box would reach the remote shell (and every other pane in broadcast mode),
 * or the editor behind the snippet quick open. Only the events the pass-through predicate accepts
 * carry on to the window behind, such as the chord that also closes the popup.
 *
 * <p>Because a scene-level handler runs before the scene's own focus traversal, Tab and Shift+Tab
 * are turned into a traversal callback here instead of being lost. Likewise Esc, which the popup's
 * hide-on-Escape only sees when nothing consumed it, goes to a close callback. Toolkit-free: the
 * decision reads only the event's own fields, never {@code KeyEvent.isShortcutDown()}.
 */
final class QuickPickKeyFirewall implements EventHandler<KeyEvent> {

    /** A pass-through predicate that lets no key event through. */
    static final Predicate<KeyEvent> NONE = event -> false;

    private final Predicate<? super KeyEvent> passThrough;
    private final Consumer<Boolean> traverse;
    private final Runnable close;

    /**
     * @param passThrough the key events that may carry on to the window behind the popup
     * @param traverse    moves the focus inside the popup; called with {@code true} for Tab and
     *                    {@code false} for Shift+Tab
     * @param close       closes the popup on a plain Esc
     */
    QuickPickKeyFirewall(@NotNull Predicate<? super KeyEvent> passThrough, @NotNull Consumer<Boolean> traverse,
                         @NotNull Runnable close) {
        this.passThrough = Objects.requireNonNull(passThrough, "passThrough");
        this.traverse = Objects.requireNonNull(traverse, "traverse");
        this.close = Objects.requireNonNull(close, "close");
    }

    @Override
    public void handle(KeyEvent event) {
        if (!consumes(event, passThrough)) {
            return;
        }
        if (isFocusTraversal(event)) {
            traverse.accept(!event.isShiftDown());
        } else if (isClose(event)) {
            close.run();
        }
        event.consume();
    }

    /** Whether the firewall stops {@code event}: every unconsumed event that {@code passThrough} rejects. */
    static boolean consumes(@NotNull KeyEvent event, @NotNull Predicate<? super KeyEvent> passThrough) {
        return !event.isConsumed() && !passThrough.test(event);
    }

    /** Tab or Shift+Tab without Ctrl, Alt or Meta, which moves the focus between the field and the list. */
    static boolean isFocusTraversal(@NotNull KeyEvent event) {
        return event.getEventType() == KeyEvent.KEY_PRESSED
            && event.getCode() == KeyCode.TAB
            && !event.isControlDown()
            && !event.isAltDown()
            && !event.isMetaDown();
    }

    /** A plain Esc, the key the popup's own hide-on-Escape answers to. */
    static boolean isClose(@NotNull KeyEvent event) {
        return event.getEventType() == KeyEvent.KEY_PRESSED
            && event.getCode() == KeyCode.ESCAPE
            && !event.isShiftDown()
            && !event.isControlDown()
            && !event.isAltDown()
            && !event.isMetaDown();
    }
}
