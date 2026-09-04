package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.model.SnippetDiagramType;

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
     * Result of a language migration: the full rewritten snippet, a summary, and the notes naming
     * everything that could not be carried over. The notes are the honest part of the contract —
     * a migration that silently dropped a construct would be worse than one that refused.
     */
    public record LanguageMigration(String replacement, String summary, List<String> notes) {
        public LanguageMigration {
            replacement = replacement != null ? replacement : "";
            summary = summary != null ? summary.trim() : "";
            notes = notes != null
                ? notes.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList()
                : List.of();
        }

        public boolean isUsable() {
            return !replacement.isBlank();
        }
    }

    /**
     * Result of applying selected security findings: the full fixed snippet, an overall summary, and a
     * per-change list with anchors + reasons for the diff hover annotations.
     */
    public record SnippetSecurityFix(
            String replacement,
            String summary,
            List<SecurityChange> changes,
            List<String> implementedRequirements) {
        public SnippetSecurityFix {
            replacement = replacement != null ? replacement : "";
            summary = summary != null ? summary.trim() : "";
            changes = changes != null ? List.copyOf(changes) : List.of();
            implementedRequirements = implementedRequirements != null
                ? implementedRequirements.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList()
                : List.of();
        }

        public SnippetSecurityFix(String replacement, String summary, List<SecurityChange> changes) {
            this(replacement, summary, changes, List.of());
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

    /**
     * One generated diagram. {@code rejectionReason} is set on an unusable result and names why
     * the AI answer was thrown away — for the log and for the notice shown next to the local
     * fallback diagram; it is {@code null} on a usable diagram.
     */
    public record MermaidDiagram(
        String title,
        String mermaid,
        List<SnippetDiagramSupport.SourceCodeReference> codeReferences,
        SnippetDiagramType diagramType,
        String rejectionReason) {

        public MermaidDiagram(String title, String mermaid) {
            this(title, mermaid, List.of(), SnippetDiagramType.LOGICAL_STRUCTURE, null);
        }

        public MermaidDiagram(
            String title,
            String mermaid,
            List<SnippetDiagramSupport.SourceCodeReference> codeReferences) {

            this(title, mermaid, codeReferences, SnippetDiagramType.LOGICAL_STRUCTURE, null);
        }

        public MermaidDiagram(
            String title,
            String mermaid,
            List<SnippetDiagramSupport.SourceCodeReference> codeReferences,
            SnippetDiagramType diagramType) {

            this(title, mermaid, codeReferences, diagramType, null);
        }

        /** An unusable result that carries the reason the AI answer was rejected. */
        public static MermaidDiagram rejected(SnippetDiagramType diagramType, String reason) {
            return new MermaidDiagram("", "", List.of(), diagramType,
                reason != null && !reason.isBlank() ? reason : "The AI answer contained no usable diagram.");
        }

        public MermaidDiagram {
            diagramType = diagramType != null ? diagramType : SnippetDiagramType.LOGICAL_STRUCTURE;
            rejectionReason = rejectionReason != null && !rejectionReason.isBlank() ? rejectionReason.trim() : null;
            title = title != null && !title.isBlank() ? title.trim() : "Snippet structure";
            String rawMermaid = mermaid != null ? mermaid : "";
            mermaid = rawMermaid.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= SnippetDiagramSupport.MAX_MERMAID_SOURCE_BYTES
                    ? SnippetDiagramSupport.normalizeMermaid(rawMermaid)
                    : "";
            codeReferences = SnippetTypedDiagramSupport.filterValidSourceReferences(
                diagramType, mermaid, codeReferences);
        }

        public boolean isUsable() {
            return SnippetTypedDiagramSupport.validate(diagramType, mermaid).valid();
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
        String replacement = joinedStringArray(object, "replacementLines");
        if (replacement == null) {
            replacement = firstString(object, "replacement", "code", "content", "text");
        }
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
     * {@link #parseCodeImprovement} and additionally reads optional per-region {@code changes} plus the
     * compact {@code implementedRequirements} checklist used by Full code analysis hardening.
     */
    /** One region a stage replaces: the original lines {@code startLine..endLine} (1-based, inclusive). */
    public record SnippetEdit(int startLine, int endLine, List<String> replacementLines) {
        public SnippetEdit {
            replacementLines = replacementLines != null ? List.copyOf(replacementLines) : List.of();
        }
    }

    /**
     * The edit-mode apply answer for a long snippet: the changed regions instead of the whole
     * script, plus the same summary, change annotations and requirement checklist as the
     * whole-file answer. Applied locally by {@link #applySnippetEdits}.
     */
    public record SnippetEdits(
        List<SnippetEdit> edits,
        String summary,
        List<SecurityChange> changes,
        List<String> implementedRequirements,
        boolean recoveredFromBrokenJson) {

        public SnippetEdits(
            List<SnippetEdit> edits,
            String summary,
            List<SecurityChange> changes,
            List<String> implementedRequirements) {
            this(edits, summary, changes, implementedRequirements, false);
        }

        public SnippetEdits {
            edits = edits != null ? List.copyOf(edits) : List.of();
            summary = summary != null ? summary.trim() : "";
            changes = changes != null ? List.copyOf(changes) : List.of();
            implementedRequirements = implementedRequirements != null ? List.copyOf(implementedRequirements) : List.of();
        }

        public boolean isUsable() {
            return !edits.isEmpty();
        }
    }

    public static SnippetEdits parseSnippetEdits(String responseText) {
        JsonObject object = parseJsonObject(responseText);
        if (object == null || !object.has("edits")) {
            // A quote in a code line that the model did not escape breaks the whole object; the
            // edits are still there, one string per line, and are read back without a second request.
            SnippetEdits recovered = recoverEditsFromBrokenJson(responseText);
            if (recovered != null) {
                return recovered;
            }
        }
        if (object == null) {
            return new SnippetEdits(List.of(), "", List.of(), List.of());
        }
        List<SnippetEdit> edits = new ArrayList<>();
        JsonArray array = firstArray(object, "edits", "patches", "replacements");
        if (array != null) {
            for (JsonElement element : array) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject edit = element.getAsJsonObject();
                Integer startLine = firstInt(edit, "startLine", "start", "from", "line");
                Integer endLine = firstInt(edit, "endLine", "end", "to");
                if (startLine == null) {
                    continue;
                }
                // Source lines, not identifiers: indentation, blank lines and a repeated `fi` or `}`
                // are the code. An entry that is not a string makes the region untrustworthy; it is
                // left to the repair round rather than guessed.
                List<String> replacementLines = verbatimStringArray(
                    firstArray(edit, "replacementLines", "lines", "replacement", "newLines"));
                if (replacementLines == null) {
                    continue;
                }
                edits.add(new SnippetEdit(startLine, endLine != null ? endLine : startLine, replacementLines));
            }
        }
        return new SnippetEdits(
            edits,
            firstString(object, "summary", "description"),
            parseSecurityChanges(object),
            parseStringArray(object, "implementedRequirements"));
    }

    private static final Pattern EDIT_START_LINE = Pattern.compile("\"startLine\"\\s*:\\s*(\\d+)");
    private static final Pattern EDIT_END_LINE = Pattern.compile("\"endLine\"\\s*:\\s*(\\d+)");
    private static final Pattern EDIT_LINES_OPEN = Pattern.compile("\"replacementLines\"\\s*:\\s*\\[");
    private static final Pattern EDIT_FINDING = Pattern.compile("\"finding\"\\s*:\\s*\"([^\"]{1,64})\"");

    /**
     * Reads the edits out of an answer whose JSON does not parse. Only the newline-anchored form
     * is read — every entry of {@code replacementLines} on its own line — because there the
     * boundary of a string is the line end and an unescaped inner quote cannot mislead it; a
     * compact single-line array would need a guess, and a guess in code is worse than the retry.
     * All or nothing: the number of recovered edits must equal the number of {@code "startLine"}
     * keys in the answer, so a partly readable answer still goes the retry route instead of
     * silently applying half of what the model meant.
     *
     * @return the recovered edits, or {@code null} when the answer cannot be read this way
     */
    static SnippetEdits recoverEditsFromBrokenJson(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            return null;
        }
        String text = AiResponseSanitizer.sanitizeForDisplay(responseText);
        Matcher starts = EDIT_START_LINE.matcher(text);
        List<Integer> startOffsets = new ArrayList<>();
        List<Integer> startLines = new ArrayList<>();
        while (starts.find()) {
            startOffsets.add(starts.start());
            startLines.add(Integer.parseInt(starts.group(1)));
        }
        if (startOffsets.isEmpty()) {
            return null;
        }
        List<SnippetEdit> edits = new ArrayList<>();
        for (int index = 0; index < startOffsets.size(); index++) {
            int from = startOffsets.get(index);
            int to = index + 1 < startOffsets.size() ? startOffsets.get(index + 1) : text.length();
            String block = text.substring(from, to);
            Matcher open = EDIT_LINES_OPEN.matcher(block);
            if (!open.find()) {
                return null;
            }
            List<String> lines = readNewlineAnchoredStringArray(block, open.end());
            if (lines == null) {
                return null;
            }
            // endLine may sit before or after startLine (nothing enforces key order on an endpoint
            // that ignores the schema), so it is looked for from this object's own brace up to the
            // next object's — and there must be exactly one, or the range is not trusted.
            int objectStart = Math.max(0, text.lastIndexOf('{', from));
            int nextBrace = index + 1 < startOffsets.size() ? text.lastIndexOf('{', startOffsets.get(index + 1)) : -1;
            int objectEnd = nextBrace > from ? nextBrace : to;
            Matcher end = EDIT_END_LINE.matcher(text.substring(objectStart, objectEnd));
            if (!end.find()) {
                return null;
            }
            int endLine = Integer.parseInt(end.group(1));
            if (end.find()) {
                return null;
            }
            edits.add(new SnippetEdit(startLines.get(index), endLine, lines));
        }
        List<SecurityChange> changes = new ArrayList<>();
        Matcher finding = EDIT_FINDING.matcher(text);
        while (finding.find()) {
            String anchor = extractLenientJsonStringField(text.substring(finding.end()), "anchor");
            String reason = extractLenientJsonStringField(text.substring(finding.end()), "reason");
            changes.add(new SecurityChange(finding.group(1), anchor != null ? anchor : "", reason != null ? reason : ""));
        }
        String summary = extractLenientJsonStringField(text, "summary");
        return new SnippetEdits(
            edits, summary != null ? summary : "", changes,
            parseLenientStringArrayField(text, "implementedRequirements"), true);
    }

    /**
     * Reads {@code ["…", "…"]} where every entry sits on its own line, tolerating an unescaped
     * quote inside an entry. Returns {@code null} for a compact array or one that never closes.
     */
    private static List<String> readNewlineAnchoredStringArray(String block, int offset) {
        String rest = block.substring(offset);
        String[] lines = rest.split("\\R", -1);
        if (lines.length < 2 || !lines[0].isBlank()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].strip();
            if (line.equals("]") || line.equals("],") || line.startsWith("]")) {
                return values;
            }
            if (line.isEmpty()) {
                continue;
            }
            if (!line.startsWith("\"")) {
                return null;
            }
            String core = line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
            if (core.length() < 2 || !core.endsWith("\"")) {
                // A lone quote: a string the model broke across lines. Fail closed into the retry.
                return null;
            }
            String body = core.substring(1, core.length() - 1);
            StringBuilder value = new StringBuilder(body.length());
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c == '\\' && i + 1 < body.length()) {
                    if (body.charAt(i + 1) == 'u' && i + 5 < body.length()) {
                        try {
                            value.append((char) Integer.parseInt(body.substring(i + 2, i + 6), 16));
                            i += 5;
                            continue;
                        } catch (NumberFormatException notUnicode) {
                            // Not a JSON unicode escape: kept literally below, like any other token.
                        }
                    }
                    appendJsonEscaped(value, body.charAt(++i));
                } else {
                    value.append(c);
                }
            }
            values.add(value.toString());
        }
        return null;
    }

    /**
     * The outcome of applying edit regions: the text, the edits that went in, and a note for
     * every edit that could not be trusted and was left out.
     */
    public record AppliedEdits(String replacement, List<SnippetEdit> applied, List<String> dropped) {
        public AppliedEdits {
            applied = applied != null ? List.copyOf(applied) : List.of();
            dropped = dropped != null ? List.copyOf(dropped) : List.of();
        }
    }

    /**
     * Applies edit-mode regions to the snippet they were written against, strictly: {@code null}
     * as soon as one range cannot be trusted. Kept for callers that want all or nothing; the apply
     * stage uses {@link #applySnippetEditsLeniently}.
     */
    public static String applySnippetEdits(String original, List<SnippetEdit> edits) {
        AppliedEdits applied = applySnippetEditsLeniently(original, edits);
        return applied != null && applied.dropped().isEmpty() ? applied.replacement() : null;
    }

    /**
     * Applies what can be trusted and reports the rest. A reversed range is read the right way
     * round and an end one line past the last (the off-by-one of a trailing newline) is clamped;
     * an edit that starts outside the snippet, reaches further past its end, or overlaps an
     * earlier one is dropped with a note — one bad range used to fail the whole stage, and the
     * repair round that follows can ask for what a dropped edit meant to do. Returns {@code null}
     * when nothing at all could be applied.
     */
    public static AppliedEdits applySnippetEditsLeniently(String original, List<SnippetEdit> edits) {
        if (original == null || edits == null || edits.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>(List.of(original.split("\\R", -1)));
        List<SnippetEdit> ordered = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        // The same edit listed twice is a stutter, not a conflict (seen live: identical ranges
        // and lines repeated within one answer).
        for (SnippetEdit edit : new java.util.LinkedHashSet<>(edits)) {
            int start = Math.min(edit.startLine(), edit.endLine());
            int end = Math.max(edit.startLine(), edit.endLine());
            if (start < 1 || start > lines.size() || end > lines.size() + 1) {
                dropped.add(start + "-" + end + " lies outside the " + lines.size() + "-line snippet");
                continue;
            }
            ordered.add(new SnippetEdit(start, Math.min(end, lines.size()), edit.replacementLines()));
        }
        ordered.sort(java.util.Comparator.comparingInt(SnippetEdit::startLine));
        List<SnippetEdit> accepted = new ArrayList<>();
        int previousEnd = 0;
        for (SnippetEdit edit : ordered) {
            if (edit.startLine() <= previousEnd) {
                dropped.add(edit.startLine() + "-" + edit.endLine() + " overlaps an earlier edit");
                continue;
            }
            accepted.add(edit);
            previousEnd = edit.endLine();
        }
        if (accepted.isEmpty()) {
            return null;
        }
        for (int index = accepted.size() - 1; index >= 0; index--) {
            SnippetEdit edit = accepted.get(index);
            lines.subList(edit.startLine() - 1, edit.endLine()).clear();
            lines.addAll(edit.startLine() - 1, edit.replacementLines());
        }
        return new AppliedEdits(String.join("\n", lines), accepted, dropped);
    }

    public static SnippetSecurityFix parseSecurityFix(String responseText) {
        CodeImprovement improvement = parseCodeImprovement(responseText, true);
        JsonObject object = parseJsonObject(responseText);
        List<SecurityChange> changes = object != null ? parseSecurityChanges(object) : List.of();
        List<String> implementedRequirements = object != null
            ? parseStringArray(object, "implementedRequirements")
            : parseLenientStringArrayField(responseText, "implementedRequirements");
        return new SnippetSecurityFix(
            improvement.replacement(), improvement.summary(), changes, implementedRequirements);
    }

    /**
     * Parses the language-migration response. Reuses the robust {@code replacementLines}/summary
     * extraction of {@link #parseCodeImprovement} and additionally reads the {@code notes} array.
     */
    public static LanguageMigration parseLanguageMigration(String responseText) {
        CodeImprovement improvement = parseCodeImprovement(responseText, true);
        JsonObject object = parseJsonObject(responseText);
        List<String> notes = List.of();
        for (String field : new String[] {"notes", "limitations", "warnings"}) {
            notes = object != null
                ? parseStringArray(object, field)
                : parseLenientStringArrayField(responseText, field);
            if (!notes.isEmpty()) {
                break;
            }
        }
        return new LanguageMigration(improvement.replacement(), improvement.summary(), notes);
    }

    /**
     * True when a whole-script migration result must NOT be applied.
     *
     * <p>{@link #isDegenerateFullReplacement} is deliberately not reused here: a migration
     * legitimately rewrites nearly every line, so its line-level similarity checks would reject
     * correct results. What still cannot be right is an empty answer or one that lost most of the
     * program — a rewrite in another language changes the wording, not the amount of work done.
     */
    public static boolean isDegenerateMigration(String original, String replacement) {
        String candidate = replacement != null ? replacement.strip() : "";
        if (candidate.isEmpty()) {
            return true;
        }
        if (introducesOmittedCodeMarker(original != null ? original.strip() : "", candidate)) {
            return true;
        }
        long originalLines = countCodeLines(original);
        if (originalLines < 5) {
            return false;
        }
        return countCodeLines(replacement) * 10 < originalLines * 4;
    }

    private static long countCodeLines(String content) {
        return content == null ? 0 : content.lines().filter(line -> !line.isBlank()).count();
    }

    private static final Pattern BARE_TOKEN_PATTERN =
        Pattern.compile("[$@%&]?\\{?[A-Za-z_][A-Za-z0-9_]*}?");

    /**
     * Phrases models commonly insert instead of reproducing unchanged source. Kept deliberately
     * conservative: a match is rejected only when the replacement introduces a new matching line.
     */
    private static final Pattern OMITTED_CODE_MARKER_PATTERN = Pattern.compile(
        "(?iu)(?:"
            + "\\b(?:rest|remainder|remaining|existing|original|other|previous|following)\\b.{0,120}"
            + "\\b(?:unchanged|omitted|elided|skipped|same\\s+as\\s+(?:above|before|original))\\b"
            + "|\\b(?:code|functions?|methods?|implementation|content|lines?|script|snippet)\\b.{0,120}"
            + "\\b(?:unchanged|omitted|elided|skipped|same\\s+as\\s+(?:above|before|original))\\b"
            + "|\\b(?:omitted|elided|skipped)\\s+for\\s+brevity\\b"
            + "|\\b(?:rest|restlicher|restliche|restliches|übriger|übrige|übriges|verbleibender|"
            + "verbleibende|verbleibendes|bestehender|bestehende|bestehendes|ursprünglicher|"
            + "ursprüngliche|ursprüngliches)\\b.{0,120}\\b(?:unverändert|ausgelassen|weggelassen)\\b"
            + "|\\b(?:code|funktionen?|methoden?|implementierung|inhalt|zeilen?|skript)\\b.{0,120}"
            + "\\b(?:unverändert|ausgelassen|weggelassen)\\b"
            + ")");

    /**
     * True when a whole-snippet {@code replacement} is incomplete and must NOT be applied — applying it
     * would silently wipe the user's code. Catches bare tokens such as {@code "$code"}, substantial
     * multi-line bodies collapsing to a tiny single line, and newly introduced omission comments such as
     * {@code # ... (rest of original functions unchanged) ...}. An omission marker already present in the
     * original is allowed when it is preserved unchanged.
     */
    public static boolean isDegenerateFullReplacement(String original, String replacement) {
        String current = original != null ? original.strip() : "";
        String candidate = replacement != null ? replacement.strip() : "";
        if (candidate.isEmpty()) {
            return true;
        }
        // An explicit omission marker is never a complete replacement, even for a short source.
        if (introducesOmittedCodeMarker(current, candidate)) {
            return true;
        }
        if (current.length() < 40) {
            return false;
        }
        // A single bare identifier/variable ("$code", "${code}", "code", "@arr", …) is never a snippet body.
        if (BARE_TOKEN_PATTERN.matcher(candidate).matches()) {
            return true;
        }
        // A multi-line body collapsing to a tiny single line is degenerate too.
        boolean originalMultiLine = current.lines().filter(line -> !line.isBlank()).count() >= 3;
        boolean tinySingleLine = !candidate.contains("\n") && candidate.length() < Math.max(24, current.length() / 8);
        if (originalMultiLine && tinySingleLine) {
            return true;
        }
        // Structured-output grammars can still yield a syntactically valid JSON object whose
        // replacement is only a short, multi-line fragment. Reject a simultaneous character and
        // line-count collapse so such a fragment never advances to a later stage or the preview.
        long originalLines = current.lines().filter(line -> !line.isBlank()).count();
        long candidateLines = candidate.lines().filter(line -> !line.isBlank()).count();
        boolean substantialOriginal = current.length() >= 400 && originalLines >= 12;
        boolean collapsedCharacters = candidate.length() < current.length() / 5;
        boolean collapsedLines = candidateLines < Math.max(3, originalLines / 4);
        return substantialOriginal && collapsedCharacters && collapsedLines;
    }

    private static boolean introducesOmittedCodeMarker(String original, String replacement) {
        List<String> originalMarkers = new ArrayList<>();
        original.lines()
            .map(SnippetAiResponseSupport::normalizedOmittedCodeMarker)
            .filter(marker -> marker != null)
            .forEach(originalMarkers::add);
        for (String line : replacement.lines().toList()) {
            String marker = normalizedOmittedCodeMarker(line);
            if (marker != null && !originalMarkers.remove(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizedOmittedCodeMarker(String line) {
        if (line == null || !OMITTED_CODE_MARKER_PATTERN.matcher(line).find()) {
            return null;
        }
        return line.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
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

    private static List<String> parseStringArray(JsonObject object, String fieldName) {
        JsonArray array = firstArray(object, fieldName);
        return parseStringArray(array);
    }

    private static List<String> parseLenientStringArrayField(String text, String fieldName) {
        if (text == null || text.isBlank() || fieldName == null || fieldName.isBlank()) {
            return List.of();
        }
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\\[").matcher(text);
        if (!matcher.find()) {
            return List.of();
        }
        String arrayText = balancedSpan(text, matcher.end() - 1, '[', ']');
        JsonElement parsed = parseJsonElement(arrayText);
        return parsed != null && parsed.isJsonArray()
            ? parseStringArray(parsed.getAsJsonArray())
            : List.of();
    }

    /**
     * Source lines exactly as returned — no trim, no blank filter, no de-duplication — or
     * {@code null} when an entry is not a string. A missing array is an empty list: a deletion.
     */
    private static List<String> verbatimStringArray(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                return null;
            }
            values.add(element.getAsString());
        }
        return List.copyOf(values);
    }

    private static List<String> parseStringArray(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String value = element.getAsString().trim();
                if (!value.isBlank() && !values.contains(value)) {
                    values.add(value);
                }
            }
        }
        return List.copyOf(values);
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

    /**
     * Whether an answer carries the diagram object's shape — a JSON object with a non-blank
     * {@code mermaid} value. Deliberately weaker than {@link #parseMermaidDiagram}: the transport
     * uses it to tell "the endpoint ignored the response format and answered in prose" from "the
     * model returned the object but drew a diagram korTTY rejects", and only the first of those is
     * something a differently phrased request could fix.
     */
    public static boolean carriesDiagramJson(String responseText) {
        JsonObject object = parseJsonObject(responseText);
        if (object == null) {
            return false;
        }
        String mermaid = firstString(object, "mermaid");
        return mermaid != null && !mermaid.isBlank();
    }

    public static MermaidDiagram parseMermaidDiagram(String responseText) {
        return parseMermaidDiagram(SnippetDiagramType.LOGICAL_STRUCTURE, responseText);
    }

    public static MermaidDiagram parseMermaidDiagram(SnippetDiagramType diagramType, String responseText) {
        return parseMermaidDiagram(diagramType, responseText, null);
    }

    /**
     * Parses one diagram answer. {@code snippetContent} sizes the flowchart's node cap; without
     * it the base cap of a short snippet applies. An unusable result names its rejection reason.
     */
    public static MermaidDiagram parseMermaidDiagram(
        SnippetDiagramType diagramType, String responseText, String snippetContent) {

        SnippetDiagramType type = diagramType != null ? diagramType : SnippetDiagramType.LOGICAL_STRUCTURE;
        JsonObject object = parseJsonObject(responseText);
        if (object == null) {
            return MermaidDiagram.rejected(type, "The AI answer contained no JSON object ("
                + (responseText != null ? responseText.length() : 0) + " characters).");
        }
        String rawMermaid = SnippetDiagramSupport.stripPresentationStatements(firstString(object, "mermaid"));
        if (rawMermaid == null || rawMermaid.isBlank()) {
            // When the whole envelope fails to parse, the scanner settles on the first object that
            // does — typically a codeReferences entry. Naming that "no mermaid value" sent a
            // reader after the wrong defect.
            boolean envelopeLost = !object.has("mermaid") && responseText.contains("\"mermaid\"");
            return MermaidDiagram.rejected(type, envelopeLost
                ? "The AI answer's JSON envelope could not be parsed, probably because quotes inside "
                    + "the diagram are not escaped."
                : "The AI answer JSON has no 'mermaid' value.");
        }
        MermaidDiagram diagram = new MermaidDiagram(
            firstString(object, "title", "name"),
            rawMermaid,
            parseDiagramCodeReferences(object),
            type);
        if (!diagram.isUsable()) {
            // The constructor blanks an oversized source, so the reason comes from the raw value.
            SnippetDiagramSupport.MermaidValidation basic = SnippetTypedDiagramSupport.validate(type, rawMermaid);
            return MermaidDiagram.rejected(type,
                basic.valid() ? "The AI answer contained no usable diagram." : basic.message());
        }
        SnippetDiagramSupport.MermaidValidation generated =
            SnippetTypedDiagramSupport.validateGenerated(type, diagram.mermaid(), snippetContent);
        return generated.valid() ? diagram : MermaidDiagram.rejected(type, generated.message());
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
            String nodeId = firstString(reference, "nodeId");
            String label = firstString(reference, "label");
            Integer startLine = firstInt(reference, "startLine", "lineStart", "line");
            Integer endLine = firstInt(reference, "endLine", "lineEnd");
            if (endLine == null) {
                endLine = startLine;
            }
            if (nodeId != null && !nodeId.isBlank() && label != null && !label.isBlank()
                && startLine != null && endLine != null) {
                parsedReferences.add(new SnippetDiagramSupport.SourceCodeReference(
                    nodeId, label, startLine, endLine));
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
            String nestedReplacement = joinedStringArray(nestedObject, "replacementLines");
            if (nestedReplacement == null) {
                nestedReplacement = firstString(nestedObject, "replacement", "code", "content", "text");
            }
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

    private static String joinedStringArray(JsonObject object, String fieldName) {
        if (object == null || fieldName == null || !object.has(fieldName)
                || !object.get(fieldName).isJsonArray()) {
            return null;
        }
        JsonArray lines = object.getAsJsonArray(fieldName);
        List<String> values = new ArrayList<>(lines.size());
        for (JsonElement line : lines) {
            if (line == null || line.isJsonNull() || !line.isJsonPrimitive()
                    || !line.getAsJsonPrimitive().isString()) {
                return null;
            }
            values.add(line.getAsString());
        }
        return String.join("\n", values);
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
            // Preserve non-JSON source-code escapes such as Perl/Python regex tokens \s and \d.
            default -> builder.append('\\').append(escaped);
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
