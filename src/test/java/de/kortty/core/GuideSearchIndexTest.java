package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/** Runs against the real bundled guide search index on the test classpath. */
class GuideSearchIndexTest {

    @Test
    void loadsBothBundledLanguages() {
        for (String lang : new String[] {"en", "de"}) {
            GuideSearchIndex index = GuideSearchIndex.load(lang);
            assertWithMessage("index for " + lang).that(index).isNotNull();
            assertWithMessage("entry count for " + lang)
                .that(index.entries().size()).isGreaterThan(400);
        }
    }

    @Test
    void missingLanguageReturnsNull() {
        assertThat(GuideSearchIndex.load("xx")).isNull();
    }

    @Test
    void entriesHaveHtmlStrippedAndLocationsSplit() {
        GuideSearchIndex index = GuideSearchIndex.load("en");
        boolean sawAnchor = false;
        for (GuideSearchIndex.Entry entry : index.entries()) {
            assertWithMessage("tags stripped in " + entry.location())
                .that(entry.plainText()).doesNotContain("<p>");
            assertWithMessage("tags stripped in " + entry.location())
                .that(entry.plainText()).doesNotContain("<ul>");
            assertWithMessage("pageTitle of " + entry.location())
                .that(entry.pageTitle()).isNotEmpty();
            if (entry.anchor() != null) {
                sawAnchor = true;
                assertThat(entry.location())
                    .isEqualTo(entry.pagePath() + "#" + entry.anchor());
            } else {
                assertThat(entry.location()).isEqualTo(entry.pagePath());
            }
        }
        assertThat(sawAnchor).isTrue();
    }

    @Test
    void cleanTextPreservesEscapedCodeExamples() {
        // Real markup is stripped; entity-escaped code samples survive as text.
        assertThat(GuideSearchIndex.cleanText("<p>Run <code>agent &lt;goal&gt;</code> now</p>"))
            .isEqualTo("Run agent <goal> now");
    }

    @Test
    void germanAnchorsAreLocalized() {
        GuideSearchIndex de = GuideSearchIndex.load("de");
        boolean found = de.entries().stream().anyMatch(entry ->
            "features/ai-tools.html#so-funktioniert-der-ki-agent".equals(entry.location()));
        assertWithMessage("localized DE anchor present").that(found).isTrue();
    }
}
