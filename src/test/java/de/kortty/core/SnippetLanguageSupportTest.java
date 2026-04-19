package de.kortty.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnippetLanguageSupportTest {

    @Test
    void detectSnippetLanguageFallsBackToShebangWhenFenceLanguageIsMissing() {
        String detected = SnippetLanguageSupport.detectSnippetLanguage("", "#!/usr/bin/env python3\nprint('ok')\n");

        assertEquals("python", detected);
    }

    @Test
    void detectSnippetLanguageRecognizesPosixShFromEnvShebang() {
        String detected = SnippetLanguageSupport.detectSnippetLanguage("", "#!/usr/bin/env -S sh -eu\necho ok\n");

        assertEquals("bash", detected);
    }

    @Test
    void detectSnippetLanguageDoesNotTreatCshAsPosixSh() {
        String detected = SnippetLanguageSupport.detectSnippetLanguage("", "#!/bin/csh\necho ok\n");

        assertEquals("plain", detected);
    }

    @Test
    void sanitizeFileNameAddsExpectedExtensionAndAsciiSafeCharacters() {
        String fileName = SnippetLanguageSupport.sanitizeFileName("Backup Logs 2026", "bash");

        assertEquals("Backup-Logs-2026.sh", fileName);
    }

    @Test
    void scriptCandidateRecognizesRubyFenceAlias() {
        assertTrue(SnippetLanguageSupport.isScriptSnippetCandidate("rb", "puts 'ok'"));
    }
}
