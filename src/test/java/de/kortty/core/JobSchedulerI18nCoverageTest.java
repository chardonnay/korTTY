package de.kortty.core;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.google.common.truth.Truth.assertThat;

class JobSchedulerI18nCoverageTest {

    private static final String PREFIX = "jobscheduler.dialog.";
    private static final List<String> BUNDLES = List.of(
        "messages.properties", "messages_de.properties", "messages_es.properties",
        "messages_fr.properties", "messages_hr.properties", "messages_it.properties",
        "messages_nl.properties", "messages_pt.properties");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");
    private static final Map<String, Set<String>> SHARED_TECHNICAL_TERMS = Map.of(
        "messages_de.properties", Set.of("action.rsync", "action.sudo", "archiveFormat.tar", "archiveFormat.tar_bz2", "archiveFormat.zip", "job.column.job", "job.journal", "job.name", "journal.column.detail", "journal.column.job", "journal.column.status", "journal.column.stderr", "journal.column.stdout", "tab.job", "tab.journal", "title"),
        "messages_es.properties", Set.of("archiveFormat.tar_bz2", "journal.column.stderr"),
        "messages_fr.properties", Set.of("action.compression", "action.rsync", "action.sudo", "action.type", "archiveFormat.tar_bz2", "job.journal", "journal.column.stderr", "tab.action", "tab.journal"),
        "messages_hr.properties", Set.of("action.rsync", "action.sudo", "actionType.ai_agent", "archiveFormat.tar_bz2", "archiveFormat.zip", "journal.column.status", "journal.column.stderr", "journal.column.stdout", "title"),
        "messages_it.properties", Set.of("action.sudo", "archiveFormat.tar_bz2", "journal.column.stderr", "journal.column.stdout", "title"),
        "messages_nl.properties", Set.of("action.rsync", "action.sudo", "archiveFormat.tar_bz2", "archiveFormat.zip", "journal.column.detail", "journal.column.status", "journal.column.stderr", "target.servers"),
        "messages_pt.properties", Set.of("action.sudo", "archiveFormat.tar_bz2", "journal.column.status", "journal.column.stderr"));

    @Test
    void everyLocaleContainsEveryJobSchedulerDialogKeyWithMatchingPlaceholders() throws Exception {
        Properties english = load("messages.properties");
        Set<String> keys = english.stringPropertyNames().stream()
            .filter(key -> key.startsWith(PREFIX))
            .collect(Collectors.toSet());
        assertThat(keys.size()).isAtLeast(170);

        for (String bundle : BUNDLES) {
            Properties localized = load(bundle);
            for (String key : keys) {
                assertThat(localized.getProperty(key)).isNotNull();
                assertThat(placeholders(localized.getProperty(key)))
                    .containsExactlyElementsIn(placeholders(english.getProperty(key)));
            }
        }
    }

    @Test
    void importantGermanLabelsAreActuallyLocalized() throws Exception {
        Properties german = load("messages_de.properties");
        assertThat(german.getProperty(PREFIX + "enabled")).isEqualTo("Aktiviert");
        assertThat(german.getProperty(PREFIX + "tab.job")).isEqualTo("Job");
        assertThat(german.getProperty(PREFIX + "tab.journal")).isEqualTo("Journal");
        assertThat(german.getProperty(PREFIX + "weekday.wednesday")).isEqualTo("Mi");
        assertThat(german.getProperty(PREFIX + "weekday.sunday")).isEqualTo("So");
        assertThat(german.getProperty(PREFIX + "archiveFormat.zip")).isEqualTo("ZIP");
        assertThat(german.getProperty(PREFIX + "archiveFormat.tar")).isEqualTo("TAR");
        assertThat(german.getProperty(PREFIX + "error.unknown")).isEqualTo("Unbekannter Fehler.");
    }

    @Test
    void everyLocaleTranslatesAllNonTechnicalJobSchedulerLabels() throws Exception {
        Properties english = load("messages.properties");
        Set<String> keys = english.stringPropertyNames().stream()
            .filter(key -> key.startsWith(PREFIX))
            .collect(Collectors.toSet());

        for (Map.Entry<String, Set<String>> locale : SHARED_TECHNICAL_TERMS.entrySet()) {
            Properties localized = load(locale.getKey());
            Set<String> stillEnglish = keys.stream()
                .filter(key -> english.getProperty(key).equals(localized.getProperty(key)))
                .map(key -> key.substring(PREFIX.length()))
                .collect(Collectors.toSet());
            assertThat(stillEnglish).containsExactlyElementsIn(locale.getValue());
        }
    }

    private static Set<String> placeholders(String value) {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value != null ? value : "");
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private static Properties load(String bundle) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = JobSchedulerI18nCoverageTest.class
            .getResourceAsStream("/i18n/" + bundle)) {
            assertThat(input).isNotNull();
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
