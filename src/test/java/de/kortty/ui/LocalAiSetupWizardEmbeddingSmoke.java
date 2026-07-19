package de.kortty.ui;

import de.kortty.ai.huggingface.HuggingFaceModelCatalog;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Headed smoke harness for the local-AI setup wizard's role page. Opens the real wizard with a
 * 32-GiB memory tier and the embedding-focused flow (as used by the knowledge-store setup),
 * navigates to the role page and verifies the RAG-embedding combo now offers the full catalog of
 * embedding models — small default first — instead of a single fixed suggestion. A 2x snapshot is
 * written to {@code build/smoke/local-ai-wizard-embeddings.png}.
 */
public final class LocalAiSetupWizardEmbeddingSmoke {

    private static final long GIB = 1024L * 1024L * 1024L;

    private LocalAiSetupWizardEmbeddingSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-wizard-embedding-smoke");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Platform.startup(() -> run(failure, done));

        boolean finished = done.await(45, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("LocalAiSetupWizardEmbeddingSmoke TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("LocalAiSetupWizardEmbeddingSmoke FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("LocalAiSetupWizardEmbeddingSmoke OK");
        System.exit(0);
    }

    private static void run(AtomicReference<String> failure, CountDownLatch done) {
        LocalAiSetupWizardDialog dialog = null;
        try {
            GlobalSettings settings = new GlobalSettings();
            settings.setLanguage("en");
            LanguageManager.getInstance().initialize(settings);

            dialog = new LocalAiSetupWizardDialog(
                null, 32 * GIB, inertWorkflow(), HuggingFaceModelCatalog.Role.EMBEDDING);
            dialog.show();

            DialogPane dialogPane = dialog.getDialogPane();
            Stage stage = (Stage) dialogPane.getScene().getWindow();
            stage.setWidth(760);
            stage.setHeight(520);

            // Button order is [back, next, finish, cancel]; click "next" twice to reach ROLES.
            Button next = (Button) dialogPane.lookupButton(dialogPane.getButtonTypes().get(1));
            next.fire();
            next.fire();

            dialogPane.applyCss();
            dialogPane.layout();

            List<ComboBox<?>> combos = new ArrayList<>();
            for (Node node : dialogPane.lookupAll(".combo-box")) {
                if (node instanceof ComboBox<?> combo) {
                    combos.add(combo);
                }
            }
            check(combos.size() == 3, "expected the three role combos, got " + combos.size());

            ComboBox<?> embedding = combos.stream()
                .filter(combo -> comboIds(combo).stream().anyMatch(id -> id.contains("Embedding")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("embedding combo not found"));
            List<String> offered = comboIds(embedding);
            check(offered.size() == 5, "expected five embedding choices, got " + offered);
            check(offered.get(0).contains("Qwen3-Embedding-0.6B"),
                "small Qwen3 embedding model must stay the first/default choice: " + offered);
            check(offered.stream().anyMatch(id -> id.contains("Qwen3-Embedding-4B")),
                "Qwen3-Embedding-4B missing: " + offered);
            check(offered.stream().anyMatch(id -> id.contains("Qwen3-Embedding-8B")),
                "Qwen3-Embedding-8B missing: " + offered);
            check(offered.stream().anyMatch(id -> id.contains("bge-m3")),
                "bge-m3 missing: " + offered);
            check(offered.stream().anyMatch(id -> id.contains("nomic-embed-text")),
                "nomic-embed-text missing: " + offered);
            Object selected = embedding.getValue();
            check(selected != null && selected.toString().contains("qwen3-embedding-0.6b")
                    || selectedModelId(embedding).contains("Qwen3-Embedding-0.6B"),
                "default embedding selection must be the small Qwen3 model, got " + embedding.getValue());

            snapshot(dialogPane, "local-ai-wizard-embeddings.png");
        } catch (Throwable error) {
            failure.compareAndSet(null, stack(error));
        } finally {
            if (dialog != null) {
                dialog.close();
            }
            done.countDown();
        }
    }

    private static List<String> comboIds(ComboBox<?> combo) {
        List<String> ids = new ArrayList<>();
        for (Object item : combo.getItems()) {
            if (item instanceof HuggingFaceModelCatalog.Recommendation recommendation) {
                ids.add(recommendation.modelId());
            }
        }
        return ids;
    }

    private static String selectedModelId(ComboBox<?> combo) {
        return combo.getValue() instanceof HuggingFaceModelCatalog.Recommendation recommendation
            ? recommendation.modelId()
            : "";
    }

    private static LocalAiSetupWorkflow inertWorkflow() {
        return new LocalAiSetupWorkflow() {
            @Override
            public CompletableFuture<List<ModelDetails>> inspect(
                Map<HuggingFaceModelCatalog.Role, HuggingFaceModelCatalog.Recommendation> selections
            ) {
                return new CompletableFuture<>(); // never completes; the smoke stays on ROLES
            }

            @Override
            public Installation install(
                Map<HuggingFaceModelCatalog.Role, HuggingFaceModelCatalog.Recommendation> selections,
                List<ModelDetails> details,
                Consumer<Progress> progressListener
            ) {
                throw new UnsupportedOperationException("The smoke never installs.");
            }
        };
    }

    private static void snapshot(Node pane, String fileName) throws Exception {
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.web("#1e1e1e"));
        parameters.setTransform(Transform.scale(2, 2));
        WritableImage image = pane.snapshot(parameters, null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        check(nonBlackPixelRatio(buffered) >= 0.20,
            "Wizard snapshot is mostly black; JavaFX did not render the dialog content");

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
