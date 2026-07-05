package de.kortty.telemetry;

import java.util.Map;

/**
 * Static facade for instrumentation call sites. A no-op until initialized and
 * while the user has not opted in — call sites never need to check consent.
 */
public final class Telemetry {

    private static volatile TelemetryService service;

    public static void init(TelemetryService telemetryService) {
        service = telemetryService;
    }

    public static void track(String eventName) {
        TelemetryService current = service;
        if (current != null) {
            current.trackEvent(eventName);
        }
    }

    public static void track(String eventName, Map<String, Object> props) {
        TelemetryService current = service;
        if (current != null) {
            current.trackEvent(eventName, props);
        }
    }

    private Telemetry() {
    }
}
