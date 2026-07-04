package de.kortty.telemetry;

import de.kortty.model.AiProfile;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Shared prop builders for instrumentation call sites. Anti-PII contract:
 * never reads profile names, API URLs, keys, or executable paths.
 */
public final class TelemetryProps {

    private static final int MAX_MODEL_LENGTH = 64;

    /** {@code mode}, {@code cli_provider}, {@code model} for an AI profile. */
    public static Map<String, Object> aiProfileProps(AiProfile profile) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (profile == null) {
            return props;
        }
        props.put("mode", profile.getConnectionMode().name().toLowerCase(Locale.ROOT));
        String cliProvider = profile.getCliProviderId();
        props.put("cli_provider", cliProvider != null && !cliProvider.isBlank() ? cliProvider.trim() : "none");
        String model = profile.getModel();
        if (model == null || model.isBlank()) {
            props.put("model", "auto");
        } else {
            String trimmed = model.trim();
            props.put("model", trimmed.length() > MAX_MODEL_LENGTH ? trimmed.substring(0, MAX_MODEL_LENGTH) : trimmed);
        }
        return props;
    }

    private TelemetryProps() {
    }
}
