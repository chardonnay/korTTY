package de.kortty.ui;

import com.sithtermfx.ui.SithTermFxWidget;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;

/**
 * What a click on a desktop notification does: bring the window that holds the tab the
 * notification is about to the front, select the tab and focus the pane that asked. The tab and the
 * pane are held weakly — a notification can sit in the notification centre for hours after its tab
 * was closed, and must not keep the terminal alive — and the window is resolved only when the click
 * comes, because a tab can be dragged to another window in the meantime. A tab that is gone by then
 * makes the click do nothing. JavaFX thread.
 */
final class NotificationActivation {

    private NotificationActivation() {
    }

    /**
     * The click action for a notification about {@code widget}, a pane of {@code tab}: the window to
     * the front, the tab selected, the pane focused; just the tab when {@code widget} is
     * {@code null} or no longer one of its panes.
     */
    static Runnable focusPane(Tab tab, @Nullable SithTermFxWidget widget) {
        WeakReference<Tab> tabRef = new WeakReference<>(tab);
        WeakReference<SithTermFxWidget> widgetRef = new WeakReference<>(widget);
        return () -> {
            Tab target = tabRef.get();
            if (target != null) {
                focus(target, widgetRef.get());
            }
        };
    }

    /** The click action for a notification about {@code tab} as a whole, such as an AI swarm's. */
    static Runnable focusTab(Tab tab) {
        return focusPane(tab, null);
    }

    /**
     * Brings {@code tab}'s window to the front, selects {@code tab} and focuses {@code widget} when it
     * still is one of the tab's panes; false when the tab is no longer in any window.
     */
    static boolean focus(Tab tab, @Nullable SithTermFxWidget widget) {
        TabPane tabPane = tab.getTabPane();
        Scene scene = tabPane != null ? tabPane.getScene() : null;
        Window window = scene != null ? scene.getWindow() : null;
        if (tabPane == null || window == null) {
            return false;
        }
        if (window instanceof Stage stage) {
            raise(stage);
        } else {
            window.requestFocus();
        }
        tabPane.getSelectionModel().select(tab);
        if (widget != null && tab instanceof TerminalTab terminalTab) {
            TerminalView view = terminalTab.getTerminalView();
            if (view != null && view.getOrderedWidgets().contains(widget)) {
                // The tab-selection listener focuses the tab's current widget one FX pulse later; land
                // on the notification's pane after that so the specific split pane wins.
                Platform.runLater(() -> Platform.runLater(() -> view.focusWidget(widget)));
            }
        }
        return true;
    }

    /** Shows {@code stage} if hidden, restores it if minimised and brings it to the front with focus. */
    static void raise(Stage stage) {
        if (!stage.isShowing()) {
            stage.show();
        }
        if (stage.isIconified()) {
            stage.setIconified(false);
        }
        stage.toFront();
        stage.requestFocus();
    }
}
