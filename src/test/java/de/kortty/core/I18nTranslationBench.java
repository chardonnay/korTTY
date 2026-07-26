package de.kortty.core;

import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiWorkload;
import de.kortty.model.GlobalSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Measures what the configured local AI profile actually does to the UI language bundle: how
 * fast it translates {@code i18n/messages.properties}, and how well.
 *
 * <p>Companion to {@link GuideTranslationBench} for the other translation surface in the app —
 * see that class for the rationale (drives the real {@link DynamicLanguageGenerator}, not a
 * hand-rolled call; settings come from the real config directory, output goes to
 * build/benchmark so a benchmark run never leaves a generated bundle in {@code ~/.kortty}).
 *
 * <pre>
 * ./gradlew i18nTranslationBench                       # all UI strings -> German
 * ./gradlew i18nTranslationBench --args="--lang fr"
 * ./gradlew i18nTranslationBench --args="--list-profiles"
 * ./gradlew i18nTranslationBench --args="--model some-mlx-model-id"
 * ./gradlew i18nTranslationBench --args="--profile 'my other model' --lang af"
 * </pre>
 */
public final class I18nTranslationBench {

    private I18nTranslationBench() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        String lang = options.getOrDefault("lang", "de");
        Path outRoot = Path.of(options.getOrDefault("out", "build/benchmark"));

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
        ServiceBundleHolder bundle = buildTranslationService(profile);
        if (bundle == null) {
            System.err.println("Could not build a translation service from profile '"
                + profile.getName() + "' (" + profile.getConnectionMode() + ").");
            if (profile.getConnectionMode().isEmbedded()) {
                System.err.println("Is the embedded model downloaded and selected?");
            } else {
                System.err.println("Non-embedded profiles work here only without a stored API key "
                    + "(a local HTTP endpoint, say) — the key vault needs the running application. "
                    + "Use Settings -> Translation to measure a profile that needs a key.");
            }
            System.exit(2);
        }

        Map<String, String> base = DynamicLanguageGenerator.loadBaseProperties();
        int totalKeys = base.size();
        long totalChars = base.values().stream().mapToLong(String::length).sum();

        System.out.printf("""
            korTTY i18n translation benchmark
              profile      : %s  (%s)
              target       : %s
              keys         : %d  (%d chars)
              output       : %s
            %n""", profile.getName(), profile.getConnectionMode(), lang,
            totalKeys, totalChars, outRoot.resolve("i18n"));
        System.out.flush();

        DynamicLanguageGenerator generator = new DynamicLanguageGenerator(bundle.service(), outRoot);
        long start = System.nanoTime();
        Path written = generator.generate(lang, new ProgressPrinter());
        long elapsedNanos = System.nanoTime() - start;

        report(written, bundle.metering(), bundle.resilience(), elapsedNanos, totalKeys, totalChars, lang);
    }

    // ------------------------------------------------------------------ setup

    /** Same recipe as {@link GuideTranslationBench#resolveProfile}. */
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

    record ServiceBundleHolder(TranslationService service, TokenMeteringAiPromptService metering,
                                ResilientTranslationService resilience) {
    }

    private static ServiceBundleHolder buildTranslationService(AiProfile profile) {
        AiProfile translationProfile = new AiProfile(profile);
        translationProfile.setInternetAccessMode(AiInternetAccessMode.DISABLED);
        try {
            AiService service = AiServiceFactory.create(translationProfile, null,
                AiInternetAccessConfiguration.disabled(), AiSkillPromptSupport.disabled());
            if (!(service instanceof AiPromptService prompt)) {
                return null;
            }
            TokenMeteringAiPromptService metering = new TokenMeteringAiPromptService(prompt);
            ResilientTranslationService resilient =
                new ResilientTranslationService(new LocalAiTranslationService(metering));
            return new ServiceBundleHolder(resilient, metering, resilient);
        } catch (RuntimeException e) {
            System.err.println("AI service could not be created: " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------- reporting

    private static void report(Path written, TokenMeteringAiPromptService metering,
                                ResilientTranslationService resilience,
                                long elapsedNanos, int totalKeys, long totalChars, String lang)
            throws IOException {
        double seconds = elapsedNanos / 1_000_000_000.0;

        System.out.printf("""

            results
              wall clock          %s
              keys translated     %d  (%d refused -> kept English)
              batch retries       %d
              chars sent          %d
            %n""", formatDuration(seconds), totalKeys, resilience.refused(),
            resilience.retries(), totalChars);

        if (metering.callsWithUsage() > 0) {
            System.out.printf("  tokens              %d prompt + %d completion = %d total%s%n",
                metering.promptTokens(), metering.completionTokens(), metering.totalTokens(),
                metering.anyCallMissingUsage() ? "  (some calls reported no usage)" : "");
        } else {
            System.out.println("  tokens              (backend reported no usage data)");
        }

        System.out.printf("  throughput          %.2f key(s)/s, %.0f chars/s%n",
            totalKeys / seconds, totalChars / seconds);

        System.out.println("\ntranslated bundle: " + written.toAbsolutePath());
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

    /** Prints a line per 5% so a long run visibly progresses. */
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
            System.out.flush();
        }
    }
}
