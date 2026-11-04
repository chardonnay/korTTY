package de.kortty.ui;

import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiTokenUsage;
import de.kortty.core.SessionJournalAiSupport;
import de.kortty.core.SessionJournalReevaluationJob;
import de.kortty.core.SessionJournalSession;
import de.kortty.model.AppDesign;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Labeled;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TreeTableView;
import javafx.scene.image.WritableImage;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real main window, real journal manager, headed: "Re-evaluate with AI…" runs in the background against a
 * scripted profile that needs a few seconds per call. Checks that the progress window shows a determinate bar,
 * the step, the elapsed and the remaining time; that the manager shows the run in its status line and as a badge
 * on the row, and keeps Delete and Re-evaluate off for that journal; that "Continue in background" closes the
 * window and leaves the run going; and that the report shows in the manager until it is read.
 *
 * <p>{@code user.home} is redirected to a throwaway directory first. {@code -Dkortty.smoke.design=normal} for
 * the Normal design, default AtlantaFX Cupertino Dark; {@code -Dkortty.smoke.lang}, default German. PNGs go to
 * the first argument (default {@code build/smoke/journal-reevaluation}). Exit 0 = OK.</p>
 */
public final class SessionJournalReevaluationSmoke {

    private static final long CALL_MILLIS = 2_500;

    private SessionJournalReevaluationSmoke() {
    }

    /** Answers like a model that needs {@link #CALL_MILLIS} per call. */
    private static final class SlowInvoker implements SessionJournalAiSupport.AiInvoker {
        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public AiExecutionResult execute(String systemPrompt, String userPrompt) throws Exception {
            Thread.sleep(CALL_MILLIS);
            return new AiExecutionResult("{\"title\":\"Nginx-Konfiguration geprüft\",\"summary\":\"Die Konfiguration "
                + "wurde mit `nginx -t` geprüft.\",\"category\":\"info\"}", new AiTokenUsage(900, 120, 1020));
        }
    }

