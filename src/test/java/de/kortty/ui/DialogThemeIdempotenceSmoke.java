package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.model.AppDesign;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed check that a {@link ThemeAwareDialog} is styled exactly once: after {@code show()} its
 * pane and scene carry one copy of every stylesheet, the show itself performs no stylesheet-list
 * change (the global window listener finds the constructor's stamp and skips), no CSS pass is
 * forced before the window is on screen, and a live design switch still restyles the open dialog.
 * Runs against a real {@link KorTTYApplication#init()} in an isolated home with an AtlantaFX
 * design and a 130 % UI font scale — the combination that used to force two extra full-tree
 * {@code applyCss()} passes per dialog open.
 */
public final class DialogThemeIdempotenceSmoke {

    private static String probeCss() throws Exception {
        Path css = Files.createTempFile("kortty-probe-", ".css");
        css.toFile().deleteOnExit();
        Files.writeString(css, ".idempotence-probe { -fx-opacity: 0.5; }");
        return css.toUri().toString();
    }

    private DialogThemeIdempotenceSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("kortty-theme-idempotence");
        System.setProperty("user.home", home.toString());
        Locale.setDefault(Locale.ENGLISH);
        List<String> failures = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> crash = new AtomicReference<>();
        Platform.startup(() -> {
            try {
                run(failures, done);
            } catch (Throwable t) {
                crash.set(t);
                done.countDown();
            }
        });
        done.await(2, TimeUnit.MINUTES);
        if (crash.get() != null) {
            crash.get().printStackTrace();
            System.exit(1);
        }
        if (!failures.isEmpty()) {
            failures.forEach(f -> System.err.println("FAIL " + f));
            System.exit(1);
        }
        System.out.println("DIALOG THEME IDEMPOTENCE SMOKE OK");
        System.exit(0);
    }

    private static void run(List<String> failures, CountDownLatch done) throws Exception {
        KorTTYApplication app = new KorTTYApplication();
        app.init();
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        settings.setLanguage("en");
        settings.setAppDesign(AppDesign.ATLANTAFX_PRIMER_DARK);
        settings.setUiFontScaleAuto(false);
        settings.setUiFontScalePercent(130);
        LanguageManager.getInstance().initialize(settings);
        AppDesignStyleSupport.initializeGlobalStyling(settings.getAppDesign());

        // The owner stands in for the main window: JavaFX's Dialog.initOwner binds the dialog
        // scene's stylesheets to the owner scene's, so the owner must be a styled korTTY surface
        // exactly like MainWindow (registered base styles + design + font scale).
        Stage owner = new Stage();
        Scene ownerScene = new Scene(new VBox(new Label("owner")));
        AppDesignStyleSupport.registerApplicationBaseStyles(ownerScene);
        AppDesignStyleSupport.applyToScene(ownerScene);
        owner.setScene(ownerScene);
        owner.show();

        ThemeAwareDialog<ButtonType> dialog = new ThemeAwareDialog<>();
        dialog.initOwner(owner);
        Region probe = new Region();
        probe.getStyleClass().add("idempotence-probe");
        probe.setPrefSize(40, 40);
        dialog.getDialogPane().getStylesheets().add(probeCss());
        dialog.getDialogPane().setContent(new VBox(new Label("content"), probe));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Scene scene = dialog.getDialogPane().getScene();

        check(failures, "scene exists at construction", scene != null && scene.getRoot() == dialog.getDialogPane());
        check(failures, "no CSS pass forced before show (probe opacity still 1.0)", probe.getOpacity() == 1.0);
        check(failures, "pane stamped at construction",
            AppDesignStyleSupport.stampOf(dialog.getDialogPane().getProperties()) != null);
        check(failures, "scene stamped at construction",
            scene != null && AppDesignStyleSupport.stampOf(scene.getProperties()) != null);

        int[] paneChanges = {0};
        int[] sceneChanges = {0};
        dialog.getDialogPane().getStylesheets().addListener((ListChangeListener<String>) c -> paneChanges[0]++);
        scene.getStylesheets().addListener((ListChangeListener<String>) c -> sceneChanges[0]++);
        dialog.show();
        check(failures, "show() changed no pane stylesheets (was " + paneChanges[0] + ")", paneChanges[0] == 0);
        check(failures, "show() changed no scene stylesheets (was " + sceneChanges[0] + ")", sceneChanges[0] == 0);
        check(failures, "pane stylesheets unique: " + dialog.getDialogPane().getStylesheets(),
            unique(dialog.getDialogPane().getStylesheets()));
        check(failures, "scene stylesheets unique: " + scene.getStylesheets(), unique(scene.getStylesheets()));
        String components = AppDesignStyleSupport.atlantaFxComponentsStylesheetUrl();
        check(failures, "AtlantaFX component sheet on pane", dialog.getDialogPane().getStylesheets().contains(components));
        check(failures, "dialog scene mirrors the owner scene (bound by initOwner): " + scene.getStylesheets(),
            scene.getStylesheets().equals(ownerScene.getStylesheets()) && scene.getStylesheets().contains(components));
        check(failures, "font-scale sheet on pane", dialog.getDialogPane().getStylesheets().stream().anyMatch(u -> u.contains("kortty-uifont-")));

        Runnable[] afterPulse = new Runnable[1];
        afterPulse[0] = () -> {
            scene.removePostLayoutPulseListener(afterPulse[0]);
            check(failures, "first pulse applied the probe rule (opacity 0.5, was " + probe.getOpacity() + ")",
                probe.getOpacity() == 0.5);
            // Live switch: forced path must restyle the shown dialog even though it is stamped.
            String matrix = AppDesignStyleSupport.getMatrixStylesheetUrl();
            AppDesignStyleSupport.applyToOpenWindows(AppDesign.MATRIX_TERMINAL);
            check(failures, "live switch put the Matrix sheet on the pane",
                dialog.getDialogPane().getStylesheets().contains(matrix));
            check(failures, "live switch put the Matrix sheet on the scene", scene.getStylesheets().contains(matrix));
            check(failures, "live switch removed the AtlantaFX component sheet from the pane",
                !dialog.getDialogPane().getStylesheets().contains(components));
            check(failures, "pane stylesheets still unique after switch", unique(dialog.getDialogPane().getStylesheets()));
            // A second, non-forced pass (what the window listener does) must now be a no-op.
            int before = paneChanges[0];
            AppDesignStyleSupport.applyToWindow(scene.getWindow(), AppDesign.MATRIX_TERMINAL, false);
            check(failures, "non-forced re-application is a no-op", paneChanges[0] == before);
            dialog.close();
            owner.close();
            done.countDown();
        };
        scene.addPostLayoutPulseListener(afterPulse[0]);
    }

    private static boolean unique(List<String> list) {
        return new HashSet<>(list).size() == list.size();
    }

    private static void check(List<String> failures, String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) {
            failures.add(what);
        }
    }
}
