package de.kortty.paste;

import java.util.function.Consumer;

/**
 * Asks the user whether a paste may reach its pane. Called on the JavaFX thread; it must not block
 * that thread (no {@code showAndWait()}), because a paste starts inside a key or mouse handler.
 */
@FunctionalInterface
public interface PasteConfirmer {

    /**
     * Shows {@code request} and reports the answer on the JavaFX thread.
     *
     * @param request what to show
     * @param answer receives {@code true} to paste and {@code false} to drop the paste; only the first
     *     call counts
     */
    void confirm(PasteConfirmationRequest request, Consumer<Boolean> answer);
}
