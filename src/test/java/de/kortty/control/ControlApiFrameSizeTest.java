package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.FakeFocusOracle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The two size limits of §2, exercised where they can actually fail: across a socket.
 *
 * <p>The outbound rule is the subtle one. Every line is capped at 1 MiB, but a {@code pane.read} of
 * ten thousand wide rows is several megabytes of text, so the result is additionally hard-capped at
 * 512 KiB <strong>before</strong> framing and the drop is reported as {@code truncated:true}. If that
 * cap were missing the server would emit a frame its own codec rejects, and the client — which shares
 * that codec — would fail to read an answer it had correctly asked for. Nothing inside
 * {@code de.kortty.control}'s unit tests can see that, because it only happens once a real result
 * meets a real writer.
 *
 * <p>The inbound rule is the blunt one: a line over the cap is {@code message_too_large} and the
 * connection is <strong>closed</strong>, because a truncated line cannot be resynchronised safely —
 * the next bytes on the wire are the middle of a frame, not the start of one.
 */
public class ControlApiFrameSizeTest {

    private static final String PANE = "p1a2b";

    /** The largest read §6 accepts. */
    private static final int HUGE_LINES = 10_000;

    /** A wide terminal row, so the text really is megabytes rather than kilobytes. */
    private static final int COLUMNS = 400;

    private Path root;

    private FakeControlSurface surface;

    private CodingAgentRegistry agents;

    private ControlApiServer server;

    private EndpointDescriptor endpoint;

    @BeforeMethod
    void startTheServer() throws IOException {
        root = ControlApiScenarioFixtures.newTempRoot();
        surface = (FakeControlSurface) ControlApiScenarioFixtures.oneLocalShellPaneWithClaudeCode();
        agents = CodingAgentRegistry.forTests(new FakeFocusOracle(), System::currentTimeMillis);
        server = ControlApiScenarioFixtures.startServer(root, surface, agents);
        endpoint = server.endpoint().orElseThrow();
    }

    @AfterMethod(alwaysRun = true)
    void stopTheServerAndDeleteTheTempTree() {
        if (server != null) {
            server.close();
            server = null;
        }
        if (agents != null) {
            agents.clear();
        }
        ControlApiScenarioFixtures.deleteTree(root);
    }

