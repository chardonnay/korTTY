package de.kortty.ui;

import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.KeyChord;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.ui.actions.ActionIds;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless smoke for the Settings &rarr; Keyboard tab on the REAL {@link SettingsDialog}: key presses
 * on the recorder field rebind the selected action, Escape ends recording without closing the
 * dialog, a chord another action uses shows the badge and keeps Save from going ahead (the Save
 * button's action is consumed and the Keyboard tab is selected), once the conflict is resolved
 * the overrides reach the global settings, and applied to a menu bar in a window that is already
 * shown, as {@code MainWindow.applyKeymap} does after saving, a real key press runs the action that
 * now owns the chord. Exit 0 = OK.
 */
public final class KeyboardTabSmoke {

    private static final String PALETTE = "menu.view.commandPalette";
    private static final String DASHBOARD = "menu.view.dashboard";

    private KeyboardTabSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ENGLISH);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Platform.startup(() -> {
            try {
                run();
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
            } finally {
                done.countDown();
            }
        });
        boolean finished = done.await(60, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("KeyboardTabSmoke TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("KeyboardTabSmoke FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("KeyboardTabSmoke OK");
        System.exit(0);
    }

    private static void run() throws Exception {
        Path tempDir = Files.createTempDirectory("kortty-keyboard-smoke");
        GlobalSettings settings = new GlobalSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);
        SettingsDialog dialog = new SettingsDialog(null, null, new ConfigurationManager(tempDir), settings,
            new CredentialManager(tempDir), new GPGKeyManager(tempDir));
        KeyChord.Os os = KeyChord.Os.current();
        dialog.setKeymapCatalogSource(() -> KeymapSupport.catalog(menus(), os));

        TabPane tabs = (TabPane) field(dialog, "mainTabPane");
        Tab keyboardTab = (Tab) field(dialog, "keyboardTab");
        tabs.getSelectionModel().select(keyboardTab);
        Node content = LazyTabContent.ensureContent(keyboardTab);
        KeyboardSettingsPage page = (KeyboardSettingsPage) field(dialog, "keyboardPage");
        check(page != null && content == page.node(), "the page is built on selection");
        TextField recorder = (TextField) content.lookup("#" + KeyboardSettingsPage.RECORDER_ID);
        Label badge = (Label) page.badge();
        check(!badge.isVisible(), "no badge without a conflict");

        page.select(DASHBOARD);
        check(!recorder.isDisabled(), "the recorder is enabled for a rebindable action");
        // Cmd+Option+B on a Mac, Ctrl+Alt+B on Linux; Ctrl+Alt+B is AltGr on Windows, so Ctrl+F9 there.
        boolean windows = os == KeyChord.Os.WINDOWS;
        boolean mac = os.isMac();
        if (windows) {
            press(recorder, KeyCode.F9, false, true, false, false);
        } else {
            press(recorder, KeyCode.B, false, !mac, true, mac);
        }
        KeyChord expected = KeyChord.parse(windows ? "Shortcut+F9" : "Shortcut+Alt+B");
        check(expected.equals(page.model().row(DASHBOARD).chord()),
            "the key press rebinds the action: " + page.model().row(DASHBOARD).chord());

        check(press(recorder, KeyCode.ESCAPE, false, false, false, false),
            "Escape ends recording and does not reach the dialog");

        // Cmd/Ctrl+Shift+P: the palette's chord.
        page.select(DASHBOARD);
        press(recorder, KeyCode.P, true, !mac, false, mac);
        check(!page.canSave(), "a shared chord blocks saving");
        check(badge.isVisible() && "1".equals(badge.getText()), "the badge counts the conflict: " + badge.getText());

        tabs.getSelectionModel().select(0);
        Button save = saveButton(dialog);
        save.fireEvent(new ActionEvent());
        check(tabs.getSelectionModel().getSelectedItem() == keyboardTab, "Save shows the Keyboard tab");
        check(dialog.getResult() == null, "Save did not go ahead");

        page.select(PALETTE);
        press(recorder, KeyCode.F7, false, !mac, false, mac);
        check(page.canSave(), "the conflict is resolved: " + page.model().conflictsText());
        check(!badge.isVisible(), "the badge is hidden again");

        Method apply = SettingsDialog.class.getDeclaredMethod("applySettings");
        apply.setAccessible(true);
        check(Boolean.TRUE.equals(apply.invoke(dialog)), "applySettings succeeds");
        check(settings.getKeyBindingOverrides().equals(List.of(PALETTE + "=Shortcut+F7", DASHBOARD + "=Shortcut+Shift+P")),
            "the overrides reach the global settings: " + settings.getKeyBindingOverrides());

        checkShownWindowFollowsTheOverrides(settings, os);
    }

    /**
     * The saved overrides, applied to the menu bar of a window that is already shown, the way
     * MainWindow.applyKeymap does it after saving: the palette's old chord now runs Show Dashboard,
     * F7 the palette, and Dashboard's old chord nothing; after Reset All each runs its own action
     * again. JavaFX alone would leave the palette's chord without an action after the reset and
     * Dashboard's chord running the palette.
     */
    private static void checkShownWindowFollowsTheOverrides(GlobalSettings settings, KeyChord.Os os) {
        List<String> fired = new java.util.ArrayList<>();
        List<Menu> menus = menus();
        for (Menu menu : menus) {
            for (MenuItem item : menu.getItems()) {
                item.setOnAction(event -> fired.add(ActionIds.idOf(item)));
            }
        }
        javafx.scene.Scene scene = new javafx.scene.Scene(new javafx.scene.layout.VBox(
            new javafx.scene.control.MenuBar(menus.toArray(Menu[]::new))), 400, 200);
        javafx.stage.Stage stage = new javafx.stage.Stage();
        stage.setScene(scene);
        stage.show();
        try {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            KeymapSupport.Defaults defaults = KeymapSupport.defaults(menus, os);
            de.kortty.core.KeymapOverrides.Resolution keymap = de.kortty.core.KeymapOverrides
                .parse(settings.getKeyBindingOverrides())
                .resolve(defaults.rebindable(), KeymapSupport.rules(os, defaults.fixed()));
            KeymapSupport.reinstallAccelerators(scene.getAccelerators(), KeymapSupport.applyToMenus(menus, keymap));

            boolean mac = os.isMac();
            keyPress(scene, KeyCode.P, true, mac);
            check(fired.equals(List.of(DASHBOARD)), "the palette's old chord runs Show Dashboard: " + fired);
            fired.clear();
            keyPress(scene, KeyCode.F7, false, mac);
            check(fired.equals(List.of(PALETTE)), "F7 runs the palette: " + fired);
            fired.clear();
            keyPress(scene, KeyCode.D, true, mac);
            check(fired.isEmpty(), "Dashboard's old chord runs nothing: " + fired);

            // Reset All, saved: each takes back the chord the other holds, palette first.
            KeymapSupport.reinstallAccelerators(scene.getAccelerators(), KeymapSupport.applyToMenus(menus,
                de.kortty.core.KeymapOverrides.empty().resolve(defaults.rebindable(),
                    KeymapSupport.rules(os, defaults.fixed()))));
            keyPress(scene, KeyCode.P, true, mac);
            keyPress(scene, KeyCode.D, true, mac);
            keyPress(scene, KeyCode.F7, false, mac);
            check(fired.equals(List.of(PALETTE, DASHBOARD)), "the defaults run their own actions again: " + fired);
        } finally {
            stage.hide();
        }
    }

    /** A real KEY_PRESSED of Cmd (macOS) or Ctrl with {@code code}, as the scene's accelerators see it. */
    private static void keyPress(javafx.scene.Scene scene, KeyCode code, boolean shift, boolean mac) {
        javafx.event.Event.fireEvent(scene, new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "", code,
            shift, !mac, false, mac));
    }

    private static List<Menu> menus() {
        MenuItem palette = ActionIds.tag(new MenuItem("Command Palette"), PALETTE);
        palette.setAccelerator(new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        MenuItem dashboard = ActionIds.tag(new MenuItem("Show Dashboard"), DASHBOARD);
        dashboard.setAccelerator(new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        Menu view = new Menu("View");
        view.getItems().addAll(palette, dashboard);
        return List.of(view);
    }

    /** Fires one key press at the recorder; true when its filter consumed it (it never bubbled up). */
    private static boolean press(TextField target, KeyCode code, boolean shift, boolean control, boolean alt,
                                 boolean meta) {
        target.requestFocus();
        boolean[] bubbled = {false};
        javafx.event.EventHandler<KeyEvent> probe = event -> bubbled[0] = true;
        target.getParent().addEventHandler(KeyEvent.KEY_PRESSED, probe);
        try {
            target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "", code, shift, control,
                alt, meta));
        } finally {
            target.getParent().removeEventHandler(KeyEvent.KEY_PRESSED, probe);
        }
        return !bubbled[0];
    }

    private static Button saveButton(SettingsDialog dialog) {
        for (ButtonType type : dialog.getDialogPane().getButtonTypes()) {
            if (type.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                return (Button) dialog.getDialogPane().lookupButton(type);
            }
        }
        throw new IllegalStateException("no Save button");
    }

    private static Object field(SettingsDialog dialog, String name) throws Exception {
        Field field = SettingsDialog.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(dialog);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
