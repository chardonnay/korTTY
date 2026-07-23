package de.kortty.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;

/**
 * Parses and normalizes AI-generated snippet metadata.
 */
public final class AiSnippetMetadataSupport {

    private AiSnippetMetadataSupport() {
    }

    /**
     * @param language     the detected code language (bash, python, …)
     * @param textLanguage the ISO 639-1 code of the natural language used in the snippet's comments
     *                     and printed output, or {@code null} when the script carries no readable text
     */
    public record SuggestedSnippetMetadata(
        String fileName, String description, String language, String textLanguage) {
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
                normalizedLanguage,
                normalizeTextLanguage(getString(root, "textLanguage")));
        }
        return new SuggestedSnippetMetadata(
            SnippetLanguageSupport.sanitizeFileName(null, normalizedFallbackLanguage),
            normalizeDescription(responseText),
            normalizedFallbackLanguage,
            null);
    }

    /**
     * Reduces the model's answer to a bare ISO 639-1 code. Models answer this field with anything from
     * {@code "de"} through {@code "de-DE"} to {@code "German"}, and with filler like {@code "unknown"}
     * or {@code "none"} when a script prints no human-readable text at all — all of which must not end
     * up selected as a language.
     */
    public static String normalizeTextLanguage(String proposedTextLanguage) {
        if (proposedTextLanguage == null) {
            return null;
        }
        String candidate = proposedTextLanguage.trim().toLowerCase(Locale.ROOT);
        int separator = candidate.indexOf(candidate.contains("-") ? '-' : '_');
        if (separator > 0) {
            candidate = candidate.substring(0, separator);
        }
        if (candidate.length() > 2) {
            String fromDisplayName = languageCodeForDisplayName(candidate);
            candidate = fromDisplayName != null ? fromDisplayName : "";
        }
        if (candidate.length() != 2 || !candidate.chars().allMatch(Character::isLetter)) {
            return null;
        }
        return candidate;
    }

    /** Maps an English or endonym language name ("German", "Deutsch") to its ISO 639-1 code. */
    private static String languageCodeForDisplayName(String displayName) {
        for (String isoLanguage : Locale.getISOLanguages()) {
            Locale locale = Locale.forLanguageTag(isoLanguage);
            if (displayName.equalsIgnoreCase(locale.getDisplayLanguage(Locale.ENGLISH))
                || displayName.equalsIgnoreCase(locale.getDisplayLanguage(locale))) {
                return isoLanguage;
            }
        }
        return null;
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
