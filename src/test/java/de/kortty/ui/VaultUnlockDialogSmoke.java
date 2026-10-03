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
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless smoke harness for the mid-session unlock dialog. It sets up a master password in a
 * temporary config directory, opens {@link MasterPasswordDialog#forUnlock} with a second manager
 * over that directory (a start with the startup prompt turned off, so the vault is locked), checks
 * the unlock wording, enters a wrong and then the right password through the real controls and
 * asserts the manager ends up unlocked. The rendered dialog is snapshotted to
 * {@code build/smoke/vault-unlock-dialog.png}. Run via the {@code vaultUnlockDialogSmoke} Gradle
 * task. Exit 0 = OK.
 */
public final class VaultUnlockDialogSmoke {

    private static final String PASSWORD = "smoke-master-pass";

    private VaultUnlockDialogSmoke() {
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
        System.out.println("vaultUnlockDialogSmoke OK");
    }

    private static void render(AtomicReference<String> failure, CountDownLatch done) throws Exception {
        Path configDir = Files.createTempDirectory("kortty-vault-unlock-smoke");
        new MasterPasswordManager(configDir).setupPassword(PASSWORD.toCharArray());
        MasterPasswordManager locked = new MasterPasswordManager(configDir);
        if (!locked.isLocked()) {
            throw new AssertionError("A second manager over the same profile must start locked");
        }
        Path emptyDir = Files.createTempDirectory("kortty-no-vault-smoke");
        try {
            MasterPasswordDialog.forUnlock(null, new MasterPasswordManager(emptyDir));
            throw new AssertionError("forUnlock must refuse a profile without a master password");
        } catch (IllegalStateException expected) {
            // no vault, nothing to unlock
        } finally {
            deleteRecursively(emptyDir);
        }

        MasterPasswordDialog dialog = MasterPasswordDialog.forUnlock(null, locked);
        Stage stage = stage(dialog);
        Scene scene = stage.getScene();
        if (!I18n.get("masterPassword.unlock.title").equals(stage.getTitle())) {
            throw new AssertionError("Unexpected window title: " + stage.getTitle());
        }

        List<Node> nodes = new ArrayList<>();
        collect(scene.getRoot(), nodes);
        PasswordField passwordField = nodes.stream().filter(PasswordField.class::isInstance)
            .map(PasswordField.class::cast).findFirst()
            .orElseThrow(() -> new AssertionError("No password field in the unlock dialog"));
        Button unlockButton = nodes.stream().filter(Button.class::isInstance).map(Button.class::cast)
            .filter(Button::isDefaultButton).findFirst()
            .orElseThrow(() -> new AssertionError("No default button in the unlock dialog"));
        if (!I18n.get("masterPassword.unlockButton").equals(unlockButton.getText())) {
            throw new AssertionError("Default button is not the unlock button: " + unlockButton.getText());
        }
        Label header = nodes.stream().filter(Label.class::isInstance).map(Label.class::cast)
            .filter(label -> I18n.get("masterPassword.unlock.header").equals(label.getText())).findFirst()
            .orElseThrow(() -> new AssertionError("The unlock header is missing"));

        stage.show();
        PauseTransition settle = new PauseTransition(Duration.millis(300));
        settle.setOnFinished(ignored -> {
            try {
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                assertNotTruncated(header);
                assertNotTruncated(unlockButton);
                snapshot(scene, "vault-unlock-dialog.png");

                passwordField.setText("wrong-password");
                unlockButton.fire();
                if (!locked.isLocked() || !stage.isShowing()) {
                    throw new AssertionError("A wrong password must keep the vault locked and the dialog open");
                }
                passwordField.setText(PASSWORD);
                unlockButton.fire();
                if (!locked.isUnlocked() || locked.getMasterPassword() == null) {
                    throw new AssertionError("The right password must unlock the vault");
                }
                if (stage.isShowing()) {
                    throw new AssertionError("A successful unlock must close the dialog");
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, "Smoke failed after first render pulse: " + e);
            } finally {
                if (stage.isShowing()) {
                    stage.close();
                }
                deleteRecursively(configDir);
                done.countDown();
            }
        });
        settle.play();
    }

    /** A label cut to "…" would hide the instruction or the action from the user. */
    private static void assertNotTruncated(javafx.scene.control.Labeled labeled) {
        Node text = labeled.lookup(".text");
        if (text instanceof javafx.scene.text.Text rendered && !labeled.getText().equals(rendered.getText())) {
            throw new AssertionError("'" + labeled.getText() + "' is rendered truncated as '" + rendered.getText() + "'");
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (Exception ignored) {
            // best-effort cleanup of the temp profile
        }
    }

    /** The dialog owns its Stage privately; the smoke shows it to exercise the real window layout. */
    private static Stage stage(MasterPasswordDialog dialog) throws Exception {
        Field field = MasterPasswordDialog.class.getDeclaredField("dialog");
        field.setAccessible(true);
        return (Stage) field.get(dialog);
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
        File out = new File("build/smoke/" + fileName);
        out.getParentFile().mkdirs();
        ImageIO.write(buffered, "png", out);
        System.out.println("Wrote " + out.getPath() + " (" + (int) image.getWidth()
            + "x" + (int) image.getHeight() + ")");
    }
}
