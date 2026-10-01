package de.kortty.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.kortty.core.SnippetModularizationSupport.ModularizationPlan;
import de.kortty.core.SnippetModularizationSupport.ModuleFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The AI side of the folder ("project") analysis and of the modularization option: the project
 * context sent to the model, the file each project finding belongs to, and the three requests —
 * {@link AiAction#ANALYZE_SNIPPET_PROJECT}, {@link AiAction#PLAN_SNIPPET_MODULARIZATION} and
 * {@link AiAction#GENERATE_SNIPPET_MODULE}. FX-free.
 *
 * <p>A project finding names its file in front of the title ({@code [lib/util.sh] Quote $1}), so
 * the stored analysis, the report and every export show it without a new record field.</p>
 */
public final class SnippetProjectAiSupport {

    private static final Logger logger = LoggerFactory.getLogger(SnippetProjectAiSupport.class);
    private static final Pattern FILE_TAG = Pattern.compile("^\\[([^\\]\\n]{1,240})\\]\\s+");
    /** Rough characters per token for the context estimate (code is denser than prose). */
    private static final double CHARS_PER_TOKEN = 3.2;

    private SnippetProjectAiSupport() {
    }

    // ---- Project context ----

    /** One file of the analysed folder. */
    public record ProjectFile(String path, String language, boolean executable, String content, String snippetId) {
        public ProjectFile {
            path = path != null ? path : "";
            language = language != null ? language : "plain";
            content = content != null ? content : "";
        }
    }

    /** The analysed folder: its files in layout order, below {@code rootLabel}. */
    public record ProjectContext(String rootLabel, List<ProjectFile> files) {
        public ProjectContext {
            rootLabel = rootLabel != null ? rootLabel : "";
            files = files == null ? List.of() : List.copyOf(files);
        }

        /** The text sent to the model: the file tree, then every file with per-file line numbers. */
        public String render() {
            StringBuilder text = new StringBuilder();
            text.append("Project folder: ").append(rootLabel.isBlank() ? "/" : rootLabel).append('\n');
            text.append("Files (path, language, executable):\n");
            for (ProjectFile file : files) {
                text.append("- ").append(file.path()).append(" (").append(file.language())
                    .append(file.executable() ? ", executable" : ", not executable").append(")\n");
            }
            for (ProjectFile file : files) {
                text.append("\n=== File: ").append(file.path()).append(" ===\n")
                    .append(lineNumbered(file.content())).append('\n');
            }
            return text.toString();
        }

        /** A rough token estimate of {@link #render()}. */
        public int estimatedTokens() {
            return estimateTokens(render());
        }

        /** The hash the stored analysis records, so a later change of any file makes it stale. */
        public String sha256() {
            return SnippetDiagramSupport.contentHash(render());
        }

        public ProjectFile file(String path) {
            return files.stream().filter(file -> file.path().equals(path)).findFirst().orElse(null);
        }

        /** The same context without the files whose paths are in {@code excluded}. */
        public ProjectContext without(java.util.Collection<String> excluded) {
            return new ProjectContext(rootLabel, files.stream().filter(file -> !excluded.contains(file.path())).toList());
        }
    }

    /** The project context of a folder layout (paths relative to the analysed folder). */
    public static ProjectContext contextOf(SnippetFolderLayout layout, String rootLabel) {
        List<ProjectFile> files = new ArrayList<>();
        for (SnippetFolderLayout.Entry entry : layout.entries()) {
            String language = SnippetLanguageSupport.detectSnippetLanguage(
                entry.snippet().getLanguage(), entry.snippet().getContent());
            files.add(new ProjectFile(entry.relativePath(), language, entry.executable(),
                SnippetManager.fileContent(entry.snippet()), entry.snippet().getId()));
        }
        return new ProjectContext(rootLabel, files);
    }

    public static int estimateTokens(String text) {
        return text == null ? 0 : (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    // ---- Findings and their files ----

    /** The file a project finding belongs to; {@code ""} for a cross-file finding or a plain one. */
    public static String fileOf(SnippetAiResponseSupport.ScriptImprovement improvement) {
        if (improvement == null) {
            return "";
        }
        Matcher matcher = FILE_TAG.matcher(improvement.title());
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /** A title without its {@code [file]} tag. */
    public static String untagged(String title) {
        return title == null ? "" : FILE_TAG.matcher(title).replaceFirst("");
    }

    /**
     * The improvements that change {@code path}: those tagged with it plus the cross-file ones
     * (which every file may need to follow), with the tag removed.
     */
    public static List<SnippetAiResponseSupport.ScriptImprovement> improvementsFor(
            List<SnippetAiResponseSupport.ScriptImprovement> selected, String path) {
        List<SnippetAiResponseSupport.ScriptImprovement> result = new ArrayList<>();
        for (SnippetAiResponseSupport.ScriptImprovement improvement : selected) {
            String file = fileOf(improvement);
            if (file.isEmpty() || file.equals(path)) {
                result.add(new SnippetAiResponseSupport.ScriptImprovement(improvement.id(), improvement.category(),
                    improvement.severity(), untagged(improvement.title()), improvement.detail(),
                    improvement.recommendation(), file.isEmpty() ? null : improvement.line()));
            }
        }
        return result;
    }

    /** The files touched by {@code selected}: tagged files, or every file when a cross-file finding is selected. */
    public static List<String> affectedFiles(ProjectContext context,
                                             List<SnippetAiResponseSupport.ScriptImprovement> selected,
                                             List<SnippetAiResponseSupport.ScriptDependency> dependencies) {
        boolean everyFile = !dependencies.isEmpty() || selected.stream().anyMatch(improvement -> {
            String file = fileOf(improvement);
            return file.isEmpty() || context.file(file) == null;
        });
        List<String> files = new ArrayList<>();
        for (ProjectFile file : context.files()) {
            if (everyFile || selected.stream().anyMatch(improvement -> file.path().equals(fileOf(improvement)))) {
                files.add(file.path());
            }
        }
        return files;
    }

    // ---- Requests ----

    /** Full code analysis of a folder as one project; findings carry their file in the title. */
    public static SnippetAiResponseSupport.ScriptAnalysis analyzeProject(
            AiService aiService, SnippetAiWorkflowSupport.UsageRecorder usageRecorder, ProjectContext context,
            String connectionDisplayName, String fallbackLanguageCode, String additionalInstructions) throws Exception {
        Objects.requireNonNull(context, "context");
        String rendered = context.render();
        AiRequest request = new AiRequest(AiAction.ANALYZE_SNIPPET_PROJECT, rendered, connectionDisplayName,
            fallbackLanguageCode, additionalInstructions,
            "Natural language for the analysis: " + fallbackLanguageCode + "\n" + rendered);
        String answer = execute(aiService, usageRecorder, request);
        SnippetAiResponseSupport.ScriptAnalysis analysis = SnippetAiResponseSupport.parseScriptAnalysis(answer);
        if (!analysis.isUsable()) {
            AiAnswerArchive.save(AiAction.ANALYZE_SNIPPET_PROJECT, "no-usable-analysis", answer);
            throw new IllegalStateException("AI project analysis returned no usable analysis.");
        }
        return tagFiles(analysis, filesById(answer), context);
    }

    /** The {@code file} of each improvement in the raw answer, by id. */
    static Map<String, String> filesById(String answer) {
        Map<String, String> files = new HashMap<>();
        JsonObject object = SnippetAiResponseSupport.parseLenientJsonObject(answer);
        JsonElement improvements = object != null ? object.get("improvements") : null;
        if (improvements == null || !improvements.isJsonArray()) {
            return files;
        }
        for (JsonElement element : improvements.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject improvement = element.getAsJsonObject();
            JsonElement id = improvement.get("id");
            JsonElement file = improvement.has("file") ? improvement.get("file") : improvement.get("path");
            if (id != null && id.isJsonPrimitive() && file != null && file.isJsonPrimitive()) {
                files.putIfAbsent(id.getAsString().trim(), file.getAsString().trim());
            }
        }
        return files;
    }

    /** Puts each improvement's file in front of its title; unknown paths count as cross-file. */
    static SnippetAiResponseSupport.ScriptAnalysis tagFiles(SnippetAiResponseSupport.ScriptAnalysis analysis,
                                                            Map<String, String> filesById, ProjectContext context) {
        List<SnippetAiResponseSupport.ScriptImprovement> tagged = new ArrayList<>();
        for (SnippetAiResponseSupport.ScriptImprovement improvement : analysis.improvements()) {
            String file = normalizeKnownPath(filesById.get(improvement.id()), context);
            String title = untagged(improvement.title());
            tagged.add(new SnippetAiResponseSupport.ScriptImprovement(improvement.id(), improvement.category(),
                improvement.severity(), file.isEmpty() ? title : "[" + file + "] " + title, improvement.detail(),
                improvement.recommendation(), improvement.line()));
        }
        return new SnippetAiResponseSupport.ScriptAnalysis(analysis.summary(), analysis.dependencies(), tagged);
    }

    private static String normalizeKnownPath(String raw, ProjectContext context) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String path = raw.trim().replace('\\', '/');
        while (path.startsWith("./") || path.startsWith("/")) {
            path = path.substring(path.startsWith("./") ? 2 : 1);
        }
        if (context.file(path) != null) {
            return path;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (ProjectFile file : context.files()) {
            if (file.path().toLowerCase(Locale.ROOT).equals(lower)
                || file.path().toLowerCase(Locale.ROOT).endsWith("/" + lower)) {
                return file.path();
            }
        }
        return "";
    }

    /**
     * Asks whether the code should be split into modules. {@code sourceContext} is either one
     * script (with its file name) or a rendered project.
     */
    public static ModularizationPlan planModularization(
            AiService aiService, SnippetAiWorkflowSupport.UsageRecorder usageRecorder, String sourceContext,
            String language, String connectionDisplayName, String fallbackLanguageCode,
            String additionalInstructions) throws Exception {
        AiRequest request = new AiRequest(AiAction.PLAN_SNIPPET_MODULARIZATION, sourceContext, connectionDisplayName,
            fallbackLanguageCode, additionalInstructions,
            "Language: " + language + "\nNatural language for the rationale: " + fallbackLanguageCode + "\n"
                + sourceContext);
        String answer = execute(aiService, usageRecorder, request);
        ModularizationPlan plan = SnippetModularizationSupport.parsePlan(answer,
            SnippetLanguageSupport.defaultFileExtension(language));
        if (!plan.isUsable()) {
            AiAnswerArchive.save(AiAction.PLAN_SNIPPET_MODULARIZATION, "no-usable-plan", answer);
            throw new IllegalStateException("The AI returned no usable modularization plan.");
        }
        return plan;
    }

    /** The context of a single script for {@link #planModularization} and {@link #generateModule}. */
    public static String scriptContext(String fileName, String content) {
        return "Script " + (fileName != null && !fileName.isBlank() ? fileName : "script") + ":\n"
            + lineNumbered(content);
    }

    /**
     * Writes one planned file. {@code written} holds the files generated so far (modules before the
     * entry point), so the entry point can call their real names.
     */
    public static String generateModule(
            AiService aiService, SnippetAiWorkflowSupport.UsageRecorder usageRecorder, String sourceContext,
            String originalSource, ModularizationPlan plan, ModuleFile target, Map<String, String> written,
            String language, String connectionDisplayName, String fallbackLanguageCode,
            String additionalInstructions, String repairHint) throws Exception {
        StringBuilder context = new StringBuilder();
        context.append("Language: ").append(language).append('\n')
            .append("Accepted plan:\n").append(SnippetModularizationSupport.describe(plan))
            .append("\nWrite the file: ").append(target.path())
            .append(target.entryPoint() ? " (the entry point)" : "").append('\n');
        if (!target.symbols().isEmpty()) {
            context.append("It defines: ").append(String.join(", ", target.symbols())).append('\n');
        }
        if (written != null && !written.isEmpty()) {
            context.append("\nFiles already written (use their names exactly):\n");
            for (Map.Entry<String, String> file : written.entrySet()) {
                context.append("=== ").append(file.getKey()).append(" ===\n")
                    .append(AiPromptBuilder.toSafeTextCodeBlock(file.getValue())).append('\n');
            }
        }
        if (repairHint != null && !repairHint.isBlank()) {
            context.append("\nYour previous attempt had these problems; fix them:\n").append(repairHint).append('\n');
        }
        context.append("\nOriginal code:\n").append(sourceContext);
        AiRequest request = new AiRequest(AiAction.GENERATE_SNIPPET_MODULE, originalSource, connectionDisplayName,
            fallbackLanguageCode, additionalInstructions, context.toString());
        String answer = execute(aiService, usageRecorder, request);
        String content = SnippetModularizationSupport.parseGeneratedFile(answer);
        if (content == null) {
            AiAnswerArchive.save(AiAction.GENERATE_SNIPPET_MODULE, "no-usable-file", answer);
            throw new IllegalStateException("The AI returned no content for " + target.path() + ".");
        }
        return content.endsWith("\n") ? content : content + "\n";
    }

    private static String execute(AiService aiService, SnippetAiWorkflowSupport.UsageRecorder usageRecorder,
                                  AiRequest request) throws Exception {
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.record(request, result);
        }
        String answer = result != null && result.content() != null ? result.content() : "";
        if (answer.isBlank() && result != null && result.reasoning() != null) {
            // A thinking model sometimes leaves the JSON in its reasoning channel.
            logger.debug("{} answer was empty; reading the reasoning channel", request.action());
            answer = result.reasoning();
        }
        return answer;
    }

    static String lineNumbered(String text) {
        String value = text != null ? text : "";
        String[] lines = value.split("\\R", -1);
        int width = String.valueOf(Math.max(1, lines.length)).length();
        StringBuilder builder = new StringBuilder("```text\n");
        for (int i = 0; i < lines.length; i++) {
            builder.append(String.format(Locale.ROOT, "%" + width + "d | %s", i + 1, lines[i])).append('\n');
        }
        return builder.append("```").toString();
    }
}
