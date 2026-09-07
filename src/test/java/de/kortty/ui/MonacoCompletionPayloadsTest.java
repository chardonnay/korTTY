package de.kortty.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.core.SnippetAiResponseSupport.CompletionSuggestion;
import de.kortty.core.SnippetCompletionSupport;
import de.kortty.core.SnippetCompletionSupport.Candidate;
import de.kortty.core.SnippetCompletionSupport.CandidateKind;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

class MonacoCompletionPayloadsTest {

    private static final Map<CandidateKind, String> KIND_LABELS = kindLabels();

    @Test
    void listPayloadCarriesTheFieldNamesCompletionJsParses() {
        Candidate array = new Candidate(
            "\"${ARR[@]}\"", "\"${ARR[@]}\"", "ARR \"${ARR[@]}\"", CandidateKind.ARRAY, false, "0_000", 12, "");
        Candidate idiom = new Candidate(
            "$(command)", "$(${1:command})", "$(command)", CandidateKind.IDIOM, true, "2_001", 12, "");

        JsonObject payload = parse(MonacoCompletionPayloads.list(7L, "local", List.of(array, idiom), KIND_LABELS));

        assertThat(payload.get("id").getAsLong()).isEqualTo(7L);
        assertThat(payload.get("source").getAsString()).isEqualTo("local");
        JsonArray items = payload.getAsJsonArray("items");
        assertThat(items.size()).isEqualTo(2);
        JsonObject first = items.get(0).getAsJsonObject();
        assertThat(first.get("label").getAsString()).isEqualTo("\"${ARR[@]}\"");
        assertThat(first.get("insertText").getAsString()).isEqualTo("\"${ARR[@]}\"");
        assertThat(first.get("filterText").getAsString()).isEqualTo("ARR \"${ARR[@]}\"");
        assertThat(first.get("sortText").getAsString()).isEqualTo("0_000");
        assertThat(first.get("kind").getAsString()).isEqualTo("array");
        assertThat(first.get("snippet").getAsBoolean()).isFalse();
        assertThat(first.get("replaceStart").getAsInt()).isEqualTo(12);
        assertThat(first.get("detail").getAsString()).isEqualTo("Array");
        assertThat(first.has("documentation")).isFalse();
        JsonObject second = items.get(1).getAsJsonObject();
        // Idiom placeholders stay Monaco snippet syntax; the flag tells the page to insert as snippet.
        assertThat(second.get("insertText").getAsString()).isEqualTo("$(${1:command})");
        assertThat(second.get("snippet").getAsBoolean()).isTrue();
        assertThat(second.get("kind").getAsString()).isEqualTo("idiom");
        assertThat(second.get("detail").getAsString()).isEqualTo("Idiom");
    }

    @Test
    void unknownSourceIsSentAsLocalAndBlankCandidatesAreDropped() {
        Candidate blank = new Candidate("x", "", "", CandidateKind.TEXT, false, "", 0, "");
        Candidate text = new Candidate("name", "name", "name", CandidateKind.TEXT, false, "3_000", 4, "");

        JsonObject payload = parse(MonacoCompletionPayloads.list(1L, "bogus", Arrays.asList(blank, null, text), KIND_LABELS));

        assertThat(payload.get("source").getAsString()).isEqualTo("local");
        assertThat(payload.getAsJsonArray("items").size()).isEqualTo(1);
        assertThat(parse(MonacoCompletionPayloads.list(1L, "ai", null, KIND_LABELS)).getAsJsonArray("items").size())
            .isEqualTo(0);
    }

    @Test
    void aiRowsKeepTheSparkleLabelAndPutTheSummaryIntoDocumentation() {
        SnippetCompletionSupport.Context ctx = SnippetCompletionSupport.classify("bash", "for x in ", 9);
        List<Candidate> ai = SnippetCompletionSupport.aiCandidates(
            ctx, 9, List.of(),
            List.of(
                new CompletionSuggestion("\"$@\"; do\n  echo \"$x\"\ndone", "Iterates over the arguments."),
                new CompletionSuggestion("*.txt; do\n  echo \"$x\"\ndone", "")),
            KIND_LABELS.get(CandidateKind.AI));

        JsonObject payload = parse(MonacoCompletionPayloads.list(9L, "ai", ai, KIND_LABELS));

        assertThat(payload.get("source").getAsString()).isEqualTo("ai");
        JsonArray items = payload.getAsJsonArray("items");
        assertThat(items.size()).isEqualTo(2);
        JsonObject first = items.get(0).getAsJsonObject();
        assertThat(first.get("label").getAsString()).startsWith(SnippetCompletionSupport.AI_LABEL_PREFIX);
        assertThat(first.get("label").getAsString()).doesNotContain("\n");
        assertThat(first.get("kind").getAsString()).isEqualTo("ai");
        assertThat(first.get("snippet").getAsBoolean()).isFalse();
        assertThat(first.get("insertText").getAsString()).isEqualTo("\"$@\"; do\n  echo \"$x\"\ndone");
        assertThat(first.get("detail").getAsString()).isEqualTo("AI suggestion");
        assertThat(first.get("documentation").getAsString()).isEqualTo("Iterates over the arguments.");
        JsonObject second = items.get(1).getAsJsonObject();
        // No summary: Step 1 falls back to the AI label, which already sits in detail.
        assertThat(second.get("detail").getAsString()).isEqualTo("AI suggestion");
        assertThat(second.has("documentation")).isFalse();
    }

