package de.kortty.ui;

import de.kortty.ai.huggingface.HuggingFaceDownloadProgress;
import de.kortty.ai.huggingface.HuggingFaceModel;
import de.kortty.ai.huggingface.HuggingFaceModelFile;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
            check(dialog.getModality() == Modality.NONE,
                "AI Manager must be modeless so the main window remains usable");
            dialog.show();

            DialogPane dialogPane = dialog.getDialogPane();
            Stage stage = (Stage) dialogPane.getScene().getWindow();
            stage.setWidth(WIDTH);
            stage.setHeight(HEIGHT);

            Node navigationNode = dialogPane.lookup(".ai-manager-primary-navigation");
            check(navigationNode instanceof TabPane,
                "AI Manager primary navigation style class is missing");
            TabPane navigation = (TabPane) navigationNode;
            check(navigation.getTabs().size() == 5,
                "expected five primary AI Manager tabs, got " + navigation.getTabs().size());

            Tab localModels = navigation.getTabs().get(1);
            check(localModels.getStyleClass().contains("ai-manager-primary-tab"),
                "Local Models tab is missing the persistent-marker style class");
            navigation.getSelectionModel().select(localModels);

            LocalModelManagerPane modelsPane = field(dialog, "localModelManagerPane");
            TextField searchField = field(modelsPane, "hubQuery");
            configureDownloadDemo(modelsPane);

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
                    snapshot(dialogPane, "ai-manager-navigation.png");
                    snapshot(modelsPane, "local-model-download-status.png");
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

    private static void configureDownloadDemo(LocalModelManagerPane pane) throws Exception {
        long gib = 1024L * 1024L * 1024L;
        String revision = "0123456789abcdef0123456789abcdef01234567";
        HuggingFaceModelFile selectedFile = new HuggingFaceModelFile(
            "Qwen3-4B-Q4_K_M.gguf",
            5L * gib / 2L,
            "0".repeat(64),
            URI.create("https://huggingface.co/bartowski/Qwen_Qwen3-4B-GGUF/resolve/" + revision
                + "/Qwen3-4B-Q4_K_M.gguf"),
            "Q4_K_M",
            1,
            1);
        HuggingFaceModel demoModel = new HuggingFaceModel(
            "bartowski/Qwen_Qwen3-4B-GGUF",
            "bartowski",
            revision,
            "apache-2.0",
            "qwen3",
            40_960,
            selectedFile.size(),
            Set.of("Q4_K_M"),
            List.of(selectedFile),
            Set.of("gguf", "text-generation"),
            false,
            false,
            1_250_000,
            4_200,
            Instant.parse("2026-07-01T12:00:00Z"),
            // Published well before it was last touched, so the snapshot shows a realistic age.
            Instant.parse("2026-05-28T12:00:00Z"));
        ObservableList<HuggingFaceModel> models = field(pane, "hubModels");
        models.setAll(demoModel);
        ComboBox<String> quantization = field(pane, "quantization");
        quantization.getItems().setAll("Q4_K_M");
        quantization.setValue("Q4_K_M");
        TextField query = field(pane, "hubQuery");
        query.setText("qwen3");

        pane.prepareDownloadStatus(demoModel.id(), "Q4_K_M", selectedFile.size());
        pane.showDownloadProgress(new HuggingFaceDownloadProgress(
            HuggingFaceDownloadProgress.Phase.DOWNLOADING,
            selectedFile.path(),
            1,
            1,
            gib + 256L * 1024L * 1024L,
            selectedFile.size(),
            18L * 1024L * 1024L,
            java.time.Duration.ofMinutes(1).plusSeconds(47),
            java.time.Duration.ofMinutes(1).plusSeconds(7)));
    }

    private static void snapshot(Node pane, String fileName) throws Exception {
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.web("#1e1e1e"));
        parameters.setTransform(Transform.scale(2, 2));
        WritableImage image = pane.snapshot(parameters, null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        check(nonBlackPixelRatio(buffered) >= 0.20,
            "AI Manager snapshot is mostly black; JavaFX did not render the dialog content");

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
