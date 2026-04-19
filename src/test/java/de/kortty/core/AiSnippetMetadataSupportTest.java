package de.kortty.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        assertEquals("cleanup-logs.py", metadata.fileName());
        assertEquals("Bereinigt alte Logdateien und komprimiert verbleibende Archive.", metadata.description());
        assertEquals("python", metadata.language());
    }

    @Test
    void parseMetadataResponseFallsBackToDetectedLanguageWhenJsonIsMissing() {
        AiSnippetMetadataSupport.SuggestedSnippetMetadata metadata = AiSnippetMetadataSupport.parseMetadataResponse(
            "Kurze Beschreibung ohne JSON",
            "plain",
            "#!/usr/bin/env python3\nprint('ok')");

        assertEquals("snippet.py", metadata.fileName());
        assertEquals("Kurze Beschreibung ohne JSON", metadata.description());
        assertEquals("python", metadata.language());
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

        assertEquals("backup-job.sh", metadata.fileName());
        assertEquals("Keeps {daily} backups.", metadata.description());
        assertEquals("bash", metadata.language());
    }
}
