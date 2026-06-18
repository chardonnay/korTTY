package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.animation.PauseTransition;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX smoke harness for {@link TerminalAgentCompletionPopup}. Boots the toolkit, shows the
 * history-mode popup (deletion enabled) with sample entries, and reports whether it actually becomes
 * visible without throwing during show/cell-render. Run via the {@code agentCompletionPopupSmoke}
 * Gradle task. Exit code 0 = popup shown OK, non-zero = failure (details on stderr).
 */
public final class TerminalAgentCompletionPopupSmoke {

    private TerminalAgentCompletionPopupSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + stack(e));
        });

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                Stage stage = new Stage();
                Pane root = new Pane();
                root.setPrefSize(900, 500);
                Scene scene = new Scene(root, 900, 500);
                stage.setScene(scene);
                stage.show();

                TerminalAgentCompletionPopup popup = new TerminalAgentCompletionPopup();
                List<TerminalAgentCompletionPopup.CompletionEntry> history = List.of(
                    new TerminalAgentCompletionPopup.CompletionEntry(
                        "show the 10 largest log files in this directory",
                        "show the 10 largest log files in this directory",
                        "2026-06-18 14:30"),
                    new TerminalAgentCompletionPopup.CompletionEntry(
                        "migrate find_biggest_files.pl to an ansible playbook that searches recursively and is very very long so it would otherwise squeeze out the timestamp column entirely",
                        "migrate find_biggest_files.pl to an ansible playbook that searches recursively and is very very long so it would otherwise squeeze out the timestamp column entirely",
                        "2026-06-17 09:05"),
                    new TerminalAgentCompletionPopup.CompletionEntry(
                        "legacy entry without timestamp", "legacy entry without timestamp", ""));
                List<TerminalAgentCompletionPopup.CompletionEntry> commands = List.of(
                    TerminalAgentCompletionPopup.CompletionEntry.of("agent"),
                    TerminalAgentCompletionPopup.CompletionEntry.of("agent-ask"),
                    TerminalAgentCompletionPopup.CompletionEntry.of("agent-plan"));

                // Reproduce the real-app reuse pattern: one popup instance reused across command and
                // history modes with hide/re-show cycles (this is what showAgentCompletion does).
                popup.show(root, commands,
                    selected -> System.out.println("SELECT(cmd)=" + selected),
                    () -> System.out.println("CLOSED(cmd)"));
                System.out.println("after command show -> isShowing=" + popup.isShowing());
                popup.hide();

                popup.setHistoryGeometry(560, 320,
                    (w, h) -> System.out.println("GEOMETRY-SAVED=" + w + "x" + h));
                popup.show(root, history,
                    selected -> System.out.println("SELECT(hist)=" + selected),
                    () -> System.out.println("CLOSED(hist)"),
                    prompt -> System.out.println("DELETE=" + prompt),
                    () -> System.out.println("CLEAR-ALL"));
                System.out.println("after first history show -> isShowing=" + popup.isShowing());
                popup.hide();

                popup.show(root, history,
                    selected -> System.out.println("SELECT(hist2)=" + selected),
                    () -> System.out.println("CLOSED(hist2)"),
                    prompt -> System.out.println("DELETE=" + prompt),
                    () -> System.out.println("CLEAR-ALL"));
                System.out.println("after second history show -> isShowing=" + popup.isShowing());

                // Let the layout pulse run so cells render (that is where a render-time throw surfaces).
                PauseTransition wait = new PauseTransition(Duration.millis(800));
                wait.setOnFinished(ev -> {
                    try {
                        if (!popup.isShowing()) {
                            failure.compareAndSet(null, "popup.isShowing()==false after show()");
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, "checking isShowing: " + stack(t));
                    } finally {
                        done.countDown();
                    }
                });
                wait.play();
            } catch (Throwable t) {
                failure.compareAndSet(null, "during setup: " + stack(t));
                done.countDown();
            }
        });

        boolean finished = done.await(30, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("SMOKE TIMEOUT (no completion within 30s)");
            System.exit(2);
        }
        String fail = failure.get();
        if (fail != null) {
            System.err.println("SMOKE FAILURE: " + fail);
            System.exit(1);
        }
        System.out.println("SMOKE OK: history popup shown without throwing");
        System.exit(0);
    }

    private static String stack(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
