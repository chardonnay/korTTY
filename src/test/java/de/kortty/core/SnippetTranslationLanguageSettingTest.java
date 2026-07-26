package de.kortty.core;

import de.kortty.model.GlobalSettings;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static com.google.common.truth.Truth.assertThat;

/**
 * The snippet editor's remembered translation target language. It holds either a dropdown language
 * code or a name the user typed, because the value reaches the model as prompt text and is never
 * parsed as a locale.
 */
class SnippetTranslationLanguageSettingTest {

    Path tempDir;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-snippet-translation-test");
    }

    @AfterMethod
    void deleteTempDir() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    private String roundTrip(String stored) throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(tempDir);
        manager.load();
        manager.getSettings().setSnippetTranslationTargetLanguage(stored);
        manager.save();

        GlobalSettingsManager reloaded = new GlobalSettingsManager(tempDir);
        reloaded.load();
        return reloaded.getSettings().getSnippetTranslationTargetLanguage();
    }

    @Test
    void aLanguageCodeSurvivesSaveAndReload() throws Exception {
        assertThat(roundTrip("fr")).isEqualTo("fr");
    }

    /** A typed language must round-trip verbatim, including one the dropdown never offers. */
    @Test
    void aTypedLanguageNameSurvivesSaveAndReload() throws Exception {
        assertThat(roundTrip("Schwäbisch")).isEqualTo("Schwäbisch");
        assertThat(roundTrip("Brazilian Portuguese")).isEqualTo("Brazilian Portuguese");
    }

    @Test
    void aLaterChoiceReplacesTheEarlierOne() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(tempDir);
        manager.load();
        manager.getSettings().setSnippetTranslationTargetLanguage("Klingon");
        manager.save();
        manager.getSettings().setSnippetTranslationTargetLanguage("it");
        manager.save();

        GlobalSettingsManager reloaded = new GlobalSettingsManager(tempDir);
        reloaded.load();
        assertThat(reloaded.getSettings().getSnippetTranslationTargetLanguage()).isEqualTo("it");
    }

    @Test
    void blankInputIsStoredAsNothingRatherThanAnEmptyLanguage() {
        GlobalSettings settings = new GlobalSettings();
        settings.setSnippetTranslationTargetLanguage("   ");
        assertThat(settings.getSnippetTranslationTargetLanguage()).isNull();
        settings.setSnippetTranslationTargetLanguage(null);
        assertThat(settings.getSnippetTranslationTargetLanguage()).isNull();
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        GlobalSettings settings = new GlobalSettings();
        settings.setSnippetTranslationTargetLanguage("  Norwegian Bokmål  ");
        assertThat(settings.getSnippetTranslationTargetLanguage()).isEqualTo("Norwegian Bokmål");
    }

    /** Never chosen yet must read as null so the dialog can fall back to the AI-text default. */
    @Test
    void anUntouchedSettingIsNull() {
        assertThat(new GlobalSettings().getSnippetTranslationTargetLanguage()).isNull();
    }
}
