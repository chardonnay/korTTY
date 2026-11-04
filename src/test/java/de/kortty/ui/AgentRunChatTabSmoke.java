package de.kortty.ui;

import de.kortty.core.AgentRunChatSeeder;
import de.kortty.core.AgentRunChatSource;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AppDesign;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SavedAiChat;
import de.kortty.model.SessionJournalChatSnapshot;
import de.kortty.model.TerminalAgentModels;
import de.kortty.model.TerminalAgentModels.AgentActivity;
import de.kortty.model.TerminalAgentModels.AgentActivityStatus;
import de.kortty.model.TerminalAgentModels.AgentActivityType;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tab;
import javafx.scene.image.WritableImage;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed check of a KI-chat seeded from a finished terminal agent run, in the real main window: the chat opens
 * with the origin line and a collapsed "Steps (N)" disclosure under the answer, the disclosure expands to the
 * commands with their exit codes and output, and a journal copy of a deleted chat opens read-only without
 * writing anything back to the chat history.
 *
 * <p>{@code user.home} is redirected to a throwaway directory first, so no real profile data is read or shown.
 * Run in both designs: {@code -Dkortty.smoke.design=normal} or the default Cupertino Dark. PNGs go to the first
 * argument (default {@code build/smoke/agent-run-chat}). Exit 0 = OK.
 */
public final class AgentRunChatTabSmoke {

    private static final Instant STARTED = Instant.parse("2026-11-03T14:50:00Z");
    private static final Instant FINISHED = Instant.parse("2026-11-03T14:51:12Z");

    private AgentRunChatTabSmoke() {
    }

