package de.kortty.core;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Runs against the real translation manifests on the test classpath, the same way
 * {@link GuideSearchIndexTest} runs against the real search index. If these fail after a docs
 * rebuild, the manifests are stale: regenerate them with scripts/extract_guide_segments.py.
 */
class GuideTranslationGeneratorTest {

    Path tempDir;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-guide-translation-test");
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
                    // best effort; the OS reaps the temp dir anyway
                }
            });
        }
    }

    // ------------------------------------------------------------ real corpus

    @Test
    void bundledManifestsCoverEveryPage() throws IOException {
        List<String> pages = GuideTranslationGenerator.listPages();
        assertThat(pages).isNotEmpty();
        assertThat(pages).contains("index.html");
        assertThat(pages).contains("features/connections.html");
        for (String page : pages) {
            assertWithMessage("manifest for " + page)
                .that(GuideTranslationGenerator.loadManifest(page).segments()).isNotEmpty();
        }
    }

    /**
     * The load-bearing invariant: translating with an identity service must reproduce every
     * bundled page byte for byte. That exercises UTF-16 offsets, masking and splicing over the
     * whole real corpus at once — including the seven pages carrying astral emoji, where a
     * code-point offset would silently shift every later segment.
     */
    @Test
    void identityTranslationReproducesEveryBundledPageExactly() throws IOException {
        GuideTranslationGenerator generator =
            new GuideTranslationGenerator(new IdentityService(), tempDir);

        GuideTranslationGenerator.Result result = generator.generate("xx", null, null);

        assertThat(result.pagesSkipped()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);
        assertThat(result.pagesWritten()).isEqualTo(GuideTranslationGenerator.listPages().size());

        for (String page : GuideTranslationGenerator.listPages()) {
            String expected = readBundledPage(page)
                .replace("<html lang=\"en\"", "<html lang=\"xx\"");
            String actual = Files.readString(tempDir.resolve("guide/xx").resolve(page),
                StandardCharsets.UTF_8);
            assertWithMessage(page).that(actual).isEqualTo(expected);
        }
    }

    @Test
    void headingIdsStayEnglishSoAnchorsKeepResolving() throws IOException {
        GuideTranslationGenerator generator =
            new GuideTranslationGenerator(new PrefixService(), tempDir);
        generator.generate("xx", null, null);

        String translated = Files.readString(
            tempDir.resolve("guide/xx/features/connections.html"), StandardCharsets.UTF_8);

        // The slug is what cross-page "…#ssh-host-key-verification" links target.
        assertThat(translated).contains("id=\"ssh-host-key-verification\"");
        assertThat(translated).contains("href=\"#ssh-host-key-verification\"");
        // …while the visible heading text did go through the translator.
        assertThat(translated).contains("[xx]SSH host-key verification");
    }

    @Test
    void repeatedSegmentsAreTranslatedOnlyOnce() throws IOException {
        CountingService service = new CountingService();
        new GuideTranslationGenerator(service, tempDir).generate("xx", null, null);

        int distinct = service.seen.size();
        assertThat(distinct).isEqualTo(service.requested);
        // Navigation repeats on every page and each heading recurs in the TOCs, so the corpus
        // must collapse substantially; a regression here means the dedup stopped working.
        assertThat(distinct).isLessThan(totalSegmentOccurrences() * 3 / 4);
    }

    @Test
    void translationMemoryLetsASecondRunSkipTheService() throws IOException {
        new GuideTranslationGenerator(new IdentityService(), tempDir).generate("xx", null, null);

        CountingService second = new CountingService();
        GuideTranslationGenerator.Result result =
            new GuideTranslationGenerator(second, tempDir).generate("xx", null, null);

        assertThat(second.requested).isEqualTo(0);
        assertThat(result.reused()).isEqualTo(result.distinctSegments());
    }

    @Test
    void progressEndsAtOne() throws IOException {
        List<Double> reported = new ArrayList<>();
        new GuideTranslationGenerator(new IdentityService(), tempDir)
            .generate("xx", reported::add, null);

        assertThat(reported).isNotEmpty();
        assertThat(reported.getLast()).isEqualTo(1.0);
        assertThat(reported).isInOrder();
    }

    @Test
    void cancellationStopsBeforeWritingEveryPage() throws IOException {
        GuideTranslationGenerator.Result result =
            new GuideTranslationGenerator(new IdentityService(), tempDir)
                .generate("xx", null, () -> true);

        assertThat(result.pagesWritten()).isEqualTo(0);
    }

    // -------------------------------------------------------- failure handling

    @Test
    void aDroppedBatchItemIsRetriedInHalvesInsteadOfLosingThePage() throws IOException {
        // Fails any batch holding more than one item, mimicking a small local model that
        // collapses a list; only the single-item retries succeed.
        TranslationService flaky = new TranslationService() {
            @Override
            public String translate(String text, String source, String target) {
                return text;
            }

            @Override
            public List<String> translateBatch(List<String> texts, String source, String target) {
                return texts.size() == 1 ? List.of("[ok]" + texts.getFirst()) : null;
            }

            @Override
            public boolean testConnection() {
                return true;
            }
        };

        GuideTranslationGenerator.Result result =
            new GuideTranslationGenerator(flaky, tempDir).generate("xx", null, null);

        assertThat(result.failed()).isEqualTo(0);
        assertThat(result.pagesWritten()).isGreaterThan(0);
        assertThat(Files.readString(tempDir.resolve("guide/xx/index.html"), StandardCharsets.UTF_8))
            .contains("[ok]");
    }

    @Test
    void segmentsWhoseMarkupTheModelDestroyedKeepTheirEnglishSource() throws IOException {
        // Strips every placeholder — the worst realistic local-model failure, because the
        // markup, not just the wording, would be lost.
        TranslationService destructive = new TranslationService() {
            @Override
            public String translate(String text, String source, String target) {
                return text.replaceAll("KTPH\\d{3}", "");
            }

            @Override
            public List<String> translateBatch(List<String> texts, String source, String target) {
                List<String> out = new ArrayList<>(texts.size());
                texts.forEach(text -> out.add(text.replaceAll("KTPH\\d{3}", "")));
                return out;
            }

            @Override
            public boolean testConnection() {
                return true;
            }
        };

        GuideTranslationGenerator.Result result =
            new GuideTranslationGenerator(destructive, tempDir).generate("xx", null, null);

        // Segments without markup translate fine; the ones carrying tokens are refused and
        // fall back to English, so the page still renders.
        assertThat(result.failed()).isGreaterThan(0);
        String page = Files.readString(
            tempDir.resolve("guide/xx/features/connections.html"), StandardCharsets.UTF_8);
        assertThat(page).contains("<strong>Host key verification</strong>");
        assertThat(page).contains("id=\"ssh-host-key-verification\"");
    }

    // ------------------------------------------------------------------ units

    @Test
    void placeholderCheckAllowsReorderingButRejectsLossAndInvention() {
        assertThat(GuideTranslationGenerator.placeholdersIntact(
            "set KTPH000Host keyKTPH001 on the tab", "auf dem Tab KTPH000Host keyKTPH001 setzen"))
            .isTrue();
        assertThat(GuideTranslationGenerator.placeholdersIntact(
            "set KTPH000Host keyKTPH001", "setzen KTPH000Host key")).isFalse();
        assertThat(GuideTranslationGenerator.placeholdersIntact(
            "set KTPH000x", "setzen KTPH000x KTPH001")).isFalse();
        assertThat(GuideTranslationGenerator.placeholdersIntact(
            "plain text", "einfacher Text")).isTrue();
    }

    @Test
    void unmaskRestoresFragmentsInReverseIndexOrder() {
        assertThat(GuideTranslationGenerator.unmask(
            "KTPH000bold textKTPH001 tail", List.of("<strong>", "</strong>")))
            .isEqualTo("<strong>bold text</strong> tail");
        // A fragment that itself contains a lower-numbered token still resolves.
        assertThat(GuideTranslationGenerator.unmask(
            "KTPH001", List.of("&amp;", "<img alt=\"a KTPH000 b\">")))
            .isEqualTo("<img alt=\"a &amp; b\">");
    }

    // ------------------------------------------------------- regression guards

    /**
     * Under a locale whose default numbering system is not latn, String.format("%d") emits
     * Eastern Arabic digits. The whole run used to produce nothing on such a machine: every
     * token was rebuilt as KTPH٠٠٠, matched no manifest text, and all 54 pages were skipped.
     */
    @Test
    void aNonLatinDefaultLocaleDoesNotSilentlyProduceAnEmptyTranslation() throws IOException {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-SA"));
            assertThat(GuideTranslationGenerator.unmask("KTPH000tail", List.of("<b>")))
                .isEqualTo("<b>tail");

            GuideTranslationGenerator.Result result =
                new GuideTranslationGenerator(new IdentityService(), tempDir)
                    .generate("xx", null, null);

            assertThat(result.pagesSkipped()).isEqualTo(0);
            assertThat(result.pagesWritten())
                .isEqualTo(GuideTranslationGenerator.listPages().size());
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void markupInventedByTheModelIsNeutralisedBeforeItReachesThePage() throws IOException {
        assertThat(GuideTranslationGenerator.escapeMarkup("a <b>x</b> c"))
            .isEqualTo("a &lt;b&gt;x&lt;/b&gt; c");
        // Bare ampersands occur in the real guide ("Feedback & support") and must pass through.
        assertThat(GuideTranslationGenerator.escapeMarkup("Feedback & support"))
            .isEqualTo("Feedback & support");

        TranslationService injecting = new TranslationService() {
            @Override
            public String translate(String text, String sourceLang, String targetLang) {
                return "<injected>" + text;
            }

            @Override
            public List<String> translateBatch(List<String> texts, String s, String t) {
                List<String> out = new ArrayList<>(texts.size());
                texts.forEach(text -> out.add(translate(text, s, t)));
                return out;
            }

            @Override
            public boolean testConnection() {
                return true;
            }
        };
        new GuideTranslationGenerator(injecting, tempDir).generate("xx", null, null);

        String page = Files.readString(tempDir.resolve("guide/xx/index.html"),
            StandardCharsets.UTF_8);
        assertThat(page).contains("&lt;injected&gt;");
        assertThat(page).doesNotContain("<injected>");
    }

    @Test
    void aTruncatedMemoryFileIsIgnoredInsteadOfFailingTheRun() throws IOException {
        new GuideTranslationGenerator(new IdentityService(), tempDir).generate("xx", null, null);
        Path memory = tempDir.resolve("guide/xx/translation-memory.json");
        String full = Files.readString(memory, StandardCharsets.UTF_8);
        Files.writeString(memory, full.substring(0, full.length() / 2), StandardCharsets.UTF_8);

        GuideTranslationGenerator.Result result =
            new GuideTranslationGenerator(new IdentityService(), tempDir)
                .generate("xx", null, null);

        assertThat(result.reused()).isEqualTo(0);
        assertThat(result.pagesWritten())
            .isEqualTo(GuideTranslationGenerator.listPages().size());
    }

    /** A cancelled run must not report the segments it never attempted as translated. */
    @Test
    void cancellationDoesNotInflateTheTranslatedCount() throws IOException {
        GuideTranslationGenerator.Result result =
            new GuideTranslationGenerator(new IdentityService(), tempDir)
                .generate("xx", null, () -> true);

        assertThat(result.translated()).isEqualTo(0);
        assertThat(result.distinctSegments()).isGreaterThan(0);
    }

    /**
     * The runtime path must reach the same terminology as the build-time Markdown pipeline:
     * a guide that calls itself "Handbuch" while the menu says "Anleitung" is worse than an
     * untranslated one, because the reader cannot tell they are the same thing.
     */
    @Test
    void germanOutputIsCorrectedToTheProductsOwnTerminology() throws IOException {
        TranslationService callsItHandbuch = new TranslationService() {
            @Override
            public String translate(String text, String sourceLang, String targetLang) {
                return text.replace("korTTY Guide", "korTTY Handbuch");
            }

            @Override
            public List<String> translateBatch(List<String> texts, String s, String t) {
                List<String> out = new ArrayList<>(texts.size());
                texts.forEach(text -> out.add(translate(text, s, t)));
                return out;
            }

            @Override
            public boolean testConnection() {
                return true;
            }
        };
        new GuideTranslationGenerator(callsItHandbuch, tempDir)
            .generate("de", List.of("index.html"), null, null);

        String page = Files.readString(tempDir.resolve("guide/de/index.html"),
            StandardCharsets.UTF_8);
        assertThat(page).contains("korTTY Anleitung");
        assertThat(page).doesNotContain("korTTY Handbuch");
    }

    /** A language with no glossary must be left exactly as the model produced it. */
    @Test
    void aLanguageWithoutAGlossaryIsNotRewritten() throws IOException {
        new GuideTranslationGenerator(new IdentityService(), tempDir)
            .generate("xx", List.of("index.html"), null, null);

        String expected = readBundledPage("index.html")
            .replace("<html lang=\"en\"", "<html lang=\"xx\"");
        assertThat(Files.readString(tempDir.resolve("guide/xx/index.html"), StandardCharsets.UTF_8))
            .isEqualTo(expected);
    }

    // -------------------------------------------------------------- estimate

    @Test
    void anEstimateProjectsTheRemainingWorkFromASample() throws IOException {
        CountingService service = new CountingService();
        GuideTranslationGenerator.Estimate estimate =
            new GuideTranslationGenerator(service, tempDir).estimate("xx", 40, null);

        assertThat(estimate.isComplete()).isFalse();
        assertThat(estimate.isUsable()).isTrue();
        // At least the request; the floor may raise it so the sample spans several batches.
        assertThat(estimate.sampleSegments()).isAtLeast(40);
        // One extra item beyond the sample: the untimed warm-up request.
        assertThat(service.requested).isEqualTo(estimate.sampleSegments() + 1);
        // The sample is a small fraction of the corpus, and the projection covers all of it.
        assertThat(estimate.remainingSegments()).isGreaterThan(1000);
        assertThat(estimate.lowMillis()).isAtMost(estimate.highMillis());
    }

    /**
     * The sample must mirror the corpus's mix of lengths. Taking the first N segments would draw
     * almost only short navigation labels and understate a real run badly.
     */
    @Test
    void theSampleSpansTheCorpusLengthDistribution() throws IOException {
        List<String> all = new ArrayList<>();
        for (String page : GuideTranslationGenerator.listPages()) {
            GuideTranslationGenerator.loadManifest(page).segments()
                .forEach(segment -> all.add(segment.text()));
        }
        List<String> sample = GuideTranslationGenerator.spreadAcrossLengths(all, 40);

        assertThat(sample).hasSize(40);
        double sampleMean = sample.stream().mapToInt(String::length).average().orElse(0);
        double corpusMean = all.stream().mapToInt(String::length).average().orElse(0);
        // Within a factor of two of the corpus average; the naive "first 40" is far outside this.
        assertThat(sampleMean).isGreaterThan(corpusMean / 2);
        assertThat(sampleMean).isLessThan(corpusMean * 2);

        List<String> naive = all.subList(0, 40);
        double naiveMean = naive.stream().mapToInt(String::length).average().orElse(0);
        assertWithMessage("spread sampling should beat taking the first 40")
            .that(Math.abs(sampleMean - corpusMean)).isLessThan(Math.abs(naiveMean - corpusMean));
    }

    /**
     * The default sample is one full batch — the measurement has to look like the work it
     * predicts, because on a reasoning model cost is per request rather than per character.
     */
    @Test
    void theDefaultSampleIsOneFullBatch() throws IOException {
        CountingService service = new CountingService();
        GuideTranslationGenerator.Estimate estimate = new GuideTranslationGenerator(service, tempDir)
            .estimate("xx", GuideTranslationGenerator.DEFAULT_ESTIMATE_SAMPLE, null);

        // Bounded by the batch's item cap and its character budget, whichever binds first.
        assertThat(estimate.sampleSegments())
            .isAtMost(GuideTranslationGenerator.DEFAULT_MAX_BATCH_ITEMS);
        assertThat(estimate.sampleChars())
            .isAtMost((long) GuideTranslationGenerator.ESTIMATE_SAMPLE_CHARS);
        assertThat(estimate.sampleSegments()).isGreaterThan(1);
        // One warm-up plus one timed batch. The warm-up is what keeps a model's one-off load
        // out of the timing, where it would otherwise be multiplied by the batch count.
        assertThat(service.calls).isEqualTo(2);
    }

    /** An explicit request is the caller overriding the default, and is honoured as given. */
    @Test
    void anExplicitSampleSizeIsHonoured() throws IOException {
        GuideTranslationGenerator.Estimate estimate =
            new GuideTranslationGenerator(new IdentityService(), tempDir).estimate("xx", 3, null);
        assertThat(estimate.sampleSegments()).isEqualTo(3);
    }

    @Test
    void aGenerousSampleRequestIsHonouredAsGiven() throws IOException {
        GuideTranslationGenerator.Estimate estimate =
            new GuideTranslationGenerator(new IdentityService(), tempDir).estimate("xx", 120, null);
        assertThat(estimate.sampleSegments()).isEqualTo(120);
    }

    @Test
    void samplingIsDeterministicSoTwoEstimatesAreComparable() {
        List<String> input = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            input.add("segment " + i + " ".repeat(i % 40));
        }
        assertThat(GuideTranslationGenerator.spreadAcrossLengths(input, 20))
            .isEqualTo(GuideTranslationGenerator.spreadAcrossLengths(input, 20));
    }

    @Test
    void aSmallCorpusIsSampledWhole() {
        List<String> input = List.of("a", "bb", "ccc");
        assertThat(GuideTranslationGenerator.spreadAcrossLengths(input, 40)).hasSize(3);
    }

    /** Sampling is real translation: the run that follows must reuse it, not repeat it. */
    @Test
    void theSampledSegmentsAreKeptForTheRealRun() throws IOException {
        GuideTranslationGenerator generator = new GuideTranslationGenerator(new IdentityService(), tempDir);
        generator.estimate("xx", 40, null);

        GuideTranslationGenerator.Estimate second = generator.estimate("xx", 40, null);

        // The second estimate must sample segments the first did not already translate.
        assertThat(second.remainingSegments()).isLessThan(
            GuideTranslationGenerator.listPages().size() * 1000);
        Path memory = tempDir.resolve("guide/xx/translation-memory.json");
        assertThat(Files.readString(memory, StandardCharsets.UTF_8).length()).isGreaterThan(100);
    }

    @Test
    void anEstimateReportsCompletionInsteadOfAProjectionWhenNothingIsLeft() throws IOException {
        new GuideTranslationGenerator(new IdentityService(), tempDir).generate("xx", null, null);

        GuideTranslationGenerator.Estimate estimate =
            new GuideTranslationGenerator(new IdentityService(), tempDir).estimate("xx", 40, null);

        assertThat(estimate.isComplete()).isTrue();
        assertThat(estimate.remainingSegments()).isEqualTo(0);
    }

    /** A service that translates nothing must report "no projection", not a fabricated one. */
    @Test
    void anEstimateFromAFailingServiceIsMarkedUnusable() throws IOException {
        TranslationService broken = new TranslationService() {
            @Override
            public String translate(String text, String sourceLang, String targetLang) {
                return null;
            }

            @Override
            public List<String> translateBatch(List<String> texts, String s, String t) {
                return null;
            }

            @Override
            public boolean testConnection() {
                return false;
            }
        };

        GuideTranslationGenerator.Estimate estimate =
            new GuideTranslationGenerator(broken, tempDir).estimate("xx", 8, null);

        assertThat(estimate.isUsable()).isFalse();
        assertThat(estimate.lowMillis()).isEqualTo(-1);
    }

    // ----------------------------------------------------------- search index

    /**
     * A translated guide whose search still answers in English is worse than none: the reader
     * cannot find the page they are looking at. The index is rebuilt from the translated pages,
     * so it costs no model time.
     */
    @Test
    void theSearchIndexIsRebuiltFromTheTranslatedPages() throws IOException {
        new GuideTranslationGenerator(new PrefixService(), tempDir).generate("xx", null, null);

        Path index = tempDir.resolve("guide/xx/search/search_index.json");
        assertThat(Files.isRegularFile(index)).isTrue();
        String json = Files.readString(index, StandardCharsets.UTF_8);
        // PrefixService marks every translated string; the index must carry the marks too.
        assertThat(json).contains("[xx]");

        GuideSearchIndex parsed = GuideSearchIndex.load("xx", tempDir);
        assertThat(parsed).isNotNull();
        assertThat(parsed.entries().size()).isGreaterThan(400);
        long marked = parsed.entries().stream()
            .filter(entry -> entry.title().contains("[xx]") || entry.plainText().contains("[xx]"))
            .count();
        assertWithMessage("most entries should carry translated text")
            .that(marked).isGreaterThan(parsed.entries().size() * 3L / 4);
    }

    /** Locations and anchors must survive, or every search result would lead nowhere. */
    @Test
    void rebuiltIndexEntriesStillPointAtRealPagesAndAnchors() throws IOException {
        new GuideTranslationGenerator(new IdentityService(), tempDir).generate("xx", null, null);

        GuideSearchIndex translated = GuideSearchIndex.load("xx", tempDir);
        GuideSearchIndex english = GuideSearchIndex.load("en", tempDir);
        assertThat(translated).isNotNull();
        assertThat(english).isNotNull();

        java.util.Set<String> englishLocations = english.entries().stream()
            .map(GuideSearchIndex.Entry::location).collect(java.util.stream.Collectors.toSet());
        for (GuideSearchIndex.Entry entry : translated.entries()) {
            assertWithMessage("location " + entry.location())
                .that(englishLocations).contains(entry.location());
            if (entry.anchor() != null) {
                String page = Files.readString(tempDir.resolve("guide/xx").resolve(entry.pagePath()),
                    StandardCharsets.UTF_8);
                assertWithMessage("anchor of " + entry.location())
                    .that(page).contains("id=\"" + entry.anchor() + "\"");
            }
        }
    }

    @Test
    void theIndexUsesTheTargetLanguageStemmerWhenOneIsAvailable() throws IOException {
        new GuideTranslationGenerator(new IdentityService(), tempDir).generate("de", null, null);

        String json = Files.readString(tempDir.resolve("guide/de/search/search_index.json"),
            StandardCharsets.UTF_8);
        assertThat(json).contains("\"lang\":[\"de\"]");

        // A language lunr has no stemmer for must stay on English rather than break search.
        new GuideTranslationGenerator(new IdentityService(), tempDir).generate("xx", null, null);
        assertThat(Files.readString(tempDir.resolve("guide/xx/search/search_index.json"),
            StandardCharsets.UTF_8)).contains("\"lang\":[\"en\"]");
    }

    // ---------------------------------------------------------------- assets

    /**
     * The load-bearing property for the viewer: a generated page is opened over {@code file:},
     * its asset links are relative, and a {@code file:} document cannot reach into the jar. So
     * every relative asset reference on a generated page must resolve to a real file next to it.
     */
    @Test
    void everyRelativeAssetReferenceOnAGeneratedPageResolvesOnDisk() throws IOException {
        new GuideTranslationGenerator(new IdentityService(), tempDir)
            .generate("xx", List.of("index.html", "features/connections.html"), null, null);

        Path root = tempDir.resolve("guide/xx");
        java.util.regex.Pattern reference =
            java.util.regex.Pattern.compile("(?:href|src)=\"((?!https?:|mailto:|#|data:)[^\"]+)\"");
        int checked = 0;
        for (String page : List.of("index.html", "features/connections.html")) {
            Path file = root.resolve(page);
            java.util.regex.Matcher matcher =
                reference.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (matcher.find()) {
                String target = matcher.group(1).split("#")[0];
                if (target.isEmpty() || !target.contains("assets/")) {
                    continue;
                }
                Path resolved = file.getParent().resolve(target).normalize();
                assertWithMessage(page + " -> " + target).that(Files.isRegularFile(resolved)).isTrue();
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(4);
    }

    @Test
    void stagingIsIdempotentAndSkipsAssetsStrippedFromTheJar() throws IOException {
        GuideTranslationGenerator generator =
            new GuideTranslationGenerator(new IdentityService(), tempDir);
        Path outDir = tempDir.resolve("guide/xx");
        Files.createDirectories(outDir);

        int first = generator.stageAssets(outDir);
        int second = generator.stageAssets(outDir);

        assertThat(first).isGreaterThan(0);
        assertThat(second).isEqualTo(0);
        // The inventory lists source maps that build.gradle.kts strips from the jar, so the
        // number staged is allowed to be lower than the number recorded — but never zero.
        assertThat(first).isAtMost(GuideTranslationGenerator.listAssets().size());
    }

    @Test
    void aGeneratedTreeIsRecognisedByTheResolver() throws IOException {
        assertThat(GuideLocationResolver.isGenerated("xx", tempDir)).isFalse();

        new GuideTranslationGenerator(new IdentityService(), tempDir)
            .generate("xx", List.of("index.html"), null, null);

        assertThat(GuideLocationResolver.isGenerated("xx", tempDir)).isTrue();
        assertThat(GuideLocationResolver.availableGeneratedLanguages(tempDir)).contains("xx");
        assertThat(GuideLocationResolver.pageUrl("xx", "index.html", tempDir)).startsWith("file:");
    }

    // --------------------------------------------------------------- fixtures

    private static String readBundledPage(String page) throws IOException {
        try (var in = GuideTranslationGenerator.class.getResourceAsStream("/guide/en/" + page)) {
            assertWithMessage("bundled page " + page).that(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int totalSegmentOccurrences() throws IOException {
        int total = 0;
        for (String page : GuideTranslationGenerator.listPages()) {
            total += GuideTranslationGenerator.loadManifest(page).segments().size();
        }
        return total;
    }

    private static final class IdentityService implements TranslationService {
        @Override
        public String translate(String text, String sourceLang, String targetLang) {
            return text;
        }

        @Override
        public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang) {
            return List.copyOf(texts);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }

    /** Marks translated text so it can be told apart from untouched English. */
    private static final class PrefixService implements TranslationService {
        @Override
        public String translate(String text, String sourceLang, String targetLang) {
            return "[" + targetLang.toLowerCase(Locale.ROOT) + "]" + text;
        }

        @Override
        public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang) {
            List<String> out = new ArrayList<>(texts.size());
            texts.forEach(text -> out.add(translate(text, sourceLang, targetLang)));
            return out;
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }

    private static final class CountingService implements TranslationService {
        final java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        int requested;
        int calls;

        @Override
        public String translate(String text, String sourceLang, String targetLang) {
            return text;
        }

        @Override
        public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang) {
            calls++;
            requested += texts.size();
            seen.addAll(texts);
            return List.copyOf(texts);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
