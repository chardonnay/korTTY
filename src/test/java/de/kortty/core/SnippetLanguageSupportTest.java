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
}
