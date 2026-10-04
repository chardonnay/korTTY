package de.kortty.telemetry;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The props of {@value TelemetryEvents#JOB_NOTIFICATION_SENT}: one event per JobScheduler run
 * notification and channel (a desktop notification, or one webhook target).
 *
 * <p>Anti-PII contract: only fixed ids and the delivery attempt count; never a job or target name,
 * a URL, a host, a status text or anything of the run's output. FX-free, so the prop types are
 * unit-tested.
 */
public final class JobNotificationTelemetry {

    /** Where the notification went. */
    public enum Channel {
        DESKTOP, WEBHOOK;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The payload format; {@link #NONE} for a desktop notification. */
    public enum Format {
        SLACK, TEAMS, GENERIC, NONE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** How the notification ended. */
    public enum Outcome {
        /** Shown, or delivered to the receiver. */
        OK,
        /** The receiver did not accept it after the retries, or the send queue dropped it. */
        FAILED,
        /** The policy or a locked master password stopped it before anything was sent. */
        BLOCKED;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The most delivery attempts the props report; a webhook send never makes more. */
    static final int MAX_ATTEMPTS = 3;

    private JobNotificationTelemetry() {
    }

    /** {@code channel}, {@code format}, {@code outcome} and {@code attempts} (0 to 3). */
    public static Map<String, Object> props(Channel channel, Format format, Outcome outcome, int attempts) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("channel", (channel != null ? channel : Channel.DESKTOP).id());
        props.put("format", (format != null ? format : Format.NONE).id());
        props.put("outcome", (outcome != null ? outcome : Outcome.OK).id());
        props.put("attempts", Math.max(0, Math.min(MAX_ATTEMPTS, attempts)));
        return props;
    }

    /** Records one notification; a no-op while the user has not allowed anonymous statistics. */
    public static void track(Channel channel, Format format, Outcome outcome, int attempts) {
        Telemetry.track(TelemetryEvents.JOB_NOTIFICATION_SENT, props(channel, format, outcome, attempts));
    }
}
