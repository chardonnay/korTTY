package de.kortty.ui;

import de.kortty.agentdesign.AgentDesignCombination;
import de.kortty.agentdesign.AgentDesignPresets;
import de.kortty.agentdesign.AgentRole;
import de.kortty.core.LanguageManager;
import de.kortty.model.AppDesign;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Headed smoke for the KI-Agent designs of phases 3 to 5: plays the sample run of the design dialog's
 * preview with each combination below in its own window and snapshots every preview while the run
 * starts, while it works, while it waits for the approval and after it ended, to {@code build/smoke/gallery-<name>-<moment>.png}.
 * Property {@code kortty.smoke.designs} picks combinations by name (comma-separated, default all),
 * {@code kortty.smoke.design} the AppDesign (default {@code atlantafx-cupertino-dark}). Exit 0 = OK.
 */
public final class AiAgentDesignGallerySmoke {

    private static final String OUTPUT_DIR = "build/smoke";

    private AiAgentDesignGallerySmoke() {
    }

    /** The combinations by name: the standard one with one module swapped (or two). */
    static Map<String, AgentDesignCombination> combinations() {
        Map<String, AgentDesignCombination> all = new LinkedHashMap<>();
        AgentDesignCombination quiet = AgentDesignPresets.STANDARD.with(AgentRole.STATUS, "none");
        for (String history : List.of("d02", "d06", "d07", "d09", "d13", "d16", "d17")) {
            all.put(history, quiet.with(AgentRole.HISTORY, history));
        }
        for (String status : List.of("d04", "d05", "d10", "d15")) {
            all.put(status, AgentDesignPresets.STANDARD.with(AgentRole.STATUS, status));
        }
        all.put("d14", quiet.with(AgentRole.APPROVAL, "d14"));
        all.put("d19", quiet.with(AgentRole.ARRANGEMENT, "d19"));
        all.put("d19-d16", quiet.with(AgentRole.HISTORY, "d16").with(AgentRole.ARRANGEMENT, "d19"));
        all.put("d20", AgentDesignPresets.STANDARD.with(AgentRole.ARRANGEMENT, "d20"));
        return all;
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("kortty-design-gallery-smoke");
        System.setProperty("user.home", home.toString());
        System.setProperty("TEST_MODE_KORTTY", "1");
        String designId = System.getProperty("kortty.smoke.design", "atlantafx-cupertino-dark");
        String picked = System.getProperty("kortty.smoke.designs", "");
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
            for (Map.Entry<String, AgentDesignCombination> entry : combinations().entrySet()) {
                if (!picked.isBlank() && !List.of(picked.split(",")).contains(entry.getKey())) {
                    continue;
                }
                AgentDesignPreview preview = new AgentDesignPreview();
                Stage stage = new Stage();
                stage.setTitle(entry.getKey());
                stage.setScene(new Scene(new StackPane(preview)));
                stage.setX(10 + (index % 3) * 640);
                stage.setY(10 + (index / 3 % 3) * 390);
                stage.show();
                preview.play(entry.getValue());
                previews.put(entry.getKey(), preview);
                index++;
            }
        }, failures);

        Thread.sleep(350);
        capture(previews, "start", outDir, failures);
        Thread.sleep(3_550);
        capture(previews, "running", outDir, failures);
        Thread.sleep(2_700);
        capture(previews, "approval", outDir, failures);
        Thread.sleep(4_300);
        capture(previews, "done", outDir, failures);

        failures.forEach(failure -> System.err.println("SMOKE FAILURE: " + failure));
        int status = failures.isEmpty() ? 0 : 1;
        new Thread(() -> Runtime.getRuntime().halt(status)).start();
    }

    private static void capture(Map<String, AgentDesignPreview> previews, String moment, File outDir,
                                List<String> failures) throws InterruptedException {
        onFx(() -> {
            for (Map.Entry<String, AgentDesignPreview> entry : previews.entrySet()) {
                File file = new File(outDir, "gallery-" + entry.getKey() + "-" + moment + ".png");
                try {
                    WritableImage image = entry.getValue().snapshot(new SnapshotParameters(), null);
                    ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
                } catch (Exception e) {
                    failures.add(file.getName() + ": " + e);
                }
            }
            System.out.println("  captured " + moment);
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
