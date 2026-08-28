package de.kortty.core;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertWithMessage;

class TerminalLogI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
            "messages.properties",
            "messages_de.properties",
            "messages_it.properties",
            "messages_es.properties",
            "messages_pt.properties",
            "messages_fr.properties",
            "messages_hr.properties",
            "messages_nl.properties");

    private static final List<String> REQUIRED_KEYS = List.of(
            "connEdit.loggingInfo",
            "connEdit.log.directory",
            "connEdit.log.namePreview",
            "connEdit.log.selectDirectory",
            "connEdit.log.compress",
            "connEdit.log.compress.hint",
            "connEdit.log.rotateDaily",
            "connEdit.log.retention",
            "connEdit.log.retention.unit",
            "connEdit.log.retention.hint",
            "quickConnect.section.terminalLog",
            "quickConnect.log.enable",
            "quickConnect.log.hint",
            "terminal.log.error.start");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    @Test
    void everyTerminalLogKeyExistsInEveryBundledLocale() throws Exception {
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
                Matcher matcher = PLACEHOLDER.matcher(english.getProperty(key));
                while (matcher.find()) {
                    // A dropped {0} turns the message into a lie about what went wrong.
                    assertWithMessage(bundle + " lost " + matcher.group() + " from " + key)
                            .that(localized.getProperty(key)).contains(matcher.group());
                }
            }
        }
    }

    @Test
    void theFormatNameIsQuotedVerbatimEverywhere() throws Exception {
        // "Plain Text" is the literal label of TerminalLogConfig.LogFormat.PLAIN_TEXT in the
        // format combo box, so the help text has to name it exactly, in every language.
        for (String bundle : BUNDLES) {
            String info = loadBundle(bundle).getProperty("connEdit.loggingInfo");
            for (String formatName : List.of("Plain Text", "XML", "JSON")) {
                assertWithMessage(bundle + " does not name the format " + formatName)
                        .that(info).contains(formatName);
            }
        }
    }

    @Test
    void everyBundleDescribesTheCurrentBehaviourRatherThanTheOldOne() throws Exception {
        for (String bundle : BUNDLES) {
            String info = loadBundle(bundle).getProperty("connEdit.loggingInfo");

            // The naming scheme is the substance of this help text, and the placeholders are
            // language-independent, so their presence is a reliable proof it was updated.
            for (String token : List.of("<date>", "<time>", "<server>", "<number>")) {
                assertWithMessage(bundle + " does not explain the file naming (" + token + ")")
                        .that(info).contains(token);
            }
            // Non-ASCII characters used to be stripped and the text said so. They no longer are.
            // "ANSI" is still mentioned and must not trip this — it is a different word.
            assertWithMessage(bundle + " still claims non-ASCII characters are removed")
                    .that(info.toLowerCase()).doesNotContain("ascii");
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
}
