package de.kortty.ui;

import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.KeyChord;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.ui.actions.ActionIds;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline generator for the manual's Settings &rarr; Keyboard tab screenshot.
 *
 * <p>Mirrors {@link WindowTabScreenshotGenerator}: builds the REAL {@link SettingsDialog} headless
 * (null owner/app, empty managers on a temp dir), applies the dialog theme, selects the Keyboard tab
 * and snapshots the dialog pane at 2x to {@code app-docs/screenshots/settings/keyboard.png}. A dialog
 * without an app has no main window, so the page gets a small demo menu bar shaped like the main
 * window's (real menu labels and default shortcuts, through {@link KeymapSupport#catalog}) and one
 * changed shortcut, which is selected so the recorder below the list shows it.</p>
 *
 * <p>Run via the {@code generateKeyboardTabScreenshot} Gradle task. Exit 0 = OK.</p>
 */
public final class KeyboardTabScreenshotGenerator {

    private static final int WIDTH = 1120;

    /** Padding below the page's last line, in unscaled pixels. */
    private static final double BOTTOM_MARGIN = 24;

    /** Height used for the first layout pass, before the content's real height is known. */
    private static final int MEASURE_HEIGHT = 1600;

    private static final String OUTPUT_FILE = "app-docs/screenshots/settings/keyboard.png";

    private static final String CHANGED_ACTION = "menu.view.dashboard";

