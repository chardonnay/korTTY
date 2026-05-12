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

    public record CompletionSuggestion(String insertText, String summary) {
        public CompletionSuggestion {
            insertText = insertText != null ? insertText : "";
            summary = summary != null ? summary.trim() : "";
        }

        public boolean isUsable() {
            return !insertText.isBlank();
        }
    }

    public record CodeReviewFinding(String id, String severity, String title, String detail, String recommendation, Integer line) {
        public CodeReviewFinding {
            id = id != null && !id.isBlank() ? id.trim() : "R";
            severity = severity != null && !severity.isBlank() ? severity.trim() : "info";
            title = title != null ? title.trim() : "";
            detail = detail != null ? detail.trim() : "";
            recommendation = recommendation != null ? recommendation.trim() : "";
        }

        public boolean isUsable() {
            return !title.isBlank() || !detail.isBlank() || !recommendation.isBlank();
        }
    }

    public record CodeImprovement(String replacement, String summary) {
        public CodeImprovement {
            replacement = replacement != null ? replacement : "";
            summary = summary != null ? summary.trim() : "";
        }

        public boolean isUsable() {
            return !replacement.isBlank();
        }
    }

    public record SecurityFinding(String id, String severity, String title, String impact, String recommendation) {
        public SecurityFinding {
            id = id != null && !id.isBlank() ? id.trim() : "S";
            severity = severity != null && !severity.isBlank() ? severity.trim() : "info";
            title = title != null ? title.trim() : "";
            impact = impact != null ? impact.trim() : "";
            recommendation = recommendation != null ? recommendation.trim() : "";
        }

        public boolean isUsable() {
            return !title.isBlank() || !impact.isBlank() || !recommendation.isBlank();
        }
    }

    public record PlantUmlDiagram(String title, String plantUml) {
        public PlantUmlDiagram {
            title = title != null && !title.isBlank() ? title.trim() : "Snippet structure";
            plantUml = SnippetDiagramSupport.normalizePlantUml(plantUml);
        }

        public boolean isUsable() {
            return SnippetDiagramSupport.isRenderablePlantUml(plantUml);
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

    public static CompletionSuggestion parseCompletionSuggestion(String responseText) {
        JsonObject object = parseJsonObject(responseText);
        if (object == null) {
            return new CompletionSuggestion("", "");
        }
        String insertText = firstString(object, "insertText", "completion", "text", "code");
        String summary = firstString(object, "summary", "description");
        CompletionSuggestion suggestion = new CompletionSuggestion(insertText, summary);
        return suggestion.isUsable() ? suggestion : new CompletionSuggestion("", "");
    }

    public static List<CodeReviewFinding> parseCodeReviewFindings(String responseText) {
        JsonArray findings = parseArrayField(responseText, "findings");
        if (findings == null) {
            return List.of();
        }
        List<CodeReviewFinding> parsedFindings = new ArrayList<>();
        int fallbackIndex = 1;
        for (JsonElement element : findings) {
            CodeReviewFinding finding = parseCodeReviewFinding(element, fallbackIndex++);
            if (finding != null && finding.isUsable()) {
                parsedFindings.add(finding);
            }
        }
        return parsedFindings;
    }

    public static CodeImprovement parseCodeImprovement(String responseText) {
        JsonObject object = parseJsonObject(responseText);
        if (object == null) {
            return new CodeImprovement("", "");
        }
        String replacement = firstString(object, "replacement", "code", "content", "text");
        String summary = firstString(object, "summary", "description");
        CodeImprovement improvement = new CodeImprovement(replacement, summary);
        return improvement.isUsable() ? improvement : new CodeImprovement("", "");
    }

    public static List<SecurityFinding> parseSecurityFindings(String responseText) {
        JsonArray findings = parseArrayField(responseText, "findings");
        if (findings == null) {
            return List.of();
        }
        List<SecurityFinding> parsedFindings = new ArrayList<>();
        int fallbackIndex = 1;
        for (JsonElement element : findings) {
            SecurityFinding finding = parseSecurityFinding(element, fallbackIndex++);
            if (finding != null && finding.isUsable()) {
                parsedFindings.add(finding);
            }
        }
        return parsedFindings;
    }

    public static PlantUmlDiagram parsePlantUmlDiagram(String responseText) {
        JsonObject object = parseJsonObject(responseText);
        if (object == null) {
            return new PlantUmlDiagram("", "");
        }
        PlantUmlDiagram diagram = new PlantUmlDiagram(
            firstString(object, "title", "name"),
            firstString(object, "plantUml", "plantuml", "source", "diagram"));
        return diagram.isUsable() ? diagram : new PlantUmlDiagram("", "");
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

    private static JsonObject parseJsonObject(String responseText) {
        String jsonCandidate = extractJsonPayload(responseText);
        if (jsonCandidate == null) {
            return null;
        }
        JsonElement root = parseJsonElement(jsonCandidate);
        return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
    }

    private static JsonArray parseArrayField(String responseText, String fieldName) {
        JsonArray array = parseArrayFieldRoot(parseJsonElement(responseText), fieldName);
        if (array != null) {
            return array;
        }
        array = parseArrayFieldRoot(parseJsonElement(extractJsonPayload(responseText)), fieldName);
        if (array != null) {
            return array;
        }
        Matcher arrayMatcher = responseText != null ? JSON_ARRAY_PATTERN.matcher(responseText) : null;
        return arrayMatcher != null && arrayMatcher.find()
            ? parseArrayFieldRoot(parseJsonElement(arrayMatcher.group()), fieldName)
            : null;
    }

    private static JsonElement parseJsonElement(String jsonCandidate) {
        if (jsonCandidate == null || jsonCandidate.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(jsonCandidate);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonArray parseArrayFieldRoot(JsonElement root, String fieldName) {
        if (root == null) {
            return null;
        }
        if (root.isJsonArray()) {
            return root.getAsJsonArray();
        }
        if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            if (object.has(fieldName) && object.get(fieldName).isJsonArray()) {
                return object.getAsJsonArray(fieldName);
            }
        }
        return null;
    }

    private static CodeReviewFinding parseCodeReviewFinding(JsonElement element, int fallbackIndex) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        Integer line = null;
        if (object.has("line") && object.get("line").isJsonPrimitive()) {
            try {
                line = object.get("line").getAsInt();
            } catch (NumberFormatException ignored) {
            }
        }
        CodeReviewFinding finding = new CodeReviewFinding(
            nonBlank(firstString(object, "id"), "R" + fallbackIndex),
            firstString(object, "severity"),
            firstString(object, "title"),
            firstString(object, "detail", "impact", "description"),
            firstString(object, "recommendation", "fix", "suggestion"),
            line);
        return finding.isUsable() ? finding : null;
    }

    private static SecurityFinding parseSecurityFinding(JsonElement element, int fallbackIndex) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        SecurityFinding finding = new SecurityFinding(
            nonBlank(firstString(object, "id"), "S" + fallbackIndex),
            firstString(object, "severity"),
            firstString(object, "title"),
            firstString(object, "impact", "detail", "description"),
            firstString(object, "recommendation", "fix", "suggestion"));
        return finding.isUsable() ? finding : null;
    }

    private static String firstString(JsonObject object, String... names) {
        if (object == null || names == null) {
            return "";
        }
        for (String name : names) {
            if (name != null && object.has(name) && !object.get(name).isJsonNull()) {
                try {
                    return object.get(name).getAsString();
                } catch (Exception ignored) {
                }
            }
        }
        return "";
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }
}
