package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Post-translation terminology corrections for one target language.
 *
 * <p>Machine translation does not know the product's vocabulary. Left alone it renders the guide
 * as "Handbuch" while the application's own menu says "Anleitung", turns Mermaid into
 * "Meerjungfrau" and a GitHub issue into a "Problem". The build-time Markdown pipeline has
 * corrected this for a long time; this is the same table, applied to the runtime HTML path so a
 * locally translated guide agrees with the interface around it.
 *
 * <p>The table lives in {@code /i18n/glossary/<lang>.json} and is read by both sides —
 * {@code scripts/translate_docs.py} loads the very same file. Two copies would drift, and the
 * drift would be invisible: each pipeline would look right on its own.
 *
 * <p>Order is significant and preserved from the file: a longer term must be replaced before a
 * shorter one it contains, otherwise "Benutzerhandbuch" becomes "BenutzerAnleitung".
 */
public final class TranslationGlossary {

    private static final Logger logger = LoggerFactory.getLogger(TranslationGlossary.class);
    private static final String RESOURCE_ROOT = "/i18n/glossary/";

    /** Which rendering the corrections are applied to; entries may target one or both. */
    public enum Scope {
        HTML("html"),
        MARKDOWN("markdown");

        private final String key;

        Scope(String key) {
            this.key = key;
        }
    }

    private record Replacement(String from, String to, boolean exact) {
    }

    private final String language;
    private final List<Replacement> replacements;

    private TranslationGlossary(String language, List<Replacement> replacements) {
        this.language = language;
        this.replacements = replacements;
    }

    /**
     * Loads the glossary for {@code langCode}, or an empty one when the language has no table.
     * Most languages have none — an empty glossary is the normal case, not a failure.
     */
    public static TranslationGlossary forLanguage(String langCode, Scope scope) {
        String normalized = langCode == null ? "" : langCode.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return new TranslationGlossary("", List.of());
        }
        String resource = RESOURCE_ROOT + normalized + ".json";
        try (InputStream in = TranslationGlossary.class.getResourceAsStream(resource)) {
            if (in == null) {
                return new TranslationGlossary(normalized, List.of());
            }
            JsonObject root = JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray rows = root.getAsJsonArray("replacements");
            List<Replacement> parsed = new ArrayList<>();
            if (rows != null) {
                for (JsonElement element : rows) {
                    JsonObject row = element.getAsJsonObject();
                    if (!row.has("from") || !row.has("to")) {
                        continue;
                    }
                    String entryScope = row.has("scope") ? row.get("scope").getAsString() : "any";
                    if (!"any".equals(entryScope) && !scope.key.equals(entryScope)) {
                        continue;
                    }
                    parsed.add(new Replacement(row.get("from").getAsString(),
                        row.get("to").getAsString(),
                        row.has("match") && "exact".equals(row.get("match").getAsString())));
                }
            }
            return new TranslationGlossary(normalized, List.copyOf(parsed));
        } catch (Exception e) {
            // A malformed glossary must not sink a translation run; untouched terminology is a
            // far smaller problem than no translated guide at all.
            logger.warn("Ignoring unreadable translation glossary {}", resource, e);
            return new TranslationGlossary(normalized, List.of());
        }
    }

    public String language() {
        return language;
    }

    public boolean isEmpty() {
        return replacements.isEmpty();
    }

    public int size() {
        return replacements.size();
    }

    /** Applies every correction in file order. Returns {@code text} unchanged when empty. */
    public String apply(String text) {
        if (text == null || text.isEmpty() || replacements.isEmpty()) {
            return text;
        }
        String out = text;
        for (Replacement replacement : replacements) {
            out = replacement.exact()
                ? applyExact(out, replacement)
                : out.replace(replacement.from(), replacement.to());
        }
        return out;
    }

    /**
     * Replaces only when the whole segment is the term, ignoring surrounding whitespace. A
     * settings-table label such as "Enabled" must become the adjective "Aktiviert" as a cell,
     * while the same word inside a sentence is a verb and has to stay untouched.
     */
    private static String applyExact(String text, Replacement replacement) {
        String stripped = text.strip();
        if (!stripped.equals(replacement.from())) {
            return text;
        }
        int start = text.indexOf(stripped);
        return text.substring(0, start) + replacement.to()
            + text.substring(start + stripped.length());
    }
}
