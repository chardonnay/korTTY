package de.kortty.ui;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.kortty.core.SnippetAiResponseSupport.CompletionSuggestion;
import de.kortty.core.SnippetCompletionSupport.Candidate;
import de.kortty.core.SnippetCompletionSupport.CandidateKind;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the JSON payloads {@code completion.js} parses in {@code pushCompletions} and
 * {@code pushGhostCompletions}. The field names are the protocol; the page turns them into Monaco
 * items itself (kind string to icon, {@code snippet} flag to insert-text rules, {@code detail} next
 * to the label, {@code documentation} in the details pane). Gson escapes quotes, control characters
 * and the U+2028/U+2029 line separators, so the strings survive {@code JSON.parse} on the page.
 */
final class MonacoCompletionPayloads {

    private static final Gson GSON = new Gson();

    private MonacoCompletionPayloads() {
    }

    /**
     * The list payload {@code {id, source, items:[...]}}. {@code source} is {@code "local"} (resolves
     * the open request) or {@code "ai"} (merged into the open list); anything else is sent as local.
     * {@code kindLabels} supplies the translated {@code detail} per kind; for AI rows that is the
     * "AI suggestion" label, and the model's summary — when Step 1 put one into
     * {@link Candidate#documentation()} — becomes the {@code documentation} of the row.
     */
    static String list(long id, String source, List<Candidate> items, Map<CandidateKind, String> kindLabels) {
        return list(id, source, items, kindLabels, false);
    }

    /**
     * Like {@link #list(long, String, List, Map)}; {@code aiPending} marks a local payload whose AI
     * rows are still on their way, so the page keeps the list loading when nothing local matched
     * instead of showing "No suggestions" until the AI answer (or its failure) is pushed.
     */
    static String list(long id, String source, List<Candidate> items, Map<CandidateKind, String> kindLabels,
                       boolean aiPending) {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", id);
        payload.addProperty("source", "ai".equals(source) ? "ai" : "local");
        if (aiPending && !"ai".equals(source)) {
            payload.addProperty("aiPending", true);
        }
        JsonArray array = new JsonArray();
        if (items != null) {
            for (Candidate candidate : items) {
                if (candidate == null || candidate.insertText().isEmpty()) {
                    continue;
                }
                array.add(item(candidate, kindLabels));
            }
        }
        payload.add("items", array);
        return GSON.toJson(payload);
    }

    /**
     * The ghost-text payload {@code {id, caretOffset, textLength, suggestions:[string...]}}. The page
     * only shows it while the model still has {@code textLength} characters and the caret still sits
     * at {@code caretOffset}. Line endings are normalized to LF, blanks and duplicates dropped.
     */
    static String ghost(long id, int caretOffset, int textLength, List<CompletionSuggestion> items) {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", id);
        payload.addProperty("caretOffset", caretOffset);
        payload.addProperty("textLength", textLength);
        JsonArray suggestions = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        if (items != null) {
            for (CompletionSuggestion suggestion : items) {
                if (suggestion == null) {
                    continue;
                }
                String text = normalizeEol(suggestion.insertText());
                if (text.isBlank() || !seen.add(text)) {
                    continue;
                }
                suggestions.add(text);
            }
        }
        payload.add("suggestions", suggestions);
        return GSON.toJson(payload);
    }

    private static JsonObject item(Candidate candidate, Map<CandidateKind, String> kindLabels) {
        JsonObject item = new JsonObject();
        item.addProperty("label", candidate.label().isEmpty() ? firstLine(candidate.insertText()) : candidate.label());
        item.addProperty("insertText", candidate.insertText());
        if (!candidate.filterText().isEmpty()) {
            item.addProperty("filterText", candidate.filterText());
        }
        if (!candidate.sortText().isEmpty()) {
            item.addProperty("sortText", candidate.sortText());
        }
        item.addProperty("kind", candidate.kind().name().toLowerCase(Locale.ROOT));
        item.addProperty("snippet", candidate.snippet());
        item.addProperty("replaceStart", candidate.replaceStart());
        String detail = kindLabels != null ? kindLabels.get(candidate.kind()) : null;
        if (detail != null && !detail.isBlank()) {
            item.addProperty("detail", detail.trim());
        }
        // Step 1 stores the model's summary in documentation and falls back to the AI detail label
        // when there is none; the label already sits in detail, so it is not repeated as documentation.
        String documentation = candidate.documentation().trim();
        if (!documentation.isEmpty() && !(detail != null && documentation.equals(detail.trim()))) {
            item.addProperty("documentation", documentation);
        }
        return item;
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        String line = end >= 0 ? text.substring(0, end) : text;
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static String normalizeEol(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
