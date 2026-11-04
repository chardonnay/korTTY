package de.kortty.ui;

import atlantafx.base.theme.CupertinoDark;
import de.kortty.agentdesign.AgentDesignPresets;
import de.kortty.agentdesign.AgentRole;
import de.kortty.core.AgentRunChatSource;
import de.kortty.core.LanguageManager;
import de.kortty.model.AgentActionCategory;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TerminalAgentModels;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Headed smoke for the run-to-chat handoff bar that a finished KI-Agent run shows in the pane's host: a run
 * with a source is offered (available, then unavailable when AI is off), the run menu's "Continue in AI chat"
 * entry opens the chat through the handler and hides the bar, a question typed in the bar is sent with the
 * open, and a classic-design run is offered the same way. Writes PNGs of each state to the directory given as
 * the first argument (default build/smoke/handoff), named after the design: {@code -Dkortty.smoke.design=normal}
 * for the Normal design, the default is the Cupertino dark design. Exit 0 = OK.
 *
 * <p>The AI chat itself (an AiResultTab) needs a running main window and is not built here.</p>
 */
public final class AgentChatHandoffSmoke {

    private static final String PROMPT = "why is /var full?";
    private static final String ANSWER = "/var/log/journal takes 21 GB.";
    private static final String COMMAND = "du -xh /var | tail -3";

