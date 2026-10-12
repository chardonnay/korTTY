package de.kortty.core;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Guards the Logfile Manager strings in every bundled locale. A locale that lacks one of these keys
 * shows English in the middle of a translated dialog, which is easy to miss in review, so the list is
 * spelled out here instead of being derived from the English bundle. A translation that drops a
 * placeholder is worse: the message then reports something other than what actually happened.
 */
class LogfileManagerI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
            "messages.properties",
            "messages_de.properties",
            "messages_fr.properties",
            "messages_es.properties",
            "messages_it.properties",
            "messages_nl.properties",
            "messages_pt.properties",
            "messages_hr.properties");

    private static final List<String> REQUIRED_KEYS = List.of(
            "logfile.export.cancelled",
            "logfile.export.chooseFolder",
            "logfile.export.cutHint",
            "logfile.export.done",
            "logfile.export.failed",
            "logfile.export.format",
            "logfile.export.header",
            "logfile.export.openTarget",
            "logfile.export.overwrite.content",
            "logfile.export.overwrite.header",
            "logfile.export.progress.message",
            "logfile.export.progress.title",
            "logfile.export.range",
            "logfile.export.rangeFrom",
            "logfile.export.rangeInvalid",
            "logfile.export.rangeTo",
            "logfile.export.saveAs",
            "logfile.export.skippedEmpty",
            "logfile.export.title",
            "logfile.export.wouldBeSwept",
            "logfile.manager.addFolder",
            "logfile.manager.addFolder.title",
            "logfile.manager.column.compression",
            "logfile.manager.column.deletableFrom",
            "logfile.manager.column.deletableFrom.tooltip",
            "logfile.manager.column.folder",
            "logfile.manager.column.name",
            "logfile.manager.column.size",
            "logfile.manager.column.size.tooltip",
            "logfile.manager.column.started",
            "logfile.manager.column.type",
            "logfile.manager.compression.bzip2",
            "logfile.manager.compression.gzip",
            "logfile.manager.compression.none",
            "logfile.manager.compression.zstd",
            "logfile.manager.delete",
            "logfile.manager.delete.content",
            "logfile.manager.delete.done",
            "logfile.manager.delete.failed",
            "logfile.manager.delete.header",
            "logfile.manager.delete.plainWarning",
            "logfile.manager.delete.title",
            "logfile.manager.empty",
            "logfile.manager.error",
            "logfile.manager.export",
            "logfile.manager.filter.all",
            "logfile.manager.filter.clear",
            "logfile.manager.filter.compression",
            "logfile.manager.filter.from",
            "logfile.manager.filter.text",
            "logfile.manager.filter.text.prompt",
            "logfile.manager.filter.time.prompt",
            "logfile.manager.filter.timeInvalid",
            "logfile.manager.filter.to",
            "logfile.manager.filter.type",
            "logfile.manager.forgetFolder",
            "logfile.manager.header",
            "logfile.manager.live",
            "logfile.manager.liveSkipped",
            "logfile.manager.noMatch",
            "logfile.manager.noSelection",
            "logfile.manager.openFolder",
            "logfile.manager.refresh",
            "logfile.manager.scanFailed",
            "logfile.manager.scanning",
            "logfile.manager.status",
            "logfile.manager.title",
            "logfile.manager.view",
            "logfile.viewer.clickToCopy",
            "logfile.viewer.close",
            "logfile.viewer.copied",
            "logfile.viewer.copyLine",
            "logfile.viewer.copySelection",
            "logfile.viewer.copyTooLarge",
            "logfile.viewer.decompressing",
            "logfile.viewer.error",
            "logfile.viewer.find",
            "logfile.viewer.find.prompt",
            "logfile.viewer.findHint",
            "logfile.viewer.goToLine",
            "logfile.viewer.goToLine.prompt",
            "logfile.viewer.highlightKeywords",
            "logfile.viewer.highlightLinks",
            "logfile.viewer.hitBeyondView",
            "logfile.viewer.hits",
            "logfile.viewer.hitsCapped",
            "logfile.viewer.indexing",
            "logfile.viewer.info",
            "logfile.viewer.keywordSet",
            "logfile.viewer.lineRange",
            "logfile.viewer.next",
            "logfile.viewer.noMatch",
            "logfile.viewer.placeholder",
            "logfile.viewer.previous",
            "logfile.viewer.refresh",
            "logfile.viewer.reload",
            "logfile.viewer.rotated",
            "logfile.viewer.snapshot",
            "logfile.viewer.title",
            "logfile.viewer.truncated",
            "menu.tools.logfileManager");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    @Test
    void everyLogfileManagerKeyExistsInEveryBundledLocale() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for " + key)
                        .that(value.isBlank()).isFalse();
            }
        }
    }

    @Test
    void everyPlaceholderSurvivesTranslation() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                String source = english.getProperty(key);
                assertWithMessage("English has no value for " + key).that(source).isNotNull();
                Matcher matcher = PLACEHOLDER.matcher(source);
                while (matcher.find()) {
                    // A dropped {0} turns the message into a lie about what went wrong.
                    assertWithMessage(bundle + " lost " + matcher.group() + " from " + key)
                            .that(localized.getProperty(key)).contains(matcher.group());
                }
            }
        }
    }

    private Properties loadBundle(String fileName) throws Exception {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("i18n/" + fileName)) {
            assertWithMessage("Missing i18n bundle " + fileName).that(inputStream).isNotNull();
            Properties properties = new Properties();
            properties.load(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            return properties;
        }
    }
}
