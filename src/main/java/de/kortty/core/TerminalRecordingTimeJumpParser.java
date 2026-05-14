package de.kortty.core;

import java.util.OptionalDouble;

public final class TerminalRecordingTimeJumpParser {

    private static final double SECONDS_PER_MINUTE = 60.0;

    private TerminalRecordingTimeJumpParser() {
    }

    public static OptionalDouble parseSeconds(String text, double maxSeconds) {
        if (text == null || text.isBlank() || !Double.isFinite(maxSeconds) || maxSeconds < 0.0) {
            return OptionalDouble.empty();
        }

        OptionalDouble parsedSeconds = text.contains(":")
            ? parseMinutesAndSeconds(text)
            : parseMinutes(text);
        if (parsedSeconds.isEmpty()) {
            return OptionalDouble.empty();
        }

        double seconds = parsedSeconds.getAsDouble();
        if (seconds < 0.0 || seconds > maxSeconds + 0.0001) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(seconds);
    }

    private static OptionalDouble parseMinutes(String text) {
        try {
            double minutes = Double.parseDouble(text.trim().replace(',', '.'));
            return Double.isFinite(minutes)
                ? OptionalDouble.of(minutes * SECONDS_PER_MINUTE)
                : OptionalDouble.empty();
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    private static OptionalDouble parseMinutesAndSeconds(String text) {
        String[] parts = text.trim().split(":");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return OptionalDouble.empty();
        }
        try {
            long minutes = Long.parseLong(parts[0].trim());
            long seconds = Long.parseLong(parts[1].trim());
            if (minutes < 0L || seconds < 0L || seconds >= 60L) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of((minutes * SECONDS_PER_MINUTE) + seconds);
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }
}
