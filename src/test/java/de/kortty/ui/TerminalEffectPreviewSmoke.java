package de.kortty.ui;

import de.kortty.core.TerminalEffectPluginManager;
import de.kortty.plugin.terminaleffects.TerminalEffectPlugin;
import de.kortty.plugin.terminaleffects.TerminalEffectPreview;
import de.kortty.plugin.terminaleffects.mother.MotherTerminalEffectPlugin;
import de.kortty.plugin.terminaleffects.pack.AmberCrt90Plugin;
import de.kortty.plugin.terminaleffects.pack.CommodoreHeritagePlugin;
import de.kortty.plugin.terminaleffects.pack.DeepSpaceRadarPlugin;
import de.kortty.plugin.terminaleffects.pack.DigitalRainPlugin;
import de.kortty.plugin.terminaleffects.pack.HologramHudPlugin;
import de.kortty.plugin.terminaleffects.pack.NeonCityPlugin;
import de.kortty.plugin.terminaleffects.pack.PoltergeistPlugin;
import de.kortty.plugin.terminaleffects.pack.SynthwaveHorizonPlugin;
import de.kortty.plugin.terminaleffects.pack.TypewriterNoirPlugin;
import de.kortty.plugin.terminaleffects.pack.Vhs1987Plugin;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.TableView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline smoke check for the terminal effect previews.
 *
 * <p>For every built-in terminal effect plugin it creates the animated preview, lets it run a few
 * frames and snapshots it to {@code build/smoke/terminal-effect-&lt;id&gt;.png}. Run via the
 * {@code terminalEffectPreviewSmoke} Gradle task. Exit 0 = OK.</p>
 */
public final class TerminalEffectPreviewSmoke {

    private static final String OUTPUT_DIR = "build/smoke";

    private TerminalEffectPreviewSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch toolkitReady = new CountDownLatch(1);
        Platform.startup(toolkitReady::countDown);
        if (!toolkitReady.await(30, TimeUnit.SECONDS)) {
            System.err.println("SMOKE TIMEOUT: JavaFX toolkit did not start");
            System.exit(2);
        }
        Platform.setImplicitExit(false);

        List<TerminalEffectPlugin> plugins = List.of(
                new MotherTerminalEffectPlugin(),
                new AmberCrt90Plugin(),
                new CommodoreHeritagePlugin(),
                new NeonCityPlugin(),
                new DigitalRainPlugin(),
                new HologramHudPlugin(),
                new PoltergeistPlugin(),
                new Vhs1987Plugin(),
                new SynthwaveHorizonPlugin(),
                new DeepSpaceRadarPlugin(),
                new TypewriterNoirPlugin());

        File outDir = new File(OUTPUT_DIR);
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            System.err.println("Cannot create output dir: " + outDir.getAbsolutePath());
            System.exit(1);
        }

        List<String> failures = new ArrayList<>();
        for (TerminalEffectPlugin plugin : plugins) {
            try {
                snapshotPreview(plugin, outDir);
                System.out.println("  " + plugin.id() + " -> terminal-effect-" + plugin.id() + ".png");
            } catch (Throwable t) {
                failures.add(plugin.id() + ": " + t);
            }
        }
        try {
            snapshotManagerDialog(outDir);
            System.out.println("  manager dialog -> terminal-effect-manager-dialog.png");
        } catch (Throwable t) {
            failures.add("manager-dialog: " + t);
        }
        try {
            runOverlayVisibilityCheck();
            System.out.println("  overlay visibility -> hidden overlays release their backing store");
        } catch (Throwable t) {
            failures.add("overlay-visibility: " + t);
        }

        Platform.runLater(Platform::exit);
        if (!failures.isEmpty()) {
            failures.forEach(failure -> System.err.println("SMOKE FAILURE: " + failure));
            System.exit(1);
        }
        System.out.println("Generated " + plugins.size() + " preview snapshots into " + outDir.getAbsolutePath());
        System.exit(0);
    }

    private static void snapshotPreview(TerminalEffectPlugin plugin, File outDir) throws Exception {
        AtomicReference<TerminalEffectPreview> previewRef = new AtomicReference<>();
        AtomicReference<StackPane> rootRef = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        CountDownLatch built = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                TerminalEffectPreview preview = plugin.createPreview();
                if (preview == null) {
                    throw new IllegalStateException("plugin has no preview");
                }
                StackPane root = new StackPane(preview.node());
                new Scene(root);
                preview.start();
                previewRef.set(preview);
                rootRef.set(root);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                built.countDown();
            }
        });
        awaitStep(built, failure, "preview build");

        // Let a few animation frames run before capturing.
        Thread.sleep(450);

        CountDownLatch captured = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                WritableImage image = rootRef.get().snapshot(new SnapshotParameters(), null);
                BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
                ImageIO.write(buffered, "png", new File(outDir, "terminal-effect-" + plugin.id() + ".png"));
                previewRef.get().stop();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                captured.countDown();
            }
        });
        awaitStep(captured, failure, "snapshot");
    }

    /**
     * Builds the real plugin manager dialog headless, selects the first row (which must start the
     * selection-driven preview) and snapshots the dialog pane.
     */
    private static void snapshotManagerDialog(File outDir) throws Exception {
        TerminalEffectPluginManager manager = new TerminalEffectPluginManager(
                Files.createTempDirectory("kortty-smoke-effects"));
        manager.load();

        AtomicReference<StackPane> rootRef = new AtomicReference<>();
        AtomicReference<TerminalEffectPluginManagerDialog> dialogRef = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        CountDownLatch built = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                TerminalEffectPluginManagerDialog dialog =
                        new TerminalEffectPluginManagerDialog(null, manager);
                StackPane root = new StackPane(dialog.getDialogPane());
                new Scene(root);
                root.applyCss();
                root.layout();
                TableView<?> table = (TableView<?>) dialog.getDialogPane().lookup(".table-view");
                if (table == null) {
                    throw new IllegalStateException("plugin table not found in dialog");
                }
                if (table.getItems().isEmpty()) {
                    throw new IllegalStateException("plugin table is empty");
                }
                table.getSelectionModel().select(0);
                dialogRef.set(dialog);
                rootRef.set(root);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                built.countDown();
            }
        });
        awaitStep(built, failure, "manager dialog build");

        Thread.sleep(450);

        CountDownLatch captured = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                StackPane root = rootRef.get();
                root.applyCss();
                root.layout();
                WritableImage image = root.snapshot(new SnapshotParameters(), null);
                BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
                ImageIO.write(buffered, "png", new File(outDir, "terminal-effect-manager-dialog.png"));
                // Mirrors closing the dialog: the active preview must stop without errors.
                ((TableView<?>) dialogRef.get().getDialogPane().lookup(".table-view"))
                        .getSelectionModel().clearSelection();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                captured.countDown();
            }
        });
        awaitStep(captured, failure, "manager dialog snapshot");
    }

    private static void runOverlayVisibilityCheck() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                de.kortty.plugin.terminaleffects.pack.PackOverlayVisibilitySmokeSupport
                        .verifyHiddenOverlayReleasesBackingStore();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        awaitStep(done, failure, "overlay visibility check");
    }

    private static void awaitStep(
            CountDownLatch latch, AtomicReference<Throwable> failure, String step) throws Exception {
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException(step + " timed out");
        }
        Throwable throwable = failure.get();
        if (throwable != null) {
            throw new Exception(step + " failed", throwable);
        }
    }
}
