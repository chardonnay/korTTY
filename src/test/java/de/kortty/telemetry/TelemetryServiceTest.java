package de.kortty.telemetry;

import de.kortty.core.GlobalSettingsManager;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

class TelemetryServiceTest {

    private Path configDir;
    private GlobalSettingsManager settingsManager;
    private TelemetryService service;

    @BeforeMethod
    void setUp() throws Exception {
        configDir = Files.createTempDirectory("kortty-telemetry-test");
        settingsManager = new GlobalSettingsManager(configDir);
        service = newService();
    }

    @AfterMethod
    void tearDown() {
        if (service != null) {
            service.shutdown(Duration.ofMillis(100));
        }
    }

    private TelemetryService newService() {
        // Unreachable endpoint: connection refused immediately, nothing leaves the machine.
        AptabaseClient client = new AptabaseClient(
            HttpClient.newHttpClient(), URI.create("http://127.0.0.1:9/api/v0/events"));
        Clock clock = Clock.fixed(Instant.parse("2026-07-04T10:00:00Z"), ZoneOffset.UTC);
        return new TelemetryService(settingsManager, configDir, client, clock);
    }

    @Test
    void staysInertWithoutConsent() {
        service.start();

        assertThat(service.isActive()).isFalse();
        service.trackEvent("tool_opened", Map.of("tool", "snippet_manager"));
        assertThat(service.queuedEventCount()).isEqualTo(0);
        assertThat(Files.exists(configDir.resolve(TelemetryService.RUN_MARKER_FILE))).isFalse();
    }

    @Test
    void queuesEventsOnceEnabled() {
        settingsManager.getSettings().setTelemetryEnabled(true);
        service.start();

        assertThat(service.isActive()).isTrue();
        assertThat(service.queuedEventCount()).isEqualTo(1); // app_started
        service.trackEvent("tool_opened", Map.of("tool", "snippet_manager"));
        assertThat(service.queuedEventCount()).isEqualTo(2);
        assertThat(Files.exists(configDir.resolve(TelemetryService.RUN_MARKER_FILE))).isTrue();
    }

    @Test
    void disablingDiscardsQueueAndMarker() {
        settingsManager.getSettings().setTelemetryEnabled(true);
        service.start();
        service.trackEvent("tool_opened", Map.of("tool", "ascii_art"));
        assertThat(service.queuedEventCount()).isAtLeast(2);

        settingsManager.getSettings().setTelemetryEnabled(false);
        service.applyEnabledState();

        assertThat(service.isActive()).isFalse();
        assertThat(service.queuedEventCount()).isEqualTo(0);
        assertThat(Files.exists(configDir.resolve(TelemetryService.RUN_MARKER_FILE))).isFalse();
        service.trackEvent("tool_opened", Map.of("tool", "ascii_art"));
        assertThat(service.queuedEventCount()).isEqualTo(0);
    }

    @Test
    void consentPromptNeededOnlyUntilDecided() {
        assertThat(service.isConsentPromptNeeded()).isTrue();

        service.recordConsent(false);

        assertThat(service.isConsentPromptNeeded()).isFalse();
        assertThat(service.isEnabled()).isFalse();
        assertThat(service.isActive()).isFalse();
        assertThat(settingsManager.getSettings().getTelemetryConsentVersion())
            .isEqualTo(TelemetryService.CURRENT_CONSENT_VERSION);
        assertThat(settingsManager.getSettings().getTelemetryConsentDate()).isNotNull();
    }

    @Test
    void grantingConsentPersistsAndActivates() throws Exception {
        service.recordConsent(true);

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.isActive()).isTrue();
        assertThat(service.queuedEventCount()).isAtLeast(1); // app_started

