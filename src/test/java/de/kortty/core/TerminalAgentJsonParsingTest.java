package de.kortty.core;

import com.google.gson.JsonSyntaxException;
import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;


class TerminalAgentJsonParsingTest {

    @Test
    void extractsJsonObjectFromMarkdownFence() {
        String parsed = TerminalAgentService.extractJsonObjectContent("""
            ```json
            {"status":"done","summary":"ok","userMessage":"ready","commands":[],"needsReprobe":false}
            ```
            """);

        assertThat(parsed).isEqualTo("{\"status\":\"done\",\"summary\":\"ok\",\"userMessage\":\"ready\",\"commands\":[],\"needsReprobe\":false}");
    }

    @Test
    void extractsJsonObjectFromJsonStringResponse() {
        String parsed = TerminalAgentService.extractJsonObjectContent(
            "\"{\\\"status\\\":\\\"blocked\\\",\\\"summary\\\":\\\"no repo\\\",\\\"userMessage\\\":\\\"Need repo details\\\",\\\"commands\\\":[],\\\"needsReprobe\\\":false}\"");

        assertThat(parsed).isEqualTo("{\"status\":\"blocked\",\"summary\":\"no repo\",\"userMessage\":\"Need repo details\",\"commands\":[],\"needsReprobe\":false}");
    }

    @Test
    void extractsJsonObjectEmbeddedInText() {
        String parsed = TerminalAgentService.extractJsonObjectContent(
            "Sure. {\"status\":\"done\",\"summary\":\"ok\",\"userMessage\":\"ready\",\"commands\":[],\"needsReprobe\":false}");

        assertThat(parsed).isEqualTo("{\"status\":\"done\",\"summary\":\"ok\",\"userMessage\":\"ready\",\"commands\":[],\"needsReprobe\":false}");
    }

    @Test
    void rejectsPlainTextWithoutJsonObject() {
        expectThrows(JsonSyntaxException.class, () ->
            TerminalAgentService.extractJsonObjectContent("I would install tomcat with dnf."));
    }
}
