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
 * the validator's messages, the menu and status texts, the Settings → Terminal section, the rule-set
 * editor with its color names, the connection editor's rule-set dropdown, the texts of a trigger's
 * notification and those of a rule that runs a snippet. Placeholders must survive translation, and
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
        HighlightMenuSupport.DISABLED_KEY,
        HighlightMenuSupport.MANAGE_KEY);

    /**
     * Keys whose text may legitimately read the same as in English: "{0}: {1}", "Snippet:", which several
     * languages borrow unchanged, and color names such as Magenta and Cyan, for the same reason.
     */
    private static final List<String> MAY_EQUAL_ENGLISH;

    static {
        List<String> keys = new ArrayList<>(List.of(HighlightRulesEditorModel.PROBLEM_SET_KEY,
            HighlightTriggerDispatcher.BODY_WITH_TEXT_KEY, HighlightRulesDialog.SNIPPET_KEY,
            HighlightSnippetTrigger.CONFIRM_PREVIEW_KEY));
        keys.addAll(HighlightColorChoices.NAME_KEYS);
        MAY_EQUAL_ENGLISH = List.copyOf(keys);
    }

    /** Every key of the editor and its color choices. */
    private static List<String> editorKeys() {
        List<String> keys = new ArrayList<>(HighlightRulesDialog.KEYS);
        keys.addAll(HighlightRulesEditorModel.KEYS);
        keys.addAll(HighlightColorChoices.KEYS);
        return keys;
    }

    /** The texts a user reads, which must not stay English in a translated bundle. */
    private static List<String> translatedKeys() {
        List<String> keys = new ArrayList<>(MENU_KEYS);
        keys.addAll(HighlightSettingsSupport.KEYS);
        keys.addAll(HighlightConnectionSupport.KEYS);
        keys.addAll(HighlightTriggerDispatcher.KEYS);
        keys.addAll(HighlightSnippetTrigger.KEYS);
        keys.addAll(editorKeys());
        keys.removeAll(MAY_EQUAL_ENGLISH);
        return keys;
    }

    private static List<String> requiredKeys() {
        List<String> keys = new ArrayList<>(MENU_KEYS);
        keys.addAll(HighlightSettingsSupport.KEYS);
        keys.addAll(HighlightConnectionSupport.KEYS);
        keys.addAll(HighlightTriggerDispatcher.KEYS);
        keys.addAll(HighlightSnippetTrigger.KEYS);
        keys.addAll(editorKeys());
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
        List<String> withArgument = new ArrayList<>(List.of(HighlightMenuSupport.STATUS_ON_KEY,
            HighlightSettingsSupport.DEFAULT_SET_UNKNOWN_KEY, HighlightRulesDialog.PREVIEW_TRUNCATED_KEY,
            HighlightRulesEditorModel.COPY_NAME_KEY, HighlightColorChoices.BRIGHT_KEY,
            HighlightConnectionSupport.DEFAULT_KEY, HighlightConnectionSupport.UNKNOWN_KEY,
            HighlightTriggerDispatcher.TOOLTIP_KEY));
        for (String key : HighlightRuleValidator.MESSAGE_KEYS) {
            if (HighlightRuleValidator.messageArguments(key).length > 0) {
                withArgument.add(key);
            }
        }
        // These name the invisible character ({0} = U+0007); without it the user could not find it.
        withArgument.addAll(HighlightRuleValidator.UNSTORABLE_KEYS);
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : withArgument) {
                assertWithMessage(bundle + " lost the {0} of " + key).that(localized.getProperty(key)).contains("{0}");
            }
        }
    }

    @Test
    void theEditorsFooterKeepsAllItsPlaceholders() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            assertWithMessage(bundle).that(localized.getProperty(HighlightRulesEditorModel.PROBLEM_RULE_KEY))
                .containsMatch("\\{0\\}.*\\{1\\}.*\\{2\\}");
            assertWithMessage(bundle).that(localized.getProperty(HighlightRulesEditorModel.PROBLEM_SET_KEY))
                .containsMatch("\\{0\\}.*\\{1\\}");
        }
    }

    @Test
    void aTriggersNotificationKeepsTheRuleNameAndTheText() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            assertWithMessage(bundle).that(localized.getProperty(HighlightTriggerDispatcher.BODY_WITH_TEXT_KEY))
                .containsMatch("\\{0\\}.*\\{1\\}");
        }
    }

    @Test
    void theSnippetTriggerTextsKeepEveryPlaceholder() throws Exception {
        java.util.Map<String, Integer> placeholders = java.util.Map.of(
            HighlightSnippetTrigger.CONFIRM_TEXT_KEY, 3,
            HighlightSnippetTrigger.CONFIRM_DETAILS_KEY, 2,
            HighlightSnippetTrigger.RAN_KEY, 1,
            HighlightSnippetTrigger.LOOP_STOPPED_KEY, 2,
            HighlightSnippetTrigger.MISSING_KEY, 1,
            HighlightSnippetTrigger.NEEDS_VALUE_KEY, 2,
            HighlightSnippetTrigger.UNSUPPORTED_KEY, 1);
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            placeholders.forEach((key, count) -> {
                for (int i = 0; i < count; i++) {
                    assertWithMessage(bundle + " lost the {" + i + "} of " + key)
                        .that(localized.getProperty(key)).contains("{" + i + "}");
                }
            });
        }
    }

    @Test
    void translationsAreNotLeftInEnglish() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (String bundle : BUNDLES.subList(1, BUNDLES.size())) {
            Properties localized = loadBundle(bundle);
            for (String key : translatedKeys()) {
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
