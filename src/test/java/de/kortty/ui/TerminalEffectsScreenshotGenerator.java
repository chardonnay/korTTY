package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.core.TerminalEffectPluginManager;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TableView;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
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
 * Offline generator for the manual's Terminal Effects manager screenshot
 * ({@code app-docs/screenshots/plugins/terminal-effects.png}).
 *
 * <p>Builds the REAL {@link TerminalEffectPluginManagerDialog} over the application's plugin
 * manager (bundled plugins only, isolated {@code user.home}, English), selects the first effect so
 * its live preview runs, and snapshots the dialog pane at 2x.</p>
 *
 * <p>Run via the {@code generateTerminalEffectsScreenshot} Gradle task. Exit 0 = OK.</p>
 */
public final class TerminalEffectsScreenshotGenerator {

    private static final double WIDTH = 1000;
    private static final double HEIGHT = 470;
    private static final String OUTPUT_FILE = "app-docs/screenshots/plugins/terminal-effects.png";

    private TerminalEffectsScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-terminal-effects-screenshot");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                generate(done, failure);
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
                done.countDown();
            }
        });

        boolean finished = done.await(90, TimeUnit.SECONDS);
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

    private static void generate(CountDownLatch done, AtomicReference<String> failure) throws Exception {
        KorTTYApplication app = new KorTTYApplication();
        app.init();
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);

        TerminalEffectPluginManager manager = app.getTerminalEffectPluginManager();
        if (manager.getPluginEntries().isEmpty()) {
            manager.load();
        }
        if (manager.getPluginEntries().isEmpty()) {
            throw new IllegalStateException("no terminal effect plugin loaded — the bundled plugins are missing");
        }

        TerminalEffectPluginManagerDialog dialog = new TerminalEffectPluginManagerDialog(null, manager);
        DialogThemeHelper.applyTheme(dialog);
        String dynamicCss = ThemeCssSupport.getDynamicStylesheetUrl(
            ThemeCssSupport.resolveThemeColors(settings, null));
        if (dynamicCss != null) {
            dialog.getDialogPane().getStylesheets().add(dynamicCss);
        }
        DialogPane pane = dialog.getDialogPane();
        pane.setMinSize(WIDTH, HEIGHT);
        pane.setPrefSize(WIDTH, HEIGHT);
        pane.setMaxSize(WIDTH, HEIGHT);
        dialog.show();
        Stage stage = (Stage) pane.getScene().getWindow();
        stage.setX(-4000);
        stage.setY(-4000); // off-screen: no visible window flash during generation

        // Selecting a row starts its preview animation; let it draw a few frames first.
        TableView<?> table = (TableView<?>) rawField(dialog, "table");
        table.getSelectionModel().selectFirst();
        pane.applyCss();
        pane.layout();

        PauseTransition settle = new PauseTransition(Duration.millis(2500));
        settle.setOnFinished(event -> {
            try {
                capture(pane);
                dialog.close();
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
            } finally {
                done.countDown();
            }
        });
        settle.play();
    }

    private static void capture(DialogPane pane) throws Exception {
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

    private static Object rawField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
