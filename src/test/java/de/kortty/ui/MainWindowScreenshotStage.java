package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuButton;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shows the REAL {@link MainWindow} for the guide's main-window and menu screenshots, prints
 * {@code READY x y w h} for the region to capture, and keeps the window open until the file given
 * as the first program argument appears (capture-done flag) or 45 seconds pass. The companion of
 * {@link SettingsTabScreenshotStage}, for the shots that live outside the Settings dialog.
 *
 * <p>A menu screenshot cannot be produced with {@code Scene.snapshot}: an open menu is a separate
 * popup window, so it is absent from the owning scene's snapshot. Hence the on-screen route, where
 * an external {@code screencapture -R} records whatever is actually on screen.</p>
 *
 * <p>The window is real, not a rebuild: {@link KorTTYApplication#init()} creates the singleton and
 * every manager without a GUI and without asking for the master password (that lives in
 * {@code start()}), which is exactly what {@code MainWindow}'s constructor needs. Everything runs
 * against an isolated, empty home in English, so no real connection, host or credential of the
 * developer's own installation can end up in a published screenshot.</p>
 *
 * <p>System properties:</p>
 * <ul>
 *   <li>{@code kortty.mainWindowMenu} — i18n key of a menu to open (e.g. {@code menu.ai}); the
 *       reported region then covers the menu title plus its popup. Omitted: the whole window.</li>
 *   <li>{@code kortty.screenshotHome} — isolated home to use (default: a temp directory).</li>
 *   <li>{@code kortty.mainWindowWidth} / {@code kortty.mainWindowHeight} — window size in points.</li>
 * </ul>
 */
public final class MainWindowScreenshotStage {

    private static final double X = 80;
    private static final double Y = 80;
    /** Matches the catalog's main-window capture (1443x1500 including the 28pt title bar). */
    private static final double WIDTH = sizeProperty("kortty.mainWindowWidth", 1443);
    private static final double HEIGHT = sizeProperty("kortty.mainWindowHeight", 1500);
    /**
     * Breathing room around a menu title and its popup. Wider horizontally on purpose: the crop
     * then also shows the neighbouring menu titles, which is what places the menu in the window
     * for the reader (the catalog's previous menu shots have that context too).
     */
    private static final double MENU_PADDING_X = 110;
    private static final double MENU_PADDING_Y = 12;

    private MainWindowScreenshotStage() {
    }

    /** Double has no system-property accessor (only Integer/Long/Boolean do). */
    private static double sizeProperty(String key, double fallback) {
        try {
            String value = System.getProperty(key);
            return value != null && !value.isBlank() ? Double.parseDouble(value.trim()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static void main(String[] args) throws Exception {
        Path doneFlag = args.length > 0 && !args[0].isBlank() ? Path.of(args[0]) : null;
        String menuKey = System.getProperty("kortty.mainWindowMenu", "").trim();
        String homeOverride = System.getProperty("kortty.screenshotHome");
        Path isolatedHome = homeOverride != null && !homeOverride.isBlank()
            ? Files.createDirectories(Path.of(homeOverride))
            : Files.createTempDirectory("kortty-main-window-screenshot");
        // Set before the application class is touched: its static initializer already resolves the
        // configuration directory from user.home.
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                show(doneFlag, menuKey, done);
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
                done.countDown();
            }
        });

        boolean finished = done.await(90, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (failure.get() != null) {
            System.err.println("MAIN WINDOW SCREENSHOT FAILURE: " + failure.get());
            System.exit(1);
        }
        if (!finished) {
            System.err.println("MAIN WINDOW SCREENSHOT TIMEOUT");
            System.exit(2);
        }
        System.exit(0);
    }

    private static void show(Path doneFlag, String menuKey, CountDownLatch done) throws Exception {
        KorTTYApplication app = new KorTTYApplication();
        app.init(); // singleton + managers; no GUI, no master-password prompt

        // The isolated home has no settings file, so the language would follow the host locale.
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);

        Stage stage = new Stage();
        MainWindow window = new MainWindow(stage);
        window.show();
        // Size and position after show(): MainWindow.show() applies persisted geometry, which an
        // isolated home does not have, but an explicit size must still win over its default.
        stage.setWidth(WIDTH);
        stage.setHeight(HEIGHT);
        stage.setX(X);
        stage.setY(Y);
        stage.setAlwaysOnTop(true);
        stage.toFront();

        // Menu capture is a three-phase handshake, forced by two macOS facts: activating the
        // window while a menu is open dismisses the popup, and while a menu IS open the system
        // refuses screen captures from other processes ("could not create image from rect"). So
        // the caller must start a TIMED capture before the menu opens, which in turn needs the
        // region up front — hence the measure-then-close step.
        //
        //   WINDOW <window>  -> caller activates the window, touches menuTrigger
        //   REGION <region>  -> menu opened, measured and closed again;
        //                       caller starts `screencapture -T`, touches captureTrigger
        //   OPENED <region>  -> menu is open again and stays open for the timer to fire
        Path menuTrigger = pathProperty("kortty.menuTriggerFlag");
        Path captureTrigger = pathProperty("kortty.captureTriggerFlag");
        PauseTransition settle = new PauseTransition(Duration.millis(1500));
        settle.setOnFinished(e -> {
            if (menuKey.isEmpty()) {
                announce("READY", stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
                return;
            }
            if (menuTrigger == null || captureTrigger == null) {
                // No handshake requested: open and report, useful for a manual capture.
                openMenuAndAnnounce(stage, menuKey);
                return;
            }
            announce("WINDOW", stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
            awaitFile(menuTrigger, () -> measureThenReopen(stage, menuKey, captureTrigger));
        });
        settle.play();

        // Poll for the capture-done flag; hard stop after 45s either way.
        Timeline poll = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            if (doneFlag != null && Files.exists(doneFlag)) {
                stage.hide();
                done.countDown();
            }
        }));
        poll.setCycleCount(90);
        poll.setOnFinished(e -> {
            stage.hide();
            done.countDown();
        });
        poll.play();
    }

    private static Path pathProperty(String key) {
        String value = System.getProperty(key);
        return value != null && !value.isBlank() ? Path.of(value.trim()) : null;
    }

    private static void announce(String label, double x, double y, double w, double h) {
        System.out.printf(Locale.ROOT, "%s %.2f %.2f %.2f %.2f%n", label, x, y, w, h);
        System.out.flush();
    }

    /** Polls for a flag file and runs the action once, on the JavaFX thread. */
    private static void awaitFile(Path flag, Runnable then) {
        Timeline[] poll = new Timeline[1];
        // Self-reference so the poll stops itself the moment the flag appears; otherwise it would
        // keep firing and repeat the action on every tick.
        poll[0] = new Timeline(new KeyFrame(Duration.millis(250), e -> {
            if (Files.exists(flag)) {
                poll[0].stop();
                then.run();
            }
        }));
        poll[0].setCycleCount(240);
        poll[0].play();
    }

    /**
     * Opens the menu to measure its real region, closes it so the caller's screen capture can
     * start, and re-opens it when the caller signals that the timed capture is armed.
     */
    private static void measureThenReopen(Stage stage, String menuKey, Path captureTrigger) {
        MenuButton button = openMenu(stage, I18n.get(menuKey));
        PauseTransition paint = new PauseTransition(Duration.millis(700));
        paint.setOnFinished(e -> {
            double[] region = menuRegion(button);
            button.hide();
            announce("REGION", region[0], region[1], region[2], region[3]);
            awaitFile(captureTrigger, () -> {
                openMenu(stage, I18n.get(menuKey));
                announce("OPENED", region[0], region[1], region[2], region[3]);
            });
        });
        paint.play();
    }

    /** Opens the menu, waits for its popup to paint, then announces the region to capture. */
    private static void openMenuAndAnnounce(Stage stage, String menuKey) {
        MenuButton opened = openMenu(stage, I18n.get(menuKey));
        PauseTransition paint = new PauseTransition(Duration.millis(700));
        paint.setOnFinished(e -> {
            double[] region = menuRegion(opened);
            announce("READY", region[0], region[1], region[2], region[3]);
        });
        paint.play();
    }

    /**
     * Opens the menu bar's menu with the given title and returns its button. A menu-bar menu is
     * rendered as a {@link MenuButton} by the skin, so showing it needs no synthetic mouse event.
     */
    private static MenuButton openMenu(Stage stage, String title) {
        Set<Node> candidates = stage.getScene().getRoot().lookupAll(".menu-button");
        for (Node node : candidates) {
            if (node instanceof MenuButton button && title.equals(button.getText())) {
                button.show();
                return button;
            }
        }
        throw new IllegalStateException("Menu not found in the menu bar: " + title
            + " (found: " + candidates.stream()
                .filter(MenuButton.class::isInstance)
                .map(n -> ((MenuButton) n).getText())
                .toList() + ")");
    }

    /**
     * Screen region covering a menu title and its open popup, with a little padding.
     *
     * <p>The popup is found through {@link Window#getWindows()}, not through the button: an open
     * menu is a {@link ContextMenu}, which is a window of its own, while a control's
     * {@code getContextMenu()} is its unrelated right-click menu and stays null here.</p>
     */
    private static double[] menuRegion(MenuButton button) {
        Bounds title = button.localToScreen(button.getBoundsInLocal());
        double minX = title.getMinX();
        double minY = title.getMinY();
        double maxX = title.getMaxX();
        double maxY = title.getMaxY();

        boolean sawPopup = false;
        for (Window open : Window.getWindows()) {
            if (open instanceof ContextMenu popup && popup.isShowing() && popup.getWidth() > 0) {
                sawPopup = true;
                minX = Math.min(minX, popup.getX());
                minY = Math.min(minY, popup.getY());
                maxX = Math.max(maxX, popup.getX() + popup.getWidth());
                maxY = Math.max(maxY, popup.getY() + popup.getHeight());
            }
        }
        if (!sawPopup) {
            throw new IllegalStateException(
                "menu popup is not showing — capturing the title alone would be useless");
        }
        double x = Math.max(0, minX - MENU_PADDING_X);
        double y = Math.max(0, minY - MENU_PADDING_Y);
        return new double[]{x, y, maxX - x + MENU_PADDING_X, maxY - y + MENU_PADDING_Y};
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
