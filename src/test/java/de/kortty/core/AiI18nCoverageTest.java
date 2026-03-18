package de.kortty.core;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_en.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    private static final List<String> REQUIRED_KEYS = List.of(
        "menu.tools.aiManager",
        "ai.action.generateTitle",
        "ai.saved.defaultTitle",
        "ai.manager.title",
        "ai.manager.header",
        "ai.manager.empty",
        "ai.manager.column.title",
        "ai.manager.column.profile",
        "ai.manager.column.updated",
        "ai.manager.column.connection",
        "ai.manager.open",
        "ai.manager.rename",
        "ai.manager.delete",
        "ai.manager.refresh",
        "ai.manager.opened",
        "ai.manager.renamed",
        "ai.manager.deleted",
        "ai.manager.delete.title",
        "ai.manager.delete.header",
        "ai.manager.delete.content",
        "ai.profile.missing.title",
        "ai.profile.missing.header",
        "ai.profile.missing.content",
        "ai.result.profile",
        "ai.result.save",
        "ai.result.share",
        "ai.result.readOnly",
        "ai.result.save.title",
        "ai.result.save.header",
        "ai.result.rename.title",
        "ai.result.rename.header",
        "ai.result.save.label",
        "ai.result.save.generating",
        "ai.result.save.generated",
        "ai.result.save.generateFailed",
        "ai.result.save.success",
        "ai.result.save.failed",
        "ai.result.save.autosaveFailed",
        "ai.result.share.success",
        "ai.result.share.directoryFallback",
        "ai.result.share.failed",
        "ai.table.copyTable",
        "ai.table.copyTable.success",
        "ai.table.copyColumn",
        "ai.table.copyColumn.success");

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\d+}");

    @Test
    void allNewAiKeysExistInEveryBundledLocaleAndKeepPlaceholderCounts() throws Exception {
        Properties base = loadBundle("messages.properties");

        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                String baseValue = base.getProperty(key);
                String localizedValue = localized.getProperty(key);
                assertNotNull(baseValue, "Base bundle is missing key " + key);
                assertNotNull(localizedValue, bundle + " is missing key " + key);
                assertEquals(countPlaceholders(baseValue), countPlaceholders(localizedValue),
                    bundle + " has different placeholder count for key " + key);
                assertTrue(!localizedValue.isBlank(), bundle + " has blank value for key " + key);
            }
        }
    }

    private Properties loadBundle(String fileName) throws Exception {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("i18n/" + fileName)) {
            assertNotNull(inputStream, "Missing i18n bundle " + fileName);
            Properties properties = new Properties();
            properties.load(new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8));
            return properties;
        }
    }

    private int countPlaceholders(String value) {
        int count = 0;
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
