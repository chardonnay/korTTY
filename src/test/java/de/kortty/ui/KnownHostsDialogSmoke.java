package de.kortty.ui;

import de.kortty.JavaFxPlatformSupport;
import de.kortty.core.LanguageManager;
import de.kortty.core.SshHostKeyTrustManager;
import de.kortty.core.SshHostKeyTrustManager.HostKeyPrompt.MismatchResolution;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Headless smoke harness for the host-key management UI. It seeds a temporary trust store, opens
 * the real {@link KnownHostsDialog}, filters and removes one entry through the confirmation (whose
 * default must be No), checks the policy-locked state, and then drives the changed-key alert of
 * {@link SshHostKeyTrustManager.JavaFxHostKeyPrompt}: Close is the default, "Review and Replace…"
 * opens the second confirmation, whose replace button stays disabled until the verification box
 * is ticked. Snapshots land in {@code build/smoke/}. Run via the {@code knownHostsDialogSmoke}
 * Gradle task. Exit 0 = OK.
 */
public final class KnownHostsDialogSmoke {

    private static final Deque<DialogStep> STEPS = new ArrayDeque<>();
    private static final AtomicReference<String> FAILURE = new AtomicReference<>();

    private KnownHostsDialogSmoke() {
    }

    private record DialogStep(String title, Consumer<DialogPane> action) {
    }

