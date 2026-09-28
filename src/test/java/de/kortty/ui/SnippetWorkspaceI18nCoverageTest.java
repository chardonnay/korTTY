package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Every {@code snippets.workspace.*} and {@code snippets.draft.*}, {@code snippets.saveAsNew.*} and {@code snippets.batchExport.*} key used by the workspace code exists in all 8 bundles with
 * the same placeholders as the English one.
 */
class SnippetWorkspaceI18nCoverageTest {

    private static final Path BUNDLES = Path.of("src/main/resources/i18n");
    private static final Path UI_ROOT = Path.of("src/main/java/de/kortty/ui");
    private static final List<String> LOCALES = List.of("", "_de", "_es", "_fr", "_hr", "_it", "_nl", "_pt");
    private static final Pattern KEY_USE = Pattern.compile("\"(snippets\\.(?:workspace|draft|saveAsNew|batchExport)\\.[A-Za-z.]+)\"");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    @Test
    void everyWorkspaceKeyIsTranslatedWithMatchingPlaceholders() throws IOException {
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.list(UI_ROOT)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher matcher = KEY_USE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    used.add(matcher.group(1));
                }
            }
        }
        assertWithMessage("the workspace uses snippets.workspace.* keys").that(used).isNotEmpty();

        Properties english = load("");
        List<String> problems = new ArrayList<>();
        for (String locale : LOCALES) {
            Properties bundle = load(locale);
            for (String key : used) {
                String value = bundle.getProperty(key);
                if (value == null || value.isBlank()) {
                    problems.add("messages" + locale + ": missing " + key);
                } else if (english.getProperty(key) != null
                    && !placeholders(value).equals(placeholders(english.getProperty(key)))) {
                    problems.add("messages" + locale + ": placeholders differ for " + key);
                }
            }
        }
        assertWithMessage("snippet workspace i18n problems").that(problems).isEmpty();
    }

    private static Properties load(String locale) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(BUNDLES.resolve("messages" + locale + ".properties"),
            StandardCharsets.UTF_8)) {
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
}