    @Test
    void quotesNewlinesAndLineSeparatorsSurviveAsEscapedJson() {
        String insertText = "printf '%s\\n' \"$value\" \nnext\r\nline";
        Candidate candidate = new Candidate(
            "printf \"$value\"", insertText, "printf", CandidateKind.FUNCTION, false, "1_000", 0, "doc \"quoted\"");

        String json = MonacoCompletionPayloads.list(3L, "local", List.of(candidate), KIND_LABELS);

        assertThat(json).doesNotContain(" ");
        assertThat(json).contains("\\u2028");
        assertThat(json).doesNotContain("\n");
        JsonObject item = parse(json).getAsJsonArray("items").get(0).getAsJsonObject();
        assertThat(item.get("insertText").getAsString()).isEqualTo(insertText);
        assertThat(item.get("label").getAsString()).isEqualTo("printf \"$value\"");
        assertThat(item.get("documentation").getAsString()).isEqualTo("doc \"quoted\"");
    }

    @Test
    void ghostPayloadNormalizesLineEndingsAndDropsBlanksAndDuplicates() {
        JsonObject payload = parse(MonacoCompletionPayloads.ghost(1L << 40, 27, 128, Arrays.asList(
            new CompletionSuggestion("qw(strftime floor ceil);\r\n", "posix"),
            new CompletionSuggestion("   ", ""),
            null,
            new CompletionSuggestion("qw(strftime floor ceil);\n", "dup"),
            new CompletionSuggestion("qw(strftime);", ""))));

        assertThat(payload.get("id").getAsLong()).isEqualTo(1L << 40);
        assertThat(payload.get("caretOffset").getAsInt()).isEqualTo(27);
        assertThat(payload.get("textLength").getAsInt()).isEqualTo(128);
        JsonArray suggestions = payload.getAsJsonArray("suggestions");
        assertThat(suggestions.size()).isEqualTo(2);
        assertThat(suggestions.get(0).getAsString()).isEqualTo("qw(strftime floor ceil);\n");
        assertThat(suggestions.get(1).getAsString()).isEqualTo("qw(strftime);");
        assertThat(parse(MonacoCompletionPayloads.ghost(2L, 0, 0, null)).getAsJsonArray("suggestions").size())
            .isEqualTo(0);
    }

    @Test
    void missingKindLabelLeavesDetailOutAndLabelFallsBackToTheFirstInsertLine() {
        Candidate candidate = new Candidate("", "first\nsecond", "", CandidateKind.VARIABLE, false, "", 0, "");

        JsonObject item = parse(MonacoCompletionPayloads.list(1L, "local", List.of(candidate), Map.of()))
            .getAsJsonArray("items").get(0).getAsJsonObject();

        assertThat(item.get("label").getAsString()).isEqualTo("first");
        assertThat(item.has("detail")).isFalse();
        assertThat(item.has("filterText")).isFalse();
        assertThat(item.has("sortText")).isFalse();
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static Map<CandidateKind, String> kindLabels() {
        Map<CandidateKind, String> labels = new EnumMap<>(CandidateKind.class);
        labels.put(CandidateKind.ARRAY, "Array");
        labels.put(CandidateKind.HASH, "Hash");
        labels.put(CandidateKind.VARIABLE, "Variable");
        labels.put(CandidateKind.FUNCTION, "Function");
        labels.put(CandidateKind.IDIOM, "Idiom");
        labels.put(CandidateKind.TEXT, "Text");
        labels.put(CandidateKind.AI, "AI suggestion");
        return labels;
    }
}
