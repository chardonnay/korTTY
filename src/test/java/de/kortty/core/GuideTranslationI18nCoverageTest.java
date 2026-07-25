package de.kortty.core;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Every bundled locale must carry the guide-translation strings. A missing key does not fail the
 * build, it renders as the raw key in the settings dialog — so the check has to live in a test.
 */
class GuideTranslationI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "/i18n/messages.properties",
        "/i18n/messages_en.properties",
        "/i18n/messages_de.properties",
        "/i18n/messages_es.properties",
        "/i18n/messages_fr.properties",
        "/i18n/messages_hr.properties",
        "/i18n/messages_it.properties",
        "/i18n/messages_nl.properties",
        "/i18n/messages_pt.properties");

    private static final List<String> REQUIRED_KEYS = List.of(
        "settings.translation.guide.section",
        "settings.translation.guide.hint",
        "settings.translation.guide.generate",
        "settings.translation.guide.cancel",
        "settings.translation.guide.generated",
        "settings.translation.guide.progress",
        "settings.translation.guide.success",
        "settings.translation.guide.cancelled",
        "settings.translation.guide.error",
        "settings.translation.guide.deleteTitle",
        "settings.translation.guide.deleteConfirm",
        "settings.translation.guide.estimate",
        "settings.translation.guide.estimateRunning",
        "settings.translation.guide.estimateResult",
        "settings.translation.guide.estimateComplete",
        "settings.translation.guide.estimateFailed");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    private static Properties load(String resource) throws IOException {
        try (InputStream in = GuideTranslationI18nCoverageTest.class.getResourceAsStream(resource)) {
            assertWithMessage("bundle " + resource).that(in).isNotNull();
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        }
    }

    @Test
    void everyBundledLocaleDefinesTheGuideTranslationStrings() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String bundle : BUNDLES) {
            Properties properties = load(bundle);
            for (String key : REQUIRED_KEYS) {
                String value = properties.getProperty(key);
                if (value == null || value.isBlank()) {
                    missing.add(bundle + " -> " + key);
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    /**
     * A locale that drops a {0} shows the reader an un-substituted sentence; one that invents a
     * {1} throws at format time. Both are only visible at runtime, so they are asserted here.
     */
    @Test
    void placeholdersMatchTheBaseBundleInEveryLocale() throws IOException {
        Properties base = load(BUNDLES.getFirst());
        List<String> problems = new ArrayList<>();
        for (String bundle : BUNDLES) {
            Properties properties = load(bundle);
            for (String key : REQUIRED_KEYS) {
                String expected = placeholders(base.getProperty(key));
                String actual = placeholders(properties.getProperty(key));
                if (!expected.equals(actual)) {
                    problems.add(bundle + " -> " + key + ": expected " + expected + ", got " + actual);
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    /** German must use the product's own term for the guide, not a synonym. */
    @Test
    void germanUsesTheCanonicalTermForTheGuide() throws IOException {
        Properties german = load("/i18n/messages_de.properties");
        for (String key : REQUIRED_KEYS) {
            String value = german.getProperty(key);
            assertWithMessage(key).that(value).doesNotContain("Handbuch");
            assertWithMessage(key).that(value).doesNotContain("Leitfaden");
        }
        assertThat(german.getProperty("settings.translation.guide.section")).contains("Anleitung");
    }

    private static String placeholders(String value) {
        if (value == null) {
            return "(missing)";
        }
        List<String> found = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        found.sort(String::compareTo);
        return found.toString();
    }
}
