package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Renders real tool dialogs (Snippet Manager, Snippet Editor) hosted as {@link DialogHostTab}s in a
 * shown stage, snapshots each tab to {@code build/smoke/tool-tab-*.png} for visual inspection, and
 * counts layout passes over a settle window to detect relayout/flicker loops.
 */
public final class ToolTabRenderSmoke {

    private ToolTabRenderSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-tool-tab-render-smoke");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Platform.startup(() -> run(isolatedHome, failure, done));

        boolean finished = done.await(90, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("ToolTabRenderSmoke TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("ToolTabRenderSmoke FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("ToolTabRenderSmoke OK");
        System.exit(0);
    }

    private static void run(Path isolatedHome, AtomicReference<String> failure, CountDownLatch done) {
        try {
            GlobalSettings settings = new GlobalSettings();
            settings.setLanguage("en");
            LanguageManager.getInstance().initialize(settings);

            SnippetManager snippetManager = new SnippetManager(isolatedHome.resolve(".kortty"));
            Snippet snippet = new Snippet();
            snippet.setName("server_load.pl");
            snippet.setLanguage("perl");
            snippet.setContent("#!/usr/bin/perl\nprint \"load\\n\";\n");
            snippetManager.addSnippet(snippet);

            TabPane tabPane = new TabPane();
            Stage stage = new Stage();
            stage.setScene(new Scene(tabPane, 1200, 900));
            stage.show();

            SnippetManagementDialog manager = new SnippetManagementDialog(snippetManager, null);
            DialogHostTab managerTab = DialogHostTab.host(tabPane, "snippets", manager, null);

            SnippetEditDialog editor = new SnippetEditDialog(snippet,
                java.util.List.of("General"));
            DialogHostTab editorTab = DialogHostTab.host(tabPane, null, editor, null);

            // Let the scene settle, then measure layout passes over one second: a loop shows up as
            // a layout request on (nearly) every pulse.
            PauseTransition settle = new PauseTransition(Duration.millis(1500));
            settle.setOnFinished(e1 -> {
                AtomicLong layoutPasses = new AtomicLong();
                AnimationTimer counter = new AnimationTimer() {
                    @Override
                    public void handle(long now) {
                        if (tabPane.isNeedsLayout()
                            || manager.getDialogPane().isNeedsLayout()
                            || editor.getDialogPane().isNeedsLayout()) {
                            layoutPasses.incrementAndGet();
                        }
                    }
                };
                counter.start();
                PauseTransition measure = new PauseTransition(Duration.millis(1000));
                measure.setOnFinished(e2 -> {
                    counter.stop();
                    System.out.println("layout passes during 1s settle window: " + layoutPasses.get());
                    tabPane.getSelectionModel().select(managerTab);
                    PauseTransition afterManagerSelect = new PauseTransition(Duration.millis(400));
                    afterManagerSelect.setOnFinished(e3 -> {
                        try {
                            describe("manager", manager.getDialogPane());
                            snapshot(manager.getDialogPane(), "tool-tab-snippet-manager.png");
                            assertFills("snippet manager", manager.getDialogPane(), stage);
                        } catch (Throwable error) {
                            failure.compareAndSet(null, stack(error));
                            stage.hide();
                            done.countDown();
                            return;
                        }
                        tabPane.getSelectionModel().select(editorTab);
                        PauseTransition afterEditorSelect = new PauseTransition(Duration.millis(400));
                        afterEditorSelect.setOnFinished(e4 -> {
                            try {
                                describe("editor", editor.getDialogPane());
                                snapshot(editor.getDialogPane(), "tool-tab-snippet-editor.png");
                                assertFills("snippet editor", editor.getDialogPane(), stage);
                                if (layoutPasses.get() > 30) {
                                    throw new IllegalStateException(
                                        "layout loop detected: " + layoutPasses.get() + " dirty pulses in 1s");
                                }
                            } catch (Throwable error) {
                                failure.compareAndSet(null, stack(error));
                            } finally {
                                stage.hide();
                                done.countDown();
                            }
                        });
                        afterEditorSelect.play();
                    });
                    afterManagerSelect.play();
                });
                measure.play();
            });
            settle.play();
        } catch (Throwable error) {
            failure.compareAndSet(null, stack(error));
            done.countDown();
        }
    }

    private static void describe(String label, Node node) {
        StringBuilder chain = new StringBuilder();
        for (Node n = node; n != null; n = n.getParent()) {
            chain.append(n.getClass().getSimpleName())
                .append(String.format("[%.0fx%.0f]", n.getLayoutBounds().getWidth(),
                    n.getLayoutBounds().getHeight()))
                .append(" <- ");
        }
        System.out.println(label + " chain: " + chain);
    }

    private static void assertFills(String label, javafx.scene.control.DialogPane pane, Stage stage) {
        double expectedMinHeight = stage.getScene().getHeight() - 120;
        if (pane.getHeight() < expectedMinHeight) {
            throw new IllegalStateException(label + " pane does not fill the tab: "
                + pane.getHeight() + " < " + expectedMinHeight);
        }
    }

    private static void snapshot(Node node, String fileName) throws Exception {
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.web("#1e1e1e"));
        WritableImage image = node.snapshot(parameters, null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        File output = new File("build/smoke", fileName);
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create snapshot directory: " + parent);
        }
        ImageIO.write(buffered, "png", output);
        System.out.println("Snapshot written: " + output.getAbsolutePath()
            + " (" + (int) image.getWidth() + "x" + (int) image.getHeight() + ")");
    }

    private static String stack(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
