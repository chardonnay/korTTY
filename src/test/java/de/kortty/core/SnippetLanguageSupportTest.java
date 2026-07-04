package de.kortty.core;

import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;


class SnippetLanguageSupportTest {

    @Test
    void detectSnippetLanguageFallsBackToShebangWhenFenceLanguageIsMissing() {
        String detected = SnippetLanguageSupport.detectSnippetLanguage("", "#!/usr/bin/env python3\nprint('ok')\n");

        assertThat(detected).isEqualTo("python");
    }

    @Test
    void detectSnippetLanguageRecognizesPosixShFromEnvShebang() {
        String detected = SnippetLanguageSupport.detectSnippetLanguage("", "#!/usr/bin/env -S sh -eu\necho ok\n");

        assertThat(detected).isEqualTo("bash");
    }

    @Test
    void detectSnippetLanguageDoesNotTreatCshAsPosixSh() {
        String detected = SnippetLanguageSupport.detectSnippetLanguage("", "#!/bin/csh\necho ok\n");

        assertThat(detected).isEqualTo("plain");
    }

    @Test
    void sanitizeFileNameAddsExpectedExtensionAndAsciiSafeCharacters() {
        String fileName = SnippetLanguageSupport.sanitizeFileName("Backup Logs 2026", "bash");

        assertThat(fileName).isEqualTo("Backup-Logs-2026.sh");
    }

    @Test
    void scriptCandidateRecognizesRubyFenceAlias() {
        assertThat(SnippetLanguageSupport.isScriptSnippetCandidate("rb", "puts 'ok'")).isTrue();
    }

    @Test
    void detectFileLanguageRecognizesKnownExtensionsAndNames() {
        assertThat(SnippetLanguageSupport.detectFileLanguage("deploy.sh", "echo ok")).isEqualTo("bash");
        assertThat(SnippetLanguageSupport.detectFileLanguage("script.py", "print('ok')")).isEqualTo("python");
        assertThat(SnippetLanguageSupport.detectFileLanguage("data.json", "{}")).isEqualTo("json");
        assertThat(SnippetLanguageSupport.detectFileLanguage("compose.yml", "services: {}")).isEqualTo("yaml");
        assertThat(SnippetLanguageSupport.detectFileLanguage("README.md", "# Title")).isEqualTo("markdown");
        assertThat(SnippetLanguageSupport.detectFileLanguage("guide.adoc", "= Title")).isEqualTo("asciidoctor");
        assertThat(SnippetLanguageSupport.detectFileLanguage("Dockerfile", "FROM alpine")).isEqualTo("dockerfile");
    }

    @Test
    void detectFileLanguageFallsBackToShebangForUnknownExtensions() {
        String detected = SnippetLanguageSupport.detectFileLanguage("run.custom", "#!/usr/bin/env bash\necho ok\n");

        assertThat(detected).isEqualTo("bash");
    }

    @Test
    void detectFileLanguageUsesPlainForUnknownExtensionWithoutShebang() {
        String detected = SnippetLanguageSupport.detectFileLanguage("notes.custom", "plain text");

        assertThat(detected).isEqualTo("plain");
    }

    /**
     * Regression test for KNOWN_LANGUAGES drifting out of sync with normalizeSnippetLanguage:
     * every non-default switch branch must map to a token telemetryLanguageToken accepts as
     * "known" (not silently bucketed into "other"). Exercised via the public API only, so a
     * newly added switch case that forgets to update KNOWN_LANGUAGES fails this test.
     */
    @Test
    void telemetryLanguageTokenRecognizesEveryNormalizedLanguage() {
        String[] representativeAliases = {
            "sh", "shell", "zsh", "bash",
            "py", "python", "python3",
            "pl", "perl",
            "rb", "ruby",
            "js", "javascript", "node", "nodejs",
            "ps", "ps1", "pwsh", "powershell",
            "groovy",
            "java",
            "json",
            "yaml", "yml",
            "xml",
            "markdown", "md",
            "asciidoctor", "asciidoc", "adoc",
            "sql",
            "dockerfile",
            "properties", "ini",
            "html",
            "plain", "text", "txt"
        };

        for (String alias : representativeAliases) {
            assertThat(SnippetLanguageSupport.telemetryLanguageToken(alias, "")).isNotEqualTo("other");
        }
    }

    @Test
    void telemetryLanguageTokenClampsFreeTextToOther() {
        assertThat(SnippetLanguageSupport.telemetryLanguageToken("cobol", "")).isEqualTo("other");
        assertThat(SnippetLanguageSupport.telemetryLanguageToken("some made-up language name", "")).isEqualTo("other");
    }
}
