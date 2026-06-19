package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX smoke harness for the AI-agent side-dock feature. Exercises the riskiest part: an
 * {@link AiAgentActivityTabsPanel} re-parented between a bottom host and a side tab, switching between
 * the tabbed (bottom) and stacked (side) layouts while runs exist, plus constructing an
 * {@link AiAgentSidePanel}. Run via the {@code aiAgentSidePanelSmoke} Gradle task. Exit 0 = OK.
 */
public final class AiAgentSidePanelSmoke {

    private AiAgentSidePanelSmoke() {
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
                VBox bottomHost = new VBox();
                HBox.setHgrow(bottomHost, Priority.ALWAYS);
                TabPane sideTabs = new TabPane();
                AiAgentSidePanel sidePanel = new AiAgentSidePanel();
                sidePanel.setPrefWidth(320);
                HBox root = new HBox(bottomHost, sideTabs, sidePanel);
                stage.setScene(new Scene(root, 1100, 600));
                stage.show();

                AiAgentActivityPanel.RunMetadata md =
                    new AiAgentActivityPanel.RunMetadata(null, null, null, null, null, null, null);
                AiAgentActivityTabsPanel panel = new AiAgentActivityTabsPanel();
                bottomHost.getChildren().add(panel);

                // Two runs in the default tabbed (bottom) layout.
                panel.beginRun("run-1", "show the 5 biggest log files", () -> { }, b -> { }, () -> { }, md);
                panel.beginRun("run-2", "migrate the perl script to an ansible playbook", () -> { }, b -> { }, () -> { }, md);

                // Dock to the side: re-parent into a tab and switch to the stacked layout.
                bottomHost.getChildren().remove(panel);
                panel.setSideDocked(true);
                Tab outer = new Tab(AiAgentSidePanel.formatTabTitle(1, null));
                outer.setClosable(false);
                outer.setContent(panel);
                sideTabs.getTabs().add(outer);

                // A run started while docked must appear as a stacked section.
                panel.beginRun("run-3", "check why nginx failed to start", () -> { }, b -> { }, () -> { }, md);

                // Undock: switch back to tabs and re-parent into the bottom host.
                panel.setSideDocked(false);
                outer.setContent(null);
                bottomHost.getChildren().add(panel);

                // Let two full FX pulses elapse (deterministic, no fixed sleep) so any pending
                // re-parent + layout has run and could surface exceptions, then assert.
                Platform.runLater(() -> Platform.runLater(() -> {
                    try {
                        if (panel.runCount() != 3) {
                            failure.compareAndSet(null, "expected 3 runs after migrations, got " + panel.runCount());
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, "post-check: " + stack(t));
                    } finally {
                        done.countDown();
                    }
                }));
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
        System.out.println("SMOKE OK: side-dock re-parenting + stacked layout migration worked");
        System.exit(0);
    }

    private static String stack(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
