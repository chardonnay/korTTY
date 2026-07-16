package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Uses the configured local/text AI profile for dynamic interface translation. */
public final class LocalAiTranslationService implements TranslationService {

    private static final Gson GSON = new Gson();
    private final AiPromptService service;

    public LocalAiTranslationService(AiPromptService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String translate(String text, String sourceLang, String targetLang) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> translated = translateBatch(List.of(text), sourceLang, targetLang);
        return translated != null && translated.size() == 1 ? translated.getFirst() : null;
    }

    @Override
    public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        JsonObject request = new JsonObject();
        request.addProperty("sourceLanguage", normalizeLanguage(sourceLang, "en"));
        request.addProperty("targetLanguage", normalizeLanguage(targetLang, "en"));
        JsonArray values = new JsonArray();
        texts.forEach(values::add);
        request.add("texts", values);
        String system = "Translate every input string faithfully. Preserve placeholders, punctuation, keyboard labels, and order. "
            + "Return exactly one JSON object with a translations array containing exactly one string per input. "
            + "Do not add explanations.";
        try {
            AiExecutionResult result = service.executeJsonPrompt(
                system, GSON.toJson(request), AiPromptExecutionScope.TEXT);
            return parseTranslations(result != null ? result.content() : null, texts.size());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean testConnection() {
        List<String> translated = translateBatch(List.of("Hello"), "en", "de");
        return translated != null && translated.size() == 1 && !translated.getFirst().isBlank();
    }

    static List<String> parseTranslations(String response, int expectedCount) {
        String json = stripCodeFence(AiResponseSanitizer.sanitizeForDisplay(response));
        if (json.isBlank()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray translations = root.getAsJsonArray("translations");
            if (translations == null || translations.size() != expectedCount) {
                return null;
            }
            List<String> values = new ArrayList<>(translations.size());
            for (int i = 0; i < translations.size(); i++) {
                if (!translations.get(i).isJsonPrimitive()
                    || !translations.get(i).getAsJsonPrimitive().isString()) {
                    return null;
                }
                values.add(translations.get(i).getAsString());
            }
            return List.copyOf(values);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String stripCodeFence(String value) {
        String trimmed = value != null ? value.trim() : "";
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int closing = trimmed.lastIndexOf("```");
        return firstNewline >= 0 && closing > firstNewline
            ? trimmed.substring(firstNewline + 1, closing).trim()
            : trimmed;
    }

    private static String normalizeLanguage(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }
}
