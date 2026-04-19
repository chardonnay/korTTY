package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses structured AI responses used by snippet-editor AI features.
 */
public final class SnippetAiResponseSupport {

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("(?s)\\{.*}");
    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("(?s)\\[.*]");

    private SnippetAiResponseSupport() {
    }

    public record AlternativeSolution(String title, String code, String summary) {
        public AlternativeSolution {
            title = title != null && !title.isBlank() ? title.trim() : "Alternative";
            code = code != null ? code.trim() : "";
            summary = summary != null ? summary.trim() : "";
        }

        public boolean isUsable() {
            return !code.isBlank();
        }
    }

    public static List<String> parseSegmentReplacements(String responseText, int expectedCount) {
        if (expectedCount <= 0) {
            return List.of();
        }
        String jsonCandidate = extractJsonPayload(responseText);
        if (jsonCandidate != null) {
            try {
                JsonElement root = JsonParser.parseString(jsonCandidate);
                JsonArray segments = null;
                if (root.isJsonObject()) {
                    JsonObject object = root.getAsJsonObject();
                    if (object.has("segments") && object.get("segments").isJsonArray()) {
                        segments = object.getAsJsonArray("segments");
                    } else if (object.has("replacements") && object.get("replacements").isJsonArray()) {
                        segments = object.getAsJsonArray("replacements");
                    }
                } else if (root.isJsonArray()) {
                    segments = root.getAsJsonArray();
                }
                if (segments != null) {
                    List<String> replacements = new ArrayList<>();
                    for (JsonElement segmentElement : segments) {
                        if (replacements.size() >= expectedCount) {
                            break;
                        }
                        String text = extractReplacementText(segmentElement);
                        if (text == null) {
                            continue;
                        }
                        replacements.add(text);
                    }
                    if (!replacements.isEmpty()) {
                        return replacements;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (expectedCount == 1) {
            return List.of(SnippetAiTextSupport.normalizePlainText(responseText));
        }
        return List.of();
    }

    public static List<AlternativeSolution> parseAlternativeSolutions(String responseText, int maxSolutions) {
        int limit = Math.max(1, maxSolutions);
        String jsonCandidate = extractJsonPayload(responseText);
        if (jsonCandidate == null) {
            return List.of();
        }
        try {
            JsonElement root = JsonParser.parseString(jsonCandidate);
            JsonArray solutions = null;
            if (root.isJsonObject()) {
                JsonObject object = root.getAsJsonObject();
                if (object.has("solutions") && object.get("solutions").isJsonArray()) {
                    solutions = object.getAsJsonArray("solutions");
                }
            } else if (root.isJsonArray()) {
                solutions = root.getAsJsonArray();
            }
            if (solutions == null) {
                return List.of();
            }
            List<AlternativeSolution> parsedSolutions = new ArrayList<>();
            for (JsonElement element : solutions) {
                if (parsedSolutions.size() >= limit) {
                    break;
                }
                AlternativeSolution solution = parseAlternativeSolution(element, parsedSolutions.size() + 1);
                if (solution != null && solution.isUsable()) {
                    parsedSolutions.add(solution);
                }
            }
            return parsedSolutions;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String extractJsonPayload(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            return null;
        }
        Matcher objectMatcher = JSON_OBJECT_PATTERN.matcher(responseText);
        if (objectMatcher.find()) {
            return objectMatcher.group();
        }
        Matcher arrayMatcher = JSON_ARRAY_PATTERN.matcher(responseText);
        return arrayMatcher.find() ? arrayMatcher.group() : null;
    }

    private static String extractReplacementText(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("text") && !object.get("text").isJsonNull()) {
            return object.get("text").getAsString();
        }
        if (object.has("replacement") && !object.get("replacement").isJsonNull()) {
            return object.get("replacement").getAsString();
        }
        if (object.has("content") && !object.get("content").isJsonNull()) {
            return object.get("content").getAsString();
        }
        return null;
    }

    private static AlternativeSolution parseAlternativeSolution(JsonElement element, int fallbackIndex) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            String code = element.getAsString();
            return new AlternativeSolution("Alternative " + fallbackIndex, code, "");
        }
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        String title = object.has("title") && !object.get("title").isJsonNull()
            ? object.get("title").getAsString()
            : "Alternative " + fallbackIndex;
        String code = object.has("code") && !object.get("code").isJsonNull()
            ? object.get("code").getAsString()
            : null;
        String summary = object.has("summary") && !object.get("summary").isJsonNull()
            ? object.get("summary").getAsString()
            : "";
        AlternativeSolution solution = new AlternativeSolution(title, code, summary);
        return solution.isUsable() ? solution : null;
    }
}
