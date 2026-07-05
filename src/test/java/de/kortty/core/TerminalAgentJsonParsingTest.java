package de.kortty.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    void repairsMultilineCommandStringsWithUnderEscapedBackslashes() {
        String parsed = TerminalAgentService.extractJsonObjectContent("{\n"
            + "  \"status\": \"needs_confirmation\",\n"
            + "  \"summary\": \"Create Perl script\",\n"
            + "  \"userMessage\": \"Creating the script.\",\n"
            + "  \"commands\": [\n"
            + "    {\n"
            + "      \"command\": \"cat > /home/daniel/find_xml.pl << 'EOF'\n"
            + "#!/usr/bin/perl\n"
            + "print if /\\.xml$/i;\n"
            + "EOF\n"
            + "\",\n"
            + "      \"purpose\": \"Create the Perl script\",\n"
            + "      \"risk\": \"requires_confirmation\"\n"
            + "    }\n"
            + "  ],\n"
            + "  \"needsReprobe\": false\n"
            + "}");

        JsonObject object = JsonParser.parseString(parsed).getAsJsonObject();
        String command = object.getAsJsonArray("commands")
            .get(0)
            .getAsJsonObject()
            .get("command")
            .getAsString();

        assertThat(command).contains("#!/usr/bin/perl");
        assertThat(command).contains("/\\.xml$/i");
    }

    @Test
    void rejectsPlainTextWithoutJsonObject() {
        expectThrows(JsonSyntaxException.class, () ->
            TerminalAgentService.extractJsonObjectContent("I would install tomcat with dnf."));
    }

    @Test
    void reportsTruncatedJsonObjectClearly() {
        JsonSyntaxException failure = expectThrows(JsonSyntaxException.class, () ->
            TerminalAgentService.extractJsonObjectContent(
                "{\"status\":\"final_plan\",\"title\":\"x\",\"summary\":\"cut off here"));
        assertThat(failure.getMessage()).contains("incomplete or truncated");
    }
}
