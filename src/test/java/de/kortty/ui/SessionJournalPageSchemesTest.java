package de.kortty.ui;

import de.kortty.core.SessionJournalPageAppearance;
import de.kortty.model.SessionJournalPageScheme;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalPageSchemesTest {

    @Test
    void offersTheDerivedSchemesFirstAndTheFixedPalettesAfterThem() {
        List<String> ids = SessionJournalPageSchemes.all().stream()
            .map(SessionJournalPageScheme::id).toList();

        assertThat(ids).containsAtLeast("auto", "theme").inOrder();
        assertThat(ids.get(0)).isEqualTo("auto");
        assertThat(ids).containsNoDuplicates();
    }

    @Test
    void anUnknownSchemeIdFallsBackToAutoRatherThanToNothing() {
        assertThat(SessionJournalPageSchemes.byId("does-not-exist").id()).isEqualTo("auto");
        assertThat(SessionJournalPageSchemes.byId(null).id()).isEqualTo("auto");
    }

    @Test
    void everyFixedSchemeCarriesCompleteAndValidColours() {
        for (SessionJournalPageScheme scheme : SessionJournalPageSchemes.all()) {
            if (scheme.derived()) {
                continue;
            }
            for (String colour : List.of(scheme.bg(), scheme.surface(), scheme.surface2(),
                scheme.border(), scheme.text(), scheme.muted(), scheme.accent(),
                scheme.input(), scheme.output(), scheme.mark(), scheme.markCurrent())) {
                assertThat(colour).matches("#[0-9a-fA-F]{6}");
            }
            assertThat(scheme.name()).isNotEmpty();
        }
    }

    @Test
    void producesAScopedCssBlockThatOutranksTheThemeBlocks() {
        String css = SessionJournalPageAppearance.schemeCss(SessionJournalPageSchemes.byId("paper"));

        assertThat(css).startsWith("html[data-scheme=\"paper\"]{");
        assertThat(css).contains("--bg:#f7f3ea");
        assertThat(css).contains("--mark-cur:");
        assertThat(css).endsWith("}\n");
    }

    @Test
    void emitsNoBlockForTheAutomaticScheme() {
        assertThat(SessionJournalPageAppearance.schemeCss(SessionJournalPageSchemes.byId("auto"))).isEmpty();
        assertThat(SessionJournalPageAppearance.schemeCss(null)).isEmpty();
    }

    @Test
    void buildsTheInlineStyleWithScaleAndSanitizedFonts() {
        SessionJournalPageAppearance appearance =
            new SessionJournalPageAppearance("paper", "Segoe UI", "JetBrains Mono", 125);

        String style = appearance.htmlStyle();

        assertThat(style).contains("--font-scale:1.25");
        assertThat(style).contains("--ui-font:'Segoe UI',ui-sans-serif,sans-serif");
        assertThat(style).contains("--mono-font:'JetBrains Mono',ui-monospace,monospace");
    }

    @Test
    void keepsAHostileFontNameOutOfTheStyleAttribute() {
        SessionJournalPageAppearance appearance = new SessionJournalPageAppearance(
            "auto", "Arial';background:url(http://evil/x)'", null, 100);

        String style = appearance.htmlStyle();

        // The letters may survive; what must not is the CSS syntax that would let them do
        // anything — quotes to close the value, a semicolon to start a new declaration, and the
        // parentheses and slashes of a url() reference.
        assertThat(style).doesNotContain("url(");
        assertThat(style).doesNotContain("';");
        assertThat(style).doesNotContain("//");
        assertThat(style).contains("--ui-font:'Arialbackgroundurlhttpevilx'");
    }

    @Test
    void clampsTheFontScaleAndNormalizesBlankValues() {
        assertThat(new SessionJournalPageAppearance("auto", " ", "", 10).fontScalePercent())
            .isEqualTo(SessionJournalPageAppearance.MIN_FONT_SCALE);
        assertThat(new SessionJournalPageAppearance("auto", null, null, 9999).fontScalePercent())
            .isEqualTo(SessionJournalPageAppearance.MAX_FONT_SCALE);
        SessionJournalPageAppearance blanked = new SessionJournalPageAppearance("auto", "  ", "  ", 100);
        assertThat(blanked.uiFont()).isNull();
        assertThat(blanked.monoFont()).isNull();
    }

    @Test
    void thePreviewScriptSetsThePropertiesAFixedSchemeNeeds() {
        SessionJournalPageAppearance appearance =
            new SessionJournalPageAppearance("paper", null, "Menlo", 110);

        String js = appearance.previewScript(SessionJournalPageSchemes.byId("paper"));

        assertThat(js).startsWith("(function(){");
        assertThat(js).endsWith("})()");
        assertThat(js).contains("root.setAttribute('data-scheme',\"paper\")");
        assertThat(js).contains("\"--bg\",\"#f7f3ea\"");
        // Quotes inside the JS string literal arrive escaped; the browser turns them back.
        assertThat(js).contains("--mono-font:");
        assertThat(js).contains("Menlo");
    }

    @Test
    void thePreviewScriptClearsEverythingWhenSwitchingBackToAuto() {
        String js = SessionJournalPageAppearance.defaults()
            .previewScript(SessionJournalPageSchemes.byId("auto"));

        assertThat(js).contains("root.removeAttribute('data-scheme')");
        assertThat(js).contains("removeProperty");
        assertThat(js).doesNotContain("setProperty");
    }

    @Test
    void onlyANonAutomaticSchemeCountsAsFixed() {
        assertThat(SessionJournalPageAppearance.defaults().hasFixedScheme()).isFalse();
        assertThat(new SessionJournalPageAppearance("paper", null, null, 100).hasFixedScheme()).isTrue();
    }
}
