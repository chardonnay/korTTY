package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/** Guards the note linkifier: it produces links for http(s) and nothing else, ever. */
class SessionJournalNoteLinkTest {

    @Test
    void turnsABareUrlIntoALink() {
        String html = SessionJournalHtmlRenderer.escapeHtmlWithLinks(
            "see https://example.test/docs for details");

        assertThat(html).isEqualTo("see <a class=\"ext\" href=\"https://example.test/docs\""
            + " rel=\"noopener noreferrer\" target=\"_blank\">https://example.test/docs</a>"
            + " for details");
    }

    @Test
    void linksSeveralUrlsAndKeepsTheTextAroundThem() {
        String html = SessionJournalHtmlRenderer.escapeHtmlWithLinks(
            "ticket http://tracker.test/1 and http://tracker.test/2");

        assertThat(html).contains("href=\"http://tracker.test/1\"");
        assertThat(html).contains("href=\"http://tracker.test/2\"");
        assertThat(html).startsWith("ticket ");
        assertThat(html).contains("</a> and <a");
    }

    @Test
    void neverLinksASchemeThatIsNotHttp() {
        // The whole point of matching the scheme literally: no note can produce an executable href.
        String html = SessionJournalHtmlRenderer.escapeHtmlWithLinks(
            "javascript:alert(1) and file:///etc/passwd and data:text/html;base64,AA");

        assertThat(html).doesNotContain("<a");
        assertThat(html).doesNotContain("href");
    }

    @Test
    void escapesMarkupInsideAndAroundALink() {
        String html = SessionJournalHtmlRenderer.escapeHtmlWithLinks(
            "<b>note</b> https://example.test/a?x=1&y=2");

        assertThat(html).startsWith("&lt;b&gt;note&lt;/b&gt; ");
        // The ampersand is escaped in the attribute as well as in the visible label.
        assertThat(html).contains("href=\"https://example.test/a?x=1&amp;y=2\"");
        assertThat(html).doesNotContain("<b>");
    }

    @Test
    void aTagInjectionAttemptStaysText() {
        String html = SessionJournalHtmlRenderer.escapeHtmlWithLinks(
            "<a href=\"javascript:alert(1)\">click</a>");

        assertThat(html).doesNotContain("<a href");
        assertThat(html).contains("&lt;a href=&quot;javascript:alert(1)&quot;&gt;");
    }

    @Test
    void dropsSentencePunctuationFromTheLinkButKeepsItInTheText() {
        String html = SessionJournalHtmlRenderer.escapeHtmlWithLinks("read https://example.test/a.");

        assertThat(html).contains("href=\"https://example.test/a\"");
        assertThat(html).endsWith("</a>.");
    }

    @Test
    void keepsBalancedBracketsInsideTheUrl() {
        String html = SessionJournalHtmlRenderer.escapeHtmlWithLinks(
            "see https://example.test/a_(b) here");
        assertThat(html).contains("href=\"https://example.test/a_(b)\"");

        String wrapped = SessionJournalHtmlRenderer.escapeHtmlWithLinks(
            "(see https://example.test/a)");
        assertThat(wrapped).contains("href=\"https://example.test/a\"");
        assertThat(wrapped).endsWith("</a>)");
    }

    @Test
    void textWithoutUrlsIsPlainEscapedText() {
        assertThat(SessionJournalHtmlRenderer.escapeHtmlWithLinks("plain & <text>"))
            .isEqualTo("plain &amp; &lt;text&gt;");
        assertThat(SessionJournalHtmlRenderer.escapeHtmlWithLinks(null)).isEmpty();
    }

    @Test
    void aSchemeWithoutAHostStaysText() {
        assertThat(SessionJournalHtmlRenderer.escapeHtmlWithLinks("https://.")).doesNotContain("<a");
    }
}