    private AgentChatHandoffSmoke() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("kortty-handoff-smoke").toString());
        String design = System.getProperty("kortty.smoke.design", "cupertino-dark");
        Path output = Path.of(args.length > 0 ? args[0] : "build/smoke/handoff").toAbsolutePath();
        Files.createDirectories(output);
        List<String> failures = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(ready::countDown);
        ready.await(30, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
        if (!"normal".equalsIgnoreCase(design)) {
            Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());
        }

        onFx(() -> {
            GlobalSettings settings = new GlobalSettings();
            settings.setLanguage("en");
            LanguageManager.getInstance().initialize(settings);
            AgentDesignHost host = new AgentDesignHost(null);
            host.applyTheme(null);
            Stage stage = new Stage();
            Scene scene = new Scene(new StackPane(host), 900, 320);
            stage.setScene(scene);
            stage.show();

            List<String> calls = new ArrayList<>();
            boolean[] available = {true};
            host.setChatHandoffHandler(new AgentDesignHost.ChatHandoffHandler() {
                @Override
                public boolean available() {
                    return available[0];
                }

                @Override
                public boolean open(String runId, AgentRunChatSource source,
                                    AgentDesignHost.RunChatTarget target, String followUp) {
                    calls.add(runId + ":" + followUp);
                    return true;
                }
            });

            // A finished run in the default design, offered its chat.
            host.beginRun("h1", PROMPT, () -> { }, paused -> { }, () -> { },
                new AiAgentActivityPanel.RunMetadata("p1", "Workstation", "model", "", "me", "admin", "web-01"),
                AgentDesignPresets.STANDARD.with(AgentRole.HISTORY, "d16"));
            host.publishActivity("h1", command("h1:command:1"));
            host.showFinalMessage("h1", ANSWER, TerminalAgentModels.Phase.DONE);
            host.offerChatHandoff("h1", source("h1", PROMPT, TerminalAgentModels.Phase.DONE), new AgentDesignHost.RunChatTarget(null, null));
            host.finishRun("h1");
            shot(scene, output, "bar-available-" + design);
            Button continueButton = findButton(scene.getRoot(), "Continue in AI chat");
            expect(failures, continueButton != null && !continueButton.isDisable(), "the bar offers the chat when AI is on");
            expect(failures, findField(scene.getRoot()) != null && !findField(scene.getRoot()).isDisable(),
                "the follow-up field is enabled when AI is on");

            List<MenuItem> menu = host.runMenuItems("h1", host);
            MenuItem continueItem = menu.stream()
                .filter(item -> "Continue in AI chat".equals(item.getText())).findFirst().orElse(null);
            expect(failures, continueItem != null && !continueItem.isDisable(), "the run menu offers the chat");
            if (continueItem != null) {
                continueItem.fire();
            }
            expect(failures, calls.equals(List.of("h1:null")), "the menu opens the chat without a question: " + calls);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            expect(failures, !barOf(host).isVisible(), "the bar hides after the handoff");

            // A second run whose chat is asked a question from the bar.
            host.beginRun("h2", "and /home?", () -> { }, paused -> { }, () -> { },
                new AiAgentActivityPanel.RunMetadata("p1", "Workstation", "model", "", "me", "admin", "web-01"),
                AgentDesignPresets.STANDARD.with(AgentRole.HISTORY, "d16"));
            host.offerChatHandoff("h2", source("h2", "and /home?", TerminalAgentModels.Phase.DONE), new AgentDesignHost.RunChatTarget(null, null));
            host.finishRun("h2");
            TextField question = findField(scene.getRoot());
            if (question != null) {
                question.setText("Which directory is the biggest?");
                question.getOnAction().handle(new ActionEvent());
            }
            expect(failures, calls.contains("h2:Which directory is the biggest?"),
                "Enter in the bar sends the typed question: " + calls);

            // A cancelled run is offered too, marked as stopped; with AI off the controls are disabled.
            available[0] = false;
            host.beginRun("h3", "cancelled run", () -> { }, paused -> { }, () -> { },
                new AiAgentActivityPanel.RunMetadata("p1", "Workstation", "model", "", "me", "admin", "web-01"),
                AgentDesignPresets.STANDARD.with(AgentRole.HISTORY, "d16"));
            host.offerChatHandoff("h3", source("h3", "cancelled run", TerminalAgentModels.Phase.CANCELLED), new AgentDesignHost.RunChatTarget(null, null));
            host.finishRun("h3");
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            shot(scene, output, "bar-unavailable-" + design);
            Button disabled = findButton(scene.getRoot(), "Continue in AI chat");
            expect(failures, disabled != null && disabled.isDisable(), "the bar's button is disabled without AI");

            // The classic design: its run is offered the same way under the classic panel.
            available[0] = true;
            host.beginRun("c1", "classic run", () -> { }, paused -> { }, () -> { },
                new AiAgentActivityPanel.RunMetadata("p1", "Workstation", "model", "", "me", "admin", "web-01"),
                AgentDesignPresets.CLASSIC);
            host.publishActivity("c1", command("c1:command:1"));
            host.offerChatHandoff("c1", source("c1", "classic run", TerminalAgentModels.Phase.DONE), new AgentDesignHost.RunChatTarget(null, null));
            host.finishRun("c1");
            shot(scene, output, "bar-classic-" + design);
            expect(failures, barOf(host).isVisible(), "the classic design shows the bar");
        }, failures);

        failures.forEach(failure -> System.err.println("SMOKE FAILURE: " + failure));
        System.out.println(failures.isEmpty() ? "agent chat handoff smoke OK (" + design + ")"
            : failures.size() + " failure(s) (" + design + ")");
        int status = failures.isEmpty() ? 0 : 1;
        new Thread(() -> Runtime.getRuntime().halt(status)).start();
    }

    private static TerminalAgentModels.AgentActivity command(String id) {
        return new TerminalAgentModels.AgentActivity(id,
            TerminalAgentModels.AgentActivityType.ACTION, TerminalAgentModels.AgentActivityStatus.COMPLETED,
            "Read(" + COMMAND + ")", "Exit 0 - 1 output lines",
            "Find the largest\nCommand: " + COMMAND + "\nExit code: 0\nstdout:\n23G\t/var",
            TerminalAgentModels.AgentActivityTokenUsage.unknown(), 1L, true, true, AgentActionCategory.READ);
    }

    private static AgentRunChatSource source(String runId, String prompt, TerminalAgentModels.Phase phase) {
        Instant now = Instant.now();
        return new AgentRunChatSource(runId, "terminal-session", "connection-1", "web-01", prompt,
            phase == TerminalAgentModels.Phase.DONE ? ANSWER : null, phase, "p1", "Workstation", "model",
            now.minusSeconds(12), now, 12L, 900L, List.of(command(runId + ":command:1")),
            null, null, null, null);
    }

    private static AgentChatHandoffBar barOf(AgentDesignHost host) {
        return host.getChildren().stream()
            .filter(AgentChatHandoffBar.class::isInstance)
            .map(AgentChatHandoffBar.class::cast)
            .findFirst().orElseThrow();
    }

    private static Button findButton(Node root, String text) {
        return root.lookupAll(".button").stream()
            .filter(node -> node instanceof Button button && text.equals(button.getText()))
            .map(node -> (Button) node)
            .findFirst().orElse(null);
    }

    private static TextField findField(Node root) {
        return root.lookupAll(".text-field").stream()
            .filter(node -> node instanceof TextField)
            .map(node -> (TextField) node)
            .findFirst().orElse(null);
    }

    private static void shot(Scene scene, Path output, String name) {
        try {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            var image = scene.snapshot(null);
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", output.resolve(name + ".png").toFile());
        } catch (Exception e) {
            throw new IllegalStateException("Could not write " + name, e);
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
            } catch (Throwable e) {
                failures.add("exception: " + e);
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        done.await(60, TimeUnit.SECONDS);
    }
}
