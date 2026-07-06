package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.update.AvailableUpdate;
import de.kortty.update.UpdateAsset;
import de.kortty.update.UpdateRelease;
import de.kortty.update.UpdateVersion;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless smoke harness for the "download complete" update dialog. It builds the real dialog via
 * {@link MainWindow#buildDownloadCompleteDialog} (owner-less, app-less — the theme helpers fall back
 * to the dark {@code terminal.css} palette when no app is present), asserts the download path and the
 * two action buttons are present, then snapshots it to {@code build/smoke/update-download-complete.png}.
 * This proves the regression fix: the plain {@link javafx.scene.control.Alert} it replaced rendered
 * white/unthemed, whereas the {@code .dialog-pane} background here is the dark {@code #2d2d2d} theme.
 * The stage is never shown. Run via the {@code updateDownloadCompleteSmoke} Gradle task. Exit 0 = OK.
 */
public final class UpdateDownloadCompleteSmoke {

    private UpdateDownloadCompleteSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + e));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                render();
            } catch (Throwable e) {
                failure.compareAndSet(null, "Smoke failed: " + e);
            } finally {
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
        System.out.println("updateDownloadCompleteSmoke OK");
    }

    private static void render() throws Exception {
        String assetName = "korTTY-macOS-2.5.0-arm64.dmg";
        UpdateAsset asset = new UpdateAsset(
            assetName,
            URI.create("https://example.test/download/v2.5.0/" + assetName),
            123_456_789L,
            "sha256:0000000000000000000000000000000000000000000000000000000000000000");
        UpdateRelease release = new UpdateRelease(
            "v2.5.0",
            "korTTY v2.5.0",
            URI.create("https://example.test/releases/v2.5.0"),
            Instant.parse("2026-07-01T10:00:00Z"),
            false,
            false,
            List.of(asset));
        AvailableUpdate update = new AvailableUpdate(
            release,
            asset,
            UpdateVersion.parse("2.5.0").orElseThrow(),
            UpdateVersion.parse("2.4.0").orElseThrow());
        Path downloaded = Path.of(System.getProperty("user.home"), "Downloads", assetName);

        javafx.scene.control.Dialog<Void> dialog =
            MainWindow.buildDownloadCompleteDialog(null, null, update, downloaded);
        DialogPane pane = dialog.getDialogPane();

        // The download path must be visible, and both action buttons present.
        List<Node> nodes = new ArrayList<>();
        collect(pane.getContent(), nodes);
        boolean pathShown = nodes.stream().anyMatch(n ->
            n instanceof TextField tf && downloaded.toString().equals(tf.getText()));
        long buttonCount = nodes.stream().filter(n -> n instanceof Button).count();
        if (!pathShown) {
            throw new AssertionError("Download path not shown in the dialog");
        }
        if (buttonCount < 2) {
            throw new AssertionError("Expected two action buttons (open + guide) but found " + buttonCount);
        }

        // The Dialog already owns the pane in its own (unshown) scene, so the theme stylesheets are
        // attached; snapshot the pane node directly after forcing a layout at its preferred size.
        pane.applyCss();
        pane.layout();
        double width = Math.max(pane.prefWidth(-1), 480);
        double height = pane.prefHeight(width);
        pane.resize(width, height);
        pane.applyCss();
        pane.layout();
        snapshot(pane, "update-download-complete.png");
    }

    /** Depth-first collect of every node under {@code root} (inclusive). */
    private static void collect(Node root, List<Node> out) {
        if (root == null) {
            return;
        }
        out.add(root);
        if (root instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }

    private static void snapshot(Node node, String fileName) throws Exception {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#2d2d2d"));
        WritableImage image = node.snapshot(params, null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        File out = new File("build/smoke/" + fileName);
        out.getParentFile().mkdirs();
        ImageIO.write(buffered, "png", out);
        System.out.println("Snapshot written: " + out.getAbsolutePath());
    }
}
