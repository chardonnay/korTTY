package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline generator for the manual's AI Manager &rarr; General screenshot.
 *
 * <p>Builds the REAL {@link AiManagerDialog} (null owner, isolated {@code user.home}, so every option
 * shows its default), selects General &rarr; Features &amp; safety and snapshots the dialog pane at 2x
 * to {@code app-docs/screenshots/ai/ai-manager-general.png}.</p>
 *
 * <p>Run via the {@code generateAiManagerGeneralTabScreenshot} Gradle task. Exit 0 = OK.</p>
 */
public final class AiManagerGeneralTabScreenshotGenerator {

    private static final double WIDTH = 980;
    private static final double HEIGHT = 640;
    private static final String OUTPUT_FILE = "app-docs/screenshots/ai/ai-manager-general.png";

    private AiManagerGeneralTabScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-ai-manager-general-screenshot");
        System.setProperty("user.home", isolatedHome.toString());
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
        String fail = failure.get();
        if (fail != null) {
            System.err.println("SCREENSHOT GENERATION FAILURE: " + fail);
            System.exit(1);
        }
        System.exit(0);
    }

    private static void writeScreenshot() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);

        AiManagerDialog dialog = new AiManagerDialog(null);
        DialogThemeHelper.applyTheme(dialog);
        // Without a running application, DialogThemeHelper cannot resolve the terminal theme, so the
        // dynamic overlay is skipped. Add it from the default settings so the capture matches the app.
        String dynamicCss = ThemeCssSupport.getDynamicStylesheetUrl(
            ThemeCssSupport.resolveThemeColors(settings, null));
        if (dynamicCss != null) {
            dialog.getDialogPane().getStylesheets().add(dynamicCss);
        }
        dialog.show();

        DialogPane pane = dialog.getDialogPane();
        select(pane, ".ai-manager-primary-navigation", I18n.get("ai.manager.tab.general"));
        pane.applyCss();
        pane.layout();
        select(pane, "." + AiManagerSections.NAVIGATION_STYLE_CLASS, I18n.get("ai.manager.general.section.features"));

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
        dialog.close();
    }

    /**
     * Selects the tab titled {@code title} in whichever tab strip carrying {@code selector} has it: every
     * AI Manager section that was built has its own strip with the same style class.
     */
    private static void select(DialogPane pane, String selector, String title) {
        for (javafx.scene.Node node : pane.lookupAll(selector)) {
            if (node instanceof TabPane tabPane) {
                for (Tab tab : tabPane.getTabs()) {
                    if (title.equals(tab.getText())) {
                        tabPane.getSelectionModel().select(tab);
                        return;
                    }
                }
            }
        }
        throw new IllegalStateException("Tab " + title + " not found in " + selector);
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