    private KeyboardTabScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        // JavaFX labels ButtonType.CANCEL from its own resources in the JVM locale of the moment
        // the class loads, which can be before the app language is set; the manual is English.
        Locale.setDefault(Locale.ENGLISH);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                writeScreenshot();
                done.countDown();
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("SCREENSHOT GENERATION TIMEOUT");
            System.exit(2);
        }
        String fail = failure.get();
        if (fail != null) {
            System.err.println("SCREENSHOT GENERATION FAILURE: " + fail);
            System.exit(1);
        }
        System.exit(0);
    }

    private static void writeScreenshot() throws Exception {
        Path tempDir = Files.createTempDirectory("kortty-keyboard-screenshot");

        GlobalSettings settings = new GlobalSettings();
        settings.setLanguage("en");
        settings.setKeyBindingOverrides(List.of(CHANGED_ACTION + "=Shortcut+Alt+B"));
        LanguageManager.getInstance().initialize(settings);

        SettingsDialog dialog = new SettingsDialog(
            null,
            null,
            new ConfigurationManager(tempDir),
            settings,
            new CredentialManager(tempDir),
            new GPGKeyManager(tempDir));
        dialog.setKeymapCatalogSource(() -> KeymapSupport.catalog(demoMenus(), KeyChord.Os.current()));
        DialogThemeHelper.applyTheme(dialog);

        DialogPane pane = dialog.getDialogPane();
        TabPane tabPane = (TabPane) field(dialog, "mainTabPane");
        Node content = selectKeyboardTab(tabPane);
        tabPane.setMinHeight(0);
        KeyboardSettingsPage page = (KeyboardSettingsPage) field(dialog, "keyboardPage");
        if (page == null) {
            throw new IllegalStateException("Keyboard page was not built");
        }
        page.select(CHANGED_ACTION);
        // Tall enough for every demo row, the fixed Tabs shortcuts at the end included.
        ((javafx.scene.layout.Region) page.node().lookup("#" + KeyboardSettingsPage.TABLE_ID)).setPrefHeight(480);

        // Measure at a deliberately tall size, then snapshot at the height the page needs.
        layoutAt(pane, MEASURE_HEIGHT);
        double chrome = MEASURE_HEIGHT - content.getLayoutBounds().getHeight();
        double wanted = content.prefHeight(content.getLayoutBounds().getWidth());
        layoutAt(pane, Math.ceil(chrome + wanted + BOTTOM_MARGIN));

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#1e1e1e"));
        params.setTransform(Transform.scale(2, 2)); // match the Retina crispness of the other shots
        WritableImage image = pane.snapshot(params, null);

        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        File outFile = new File(OUTPUT_FILE);
        File parent = outFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create output dir: " + parent.getAbsolutePath());
        }
        ImageIO.write(buffered, "png", outFile);
        System.out.println("Generated " + outFile.getAbsolutePath()
            + " (" + buffered.getWidth() + "x" + buffered.getHeight() + ")");
    }

    /** A menu bar shaped like the main window's: real labels, ids and default shortcuts. */
    private static List<Menu> demoMenus() {
        Menu file = menu("menu.file",
            item("menu.file.newTab", combo(KeyCode.T, KeyCombination.SHORTCUT_DOWN)),
            item("menu.file.newWindow", combo(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)),
            item("menu.file.reopenClosedTab", combo(KeyCode.T, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN,
                KeyCombination.SHIFT_DOWN)),
            item("menu.file.closeTab", combo(KeyCode.W, KeyCombination.SHORTCUT_DOWN)));
        Menu edit = menu("menu.edit",
            item("menu.edit.copy", combo(KeyCode.C, KeyCombination.SHORTCUT_DOWN)),
            item("menu.edit.paste", combo(KeyCode.V, KeyCombination.SHORTCUT_DOWN)),
            item("menu.edit.find", combo(KeyCode.F, KeyCombination.SHORTCUT_DOWN)),
            item("menu.edit.quickSelect", combo(KeyCode.SPACE, KeyCombination.SHORTCUT_DOWN,
                KeyCombination.SHIFT_DOWN)));
        CheckMenuItem dashboard = ActionIds.tag(new CheckMenuItem(I18n.get(CHANGED_ACTION)), CHANGED_ACTION);
        dashboard.setAccelerator(combo(KeyCode.D, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        Menu view = menu("menu.view",
            item("menu.view.commandPalette", combo(KeyCode.P, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)),
            item("menu.view.menuBar", combo(KeyCode.L, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)),
            dashboard,
            item("menu.view.terminalOnlyFullscreen", combo(KeyCode.F, KeyCombination.SHORTCUT_DOWN,
                KeyCombination.SHIFT_DOWN)),
            item("menu.view.zoomIn", combo(KeyCode.PLUS, KeyCombination.ALT_DOWN)),
            item("menu.view.zoomOut", combo(KeyCode.MINUS, KeyCombination.ALT_DOWN)));
        Menu security = menu("menu.security",
            item("menu.security.credentials", combo(KeyCode.M, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)));
        Menu configuration = menu("menu.configuration",
            item("menu.settings.global", combo(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN)));
        return List.of(file, edit, view, security, configuration);
    }

    private static Menu menu(String key, MenuItem... items) {
        Menu menu = new Menu(I18n.get(key));
        menu.getItems().addAll(items);
        return menu;
    }

    private static MenuItem item(String key, KeyCombination accelerator) {
        MenuItem item = ActionIds.tag(new MenuItem(I18n.get(key)), key);
        item.setAccelerator(accelerator);
        return item;
    }

    private static KeyCombination combo(KeyCode code, KeyCombination.Modifier... modifiers) {
        return new KeyCodeCombination(code, modifiers);
    }

    private static void layoutAt(DialogPane pane, double height) {
        pane.setMinSize(WIDTH, height);
        pane.setPrefSize(WIDTH, height);
        pane.setMaxSize(WIDTH, height);
        pane.applyCss();
        pane.resize(WIDTH, height);
        pane.layout();
    }

    private static Object field(SettingsDialog dialog, String name) throws Exception {
        Field field = SettingsDialog.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(dialog);
    }

    private static Node selectKeyboardTab(TabPane tabPane) {
        String title = I18n.get("settings.tab.keyboard");
        for (Tab tab : tabPane.getTabs()) {
            if (title.equals(tab.getText())) {
                tabPane.getSelectionModel().select(tab);
                Node content = LazyTabContent.ensureContent(tab);
                if (content == null) {
                    throw new IllegalStateException("Keyboard tab has no content after selection");
                }
                return content;
            }
        }
        throw new IllegalStateException("Keyboard tab not found in SettingsDialog");
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
