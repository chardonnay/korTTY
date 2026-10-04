package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.shellintegration.ShellIntegrationSnippet;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.DialogPane;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javax.imageio.ImageIO;

/**
 * Offline generator for the manual's <i>Set Up Shell Integration…</i> screenshot.
 *
 * <p>Builds the real {@link ShellIntegrationSetupDialog} on its bash tab without showing it, in the
 * Normal design, and snapshots its dialog pane at 2x to
 * {@code app-docs/screenshots/main/shell-integration-setup.png}. The window shows only the snippets
 * korTTY ships, so there is no demo data to invent.</p>
 *
 * <p>Run via the {@code generateShellIntegrationSetupScreenshot} Gradle task. Exit 0 = OK.</p>
 */
public final class ShellIntegrationSetupScreenshotGenerator {

    private static final String OUTPUT_FILE = "app-docs/screenshots/main/shell-integration-setup.png";

    private ShellIntegrationSetupScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        // JavaFX labels ButtonType.CLOSE from its own resources in the JVM locale of the moment the
        // class loads, which can be before the app language is set; the manual is English.
        Locale.setDefault(Locale.ENGLISH);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                writeScreenshot();
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
            } finally {
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("SCREENSHOT GENERATION TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("SCREENSHOT GENERATION FAILURE: " + failure.get());
            System.exit(1);
        }
        System.exit(0);
    }

    private static void writeScreenshot() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);

        ShellIntegrationSetupDialog dialog = new ShellIntegrationSetupDialog(null, ShellIntegrationSnippet.BASH);
        DialogPane pane = dialog.getDialogPane();

        double width = pane.getPrefWidth();
        pane.applyCss();
        pane.resize(width, 1200);
        pane.layout();
        double height = Math.ceil(pane.prefHeight(width));
        pane.resize(width, height);
        pane.layout();

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#1e1e1e"));
        params.setTransform(Transform.scale(2, 2));
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

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
