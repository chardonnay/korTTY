package de.kortty.policy;

/**
 * Admin-imposed log file format: classic pattern lines or structured JSON lines (one JSON object
 * per event, logback's built-in JSON encoder — convenient for SIEM/central log ingestion).
 */
public enum LogFormat {
    TEXT,
    JSON;

    /** Parses the TOML value ("text" | "json"); null for unknown input. */
    public static LogFormat fromToml(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "text" -> TEXT;
            case "json" -> JSON;
            default -> null;
        };
    }
}
