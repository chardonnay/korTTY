package de.kortty.ui;

import de.kortty.core.AiCancellation;
import de.kortty.core.SnippetModularizationSupport;
import de.kortty.core.SnippetModularizationSupport.ModularizationPlan;
import de.kortty.core.SnippetModularizationSupport.ModuleFile;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns an accepted modularization plan into files: one AI request per planned file (modules
 * first, the entry point last, so it can call their real names), one repair round when the
 * result fails {@link SnippetModularizationSupport#problems}, then the multi-file preview. The
 * caller writes what the user accepts there.
 */
final class SnippetModularizationRunner {

    private static final Logger logger = LoggerFactory.getLogger(SnippetModularizationRunner.class);

    /** What the run needs: the AI, the sources and where the planned files exist already. */
    record Job(SnippetProjectAi ai, String sourceContext, String originalSource, ModularizationPlan plan,
               String language, String aiProfileId, String fallbackLanguageCode, String additionalInstructions,
               Map<String, String> existingContents, Map<String, String> existingSnippetIds) {
    }

    private SnippetModularizationRunner() {
    }

    /**
     * Generates the files with a cancellable progress window and opens the preview;
     * {@code onAccept} receives the files the user accepted.
     */
    static void run(Window owner, Job job, EditorSettingsHelper.Settings editorSettings,
                    Consumer<List<SnippetMultiFilePreview.FileChange>> onAccept) {
        List<ModuleFile> order = job.plan().modulesFirst();
        ProgressBar progress = new ProgressBar(0);
        progress.setPrefWidth(380);
        Label status = new Label(I18n.get("snippets.modularize.generating", 0, order.size(), ""));
        status.setWrapText(true);
        VBox content = new VBox(10, status, progress);
        content.setPadding(new Insets(14));
        ThemeAwareDialog<Void> dialog = new ThemeAwareDialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(I18n.get("snippets.modularize.title"));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL);

        Task<Map<String, String>> task = new Task<>() {
            @Override
            protected Map<String, String> call() throws Exception {
                Map<String, String> written = new LinkedHashMap<>();
                int index = 0;
                for (ModuleFile file : order) {
                    if (isCancelled()) {
                        return written;
                    }
                    int done = index++;
                    Platform.runLater(() -> {
                        status.setText(I18n.get("snippets.modularize.generating", done + 1, order.size(), file.path()));
                        progress.setProgress((double) done / order.size());
                    });
                    if (SnippetModularizationSupport.isPackageMarker(file)) {
                        written.put(file.path(), "");
                        continue;
                    }
                    written.put(file.path(), generate(job, file, written, null));
                }
                List<String> problems = SnippetModularizationSupport.problems(job.plan(), written);
                if (!problems.isEmpty() && !isCancelled()) {
                    logger.info("Modularization result needs a repair round: {}", problems);
                    Platform.runLater(() -> status.setText(I18n.get("snippets.modularize.repairing")));
                    for (ModuleFile file : order) {
                        boolean affected = problems.stream().anyMatch(problem -> problem.contains(file.path()))
                            || file.entryPoint();
                        if (affected && !SnippetModularizationSupport.isPackageMarker(file) && !isCancelled()) {
                            Map<String, String> others = new LinkedHashMap<>(written);
                            others.remove(file.path());
                            written.put(file.path(), generate(job, file, others, String.join("\n", problems)));
                        }
                    }
                }
                return written;
            }
        };
        dialog.setOnCloseRequest(event -> task.cancel(true));
        task.setOnSucceeded(event -> {
            dialog.close();
            if (task.isCancelled()) {
                return;
            }
            Map<String, String> written = task.getValue();
            List<String> remaining = SnippetModularizationSupport.problems(job.plan(), written);
            List<SnippetMultiFilePreview.FileChange> files = new ArrayList<>();
            for (Map.Entry<String, String> entry : SnippetModularizationSupport.inPlanOrder(job.plan(), written).entrySet()) {
                ModuleFile file = job.plan().files().stream()
                    .filter(candidate -> candidate.path().equals(entry.getKey())).findFirst().orElse(null);
                files.add(new SnippetMultiFilePreview.FileChange(entry.getKey(),
                    job.existingContents().get(entry.getKey()), entry.getValue(),
                    file != null && file.executable(), job.existingSnippetIds().get(entry.getKey())));
            }
            String summary = job.plan().rationale();
            if (!remaining.isEmpty()) {
                summary = summary + "\n\n" + I18n.get("snippets.modularize.remainingProblems",
                    String.join("\n", remaining));
            }
            SnippetMultiFilePreview.show(owner, I18n.get("snippets.modularize.previewTitle"), summary, files,
                editorSettings, onAccept);
        });
        task.setOnFailed(event -> {
            dialog.close();
            Throwable failure = task.getException();
            if (AiCancellation.isCancellation(failure)) {
                return;
            }
            logger.warn("Modularization failed", failure);
            Alert alert = new Alert(Alert.AlertType.ERROR,
                I18n.get("snippets.modularize.failed", failure != null && failure.getMessage() != null
                    ? failure.getMessage() : String.valueOf(failure)), ButtonType.OK);
            alert.initOwner(owner);
            alert.setHeaderText(null);
            alert.showAndWait();
        });
        task.setOnCancelled(event -> dialog.close());
        AiTaskRunner.start(task, "snippet-modularize");
        dialog.show();
    }

    private static String generate(Job job, ModuleFile file, Map<String, String> written, String repairHint)
            throws Exception {
        return job.ai().generateModule(job.sourceContext(), job.originalSource(), job.plan(), file,
            new LinkedHashMap<>(written), job.language(), job.aiProfileId(), job.fallbackLanguageCode(),
            job.additionalInstructions(), repairHint);
    }
}
