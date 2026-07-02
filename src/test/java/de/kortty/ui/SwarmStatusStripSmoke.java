package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.swarm.SwarmModels;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SavedSwarmServerSummary;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless smoke harness for the {@link SwarmStatusStrip}: builds the real component, feeds it
 * fake agents covering every state (including a slow-flagged runner and a rehydrated saved chat
 * with a bogus final state), renders deterministic frames via the test seam and snapshots them to
 * {@code build/smoke/swarm-strip-*.png}. The stage is never shown, so the animation timer stays
 * off and every frame comes from {@code renderFrameForTest}. Run via the
 * {@code swarmStatusStripSmoke} Gradle task. Exit 0 = OK.
 */
public final class SwarmStatusStripSmoke {

    private static final double FROZEN_T = 100.3;

    private SwarmStatusStripSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + e));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());

                renderAllStates();
                renderSingleAgent();
                renderFiftyAgents();
                renderRehydrated(failure);
            } catch (Exception e) {
                failure.compareAndSet(null, "Setup failed: " + e);
            } finally {
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("Smoke timed out");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println(failure.get());
            System.exit(1);
        }
        System.out.println("swarmStatusStripSmoke OK");
    }

    /** One orb per state plus one slow-flagged runner (fallback threshold: 300s > 180s). */
    private static void renderAllStates() throws Exception {
        SwarmStatusStrip strip = newStripInScene();
        String[][] agents = {
            {"a1", "queue-01"}, {"a2", "conn-01"}, {"a3", "probe-01"}, {"a4", "web-01"},
            {"a5", "db-01"}, {"a6", "app-01"}, {"a7", "cache-01"}, {"a8", "old-01"},
            {"a9", "skip-01"}, {"a10", "web-02"}, {"a11", "pause-01"},
        };
        for (String[] agent : agents) {
            strip.addAgent(agent[0], agent[1]);
        }
        strip.applyAgentStatus(status("a11", "pause-01", SwarmModels.SwarmAgentState.PAUSED, 40, 700));
        strip.applyAgentStatus(status("a2", "conn-01", SwarmModels.SwarmAgentState.CONNECTING, 5, 0));
        strip.applyAgentStatus(status("a3", "probe-01", SwarmModels.SwarmAgentState.PROBING, 12, 150));
        strip.applyAgentStatus(status("a4", "web-01", SwarmModels.SwarmAgentState.RUNNING, 45, 1250));
        strip.applyAgentStatus(status("a5", "db-01", SwarmModels.SwarmAgentState.AWAITING_APPROVAL, 151, 900));
        strip.applyAgentStatus(status("a6", "app-01", SwarmModels.SwarmAgentState.DONE, 30, 2100));
        strip.applyAgentStatus(status("a7", "cache-01", SwarmModels.SwarmAgentState.FAILED, 22, 400));
        strip.applyAgentStatus(status("a8", "old-01", SwarmModels.SwarmAgentState.CANCELLED, 10, 0));
        strip.applyAgentStatus(status("a9", "skip-01", SwarmModels.SwarmAgentState.SKIPPED, 0, 0));
        strip.applyAgentStatus(status("a10", "web-02", SwarmModels.SwarmAgentState.RUNNING, 300, 1842));
        snapshot(strip, "swarm-strip-states.png");
    }

    private static void renderSingleAgent() throws Exception {
        SwarmStatusStrip strip = newStripInScene();
        strip.addAgent("only", "solo-server.example.com");
        strip.applyAgentStatus(status("only", "solo-server.example.com",
            SwarmModels.SwarmAgentState.RUNNING, 30, 512));
        snapshot(strip, "swarm-strip-one.png");
    }

    private static void renderFiftyAgents() throws Exception {
        SwarmStatusStrip strip = newStripInScene();
        SwarmModels.SwarmAgentState[] mix = {
            SwarmModels.SwarmAgentState.DONE,
            SwarmModels.SwarmAgentState.RUNNING,
            SwarmModels.SwarmAgentState.RUNNING,
            SwarmModels.SwarmAgentState.QUEUED,
            SwarmModels.SwarmAgentState.AWAITING_APPROVAL,
        };
        for (int i = 0; i < 50; i++) {
            String id = "n" + i;
            strip.addAgent(id, "node-" + String.format("%02d", i));
            SwarmModels.SwarmAgentState state = mix[i % mix.length];
            if (state != SwarmModels.SwarmAgentState.QUEUED) {
                strip.applyAgentStatus(status(id, "node-" + i, state, 20 + i, i * 40L));
            }
        }
        snapshot(strip, "swarm-strip-50.png");
    }

    private static void renderRehydrated(AtomicReference<String> failure) throws Exception {
        SwarmStatusStrip strip = newStripInScene();
        List<SavedSwarmServerSummary> summaries = new ArrayList<>();
        summaries.add(summary("web-01", "DONE", 42, 1200));
        summaries.add(summary("web-02", "DONE", 55, 1900));
        summaries.add(summary("db-01", "FAILED", 30, 300));
        summaries.add(summary("legacy-01", "totally-bogus-state", 10, 0));
        strip.showFinalSummaries(summaries);
        if (strip.isAnimating()) {
            failure.compareAndSet(null, "Strip must not animate in static mode");
        }
        if (!strip.isVisible()) {
            failure.compareAndSet(null, "Strip must be visible with summaries");
        }
        snapshot(strip, "swarm-strip-rehydrated.png");

        strip.showFinalSummaries(List.of());
        if (strip.isVisible() || strip.isManaged()) {
            failure.compareAndSet(null, "Strip must collapse without summaries");
        }
        strip.addAgent("fresh", "fresh-01");
        if (!strip.isVisible() || !strip.isManaged()) {
            failure.compareAndSet(null, "addAgent must un-collapse the strip");
        }
    }

    private static SwarmStatusStrip newStripInScene() {
        SwarmStatusStrip strip = new SwarmStatusStrip();
        VBox root = new VBox(strip);
        Scene scene = new Scene(root, 1120, 110);
        scene.setFill(Color.web("#1f2933"));
        Stage stage = new Stage();
        stage.setScene(scene);
        // Never shown: snapshots force layout, the animation timer stays off and every frame is
        // produced deterministically via renderFrameForTest.
        return strip;
    }

    private static void snapshot(SwarmStatusStrip strip, String fileName) throws Exception {
        Scene scene = strip.getScene();
        scene.snapshot(null);
        strip.renderFrameForTest(FROZEN_T);
        WritableImage image = scene.snapshot(null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        File out = new File("build/smoke/" + fileName);
        out.getParentFile().mkdirs();
        ImageIO.write(buffered, "png", out);
        System.out.println("Snapshot written: " + out.getAbsolutePath());
    }

    private static SwarmModels.SwarmAgentStatus status(
        String agentId, String displayName, SwarmModels.SwarmAgentState state,
        long elapsedSeconds, long totalTokens) {
        return new SwarmModels.SwarmAgentStatus(
            agentId, displayName, null, state, "", elapsedSeconds,
            new SwarmModels.TokenTotals(0, 0, totalTokens), null, null, null);
    }

    private static SavedSwarmServerSummary summary(
        String name, String finalState, long elapsedSeconds, long totalTokens) {
        SavedSwarmServerSummary summary = new SavedSwarmServerSummary();
        summary.setServerDisplayName(name);
        summary.setFinalState(finalState);
        summary.setElapsedSeconds(elapsedSeconds);
        summary.setTotalTokens(totalTokens);
        return summary;
    }
}
