package de.kortty.telemetry;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The props of {@value TelemetryEvents#SFTP_REMOTE_EDIT}: one event when the SFTP manager stops
 * watching a file edited in an external editor (or as root, later).
 *
 * <p>Anti-PII contract (D20): only fixed ids and a bucketed count; never a path, a file, host or
 * editor name, or a size. FX-free, so the prop types are unit-tested.
 */
public final class RemoteEditTelemetry {

    /** The lower bounds of the upload-count buckets. */
    static final int[] UPLOAD_BUCKETS = {0, 1, 2, 5, 10, 50};

    /** How the file was edited. */
    public enum Mode {
        EXTERNAL, SUDO;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** How the edit session ended. */
    public enum Outcome {
        /** The user pressed Stop. */
        STOPPED,
        /** The user stopped it at a conflict with a change on the server. */
        CONFLICT,
        /** The connection was lost. */
        DISCONNECTED,
        /** The SFTP tab was closed. */
        CLOSED,
        /** The file could not be opened or the editor not started. */
        FAILED;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private RemoteEditTelemetry() {
    }

    /** {@code mode}, {@code outcome} and {@code uploads} (a bucket). */
    public static Map<String, Object> props(Mode mode, Outcome outcome, int uploads) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("mode", (mode != null ? mode : Mode.EXTERNAL).id());
        props.put("outcome", (outcome != null ? outcome : Outcome.STOPPED).id());
        props.put("uploads", uploadBucket(uploads));
        return props;
    }

    /** An upload count rounded down to the lower bound of its bucket (0, 1, 2, 5, 10, 50). */
    public static int uploadBucket(int count) {
        int bucket = 0;
        for (int bound : UPLOAD_BUCKETS) {
            if (count >= bound) {
                bucket = bound;
            }
        }
        return bucket;
    }
}
