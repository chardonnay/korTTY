package de.kortty.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Parses and normalizes AI-generated snippet metadata.
 */
public final class AiSnippetMetadataSupport {

    private AiSnippetMetadataSupport() {
    }

    public record SuggestedSnippetMetadata(String fileName, String description, String language) {
    }

    public static SuggestedSnippetMetadata parseMetadataResponse(String responseText, String fallbackLanguage, String content) {
        String normalizedFallbackLanguage = SnippetLanguageSupport.detectSnippetLanguage(fallbackLanguage, content);
        JsonObject root = parseFirstJsonObject(responseText);
        if (root != null) {
            String fileName = getString(root, "fileName");
            String description = getString(root, "description");
            String language = getString(root, "language");
            String normalizedLanguage = normalizeMetadataLanguage(language, normalizedFallbackLanguage, content);
            return new SuggestedSnippetMetadata(
                SnippetLanguageSupport.sanitizeFileName(fileName, normalizedLanguage),
                normalizeDescription(description),
                normalizedLanguage);
        }
        return new SuggestedSnippetMetadata(
            SnippetLanguageSupport.sanitizeFileName(null, normalizedFallbackLanguage),
            normalizeDescription(responseText),
            normalizedFallbackLanguage);
    }

    public static String normalizeDescription(String description) {
        if (description == null) {
            return "";
        }
        return description
            .replace("```", "")
            .replace("\r", "\n")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static JsonObject parseFirstJsonObject(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            return null;
        }
        for (int start = responseText.indexOf('{'); start >= 0; start = responseText.indexOf('{', start + 1)) {
            String candidate = extractFirstJsonObject(responseText, start);
            if (candidate != null) {
                try {
                    return JsonParser.parseString(candidate).getAsJsonObject();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static String extractFirstJsonObject(String text, int startIndex) {
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;
        for (int i = startIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaping) {
                escaping = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escaping = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                depth++;
                continue;
            }
            if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(startIndex, i + 1);
                }
            }
        }
        return null;
    }

    private static String getString(JsonObject root, String propertyName) {
        if (root == null || propertyName == null || !root.has(propertyName) || root.get(propertyName).isJsonNull()) {
            return null;
        }
        try {
            return root.get(propertyName).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeMetadataLanguage(String proposedLanguage, String fallbackLanguage, String content) {
        String normalized = SnippetLanguageSupport.normalizeSnippetLanguage(proposedLanguage);
        if (normalized != null && !"plain".equals(normalized)) {
            return normalized;
        }
        return SnippetLanguageSupport.detectSnippetLanguage(fallbackLanguage, content);
    }
}
