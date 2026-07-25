package de.kortty.ui;

import javafx.scene.control.Dialog;

/**
 * Base dialog that automatically applies the active KorTTY theme.
 *
 * <p>Subclasses can alternatively be hosted as a main-window tab via {@link DialogHostTab} (the
 * "open tool windows as tabs" setting). In that mode the dialog itself is never shown, so
 * {@link #isShowing()} stays {@code false} and {@code close()} would be a no-op — hosted-aware
 * code uses {@link #isOpenAsDialogOrTab()} and {@link #closeDialogOrHostTab()} instead.
 */
public class ThemeAwareDialog<R> extends Dialog<R> {

    private DialogHostTab hostTab;

    public ThemeAwareDialog() {
        DialogThemeHelper.applyTheme(this);
    }

    void setHostTab(DialogHostTab hostTab) {
        this.hostTab = hostTab;
    }

    /** Whether this dialog's pane is embedded as a main-window tab instead of an own window. */
    public final boolean isHostedInTab() {
        return hostTab != null;
    }

    /** Whether the dialog is currently open, either as a window or as a hosted tab. */
    protected final boolean isOpenAsDialogOrTab() {
        return isShowing() || (hostTab != null && hostTab.getTabPane() != null);
    }

    /**
     * Closes this dialog regardless of presentation: removes the host tab (firing the same
     * {@code DIALOG_HIDDEN} lifecycle a window close would), or calls {@link #close()}.
     */
    protected final void closeDialogOrHostTab() {
        if (hostTab != null) {
            hostTab.closeProgrammatically();
        } else {
            close();
        }
    }
}
