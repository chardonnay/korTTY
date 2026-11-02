package de.kortty.ui;

import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.core.AiSecretGuard;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

/**
 * Every message of the solution card ({@code ai.triage.*}) and of the complexity setting ({@code settings.ai.triage.*})
 * exists, translated, in all eight bundles, keeps the English placeholders, and writes each apostrophe once.
 */
class AiTriageI18nCoverageTest {

    private static final List<String> PREFIXES = List.of("ai.triage.", "settings.ai.triage.", "ai.agent.question.");

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d}");

    @Test
    void everyKeyExistsInEveryBundleWithTheEnglishPlaceholders() throws Exception {
        Properties english = loadBundle("messages.properties");
        Set<String> keys = keys();
        assertWithMessage("triage keys").that(keys.size()).isAtLeast(32);
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : keys) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for " + key).that(value.isBlank()).isFalse();
                assertWithMessage(bundle + " doubles an apostrophe in " + key).that(value).doesNotContain("''");
                assertWithMessage(bundle + " placeholders of " + key)
                    .that(placeholders(value)).isEqualTo(placeholders(english.getProperty(key)));
            }
        }
    }

    @Test
    void translationsAreNotLeftInEnglish() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (String bundle : BUNDLES.subList(1, BUNDLES.size())) {
            Properties localized = loadBundle(bundle);
            for (String key : keys()) {
                assertWithMessage(bundle + " still has the English text for " + key)
                    .that(localized.getProperty(key)).isNotEqualTo(english.getProperty(key));
            }
        }
    }

    @Test
    void everySecretCategoryHasItsName() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (AiSecretGuard.Category category : AiSecretGuard.Category.values()) {
            assertWithMessage("name of " + category)
                .that(english.getProperty(SolutionChoiceRules.categoryKey(category))).isNotNull();
        }
    }

    private static Set<String> keys() throws Exception {
        Set<String> keys = new TreeSet<>();
        for (String name : loadBundle("messages.properties").stringPropertyNames()) {
            for (String prefix : PREFIXES) {
                if (name.startsWith(prefix)) {
                    keys.add(name);
                }
            }
        }
        return keys;
    }

    private static List<String> placeholders(String value) {
        List<String> found = new java.util.ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private static Properties loadBundle(String name) throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = AiTriageI18nCoverageTest.class.getClassLoader().getResourceAsStream("i18n/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundle " + name);
            }
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
