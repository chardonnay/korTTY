package de.kortty.core;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertWithMessage;

class SessionJournalI18nCoverageTest {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\d+\\}");

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
        "menu.tools.sessionJournals",
        "menu.tools.toggleSessionJournal",
        "menu.tools.journalScreenshot",
        "connEdit.tab.journal",
        "connEdit.journal.enable",
        "connEdit.journal.captureInput",
        "connEdit.journal.aiSummaries",
        "connEdit.journal.summaryInterval",
        "connEdit.journal.info",
        "terminal.journal.start",
        "terminal.journal.stop",
        "terminal.journal.off",
        "terminal.journal.activeSince",
        "terminal.journal.stoppedAt",
        "terminal.journal.screenshot",
        "terminal.journal.screenshotAdded",
        "terminal.journal.note",
        "terminal.journal.noteAdded",
        "terminal.journal.note.title",
        "terminal.journal.note.header",
        "terminal.journal.note.content",
        "terminal.journal.error.title",
        "terminal.journal.error.header",
        "terminal.journal.error.notConnected",
        "terminal.journal.error.notActive",
        "terminal.journal.error.policy",
        "terminal.journal.error.enforced",
        "terminal.journal.error.start",
        "terminal.journal.error.screenshot",
        "terminal.journal.error.note",
        "terminal.contextMenu.journalScreenshot",
        "terminal.contextMenu.journalNote",
        "tab.contextMenu.journal",
        "tab.contextMenu.journal.start",
        "tab.contextMenu.journal.stop",
        "tab.contextMenu.journal.screenshot",
        "tab.contextMenu.journal.note",
        "journal.manager.title",
        "journal.manager.search.prompt",
        "journal.manager.fulltext",
        "journal.manager.empty",
        "journal.manager.column.started",
        "journal.manager.column.duration",
        "journal.manager.column.connection",
        "journal.manager.column.server",
        "journal.manager.column.title",
        "journal.manager.column.entries",
        "journal.manager.running",
        "journal.manager.open",
        "journal.manager.rename",
        "journal.manager.delete",
        "journal.manager.refresh",
        "journal.manager.export",
        "journal.manager.options",
        "journal.manager.rename.title",
        "journal.manager.rename.header",
        "journal.manager.delete.title",
        "journal.manager.delete.header",
        "journal.manager.delete.content",
        "journal.manager.description",
        "journal.manager.description.save",
        "journal.manager.description.saved",
        "journal.options.title",
        "journal.options.logFormat",
        "journal.options.maxLines",
        "journal.options.tokenBudget",
        "journal.options.chunking",
        "journal.options.chunking.warning",
        "journal.options.aiTitle",
        "journal.options.managed",
        "journal.export.pdf",
        "journal.export.markdown",
        "journal.export.htmlBundle",
        "journal.export.title",
        "journal.export.includeScreenshots",
        "journal.export.file.pdf",
        "journal.export.file.markdown",
        "journal.export.file.bundle",
        "journal.export.done",
        "journal.export.error",
        "journal.viewer.openBrowser",
        "journal.viewer.edit",
        "journal.viewer.refresh",
        "journal.viewer.entries",
        "journal.viewer.column.time",
        "journal.viewer.column.kind",
        "journal.viewer.column.marker",
        "journal.viewer.column.text",
        "journal.viewer.marker",
        "journal.viewer.note",
        "journal.viewer.save",
        "journal.viewer.revert",
        "journal.viewer.saved",
        "journal.marker.none",
        "journal.marker.info",
        "journal.marker.important",
        "journal.marker.error",
        "journal.summary.raw.title",
        "journal.summary.raw.text",
        "journal.summary.failed.title",
        "journal.summary.failed.text",
        "journal.summary.final.title",
        "journal.html.defaultTitle",
        "journal.html.live",
        "journal.html.started",
        "journal.html.duration",
        "journal.html.entries",
        "journal.html.commands",
        "journal.html.errors",
        "journal.html.screenshots",
        "journal.html.screenshot",
        "journal.html.theme",
        "journal.html.empty",
        "journal.html.raw",
        "journal.html.failed",
        "journal.html.sessionSummary",
        "journal.html.hiddenInput",
        "journal.html.search.placeholder",
        "journal.pdf.started",
        "journal.pdf.duration",
        "journal.pdf.commands",
        "journal.pdf.errors",
        "journal.pdf.screenshots",
        "journal.pdf.note",
        "journal.pdf.sessionSummary",
        "journal.pdf.footer",
        "journal.md.connection",
        "journal.md.started",
        "journal.md.duration",
        "journal.md.commands",
        "journal.md.errors",
        "journal.md.screenshots",
        "journal.md.description",
        "journal.md.input",
        "journal.md.output",
        "journal.md.note",
        "journal.md.screenshot",
        "settings.journal.section",
        "settings.journal.storagePath",
        "settings.journal.browse",
        "settings.journal.aiSummaries",
        "settings.journal.interval",
        "settings.journal.aiProfile",
        "settings.journal.defaultProfile");

    @Test
    void allSessionJournalKeysExistInEveryBundledLocaleAndKeepPlaceholderCounts() throws Exception {
        Properties base = loadBundle("messages.properties");

        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                String baseValue = base.getProperty(key);
                String localizedValue = localized.getProperty(key);
                assertWithMessage("Base bundle is missing key " + key).that(baseValue).isNotNull();
                assertWithMessage(bundle + " is missing key " + key).that(localizedValue).isNotNull();
                assertWithMessage(bundle + " has different placeholder count for key " + key)
                    .that(countPlaceholders(localizedValue)).isEqualTo(countPlaceholders(baseValue));
                assertWithMessage(bundle + " has blank value for key " + key)
                    .that(!localizedValue.isBlank()).isTrue();
            }
        }
    }

    private Properties loadBundle(String fileName) throws Exception {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("i18n/" + fileName)) {
            assertWithMessage("Missing i18n bundle " + fileName).that(inputStream).isNotNull();
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
