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
 * ./gradlew guideTranslationBench --args="--list-profiles"
 * ./gradlew guideTranslationBench --args="--model some-mlx-model-id --estimate 0"
 * ./gradlew guideTranslationBench --args="--profile 'my other model' --estimate 40"
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

        if (options.containsKey("list-profiles")) {
            listProfiles();
            return;
        }
        AiProfile profile = resolveProfile(options.get("profile"));
        String modelOverride = options.get("model");
        if (profile != null && modelOverride != null && !modelOverride.isBlank()) {
            // On a copy: measuring a second model must not rewrite the user's configuration.
            profile = new AiProfile(profile);
            profile.setEmbeddedModelId(modelOverride);
            System.out.println("model override: " + modelOverride);
        }
        if (profile == null) {
            System.err.println("""
                No usable AI profile.

                Pass --profile <name> to measure a specific one, or --list-profiles to see
                what is configured. Without --profile the workload's text profile is used, and
                only when it runs a local model — that is the case this feature exists for:
                Settings -> AI -> a profile with connection mode EMBEDDED_LLAMA_CPP or
                EMBEDDED_MLX, selected as the text/default profile.""");
            System.exit(2);
        }
        ServiceBundle bundle = buildTranslationService(profile);
        TranslationService service = bundle != null ? bundle.service() : null;
        if (service == null) {
            System.err.println("Could not build a translation service from profile '"
                + profile.getName() + "' (" + profile.getConnectionMode() + ").");
            if (profile.getConnectionMode().isEmbedded()) {
                System.err.println("Is the embedded model downloaded and selected?");
            } else {
                // No master password prompt exists here, so an encrypted key cannot be unlocked.
                System.err.println("Non-embedded profiles work here only without a stored API key "
                    + "(a local HTTP endpoint, say) — the key vault needs the running application. "
                    + "Use Settings -> Translation to measure a profile that needs a key.");
            }
            System.exit(2);
        }

        if (options.containsKey("estimate")) {
            int sampleSize = Integer.parseInt(options.getOrDefault("estimate",
                String.valueOf(GuideTranslationGenerator.DEFAULT_ESTIMATE_SAMPLE)));
            System.out.printf("""
                korTTY guide translation estimate
                  profile          : %s  (%s)
                  target           : %s
                  sample requested : %d segment(s) — raised if too small to span several batches
                %n""", profile.getName(), profile.getConnectionMode(), lang, sampleSize);
            System.out.flush();
            long started = System.nanoTime();
            GuideTranslationGenerator.Estimate estimate =
                new GuideTranslationGenerator(service, outRoot).estimate(lang, sampleSize, null);
            double actualSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
            if (estimate.isComplete()) {
                System.out.println("nothing left to translate for this language");
            } else if (!estimate.connectionOk()) {
                System.out.println("CONNECTION FAILED — the AI profile did not answer the probe "
                    + "(" + formatDuration(estimate.elapsedMillis() / 1000.0) + " waiting). "
                    + "No translation was attempted.");
            } else if (!estimate.isUsable()) {
                System.out.println("the sample produced nothing usable — no projection possible");
            } else {
                System.out.printf("""
                      sample took        %s for %d segment(s) / %d chars
                      remaining          %d segment(s) / %d chars
                      PROJECTED FULL RUN %s to %s
                    %n""", formatDuration(estimate.elapsedMillis() / 1000.0),
                    estimate.sampleSegments(), estimate.sampleChars(),
                    estimate.remainingSegments(), estimate.remainingChars(),
                    formatDuration(estimate.lowMillis() / 1000.0),
                    formatDuration(estimate.highMillis() / 1000.0));
            }
            System.out.printf("  (wall clock of this estimate: %s)%n", formatDuration(actualSeconds));
            return;
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

        report(result, recorder, bundle.metering(), elapsedNanos, distinctAll, outRoot, lang);
    }

    // ------------------------------------------------------------------ setup

    /**
     * The production recipe from SettingsDialog.createLocalAiTranslationService(), extended with
     * an explicit choice so a second model can be measured without changing the app's settings.
     *
     * @param wanted profile name or id to use; null selects the workload's text profile
     */
    private static AiProfile resolveProfile(String wanted) throws Exception {
        GlobalSettingsManager manager =
            new GlobalSettingsManager(de.kortty.KorTTYApplication.getConfigDirectory());
        manager.load();
        GlobalSettings settings = manager.getSettings();
        List<AiProfile> profiles = settings.getAiProfiles() != null
            ? settings.getAiProfiles() : List.of();
        if (wanted != null && !wanted.isBlank()) {
            AiProfile match = profiles.stream()
                .filter(p -> wanted.equalsIgnoreCase(p.getName()) || wanted.equals(p.getId()))
                .findFirst().orElse(null);
            if (match == null) {
                System.err.println("No AI profile named '" + wanted + "'. Available:");
                profiles.forEach(p -> System.err.printf("  %-28s %s%n",
                    p.getName(), p.getConnectionMode()));
            }
            return match;
        }
        AiProfile profile = AiProfileSelectionSupport.workloadProfile(
            profiles, AiWorkload.TEXT, settings.getTextAiProfileId(),
            settings.getCodingAiProfileId(), settings.getDefaultAiProfileId());
        return profile != null && profile.getConnectionMode().isEmbedded() ? profile : null;
    }

    private static void listProfiles() throws Exception {
        GlobalSettingsManager manager =
            new GlobalSettingsManager(de.kortty.KorTTYApplication.getConfigDirectory());
        manager.load();
        List<AiProfile> profiles = manager.getSettings().getAiProfiles() != null
            ? manager.getSettings().getAiProfiles() : List.of();
        System.out.println("configured AI profiles:");
        if (profiles.isEmpty()) {
            System.out.println("  (none)");
        }
        profiles.forEach(p -> System.out.printf("  %-28s %-20s %s%n",
            p.getName(), p.getConnectionMode(),
            p.getEmbeddedModelId() != null ? p.getEmbeddedModelId() : ""));
    }

    /** Pairs the translation service under test with the token meter wrapped underneath it. */
    record ServiceBundle(TranslationService service, TokenMeteringAiPromptService metering) {
    }

    private static ServiceBundle buildTranslationService(AiProfile profile) {
        // Copy and disable internet access: the factory rejects a Tavily-backed mode without a
        // key, and translation never needs web search.
        AiProfile translationProfile = new AiProfile(profile);
        translationProfile.setInternetAccessMode(AiInternetAccessMode.DISABLED);
        try {
            AiService service = AiServiceFactory.create(translationProfile, null,
                AiInternetAccessConfiguration.disabled(), AiSkillPromptSupport.disabled());
            if (!(service instanceof AiPromptService prompt)) {
                return null;
            }
            TokenMeteringAiPromptService metering = new TokenMeteringAiPromptService(prompt);
            return new ServiceBundle(new LocalAiTranslationService(metering), metering);
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
                               TokenMeteringAiPromptService metering,
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

        if (metering.callsWithUsage() > 0) {
            System.out.printf("  tokens              %d prompt + %d completion = %d total%s%n",
                metering.promptTokens(), metering.completionTokens(), metering.totalTokens(),
                metering.anyCallMissingUsage() ? "  (some calls reported no usage)" : "");
        } else {
            System.out.println("  tokens              (backend reported no usage data)");
        }

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
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                continue;
            }
            String name = args[i].substring(2);
            boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--");
            options.put(name, hasValue ? args[i + 1] : "");
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
