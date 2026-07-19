package de.kortty.ui;

import de.kortty.ai.huggingface.HuggingFaceDownloadProgress;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed regression harness for the fixed local-model download status area. It feeds synthetic
 * progress to the real pane and verifies that model, file, byte, timing and transfer information
 * remains visible in a final bottom panel together with pause/resume and cancel controls.
 */
public final class LocalModelDownloadStatusSmoke {

    private LocalModelDownloadStatusSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-local-model-download-smoke");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> run(failure, done));

        boolean finished = done.await(30, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("LocalModelDownloadStatusSmoke TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            failure.get().printStackTrace(System.err);
            System.exit(1);
        }
        System.out.println("LocalModelDownloadStatusSmoke OK");
        System.exit(0);
    }

    private static void run(AtomicReference<Throwable> failure, CountDownLatch done) {
        LocalModelManagerPane pane = null;
        try {
            GlobalSettings settings = new GlobalSettings();
            settings.setLanguage("en");
            LanguageManager.getInstance().initialize(settings);

            pane = new LocalModelManagerPane(null, null, () -> { });
            VBox statusPanel = field(pane, "downloadStatusPanel");
            Label modelDetails = field(pane, "downloadModelDetails");
            Label fileDetails = field(pane, "downloadFileDetails");
            Label amountDetails = field(pane, "downloadAmountDetails");
            Label timingDetails = field(pane, "downloadTimingDetails");
            ProgressBar progressBar = field(pane, "downloadProgress");
            Button pause = field(pane, "pauseDownload");
            Button cancel = field(pane, "cancelDownload");

            check(pane.getChildren().get(pane.getChildren().size() - 1) == statusPanel,
                "download status panel is not the final bottom section of Local Models");
            check(!statusPanel.isVisible() && !statusPanel.isManaged(),
                "idle download status panel should not reserve layout space");

            pane.prepareDownloadStatus(
                "owner/model", "Q4_K_M", 1024L * 1024L * 1024L);
            pane.showDownloadProgress(new HuggingFaceDownloadProgress(
                HuggingFaceDownloadProgress.Phase.DOWNLOADING,
                "model-00002-of-00003.gguf",
                2,
                3,
                384L * 1024L * 1024L,
                1024L * 1024L * 1024L,
                12L * 1024L * 1024L,
                Duration.ofMinutes(1).plusSeconds(23),
                Duration.ofSeconds(53)));

            check(statusPanel.isVisible() && statusPanel.isManaged(),
                "active download status panel is not visible and managed");
            check(contains(modelDetails, "owner/model") && contains(modelDetails, "Q4_K_M"),
                "model/repository and quantization details are missing");
            check(contains(fileDetails, "model-00002-of-00003.gguf"),
                "current GGUF file is missing");
            check(contains(fileDetails, "2") && contains(fileDetails, "3"),
                "multipart file position is missing");
            check(contains(amountDetails, "384.0 MiB") && contains(amountDetails, "1.0 GiB"),
                "downloaded and total byte values are missing");
            check(contains(timingDetails, "01:23"), "elapsed time is missing");
            check(contains(timingDetails, "12.0 MiB/s"), "transfer speed is missing");
            check(contains(timingDetails, "00:53"), "estimated remaining time is missing");
            check(progressBar.getProgress() > 0.37 && progressBar.getProgress() < 0.38,
                "progress bar does not reflect downloaded bytes");
            check(statusPanel.getChildren().stream().anyMatch(node -> containsDescendant(node, pause)),
                "pause/resume control is outside the fixed status panel");
            check(statusPanel.getChildren().stream().anyMatch(node -> containsDescendant(node, cancel)),
                "cancel control is outside the fixed status panel");
            check(pause.getText().toLowerCase(Locale.ROOT).contains("pause"),
                "active download does not offer Pause");
            check(cancel.getText().toLowerCase(Locale.ROOT).contains("cancel"),
                "active download does not offer Cancel");

            pane.showDownloadProgress(new HuggingFaceDownloadProgress(
                HuggingFaceDownloadProgress.Phase.PAUSED,
                "model-00002-of-00003.gguf",
                2,
                3,
                384L * 1024L * 1024L,
                1024L * 1024L * 1024L,
                0,
                Duration.ofMinutes(2),
                null));
            check(pause.getText().toLowerCase(Locale.ROOT).contains("resume"),
                "paused download does not offer Resume");
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
        } finally {
            if (pane != null) {
                pane.close();
            }
            done.countDown();
        }
    }

    private static boolean contains(Label label, String fragment) {
        return label.getText() != null && label.getText().contains(fragment);
    }

    private static boolean containsDescendant(Node root, Node expected) {
        if (root == expected) {
            return true;
        }
        if (root instanceof javafx.scene.Parent parent) {
            return parent.getChildrenUnmodifiable().stream()
                .anyMatch(child -> containsDescendant(child, expected));
        }
        return false;
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
}