    @Test(timeOut = 120_000)
    void aReadOfTenThousandWideRowsIsTruncatedIntoAFrameTheCodecStillAccepts() throws Exception {
        giveThePaneScrollback(HUGE_LINES, COLUMNS);
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject frame = wire.call("pane.read", ControlApiScenarioFixtures.params(
                "pane", PANE, "mode", "recent", "lines", HUGE_LINES));
            JsonObject text = result(frame);

            assertWithMessage("dropping leading rows silently would let a caller believe it had read"
                    + " the whole scrollback; §6 requires the flag")
                .that(text.get("truncated").getAsBoolean()).isTrue();
            JsonArray lines = text.getAsJsonArray("lines");
            assertWithMessage("%s rows of %s columns cannot fit the 512 KiB result cap", HUGE_LINES,
                    COLUMNS)
                .that(lines.size()).isLessThan(HUGE_LINES);
            assertThat(lines.size()).isGreaterThan(0);

            long payload = 0L;
            for (int i = 0; i < lines.size(); i++) {
                payload += lines.get(i).getAsString().getBytes(StandardCharsets.UTF_8).length + 1L;
            }
            assertWithMessage("the text the server kept must fit the documented 512 KiB result cap")
                .that(payload).isAtMost((long) ControlApiProtocol.MAX_RESULT_BYTES);

            int frameBytes = ControlJson.gson().toJson(frame).getBytes(StandardCharsets.UTF_8).length;
            assertWithMessage("the server must never emit a frame its own codec would reject: this"
                    + " one is %s bytes against a %s-byte line limit", frameBytes,
                    ControlApiProtocol.MAX_LINE_BYTES)
                .that(frameBytes).isLessThan(ControlApiProtocol.MAX_LINE_BYTES);

            assertWithMessage("truncation drops leading ROWS, never characters: a half-written line"
                    + " would corrupt whatever the caller greps for")
                .that(lines.get(lines.size() - 1).getAsString()).hasLength(COLUMNS);
            assertWithMessage("the last rows are the interesting ones, so they are the ones kept")
                .that(lines.get(lines.size() - 1).getAsString())
                .isEqualTo(row(HUGE_LINES - 1, COLUMNS));
        }
    }

    @Test(timeOut = 120_000)
    void aWaitOutputMatchOnAHugeScreenIsTruncatedRatherThanTurnedIntoAnInternalError() throws Exception {
        giveThePaneScrollback(HUGE_LINES, COLUMNS);
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject frame = wire.call("pane.wait_output", ControlApiScenarioFixtures.params(
                "pane", PANE, "contains", "line-" + (HUGE_LINES - 1) + " ", "mode", "recent",
                "lines", HUGE_LINES, "timeout_ms", 5_000));

            assertWithMessage("pane.wait_output embeds a whole screen in its result; without the"
                    + " 512 KiB cap ControlConnection.send answers internal_error and the caller"
                    + " loses the match it waited for")
                .that(frame.has("error")).isFalse();
            JsonObject result = result(frame);
            assertThat(result.get("matched").getAsBoolean()).isTrue();
            JsonObject screen = result.getAsJsonObject("screen");
            assertWithMessage("dropping leading rows silently would let a caller believe it had the"
                    + " whole scrollback")
                .that(screen.get("truncated").getAsBoolean()).isTrue();

            long payload = 0L;
            JsonArray lines = screen.getAsJsonArray("lines");
            for (int i = 0; i < lines.size(); i++) {
                payload += lines.get(i).getAsString().getBytes(StandardCharsets.UTF_8).length + 1L;
            }
            assertWithMessage("the embedded screen must fit the documented 512 KiB result cap")
                .that(payload).isAtMost((long) ControlApiProtocol.MAX_RESULT_BYTES);

            int frameBytes = ControlJson.gson().toJson(frame).getBytes(StandardCharsets.UTF_8).length;
            assertWithMessage("the server must never emit a frame its own codec would reject: this"
                    + " one is %s bytes against a %s-byte line limit", frameBytes,
                    ControlApiProtocol.MAX_LINE_BYTES)
                .that(frameBytes).isLessThan(ControlApiProtocol.MAX_LINE_BYTES);

            int lineIndex = result.get("line_index").getAsInt();
            assertWithMessage("line_index addresses a row of the screen that is actually returned")
                .that(lineIndex).isLessThan(lines.size());
        }
    }

    @Test(timeOut = 60_000)
    void aReadWellInsideTheCapIsNotMarkedTruncated() throws Exception {
        giveThePaneScrollback(50, 80);
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject text = result(wire.call("pane.read", ControlApiScenarioFixtures.params(
                "pane", PANE, "mode", "recent", "lines", 50)));
            assertThat(text.get("truncated").getAsBoolean()).isFalse();
            assertThat(text.getAsJsonArray("lines").size()).isEqualTo(50);
        }
    }

    @Test(timeOut = 60_000)
    void aRequestForMoreRowsThanTheMaximumIsClampedRatherThanRefused() throws Exception {
        FakePaneReader reader = giveThePaneScrollback(10, 20);
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            result(wire.call("pane.read", ControlApiScenarioFixtures.params(
                "pane", PANE, "mode", "recent", "lines", 999_999)));
            assertWithMessage("§8 publishes max_read_lines as a limit, and the schema calls 'lines'"
                    + " clamped, so an over-large request must be served, not refused")
                .that(reader.reads()).contains(ReadMode.RECENT.wire() + ":"
                    + ControlApiProtocol.MAX_READ_LINES);
        }
    }

    @Test(timeOut = 60_000)
    void aRequestOfExactlyTheLineLimitIsAccepted() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            String line = paddedPing(ControlApiProtocol.MAX_LINE_BYTES);
            assertThat(line.getBytes(StandardCharsets.UTF_8).length)
                .isEqualTo(ControlApiProtocol.MAX_LINE_BYTES);

            wire.sendRaw(line);
            JsonObject frame = wire.next();
            assertWithMessage("the cap counts the payload, excluding the newline, so a line of"
                    + " exactly %s bytes is legal and must be answered",
                    ControlApiProtocol.MAX_LINE_BYTES)
                .that(frame.has("result")).isTrue();
            assertThat(frame.getAsJsonObject("result").get("pong").getAsBoolean()).isTrue();
        }
    }

    @Test(timeOut = 60_000)
    void aRequestOneByteOverTheLimitIsMessageTooLargeAndTheConnectionIsClosed() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            wire.sendRaw(paddedPing(ControlApiProtocol.MAX_LINE_BYTES + 1));

            JsonObject frame = wire.next();
            assertThat(frame).isNotNull();
            JsonObject error = frame.getAsJsonObject("error");
            assertWithMessage("an over-long line cannot be correlated to a request id")
                .that(frame.get("id").isJsonNull()).isTrue();
            assertThat(error.get("code").getAsInt())
                .isEqualTo(ControlErrorCode.MESSAGE_TOO_LARGE.jsonRpcCode());
            JsonObject data = error.getAsJsonObject("data");
            assertThat(data.get("code").getAsString())
                .isEqualTo(ControlErrorCode.MESSAGE_TOO_LARGE.wire());
            assertThat(data.get("exit").getAsInt())
                .isEqualTo(ControlErrorCode.MESSAGE_TOO_LARGE.cliExit());
            assertWithMessage("§2 requires data.max_line_bytes so a client can size its own writes")
                .that(data.get("max_line_bytes").getAsLong())
                .isEqualTo(ControlApiProtocol.MAX_LINE_BYTES);

            assertWithMessage("the rest of the over-long line is still in the stream, so the only"
                    + " safe move is to close: a resynchronisation attempt would read the middle of"
                    + " a frame as the start of one")
                .that(wire.next()).isNull();
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private static JsonObject result(JsonObject frame) {
        assertThat(frame).isNotNull();
        if (frame.has("error")) {
            throw new AssertionError("the request failed on the wire: " + frame.get("error"));
        }
        return frame.getAsJsonObject("result");
    }

    private FakePaneReader giveThePaneScrollback(int rows, int columns) {
        List<String> lines = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            lines.add(row(i, columns));
        }
        FakePaneReader reader = new FakePaneReader(PANE);
        reader.setLines(ReadMode.RECENT, lines);
        reader.setGeometry(columns, 40);
        surface.setReader(PANE, reader);
        return reader;
    }

    /** A row whose first characters name its index, so a dropped prefix is provable. */
    private static String row(int index, int columns) {
        String marker = "line-" + index + " ";
        StringBuilder text = new StringBuilder(columns);
        text.append(marker);
        while (text.length() < columns) {
            text.append('x');
        }
        return text.substring(0, columns);
    }

    /** A valid {@code ping} request padded to exactly {@code bytes} bytes. */
    private static String paddedPing(int bytes) {
        String prefix = "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"ping\",\"params\":{\"pad\":\"";
        String suffix = "\"}}";
        return prefix + "x".repeat(bytes - prefix.length() - suffix.length()) + suffix;
    }
}
