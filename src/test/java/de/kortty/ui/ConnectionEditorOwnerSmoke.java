package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Headed regression harness for the beep on closing the connection editor. The Connection Manager
 * opened its editor owned by the main window, which the manager itself still blocks as a modal;
 * closing the editor handed the focus to that blocked window and macOS beeped on every Save and
 * Cancel. Opens the editor from a live manager for a new and for an existing connection, closes it
 * with Cancel and with Save, and fails unless the editor is owned by the manager every time and a
 * saved connection really lands in the manager's list.
 */
public final class ConnectionEditorOwnerSmoke {

    private static final List<String> FAILURES = new ArrayList<>();

    private ConnectionEditorOwnerSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("kortty-connection-editor-owner");
        System.setProperty("user.home", home.toString());
        Locale.setDefault(Locale.ENGLISH);
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                run();
            } catch (Throwable t) {
                t.printStackTrace();
                FAILURES.add("harness: " + t);
            }
            done.countDown();
        });
        boolean finished = done.await(40, TimeUnit.SECONDS);
        if (!finished) {
            CountDownLatch dumped = new CountDownLatch(1);
            Platform.runLater(() -> {
                for (Window open : Window.getWindows()) {
                    String text = open.getScene() != null && open.getScene().getRoot() instanceof DialogPane pane
                        ? " header=" + pane.getHeaderText() + " content=" + pane.getContentText() : "";
                    System.err.println("open window: " + describe(open) + text);
                }
                FAILURES.forEach(failure -> System.err.println("  so far: " + failure));
                dumped.countDown();
            });
            dumped.await(5, TimeUnit.SECONDS);
        }
        Platform.exit();
        if (!finished) {
            System.err.println("ConnectionEditorOwnerSmoke TIMEOUT");
            System.exit(2);
        }
        if (!FAILURES.isEmpty()) {
            System.err.println("ConnectionEditorOwnerSmoke FAILURE:");
            FAILURES.forEach(failure -> System.err.println("  " + failure));
            System.exit(1);
        }
        System.out.println("ConnectionEditorOwnerSmoke OK");
        System.exit(0);
    }

    private static void run() throws Exception {
        KorTTYApplication app = new KorTTYApplication();
        app.init();
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);
        AppDesignStyleSupport.initializeGlobalStyling(settings.getAppDesign());

        // Saving encrypts connections.xml: without a master password the save fails into an alert.
        // Throwaway fixture value, generated per run.
        app.getMasterPasswordManager().setupPassword(("smoke-" + Long.toHexString(System.nanoTime())).toCharArray());

        Stage mainStage = new Stage();
        MainWindow window = new MainWindow(mainStage);
        window.show();

        ConnectionManagerDialog manager = new ConnectionManagerDialog(mainStage, app);
        manager.show();
        Window managerWindow = manager.getDialogPane().getScene().getWindow();

        Method add = ConnectionManagerDialog.class.getDeclaredMethod("addConnection");
        add.setAccessible(true);
        Method edit = ConnectionManagerDialog.class.getDeclaredMethod("editConnection", ServerConnection.class);
        edit.setAccessible(true);

        // Each call opens the editor with showAndWait; the listener answers it from inside that loop.
        openAndClose("new connection, Cancel", managerWindow, false, () -> invoke(add, manager));
        openAndClose("new connection, Save", managerWindow, true, () -> invoke(add, manager));
        List<ServerConnection> saved = app.getConfigManager().getConnections();
        if (saved.stream().noneMatch(c -> "owner-smoke.example.invalid".equals(c.getHost()))) {
            FAILURES.add("Save did not add the connection to the manager");
        }
        ServerConnection existing = saved.stream()
            .filter(c -> "owner-smoke.example.invalid".equals(c.getHost())).findFirst().orElse(null);
        if (existing != null) {
            Object local = existing;
            // editConnection only accepts the manager's own list entry.
            java.lang.reflect.Field field = ConnectionManagerDialog.class.getDeclaredField("connections");
            field.setAccessible(true);
            for (Object candidate : (List<?>) field.get(manager)) {
                if (candidate instanceof ServerConnection connection && connection.getId().equals(existing.getId())) {
                    local = connection;
                }
            }
            ServerConnection target = (ServerConnection) local;
            openAndClose("existing connection, Cancel", managerWindow, false, () -> invoke(edit, manager, target));
            openAndClose("existing connection, Save", managerWindow, true, () -> invoke(edit, manager, target));
        }
        manager.close();
        if (managerWindow.isShowing()) {
            managerWindow.hide();
        }
    }

    private static void invoke(Method method, Object target, Object... args) {
        try {
            method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void openAndClose(String label, Window expectedOwner, boolean save, Runnable open) {
        boolean[] seen = {false};
        ListChangeListener<Window> listener = new ListChangeListener<>() {
            @Override
            public void onChanged(Change<? extends Window> change) {
                while (change.next()) {
                    for (Window added : change.getAddedSubList()) {
                        if (!(added instanceof Stage stage) || stage.getScene() == null
                            || !(stage.getScene().getRoot() instanceof DialogPane pane)
                            || pane.lookup(".tab-pane") == null) {
                            continue;
                        }
                        seen[0] = true;
                        Window.getWindows().removeListener(this);
                        if (stage.getOwner() != expectedOwner) {
                            FAILURES.add(label + ": the editor is owned by " + describe(stage.getOwner())
                                + ", not by the Connection Manager");
                        }
                        Platform.runLater(() -> close(pane, save));
                    }
                }
            }
        };
        Window.getWindows().addListener(listener);
        open.run();
        if (!seen[0]) {
            Window.getWindows().removeListener(listener);
            FAILURES.add(label + ": no connection editor appeared");
        }
        System.out.println("  " + label + ": checked");
    }

    private static void close(DialogPane pane, boolean save) {
        if (save) {
            // Fill the empty plain fields of the Connection tab (name, host, user, group, tag);
            // spinner and combo editors keep their values, as text there would not parse.
            javafx.scene.control.TabPane tabs = (javafx.scene.control.TabPane) pane.lookup(".tab-pane");
            javafx.scene.Node first = tabs.getTabs().get(0).getContent();
            for (javafx.scene.Node node : first.lookupAll(".text-field")) {
                if (node instanceof TextField field && !(field.getParent() instanceof javafx.scene.control.Spinner<?>)
                    && !(field.getParent() instanceof javafx.scene.control.ComboBoxBase<?>)
                    && (field.getText() == null || field.getText().isEmpty()) && field.isEditable() && !field.isDisabled()) {
                    field.setText("owner-smoke.example.invalid");
                }
            }
        }
        for (ButtonType type : pane.getButtonTypes()) {
            boolean wanted = save
                ? type.getButtonData() == ButtonBar.ButtonData.OK_DONE
                : type.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE;
            if (wanted && pane.lookupButton(type) instanceof Button button) {
                if (button.isDisabled()) {
                    FAILURES.add("the " + (save ? "Save" : "Cancel") + " button is disabled");
                    pane.getScene().getWindow().hide();
                    return;
                }
                button.fire();
                return;
            }
        }
    }

    private static String describe(Window window) {
        if (window instanceof Stage stage) {
            return "\"" + stage.getTitle() + "\"";
        }
        return String.valueOf(window);
    }
}
