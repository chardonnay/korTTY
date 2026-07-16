package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed regression harness for the AI Manager's primary navigation. It selects Local Models,
 * moves keyboard focus into the Hugging Face search field and verifies that the selected header
 * retains its focus-independent bottom marker. A 2x snapshot is written to
 * {@code build/smoke/ai-manager-navigation.png} for visual inspection.
 */
public final class AiManagerNavigationSmoke {

    private static final double WIDTH = 1180;
    private static final double HEIGHT = 820;

    private AiManagerNavigationSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-ai-manager-navigation-smoke");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Platform.startup(() -> run(failure, done));

        boolean finished = done.await(45, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("AiManagerNavigationSmoke TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("AiManagerNavigationSmoke FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("AiManagerNavigationSmoke OK");
        System.exit(0);
    }

    private static void run(AtomicReference<String> failure, CountDownLatch done) {
        AiManagerDialog dialog = null;
        try {
            GlobalSettings settings = new GlobalSettings();
            settings.setLanguage("en");
            LanguageManager.getInstance().initialize(settings);

            dialog = new AiManagerDialog(null);
            dialog.show();

            DialogPane dialogPane = dialog.getDialogPane();
            Stage stage = (Stage) dialogPane.getScene().getWindow();
            stage.setWidth(WIDTH);
            stage.setHeight(HEIGHT);

            Node navigationNode = dialogPane.lookup(".ai-manager-primary-navigation");
            check(navigationNode instanceof TabPane,
                "AI Manager primary navigation style class is missing");
            TabPane navigation = (TabPane) navigationNode;
            check(navigation.getTabs().size() == 6,
                "expected six primary AI Manager tabs, got " + navigation.getTabs().size());

            Tab localModels = navigation.getTabs().get(1);
            check(localModels.getStyleClass().contains("ai-manager-primary-tab"),
                "Local Models tab is missing the persistent-marker style class");
            navigation.getSelectionModel().select(localModels);

            LocalModelManagerPane modelsPane = field(dialog, "localModelManagerPane");
            TextField searchField = field(modelsPane, "hubQuery");

            dialogPane.applyCss();
            dialogPane.layout();

            searchField.requestFocus();
            AiManagerDialog finalDialog = dialog;
            PauseTransition pulse = new PauseTransition(Duration.millis(350));
            pulse.setOnFinished(event -> {
                try {
                    dialogPane.applyCss();
                    dialogPane.layout();
                    check(dialogPane.getScene().getFocusOwner() == searchField,
                        "Hugging Face search field did not become the scene focus owner; owner="
                            + dialogPane.getScene().getFocusOwner());
                    check(navigation.getSelectionModel().getSelectedItem() == localModels,
                        "Local Models selection changed after focus moved into the content");
                    check(!navigation.isFocused(),
                        "primary navigation unexpectedly retained focus during the regression check");

                    Region headerAfterFocusMove = selectedHeader(navigation);
                    check(bottomBorderWidth(headerAfterFocusMove) >= 2.0,
                        "selected tab lost its visible bottom marker after focus moved");
                    snapshot(dialogPane);
                } catch (Throwable error) {
                    failure.compareAndSet(null, stack(error));
                } finally {
                    finalDialog.close();
                    done.countDown();
                }
            });
            pulse.play();
        } catch (Throwable error) {
            if (dialog != null) {
                dialog.close();
            }
            failure.compareAndSet(null, stack(error));
            done.countDown();
        }
    }

    private static Region selectedHeader(TabPane navigation) {
        PseudoClass selectedPseudoClass = PseudoClass.getPseudoClass("selected");
        for (Node candidate : navigation.lookupAll(".ai-manager-primary-tab")) {
            if (candidate instanceof Region region
                && candidate.getPseudoClassStates().contains(selectedPseudoClass)) {
                return region;
            }
        }
        throw new IllegalStateException("selected primary tab header not found; headers="
            + navigation.lookupAll(".ai-manager-primary-tab"));
    }

    private static double bottomBorderWidth(Region region) {
        if (region.getBorder() == null || region.getBorder().getStrokes().isEmpty()) {
            return 0;
        }
        return region.getBorder().getStrokes().stream()
            .mapToDouble(stroke -> stroke.getWidths().getBottom())
            .max()
            .orElse(0);
    }

    private static void snapshot(DialogPane pane) throws Exception {
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.web("#1e1e1e"));
        parameters.setTransform(Transform.scale(2, 2));
        WritableImage image = pane.snapshot(parameters, null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        check(nonBlackPixelRatio(buffered) >= 0.20,
            "AI Manager snapshot is mostly black; JavaFX did not render the dialog content");

        File output = new File("build/smoke/ai-manager-navigation.png");
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

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
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
