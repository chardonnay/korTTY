package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.DialogPane;
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
 * Offline generator for the manual's Plugins → AI Agent Design screenshots: the REAL
 * {@link AgentDesignPluginManagerDialog} (isolated {@code user.home}, English, Normal design) while its
 * live preview waits for the sample run's approval, and the preview alone after the run collapsed to
 * its answer. Writes {@code app-docs/screenshots/plugins/ai-agent-design.png} and
 * {@code ai-agent-design-finished.png}.
 *
 * <p>Run via the {@code generateAiAgentDesignScreenshots} Gradle task. Exit 0 = OK.</p>
 */
public final class AiAgentDesignScreenshotGenerator {

    private static final String DIALOG_FILE = "app-docs/screenshots/plugins/ai-agent-design.png";
    private static final String FINISHED_FILE = "app-docs/screenshots/plugins/ai-agent-design-finished.png";
    /** Seconds after opening: the sample run waits for its approval, then has collapsed to its answer. */
    private static final double APPROVAL_MOMENT = 6.6;
    private static final double FINISHED_MOMENT = 10.6;

    private AiAgentDesignScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-ai-agent-design-screenshot");
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

        AgentDesignPluginManagerDialog dialog = new AgentDesignPluginManagerDialog(null, app.getGlobalSettingsManager());
        DialogThemeHelper.applyTheme(dialog);
        String dynamicCss = ThemeCssSupport.getDynamicStylesheetUrl(ThemeCssSupport.resolveThemeColors(settings, null));
        if (dynamicCss != null) {
            dialog.getDialogPane().getStylesheets().add(dynamicCss);
        }
        DialogPane pane = dialog.getDialogPane();
        dialog.show();
        Stage stage = (Stage) pane.getScene().getWindow();
        stage.setX(-4000);
        stage.setY(-4000); // off-screen: no visible window flash during generation
        Node preview = (Node) rawField(dialog, "preview");

        PauseTransition approval = new PauseTransition(Duration.seconds(APPROVAL_MOMENT));
        approval.setOnFinished(event -> {
            try {
                capture(pane, DIALOG_FILE);
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
            }
        });
        PauseTransition finished = new PauseTransition(Duration.seconds(FINISHED_MOMENT));
        finished.setOnFinished(event -> {
            try {
                capture(preview, FINISHED_FILE);
                dialog.close();
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
            } finally {
                done.countDown();
            }
        });
        approval.play();
        finished.play();
    }

    private static void capture(Node node, String file) throws Exception {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#1e1e1e"));
        params.setTransform(Transform.scale(2, 2));
        WritableImage image = node.snapshot(params, null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        File outFile = new File(file);
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
