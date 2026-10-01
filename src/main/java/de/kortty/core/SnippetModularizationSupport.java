package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The modularization option of the Full code analysis: the AI's plan (which files a script or a
 * folder splits into), its parsing and normalization, and the checks run on the generated files
 * before they are offered for review. FX-free.
 */
public final class SnippetModularizationSupport {

    private SnippetModularizationSupport() {
    }

    /**
     * One planned file. {@code path} is relative with {@code /} separators; exactly one file of a
     * usable plan is the {@code entryPoint} (the one the user runs).
     */
    public record ModuleFile(String path, String purpose, List<String> symbols, boolean executable,
                             boolean entryPoint) {
        public ModuleFile {
            path = path != null ? path.trim() : "";
            purpose = purpose != null ? purpose.trim() : "";
            symbols = symbols == null ? List.of() : symbols.stream()
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .map(String::trim)
                .toList();
        }

        public String fileName() {
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        }

        public String directory() {
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(0, slash) : "";
        }
    }

    /** The AI's proposal; {@code recommended == false} keeps the code as it is, with the reason. */
    public record ModularizationPlan(boolean recommended, String rationale, List<ModuleFile> files) {
        public static final ModularizationPlan NONE = new ModularizationPlan(false, "", List.of());

        public ModularizationPlan {
            rationale = rationale != null ? rationale.trim() : "";
            files = files == null ? List.of() : List.copyOf(files);
        }

        /** A plan that can be applied: recommended, with at least two files and one entry point. */
        public boolean isApplicable() {
            return recommended && files.size() >= 2 && entryPoint() != null;
        }

        /** Whether the AI answered at all (a usable "no split" counts). */
        public boolean isUsable() {
            return !rationale.isBlank() || !files.isEmpty();
        }

        public ModuleFile entryPoint() {
            return files.stream().filter(ModuleFile::entryPoint).findFirst().orElse(null);
        }

        /** The entry point first, then the modules in plan order. */
        public List<ModuleFile> modulesFirst() {
            List<ModuleFile> ordered = new ArrayList<>(files.stream().filter(file -> !file.entryPoint()).toList());
            ModuleFile entry = entryPoint();
            if (entry != null) {
                ordered.add(entry);
            }
            return ordered;
        }
    }

    // ---- Parsing ----

    /**
     * Reads a plan answer. Paths are normalized (see {@link #normalizePath}), duplicates dropped
     * and exactly one entry point kept (the first marked one, else the first executable file,
     * else the first file); the entry point is always executable.
     */
    public static ModularizationPlan parsePlan(String answer, String defaultExtension) {
        JsonObject object = SnippetAiResponseSupport.parseLenientJsonObject(answer);
        if (object == null) {
            return ModularizationPlan.NONE;
        }
        boolean recommended = booleanValue(object, "recommended", "split", "modularize");
        String rationale = stringValue(object, "rationale", "reason", "explanation", "summary");
        JsonArray array = arrayValue(object, "files", "modules");
        List<ModuleFile> files = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (array != null) {
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject file = element.getAsJsonObject();
                String path = normalizePath(stringValue(file, "path", "file", "fileName", "name"), defaultExtension);
                if (path.isEmpty() || !seen.add(path.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                List<String> symbols = new ArrayList<>();
                JsonArray symbolArray = arrayValue(file, "symbols", "functions", "contains");
                if (symbolArray != null) {
                    symbolArray.forEach(symbol -> {
                        if (symbol.isJsonPrimitive()) {
                            symbols.add(symbol.getAsString());
                        }
                    });
                }
                files.add(new ModuleFile(path, stringValue(file, "purpose", "description", "role"), symbols,
                    booleanValue(file, "executable"), booleanValue(file, "entryPoint", "entry", "main")));
            }
        }
        return new ModularizationPlan(recommended && files.size() >= 2, rationale, withOneEntryPoint(files));
    }

    private static List<ModuleFile> withOneEntryPoint(List<ModuleFile> files) {
        if (files.isEmpty()) {
            return files;
        }
        int entry = -1;
        for (int i = 0; i < files.size() && entry < 0; i++) {
            if (files.get(i).entryPoint()) {
                entry = i;
            }
        }
        for (int i = 0; i < files.size() && entry < 0; i++) {
            if (files.get(i).executable()) {
                entry = i;
            }
        }
        if (entry < 0) {
            entry = 0;
        }
        List<ModuleFile> normalized = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            ModuleFile file = files.get(i);
            boolean isEntry = i == entry;
            normalized.add(new ModuleFile(file.path(), file.purpose(), file.symbols(),
                isEntry || file.executable(), isEntry));
        }
        return normalized;
    }

