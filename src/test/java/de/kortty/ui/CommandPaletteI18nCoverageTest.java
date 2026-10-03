package de.kortty.ui;

import de.kortty.ui.actions.PaletteEntry;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Every command palette string exists, translated, in all eight bundles: the View menu item, the
 * palette's own texts, the kind badges and the tab actions. Placeholders survive translation, and
 * an apostrophe is written once, because LanguageManager fills {0} with String.replace rather than
 * MessageFormat. The helpers that build a row's texts are tried on the English bundle.
 */
class CommandPaletteI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    private static final List<String> TAB_ACTION_KEYS = List.of(
        "palette.category.tab", "palette.action.nextTab", "palette.action.previousTab");

    /** Words a language shares with English: "Tab" in German and Croatian, "Snippet" in German, Italian and Portuguese. */
    private static final Map<String, Set<String>> MAY_EQUAL_ENGLISH = Map.of(
        "messages_de.properties", Set.of("palette.kind.tab", "palette.kind.snippet", "palette.category.tab"),
        "messages_it.properties", Set.of("palette.kind.snippet"),
        "messages_pt.properties", Set.of("palette.kind.snippet"),
        "messages_hr.properties", Set.of("palette.kind.tab"));

    private static List<String> keys() {
        List<String> keys = new ArrayList<>(List.of("menu.view.commandPalette"));
        keys.addAll(CommandPalettePopup.KEYS);
        keys.addAll(TAB_ACTION_KEYS);
        return keys;
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
    void everyKindHasABadge() {
        for (PaletteEntry.Kind kind : PaletteEntry.Kind.values()) {
            String key = "palette.kind." + kind.name().toLowerCase(java.util.Locale.ROOT);
            assertWithMessage("badge key of " + kind).that(CommandPalettePopup.KEYS).contains(key);
        }
    }

    @Test
    void placeholdersSurviveTranslation() throws Exception {
        for (String bundle : BUNDLES) {
            assertWithMessage(bundle).that(loadBundle(bundle).getProperty("palette.scopes")).contains("{0}");
        }
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

    @Test
    void aRowIsReadOutWithItsKindDetailStateAndShortcut() {
        PaletteEntry dashboard = new PaletteEntry(PaletteEntry.Kind.ACTION, "action:menu.view.dashboard",
            "Show Dashboard", "View", "Ctrl+Shift+D", true, "", true, () -> { });
        PaletteEntry plain = new PaletteEntry(PaletteEntry.Kind.ACTION, "action:x", "About korTTY", "", "",
            true, "", false, () -> { });

        assertThat(CommandPalettePopup.accessibleText(dashboard)).isEqualTo(String.join(", ", "Show Dashboard",
            I18n.get("palette.kind.action"), "View", I18n.get("palette.checked"), "Ctrl+Shift+D"));
        assertThat(CommandPalettePopup.accessibleText(plain))
            .isEqualTo("About korTTY, " + I18n.get("palette.kind.action"));
    }

    @Test
    void theFooterNamesTheScopePrefixesOnlyWhenThereIsMoreThanOneKind() {
        assertThat(CommandPalettePopup.hintText(Set.of(PaletteEntry.Kind.ACTION))).isEqualTo(I18n.get("palette.hint"));

        String twoKinds = CommandPalettePopup.hintText(
            java.util.EnumSet.of(PaletteEntry.Kind.ACTION, PaletteEntry.Kind.TAB));
        assertThat(twoKinds).startsWith(I18n.get("palette.hint") + "\n");
        assertThat(twoKinds).contains("> " + I18n.get("palette.kind.action"));
        assertThat(twoKinds).contains("# " + I18n.get("palette.kind.tab"));
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
