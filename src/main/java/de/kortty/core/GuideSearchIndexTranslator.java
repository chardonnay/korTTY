package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rebuilds the guide's search index for a translated language.
 *
 * <p>Without this the feature is half-done: a fully translated guide whose search still answers
 * in English is worse than no translation, because the reader cannot find the page they are
 * looking at.
 *
 * <p>Nothing here calls the model. The index holds 548k characters of title and text — as much
 * as the pages themselves — so translating it directly would double a run that already takes
 * hours. Instead each entry is re-derived from the page that was just translated: an index entry
 * is exactly one section of one page, and sections are delimited by headings whose {@code id}
 * the translation deliberately leaves in English, so they can still be found. Measured against
 * the real corpus, all 557 section entries reconstruct from their page section.
 */
public final class GuideSearchIndexTranslator {

    private static final Logger logger = LoggerFactory.getLogger(GuideSearchIndexTranslator.class);
    private static final String SOURCE_INDEX = "/guide/en/search/search_index.json";
    private static final String LUNR_PACK = "assets/javascripts/lunr/min/lunr.%s.min.js";

    private static final Pattern ARTICLE =
        Pattern.compile("<article\\b[^>]*>(.*)</article>", Pattern.DOTALL);
    private static final Pattern HEADING =
        Pattern.compile("<h([1-6])\\b[^>]*\\bid=\"([^\"]+)\"[^>]*>", Pattern.DOTALL);
    private static final Pattern HEADERLINK =
        Pattern.compile("<a\\b[^>]*class=\"[^\"]*headerlink[^\"]*\"[^>]*>.*?</a>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>", Pattern.DOTALL);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private GuideSearchIndexTranslator() {
    }

    public record Result(int translated, int keptEnglish) {
    }

    /**
     * Writes {@code <languageRoot>/search/search_index.json} (and its {@code search_index.js}
     * offline wrapper) from the pages under
     * {@code languageRoot}. Entries whose page has not been translated yet keep their English
     * text, so a partially translated guide still has a working — if mixed — search.
     */
    public static Result rebuild(Path languageRoot, String targetLang) throws IOException {
        JsonObject root;
        try (InputStream in = GuideSearchIndexTranslator.class.getResourceAsStream(SOURCE_INDEX)) {
            if (in == null) {
                throw new IOException("Bundled search index not found: " + SOURCE_INDEX);
            }
            root = JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonArray docs = root.getAsJsonArray("docs");
        if (docs == null) {
            throw new IOException("Bundled search index has no docs array");
        }

        Map<String, String> articles = new HashMap<>();
        int translated = 0;
        int kept = 0;
        for (JsonElement element : docs) {
            JsonObject doc = element.getAsJsonObject();
            String location = doc.has("location") ? doc.get("location").getAsString() : "";
            int hash = location.indexOf('#');
            String page = hash >= 0 ? location.substring(0, hash) : location;
            String anchor = hash >= 0 ? location.substring(hash + 1) : null;

            String article = articles.computeIfAbsent(page, key -> readArticle(languageRoot, key));
            if (article == null) {
                kept++;
                continue;
            }
            Section section = sectionOf(article, anchor);
            if (section == null) {
                kept++;
                continue;
            }
            doc.addProperty("title", section.title());
            doc.addProperty("text", section.text());
            translated++;
        }

        JsonObject config = root.getAsJsonObject("config");
        if (config != null) {
            applyLanguage(config, languageRoot, targetLang);
        }

        Path target = languageRoot.resolve("search").resolve("search_index.json");
        Files.createDirectories(target.getParent());
        String json = root.toString();
        Files.writeString(target, json, StandardCharsets.UTF_8);
        // Generated guides load over file:, where mkdocs-material's offline search reads the
        // script wrapper (search_index.js -> global __index) instead of fetching the JSON.
        // Without a rebuilt wrapper the in-page search of a translated guide serves English.
        Files.writeString(target.resolveSibling("search_index.js"),
            "var __index = " + json, StandardCharsets.UTF_8);
        logger.info("Rebuilt guide search index for {}: {} entry/entries translated, {} kept English",
            targetLang, translated, kept);
        return new Result(translated, kept);
    }

    /**
     * Points lunr at the target language when a stemmer for it ships, and otherwise leaves the
     * index on English. A missing stemmer breaks the in-page search outright, which is a worse
     * outcome than searching translated text with English stemming rules.
     */
    private static void applyLanguage(JsonObject config, Path languageRoot, String targetLang) {
        String lang = targetLang.toLowerCase(Locale.ROOT);
        boolean supported = "en".equals(lang)
            || Files.isRegularFile(languageRoot.resolve(String.format(LUNR_PACK, lang)));
        if (!supported) {
            logger.info("No lunr stemmer for {}; the search index keeps English stemming", lang);
            return;
        }
        JsonArray languages = new JsonArray();
        languages.add(lang);
        config.add("lang", languages);
    }

    private static String readArticle(Path languageRoot, String page) {
        Path file = languageRoot.resolve(page);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Matcher matcher = ARTICLE.matcher(Files.readString(file, StandardCharsets.UTF_8));
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not read translated page {}", file, e);
            return null;
        }
    }

    private record Section(String title, String text) {
    }

    /**
     * The slice of the article an index entry covers: from its heading to the next heading, or
     * the whole article for the page-level entry.
     */
    private static Section sectionOf(String article, String anchor) {
        Matcher matcher = HEADING.matcher(article);
        record Head(int start, int contentStart, String id) {
        }
        java.util.List<Head> heads = new java.util.ArrayList<>();
        while (matcher.find()) {
            heads.add(new Head(matcher.start(), matcher.end(), matcher.group(2)));
        }
        if (anchor == null) {
            String title = heads.isEmpty() ? "" : headingText(article, heads.getFirst().contentStart());
            return new Section(title, normalize(article));
        }
        for (int i = 0; i < heads.size(); i++) {
            if (!heads.get(i).id().equals(anchor)) {
                continue;
            }
            int end = i + 1 < heads.size() ? heads.get(i + 1).start() : article.length();
            String body = article.substring(heads.get(i).contentStart(), end);
            // Drop the heading's own line from the body; mkdocs indexes the section under it.
            int close = body.indexOf("</h");
            String text = close >= 0 ? body.substring(close) : body;
            return new Section(headingText(article, heads.get(i).contentStart()), normalize(text));
        }
        return null;
    }

    /** Visible text of a heading: its content up to {@code </h_>}, minus the ¶ permalink. */
    private static String headingText(String article, int contentStart) {
        int close = article.indexOf("</h", contentStart);
        String raw = close >= 0 ? article.substring(contentStart, close) : "";
        return normalize(HEADERLINK.matcher(raw).replaceAll(" "));
    }

    /** Strips markup and collapses whitespace, matching what the index stores. */
    private static String normalize(String html) {
        String text = TAG.matcher(html).replaceAll(" ");
        text = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }
}
