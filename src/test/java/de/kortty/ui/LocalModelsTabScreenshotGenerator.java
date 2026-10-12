package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.ai.huggingface.HuggingFaceDownloadProgress;
import de.kortty.ai.huggingface.HuggingFaceModel;
import de.kortty.ai.huggingface.HuggingFaceModelFile;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 * Offline generator for the manual's AI Manager &rarr; Local Models screenshot
 * ({@code app-docs/screenshots/ai/local-models.png}, which the guide also uses as the AI
 * Manager's representative picture).
 *
 * <p>Builds the REAL {@link AiManagerDialog} against an isolated {@code user.home}, selects the
 * Local Models tab and fills its Hugging Face browser with three demo repositories the way a
 * search for "qwen3" would, then shows the download panel mid-download. Nothing touches the
 * network: the rows carry exact file sizes, so the pane never asks the Hub for details, and the
 * progress is fed straight into the panel.</p>
 *
 * <p>Run via the {@code generateLocalModelsTabScreenshot} Gradle task. Exit 0 = OK.</p>
 */
public final class LocalModelsTabScreenshotGenerator {

    private static final double WIDTH = 1180;
    private static final double HEIGHT = 950;
    private static final List<String> OUTPUT_FILES = List.of(
        "app-docs/screenshots/ai/local-models.png");

    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;
    /** A fixed "now" for the Age column, so the picture does not drift between regenerations. */
    private static final Instant CREATED = Instant.parse("2026-05-14T09:30:00Z");

    private LocalModelsTabScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-local-models-screenshot");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                generate(done, failure);
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
                done.countDown();
            }
        });

        boolean finished = done.await(90, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("SCREENSHOT GENERATION TIMEOUT");
            System.exit(2);
        }
        String fail = failure.get();
        if (fail != null) {
            System.err.println("SCREENSHOT GENERATION FAILURE: " + fail);
            System.exit(1);
        }
        System.exit(0);
    }

    private static void generate(CountDownLatch done, AtomicReference<String> failure) throws Exception {
        // The Local Models pane reads the application's settings (Hugging Face token) and the
        // master-password manager; a bare application object with its managers is enough.
        KorTTYApplication app = new KorTTYApplication();
        app.init();
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);

        AiManagerDialog dialog = new AiManagerDialog(null);
        DialogThemeHelper.applyTheme(dialog);
        String dynamicCss = ThemeCssSupport.getDynamicStylesheetUrl(
            ThemeCssSupport.resolveThemeColors(settings, null));
        if (dynamicCss != null) {
            dialog.getDialogPane().getStylesheets().add(dynamicCss);
        }
        dialog.show();
        javafx.stage.Stage stage = (javafx.stage.Stage) dialog.getDialogPane().getScene().getWindow();
        stage.setX(-4000);
        stage.setY(-4000); // off-screen: no visible window flash during generation

        selectLocalModelsTab(dialog);
        LocalModelManagerPane pane = (LocalModelManagerPane) rawField(dialog, "localModelManagerPane");
        if (pane == null) {
            throw new IllegalStateException("Local Models pane was not built on tab selection");
        }
        fillDemoHubResults(pane);
        showDemoDownload(pane);

        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.setMinSize(WIDTH, HEIGHT);
        dialogPane.setPrefSize(WIDTH, HEIGHT);
        dialogPane.setMaxSize(WIDTH, HEIGHT);
        dialogPane.applyCss();
        dialogPane.resize(WIDTH, HEIGHT);
        dialogPane.layout();

        // The runtime status line and the table cells settle on later pulses.
        PauseTransition settle = new PauseTransition(Duration.millis(1200));
        settle.setOnFinished(event -> {
            try {
                // Whatever the runtime coordinator wrote meanwhile, the status line should report
                // the search, as it does right after a search completes.
                invokePrivate(pane, "updateHubResultCount");
                dialogPane.applyCss();
                dialogPane.layout();
                capture(dialogPane);
                dialog.close();
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
            } finally {
                done.countDown();
            }
        });
        settle.play();
    }

    private static void selectLocalModelsTab(AiManagerDialog dialog) {
        TabPane tabPane = (TabPane) dialog.getDialogPane().lookup(".ai-manager-primary-navigation");
        if (tabPane == null) {
            throw new IllegalStateException("AI Manager primary navigation not found");
        }
        String title = I18n.get("ai.local.models.tab");
        for (Tab tab : tabPane.getTabs()) {
            if (title.equals(tab.getText())) {
                tabPane.getSelectionModel().select(tab);
                LazyTabContent.ensureContent(tab);
                return;
            }
        }
        throw new IllegalStateException("Local Models tab not found in AiManagerDialog");
    }

    /**
     * Three GGUF repositories as a "qwen3" search would list them. Every quantization has an
     * exact file size: that is what the usability filter needs to show the row at all, and what
     * keeps the pane from fetching repository details from the Hub for the selected row.
     */
    @SuppressWarnings("unchecked")
    private static void fillDemoHubResults(LocalModelManagerPane pane) throws Exception {
        List<HuggingFaceModel> results = List.of(
            repository("bartowski/Qwen_Qwen3-4B-GGUF", "bartowski", "Qwen_Qwen3-4B", 40_960,
                List.of("Q4_K_M", 2_497L * MIB, "Q6_K", 3_306L * MIB, "Q8_0", 4_280L * MIB)),
            repository("unsloth/Qwen3-8B-GGUF", "unsloth", "Qwen3-8B", 40_960,
                List.of("Q4_K_M", 5_028L * MIB, "Q8_0", 8_710L * MIB)),
            repository("Qwen/Qwen3-1.7B-GGUF", "Qwen", "Qwen3-1.7B", 40_960,
                List.of("Q8_0", 2_028L * MIB)));

        ((TextField) rawField(pane, "hubQuery")).setText("qwen3");
        ObservableList<HuggingFaceModel> hubModels = (ObservableList<HuggingFaceModel>) rawField(pane, "hubModels");
        hubModels.setAll(results);
        TableView<HuggingFaceModel> hubTable = (TableView<HuggingFaceModel>) rawField(pane, "hubTable");
        hubTable.getSelectionModel().select(results.get(0));
        ((ComboBox<String>) rawField(pane, "quantization")).setValue("Q4_K_M");
    }

    private static HuggingFaceModel repository(String id, String author, String fileStem, long context,
                                               List<Object> quantizationsAndSizes) {
        List<HuggingFaceModelFile> files = new java.util.ArrayList<>();
        for (int i = 0; i < quantizationsAndSizes.size(); i += 2) {
            String quantization = (String) quantizationsAndSizes.get(i);
            long size = (Long) quantizationsAndSizes.get(i + 1);
            String path = fileStem + "-" + quantization + ".gguf";
            files.add(new HuggingFaceModelFile(path, size, null,
                URI.create("https://huggingface.co/" + id + "/resolve/main/" + path), quantization, 1, 1, null));
        }
        return new HuggingFaceModel(id, author, null, "apache-2.0", "qwen3", context, -1, null, files,
            Set.of("gguf", "text-generation"), false, false, 184_300, 212, CREATED, CREATED);
    }

    /** The download panel the way it looks halfway through fetching the selected quantization. */
    private static void showDemoDownload(LocalModelManagerPane pane) {
        long total = 2_497L * MIB;
        pane.prepareDownloadStatus("bartowski/Qwen_Qwen3-4B-GGUF", "Q4_K_M", total);
        pane.showDownloadProgress(new HuggingFaceDownloadProgress(
            HuggingFaceDownloadProgress.Phase.DOWNLOADING, "Qwen_Qwen3-4B-Q4_K_M.gguf", 1, 1,
            (long) (1.3 * GIB), total, 18 * MIB,
            java.time.Duration.ofSeconds(107), java.time.Duration.ofSeconds(67)));
    }

    private static void capture(DialogPane pane) throws Exception {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#1e1e1e"));
        params.setTransform(Transform.scale(2, 2)); // match the Retina crispness of the other shots
        WritableImage image = pane.snapshot(params, null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        for (String outputFile : OUTPUT_FILES) {
            File outFile = new File(outputFile);
            File parent = outFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IllegalStateException("Cannot create output dir: " + parent.getAbsolutePath());
            }
            ImageIO.write(buffered, "png", outFile);
            System.out.println("Generated " + outFile.getAbsolutePath()
                + " (" + buffered.getWidth() + "x" + buffered.getHeight() + ")");
        }
    }

    private static Object rawField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void invokePrivate(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
