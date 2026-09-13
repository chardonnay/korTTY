package de.kortty.control;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code pane.read} mode selector.
 *
 * <p>Pure, any thread.
 */
public enum ReadMode {

    /** The live screen, including the alternate buffer. */
    VISIBLE,

    /** The history buffer plus the screen, read under one buffer lock. */
    RECENT,

    /** The coding-agent monitor's already-published snapshot; the detector is never re-run. */
    DETECTION;

    private final String wire = name().toLowerCase(Locale.ROOT);

    /** The stable wire spelling, always {@code name().toLowerCase(Locale.ROOT)}. */
    public String wire() {
        return wire;
    }

    /**
     * Parses a wire spelling, case-insensitively.
     *
     * @throws ControlApiException {@link ControlErrorCode#INVALID_PARAMS} for anything else
     */
    public static ReadMode parse(String text) throws ControlApiException {
        if (text != null) {
            String normalised = text.strip().toLowerCase(Locale.ROOT);
            for (ReadMode mode : values()) {
                if (mode.wire.equals(normalised)) {
                    return mode;
                }
            }
        }
        throw new ControlApiException(ControlErrorCode.INVALID_PARAMS,
            "Unknown read mode: " + text,
            Map.of("param", "mode", "known", List.of(VISIBLE.wire, RECENT.wire, DETECTION.wire)));
    }
}
