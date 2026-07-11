package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static com.google.common.truth.Truth.assertThat;

class MonacoResourceBundleTest {

    @Test
    void editorAndDiffPagesLoadTheSameModeAwareHostAssets() throws IOException {
        String editorHtml = resourceText("/monaco/monaco-editor.html");
        String diffHtml = resourceText("/monaco/monaco-diff-editor.html");

        assertThat(editorHtml).contains("data-monaco-mode=\"editor\"");
        assertThat(diffHtml).contains("data-monaco-mode=\"diff\"");
        assertThat(editorHtml).contains("href=\"monaco-host.css\"");
        assertThat(diffHtml).contains("href=\"monaco-host.css\"");
        assertThat(editorHtml).contains("src=\"monaco-host.js\"");
        assertThat(diffHtml).contains("src=\"monaco-host.js\"");
        assertThat(editorHtml).doesNotContain("monaco-diff-host");
        assertThat(diffHtml).doesNotContain("monaco-diff-host");
    }

    @Test
    void bundleContainsBothStableApisAndNoLegacyDiffArtifact() throws IOException {
        String host = resourceText("/monaco/monaco-host.js");

        assertThat(host).contains("korttyMonaco");
        assertThat(host).contains("korttyMonacoDiff");
        assertThat(getClass().getResource("/monaco/monaco-host.css")).isNotNull();
        assertThat(getClass().getResource("/monaco/monaco-diff-host.js")).isNull();
        assertThat(getClass().getResource("/monaco/monaco-diff-host.css")).isNull();
    }

    private String resourceText(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(name)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
