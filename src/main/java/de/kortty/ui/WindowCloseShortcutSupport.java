package de.kortty.ui;

import javafx.collections.ListChangeListener;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/**
 * Installs Ctrl+Q as a close-window shortcut for secondary KorTTY windows.
 * The primary main window is intentionally excluded.
 */
public final class WindowCloseShortcutSupport {

    private static final KeyCombination CLOSE_WINDOW_SHORTCUT =
            new KeyCodeCombination(KeyCode.Q, KeyCombination.CONTROL_DOWN);
    private static final String INSTALLED_KEY = WindowCloseShortcutSupport.class.getName() + ".installed";
    private static final String STATE_KEY = WindowCloseShortcutSupport.class.getName() + ".state";

    private static Window primaryMainWindow;
    private static boolean globalWindowTrackingInstalled;

    private WindowCloseShortcutSupport() {
    }

    public static void installForMainWindow(Stage stage, boolean primary, Runnable closeAction) {
        if (stage == null) {
            return;
        }
        if (primary && primaryMainWindow == null) {
            primaryMainWindow = stage;
            installGlobalWindowTracking();
        }
        installForWindow(stage, closeAction != null ? closeAction : () -> fireCloseRequest(stage));
    }

    public static void installForDialog(Dialog<?> dialog) {
        if (dialog == null || dialog.getDialogPane() == null) {
            return;
        }
        dialog.getDialogPane().sceneProperty().addListener((obs, oldScene, newScene) ->
                installForScene(newScene, dialog::close));
        installForScene(dialog.getDialogPane().getScene(), dialog::close);
    }

    private static void installGlobalWindowTracking() {
        if (globalWindowTrackingInstalled) {
            return;
        }
        globalWindowTrackingInstalled = true;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (Window window : change.getAddedSubList()) {
                        installForWindow(window, null);
                    }
                }
            }
        });
        for (Window window : Window.getWindows()) {
            installForWindow(window, null);
        }
    }

    private static void installForWindow(Window window, Runnable closeAction) {
        if (window == null) {
            return;
        }
        window.sceneProperty().addListener((obs, oldScene, newScene) -> installForScene(newScene, closeAction));
        installForScene(window.getScene(), closeAction);
    }

    private static void installForScene(Scene scene, Runnable closeAction) {
        if (scene == null) {
            return;
        }

        ShortcutState state = (ShortcutState) scene.getProperties().get(STATE_KEY);
        if (state == null) {
            state = new ShortcutState();
            scene.getProperties().put(STATE_KEY, state);
        }
        if (closeAction != null) {
            state.closeAction = closeAction;
        }

        if (Boolean.TRUE.equals(scene.getProperties().get(INSTALLED_KEY))) {
            return;
        }
        scene.getProperties().put(INSTALLED_KEY, Boolean.TRUE);
        ShortcutState installedState = state;
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> handleShortcut(scene, installedState, event));
    }

    private static void handleShortcut(Scene scene, ShortcutState state, KeyEvent event) {
        if (!CLOSE_WINDOW_SHORTCUT.match(event)) {
            return;
        }
        event.consume();
        Window window = scene.getWindow();
        if (window == null || window == primaryMainWindow) {
            return;
        }
        if (state.closeAction != null) {
            state.closeAction.run();
        } else {
            closeWindow(window);
        }
    }

    private static void closeWindow(Window window) {
        if (window == null || window == primaryMainWindow) {
            return;
        }
        if (window instanceof Stage stage) {
            fireCloseRequest(stage);
            return;
        }
        window.hide();
    }

    private static void fireCloseRequest(Stage stage) {
        if (stage != null) {
            Event.fireEvent(stage, new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
        }
    }

    private static final class ShortcutState {
        private Runnable closeAction;
    }
}
