package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Map;
import org.testng.annotations.Test;

class ControlFrameTest {

    private static JsonObject parse(String line) throws Exception {
        return ControlJson.parseObjectStrict(line.strip());
    }

    @Test
    void aResultFrameIsOneLineWithATrailingNewline() throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("pong", true);
        String line = ControlFrame.result(new JsonPrimitive(7), result).toLine();

        assertThat(line).endsWith("\n");
        assertThat(line.indexOf('\n')).isEqualTo(line.length() - 1);

        JsonObject frame = parse(line);
        assertThat(frame.get("jsonrpc").getAsString()).isEqualTo("2.0");
        assertThat(frame.get("id").getAsInt()).isEqualTo(7);
        assertThat(frame.getAsJsonObject("result").get("pong").getAsBoolean()).isTrue();
        assertThat(frame.has("error")).isFalse();
    }

    @Test
    void anErrorFrameCarriesCodeRetryableAndExitInItsData() throws Exception {
        ControlApiError error = ControlApiError.of(ControlErrorCode.PANE_NOT_FOUND,
            "No pane p1a2b3c4d is open", Map.of("pane", "p1a2b3c4d"));
        String line = ControlFrame.error(new JsonPrimitive(7), error).toLine();

        assertThat(line).endsWith("\n");
        assertThat(line.indexOf('\n')).isEqualTo(line.length() - 1);

        JsonObject frame = parse(line);
        JsonObject wire = frame.getAsJsonObject("error");
        assertThat(wire.get("code").getAsInt()).isEqualTo(-32012);
        assertThat(wire.get("message").getAsString()).isEqualTo("No pane p1a2b3c4d is open");
        JsonObject data = wire.getAsJsonObject("data");
        assertThat(data.get("code").getAsString()).isEqualTo("pane_not_found");
        assertThat(data.get("retryable").getAsBoolean()).isFalse();
        assertThat(data.get("exit").getAsInt()).isEqualTo(1);
        assertThat(data.get("pane").getAsString()).isEqualTo("p1a2b3c4d");
        assertThat(frame.has("result")).isFalse();
    }

    @Test
    void everyErrorCodeProducesAFrameWithTheThreeInvariantDataFields() throws Exception {
        for (ControlErrorCode code : ControlErrorCode.values()) {
            JsonObject frame = parse(
                ControlFrame.error(new JsonPrimitive(1), ControlApiError.of(code, "boom")).toLine());
            JsonObject data = frame.getAsJsonObject("error").getAsJsonObject("data");
            assertThat(data.get("code").getAsString()).isEqualTo(code.wire());
            assertThat(data.get("retryable").getAsBoolean()).isEqualTo(code.retryable());
            assertThat(data.get("exit").getAsInt()).isEqualTo(code.cliExit());
        }
    }

    @Test
    void aFramingErrorCanCarryANullId() throws Exception {
        String line = ControlFrame.error(null,
            ControlApiError.of(ControlErrorCode.MESSAGE_TOO_LARGE, "too big")).toLine();
        JsonObject frame = parse(line);
        assertThat(frame.has("id")).isTrue();
        assertThat(frame.get("id").isJsonNull()).isTrue();
    }

    @Test
    void anEventFrameIsANotificationWithNoId() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("kind", "agent.state_changed");
        params.addProperty("pane_id", "p1a2b");
        String line = ControlFrame.event("event", params).toLine();

        assertThat(line).endsWith("\n");
        assertThat(line.indexOf('\n')).isEqualTo(line.length() - 1);

        JsonObject frame = parse(line);
        assertThat(frame.has("id")).isFalse();
        assertThat(frame.get("method").getAsString()).isEqualTo("event");
        assertThat(frame.getAsJsonObject("params").get("kind").getAsString())
            .isEqualTo("agent.state_changed");
    }

    @Test
    void aNewlineInsideAValueIsEscapedRatherThanEmitted() throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("explain", "first line\nsecond line\r\nthird");
        String line = ControlFrame.result(new JsonPrimitive("id-1"), result).toLine();

        assertThat(line.indexOf('\n')).isEqualTo(line.length() - 1);
        assertThat(line).doesNotContain("\r");
        assertThat(parse(line).getAsJsonObject("result").get("explain").getAsString())
            .isEqualTo("first line\nsecond line\r\nthird");
    }

    @Test
    void aStringIdSurvivesTheRoundTrip() throws Exception {
        JsonObject frame = parse(ControlFrame.result(new JsonPrimitive("abc"), new JsonObject()).toLine());
        assertThat(frame.get("id").getAsString()).isEqualTo("abc");
    }
}
