package de.kortty.ui;

import javafx.event.Event;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

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
 * pane itself). The embedding mechanics live in {@link DialogPaneAdoption}, shared with the
 * snippet workspace's inner editor tabs.
 */
public class DialogHostTab extends Tab implements DialogPaneHost {

    /** Dedupe key for tool tabs (one per main window); {@code null} for multi-instance tools. */
    private final String toolId;
    private final ThemeAwareDialog<?> dialog;
    private final DialogPaneAdoption adoption;

    private DialogHostTab(String toolId, ThemeAwareDialog<?> dialog, Runnable afterClosed) {
        this.toolId = toolId;
        this.dialog = dialog;
        this.adoption = new DialogPaneAdoption(dialog, this, afterClosed, false);
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
        setContent(adoption.adopt());
        // Follow the dialog's title (an editor names its snippet, a workspace marks unsaved work).
        // A listener rather than a binding: tab text stays settable for any code that renames tabs.
        applyTitle(dialog.getTitle());
        dialog.titleProperty().addListener((obs, oldTitle, newTitle) -> applyTitle(newTitle));
        setOnCloseRequest(this::onTabCloseRequest);
        setOnClosed(event -> adoption.finishClose());
    }

    private void applyTitle(String title) {
        setText(title != null && !title.isBlank() ? title : "\u2026");
    }

    private void onTabCloseRequest(Event tabEvent) {
        if (!adoption.requestClose()) {
            tabEvent.consume();
        }
    }

    /** Closes the tab as if the dialog were closed programmatically (e.g. {@code close()}). */
    @Override
    public void closeProgrammatically() {
        // Resolve the pane the tab currently lives in — tabs can be dragged between windows.
        TabPane currentPane = getTabPane();
        if (currentPane != null) {
            currentPane.getTabs().remove(this);
        }
        adoption.finishClose();
    }

    @Override
    public boolean isAttached() {
        return getTabPane() != null;
    }

    @Override
    public void reveal() {
        TabPane currentPane = getTabPane();
        if (currentPane == null) {
            return;
        }
        currentPane.getSelectionModel().select(this);
        Window window = currentPane.getScene() != null ? currentPane.getScene().getWindow() : null;
        if (window instanceof Stage stage) {
            stage.setIconified(false);
            stage.toFront();
            stage.requestFocus();
        }
    }

    /**
     * Asks the hosted dialog whether the main window may dispose this tab on a path that bypasses
     * the tab's own close request (Cmd+W, close all, loading a project, window close, quit). Only
     * dialogs with unsaved work ({@link HostedCloseGuard}) are asked; the answer has no side effect
     * beyond saving when the user chose Save — the caller disposes the tab afterwards, and only
     * once all of its other prompts passed.
     *
     * @return {@code true} when the tab may be disposed
     */
    boolean confirmClose() {
        if (adoption.isCloseFinished() || !(dialog instanceof HostedCloseGuard guard)) {
            return true;
        }
        return guard.confirmHostedClose();
    }

    /** Whether {@link #confirmClose()} would ask anything right now. */
    boolean needsCloseConfirmation() {
        return !adoption.isCloseFinished()
            && dialog instanceof HostedCloseGuard guard
            && guard.needsCloseConfirmation();
    }

    /** Releases the hosted pane's resources without touching the tab list (window teardown). */
    void disposeOnWindowClose() {
        adoption.finishClose();
    }

    String getToolId() {
        return toolId;
    }

    ThemeAwareDialog<?> getHostedDialog() {
        return dialog;
    }
}
