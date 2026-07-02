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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only view of the bundled MkDocs search index
 * ({@code /guide/<lang>/search/search_index.json}) used as the retrieval corpus for the
 * guide's AI docs search. Each entry is one page section with its location (page path plus
 * localized anchor), section and page titles, and the section text with HTML stripped.
 *
 * <p>Locations are kept verbatim: anchors are language specific (the German site slugs are
 * localized), so citations must always quote a location from the index of the language that
 * is currently displayed, never construct one.</p>
 */
public final class GuideSearchIndex {

    private static final Logger logger = LoggerFactory.getLogger(GuideSearchIndex.class);

    // Optional so a missing bundled index is also cached and not re-probed on every question.
    private static final Map<String, Optional<GuideSearchIndex>> CACHE = new ConcurrentHashMap<>();

    /** One section of the guide; {@code plainText} has tags stripped and entities decoded. */
    public record Entry(String location, String pagePath, String anchor,
                        String title, String pageTitle, String plainText) {
    }

    private final String language;
    private final List<Entry> entries;

    private GuideSearchIndex(String language, List<Entry> entries) {
        this.language = language;
        this.entries = List.copyOf(entries);
    }

    /** Loads (and caches) the index for {@code lang}, or {@code null} if the build lacks it. */
    public static GuideSearchIndex load(String lang) {
        String normalized = lang != null && !lang.isBlank() ? lang : "en";
        return CACHE.computeIfAbsent(normalized, GuideSearchIndex::parseResource).orElse(null);
    }

    public String language() {
        return language;
    }

    public List<Entry> entries() {
        return entries;
    }

    private static Optional<GuideSearchIndex> parseResource(String lang) {
        String resourcePath = "/guide/" + lang + "/search/search_index.json";
        try (InputStream stream = GuideSearchIndex.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                logger.warn("Bundled guide search index not found: {}", resourcePath);
                return Optional.empty();
            }
            JsonObject root = JsonParser
                .parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .getAsJsonObject();
            JsonArray docs = root.getAsJsonArray("docs");
            if (docs == null) {
                logger.warn("Guide search index {} has no docs array", resourcePath);
                return Optional.empty();
            }

            // First pass: page-level titles (locations without an anchor) for the pageTitle field.
            Map<String, String> pageTitles = new HashMap<>();
            for (JsonElement element : docs) {
                JsonObject doc = element.getAsJsonObject();
                String location = stringMember(doc, "location");
                if (!location.contains("#")) {
                    pageTitles.put(location, cleanText(stringMember(doc, "title")));
                }
            }

            List<Entry> entries = new ArrayList<>(docs.size());
            for (JsonElement element : docs) {
                JsonObject doc = element.getAsJsonObject();
                String location = stringMember(doc, "location");
                String title = cleanText(stringMember(doc, "title"));
                String text = cleanText(stringMember(doc, "text"));
                if (location.isBlank() || (title.isBlank() && text.isBlank())) {
                    continue;
                }
                int hash = location.indexOf('#');
                String pagePath = hash >= 0 ? location.substring(0, hash) : location;
                String anchor = hash >= 0 ? location.substring(hash + 1) : null;
                String pageTitle = pageTitles.getOrDefault(pagePath, title);
                entries.add(new Entry(location, pagePath, anchor, title, pageTitle, text));
            }
            return Optional.of(new GuideSearchIndex(lang, entries));
        } catch (Exception e) {
            logger.warn("Could not parse guide search index {}", resourcePath, e);
            return Optional.empty();
        }
    }

    private static String stringMember(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /**
     * Strips HTML tags, then decodes the entities MkDocs emits. Order matters: real markup
     * arrives as tags while code samples arrive entity-escaped (e.g. {@code agent &lt;goal&gt;}),
     * so decoding after stripping preserves command examples for retrieval and prompts.
     */
    static String cleanText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = raw.replaceAll("<[^>]+>", " ");
        text = text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&");
        return text.replaceAll("\\s+", " ").trim();
    }
}
