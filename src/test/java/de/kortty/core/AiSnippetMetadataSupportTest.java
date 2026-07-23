package de.kortty.core;

import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;


class AiSnippetMetadataSupportTest {

    @Test
    void parseMetadataResponseExtractsJsonNormalizesFileNameAndUsesAiLanguage() {
        AiSnippetMetadataSupport.SuggestedSnippetMetadata metadata = AiSnippetMetadataSupport.parseMetadataResponse(
            """
            ```json
            {
              "fileName": "cleanup logs",
              "description": "Bereinigt alte Logdateien und komprimiert verbleibende Archive.",
              "language": "python"
            }
            ```
            """,
            "bash",
            "#!/usr/bin/env bash\nfind /var/log -type f");

        assertThat(metadata.fileName()).isEqualTo("cleanup-logs.py");
        assertThat(metadata.description()).isEqualTo("Bereinigt alte Logdateien und komprimiert verbleibende Archive.");
        assertThat(metadata.language()).isEqualTo("python");
    }

    @Test
    void parseMetadataResponseFallsBackToDetectedLanguageWhenJsonIsMissing() {
        AiSnippetMetadataSupport.SuggestedSnippetMetadata metadata = AiSnippetMetadataSupport.parseMetadataResponse(
            "Kurze Beschreibung ohne JSON",
            "plain",
            "#!/usr/bin/env python3\nprint('ok')");

        assertThat(metadata.fileName()).isEqualTo("snippet.py");
        assertThat(metadata.description()).isEqualTo("Kurze Beschreibung ohne JSON");
        assertThat(metadata.language()).isEqualTo("python");
    }

    @Test
    void parseMetadataResponseReadsTheDetectedTextLanguage() {
        AiSnippetMetadataSupport.SuggestedSnippetMetadata metadata = AiSnippetMetadataSupport.parseMetadataResponse(
            """
            {
              "fileName": "backup.sh",
              "description": "Sichert Verzeichnisse.",
              "language": "bash",
              "textLanguage": "de"
            }
            """,
            "bash",
            "#!/bin/bash\n# Sicherung starten\necho \"Sicherung läuft\"");

        assertThat(metadata.textLanguage()).isEqualTo("de");
    }

    @Test
    void normalizeTextLanguageReducesModelAnswersToAnIsoCode() {
        assertThat(AiSnippetMetadataSupport.normalizeTextLanguage("de")).isEqualTo("de");
        assertThat(AiSnippetMetadataSupport.normalizeTextLanguage("DE-de")).isEqualTo("de");
        assertThat(AiSnippetMetadataSupport.normalizeTextLanguage("pt_BR")).isEqualTo("pt");
        assertThat(AiSnippetMetadataSupport.normalizeTextLanguage("German")).isEqualTo("de");
        assertThat(AiSnippetMetadataSupport.normalizeTextLanguage("Deutsch")).isEqualTo("de");
        // A script with no readable text must not preselect a language.
        assertThat(AiSnippetMetadataSupport.normalizeTextLanguage("unknown")).isNull();
        assertThat(AiSnippetMetadataSupport.normalizeTextLanguage("")).isNull();
        assertThat(AiSnippetMetadataSupport.normalizeTextLanguage(null)).isNull();
    }

    @Test
    void parseMetadataResponseUsesFirstBalancedJsonObjectWhenMultipleObjectsExist() {
        AiSnippetMetadataSupport.SuggestedSnippetMetadata metadata = AiSnippetMetadataSupport.parseMetadataResponse(
            """
            Metadata hint: {not-json}
            {"fileName":"backup job","description":"Keeps {daily} backups.","language":"bash"}
            Ignore this trailing object:
            {"ignored":true}
            """,
            "plain",
            "#!/bin/sh\necho ok\n");

        assertThat(metadata.fileName()).isEqualTo("backup-job.sh");
        assertThat(metadata.description()).isEqualTo("Keeps {daily} backups.");
        assertThat(metadata.language()).isEqualTo("bash");
    }
}
