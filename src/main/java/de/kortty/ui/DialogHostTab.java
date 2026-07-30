package de.kortty.ui;

import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

/**
 * Hosts a {@link ThemeAwareDialog}'s pane as a tab in a main window's tab pane instead of a separate
 * window (the "open tool windows as tabs" setting). The dialog is constructed as usual but never
 * shown; its {@link DialogPane} is detached from the hidden dialog window and embedded as the tab's
 * content. The host reproduces the dialog lifecycle the pane's owner class relies on:
 * <ul>
 *   <li>Button presses run the dialog's result converter, publish the result via
 *       {@link Dialog#setResult(Object)} and close the tab.</li>
 *   <li>Closing the tab (close button, close-all) fires {@code DIALOG_CLOSE_REQUEST} first, so
 *       dialogs can veto (e.g. unsaved-changes prompts), then runs the cancel conversion.</li>
 *   <li>On close, {@code DIALOG_HIDDEN} is fired exactly once so every existing
 *       {@code setOnHidden}/{@code DIALOG_HIDDEN} cleanup and result-delivery handler runs unchanged.</li>
 * </ul>
 * Child dialogs opened by the hosted pane resolve their owner via
 * {@code getDialogPane().getScene().getWindow()}, which inside the tab is the main window's stage —
 * exactly the desired owner. Theming travels with the pane ({@link DialogThemeHelper} styles the
 * pane itself).
 */
public class DialogHostTab extends Tab {

    /** Dedupe key for tool tabs (one per main window); {@code null} for multi-instance tools. */
    private final String toolId;
    private final ThemeAwareDialog<?> dialog;
    private final Runnable afterClosed;
    /** {@code DIALOG_HIDDEN} was observed — either fired by us or by the dialog's own close path. */
    private boolean hiddenSeen;
    private boolean closeFinished;

    private DialogHostTab(String toolId, ThemeAwareDialog<?> dialog, Runnable afterClosed) {
        this.toolId = toolId;
        this.dialog = dialog;
        this.afterClosed = afterClosed;
    }

    /**
     * Detaches {@code dialog}'s pane, wraps it in a tab, adds it to {@code tabPane} and selects it.
     *
     * @param toolId      dedupe key (callers dedupe via {@link #getToolId()} before hosting), or
     *                    {@code null} for tools that open a new tab each time
     * @param afterClosed optional post-close refresh, run after {@code DIALOG_HIDDEN} handlers
     */
    static DialogHostTab host(TabPane tabPane, String toolId, ThemeAwareDialog<?> dialog,
                              Runnable afterClosed) {
        DialogHostTab tab = new DialogHostTab(toolId, dialog, afterClosed);
        tab.adoptPane();
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
        return tab;
    }

    private void adoptPane() {
        DialogPane pane = dialog.getDialogPane();
        detachFromDialogWindow(pane);
        dialog.setHostTab(this);
        // Dialog.setResult(non-null) runs the dialog's own close machinery, which fires
        // DIALOG_HIDDEN itself even for a never-shown dialog. Track it so finishClose() fires
        // the event only when the dialog's own path didn't.
        dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> hiddenSeen = true);
        String title = dialog.getTitle();
        setText(title != null && !title.isBlank() ? title : "…");
        setContent(buildHolder(pane));
        interceptButtons(pane);
        setOnCloseRequest(this::onTabCloseRequest);
        setOnClosed(event -> finishClose());
    }

    /**
     * Wraps the pane so it reliably fills the tab. {@code DialogPane.layoutChildren} RESIZES ITSELF
     * to its pref/min height bounded by its dialog window's scene height — sane as a window root,
     * but hosted in a tab the never-shown dialog window reports height 0, so every layout pass the
     * pane shrinks itself to its min height while the tab content area stretches it back: a per-pulse
     * tug-of-war that renders as constant flicker and clipped/overlapping controls. Keeping the
     * pane's min and pref sizes equal to the holder's size makes the pane's own resize land exactly
     * on the tab area in every branch of that logic, ending the war.
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
        // The holder must not report the size it just pinned on the pane back up to the tab: that
        // minimum would keep the tab area from ever getting smaller, the listeners above would
        // never fire again, and the pane would stay at its largest size while the window shrinks —
        // pushing the dialog's button bar out of the window with no way to reach it.
        holder.setMinSize(0, 0);
        return holder;
    }

    /**
     * Detaches the pane from the never-shown dialog window's scene. {@code Dialog} attaches its pane
     * as that scene's root at construction time; a node cannot be a scene root and a tab's content at
     * once. Swapping the root out (rather than {@code dialog.setDialogPane(...)}) keeps
     * {@code dialog.getDialogPane()} — which the hosted classes use heavily — intact.
     */
    private static void detachFromDialogWindow(DialogPane pane) {
        Scene scene = pane.getScene();
        if (scene != null && scene.getRoot() == pane) {
            scene.setRoot(new Pane());
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
                closeProgrammatically();
            });
        }
    }

    private void onTabCloseRequest(Event tabEvent) {
        DialogEvent closeRequest = new DialogEvent(dialog, DialogEvent.DIALOG_CLOSE_REQUEST);
        Event.fireEvent(dialog, closeRequest);
        if (closeRequest.isConsumed()) {
            tabEvent.consume();
            return;
        }
        applyResult(cancelButtonType());
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

    /** Closes the tab as if the dialog were closed programmatically (e.g. {@code close()}). */
    void closeProgrammatically() {
        // Resolve the pane the tab currently lives in — tabs can be dragged between windows.
        TabPane currentPane = getTabPane();
        if (currentPane != null) {
            currentPane.getTabs().remove(this);
        }
        finishClose();
    }

    /** Ensures {@code DIALOG_HIDDEN} was delivered exactly once, then runs the post-close callback. */
    private void finishClose() {
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

    /** Releases the hosted pane's resources without touching the tab list (window teardown). */
    void disposeOnWindowClose() {
        finishClose();
    }

    String getToolId() {
        return toolId;
    }

    ThemeAwareDialog<?> getHostedDialog() {
        return dialog;
    }
}
