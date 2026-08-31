package de.kortty.ui;

import de.kortty.model.AppDesign;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Offline generator for the Settings &rarr; Appearance preview thumbnails.
 *
 * <p>For every {@link AppDesign} that has a preview image it builds a small, representative
 * korTTY chrome mock (title, menu bar, buttons, input, list, status bar with the animated
 * {@code .app-design-cursor} node), applies that design's stylesheet, and snapshots it to
 * {@code src/main/resources/previews/<id>-preview.png}. Reproducible and CSS-accurate.</p>
 *
 * <p>Run via the {@code generateDesignPreviews} Gradle task. Exit 0 = OK.</p>
 */
public final class AppDesignPreviewGenerator {

    private static final int WIDTH = 560;
    private static final int HEIGHT = 340;
    private static final String OUTPUT_DIR = "src/main/resources/previews";

    // Generated previews live here; the four original design previews remain hand-curated.
    private static final Set<AppDesign> GENERATED = EnumSet.of(
            AppDesign.AMBER_CRT, AppDesign.SYNTHWAVE_84,
            AppDesign.GRUVBOX_RETRO, AppDesign.NORD_ARCTIC, AppDesign.DRACULA,
            AppDesign.ATLANTAFX_PRIMER_DARK);

    private AppDesignPreviewGenerator() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                File outDir = new File(OUTPUT_DIR);
                if (!outDir.isDirectory() && !outDir.mkdirs()) {
                    throw new IllegalStateException("Cannot create output dir: " + outDir.getAbsolutePath());
                }
                writePreviewsSequentially(
                        List.copyOf(requestedDesigns()), 0, 0, outDir, failure, done);
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("PREVIEW GENERATION TIMEOUT");
            System.exit(2);
        }
        String fail = failure.get();
        if (fail != null) {
            System.err.println("PREVIEW GENERATION FAILURE: " + fail);
            System.exit(1);
        }
        System.exit(0);
    }

    private static void writePreviewsSequentially(
            List<AppDesign> designs,
            int index,
            int written,
            File outDir,
            AtomicReference<String> failure,
            CountDownLatch done) {
        if (index >= designs.size()) {
            System.out.println("Generated " + written + " design previews into " + outDir.getAbsolutePath());
            done.countDown();
            return;
        }

        AppDesign design = designs.get(index);
        AppDesignStyleSupport.applyUserAgentStylesheet(design);
        String previewResource = AppDesignStyleSupport.previewResource(design);
        String stylesheet = AppDesignStyleSupport.stylesheetUrl(design);
        if (previewResource == null || stylesheet == null) {
            writePreviewsSequentially(designs, index + 1, written, outDir, failure, done);
            return;
        }
        String fileName = previewResource.substring(previewResource.lastIndexOf('/') + 1);
        writePreview(
                design,
                stylesheet,
                new File(outDir, fileName),
                () -> writePreviewsSequentially(
                        designs, index + 1, written + 1, outDir, failure, done),
                error -> {
                    failure.compareAndSet(null, stack(error));
                    done.countDown();
                });
    }

    private static void writePreview(
            AppDesign design,
            String stylesheetUrl,
            File outFile,
            Runnable onWritten,
            Consumer<Throwable> onFailure) {
        VBox root = buildMock(design);
        root.getStyleClass().addAll("kortty-main-root");
        root.setPrefSize(WIDTH, HEIGHT);
        root.setMinSize(WIDTH, HEIGHT);
        root.setMaxSize(WIDTH, HEIGHT);

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        scene.setFill(Color.web(AppDesignStyleSupport.backgroundColor(design)));
        scene.getStylesheets().add(stylesheetUrl);
        Stage stage = new Stage(StageStyle.UNDECORATED);
        stage.setScene(scene);
        stage.show();

        // A shown Scene needs one real JavaFX pulse before its skins have render peers. Waiting
        // asynchronously keeps the FX thread free for that pulse and avoids blank background-only
        // snapshots from user-agent stylesheets such as AtlantaFX.
        PauseTransition settle = new PauseTransition(Duration.millis(100));
        settle.setOnFinished(event -> {
            try {
                root.applyCss();
                root.layout();
                WritableImage image = scene.snapshot(null);
                BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
                ImageIO.write(buffered, "png", outFile);
                System.out.println("  " + design.getId() + " -> " + outFile.getName());
                onWritten.run();
            } catch (Throwable t) {
                onFailure.accept(t);
            } finally {
                stage.hide();
            }
        });
        settle.play();
    }

    private static VBox buildMock(AppDesign design) {
        Label title = new Label("korTTY — " + prettyName(design));
        title.getStyleClass().add("field-label");
        HBox titleBar = new HBox(title);
        titleBar.setAlignment(Pos.CENTER_LEFT);

        MenuBar menuBar = new MenuBar(new Menu("File"), new Menu("Edit"), new Menu("View"), new Menu("Help"));

        Button connect = new Button("Connect");
        connect.setDefaultButton(true);
        Button cancel = new Button("Cancel");
        Button settings = new Button("Settings");
        HBox buttons = new HBox(10, connect, cancel, settings);

        TextField input = new TextField("kortty connect prod-01");
        input.setPromptText("ssh user@host");

        ListView<String> list = new ListView<>();
        list.getItems().addAll("Production  • prod-01", "Staging  • stg-02", "Local  • 127.0.0.1");
        list.getSelectionModel().select(0);
        list.setPrefHeight(96);

        Label statusLabel = new Label("session ready • 2 tabs");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Region cursor = new Region();
        cursor.getStyleClass().add("app-design-cursor");
        HBox statusBar = new HBox(8, statusLabel, spacer, cursor);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(6, 10, 6, 10));

        VBox root = new VBox(12, titleBar, menuBar, buttons, input, list, statusBar);
        root.setPadding(new Insets(16));
        return root;
    }

    private static String prettyName(AppDesign design) {
        String[] parts = design.getId().split("-");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private static Set<AppDesign> requestedDesigns() {
        String requested = System.getenv("KORTTY_PREVIEW_DESIGN");
        if (requested == null || requested.isBlank()) {
            return GENERATED;
        }
        AppDesign design = AppDesign.fromId(requested);
        if (design == AppDesign.NORMAL || !GENERATED.contains(design)) {
            throw new IllegalArgumentException("Unsupported generated design: " + requested);
        }
        return EnumSet.of(design);
    }

    private static String stack(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
