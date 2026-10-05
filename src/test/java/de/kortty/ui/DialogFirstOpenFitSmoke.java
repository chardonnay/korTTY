package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Headed regression harness for "the tool windows open too small to read their labels". Opens the
 * Connection Manager, AI Manager and the Tools-menu windows against an isolated, empty home — the
 * state right after a fresh install, with no remembered window geometry — waits for the first
 * layout pulse and fails on every label, button or check box whose text is drawn cut short (with
 * an ellipsis). Writes {@code build/smoke/first-open/<dialog>.png} for visual inspection.
 *
 * <p>System properties: {@code kortty.fit.language} (default {@code de}, the longest labels),
 * {@code kortty.fit.dialogs} (comma list, default all), {@code kortty.fit.fontScale} (percent).</p>
 */
public final class DialogFirstOpenFitSmoke {

    private static final List<String> FAILURES = new ArrayList<>();
    private static final double LAPTOP_WIDTH = 1512;
    private static final double LAPTOP_HEIGHT = 945;
    private static final String LAPTOP_KEY = "smoke.laptopScreen";
    private static final List<Runnable> QUEUE = new ArrayList<>();

    private DialogFirstOpenFitSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("kortty-first-open-fit");
        System.setProperty("user.home", home.toString());
        String language = System.getProperty("kortty.fit.language", "de");
        Locale.setDefault(Locale.forLanguageTag(language));

        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                run(language, done);
            } catch (Throwable t) {
                t.printStackTrace();
                FAILURES.add("harness: " + t);
                done.countDown();
            }
        });
        boolean finished = done.await(5, TimeUnit.MINUTES);
        Platform.exit();
        if (!finished) {
            System.err.println("DialogFirstOpenFitSmoke TIMEOUT");
            System.exit(2);
        }
        if (!FAILURES.isEmpty()) {
            System.err.println("DialogFirstOpenFitSmoke FAILURE:");
            FAILURES.forEach(failure -> System.err.println("  " + failure));
            System.exit(1);
        }
        System.out.println("DialogFirstOpenFitSmoke OK");
        System.exit(0);
    }

    private static void run(String language, CountDownLatch done) throws Exception {
        KorTTYApplication app = new KorTTYApplication();
        app.init();
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        settings.setLanguage(language);
        // Every dialog must be a true first open: one closed earlier in this run would otherwise
        // store its geometry, and the next dialog of the same class would restore it.
        settings.setRememberWindowGeometry(false);
        String fontScale = System.getProperty("kortty.fit.fontScale");
        if (fontScale != null && !fontScale.isBlank()) {
            settings.setUiFontScalePercent(Integer.parseInt(fontScale.trim()));
        }
        LanguageManager.getInstance().initialize(settings);
        AppDesignStyleSupport.initializeGlobalStyling(settings.getAppDesign());

        // start() creates the scheduler service; the Job Scheduler window cannot open without it.
        if (app.getJobSchedulerService() == null) {
            de.kortty.jobscheduler.JobSchedulerService scheduler =
                new de.kortty.jobscheduler.JobSchedulerService(app, app.getConfigDirectory());
            scheduler.load();
            java.lang.reflect.Field field = KorTTYApplication.class.getDeclaredField("jobSchedulerService");
            field.setAccessible(true);
            field.set(app, scheduler);
        }

        Stage stage = new Stage();
        MainWindow window = new MainWindow(stage);
        window.show();
        stage.setWidth(1200);
        stage.setHeight(800);

        ServerConnection connection = new ServerConnection();
        connection.setName("demo");
        connection.setHost("demo.example.invalid");
        connection.setUsername("demo");

        Map<String, Supplier<Dialog<?>>> factories = new LinkedHashMap<>();
        factories.put("connectionManager", () -> new ConnectionManagerDialog(stage, app));
        factories.put("connectionEdit", () -> new ConnectionEditDialog(stage, connection,
            app.getCredentialManager(), app.getSSHKeyManager(), app.getMasterPasswordManager().getMasterPassword()));
        // The same dialog fitted to a 1512x945 laptop screen (this one may be much larger): it must
        // be held to that height and still show every label, its tabs scrolling instead.
        factories.put("connectionEditLaptop", () -> {
            ConnectionEditDialog dialog = new ConnectionEditDialog(stage, connection, app.getCredentialManager(),
                app.getSSHKeyManager(), app.getMasterPasswordManager().getMasterPassword());
            DialogContentFit.fit(dialog.getDialogPane(), LAPTOP_WIDTH * 0.92, LAPTOP_HEIGHT * 0.92);
            dialog.getDialogPane().getProperties().put(LAPTOP_KEY, Boolean.TRUE);
            return dialog;
        });
        factories.put("aiManager", () -> owned(new AiManagerDialog(window), stage));
        factories.put("jobScheduler", () -> new JobSchedulerDialog(app, stage));
        factories.put("snippetWorkspace", () -> owned(new SnippetWorkspaceDialog(app.getSnippetManager(), window), stage));
        factories.put("recordings", () -> owned(new TerminalRecordingManagerDialog(
            app.getGlobalSettingsManager(), new de.kortty.core.TerminalRecordingService()), stage));
        factories.put("sessionJournals", () -> owned(new SessionJournalManagerDialog(window), stage));
        factories.put("sessionProcesses", () -> owned(new SessionProcessesDialog(), stage));
        factories.put("asciiArt", () -> owned(new AsciiArtBannerDialog(window), stage));
        factories.put("savedChats", () -> owned(new SavedChatsDialog(window), stage));

        String selection = System.getProperty("kortty.fit.dialogs", "");
        List<String> names = selection.isBlank()
            ? new ArrayList<>(factories.keySet())
            : Arrays.asList(selection.split("\\s*,\\s*"));
        Path out = Path.of("build", "smoke", "first-open");
        Files.createDirectories(out);
        for (String name : names) {
            Supplier<Dialog<?>> factory = factories.get(name);
            if (factory == null) {
                throw new IllegalArgumentException("Unknown dialog: " + name);
            }
            QUEUE.add(() -> inspect(name, factory, out));
        }
        QUEUE.add(done::countDown);
        PauseTransition settle = new PauseTransition(Duration.millis(2500));
        settle.setOnFinished(e -> next());
        settle.play();
    }

    private static Dialog<?> owned(Dialog<?> dialog, Stage owner) {
        dialog.initOwner(owner);
        return dialog;
    }

    private static void next() {
        if (!QUEUE.isEmpty()) {
            QUEUE.remove(0).run();
        }
    }

    private static void inspect(String name, Supplier<Dialog<?>> factory, Path out) {
        Dialog<?> dialog;
        try {
            dialog = factory.get();
        } catch (Throwable t) {
            t.printStackTrace();
            FAILURES.add(name + ": could not be constructed: " + t);
            next();
            return;
        }
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, event -> {
            // Two pulses: the first lays out at the mapped size, the second settles late skins.
            PauseTransition wait = new PauseTransition(Duration.millis(600));
            wait.setOnFinished(e -> {
                Scene scene = dialog.getDialogPane().getScene();
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                List<DialogContentFit.Shortfall> cut = DialogContentFit.shortfalls(dialog.getDialogPane());
                Stage window = (Stage) scene.getWindow();
                System.out.printf(Locale.ROOT, "FIT %-18s window=%.0fx%.0f cut=%d%n",
                    name, window.getWidth(), window.getHeight(), cut.size());
                javafx.geometry.Rectangle2D screen = dialog.getDialogPane().getProperties().containsKey(LAPTOP_KEY)
                    ? new javafx.geometry.Rectangle2D(0, 0, LAPTOP_WIDTH, LAPTOP_HEIGHT)
                    : javafx.stage.Screen.getPrimary().getVisualBounds();
                if (window.getHeight() > screen.getHeight() || window.getWidth() > screen.getWidth()) {
                    FAILURES.add(String.format(Locale.ROOT, "%s: window %.0fx%.0f exceeds the screen %.0fx%.0f",
                        name, window.getWidth(), window.getHeight(), screen.getWidth(), screen.getHeight()));
                }
                for (DialogContentFit.Shortfall shortfall : cut) {
                    String line = String.format(Locale.ROOT, "%s: \"%s\" cut by %.0fx%.0f px%s",
                        name, shortfall.text(), shortfall.width(), shortfall.height(),
                        shortfall.growable() ? "" : " (fixed width)");
                    System.out.println("    " + line);
                    FAILURES.add(line);
                }
                try {
                    WritableImage image = scene.snapshot(null);
                    ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                        out.resolve(name + ".png").toFile());
                } catch (Exception ex) {
                    System.err.println("snapshot failed for " + name + ": " + ex);
                }
                dialog.close();
                if (window.isShowing()) {
                    // A dialog without a cancel button refuses close(); hiding the window works.
                    window.hide();
                }
                Platform.runLater(DialogFirstOpenFitSmoke::next);
            });
            wait.play();
        });
        dialog.show();
    }
}
