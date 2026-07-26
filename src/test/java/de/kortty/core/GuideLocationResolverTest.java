package de.kortty.core;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;

class GuideLocationResolverTest {

    Path tempDir;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-guide-resolver-test");
    }

    @AfterMethod
    void deleteTempDir() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    /** Writes a minimally complete generated tree: an entry page plus staged assets. */
    private void fakeGenerated(String lang) throws IOException {
        Path root = GuideLocationResolver.generatedRoot(tempDir).resolve(lang);
        Files.createDirectories(root.resolve("assets/stylesheets"));
        Files.writeString(root.resolve("index.html"), "<html lang=\"" + lang + "\"></html>",
            StandardCharsets.UTF_8);
        Files.writeString(root.resolve("assets/stylesheets/main.css"), "body{}",
            StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("features"));
        Files.writeString(root.resolve("features/connections.html"), "<html></html>",
            StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------- language

    @Test
    void bundledLanguagesResolveToThemselves() {
        assertThat(GuideLocationResolver.resolveLanguage(Locale.GERMAN, tempDir)).isEqualTo("de");
        assertThat(GuideLocationResolver.resolveLanguage(Locale.ENGLISH, tempDir)).isEqualTo("en");
    }

    @Test
    void aLanguageWithNeitherTreeFallsBackToEnglish() {
        assertThat(GuideLocationResolver.resolveLanguage(Locale.FRENCH, tempDir)).isEqualTo("en");
        assertThat(GuideLocationResolver.resolveLanguage(null, tempDir)).isEqualTo("en");
        assertThat(GuideLocationResolver.resolveLanguage(Locale.FRENCH, null)).isEqualTo("en");
    }

    @Test
    void aGeneratedLanguageIsUsedWhenNothingIsBundledForIt() throws IOException {
        fakeGenerated("fr");
        assertThat(GuideLocationResolver.resolveLanguage(Locale.FRENCH, tempDir)).isEqualTo("fr");
        assertThat(GuideLocationResolver.isGenerated("fr", tempDir)).isTrue();
    }

    /**
     * The bundled German guide is reviewed prose with a matching search index; a locally
     * generated one is neither, so it must not quietly take over.
     */
    @Test
    void aBundledLanguageWinsOverAGeneratedOneOfTheSameCode() throws IOException {
        fakeGenerated("de");
        assertThat(GuideLocationResolver.resolveLanguage(Locale.GERMAN, tempDir)).isEqualTo("de");
        assertThat(GuideLocationResolver.pageUrl("de", "index.html", tempDir))
            .doesNotContain(tempDir.toUri().getPath());
    }

    @Test
    void anIncompleteGeneratedTreeIsNotOffered() throws IOException {
        // Pages but no staged assets: loading this would render as unstyled text, which looks
        // like a broken app rather than an unfinished translation.
        Path root = GuideLocationResolver.generatedRoot(tempDir).resolve("it");
        Files.createDirectories(root);
        Files.writeString(root.resolve("index.html"), "<html></html>", StandardCharsets.UTF_8);

        assertThat(GuideLocationResolver.isGenerated("it", tempDir)).isFalse();
        assertThat(GuideLocationResolver.resolveLanguage(Locale.ITALIAN, tempDir)).isEqualTo("en");
    }

    // ----------------------------------------------------------------- urls

    @Test
    void bundledPagesResolveToTheClasspath() {
        String url = GuideLocationResolver.pageUrl("en", "features/connections.html", tempDir);
        assertThat(url).isNotNull();
        assertThat(url).contains("guide/en/features/connections.html");
    }

    @Test
    void generatedPagesResolveToAFileUrl() throws IOException {
        fakeGenerated("fr");
        String url = GuideLocationResolver.pageUrl("fr", "features/connections.html", tempDir);
        assertThat(url).startsWith("file:");
        assertThat(url).contains("/guide/fr/features/connections.html");
    }

    @Test
    void aPageMissingFromAGeneratedTreeFallsBackToEnglish() throws IOException {
        fakeGenerated("fr");
        String url = GuideLocationResolver.pageUrl("fr", "reference/keyboard-shortcuts.html", tempDir);
        assertThat(url).isNotNull();
        assertThat(url).contains("guide/en/reference/keyboard-shortcuts.html");
    }

    @Test
    void anUnknownPageResolvesToNothing() {
        assertThat(GuideLocationResolver.pageUrl("en", "no/such/page.html", tempDir)).isNull();
    }

    /** Citations come from the AI answer panel, so the path is untrusted input. */
    @Test
    void traversalAndAbsolutePathsAreRefused() {
        for (String path : List.of("../../../etc/passwd", "/etc/passwd", "a/../../b.html",
                "\\windows\\system32", "file:/etc/passwd")) {
            assertThat(GuideLocationResolver.pageUrl("en", path, tempDir)).isNull();
        }
    }

    @Test
    void aMalformedLanguageCodeCannotEscapeTheGuideTree() {
        assertThat(GuideLocationResolver.isBundled("../../etc")).isFalse();
        assertThat(GuideLocationResolver.isGenerated("../..", tempDir)).isFalse();
        // Falls back to English rather than building a path from the bad code.
        assertThat(GuideLocationResolver.pageUrl("../..", "index.html", tempDir))
            .contains("guide/en/index.html");
    }

    // ------------------------------------------------------------ discovery

    @Test
    void generatedLanguagesAreListedWithoutBundledOrIncompleteOnes() throws IOException {
        fakeGenerated("fr");
        fakeGenerated("it");
        fakeGenerated("de"); // bundled — must not appear
        Files.createDirectories(GuideLocationResolver.generatedRoot(tempDir).resolve("pt"));

        assertThat(GuideLocationResolver.availableGeneratedLanguages(tempDir))
            .containsExactly("fr", "it").inOrder();
    }

    // -------------------------------------------------- missing-page fallback

    @Test
    void aLinkIntoANotYetTranslatedPageFallsBackToTheBundledOne() throws IOException {
        fakeGenerated("fr");
        String missing = GuideLocationResolver.generatedRoot(tempDir)
            .resolve("fr/reference/keyboard-shortcuts.html").toUri().toString();

        String fallback = GuideLocationResolver.fallbackForMissingGeneratedPage(missing, tempDir);

        assertThat(fallback).isNotNull();
        assertThat(fallback).contains("guide/en/reference/keyboard-shortcuts.html");
    }

    /** The theme's language switcher emits ../de/index.html, which no generated tree contains. */
    @Test
    void theLanguageSwitcherReachesTheBundledTree() throws IOException {
        fakeGenerated("fr");
        String switcherTarget = GuideLocationResolver.generatedRoot(tempDir)
            .resolve("de/index.html").toUri().toString();

        String fallback =
            GuideLocationResolver.fallbackForMissingGeneratedPage(switcherTarget, tempDir);

        assertThat(fallback).isNotNull();
        assertThat(fallback).contains("guide/de/index.html");
        // The bundled tree, not the config directory. Not asserted via the URL scheme: from a
        // build directory the classpath itself is a file: URL, and only a packaged run yields jar:.
        assertThat(fallback).doesNotContain(tempDir.toUri().getPath());
    }

    @Test
    void aPageThatExistsIsNotRedirected() throws IOException {
        fakeGenerated("fr");
        String existing = GuideLocationResolver.generatedRoot(tempDir)
            .resolve("fr/index.html").toUri().toString();
        assertThat(GuideLocationResolver.fallbackForMissingGeneratedPage(existing, tempDir)).isNull();
    }

    @Test
    void locationsOutsideTheGeneratedTreeAreNotRedirected() {
        assertThat(GuideLocationResolver.fallbackForMissingGeneratedPage(
            "jar:file:/app.jar!/guide/en/index.html", tempDir)).isNull();
        assertThat(GuideLocationResolver.fallbackForMissingGeneratedPage(
            "https://example.com/x.html", tempDir)).isNull();
        assertThat(GuideLocationResolver.fallbackForMissingGeneratedPage(
            Path.of(System.getProperty("java.io.tmpdir"), "nope.html").toUri().toString(),
            tempDir)).isNull();
        assertThat(GuideLocationResolver.fallbackForMissingGeneratedPage(null, tempDir)).isNull();
    }

    @Test
    void aFragmentOnAMissingPageStillResolves() throws IOException {
        fakeGenerated("fr");
        String missing = GuideLocationResolver.generatedRoot(tempDir)
            .resolve("fr/reference/keyboard-shortcuts.html").toUri() + "#general";
        assertThat(GuideLocationResolver.fallbackForMissingGeneratedPage(missing, tempDir))
            .contains("guide/en/reference/keyboard-shortcuts.html");
    }

    @Test
    void listingIsEmptyWhenNothingWasGenerated() {
        assertThat(GuideLocationResolver.availableGeneratedLanguages(tempDir)).isEmpty();
        assertThat(GuideLocationResolver.availableGeneratedLanguages(null)).isEmpty();
    }
}
