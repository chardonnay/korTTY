package de.kortty.ui;

import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * Embeds a never-shown {@link ThemeAwareDialog}'s {@link DialogPane} somewhere else (a main-window
 * tab, an inner workspace tab) and reproduces the dialog lifecycle its owner class relies on:
 * <ul>
 *   <li>Button presses run the dialog's result converter, publish the result via
 *       {@link Dialog#setResult(Object)} and close the host.</li>
 *   <li>{@link #requestClose()} fires {@code DIALOG_CLOSE_REQUEST} first, so dialogs can veto
 *       (unsaved-changes prompts), then runs the cancel conversion.</li>
 *   <li>{@link #finishClose()} delivers {@code DIALOG_HIDDEN} exactly once, so every existing
 *       {@code setOnHidden}/{@code DIALOG_HIDDEN} cleanup and result handler runs unchanged.</li>
 * </ul>
 * A dialog is single-use: its hidden handlers typically dispose native resources (Monaco), so an
 * adopted dialog must never be adopted again.
 */
final class DialogPaneAdoption {

    private final ThemeAwareDialog<?> dialog;
    private final DialogPaneHost host;
    private final Runnable afterClosed;
    private final boolean embedded;
    private boolean adopted;
    /** {@code DIALOG_HIDDEN} was observed — either fired by us or by the dialog's own close path. */
    private boolean hiddenSeen;
    private boolean closeFinished;

    /**
     * @param afterClosed optional post-close callback, run after the {@code DIALOG_HIDDEN} handlers
     * @param embedded    {@code true} for a pane nested inside another dialog: its empty button bar
     *                    and outer padding are collapsed so it does not frame itself twice
     */
    DialogPaneAdoption(ThemeAwareDialog<?> dialog, DialogPaneHost host, Runnable afterClosed, boolean embedded) {
        this.dialog = dialog;
        this.host = host;
        this.afterClosed = afterClosed;
        this.embedded = embedded;
    }

    /** Detaches the pane from its hidden dialog window and returns the node the host shows. */
    Region adopt() {
        if (adopted) {
            throw new IllegalStateException("A dialog pane can only be adopted once");
        }
        adopted = true;
        DialogPane pane = dialog.getDialogPane();
        detachFromDialogWindow(pane);
        // The host must be known before the pane joins its new scene: scene listeners (Ctrl+Q
        // installation) ask isHostedInTab() at that moment.
        dialog.setPaneHost(host);
        // Dialog.setResult(non-null) runs the dialog's own close machinery, which fires
        // DIALOG_HIDDEN itself even for a never-shown dialog. Track it so finishClose() fires
        // the event only when the dialog's own path didn't.
        dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> hiddenSeen = true);
        StackPane holder = buildHolder(pane);
        interceptButtons(pane);
        if (embedded) {
            collapseChrome(pane);
        }
        dialog.notifyHostedAttached();
        return holder;
    }

    /**
     * Wraps the pane so it reliably fills its host. {@code DialogPane.layoutChildren} RESIZES ITSELF
     * to its pref/min height bounded by its dialog window's scene height — sane as a window root,
     * but hosted elsewhere the never-shown dialog window reports height 0, so every layout pass the
     * pane shrinks itself to its min height while the host stretches it back: a per-pulse
     * tug-of-war that renders as constant flicker and clipped/overlapping controls. Keeping the
     * pane's min and pref sizes equal to the holder's size makes the pane's own resize land exactly
     * on the host area in every branch of that logic, ending the war.
     */
    private static StackPane buildHolder(DialogPane pane) {
        StackPane holder = new StackPane(pane);
        holder.widthProperty().addListener((obs, oldWidth, width) -> {
            pane.setMinWidth(width.doubleValue());
            pane.setPrefWidth(width.doubleValue());
        });
        holder.heightProperty().addListener((obs, oldHeight, height) -> {
            pane.setMinHeight(height.doubleValue());
            pane.setPrefHeight(height.doubleValue());
        });
        pane.setMaxWidth(Double.MAX_VALUE);
        pane.setMaxHeight(Double.MAX_VALUE);
        // The holder must not report the size it just pinned on the pane back up to the host: that
        // minimum would keep the host area from ever getting smaller, the listeners above would
        // never fire again, and the pane would stay at its largest size while the window shrinks —
        // pushing the dialog's button bar out of the window with no way to reach it.
        holder.setMinSize(0, 0);
        return holder;
    }

    /**
     * Detaches the pane from the never-shown dialog window's scene. {@code Dialog} attaches its pane
     * as that scene's root at construction time; a node cannot be a scene root and a host's content
     * at once. Swapping the root out (rather than {@code dialog.setDialogPane(...)}) keeps
     * {@code dialog.getDialogPane()} — which the hosted classes use heavily — intact.
     */
    private static void detachFromDialogWindow(DialogPane pane) {
        Scene scene = pane.getScene();
        if (scene != null && scene.getRoot() == pane) {
            scene.setRoot(new Pane());
        }
    }

    /**
     * A pane nested inside another dialog has no buttons of its own; its (empty) button bar would
     * still reserve a strip at the bottom and its padding would frame the content twice.
     */
    private static void collapseChrome(DialogPane pane) {
        pane.setStyle("-fx-padding: 0;");
        Node buttonBar = pane.lookup(".button-bar");
        if (buttonBar != null && pane.getButtonTypes().isEmpty()) {
            buttonBar.setVisible(false);
            buttonBar.setManaged(false);
            if (buttonBar instanceof Region region) {
                region.setMinHeight(0);
                region.setPrefHeight(0);
                region.setMaxHeight(0);
            }
        }
    }

    private void interceptButtons(DialogPane pane) {
        for (ButtonType buttonType : pane.getButtonTypes()) {
            if (!(pane.lookupButton(buttonType) instanceof Button button)) {
                continue;
            }
            // Registered after any filters the dialog itself installed (e.g. save-without-closing
            // buttons that consume the event), so those keep full control.
            button.addEventFilter(ActionEvent.ACTION, event -> {
                if (event.isConsumed()) {
                    return;
                }
                event.consume();
                applyResult(buttonType);
                host.closeProgrammatically();
            });
        }
    }

    /**
     * Asks the dialog whether it may close. Returns {@code false} when a {@code DIALOG_CLOSE_REQUEST}
     * handler vetoed (the dialog may still close itself afterwards, e.g. after "Save" in its own
     * unsaved-changes prompt); otherwise runs the cancel conversion and returns {@code true} — the
     * caller then removes the host.
     */
    boolean requestClose() {
        if (closeFinished) {
            return true;
        }
        DialogEvent closeRequest = new DialogEvent(dialog, DialogEvent.DIALOG_CLOSE_REQUEST);
        Event.fireEvent(dialog, closeRequest);
        if (closeRequest.isConsumed()) {
            return false;
        }
        applyResult(cancelButtonType());
        return true;
    }

    /** Runs the result converter for {@code buttonType} and publishes the result on the dialog. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyResult(ButtonType buttonType) {
        var converter = dialog.getResultConverter();
        if (converter == null || buttonType == null) {
            return;
        }
        Object result = converter.call(buttonType);
        ((Dialog) dialog).setResult(result);
    }

    private ButtonType cancelButtonType() {
        for (ButtonType buttonType : dialog.getDialogPane().getButtonTypes()) {
            ButtonBar.ButtonData data = buttonType.getButtonData();
            if (data != null && data.isCancelButton()) {
                return buttonType;
            }
        }
        return null;
    }

    /** Ensures {@code DIALOG_HIDDEN} was delivered exactly once, then runs the post-close callback. */
    void finishClose() {
        if (closeFinished) {
            return;
        }
        closeFinished = true;
        if (!hiddenSeen) {
            Event.fireEvent(dialog, new DialogEvent(dialog, DialogEvent.DIALOG_HIDDEN));
        }
        if (afterClosed != null) {
            afterClosed.run();
        }
    }

    boolean isCloseFinished() {
        return closeFinished;
    }
}
