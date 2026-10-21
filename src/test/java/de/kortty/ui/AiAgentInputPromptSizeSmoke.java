package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TerminalAgentModels;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX smoke harness: a collapsed bottom AI-agent panel that receives an approval request must
 * expand far enough that the whole prompt (message, command preview and buttons) is visible, also after
 * the user collapses and re-expands it. Run via the {@code aiAgentInputPromptSizeSmoke} Gradle task.
 * Exit 0 = OK.
 */
public final class AiAgentInputPromptSizeSmoke {

    private AiAgentInputPromptSizeSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + stack(e)));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                Stage stage = new Stage();
                Region terminal = new Region();
                terminal.setMinHeight(0);
                VBox.setVgrow(terminal, Priority.ALWAYS);
                AiAgentActivityTabsPanel panel = new AiAgentActivityTabsPanel();
                VBox root = new VBox(terminal, panel);
                stage.setScene(new Scene(root, 1100, 900));
                stage.show();

                AiAgentActivityPanel.RunMetadata md =
                    new AiAgentActivityPanel.RunMetadata(null, null, null, null, null, null, null);
                panel.beginRun("run-1", "extend the script with user groups and permissions",
                    () -> { }, b -> { }, () -> { }, md);
                Button collapse = (Button) panel.lookup(".ai-agent-collapse-button");
                collapse.fire();

                TerminalAgentModels.Approval approval = new TerminalAgentModels.Approval(
                    "run-1", null, null, "Create script",
                    "Create a script that outputs the current user, its groups and permissions",
                    List.of(
                        new TerminalAgentModels.PlannedCommand("Set-Content -Path 'S.ps1' -Value @'",
                            "write the script", TerminalAgentModels.Risk.REQUIRES_CONFIRMATION),
                        new TerminalAgentModels.PlannedCommand("$u = $env:USERNAME",
                            "current user", TerminalAgentModels.Risk.REQUIRES_CONFIRMATION),
                        new TerminalAgentModels.PlannedCommand("$g = Get-LocalGroup", "groups",
                            TerminalAgentModels.Risk.REQUIRES_CONFIRMATION)),
                    true);
                Thread worker = new Thread(() -> panel.requestApproval("run-1", approval), "agent-worker");
                worker.setDaemon(true);
                worker.start();

                after(1.0, () -> {
                    checkPromptVisible(panel, "after approval request", failure);
                    collapse.fire();
                    after(0.3, () -> {
                        collapse.fire();
                        after(0.5, () -> {
                            checkPromptVisible(panel, "after collapse + re-expand", failure);
                            done.countDown();
                        });
                    });
                });
            } catch (Throwable t) {
                failure.compareAndSet(null, "during setup: " + stack(t));
                done.countDown();
            }
        });

        boolean finished = done.await(30, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("SMOKE TIMEOUT");
            System.exit(2);
        }
        String fail = failure.get();
        if (fail != null) {
            System.err.println("SMOKE FAILURE: " + fail);
            System.exit(1);
        }
        System.out.println("SMOKE OK: collapsed agent panel grows to show the whole input prompt");
        System.exit(0);
    }

    private static void checkPromptVisible(AiAgentActivityTabsPanel panel, String when, AtomicReference<String> failure) {
        try {
            Node promptBox = panel.lookup(".ai-agent-prompt-box");
            if (promptBox == null || !promptBox.isVisible()) {
                failure.compareAndSet(null, when + ": prompt box not visible");
                return;
            }
            Bounds prompt = promptBox.localToScene(promptBox.getBoundsInLocal());
            Bounds wrapper = panel.localToScene(panel.getBoundsInLocal());
            double needed = promptBox.prefHeight(promptBox.getLayoutBounds().getWidth());
            System.out.printf("%s: panel=%.0f prompt=%.0f/%.0f promptBottom=%.0f panelBottom=%.0f%n",
                when, panel.getHeight(), prompt.getHeight(), needed, prompt.getMaxY(), wrapper.getMaxY());
            if (prompt.getHeight() + 1.0 < needed) {
                failure.compareAndSet(null, when + ": prompt squeezed to " + prompt.getHeight() + " of " + needed);
            } else if (prompt.getMaxY() > wrapper.getMaxY() + 1.0) {
                failure.compareAndSet(null, when + ": prompt bottom " + prompt.getMaxY()
                    + " is below the panel bottom " + wrapper.getMaxY());
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, when + ": " + stack(t));
        }
    }

    private static void after(double seconds, Runnable action) {
        PauseTransition pause = new PauseTransition(Duration.seconds(seconds));
        pause.setOnFinished(event -> action.run());
        pause.play();
    }

    private static String stack(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
