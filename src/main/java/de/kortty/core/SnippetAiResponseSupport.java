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

    private static final Pattern MARKDOWN_CODE_BLOCK_PATTERN =
        Pattern.compile("(?s)```[A-Za-z0-9_+.#-]*\\R(.*?)\\R?```");

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

    /**
     * A single explained change produced by the security-fix flow: {@code anchor} is a verbatim line
     * from the fixed code used to locate the region, {@code reason} explains why it changed.
     */
    public record SecurityChange(String finding, String anchor, String reason) {
        public SecurityChange {
            finding = finding != null ? finding.trim() : "";
            anchor = anchor != null ? anchor.strip() : "";
            reason = reason != null ? reason.trim() : "";
        }

        public boolean isUsable() {
            return !reason.isBlank() && (!anchor.isBlank() || !finding.isBlank());
        }
    }

    /**
     * Result of applying selected security findings: the full fixed snippet, an overall summary, and a
     * per-change list with anchors + reasons for the diff hover annotations.
     */
    public record SnippetSecurityFix(String replacement, String summary, List<SecurityChange> changes) {
        public SnippetSecurityFix {
            replacement = replacement != null ? replacement : "";
            summary = summary != null ? summary.trim() : "";
            changes = changes != null ? List.copyOf(changes) : List.of();
        }

        public boolean isUsable() {
            return !replacement.isBlank();
        }
    }

    public record OneLinerSuggestion(String command) {
        public OneLinerSuggestion {
            command = command != null ? command.trim() : "";
        }

        public boolean isUsable() {
            return !command.isBlank() && !command.contains("\n") && !command.contains("\r");
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

    public record PlantUmlDiagram(
        String title,
        String plantUml,
        List<SnippetDiagramSupport.SourceCodeReference> codeReferences) {

        public PlantUmlDiagram(String title, String plantUml) {
            this(title, plantUml, List.of());
        }

        public PlantUmlDiagram {
            title = title != null && !title.isBlank() ? title.trim() : "Snippet structure";
            plantUml = SnippetDiagramSupport.ensureReadableActivityColors(plantUml);
            codeReferences = codeReferences != null ? List.copyOf(codeReferences) : List.of();
        }

        public boolean isUsable() {
            return SnippetDiagramSupport.isRenderablePlantUml(plantUml);
        }
    }

    /**
     * A single categorized improvement from the rich code-analysis flow. {@code category} is normalized to
     * one of {@code security|optimization|design|dependency}. Mirrors {@link CodeReviewFinding} plus a category.
     */
    public record ScriptImprovement(String id, String category, String severity, String title,
                                    String detail, String recommendation, Integer line) {
        public ScriptImprovement {
            id = id != null && !id.isBlank() ? id.trim() : "I";
            category = normalizeImprovementCategory(category);
            severity = severity != null && !severity.isBlank() ? severity.trim() : "info";
            title = title != null ? title.trim() : "";
            detail = detail != null ? detail.trim() : "";
            recommendation = recommendation != null ? recommendation.trim() : "";
        }

        public boolean isUsable() {
            return !title.isBlank() || !detail.isBlank() || !recommendation.isBlank();
        }
    }

    /** An external dependency the script relies on, plus a reduce/replace suggestion. */
    public record ScriptDependency(String id, String name, String kind, String purpose, String suggestion) {
        public ScriptDependency {
            id = id != null && !id.isBlank() ? id.trim() : "D";
            name = name != null ? name.trim() : "";
            kind = kind != null ? kind.trim() : "";
            purpose = purpose != null ? purpose.trim() : "";
            suggestion = suggestion != null ? suggestion.trim() : "";
        }

        public boolean isUsable() {
            return !name.isBlank();
        }
    }

    /** Rich code-analysis result: a plain-language summary, external dependencies and categorized improvements. */
    public record ScriptAnalysis(String summary, List<ScriptDependency> dependencies,
                                 List<ScriptImprovement> improvements) {
        public ScriptAnalysis {
            summary = summary != null ? summary.trim() : "";
            dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
            improvements = improvements != null ? List.copyOf(improvements) : List.of();
        }

        public boolean isUsable() {
            return !summary.isBlank() || !dependencies.isEmpty() || !improvements.isEmpty();
        }
    }

    private static String normalizeImprovementCategory(String category) {
        String value = category != null ? category.trim().toLowerCase() : "";
        return switch (value) {
            case "security", "sicherheit", "sec", "vulnerability" -> "security";
            case "optimization", "optimisation", "performance", "optimierung", "perf", "efficiency" -> "optimization";
            case "dependency", "dependencies", "abhängigkeit", "abhaengigkeit", "deps", "dep" -> "dependency";
            default -> "design";
        };
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
            String sanitized = AiResponseSanitizer.sanitizeForDisplay(responseText);
            return List.of(SnippetAiTextSupport.normalizePlainText(sanitized));
        }
        return List.of();
    }

    public static List<AlternativeSolution> parseAlternativeSolutions(String responseText, int maxSolutions) {
        int limit = Math.max(1, maxSolutions);
        JsonElement root = parseJsonElement(responseText);
        if (root == null) {
            root = parseJsonElement(extractJsonPayload(responseText));
        }
        if (root == null) {
            return List.of();
        }
        try {
            JsonArray solutions = null;
            if (root.isJsonObject()) {
                JsonObject object = root.getAsJsonObject();
                solutions = firstArray(object, "solutions", "alternatives", "alternativeSolutions", "results");
                if (solutions == null) {
                    AlternativeSolution singleSolution = parseAlternativeSolution(object, 1);
                    return singleSolution != null && singleSolution.isUsable()
                        ? List.of(singleSolution)
                        : List.of();
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
        return parseCodeImprovement(responseText, false);
    }

    public static CodeImprovement parseCodeImprovement(String responseText, boolean allowPlainTextFallback) {
        JsonObject object = parseJsonObject(responseText);
        if (object == null && !allowPlainTextFallback) {
            return new CodeImprovement("", "");
        }
        if (object == null) {
            CodeImprovement fallback = parseLenientCodeImprovement(responseText);
            if (fallback == null || !fallback.isUsable()) {
                fallback = new CodeImprovement(
                    extractPlainCodeFallback(responseText),
                    "");
            }
            return fallback.isUsable() ? fallback : new CodeImprovement("", "");
        }
        String replacement = firstString(object, "replacement", "code", "content", "text");
        String summary = firstString(object, "summary", "description");
        CodeImprovement nested = parseNestedCodeImprovement(replacement, summary);
        if (nested != null && nested.isUsable()) {
            return nested;
        }
        CodeImprovement improvement = new CodeImprovement(replacement, summary);
        return improvement.isUsable() ? improvement : new CodeImprovement("", "");
    }

    /**
     * Parses the security-fix response. Reuses the robust replacement/summary extraction of
     * {@link #parseCodeImprovement} and additionally reads the optional {@code changes} array so a fix
     * is still applied even when the model omits (or malforms) the explanations.
     */
    public static SnippetSecurityFix parseSecurityFix(String responseText) {
        CodeImprovement improvement = parseCodeImprovement(responseText, true);
        JsonObject object = parseJsonObject(responseText);
        List<SecurityChange> changes = object != null ? parseSecurityChanges(object) : List.of();
        return new SnippetSecurityFix(improvement.replacement(), improvement.summary(), changes);
    }

    private static List<SecurityChange> parseSecurityChanges(JsonObject object) {
        JsonArray array = firstArray(object, "changes", "explanations", "reasons");
        if (array == null) {
            return List.of();
        }
        List<SecurityChange> changes = new ArrayList<>();
        for (JsonElement element : array) {
            SecurityChange change = parseSecurityChange(element);
            if (change != null && change.isUsable()) {
                changes.add(change);
            }
        }
        return changes;
    }

    private static SecurityChange parseSecurityChange(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        SecurityChange change = new SecurityChange(
            firstString(object, "finding", "id", "findingId"),
            firstString(object, "anchor", "snippet", "code", "line"),
            firstString(object, "reason", "explanation", "why", "detail"));
        return change.isUsable() ? change : null;
    }

    public static OneLinerSuggestion parseOneLinerSuggestion(String responseText) {
        JsonObject object = parseJsonObject(responseText);
        if (object == null) {
            return new OneLinerSuggestion("");
        }
        OneLinerSuggestion suggestion = new OneLinerSuggestion(
            firstString(object, "command", "oneLiner", "one_liner", "line"));
        return suggestion.isUsable() ? suggestion : new OneLinerSuggestion("");
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
            firstString(object, "plantUml", "plantuml", "source", "diagram"),
            parseDiagramCodeReferences(object));
        return diagram.isUsable() ? diagram : new PlantUmlDiagram("", "");
    }

    private static List<SnippetDiagramSupport.SourceCodeReference> parseDiagramCodeReferences(JsonObject object) {
        JsonArray references = firstArray(object, "codeReferences", "sourceMap", "codeMap", "mappings");
        if (references == null) {
            return List.of();
        }
        List<SnippetDiagramSupport.SourceCodeReference> parsedReferences = new ArrayList<>();
        for (JsonElement element : references) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject reference = element.getAsJsonObject();
            String label = firstString(reference, "label", "diagramLabel", "node", "activity", "decision");
            Integer startLine = firstInt(reference, "startLine", "lineStart", "line");
            Integer endLine = firstInt(reference, "endLine", "lineEnd");
            if (endLine == null) {
                endLine = startLine;
            }
            if (label != null && !label.isBlank() && startLine != null && endLine != null) {
                parsedReferences.add(new SnippetDiagramSupport.SourceCodeReference(label, startLine, endLine));
            }
        }
        return List.copyOf(parsedReferences);
    }

    private static String extractJsonPayload(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            return null;
        }
        // Strip <think>…</think> reasoning first: reasoning-capable local models (LM Studio, Ollama,
        // llama.cpp serving DeepSeek-R1/QwQ/gpt-oss) leak their chain-of-thought into the answer, and
        // its braces used to corrupt extraction. Fall back to the raw text if sanitizing left nothing.
        String sanitized = AiResponseSanitizer.sanitizeForDisplay(responseText);
        String payload = firstBalancedJson(sanitized);
        return payload != null ? payload : firstBalancedJson(responseText);
    }

    /**
     * Returns the first balanced JSON value in {@code text} that actually parses, replacing a greedy
     * "first brace to last brace" match that broke whenever the model wrapped the JSON in prose or a
     * fenced block that also contained braces.
     *
     * <p>Passes are ordered so neither prose nor a stray list can shadow the real payload:
     * strictness first (a STRICT pass rejects prose like {@code &#123;key: value&#125;} with unquoted
     * names, then a LENIENT pass tolerates a model's minor JSON deviations, then an "allow empty"
     * pass as a last resort); and within each pass OBJECTS are preferred over ARRAYS — the snippet
     * prompts ask for objects, and a decoy bracketed list in prose must not win over the real object.
     * Root-array answers still resolve via {@link #parseArrayField}'s own array fallback.</p>
     */
    private static String firstBalancedJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String strict = scanBalancedJson(text, true, true);
        if (strict != null) {
            return strict;
        }
        String lenientNonEmpty = scanBalancedJson(text, false, true);
        return lenientNonEmpty != null ? lenientNonEmpty : scanBalancedJson(text, false, false);
    }

    private static String scanBalancedJson(String text, boolean strict, boolean requireNonEmpty) {
        String object = firstBalancedContainer(text, '{', '}', strict, requireNonEmpty);
        return object != null ? object : firstBalancedContainer(text, '[', ']', strict, requireNonEmpty);
    }

    private static String firstBalancedContainer(
        String text, char open, char close, boolean strict, boolean requireNonEmpty) {
        for (int i = text.indexOf(open); i >= 0; i = text.indexOf(open, i + 1)) {
            String span = balancedSpan(text, i, open, close);
            if (span == null) {
                continue;
            }
            JsonElement parsed = strict ? parseStrictJsonElement(span) : parseJsonElement(span);
            if (parsed != null && (!requireNonEmpty || isNonEmptyContainer(parsed))) {
                return span;
            }
        }
        return null;
    }

    private static JsonElement parseStrictJsonElement(String candidate) {
        try (com.google.gson.stream.JsonReader reader =
                 new com.google.gson.stream.JsonReader(new java.io.StringReader(candidate))) {
            reader.setStrictness(com.google.gson.Strictness.STRICT);
            JsonElement element = JsonParser.parseReader(reader);
            return reader.peek() == com.google.gson.stream.JsonToken.END_DOCUMENT ? element : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String balancedSpan(String text, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static boolean isNonEmptyContainer(JsonElement element) {
        if (element.isJsonObject()) {
            return !element.getAsJsonObject().keySet().isEmpty();
        }
        if (element.isJsonArray()) {
            return !element.getAsJsonArray().isEmpty();
        }
        return false;
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

    private static String extractPlainCodeFallback(String responseText) {
        String sanitized = AiResponseSanitizer.sanitizeForDisplay(responseText);
        if (sanitized.isBlank()) {
            return "";
        }
        CodeImprovement lenient = parseLenientCodeImprovement(sanitized);
        if (lenient != null && lenient.isUsable()) {
            return lenient.replacement();
        }
        Matcher codeBlock = MARKDOWN_CODE_BLOCK_PATTERN.matcher(sanitized);
        return codeBlock.find() ? codeBlock.group(1) : sanitized;
    }

    private static CodeImprovement parseNestedCodeImprovement(String replacement, String outerSummary) {
        if (replacement == null || replacement.isBlank()) {
            return null;
        }
        JsonObject nestedObject = parseJsonObject(replacement);
        if (nestedObject != null) {
            String nestedReplacement = firstString(nestedObject, "replacement", "code", "content", "text");
            String nestedSummary = firstString(nestedObject, "summary", "description");
            CodeImprovement nested = new CodeImprovement(
                nestedReplacement,
                nonBlank(nestedSummary, outerSummary));
            return nested.isUsable() ? nested : null;
        }
        CodeImprovement lenient = parseLenientCodeImprovement(replacement);
        if (lenient != null && lenient.isUsable()) {
            return new CodeImprovement(lenient.replacement(), nonBlank(lenient.summary(), outerSummary));
        }
        return null;
    }

    private static CodeImprovement parseLenientCodeImprovement(String responseText) {
        String value = responseText != null ? responseText.trim() : "";
        if (value.isBlank() || !value.contains("\"replacement\"")) {
            return null;
        }
        String replacement = extractLenientJsonStringField(value, "replacement");
        if (replacement == null || replacement.isBlank()) {
            return null;
        }
        String summary = extractLenientJsonStringField(value, "summary");
        return new CodeImprovement(replacement, summary);
    }

    private static String extractLenientJsonStringField(String text, String fieldName) {
        Pattern fieldPattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"");
        Matcher matcher = fieldPattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        int start = matcher.end();
        StringBuilder value = new StringBuilder();
        boolean escaping = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaping) {
                appendJsonEscaped(value, c);
                escaping = false;
                continue;
            }
            if (c == '\\') {
                escaping = true;
                continue;
            }
            if (c == '"' && looksLikeFieldTerminator(text, i + 1)) {
                return value.toString();
            }
            value.append(c);
        }
        return value.toString();
    }

    private static void appendJsonEscaped(StringBuilder builder, char escaped) {
        switch (escaped) {
            case 'n' -> builder.append('\n');
            case 'r' -> builder.append('\r');
            case 't' -> builder.append('\t');
            case 'b' -> builder.append('\b');
            case 'f' -> builder.append('\f');
            case '"', '\\', '/' -> builder.append(escaped);
            default -> builder.append(escaped);
        }
    }

    private static boolean looksLikeFieldTerminator(String text, int offset) {
        int i = offset;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i >= text.length()
            || text.charAt(i) == ','
            || text.charAt(i) == '}'
            || text.charAt(i) == ']';
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
        String code = firstString(object, "code", "replacement", "content", "text", "solution");
        String summary = firstString(object, "summary", "description", "explanation");
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
        // Root-array answer (e.g. "[ {finding}, {finding} ]") that the object-preferring payload
        // extraction above did not surface. Use a balanced array scan that favours an array OF
        // OBJECTS (findings/solutions/segments are all object lists), so a stray primitive list in
        // prose — even one nested inside a decoy object — cannot shadow the real array.
        String arrayPayload = firstBalancedArray(responseText);
        return arrayPayload != null ? parseArrayFieldRoot(parseJsonElement(arrayPayload), fieldName) : null;
    }

    /**
     * Finds the first balanced {@code [...]} array to use as an array-field fallback: an array whose
     * first element is an object wins over any other array, so a decoy list of primitives never
     * shadows the real list of findings/solutions. Sanitized (reasoning-stripped) text is tried
     * first, then the raw text if sanitizing removed too much.
     */
    private static String firstBalancedArray(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String sanitized = AiResponseSanitizer.sanitizeForDisplay(text);
        String objectsArray = firstBalancedArrayOfObjects(sanitized);
        if (objectsArray != null) {
            return objectsArray;
        }
        String anyArray = firstBalancedContainer(sanitized, '[', ']', false, true);
        return anyArray != null ? anyArray : firstBalancedContainer(text, '[', ']', false, true);
    }

    private static String firstBalancedArrayOfObjects(String text) {
        for (int i = text.indexOf('['); i >= 0; i = text.indexOf('[', i + 1)) {
            String span = balancedSpan(text, i, '[', ']');
            if (span == null) {
                continue;
            }
            JsonElement parsed = parseJsonElement(span);
            if (parsed != null && parsed.isJsonArray()) {
                JsonArray array = parsed.getAsJsonArray();
                if (!array.isEmpty() && array.get(0).isJsonObject()) {
                    return span;
                }
            }
        }
        return null;
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

    /**
     * Parses the rich code-analysis response: a {@code summary}, a {@code dependencies} array and a
     * categorized {@code improvements} array. Tolerant of fences/prose (multi-pass {@link #parseJsonObject}),
     * missing ids (fallback ids) and empty arrays, mirroring the other parsers here.
     */
    public static ScriptAnalysis parseScriptAnalysis(String responseText) {
        JsonObject object = parseJsonObject(responseText);
        if (object == null) {
            return new ScriptAnalysis("", List.of(), List.of());
        }
        String summary = firstString(object, "summary", "explanation", "description", "overview");
        return new ScriptAnalysis(summary, parseScriptDependencies(object), parseScriptImprovements(object));
    }

    private static List<ScriptImprovement> parseScriptImprovements(JsonObject object) {
        JsonArray array = firstArray(object, "improvements", "findings", "suggestions", "tips");
        if (array == null) {
            return List.of();
        }
        List<ScriptImprovement> result = new ArrayList<>();
        int fallbackIndex = 1;
        for (JsonElement element : array) {
            ScriptImprovement item = parseScriptImprovement(element, fallbackIndex++);
            if (item != null && item.isUsable()) {
                result.add(item);
            }
        }
        return result;
    }

    private static ScriptImprovement parseScriptImprovement(JsonElement element, int fallbackIndex) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        ScriptImprovement item = new ScriptImprovement(
            nonBlank(firstString(object, "id"), "I" + fallbackIndex),
            firstString(object, "category", "kind", "type"),
            firstString(object, "severity", "priority"),
            firstString(object, "title", "name"),
            firstString(object, "detail", "impact", "description"),
            firstString(object, "recommendation", "fix", "suggestion"),
            firstInt(object, "line", "lineNumber"));
        return item.isUsable() ? item : null;
    }

    private static List<ScriptDependency> parseScriptDependencies(JsonObject object) {
        JsonArray array = firstArray(object, "dependencies", "deps", "requirements");
        if (array == null) {
            return List.of();
        }
        List<ScriptDependency> result = new ArrayList<>();
        int fallbackIndex = 1;
        for (JsonElement element : array) {
            ScriptDependency dependency = parseScriptDependency(element, fallbackIndex++);
            if (dependency != null && dependency.isUsable()) {
                result.add(dependency);
            }
        }
        return result;
    }

    private static ScriptDependency parseScriptDependency(JsonElement element, int fallbackIndex) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        ScriptDependency dependency = new ScriptDependency(
            nonBlank(firstString(object, "id"), "D" + fallbackIndex),
            firstString(object, "name", "dependency", "tool", "service"),
            firstString(object, "kind", "type", "category"),
            firstString(object, "purpose", "reason", "detail", "description"),
            firstString(object, "suggestion", "recommendation", "replacement", "reduce", "alternative"));
        return dependency.isUsable() ? dependency : null;
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

    private static JsonArray firstArray(JsonObject object, String... names) {
        if (object == null || names == null) {
            return null;
        }
        for (String name : names) {
            if (name != null && object.has(name) && object.get(name).isJsonArray()) {
                return object.getAsJsonArray(name);
            }
        }
        return null;
    }

    private static Integer firstInt(JsonObject object, String... names) {
        if (object == null || names == null) {
            return null;
        }
        for (String name : names) {
            if (name == null || !object.has(name) || object.get(name).isJsonNull()) {
                continue;
            }
            try {
                return object.get(name).getAsInt();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }
}
