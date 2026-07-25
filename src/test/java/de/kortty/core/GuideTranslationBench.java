package de.kortty.core;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiWorkload;
import de.kortty.model.GlobalSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Measures what the configured local AI profile actually does to the guide: how fast it
 * translates, and how well.
 *
 * <p>Not a test — there is nothing to assert. Translation quality is a judgement call, and the
 * throughput depends entirely on the machine and the model, so this prints numbers and sample
 * pairs for a human to read. It drives the real {@link GuideTranslationGenerator} rather than
 * calling the model directly, so the batching, placeholder validation and halving-on-failure
 * behaviour under measurement is the behaviour that ships.
 *
 * <p>Settings are read from the real config directory, but output goes to build/benchmark —
 * a benchmark must not leave a half-translated guide in {@code ~/.kortty}.
 *
 * <pre>
 * ./gradlew guideTranslationBench                       # 3 representative pages -> German
 * ./gradlew guideTranslationBench --args="--lang fr"
 * ./gradlew guideTranslationBench --args="--pages all --samples 20"
 * </pre>
 */
public final class GuideTranslationBench {

    /** Chosen to span the shapes the extractor treats differently, not for size. */
    private static final List<String> DEFAULT_PAGES = List.of(
        "index.html",                          // cards, emoji icons, short prose
        "features/connections.html",           // dense prose, heavy inline markup
        "reference/keyboard-shortcuts.html");  // tables and key caps

    private GuideTranslationBench() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        String lang = options.getOrDefault("lang", "de");
        int samples = Integer.parseInt(options.getOrDefault("samples", "8"));
        int budget = Integer.parseInt(options.getOrDefault("budget",
            String.valueOf(GuideTranslationGenerator.DEFAULT_CHAR_BUDGET)));
        int items = Integer.parseInt(options.getOrDefault("items",
            String.valueOf(GuideTranslationGenerator.DEFAULT_MAX_BATCH_ITEMS)));
        Path outRoot = Path.of(options.getOrDefault("out", "build/benchmark"));

        List<String> allPages = GuideTranslationGenerator.listPages();
        List<String> pages = resolvePages(options.get("pages"), allPages);

        AiProfile profile = resolveProfile();
        if (profile == null) {
            System.err.println("""
                No embedded AI profile configured.

                This benchmark needs a local model, because that is the case the feature
                exists for: Settings -> AI -> a profile whose connection mode is
                EMBEDDED_LLAMA_CPP or EMBEDDED_MLX, selected as the text/default profile.""");
            System.exit(2);
        }
        TranslationService service = buildTranslationService(profile);
        if (service == null) {
            System.err.println("Could not build a translation service from profile '"
                + profile.getName() + "'. Is an embedded model downloaded and selected?");
            System.exit(2);
        }

        Recorder recorder = new Recorder(service, samples);
        GuideTranslationGenerator generator =
            new GuideTranslationGenerator(recorder, outRoot, budget, items);

        int distinctHere = countDistinct(pages);
        int distinctAll = countDistinct(allPages);

        System.out.printf("""
            korTTY guide translation benchmark
              profile      : %s  (%s)
              target       : %s
              pages        : %d of %d
              batch budget : %d chars / max %d items
              output       : %s
            %n""", profile.getName(), profile.getConnectionMode(), lang,
            pages.size(), allPages.size(), budget, items, outRoot.resolve("guide").resolve(lang));
        System.out.printf("translating %d distinct segment(s) of %d total%n%n",
            distinctHere, distinctAll);
        System.out.flush();

        long start = System.nanoTime();
        GuideTranslationGenerator.Result result =
            generator.generate(lang, pages, new ProgressPrinter(), null);
        long elapsedNanos = System.nanoTime() - start;

