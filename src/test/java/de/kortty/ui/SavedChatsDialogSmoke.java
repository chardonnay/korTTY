package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Modality;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed smoke harness for {@link SavedChatsDialog}. Verifies the standalone saved-chats window is
 * modeless, renders two primary tabs ("Chats" and the swarm-chats section) and paints real content.
 * A 2x snapshot is written to {@code build/smoke/saved-chats.png} for visual inspection.
 */
public final class SavedChatsDialogSmoke {

    private static final double WIDTH = 900;
    private static final double HEIGHT = 600;

    private SavedChatsDialogSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-saved-chats-smoke");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Platform.startup(() -> run(failure, done));

        boolean finished = done.await(45, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("SavedChatsDialogSmoke TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("SavedChatsDialogSmoke FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("SavedChatsDialogSmoke OK");
        System.exit(0);
    }

    private static void run(AtomicReference<String> failure, CountDownLatch done) {
        SavedChatsDialog dialog = null;
        try {
            GlobalSettings settings = new GlobalSettings();
            settings.setLanguage("en");
            LanguageManager.getInstance().initialize(settings);

            dialog = new SavedChatsDialog(null);
            check(dialog.getModality() == Modality.NONE,
                "Saved chats window must be modeless so the main window stays usable");
            dialog.show();

            DialogPane dialogPane = dialog.getDialogPane();
            Stage stage = (Stage) dialogPane.getScene().getWindow();
            stage.setWidth(WIDTH);
            stage.setHeight(HEIGHT);

            check(dialogPane.getContent() instanceof TabPane,
                "Saved chats window content must be a TabPane");
            TabPane tabs = (TabPane) dialogPane.getContent();
            check(tabs.getTabs().size() == 2,
                "expected two saved-chats tabs, got " + tabs.getTabs().size());
            List<String> titles = tabs.getTabs().stream().map(t -> t.getText()).toList();
            check(titles.equals(List.of("Chats", "Swarm Chats")),
                "unexpected saved-chats tab titles: " + titles);

            dialogPane.applyCss();
            dialogPane.layout();
            snapshot(dialogPane, "saved-chats.png");
        } catch (Throwable error) {
            failure.compareAndSet(null, stack(error));
        } finally {
            if (dialog != null) {
                dialog.close();
            }
            done.countDown();
        }
    }

    private static void snapshot(Node pane, String fileName) throws Exception {
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.web("#1e1e1e"));
        parameters.setTransform(Transform.scale(2, 2));
        WritableImage image = pane.snapshot(parameters, null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        check(nonBlackPixelRatio(buffered) >= 0.20,
            "Saved chats snapshot is mostly black; JavaFX did not render the dialog content");

        File output = new File("build/smoke", fileName);
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create snapshot directory: " + parent);
        }
        ImageIO.write(buffered, "png", output);
        System.out.println("Snapshot written: " + output.getAbsolutePath());
    }

    private static double nonBlackPixelRatio(BufferedImage image) {
        long visible = 0;
        long sampled = 0;
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >>> 16) & 0xff;
                int green = (rgb >>> 8) & 0xff;
                int blue = rgb & 0xff;
                if (red + green + blue > 45) {
                    visible++;
                }
                sampled++;
            }
        }
        return sampled == 0 ? 0 : (double) visible / sampled;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String stack(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
