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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates the bundled HTML guide into a target language with a {@link TranslationService},
 * for users who cannot reach a translation API and run a local AI profile instead.
 *
 * <p>Counterpart to {@link DynamicLanguageGenerator}, which does the same for the interface
 * strings. The guide is harder in three ways, and the design follows from them:
 *
 * <ul>
 *   <li><b>The page must survive.</b> Only prose is sent to the model. Build-time manifests
 *       under {@code /guide/en/translation/} record each translatable run together with the
 *       UTF-16 offsets it occupies, so the English page shipped in the jar stays the single
 *       source of markup and this class only splices text back in. Every segment is checked
 *       against the page before anything is written: if the manifest and the HTML have drifted
 *       apart, the page is skipped rather than corrupted.</li>
 *   <li><b>Volume.</b> The 54 pages hold ~11.9k segments, but only ~5.3k are distinct — the
 *       navigation sidebar repeats on every page, and each heading appears up to four times
 *       (heading, page TOC, sidebar TOC, title). Translating distinct text once cuts the work
 *       roughly in half and, more importantly, keeps those copies consistent.</li>
 *   <li><b>Local models fail differently from APIs.</b> A cloud translator returns a wrong
 *       word; a small local model drops an item from a batch or mangles a placeholder. Both are
 *       caught (strict count contract plus placeholder validation) and answered by halving the
 *       batch, so one bad segment costs one segment, not a page.</li>
 * </ul>
 *
 * <p>Progress is reported per translated batch, and {@code cancelled} is polled between batches
 * and pages: on a local model a full run is a background job of minutes to hours, and the
 * translation memory is persisted so an interrupted run resumes instead of starting over.
 *
 * <p>This writes HTML only. The Material theme's CSS/JS/images are not staged next to it, so the
 * output is not yet loadable in the guide viewer — see the class comment on the generated
 * language directory in {@link #guideDirectory()}.
 */
public class GuideTranslationGenerator {

    private static final Logger logger = LoggerFactory.getLogger(GuideTranslationGenerator.class);

    private static final String MANIFEST_ROOT = "/guide/en/translation/";
    private static final String SOURCE_ROOT = "/guide/en/";
    private static final String SOURCE_LANG = "en";
    /** Manifest layout this class understands; a newer manifest is refused, not guessed at. */
    static final int SUPPORTED_FORMAT_VERSION = 1;

    /**
     * Batches are sized by characters rather than item count. Segment length ranges from a
     * two-word table cell to a full paragraph, so a fixed count either starves a batch of
     * context or overruns a small local model's window.
     */
    static final int DEFAULT_CHAR_BUDGET = 1_500;
    static final int DEFAULT_MAX_BATCH_ITEMS = 20;
    /** How often the resume point is written out during a long run. */
    static final int CHECKPOINT_EVERY_BATCHES = 25;

    private static final Pattern TOKEN = Pattern.compile("KTPH\\d{3}");

    private final TranslationService translationService;
    private final Path guideDir;
    private final int charBudget;
    private final int maxBatchItems;

    public GuideTranslationGenerator(TranslationService translationService, Path configDirectory) {
        this(translationService, configDirectory, DEFAULT_CHAR_BUDGET, DEFAULT_MAX_BATCH_ITEMS);
    }

    public GuideTranslationGenerator(TranslationService translationService, Path configDirectory,
                                     int charBudget, int maxBatchItems) {
        this.translationService = translationService;
        this.guideDir = configDirectory.resolve("guide");
        this.charBudget = Math.max(1, charBudget);
        this.maxBatchItems = Math.max(1, maxBatchItems);
    }

    /** Root the generated language trees are written under. */
    public Path guideDirectory() {
        return guideDir;
    }

    // ------------------------------------------------------------------ model

    /** One translatable run: {@code [startUtf16, endUtf16)} of the page, masked to {@code text}. */
    public record Segment(int startUtf16, int endUtf16, String kind, String text,
                          List<String> fragments) {
    }

    public record PageManifest(String page, String sourceSha256, List<Segment> segments) {
    }

    public record Result(int pagesWritten, int pagesSkipped, int distinctSegments,
                         int translated, int reused, int failed) {
    }

    // -------------------------------------------------------------- manifests

    /** Page paths listed by the build-time extractor, in manifest order. */
    public static List<String> listPages() throws IOException {
        try (InputStream in = open(MANIFEST_ROOT + "index.json")) {
            JsonObject root = JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            requireSupportedVersion(root, "index.json");
            List<String> pages = new ArrayList<>();
            JsonArray array = root.getAsJsonArray("pages");
            if (array != null) {
                for (JsonElement element : array) {
                    JsonObject entry = element.getAsJsonObject();
                    if (entry.has("page")) {
                        pages.add(entry.get("page").getAsString());
                    }
                }
            }
            return List.copyOf(pages);
        }
    }

    /**
     * Stylesheets, scripts and images the pages reference, relative to the language root.
     *
     * <p>Recorded at build time rather than discovered at runtime because the guide lives
     * inside the jar, where there is no directory listing to walk.
     */
    public static List<String> listAssets() throws IOException {
        try (InputStream in = open(MANIFEST_ROOT + "index.json")) {
            JsonObject root = JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            requireSupportedVersion(root, "index.json");
            List<String> assets = new ArrayList<>();
            JsonArray array = root.getAsJsonArray("assets");
            if (array != null) {
                array.forEach(element -> assets.add(element.getAsString()));
            }
            return List.copyOf(assets);
        }
    }

    public static PageManifest loadManifest(String page) throws IOException {
        try (InputStream in = open(MANIFEST_ROOT + page + ".json")) {
            JsonObject root = JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            requireSupportedVersion(root, page);
            List<Segment> segments = new ArrayList<>();
            JsonArray array = root.getAsJsonArray("segments");
            if (array != null) {
                for (JsonElement element : array) {
                    JsonObject entry = element.getAsJsonObject();
                    List<String> fragments = new ArrayList<>();
                    JsonArray raw = entry.getAsJsonArray("f");
                    if (raw != null) {
                        for (JsonElement fragment : raw) {
                            fragments.add(fragment.getAsString());
                        }
                    }
                    segments.add(new Segment(entry.get("s").getAsInt(), entry.get("e").getAsInt(),
                        entry.has("k") ? entry.get("k").getAsString() : "block",
                        entry.get("t").getAsString(), List.copyOf(fragments)));
                }
            }
            return new PageManifest(root.get("page").getAsString(),
                root.get("sourceSha256").getAsString(), List.copyOf(segments));
        }
    }

    private static void requireSupportedVersion(JsonObject root, String what) throws IOException {
        int version = root.has("formatVersion") ? root.get("formatVersion").getAsInt() : -1;
        if (version != SUPPORTED_FORMAT_VERSION) {
            throw new IOException("Unsupported guide manifest version " + version + " in " + what
                + " (expected " + SUPPORTED_FORMAT_VERSION + ")");
        }
    }

    private static InputStream open(String resource) throws IOException {
        InputStream in = GuideTranslationGenerator.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("Guide resource not found: " + resource);
        }
        return in;
    }

    // -------------------------------------------------------------- estimate

    /**
     * Result of a sample run. {@code lowMillis}/{@code highMillis} bracket the projection rather
     * than pretending to a single number: a short sample cannot separate per-batch overhead from
     * per-character cost, so the two plausible extrapolations are reported as a range. Both are
     * -1 when the sample produced nothing usable and no projection is possible.
     */
    public record Estimate(int sampleSegments, long sampleChars, long elapsedMillis,
                           int remainingSegments, long remainingChars,
                           long lowMillis, long highMillis) {

        public boolean isUsable() {
            return lowMillis >= 0 && highMillis >= 0;
        }

        /** True when the memory already covers the guide and nothing would be translated. */
        public boolean isComplete() {
            return remainingSegments == 0;
        }
    }

    /** Segments sampled by default: enough batches for the per-batch cost to average out. */
    public static final int DEFAULT_ESTIMATE_SAMPLE = 40;

    /**
     * Measures the configured service on a sample of the real corpus and projects the remaining
     * work. Exists because the honest answer to "how long will this take" ranges from a minute on
     * a cloud API to most of a night on a local model, and only the user's own hardware can say.
     *
     * <p>The sample is spread across the length distribution rather than taken from the front:
     * segments range from a two-word table cell to a full paragraph, and the leading pages are
     * mostly short navigation labels, which would flatter the estimate badly.
     *
     * <p>Sampling is real work, not a simulation — translations land in the translation memory,
     * so the run that follows reuses them instead of repeating them.
     */
    public Estimate estimate(String targetLangCode, int sampleSize, BooleanSupplier cancelled)
            throws IOException {
        if (targetLangCode == null || targetLangCode.isBlank()) {
            throw new IllegalArgumentException("targetLangCode is required");
        }
        String target = targetLangCode.trim().toLowerCase(java.util.Locale.ROOT);
        Path outDir = guideDir.resolve(target);
        Files.createDirectories(outDir);

        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String page : listPages()) {
            for (Segment segment : loadManifest(page).segments()) {
                distinct.add(segment.text());
            }
        }
        Map<String, String> memory = loadMemory(outDir);
        List<String> pending = new ArrayList<>();
        long pendingChars = 0;
        for (String text : distinct) {
            if (!memory.containsKey(text)) {
                pending.add(text);
                pendingChars += text.length();
            }
        }
        if (pending.isEmpty()) {
            return new Estimate(0, 0, 0, 0, 0, 0, 0);
        }

        List<String> sample = spreadAcrossLengths(pending, effectiveSampleSize(sampleSize, pending));
        long sampleChars = sample.stream().mapToLong(String::length).sum();

        long started = System.nanoTime();
        int failed = translateAll(sample, target, memory, null, cancelled, outDir);
        long elapsedNanos = System.nanoTime() - started;
        long elapsedMillis = elapsedNanos / 1_000_000L;
        saveMemory(outDir, memory);

        int translated = 0;
        long translatedChars = 0;
        for (String text : sample) {
            if (memory.containsKey(text)) {
                translated++;
                translatedChars += text.length();
            }
        }
        if (translated == 0) {
            logger.warn("Guide translation estimate produced no usable sample ({} failed)", failed);
            return new Estimate(sample.size(), sampleChars, elapsedMillis,
                pending.size(), pendingChars, -1, -1);
        }

        // Two extrapolations: cost per character and cost per segment. They agree when the
        // service scales with content and diverge when per-request overhead dominates, which is
        // exactly the uncertainty the range is there to show.
        //
        // Scaled from nanoseconds, not from the rounded millisecond figure: a fast service can
        // finish the sample inside one millisecond, and dividing by that zero would report a
        // perfectly measurable run as unusable.
        double byCharsNanos = (double) elapsedNanos / translatedChars * pendingChars;
        double bySegmentsNanos = (double) elapsedNanos / translated * pending.size();
        long byChars = Math.round(byCharsNanos / 1_000_000.0);
        long bySegments = Math.round(bySegmentsNanos / 1_000_000.0);
        return new Estimate(sample.size(), sampleChars, elapsedMillis, pending.size(), pendingChars,
            Math.min(byChars, bySegments), Math.max(byChars, bySegments));
    }

    /** Batches a sample must span before a projection means anything. */
    static final int MIN_ESTIMATE_BATCHES = 3;

    /**
     * Raises a too-small request to something that actually spans several batches.
     *
     * <p>A one-batch sample cannot be extrapolated at all: a batch carries a fixed cost — on a
     * reasoning model, most of it — and dividing that whole cost by a handful of segments
     * overstates both the per-segment and the per-character rate, so even the range between them
     * stays wrong. Measured against an embedded 20B model, 8 segments projected 17–25 hours where
     * 40 segments projected 4h49–5h20 and the truth was near 5h.
     *
     * <p>Derived from the batch settings and the corpus rather than hardcoded, so it stays right
     * when the budget is tuned or the guide changes shape.
     */
    private int effectiveSampleSize(int requested, List<String> pending) {
        long totalChars = pending.stream().mapToLong(String::length).sum();
        int averageLength = (int) Math.max(1, totalChars / pending.size());
        int perBatch = Math.min(maxBatchItems, Math.max(1, charBudget / averageLength));
        int minimum = Math.min(pending.size(), MIN_ESTIMATE_BATCHES * perBatch);
        return Math.max(Math.max(1, requested), minimum);
    }

    /**
     * Picks {@code count} entries spread evenly across the length-sorted input, so the sample
     * mirrors the corpus's mix of short labels and long paragraphs. Deterministic on purpose:
     * two estimates of the same corpus should be comparable.
     */
    static List<String> spreadAcrossLengths(List<String> candidates, int count) {
        if (candidates.size() <= count) {
            return List.copyOf(candidates);
        }
        List<String> sorted = new ArrayList<>(candidates);
        sorted.sort(java.util.Comparator.comparingInt(String::length).thenComparing(text -> text));
        List<String> picked = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            picked.add(sorted.get((int) ((long) i * sorted.size() / count)));
        }
        return List.copyOf(picked);
    }

    // ------------------------------------------------------------- generation

    /**
     * Translates every bundled page into {@code targetLangCode}.
     *
     * @param progress  optional 0.0..1.0 callback; may be null
     * @param cancelled optional poll for cancellation; may be null
     */
    public Result generate(String targetLangCode, Consumer<Double> progress,
                           BooleanSupplier cancelled) throws IOException {
        return generate(targetLangCode, listPages(), progress, cancelled);
    }

    /**
     * Translates the given subset of pages. Useful to translate what the reader is about to
     * open before committing to the whole guide, and to benchmark a local model on a sample
     * without waiting out the full corpus. The translation memory is shared across runs, so
     * successive subsets accumulate rather than repeat work.
     */
    public Result generate(String targetLangCode, List<String> pages, Consumer<Double> progress,
                           BooleanSupplier cancelled) throws IOException {
        if (targetLangCode == null || targetLangCode.isBlank()) {
            throw new IllegalArgumentException("targetLangCode is required");
        }
        if (pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException("at least one page is required");
        }
        String target = targetLangCode.trim().toLowerCase(java.util.Locale.ROOT);
        Path outDir = guideDir.resolve(target);
        Files.createDirectories(outDir);

        List<PageManifest> manifests = new ArrayList<>(pages.size());
        // LinkedHashSet: distinct, but still in page order so a partial run produces
        // readable early pages rather than a scatter of finished fragments.
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String page : pages) {
            PageManifest manifest = loadManifest(page);
            manifests.add(manifest);
            for (Segment segment : manifest.segments()) {
                distinct.add(segment.text());
            }
        }

        Map<String, String> memory = loadMemory(outDir);
        int reused = 0;
        List<String> pending = new ArrayList<>();
        for (String text : distinct) {
            if (memory.containsKey(text)) {
                reused++;
            } else {
                pending.add(text);
            }
        }

        int failed = translateAll(pending, target, memory, progress, cancelled, outDir);
        saveMemory(outDir, memory);

        // Counted from the memory rather than derived as (distinct - reused - failed): a
        // cancelled run leaves most of `pending` never attempted, and the derived form would
        // report those as translated.
        int translated = 0;
        for (String text : pending) {
            if (memory.containsKey(text)) {
                translated++;
            }
        }

        TranslationGlossary glossary =
            TranslationGlossary.forLanguage(target, TranslationGlossary.Scope.HTML);
        if (!glossary.isEmpty()) {
            logger.info("Applying {} glossary correction(s) for {}", glossary.size(), target);
        }

        int staged = stageAssets(outDir);
        if (staged > 0) {
            logger.info("Staged {} guide asset(s) into {}", staged, outDir);
        }

        int written = 0;
        int skipped = 0;
        for (PageManifest manifest : manifests) {
            if (isCancelled(cancelled)) {
                break;
            }
            if (writePage(manifest, target, memory, outDir, glossary)) {
                written++;
            } else {
                skipped++;
            }
        }
        if (written > 0) {
            // Derived from the pages just written, not translated separately: the index holds as
            // much text as the guide itself, and a second pass would double an already long run.
            try {
                GuideSearchIndexTranslator.rebuild(outDir, target);
                GuideSearchIndex.invalidate(target);
            } catch (IOException | RuntimeException e) {
                // A translated guide with an English index is degraded but usable; failing the
                // whole run over it would throw away hours of work.
                logger.warn("Could not rebuild the search index for {}", target, e);
            }
        }
        if (progress != null) {
            progress.accept(1.0);
        }
        logger.info("Generated guide/{}: {} page(s) written, {} skipped, {} segment(s) untranslated",
            target, written, skipped, failed);
        return new Result(written, skipped, distinct.size(), translated, reused, failed);
    }

    /**
     * Fills {@code memory} for every pending text. Returns how many could not be translated;
     * those keep their English source, which is the one failure mode a reader can live with.
     */
    private int translateAll(List<String> pending, String target, Map<String, String> memory,
                             Consumer<Double> progress, BooleanSupplier cancelled, Path outDir) {
        int failed = 0;
        int done = 0;
        int batches = 0;
        int total = pending.size();
        List<String> batch = new ArrayList<>();
        int batchChars = 0;
        for (int i = 0; i <= pending.size(); i++) {
            boolean flush = i == pending.size();
            if (!flush) {
                String text = pending.get(i);
                if (!batch.isEmpty()
                    && (batchChars + text.length() > charBudget || batch.size() >= maxBatchItems)) {
                    flush = true;
                    i--; // reconsider this text as the first item of the next batch
                } else {
                    batch.add(text);
                    batchChars += text.length();
                }
            }
            if (!flush || batch.isEmpty()) {
                continue;
            }
            if (isCancelled(cancelled)) {
                return failed;
            }
            failed += translateBatch(batch, target, memory);
            done += batch.size();
            // Checkpoint: a full local-model run is measured in hours, so the memory has to
            // survive a crash or a kill, not just an orderly finish.
            if (++batches % CHECKPOINT_EVERY_BATCHES == 0) {
                saveMemory(outDir, memory);
            }
            if (progress != null && total > 0) {
                // Splicing is negligible next to model latency; report translation as the whole job.
                progress.accept(Math.min(1.0, (double) done / total));
            }
            batch = new ArrayList<>();
            batchChars = 0;
        }
        return failed;
    }

    /**
     * Translates one batch, halving it on any failure. A local model that drops an item or
     * mangles a placeholder poisons only its own half this way, and recursion bottoms out at a
     * single segment which is then left in English.
     */
    private int translateBatch(List<String> batch, String target, Map<String, String> memory) {
        List<String> result = null;
        try {
            result = translationService.translateBatch(batch, SOURCE_LANG, target);
        } catch (RuntimeException e) {
            logger.debug("Translation batch of {} failed", batch.size(), e);
        }
        boolean usable = result != null && result.size() == batch.size();
        List<String> retry = new ArrayList<>();
        int failed = 0;
        for (int i = 0; i < batch.size(); i++) {
            String source = batch.get(i);
            String translated = usable ? result.get(i) : null;
            if (translated != null && !translated.isBlank() && placeholdersIntact(source, translated)) {
                memory.put(source, translated);
            } else if (batch.size() == 1) {
                failed++;
            } else {
                retry.add(source);
            }
        }
        if (retry.isEmpty()) {
            return failed;
        }
        if (retry.size() == batch.size()) {
            int half = batch.size() / 2;
            failed += translateBatch(new ArrayList<>(retry.subList(0, half)), target, memory);
            failed += translateBatch(new ArrayList<>(retry.subList(half, retry.size())), target, memory);
            return failed;
        }
        return failed + translateBatch(retry, target, memory);
    }

    /**
     * Every placeholder of the source must appear exactly once in the translation, and no new
     * one may be invented. Word order is free — German routinely moves a token across the
     * sentence — but a lost token means lost markup, which is what this refuses to write.
     */
    static boolean placeholdersIntact(String source, String translated) {
        Map<String, Integer> expected = countTokens(source);
        Map<String, Integer> actual = countTokens(translated);
        return expected.equals(actual);
    }

    private static Map<String, Integer> countTokens(String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            counts.merge(matcher.group(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Restores masked markup. Highest index first, mirroring the extractor's inverse.
     *
     * <p>{@code Locale.ROOT} is required, not cosmetic: {@code %d} formats through the default
     * locale's digits, so under Arabic, Persian, Bengali, Nepali, Urdu, Marathi or Burmese
     * defaults the token came out as {@code KTPH٠٠٠} and matched nothing in the manifest. Every
     * segment then failed the round-trip guard and all 54 pages were skipped — after the local
     * model had already spent the full run translating them.
     */
    static String unmask(String masked, List<String> fragments) {
        String out = masked;
        for (int i = fragments.size() - 1; i >= 0; i--) {
            String token = String.format(java.util.Locale.ROOT, "KTPH%03d", i);
            int at = out.indexOf(token);
            if (at >= 0) {
                out = out.substring(0, at) + fragments.get(i) + out.substring(at + token.length());
            }
        }
        return out;
    }

    // ----------------------------------------------------------------- assets

    /**
     * Copies the theme's stylesheets, scripts and images next to the translated pages.
     *
     * <p>Required, not an optimisation: a generated page is loaded from the config directory
     * over {@code file:}, its asset links are relative ({@code ../assets/…}), and a {@code file:}
     * document cannot reach back into the {@code jar:} the English guide ships in. Without this
     * the page renders as unstyled text with no images.
     *
     * <p>Skips assets already staged and assets absent from the jar — the build deliberately
     * strips source maps and the lunr segmenters, so a few entries of the inventory never exist.
     *
     * @return how many files were copied
     */
    int stageAssets(Path outDir) throws IOException {
        int copied = 0;
        int missing = 0;
        for (String asset : listAssets()) {
            Path target = outDir.resolve(asset);
            if (Files.isRegularFile(target)) {
                continue;
            }
            try (InputStream in = GuideTranslationGenerator.class
                    .getResourceAsStream(SOURCE_ROOT + asset)) {
                if (in == null) {
                    missing++;
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(in, target);
                copied++;
            }
        }
        if (missing > 0) {
            logger.debug("{} guide asset(s) are not in the jar and were skipped", missing);
        }
        return copied;
    }

    // ---------------------------------------------------------------- writing

    /**
     * Splices translated segments into the English page and writes it. Returns false when the
     * page was left out, which happens only if the manifest no longer describes the shipped
     * HTML — writing then would produce a page with text spliced at the wrong offsets.
     */
    private boolean writePage(PageManifest manifest, String target, Map<String, String> memory,
                              Path outDir, TranslationGlossary glossary) throws IOException {
        byte[] raw;
        try (InputStream in = open(SOURCE_ROOT + manifest.page())) {
            raw = in.readAllBytes();
        }
        if (!sha256(raw).equals(manifest.sourceSha256())) {
            logger.warn("Skipping {}: manifest does not match the bundled page", manifest.page());
            return false;
        }
        String html = new String(raw, StandardCharsets.UTF_8);

        StringBuilder out = new StringBuilder(html.length() + 1024);
        int cursor = 0;
        for (Segment segment : manifest.segments()) {
            if (segment.startUtf16() < cursor || segment.endUtf16() > html.length()
                || segment.startUtf16() > segment.endUtf16()) {
                logger.warn("Skipping {}: segment offsets are out of order", manifest.page());
                return false;
            }
            String source = html.substring(segment.startUtf16(), segment.endUtf16());
            if (!unmask(segment.text(), segment.fragments()).equals(source)) {
                logger.warn("Skipping {}: segment at {} does not match the page",
                    manifest.page(), segment.startUtf16());
                return false;
            }
            String translated = memory.get(segment.text());
            if (translated != null) {
                // Applied here rather than before storing, so the memory keeps the raw model
                // output: editing the glossary then takes effect on the next write instead of
                // invalidating hours of translation.
                String corrected = glossary.apply(translated);
                translated = placeholdersIntact(translated, corrected) ? corrected : translated;
            }
            out.append(html, cursor, segment.startUtf16());
            out.append(translated != null
                ? unmask(escapeMarkup(translated), segment.fragments())
                : source);
            cursor = segment.endUtf16();
        }
        out.append(html, cursor, html.length());

        Path pagePath = outDir.resolve(manifest.page());
        Files.createDirectories(pagePath.getParent());
        Files.writeString(pagePath, localizeHtmlLang(out.toString(), target), StandardCharsets.UTF_8);
        return true;
    }

    /**
     * Neutralises angle brackets a model may have invented before the text is spliced into the
     * page. Whatever comes back is written straight into HTML, and a local model asked to
     * translate prose does sometimes answer with a stray tag; unescaped, that reopens or closes
     * elements and can swallow the rest of the document.
     *
     * <p>{@code &} is deliberately left alone. 330 segments of the real guide carry a bare
     * ampersand ("Feedback &amp; support"), which browsers render as text; escaping it would
     * rewrite bytes in segments the model never touched, for no rendered difference.
     */
    static String escapeMarkup(String text) {
        return text.indexOf('<') < 0 && text.indexOf('>') < 0
            ? text
            : text.replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Retags the document language so hyphenation and screen readers follow the translation. */
    private static String localizeHtmlLang(String html, String target) {
        int at = html.indexOf("<html lang=\"" + SOURCE_LANG + "\"");
        return at < 0 ? html
            : html.substring(0, at) + "<html lang=\"" + target + "\""
              + html.substring(at + ("<html lang=\"" + SOURCE_LANG + "\"").length());
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest(data)) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ----------------------------------------------------------------- memory

    /**
     * The translation memory doubles as the resume point. A local-model run takes long enough
     * that losing it to a restart would make the feature unusable in practice.
     */
    Path memoryFile(String target) {
        return guideDir.resolve(target).resolve("translation-memory.json");
    }

    private Map<String, String> loadMemory(Path outDir) {
        Path file = outDir.resolve("translation-memory.json");
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
            Map<String, String> memory = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    memory.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            return memory;
        } catch (RuntimeException | IOException e) {
            logger.warn("Ignoring unreadable guide translation memory {}", file, e);
            return new LinkedHashMap<>();
        }
    }

    /**
     * Writes the memory through a temporary file and an atomic rename. A direct write truncates
     * first, so a crash or kill mid-write leaves a partial JSON object — and Gson recovers
     * nothing from one, which would silently discard the entire run rather than most of it.
     */
    private void saveMemory(Path outDir, Map<String, String> memory) {
        JsonObject root = new JsonObject();
        memory.forEach(root::addProperty);
        Path target = outDir.resolve("translation-memory.json");
        Path temp = outDir.resolve("translation-memory.json.tmp");
        try {
            Files.writeString(temp, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.warn("Could not persist guide translation memory", e);
        }
    }

    private static boolean isCancelled(BooleanSupplier cancelled) {
        return cancelled != null && cancelled.getAsBoolean();
    }
}
