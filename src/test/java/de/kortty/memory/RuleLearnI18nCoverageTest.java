package de.kortty.memory;

import static com.google.common.truth.Truth.assertWithMessage;

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
 * Every message of the rule learning ({@code ai.learn.*}) exists, translated, in all eight bundles, keeps the English
 * placeholders, and writes each apostrophe once: {@code I18n} fills {0} with String.replace.
 */
class RuleLearnI18nCoverageTest {

    private static final String PREFIX = "ai.learn.";

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
    void everyRefusalReasonHasItsMessage() throws Exception {
        Set<String> keys = keys();
        for (RuleSaveException.Reason reason : RuleSaveException.Reason.values()) {
            assertWithMessage("message of " + reason).that(keys).contains(reason.messageKey());
        }
    }

    @Test
    void everyKeyExistsInEveryBundleWithTheEnglishPlaceholders() throws Exception {
        Properties english = loadBundle("messages.properties");
        Set<String> keys = keys();
        assertWithMessage("rule learning keys").that(keys.size()).isAtLeast(21);
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : keys) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for key " + key).that(value.isBlank()).isFalse();
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

    private static Set<String> keys() throws Exception {
        Set<String> keys = new TreeSet<>();
        for (String name : loadBundle("messages.properties").stringPropertyNames()) {
            if (name.startsWith(PREFIX)) {
                keys.add(name);
            }
        }
        return keys;
    }

    private static Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value != null ? value : "");
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private static Properties loadBundle(String fileName) throws Exception {
        try (InputStream inputStream = RuleLearnI18nCoverageTest.class.getClassLoader()
            .getResourceAsStream("i18n/" + fileName)) {
            assertWithMessage("Missing i18n bundle " + fileName).that(inputStream).isNotNull();
            Properties properties = new Properties();
            properties.load(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            return properties;
        }
    }
}
