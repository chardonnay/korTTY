package de.kortty.ui;

import de.kortty.core.KeymapOverrides.Problem;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Every string of the Settings → Keyboard page exists, translated, in all eight bundles: the tab,
 * the page's texts, the status of a row, the sentence of every {@link Problem} and the names of the
 * fixed shortcuts without a menu item. Placeholders survive translation, and an apostrophe is
 * written once, because LanguageManager fills {0} with String.replace rather than MessageFormat.
 * Every key sits under settings.keyboard. (the tab's name under settings.tab.), so the guide page
 * that owns that prefix documents them.
 */
class KeyboardSettingsI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    /** Words a language shares with English: "Status" and "Tabs" in German, "Menu" and "Action" elsewhere. */
    private static final Map<String, Set<String>> MAY_EQUAL_ENGLISH = Map.of(
        "messages_de.properties", Set.of("settings.keyboard.column.status", "settings.keyboard.category.tabs"),
        "messages_it.properties", Set.of("settings.keyboard.column.menu"),
        "messages_pt.properties", Set.of("settings.keyboard.column.menu"),
        "messages_fr.properties", Set.of("settings.keyboard.column.action", "settings.keyboard.column.menu"),
        "messages_nl.properties", Set.of("settings.keyboard.column.menu", "settings.keyboard.column.status"));

    private static List<String> keys() {
        Set<String> keys = new LinkedHashSet<>(KeyboardSettingsModel.KEYS);
        keys.addAll(KeyboardSettingsPage.KEYS);
        return new ArrayList<>(keys);
    }

    @Test
    void everyKeyExistsInEveryBundledLocale() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : keys()) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for key " + key).that(value.isBlank()).isFalse();
                assertWithMessage(bundle + " doubles an apostrophe in " + key).that(value).doesNotContain("''");
            }
        }
    }

    @Test
    void everyProblemAndEveryFixedShortcutWithoutAMenuItemHasAKey() {
        for (Problem problem : Problem.values()) {
            assertWithMessage(problem.name()).that(keys()).contains(KeyboardSettingsModel.PROBLEM_KEYS.get(problem));
        }
        for (String owner : List.of(KeymapSupport.FIXED_TAB_JUMP, KeymapSupport.FIXED_NEXT_TAB,
                KeymapSupport.FIXED_PREVIOUS_TAB)) {
            assertWithMessage(owner).that(keys()).contains(KeyboardSettingsModel.FIXED_OWNER_KEYS.get(owner));
        }
    }

    @Test
    void everyKeyBelongsToTheKeyboardPagesPrefix() {
        for (String key : keys()) {
            assertWithMessage(key).that(key.startsWith("settings.keyboard.") || key.equals("settings.tab.keyboard"))
                .isTrue();
        }
    }

    @Test
    void placeholdersSurviveTranslation() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : keys()) {
                for (String placeholder : List.of("{0}", "{1}")) {
                    assertWithMessage(bundle + " " + key + " " + placeholder)
                        .that(localized.getProperty(key).contains(placeholder))
                        .isEqualTo(english.getProperty(key).contains(placeholder));
                }
            }
        }
        // The sentences the model fills: the chord, and the other action where one is named.
        assertThat(english.getProperty("settings.keyboard.problem.conflict")).contains("{1}");
        assertThat(english.getProperty("settings.keyboard.problem.fixed")).contains("{1}");
        assertThat(english.getProperty("settings.keyboard.problem.required")).contains("{1}");
        assertThat(english.getProperty("settings.keyboard.problem.reservedShell")).contains("{0}");
        assertThat(english.getProperty("settings.keyboard.problem.unknownAction")).contains("{0}");
    }

    @Test
    void translationsAreNotLeftInEnglish() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (String bundle : BUNDLES.subList(1, BUNDLES.size())) {
            Properties localized = loadBundle(bundle);
            Set<String> allowed = MAY_EQUAL_ENGLISH.getOrDefault(bundle, Set.of());
            for (String key : keys()) {
                if (allowed.contains(key)) {
                    continue;
                }
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
