package de.kortty.ui;

import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

/**
 * Every string of the "Sent along" disclosure exists in all eight bundles with the same placeholders as the English text.
 */
class SentContextI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_es.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_it.properties",
        "messages_nl.properties",
        "messages_pt.properties");

    private static final String PREFIX = "ai.memory.sent.";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d}");

    private static Properties loadBundle(String name) throws IOException {
        Path file = Path.of("src/main/resources/i18n").resolve(name);
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    @Test
    void everySentContextKeyExistsInEveryBundleWithTheEnglishPlaceholders() throws Exception {
        Properties english = loadBundle("messages.properties");
        Set<String> keys = new TreeSet<>();
        for (String name : english.stringPropertyNames()) {
            if (name.startsWith(PREFIX)) {
                keys.add(name);
            }
        }
        assertWithMessage("sent context keys").that(keys.size()).isAtLeast(14);
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
}
