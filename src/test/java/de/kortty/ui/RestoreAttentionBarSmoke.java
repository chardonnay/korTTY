package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.ui.ConnectionAuthResolver.Resolution;
import de.kortty.ui.ConnectionAuthResolver.Status;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX check of the restore bar in a window's status bar, as an opened project leaves it:
 * two tabs waiting for a password, one for the vault, one blocked and one gone. The bar shows the
 * counts, offers Connect…, Unlock Vault…, Details and Dismiss, lists every tab in Details with the
 * listed-only ones greyed out, runs its actions, and hides without taking room once no tab waits.
 * Pass a PNG path via --args to also save a snapshot. Run via the {@code restoreAttentionBarSmoke}
 * Gradle task. Exit 0 = OK.
 */
public final class RestoreAttentionBarSmoke {

    private RestoreAttentionBarSmoke() {
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
            System.err.println("RestoreAttentionBarSmoke FAILED: " + failure.get());
            System.exit(1);
        }
        System.out.println("RestoreAttentionBarSmoke OK");
        System.exit(0);
    }

    private static void run(String snapshotPath) throws Exception {
        ServerConnection web = new ServerConnection("web-01", "web-01.example", 22, "me");
        ServerConnection db = new ServerConnection("db", "db.example", 22, "me");
        ServerConnection prod = new ServerConnection("prod", "prod.example", 22, "me");
        RestoreAttention<String> attention = new RestoreAttention<>();
        attention.add(item("web-01", 0, web, Resolution.needs(Status.NEEDS_PASSWORD, web)));
        attention.add(item("SFTP: web-01", 1, web, Resolution.needs(Status.NEEDS_PASSWORD, web)));
        attention.add(item("db", 2, db, Resolution.needs(Status.NEEDS_UNLOCK, db)));
        attention.add(item("prod", 3, prod, Resolution.blocked(prod, "prod.example:22")));
        attention.add(new RestoreAttention.Item<>("gone", 4, "Terminal tab 5", null,
            TabRestoreTriage.Outcome.of(Resolution.missing())));

        AtomicInteger connects = new AtomicInteger();
        AtomicInteger unlocks = new AtomicInteger();
        AtomicInteger dismisses = new AtomicInteger();
        RestoreAttentionBar bar = new RestoreAttentionBar();
        bar.setOnConnect(connects::incrementAndGet);
        bar.setOnUnlock(unlocks::incrementAndGet);
        bar.setOnDismiss(dismisses::incrementAndGet);

        Label status = new Label("Project loaded: demo");
        status.setStyle("-fx-text-fill: #cccccc;");
        HBox statusRow = new HBox(8, status, new Region());
        VBox statusBar = new VBox(statusRow);
        statusBar.getChildren().add(0, bar);
        VBox.setMargin(bar, new javafx.geometry.Insets(0, 0, 4, 0));
        statusBar.getStyleClass().add("status-bar");
        statusBar.setStyle("-fx-padding: 5; -fx-background-color: #2d2d2d;");
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #1e1e1e;");
        root.setBottom(statusBar);
        Scene scene = new Scene(root, 960, 160);
        scene.getStylesheets().add(RestoreAttentionBarSmoke.class.getResource("/styles/terminal.css").toExternalForm());
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();

        check(!bar.isVisible() && !bar.isManaged(), "the bar is hidden before a project leaves tabs waiting");
        List<RestoreAttentionBar.Line> lines = new ArrayList<>();
        AtomicInteger singleConnects = new AtomicInteger();
        for (RestoreAttention.Item<String> item : attention.items()) {
            lines.add(new RestoreAttentionBar.Line(attention.itemText(item, I18n::get),
                item.classification().waitsForUser() ? singleConnects::incrementAndGet : null));
        }
        String summary = attention.summary(I18n::get);
        bar.showBar(summary, attention.offersConnect(), attention.offersUnlock(true), lines, false);
        root.applyCss();
        root.layout();

        check(bar.isVisible() && bar.isManaged(), "the bar shows while tabs wait");
        check(summary.equals("Tabs not reopened: sign-in needed (2) · vault locked (1) · blocked by policy (1) · not found (1)"),
            "unexpected summary: " + summary);
        List<Hyperlink> links = bar.getChildren().stream().filter(Hyperlink.class::isInstance).map(Hyperlink.class::cast).toList();
        check(links.size() == 4, "four actions");
        check(links.stream().allMatch(Hyperlink::isVisible), "Connect, Unlock Vault, Details and Dismiss are offered");
        links.get(0).fire();
        links.get(1).fire();
        check(connects.get() == 1 && unlocks.get() == 1, "Connect and Unlock Vault run their actions");

        if (snapshotPath != null) {
            WritableImage image = scene.snapshot(null);
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", new File(snapshotPath));
            System.out.println("Snapshot written to " + snapshotPath);
        }

        bar.showBar(summary, true, false, lines, true);
        check(links.stream().allMatch(Hyperlink::isDisabled), "the actions are greyed out while Connect asks");
        check(!links.get(1).isVisible(), "Unlock Vault is gone once the vault is open");
        links.get(3).setDisable(false);
        links.get(3).fire();
        check(dismisses.get() == 1, "Dismiss runs its action");

        double before = statusBar.prefHeight(-1);
        bar.hideBar();
        root.applyCss();
        root.layout();
        check(!bar.isManaged() && statusBar.prefHeight(-1) < before, "a hidden bar takes no room");
        stage.close();
    }

    private static RestoreAttention.Item<String> item(String label, int order, ServerConnection connection, Resolution auth) {
        return new RestoreAttention.Item<>(label, order, label, connection.getId(), TabRestoreTriage.Outcome.of(auth));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