    public static void main(String[] args) throws Exception {
        JavaFxPlatformSupport.configureRenderer();
        CountDownLatch done = new CountDownLatch(1);
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            FAILURE.compareAndSet(null, "Uncaught on " + t.getName() + ": " + e));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                startDialogPoller();
                knownHostsDialog(done);
            } catch (Throwable e) {
                FAILURE.compareAndSet(null, "Smoke failed: " + e);
                done.countDown();
            }
        });

        boolean finished = done.await(90, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("Smoke timed out");
            System.exit(2);
        }
        if (FAILURE.get() != null) {
            System.err.println(FAILURE.get());
            System.exit(1);
        }
        System.out.println("knownHostsDialogSmoke OK");
        System.exit(0);
    }

    // ---- Known Hosts dialog ---------------------------------------------------------------------

    private static void knownHostsDialog(CountDownLatch done) throws Exception {
        Path store = Files.createTempDirectory("kortty-known-hosts-smoke").resolve("ssh-host-keys.properties");
        SshHostKeyTrustManager trust = new SshHostKeyTrustManager(store, new AcceptingPrompt(), () -> false);
        for (String host : List.of("alpha.example.com", "beta.internal", "gamma.lab")) {
            ServerConnection connection = new ServerConnection(host, host, 22, "demo");
            check(trust.verifierFor(connection).verifyServerKey(null, null, newKey()), "seeding " + host);
        }

        KnownHostsDialog dialog = new KnownHostsDialog(trust);
        dialog.show();
        TableView<?> table = field(dialog, "table", TableView.class);
        TextField search = field(dialog, "searchField", TextField.class);
        Button remove = field(dialog, "removeButton", Button.class);
        check(table.getItems().size() == 3, "three seeded rows, got " + table.getItems().size());
        check(remove.isDisabled(), "Remove is disabled without a selection");

        settle(() -> {
            snapshot(dialog.getDialogPane().getScene(), "known-hosts-dialog.png");
            search.setText("BETA");
            check(table.getItems().size() == 1, "filter 'BETA' keeps one row, got " + table.getItems().size());
            table.getSelectionModel().select(0);
            check(!remove.isDisabled(), "Remove is enabled for a selected row");

            STEPS.add(new DialogStep(I18n.get("ssh.knownHosts.remove.confirm.title"), pane -> {
                Button no = (Button) pane.lookupButton(ButtonType.NO);
                Button yes = (Button) pane.lookupButton(ButtonType.YES);
                check(no != null && no.isDefaultButton(), "No is the default of the remove confirmation");
                check(yes != null && !yes.isDefaultButton(), "Yes is not the default of the remove confirmation");
                yes.fire();
            }));
            Platform.runLater(() -> guarded(done, () -> {
                remove.fire();
                check(STEPS.isEmpty(), "the remove confirmation was shown");
                List<SshHostKeyTrustManager.TrustedHostKey> left = trust.listTrustedKeys();
                check(left.size() == 2, "two pins left after removal, got " + left.size());
                check(left.stream().noneMatch(key -> key.host().equals("beta.internal")), "beta was removed");
                check(table.getItems().isEmpty(), "the filtered view refreshed to no rows");
                search.setText("");
                check(table.getItems().size() == 2, "two rows after clearing the filter");
                dialog.close();
                lockedDialog(store, done);
            }));
        }, done);
    }

    private static void lockedDialog(Path store, CountDownLatch done) throws Exception {
        SshHostKeyTrustManager locked = new SshHostKeyTrustManager(store, new AcceptingPrompt(), () -> true);
        KnownHostsDialog dialog = new KnownHostsDialog(locked);
        dialog.show();
        TableView<?> table = field(dialog, "table", TableView.class);
        Button remove = field(dialog, "removeButton", Button.class);
        Label policy = field(dialog, "policyLabel", Label.class);
        table.getSelectionModel().select(0);
        check(remove.isDisabled(), "Remove is disabled while the policy locks pins");
        check(remove.getTooltip() != null, "the disabled Remove carries the managed-by tooltip");
        check(policy.isVisible() && !policy.getText().isBlank(), "the policy note is visible");
        settle(() -> {
            snapshot(dialog.getDialogPane().getScene(), "known-hosts-dialog-locked.png");
            dialog.close();
            Platform.runLater(() -> guarded(done, KnownHostsDialogSmoke::mismatchFlows));
        }, done);
    }

    // ---- Changed-key alert and replacement confirmation -----------------------------------------

    private static void mismatchFlows(CountDownLatch done) throws Exception {
        SshHostKeyTrustManager.JavaFxHostKeyPrompt prompt = new SshHostKeyTrustManager.JavaFxHostKeyPrompt();
        SshHostKeyTrustManager.HostKeyMismatch mismatch = new SshHostKeyTrustManager.HostKeyMismatch(
            "rebuilt.example.com", 22,
            "ssh-ed25519", "SHA256:oldOLDoldOLDoldOLDoldOLDoldOLDoldOLDoldOLD",
            "ssh-ed25519", "SHA256:newNEWnewNEWnewNEWnewNEWnewNEWnewNEWnewNEW");
        String mismatchTitle = I18n.get("ssh.hostKey.mismatch.title");
        String replaceTitle = I18n.get("ssh.hostKey.replace.title");
        String reviewLabel = I18n.get("ssh.hostKey.mismatch.reviewReplace");

        // 1. Review, look at the confirmation, then cancel: still blocked.
        STEPS.add(new DialogStep(mismatchTitle, pane -> {
            Button close = (Button) pane.lookupButton(ButtonType.CLOSE);
            Button review = buttonWithText(pane, reviewLabel);
            check(close != null && close.isDefaultButton(), "Close is the default of the mismatch alert");
            check(review != null && !review.isDefaultButton(), "Review and Replace is offered, not default");
            snapshot(pane.getScene(), "host-key-mismatch-alert.png");
            review.fire();
        }));
        STEPS.add(new DialogStep(replaceTitle, pane -> {
            Button replace = buttonWithData(pane, ButtonBar.ButtonData.OK_DONE);
            Button cancel = (Button) pane.lookupButton(ButtonType.CANCEL);
            CheckBox verified = first(pane, CheckBox.class);
            check(replace != null && replace.isDisabled(), "Replace is disabled until the box is ticked");
            check(!replace.isDefaultButton(), "Replace is never the default button");
            check(cancel != null && cancel.isDefaultButton(), "Cancel is the default of the confirmation");
            check(verified != null && !verified.isSelected(), "the verification box starts unticked");
            snapshot(pane.getScene(), "host-key-replace-confirm.png");
            verified.setSelected(true);
            check(!replace.isDisabled(), "ticking the box enables Replace");
            cancel.fire();
        }));
        MismatchResolution cancelled = prompt.resolveMismatch(mismatch, true);
        check(cancelled == MismatchResolution.KEEP_BLOCKED, "Cancel keeps the block, got " + cancelled);

        // 2. Review, tick, replace.
        STEPS.add(new DialogStep(mismatchTitle, pane -> buttonWithText(pane, reviewLabel).fire()));
        STEPS.add(new DialogStep(replaceTitle, pane -> {
            first(pane, CheckBox.class).setSelected(true);
            buttonWithData(pane, ButtonBar.ButtonData.OK_DONE).fire();
        }));
        MismatchResolution replaced = prompt.resolveMismatch(mismatch, true);
        check(replaced == MismatchResolution.REPLACE, "a ticked confirmation replaces, got " + replaced);

        // 3. Not allowed: no review button, the hint explains the way out.
        STEPS.add(new DialogStep(mismatchTitle, pane -> {
            check(buttonWithText(pane, reviewLabel) == null, "no Review button without permission");
            check(pane.getContentText().contains(I18n.get("ssh.hostKey.mismatch.knownHostsHint")),
                "the unattended alert points to Known Hosts");
            snapshot(pane.getScene(), "host-key-mismatch-alert-unattended.png");
            ((Button) pane.lookupButton(pane.getButtonTypes().getFirst())).fire();
        }));
        MismatchResolution unattended = prompt.resolveMismatch(mismatch, false);
        check(unattended == MismatchResolution.KEEP_BLOCKED, "unattended stays blocked, got " + unattended);
        check(STEPS.isEmpty(), "every expected dialog was shown");
        done.countDown();
    }

    // ---- Harness --------------------------------------------------------------------------------

    /** Answers the next expected dialog as soon as it is showing (runs inside nested event loops). */
    private static void startDialogPoller() {
        Timeline poller = new Timeline(new KeyFrame(Duration.millis(150), event -> {
            DialogStep next = STEPS.peek();
            if (next == null) {
                return;
            }
            for (Window window : new ArrayList<>(Window.getWindows())) {
                if (window.isShowing() && window instanceof Stage stage && next.title().equals(stage.getTitle())
                        && stage.getScene() != null && stage.getScene().getRoot() instanceof DialogPane pane) {
                    STEPS.poll();
                    try {
                        next.action().accept(pane);
                    } catch (Throwable e) {
                        FAILURE.compareAndSet(null, "Dialog step '" + next.title() + "' failed: " + e);
                        stage.close();
                    }
                    return;
                }
            }
        }));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
    }

    private interface Step {
        void run() throws Exception;
    }

    private interface DoneStep {
        void run(CountDownLatch done) throws Exception;
    }

    private static void guarded(CountDownLatch done, Step step) {
        try {
            step.run();
        } catch (Throwable e) {
            FAILURE.compareAndSet(null, "Smoke failed: " + e);
            done.countDown();
        }
    }

    private static void guarded(CountDownLatch done, DoneStep step) {
        guarded(done, () -> step.run(done));
    }

    private static void settle(Step step, CountDownLatch done) {
        PauseTransition settle = new PauseTransition(Duration.millis(300));
        settle.setOnFinished(ignored -> guarded(done, step));
        settle.play();
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(owner));
    }

    private static Button buttonWithText(DialogPane pane, String text) {
        for (ButtonType type : pane.getButtonTypes()) {
            if (pane.lookupButton(type) instanceof Button button && text.equals(button.getText())) {
                return button;
            }
        }
        return null;
    }

    private static Button buttonWithData(DialogPane pane, ButtonBar.ButtonData data) {
        for (ButtonType type : pane.getButtonTypes()) {
            if (type.getButtonData() == data && pane.lookupButton(type) instanceof Button button) {
                return button;
            }
        }
        return null;
    }

    private static <T extends Node> T first(Node root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                T found = first(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void snapshot(Scene scene, String fileName) {
        try {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            WritableImage image = scene.snapshot(null);
            File out = new File("build/smoke/" + fileName);
            out.getParentFile().mkdirs();
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", out);
            System.out.println("Wrote " + out.getPath() + " (" + (int) image.getWidth()
                + "x" + (int) image.getHeight() + ")");
        } catch (Exception e) {
            throw new AssertionError("Snapshot " + fileName + " failed: " + e, e);
        }
    }

    private static PublicKey newKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair().getPublic();
    }

    private static final class AcceptingPrompt implements SshHostKeyTrustManager.HostKeyPrompt {
        @Override
        public boolean confirmFirstUse(SshHostKeyTrustManager.HostKeyDetails details) {
            return true;
        }

        @Override
        public void warnMismatch(SshHostKeyTrustManager.HostKeyMismatch mismatch) {
        }

        @Override
        public void warnVerificationFailure(SshHostKeyTrustManager.HostKeyVerificationFailure failure) {
        }
    }
}
