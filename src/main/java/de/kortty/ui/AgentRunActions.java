package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.core.TerminalAgentActivityExportService;
import de.kortty.core.WorkflowScriptSupport;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import de.kortty.model.TerminalAgentModels;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * What can be done with a KI-Agent run and its steps, whichever design shows it: turn a finished run into
 * a reusable workflow script, export runs to a file, open a step in the Snippet Editor, and the texts a
 * step offers to copy. The classic activity panel and the designs of Plugins → AI Agent Design share it.
 * FX thread.
 */
final class AgentRunActions {

    private static final Logger logger = LoggerFactory.getLogger(AgentRunActions.class);
    private static final TerminalAgentActivityExportService EXPORT_SERVICE = new TerminalAgentActivityExportService();

    /**
     * A run as these actions need it.
     *
     * @param title          the run's title, usually its question
     * @param prompt         the user's question
     * @param metadata       the profile, model, users and connection the run was started with; may be {@code null}
     * @param startedAt      when the run started
     * @param finishedAt     when it ended, {@code null} while it works
     * @param elapsedSeconds how long it worked
     * @param reportedTokens the tokens its steps reported, {@code -1} when none did
     * @param activities     its steps in order
     */
    record RunRecord(
        String title,
        String prompt,
        @Nullable AiAgentActivityPanel.RunMetadata metadata,
        LocalDateTime startedAt,
        @Nullable LocalDateTime finishedAt,
        long elapsedSeconds,
        long reportedTokens,
        List<TerminalAgentModels.AgentActivity> activities) {

        RunRecord {
            prompt = prompt != null ? prompt.strip() : "";
            title = title != null && !title.isBlank() ? title.strip() : prompt;
            startedAt = startedAt != null ? startedAt : LocalDateTime.now();
            activities = activities != null ? List.copyOf(activities) : List.of();
        }

        private String meta(java.util.function.Function<AiAgentActivityPanel.RunMetadata, String> field) {
            String value = metadata != null ? field.apply(metadata) : null;
            return value != null && !value.isBlank() ? value.strip() : null;
        }
    }

    private AgentRunActions() {
    }

    // ------------------------------------------------------------------ workflow

    /** Whether the run did something a script could repeat: a command (or a failed one). */
    static boolean hasReproducibleWork(RunRecord run) {
        if (run == null) {
            return false;
        }
        for (TerminalAgentModels.AgentActivity activity : run.activities()) {
            if (activity.type() == TerminalAgentModels.AgentActivityType.ACTION
                || activity.type() == TerminalAgentModels.AgentActivityType.ERROR) {
                return true;
            }
        }
        return false;
    }

    /** Opens the Workflow dialog, which turns the finished run into a reusable script. */
    static void openWorkflow(@Nullable Window owner, RunRecord run) {
        if (run == null || !hasReproducibleWork(run)) {
            showMessage(owner, Alert.AlertType.INFORMATION, I18n.get("ai.workflow.title"), I18n.get("ai.workflow.error.noWork"));
            return;
        }
        KorTTYApplication application = KorTTYApplication.getInstance();
        if (application == null) {
            showMessage(owner, Alert.AlertType.ERROR, I18n.get("ai.workflow.title"), I18n.get("ai.workflow.error.noProfile"));
            return;
        }
        WorkflowScriptGenerator.RunExportData runData = new WorkflowScriptGenerator.RunExportData(
            run.meta(AiAgentActivityPanel.RunMetadata::profileId), run.meta(AiAgentActivityPanel.RunMetadata::profileName),
            run.prompt(), toExportRun(run), detectedOperatingSystem(run.activities()));
        String creator = run.meta(AiAgentActivityPanel.RunMetadata::creatorUser);
        WorkflowScriptSupport.HeaderFacts baseFacts = new WorkflowScriptSupport.HeaderFacts(
            WorkflowScriptSupport.defaultScriptName(run.prompt(), WorkflowScriptSupport.ScriptLanguage.BASH),
            creator != null ? creator : System.getProperty("user.name"),
            run.meta(AiAgentActivityPanel.RunMetadata::sshUser),
            run.meta(AiAgentActivityPanel.RunMetadata::connectionName),
            LocalDateTime.now(),
            run.prompt(),
            run.meta(AiAgentActivityPanel.RunMetadata::profileName));
        new WorkflowScriptDialog(owner, new WorkflowScriptGenerator(application), runData, baseFacts).showAndWait();
    }

