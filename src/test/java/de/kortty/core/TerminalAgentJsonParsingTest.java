package de.kortty.core;

import com.google.gson.JsonSyntaxException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TerminalAgentJsonParsingTest {

    @Test
    void extractsJsonObjectFromMarkdownFence() {
        String parsed = TerminalAgentService.extractJsonObjectContent("""
            ```json
            {"status":"done","summary":"ok","userMessage":"ready","commands":[],"needsReprobe":false}
            ```
            """);

        assertEquals(
            "{\"status\":\"done\",\"summary\":\"ok\",\"userMessage\":\"ready\",\"commands\":[],\"needsReprobe\":false}",
            parsed);
    }

    @Test
    void extractsJsonObjectFromJsonStringResponse() {
        String parsed = TerminalAgentService.extractJsonObjectContent(
            "\"{\\\"status\\\":\\\"blocked\\\",\\\"summary\\\":\\\"no repo\\\",\\\"userMessage\\\":\\\"Need repo details\\\",\\\"commands\\\":[],\\\"needsReprobe\\\":false}\"");

        assertEquals(
            "{\"status\":\"blocked\",\"summary\":\"no repo\",\"userMessage\":\"Need repo details\",\"commands\":[],\"needsReprobe\":false}",
            parsed);
    }

    @Test
    void extractsJsonObjectEmbeddedInText() {
        String parsed = TerminalAgentService.extractJsonObjectContent(
            "Sure. {\"status\":\"done\",\"summary\":\"ok\",\"userMessage\":\"ready\",\"commands\":[],\"needsReprobe\":false}");

        assertEquals(
            "{\"status\":\"done\",\"summary\":\"ok\",\"userMessage\":\"ready\",\"commands\":[],\"needsReprobe\":false}",
            parsed);
    }

    @Test
    void rejectsPlainTextWithoutJsonObject() {
        assertThrows(JsonSyntaxException.class, () ->
            TerminalAgentService.extractJsonObjectContent("I would install tomcat with dnf."));
    }
}
