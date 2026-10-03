package de.kortty.paste;

import de.kortty.model.GlobalSettings;

/**
 * The paste protection that applies to one paste: when line breaks and control characters ask
 * ({@link PasteWarningMode}) and from which size a paste asks for being large.
 *
 * @param mode when line breaks and control characters ask; null means {@link PasteWarningMode#DEFAULT}
 * @param largeWarningKiB pastes larger than this many KiB (of UTF-8) ask; 0 turns the size check
 *     off. Clamped to {@code 0..}{@link #MAX_LARGE_WARNING_KIB}.
 */
public record PasteProtectionSettings(PasteWarningMode mode, int largeWarningKiB) {

    /** The size check a fresh installation uses, in KiB. */
    public static final int DEFAULT_LARGE_WARNING_KIB = 5;

    /** The largest size threshold a user can choose, in KiB (10 MiB). */
    public static final int MAX_LARGE_WARNING_KIB = 10_240;

    /** What a fresh installation uses: line breaks ask unless bracketed, pastes over 5 KiB ask. */
    public static final PasteProtectionSettings DEFAULTS =
        new PasteProtectionSettings(PasteWarningMode.DEFAULT, DEFAULT_LARGE_WARNING_KIB);

    /** Nothing ever asks. */
    public static final PasteProtectionSettings DISABLED = new PasteProtectionSettings(PasteWarningMode.OFF, 0);

    public PasteProtectionSettings {
        mode = mode != null ? mode : PasteWarningMode.DEFAULT;
        largeWarningKiB = clampLargeWarningKiB(largeWarningKiB);
    }

    /** {@code kib} limited to {@code 0..}{@link #MAX_LARGE_WARNING_KIB}; a negative value means off. */
    public static int clampLargeWarningKiB(int kib) {
        return Math.max(0, Math.min(MAX_LARGE_WARNING_KIB, kib));
    }

    /**
     * The paste protection the global settings ask for. Until the Terminal settings offer paste
     * protection, every settings object, and null, yields {@link #DEFAULTS}.
     */
    public static PasteProtectionSettings from(GlobalSettings settings) {
        return DEFAULTS;
    }

    /** Whether the size check is on. */
    public boolean largeWarningEnabled() {
        return largeWarningKiB > 0;
    }

    /** The size in UTF-8 bytes a paste must exceed to ask for being large; 0 when the check is off. */
    public long largeWarningBytes() {
        return largeWarningKiB * 1024L;
    }

    /** Whether any paste could ask at all; false means every paste goes straight to the pane. */
    public boolean asksForAnything() {
        return mode != PasteWarningMode.OFF || largeWarningEnabled();
    }
}
