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
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import static com.google.common.truth.Truth.assertThat;

/**
 * Runs against the real i18n bundle on the test classpath, the same way
 * {@link GuideTranslationGeneratorTest} runs against the real guide manifests.
 */
class DynamicLanguageGeneratorTest {

    Path tempDir;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-i18n-translation-test");
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

    @Test
    void everyKeyIsTranslatedWhenTheServiceNeverFails() throws IOException {
        Map<String, String> base = DynamicLanguageGenerator.loadBaseProperties();

        DynamicLanguageGenerator generator = new DynamicLanguageGenerator(new PrefixService(), tempDir);
        Path written = generator.generate("xx", null);

        Properties result = load(written);
        assertThat(result.stringPropertyNames()).hasSize(base.size());
        for (String key : base.keySet()) {
            assertThat(result.getProperty(key)).isEqualTo("XX:" + base.get(key));
        }
    }

    /**
     * The load-bearing regression: a single batch that comes back null or the wrong size used
     * to throw and abort the whole bundle (no file was written at all). Now the poisoned batch
     * is retried and halved until only the value the model can't handle is left; that one value
     * keeps its English text, and everything else still gets translated.
     */
    @Test
    void aKeyTheModelCanNeverTranslateKeepsItsEnglishTextInsteadOfAbortingTheRun() throws IOException {
        Map<String, String> base = DynamicLanguageGenerator.loadBaseProperties();
        Map<String, Long> valueCounts = base.values().stream()
            .collect(Collectors.groupingBy(v -> v, Collectors.counting()));
        // A unique, placeholder-free value: masking is a no-op for it, and poisoning it can't
        // accidentally also poison an unrelated key that happens to share the same English text.
        Map.Entry<String, String> poisoned = base.entrySet().stream()
            .filter(e -> valueCounts.get(e.getValue()) == 1L)
            .filter(e -> e.getValue().length() > 3)
            .filter(e -> e.getValue().indexOf('{') < 0 && e.getValue().indexOf('$') < 0)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no suitable key found to poison"));
        String survivorKey = base.keySet().stream()
            .filter(k -> !k.equals(poisoned.getKey())).findFirst().orElseThrow();

        DynamicLanguageGenerator generator =
            new DynamicLanguageGenerator(new FlakyService(poisoned.getValue()), tempDir);
        Path written = generator.generate("xx", null);

        Properties result = load(written);
        assertThat(result.stringPropertyNames()).hasSize(base.size());
        assertThat(result.getProperty(poisoned.getKey())).isEqualTo(poisoned.getValue());
        assertThat(result.getProperty(survivorKey)).isEqualTo("XX:" + base.get(survivorKey));
    }

    private static Properties load(Path file) throws IOException {
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    private static final class PrefixService implements TranslationService {
        @Override
        public String translate(String text, String sourceLang, String targetLang) {
            return "XX:" + text;
        }

        @Override
        public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang) {
            return texts.stream().map(t -> "XX:" + t).collect(Collectors.toList());
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }

    /** Returns nothing usable for any batch containing {@code poison}, however small the batch. */
    private static final class FlakyService implements TranslationService {
        private final String poison;

        FlakyService(String poison) {
            this.poison = poison;
        }

        @Override
        public String translate(String text, String sourceLang, String targetLang) {
            List<String> result = translateBatch(List.of(text), sourceLang, targetLang);
            return result != null ? result.get(0) : null;
        }

        @Override
        public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang) {
            if (texts.contains(poison)) {
                return null;
            }
            return texts.stream().map(t -> "XX:" + t).collect(Collectors.toList());
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
