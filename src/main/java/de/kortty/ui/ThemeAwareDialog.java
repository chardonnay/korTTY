package de.kortty.ui;

import javafx.scene.control.Dialog;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Base dialog that automatically applies the active KorTTY theme.
 *
 * <p>Subclasses can alternatively be embedded outside their own window — as a main-window tab via
 * {@link DialogHostTab} (the "open tool windows as tabs" setting) or as an inner tab of the snippet
 * workspace. In that mode the dialog itself is never shown, so {@link #isShowing()} stays
 * {@code false} and {@code close()} would be a no-op — hosted-aware code uses
 * {@link #isOpenAsDialogOrTab()} and {@link #closeDialogOrHostTab()} instead.
 */
public class ThemeAwareDialog<R> extends Dialog<R> {

    private DialogPaneHost paneHost;

    public ThemeAwareDialog() {
        DialogThemeHelper.applyTheme(this);
    }

    /** Set by {@link DialogPaneAdoption} before the pane joins its host's scene. */
    void setPaneHost(DialogPaneHost paneHost) {
        this.paneHost = paneHost;
    }

    /** Runs {@link #onHostedAttached()}; called by {@link DialogPaneAdoption} once the pane is adopted. */
    final void notifyHostedAttached() {
        onHostedAttached();
    }

    /**
     * Called once when this dialog's pane has been adopted by a host (main-window tab, workspace
     * tab). A hosted dialog is never shown, so {@code setOnShown}/{@code DIALOG_SHOWN} never fire;
     * subclasses that start work "when opened" (subscriptions, focus) do it here as well.
     */
    protected void onHostedAttached() {
    }

    /**
     * Whether this dialog's pane is embedded outside its own window (a main-window tab or an inner
     * workspace tab) instead of being shown as a window.
     */
    public final boolean isHostedInTab() {
        return paneHost != null;
    }

    /** Whether the dialog is currently open, either as a window or as a hosted pane. */
    protected final boolean isOpenAsDialogOrTab() {
        return isShowing() || (paneHost != null && paneHost.isAttached());
    }

    /**
     * Closes this dialog regardless of presentation: removes the host (firing the same
     * {@code DIALOG_HIDDEN} lifecycle a window close would), or calls {@link #close()}.
     */
    protected final void closeDialogOrHostTab() {
        if (paneHost != null) {
            paneHost.closeProgrammatically();
        } else {
            close();
        }
    }

    /** Brings this dialog to the front: selects its host tab, or raises its own window. */
    protected final void revealDialogOrHost() {
        if (paneHost != null) {
            paneHost.reveal();
            return;
        }
        Window window = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
        if (window instanceof Stage stage) {
            stage.setIconified(false);
            stage.toFront();
            stage.requestFocus();
        }
    }
}
