package de.kortty.telemetry;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

class TelemetrySpoolTest {

    private Path configDir;
    private TelemetrySpool spool;

    @BeforeMethod
    void setUp() throws Exception {
        configDir = Files.createTempDirectory("kortty-spool-test");
        spool = new TelemetrySpool(configDir);
    }

    @Test
    void roundTripsEventsAndDeletesOnRead() {
        TelemetryEvent event = new TelemetryEvent(
            "2026-07-04T10:00:00Z", "170000000012345678", "tool_opened",
            Map.of("tool", "snippet_manager", "open_tabs", 3, "enabled", true));
        event.sendAttempts = 2;

        spool.write(List.of(event));
        assertThat(Files.exists(configDir.resolve(TelemetryService.SPOOL_FILE))).isTrue();

        List<TelemetryEvent> restored = spool.readAndDelete();
        assertThat(restored).hasSize(1);
        TelemetryEvent r = restored.get(0);
        assertThat(r.timestamp).isEqualTo("2026-07-04T10:00:00Z");
        assertThat(r.sessionId).isEqualTo("170000000012345678");
        assertThat(r.eventName).isEqualTo("tool_opened");
        assertThat(r.sendAttempts).isEqualTo(2);
        assertThat(r.props.get("tool")).isEqualTo("snippet_manager");
        assertThat(((Number) r.props.get("open_tabs")).intValue()).isEqualTo(3);
        assertThat(r.props.get("enabled")).isEqualTo(true);
        // Reading consumes the spool file.
        assertThat(Files.exists(configDir.resolve(TelemetryService.SPOOL_FILE))).isFalse();
    }

    @Test
    void returnsEmptyWhenMissing() {
        assertThat(spool.readAndDelete()).isEmpty();
    }

    @Test
    void handlesCorruptFileWithoutThrowing() throws Exception {
        Files.writeString(configDir.resolve(TelemetryService.SPOOL_FILE), "{ this is not valid json");

        assertThat(spool.readAndDelete()).isEmpty();
        assertThat(Files.exists(configDir.resolve(TelemetryService.SPOOL_FILE))).isFalse();
    }

    @Test
    void deleteRemovesSpool() throws Exception {
        spool.write(List.of(new TelemetryEvent("2026-07-04T10:00:00Z", "1", "app_started", Map.of())));
        assertThat(Files.exists(configDir.resolve(TelemetryService.SPOOL_FILE))).isTrue();

        spool.delete();

        assertThat(Files.exists(configDir.resolve(TelemetryService.SPOOL_FILE))).isFalse();
    }
}
