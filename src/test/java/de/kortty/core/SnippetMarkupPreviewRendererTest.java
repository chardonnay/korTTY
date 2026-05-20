package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class SnippetMarkupPreviewRendererTest {

    @Test
    void supportsMarkdownAndAsciidoctorLanguages() {
        assertThat(SnippetMarkupPreviewRenderer.supports("markdown")).isTrue();
        assertThat(SnippetMarkupPreviewRenderer.supports("md")).isTrue();
        assertThat(SnippetMarkupPreviewRenderer.supports("asciidoctor")).isTrue();
        assertThat(SnippetMarkupPreviewRenderer.supports("adoc")).isTrue();
        assertThat(SnippetMarkupPreviewRenderer.supports("bash")).isFalse();
    }

    @Test
    void markdownPreviewRendersBlocksAndEscapesHtml() {
        String html = SnippetMarkupPreviewRenderer.renderMarkdownBody("""
            # Title

            **safe** <script>

            ```bash
            echo hi
            ```
            """);

        assertThat(html).contains("<h1>Title</h1>");
        assertThat(html).contains("<strong>safe</strong> &lt;script&gt;");
        assertThat(html).contains("<pre><code>echo hi</code></pre>");
        assertThat(html).doesNotContain("<script>");
    }

    @Test
    void asciidoctorPreviewRendersHeadingListsAndListingBlock() {
        String html = SnippetMarkupPreviewRenderer.renderAsciidoctorBody("""
            = Guide

            * one
            * two

            ----
            dnf install gpg-pubkey
            ----
            """);

        assertThat(html).contains("<h1>Guide</h1>");
        assertThat(html).contains("<ul>");
        assertThat(html).contains("<li>one</li>");
        assertThat(html).contains("<pre><code>dnf install gpg-pubkey</code></pre>");
    }
}