        report(result, recorder, elapsedNanos, distinctAll, outRoot, lang);
    }

    // ------------------------------------------------------------------ setup

    /** The production recipe from SettingsDialog.createLocalAiTranslationService(). */
    private static AiProfile resolveProfile() throws Exception {
        GlobalSettingsManager manager =
            new GlobalSettingsManager(de.kortty.KorTTYApplication.getConfigDirectory());
        manager.load();
        GlobalSettings settings = manager.getSettings();
        AiProfile profile = AiProfileSelectionSupport.workloadProfile(
            settings.getAiProfiles(), AiWorkload.TEXT, settings.getTextAiProfileId(),
            settings.getCodingAiProfileId(), settings.getDefaultAiProfileId());
        return profile != null && profile.getConnectionMode().isEmbedded() ? profile : null;
    }

    private static TranslationService buildTranslationService(AiProfile profile) {
        // Copy and disable internet access: the factory rejects a Tavily-backed mode without a
        // key, and translation never needs web search.
        AiProfile translationProfile = new AiProfile(profile);
        translationProfile.setInternetAccessMode(AiInternetAccessMode.DISABLED);
        try {
            AiService service = AiServiceFactory.create(translationProfile, null,
                AiInternetAccessConfiguration.disabled(), AiSkillPromptSupport.disabled());
            return service instanceof AiPromptService prompt
                ? new LocalAiTranslationService(prompt)
                : null;
        } catch (RuntimeException e) {
            System.err.println("AI service could not be created: " + e.getMessage());
            return null;
        }
    }

    private static List<String> resolvePages(String requested, List<String> allPages) {
        if (requested == null) {
            return DEFAULT_PAGES.stream().filter(allPages::contains).toList();
        }
        if ("all".equalsIgnoreCase(requested)) {
            return allPages;
        }
        List<String> chosen = new ArrayList<>();
        for (String page : requested.split(",")) {
            String trimmed = page.trim();
            if (!trimmed.isEmpty() && allPages.contains(trimmed)) {
                chosen.add(trimmed);
            } else if (!trimmed.isEmpty()) {
                System.err.println("unknown page, ignored: " + trimmed);
            }
        }
        return chosen.isEmpty() ? DEFAULT_PAGES : chosen;
    }

    private static int countDistinct(List<String> pages) throws IOException {
        java.util.Set<String> distinct = new java.util.HashSet<>();
        for (String page : pages) {
            GuideTranslationGenerator.loadManifest(page).segments()
                .forEach(segment -> distinct.add(segment.text()));
        }
        return distinct.size();
    }

    // -------------------------------------------------------------- reporting

    private static void report(GuideTranslationGenerator.Result result, Recorder recorder,
                               long elapsedNanos, int distinctAll, Path outRoot, String lang)
            throws IOException {
        double seconds = elapsedNanos / 1_000_000_000.0;
        double perSegment = result.translated() > 0 ? seconds / result.translated() : 0;

        System.out.printf("""

            results
              wall clock          %s
              pages written       %d  (%d skipped)
              segments translated %d
              segments reused     %d  (already in the translation memory)
              segments refused    %d  (placeholder lost -> kept English)
              model calls         %d  (%d returned nothing usable -> batch halved)
              chars sent          %d
            %n""", formatDuration(seconds), result.pagesWritten(), result.pagesSkipped(),
            result.translated(), result.reused(), result.failed(),
            recorder.calls, recorder.failures, recorder.chars);

        if (result.translated() > 0) {
            System.out.printf("  throughput          %.2f segment(s)/s, %.0f chars/s%n",
                result.translated() / seconds, recorder.chars / seconds);
            System.out.printf("  projected full guide %s for %d distinct segment(s)%n",
                formatDuration(perSegment * distinctAll), distinctAll);
        }
        if (result.failed() > 0) {
            System.out.printf("%n  %.1f%% of segments were refused — a high rate means the model "
                    + "is losing the KTPH placeholders; try a smaller batch budget.%n",
                100.0 * result.failed() / Math.max(1, result.failed() + result.translated()));
        }

        System.out.println("\nsample translations");
        if (recorder.samples.isEmpty()) {
            System.out.println("  (none — everything was reused from the translation memory)");
        }
        recorder.samples.forEach((source, translated) -> {
            System.out.println("  EN  " + abbreviate(source));
            System.out.println("  " + lang.toUpperCase(Locale.ROOT) + "  " + abbreviate(translated));
            System.out.println();
        });

        Path written = outRoot.resolve("guide").resolve(lang);
        if (Files.isDirectory(written)) {
            System.out.println("translated pages: " + written.toAbsolutePath());
        }
    }

    private static String abbreviate(String text) {
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() <= 160 ? single : single.substring(0, 157) + "…";
    }

    private static String formatDuration(double seconds) {
        if (seconds < 90) {
            return String.format(Locale.ROOT, "%.1f s", seconds);
        }
        long total = Math.round(seconds);
        return total < 3600
            ? String.format(Locale.ROOT, "%dm %02ds", total / 60, total % 60)
            : String.format(Locale.ROOT, "%dh %02dm", total / 3600, (total % 3600) / 60);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) {
                options.put(args[i].substring(2), args[i + 1]);
            }
        }
        return options;
    }

    // ------------------------------------------------------------- collectors

    /** Wraps the real service to time it and keep a few pairs for eyeballing. */
    private static final class Recorder implements TranslationService {
        private final TranslationService delegate;
        private final int sampleLimit;
        final Map<String, String> samples = new LinkedHashMap<>();
        int calls;
        int failures;
        long chars;

        Recorder(TranslationService delegate, int sampleLimit) {
            this.delegate = delegate;
            this.sampleLimit = sampleLimit;
        }

        @Override
        public String translate(String text, String sourceLang, String targetLang) {
            return delegate.translate(text, sourceLang, targetLang);
        }

        @Override
        public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang) {
            calls++;
            texts.forEach(text -> chars += text.length());
            List<String> result = delegate.translateBatch(texts, sourceLang, targetLang);
            if (result == null || result.size() != texts.size()) {
                failures++;
                return result;
            }
            for (int i = 0; i < texts.size() && samples.size() < sampleLimit; i++) {
                // Prefer segments with markup: those are where a local model actually struggles.
                if (texts.get(i).contains("KTPH") && texts.get(i).length() > 60) {
                    samples.putIfAbsent(texts.get(i), result.get(i));
                }
            }
            return result;
        }

        @Override
        public boolean testConnection() {
            return delegate.testConnection();
        }
    }

    /** Prints a line per 5% so a multi-hour run visibly progresses. */
    private static final class ProgressPrinter implements java.util.function.Consumer<Double> {
        private final long start = System.nanoTime();
        private int lastBucket = -1;

        @Override
        public void accept(Double fraction) {
            int bucket = (int) (fraction * 20);
            if (bucket == lastBucket) {
                return;
            }
            lastBucket = bucket;
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            double remaining = fraction > 0.01 ? seconds / fraction - seconds : 0;
            System.out.printf("  %3.0f%%  %s elapsed%s%n", fraction * 100, formatDuration(seconds),
                remaining > 1 ? ", ~" + formatDuration(remaining) + " left" : "");
            // Explicit: System.out is block-buffered when Gradle's output is redirected, so
            // without this a multi-hour run shows nothing at all until it finishes.
            System.out.flush();
        }
    }
}
