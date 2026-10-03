package de.kortty.ui;

import javafx.scene.control.Tab;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Whether an editor with possibly unsaved changes may close, decided apart from the dialog that
 * asks: the editor supplies the question and its save, this decides. A clean editor closes without
 * a question. A dirty one asks Save / Discard / Cancel: Save closes only once the save succeeded,
 * Discard closes, and Cancel — or a dialog dismissed without an answer — keeps it open.
 *
 * <p>Used by {@link FileEditorTab}, whose own Close button, Cmd/Ctrl+W, tab close button and the
 * main window's close paths ({@link HostedCloseGuards}) all end up here.
 */
final class UnsavedChangesClose {

    /** The answers of the unsaved-changes question. */
    enum Choice {
        SAVE,
        DISCARD,
        CANCEL
    }

    private UnsavedChangesClose() {
    }

    /**
     * @param modified whether the editor holds unsaved changes
     * @param ask      shows the question; only called when {@code modified}, {@code null} counts
     *                 as Cancel
     * @param save     saves the changes; {@code true} when they were stored
     * @return {@code true} when the editor may close now
     */
    static boolean mayClose(boolean modified, Supplier<Choice> ask, BooleanSupplier save) {
        if (!modified) {
            return true;
        }
        Choice choice = ask.get();
        if (choice == null) {
            return false;
        }
        return switch (choice) {
            // A failed save keeps the tab, or the changes would be gone after the error message.
            case SAVE -> save.getAsBoolean();
            case DISCARD -> true;
            case CANCEL -> false;
        };
    }

    /**
     * Makes the tab's close button ask {@code guard} first: a veto consumes the close request, so
     * the tab stays open.
     */
    static void guardCloseRequest(Tab tab, HostedCloseGuard guard) {
        Objects.requireNonNull(guard, "guard");
        tab.setOnCloseRequest(event -> {
            if (!guard.confirmHostedClose()) {
                event.consume();
            }
        });
    }
}
