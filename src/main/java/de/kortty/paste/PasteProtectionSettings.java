package de.kortty.paste;

import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;

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
     * The paste protection chosen in Settings → Terminal → Paste protection.
     *
     * @param settings the global settings; null yields {@link #DEFAULTS}
     */
    public static PasteProtectionSettings from(GlobalSettings settings) {
        if (settings == null) {
            return DEFAULTS;
        }
        return new PasteProtectionSettings(settings.getPasteWarningMode(), settings.getPasteLargeWarningKiB());
    }

    /**
     * The paste protection for a paste into a terminal of {@code connection}: the connection's own warning
     * mode when it sets one that applies ({@link #connectionWarningMode}), else the mode of Settings →
     * Terminal → Paste protection; the size check always comes from Settings. An enterprise policy floor
     * is not part of this yet.
     *
     * @param global the global settings; null means {@link #DEFAULTS}
     * @param connection the connection the pane runs; null follows the global settings
     */
    public static PasteProtectionSettings resolve(GlobalSettings global, ServerConnection connection) {
        PasteProtectionSettings base = from(global);
        PasteWarningMode own = connectionWarningMode(base.mode(), connection);
        return own != null && own != base.mode() ? new PasteProtectionSettings(own, base.largeWarningKiB()) : base;
    }

    /**
     * The warning mode {@code connection} sets for itself, when it is the one that applies instead of
     * {@code globalMode}; null when the global mode applies.
     *
     * <ul>
     *   <li>A connection of your own applies its mode in both directions: {@link PasteWarningMode#ALWAYS}
     *       for a production server, {@link PasteWarningMode#OFF} for a lab machine.</li>
     *   <li>A connection from a shared teamwork file applies its mode only when it is stricter than
     *       {@code globalMode}: whoever maintains the shared file can make the warning ask more often, but
     *       can never switch off a warning you chose.</li>
     *   <li>No mode of its own, or a stored value this version does not know, follows {@code globalMode}.</li>
     * </ul>
     *
     * @param globalMode the mode of Settings → Terminal; null means {@link PasteWarningMode#DEFAULT}
     * @param connection the connection the pane runs; may be null
     */
    public static PasteWarningMode connectionWarningMode(PasteWarningMode globalMode, ServerConnection connection) {
        PasteWarningMode own = connection != null ? connection.getPasteWarningMode() : null;
        if (own == null) {
            return null;
        }
        if (connection.isTeamworkConnection()) {
            PasteWarningMode global = globalMode != null ? globalMode : PasteWarningMode.DEFAULT;
            return own.ordinal() > global.ordinal() ? own : null;
        }
        return own;
    }

    /**
     * The pause after each line of a paste into a terminal of {@code connection}, in milliseconds: the
     * connection's own pause when it sets one (0 pastes at once even when Settings sets a pause), else the
     * pause of Settings → Terminal → Paste protection. A teamwork connection's pause applies as well; it
     * only slows a paste down, and Esc stops it.
     *
     * @param global the global settings; null means no pause
     * @param connection the connection the pane runs; null follows the global settings
     * @return the pause, {@code 0..}{@link PastePacer#MAX_LINE_DELAY_MS}
     */
    public static int resolveLineDelayMs(GlobalSettings global, ServerConnection connection) {
        Integer own = connection != null ? connection.getPasteLineDelayMs() : null;
        if (own != null) {
            return PastePacer.clampLineDelayMs(own);
        }
        return global != null ? global.getPasteLineDelayMs() : 0;
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
