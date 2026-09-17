package de.kortty.ui;

import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
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
 * Offline generator for the manual's Settings &rarr; Terminal tab screenshot.
 *
 * <p>Mirrors {@link PrivacyTabScreenshotGenerator}: builds the REAL {@link SettingsDialog}
 * headless (null owner/app, empty managers on a temp dir), applies the dialog theme, selects
 * the Terminal tab and snapshots the dialog pane at 2x to
 * {@code app-docs/screenshots/settings/terminal.png}.</p>
 *
 * <p>The tab is left at its shipped defaults, so the Control API section shows the state a new
 * installation is actually in — off, with the status line reading disabled.</p>
 *
 * <p>Run via the {@code generateTerminalTabScreenshot} Gradle task. Exit 0 = OK.</p>
 */
public final class TerminalTabScreenshotGenerator {

    private static final int WIDTH = 1120;

    /** Padding below the last row of the tab's grid, in unscaled pixels. */
    private static final double BOTTOM_MARGIN = 24;

    /** Height used for the first layout pass, before the content's real height is known. */
    private static final int MEASURE_HEIGHT = 1600;

    private static final String OUTPUT_FILE = "app-docs/screenshots/settings/terminal.png";

    private TerminalTabScreenshotGenerator() {
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
        Path tempDir = Files.createTempDirectory("kortty-terminal-screenshot");

        GlobalSettings settings = new GlobalSettings();
        settings.setLanguage("en");
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
        Node content = selectTerminalTab(dialog);

        // The tab's content is built lazily on selection, so its height is only known after a
        // layout pass. Measure at a deliberately tall size, then snapshot at exactly the height
        // the content needs instead of trailing hundreds of empty pixels. The grid is stretched
        // to fill the tab, so its laid-out height is the space it was given, not the space it
        // wants — only prefHeight reports the latter.
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

    private static void layoutAt(DialogPane pane, double height) {
        pane.setMinSize(WIDTH, height);
        pane.setPrefSize(WIDTH, height);
        pane.setMaxSize(WIDTH, height);
        pane.applyCss();
        pane.resize(WIDTH, height);
        pane.layout();
    }

    private static Node selectTerminalTab(SettingsDialog dialog) throws Exception {
        Field tabPaneField = SettingsDialog.class.getDeclaredField("mainTabPane");
        tabPaneField.setAccessible(true);
        TabPane tabPane = (TabPane) tabPaneField.get(dialog);
        String terminalTitle = I18n.get("settings.tab.terminal");
        for (Tab tab : tabPane.getTabs()) {
            if (terminalTitle.equals(tab.getText())) {
                tabPane.getSelectionModel().select(tab);
                Node content = LazyTabContent.ensureContent(tab);
                if (content == null) {
                    throw new IllegalStateException("Terminal tab has no content after selection");
                }
                return content;
            }
        }
        throw new IllegalStateException("Terminal tab not found in SettingsDialog");
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
