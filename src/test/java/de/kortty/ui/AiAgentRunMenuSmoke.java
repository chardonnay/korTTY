package de.kortty.ui;

import de.kortty.agentdesign.AgentDesignPresets;
import de.kortty.agentdesign.AgentRole;
import de.kortty.core.LanguageManager;
import de.kortty.model.AgentActionCategory;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TerminalAgentModels;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headed smoke for the run menu and step menu every AI Agent Design offers: drives a real
 * {@link AgentDesignHost} (with the log table as history) through a run, and checks which entries the run
 * menu has and when they are enabled, that "run again", "copy answer" and "copy command" work, that the
 * pane's runs are listed, that the ⋯ button opens the menu, and that the terminal context menu gets the
 * last run. Exit 0 = OK.
 */
public final class AiAgentRunMenuSmoke {

    private static final String COMMAND = "du -xh /var | tail -3";

    private AiAgentRunMenuSmoke() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("kortty-run-menu-smoke").toString());
        List<String> failures = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(ready::countDown);
        ready.await(30, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
        AtomicInteger reruns = new AtomicInteger();

        onFx(() -> {
            GlobalSettings settings = new GlobalSettings();
            settings.setLanguage("en");
            LanguageManager.getInstance().initialize(settings);
            AgentDesignHost host = new AgentDesignHost(null);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(host), 900, 300));
            stage.show();
            host.beginRun("r1", "why is /var full?", () -> { }, paused -> { }, reruns::incrementAndGet,
                new AiAgentActivityPanel.RunMetadata("p1", "Workstation", "model", "", "me", "admin", "web-01"),
                AgentDesignPresets.STANDARD.with(AgentRole.HISTORY, "d16"));
            TerminalAgentModels.AgentActivity command = new TerminalAgentModels.AgentActivity("r1:command:1",
                TerminalAgentModels.AgentActivityType.ACTION, TerminalAgentModels.AgentActivityStatus.COMPLETED,
                "Read(" + COMMAND + ")", "Exit 0 - 1 output lines",
                "Find the largest\nCommand: " + COMMAND + "\nExit code: 0\nstdout:\n23G\t/var",
                TerminalAgentModels.AgentActivityTokenUsage.unknown(), 1L, true, true, AgentActionCategory.READ);
            host.publishActivity("r1", command);

            List<MenuItem> running = host.runMenuItems("r1", host);
            expect(failures, labels(running).equals(List.of("Workflow…", "Export this run", "Export all runs", "Run again",
                "Copy answer", "", "Runs of this terminal")), "run menu entries: " + labels(running));
            expect(failures, running.get(0).isDisable(), "Workflow is off while the run works");
            expect(failures, running.get(1).isDisable(), "export is off while the run works");
            expect(failures, running.get(3).isDisable(), "run again is off while the run works");

            host.showFinalMessage("r1", "/var/log/journal takes 21 GB.", TerminalAgentModels.Phase.DONE);
            host.finishRun("r1");
            List<MenuItem> done = host.runMenuItems("r1", host);
            expect(failures, !done.get(0).isDisable(), "Workflow is on for a finished run with a command");
            expect(failures, !done.get(1).isDisable() && !done.get(2).isDisable(), "export is on after the run");
            expect(failures, ((Menu) done.get(1)).getItems().size() == 7, "seven export formats");
            done.get(3).fire();
            expect(failures, reruns.get() == 1, "run again starts the run again");
            done.get(4).fire();
            expect(failures, "/var/log/journal takes 21 GB.".equals(Clipboard.getSystemClipboard().getString()),
                "copy answer copies the answer");

            List<MenuItem> step = host.stepMenuItems(command, host);
            expect(failures, labels(step).equals(List.of("Copy command", "Copy output", "Copy entry", "Open in Snippet Editor")),
                "step menu entries: " + labels(step));
            step.get(0).fire();
            expect(failures, COMMAND.equals(Clipboard.getSystemClipboard().getString()), "copy command copies the command");

            host.beginRun("r2", "and /home?", () -> { }, paused -> { }, () -> { }, null,
                AgentDesignPresets.STANDARD.with(AgentRole.HISTORY, "d16"));
            Menu runs = (Menu) host.runMenuItems("r2", host).get(6);
            expect(failures, !runs.isDisable() && runs.getItems().size() == 2, "both runs are listed");
            expect(failures, host.lastRunMenu(host) != null, "the terminal context menu gets the last run");

            Button more = (Button) host.getScene().getRoot().lookupAll(".button").stream()
                .filter(node -> node instanceof Button button && "⋯".equals(button.getText()))
                .findFirst().orElse(null);
            expect(failures, more != null, "the log table has a ⋯ button");
            if (more != null) {
                more.fire();
                ContextMenu open = openMenu(host);
                expect(failures, open != null && open.isShowing(), "⋯ opens the run menu");
                if (open != null) {
                    open.hide();
                }
            }
        }, failures);

        failures.forEach(failure -> System.err.println("SMOKE FAILURE: " + failure));
        System.out.println(failures.isEmpty() ? "run menu smoke OK" : failures.size() + " failure(s)");
        int status = failures.isEmpty() ? 0 : 1;
        new Thread(() -> Runtime.getRuntime().halt(status)).start();
    }

    private static List<String> labels(List<MenuItem> items) {
        return items.stream().map(item -> item.getText() != null ? item.getText() : "").toList();
    }

    private static ContextMenu openMenu(AgentDesignHost host) {
        try {
            java.lang.reflect.Field field = AgentDesignHost.class.getDeclaredField("openMenu");
            field.setAccessible(true);
            return (ContextMenu) field.get(host);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static void expect(List<String> failures, boolean condition, String what) {
        if (!condition) {
            failures.add(what);
        }
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