    /**
     * The server's OS from the run's session probe, whose detail or summary reads
     * {@code {osRelease} | user {user} | cwd {dir} | {sudo}}; {@code null} when the run has none.
     */
    static @Nullable String detectedOperatingSystem(List<TerminalAgentModels.AgentActivity> activities) {
        if (activities == null) {
            return null;
        }
        for (TerminalAgentModels.AgentActivity activity : activities) {
            String os = extractProbeOs(activity.detail());
            if (os == null) {
                os = extractProbeOs(activity.summary());
            }
            if (os != null) {
                return os;
            }
        }
        return null;
    }

    private static @Nullable String extractProbeOs(String text) {
        // Only a probe summary carries these fields.
        if (text == null || !text.contains(" | user ") || !text.contains(" | cwd ")) {
            return null;
        }
        int bar = text.indexOf(" | ");
        String os = (bar > 0 ? text.substring(0, bar) : text).strip();
        return os.isEmpty() ? null : os;
    }

    // ------------------------------------------------------------------ export

    static TerminalAgentActivityExportService.Run toExportRun(RunRecord run) {
        List<TerminalAgentActivityExportService.Activity> activities = run.activities().stream()
            .map(activity -> new TerminalAgentActivityExportService.Activity(activity.id(), activity.type(),
                activity.status(), activity.title(), activity.summary(), activity.detail(), activity.tokenUsage(),
                activity.elapsedSeconds()))
            .toList();
        return new TerminalAgentActivityExportService.Run(
            run.title(),
            run.prompt(),
            run.meta(AiAgentActivityPanel.RunMetadata::profileId),
            run.meta(AiAgentActivityPanel.RunMetadata::profileName),
            run.meta(AiAgentActivityPanel.RunMetadata::modelName),
            run.meta(AiAgentActivityPanel.RunMetadata::reasoningStatus),
            run.startedAt(),
            run.finishedAt(),
            run.elapsedSeconds(),
            run.reportedTokens() >= 0,
            Math.max(0L, run.reportedTokens()),
            activities);
    }

    static TerminalAgentActivityExportService.ExportDocument exportDocument(List<RunRecord> runs) {
        List<TerminalAgentActivityExportService.Run> exported = runs.stream().map(AgentRunActions::toExportRun).toList();
        String title = exported.size() == 1 ? exported.getFirst().title() : I18n.get("ai.agent.export.documentTitle");
        return new TerminalAgentActivityExportService.ExportDocument(title, LocalDateTime.now(), exported);
    }

    static String exportFormatLabelKey(TerminalAgentActivityExportService.Format format) {
        return switch (format) {
            case MARKDOWN -> "ai.agent.export.format.markdown";
            case TEXT -> "ai.agent.export.format.text";
            case YAML -> "ai.agent.export.format.yaml";
            case XML -> "ai.agent.export.format.xml";
            case JSON -> "ai.agent.export.format.json";
            case PDF -> "ai.agent.export.format.pdf";
            case ASCIIDOCTOR -> "ai.agent.export.format.asciidoctor";
        };
    }

