package de.kortty.ui;

import de.kortty.agentdesign.AgentDesignPresets;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.LanguageManager;
import de.kortty.model.AppDesign;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed smoke for Plugins → KI-Agent Design: opens the real dialog, lets the live preview play the
 * sample run in the standard combination (blocks in the terminal + metro line) and in the classic one,
 * and snapshots the dialog while the run works, while it waits for the approval, and after it ended,
 * to {@code build/smoke/agent-design-<design>-<moment>.png}. Properties: {@code kortty.smoke.design}
 * (an AppDesign id, default {@code atlantafx-cupertino-dark}) and {@code kortty.smoke.language}
 * (default {@code de}). Exit 0 = OK.
 */
public final class AiAgentDesignPreviewSmoke {

    private static final String OUTPUT_DIR = "build/smoke";

    private AiAgentDesignPreviewSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("kortty-agent-design-smoke");
        System.setProperty("user.home", home.toString());
        System.setProperty("TEST_MODE_KORTTY", "1");
        String designId = System.getProperty("kortty.smoke.design", "atlantafx-cupertino-dark");
        String language = System.getProperty("kortty.smoke.language", "de");
        File outDir = new File(OUTPUT_DIR);
        outDir.mkdirs();
        List<String> failures = new ArrayList<>();

        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(ready::countDown);
        ready.await(30, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);

        AtomicReference<AgentDesignPluginManagerDialog> dialogRef = new AtomicReference<>();
        AtomicReference<AgentDesignPreview> classicRef = new AtomicReference<>();
        onFx(() -> {
            GlobalSettings settings = new GlobalSettings();
            settings.setLanguage(language);
            LanguageManager.getInstance().initialize(settings);
            AppDesign design = AppDesign.fromId(designId);
            AppDesignStyleSupport.initializeGlobalStyling(design);
            GlobalSettingsManager manager = new GlobalSettingsManager(home.resolve(".kortty"));
            AgentDesignPluginManagerDialog dialog = new AgentDesignPluginManagerDialog(null, manager);
            dialogRef.set(dialog);
            dialog.show();

            AgentDesignPreview classic = new AgentDesignPreview();
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(classic)));
            stage.setX(20);
            stage.setY(20);
            stage.show();
            classic.play(AgentDesignPresets.CLASSIC);
            classicRef.set(classic);
        }, failures);

        String tag = designId.replace("atlantafx-", "");
        capture(4.0, () -> dialogRef.get().getDialogPane(), new File(outDir, "agent-design-" + tag + "-running.png"), failures);
        capture(2.8, () -> dialogRef.get().getDialogPane(), new File(outDir, "agent-design-" + tag + "-approval.png"), failures);
        capture(0.1, () -> classicRef.get(), new File(outDir, "agent-design-" + tag + "-classic.png"), failures);
        capture(4.0, () -> dialogRef.get().getDialogPane(), new File(outDir, "agent-design-" + tag + "-done.png"), failures);

        failures.forEach(failure -> System.err.println("SMOKE FAILURE: " + failure));
        int status = failures.isEmpty() ? 0 : 1;
        new Thread(() -> Runtime.getRuntime().halt(status)).start();
    }

    private interface NodeSupplier {
        Node get();
    }

    private static void capture(double afterSeconds, NodeSupplier node, File file, List<String> failures) throws Exception {
        Thread.sleep((long) (afterSeconds * 1000));
        onFx(() -> {
            try {
                WritableImage image = node.get().snapshot(new SnapshotParameters(), null);
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
                System.out.println("  -> " + file.getPath());
            } catch (Exception e) {
                failures.add(file.getName() + ": " + e);
            }
        }, failures);
    }

    private static void onFx(Runnable task, List<String> failures) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                t.printStackTrace();
                failures.add(t.toString());
            } finally {
                done.countDown();
            }
        });
        done.await(30, TimeUnit.SECONDS);
    }
}
