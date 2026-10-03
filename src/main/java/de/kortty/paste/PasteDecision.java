package de.kortty.paste;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The paste protection rules: which {@link PasteReason}s a paste raises under a
 * {@link PasteProtectionSettings}.
 *
 * <ul>
 *   <li>{@link PasteReason#MULTI_LINE}: the text holds a line break (a single trailing one
 *       included) and the mode is {@link PasteWarningMode#ALWAYS}, or it is
 *       {@link PasteWarningMode#UNLESS_BRACKETED} and the program in the pane has not enabled
 *       bracketed paste.</li>
 *   <li>{@link PasteReason#CONTROL_CHARACTERS}: the text holds control or bidi characters and the
 *       mode is not {@link PasteWarningMode#OFF}, bracketed or not. The remote tty acts on Ctrl+C,
 *       Ctrl+Z or Ctrl+S before the program sees the bracketing, and bidi characters make the
 *       preview show something other than what is sent.</li>
 *   <li>{@link PasteReason#LARGE}: the size check is on and the text is larger than the threshold in
 *       UTF-8, whatever the mode and the bracketing.</li>
 * </ul>
 *
 * <p>Pure, any thread.
 */
public final class PasteDecision implements PasteRules {

    private final PasteProtectionSettings settings;

    /** @param settings the protection to apply; null means {@link PasteProtectionSettings#DEFAULTS} */
    public PasteDecision(PasteProtectionSettings settings) {
        this.settings = settings != null ? settings : PasteProtectionSettings.DEFAULTS;
    }

    /** The protection these rules apply. */
    public PasteProtectionSettings settings() {
        return settings;
    }

    @Override
    public Set<PasteReason> reasons(String text, boolean bracketed) {
        if (text == null || text.isEmpty() || !settings.asksForAnything()) {
            return Set.of();
        }
        return reasonsFor(PasteInspection.of(text), bracketed);
    }

    /**
     * The reasons for a paste that has already been measured.
     *
     * @param inspection the measured paste
     * @param bracketed whether the program in the target pane has enabled bracketed paste
     * @return the reasons in the enum's order; empty when the paste needs no confirmation
     */
    public Set<PasteReason> reasonsFor(PasteInspection inspection, boolean bracketed) {
        Objects.requireNonNull(inspection, "inspection");
        PasteWarningMode mode = settings.mode();
        EnumSet<PasteReason> reasons = EnumSet.noneOf(PasteReason.class);
        if (inspection.containsLineBreak() && asksAboutLineBreaks(mode, bracketed)) {
            reasons.add(PasteReason.MULTI_LINE);
        }
        if (inspection.containsControlCharacters() && mode != PasteWarningMode.OFF) {
            reasons.add(PasteReason.CONTROL_CHARACTERS);
        }
        if (settings.largeWarningEnabled() && inspection.utf8Bytes() > settings.largeWarningBytes()) {
            reasons.add(PasteReason.LARGE);
        }
        return reasons.isEmpty() ? Set.of() : Collections.unmodifiableSet(reasons);
    }

    private static boolean asksAboutLineBreaks(PasteWarningMode mode, boolean bracketed) {
        return switch (mode) {
            case ALWAYS -> true;
            case UNLESS_BRACKETED -> !bracketed;
            case OFF -> false;
        };
    }
}
