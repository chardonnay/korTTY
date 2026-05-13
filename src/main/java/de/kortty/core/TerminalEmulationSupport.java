package de.kortty.core;

import com.sithtermfx.core.emulator.EmulationType;
import de.kortty.model.ServerConnection;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Central mapping between persisted connection values and SithTermFX emulation types.
 */
public final class TerminalEmulationSupport {

    public static final EmulationType DEFAULT_EMULATION = EmulationType.XTERM;

    private static final List<EmulationType> AVAILABLE_EMULATIONS =
            List.copyOf(Arrays.asList(EmulationType.values()));

    private TerminalEmulationSupport() {
    }

    public static List<EmulationType> availableEmulations() {
        return AVAILABLE_EMULATIONS;
    }

    public static EmulationType defaultEmulation() {
        return DEFAULT_EMULATION;
    }

    public static String defaultStoredValue() {
        return DEFAULT_EMULATION.name();
    }

    public static EmulationType fromStoredValue(String storedValue) {
        if (storedValue == null || storedValue.isBlank()) {
            return DEFAULT_EMULATION;
        }
        return findExact(storedValue).orElse(DEFAULT_EMULATION);
    }

    public static EmulationType fromConnection(ServerConnection connection) {
        return connection != null
                ? fromStoredValue(connection.getTerminalEmulationType())
                : DEFAULT_EMULATION;
    }

    public static String storedValue(EmulationType emulationType) {
        return emulationType != null ? emulationType.name() : defaultStoredValue();
    }

    public static String termName(ServerConnection connection) {
        return fromConnection(connection).getTermName();
    }

    public static String displayName(EmulationType emulationType) {
        EmulationType resolved = emulationType != null ? emulationType : DEFAULT_EMULATION;
        return resolved.getDisplayName() + " (" + resolved.getTermName() + ")";
    }

    public static Optional<EmulationType> findExact(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(input);
        return AVAILABLE_EMULATIONS.stream()
                .filter(type -> normalize(type.name()).equals(normalized)
                        || normalize(type.getTermName()).equals(normalized)
                        || normalize(type.getDisplayName()).equals(normalized)
                        || normalize(displayName(type)).equals(normalized))
                .findFirst();
    }

    public static boolean matchesSearch(EmulationType emulationType, String query) {
        if (emulationType == null) {
            return false;
        }
        if (query == null || query.isBlank()) {
            return true;
        }
        String normalized = normalize(query);
        return normalize(emulationType.name()).contains(normalized)
                || normalize(emulationType.getTermName()).contains(normalized)
                || normalize(emulationType.getDisplayName()).contains(normalized)
                || normalize(displayName(emulationType)).contains(normalized);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
