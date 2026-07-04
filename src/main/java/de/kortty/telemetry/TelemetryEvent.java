package de.kortty.telemetry;

import java.util.Map;

/**
 * One queued Aptabase event. Props are already sanitized (flat map of
 * String/Number/Boolean values) and immutable.
 */
final class TelemetryEvent {

    final String timestamp;
    final String sessionId;
    final String eventName;
    final Map<String, Object> props;
    int sendAttempts;

    TelemetryEvent(String timestamp, String sessionId, String eventName, Map<String, Object> props) {
        this.timestamp = timestamp;
        this.sessionId = sessionId;
        this.eventName = eventName;
        this.props = props;
    }
}
