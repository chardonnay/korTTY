package de.kortty.ui;

import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shows the REAL {@link SettingsDialog} with a chosen tab selected as an on-screen window at a
 * fixed position and size, prints {@code READY x y w h} and keeps it open so the docs screenshot
 * can be captured externally via {@code screencapture -R} (the guide's settings screenshots are
 * real window captures including the macOS title bar). Closes on its own after 45 seconds, or as
 * soon as the file given as the first program argument exists (capture-done flag).
 *
 * <p>The tab to show is the i18n key given by the {@code kortty.screenshotTabKey} system property
 * (e.g. {@code settings.tab.security}); it defaults to {@code settings.tab.window}. The dialog runs
 * against an isolated temp home in English so no real credentials or German labels appear.
 */
public final class SettingsTabScreenshotStage {

    private static final double X = 80;
    private static final double Y = 80;
    /** Pane size chosen so the decorated window's aspect matches the guide's 1397x1400 target. */
    private static final double PANE_WIDTH = 1000;
    private static final double PANE_HEIGHT = 974;
    private static final String DEFAULT_TAB_KEY = "settings.tab.window";

    private SettingsTabScreenshotStage() {
    }

    public static void main(String[] args) throws Exception {
        Path doneFlag = args.length > 0 && !args[0].isBlank() ? Path.of(args[0]) : null;
        String tabKey = System.getProperty("kortty.screenshotTabKey", DEFAULT_TAB_KEY);
        Path isolatedHome = Files.createTempDirectory("kortty-settings-tab-screenshot");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                show(isolatedHome, doneFlag, tabKey, done);
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (failure.get() != null) {
            System.err.println("SETTINGS TAB SCREENSHOT FAILURE: " + failure.get());
            System.exit(1);
        }
        if (!finished) {
            System.err.println("SETTINGS TAB SCREENSHOT TIMEOUT");
            System.exit(2);
        }
        System.exit(0);
    }

    private static void show(Path isolatedHome, Path doneFlag, String tabKey, CountDownLatch done)
            throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);

        SettingsDialog dialog = new SettingsDialog(
            null, null,
            new ConfigurationManager(isolatedHome), settings,
            new CredentialManager(isolatedHome), new GPGKeyManager(isolatedHome));
        DialogThemeHelper.applyTheme(dialog);
        // Without a running application the theme helper skips the dynamic overlay; add it from the
        // default settings so the capture matches what a user sees (same as the AI-skills generator).
        String dynamicCss = ThemeCssSupport.getDynamicStylesheetUrl(
            ThemeCssSupport.resolveThemeColors(settings, null));
        if (dynamicCss != null) {
            dialog.getDialogPane().getStylesheets().add(dynamicCss);
        }
        // Size via the pane and let the dialog window size itself to the scene — forcing a smaller
        // stage would clip the pane (and its button bar) at the bottom.
        DialogPane pane = dialog.getDialogPane();
        pane.setMinSize(PANE_WIDTH, PANE_HEIGHT);
        pane.setPrefSize(PANE_WIDTH, PANE_HEIGHT);
        pane.setMaxSize(PANE_WIDTH, PANE_HEIGHT);
        dialog.show();

        Stage stage = (Stage) pane.getScene().getWindow();
        stage.setAlwaysOnTop(true);
        stage.setX(X);
        stage.setY(Y);
        stage.toFront();

        // Select the tab AFTER showing/sizing so the tab header scrolls it into view, then give
        // macOS a moment to place/paint the window before announcing the real window bounds.
        javafx.animation.PauseTransition announce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(1200));
        announce.setOnFinished(e -> {
            try {
                selectTab(dialog, I18n.get(tabKey));
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            pane.applyCss();
            pane.layout();
            javafx.animation.PauseTransition settle =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(600));
            settle.setOnFinished(e2 -> {
                System.out.printf(Locale.ROOT, "READY %.2f %.2f %.2f %.2f%n",
                    stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
                System.out.flush();
            });
            settle.play();
        });
        announce.play();

        // Poll for the capture-done flag; hard stop after 45s either way.
        javafx.animation.Timeline poll = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.millis(500), e -> {
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

    private static void selectTab(SettingsDialog dialog, String title) throws Exception {
        Field field = SettingsDialog.class.getDeclaredField("mainTabPane");
        field.setAccessible(true);
        TabPane tabPane = (TabPane) field.get(dialog);
        for (Tab tab : tabPane.getTabs()) {
            if (title.equals(tab.getText())) {
                tabPane.getSelectionModel().select(tab);
                return;
            }
        }
        throw new IllegalStateException("Tab not found: " + title);
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
