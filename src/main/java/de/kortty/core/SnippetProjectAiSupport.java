package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.kortty.core.SnippetModularizationSupport.ModularizationPlan;
import de.kortty.core.SnippetModularizationSupport.ModuleFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    /** Where a model may put a finding's file, tried in this order. */
    private static final List<String> FILE_KEYS = List.of("file", "path", "filePath", "file_path", "filename",
        "fileName", "file_name", "location", "source");
    private static final Pattern LINE_SUFFIX = Pattern.compile("(?::\\d+)+$");
    private static final Pattern QUOTES = Pattern.compile("^[`'\"]+|[`'\"]+$");
    /** Rough characters per token for the context estimate (code is denser than prose). */
    private static final double CHARS_PER_TOKEN = 3.2;

    /** The analysis store keeps a folder's project analyses under this prefix plus the folder id. */
    public static final String FOLDER_KEY_PREFIX = "folder-";

    private SnippetProjectAiSupport() {
    }

    /** The analysis-store key of a folder's project analyses. */
    public static String folderKey(String folderId) {
        return FOLDER_KEY_PREFIX + folderId;
    }

    /** The folder id behind a {@link #folderKey}, or {@code null} for a snippet id. */
    public static String folderIdOfKey(String key) {
        return key != null && key.startsWith(FOLDER_KEY_PREFIX) && key.length() > FOLDER_KEY_PREFIX.length()
            ? key.substring(FOLDER_KEY_PREFIX.length()) : null;
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

    /**
     * A project analysis: the findings, each tagged with its file, and what each file does
     * ({@code filePurposes}, by project path; empty when the AI did not describe the files).
     */
    public record ProjectAnalysis(SnippetAiResponseSupport.ScriptAnalysis analysis, Map<String, String> filePurposes) {
        public ProjectAnalysis {
            Objects.requireNonNull(analysis, "analysis");
            filePurposes = filePurposes == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(filePurposes));
        }
    }

    /** Full code analysis of a folder as one project; findings carry their file in the title. */
    public static ProjectAnalysis analyzeProject(
            AiService aiService, SnippetAiWorkflowSupport.UsageRecorder usageRecorder, ProjectContext context,
            String connectionDisplayName, String fallbackLanguageCode, String additionalInstructions) throws Exception {
        Objects.requireNonNull(context, "context");
        String rendered = context.render();
        AiRequest request = new AiRequest(AiAction.ANALYZE_SNIPPET_PROJECT, rendered, connectionDisplayName,
            fallbackLanguageCode, additionalInstructions,
            "Natural language for the analysis: " + fallbackLanguageCode + "\n" + rendered);
        String answer = execute(aiService, usageRecorder, request);
        SnippetAiResponseSupport.ScriptAnalysis analysis = SnippetAiResponseSupport.parseScriptAnalysis(answer);
        String failure = SnippetAiResponseSupport.describeJsonFailure(answer);
        if (!analysis.isUsable()) {
            java.nio.file.Path archived =
                AiAnswerArchive.save(AiAction.ANALYZE_SNIPPET_PROJECT, "no-usable-analysis", answer);
            logger.warn("AI project analysis returned no usable analysis [answer chars={}, {}] Full answer: {}",
                answer.length(),
                failure != null ? failure : "JSON parses but holds no analysis",
                archived != null ? archived : "not archived");
            throw new IllegalStateException("AI project analysis returned no usable analysis.");
        }
        if (failure != null) {
            logger.info("AI project analysis read from an answer whose JSON was repaired: {} finding(s) kept "
                + "[answer chars={}, {}]", analysis.improvements().size(), answer.length(), failure);
        }
        SnippetAiResponseSupport.ScriptAnalysis tagged = tagFiles(analysis, improvementFiles(answer), context);
        if (!tagged.improvements().isEmpty() && context.files().size() > 1
                && tagged.improvements().stream().allMatch(improvement -> fileOf(improvement).isEmpty())) {
            // Every finding counts as cross-file and applying one rewrites every file: keep the
            // answer, so the key or path format the model used can be added above.
            java.nio.file.Path archived = AiAnswerArchive.save(AiAction.ANALYZE_SNIPPET_PROJECT, "no-finding-files", answer);
            logger.warn("AI project analysis named no known file for any of its {} finding(s). Full answer: {}",
                tagged.improvements().size(), archived != null ? archived : "not archived");
        }
        return new ProjectAnalysis(tagged, filePurposes(answer, context));
    }

    /**
     * The file each improvement of the answer names, in the order {@link SnippetAiResponseSupport#parseScriptAnalysis}
     * keeps the improvements ({@code ""} = none). By position, not by id: a model that numbers the
     * findings per file repeats ids.
     */
    static List<String> improvementFiles(String answer) {
        List<String> files = new ArrayList<>();
        for (JsonObject improvement : SnippetAiResponseSupport.scriptImprovementObjects(
                SnippetAiResponseSupport.scriptAnalysisObject(answer))) {
            files.add(rawFile(improvement));
        }
        return files;
    }

    /** The file an answer object names under any of {@link #FILE_KEYS}; several files read as none. */
    private static String rawFile(JsonObject object) {
        for (String key : FILE_KEYS) {
            JsonElement value = object.get(key);
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (value.isJsonObject()) {
                // "location": {"file": "lib/a.sh", "line": 3}
                String nested = rawFile(value.getAsJsonObject());
                if (!nested.isEmpty()) {
                    return nested;
                }
            } else if (value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                return array.size() == 1 && array.get(0).isJsonPrimitive() ? array.get(0).getAsString().trim() : "";
            } else if (value.isJsonPrimitive() && !value.getAsString().isBlank()) {
                return value.getAsString().trim();
            }
        }
        return "";
    }

    /**
     * What each file does, by project path, from the answer's {@code files} list
     * ({@code [{"path": ..., "purpose": ...}]}, or an object of path to text).
     */
    static Map<String, String> filePurposes(String answer, ProjectContext context) {
        Map<String, String> purposes = new LinkedHashMap<>();
        JsonObject object = SnippetAiResponseSupport.scriptAnalysisObject(answer);
        JsonElement files = null;
        for (String key : List.of("files", "fileSummaries", "file_summaries")) {
            if (object != null && object.has(key) && !object.get(key).isJsonNull()) {
                files = object.get(key);
                break;
            }
        }
        if (files == null) {
            return purposes;
        }
        if (files.isJsonArray()) {
            for (JsonElement element : files.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    JsonObject file = element.getAsJsonObject();
                    putPurpose(purposes, normalizeKnownPath(rawFile(file), context), purposeText(file));
                }
            }
        } else if (files.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : files.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                String text = value.isJsonPrimitive() ? value.getAsString()
                    : value.isJsonObject() ? purposeText(value.getAsJsonObject()) : "";
                putPurpose(purposes, normalizeKnownPath(entry.getKey(), context), text);
            }
        }
        return purposes;
    }

    private static String purposeText(JsonObject file) {
        for (String key : List.of("purpose", "summary", "description", "role")) {
            JsonElement value = file.get(key);
            if (value != null && value.isJsonPrimitive() && !value.getAsString().isBlank()) {
                return value.getAsString();
            }
        }
        return "";
    }

    private static void putPurpose(Map<String, String> purposes, String path, String text) {
        if (!path.isEmpty() && text != null && !text.isBlank()) {
            purposes.putIfAbsent(path, text.trim());
        }
    }

    /** Puts each improvement's file in front of its title; unknown paths count as cross-file. */
    static SnippetAiResponseSupport.ScriptAnalysis tagFiles(SnippetAiResponseSupport.ScriptAnalysis analysis,
                                                            List<String> files, ProjectContext context) {
        List<SnippetAiResponseSupport.ScriptImprovement> tagged = new ArrayList<>();
        List<SnippetAiResponseSupport.ScriptImprovement> improvements = analysis.improvements();
        for (int i = 0; i < improvements.size(); i++) {
            SnippetAiResponseSupport.ScriptImprovement improvement = improvements.get(i);
            String file = normalizeKnownPath(i < files.size() ? files.get(i) : null, context);
            String title = untagged(improvement.title());
            tagged.add(new SnippetAiResponseSupport.ScriptImprovement(improvement.id(), improvement.category(),
                improvement.severity(), file.isEmpty() ? title : "[" + file + "] " + title, improvement.detail(),
                improvement.recommendation(), improvement.line()));
        }
        return new SnippetAiResponseSupport.ScriptAnalysis(analysis.summary(), analysis.dependencies(), tagged);
    }

    /**
     * The project path {@code raw} names, or {@code ""}: exact or case-insensitive, with a line
     * suffix ({@code lib/a.sh:52}) or the folder in front ({@code getssl/lib/a.sh}) dropped, or a
     * shorter name ({@code a.sh}) that only one file ends with.
     */
    static String normalizeKnownPath(String raw, ProjectContext context) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String path = LINE_SUFFIX.matcher(QUOTES.matcher(raw.trim()).replaceAll("").replace('\\', '/')).replaceFirst("");
        while (path.startsWith("./") || path.startsWith("/")) {
            path = path.substring(path.startsWith("./") ? 2 : 1);
        }
        if (path.isEmpty()) {
            return "";
        }
        if (context.file(path) != null) {
            return path;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        String longest = "";
        for (ProjectFile file : context.files()) {
            String candidate = file.path().toLowerCase(Locale.ROOT);
            if ((candidate.equals(lower) || lower.endsWith("/" + candidate)) && candidate.length() > longest.length()) {
                longest = file.path();
            }
        }
        if (!longest.isEmpty()) {
            return longest;
        }
        String only = "";
        for (ProjectFile file : context.files()) {
            if (file.path().toLowerCase(Locale.ROOT).endsWith("/" + lower)) {
                if (!only.isEmpty()) {
                    return "";
                }
                only = file.path();
            }
        }
        return only;
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
