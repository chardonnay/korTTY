package de.kortty.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import de.kortty.control.ControlJson;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The whole stdout and stderr contract of the CLI, in two methods.
 *
 * <p>Success goes to stdout and failure goes to stderr, and nothing else is ever written to stdout —
 * no banner, no progress, no colour — so {@code $( … )} and {@code | jq} are always safe. That is the
 * reason this class exists rather than a handful of {@code println} calls spread through
 * {@link KorttyCli}: there is exactly one place that can violate the invariant, and it is small
 * enough to read.
 *
 * <p>Neither method prints the token, the endpoint's contents, or any pane text the caller did not
 * explicitly ask for.
 *
 * <p>Pure apart from writing to the stream it is handed; any thread.
 */
public final class CliOutput {

    /** The prefix of every human-readable diagnostic, so a log line names the program that failed. */
    static final String PROGRAM = "kortty-cli";

    private static final Gson PRETTY = new GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .disableHtmlEscaping()
        .create();

    private CliOutput() {
    }

    /**
     * Prints one successful result.
     *
     * <p>The default is one compact line, which is what makes the output composable. {@code --pretty}
     * indents it for a human, and {@code --raw} replaces it with the plain text of the verbs that
     * carry text: the joined rows of {@code pane read} and the explanation of {@code agent explain}.
     * A verb with no text form ignores {@code --raw} and prints its JSON rather than inventing a
     * rendering, because a script that pipes the result into {@code jq} must still get JSON.
     */
    public static void printResult(PrintStream out, JsonElement result, boolean raw, boolean pretty) {
        if (out == null) {
            return;
        }
        if (raw) {
            String text = rawText(result);
            if (text != null) {
                out.println(text);
                return;
            }
        }
        out.println(json(result, pretty));
    }

    /**
     * Prints one failure.
     *
     * <p>Without {@code --pretty} the human-readable form is used, one line naming the message and
     * the stable wire code — the code being what a script branches on and what a bug report needs.
     * With {@code --pretty} the JSON-RPC error object is reproduced instead, so
     * {@code 2>file; jq .data.code file} works.
     */
    public static void printError(PrintStream err, CliServerException e, boolean pretty) {
        if (err == null || e == null) {
            return;
        }
        if (!pretty) {
            err.println(PROGRAM + ": " + e.getMessage() + " (" + e.wireCode() + ")");
            return;
        }
        JsonObject error = new JsonObject();
        error.addProperty("code", e.jsonRpcCode());
        error.addProperty("message", e.getMessage());
        error.add("data", e.data());
        err.println(PRETTY.toJson(error));
    }

    /** One compact line, or the indented form under {@code --pretty}. */
    static String json(JsonElement result, boolean pretty) {
        JsonElement value = result == null ? JsonNull.INSTANCE : result;
        return pretty ? PRETTY.toJson(value) : ControlJson.gson().toJson(value);
    }

    /** The plain-text rendering of a text-bearing result, or null when the result carries none. */
    private static String rawText(JsonElement result) {
        if (result == null || result.isJsonNull()) {
            return null;
        }
        if (result.isJsonPrimitive() && result.getAsJsonPrimitive().isString()) {
            return result.getAsString();
        }
        if (!result.isJsonObject()) {
            return null;
        }
        JsonObject object = result.getAsJsonObject();
        JsonElement explain = object.get("explain");
        if (explain != null && explain.isJsonPrimitive() && explain.getAsJsonPrimitive().isString()) {
            return explain.getAsString();
        }
        JsonElement lines = object.get("lines");
        if (lines == null || !lines.isJsonArray()) {
            return null;
        }
        List<String> rows = new ArrayList<>();
        for (JsonElement line : lines.getAsJsonArray()) {
            if (line == null || line.isJsonNull()) {
                rows.add("");
            } else if (line.isJsonPrimitive() && line.getAsJsonPrimitive().isString()) {
                rows.add(line.getAsString());
            } else {
                // Not a text array after all; fall back to JSON rather than print a half rendering.
                return null;
            }
        }
        return String.join(System.lineSeparator(), rows);
    }
}