    public static void main(String[] args) throws Exception {
        // FIRST: KorTTYApplication resolves its config directory from user.home in a static initializer.
        Path sandbox = Files.createTempDirectory("kortty-journal-reevaluation-smoke");
        System.setProperty("user.home", sandbox.toString());
        String design = System.getProperty("kortty.smoke.design", "cupertino-dark");
        Path output = Path.of(args.length > 0 ? args[0] : "build/smoke/journal-reevaluation").toAbsolutePath();
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
                // Several windows per journal, so the bar has steps to move through.
                settings.setSessionJournalAiChunkingEnabled(true);
                settings.setSessionJournalAiMaxLines(2);
                de.kortty.core.LanguageManager.getInstance().initialize(settings);
                AppDesignStyleSupport.initializeGlobalStyling(settings.getAppDesign());
                Stage stage = new Stage();
                MainWindow window = new MainWindow(stage);
                stage.setWidth(1180);
                stage.setHeight(800);
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
            System.err.println("SessionJournalReevaluationSmoke FAILURE: " + failures);
            Runtime.getRuntime().halt(2);
        }
        try {
            run(appRef.get(), windowRef.get(), stageRef.get(), output, design, failures);
        } catch (Throwable error) {
            failures.add(String.valueOf(error));
            error.printStackTrace();
        }
        if (failures.isEmpty()) {
            System.out.println("SessionJournalReevaluationSmoke PASS (" + design + ")");
        } else {
            failures.forEach(failure -> System.out.println("FAIL " + failure));
        }
        int code = failures.isEmpty() ? 0 : 1;
        new Thread(() -> Runtime.getRuntime().halt(code), "smoke-exit").start();
    }

    private static void run(de.kortty.KorTTYApplication app, MainWindow window, Stage stage, Path output, String design,
                            List<String> failures) throws Exception {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        ServerConnection connection = new ServerConnection("web-01 (Demo)", "10.0.0.15", 22, "demo");
        connection.getSessionJournalConfig().setEnabled(true);
        SessionJournalSession session = app.getSessionJournalService()
            .createSession(connection, "tab-reevaluation-smoke", settings, List.of(), false);
        session.start();
        String[][] commands = {
            {"sudo nginx -t", "nginx: configuration file /etc/nginx/nginx.conf test is successful"},
            {"systemctl status nginx", "Active: active (running)"},
            {"ss -tlnp | grep :80", "LISTEN 0 511 0.0.0.0:80"},
            {"curl -I http://localhost", "HTTP/1.1 200 OK"},
            {"tail -n 2 /var/log/nginx/error.log", "no errors"},
            {"df -h /", "/dev/sda1  40G  12G  28G  30% /"},
        };
        for (String[] command : commands) {
            session.appendInputLine(command[0]);
            session.appendOutputChunk(command[1] + "\n");
        }
        Path journal = session.getDirectory();
        session.close();

        SessionJournalManagerDialog manager = onFx(() -> {
            SessionJournalManagerDialog dialog = new SessionJournalManagerDialog(window);
            dialog.initOwner(stage);
            dialog.show();
            return dialog;
        });
        Scene managerScene = onFx(() -> manager.getDialogPane().getScene());
        settle(managerScene);
        @SuppressWarnings("unchecked")
        TreeTableView<SessionJournalTreeSupport.Node> table =
            (TreeTableView<SessionJournalTreeSupport.Node>) field(manager, "table");
        onFx(() -> {
            table.getSelectionModel().select(0);
            return null;
        });

        SessionJournalReevaluationJob job = app.getSessionJournalReevaluationJob();
        boolean queued = job.submit(new SessionJournalReevaluationJob.Request(
            List.of(new SessionJournalReevaluationJob.Target(journal, "web-01 (Demo)")), null, "Demo-Profil",
            new SlowInvoker(), false));
        check(queued, "the run was not queued", failures);
        onFx(() -> {
            SessionJournalReevaluateProgressWindow.show(stage, job);
            return null;
        });

        // Wait until one call finished (so a remaining time exists) and the next has run for a while.
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            var snapshot = job.snapshot();
            if (snapshot.estimate() != null && snapshot.estimate().remaining() != null
                    && snapshot.estimate().stepNumber() >= 2) {
                break;
            }
            Thread.sleep(100);
        }
        Thread.sleep(1_300);
        Dialog<?> progressDialog = progressDialog();
        check(progressDialog != null && onFx(progressDialog::isShowing), "the progress window is not showing", failures);
        Scene progressScene = onFx(() -> progressDialog.getDialogPane().getScene());
        settle(progressScene);
        String progressTexts = onFx(() -> String.join("\n", texts(progressDialog.getDialogPane())));
        System.out.println("  progress window:\n    " + progressTexts.replace("\n", "\n    "));
        ProgressBar bar = onFx(() -> find(progressDialog.getDialogPane(), ProgressBar.class));
        double fraction = onFx(bar::getProgress);
        // A VBox short of room once squeezed the bar to 0 px while the labels kept their lines.
        check(onFx(() -> bar.getHeight()) > 4, "the bar is squeezed to nothing: " + onFx(() -> bar.getHeight()), failures);
        Button keep = onFx(() -> button(progressDialog.getDialogPane(), I18n.get("journal.reevaluate.background")));
        check(keep != null && onFx(keep::isDefaultButton), "\"Continue in background\" is not the default button", failures);
        check(fraction > 0 && fraction < 1, "the bar is not determinate between 0 and 1: " + fraction, failures);
        check(progressTexts.lines().count() >= 3, "headline, step and time are not all shown: " + progressTexts, failures);
        check(progressTexts.contains(" · ") && !progressTexts.contains(I18n.get("journal.reevaluate.remaining.unknown")),
            "no remaining time in: " + progressTexts, failures);
        check(progressTexts.matches("(?s).*\\b0:0\\d\\b.*"), "no elapsed clock in: " + progressTexts, failures);
        snapshot(progressScene, output.resolve("progress-running-" + design + ".png"));

        settle(managerScene);
        String managerTexts = onFx(() -> String.join("\n", texts(manager.getDialogPane())));
        Node barNode = ((SessionJournalReevaluationBar) field(manager, "reevaluationBar")).getNode();
        check(onFx(barNode::isVisible), "the manager has no status line for the run", failures);
        String barText = onFx(() -> String.join(" ", texts((Parent) barNode)));
        System.out.println("  manager status line: " + barText);
        check(barText.contains("web-01 (Demo)") && barText.contains("%"), "the status line does not name the journal: "
            + barText, failures);
        check(managerTexts.contains(I18n.get("journal.manager.badge.reevaluating")), "no re-evaluating badge on the row",
            failures);
        Button delete = onFx(() -> button(manager.getDialogPane(), I18n.get("journal.manager.delete")));
        Button reevaluate = onFx(() -> button(manager.getDialogPane(), I18n.get("journal.reevaluate.button")));
        check(delete != null && onFx(delete::isDisabled), "Delete stays on for a journal being re-evaluated", failures);
        check(reevaluate != null && onFx(reevaluate::isDisabled), "Re-evaluate stays on for a journal in the run", failures);
        snapshot(managerScene, output.resolve("manager-running-" + design + ".png"));

        // "Continue in background": the window goes, the run stays.
        onFx(() -> {
            ((Button) progressDialog.getDialogPane().lookupButton(progressDialog.getDialogPane().getButtonTypes().stream()
                .filter(type -> type.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE)
                .findFirst().orElseThrow())).fire();
            return null;
        });
        Thread.sleep(300);
        check(!onFx(progressDialog::isShowing), "\"Continue in background\" did not close the window", failures);
        check(job.isRunning(), "closing the window stopped the run", failures);

        deadline = System.currentTimeMillis() + 60_000;
        while (job.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        check(!job.isRunning(), "the run did not finish", failures);
        Thread.sleep(1_200);
        settle(managerScene);
        String finished = onFx(() -> String.join(" ", texts((Parent) barNode)));
        System.out.println("  manager status line after the run: " + finished);
        check(finished.startsWith("✓"), "the status line does not report success: " + finished, failures);
        check(!onFx(() -> String.join("\n", texts(manager.getDialogPane())))
            .contains(I18n.get("journal.manager.badge.reevaluating")), "the badge stays after the run", failures);
        check(delete != null && !onFx(delete::isDisabled), "Delete stays off after the run", failures);
        snapshot(managerScene, output.resolve("manager-finished-" + design + ".png"));

        // "Show report" opens the window with the report; closing it clears the status line.
        Button report = onFx(() -> button((Parent) barNode, I18n.get("journal.reevaluate.strip.details")));
        check(report != null, "no report button in the status line", failures);
        if (report != null) {
            onFx(() -> {
                report.fire();
                return null;
            });
            Thread.sleep(500);
            Dialog<?> reportDialog = progressDialog();
            Scene reportScene = onFx(() -> reportDialog.getDialogPane().getScene());
            settle(reportScene);
            String reportTexts = onFx(() -> String.join("\n", texts(reportDialog.getDialogPane())));
            System.out.println("  report: " + reportTexts.replace("\n", " | "));
            check(reportTexts.contains("1"), "the report does not count the journal", failures);
            snapshot(reportScene, output.resolve("progress-report-" + design + ".png"));
            onFx(() -> {
                ((Button) reportDialog.getDialogPane().lookupButton(ButtonType.CLOSE)).fire();
                return null;
            });
            Thread.sleep(500);
            check(!onFx(barNode::isVisible), "the status line stays after the report was read", failures);
        }
    }

    private static Dialog<?> progressDialog() throws Exception {
        java.lang.reflect.Field open = SessionJournalReevaluateProgressWindow.class.getDeclaredField("open");
        open.setAccessible(true);
        Object window = open.get(null);
        if (window == null) {
            return null;
        }
        java.lang.reflect.Field dialog = SessionJournalReevaluateProgressWindow.class.getDeclaredField("dialog");
        dialog.setAccessible(true);
        return (Dialog<?>) dialog.get(window);
    }

    private static Object field(Object owner, String name) throws Exception {
        java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static List<String> texts(Parent root) {
        List<String> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Node node, List<String> out) {
        if (!node.isVisible()) {
            return;
        }
        if (node instanceof Labeled labeled && labeled.getText() != null && !labeled.getText().isBlank()) {
            out.add(labeled.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }

    private static Button button(Parent root, String text) {
        if (root instanceof Button button && text.equals(button.getText())) {
            return button;
        }
        for (Node child : root.getChildrenUnmodifiable()) {
            if (child instanceof Parent parent) {
                Button found = button(parent, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T> T find(Parent root, Class<T> type) {
        for (Node child : root.getChildrenUnmodifiable()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Parent parent) {
                T found = find(parent, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void settle(Scene scene) throws Exception {
        for (int i = 0; i < 4; i++) {
            Thread.sleep(120);
            onFx(() -> {
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                return null;
            });
        }
    }

    private static void snapshot(Scene scene, Path file) throws Exception {
        onFx(() -> {
            SnapshotParameters params = new SnapshotParameters();
            params.setTransform(Transform.scale(2, 2));
            WritableImage image = scene.getRoot().snapshot(params, null);
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
