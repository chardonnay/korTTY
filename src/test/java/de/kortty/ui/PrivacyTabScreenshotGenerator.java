package de.kortty.ui;

import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.telemetry.TelemetryService;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline generator for the manual's Settings &rarr; Privacy tab screenshot.
 *
 * <p>Builds the REAL {@link SettingsDialog} headless (null owner/app, empty managers on a
 * temp dir), applies the dialog theme, selects the Privacy tab, and snapshots the dialog
 * pane at 2x to {@code app-docs/screenshots/settings/telemetry.png}. Reproducible and
 * CSS-accurate — no live app or manual capture needed.</p>
 *
 * <p>Run via the {@code generatePrivacyTabScreenshot} Gradle task. Exit 0 = OK.</p>
 */
public final class PrivacyTabScreenshotGenerator {

    private static final int WIDTH = 1120;
    private static final int HEIGHT = 700;
    private static final String OUTPUT_FILE = "app-docs/screenshots/settings/telemetry.png";

    private PrivacyTabScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
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
        Path tempDir = Files.createTempDirectory("kortty-privacy-screenshot");

        GlobalSettings settings = new GlobalSettings();
        settings.setLanguage("en");
        settings.setTelemetryEnabled(true);
        settings.setTelemetryConsentVersion(TelemetryService.CURRENT_CONSENT_VERSION);
        settings.setTelemetryConsentDate("2026-07-04T10:00:00Z");
        LanguageManager.getInstance().initialize(settings);

        SettingsDialog dialog = new SettingsDialog(
            null,
            null,
            new ConfigurationManager(tempDir),
            settings,
            new CredentialManager(tempDir),
            new GPGKeyManager(tempDir));
        DialogThemeHelper.applyTheme(dialog);

        DialogPane pane = dialog.getDialogPane();
        selectPrivacyTab(dialog);

        // The Dialog already owns a Scene for its pane; just size the pane and let
        // Node.snapshot run the CSS/layout pass.
        pane.setMinSize(WIDTH, HEIGHT);
        pane.setPrefSize(WIDTH, HEIGHT);
        pane.setMaxSize(WIDTH, HEIGHT);
        pane.applyCss();
        pane.resize(WIDTH, HEIGHT);
        pane.layout();

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

    private static void selectPrivacyTab(SettingsDialog dialog) throws Exception {
        Field tabPaneField = SettingsDialog.class.getDeclaredField("mainTabPane");
        tabPaneField.setAccessible(true);
        TabPane tabPane = (TabPane) tabPaneField.get(dialog);
        String privacyTitle = I18n.get("settings.tab.privacy");
        for (Tab tab : tabPane.getTabs()) {
            if (privacyTitle.equals(tab.getText())) {
                tabPane.getSelectionModel().select(tab);
                return;
            }
        }
        throw new IllegalStateException("Privacy tab not found in SettingsDialog");
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
