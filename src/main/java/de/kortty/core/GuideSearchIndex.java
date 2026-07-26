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
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** Loads (and caches) the index for {@code lang}, or {@code null} if neither tree has one. */
    public static GuideSearchIndex load(String lang) {
        String normalized = lang != null && !lang.isBlank() ? lang : "en";
        return CACHE.computeIfAbsent(normalized, GuideSearchIndex::parseAnywhere).orElse(null);
    }

    /**
     * Drops the cached index for {@code lang}. Called after a language is translated: the cache
     * is process-lifetime and remembers misses too, so without this the AI docs search would keep
     * answering from the English index for the rest of the session.
     */
    public static void invalidate(String lang) {
        CACHE.remove(lang != null && !lang.isBlank() ? lang : "en");
    }

    /**
     * Uncached lookup against an explicit config directory. The cached {@link #load(String)} is
     * pinned to the application's own directory, which makes it useless to a caller that knows
     * where the tree is — a test, or code staging a translation elsewhere.
     */
    public static GuideSearchIndex load(String lang, Path configDirectory) {
        String normalized = lang != null && !lang.isBlank() ? lang : "en";
        return parseAnywhere(normalized, configDirectory).orElse(null);
    }

    private static Optional<GuideSearchIndex> parseAnywhere(String lang) {
        return parseAnywhere(lang, appConfigDirectory());
    }

    private static Path appConfigDirectory() {
        try {
            return de.kortty.KorTTYApplication.getConfigDirectory();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Bundled tree first, then a locally translated one under the config directory. */
    private static Optional<GuideSearchIndex> parseAnywhere(String lang, Path configDirectory) {
        Optional<GuideSearchIndex> bundled = parseResource(lang);
        if (bundled.isPresent() || configDirectory == null) {
            return bundled;
        }
        try {
            Path generated = GuideLocationResolver.generatedRoot(configDirectory)
                .resolve(lang).resolve("search").resolve("search_index.json");
            if (!Files.isRegularFile(generated)) {
                return Optional.empty();
            }
            try (InputStream stream = Files.newInputStream(generated)) {
                return parseStream(stream, generated.toString(), lang);
            }
        } catch (Exception e) {
            logger.debug("Could not read a generated guide search index for {}", lang, e);
            return Optional.empty();
        }
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
                // Not a warning: a locally translated language legitimately has no bundled index,
                // and parseAnywhere looks in the config directory next.
                logger.debug("No bundled guide search index at {}", resourcePath);
                return Optional.empty();
            }
            return parseStream(stream, resourcePath, lang);
        } catch (Exception e) {
            logger.warn("Could not parse guide search index {}", resourcePath, e);
            return Optional.empty();
        }
    }

    private static Optional<GuideSearchIndex> parseStream(InputStream stream, String label,
                                                          String lang) {
        String resourcePath = label;
        try {
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