    /** Asks where to save an export in {@code format}; {@code null} when the user cancelled. */
    static @Nullable File chooseExportTarget(@Nullable Window owner, TerminalAgentActivityExportService.Format format,
                                             String filePrefix) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("ai.agent.export.title"));
        String safePrefix = filePrefix != null && !filePrefix.isBlank() ? filePrefix : "terminal-agent";
        chooser.setInitialFileName(AiAgentActivityPanel.exportFileStem(safePrefix, LocalDateTime.now()));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.get(format.filterKey()), "*" + format.extension()));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.get("connEdit.allFiles"), "*.*"));
        return AiAgentActivityPanel.normalizeExportTargetFile(chooser.showSaveDialog(owner), format);
    }

    /**
     * Exports {@code runs} to a file the user picks, in the background. {@code status} hears
     * "exporting …" and "exported …"; a failure is shown in a message.
     */
    static void export(@Nullable Window owner, List<RunRecord> runs, TerminalAgentActivityExportService.Format format,
                       String filePrefix, Consumer<String> status) {
        if (runs == null || runs.isEmpty()) {
            return;
        }
        File target = chooseExportTarget(owner, format, filePrefix);
        if (target == null) {
            return;
        }
        TerminalAgentActivityExportService.ExportDocument document = exportDocument(runs);
        status.accept(I18n.get("ai.agent.export.running", target.getName()));
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                EXPORT_SERVICE.export(target.toPath(), format, document);
                return null;
            }
        };
        task.setOnSucceeded(event -> status.accept(I18n.get("ai.agent.export.success", target.getName())));
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            logger.warn("Failed to export terminal AI agent activity", failure);
            status.accept("");
            showMessage(owner, Alert.AlertType.ERROR, I18n.get("ai.agent.export.failed"),
                failure != null && failure.getMessage() != null ? failure.getMessage() : I18n.get("ai.agent.export.failed"));
        });
        Thread thread = new Thread(task, "ai-agent-export");
        thread.setDaemon(true);
        thread.start();
    }

    // ------------------------------------------------------------------ steps

    /** What "copy" of a step copies: its detail, else its summary, else its title. */
    static String copyText(TerminalAgentModels.AgentActivity step) {
        return AiAgentActivityPanel.copyTextForActivity(step);
    }

    /** The command of a command step, or empty for any other step. */
    static String commandText(TerminalAgentModels.AgentActivity step) {
        return AgentStepDetails.isCommand(step) ? AgentStepDetails.command(step) : "";
    }

    /** The output lines of a finished command step, joined; empty when it printed nothing. */
    static String outputText(TerminalAgentModels.AgentActivity step) {
        return String.join("\n", AgentStepDetails.output(step));
    }

    /** Opens the Snippet Editor with {@code text} as a new snippet named after the step. */
    static void openSnippetEditor(@Nullable Window owner, String text, @Nullable TerminalAgentModels.AgentActivity step) {
        String content = AiAgentActivityPanel.stripActivityQuotePrefixes(text);
        if (content.isBlank()) {
            return;
        }
        Snippet snippet = new Snippet();
        snippet.setName(snippetName(step, content));
        snippet.setContent(content);
        snippet.setLanguage(SnippetLanguageSupport.detectSnippetLanguage("", content));
        snippet.setDescription(step != null && step.summary() != null ? step.summary() : "");
        openSnippetEditorWithDraft(owner, snippet);
    }

    /** Opens the Snippet Editor with a ready draft and stores it when saved. */
    static void openSnippetEditorWithDraft(@Nullable Window owner, Snippet snippet) {
        if (snippet == null || snippet.getContent() == null || snippet.getContent().isBlank()) {
            return;
        }
        KorTTYApplication application = KorTTYApplication.getInstance();
        var snippetManager = application != null ? application.getSnippetManager() : null;
        if (snippetManager == null) {
            showMessage(owner, Alert.AlertType.ERROR, I18n.get("ai.result.saveSnippet.failed"), "Snippet Manager not initialized");
            return;
        }
        List<String> categoryNames = snippetManager.getAllCategories().stream()
            .map(SnippetCategory::getName)
            .filter(Objects::nonNull)
            .sorted(String::compareToIgnoreCase)
            .toList();
        SnippetEditDialog dialog = new SnippetEditDialog(snippet, categoryNames);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.showNonBlocking(savedSnippet -> {
            try {
                String category = savedSnippet.getCategory() != null ? savedSnippet.getCategory().trim() : "";
                if (!category.isBlank() && snippetManager.findCategoryByName(category).isEmpty()) {
                    snippetManager.addCategory(new SnippetCategory(category));
                }
                // Add-or-update: a live save already stored this snippet; saving again updates it.
                snippetManager.addOrUpdateSnippet(savedSnippet);
                snippetManager.save();
            } catch (Exception e) {
                logger.warn("Could not save terminal agent activity as snippet", e);
                showMessage(owner, Alert.AlertType.ERROR, I18n.get("ai.result.saveSnippet.failed"),
                    I18n.get("ai.agent.activity.openSnippetEditor.failed", e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        });
    }

    /** A snippet name from the step's title or summary, else the content's first line; at most 80 characters. */
    static String snippetName(@Nullable TerminalAgentModels.AgentActivity step, String content) {
        String seed = step != null && step.title() != null && !step.title().isBlank() ? step.title()
            : step != null && step.summary() != null && !step.summary().isBlank() ? step.summary()
            : content == null ? "" : content.lines().map(String::trim).filter(line -> !line.isBlank()).findFirst().orElse("");
        String normalized = seed.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            normalized = I18n.get("ai.agent.title");
        }
        return normalized.length() > 80 ? normalized.substring(0, 80).trim() : normalized;
    }

    static void showMessage(@Nullable Window owner, Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.showAndWait();
    }
}
