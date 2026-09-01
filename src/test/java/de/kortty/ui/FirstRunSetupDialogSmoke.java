package de.kortty.ui;

import de.kortty.JavaFxPlatformSupport;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.security.MasterPasswordManager;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless smoke harness for the first-run master-password setup dialog. It builds the real
 * {@link MasterPasswordDialog} against an empty config directory — so the manager reports no
 * password and the constructor takes the setup branch — and asserts the anonymous-statistics
 * checkbox is present in the rendered tree and <em>pre-selected</em>, which is what makes sharing
 * the default that a user opts out of rather than into. The dialog is never shown; the scene is
 * snapshotted to {@code build/smoke/first-run-setup-dialog.png} for a visual check.
 * Run via the {@code firstRunSetupDialogSmoke} Gradle task. Exit 0 = OK.
 */
public final class FirstRunSetupDialogSmoke {

    private FirstRunSetupDialogSmoke() {
    }

    public static void main(String[] args) throws Exception {
        JavaFxPlatformSupport.configureRenderer();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + e));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                render(failure, done);
            } catch (Throwable e) {
                failure.compareAndSet(null, "Smoke failed: " + e);
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("Smoke timed out");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println(failure.get());
            System.exit(1);
        }
        System.out.println("firstRunSetupDialogSmoke OK");
    }

    private static void render(AtomicReference<String> failure, CountDownLatch done) throws Exception {
        Path configDir = Files.createTempDirectory("kortty-first-run-smoke");
        MasterPasswordManager passwordManager = new MasterPasswordManager(configDir);
        if (passwordManager.isPasswordSet()) {
            throw new AssertionError("Fresh config dir must not report a master password");
        }

        MasterPasswordDialog dialog = new MasterPasswordDialog(null, passwordManager);
        Stage stage = stage(dialog);
        Scene scene = stage != null ? stage.getScene() : null;
        if (scene == null) {
            throw new AssertionError("The setup dialog has no scene");
        }

        List<Node> nodes = new ArrayList<>();
        collect(scene.getRoot(), nodes);
        List<CheckBox> checkBoxes = nodes.stream().filter(CheckBox.class::isInstance)
            .map(CheckBox.class::cast).toList();
        if (checkBoxes.size() != 1) {
            throw new AssertionError("Expected exactly one checkbox (the consent box) but found "
                + checkBoxes.size());
        }
        CheckBox consent = checkBoxes.get(0);
        String expectedLabel = I18n.get("masterPassword.telemetry.consent");
        if (!expectedLabel.equals(consent.getText())) {
            throw new AssertionError("Checkbox is not the consent box: " + consent.getText());
        }
        if (!consent.isSelected()) {
            throw new AssertionError("The anonymous-statistics consent box must be pre-selected");
        }
        if (consent.isDisabled()) {
            throw new AssertionError("The consent box must stay changeable without a policy");
        }

        stage.show();
        PauseTransition settle = new PauseTransition(Duration.millis(300));
        settle.setOnFinished(ignored -> {
            try {
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                assertRenderedInsideScene(scene, consent);
                snapshot(scene, "first-run-setup-dialog.png");
            } catch (Throwable e) {
                failure.compareAndSet(null, "Smoke failed after first render pulse: " + e);
            } finally {
                stage.close();
                try {
                    Files.deleteIfExists(configDir);
                } catch (Exception e) {
                    failure.compareAndSet(null, "Could not clean smoke config: " + e);
                }
                done.countDown();
            }
        });
        settle.play();
    }

    /** The dialog owns its Stage privately; the smoke shows it to exercise the real window layout. */
    private static Stage stage(MasterPasswordDialog dialog) throws Exception {
        Field field = MasterPasswordDialog.class.getDeclaredField("dialog");
        field.setAccessible(true);
        return (Stage) field.get(dialog);
    }

    private static void assertRenderedInsideScene(Scene scene, Node node) {
        var bounds = node.localToScene(node.getBoundsInLocal());
        if (!node.isVisible() || node.getOpacity() <= 0
            || bounds.getMaxX() <= 0 || bounds.getMinX() >= scene.getWidth()
            || bounds.getMaxY() <= 0 || bounds.getMinY() >= scene.getHeight()) {
            throw new AssertionError("Consent control is outside the visible scene: " + bounds
                + ", scene=" + scene.getWidth() + "x" + scene.getHeight());
        }
    }

    /** Depth-first collect of every node under {@code root} (inclusive). */
    private static void collect(Node root, List<Node> out) {
        if (root == null) {
            return;
        }
        out.add(root);
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }

    private static void snapshot(Scene scene, String fileName) throws Exception {
        WritableImage image = scene.snapshot(null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < buffered.getHeight() && colors.size() < 16; y += 4) {
            for (int x = 0; x < buffered.getWidth() && colors.size() < 16; x += 4) {
                colors.add(buffered.getRGB(x, y));
            }
        }
        if (colors.size() < 16) {
            throw new AssertionError("Rendered setup dialog is blank (only " + colors.size()
                + " sampled colors)");
        }
        File out = new File("build/smoke/" + fileName);
        out.getParentFile().mkdirs();
        ImageIO.write(buffered, "png", out);
        System.out.println("Wrote " + out.getPath() + " (" + (int) image.getWidth()
            + "x" + (int) image.getHeight() + ")");
    }
}
