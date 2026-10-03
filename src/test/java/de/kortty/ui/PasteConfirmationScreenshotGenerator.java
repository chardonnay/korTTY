package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.paste.PasteConfirmationRequest;
import de.kortty.paste.PasteReason;
import de.kortty.paste.PasteSource;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Alert;
import javafx.scene.control.DialogPane;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javax.imageio.ImageIO;

/**
 * Offline generator for the manual's paste confirmation screenshot.
 *
 * <p>Builds the real {@link PasteConfirmationDialog} for a demo paste without showing it, in the
 * Normal design, and snapshots its dialog pane at 2x to {@code app-docs/screenshots/main/paste-confirmation.png}.
 * The paste is three lines into a pane whose program does not use bracketed paste, one of them with
 * an escape sequence, so the reasons, the bracketed-paste note and the control-picture preview all
 * show.</p>
 *
 * <p>Run via the {@code generatePasteConfirmationScreenshot} Gradle task. Exit 0 = OK.</p>
 */
public final class PasteConfirmationScreenshotGenerator {

    private static final String OUTPUT_FILE = "app-docs/screenshots/main/paste-confirmation.png";

    private static final String DEMO_TEXT = "cd /srv/demo-app\n"
        + "git pull --ff-only\n"
        + "printf '\u001b[2J'; sudo systemctl restart demo-app\n";

    private PasteConfirmationScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
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

        PasteConfirmationRequest request = new PasteConfirmationRequest("demo-web-01", DEMO_TEXT,
            EnumSet.of(PasteReason.MULTI_LINE, PasteReason.CONTROL_CHARACTERS), false, PasteSource.CLIPBOARD, false);
        Alert alert = new PasteConfirmationDialog(() -> null).build(request, accepted -> {
        });
        DialogPane pane = alert.getDialogPane();

        double width = pane.getPrefWidth();
        pane.applyCss();
        pane.resize(width, 1000);
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
