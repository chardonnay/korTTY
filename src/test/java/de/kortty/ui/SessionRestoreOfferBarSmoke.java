package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.SessionRestoreDecision;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX check of the startup offer in a window's status bar, above the restore bar: it is
 * hidden until offered, shows the prompt with the counts, styled like the restore bar, runs Restore
 * and Dismiss, says why it asks after a crash, and takes no room once hidden. Pass a PNG path via
 * --args to also save a snapshot. Run via the {@code sessionRestoreOfferBarSmoke} Gradle task.
 * Exit 0 = OK.
 */
public final class SessionRestoreOfferBarSmoke {

    private SessionRestoreOfferBarSmoke() {
    }

    public static void main(String[] args) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.ENGLISH);
        String snapshotPath = args.length > 0 ? args[0] : null;
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                run(snapshotPath);
            } catch (Throwable e) {
                failure.set(e.toString());
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS)) {
            failure.compareAndSet(null, "timed out");
        }
        Platform.exit();
        if (failure.get() != null) {
            System.err.println("SessionRestoreOfferBarSmoke FAILED: " + failure.get());
            System.exit(1);
        }
        System.out.println("SessionRestoreOfferBarSmoke OK");
        System.exit(0);
    }

    private static void run(String snapshotPath) throws Exception {
        AtomicInteger restores = new AtomicInteger();
        AtomicInteger dismisses = new AtomicInteger();
        SessionRestoreOfferBar bar = new SessionRestoreOfferBar();
        bar.setOnRestore(restores::incrementAndGet);
        bar.setOnDismiss(dismisses::incrementAndGet);
        RestoreAttentionBar attentionBar = new RestoreAttentionBar();

        Label status = new Label("Ready");
        status.setStyle("-fx-text-fill: #cccccc;");
        HBox statusRow = new HBox(8, status, new Region());
        VBox statusBar = new VBox(statusRow);
        statusBar.getChildren().add(0, attentionBar);
        statusBar.getChildren().add(0, bar);
        VBox.setMargin(bar, new javafx.geometry.Insets(0, 0, 4, 0));
        statusBar.getStyleClass().add("status-bar");
        statusBar.setStyle("-fx-padding: 5; -fx-background-color: #2d2d2d;");
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #1e1e1e;");
        root.setBottom(statusBar);
        Scene scene = new Scene(root, 960, 140);
        scene.getStylesheets().add(SessionRestoreOfferBarSmoke.class.getResource("/styles/terminal.css").toExternalForm());
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();

        check(!bar.isVisible() && !bar.isManaged(), "the offer is hidden until korTTY offers the session");
        double hiddenHeight = statusBar.prefHeight(-1);

        SessionRestoreDecision.Facts facts = new SessionRestoreDecision.Facts(true, 2, 6, false);
        SessionRestoreCoordinator texts = new SessionRestoreCoordinator(new NoHost(),
            new SessionRestoreDecision.Decision(SessionRestoreDecision.Action.OFFER, false), facts, I18n::get);
        String prompt = texts.offerText(false);
        check(prompt.equals("Restore the windows and tabs from before this start? Windows: 2, tabs: 6"),
            "unexpected prompt: " + prompt);
        bar.showOffer(prompt);
        root.applyCss();
        root.layout();

        check(bar.isOffering() && bar.isManaged(), "the offer shows");
        check(bar.getStyleClass().contains(RestoreAttentionBar.STYLE_CLASS), "styled like the restore bar");
        check(statusBar.prefHeight(-1) > hiddenHeight, "the offer takes a row above the status line");
        List<Hyperlink> links = bar.actions();
        check(links.get(0).getText().equals("Restore") && links.get(1).getText().equals("Dismiss"),
            "Restore and Dismiss are offered");
        check(links.stream().allMatch(Hyperlink::isVisible), "both actions are visible");
        check(links.get(0).getLayoutX() + links.get(0).getWidth() <= links.get(1).getLayoutX(),
            "Restore comes before Dismiss");

        if (snapshotPath != null) {
            WritableImage image = scene.snapshot(null);
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", new File(snapshotPath));
            System.out.println("Snapshot written to " + snapshotPath);
        }

        links.get(0).fire();
        links.get(1).fire();
        check(restores.get() == 1 && dismisses.get() == 1, "Restore and Dismiss run their actions");

        String afterCrash = texts.offerText(true);
        check(afterCrash.startsWith("korTTY ended unexpectedly after the last restore.") && afterCrash.endsWith("Windows: 2, tabs: 6"),
            "unexpected crash prompt: " + afterCrash);

        bar.hideOffer();
        root.applyCss();
        root.layout();
        check(!bar.isManaged() && statusBar.prefHeight(-1) == hiddenHeight, "a hidden offer takes no room");
        stage.close();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** The texts need no host. */
    private static final class NoHost implements SessionRestoreCoordinator.Host {
        @Override
        public boolean modalShowing() {
            return false;
        }

        @Override
        public boolean previousRestorable() {
            return false;
        }

        @Override
        public void keepPreviousSession() {
        }

        @Override
        public void offer(String text) {
        }

        @Override
        public void restore() {
        }

        @Override
        public void schedule(long delayMillis, Runnable task) {
        }
    }
}
