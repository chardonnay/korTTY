package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
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
        // A remembered geometry narrower than the labels need (a 600px-wide connection editor was
        // stored by an earlier release): restored as stored, then widened once it shows.
        factories.put("connectionEditRestoredNarrow", () -> {
            ConnectionEditDialog dialog = new ConnectionEditDialog(stage, connection, app.getCredentialManager(),
                app.getSSHKeyManager(), app.getMasterPasswordManager().getMasterPassword());
            // Applied the way DialogGeometrySupport restores it: on the window, once it shows.
            dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, event -> {
                Stage restored = (Stage) dialog.getDialogPane().getScene().getWindow();
                DialogGeometrySupport.whenShowing(restored, () -> {
                    restored.setWidth(600);
                    restored.setHeight(800);
                });
            });
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

    /**
     * Clicks through every tab the way a user would and measures each one while it is showing
     * (lazily attached tab content only appears on selection), then restores the selection.
     * Only labels not already reported for the first picture are returned.
     */
    private static List<DialogContentFit.Shortfall> selectEveryTab(javafx.scene.control.DialogPane pane, Path snapshotPrefix) {
        List<DialogContentFit.Shortfall> found = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        DialogContentFit.shortfalls(pane).forEach(shortfall -> seen.add(shortfall.text()));
        int index = 0;
        for (Node node : pane.lookupAll(".tab-pane")) {
            if (!(node instanceof javafx.scene.control.TabPane tabPane) || !node.isVisible()) {
                continue;
            }
            javafx.scene.control.Tab original = tabPane.getSelectionModel().getSelectedItem();
            for (javafx.scene.control.Tab tab : tabPane.getTabs()) {
                if (tab.isDisable()) {
                    continue;
                }
                tabPane.getSelectionModel().select(tab);
                pane.applyCss();
                pane.layout();
                pane.layout();
                try {
                    ImageIO.write(SwingFXUtils.fromFXImage(pane.getScene().snapshot(null), null), "png",
                        Path.of(snapshotPrefix + "-tab" + (index++) + ".png").toFile());
                } catch (Exception ex) {
                    System.err.println("tab snapshot failed: " + ex);
                }
                for (DialogContentFit.Shortfall shortfall : DialogContentFit.shortfalls(pane)) {
                    if (seen.add(shortfall.text())) {
                        found.add(new DialogContentFit.Shortfall("[" + tab.getText() + "] " + shortfall.text(),
                            shortfall.width(), shortfall.height(), shortfall.growable()));
                    }
                }
            }
            tabPane.getSelectionModel().select(original);
        }
        return found;
    }

    /** A dialog with tab arrows: they step through the tabs, stop at the ends, and nothing covers them. */
    private static void checkTabArrows(String name, javafx.scene.control.DialogPane pane) {
        Node arrows = pane.lookup("." + TabPaneArrowNavigation.ARROWS_STYLE_CLASS);
        if (arrows == null) {
            return;
        }
        javafx.scene.control.TabPane tabPane = (javafx.scene.control.TabPane) pane.lookup(".tab-pane");
        List<javafx.scene.control.Button> buttons = ((javafx.scene.Parent) arrows).getChildrenUnmodifiable().stream()
            .filter(javafx.scene.control.Button.class::isInstance).map(javafx.scene.control.Button.class::cast).toList();
        javafx.scene.control.Button previous = buttons.get(0);
        javafx.scene.control.Button next = buttons.get(1);
        javafx.scene.control.Tab original = tabPane.getSelectionModel().getSelectedItem();
        tabPane.getSelectionModel().selectFirst();
        if (!previous.isDisabled() || next.isDisabled()) {
            FAILURES.add(name + ": on the first tab the back arrow must be disabled and the forward arrow enabled");
        }
        next.fire();
        if (tabPane.getSelectionModel().getSelectedIndex() != 1) {
            FAILURES.add(name + ": the forward arrow did not select the second tab");
        }
        previous.fire();
        if (tabPane.getSelectionModel().getSelectedIndex() != 0) {
            FAILURES.add(name + ": the back arrow did not return to the first tab");
        }
        // The documented keyboard path: Ctrl+Tab from a field inside the tab moves to the next tab.
        Node field = tabPane.getSelectionModel().getSelectedItem().getContent().lookup(".text-field");
        Node target = field != null ? field : tabPane;
        javafx.event.Event.fireEvent(target, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
            "", "", javafx.scene.input.KeyCode.TAB, false, true, false, false));
        if (tabPane.getSelectionModel().getSelectedIndex() != 1) {
            FAILURES.add(name + ": Ctrl+Tab did not move to the next tab");
        }
        javafx.event.Event.fireEvent(target, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
            "", "", javafx.scene.input.KeyCode.TAB, true, true, false, false));
        if (tabPane.getSelectionModel().getSelectedIndex() != 0) {
            FAILURES.add(name + ": Ctrl+Shift+Tab did not move back to the first tab");
        }
        tabPane.getSelectionModel().selectLast();
        if (previous.isDisabled() || !next.isDisabled()) {
            FAILURES.add(name + ": on the last tab the forward arrow must be disabled");
        }
        tabPane.getSelectionModel().select(original);
        pane.applyCss();
        pane.layout();
        // The header's tabs and overflow menu must end before the arrows begin.
        javafx.geometry.Bounds arrowBounds = arrows.localToScene(arrows.getLayoutBounds());
        for (Node header : tabPane.lookupAll(".tab, .control-buttons-tab")) {
            if (!header.isVisible()) {
                continue;
            }
            javafx.geometry.Bounds bounds = header.localToScene(header.getLayoutBounds());
            if (bounds.getMaxX() > arrowBounds.getMinX() + 0.5 && bounds.getMinX() < arrowBounds.getMaxX()
                && bounds.getMinY() < arrowBounds.getMaxY() && bounds.getMaxY() > arrowBounds.getMinY()) {
                FAILURES.add(name + ": a tab header overlaps the tab arrows");
                break;
            }
        }
        System.out.println("    " + name + ": tab arrows OK");
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
                List<DialogContentFit.Shortfall> cut = new ArrayList<>(DialogContentFit.shortfalls(dialog.getDialogPane()));
                cut.addAll(selectEveryTab(dialog.getDialogPane(), out.resolve(name)));
                checkTabArrows(name, dialog.getDialogPane());
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
