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
 * its answer, and the four status bars and rails (01, 11, 12, 18) beside the in-terminal blocks while the
 * run waits for its approval. Writes {@code app-docs/screenshots/plugins/ai-agent-design.png},
 * {@code ai-agent-design-finished.png} and {@code ai-agent-status-designs.png}.
 *
 * <p>Run via the {@code generateAiAgentDesignScreenshots} Gradle task. Exit 0 = OK.</p>
 */
public final class AiAgentDesignScreenshotGenerator {

    private static final String DIALOG_FILE = "app-docs/screenshots/plugins/ai-agent-design.png";
    private static final String FINISHED_FILE = "app-docs/screenshots/plugins/ai-agent-design-finished.png";
    private static final String STATUS_FILE = "app-docs/screenshots/plugins/ai-agent-status-designs.png";
    private static final java.util.List<String> STATUS_IDS = java.util.List.of("d01", "d12", "d11", "d18");
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

        // The status designs, each beside the blocks in the terminal, in a grid of previews.
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new javafx.geometry.Insets(8));
        java.util.Map<String, AgentDesignPreview> previews = new java.util.LinkedHashMap<>();
        for (int i = 0; i < STATUS_IDS.size(); i++) {
            AgentDesignPreview statusPreview = new AgentDesignPreview();
            grid.add(statusPreview, i % 2, i / 2);
            previews.put(STATUS_IDS.get(i), statusPreview);
        }
        Stage gridStage = new Stage();
        gridStage.setScene(new javafx.scene.Scene(grid));
        gridStage.setX(-4000);
        gridStage.setY(-4000);
        gridStage.show();
        previews.forEach((statusId, statusPreview) -> statusPreview.play(
            de.kortty.agentdesign.AgentDesignPresets.STANDARD.with(de.kortty.agentdesign.AgentRole.STATUS, statusId)));
        PauseTransition unfold = new PauseTransition(Duration.seconds(3.6));
        unfold.setOnFinished(event -> {
            fire(previews.get("d01"), "▴");
            fire(previews.get("d12"), "▾");
        });
        PauseTransition point = new PauseTransition(Duration.seconds(APPROVAL_MOMENT - 0.3));
        point.setOnFinished(event -> pointAtSegment(previews.get("d11"), 1));
        unfold.play();
        point.play();

        PauseTransition approval = new PauseTransition(Duration.seconds(APPROVAL_MOMENT));
        approval.setOnFinished(event -> {
            try {
                capture(pane, DIALOG_FILE);
                capture(grid, STATUS_FILE);
                gridStage.close();
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

    /** Presses the button of a preview that shows {@code text}. */
    private static void fire(AgentDesignPreview preview, String text) {
        for (Node node : preview.lookupAll(".button")) {
            if (node instanceof javafx.scene.control.Button button && text.equals(button.getText())) {
                button.fire();
                return;
            }
        }
    }

    /** Points at a minimap segment so its lens opens. */
    private static void pointAtSegment(AgentDesignPreview preview, int index) {
        preview.lookupAll("*").stream().filter(AgentDesignMinimapRail.class::isInstance).findFirst()
            .map(rail -> ((AgentDesignMinimapRail) rail).getChildren().stream()
                .filter(javafx.scene.shape.Rectangle.class::isInstance).toList())
            .filter(segments -> segments.size() > index)
            .ifPresent(segments -> ((javafx.scene.shape.Rectangle) segments.get(index)).getOnMouseEntered().handle(null));
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
