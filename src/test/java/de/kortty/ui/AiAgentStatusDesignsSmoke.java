package de.kortty.ui;

import de.kortty.agentdesign.AgentDesignCombination;
import de.kortty.agentdesign.AgentDesignPresets;
import de.kortty.agentdesign.AgentRole;
import de.kortty.core.LanguageManager;
import de.kortty.model.AppDesign;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Headed smoke for the status designs of phase 2 (01 Powerline, 11 minimap rail, 12 top bar, 18 neon
 * ticker): plays the sample run with each one beside the in-terminal blocks in its own preview window,
 * opens the Powerline feed and the top bar's drawer, points at a minimap segment, and snapshots every
 * preview while the run works, while it waits for the approval and after it ended, to
 * {@code build/smoke/status-<id>-<moment>.png}. Property {@code kortty.smoke.design} picks the
 * AppDesign (default {@code atlantafx-cupertino-dark}). Exit 0 = OK.
 */
public final class AiAgentStatusDesignsSmoke {

    private static final String OUTPUT_DIR = "build/smoke";
    private static final List<String> STATUS_IDS = List.of("d01", "d11", "d12", "d18");

    private AiAgentStatusDesignsSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("kortty-status-designs-smoke");
        System.setProperty("user.home", home.toString());
        System.setProperty("TEST_MODE_KORTTY", "1");
        String designId = System.getProperty("kortty.smoke.design", "atlantafx-cupertino-dark");
        File outDir = new File(OUTPUT_DIR);
        outDir.mkdirs();
        List<String> failures = new ArrayList<>();

        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(ready::countDown);
        ready.await(30, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);

        Map<String, AgentDesignPreview> previews = new LinkedHashMap<>();
        onFx(() -> {
            GlobalSettings settings = new GlobalSettings();
            settings.setLanguage(System.getProperty("kortty.smoke.language", "de"));
            LanguageManager.getInstance().initialize(settings);
            AppDesignStyleSupport.initializeGlobalStyling(AppDesign.fromId(designId));
            int index = 0;
            for (String statusId : STATUS_IDS) {
                AgentDesignCombination combination = AgentDesignPresets.STANDARD.with(AgentRole.STATUS, statusId);
                AgentDesignPreview preview = new AgentDesignPreview();
                Stage stage = new Stage();
                stage.setTitle(statusId);
                stage.setScene(new Scene(new StackPane(preview)));
                stage.setX(20 + (index % 2) * 660);
                stage.setY(20 + (index / 2) * 400);
                stage.show();
                preview.play(combination);
                previews.put(statusId, preview);
                index++;
            }
        }, failures);

        Thread.sleep(3_600);
        onFx(() -> {
            fire(previews.get("d01"), "▴");
            fire(previews.get("d12"), "▾");
        }, failures);
        Thread.sleep(400);
        capture(previews, "running", outDir, failures);
        Thread.sleep(2_800);
        onFx(() -> pointAtSegment(previews.get("d11"), 1), failures);
        Thread.sleep(300);
        capture(previews, "approval", outDir, failures);
        Thread.sleep(4_000);
        capture(previews, "done", outDir, failures);

        failures.forEach(failure -> System.err.println("SMOKE FAILURE: " + failure));
        int status = failures.isEmpty() ? 0 : 1;
        new Thread(() -> Runtime.getRuntime().halt(status)).start();
    }

    /** Presses the button of a preview that shows {@code text}. */
    private static void fire(AgentDesignPreview preview, String text) {
        for (Node node : preview.lookupAll(".button")) {
            if (node instanceof Button button && text.equals(button.getText())) {
                button.fire();
                return;
            }
        }
        throw new IllegalStateException("no button " + text);
    }

    /** Points at a minimap segment so its lens opens. */
    private static void pointAtSegment(AgentDesignPreview preview, int index) {
        AgentDesignMinimapRail rail = (AgentDesignMinimapRail) preview.lookupAll("*").stream()
            .filter(AgentDesignMinimapRail.class::isInstance).findFirst()
            .orElseThrow(() -> new IllegalStateException("no minimap rail"));
        List<Rectangle> segments = rail.getChildren().stream()
            .filter(Rectangle.class::isInstance).map(Rectangle.class::cast).toList();
        if (segments.size() <= index) {
            throw new IllegalStateException("minimap has " + segments.size() + " segments");
        }
        segments.get(index).getOnMouseEntered().handle(null);
    }

    private static void capture(Map<String, AgentDesignPreview> previews, String moment, File outDir,
                                List<String> failures) throws InterruptedException {
        onFx(() -> {
            for (Map.Entry<String, AgentDesignPreview> entry : previews.entrySet()) {
                File file = new File(outDir, "status-" + entry.getKey() + "-" + moment + ".png");
                try {
                    WritableImage image = entry.getValue().snapshot(new SnapshotParameters(), null);
                    ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
                    System.out.println("  -> " + file.getPath());
                } catch (Exception e) {
                    failures.add(file.getName() + ": " + e);
                }
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
