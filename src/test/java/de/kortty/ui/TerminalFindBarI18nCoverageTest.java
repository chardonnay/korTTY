package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.sithtermfx.ui.settings.SearchBarText;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.testng.annotations.Test;

/**
 * The terminal find bar's texts exist, translated, in all eight bundles; the match counter keeps both
 * placeholders; {@link TerminalFindBarText} fills them the way LanguageManager does and never shows a raw
 * key; and KorTTYSettingsProvider hands it to SithTermFX.
 */
class TerminalFindBarI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    @Test
    void everyFindBarKeyExistsInEveryBundledLocale() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : TerminalFindBarText.KEYS) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for key " + key).that(value.isBlank()).isFalse();
                assertWithMessage(bundle + " doubles an apostrophe in " + key).that(value).doesNotContain("''");
            }
        }
    }

    @Test
    void theCounterKeepsBothPlaceholdersInOrder() throws Exception {
        for (String bundle : BUNDLES) {
            assertWithMessage(bundle).that(loadBundle(bundle).getProperty(TerminalFindBarText.COUNTER_KEY))
                .containsMatch("\\{0\\}.*\\{1\\}");
        }
    }

    @Test
    void translationsAreNotLeftInEnglish() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (String bundle : BUNDLES.subList(1, BUNDLES.size())) {
            Properties localized = loadBundle(bundle);
            for (String key : TerminalFindBarText.KEYS) {
                assertWithMessage(bundle + " still has the English text for " + key)
                    .that(localized.getProperty(key)).isNotEqualTo(english.getProperty(key));
            }
        }
    }

    @Test
    void theEnglishBundleMatchesSithTermFxsDefaultsWhereTheyAgree() throws Exception {
        SearchBarText text = new TerminalFindBarText(lookupFrom(loadBundle("messages.properties")));
        assertThat(text.placeholder()).isEqualTo(SearchBarText.ENGLISH.placeholder());
        assertThat(text.matchCounter(3, 12)).isEqualTo(SearchBarText.ENGLISH.matchCounter(3, 12));
        assertThat(text.noMatches()).isEqualTo(SearchBarText.ENGLISH.noMatches());
    }

    @Test
    void theGermanBarIsGerman() throws Exception {
        SearchBarText text = new TerminalFindBarText(lookupFrom(loadBundle("messages_de.properties")));
        assertThat(text.placeholder()).isEqualTo("Suchen");
        assertThat(text.matchCounter(3, 12)).isEqualTo("3 von 12");
        assertThat(text.noMatches()).isEqualTo("Keine Treffer");
        assertThat(text.ignoreCase()).isEqualTo("Groß-/Kleinschreibung ignorieren");
        // The buttons keep their arrows; only the tooltips are translated.
        assertThat(text.nextMatch()).isEqualTo("▼");
        assertThat(text.previousMatch()).isEqualTo("▲");
        assertThat(text.nextMatchTooltip()).startsWith("Nächster Treffer");
        assertThat(text.previousMatchTooltip()).startsWith("Vorheriger Treffer");
    }

    @Test
    void aMissingKeyFallsBackToEnglishInsteadOfShowingTheKey() {
        // LanguageManager answers an unknown key with the key itself.
        SearchBarText text = new TerminalFindBarText((key, args) -> key);
        assertThat(text.placeholder()).isEqualTo(SearchBarText.ENGLISH.placeholder());
        assertThat(text.ignoreCase()).isEqualTo(SearchBarText.ENGLISH.ignoreCase());
        assertThat(text.ignoreCaseTooltip()).isEqualTo(SearchBarText.ENGLISH.ignoreCaseTooltip());
        assertThat(text.matchCounter(1, 2)).isEqualTo(SearchBarText.ENGLISH.matchCounter(1, 2));
        assertThat(text.noMatches()).isEqualTo(SearchBarText.ENGLISH.noMatches());
        assertThat(text.nextMatchTooltip()).isEqualTo(SearchBarText.ENGLISH.nextMatchTooltip());
        assertThat(text.previousMatchTooltip()).isEqualTo(SearchBarText.ENGLISH.previousMatchTooltip());
    }

    @Test
    void theTerminalSettingsProviderHandsTheTranslatedTextsToSithTermFx() throws Exception {
        String source = Files.readString(Path.of("src/main/java/de/kortty/ui/TerminalView.java"))
            .replace("\r\n", "\n");
        int provider = source.indexOf("private static class KorTTYSettingsProvider");
        assertThat(provider).isAtLeast(0);
        assertThat(source.substring(provider)).containsMatch(
            "public @NotNull com\\.sithtermfx\\.ui\\.settings\\.SearchBarText getSearchBarText\\(\\) \\{\\s*"
                + "return TerminalFindBarText\\.INSTANCE;");
    }

    @Test
    void thePaneSettingsProviderReturnsTheTranslatedTexts() throws Exception {
        Class<?> cls = Class.forName("de.kortty.ui.TerminalView$KorTTYSettingsProvider");
        java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor(de.kortty.model.ConnectionSettings.class,
            com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider.class, java.util.function.IntSupplier.class);
        ctor.setAccessible(true);
        com.sithtermfx.ui.settings.SystemSettingsProvider provider =
            (com.sithtermfx.ui.settings.SystemSettingsProvider) ctor.newInstance(new de.kortty.model.ConnectionSettings(),
                new com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider(14f), (java.util.function.IntSupplier) () -> 0);
        assertThat(provider.getSearchBarText()).isSameInstanceAs(TerminalFindBarText.INSTANCE);
    }

    /** Fills {0}, {1}… with String.replace, as LanguageManager.getString(key, args) does. */
    private static java.util.function.BiFunction<String, Object[], String> lookupFrom(Properties bundle) {
        return (key, args) -> {
            String value = bundle.getProperty(key, key);
            for (int i = 0; i < args.length; i++) {
                value = value.replace("{" + i + "}", String.valueOf(args[i]));
            }
            return value;
        };
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