        // Decision survives a reload from disk (GDPR record).
        GlobalSettingsManager reloaded = new GlobalSettingsManager(configDir);
        reloaded.load();
        assertThat(reloaded.getSettings().isTelemetryEnabled()).isTrue();
        assertThat(reloaded.getSettings().getTelemetryConsentVersion())
            .isEqualTo(TelemetryService.CURRENT_CONSENT_VERSION);
    }

    @Test
    void detectsCrashOfPreviousRunViaStaleMarker() throws Exception {
        // Marker with a certainly-dead PID from a previous "crashed" run.
        Files.writeString(configDir.resolve(TelemetryService.RUN_MARKER_FILE), "999999999\n2.3.2\n");
        settingsManager.getSettings().setTelemetryEnabled(true);

        service.start();

        assertThat(service.queuedEventCount()).isEqualTo(2); // app_started + app_crash_detected
    }

    @Test
    void skipsCrashDetectionWhenMarkerPidIsAlive() throws Exception {
        long ownPid = ProcessHandle.current().pid();
        Files.writeString(configDir.resolve(TelemetryService.RUN_MARKER_FILE), ownPid + "\n2.3.3\n");
        settingsManager.getSettings().setTelemetryEnabled(true);

        service.start();

        assertThat(service.queuedEventCount()).isEqualTo(1); // app_started only, marker not stolen
        assertThat(Files.readString(configDir.resolve(TelemetryService.RUN_MARKER_FILE)))
            .startsWith(String.valueOf(ownPid));
    }

    @Test
    void deduplicatesErrorSignaturesAndCapsThem() {
        settingsManager.getSettings().setTelemetryEnabled(true);
        service.start();
        int baseline = service.queuedEventCount();

        service.trackError("java.lang.IllegalStateException", "MainWindow.openProject", "de.kortty.ui.MainWindow");
        service.trackError("java.lang.IllegalStateException", "MainWindow.openProject", "de.kortty.ui.MainWindow");
        assertThat(service.queuedEventCount()).isEqualTo(baseline + 1);

        for (int i = 0; i < TelemetryService.MAX_ERROR_SIGNATURES_PER_RUN + 5; i++) {
            service.trackError("java.lang.RuntimeException", "Class" + i + ".method", "de.kortty.Class" + i);
        }
        assertThat(service.queuedEventCount())
            .isAtMost(baseline + TelemetryService.MAX_ERROR_SIGNATURES_PER_RUN);
    }

    @Test
    void neverExceedsQueueCap() {
        settingsManager.getSettings().setTelemetryEnabled(true);
        service.start();

        for (int i = 0; i < TelemetryService.MAX_QUEUE_SIZE + 60; i++) {
            service.trackEvent("tool_opened", Map.of("tool", "tool_" + i));
            assertThat(service.queuedEventCount()).isAtMost(TelemetryService.MAX_QUEUE_SIZE);
        }
    }

    @Test
    void offlineEventsAreRetainedAndSpooledAcrossFlushCycles() {
        settingsManager.getSettings().setTelemetryEnabled(true);
        service.start();
        service.trackEvent("tool_opened", Map.of("tool", "snippet_manager"));
        int queuedBefore = service.queuedEventCount();
        assertThat(queuedBefore).isAtLeast(2); // app_started + tool_opened

        // Endpoint is unreachable → flush fails, events are re-queued (not dropped) and spooled.
        service.flushNowForTest();

        assertThat(service.queuedEventCount()).isEqualTo(queuedBefore);
        assertThat(Files.exists(configDir.resolve(TelemetryService.SPOOL_FILE))).isTrue();

        // A second offline cycle keeps them (unlimited retry while offline, bounded by cap/age).
        service.flushNowForTest();
        assertThat(service.queuedEventCount()).isEqualTo(queuedBefore);
    }

    @Test
    void spooledEventsAreResentAfterRestart() {
        settingsManager.getSettings().setTelemetryEnabled(true);
        service.start();
        service.trackEvent("tool_opened", Map.of("tool", "ascii_art"));
        int queuedBefore = service.queuedEventCount();

        // Shut down while offline → the final flush persists the backlog to the spool.
        service.shutdown(Duration.ofSeconds(1));
        assertThat(Files.exists(configDir.resolve(TelemetryService.SPOOL_FILE))).isTrue();
        service = null;

        // "Restart": a fresh service on the same config dir restores the spooled events.
        TelemetryService restarted = newService();
        restarted.start();
        assertThat(restarted.queuedEventCount()).isAtLeast(queuedBefore + 1); // restored backlog + app_started
        assertThat(Files.exists(configDir.resolve(TelemetryService.SPOOL_FILE))).isFalse(); // consumed on load
        restarted.shutdown(Duration.ofMillis(100));
    }

    @Test
    void disablingDeletesTheSpool() {
        settingsManager.getSettings().setTelemetryEnabled(true);
        service.start();
        service.trackEvent("tool_opened", Map.of("tool", "ascii_art"));
        service.flushNowForTest(); // offline → spool written
        assertThat(Files.exists(configDir.resolve(TelemetryService.SPOOL_FILE))).isTrue();

        settingsManager.getSettings().setTelemetryEnabled(false);
        service.applyEnabledState();

        assertThat(Files.exists(configDir.resolve(TelemetryService.SPOOL_FILE))).isFalse();
    }

    @Test
    void sanitizesPropsToFlatPrimitives() {
        Map<String, Object> props = new HashMap<>();
        props.put("string", "value");
        props.put("number", 42);
        props.put("bool", true);
        props.put("nullValue", null);
        props.put("nested", Map.of("a", "b"));
        props.put("list", List.of("a"));
        props.put("", "blank-key");

        Map<String, Object> sanitized = TelemetryService.sanitizeProps(props);

        assertThat(sanitized).containsExactly("string", "value", "number", 42, "bool", true);
    }
}
