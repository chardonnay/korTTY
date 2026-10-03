package de.kortty.ui;

import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.core.highlight.HighlightBuiltinSets;
import de.kortty.core.highlight.HighlightRuleValidator;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.testng.annotations.Test;

/**
 * Every keyword-highlighting string exists, translated, in all eight bundles: the built-in set names,
 * the validator's messages and the menu and status texts. Placeholders must survive translation, and
 * an apostrophe is written once, because LanguageManager fills {0} with String.replace rather than
 * MessageFormat (a doubled apostrophe would show up doubled).
 */
class TerminalHighlightingI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    private static final List<String> MENU_KEYS = List.of(
        HighlightMenuSupport.MENU_KEY,
        HighlightMenuSupport.TOGGLE_KEY,
        HighlightMenuSupport.NONE_KEY,
        HighlightMenuSupport.STATUS_ON_KEY,
        HighlightMenuSupport.STATUS_OFF_KEY,
        HighlightMenuSupport.DISABLED_KEY);

    private static List<String> requiredKeys() {
        List<String> keys = new ArrayList<>(MENU_KEYS);
        for (String id : HighlightBuiltinSets.IDS) {
            keys.add(HighlightBuiltinSets.nameKey(id));
        }
        keys.addAll(HighlightRuleValidator.MESSAGE_KEYS);
        return keys;
    }

    @Test
    void everyHighlightingKeyExistsInEveryBundledLocale() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : requiredKeys()) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for key " + key).that(value.isBlank()).isFalse();
                assertWithMessage(bundle + " doubles an apostrophe in " + key).that(value).doesNotContain("''");
            }
        }
    }

    @Test
    void placeholdersSurviveTranslation() throws Exception {
        List<String> withArgument = new ArrayList<>(List.of(HighlightMenuSupport.STATUS_ON_KEY));
        for (String key : HighlightRuleValidator.MESSAGE_KEYS) {
            if (HighlightRuleValidator.messageArguments(key).length > 0) {
                withArgument.add(key);
            }
        }
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : withArgument) {
                assertWithMessage(bundle + " lost the {0} of " + key).that(localized.getProperty(key)).contains("{0}");
            }
        }
    }

    @Test
    void translationsAreNotLeftInEnglish() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (String bundle : BUNDLES.subList(1, BUNDLES.size())) {
            Properties localized = loadBundle(bundle);
            for (String key : MENU_KEYS) {
                assertWithMessage(bundle + " still has the English text for " + key)
                    .that(localized.getProperty(key)).isNotEqualTo(english.getProperty(key));
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
