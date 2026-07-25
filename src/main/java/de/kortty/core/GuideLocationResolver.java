package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Decides which guide tree a page is loaded from: the two languages bundled in the jar, or a
 * language {@link GuideTranslationGenerator} produced into the config directory.
 *
 * <p>Kept out of the viewer so it can be tested without a JavaFX toolkit — the rules are more
 * involved than they look, because the two trees live behind different URL schemes and a page
 * loaded over {@code file:} cannot reach assets inside the {@code jar:}.
 *
 * <p>A bundled language always wins over a generated one of the same code. The bundled German
 * guide is reviewed prose with a matching search index; a locally generated one would be neither,
 * so regenerating German must not quietly replace it.
 */
public final class GuideLocationResolver {

    private static final Logger logger = LoggerFactory.getLogger(GuideLocationResolver.class);
    private static final String CLASSPATH_ROOT = "/guide/";
    private static final String FALLBACK_LANG = "en";
    private static final String ENTRY_PAGE = "index.html";

    private GuideLocationResolver() {
    }

    /** Root of the generated language trees inside {@code configDirectory}. */
    public static Path generatedRoot(Path configDirectory) {
        return configDirectory.resolve("guide");
    }

    /**
     * Language whose guide should be shown for {@code locale}: a bundled tree if one exists,
     * otherwise a generated one, otherwise English.
     */
    public static String resolveLanguage(Locale locale, Path configDirectory) {
        String code = locale != null && locale.getLanguage() != null && !locale.getLanguage().isBlank()
            ? locale.getLanguage().toLowerCase(Locale.ROOT)
            : FALLBACK_LANG;
        if (isBundled(code)) {
            return code;
        }
        return isGenerated(code, configDirectory) ? code : FALLBACK_LANG;
    }

    public static boolean isBundled(String lang) {
        return safe(lang) != null
            && GuideLocationResolver.class.getResource(CLASSPATH_ROOT + lang + "/" + ENTRY_PAGE) != null;
    }

    /** True when a complete generated tree is present: an entry page plus its staged assets. */
    public static boolean isGenerated(String lang, Path configDirectory) {
        String safe = safe(lang);
        if (safe == null || configDirectory == null) {
            return false;
        }
        Path root = generatedRoot(configDirectory).resolve(safe);
        // The assets check matters: without them the page loads as unstyled text, which looks
        // like a broken application rather than a guide that is still being generated.
        return Files.isRegularFile(root.resolve(ENTRY_PAGE)) && Files.isDirectory(root.resolve("assets"));
    }

    /** Generated languages ready to be shown, sorted; excludes any that shadow a bundled tree. */
    public static List<String> availableGeneratedLanguages(Path configDirectory) {
        Path root = configDirectory != null ? generatedRoot(configDirectory) : null;
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            List<String> languages = new ArrayList<>();
            entries.filter(Files::isDirectory).forEach(path -> {
                String lang = path.getFileName().toString();
                if (!isBundled(lang) && isGenerated(lang, configDirectory)) {
                    languages.add(lang);
                }
            });
            languages.sort(String::compareTo);
            return List.copyOf(languages);
        } catch (IOException e) {
            logger.debug("Could not list generated guide languages in {}", root, e);
            return List.of();
        }
    }

    /**
     * URL for {@code path} (e.g. {@code features/connections.html}) in {@code lang}, preferring
     * the bundled tree, then a generated one, then bundled English. Null when nothing matches.
     */
    public static String pageUrl(String lang, String path, Path configDirectory) {
        String safePath = safeRelativePath(path);
        if (safePath == null) {
            logger.warn("Refusing to resolve a guide path outside the guide tree: {}", path);
            return null;
        }
        String safeLang = safe(lang);
        if (safeLang != null) {
            URL bundled = GuideLocationResolver.class.getResource(CLASSPATH_ROOT + safeLang + "/" + safePath);
            if (bundled != null) {
                return bundled.toExternalForm();
            }
            String generated = generatedUrl(safeLang, safePath, configDirectory);
            if (generated != null) {
                return generated;
            }
        }
        URL english = GuideLocationResolver.class.getResource(CLASSPATH_ROOT + FALLBACK_LANG + "/" + safePath);
        return english != null ? english.toExternalForm() : null;
    }

    /**
     * Where to go instead when a generated tree is asked for a page it does not contain.
     *
     * <p>Two everyday cases land here. Translation runs page by page, so a link into a not yet
     * translated page is normal rather than exceptional; and the theme's language switcher emits
     * a relative link into the sibling language tree ({@code ../de/index.html}), which inside the
     * config directory points at nothing. Both would otherwise show an empty window.
     *
     * @return a replacement URL, or null if {@code fileUrl} is not a missing generated page
     */
    public static String fallbackForMissingGeneratedPage(String fileUrl, Path configDirectory) {
        if (fileUrl == null || !fileUrl.startsWith("file:") || configDirectory == null) {
            return null;
        }
        Path root = generatedRoot(configDirectory).toAbsolutePath().normalize();
        Path requested;
        try {
            requested = Path.of(java.net.URI.create(stripFragment(fileUrl))).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
        if (!requested.startsWith(root) || Files.exists(requested)) {
            return null;
        }
        Path relative = root.relativize(requested);
        if (relative.getNameCount() < 2) {
            return null;
        }
        String lang = relative.getName(0).toString();
        String page = relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/');
        String replacement = pageUrl(lang, page, configDirectory);
        if (replacement != null) {
            logger.debug("Generated guide has no {} for {}; using {}", page, lang, replacement);
        }
        return replacement;
    }

    private static String stripFragment(String url) {
        int hash = url.indexOf('#');
        return hash >= 0 ? url.substring(0, hash) : url;
    }

    private static String generatedUrl(String lang, String path, Path configDirectory) {
        if (configDirectory == null) {
            return null;
        }
        Path page = generatedRoot(configDirectory).resolve(lang).resolve(path);
        if (!Files.isRegularFile(page)) {
            return null;
        }
        try {
            return page.toUri().toURL().toExternalForm();
        } catch (MalformedURLException e) {
            logger.debug("Could not build a URL for generated guide page {}", page, e);
            return null;
        }
    }

    /** Language codes are used to build paths, so anything but plain letters/dashes is rejected. */
    private static String safe(String lang) {
        return lang != null && lang.matches("[a-zA-Z][a-zA-Z0-9_-]{0,15}")
            ? lang.toLowerCase(Locale.ROOT)
            : null;
    }

    /**
     * Guide citations arrive from the AI answer panel, so a path is treated as untrusted input:
     * traversal or an absolute path would let a citation address files outside the guide.
     */
    private static String safeRelativePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\")
            || path.contains("..") || path.contains(":")) {
            return null;
        }
        return path.replace('\\', '/');
    }
}
