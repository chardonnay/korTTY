package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Runs against the real glossary shipped in {@code /i18n/glossary/}, which is also the file
 * scripts/translate_docs.py reads — these assertions protect the contract between the two.
 */
class TranslationGlossaryTest {

    private static TranslationGlossary german() {
        return TranslationGlossary.forLanguage("de", TranslationGlossary.Scope.HTML);
    }

    @Test
    void germanCorrectsTheProductsOwnVocabulary() {
        TranslationGlossary glossary = german();
        assertThat(glossary.isEmpty()).isFalse();

        // The defect that motivated this: a local model calls the guide "Handbuch" while the
        // application menu calls it "Anleitung".
        assertThat(glossary.apply("korTTY Handbuch öffnen")).isEqualTo("korTTY Anleitung öffnen");
        assertThat(glossary.apply("korTTY Guide")).isEqualTo("korTTY Anleitung");
        assertThat(glossary.apply("Meerjungfrau-Diagramm")).isEqualTo("Mermaid-Diagramm");
        assertThat(glossary.apply("GitHub-Probleme")).isEqualTo("GitHub-Issues");
        assertThat(glossary.apply("ASCII-Kunst")).isEqualTo("ASCII-Art");

        // The dialog the German menu calls "Schnellverbindung" (menu.connections.quickConnect)
        // must not stay "Quick Connect" in translated prose; the contextual rows also carry the
        // feminine article the substitution would otherwise leave ungrammatical.
        assertThat(glossary.apply("Quick Connect")).isEqualTo("Schnellverbindung");
        assertThat(glossary.apply("öffnet Quick Connect, um"))
            .isEqualTo("öffnet die Schnellverbindung, um");
        assertThat(glossary.apply("oder in Quick Connect wählbar"))
            .isEqualTo("oder in der Schnellverbindung wählbar");
    }

    /** Google MT drops (usually doubled) U+200B zero-width spaces into German prose. */
    @Test
    void zeroWidthSpacesAreStripped() {
        assertThat(german().apply("mit welchem \u200b\u200bServer"))
            .isEqualTo("mit welchem Server");
    }

    /** A longer term must be replaced before a shorter one it contains. */
    @Test
    void longerTermsAreCorrectedBeforeTheShorterOnesTheyContain() {
        assertThat(german().apply("Das Benutzerhandbuch")).isEqualTo("Die Anleitung");
        assertThat(german().apply("Bedienungsanleitung")).isEqualTo("Anleitung");
    }

    @Test
    void anExactRowMatchesOnlyAWholeSegment() {
        TranslationGlossary glossary = german();
        // A settings-table cell is the adjective…
        assertThat(glossary.apply("Ermöglicht")).isEqualTo("Aktiviert");
        assertThat(glossary.apply("\n  Ermöglicht\n")).isEqualTo("\n  Aktiviert\n");
        // …the same word inside a sentence is a verb and must survive untouched.
        assertThat(glossary.apply("Dies ermöglicht den Zugriff"))
            .isEqualTo("Dies ermöglicht den Zugriff");
        assertThat(glossary.apply("Ermöglicht den Zugriff")).isEqualTo("Ermöglicht den Zugriff");
    }

    @Test
    void markdownOnlyRowsDoNotApplyToHtml() {
        // "| Ermöglicht |" is a Markdown table cell and would never match rendered HTML anyway;
        // the point is that the HTML scope does not carry it at all.
        assertThat(german().apply("| Ermöglicht |")).isEqualTo("| Ermöglicht |");
        assertThat(TranslationGlossary.forLanguage("de", TranslationGlossary.Scope.MARKDOWN)
            .apply("| Ermöglicht |")).isEqualTo("| Aktiviert |");
    }

    /**
     * The "Store" -> "Wissensspeicher" row matches inside words, so the English "Stored" that
     * stays English on purpose — code-block comments, a Java identifier — came out as
     * "Wissensspeicherd" in the German guide. Repair rows follow it; the legitimate German
     * compound must survive them.
     */
    @Test
    void englishStoredSurvivesTheKnowledgeStoreRow() {
        TranslationGlossary glossary = german();
        assertThat(glossary.apply("credentials.xml   # Stored credentials (encrypted)"))
            .isEqualTo("credentials.xml   # Stored credentials (encrypted)");
        assertThat(glossary.apply("StoredCredential")).isEqualTo("StoredCredential");
        assertThat(glossary.apply("Wissensspeicherdokumente")).isEqualTo("Wissensspeicherdokumente");
        // The row itself still does its job.
        assertThat(glossary.apply("Knowledge Store anlegen")).isEqualTo("Wissensspeicher anlegen");
    }

    /** MT renders "Hardening options" two ways; the guide uses the UI label everywhere. */
    @Test
    void hardeningOptionsUseTheUiLabel() {
        assertThat(german().apply("Härtemöglichkeiten sind Techniken"))
            .isEqualTo("Härtungsoptionen sind Techniken");
    }

    @Test
    void aLanguageWithoutAGlossaryIsEmptyAndLeavesTextAlone() {
        for (String language : List.of("fr", "xx", "", "  ")) {
            TranslationGlossary glossary =
                TranslationGlossary.forLanguage(language, TranslationGlossary.Scope.HTML);
            assertWithMessage(language).that(glossary.isEmpty()).isTrue();
            assertWithMessage(language).that(glossary.apply("Handbuch")).isEqualTo("Handbuch");
        }
        assertThat(TranslationGlossary.forLanguage(null, TranslationGlossary.Scope.HTML).isEmpty())
            .isTrue();
    }

    @Test
    void languageCodesAreCaseInsensitive() {
        assertThat(TranslationGlossary.forLanguage("DE", TranslationGlossary.Scope.HTML).isEmpty())
            .isFalse();
        assertThat(TranslationGlossary.forLanguage(" De ", TranslationGlossary.Scope.HTML).isEmpty())
            .isFalse();
    }

    @Test
    void applyToleratesNullAndEmpty() {
        assertThat(german().apply(null)).isNull();
        assertThat(german().apply("")).isEmpty();
    }

    /**
     * The glossary runs on masked text, so a row must never swallow a placeholder — that would
     * drop markup from the page. The generator guards against it, but a row that trips the guard
     * silently loses its correction, so the shipped table must not contain one.
     */
    @Test
    void noGermanRowDisturbsPlaceholders() {
        String masked = "KTPH000Das BenutzerhandbuchKTPH001 und KTPH002korTTY GuideKTPH003";
        String corrected = german().apply(masked);
        assertThat(GuideTranslationGenerator.placeholdersIntact(masked, corrected)).isTrue();
        assertThat(corrected).contains("Anleitung");
    }
}