    /**
     * A safe relative path: {@code \} becomes {@code /}, leading {@code /} and {@code ./}, empty,
     * {@code .} and {@code ..} segments are dropped and each segment is made a valid file name. A
     * last segment without an extension gets {@code defaultExtension}.
     */
    public static String normalizePath(String raw, String defaultExtension) {
        if (raw == null) {
            return "";
        }
        List<String> segments = new ArrayList<>();
        for (String segment : raw.trim().replace('\\', '/').split("/")) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty() || trimmed.equals(".") || trimmed.equals("..")) {
                continue;
            }
            String safe = SnippetExecutableSupport.sanitizeFileName(trimmed);
            if (!safe.isEmpty()) {
                segments.add(safe);
            }
        }
        if (segments.isEmpty()) {
            return "";
        }
        String last = segments.getLast();
        if (SnippetExecutableSupport.extensionOf(last) == null && defaultExtension != null
            && !defaultExtension.isBlank() && !last.equals("__init__")) {
            segments.set(segments.size() - 1, last + (defaultExtension.startsWith(".") ? defaultExtension : "." + defaultExtension));
        }
        return String.join("/", segments);
    }

    /** The file content of a {@link AiAction#GENERATE_SNIPPET_MODULE} answer; {@code null} when unusable. */
    public static String parseGeneratedFile(String answer) {
        JsonObject object = SnippetAiResponseSupport.parseLenientJsonObject(answer);
        if (object == null) {
            return null;
        }
        JsonArray lines = arrayValue(object, "fileLines", "lines", "replacementLines");
        if (lines != null) {
            List<String> text = new ArrayList<>();
            for (JsonElement line : lines) {
                text.add(line.isJsonPrimitive() ? line.getAsString() : line.toString());
            }
            String content = String.join("\n", text);
            return content.isBlank() ? null : content;
        }
        String content = stringValue(object, "content", "code", "replacement");
        return content.isBlank() ? null : content;
    }

    // ---- Validation ----

    /**
     * What is wrong with a set of generated files, empty when nothing is: a missing or empty file,
     * or an entry point that does not mention a module by its file name or module name.
     */
    public static List<String> problems(ModularizationPlan plan, Map<String, String> contents) {
        List<String> problems = new ArrayList<>();
        for (ModuleFile file : plan.files()) {
            String content = contents.get(file.path());
            if (content == null || content.isBlank() && !isPackageMarker(file)) {
                problems.add("File " + file.path() + " is empty or missing.");
            }
        }
        ModuleFile entry = plan.entryPoint();
        String entryContent = entry != null ? contents.get(entry.path()) : null;
        if (entryContent != null) {
            for (ModuleFile file : plan.files()) {
                if (file.entryPoint() || isPackageMarker(file) || !isCodeModule(file)) {
                    continue;
                }
                if (!mentionsModule(entryContent, contents, plan, file)) {
                    problems.add("No file loads the module " + file.path() + ".");
                }
            }
        }
        return problems;
    }

    /** Whether any other generated file references {@code module} (by file name or module stem). */
    private static boolean mentionsModule(String entryContent, Map<String, String> contents, ModularizationPlan plan,
                                          ModuleFile module) {
        String stem = stemOf(module.fileName());
        for (ModuleFile other : plan.files()) {
            if (other.path().equals(module.path())) {
                continue;
            }
            String text = other.entryPoint() ? entryContent : contents.get(other.path());
            if (text != null && (text.contains(module.fileName()) || containsWord(text, stem))) {
                return true;
            }
        }
        return false;
    }

    /** A Python package marker, which may stay empty and is written without asking the AI. */
    public static boolean isPackageMarker(ModuleFile file) {
        return file.fileName().equals("__init__.py");
    }

    private static boolean isCodeModule(ModuleFile file) {
        String extension = SnippetExecutableSupport.extensionOf(file.fileName());
        return extension != null && Set.of("sh", "bash", "zsh", "ksh", "py", "pl", "pm", "rb", "js", "ts", "ps1",
            "psm1", "groovy").contains(extension);
    }

    private static String stemOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static boolean containsWord(String text, String word) {
        if (word.isBlank()) {
            return false;
        }
        return java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(word)
            + "(?![A-Za-z0-9_])").matcher(text).find();
    }

    /** The plan as a compact text block for the generation prompt. */
    public static String describe(ModularizationPlan plan) {
        StringBuilder text = new StringBuilder();
        for (ModuleFile file : plan.files()) {
            text.append("- ").append(file.path());
            if (file.entryPoint()) {
                text.append(" (entry point)");
            }
            if (file.executable()) {
                text.append(" [executable]");
            }
            if (!file.purpose().isBlank()) {
                text.append(": ").append(file.purpose());
            }
            if (!file.symbols().isEmpty()) {
                text.append(" — defines ").append(String.join(", ", file.symbols()));
            }
            text.append('\n');
        }
        return text.toString();
    }

    /** Orders generated contents by plan order (entry point first) for display and storage. */
    public static Map<String, String> inPlanOrder(ModularizationPlan plan, Map<String, String> contents) {
        Map<String, String> ordered = new LinkedHashMap<>();
        ModuleFile entry = plan.entryPoint();
        if (entry != null && contents.containsKey(entry.path())) {
            ordered.put(entry.path(), contents.get(entry.path()));
        }
        for (ModuleFile file : plan.files()) {
            if (contents.containsKey(file.path())) {
                ordered.putIfAbsent(file.path(), contents.get(file.path()));
            }
        }
        return ordered;
    }

    // ---- JSON helpers ----

    private static String stringValue(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive()) {
                return value.getAsString();
            }
        }
        return "";
    }

    private static boolean booleanValue(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive()) {
                if (value.getAsJsonPrimitive().isBoolean()) {
                    return value.getAsBoolean();
                }
                String text = value.getAsString().trim().toLowerCase(Locale.ROOT);
                return text.equals("true") || text.equals("yes") || text.equals("1");
            }
        }
        return false;
    }

    private static JsonArray arrayValue(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonArray()) {
                return value.getAsJsonArray();
            }
        }
        return null;
    }
}
