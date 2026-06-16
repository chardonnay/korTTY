package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the LM Studio CLI ({@code lms}) model-management commands used by the AI
 * setup wizard: list loaded models ({@code lms ps --json}), list downloaded models
 * ({@code lms ls --json}) and load a model ({@code lms load <key> -y}). Only chat LLMs
 * (JSON {@code type == "llm"}) are returned; the model identifier is the {@code modelKey} field.
 *
 * <p>All methods are blocking and must be invoked off the JavaFX thread.
 */
public final class LmStudioCliModels {

    private LmStudioCliModels() {
    }

    /**
     * Resolves the {@code lms} executable: an explicit override, then the per-user default
     * {@code ~/.lmstudio/bin/lms}, then a PATH / common-directory lookup. Returns null when not found.
     */
    public static String resolveExecutable(String overridePath) {
        String override = overridePath != null ? overridePath.trim() : "";
        if (!override.isBlank() && Files.isExecutable(Path.of(override))) {
            return override;
        }
        Path userBinary = Path.of(System.getProperty("user.home", "")).resolve(".lmstudio").resolve("bin").resolve("lms");
        if (Files.isExecutable(userBinary)) {
            return userBinary.toString();
        }
        return AiCliProviderRegistry.findExecutable("lms").orElse(null);
    }

    /** Model keys currently loaded in LM Studio (chat LLMs only). */
    public static List<String> listLoadedModelKeys(String lmsExecutable) throws Exception {
        return parseModelKeys(run(lmsExecutable, List.of("ps", "--json"), 30));
    }

    /** Model keys downloaded on disk (chat LLMs only), whether or not currently loaded. */
    public static List<String> listDownloadedModelKeys(String lmsExecutable) throws Exception {
        return parseModelKeys(run(lmsExecutable, List.of("ls", "--json"), 30));
    }

    /** Loads the given model into LM Studio (non-interactive). Throws on failure. */
    public static void loadModel(String lmsExecutable, String modelKey) throws Exception {
        if (modelKey == null || modelKey.isBlank()) {
            throw new IllegalArgumentException("Model key must not be blank.");
        }
        run(lmsExecutable, List.of("load", modelKey.trim(), "-y"), 180);
    }

    /**
     * Extracts chat-LLM model keys from raw {@code lms ... --json} output. Tolerates a leading
     * "Waking up LM Studio service..." banner by reading the first bracketed JSON array, and skips
     * non-LLM entries (e.g. embedding models).
     */
    static List<String> parseModelKeys(String rawOutput) {
        if (rawOutput == null) {
            return new ArrayList<>();
        }
        int start = rawOutput.indexOf('[');
        int end = rawOutput.lastIndexOf(']');
        if (start < 0 || end < start) {
            return new ArrayList<>();
        }
        JsonElement parsed = JsonParser.parseString(rawOutput.substring(start, end + 1));
        return modelKeys(parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray());
    }

    private static List<String> modelKeys(JsonArray array) {
        List<String> keys = new ArrayList<>();
        if (array == null) {
            return keys;
        }
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String type = string(object, "type");
            if (!"llm".equalsIgnoreCase(type)) {
                continue;
            }
            String key = string(object, "modelKey");
            if (key != null && !key.isBlank() && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static String string(JsonObject object, String member) {
        return object.has(member) && object.get(member).isJsonPrimitive() ? object.get(member).getAsString() : null;
    }

    private static String run(String lmsExecutable, List<String> args, int timeoutSeconds) throws Exception {
        String executable = Optional.ofNullable(lmsExecutable).map(String::trim).filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalStateException("LM Studio CLI (lms) was not found."));
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(args);
        // Merge stderr into stdout: lms emits a non-fatal "Waking up..." banner and load warnings
        // there, and the JSON extraction / exit-code check tolerate the extra text.
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("lms command timed out.");
        }
        if (process.exitValue() != 0) {
            String detail = output.strip();
            throw new IllegalStateException("lms exited with code " + process.exitValue()
                + (detail.isBlank() ? "" : ": " + (detail.length() > 300 ? detail.substring(0, 300) : detail)));
        }
        return output;
    }
}