    public static void main(String[] args) throws Exception {
        // FIRST: KorTTYApplication resolves its config directory from user.home in a static initializer.
        Path sandbox = Files.createTempDirectory("kortty-agent-run-chat-smoke");
        System.setProperty("user.home", sandbox.toString());
        String design = System.getProperty("kortty.smoke.design", "cupertino-dark");
        Path output = Path.of(args.length > 0 ? args[0] : "build/smoke/agent-run-chat").toAbsolutePath();
        Files.createDirectories(output);

        List<String> failures = new ArrayList<>();
        AtomicReference<MainWindow> windowRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<de.kortty.KorTTYApplication> appRef = new AtomicReference<>();
        CountDownLatch built = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                de.kortty.KorTTYApplication app = new de.kortty.KorTTYApplication();
                app.init();
                GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
                settings.setLanguage(System.getProperty("kortty.smoke.lang", "de"));
                settings.setAppDesign("normal".equalsIgnoreCase(design) ? AppDesign.NORMAL : AppDesign.ATLANTAFX_CUPERTINO_DARK);
                AiProfile profile = new AiProfile();
                profile.setId("profile-1");
                profile.setName("GPT Ops");
                profile.setConnectionMode(AiConnectionMode.HTTP_API);
                profile.setModel("gpt-ops-model");
                settings.setAiProfiles(new ArrayList<>(List.of(profile)));
                de.kortty.core.LanguageManager.getInstance().initialize(settings);
                AppDesignStyleSupport.initializeGlobalStyling(settings.getAppDesign());
                Stage stage = new Stage();
                MainWindow window = new MainWindow(stage);
                stage.setWidth(1180);
                stage.setHeight(900);
                stage.show();
                appRef.set(app);
                stageRef.set(stage);
                windowRef.set(window);
            } catch (Throwable error) {
                failures.add("startup: " + error);
                error.printStackTrace();
            } finally {
                built.countDown();
            }
        });
        if (!built.await(90, TimeUnit.SECONDS) || !failures.isEmpty()) {
            System.err.println("AgentRunChatTabSmoke FAILURE: " + failures);
            Runtime.getRuntime().halt(2);
        }
        try {
            run(appRef.get(), windowRef.get(), stageRef.get(), output, design, failures);
        } catch (Throwable error) {
            failures.add(String.valueOf(error));
            error.printStackTrace();
        }
        if (failures.isEmpty()) {
            System.out.println("AgentRunChatTabSmoke PASS (" + design + ")");
        } else {
            failures.forEach(failure -> System.out.println("FAIL " + failure));
        }
        int code = failures.isEmpty() ? 0 : 1;
        new Thread(() -> Runtime.getRuntime().halt(code), "smoke-exit").start();
    }

    private static void run(de.kortty.KorTTYApplication app, MainWindow window, Stage stage, Path output, String design,
                            List<String> failures) throws Exception {
        SavedAiChat seeded = AgentRunChatSeeder.buildChat(source(), null, null);
        app.getAiChatManager().saveChat(seeded);
        int chatsBefore = app.getAiChatManager().getAllChats().size();

        AiResultTab tab = onFx(() -> window.openSavedAiChat(app.getAiChatManager().findById(seeded.getId()).orElseThrow()));
        check(tab != null, "the seeded chat did not open", failures);
        settle(stage);
        String texts = onFx(() -> String.join("\n", labeledTexts(tab.getContent())));
        check(texts.contains("nginx"), "the answer is not shown", failures);
        check(texts.contains(seeded.getConnectionDisplayName()), "the origin line does not name the connection", failures);
        Labeled toggle = onFx(() -> findLabeled(tab.getContent(), "▸ "));
        check(toggle != null && toggle.getText().contains("2"), "no collapsed steps disclosure with 2 steps: "
            + (toggle != null ? toggle.getText() : "none"), failures);
        check(!texts.contains("systemctl restart nginx"), "the steps are expanded before the click", failures);
        snapshot(stage, output.resolve("chat-collapsed-" + design + ".png"));

        if (toggle != null) {
            onFx(() -> {
                fire(toggle);
                return null;
            });
            settle(stage);
            String expanded = onFx(() -> String.join("\n", allTexts(tab.getContent())));
            check(expanded.contains("$ systemctl restart nginx"), "the expanded steps miss the command", failures);
            check(expanded.contains("HTTP/2 200"), "the expanded steps miss the output", failures);
            snapshot(stage, output.resolve("chat-expanded-" + design + ".png"));
        }

        // A journal copy of a chat that is no longer in the history opens read-only and never writes it back.
        SessionJournalChatSnapshot copy = AgentRunChatSeeder.journalSnapshot(seeded, null, OffsetDateTime.now());
        onFx(() -> {
            window.openJournalChat("no-such-chat", copy);
            return null;
        });
        settle(stage);
        Tab selected = onFx(() -> {
            Tab current = null;
            for (Node node : stage.getScene().getRoot().lookupAll(".tab-pane")) {
                if (node instanceof javafx.scene.control.TabPane pane
                    && pane.getSelectionModel().getSelectedItem() instanceof AiResultTab result && result != tab) {
                    current = result;
                }
            }
            return current;
        });
        check(selected != null, "the journal copy did not open in its own chat tab", failures);
        if (selected != null) {
            String copyTexts = onFx(() -> String.join("\n", labeledTexts(selected.getContent())));
            check(copyTexts.contains(I18n.get("ai.result.agentRun.snapshotOnly")), "the journal-copy notice is missing", failures);
            snapshot(stage, output.resolve("journal-copy-" + design + ".png"));
        }
        Thread.sleep(500);
        check(app.getAiChatManager().getAllChats().size() == chatsBefore, "opening the journal copy wrote a chat", failures);
    }

    private static AgentRunChatSource source() {
        List<AgentActivity> activities = List.of(
            command("a1", "systemctl restart nginx", ""),
            command("a2", "curl -sI https://localhost", "HTTP/2 200\nserver: nginx"),
            new AgentActivity("m1", AgentActivityType.MESSAGE, AgentActivityStatus.COMPLETED, "Answer",
                "nginx restarted", "nginx restarted and answers on port 443.",
                TerminalAgentModels.AgentActivityTokenUsage.unknown(), 1L, false, false));
        return new AgentRunChatSource("run-smoke", "tab-1", "conn-1", "web01 (demo)",
            "Restart nginx and check that port 443 answers", "nginx restarted and answers on port 443.",
            TerminalAgentModels.Phase.DONE, "profile-1", "GPT Ops", "gpt-ops-model", STARTED, FINISHED, 72L, 4200L,
            activities, null, null, null, null);
    }

    private static AgentActivity command(String id, String command, String output) {
        return new AgentActivity(id, AgentActivityType.ACTION, AgentActivityStatus.COMPLETED,
            "Run(" + command + ")", "Exit 0", "Command: " + command + "\nExit code: 0\nstdout:\n" + output,
            TerminalAgentModels.AgentActivityTokenUsage.unknown(), 2L, false, false);
    }

    private static void fire(Labeled toggle) {
        if (toggle instanceof javafx.scene.control.ButtonBase button) {
            button.fire();
        } else {
            toggle.fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                0, 0, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null));
        }
    }

    private static Labeled findLabeled(Node root, String prefix) {
        if (root instanceof Labeled labeled && labeled.getText() != null && labeled.getText().startsWith(prefix)
            && labeled.isVisible()) {
            return labeled;
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Labeled found = findLabeled(child, prefix);
                if (found != null) {
                    return found;
                }
            }
        }
        if (root instanceof javafx.scene.control.ScrollPane scroll) {
            return findLabeled(scroll.getContent(), prefix);
        }
        return null;
    }

    private static List<String> labeledTexts(Node root) {
        List<String> texts = new ArrayList<>();
        collect(root, texts, false);
        return texts;
    }

    private static List<String> allTexts(Node root) {
        List<String> texts = new ArrayList<>();
        collect(root, texts, true);
        return texts;
    }

    private static void collect(Node node, List<String> into, boolean withInputs) {
        if (node == null || !node.isVisible()) {
            return;
        }
        if (node instanceof Labeled labeled && labeled.getText() != null) {
            into.add(labeled.getText());
        } else if (node instanceof javafx.scene.text.Text text && text.getText() != null) {
            into.add(text.getText());
        } else if (withInputs && node instanceof javafx.scene.control.TextInputControl input) {
            into.add(input.getText());
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll) {
            collect(scroll.getContent(), into, withInputs);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, into, withInputs);
            }
        }
    }

    private static void settle(Stage stage) throws Exception {
        for (int i = 0; i < 6; i++) {
            Thread.sleep(150);
            onFx(() -> {
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                return null;
            });
        }
    }

    private static void snapshot(Stage stage, Path file) throws Exception {
        onFx(() -> {
            SnapshotParameters params = new SnapshotParameters();
            params.setTransform(Transform.scale(1, 1));
            WritableImage image = stage.getScene().getRoot().snapshot(params, null);
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
            return null;
        });
        System.out.println("  wrote " + file);
    }

    private static void check(boolean condition, String message, List<String> failures) {
        if (!condition) {
            failures.add(message);
        }
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the FX thread did not respond");
        }
        if (error.get() != null) {
            throw new IllegalStateException(error.get());
        }
        return result.get();
    }
}
